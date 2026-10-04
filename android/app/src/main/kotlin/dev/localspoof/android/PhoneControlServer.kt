package dev.localspoof.android

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import kotlinx.serialization.json.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Control uses an ADB-forwarded abstract socket, with no TCP/LAN listener. Map/search HTTPS is separate. */
class PhoneControlServer(private val observeAirplane: () -> Boolean, private val stopService: () -> Unit,
    private val onUnavailable: (Long) -> Unit = { RuntimeModel.desktopUnavailable(it) }) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var controllerGeneration = RuntimeModel.controllerGeneration
    private val socketLock = Any()
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var client: LocalSocket? = null
    private val acknowledgments = LinkedHashMap<String, Pair<String, JsonObject>>()
    private var transfer: PackTransfer? = null

    private data class PackTransfer(
        val id: String, val sizeBytes: Int, val sha256: String, val chunks: Int, val beganAt: Long,
        var nextIndex: Int = 0, val bytes: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
    )

    internal fun isAvailable(): Boolean = running.get() && server != null && !worker.isShutdown

    /** Bind before publishing enabled state. No listener/approval history survives a failed bind. */
    internal fun bind() = synchronized(socketLock) {
        check(!closed.get()) { "LOCAL_USB_SOCKET_UNAVAILABLE" }
        if (server != null) return@synchronized
        try {
            server = LocalServerSocket(SOCKET_NAME)
            running.set(true)
        } catch (failure: Exception) {
            running.set(false); closed.set(true); worker.shutdownNow()
            throw failure
        }
    }

    fun start() {
        bind()
        if (!started.compareAndSet(false, true)) return
        // DesktopControlHost has now published the newly enabled owner generation.
        controllerGeneration = RuntimeModel.controllerGeneration
        worker.execute {
            var failed = false
            try {
                while (running.get()) {
                    val accepted = server!!.accept()
                    if (!running.get() || closed.get()) {
                        runCatching { Os.shutdown(accepted.fileDescriptor, OsConstants.SHUT_RDWR) }
                        runCatching { accepted.close() }
                        break
                    }
                    val channelId = secureToken()
                    client = accepted
                    try { handle(accepted, channelId) } catch (_: Exception) { /* No payload-bearing diagnostics. */ }
                    finally {
                        runCatching { accepted.close() }; client = null
                        acknowledgments.clear(); transfer = null
                        RuntimeModel.disconnect(controllerGeneration, channelId)
                    }
                }
            } catch (_: Exception) {
                failed = running.get() && !closed.get()
            } finally {
                running.set(false)
                synchronized(socketLock) { runCatching { server?.close() }; server = null }
                worker.shutdown()
                if (failed) onUnavailable(controllerGeneration)
            }
        }
    }

    private fun handle(socket: LocalSocket, channelId: String) {
        val input = DataInputStream(socket.inputStream)
        val output = DataOutputStream(socket.outputStream)
        val acceptedAt = SystemClock.elapsedRealtime()
        val connectionDeadline = ControlConnectionDeadline(acceptedAt, channelId)
        val poll = StructPollfd().apply {
            fd = socket.fileDescriptor
            events = (OsConstants.POLLIN or OsConstants.POLLHUP or OsConstants.POLLERR).toShort()
        }
        val frames = ControlFrameReader(MAX_FRAME, SystemClock::elapsedRealtime, {
            synchronized(RuntimeModel.lock) {
                val now = SystemClock.elapsedRealtime()
                connectionDeadline.remainingMillis(now, RuntimeModel.lease, running.get())
            }
        }, { timeout -> Os.poll(arrayOf(poll), timeout) > 0 }, { timeout -> socket.soTimeout = timeout })
        while (running.get()) {
            val bytes = frames.readFrame(input)
            val response = try {
                val envelope = parseBoundedJson(bytes).jsonObject
                dispatch(envelope, channelId, acceptedAt + 120_000)
            } catch (_: Exception) { publicResponse("INVALID_COMMAND") }
            val responseBytes = response.toString().toByteArray(Charsets.UTF_8)
            if (responseBytes.size > MAX_FRAME) {
                val error = publicResponse("RESPONSE_TOO_LARGE").toString().toByteArray()
                output.writeInt(error.size); output.write(error)
            } else { output.writeInt(responseBytes.size); output.write(responseBytes) }
            output.flush()
        }
    }

    internal fun dispatch(envelope: JsonObject, channelId: String, channelExpiresAt: Long): JsonObject {
        require(envelope.str("commandId").length in 1..128) { "INVALID_COMMAND_ID" }
        require(envelope.str("type").length in 1..64) { "INVALID_COMMAND_TYPE" }
        require(envelope.long("protocolMinor") in 0..65535) { "UNSUPPORTED_PROTOCOL" }
        require(envelope.long("sequence") >= 0 && envelope.long("expectedRevision") >= 0) { "INVALID_REVISION_OR_SEQUENCE" }
        for (key in listOf("sessionId", "token")) envelope[key]?.takeUnless { it is JsonNull }?.let {
            require(envelope.str(key).length <= 128) { "INVALID_CREDENTIAL_SIZE" }
        }
        val kind = envelope["type"]?.jsonPrimitive?.content
        if (kind !in setOf("Hello", "Capabilities", "PairRequest", "PollPair") &&
            listOf("token", "sessionId").any { envelope[it] == null || envelope[it] is JsonNull }) return publicResponse("UNAUTHORIZED")
        if (kind == "ImportGeoPack" || kind == "CommitGeoPack") return dispatchResource(envelope, channelId)
        if (envelope["type"]?.jsonPrimitive?.content != "PreparePlan") return dispatchLocked(envelope, channelId, channelExpiresAt)
        // Core planning releases its own mutation lock. Local Stop can cancel it immediately.
        var cached: JsonObject? = null
        val accepted = synchronized(RuntimeModel.lock) {
            val lease = RuntimeModel.lease
            val now = SystemClock.elapsedRealtime()
            lease.expire(now)
            when {
                envelope.long("protocolMajor") != 1L -> { cached = publicResponse("UNSUPPORTED_PROTOCOL"); false }
                !RuntimeModel.view.value.desktopEnabled || envelope.str("sessionId") != lease.sessionId || !lease.matches(envelope.str("token"), channelId) -> { cached = publicResponse("UNAUTHORIZED"); false }
                else -> {
                    val fingerprint = fingerprint(envelope)
                    val prior = acknowledgments[envelope.str("commandId")]
                    if (prior != null) {
                        cached = if (prior.first == fingerprint) prior.second else RuntimeModel.response("COMMAND_ID_REUSED")
                        false
                    } else if (envelope.long("expectedRevision") != RuntimeModel.coordinator.snapshot().revision) {
                        cached = RuntimeModel.response("STALE_REVISION"); false
                    } else if (!lease.authorize(envelope.str("token"), channelId, envelope.long("sequence"), now)) {
                        cached = RuntimeModel.response("STALE_SEQUENCE"); false
                    } else true
                }
            }
        }
        if (!accepted) return cached ?: publicResponse("UNAUTHORIZED")
        val response = boundedResponse(try {
            val payload = envelope.obj("payload")
            val mappedEdges = payload["onlineRoute"]?.takeUnless { it is JsonNull }?.jsonObject?.containsKey("edgeSpeedLimits") == true ||
                payload["onlineFlightRoutes"]?.takeUnless { it is JsonNull }?.jsonObject?.let { legs ->
                    listOf("originGround", "destinationGround").any { key -> legs[key]?.takeUnless { it is JsonNull }?.jsonObject?.containsKey("edgeSpeedLimits") == true }
                } == true
            require(envelope.long("protocolMinor") >= 5 || payload.keys.none { it in setOf("drivingSpeedMode", "driveMinMps", "driveMaxMps", "followRoadLimits") } && !mappedEdges) { "UNSUPPORTED_PROTOCOL" }
            val prepared = RuntimeModel.prepare(parseRequest(payload), envelope.long("expectedRevision"), payload["radioPolicy"]?.jsonPrimitive?.content ?: "MANUAL")
            RuntimeModel.response(payload = buildJsonObject { put("preparedId", prepared.id); put("preview", prepared.preview.json()) })
        } catch (_: Exception) { RuntimeModel.response("PLAN_PREPARATION_REJECTED") })
        synchronized(RuntimeModel.lock) {
            if (!RuntimeModel.lease.matches(envelope.str("token"), channelId)) return publicResponse("UNAUTHORIZED")
            if (RuntimeModel.lease.matches(envelope.str("token"), channelId)) {
                cache(envelope.str("commandId"), fingerprint(envelope), response)
            }
        }
        return response
    }

    private fun dispatchResource(envelope: JsonObject, channelId: String): JsonObject {
        var early: JsonObject? = null
        var bytes: ByteArray? = null
        synchronized(RuntimeModel.lock) {
            val lease = RuntimeModel.lease
            val now = SystemClock.elapsedRealtime()
            lease.expire(now)
            val prior = acknowledgments[envelope.str("commandId")]
            when {
                envelope.long("protocolMajor") != 1L -> early = publicResponse("UNSUPPORTED_PROTOCOL")
                !RuntimeModel.view.value.desktopEnabled || envelope.str("sessionId") != lease.sessionId || !lease.matches(envelope.str("token"), channelId) -> early = publicResponse("UNAUTHORIZED")
                prior != null -> early = if (prior.first == fingerprint(envelope)) prior.second else RuntimeModel.response("COMMAND_ID_REUSED")
                envelope.long("expectedRevision") != RuntimeModel.coordinator.snapshot().revision -> early = RuntimeModel.response("STALE_REVISION")
                !lease.authorize(envelope.str("token"), channelId, envelope.long("sequence"), now) -> early = RuntimeModel.response("STALE_SEQUENCE")
                else -> {
                    val payload = envelope.obj("payload")
                    try {
                        bytes = if (envelope.str("type") == "ImportGeoPack") (payload["pack"] ?: payload).toString().toByteArray() else {
                            val staging = transfer ?: throw IllegalArgumentException("NO_TRANSFER")
                            require(now - staging.beganAt <= 30_000 && payload.str("transferId") == staging.id)
                            require(staging.nextIndex == staging.chunks && staging.bytes.size() == staging.sizeBytes)
                            val content = staging.bytes.toByteArray()
                            val hash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
                            require(hash == staging.sha256)
                            content
                        }
                    } catch (_: Exception) { transfer = null; early = RuntimeModel.response("RESOURCE_TRANSFER_REJECTED") }
                }
            }
        }
        early?.let { return it }
        val parsed = try { parseGeoPack(bytes!!) }
        catch (_: Exception) { synchronized(RuntimeModel.lock) { transfer = null }; return authorizedResponse(envelope, "RESOURCE_REJECTED", channelId) }
        val airports = synchronized(RuntimeModel.lock) { RuntimeModel.airports }
        val preparedResources = try { RuntimeModel.coordinator.prepareResources(parsed, airports) }
        catch (_: Exception) { return authorizedResponse(envelope, "RESOURCE_REJECTED", channelId) }
        return synchronized(RuntimeModel.lock) {
            if (!RuntimeModel.lease.matches(envelope.str("token"), channelId)) return@synchronized publicResponse("UNAUTHORIZED")
            if (envelope.long("expectedRevision") != RuntimeModel.coordinator.snapshot().revision)
                return@synchronized RuntimeModel.response("STALE_REVISION")
            RuntimeModel.configurePreparedResources(preparedResources, parsed, airports)
            transfer = null
            val response = RuntimeModel.response()
            cache(envelope.str("commandId"), fingerprint(envelope), response)
            response
        }
    }

    private fun authorizedResponse(envelope: JsonObject, error: String, channelId: String): JsonObject = synchronized(RuntimeModel.lock) {
        if (!RuntimeModel.lease.matches(envelope.str("token"), channelId)) publicResponse("UNAUTHORIZED") else RuntimeModel.response(error)
    }

    private fun fingerprint(envelope: JsonObject): String = MessageDigest.getInstance("SHA-256")
        .digest(envelope.toString().toByteArray()).joinToString("") { "%02x".format(it) }

    private fun boundedResponse(response: JsonObject): JsonObject =
        if (response.toString().toByteArray().size <= MAX_FRAME) response else publicResponse("RESPONSE_TOO_LARGE")

    private fun cache(commandId: String, fingerprint: String, response: JsonObject) {
        val bounded = boundedResponse(response)
        acknowledgments[commandId] = fingerprint to bounded
        // Count and aggregate serialized bytes are both bounded. No unlimited route cache.
        while (acknowledgments.size > 32 || acknowledgments.values.sumOf { it.second.toString().toByteArray().size.toLong() } > 2 * MAX_FRAME) {
            acknowledgments.remove(acknowledgments.keys.first())
        }
    }

    private fun dispatchLocked(envelope: JsonObject, channelId: String, channelExpiresAt: Long): JsonObject = synchronized(RuntimeModel.lock) {
        if (envelope.long("protocolMajor") != 1L) return@synchronized publicResponse("UNSUPPORTED_PROTOCOL")
        if (envelope.long("protocolMinor") < 0) return@synchronized publicResponse("UNSUPPORTED_PROTOCOL")
        val type = envelope.str("type")
        val payload = envelope["payload"]?.jsonObject ?: buildJsonObject {}
        val now = SystemClock.elapsedRealtime()
        val lease = RuntimeModel.lease
        lease.expire(now)
        when (type) {
            "Hello", "Capabilities" -> return@synchronized publicResponse(payload = buildJsonObject {
                put("protocolMajor", 1); put("protocolMinor", 5)
                put("frameworkMock", true); put("fusedMock", false)
                put("guidedAirplane", true); put("usbAirplane", false)
                put("maxFrameBytes", MAX_FRAME); put("maxPackBytes", MAX_PACK)
                put("motionTelemetry", true); put("variableCruise", true); put("mappedRoadLimits", true); put("onlineRouteGeometry", true); put("onlineFlightGroundRoutes", true)
                put("maxOnlineRoutePoints", dev.localspoof.domain.MAX_ONLINE_ROUTE_POINTS)
                put("pairingRequired", true)
                put("approvalPairing", true)
                put("livePhoneLocation", true); put("flightLegOptions", true)
                put("liveJourneyControls", true); put("routeStops", true)
            })
            "PairRequest" -> {
                if (envelope.long("protocolMinor") < 2) return@synchronized publicResponse("UNSUPPORTED_PROTOCOL")
                require(payload.keys == setOf("clientNonce") && payload.str("clientNonce").matches(Regex("[a-f0-9]{32}")))
                val accepted = lease.request(channelId, payload.str("clientNonce"), now, channelExpiresAt)
                RuntimeModel.publish()
                return@synchronized publicResponse(if (accepted) null else "PAIRING_REJECTED", buildJsonObject {
                    put("pairingId", if (accepted) lease.pairingId?.let(::JsonPrimitive) ?: JsonNull else JsonNull)
                    put("comparisonCode", if (accepted) lease.comparisonCode?.let(::JsonPrimitive) ?: JsonNull else JsonNull)
                    put("pending", accepted)
                })
            }
            "PollPair" -> {
                if (envelope.long("protocolMinor") < 2) return@synchronized publicResponse("UNSUPPORTED_PROTOCOL")
                require(payload.keys == setOf("clientNonce", "pairingId") && payload.str("clientNonce").matches(Regex("[a-f0-9]{32}")) && payload.str("pairingId").matches(Regex("[a-f0-9]{32}")))
                val credential = lease.poll(channelId, payload.str("pairingId"), payload.str("clientNonce"), now)
                val pending = lease.pendingRequest(channelId, payload.str("pairingId"), payload.str("clientNonce"))
                return@synchronized publicResponse(error = if (credential != null) null else if (pending) "PAIRING_PENDING" else "PAIRING_REJECTED", payload = buildJsonObject {
                    put("pending", pending); put("paired", credential != null)
                    put("token", credential?.let(::JsonPrimitive) ?: JsonNull)
                }, approvedSessionId = if (credential != null) lease.sessionId else null)
            }
        }
        if (!RuntimeModel.view.value.desktopEnabled) return@synchronized publicResponse("CONTROLLER_DISABLED")
        val token = envelope.str("token")
        if (envelope.str("sessionId") != lease.sessionId || !lease.matches(token, channelId))
            return@synchronized publicResponse("UNAUTHORIZED")
        val commandId = envelope.str("commandId")
        require(commandId.length in 1..128) { "INVALID_COMMAND_ID" }
        val fingerprint = fingerprint(envelope)
        acknowledgments[commandId]?.let { (original, response) ->
            if (original != fingerprint) return@synchronized RuntimeModel.response("COMMAND_ID_REUSED")
            // Reads retain only their fingerprint. Retry the latest view without renewing the lease.
            return@synchronized when (type) {
                "Snapshot" -> RuntimeModel.response(includePhoneLocation = true)
                "Heartbeat" -> RuntimeModel.response()
                "SetSpeeds", "JumpToNextStop" -> RuntimeModel.response(response["error"]?.jsonPrimitive?.content)
                else -> response
            }
        }
        if (!lease.authorize(token, channelId, envelope.long("sequence"), now)) return@synchronized RuntimeModel.response("STALE_SEQUENCE")
        val mutations = setOf("PreparePlan", "CommitPlan", "Start", "Pause", "Resume", "Stop", "RadioPhaseResult", "ImportGeoPack", "BeginGeoPack", "GeoPackChunk", "CommitGeoPack", "SetSpeeds", "JumpToNextStop")
        if (type in mutations && type != "Stop" && envelope.long("expectedRevision") != RuntimeModel.coordinator.snapshot().revision)
            return@synchronized RuntimeModel.response("STALE_REVISION")
        var terminate = false
        val response = try {
            when (type) {
                "Snapshot" -> RuntimeModel.response(includePhoneLocation = true)
                "Heartbeat" -> RuntimeModel.response()
                "CommitPlan" -> { RuntimeModel.commit(payload.str("preparedId"), envelope.long("expectedRevision")); RuntimeModel.response() }
                "Start" -> {
                    if (RuntimeModel.view.value.snapshot.state.name == "READY" && RuntimeModel.view.value.providerHealth == "NOT_PREPARED")
                        RuntimeModel.response("PHONE_START_REQUIRED")
                    else { RuntimeModel.startFromDesktop(); RuntimeModel.response() }
                }
                "SetSpeeds" -> {
                    require(envelope.long("protocolMinor") >= 4 && payload.isNotEmpty() && payload.keys.all { it in setOf("walkMps", "driveMps", "flightMps", "drivingSpeedMode", "driveMinMps", "driveMaxMps", "followRoadLimits") } && payload.values.none { it is JsonNull })
                    require(envelope.long("protocolMinor") >= 5 || payload.keys.none { it in setOf("drivingSpeedMode", "driveMinMps", "driveMaxMps", "followRoadLimits") }) { "UNSUPPORTED_PROTOCOL" }
                    RuntimeModel.setSpeeds(payload.optionalNumber("walkMps"), payload.optionalNumber("driveMps"), payload.optionalNumber("flightMps"), envelope.long("expectedRevision"),
                        payload["drivingSpeedMode"]?.let { dev.localspoof.domain.DrivingSpeedMode.valueOf(payload.str("drivingSpeedMode")) },
                        payload.optionalNumber("driveMinMps"), payload.optionalNumber("driveMaxMps"),
                        payload["followRoadLimits"]?.let { require(!it.jsonPrimitive.isString); it.jsonPrimitive.boolean }); RuntimeModel.response()
                }
                "JumpToNextStop" -> {
                    require(envelope.long("protocolMinor") >= 4 && payload.isEmpty())
                    RuntimeModel.jumpToNextStop(envelope.long("expectedRevision")); RuntimeModel.response()
                }
                "Pause" -> { RuntimeModel.pause(); RuntimeModel.response() }
                "Resume" -> { RuntimeModel.resume(); RuntimeModel.response() }
                "Stop" -> { RuntimeModel.stop(); transfer = null; acknowledgments.clear(); terminate = true; RuntimeModel.response() }
                "RadioPhaseResult" -> {
                    // Observe the platform rather than trusting desktop output or radio claims.
                    RuntimeModel.observeRadio(observeAirplane())
                    RuntimeModel.response(if (RuntimeModel.radioResult(payload)) null else "RADIO_PHASE_NOT_CONFIRMED")
                }
                "Airports" -> {
                    val query = payload["query"]?.jsonPrimitive?.content?.lowercase() ?: ""
                    RuntimeModel.response(payload = buildJsonObject {
                        put("airports", JsonArray(RuntimeModel.airports.filter { !it.closed && it.scheduledService && it.type == "large_airport" && (it.name.lowercase().contains(query) || it.id.lowercase().contains(query)) }.take(100).map { it.json() }))
                    })
                }
                "Places" -> {
                    val query = payload["query"]?.jsonPrimitive?.content?.lowercase() ?: ""
                    RuntimeModel.response(payload = buildJsonObject {
                        put("places", JsonArray(RuntimeModel.view.value.pack?.places?.filter { it.name.lowercase().contains(query) }?.take(100)?.map {
                            buildJsonObject { put("id", it.id); put("name", it.name); put("lat", it.point.lat); put("lon", it.point.lon) }
                        } ?: emptyList()))
                    })
                }
                "BeginGeoPack" -> {
                    val size = payload.long("sizeBytes"); val chunks = payload.long("totalChunks"); val hash = payload.str("sha256")
                    require(size in 1..MAX_PACK.toLong() && chunks in 1..512 && hash.matches(Regex("[a-fA-F0-9]{64}")))
                    transfer = PackTransfer(secureToken(), size.toInt(), hash.lowercase(), chunks.toInt(), now)
                    RuntimeModel.response(payload = buildJsonObject { put("transferId", transfer!!.id) })
                }
                "GeoPackChunk" -> {
                    val staging = transfer ?: throw IllegalArgumentException("NO_TRANSFER")
                    require(now - staging.beganAt <= 30_000 && payload.str("transferId") == staging.id)
                    require(payload.long("index") == staging.nextIndex.toLong() && staging.nextIndex < staging.chunks)
                    val text = payload.str("data"); require(text.length <= 256 * 1024)
                    val bytes = text.toByteArray(); require(staging.bytes.size() + bytes.size <= staging.sizeBytes)
                    staging.bytes.write(bytes); staging.nextIndex++
                    RuntimeModel.response(payload = buildJsonObject { put("nextIndex", staging.nextIndex) })
                }
                else -> RuntimeModel.response("UNKNOWN_COMMAND")
            }
        } catch (_: Exception) {
            if (type.contains("GeoPack")) transfer = null
            RuntimeModel.response(if (type == "Start") "PHONE_START_REQUIRED" else "COMMAND_REJECTED")
        }
        if (!terminate) {
            // Never keep historical device locations or stale ages in the acknowledgment cache.
            cache(commandId, fingerprint, if (type in setOf("Snapshot", "Heartbeat", "SetSpeeds", "JumpToNextStop")) publicResponse(response["error"]?.jsonPrimitive?.content) else response)
        } else {
            // The acknowledgment is sent by handle before the service closes the socket.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ stopService() }, 100)
        }
        response
    }

    override fun close() {
        closed.set(true); running.set(false)
        // close(fd) alone can leave native accept/read holding the kernel socket reference.
        // Shutdown first wakes those calls so the abstract name can be rebound in this process.
        runCatching { client?.fileDescriptor?.let { Os.shutdown(it, OsConstants.SHUT_RDWR) } }
        runCatching { client?.close() }
        synchronized(socketLock) {
            runCatching { server?.fileDescriptor?.let { Os.shutdown(it, OsConstants.SHUT_RDWR) } }
            runCatching { server?.close() }; server = null; running.set(false)
        }
        worker.shutdownNow()
        synchronized(RuntimeModel.lock) { transfer = null; acknowledgments.clear() }
    }

    companion object {
        const val SOCKET_NAME = "localspoof.control.v1"
        const val MAX_FRAME = 1_048_576
        const val MAX_PACK = 16 * 1024 * 1024
    }
}

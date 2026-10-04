package dev.localspoof.android

import android.annotation.SuppressLint

import android.os.SystemClock
import dev.localspoof.domain.*
import dev.localspoof.platform.FrameworkLocationProvider
import dev.localspoof.platform.DeviceLocationFix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

data class PhoneView(
    val snapshot: SessionSnapshot,
    val serviceRunning: Boolean = false,
    val desktopEnabled: Boolean = false,
    val pairingComparison: String? = null,
    val pairingPending: Boolean = false,
    val pairingRequestId: String? = null,
    val controllerApproved: Boolean = false,
    val providerHealth: String = "NOT_PREPARED",
    val message: String? = null,
    val pack: GeoPack? = null,
    val radioObserved: Boolean = false,
    val preview: PlanPreview? = null,
    val phoneLocation: PhoneLocation? = null,
    val motion: MotionTelemetry = MotionTelemetry(),
    val simulationArmed: Boolean = false,
)

/** One process-owned coordinator; all active mutations use lock. No persistence or event history. */
object RuntimeModel {
    val lock = Any()
    var coordinator = Coordinator()
        private set
    val lease = ControllerLease()
    // The adapter retains applicationContext only and is released when the service detaches.
    @SuppressLint("StaticFieldLeak")
    var provider: FrameworkLocationProvider? = null
    var airports: List<Airport> = emptyList()
        private set
    private var pack: GeoPack? = null
    private var desktopEnabled = false
    private var serviceRunning = false
    private var message: String? = null
    private var preview: PlanPreview? = null
    private var radio = false
    private var phoneArmed = false
    private val phoneLocation = PhoneLocationState()
    private val preparedRadioPolicies = LinkedHashMap<String, String>()
    private var activeRadioPolicy: String? = null
    private var radioGeneration = 0L
    private var initialAirplaneAtStart: Boolean? = null
    var controllerGeneration: Long = 0L
        private set
    private val mutable = MutableStateFlow(PhoneView(coordinator.snapshot()))
    val view: StateFlow<PhoneView> = mutable

    fun publish() {
        val snapshot = coordinator.snapshot()
        val currentLocation = phoneLocation.snapshot(SystemClock.elapsedRealtimeNanos())
        mutable.value = PhoneView(snapshot = snapshot, serviceRunning = serviceRunning, desktopEnabled = desktopEnabled,
            pairingComparison = lease.comparisonCode, pairingPending = lease.pending,
            pairingRequestId = lease.pairingId.takeIf { lease.pending }, controllerApproved = lease.token() != null,
            providerHealth = provider?.health ?: "NOT_PREPARED",
            message = message, pack = pack, radioObserved = radio, preview = preview,
            phoneLocation = currentLocation, motion = motionTelemetry(snapshot, currentLocation, phoneArmed), simulationArmed = phoneArmed)
    }

    fun configureResources(newPack: GeoPack? = pack, newAirports: List<Airport> = airports) {
        val prepared = coordinator.prepareResources(newPack, newAirports)
        configurePreparedResources(prepared, newPack, newAirports)
    }

    fun configurePreparedResources(prepared: PreparedResources, newPack: GeoPack?, newAirports: List<Airport>) = synchronized(lock) {
        coordinator.installResources(prepared)
        pack = newPack; airports = newAirports; message = null
        preview = null; preparedRadioPolicies.clear(); publish()
    }

    fun prepare(request: PlanRequest, revision: Long = coordinator.snapshot().revision, radioPolicy: String = "MANUAL"): PreparedPlan {
        val prepared = coordinator.prepare(request, revision)
        synchronized(lock) {
            check(coordinator.snapshot().revision == revision) { "STALE_REVISION" }
            preview = prepared.preview; message = null; publish()
            preparedRadioPolicies[prepared.id] = if (request.flight?.simulationOnly == true) "SIMULATION" else radioPolicy
            if (preparedRadioPolicies.size > 8) preparedRadioPolicies.remove(preparedRadioPolicies.keys.first())
        }
        return prepared
    }

    fun commit(id: String, revision: Long = coordinator.snapshot().revision) = synchronized(lock) {
        coordinator.commit(id, revision)
        activeRadioPolicy = preparedRadioPolicies[id]; preparedRadioPolicies.clear(); radioGeneration++
        initialAirplaneAtStart = radio
        preview = null; message = "Plan ready. Start on this phone."; publish()
    }

    fun attach(adapter: FrameworkLocationProvider) = synchronized(lock) {
        provider = adapter; serviceRunning = true; phoneLocation.attach(adapter); publish()
    }
    fun offerDeviceLocation(fix: DeviceLocationFix, expectedOwner: FrameworkLocationProvider) = synchronized(lock) {
        if (serviceRunning && provider === expectedOwner && !phoneArmed && coordinator.snapshot().state != SessionState.ERROR &&
            phoneLocation.device(expectedOwner, fix, SystemClock.elapsedRealtimeNanos())) publish()
    }
    fun shouldReadDeviceLocation(expectedOwner: FrameworkLocationProvider): Boolean = synchronized(lock) {
        serviceRunning && provider === expectedOwner && !phoneArmed && coordinator.snapshot().state != SessionState.ERROR
    }

    fun enableDesktop() = synchronized(lock) {
        if (!desktopEnabled) { controllerGeneration++; desktopEnabled = true; lease.enable() }
        if (message == "LOCAL_USB_SOCKET_UNAVAILABLE") message = null
        publish()
    }

    /** A failed/dead socket revokes only its own desktop authority, not phone playback consent. */
    fun desktopUnavailable(expectedGeneration: Long = controllerGeneration) = synchronized(lock) {
        if (expectedGeneration != controllerGeneration) return@synchronized
        lease.revoke(); desktopEnabled = false
        message = "LOCAL_USB_SOCKET_UNAVAILABLE"
        publish()
    }

    fun approveController(pairingId: String) = synchronized(lock) {
        val approved = lease.approve(pairingId, SystemClock.elapsedRealtime()); publish(); approved
    }
    fun rejectController(pairingId: String) = synchronized(lock) {
        val rejected = lease.reject(pairingId); publish(); rejected
    }

    fun startFromPhone() = synchronized(lock) {
        try {
            val adapter = provider ?: throw IllegalStateException("START_PHONE_SERVICE_FIRST")
            if (!phoneArmed) { phoneLocation.attach(adapter); phoneLocation.preparing(adapter) }
            adapter.prepare()
            phoneArmed = true
            val state = coordinator.snapshot().state
            if (state == SessionState.READY) {
                initialAirplaneAtStart = radio
                coordinator.start(SystemClock.elapsedRealtime())
            }
            else if (state == SessionState.PAUSED) coordinator.resume(SystemClock.elapsedRealtime())
            else check(state == SessionState.ACTIVE || state == SessionState.HOLDING) { "PLAN_NOT_READY" }
            message = null
        } catch (_: Exception) {
            provider?.cleanup(); phoneArmed = false
            phoneLocation.clear()
            message = provider?.readiness() ?: "PROVIDER_START_FAILED"
        }
        publish()
    }

    fun startFromDesktop() = synchronized(lock) {
        check(phoneArmed && serviceRunning) { "PHONE_START_REQUIRED" }
        if (coordinator.snapshot().state == SessionState.READY) coordinator.start(SystemClock.elapsedRealtime())
        else check(coordinator.snapshot().state in setOf(SessionState.ACTIVE, SessionState.HOLDING)) { "NOT_READY" }
        publish()
    }

    fun setSpeeds(walkMps: Double? = null, driveMps: Double? = null, flightMps: Double? = null,
        revision: Long = coordinator.snapshot().revision, drivingSpeedMode: DrivingSpeedMode? = null,
        driveMinMps: Double? = null, driveMaxMps: Double? = null, followRoadLimits: Boolean? = null) = synchronized(lock) {
        coordinator.setSpeeds(walkMps, driveMps, flightMps, revision, drivingSpeedMode, driveMinMps, driveMaxMps, followRoadLimits); preview = null; preparedRadioPolicies.clear(); publish()
    }
    fun jumpToNextStop(revision: Long = coordinator.snapshot().revision) = synchronized(lock) {
        check(phoneArmed && serviceRunning) { "PHONE_START_REQUIRED" }
        coordinator.jumpToNextStop(revision); preview = null; preparedRadioPolicies.clear(); publish()
    }

    fun pause() = synchronized(lock) { coordinator.pause(); publish() }
    fun resume() = synchronized(lock) { coordinator.resume(SystemClock.elapsedRealtime()); publish() }

    fun tick(radioEnabled: Boolean, expectedOwner: FrameworkLocationProvider) = synchronized(lock) {
        // Cancellation cannot interrupt a synchronous settings read in an old service coroutine.
        // Check ownership before even lease/radio mutations or publishing a snapshot.
        if (!serviceRunning || provider !== expectedOwner) return@synchronized
        lease.expire(SystemClock.elapsedRealtime())
        radio = radioEnabled
        try {
            coordinator.observeRadio(radioEnabled)
            val phase = coordinator.snapshot().phase?.name
            if (phase == "AWAIT_AIRPLANE_MODE" && radioEnabled) coordinator.acknowledgeRadioChange(true)
            if (phase == "RESTORE_CONNECTIVITY" && coordinator.snapshot().desiredAirplaneMode == radioEnabled) coordinator.acknowledgeRadioChange(radioEnabled)
            coordinator.tick(SystemClock.elapsedRealtime())?.let { sample ->
                (provider ?: throw IllegalStateException("PROVIDER_NOT_PREPARED")).inject(sample)
                phoneLocation.submitted(expectedOwner, sample, SystemClock.elapsedRealtimeNanos())
                coordinator.acknowledgePlacement(true)
            }
        } catch (_: Exception) {
            coordinator.fail("PROVIDER_SUBMISSION_FAILED")
            provider?.cleanup()
            phoneArmed = false
            phoneLocation.clear(expectedOwner)
            message = provider?.readiness() ?: "PROVIDER_FAILED_USE_SETTINGS"
        }
        publish()
    }

    fun failOwner(expectedOwner: FrameworkLocationProvider, code: String) = synchronized(lock) {
        if (!serviceRunning || provider !== expectedOwner) return@synchronized
        coordinator.fail(code)
        expectedOwner.cleanup()
        phoneLocation.clear(expectedOwner)
        phoneArmed = false
        message = code
        publish()
    }

    fun stop() = synchronized(lock) {
        val stoppingSnapshot = coordinator.snapshot()
        val pendingUsbRadio = stoppingSnapshot.mode == Mode.FLIGHT && activeRadioPolicy == "USB" &&
            stoppingSnapshot.state in setOf(SessionState.ACTIVE, SessionState.PAUSED, SessionState.HOLDING, SessionState.ERROR)
        val restoreRadio = coordinator.recoveryAirplaneState() != null
        // Revoke control first so queued external commands cannot survive local Stop.
        lease.revoke(); desktopEnabled = false; phoneArmed = false; phoneLocation.clear()
        coordinator.stop(); preview = null
        preparedRadioPolicies.clear(); activeRadioPolicy = null; initialAirplaneAtStart = null
        val cleanupFailed = provider?.cleanup() == false
        message = when {
            cleanupFailed && pendingUsbRadio -> "CLEANUP_FAILED: reset mock providers. Check airplane mode manually in Android settings; an in-flight USB command may still finish."
            cleanupFailed && restoreRadio -> "CLEANUP_FAILED: reset mock providers and restore airplane mode manually in Android settings."
            cleanupFailed -> "CLEANUP_FAILED: deselect mock app and toggle location or reboot."
            pendingUsbRadio -> "Check airplane mode manually in Android settings; an in-flight USB command may still finish."
            restoreRadio -> "Restore airplane mode manually in Android settings. Stop does not change radio settings."
            message?.contains("airplane mode manually") == true -> message
            else -> null
        }
        publish()
    }

    fun detach() = synchronized(lock) {
        stop(); provider = null; serviceRunning = false; publish()
    }

    fun disconnect(generation: Long, channelId: String) = synchronized(lock) {
        if (generation == controllerGeneration) { lease.disconnect(channelId); publish() }
    }
    fun showError(code: String) = synchronized(lock) { message = code; publish() }

    fun observeRadio(enabled: Boolean) = synchronized(lock) {
        radio = enabled; coordinator.observeRadio(enabled); publish()
    }

    fun radioRequest(): JsonObject? = synchronized(lock) {
        val snapshot = coordinator.snapshot()
        val desired = snapshot.desiredAirplaneMode ?: return@synchronized null
        if (activeRadioPolicy != "USB" || lease.token() == null) return@synchronized null
        buildJsonObject {
            put("phaseId", "flight-$radioGeneration-${snapshot.phase?.name}")
            put("enabled", desired)
        }
    }

    fun radioResult(payload: JsonObject): Boolean = synchronized(lock) {
        val requested = radioRequest() ?: return@synchronized false
        if (requested.str("phaseId") != payload.str("phaseId")) return@synchronized false
        val enabled = payload["enabled"]?.jsonPrimitive?.boolean ?: return@synchronized false
        if (enabled != requested["enabled"]?.jsonPrimitive?.boolean || enabled != radio) return@synchronized false
        coordinator.acknowledgeRadioChange(enabled); publish(); true
    }

    fun response(error: String? = null, payload: JsonObject? = null, includePhoneLocation: Boolean = false): JsonObject = synchronized(lock) {
        val snapshot = coordinator.snapshot()
        buildJsonObject {
            put("ok", error == null)
            if (error != null) put("error", error)
            put("sessionId", lease.sessionId?.let(::JsonPrimitive) ?: JsonNull)
            put("revision", snapshot.revision); put("state", snapshot.state.name)
            put("point", snapshot.point?.json() ?: JsonNull)
            put("phase", snapshot.phase?.let { JsonPrimitive(it.name) } ?: JsonNull)
            put("payload", payload ?: buildJsonObject {
                put("mode", snapshot.mode?.let { JsonPrimitive(it.name) } ?: JsonNull)
                put("providerHealth", provider?.health ?: "NOT_PREPARED")
                put("phoneStartRequired", !phoneArmed)
                put("radioObserved", radio)
                put("desiredAirplaneMode", snapshot.desiredAirplaneMode?.let(::JsonPrimitive) ?: JsonNull)
                put("preparedPreview", preview?.json() ?: JsonNull)
                put("message", message?.let(::JsonPrimitive) ?: JsonNull)
                put("elapsedMs", snapshot.elapsedMs)
                put("remainingMs", snapshot.remainingMs?.let(::JsonPrimitive) ?: JsonNull)
                put("remainingMeters", snapshot.remainingMeters?.let(::JsonPrimitive) ?: JsonNull)
                put("waitingFor", snapshot.waitingFor?.let(::JsonPrimitive) ?: JsonNull)
                put("speeds", buildJsonObject { put("walkMps", snapshot.speeds.walkMps); put("driveMps", snapshot.speeds.driveMps); put("flightMps", snapshot.speeds.flightMps) })
                put("speedControls", buildJsonObject { put("mode", snapshot.speedControls.mode.name); put("driveMinMps", snapshot.speedControls.driveMinMps); put("driveMaxMps", snapshot.speedControls.driveMaxMps); put("followRoadLimits", snapshot.speedControls.followRoadLimits) })
                val motion = motionTelemetry(snapshot, phoneLocation.snapshot(SystemClock.elapsedRealtimeNanos()), phoneArmed)
                put("motion", buildJsonObject { put("speedMps", motion.speedMps?.let(::JsonPrimitive) ?: JsonNull); put("sampleAgeMs", motion.sampleAgeMs?.let(::JsonPrimitive) ?: JsonNull); put("sampleSequence", motion.sampleSequence); put("placementGeneration", motion.placementGeneration); put("roadLimitMps", motion.roadLimitMps?.let(::JsonPrimitive) ?: JsonNull); put("roadLimitSource", motion.roadLimitSource); put("health", motion.health) })
                put("nextStop", snapshot.nextStop?.let { target -> buildJsonObject { put("point", target.point.json()); put("label", target.label); put("index", target.index) } } ?: JsonNull)
                put("radioRequest", radioRequest() ?: JsonNull)
                if (includePhoneLocation) put("phoneLocation", phoneLocation.snapshot(SystemClock.elapsedRealtimeNanos())?.let { current -> buildJsonObject {
                    put("point", current.point.json()); put("source", current.source.name); put("ageMs", current.ageMs)
                    current.accuracyMeters?.let { put("accuracyMeters", it) }
                    current.speedMps?.let { put("speedMps", it) }; current.bearingDegrees?.let { put("bearingDegrees", it) }; put("sampleSequence", current.sampleSequence); put("placementGeneration", current.placementGeneration)
                } } ?: JsonNull)
            })
        }
    }
}

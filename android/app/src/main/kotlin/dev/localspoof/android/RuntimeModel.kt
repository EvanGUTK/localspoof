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
    val pendingUpdateRadioState: Boolean? = null,
    val providerCleanupPending: Boolean = false,
    val updateBlockReason: String? = null,
    val updateInstallationPending: Boolean = false,
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
    private var foregroundLocationOwner: Any? = null
    private val preparedRadioPolicies = LinkedHashMap<String, String>()
    private var activeRadioPolicy: String? = null
    private var controllerOwnedEdit = false
    private var radioGeneration = 0L
    private var initialAirplaneAtStart: Boolean? = null
    private var pendingUpdateRadioState: Boolean? = null
    private var providerCleanupPending = false
    private var updateInstallationPending = false
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
            phoneLocation = currentLocation, motion = motionTelemetry(snapshot, currentLocation, phoneArmed), simulationArmed = phoneArmed, pendingUpdateRadioState = pendingUpdateRadioState, providerCleanupPending = providerCleanupPending, updateBlockReason = updateBlockReasonLocked(), updateInstallationPending = updateInstallationPending)
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
        check(!updateInstallationPending) { "UPDATE_INSTALLATION_PENDING" }
        coordinator.commit(id, revision)
        activeRadioPolicy = preparedRadioPolicies[id]; preparedRadioPolicies.clear(); radioGeneration++
        initialAirplaneAtStart = radio
        preview = null; message = "Plan ready. Start on this phone."; publish()
    }

    fun beginRemainingEdit(useUndo: Boolean = false, revision: Long = coordinator.snapshot().revision, fromDesktop: Boolean = false): RemainingEditContext = synchronized(lock) {
        check(phoneArmed && serviceRunning) { "PHONE_START_REQUIRED" }
        val confirmed=phoneLocation.snapshot(SystemClock.elapsedRealtimeNanos())
        check(confirmed?.source==PhoneLocationSource.SIMULATION && confirmed.ageMs<=3000) { "CONFIRMED_LOCATION_STALE" }
        coordinator.beginRemainingEdit(SystemClock.elapsedRealtime(), useUndo, revision).also { controllerOwnedEdit=fromDesktop;preview=null;preparedRadioPolicies.clear();publish() }
    }
    fun prepareRemainingDraft(request: PlanRequest, revision: Long = coordinator.snapshot().revision): PreparedPlan {
        val prepared=coordinator.prepareRemainingDraft(request,revision)
        synchronized(lock) { check(coordinator.snapshot().revision==revision && coordinator.snapshot().editId==null) { "STALE_REVISION" };preview=prepared.preview;message=null;publish() }
        return prepared
    }
    fun prepareRemaining(editId: String, request: PlanRequest, revision: Long = coordinator.snapshot().revision): PreparedPlan {
        val prepared=coordinator.prepareRemaining(editId,request,revision)
        synchronized(lock) { check(coordinator.snapshot().editId==editId && coordinator.snapshot().revision==revision) { "STALE_EDIT" };preview=prepared.preview;message=null;publish() }
        return prepared
    }
    fun applyRemaining(editId: String, preparedId: String, revision: Long = coordinator.snapshot().revision) = synchronized(lock) {
        check(!updateInstallationPending) { "UPDATE_INSTALLATION_PENDING" }
        coordinator.applyRemaining(editId,preparedId,SystemClock.elapsedRealtime(),revision)
        controllerOwnedEdit=false;preview=null;preparedRadioPolicies.clear();message=null;publish()
    }
    fun cancelRemaining(editId: String, revision: Long = coordinator.snapshot().revision) = synchronized(lock) {
        coordinator.cancelRemaining(editId,SystemClock.elapsedRealtime(),revision);controllerOwnedEdit=false;preview=null;message=null;publish()
    }
    fun timedPause(durationSeconds: Long, revision: Long = coordinator.snapshot().revision) = synchronized(lock) {
        coordinator.timedPause(Math.multiplyExact(durationSeconds,1000),SystemClock.elapsedRealtime(),revision);preview=null;publish()
    }
    fun updateVacation(remainingFuelLiters: Double? = null, litersPer100Km: Double? = null, reserveLiters: Double? = null,
        revision: Long = coordinator.snapshot().revision) = synchronized(lock) {
        coordinator.updateVacation(remainingFuelLiters,litersPer100Km,reserveLiters,revision);preview=null;publish()
    }
    fun leaveStop(revision: Long = coordinator.snapshot().revision) = synchronized(lock) { coordinator.leaveStop(revision);publish() }

    private fun cancelControllerEdit() {
        val snapshot=coordinator.snapshot()
        val currentEditId=snapshot.editId
        if(controllerOwnedEdit && currentEditId != null) {
            coordinator.cancelRemaining(currentEditId,SystemClock.elapsedRealtime(),snapshot.revision)
            preview=null
        }
        controllerOwnedEdit=false
    }
    fun editContextJson(context: RemainingEditContext): JsonObject = synchronized(lock) {
        val choices=context.choices.choicesJson()
        val policyChoices=if(context.choices.mode==Mode.FLIGHT) JsonObject(choices + ("radioPolicy" to JsonPrimitive(activeRadioPolicy ?: "MANUAL"))) else choices
        JsonObject(context.json() + ("choices" to policyChoices))
    }

    fun attach(adapter: FrameworkLocationProvider) = synchronized(lock) {
        check(!updateInstallationPending) { "UPDATE_INSTALLATION_PENDING" }
        foregroundLocationOwner = null
        provider = adapter; serviceRunning = true; phoneLocation.attach(adapter); publish()
    }
    /** A visible Activity may read real location without starting a service or acquiring mock authority. */
    private fun foregroundLocationAllowedLocked(): Boolean = !serviceRunning && provider == null && !phoneArmed &&
        !updateInstallationPending && !providerCleanupPending && pendingUpdateRadioState == null && coordinator.snapshot().state != SessionState.ERROR
    fun attachForegroundLocation(owner: Any): Boolean = synchronized(lock) {
        if (!foregroundLocationAllowedLocked()) return@synchronized false
        foregroundLocationOwner = owner; phoneLocation.attach(owner); publish(); true
    }
    fun shouldReadForegroundLocation(owner: Any): Boolean = synchronized(lock) {
        foregroundLocationOwner === owner && foregroundLocationAllowedLocked()
    }
    fun offerForegroundLocation(fix: DeviceLocationFix, owner: Any) = synchronized(lock) {
        if (foregroundLocationOwner === owner && foregroundLocationAllowedLocked() &&
            phoneLocation.device(owner, fix, SystemClock.elapsedRealtimeNanos())) publish()
    }
    fun refreshForegroundLocation(owner: Any) = synchronized(lock) {
        if (foregroundLocationOwner === owner && foregroundLocationAllowedLocked()) publish()
    }
    fun currentPhoneLocation(): PhoneLocation? = synchronized(lock) { phoneLocation.snapshot(SystemClock.elapsedRealtimeNanos()) }
    fun detachForegroundLocation(owner: Any) = synchronized(lock) {
        if (foregroundLocationOwner === owner) { foregroundLocationOwner = null; phoneLocation.clear(owner); publish() }
    }
    fun offerDeviceLocation(fix: DeviceLocationFix, expectedOwner: FrameworkLocationProvider) = synchronized(lock) {
        if (serviceRunning && provider === expectedOwner && !phoneArmed && coordinator.snapshot().state != SessionState.ERROR &&
            phoneLocation.device(expectedOwner, fix, SystemClock.elapsedRealtimeNanos())) publish()
    }
    fun shouldReadDeviceLocation(expectedOwner: FrameworkLocationProvider): Boolean = synchronized(lock) {
        serviceRunning && provider === expectedOwner && !phoneArmed && coordinator.snapshot().state != SessionState.ERROR
    }

    fun enableDesktop() = synchronized(lock) {
        check(!updateInstallationPending) { "UPDATE_INSTALLATION_PENDING" }
        if (!desktopEnabled) { controllerGeneration++; desktopEnabled = true; lease.enable() }
        if (message == "LOCAL_USB_SOCKET_UNAVAILABLE") message = null
        publish()
    }

    /** A failed/dead socket revokes only its own desktop authority, not phone playback consent. */
    fun desktopUnavailable(expectedGeneration: Long = controllerGeneration) = synchronized(lock) {
        if (expectedGeneration != controllerGeneration) return@synchronized
        cancelControllerEdit();lease.revoke(); desktopEnabled = false
        message = "LOCAL_USB_SOCKET_UNAVAILABLE"
        publish()
    }

    fun approveController(pairingId: String) = synchronized(lock) {
        check(!updateInstallationPending) { "UPDATE_INSTALLATION_PENDING" }
        val approved = lease.approve(pairingId, SystemClock.elapsedRealtime()); publish(); approved
    }
    fun rejectController(pairingId: String) = synchronized(lock) {
        val rejected = lease.reject(pairingId); publish(); rejected
    }

    fun startFromPhone() = synchronized(lock) {
        try {
            check(!updateInstallationPending) { "UPDATE_INSTALLATION_PENDING" }
            check(pendingUpdateRadioState==null && !providerCleanupPending) { "RECOVERY_REQUIRED_BEFORE_START" }
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
        check(!updateInstallationPending) { "UPDATE_INSTALLATION_PENDING" }
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
    fun resume() = synchronized(lock) { check(!updateInstallationPending) { "UPDATE_INSTALLATION_PENDING" };coordinator.resume(SystemClock.elapsedRealtime()); publish() }

    fun tick(radioEnabled: Boolean, expectedOwner: FrameworkLocationProvider) = synchronized(lock) {
        // Cancellation cannot interrupt a synchronous settings read in an old service coroutine.
        // Check ownership before even lease/radio mutations or publishing a snapshot.
        if (!serviceRunning || provider !== expectedOwner) return@synchronized
        lease.expire(SystemClock.elapsedRealtime())
        if(controllerOwnedEdit && lease.token()==null) cancelControllerEdit()
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
        val recoveryRadio = coordinator.recoveryAirplaneState()
        val restoreRadio = recoveryRadio != null
        // Preserve recovery evidence across Stop, detach and repeated Stop calls. No radio mutation.
        if (recoveryRadio != null) pendingUpdateRadioState = recoveryRadio
        else if (pendingUsbRadio && pendingUpdateRadioState == null) pendingUpdateRadioState = initialAirplaneAtStart ?: false
        // Revoke control first so queued external commands cannot survive local Stop.
        controllerOwnedEdit=false;lease.revoke(); desktopEnabled = false; phoneArmed = false; foregroundLocationOwner = null; phoneLocation.clear()
        coordinator.stop(); preview = null
        preparedRadioPolicies.clear(); activeRadioPolicy = null; initialAirplaneAtStart = null
        val cleanupResult = provider?.cleanup()
        if (cleanupResult != null) providerCleanupPending = !cleanupResult
        val cleanupFailed = providerCleanupPending
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

    private fun desktopUpdateSafeLocked(ignoreInstall:Boolean=false): Boolean {
        val snapshot=coordinator.snapshot()
        return (ignoreInstall || !updateInstallationPending) && snapshot.state==SessionState.IDLE && !phoneArmed && snapshot.editId==null &&
            snapshot.timedPauseRemainingMs==null && pendingUpdateRadioState==null && !providerCleanupPending &&
            (provider==null || provider?.health in setOf("NOT_PREPARED","RELEASED"))
    }
    private fun updateBlockReasonLocked(includeInstall:Boolean=true): String? = when {
        includeInstall && updateInstallationPending -> "Finish or cancel Android update confirmation before starting a journey."
        pendingUpdateRadioState!=null -> "Confirm airplane mode has been restored to its original state."
        providerCleanupPending -> "Reset mock providers before installing an update."
        !desktopUpdateSafeLocked(ignoreInstall=!includeInstall) -> "Stop the journey and release mock location before installing an update."
        serviceRunning || provider!=null || desktopEnabled || lease.token()!=null -> "Stop the phone service and disconnect desktop control before installing an update."
        else -> null
    }
    fun safeForUpdate(): Boolean = synchronized(lock) { updateBlockReasonLocked()==null }
    fun withSafeUpdateBoundary(action:()->Unit) = synchronized(lock) {
        check(updateBlockReasonLocked()==null) { "UPDATE_REQUIRES_SAFE_IDLE" }
        updateInstallationPending=true;publish()
        try { action() } catch(failure:Throwable) { updateInstallationPending=false;publish();throw failure }
    }
    fun installBoundaryReady():Boolean = synchronized(lock) { updateInstallationPending && updateBlockReasonLocked(includeInstall=false)==null }
    /** Recovered OS installer sessions retain the reservation until native reconciliation proves none remain. */
    fun reserveUpdateInstallRecovery() = synchronized(lock) {
        updateInstallationPending=true;publish()
    }
    fun endUpdateInstall() = synchronized(lock) {
        updateInstallationPending=false;publish()
    }
    /** Caller reads the platform state at the click, rather than trusting an acknowledgment alone. */
    fun confirmUpdateRadioRecovery(observedAirplaneState:Boolean): Boolean = synchronized(lock) {
        val expected=pendingUpdateRadioState ?: return@synchronized true
        radio=observedAirplaneState
        if(expected!=observedAirplaneState) { message="Restore airplane mode to its original state before confirming.";publish();return@synchronized false }
        pendingUpdateRadioState=null;message=null;publish();true
    }
    /** Explicit recovery cannot remove overrides from a service that started while UI work queued. */
    fun withProviderResetBoundary(action:()->Boolean):Boolean = synchronized(lock) {
        check(!serviceRunning && provider==null && !phoneArmed && !updateInstallationPending) { "STOP_SERVICE_BEFORE_PROVIDER_RESET" }
        val succeeded=try { action() } catch(failure:Throwable) { providerCleanupPending=true;publish();throw failure }
        providerCleanupPending=!succeeded;publish();succeeded
    }
    /** Internal phone UI calls this only after a real native provider reset completes. */
    fun recordProviderReset(succeeded:Boolean) = synchronized(lock) {
        if(succeeded && !phoneArmed) providerCleanupPending=false
        else if(!succeeded) providerCleanupPending=true
        publish()
    }

    fun detach() = synchronized(lock) {
        stop(); provider = null; serviceRunning = false; publish()
    }

    fun disconnect(generation: Long, channelId: String) = synchronized(lock) {
        if (generation == controllerGeneration) { lease.disconnect(channelId);if(lease.token()==null) cancelControllerEdit();publish() }
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
                put("desktopUpdateSafe", desktopUpdateSafeLocked())
                put("radioObserved", radio)
                put("desiredAirplaneMode", snapshot.desiredAirplaneMode?.let(::JsonPrimitive) ?: JsonNull)
                put("preparedPreview", preview?.json() ?: JsonNull)
                put("activePreview",snapshot.activePreview?.json() ?: JsonNull)
                put("activeChoices",snapshot.activeChoices?.choicesJson()?.let { choices -> if(snapshot.mode==Mode.FLIGHT) JsonObject(choices + ("radioPolicy" to JsonPrimitive(activeRadioPolicy ?: "MANUAL"))) else choices } ?: JsonNull)
                put("editId",snapshot.editId?.let(::JsonPrimitive) ?: JsonNull);put("canUndo",snapshot.canUndo)
                put("timedPauseRemainingMs",snapshot.timedPauseRemainingMs?.let(::JsonPrimitive) ?: JsonNull)
                put("stages",JsonArray(snapshot.stages.map { stage -> buildJsonObject { put("label",stage.label);put("kind",stage.kind);put("status",stage.status);put("remainingMs",stage.remainingMs?.let(::JsonPrimitive) ?: JsonNull) } }))
                put("vacation",snapshot.vacation?.let { v -> buildJsonObject {
                    put("remainingFuelLiters",v.remainingFuelLiters);put("consumedFuelLiters",v.consumedFuelLiters);put("refillCash",v.refillCash?.let(::JsonPrimitive) ?: JsonNull);put("hotelCost",v.hotelCost?.let(::JsonPrimitive) ?: JsonNull);put("consumedFuelValue",v.consumedFuelValue?.let(::JsonPrimitive) ?: JsonNull)
                    put("currency",v.currency);put("effectiveMpg",v.effectiveMpg);put("estimatedRemainingRangeMeters",v.estimatedRemainingRangeMeters);put("stopStage",v.stopStage?.let(::JsonPrimitive) ?: JsonNull)
                } } ?: JsonNull)
                put("message", message?.let(::JsonPrimitive) ?: JsonNull)
                put("elapsedMs", snapshot.elapsedMs)
                put("remainingMs", snapshot.remainingMs?.let(::JsonPrimitive) ?: JsonNull)
                put("remainingMeters", snapshot.remainingMeters?.let(::JsonPrimitive) ?: JsonNull)
                put("waitingFor", snapshot.waitingFor?.let(::JsonPrimitive) ?: JsonNull)
                put("speeds", buildJsonObject { put("walkMps", snapshot.speeds.walkMps); put("driveMps", snapshot.speeds.driveMps); put("flightMps", snapshot.speeds.flightMps) })
                put("speedControls", buildJsonObject { put("mode", snapshot.speedControls.mode.name); put("driveMinMps", snapshot.speedControls.driveMinMps); put("driveMaxMps", snapshot.speedControls.driveMaxMps); put("followRoadLimits", snapshot.speedControls.followRoadLimits) })
                val motion = motionTelemetry(snapshot, phoneLocation.snapshot(SystemClock.elapsedRealtimeNanos()), phoneArmed)
                put("motion", buildJsonObject { put("speedMps", motion.speedMps?.let(::JsonPrimitive) ?: JsonNull); put("sampleAgeMs", motion.sampleAgeMs?.let(::JsonPrimitive) ?: JsonNull); put("sampleSequence", motion.sampleSequence); put("placementGeneration", motion.placementGeneration); put("roadLimitMps", motion.roadLimitMps?.let(::JsonPrimitive) ?: JsonNull); put("roadLimitSource", motion.roadLimitSource); put("health", motion.health) })
                put("nextStop", snapshot.nextStop?.let { target -> buildJsonObject { put("point", target.point.json()); put("label", target.label); put("index", target.index);put("kind",target.kind.name) } } ?: JsonNull)
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

package dev.localspoof.android

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.contentcapture.ContentCaptureManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import dev.localspoof.domain.*
import dev.localspoof.platform.FrameworkLocationProvider
import dev.localspoof.platform.DeviceLocationSampler
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val onlinePlanner by lazy { MobileOnlinePlanner() }
    private val vehicleCatalog by lazy { MobileVehicleCatalog() }
    private var vehicleRequest = 0L
    private var vehicleJob: Job? = null
    private val updates by lazy { androidx.lifecycle.ViewModelProvider(this)[MobileUpdates::class.java] }
    private var bound = false
    private var bindingRequested = false
    private var boundService: SimulationService? = null
    private var foregroundVisible = false
    private var foregroundSampler: DeviceLocationSampler? = null
    private var foregroundLocationOwner: Any? = null
    private var foregroundLocationJob: Job? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (!bindingRequested) return
            bound = true
            boundService = (service as? SimulationService.LocalBinder)?.service
            if (boundService?.isStopped() == true) unbindIfNeeded()
        }
        override fun onServiceDisconnected(name: ComponentName?) { bound = false; boundService = null }
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results[Manifest.permission.ACCESS_FINE_LOCATION] != true) {
            mobileSession.originLocationStatus = "Allow precise location to use your phone's position, or choose an origin on the map."
            RuntimeModel.showError("Precise location permission is required to use this phone's location.")
        } else refreshForegroundLocation()
    }
    private val importPack = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) activityScope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { parseGeoPack(readBounded(it, PhoneControlServer.MAX_PACK)) }
                        ?: throw IllegalArgumentException("RESOURCE_UNREADABLE")
                }
                withContext(Dispatchers.Default) { RuntimeModel.configureResources(parsed) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { RuntimeModel.showError("Resource rejected: supported GeoPack JSON, complete graph, and 16 MiB maximum required.") }
        }
    }

    private val importSaved = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) activityScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val items = contentResolver.openInputStream(uri)?.use { MobileSavedStore.decode(readBounded(it, MobileSavedStore.MAX_BYTES)) }
                        ?: throw IllegalArgumentException("RESOURCE_UNREADABLE")
                    MobileSavedStore(applicationContext).save(items)
                }
                RuntimeModel.showError("Saved items imported locally. Reopen bookmarks to see them.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { RuntimeModel.showError("Import rejected. Choose a supported saved-plans JSON file, up to 512 KiB.") }
        }
    }
    private val exportSaved = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) activityScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val bytes = MobileSavedStore.encode(MobileSavedStore(applicationContext).load())
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        ?: throw IllegalArgumentException("RESOURCE_UNWRITABLE")
                }
                RuntimeModel.showError("Saved items exported to the file you selected.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { RuntimeModel.showError("Export failed. Choose a writable local file.") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Never restore coordinate fields or Android view state from a Bundle.
        super.onCreate(null)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        // Android 10 has activity-level opt-out; per-view capture importance starts in API 30.
        getSystemService(ContentCaptureManager::class.java)?.setContentCaptureEnabled(false)
        if (Build.VERSION.SDK_INT >= 30)
            window.decorView.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        activityScope.launch {
            try {
                val airports = withContext(Dispatchers.IO) { assets.open("airports.json").use { parseAirports(readBounded(it, 16 * 1024 * 1024)) } }
                withContext(Dispatchers.Default) { RuntimeModel.configureResources(newAirports = airports) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { RuntimeModel.showError("Airport catalog unavailable; instant/custom paths still available.") }
        }
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xff38d6b8), surface = Color(0xff101b22), background = Color(0xff0b1419))) { PhoneScreen() } }
    }

    override fun onStart() {
        super.onStart()
        foregroundVisible = true
        if (RuntimeModel.view.value.serviceRunning) bindExistingService()
        refreshForegroundLocation()
    }
    override fun onResume() { super.onResume(); refreshForegroundLocation() }
    override fun onStop() { foregroundVisible = false; closeForegroundLocation(); unbindIfNeeded(); super.onStop() }
    override fun onDestroy() { closeForegroundLocation(); onlinePlanner.close(); vehicleCatalog.close(); vehicleRequest++; vehicleJob?.cancel(); unbindIfNeeded(); activityScope.cancel(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        // Honor framework lifecycle bookkeeping, then discard every saved-state entry.
        // Inputs disable view saving and coordinates use only volatile Compose state.
        super.onSaveInstanceState(outState)
        outState.clear()
    }

    private fun openSettings(action: String) {
        try { startActivity(Intent(action)) }
        catch (_: ActivityNotFoundException) { RuntimeModel.showError("Open Android settings manually to complete setup.") }
    }

    private fun askPermissions(includeNotifications: Boolean = true) {
        val requested = mutableListOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        if (includeNotifications && Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(requested.toTypedArray())
    }

    private fun closeForegroundLocation() {
        foregroundLocationJob?.cancel(); foregroundLocationJob = null
        foregroundSampler?.close(); foregroundSampler = null
        foregroundLocationOwner?.let(RuntimeModel::detachForegroundLocation); foregroundLocationOwner = null
    }

    /** Only a visible screen reads genuine fixes. Playback keeps its separate service-owned source. */
    private fun refreshForegroundLocation() {
        if (!foregroundVisible) return
        val current = RuntimeModel.view.value
        if (current.serviceRunning || current.simulationArmed || current.updateInstallationPending || current.providerCleanupPending ||
            current.pendingUpdateRadioState != null || current.snapshot.state == SessionState.ERROR) {
            closeForegroundLocation(); return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            closeForegroundLocation()
            mobileSession.originLocationStatus = "Tap Use phone location to allow precise location, or choose an origin on the map."
            return
        }
        val locationEnabled = runCatching { getSystemService(android.location.LocationManager::class.java).isLocationEnabled }.getOrDefault(false)
        if (!locationEnabled) {
            closeForegroundLocation()
            mobileSession.originLocationStatus = "Phone location is off. Enable Location in Android, then tap Use phone location to retry."
            return
        }
        foregroundLocationOwner?.let { if (foregroundSampler != null && RuntimeModel.shouldReadForegroundLocation(it)) return }
        closeForegroundLocation()
        val owner = Any()
        if (!RuntimeModel.attachForegroundLocation(owner)) return
        foregroundLocationOwner = owner
        mobileSession.originLocationStatus = "Finding phone location…"
        foregroundSampler = DeviceLocationSampler(this) { fix -> RuntimeModel.offerForegroundLocation(fix, owner) }.also { it.start() }
        val started = SystemClock.elapsedRealtime()
        foregroundLocationJob = activityScope.launch {
            while (isActive && foregroundVisible && RuntimeModel.shouldReadForegroundLocation(owner)) {
                RuntimeModel.refreshForegroundLocation(owner)
                if (RuntimeModel.view.value.phoneLocation != null) mobileSession.originLocationStatus = null
                else if (SystemClock.elapsedRealtime() - started >= 20_000)
                    mobileSession.originLocationStatus = "No fresh phone location yet. Move near a window and tap Use phone location to retry, or choose an origin on the map."
                delay(1_000)
            }
        }
    }

    private fun bindExistingService() {
        if (!bindingRequested) bindingRequested = bindService(Intent(this, SimulationService::class.java), connection, 0)
    }

    private fun unbindIfNeeded() {
        if (bindingRequested) runCatching { unbindService(connection) }
        bindingRequested = false; bound = false; boundService = null
    }

    private fun stopPhoneSession() {
        // Domain revocation/Stop happens first, before Android callback scheduling.
        RuntimeModel.stop()
        try {
            val service = boundService
            if (service != null) service.stopFromPhone()
            else if (RuntimeModel.view.value.serviceRunning)
                startService(Intent(this, SimulationService::class.java).setAction(SimulationService.STOP))
        } catch (_: Exception) {
            stopService(Intent(this, SimulationService::class.java))
        } finally { unbindIfNeeded() }
    }

    private fun launchService(action: String) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            askPermissions(); RuntimeModel.showError("After granting precise location, press the action again."); return
        }
        try {
            startForegroundService(Intent(this, SimulationService::class.java).setAction(action))
            bindExistingService()
        } catch (_: Exception) { RuntimeModel.showError("Start requires this phone screen and location permission.") }
    }

    private val mobileSession by lazy { androidx.lifecycle.ViewModelProvider(this)[MobileSessionState::class.java] }

    private suspend fun planOnPhone(draft: MobileDraft, origin: GeoPoint?, vacation: VacationConfig?, phase: FlightPhase?, remaining: Boolean,
        revision: Long, token: MobileSessionState.PreparationToken, model: MobileSessionState, editId: String?): PlanRequest {
        val request = mobilePlanRequest(draft, origin, vacation)
        if (draft.planningSource == MobilePlanningSource.OFFLINE || draft.customPath || draft.mode == Mode.INSTANT) {
            require(!draft.automaticFuelStops || draft.mode == Mode.INSTANT) { "AUTOMATIC_STATIONS_REQUIRE_ONLINE" }
            return request
        }
        val snapshot = RuntimeModel.view.value.snapshot
        val expectedPhase = snapshot.phase
        return withContext(Dispatchers.IO) {
            onlinePlanner.plan(request, RuntimeModel.airports, MobilePlanningOptions(remainingPhase = phase, remaining = remaining,
                automaticFuelStops = draft.automaticFuelStops && request.vacation != null, currentFuelLiters = snapshot.vacation?.remainingFuelLiters.takeIf { remaining },
                ongoingRefill = remaining && snapshot.waitingFor == "REFUELING")) {
                val current = RuntimeModel.view.value.snapshot
                !model.acceptsPreparation(token) || current.revision != revision || current.phase != expectedPhase || current.editId != editId
            }
        }.also { check(model.acceptsPreparation(token)) { "PLANNING_CANCELLED" } }
    }

    private fun requestVehicle(field: String, value: String, model: MobileSessionState) {
        vehicleJob?.cancel(); vehicleCatalog.cancelRequests(); val owner = ++vehicleRequest
        val initial = model.vehicleCatalog
        val next = when (field) {
            "year" -> if (value.isBlank()) MobileVehicleUi() else MobileVehicleUi(year = value, field = "make")
            "make" -> initial.copy(make = value, model = "", id = "", field = "model")
            "model" -> initial.copy(model = value, id = "", field = "configuration")
            "configuration" -> initial.copy(id = value, field = "complete")
            else -> return
        }
        model.vehicleCatalog = next.copy(busy = true, choices = emptyList(), error = null)
        val profile = model.draft.vacation
        vehicleJob = activityScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val cancelled = { owner != vehicleRequest }
                    when (next.field) {
                        "year" -> vehicleCatalog.years(cancelled)
                        "make" -> vehicleCatalog.makes(next.year.toInt(), cancelled)
                        "model" -> vehicleCatalog.models(next.year.toInt(), next.make, cancelled)
                        "configuration" -> vehicleCatalog.options(next.year.toInt(), next.make, next.model, cancelled)
                        else -> emptyList()
                    }
                }
                if (owner != vehicleRequest) return@launch
                if (next.field == "complete") {
                    val vehicle = withContext(Dispatchers.IO) { vehicleCatalog.vehicle(next.id.toInt()) { owner != vehicleRequest } }
                    if (owner != vehicleRequest) return@launch
                    if (model.draft.vacation != profile || model.draft.mode != Mode.DRIVE || model.remainingEdit != null) {
                        model.vehicleCatalog = next.copy(error = "Draft changed while loading. Select the vehicle again to apply its reference data.")
                    } else {
                        model.invalidate()
                        model.draft = model.draft.copy(vacation = profile.copy(vehicle = "${vehicle.year} ${vehicle.make} ${vehicle.model}",
                            consumption = vehicle.combinedMpg?.let { mobileNumber(if (profile.metric) 235.214583 / it else it) } ?: "",
                            grade = vehicle.fuelGrade ?: profile.grade))
                        model.vehicleCatalog = next.copy(referenceMessage = "Reference combined MPG applied only to this draft. Tank capacity and starting fuel still require your input.${if (vehicle.combinedMpg == null) " No supported gas/diesel efficiency is provided; enter verified data manually." else ""}")
                    }
                } else model.vehicleCatalog = next.copy(choices = result.map { it.text to it.value }, error = if (result.isEmpty()) "No reference choices. Enter specifications manually." else null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (owner == vehicleRequest) model.vehicleCatalog = next.copy(error = "Reference catalog unavailable. Retry or enter your vehicle data manually.") }
        }
    }
    private fun requestReferencePrices(model: MobileSessionState) {
        vehicleJob?.cancel(); vehicleCatalog.cancelRequests(); val owner = ++vehicleRequest
        val profile = model.draft.vacation
        model.vehicleCatalog = model.vehicleCatalog.copy(busy = true, choices = emptyList(), error = null)
        vehicleJob = activityScope.launch {
            try {
                val prices = withContext(Dispatchers.IO) { vehicleCatalog.prices { owner != vehicleRequest } }
                if (owner != vehicleRequest) return@launch
                require(profile.currency == "USD" && model.draft.vacation == profile)
                val price = requireNotNull(when (profile.grade) { FuelGrade.REGULAR -> prices.regular; FuelGrade.MIDGRADE -> prices.midgrade; FuelGrade.PREMIUM -> prices.premium; FuelGrade.DIESEL -> prices.diesel })
                model.invalidate(); model.draft = model.draft.copy(vacation = profile.copy(price = mobileNumber(if (profile.metric) price / 3.785411784 else price)))
                model.vehicleCatalog = model.vehicleCatalog.copy(busy = false, referenceMessage = "${prices.source}; fetched now. Estimated reference only, not verified station pricing. Your active trip stays unchanged.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (owner == vehicleRequest) model.vehicleCatalog = model.vehicleCatalog.copy(busy = false, error = "Reference price unavailable or draft changed. Your entered price is retained.") }
        }
    }
    private fun mobileNumber(value: Double) = String.format(java.util.Locale.ROOT, "%.3f", value)

    @Composable private fun PhoneScreen() {
        val phone by RuntimeModel.view.collectAsState()
        val updateState by updates.state.collectAsState()
        val model = mobileSession
        val screenScope = rememberCoroutineScope()
        DisposableEffect(Unit) { model.attachPlanningCancellation { onlinePlanner.cancelRequests() }; onDispose { model.cancelPendingPreparation(); model.attachPlanningCancellation {} } }
        LaunchedEffect(phone.serviceRunning) { if (!phone.serviceRunning) unbindIfNeeded() }
        LaunchedEffect(phone.serviceRunning, phone.simulationArmed, phone.updateInstallationPending, phone.providerCleanupPending,
            phone.pendingUpdateRadioState, phone.snapshot.state, phone.snapshot.revision) { refreshForegroundLocation() }
        LaunchedEffect(phone.phoneLocation, model.draft.mode, model.section) {
            if (phone.snapshot.activeChoices == null) model.acceptPhoneOrigin(phone.phoneLocation)
        }
        LaunchedEffect(phone.snapshot.editId) {
            if (model.remainingEdit?.held != null && phone.snapshot.editId != model.remainingEdit?.held?.id) model.finishRemainingEdit(false)
        }
        LaunchedEffect(phone.snapshot.revision, phone.snapshot.state) {
            if (phone.snapshot.state == SessionState.IDLE && phone.snapshot.revision > 0) model.clearActivity()
        }
        MobileApp(MobileUiState(phone, model.section, model.draft, model.preferences,
            model.walkDefaultText, model.driveDefaultText, model.settingsError, RuntimeModel.airports, model.remainingEdit, model.mapOverlay, updateState, updates.installedVersion, model.vehicleCatalog, model.candidateBudget, model.originLocationStatus), onEvent = { event ->
            if ((updates.state.value.blocksStart || RuntimeModel.view.value.updateInstallationPending) && event in setOf(MobileUiEvent.StartLocal, MobileUiEvent.StartApproved, MobileUiEvent.EnableDesktop)) {
                RuntimeModel.showError("Finish or cancel the update before starting or connecting desktop control.")
            } else when (event) {
                is MobileUiEvent.PlanningSource -> { model.invalidate(); model.draft = model.draft.copy(planningSource = event.source) }
                is MobileUiEvent.AutomaticFuelStops -> { model.invalidate(); model.draft = model.draft.copy(automaticFuelStops = event.enabled) }
                MobileUiEvent.VehicleLookup -> requestVehicle("year", "", model)
                is MobileUiEvent.VehicleSelection -> requestVehicle(event.field, event.value, model)
                MobileUiEvent.ReferenceFuelPrices -> requestReferencePrices(model)
                MobileUiEvent.CheckUpdate -> updates.check()
                MobileUiEvent.DownloadUpdate -> if (RuntimeModel.safeForUpdate()) updates.download() else RuntimeModel.showError("Stop playback and desktop control before downloading this update.")
                MobileUiEvent.InstallUpdate -> updates.install()
                MobileUiEvent.CancelUpdate -> updates.cancel()
                MobileUiEvent.ContinueUpdate -> updates.confirmationIntent()?.let { runCatching { startActivity(it) }.onFailure { RuntimeModel.showError("Android confirmation unavailable. Cancel this update and retry.") } }
                MobileUiEvent.UpdateInstallPermission -> if (RuntimeModel.safeForUpdate()) runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName"))) }.onFailure { RuntimeModel.showError("Open Android settings and allow installs from LocalSpoof, then return here.") }
                MobileUiEvent.ConfirmUpdateRadio -> {
                    val observed = Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
                    if (!RuntimeModel.confirmUpdateRadioRecovery(observed)) RuntimeModel.showError("Radio recovery is incomplete. Restore the requested airplane-mode state, then check again.")
                }
                is MobileUiEvent.BeginRemainingEdit -> runCatching {
                    if (event.useUndo) {
                        val held = RuntimeModel.beginRemainingEdit(true)
                        model.startRemainingEdit(MobileRemainingDraft(held.choices, held.phase, held))
                    } else {
                        val snapshot = RuntimeModel.view.value.snapshot
                        val choices = requireNotNull(snapshot.activeChoices)
                        model.startRemainingEdit(MobileRemainingDraft(choices, snapshot.phase))
                    }
                }.onFailure { RuntimeModel.showError("Edit unavailable. Finish the current placement or airplane-mode step first.") }
                MobileUiEvent.CancelRemainingEdit -> model.remainingEdit?.let { draftEdit ->
                    model.cancelPendingPreparation()
                    draftEdit.held?.let { held -> runCatching { RuntimeModel.cancelRemaining(held.id, RuntimeModel.view.value.snapshot.revision) } }
                    model.finishRemainingEdit(false)
                }
                MobileUiEvent.ApplyRemainingEdit -> if (model.remainingEdit != null && model.draft.preparedId != null && !model.draft.busy) {
                    val draft = model.draft
                    val draftEdit = requireNotNull(model.remainingEdit)
                    val token = model.beginPreparation()
                    screenScope.launch {
                        var held: RemainingEditContext? = null
                        var applied = false
                        try {
                            held = draftEdit.held ?: RuntimeModel.beginRemainingEdit()
                            model.attachEditHold(held)
                            val request = planOnPhone(draft, held.confirmedOrigin, held.choices.vacation, held.phase, true, held.revision, token, model, held.id)
                            val prepared = withContext(Dispatchers.Default) { RuntimeModel.prepareRemaining(held.id, request, held.revision) }
                            check(model.acceptsPreparation(token)) { "EDIT_CANCELLED" }
                            RuntimeModel.applyRemaining(held.id, prepared.id, RuntimeModel.view.value.snapshot.revision)
                            applied = true
                            model.finishRemainingEdit(true)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            if (model.acceptsPreparation(token)) RuntimeModel.showError("Apply failed; your approved journey is retained. " + mobilePlanningFailureMessage(failure.message))
                        } finally {
                            if (!applied && held != null && RuntimeModel.view.value.snapshot.editId == held.id)
                                runCatching { RuntimeModel.cancelRemaining(held.id, RuntimeModel.view.value.snapshot.revision) }
                            if (!applied && model.remainingEdit?.held?.id == held?.id) { model.releaseEditHold(); model.invalidate() }
                            model.finishPreparation(token)
                        }
                    }
                }
                is MobileUiEvent.TimedPause -> runCatching { RuntimeModel.timedPause(event.seconds) }
                    .onFailure { RuntimeModel.showError("Timed stop unavailable during this placement, edit or airplane-mode step.") }
                MobileUiEvent.CancelTimedPause -> runCatching { RuntimeModel.pause() }
                    .onFailure { RuntimeModel.showError("The timed stop has already ended. Check the current playback state.") }
                MobileUiEvent.LeaveStop -> runCatching { RuntimeModel.leaveStop() }
                    .onFailure { RuntimeModel.showError("Leave now is available after confirmed hotel arrival.") }
                is MobileUiEvent.CorrectVacation -> runCatching { RuntimeModel.updateVacation(remainingFuelLiters = event.fuelLiters, litersPer100Km = event.consumption, reserveLiters = event.reserveLiters) }
                    .onFailure { RuntimeModel.showError("Fuel correction rejected. Check tank capacity, reserve and reachable future stops. The current estimate is retained.") }
                is MobileUiEvent.Vacation -> { model.invalidate(); model.draft = model.draft.copy(vacation = event.draft) }
                is MobileUiEvent.ReorderStop -> if (event.index in model.draft.stops.indices && event.index + event.offset in model.draft.stops.indices) {
                    val stops = model.draft.stops.toMutableList(); val moved = mobilePinAutomaticStop(stops.removeAt(event.index)); stops.add(event.index + event.offset, moved)
                    model.invalidate(); model.draft = model.draft.copy(stops = stops)
                }
                is MobileUiEvent.ConfigureStop -> if (event.index in model.draft.stops.indices) {
                    model.invalidate(); model.draft = model.draft.copy(stops = model.draft.stops.mapIndexed { index, stop -> if (index == event.index) mobilePinAutomaticStop(event.stop) else stop })
                }
                is MobileUiEvent.Section -> model.section = event.section
                is MobileUiEvent.Edit -> model.edit(event.field, event.value)
                is MobileUiEvent.SelectMode -> model.selectMode(event.mode)
                is MobileUiEvent.DepartureGroundMode -> model.selectDepartureGroundMode(event.mode)
                is MobileUiEvent.ArrivalGroundMode -> model.selectArrivalGroundMode(event.mode)
                is MobileUiEvent.FlightTransitMode -> model.selectFlightTransitMode(event.mode)
                is MobileUiEvent.SpeedUnit -> model.changeSpeedUnit(event.unit)
                is MobileUiEvent.DistanceUnit -> model.preferences = model.preferences.copy(distanceUnit = event.unit)
                is MobileUiEvent.DefaultSpeed -> model.editDefault(event.mode, event.value)
                is MobileUiEvent.CustomPath -> { model.invalidate(); model.draft = model.draft.copy(customPath = event.enabled) }
                is MobileUiEvent.SimulationOnly -> { model.invalidate(); model.draft = model.draft.copy(simulationOnly = event.enabled) }
                is MobileUiEvent.AirportSelected -> {
                    model.invalidate()
                    model.draft = if (event.departure) model.draft.copy(departureId = event.id, airportQuery = "") else model.draft.copy(arrivalId = event.id, airportQuery = "")
                }
                is MobileUiEvent.OriginSelected -> {
                    model.protectOrigin()
                    model.invalidate(); model.draft = model.draft.copy(originLat = event.point.lat.toString(), originLon = event.point.lon.toString(), originLabel = event.label?.take(300) ?: "Chosen on map")
                }
                is MobileUiEvent.DrivingMode -> model.selectDrivingMode(event.mode)
                is MobileUiEvent.FollowRoadLimits -> model.selectFollowRoadLimits(event.enabled)
                is MobileUiEvent.SetLiveDrivingControls -> runCatching {
                    RuntimeModel.setSpeeds(drivingSpeedMode = event.controls.mode, driveMinMps = event.controls.driveMinMps, driveMaxMps = event.controls.driveMaxMps, followRoadLimits = event.controls.followRoadLimits)
                }.onFailure { RuntimeModel.showError("Cruise update rejected. Check the range and current journey.") }
                is MobileUiEvent.SetLiveSpeed -> runCatching {
                    when (event.mode) {
                        Mode.WALK -> RuntimeModel.setSpeeds(walkMps = event.speedMps)
                        Mode.DRIVE -> RuntimeModel.setSpeeds(driveMps = event.speedMps, drivingSpeedMode = DrivingSpeedMode.FIXED)
                        Mode.FLIGHT -> RuntimeModel.setSpeeds(flightMps = event.speedMps)
                        else -> throw IllegalArgumentException("INVALID_SPEED_MODE")
                    }
                }.onFailure { RuntimeModel.showError("Speed update rejected. Check the allowed range and current journey.") }
                MobileUiEvent.JumpNextStop -> runCatching { RuntimeModel.jumpToNextStop() }
                    .onFailure { RuntimeModel.showError("Jump unavailable. Resume playback and complete the current placement or airplane-mode step first.") }
                is MobileUiEvent.AddStop -> runCatching {
                    require(event.dwellSeconds in 0..3600 && model.draft.stops.size < 32 && model.draft.mode in setOf(Mode.WALK, Mode.DRIVE))
                    val stop = RouteStop(event.point, event.dwellSeconds * 1000, event.label)
                    model.invalidate(); model.draft = model.draft.copy(stops = if (event.next) listOf(stop) + model.draft.stops else model.draft.stops + stop)
                }.onFailure { RuntimeModel.showError("Choose a walking or driving route and a valid stop, up to 32 stops.") }
                is MobileUiEvent.RemoveStop -> if (event.index in model.draft.stops.indices) {
                    model.invalidate(); model.draft = model.draft.copy(stops = model.draft.stops.filterIndexed { index, _ -> index != event.index })
                }
                is MobileUiEvent.StopDwell -> if (event.index in model.draft.stops.indices && event.seconds in 0..3600) {
                    model.invalidate(); model.draft = model.draft.copy(stops = model.draft.stops.mapIndexed { index, stop -> if (index == event.index) mobilePinAutomaticStop(stop.copy(dwellMs = event.seconds * 1000)) else stop })
                }
                is MobileUiEvent.LoadSaved -> {
                    model.protectOrigin()
                    model.invalidate(); model.draft = event.draft.copy(stops = event.draft.stops.map { it.copy(id = java.util.UUID.randomUUID().toString()) }, preparedId = null, busy = false, speedError = null, flightSpeedError = null,
                        speedText = mobileFormatSpeed(event.draft.speedMps, model.preferences.speedUnit),
                        flightSpeedText = mobileFormatSpeed(event.draft.flightSpeedMps, model.preferences.speedUnit), driveMinText = mobileFormatSpeed(event.draft.driveMinMps, model.preferences.speedUnit), driveMaxText = mobileFormatSpeed(event.draft.driveMaxMps, model.preferences.speedUnit))
                }
                MobileUiEvent.ImportSaved -> importSaved.launch("application/json")
                MobileUiEvent.ExportSaved -> exportSaved.launch("localspoof-saved-plans.json")
                is MobileUiEvent.PlaceSelected -> {
                    model.invalidate(); model.draft = model.draft.copy(destinationLat = event.point.lat.toString(), destinationLon = event.point.lon.toString(), destinationLabel = event.label?.take(300) ?: "Chosen on map", placeQuery = "")
                }
                MobileUiEvent.CurrentOrigin -> {
                    model.requestPhoneOrigin()
                    if (!model.acceptPhoneOrigin(RuntimeModel.currentPhoneLocation(), automatic = false)) {
                        closeForegroundLocation()
                        refreshForegroundLocation()
                        if (RuntimeModel.view.value.serviceRunning)
                            model.originLocationStatus = "No fresh confirmed phone position yet. Check the current playback state, then retry or choose an origin on the map."
                        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) askPermissions(includeNotifications = false)
                    }
                }
                MobileUiEvent.ResetPreferences -> model.resetPreferences()
                MobileUiEvent.Stop -> {
                    // Invalidate background replies before synchronous domain revocation.
                    model.invalidate()
                    stopPhoneSession()
                    model.clearActivity()
                }
                MobileUiEvent.Preview -> if (!model.draft.busy) {
                    val currentSnapshot = RuntimeModel.view.value.snapshot
                    if (model.remainingEdit?.held == null) currentSnapshot.activeChoices?.let { model.refreshRemainingPhase(it, currentSnapshot.phase) }
                    val draft = model.draft
                    val token = model.beginPreparation()
                    val revision = RuntimeModel.coordinator.snapshot().revision
                    screenScope.launch {
                        try {
                            val context = model.remainingEdit
                            val origin = if (context != null && context.held == null) RuntimeModel.view.value.snapshot.point else context?.confirmedOrigin
                            val capturedPhase = RuntimeModel.view.value.snapshot.phase
                            val held = context?.held
                            val request = planOnPhone(draft, origin, context?.choices?.vacation, held?.phase ?: capturedPhase, context != null, revision, token, model, held?.id)
                            val prepared = withContext(Dispatchers.Default) { if (held != null) RuntimeModel.prepareRemaining(held.id, request, revision) else if (context != null) RuntimeModel.prepareRemainingDraft(request, revision) else RuntimeModel.prepare(request, revision) }
                            if (model.acceptsPreparation(token)) {
                                model.draft = model.draft.copy(preparedId = prepared.id, stops = prepared.preview.stops,
                                    departureId = prepared.preview.originAirport?.id ?: model.draft.departureId,
                                    arrivalId = prepared.preview.destinationAirport?.id ?: model.draft.arrivalId)
                                model.candidateBudget = mobileVacationEstimate(request, prepared.preview, RuntimeModel.view.value.snapshot.vacation?.remainingFuelLiters.takeIf { context != null })
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            if (model.acceptsPreparation(token)) RuntimeModel.showError(mobilePlanningFailureMessage(failure.message))
                        } finally {
                            // No overlapping preparation is allowed, even after edits discard its ID.
                            model.finishPreparation(token)
                        }
                    }
                }
                MobileUiEvent.StartLocal -> model.draft.preparedId?.let { id ->
                    if (model.remainingEdit == null && !model.draft.busy && model.draft.speedError == null && model.draft.flightSpeedError == null && mobileDrivingValid(model.draft)) {
                        try { RuntimeModel.commit(id); model.invalidate(); launchService(SimulationService.START) }
                        catch (_: Exception) { model.invalidate(); RuntimeModel.showError("Preview is stale; prepare again.") }
                    }
                }
                MobileUiEvent.StartApproved -> if (RuntimeModel.view.value.snapshot.state == SessionState.READY) launchService(SimulationService.START)
                MobileUiEvent.PauseResume -> runCatching {
                    if (RuntimeModel.view.value.snapshot.state == SessionState.PAUSED) RuntimeModel.resume() else RuntimeModel.pause()
                }.onFailure { RuntimeModel.showError("Playback state changed. Check the current status and try again.") }
                MobileUiEvent.EnableDesktop -> launchService(SimulationService.ENABLE_DESKTOP)
                is MobileUiEvent.Approve -> if (!RuntimeModel.approveController(event.pairingId)) RuntimeModel.showError("Pairing request expired. Retry Connect in Windows and compare the new code.")
                is MobileUiEvent.Reject -> if (!RuntimeModel.rejectController(event.pairingId)) RuntimeModel.showError("Pairing request changed. Review the current comparison code.")
                MobileUiEvent.DeveloperSettings -> openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                MobileUiEvent.Permissions -> askPermissions()
                MobileUiEvent.AirplaneSettings -> openSettings(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                MobileUiEvent.ImportPack -> importPack.launch("application/json")
                MobileUiEvent.ResetProviders -> if (!RuntimeModel.view.value.serviceRunning) screenScope.launch(Dispatchers.Default) {
                    val ok = runCatching { RuntimeModel.withProviderResetBoundary { FrameworkLocationProvider(this@MainActivity).reset() } }.getOrDefault(false)
                    RuntimeModel.showError(if (ok) "Provider overrides reset. Consumers may keep their last fix briefly." else "Select LocalSpoof as mock app first; if recovery fails, deselect it, toggle location, or reboot.")
                }
            }
        })
    }
}

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
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var bound = false
    private var bindingRequested = false
    private var boundService: SimulationService? = null
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
        if (results[Manifest.permission.ACCESS_FINE_LOCATION] != true) RuntimeModel.showError("Precise location permission is required for the location foreground service.")
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
        if (RuntimeModel.view.value.serviceRunning) bindExistingService()
    }
    override fun onStop() { unbindIfNeeded(); super.onStop() }
    override fun onDestroy() { unbindIfNeeded(); activityScope.cancel(); super.onDestroy() }
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

    private fun askPermissions() {
        val requested = mutableListOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(requested.toTypedArray())
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

    @Composable private fun PhoneScreen() {
        val phone by RuntimeModel.view.collectAsState()
        val model = mobileSession
        val screenScope = rememberCoroutineScope()
        DisposableEffect(Unit) { onDispose { model.cancelPendingPreparation() } }
        LaunchedEffect(phone.serviceRunning) { if (!phone.serviceRunning) unbindIfNeeded() }
        LaunchedEffect(phone.snapshot.revision, phone.snapshot.state) {
            if (phone.snapshot.state == SessionState.IDLE && phone.snapshot.revision > 0) model.clearActivity()
        }
        MobileApp(MobileUiState(phone, model.section, model.draft, model.preferences,
            model.walkDefaultText, model.driveDefaultText, model.settingsError, RuntimeModel.airports), onEvent = { event ->
            when (event) {
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
                    model.invalidate(); model.draft = model.draft.copy(stops = model.draft.stops + stop)
                }.onFailure { RuntimeModel.showError("Choose a walking or driving route and a valid stop, up to 32 stops.") }
                is MobileUiEvent.RemoveStop -> if (event.index in model.draft.stops.indices) {
                    model.invalidate(); model.draft = model.draft.copy(stops = model.draft.stops.filterIndexed { index, _ -> index != event.index })
                }
                is MobileUiEvent.StopDwell -> if (event.index in model.draft.stops.indices && event.seconds in 0..3600) {
                    model.invalidate(); model.draft = model.draft.copy(stops = model.draft.stops.mapIndexed { index, stop -> if (index == event.index) stop.copy(dwellMs = event.seconds * 1000) else stop })
                }
                is MobileUiEvent.LoadSaved -> {
                    model.invalidate(); model.draft = event.draft.copy(preparedId = null, busy = false, speedError = null, flightSpeedError = null,
                        speedText = mobileFormatSpeed(event.draft.speedMps, model.preferences.speedUnit),
                        flightSpeedText = mobileFormatSpeed(event.draft.flightSpeedMps, model.preferences.speedUnit), driveMinText = mobileFormatSpeed(event.draft.driveMinMps, model.preferences.speedUnit), driveMaxText = mobileFormatSpeed(event.draft.driveMaxMps, model.preferences.speedUnit))
                }
                MobileUiEvent.ImportSaved -> importSaved.launch("application/json")
                MobileUiEvent.ExportSaved -> exportSaved.launch("localspoof-saved-plans.json")
                is MobileUiEvent.PlaceSelected -> {
                    model.invalidate(); model.draft = model.draft.copy(destinationLat = event.point.lat.toString(), destinationLon = event.point.lon.toString(), destinationLabel = event.label?.take(300) ?: "Chosen on map", placeQuery = "")
                }
                MobileUiEvent.CurrentOrigin -> phone.phoneLocation?.takeIf { it.ageMs <= 30_000 }?.point?.let {
                    model.invalidate(); model.draft = model.draft.copy(originLat = it.lat.toString(), originLon = it.lon.toString(), originLabel = "Phone location")
                }
                MobileUiEvent.ResetPreferences -> model.resetPreferences()
                MobileUiEvent.Stop -> {
                    // Invalidate background replies before synchronous domain revocation.
                    model.invalidate()
                    stopPhoneSession()
                    model.clearActivity()
                }
                MobileUiEvent.Preview -> if (!model.draft.busy) {
                    val draft = model.draft
                    val token = model.beginPreparation()
                    val revision = RuntimeModel.coordinator.snapshot().revision
                    screenScope.launch {
                        try {
                            require(draft.mode != Mode.FLIGHT || draft.flightTransitMode != FlightTransitMode.TIMED || draft.flightSpeedError == null)
                            require(draft.speedError == null || draft.mode == Mode.FLIGHT && draft.departureGroundMode == GroundLegMode.INSTANT && draft.arrivalGroundMode == GroundLegMode.INSTANT)
                            require(mobileDrivingValid(draft))
                            val destination = GeoPoint(draft.destinationLat.toDouble(), draft.destinationLon.toDouble())
                            val origin = if (draft.mode == Mode.INSTANT) destination else GeoPoint(draft.originLat.toDouble(), draft.originLon.toDouble())
                            val custom = if (draft.customPath && draft.mode in setOf(Mode.WALK, Mode.DRIVE)) listOf(origin) + draft.stops.map { it.point } + destination else null
                            val flight = if (draft.mode == Mode.FLIGHT) {
                                val boardingSeconds = draft.boardingHoldSeconds.toDouble().also { require(it.isFinite() && it in 0.0..3600.0) }
                                FlightOptions(simulationOnly = draft.simulationOnly, boardingMs = (boardingSeconds * 1000).toLong(),
                                    originAirportId = draft.departureId.trim().takeIf { it.isNotBlank() }, destinationAirportId = draft.arrivalId.trim().takeIf { it.isNotBlank() },
                                    departureGroundMode = draft.departureGroundMode, arrivalGroundMode = draft.arrivalGroundMode, flightTransitMode = draft.flightTransitMode)
                            } else null
                            val request = PlanRequest(draft.mode, origin, destination, draft.speedMps, custom, flight,
                                flightSpeedMps = draft.flightSpeedMps.takeIf { draft.mode == Mode.FLIGHT }, stops = draft.stops.takeIf { draft.mode in setOf(Mode.WALK, Mode.DRIVE) } ?: emptyList(), speedControls = mobileDrivingControls(draft))
                            val prepared = withContext(Dispatchers.Default) { RuntimeModel.prepare(request, revision) }
                            if (model.acceptsPreparation(token)) model.draft = model.draft.copy(preparedId = prepared.id)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            if (model.acceptsPreparation(token)) RuntimeModel.showError("Unable to prepare: check coordinates, speeds, flight settings, and connected offline road coverage.")
                        } finally {
                            // No overlapping preparation is allowed, even after edits discard its ID.
                            model.finishPreparation(token)
                        }
                    }
                }
                MobileUiEvent.StartLocal -> model.draft.preparedId?.let { id ->
                    if (!model.draft.busy && model.draft.speedError == null && model.draft.flightSpeedError == null && mobileDrivingValid(model.draft)) {
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
                    val ok = FrameworkLocationProvider(this@MainActivity).reset()
                    RuntimeModel.showError(if (ok) "Provider overrides reset. Consumers may keep their last fix briefly." else "Select LocalSpoof as mock app first; if recovery fails, deselect it, toggle location, or reboot.")
                }
            }
        })
    }
}

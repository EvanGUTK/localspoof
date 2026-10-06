package dev.localspoof.android

import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.localspoof.domain.*
import kotlin.math.max
import kotlin.math.roundToInt

internal enum class MobilePlanningSource { ONLINE, OFFLINE }
internal enum class MobileField { ORIGIN_LAT, ORIGIN_LON, DESTINATION_LAT, DESTINATION_LON, SPEED, FLIGHT_SECONDS, DEPARTURE, ARRIVAL, AIRPORT_QUERY, PLACE_QUERY, FLIGHT_SPEED, BOARDING_HOLD, DRIVE_MIN, DRIVE_MAX }
internal data class MobileDraft(
    val mode: Mode = Mode.INSTANT,
    val drivingSpeedMode: DrivingSpeedMode = DrivingSpeedMode.FIXED,
    val driveMinMps: Double = 40.2336, val driveMaxMps: Double = 55.88,
    val driveMinText: String = "90", val driveMaxText: String = "125", val drivingError: String? = null,
    val followRoadLimits: Boolean = true,
    val originLat: String = "", val originLon: String = "", val originLabel: String = "",
    val destinationLat: String = "", val destinationLon: String = "", val destinationLabel: String = "",
    val speedMps: Double = 13.9, val speedText: String = mobileFormatSpeed(13.9, MobileSpeedUnit.MPH),
    val speedError: String? = null, val flightSeconds: String = "3600", val boardingHoldSeconds: String = "30",
    val departureId: String = "", val arrivalId: String = "", val airportQuery: String = "", val placeQuery: String = "",
    val departureGroundMode: GroundLegMode = GroundLegMode.DRIVE, val arrivalGroundMode: GroundLegMode = GroundLegMode.DRIVE,
    val flightTransitMode: dev.localspoof.domain.FlightTransitMode = dev.localspoof.domain.FlightTransitMode.TIMED,
    val planningSource: MobilePlanningSource = MobilePlanningSource.ONLINE, val automaticFuelStops: Boolean = false,
    val customPath: Boolean = false, val simulationOnly: Boolean = false,
    val preparedId: String? = null, val busy: Boolean = false,
    val vacation: MobileVacationDraft = MobileVacationDraft(),
    val flightSpeedError: String? = null, val flightSpeedMps: Double = 230.0, val flightSpeedText: String = mobileFormatSpeed(230.0, MobileSpeedUnit.MPH), val stops: List<RouteStop> = emptyList()
)
internal data class MobileRemainingDraft(val choices: PlanRequest, val phase: FlightPhase?, val held: RemainingEditContext? = null) {
    val confirmedOrigin: GeoPoint get() = held?.confirmedOrigin ?: choices.origin
}
internal data class MobileUiState(
    val phone: PhoneView,
    val section: MobileSection = MobileSection.MAP,
    val draft: MobileDraft = MobileDraft(),
    val preferences: MobilePreferences = MobilePreferences(),
    val walkDefaultText: String = mobileFormatSpeed(1.4, MobileSpeedUnit.MPH),
    val driveDefaultText: String = mobileFormatSpeed(13.9, MobileSpeedUnit.MPH),
    val settingsError: String? = null,
    val airports: List<Airport> = emptyList(),
    val remainingEdit: MobileRemainingDraft? = null,
    val mapOverlay: MobileMapOverlayState? = null,
    val updateState: MobileUpdateState = MobileUpdateState(),
    val installedVersion: String = "",
    val vehicleCatalog: MobileVehicleUi = MobileVehicleUi(),
    val candidateBudget: MobileVacationEstimate? = null,
    val originLocationStatus: String? = null
)
internal sealed interface MobileUiEvent {
    data class PlanningSource(val source: MobilePlanningSource) : MobileUiEvent
    data class AutomaticFuelStops(val enabled: Boolean) : MobileUiEvent
    data class VehicleSelection(val field: String, val value: String) : MobileUiEvent
    data object VehicleLookup : MobileUiEvent
    data object ReferenceFuelPrices : MobileUiEvent
    data object CheckUpdate : MobileUiEvent
    data object DownloadUpdate : MobileUiEvent
    data object InstallUpdate : MobileUiEvent
    data object CancelUpdate : MobileUiEvent
    data object UpdateInstallPermission : MobileUiEvent
    data object ContinueUpdate : MobileUiEvent
    data object ConfirmUpdateRadio : MobileUiEvent
    data class Section(val section: MobileSection) : MobileUiEvent
    data class Edit(val field: MobileField, val value: String) : MobileUiEvent
    data class SelectMode(val mode: Mode) : MobileUiEvent
    data class SpeedUnit(val unit: MobileSpeedUnit) : MobileUiEvent
    data class DistanceUnit(val unit: MobileDistanceUnit) : MobileUiEvent
    data class DefaultSpeed(val mode: Mode, val value: String) : MobileUiEvent
    data class CustomPath(val enabled: Boolean) : MobileUiEvent
    data class SimulationOnly(val enabled: Boolean) : MobileUiEvent
    data class DepartureGroundMode(val mode: GroundLegMode) : MobileUiEvent
    data class ArrivalGroundMode(val mode: GroundLegMode) : MobileUiEvent
    data class FlightTransitMode(val mode: dev.localspoof.domain.FlightTransitMode) : MobileUiEvent
    data class AirportSelected(val id: String, val departure: Boolean) : MobileUiEvent
    data class PlaceSelected(val point: GeoPoint, val label: String? = null) : MobileUiEvent
    data class OriginSelected(val point: GeoPoint, val label: String? = null) : MobileUiEvent
    data class SetLiveSpeed(val mode: Mode, val speedMps: Double) : MobileUiEvent
    data class DrivingMode(val mode: DrivingSpeedMode) : MobileUiEvent
    data class FollowRoadLimits(val enabled: Boolean) : MobileUiEvent
    data class SetLiveDrivingControls(val controls: DrivingSpeedControls) : MobileUiEvent
    data class AddStop(val point: GeoPoint, val label: String, val dwellSeconds: Long, val next: Boolean = false) : MobileUiEvent
    data class ReorderStop(val index: Int, val offset: Int) : MobileUiEvent
    data class ConfigureStop(val index: Int, val stop: RouteStop) : MobileUiEvent
    data class CorrectVacation(val fuelLiters: Double, val consumption: Double, val reserveLiters: Double) : MobileUiEvent
    data class Vacation(val draft: MobileVacationDraft) : MobileUiEvent
    data class BeginRemainingEdit(val useUndo: Boolean = false) : MobileUiEvent
    data object CancelRemainingEdit : MobileUiEvent
    data object ApplyRemainingEdit : MobileUiEvent
    data class TimedPause(val seconds: Long) : MobileUiEvent
    data object LeaveStop : MobileUiEvent
    data object CancelTimedPause : MobileUiEvent
    data class RemoveStop(val index: Int) : MobileUiEvent
    data class StopDwell(val index: Int, val seconds: Long) : MobileUiEvent
    data class LoadSaved(val draft: MobileDraft) : MobileUiEvent
    data object ExportSaved : MobileUiEvent
    data object ImportSaved : MobileUiEvent
    data object JumpNextStop : MobileUiEvent
    data object CurrentOrigin : MobileUiEvent
    data object ResetPreferences : MobileUiEvent
    data object Preview : MobileUiEvent
    data object StartLocal : MobileUiEvent
    data object StartApproved : MobileUiEvent
    data object PauseResume : MobileUiEvent
    data object Stop : MobileUiEvent
    data object EnableDesktop : MobileUiEvent
    data class Approve(val pairingId: String) : MobileUiEvent
    data class Reject(val pairingId: String) : MobileUiEvent
    data object DeveloperSettings : MobileUiEvent
    data object Permissions : MobileUiEvent
    data object ResetProviders : MobileUiEvent
    data object AirplaneSettings : MobileUiEvent
    data object ImportPack : MobileUiEvent
}

/** Presentation emits user actions only: it cannot arm providers, pair, or change radios. */
@Composable internal fun MobileApp(state: MobileUiState, onEvent: (MobileUiEvent) -> Unit, modifier: Modifier = Modifier) {
    var bookmarks by remember { mutableStateOf(false) }
    val defaultMapOverlay = remember { MobileMapOverlayState() }
    val mapOverlay = state.mapOverlay ?: defaultMapOverlay
    var hasShownMap by remember { mutableStateOf(state.section == MobileSection.MAP) }
    Surface(modifier.fillMaxSize().testTag("mobile-root"), color = Color(0xff0b1419)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            val layout = mobileLayoutPolicy(maxWidth.value.coerceAtMost(840f), maxHeight.value, LocalDensity.current.fontScale)
            Column(Modifier.align(Alignment.TopCenter).widthIn(max = 840.dp).fillMaxSize()) {
                MobileNavigation(state.section, onEvent, { bookmarks = true })
                // Keep one renderer and camera in RAM when navigating. The covered map is
                // hidden from touch/accessibility and paused; playback remains service-owned.
                Box(Modifier.weight(1f).fillMaxWidth().testTag("mobile-content")) {
                    val mapVisible = state.section == MobileSection.MAP
                    val mapModifier = if (mapVisible) Modifier.fillMaxSize() else Modifier.fillMaxSize().graphicsLayer { alpha = 0f }.clearAndSetSemantics { }
                    if (hasShownMap || mapVisible) MapHome(state, layout, onEvent, mapModifier, mapOverlay, visible = mapVisible)
                    SideEffect { if (mapVisible) hasShownMap = true }
                    if (!mapVisible) key(state.section) {
                        Surface(Modifier.fillMaxSize(), color = Color(0xff0b1419)) {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                StatusCard(state, onEvent, layout.compactHeight)
                                when (state.section) {
                                    MobileSection.CONNECT -> ConnectScreen(state, onEvent)
                                    MobileSection.JOURNEY -> JourneyScreen(state, layout, onEvent)
                                    MobileSection.SETTINGS -> { SettingsScreen(state, onEvent); ConnectScreen(state, onEvent) }
                                    MobileSection.MAP -> Unit
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = Color(0xff26373f))
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!layout.compactHeight) StartAction(state, onEvent)
                    Button(onClick = { onEvent(MobileUiEvent.Stop) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("phone-stop"), colors = ButtonDefaults.buttonColors(containerColor = Color(0xff293b43), contentColor = Color(0xffffb7a9))) { Text("Stop and discard", softWrap = true) }
                }
            }
        }
    }
    if (bookmarks) SavedPlacesDialog(state, onEvent) { bookmarks = false }
}

@Composable private fun MobileNavigation(section: MobileSection, event: (MobileUiEvent) -> Unit, bookmarks: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().testTag("mobile-header").padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { event(MobileUiEvent.Section(MobileSection.MAP)) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("nav-map")) {
            Text("LocalSpoof", color = Color(0xff38d6b8), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
        }
        IconButton(onClick = bookmarks, modifier = Modifier.size(48.dp).testTag("mobile-bookmarks").semantics { contentDescription = "Saved places and routes" }) { MobileControlIcon("bookmark") }
        Box {
            IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp).border(1.dp, Color(0xff38d6b8), CircleShape).background(Color(0xff142a2b), CircleShape).testTag("mobile-section-selector").semantics { contentDescription = "Settings and connection" }) { MobileControlIcon("settings") }
            DropdownMenu(expanded, { expanded = false }) {
                listOf(MobileSection.MAP, MobileSection.CONNECT, MobileSection.JOURNEY, MobileSection.SETTINGS).forEach { destination -> DropdownMenuItem(text = { Text(if (destination == MobileSection.JOURNEY) "Journey options" else destination.label) }, onClick = { expanded = false; event(MobileUiEvent.Section(destination)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("nav-${destination.name.lowercase()}-menu")) }
            }
        }
    }
}

/** Drawn geometry stays visible across OEM font packs and accessibility font scales. */
@Composable internal fun MobileControlIcon(kind: String, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp).testTag("mobile-icon-$kind")) { drawMobileControlIcon(kind) }
}
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMobileControlIcon(kind: String) {
        val scale = size.width / 24f
        val color = Color(0xff38d6b8)
        val stroke = Stroke(2f * scale)
        fun p(x: Float, y: Float) = Offset(x * scale, y * scale)
        when (kind) {
            "settings" -> {
                drawCircle(color, 7.5f * scale, p(12f, 12f), style = stroke)
                drawCircle(color, 2.6f * scale, p(12f, 12f), style = stroke)
                repeat(8) { index ->
                    val angle = index * kotlin.math.PI / 4
                    val x = kotlin.math.cos(angle).toFloat(); val y = kotlin.math.sin(angle).toFloat()
                    drawLine(color, p(12f + x * 7.5f, 12f + y * 7.5f), p(12f + x * 11f, 12f + y * 11f), 2.8f * scale)
                }
            }
            "bookmark" -> {
                val path = Path().apply { moveTo(6f * scale, 3f * scale); lineTo(18f * scale, 3f * scale); lineTo(18f * scale, 21f * scale); lineTo(12f * scale, 17f * scale); lineTo(6f * scale, 21f * scale); close() }
                drawPath(path, color, style = stroke)
            }
            else -> { drawCircle(color, 6.5f * scale, p(10f, 10f), style = stroke); drawLine(color, p(15f, 15f), p(22f, 22f), 2.5f * scale) }
        }
    }

/** Volatile map presentation only; never saved or supplied to the runtime. */
@Stable internal class MobileMapOverlayState {
    var viewport by mutableStateOf<MapViewport?>(null)
    var placesBatch by mutableStateOf(java.util.UUID.randomUUID().toString())
        private set
    private var nearbyValue by mutableStateOf<List<NearbyPlace>>(emptyList())
    var nearby: List<NearbyPlace>
        get() = nearbyValue
        set(value) { replaceNearby(value) }
    var detail by mutableStateOf<NearbyPinClick?>(null)
        private set
    var preserveCameraRevision by mutableStateOf(0L)
        private set
    fun replaceNearby(value: List<NearbyPlace>) {
        if (nearby == value) return
        nearbyValue = value.take(8).toList(); placesBatch = java.util.UUID.randomUUID().toString(); detail = null
    }
    fun browse(pin: NearbyPinClick) { if (pin.batch == placesBatch && pin.index in nearby.indices) detail = pin }
    fun browse(place: NearbyPlace) { val index = nearby.indexOf(place); if (index >= 0) browse(NearbyPinClick(placesBatch, index)) }
    fun selectedDetail(): NearbyPlace? = detail?.takeIf { it.batch == placesBatch }?.let { nearby.getOrNull(it.index) }
    fun dismissDetail() { detail = null }
    fun preserveCamera() { preserveCameraRevision++ }
    var placeRole by mutableStateOf("Destination")
    var follow by mutableStateOf(false)
    var fitRevision by mutableStateOf(0L)
    var retryRevision by mutableStateOf(0L)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun MapHome(state: MobileUiState, layout: MobileLayoutPolicy, event: (MobileUiEvent) -> Unit, modifier: Modifier, overlay: MobileMapOverlayState = remember { MobileMapOverlayState() }, visible: Boolean = true) {
    var showResults by remember { mutableStateOf(false) }
    var showJourney by remember { mutableStateOf(false) }
    fun chooseOnMap(point: GeoPoint) {
        val label = overlay.nearby.firstOrNull { it.point.distanceTo(point) <= 0.01 }?.name ?: "Chosen on map"
        when (if (state.draft.mode == Mode.INSTANT) "Destination" else overlay.placeRole) {
            "Origin" -> event(MobileUiEvent.OriginSelected(point, label))
            "Stop", "Next stop" -> event(MobileUiEvent.AddStop(point, label.take(120), 0, next = overlay.placeRole == "Next stop"))
            else -> event(MobileUiEvent.PlaceSelected(point, label))
        }
        overlay.replaceNearby(emptyList())
    }
    val large = LocalDensity.current.fontScale >= 1.8f || layout.compactHeight
    if (large) Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SearchPlaces(state, event, overlay.viewport, overlay.nearby, overlay.placeRole, { overlay.placeRole = it }) { overlay.replaceNearby(it) }
        MobileLiveMap(state.phone, state.draft, Modifier.fillMaxWidth().height(160.dp).testTag("phone-live-map"), overlay.nearby, { overlay.viewport = it }, overlay.follow, overlay.fitRevision, visible = visible, restoreCamera = overlay.viewport, retryRevision = overlay.retryRevision, onGesture = { overlay.follow = false }, placesBatch = overlay.placesBatch, preserveCameraRevision = overlay.preserveCameraRevision, onPoi = { overlay.browse(it); showResults = false }) { chooseOnMap(it) }
        if (overlay.nearby.isNotEmpty()) NearbyResultsButton(overlay.nearby) { showResults = true }
        MapJourneyPanel(state, event, layout.compactHeight)
        MapCameraControls(overlay)
        MobileMapKey(event, overlay.follow, { overlay.follow = it }, { overlay.follow = false; overlay.fitRevision++ }, retry = { overlay.retryRevision++ })
        Text("© OpenStreetMap contributors · OpenFreeMap", style = MaterialTheme.typography.labelSmall)
    } else Box(modifier) {
        MobileLiveMap(state.phone, state.draft, Modifier.fillMaxSize().testTag("phone-live-map"), overlay.nearby, { overlay.viewport = it }, overlay.follow, overlay.fitRevision, visible = visible, restoreCamera = overlay.viewport, retryRevision = overlay.retryRevision, onGesture = { overlay.follow = false }, placesBatch = overlay.placesBatch, preserveCameraRevision = overlay.preserveCameraRevision, onPoi = { overlay.browse(it); showResults = false }) { chooseOnMap(it) }
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().testTag("mobile-map-top-overlay").padding(horizontal = 12.dp)) {
            SearchPlaces(state, event, overlay.viewport, overlay.nearby, overlay.placeRole, { overlay.placeRole = it }) { overlay.replaceNearby(it) }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().testTag("mobile-map-bottom-overlay").padding(horizontal = 12.dp, vertical = 6.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xf5101b22))) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (overlay.nearby.isNotEmpty()) {
                        NearbyResultsButton(overlay.nearby) { showResults = true }
                        if (state.phone.snapshot.state in setOf(SessionState.ACTIVE, SessionState.PAUSED, SessionState.HOLDING)) CompactPlayback(state, event) { if (state.remainingEdit != null) event(MobileUiEvent.Section(MobileSection.JOURNEY)) else showJourney = true }
                    } else CompactJourney(state, event) { if (state.remainingEdit != null) event(MobileUiEvent.Section(MobileSection.JOURNEY)) else showJourney = true }
                    if (state.phone.pairingPending || state.phone.message != null) {
                        TextButton(onClick = { if (state.phone.pairingPending) event(MobileUiEvent.Section(MobileSection.CONNECT)) else showJourney = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("compact-status-details")) {
                            Text(if (state.phone.pairingPending) "Computer waiting · Review" else "Attention needed · Details", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                    Text("© OpenStreetMap contributors · OpenFreeMap", style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("mobile-map-attribution"))
                }
            }
        }
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MapCameraControls(overlay)
            MobileMapKey(event, overlay.follow, { overlay.follow = it }, { overlay.follow = false; overlay.fitRevision++ }, retry = { overlay.retryRevision++ })
        }
    }
    if (visible && showResults) ModalBottomSheet(onDismissRequest = { showResults = false }, modifier = Modifier.testTag("nearby-sheet")) {
        ModalActions("nearby-sheet", event) { showResults = false }
        Text("Choose a ${if (state.draft.mode == Mode.INSTANT) "destination" else overlay.placeRole.lowercase()}", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
        NearbyResults(state, overlay.nearby) { overlay.browse(it); showResults = false }
    }
    val detailOwner = overlay.detail
    val detail = overlay.selectedDetail()
    if (visible && detail != null) ModalBottomSheet(onDismissRequest = { overlay.dismissDetail() }, modifier = Modifier.testTag("nearby-detail-sheet")) {
        NearbyPlaceDetails(state, detail, overlay.placeRole, close = { overlay.dismissDetail() }, back = { overlay.dismissDetail(); showResults = true }, select = {
            // Revalidate at confirmation time: a refreshed batch cannot select an old detail.
            if (overlay.detail == detailOwner && overlay.selectedDetail() == detail && !state.draft.busy) {
                overlay.preserveCamera()
                selectNearbyPlace(state, detail, overlay.placeRole, event)
                overlay.dismissDetail()
            }
        })
    }
    if (visible && showJourney) ModalBottomSheet(onDismissRequest = { showJourney = false }, modifier = Modifier.testTag("journey-sheet")) {
        ModalActions("journey-sheet", event) { showJourney = false }
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MapJourneyPanel(state, event, true)
            Text("Online map/search · OpenFreeMap / Photon · © OpenStreetMap", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Camera controls remain discoverable outside Map key and never mutate playback. */
@Composable private fun MapCameraControls(overlay: MobileMapOverlayState) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(selected = overlay.follow, onClick = { overlay.follow = !overlay.follow },
            label = { Text(if (overlay.follow) "Following" else "Follow") },
            modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 80.dp).background(Color(0xee101b22), CircleShape)
                .testTag("phone-follow").semantics { contentDescription = if (overlay.follow) "Following phone location. Tap to stop following." else "Follow phone location" })
        TextButton(onClick = { overlay.follow = false; overlay.fitRevision++ },
            modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 80.dp).background(Color(0xee101b22), CircleShape).testTag("phone-fit-route")) { Text("Fit route") }
    }
}

@Composable private fun NearbyResultsButton(nearby: List<NearbyPlace>, open: () -> Unit) {
    TextButton(onClick = open, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("show-nearby-results")) { Text("${nearby.size} ${nearby.firstOrNull()?.category?.label?.lowercase() ?: "places"} · Show results", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
}
@Composable private fun ModalActions(tag: String, event: (MobileUiEvent) -> Unit, close: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = close, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("$tag-close")) { Text("Close") }
        TextButton(onClick = { event(MobileUiEvent.Stop); close() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("$tag-stop")) { Text("Stop and discard", color = Color(0xffffb7a9)) }
    }
}
/** A frozen publication expires on the visible UI's monotonic clock as well as the service clock. */
@Composable private fun agingMotion(telemetry: MotionTelemetry): MotionTelemetry {
    var elapsed by remember(telemetry) { mutableLongStateOf(0) }
    LaunchedEffect(telemetry) {
        if (telemetry.health !in setOf("LIVE", "WAITING") || telemetry.sampleAgeMs == null) return@LaunchedEffect
        val received = android.os.SystemClock.elapsedRealtime()
        while (elapsed + telemetry.sampleAgeMs <= 1500) { delay(200); elapsed = (android.os.SystemClock.elapsedRealtime() - received).coerceAtLeast(0) }
    }
    val age = telemetry.sampleAgeMs?.let { it + elapsed }
    return if (age != null && age > 1500 && telemetry.health in setOf("LIVE", "WAITING")) telemetry.copy(speedMps = null, sampleAgeMs = age, health = "STALE") else telemetry.copy(sampleAgeMs = age)
}
@Composable private fun CompactPlayback(state: MobileUiState, event: (MobileUiEvent) -> Unit, details: () -> Unit) {
    val motion = agingMotion(state.phone.motion)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Sim ${mobileMotionSpeed(motion, state.preferences.speedUnit)} · ${mobileMotionStatus(motion)}", modifier = Modifier.testTag("phone-speed-gauge"), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
            Text(mobileEtaLabel(state.phone.snapshot), modifier = Modifier.testTag("phone-eta"), maxLines = 1, style = MaterialTheme.typography.bodySmall)
        }
        if (state.phone.snapshot.state in setOf(SessionState.ACTIVE, SessionState.PAUSED, SessionState.HOLDING)) TextButton(onClick = { event(MobileUiEvent.PauseResume) }, modifier = Modifier.heightIn(min = 48.dp).testTag("phone-pause")) { Text(if (state.phone.snapshot.state == SessionState.PAUSED) "Resume" else "Pause") }
        TextButton(onClick = details, modifier = Modifier.heightIn(min = 48.dp).testTag("journey-details")) { Text(if (state.remainingEdit != null) "Edit" else "Details") }
    }
}
@Composable private fun CompactJourney(state: MobileUiState, event: (MobileUiEvent) -> Unit, details: () -> Unit) {
    if (state.phone.snapshot.state in setOf(SessionState.ACTIVE, SessionState.PAUSED, SessionState.HOLDING)) CompactPlayback(state, event, details)
    else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = state.draft.mode == Mode.INSTANT, onClick = { event(MobileUiEvent.SelectMode(Mode.INSTANT)) }, label = { Text("Teleport") }, modifier = Modifier.heightIn(min = 48.dp).testTag("mobile-teleport"))
        FilterChip(selected = state.draft.mode != Mode.INSTANT, onClick = { if (state.draft.mode == Mode.INSTANT) event(MobileUiEvent.SelectMode(Mode.DRIVE)) }, label = { Text("Route") }, modifier = Modifier.heightIn(min = 48.dp).testTag("mobile-route"))
        if (state.phone.snapshot.activePreview == null && state.draft.mode == Mode.INSTANT) Button(onClick = { event(MobileUiEvent.Preview) }, enabled = !state.draft.busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("map-preview")) { Text("Preview", maxLines = 1) }
        else TextButton(onClick = details, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("journey-details")) { Text("Options", maxLines = 1) }
    }
    Text(if (state.remainingEdit != null) "Editing remaining journey · approved route stays live" else if (state.phone.snapshot.activePreview != null) "${state.phone.snapshot.mode?.name?.lowercase()?.replaceFirstChar { it.uppercase() }} journey · Phone owns playback" else state.draft.destinationLabel.ifBlank { if (state.draft.destinationLat.isNotBlank()) "Selected location" else "Search for a place or tap the map" }, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun MapJourneyPanel(state: MobileUiState, event: (MobileUiEvent) -> Unit, compact: Boolean) {
    if (state.phone.snapshot.activePreview == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = state.draft.mode == Mode.INSTANT, onClick = { event(MobileUiEvent.SelectMode(Mode.INSTANT)) }, label = { Text("Teleport") }, modifier = Modifier.heightIn(min = 48.dp).testTag("mobile-teleport"))
        FilterChip(selected = state.draft.mode != Mode.INSTANT, onClick = { if (state.draft.mode == Mode.INSTANT) event(MobileUiEvent.SelectMode(Mode.DRIVE)) }, label = { Text("Route") }, modifier = Modifier.heightIn(min = 48.dp).testTag("mobile-route"))
    }
    if (state.phone.snapshot.activePreview == null && state.draft.mode != Mode.INSTANT) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(Mode.WALK, Mode.DRIVE, Mode.FLIGHT).forEach { mode ->
        FilterChip(selected = state.draft.mode == mode, onClick = { event(MobileUiEvent.SelectMode(mode)) }, label = { Text(if (mode == Mode.FLIGHT) "Fly" else mode.name.lowercase().replaceFirstChar { it.uppercase() }) }, modifier = Modifier.heightIn(min = 48.dp))
    } }
    val preview = state.phone.snapshot.activePreview ?: state.phone.preview ?: state.phone.snapshot.preparedPreview
    Text(preview?.let { "${mobileDistanceLabel(it.distanceMeters, state.preferences.distanceUnit)} · ${max(1L, (it.durationMs + 59999) / 60000)} min preview" }
        ?: if (state.draft.destinationLat.isNotBlank()) state.draft.destinationLabel.ifBlank { "Selected place · ready to preview" } else "Search for a place or tap the map", style = MaterialTheme.typography.titleMedium)
    state.phone.phoneLocation?.let { Text("${if (it.source.name == "DEVICE") "Device location" else "Simulated location"} · ${it.ageMs / 1000} s old", style = MaterialTheme.typography.bodySmall) }
    state.draft.destinationLabel.takeIf { it.isNotBlank() && preview != null }?.let { label ->
        val draftDestination = runCatching { GeoPoint(state.draft.destinationLat.toDouble(), state.draft.destinationLon.toDouble()) }.getOrNull()
        val staged = draftDestination != null && draftDestination != preview?.destination
        Text(if (staged) "Draft destination · $label" else label, style = MaterialTheme.typography.bodyMedium)
    }
    PlaybackProgress(state, event)
    RemainingJourneyActions(state, event)
    if (state.phone.pairingPending) { Text("Computer waiting for approval"); Action("Compare and approve", "open-pairing") { event(MobileUiEvent.Section(MobileSection.CONNECT)) } }
    state.phone.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (compact) StartAction(state, event, status = true)
    if (state.phone.snapshot.state in setOf(SessionState.ACTIVE, SessionState.PAUSED, SessionState.HOLDING)) {
        Action(if (state.phone.snapshot.state == SessionState.PAUSED) "Resume" else "Pause", "phone-pause") { event(MobileUiEvent.PauseResume) }
    }
    Action("Journey options", "mobile-journey-options") { event(MobileUiEvent.Section(MobileSection.JOURNEY)) }
    if (state.draft.mode == Mode.INSTANT) Button(onClick = { event(MobileUiEvent.Preview) }, enabled = !state.draft.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("map-preview")) { Text("Preview this location") }
    else Text("Open Journey options to plan worldwide roads on this phone. Windows is an optional connected interface.", style = MaterialTheme.typography.bodySmall)
}

internal fun mobileJumpBlockReason(state: MobileUiState): String? = mobileJumpBlockReason(state.phone, state.remainingEdit != null)
internal fun mobileJumpBlockReason(phone: PhoneView, editing: Boolean = false): String? {
    val snapshot = phone.snapshot
    return when {
        !phone.simulationArmed || !phone.serviceRunning -> "Phone playback is not running. Stop, then start the journey on this phone before jumping."
        editing || snapshot.editId != null -> "Apply or cancel the remaining journey edit before jumping."
        snapshot.state != SessionState.ACTIVE -> "Resume travel before jumping."
        snapshot.waitingFor != null -> "Complete the required placement or airplane-mode step before jumping."
        snapshot.nextStop == null -> "No next stop is available to jump to."
        snapshot.mode == Mode.FLIGHT && snapshot.phase == FlightPhase.BOARDING -> {
            val hold = snapshot.stages.firstOrNull()?.remainingMs?.takeIf { it >= 0 }
            (hold?.let { "Boarding hold · ${(it / 1000) + if (it % 1000 > 0) 1 else 0} s remaining. " } ?: "") + "Jump is available after boarding and the required airplane-mode step."
        }
        snapshot.mode == Mode.FLIGHT && snapshot.phase == FlightPhase.SETTLING -> "Wait for landing placement to settle before jumping."
        snapshot.mode == Mode.FLIGHT && snapshot.phase !in setOf(FlightPhase.ORIGIN_GROUND, FlightPhase.FLIGHT_INTERVAL, FlightPhase.DESTINATION_GROUND) -> "Complete the current airport transition before jumping."
        else -> null
    }
}

@Composable private fun PlaybackProgress(state: MobileUiState, event: (MobileUiEvent) -> Unit) {
    val snapshot = state.phone.snapshot
    if (snapshot.mode == null) return
    val motion = agingMotion(state.phone.motion)
    Text("Simulated speed: ${mobileMotionSpeed(motion, state.preferences.speedUnit)}", modifier = Modifier.testTag("phone-speed-details"), style = MaterialTheme.typography.titleMedium)
    Text("${mobileMotionStatus(motion)}${motion.sampleAgeMs?.let { " · sample ${it / 1000.0} s old" } ?: ""}")
    Text(mobileRoadLimitLabel(state.phone.motion, state.preferences.speedUnit), modifier = Modifier.testTag("phone-road-limit"))
    mobileRadioPrompt(snapshot, state.phone.radioObserved)?.let { prompt ->
        Text(prompt, modifier = Modifier.testTag("phone-radio-prompt"), color = MaterialTheme.colorScheme.primary)
        Action("Open airplane-mode settings", "phone-radio-settings") { event(MobileUiEvent.AirplaneSettings) }
    }
    Text(mobileEtaLabel(snapshot), modifier = Modifier.testTag("phone-eta"), style = MaterialTheme.typography.titleMedium)
    snapshot.remainingMeters?.let { Text("${mobileDistanceLabel(it, state.preferences.distanceUnit)} remaining") }
    if (snapshot.state in setOf(SessionState.ACTIVE, SessionState.PAUSED)) {
        var options by remember { mutableStateOf(false) }
        Action(if (options) "Hide live speeds" else "Adjust live speeds", "phone-live-speed-toggle") { options = !options }
        if (options) {
            LiveDrivingOptions(state, event)
            listOf(Mode.WALK to snapshot.speeds.walkMps, Mode.DRIVE to snapshot.speeds.driveMps, Mode.FLIGHT to snapshot.speeds.flightMps).forEach { (mode, physical) ->
            var text by remember(mode, physical, state.preferences.speedUnit) { mutableStateOf(mobileFormatSpeed(physical, state.preferences.speedUnit)) }
            val parsed = runCatching { mobileParseSpeed(text, state.preferences.speedUnit, mode).also { if (mode == Mode.FLIGHT) require(it >= 30.0) } }.getOrNull()
            PrivateInput(text, "${if (mode == Mode.FLIGHT) "Plane" else if (mode == Mode.DRIVE) "Car" else "Walk"} (${state.preferences.speedUnit.label})", "live-speed-${mode.name.lowercase()}", numeric = true) { text = it }
            Action("Apply ${mode.name.lowercase()} speed", enabled = parsed != null) { parsed?.let { event(MobileUiEvent.SetLiveSpeed(mode, it)) } }
        }
        }
        snapshot.nextStop?.let { next ->
            var confirm by remember(next) { mutableStateOf(false) }
            val jumpReason = mobileJumpBlockReason(state)
            val jumpAllowed = jumpReason == null
            LaunchedEffect(jumpAllowed) { if (!jumpAllowed) confirm = false }
            Action("Jump to ${next.label}", "phone-jump-next", enabled = jumpAllowed) { confirm = true }
            jumpReason?.let { Text(it, modifier = Modifier.testTag("phone-jump-reason"), style = MaterialTheme.typography.bodySmall) }
            if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Jump to ${next.label}?") }, text = { Text("This places the simulated location at the next stop. Required placement and airplane-mode steps remain in place.") }, confirmButton = { TextButton(onClick = { confirm = false; event(MobileUiEvent.JumpNextStop) }, enabled = jumpAllowed) { Text("Jump") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
        }
    }
}

@Composable private fun DraftDrivingOptions(state: MobileUiState, event: (MobileUiEvent) -> Unit) {
    val draft = state.draft
    Text("Driving cruise", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { DrivingSpeedMode.entries.forEach { mode ->
        FilterChip(selected = draft.drivingSpeedMode == mode, onClick = { event(MobileUiEvent.DrivingMode(mode)) }, label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }, modifier = Modifier.heightIn(min = 48.dp).testTag("draft-cruise-${mode.name.lowercase()}"))
    } }
    if (draft.drivingSpeedMode == DrivingSpeedMode.VARIABLE) {
        PrivateInput(draft.driveMinText, "Minimum cruise (${state.preferences.speedUnit.label})", "draft-cruise-min", numeric = true) { event(MobileUiEvent.Edit(MobileField.DRIVE_MIN, it)) }
        PrivateInput(draft.driveMaxText, "Maximum cruise (${state.preferences.speedUnit.label})", "draft-cruise-max", numeric = true) { event(MobileUiEvent.Edit(MobileField.DRIVE_MAX, it)) }
        draft.drivingError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    Toggle(draft.followRoadLimits, "Follow route speed limits") { event(MobileUiEvent.FollowRoadLimits(it)) }
    Text("Mapped limits are labeled; missing posted limits show Unknown. Offline routes can also impose estimated speed caps. Stops and route limits can reduce speed below your minimum. Cruise changes require a new preview.", style = MaterialTheme.typography.bodySmall)
}
@Composable private fun LiveDrivingOptions(state: MobileUiState, event: (MobileUiEvent) -> Unit) {
    val controls = state.phone.snapshot.speedControls
    var mode by remember(controls) { mutableStateOf(controls.mode) }
    var follow by remember(controls) { mutableStateOf(controls.followRoadLimits) }
    var min by remember(controls, state.preferences.speedUnit) { mutableStateOf(mobileFormatSpeed(controls.driveMinMps, state.preferences.speedUnit)) }
    var max by remember(controls, state.preferences.speedUnit) { mutableStateOf(mobileFormatSpeed(controls.driveMaxMps, state.preferences.speedUnit)) }
    Text("Live driving cruise", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { DrivingSpeedMode.entries.forEach { value -> FilterChip(selected = mode == value, onClick = { mode = value }, label = { Text(value.name.lowercase().replaceFirstChar { it.uppercase() }) }, modifier = Modifier.heightIn(min = 48.dp)) } }
    if (mode == DrivingSpeedMode.VARIABLE) {
        PrivateInput(min, "Minimum (${state.preferences.speedUnit.label})", "live-cruise-min", numeric = true) { min = it }
        PrivateInput(max, "Maximum (${state.preferences.speedUnit.label})", "live-cruise-max", numeric = true) { max = it }
    }
    Toggle(follow, "Follow route speed limits") { follow = it }
    val parsed = runCatching { DrivingSpeedControls(mode, mobileParseSpeed(min, state.preferences.speedUnit, Mode.DRIVE), mobileParseSpeed(max, state.preferences.speedUnit, Mode.DRIVE), follow) }.getOrNull()
    Text(mobileRoadLimitLabel(state.phone.motion, state.preferences.speedUnit), style = MaterialTheme.typography.bodySmall)
    Action("Apply driving cruise", "apply-live-cruise", enabled = parsed != null) { parsed?.let { event(MobileUiEvent.SetLiveDrivingControls(it)) } }
    if (parsed == null) Text("Correct the range before applying.", color = MaterialTheme.colorScheme.error)
    Text("Applying the car speed below selects fixed cruise. Changes do not restart the journey.", style = MaterialTheme.typography.bodySmall)
}

@Composable private fun SearchPlaces(state: MobileUiState, event: (MobileUiEvent) -> Unit, viewport: MapViewport?, nearby: List<NearbyPlace>, role: String, setRole: (String) -> Unit, setNearby: (List<NearbyPlace>) -> Unit) {
    val scope = rememberCoroutineScope()
    val network = remember { MobileMapNetwork() }
    var category by remember { mutableStateOf<PlaceCategory?>(null) }
    var results by remember { mutableStateOf<List<MobilePlace>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableLongStateOf(0L) }
    var job by remember { mutableStateOf<Job?>(null) }
    fun cancel() { request++; job?.cancel(); job = null; network.cancelRequests(); results = emptyList(); setNearby(emptyList()); busy = false; error = null }
    DisposableEffect(network) { onDispose { network.close() } }
    LaunchedEffect(state.draft.placeQuery, role, state.draft.mode) { cancel(); category = null }
    LaunchedEffect(state.phone.snapshot.revision) { if (state.phone.snapshot.state == SessionState.IDLE && state.phone.snapshot.revision > 0) { cancel(); category = null } }
    LaunchedEffect(viewport) { if (busy && category != null) { cancel(); error = "Map moved. Tap Search this area when ready." } }
    LaunchedEffect(state.draft.originLat, state.draft.originLon, state.draft.destinationLat, state.draft.destinationLon, state.draft.stops) { if (busy) { cancel(); category = null } }
    fun choose(point: GeoPoint, name: String) {
        when (if (state.draft.mode == Mode.INSTANT) "Destination" else role) {
            "Origin" -> event(MobileUiEvent.OriginSelected(point, name))
            "Stop", "Next stop" -> event(MobileUiEvent.AddStop(point, name.take(120), 0, next = role == "Next stop"))
            else -> event(MobileUiEvent.PlaceSelected(point, name))
        }
        cancel(); category = null
    }
    fun search(selected: PlaceCategory? = null) {
        cancel(); category = selected
        val area = viewport
        if (selected != null && (area == null || area.zoom < 9.0)) { error = "Zoom in on a city or search for a place, then choose a category."; return }
        val owner = request; val selectedRole = role; val query = state.draft.placeQuery
        busy = true
        job = scope.launch {
            try {
                if (selected != null) {
                    val found = withContext(Dispatchers.IO) { network.nearby(requireNotNull(area).point, selected) }
                    if (owner == request && selectedRole == role) { setNearby(found); if (found.isEmpty()) error = "No ${selected.label.lowercase()} found within 10 km. Move the map and search this area." }
                } else {
                    val found = withContext(Dispatchers.IO) { network.search(query) }
                    if (owner == request && selectedRole == role) { results = found; if (found.isEmpty()) error = "No matches. Try a nearby city or a different name." }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (owner == request) error = "Search unavailable. Wait a moment and try again, tap the map or choose in Windows." }
            finally { if (owner == request) busy = false }
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xf5101b22))) {
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.weight(1f)) { PrivateInput(state.draft.placeQuery, "Search city, address or place", "world-place-search", hideLabel = true) { event(MobileUiEvent.Edit(MobileField.PLACE_QUERY, it)) } }
                IconButton(onClick = { search() }, enabled = !busy && state.draft.placeQuery.trim().length >= 2, modifier = Modifier.size(48.dp).testTag("world-search-button")) { if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else MobileControlIcon("search") }
            }
            Row(Modifier.fillMaxWidth().testTag("place-categories"), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (category != null) TextButton(onClick = { search(category) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("search-this-area")) { Text("Search this area") }
                PlaceCategory.entries.take(4).forEach { choice -> TextButton(onClick = { search(choice) }, modifier = Modifier.heightIn(min = 48.dp).testTag("category-${choice.name.lowercase()}")) { Text(choice.label, color = if (category == choice) Color(0xff38d6b8) else MaterialTheme.colorScheme.onSurface) } }
                }
                var more by remember { mutableStateOf(false) }
                Box { TextButton(onClick = { more = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("category-more")) { Text("More") }
                    DropdownMenu(more, { more = false }) {
                        if (category != null) DropdownMenuItem(text = { Text("Clear places") }, onClick = { cancel(); category = null; more = false }, modifier = Modifier.heightIn(min = 48.dp).testTag("clear-places"))
                        if (state.draft.mode != Mode.INSTANT) (if (state.draft.mode == Mode.FLIGHT) listOf("Origin", "Destination") else if (state.remainingEdit != null) listOf("Destination", "Next stop", "Stop") else listOf("Origin", "Destination", "Stop")).forEach { choice -> DropdownMenuItem(text = { Text("Use place as $choice") }, onClick = { cancel(); setRole(choice); more = false }, modifier = Modifier.heightIn(min = 48.dp)) }
                        PlaceCategory.entries.drop(4).forEach { choice -> DropdownMenuItem(text = { Text(choice.label) }, onClick = { more = false; search(choice) }, modifier = Modifier.heightIn(min = 48.dp).testTag("category-${choice.name.lowercase()}")) } }
                }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("place-search-message")) }
            if (results.isNotEmpty()) Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()).testTag("place-results")) {
                results.take(8).forEach { place -> Action(place.name) { choose(place.point, place.name) } }
            }
        }
    }
}

/** Browsing is presentation only; destination changes require the detail sheet's Select action. */
@Composable internal fun NearbyResults(state: MobileUiState, nearby: List<NearbyPlace>, browse: (NearbyPlace) -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()).testTag("nearby-results")) {
        nearby.forEachIndexed { index, place -> Action("${index + 1}. ${place.name} · ${mobileDistanceLabel(place.distanceMeters, state.preferences.distanceUnit)}", "nearby-result-$index") { browse(place) } }
    }
}
private fun selectNearbyPlace(state: MobileUiState, place: NearbyPlace, role: String, event: (MobileUiEvent) -> Unit) {
    when (if (state.draft.mode == Mode.INSTANT) "Destination" else role) {
        "Origin" -> event(MobileUiEvent.OriginSelected(place.point, place.name))
        "Stop", "Next stop" -> event(MobileUiEvent.AddStop(place.point, place.name.take(120), 0, next = role == "Next stop"))
        else -> event(MobileUiEvent.PlaceSelected(place.point, place.name))
    }
}
@Composable internal fun NearbyPlaceDetails(state: MobileUiState, place: NearbyPlace, role: String, close: () -> Unit, back: () -> Unit = close, select: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(place.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("nearby-detail-name"))
        Text(place.category.label, modifier = Modifier.testTag("nearby-detail-category"))
        Text("${mobileDistanceLabel(place.distanceMeters, state.preferences.distanceUnit)} from searched map center", modifier = Modifier.testTag("nearby-detail-distance"))
        Text("OpenStreetMap place information. Prices and current availability are not provided.", style = MaterialTheme.typography.bodySmall)
        val chosenRole = if (state.draft.mode == Mode.INSTANT) "destination" else role.lowercase()
        Text("Select to stage this $chosenRole. Preview and Start or Apply remain separate.", style = MaterialTheme.typography.bodySmall)
        Action("Select as $chosenRole", "nearby-detail-select", enabled = !state.draft.busy, action = select)
        Action("Back to results", "nearby-detail-back") { back() }
        Action("Close", "nearby-detail-close") { close() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun MobileMapKey(event: (MobileUiEvent) -> Unit, follow: Boolean = false, setFollow: (Boolean) -> Unit = {}, fit: () -> Unit = {}, retry: () -> Unit = {}) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp).background(Color(0xee101b22), CircleShape).testTag("map-key-toggle")) { Text("Map key") }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, modifier = Modifier.testTag("map-key-dialog"), title = { Text("Map key") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Phone" to Color(0xff4b73ed), "Origin" to Color(0xff35d4ba), "Destination" to Color(0xffef785f), "Walk / drive" to Color(0xff35d4ba), "Flight · dashed" to Color(0xffb678ef), "Stops" to Color(0xfff3c96b), "Numbered places" to Color(0xffeeae62)).forEach { (label, color) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { Canvas(Modifier.size(10.dp)) { drawCircle(color) }; Text(label, style = MaterialTheme.typography.labelSmall) }
                }
            }
            Toggle(follow, "Follow current phone location") { setFollow(it) }
            Action("Fit route", "map-fit-route") { fit(); expanded = false }
            Action("Reload map", "phone-map-retry") { retry(); expanded = false }
            Text("Gold route/pin: candidate only. The approved route changes after successful Apply.", style = MaterialTheme.typography.bodySmall)
            Text("Gray phone dot: stale, waiting or paused simulation; it does not predict movement.", style = MaterialTheme.typography.bodySmall)
            Text("Places are within 10 km of the searched map center. Pan, then tap Search this area to refresh. Choosing a place stages your plan; press Preview and Start explicitly.", style = MaterialTheme.typography.bodySmall)
            Text("Online map/search · OpenFreeMap / Photon · © OpenStreetMap", style = MaterialTheme.typography.labelSmall)
        }
    }, confirmButton = { TextButton(onClick = { expanded = false }, modifier = Modifier.heightIn(min = 48.dp).testTag("map-key-close")) { Text("Close") } }, dismissButton = { TextButton(onClick = { event(MobileUiEvent.Stop); expanded = false }, modifier = Modifier.heightIn(min = 48.dp).testTag("map-key-stop")) { Text("Stop and discard") } })
}

@Composable private fun SavedPlacesDialog(state: MobileUiState, event: (MobileUiEvent) -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { MobileSavedStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<MobileSavedItem>>(emptyList()) }
    var name by remember { mutableStateOf("") }; var group by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }; var clearConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { try { items = withContext(Dispatchers.IO) { store.load() } } catch (_: Exception) { message = "Saved items could not be read. Import a backup or clear saved items." } }
    fun persist(updated: List<MobileSavedItem>) { scope.launch { try { withContext(Dispatchers.IO) { store.save(updated) }; items = updated; message = "Saved locally on this phone." } catch (_: Exception) { message = "Unable to save. Check the selected coordinates, name and route options." } } }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Saved places and routes") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Only explicit saves persist locally. No automatic history. Loading stages a new plan and does not start playback. USB radio policies from Windows use manual radio steps when prepared locally on this phone; exports preserve the original saved policy. Export uses your chosen document provider.")
            PrivateInput(name, "Name", "saved-name") { name = it }; PrivateInput(group, "Folder", "saved-group") { group = it }
            Action("Save selected place or route", "save-current", enabled = name.isNotBlank() && items.size < 100) {
                runCatching { MobileSavedStore.newItem(name, group, mobileSaveDraft(state)) }.onSuccess { persist(items + it) }.onFailure { message = "Choose a valid place or plan before saving." }
            }
            message?.let { Text(it) }
            items.sortedWith(compareBy({ it.group }, { it.name })).forEach { item ->
                Text("${item.name}${if (item.group.isBlank()) "" else " · ${item.group}"}")
                Action("Load ${item.name}") { event(MobileUiEvent.LoadSaved(item.draft.copy(destinationLabel = item.name))); dismiss() }
                Action("Rename / move to entered folder", enabled = name.isNotBlank()) { persist(items.map { if (it.id == item.id) it.copy(name = name.trim(), group = group.trim()) else it }) }
                Action("Delete ${item.name}") { persist(items.filter { it.id != item.id }) }
            }
            Action("Export saved items") { dismiss(); event(MobileUiEvent.ExportSaved) }
            Action("Import saved items") { dismiss(); event(MobileUiEvent.ImportSaved) }
            Action("Clear all saved items") { clearConfirm = true }
            if (clearConfirm) { Text("Delete every saved place and route on this phone?"); Action("Confirm clear") { scope.launch { withContext(Dispatchers.IO) { store.clear() }; items = emptyList(); clearConfirm = false } }; Action("Keep saved items") { clearConfirm = false } }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Done") } })
}
internal fun mobileSaveDraft(state: MobileUiState): MobileDraft {
    val preview = state.phone.snapshot.activePreview ?: state.phone.preview ?: state.phone.snapshot.preparedPreview
    if (state.draft.destinationLat.isNotBlank() || preview == null) return state.draft
    return state.draft.copy(drivingSpeedMode = state.phone.snapshot.speedControls.mode, driveMinMps = state.phone.snapshot.speedControls.driveMinMps, driveMaxMps = state.phone.snapshot.speedControls.driveMaxMps, followRoadLimits = state.phone.snapshot.speedControls.followRoadLimits, mode = preview.mode, originLat = preview.origin.lat.toString(), originLon = preview.origin.lon.toString(), destinationLat = preview.destination.lat.toString(), destinationLon = preview.destination.lon.toString(), speedMps = if (preview.mode == Mode.WALK) state.phone.snapshot.speeds.walkMps else state.phone.snapshot.speeds.driveMps, flightSpeedMps = state.phone.snapshot.speeds.flightMps,
        departureId = preview.originAirport?.id ?: "", arrivalId = preview.destinationAirport?.id ?: "", departureGroundMode = preview.departureGroundMode ?: GroundLegMode.DRIVE, arrivalGroundMode = preview.arrivalGroundMode ?: GroundLegMode.DRIVE, flightTransitMode = preview.flightTransitMode ?: FlightTransitMode.TIMED, simulationOnly = preview.simulationOnly, customPath = preview.customPath, stops = preview.stops, vacation = state.phone.snapshot.activeChoices?.vacation?.let(::mobileVacationDraft) ?: state.draft.vacation)
}

@Composable private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}
@Composable private fun Action(label: String, tag: String? = null, enabled: Boolean = true, action: () -> Unit) {
    OutlinedButton(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier)) { Text(label, softWrap = true) }
}
@Composable private fun StatusCard(state: MobileUiState, event: (MobileUiEvent) -> Unit, compact: Boolean) {
    val phone = state.phone
    Panel("${phone.snapshot.state.name.lowercase().replaceFirstChar { it.uppercase() }} · ${if (phone.desktopEnabled) "USB enabled" else "Phone control"}") {
        Text("Provider: ${phone.providerHealth.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
        phone.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        phone.snapshot.phase?.let { Text("Journey phase: ${it.name.lowercase().replace('_', ' ')}") }
        PlaybackProgress(state, event)
        if (phone.message?.contains("airplane mode manually") == true && mobileRadioPrompt(phone.snapshot, phone.radioObserved) == null) {
            Text("Restore your original connectivity settings, then return.")
            Action("Open airplane-mode settings") { event(MobileUiEvent.AirplaneSettings) }
        }
        // Start stays reachable by scrolling when the fixed footer has room only for Stop.
        if (compact) StartAction(state, event, status = true)
        if (phone.snapshot.state in setOf(SessionState.ACTIVE, SessionState.PAUSED, SessionState.HOLDING)) {
            Action(if (phone.snapshot.state == SessionState.PAUSED) "Resume" else "Pause", "phone-pause") { event(MobileUiEvent.PauseResume) }
        }
    }
}
@Composable private fun StartAction(state: MobileUiState, event: (MobileUiEvent) -> Unit, status: Boolean = false) {
    if (state.draft.preparedId != null && !state.draft.busy && state.draft.speedError == null && state.draft.flightSpeedError == null && mobileBoardingValid(state.draft) && mobileDrivingValid(state.draft)) {
        Button(enabled = !state.updateState.blocksStart && !state.phone.updateInstallationPending, onClick = { event(MobileUiEvent.StartLocal) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(if (status) "phone-start-local-content" else "phone-start-local")) { Text("Start this plan", softWrap = true) }
    } else if (state.phone.snapshot.state == SessionState.READY) {
        Button(enabled = !state.updateState.blocksStart && !state.phone.updateInstallationPending, onClick = { event(MobileUiEvent.StartApproved) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(if (status) "phone-start-approved-content" else "phone-start-approved")) { Text("Start approved plan on phone", softWrap = true) }
    }
}

@Composable private fun ConnectScreen(state: MobileUiState, event: (MobileUiEvent) -> Unit) {
    Panel("Connect to Windows") {
        Text("Use Windows as an optional larger interface. This phone can plan and play independently; connecting never replaces your active journey.")
        Text("1. Enable Developer options and select LocalSpoof as the mock location app. Keep device location on.")
        Action("Open Developer options") { event(MobileUiEvent.DeveloperSettings) }
        Action("Grant location and notification permissions") { event(MobileUiEvent.Permissions) }
        Text("2. Choose USB debugging with a data cable, or Android 11+ Wireless debugging on the same Wi-Fi. Pair the computer in Windows; Android trust and LocalSpoof approval are separate.")
        Text("3. Enable desktop control below. In Windows, choose USB or Wi-Fi and connect this phone. Compare the number on both screens and approve here only if they match.")
        Button(enabled = !state.updateState.blocksStart && !state.phone.updateInstallationPending, onClick = { event(MobileUiEvent.EnableDesktop) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("enable-desktop")) { Text(if (state.phone.desktopEnabled) "Desktop control enabled" else "Enable desktop control", softWrap = true) }
    }
    if (state.phone.desktopEnabled) Panel("Pairing") {
        if (state.phone.pairingPending) {
            val comparison = state.phone.pairingComparison
            val pairingId = state.phone.pairingRequestId
            val validComparison = comparison?.matches(Regex("[0-9]{6}")) == true
            Text("Compare this number with Windows")
            Text(mobilePairingComparison(comparison ?: ""), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth().wrapContentHeight().testTag("pairing-comparison"))
            Text("Approve only if both screens show the same number and this is the computer you connected. No typing needed. The request expires after two minutes.")
            Button(enabled = validComparison && pairingId != null, onClick = { pairingId?.let { event(MobileUiEvent.Approve(it)) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pairing-approve")) { Text("Approve connected computer", softWrap = true) }
            Action("Deny request", "pairing-reject", enabled = pairingId != null) { pairingId?.let { event(MobileUiEvent.Reject(it)) } }
        } else if (state.phone.controllerApproved) {
            Text("Computer approved for this connection. Windows shows when control is ready. Stop revokes this approval.", modifier = Modifier.testTag("controller-approved"))
        } else Text("Desktop control is enabled. Connect this phone in Windows to request approval. Windows shows whether it is connected.")
        Text("Controller permission lasts only for this session. Stop revokes desktop control. Enabling it again does not replace an existing approved connection.")
    }
    Panel("Private by default") {
        Text("No account, ads, fees, activity history, or application logs. Current activity, controller permission and settings stay in memory. Explicit bookmarks persist locally until deleted. Stop discards activity; preferences reset when Android closes the app process.")
        Text("Android labels location samples as mock. Other apps may reject them. Background playback uses a visible foreground notification; screen-off behavior depends on phone battery settings.")
        Text("Online maps/search on this phone and Windows routing send requests to providers, who may retain them. Bookmarks save only when you choose Save; no cloud.")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun JourneyScreen(state: MobileUiState, layout: MobileLayoutPolicy, event: (MobileUiEvent) -> Unit) {
    val d = state.draft
    val preview = if (d.preparedId != null) state.phone.preview else (state.phone.snapshot.activePreview ?: state.phone.snapshot.preparedPreview).takeIf { state.phone.snapshot.state in setOf(SessionState.READY, SessionState.ACTIVE, SessionState.PAUSED, SessionState.HOLDING) }
    RemainingJourneyActions(state, event)
    Panel(if (state.remainingEdit != null) "Edit remaining journey" else "Journey") {
        Text("Plan and start on this phone. Windows is an optional connected interface to the same journey.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mode.entries.filter { mode -> state.remainingEdit?.let { edit -> if (edit.choices.mode == Mode.FLIGHT) mode == Mode.FLIGHT else mode in setOf(Mode.WALK, Mode.DRIVE) && (edit.choices.vacation == null || mode == Mode.DRIVE) } ?: true }.forEach { mode -> FilterChip(selected = mode == d.mode, onClick = { event(MobileUiEvent.SelectMode(mode)) }, label = { Text(if (mode == Mode.FLIGHT) "Airport journey" else mode.name.lowercase().replaceFirstChar { it.uppercase() }) }, modifier = Modifier.heightIn(min = 48.dp)) }
        }
        if (d.mode != Mode.INSTANT) {
            Text("Origin", style = MaterialTheme.typography.titleMedium)
            if (state.remainingEdit == null) Coordinates(d.originLat, d.originLon, "origin", layout.twoColumnFields, event)
            else Text("Starts at the confirmed phone position. Completed travel stays completed.")
            state.phone.phoneLocation?.let { location ->
                Text("${if (location.source.name == "DEVICE") "Device location" else "Simulated location"} · ${location.ageMs / 1000} s old")
            }
            if (state.remainingEdit == null) {
                Action("Use phone location as origin", "use-phone-origin", enabled = !d.busy) { event(MobileUiEvent.CurrentOrigin) }
                state.originLocationStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Text(if (d.mode == Mode.INSTANT) "Location to hold" else "Destination", style = MaterialTheme.typography.titleMedium)
        Coordinates(d.destinationLat, d.destinationLon, "destination", layout.twoColumnFields, event)
        if (d.mode != Mode.INSTANT) {
            Text("Route source", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { MobilePlanningSource.entries.forEach { source ->
                FilterChip(selected = d.planningSource == source, onClick = { event(MobileUiEvent.PlanningSource(source)) }, label = { Text(if (source == MobilePlanningSource.ONLINE) "Online" else "Imported offline") }, modifier = Modifier.heightIn(min = 48.dp).testTag("planning-${source.name.lowercase()}"))
            } }
            Text("Online providers receive the locations needed to calculate this draft. Planning stays local; no LocalSpoof account or backend. Coverage and posted speed limits vary.", style = MaterialTheme.typography.bodySmall)
        }
        if (d.mode == Mode.WALK || d.mode == Mode.DRIVE) {
            PrivateInput(d.speedText, "Speed (${state.preferences.speedUnit.label})", "speed-input", numeric = true) { event(MobileUiEvent.Edit(MobileField.SPEED, it)) }
            d.speedError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (d.mode == Mode.DRIVE) DraftDrivingOptions(state, event)
            Text("Allowed: ${mobileFormatSpeed(0.1, state.preferences.speedUnit)}–${mobileFormatSpeed(mobileMaximumSpeed(d.mode), state.preferences.speedUnit)} ${state.preferences.speedUnit.label}", style = MaterialTheme.typography.bodySmall)
            Toggle(d.customPath, "Custom straight-line path (no road routing)") { event(MobileUiEvent.CustomPath(it)) }
        }
        if (d.mode == Mode.FLIGHT) {
            PrivateInput(d.speedText, "Car speed (${state.preferences.speedUnit.label})", "flight-car-speed", numeric = true) { event(MobileUiEvent.Edit(MobileField.SPEED, it)) }
            d.speedError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            d.flightSpeedError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            DraftDrivingOptions(state, event)
            if (state.remainingEdit == null || state.remainingEdit.phase == FlightPhase.ORIGIN_GROUND) {
            PrivateInput(d.boardingHoldSeconds, "Boarding hold (seconds, 0–3,600)", "boarding-hold", numeric = true) { event(MobileUiEvent.Edit(MobileField.BOARDING_HOLD, it)) }
            if (!mobileBoardingValid(d)) Text("Enter a boarding hold from 0 to 3,600 seconds.", color = MaterialTheme.colorScheme.error)
            Text("To departure airport", style = MaterialTheme.typography.titleMedium)
            FlightGroundChoices(d.departureGroundMode, "departure") { event(MobileUiEvent.DepartureGroundMode(it)) }
            }
            Text("Airport to airport", style = MaterialTheme.typography.titleMedium)
            dev.localspoof.domain.FlightTransitMode.entries.forEach { transit ->
                val tag = if (transit == dev.localspoof.domain.FlightTransitMode.JUMP) "flight-jump" else "flight-timed"
                FilterChip(selected = transit == d.flightTransitMode, onClick = { event(MobileUiEvent.FlightTransitMode(transit)) },
                    label = { Text(if (transit == dev.localspoof.domain.FlightTransitMode.JUMP) "Jump flight · arrival airport" else "Timed flight", modifier = Modifier.fillMaxWidth().wrapContentHeight().testTag("$tag-label")) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag))
            }
            Text("Jump flight skips the airborne travel time. Travel to the airport, boarding, confirmed placements and airplane-mode changes still happen.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("flight-transit-explanation"))
            if (state.phone.snapshot.activePreview != null && state.remainingEdit == null) Text("These options stage your next preview. Use Jump to next stop in the live journey details to jump the current journey when available.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("flight-transit-staged"))
            if (d.flightTransitMode == dev.localspoof.domain.FlightTransitMode.TIMED) PrivateInput(d.flightSpeedText, "Plane speed (${state.preferences.speedUnit.label})", "flight-speed", numeric = true) { event(MobileUiEvent.Edit(MobileField.FLIGHT_SPEED, it)) }
            Text("To destination", style = MaterialTheme.typography.titleMedium)
            FlightGroundChoices(d.arrivalGroundMode, "arrival") { event(MobileUiEvent.ArrivalGroundMode(it)) }
            if (state.remainingEdit == null || state.remainingEdit.phase == FlightPhase.ORIGIN_GROUND) PrivateInput(d.departureId, "Departure airport ID (blank chooses nearest suitable)", "departure-airport") { event(MobileUiEvent.Edit(MobileField.DEPARTURE, it)) }
            if (state.remainingEdit == null || state.remainingEdit.phase in setOf(FlightPhase.ORIGIN_GROUND, FlightPhase.BOARDING, FlightPhase.AWAIT_AIRPLANE_MODE, FlightPhase.FLIGHT_INTERVAL)) PrivateInput(d.arrivalId, "Arrival airport ID (blank chooses nearest suitable)", "arrival-airport") { event(MobileUiEvent.Edit(MobileField.ARRIVAL, it)) }
            PrivateInput(d.airportQuery, "Find airport name or ID", "airport-search") { event(MobileUiEvent.Edit(MobileField.AIRPORT_QUERY, it)) }
            if (d.airportQuery.isNotBlank()) state.airports.filter { it.type == "large_airport" && it.scheduledService && !it.closed && (it.name.contains(d.airportQuery, true) || it.id.contains(d.airportQuery, true)) }.take(8).forEach { airport ->
                Text("${airport.id} · ${airport.name}")
                if (state.remainingEdit == null || state.remainingEdit.phase == FlightPhase.ORIGIN_GROUND) Action("Use as departure") { event(MobileUiEvent.AirportSelected(airport.id, true)) }
                if (state.remainingEdit == null || state.remainingEdit.phase in setOf(FlightPhase.ORIGIN_GROUND, FlightPhase.BOARDING, FlightPhase.AWAIT_AIRPLANE_MODE, FlightPhase.FLIGHT_INTERVAL)) Action("Use as arrival") { event(MobileUiEvent.AirportSelected(airport.id, false)) }
            }
            if (state.remainingEdit == null) Toggle(d.simulationOnly, "Simulation only; keep radio settings unchanged") { event(MobileUiEvent.SimulationOnly(it)) }
            Text("Driving legs use your selected online router or imported offline roads. Instant legs place you directly at their endpoint. Skipping the flight wait keeps the radio and confirmed placement steps. Guided mode pauses for your airplane-mode changes. No app compatibility guarantee.")
        }
        if (d.mode == Mode.DRIVE && state.remainingEdit == null) VacationOptions(state, event)
        if (d.mode == Mode.DRIVE && state.remainingEdit?.choices?.vacation != null) {
            Toggle(d.automaticFuelStops, "Recalculate reachable fuel stops") { event(MobileUiEvent.AutomaticFuelStops(it)) }
            Text("Uses current estimated fuel and remaining roads. Editing or moving an automatic stop pins it as your choice. Active refilling must finish before automatic recalculation.", style = MaterialTheme.typography.bodySmall)
        }
        d.stops.forEachIndexed { index, stop ->
            StopOptions(index, stop, state, event)
        }
        Button(enabled = !d.busy && d.speedError == null && d.flightSpeedError == null && mobileBoardingValid(d) && mobileDrivingValid(d), onClick = { event(MobileUiEvent.Preview) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mobile-preview")) { Text(if (d.busy) "Preparing…" else if (state.remainingEdit != null) "Preview remaining journey" else "Preview plan", softWrap = true) }
    }
    Panel("Offline map") {
        Action("Import offline GeoPack", "import-pack") { event(MobileUiEvent.ImportPack) }
        Text(state.phone.pack?.let { "${it.name}\n${it.attribution}\n${it.source}" } ?: "No offline pack loaded. Online planning on this phone does not require one.")
        OfflineMap(state.phone.pack, state.phone.phoneLocation?.point, preview?.geometry ?: emptyList(), layout.compactHeight, preview?.segments ?: emptyList())
        if ((state.phone.pack?.edges?.size ?: 0) > 20_000) Text("Preview draws at most 20,000 roads; routing uses the complete pack.")
        PrivateInput(d.placeQuery, "Search imported place names", "place-search") { event(MobileUiEvent.Edit(MobileField.PLACE_QUERY, it)) }
        if (d.placeQuery.isNotBlank()) state.phone.pack?.places?.filter { it.name.contains(d.placeQuery, true) }?.take(8)?.forEach { place -> Action(place.name) { event(MobileUiEvent.PlaceSelected(place.point)) } }
    }
    preview?.let { preview -> Panel(if (state.remainingEdit != null) "Candidate preview · not applied" else "Plan preview") {
        Text("${mobileDistanceLabel(preview.distanceMeters, state.preferences.distanceUnit)} · ${max(1L, (preview.durationMs + 59_999) / 60_000)} min" + if (preview.customPath) " · custom path" else "")
        if (preview.routeSource == RouteSource.ONLINE_ROUTER) Text("Online route · ${preview.routeProvider ?: "external router"}. Playback uses validated, snapped route endpoints.")
        if (preview.distanceMeters > 402336 && preview.mode != Mode.FLIGHT) Text("Over 250 miles. Airport journey offers a staged itinerary with ground route coverage.")
        if (preview.mode == Mode.FLIGHT) {
            Text("${if (preview.departureGroundMode == GroundLegMode.INSTANT) "Instant" else "Drive"} to departure · ${if (preview.flightTransitMode == dev.localspoof.domain.FlightTransitMode.JUMP) "jump flight to arrival airport" else "timed simulated flight"} · ${if (preview.arrivalGroundMode == GroundLegMode.INSTANT) "instant" else "drive"} to destination")
            Text("Roads are solid; flight is dashed; instant placements are endpoint markers. Radio and confirmed placement steps remain required.")
        }
        if (preview.stops.isNotEmpty()) Text("${preview.stops.size} planned stops · review and edit the stop list above before Start.")
        if (preview.stops.any { it.kind == StopKind.FUEL }) Text("Fuel station grade availability and estimated prices must be verified. Editing a suggested station pins your choice for the next preview.", style = MaterialTheme.typography.bodySmall)
        state.candidateBudget?.let { budget ->
            fun price(value: Double?) = value?.let { "${budget.currency} ${String.format(java.util.Locale.ROOT, "%.2f", it)}" } ?: "Unknown / partial"
            Text("Estimated fuel used ${String.format(java.util.Locale.ROOT, "%.1f", budget.consumedLiters)} L · arrival fuel ${String.format(java.util.Locale.ROOT, "%.1f", budget.finishFuelLiters)} L")
            Text("Fuel consumed value ${price(budget.consumedValue)} · refills paid ${price(budget.refillCash)} · hotels ${price(budget.hotels)}")
            Text("Estimates include route detours and stop durations. Fuel value and refill cash are separate; unknown fees or prices remain partial.", style = MaterialTheme.typography.bodySmall)
        }
        preview.originAirport?.let { Text("Departure airport: ${it.name}") }
        preview.destinationAirport?.let { Text("Arrival airport: ${it.name}") }
        preview.originAirportAccessDistanceMeters?.let { Text("Departure road access: ${it.toInt()} m from the catalog airport coordinate.") }
        preview.destinationAirportAccessDistanceMeters?.let { Text("Arrival road access: ${it.toInt()} m from the catalog airport coordinate.") }
    } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun FlightGroundChoices(selected: GroundLegMode, prefix: String, onChange: (GroundLegMode) -> Unit) {
    GroundLegMode.entries.forEach { mode ->
        val tag = "$prefix-${mode.name.lowercase()}"
        FilterChip(selected = selected == mode, onClick = { onChange(mode) },
            label = { Text(if (mode == GroundLegMode.DRIVE) "Drive" else "Instant", modifier = Modifier.fillMaxWidth().wrapContentHeight().testTag("$tag-label")) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag))
    }
}

@Composable private fun SettingsScreen(state: MobileUiState, event: (MobileUiEvent) -> Unit) {
    MobileUpdatesPanel(state, event)
    Panel("Units and defaults") {
        Text("Speed unit")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { MobileSpeedUnit.entries.forEach { unit -> FilterChip(selected = unit == state.preferences.speedUnit, onClick = { event(MobileUiEvent.SpeedUnit(unit)) }, label = { Text(unit.label) }, modifier = Modifier.heightIn(min = 48.dp)) } }
        Text("Distance unit")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { MobileDistanceUnit.entries.forEach { unit -> FilterChip(selected = unit == state.preferences.distanceUnit, onClick = { event(MobileUiEvent.DistanceUnit(unit)) }, label = { Text(if (unit == MobileDistanceUnit.MI) "Miles" else "Kilometers") }, modifier = Modifier.heightIn(min = 48.dp)) } }
        PrivateInput(state.walkDefaultText, "Default walking speed (${state.preferences.speedUnit.label})", "settings-walk-default", numeric = true) { event(MobileUiEvent.DefaultSpeed(Mode.WALK, it)) }
        PrivateInput(state.driveDefaultText, "Default driving speed (${state.preferences.speedUnit.label})", "settings-drive-default", numeric = true) { event(MobileUiEvent.DefaultSpeed(Mode.DRIVE, it)) }
        state.settingsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Unit changes preserve physical speed. Editing a matching default resets the draft speed and requires a new preview; active playback stays at its committed speed.")
        Action("Reset preferences", "settings-reset") { event(MobileUiEvent.ResetPreferences) }
        Text("Preferences are session only. Stop discards journey activity and retains these preferences until the process closes. Nothing is saved to disk.")
    }
    Panel("Online maps and search") {
        Text("Map tiles: OpenFreeMap / OpenStreetMap. Search: Photon / OpenStreetMap. These providers receive requests and may retain them. Only map/search run online here; worldwide road route planning currently runs in Windows.")
    }
    Panel("Advanced sensor privacy") {
        Text("On supported Samsung phones: Developer options → Quick settings developer tiles → Sensors Off. Then use the tile yourself. Availability depends on your phone software.", modifier = Modifier.testTag("sensors-off-guidance"))
        Text("This silences supported motion sensors, camera and ordinary microphone recording. It does not supply driving motion, change another app’s sensor readings to invented values, or disable every location/network source. Calls can still use the microphone. Restore the tile when finished; no automatic toggle is available here.")
        Action("Open Developer options", "sensor-developer-settings") { event(MobileUiEvent.DeveloperSettings) }
    }
    Panel("Phone setup and recovery") {
        Action("Open Developer options") { event(MobileUiEvent.DeveloperSettings) }
        Action("Grant location and notification permissions") { event(MobileUiEvent.Permissions) }
        Text("Select LocalSpoof as the mock location app and keep device location on. Playback uses a foreground notification. Review Android battery restrictions if your phone stops playback when its screen is off.")
        Action("Reset providers after a crash", "reset-providers", enabled = !state.phone.serviceRunning) { event(MobileUiEvent.ResetProviders) }
        Text("Stop the session before resetting providers. Stop releases mock overrides. Holding sends fresh samples at the destination. If provider recovery fails, deselect the mock app, toggle device location, or reboot.")
    }
}

@Composable private fun Toggle(value: Boolean, label: String, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = value, role = Role.Checkbox, onValueChange = changed), verticalAlignment = Alignment.CenterVertically) { Checkbox(value, null); Text(label, modifier = Modifier.weight(1f)) }
}
@Composable private fun Coordinates(lat: String, lon: String, prefix: String, columns: Boolean, event: (MobileUiEvent) -> Unit) {
    val latField = if (prefix == "origin") MobileField.ORIGIN_LAT else MobileField.DESTINATION_LAT
    val lonField = if (prefix == "origin") MobileField.ORIGIN_LON else MobileField.DESTINATION_LON
    if (columns) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) { PrivateInput(lat, "Latitude", "$prefix-lat", numeric = true) { event(MobileUiEvent.Edit(latField, it)) } }
        Box(Modifier.weight(1f)) { PrivateInput(lon, "Longitude", "$prefix-lon", numeric = true) { event(MobileUiEvent.Edit(lonField, it)) } }
    } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PrivateInput(lat, "Latitude", "$prefix-lat", numeric = true) { event(MobileUiEvent.Edit(latField, it)) }
        PrivateInput(lon, "Longitude", "$prefix-lon", numeric = true) { event(MobileUiEvent.Edit(lonField, it)) }
    }
}

private class PrivateEditText(context: android.content.Context) : EditText(context) { var applyingText = false }
/** External labels wrap, native inputs grow with text, and programmatic changes never emit edits. */
@Composable private fun PrivateInput(value: String, label: String, tag: String, numeric: Boolean = false, hideLabel: Boolean = false, changed: (String) -> Unit) {
    val callback by rememberUpdatedState(changed)
    val density = LocalDensity.current
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!hideLabel) Text(label, style = MaterialTheme.typography.bodyMedium)
        AndroidView(modifier = Modifier.fillMaxWidth().wrapContentHeight().heightIn(min = 56.dp), factory = { context ->
            PrivateEditText(context).apply {
                setTextColor(android.graphics.Color.WHITE)
                inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_DONE
                isSingleLine = false
                isSaveEnabled = false; isSaveFromParentEnabled = false
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                if (Build.VERSION.SDK_INT >= 30)
                    importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
                filters = arrayOf(android.text.InputFilter.LengthFilter(256))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (!applyingText) callback(s?.toString() ?: "") }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }
        }, update = { input ->
            input.contentDescription = label
            input.hint = if (hideLabel) label else ""
            input.setHintTextColor(android.graphics.Color.LTGRAY)
            input.minimumHeight = (56f * density.density).roundToInt()
            input.setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f * density.density * density.fontScale)
            if (input.text.toString() != value) {
                input.applyingText = true
                try { input.setText(value); input.setSelection(input.text.length) } finally { input.applyingText = false }
            }
        })
    }
}

@Composable private fun OfflineMap(pack: GeoPack?, point: GeoPoint?, route: List<GeoPoint>, compact: Boolean, segments: List<PlanSegment> = emptyList()) {
    val nodes = remember(pack) { pack?.nodes?.associateBy { it.id } ?: emptyMap() }
    val edges = remember(pack) { pack?.edges?.take(20_000) ?: emptyList() }
    Canvas(Modifier.fillMaxWidth().aspectRatio(if (compact) 2.8f else 1.8f).background(Color(0xff10272e)).semantics { contentDescription = "Offline route overview. Enter coordinates above to choose a location." }) {
        val bounds = pack?.bounds ?: GeoBounds(-90.0, -180.0, 90.0, 180.0)
        val widthDegrees = max(0.00001, if (bounds.east >= bounds.west) bounds.east - bounds.west else bounds.east + 360 - bounds.west)
        val heightDegrees = max(0.00001, bounds.north - bounds.south)
        fun project(p: GeoPoint): Offset {
            val x = if (bounds.east < bounds.west && p.lon < bounds.west) p.lon + 360 - bounds.west else p.lon - bounds.west
            return Offset((x / widthDegrees * size.width).toFloat(), ((bounds.north - p.lat) / heightDegrees * size.height).toFloat())
        }
        for (i in 1..5) {
            drawLine(Color(0xff28434a), Offset(size.width * i / 6f, 0f), Offset(size.width * i / 6f, size.height))
            drawLine(Color(0xff28434a), Offset(0f, size.height * i / 6f), Offset(size.width, size.height * i / 6f))
        }
        edges.forEach { edge -> val a = nodes[edge.from]?.point; val b = nodes[edge.to]?.point; if (a != null && b != null) drawLine(Color(0xff7599a3), project(a), project(b), 2f) }
        pack?.polygons?.forEach { polygon -> polygon.zipWithNext().forEach { (a, b) -> drawLine(Color(0xff386b66), project(a), project(b), 1f) } }
        if (segments.isEmpty()) route.take(20_000).zipWithNext().forEach { (a, b) -> drawLine(Color(0xff3478ed), project(a), project(b), 3f) }
        else segments.forEach { segment ->
            when (segment.kind) {
                SegmentKind.INSTANT -> segment.points.lastOrNull()?.let { drawCircle(Color(0xffb678ef), 6f, project(it)) }
                SegmentKind.DRIVE -> segment.points.take(20_000).zipWithNext().forEach { (a, b) -> drawLine(Color(0xff3478ed), project(a), project(b), 3f) }
                SegmentKind.FLIGHT -> {
                    val a = segment.points.first(); val b = segment.points.last()
                    val path = (0..64).map { interpolate(a, b, it / 64.0) }
                    path.zipWithNext().forEach { (first, last) -> if (kotlin.math.abs(first.lon - last.lon) <= 180) drawLine(Color(0xff9850d8), project(first), project(last), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))) }
                }
            }
        }
        point?.let { drawCircle(Color.White, 9f, project(it)); drawCircle(Color(0xff4b73ed), 7f, project(it)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun RemainingJourneyActions(state: MobileUiState, event: (MobileUiEvent) -> Unit) {
    val snapshot = state.phone.snapshot
    if (snapshot.activePreview == null) return
    Panel(if (state.remainingEdit != null) "Remaining journey edit" else "Journey controls") {
        if (state.phone.desktopEnabled && !state.phone.controllerApproved && snapshot.state in setOf(SessionState.ACTIVE, SessionState.PAUSED, SessionState.HOLDING)) Text("Phone playback continues. Reconnect and approve Windows to restore desktop control.")
        if (state.remainingEdit == null && snapshot.editId != null) Text("Windows is editing this journey. The approved route remains visible.")
        if (snapshot.timedPauseRemainingMs != null || state.remainingEdit != null || snapshot.editId != null) {
            Text("Holding playback to apply or undo an edit cancels the timed-resume countdown. A journey that was paused stays paused until you tap Resume.", modifier = Modifier.testTag("remaining-timed-pause-notice"), style = MaterialTheme.typography.bodySmall)
        }
        if (state.remainingEdit != null) {
            Text(if (state.remainingEdit.held != null) "Playback is held for this edit. Cancel retains the approved journey." else "Playback continues while you preview. Apply rebuilds from the latest confirmed phone position.")
            Action("Return to map to choose a destination or next stop", "remaining-map") { event(MobileUiEvent.Section(MobileSection.MAP)) }
            Action("Apply remaining journey", "remaining-apply", enabled = state.draft.preparedId != null && !state.draft.busy) { event(MobileUiEvent.ApplyRemainingEdit) }
            Action("Cancel edit · keep approved journey", "remaining-cancel") { event(MobileUiEvent.CancelRemainingEdit) }
        } else {
            Action("Change destination, stops or upcoming airport", "remaining-edit", enabled = snapshot.waitingFor !in setOf("AIRPLANE_MODE", "RESTORE_CONNECTIVITY", "PROVIDER_ACK", "ARRIVING") && snapshot.editId == null) { event(MobileUiEvent.BeginRemainingEdit()) }
            Action("Undo last journey edit from here", "remaining-undo", enabled = snapshot.canUndo && snapshot.waitingFor !in setOf("AIRPLANE_MODE", "RESTORE_CONNECTIVITY", "PROVIDER_ACK", "ARRIVING") && snapshot.editId == null) { event(MobileUiEvent.BeginRemainingEdit(true)) }
            var pauseOptions by remember { mutableStateOf(false) }
            Action(if (pauseOptions) "Hide timed stops" else "Pause here for…", "timed-pause-options", enabled = snapshot.state == SessionState.ACTIVE && snapshot.waitingFor == null) { pauseOptions = !pauseOptions }
            if (pauseOptions) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(60L, 300L, 900L).forEach { seconds -> TextButton(onClick = { event(MobileUiEvent.TimedPause(seconds)); pauseOptions = false }, modifier = Modifier.heightIn(min = 48.dp).testTag("timed-pause-$seconds")) { Text("${seconds / 60} min") } }
            }
            snapshot.timedPauseRemainingMs?.let { Text("Timed pause · ${(it + 999) / 1000} seconds remaining. Resume cancels the timer.", modifier = Modifier.testTag("timed-pause-countdown")); Action("Cancel timer · stay paused", "timed-pause-cancel") { event(MobileUiEvent.CancelTimedPause) } }
            if (snapshot.waitingFor in setOf("CHECK_IN", "HOTEL_STAY")) {
                Action("Leave hotel now", "hotel-leave-now") { event(MobileUiEvent.LeaveStop) }
            }
        }
        snapshot.stages.forEach { stage ->
            Text("${stage.label} · ${stage.status.lowercase().replace('_', ' ')}${stage.remainingMs?.let { " · ${(it + 59999) / 60000} min" } ?: ""}", style = MaterialTheme.typography.bodySmall)
        }
        snapshot.vacation?.let { budget ->
            Text("Estimated fuel ${String.format(java.util.Locale.ROOT, "%.1f", budget.remainingFuelLiters)} L · ${String.format(java.util.Locale.ROOT, "%.1f", budget.effectiveMpg)} US MPG")
            Text("Reserve-aware range ${mobileDistanceLabel(budget.estimatedRemainingRangeMeters, state.preferences.distanceUnit)}")
            fun price(value: Double?) = value?.let { "${budget.currency} ${String.format(java.util.Locale.ROOT, "%.2f", it)}" } ?: "Unknown / partial"
            Text("Fuel consumed value ${price(budget.consumedFuelValue)} · Refills paid ${price(budget.refillCash)}")
            Text("Hotels incurred ${price(budget.hotelCost)} · Constant consumption estimate. Unknown fees/prices remain partial.")
            ActiveVacationCorrection(state, event)
        }
        val phase = snapshot.phase?.name?.lowercase()?.replace('_', ' ') ?: snapshot.mode?.name?.lowercase() ?: "Journey"
        Text("${phase.replaceFirstChar { it.uppercase() }} · ${mobileEtaLabel(snapshot)}", modifier = Modifier.testTag("phone-stage-strip"), style = MaterialTheme.typography.bodySmall)
        Text("Plan worldwide roads here or in Windows. Online edits need connectivity; imported road coverage and custom paths remain explicit fallbacks.", style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun VacationOptions(state: MobileUiState, event: (MobileUiEvent) -> Unit) {
    val v = state.draft.vacation
    FilterChip(selected = v.enabled, onClick = { event(MobileUiEvent.Vacation(v.copy(enabled = !v.enabled))) }, label = { Text("Vacation") }, modifier = Modifier.heightIn(min = 48.dp).testTag("vacation-toggle"))
    if (!v.enabled) return
    Text("Vehicle and estimated fuel · gas/diesel", style = MaterialTheme.typography.titleMedium)
    Text("Choose a reference vehicle below or enter your own specifications. Missing tank sizes are never guessed. These are trip estimates, not vehicle telemetry.", style = MaterialTheme.typography.bodySmall)
    MobileVehiclePicker(state, event)
    PrivateInput(v.vehicle, "Vehicle description", "vacation-vehicle") { event(MobileUiEvent.Vacation(v.copy(vehicle = it))) }
    Toggle(v.metric, "Use litres, km and L/100 km") { event(MobileUiEvent.Vacation(v.copy(metric = it, tank = "", consumption = "", starting = "", reserve = "", price = ""))) }
    PrivateInput(v.tank, if (v.metric) "Tank capacity (litres)" else "Tank capacity (US gallons)", "vacation-tank", numeric = true) { event(MobileUiEvent.Vacation(v.copy(tank = it))) }
    PrivateInput(v.consumption, if (v.metric) "Estimated consumption (L/100 km)" else "Estimated efficiency (US MPG)", "vacation-consumption", numeric = true) { event(MobileUiEvent.Vacation(v.copy(consumption = it))) }
    Text("Starting fuel · required", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { MobileFuelInput.entries.forEach { method ->
        FilterChip(selected = v.startingMethod == method, onClick = { event(MobileUiEvent.Vacation(v.copy(startingMethod = method, starting = ""))) }, label = { Text(when (method) { MobileFuelInput.PERCENT -> "% tank"; MobileFuelInput.AMOUNT -> "Amount"; MobileFuelInput.RANGE -> "Range" }) }, modifier = Modifier.heightIn(min = 48.dp))
    } }
    PrivateInput(v.starting, when (v.startingMethod) { MobileFuelInput.PERCENT -> "Starting tank level (%)"; MobileFuelInput.AMOUNT -> if (v.metric) "Starting fuel (litres)" else "Starting fuel (US gallons)"; MobileFuelInput.RANGE -> if (v.metric) "Starting remaining range (km)" else "Starting remaining range (miles)" }, "vacation-starting", numeric = true) { event(MobileUiEvent.Vacation(v.copy(starting = it))) }
    if (v.startingMethod == MobileFuelInput.RANGE) Text("Range converts once to estimated fuel using your consumption input. Changing live speed does not refill the tank.", style = MaterialTheme.typography.bodySmall)
    PrivateInput(v.reserve, if (v.metric) "Keep reserve (litres)" else "Keep reserve (US gallons)", "vacation-reserve", numeric = true) { event(MobileUiEvent.Vacation(v.copy(reserve = it))) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { FuelGrade.entries.forEach { grade -> FilterChip(selected = v.grade == grade, onClick = { event(MobileUiEvent.Vacation(v.copy(grade = grade))) }, label = { Text(grade.name.lowercase().replaceFirstChar { it.uppercase() }) }, modifier = Modifier.heightIn(min = 48.dp)) } }
    PrivateInput(v.currency, "Currency code", "vacation-currency") { event(MobileUiEvent.Vacation(v.copy(currency = it))) }
    PrivateInput(v.price, if (v.metric) "Fuel price per litre (optional estimate)" else "Fuel price per US gallon (optional estimate)", "vacation-price", numeric = true) { event(MobileUiEvent.Vacation(v.copy(price = it))) }
    PrivateInput(v.hotelNightly, "Default hotel price per night (optional estimate)", "vacation-hotel-price", numeric = true) { event(MobileUiEvent.Vacation(v.copy(hotelNightly = it))) }
    PrivateInput(v.hotelNights, "Default hotel nights (explicit; independent of stop duration)", "vacation-hotel-nights", numeric = true) { event(MobileUiEvent.Vacation(v.copy(hotelNights = it))) }
    val parsed = runCatching { mobileVacationConfig(v) }
    if (parsed.isFailure) Text("Enter capacity, efficiency, starting fuel and reserve within capacity; prices must be nonnegative and currency a valid code.", color = MaterialTheme.colorScheme.error)
    Toggle(state.draft.automaticFuelStops, "Plan reachable fuel stops automatically") { event(MobileUiEvent.AutomaticFuelStops(it)) }
    Text("Automatic stations use online roads, access detours and your explicit starting fuel/reserve. Station grade availability and prices need verification. Constant consumption is editable; no high-speed efficiency curve is claimed.", style = MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun StopOptions(index: Int, stop: RouteStop, state: MobileUiState, event: (MobileUiEvent) -> Unit) {
    Text("${index + 1}. ${stop.label} · ${stop.kind.name.lowercase()}", style = MaterialTheme.typography.titleMedium)
    var editPlace by remember(stop.id) { mutableStateOf(false) }
    TextButton(onClick = { editPlace = !editPlace }, modifier = Modifier.heightIn(min = 48.dp).testTag("stop-$index-edit-place")) { Text(if (editPlace) "Close stop location" else "Change stop location / name") }
    if (editPlace) {
        var latitude by remember(stop.id) { mutableStateOf(stop.point.lat.toString()) }
        var longitude by remember(stop.id) { mutableStateOf(stop.point.lon.toString()) }
        var label by remember(stop.id) { mutableStateOf(stop.label) }
        PrivateInput(latitude, "Stop latitude", "stop-$index-lat", numeric = true) { latitude = it }
        PrivateInput(longitude, "Stop longitude", "stop-$index-lon", numeric = true) { longitude = it }
        PrivateInput(label, "Stop name", "stop-$index-name") { label = it }
        val point = runCatching { GeoPoint(latitude.toDouble(), longitude.toDouble()) }.getOrNull()
        Action("Use updated stop", "stop-$index-apply-place", enabled = point != null && label.isNotBlank() && label.length <= 120 && label.none { it.isISOControl() }) {
            event(MobileUiEvent.ConfigureStop(index, stop.copy(point = requireNotNull(point), label = label))); editPlace = false
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StopKind.entries.forEach { kind -> FilterChip(selected = stop.kind == kind, enabled = kind != StopKind.FUEL || runCatching { mobileVacationConfig(state.draft.vacation) }.getOrNull() != null, onClick = {
            val updated = when (kind) {
                StopKind.PLACE -> stop.copy(kind = kind, fuel = null, hotel = null, dwellMs = 0)
                StopKind.FUEL -> stop.copy(kind = kind, fuel = FuelStop(refillTargetLiters = requireNotNull(mobileVacationConfig(state.draft.vacation)).tankLiters), hotel = null, dwellMs = 300000)
                StopKind.HOTEL -> stop.copy(kind = kind, fuel = null, hotel = HotelStop(checkInMs = 900000, stayMs = 28800000, checkOutMs = 600000,
                    nights = state.draft.vacation.hotelNights.toIntOrNull()?.coerceIn(0, 365) ?: 0, nightlyPrice = state.draft.vacation.hotelNightly.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1_000_000.0 }, currency = state.draft.vacation.currency.takeIf { it.matches(Regex("[A-Z]{3}")) } ?: "USD"), dwellMs = 30300000)
            }; event(MobileUiEvent.ConfigureStop(index, updated))
        }, label = { Text(kind.name.lowercase().replaceFirstChar { it.uppercase() }) }, modifier = Modifier.heightIn(min = 48.dp)) }
    }
    if (stop.kind == StopKind.HOTEL) stop.hotel?.let { hotel ->
        fun change(updated: HotelStop) = event(MobileUiEvent.ConfigureStop(index, stop.copy(hotel = updated, dwellMs = updated.checkInMs + updated.stayMs + updated.checkOutMs)))
        PrivateInput(hotel.fees?.toString() ?: "", "Hotel fees (${hotel.currency}; optional estimate)", "hotel-$index-fees", numeric = true) { text -> if (text.isBlank()) change(hotel.copy(fees = null)) else text.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1_000_000.0 }?.let { fees -> change(hotel.copy(fees = fees)) } }
        listOf("Check-in minutes" to hotel.checkInMs, "Stay minutes" to hotel.stayMs, "Checkout minutes" to hotel.checkOutMs).forEachIndexed { stage, (label, ms) ->
            PrivateInput((ms / 60000).toString(), label, "hotel-$index-stage-$stage", numeric = true) { text -> text.toLongOrNull()?.takeIf { it in 0..(if (stage == 1) 43200 else 1440) }?.let { value -> change(when (stage) { 0 -> hotel.copy(checkInMs = value * 60000); 1 -> hotel.copy(stayMs = value * 60000); else -> hotel.copy(checkOutMs = value * 60000) }) } }
        }
        PrivateInput(hotel.nights.toString(), "Billable nights", "hotel-$index-nights", numeric = true) { it.toIntOrNull()?.takeIf { nights -> nights in 0..365 }?.let { nights -> change(hotel.copy(nights = nights)) } }
        PrivateInput(hotel.nightlyPrice?.toString() ?: "", "Nightly price (${hotel.currency}; optional estimate)", "hotel-$index-price", numeric = true) { text -> if (text.isBlank()) change(hotel.copy(nightlyPrice = null)) else text.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1_000_000.0 }?.let { price -> change(hotel.copy(nightlyPrice = price)) } }
        Text("Check-in, stay and checkout are held at this location. Leave now keeps the checkout stage.", style = MaterialTheme.typography.bodySmall)
    } else {
        PrivateInput((stop.dwellMs / 1000).toString(), "Stop duration (seconds)", "stop-dwell-$index", numeric = true) { value -> value.toLongOrNull()?.takeIf { it in 0..3600 }?.let { event(MobileUiEvent.StopDwell(index, it)) } }
        stop.fuel?.let { fuel ->
            PrivateInput(fuel.refillTargetLiters.toString(), "Refill target (litres)", "fuel-$index-target", numeric = true) { text -> text.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.1..2000.0 }?.let { amount -> event(MobileUiEvent.ConfigureStop(index, stop.copy(fuel = fuel.copy(refillTargetLiters = amount)))) } }
            PrivateInput(fuel.pricePerLiter?.toString() ?: "", "Station price per litre (${state.draft.vacation.currency}; estimate)", "fuel-$index-price", numeric = true) { text -> if (text.isBlank()) event(MobileUiEvent.ConfigureStop(index, stop.copy(fuel = fuel.copy(pricePerLiter = null)))) else text.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1000.0 }?.let { price -> event(MobileUiEvent.ConfigureStop(index, stop.copy(fuel = fuel.copy(pricePerLiter = price)))) } }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { event(MobileUiEvent.ReorderStop(index, -1)) }, enabled = index > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("Move earlier") }
        TextButton(onClick = { event(MobileUiEvent.ReorderStop(index, 1)) }, enabled = index + 1 < state.draft.stops.size, modifier = Modifier.heightIn(min = 48.dp)) { Text("Move later") }
        TextButton(onClick = { event(MobileUiEvent.RemoveStop(index)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove") }
    }
}

@Composable private fun ActiveVacationCorrection(state: MobileUiState, event: (MobileUiEvent) -> Unit) {
    val budget = state.phone.snapshot.vacation ?: return
    val config = state.phone.snapshot.activeChoices?.vacation ?: return
    var expanded by remember { mutableStateOf(false) }
    Action(if (expanded) "Close fuel correction" else "Correct estimated fuel / consumption", "vacation-correct-toggle", enabled = state.remainingEdit == null && state.phone.snapshot.editId == null) { expanded = !expanded }
    if (!expanded) return
    var fuel by remember { mutableStateOf(budget.remainingFuelLiters.toString()) }
    var consumption by remember { mutableStateOf(config.litersPer100Km.toString()) }
    var reserve by remember { mutableStateOf(config.reserveLiters.toString()) }
    PrivateInput(fuel, "Correct remaining fuel (litres)", "vacation-correct-fuel", numeric = true) { fuel = it }
    PrivateInput(consumption, "Estimated consumption (L/100 km)", "vacation-correct-consumption", numeric = true) { consumption = it }
    PrivateInput(reserve, "Reserve (litres)", "vacation-correct-reserve", numeric = true) { reserve = it }
    val f = fuel.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..config.tankLiters }
    val c = consumption.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.1..200.0 }
    val r = reserve.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..config.tankLiters }
    Text("This is an explicit estimate correction. Spent fuel and costs stay recorded in this session. The phone rejects changes that leave upcoming stops unreachable.", style = MaterialTheme.typography.bodySmall)
    Action("Apply fuel estimate correction", "vacation-correct-apply", enabled = f != null && c != null && r != null && state.phone.snapshot.editId == null) { event(MobileUiEvent.CorrectVacation(requireNotNull(f), requireNotNull(c), requireNotNull(r))) }
}

@Composable private fun MobileUpdatesPanel(state: MobileUiState, event: (MobileUiEvent) -> Unit) {
    val update = state.updateState
    val phone = state.phone
    val underlyingIdle = phone.snapshot.state == SessionState.IDLE && !phone.serviceRunning && !phone.desktopEnabled && !phone.simulationArmed && !phone.providerCleanupPending && phone.pendingUpdateRadioState == null
    val safe = underlyingIdle && (phone.updateBlockReason == null || update.step == MobileUpdateStep.CONFIRMATION && phone.updateInstallationPending)
    Panel("Updates") {
        Text("Installed ${state.installedVersion.ifBlank { "LocalSpoof" }}", modifier = Modifier.testTag("update-current-version"))
        Text("Manual checks use the public signed release feed. No account, background polling or journey data is sent. GitHub may keep request metadata.", style = MaterialTheme.typography.bodySmall)
        update.release?.let { release -> Text("Available ${release.version}", style = MaterialTheme.typography.titleMedium); Text(release.notes.take(8000)) }
        update.message?.let { Text(it, modifier = Modifier.testTag("update-message")) }
        update.progress?.let { Text("${it}%", modifier = Modifier.testTag("update-progress")); LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth()) }
        if (update.busy && update.progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        Action(if (update.step == MobileUpdateStep.CHECKING) "Checking…" else "Check for updates", "update-check", enabled = !update.busy && !update.blocksStart) { event(MobileUiEvent.CheckUpdate) }
        if (update.step in setOf(MobileUpdateStep.AVAILABLE, MobileUpdateStep.FAILED) && update.release != null)
            Action("Download verified update", "update-download", enabled = safe && !update.busy) { event(MobileUiEvent.DownloadUpdate) }
        if (update.step == MobileUpdateStep.PERMISSION) Action("Allow install source in Android settings", "update-permission", enabled = safe) { event(MobileUiEvent.UpdateInstallPermission) }
        if (update.step in setOf(MobileUpdateStep.READY, MobileUpdateStep.PERMISSION)) Action("Install update", "update-install", enabled = safe) { event(MobileUiEvent.InstallUpdate) }
        if (update.step == MobileUpdateStep.CONFIRMATION) Action("Continue installation", "update-confirm", enabled = safe) { event(MobileUiEvent.ContinueUpdate) }
        if (!safe) Text(phone.updateBlockReason ?: "Stop the journey and desktop control before downloading/installing. Paused travel, fuel/hotel stops and airplane-mode steps are still active.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("update-safe-gate"))
        phone.pendingUpdateRadioState?.let { expected ->
            Text("Restore airplane mode to ${if (expected) "On" else "Off"} before installing.")
            Action("Open airplane mode settings", "update-radio-settings") { event(MobileUiEvent.AirplaneSettings) }
            Action("Check restored radio state", "update-radio-confirm") { event(MobileUiEvent.ConfirmUpdateRadio) }
        }
        if (phone.providerCleanupPending) Action("Retry owned provider cleanup", "update-provider-cleanup") { event(MobileUiEvent.ResetProviders) }
        if (update.step !in setOf(MobileUpdateStep.IDLE, MobileUpdateStep.FINISHED)) Action("Cancel update", "update-cancel") { event(MobileUiEvent.CancelUpdate) }
        Text("Updates replace app binaries in place and preserve saved items. Playback never resumes automatically after installation.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun MobileVehiclePicker(state: MobileUiState, event: (MobileUiEvent) -> Unit) {
    val catalog = state.vehicleCatalog
    var expanded by remember { mutableStateOf(false) }
    var confirmPrices by remember { mutableStateOf(false) }
    LaunchedEffect(catalog.choices, catalog.busy) { if (catalog.choices.isNotEmpty() && !catalog.busy) expanded = true }
    Text("FuelEconomy.gov · US reference catalog", style = MaterialTheme.typography.bodySmall)
    Action(if (catalog.busy) "Loading reference data…" else "Choose year / make / model", "vehicle-catalog", enabled = !catalog.busy) { event(MobileUiEvent.VehicleLookup) }
    if (catalog.year.isNotBlank()) Text(listOf(catalog.year, catalog.make, catalog.model).filter { it.isNotBlank() }.joinToString(" · "))
    catalog.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    catalog.referenceMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (expanded && catalog.choices.isNotEmpty()) AlertDialog(onDismissRequest = { expanded = false }, title = { Text("Choose ${catalog.field}") }, text = {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) { catalog.choices.take(2000).forEach { (label, value) ->
            TextButton(onClick = { expanded = false; event(MobileUiEvent.VehicleSelection(catalog.field, value)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label, modifier = Modifier.fillMaxWidth()) }
        } }
    }, confirmButton = { TextButton(onClick = { expanded = false }) { Text("Cancel") } })
    Action("Use reference US fuel price", "vehicle-reference-price", enabled = !catalog.busy && state.draft.vacation.currency == "USD") {
        if (state.draft.vacation.price.isNotBlank()) confirmPrices = true else event(MobileUiEvent.ReferenceFuelPrices)
    }
    if (confirmPrices) AlertDialog(onDismissRequest = { confirmPrices = false }, title = { Text("Replace your entered fuel price?") }, text = { Text("Reference prices are estimates, not live station prices. This changes only the draft.") }, confirmButton = { TextButton(onClick = { confirmPrices = false; event(MobileUiEvent.ReferenceFuelPrices) }) { Text("Use reference price") } }, dismissButton = { TextButton(onClick = { confirmPrices = false }) { Text("Keep my price") } })
}

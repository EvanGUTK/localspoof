package dev.localspoof.domain

import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class SessionState { IDLE, READY, ACTIVE, PAUSED, HOLDING, ERROR }
enum class FlightPhase { ORIGIN_GROUND, ORIGIN_PLACEMENT, BOARDING, AWAIT_AIRPLANE_MODE, FLIGHT_INTERVAL, ARRIVAL_PLACEMENT, SETTLING, RESTORE_CONNECTIVITY, DESTINATION_GROUND, DESTINATION_PLACEMENT, COMPLETE }
enum class GroundLegMode { DRIVE, INSTANT }
enum class FlightTransitMode { TIMED, JUMP }
enum class SegmentRole { DEPARTURE, AIR, ARRIVAL }
enum class SegmentKind { DRIVE, INSTANT, FLIGHT }
data class PlanSegment(val role: SegmentRole, val kind: SegmentKind, val points: List<GeoPoint>, val durationMs: Long,
    val routeSource: RouteSource, val routeProvider: String? = null)

data class FlightOptions(
    val simulationOnly: Boolean = true,
    val boardingMs: Long = 30_000,
    val flightMs: Long? = null,
    val settlingMs: Long = 3_000,
    val groundMode: Mode = Mode.DRIVE,
    val originAirportId: String? = null,
    val destinationAirportId: String? = null,
    val departureGroundMode: GroundLegMode = GroundLegMode.DRIVE,
    val arrivalGroundMode: GroundLegMode = GroundLegMode.DRIVE,
    val flightTransitMode: FlightTransitMode = FlightTransitMode.TIMED,
) {
    init {
        require(boardingMs in 0..86_400_000 && (flightMs == null || flightMs in 1_000..604_800_000) && settlingMs in 1_000..60_000) { "INVALID_FLIGHT_DURATION" }
        require(groundMode == Mode.DRIVE) { "INVALID_GROUND_MODE" }
    }
}

const val MAX_DRIVE_SPEED_MPS = 56.0
enum class DrivingSpeedMode { FIXED, VARIABLE }
data class DrivingSpeedControls(
    val mode: DrivingSpeedMode = DrivingSpeedMode.FIXED,
    val driveMinMps: Double = 13.9,
    val driveMaxMps: Double = 13.9,
    val followRoadLimits: Boolean = true,
) {
    init {
        require(driveMinMps.isFinite() && driveMaxMps.isFinite() && driveMinMps in 0.1..MAX_DRIVE_SPEED_MPS && driveMaxMps in driveMinMps..MAX_DRIVE_SPEED_MPS) { "INVALID_DRIVE_RANGE" }
    }
    /** A slow continuous target, independent of how often a client polls or ticks. */
    fun cruiseTarget(elapsedMs: Long): Double = driveMinMps + (driveMaxMps - driveMinMps) * (0.5 + 0.5 * sin(elapsedMs / 1000.0 * (2.0 * Math.PI / 47.0)))
    fun estimatedCruiseMps(): Double = (driveMinMps + driveMaxMps) * 0.5
}

data class JourneySpeeds(val walkMps: Double = 1.4, val driveMps: Double = 13.9, val flightMps: Double = 230.0)
data class RouteStop(val point: GeoPoint, val dwellMs: Long = 0, val label: String = "Stop", val kind: StopKind = StopKind.PLACE, val fuel: FuelStop? = null, val hotel: HotelStop? = null, val id: String = UUID.randomUUID().toString()) {
    init { require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "INVALID_STOP_ID" }; require(dwellMs in 0..2_764_800_000L && label.isNotBlank() && label.length <= 120 && label.none { it.isISOControl() }) { "INVALID_STOP" }; require(kind == StopKind.HOTEL || dwellMs <= 3_600_000) { "INVALID_STOP_DWELL" }; require((kind == StopKind.FUEL) == (fuel != null) && (kind == StopKind.HOTEL) == (hotel != null)) { "INVALID_STOP_KIND" }; require(hotel == null || dwellMs == hotel.durationMs) { "HOTEL_DWELL_MISMATCH" } }
}
data class JourneyStopTarget(val point: GeoPoint, val label: String, val index: Int, val kind: StopKind = StopKind.PLACE)

data class PlanRequest(
    val mode: Mode,
    val origin: GeoPoint,
    val destination: GeoPoint,
    val speedMps: Double = 1.4,
    val customPath: List<GeoPoint>? = null,
    val flight: FlightOptions? = null,
    val waypointDwellsMs: Map<Int, Long> = emptyMap(),
    val onlineRoute: OnlineRoute? = null,
    val onlineFlightRoutes: OnlineFlightRoutes? = null,
    val flightSpeedMps: Double? = null,
    val stops: List<RouteStop> = emptyList(),
    val stopPointIndices: List<Int> = emptyList(),
    val speedControls: DrivingSpeedControls = DrivingSpeedControls(),
    val vacation: VacationConfig? = null,
    val remainingFlight: Boolean = false,
) {
    init {
        require(speedMps.isFinite() && speedMps in 0.1..MAX_DRIVE_SPEED_MPS) { "INVALID_SPEED" }
        require(mode != Mode.WALK || speedMps <= 3.0) { "INVALID_WALK_SPEED" }
        require(flightSpeedMps == null || mode == Mode.FLIGHT && flightSpeedMps.isFinite() && flightSpeedMps in 30.0..350.0) { "INVALID_FLIGHT_SPEED" }
        require(vacation == null || mode == Mode.DRIVE) { "VACATION_REQUIRES_DRIVE" }
        require(stops.none { it.kind == StopKind.FUEL } || vacation != null) { "FUEL_REQUIRES_VACATION" }
        require(stops.map { it.id }.distinct().size==stops.size) { "DUPLICATE_STOP_ID" }
        require(stops.size <= 32 && (stops.isEmpty() || mode in setOf(Mode.WALK, Mode.DRIVE))) { "INVALID_ROUTE_STOPS" }
        require(stopPointIndices.isEmpty() || onlineRoute != null && stopPointIndices.size == stops.size) { "INVALID_STOP_INDICES" }
        require(stops.isEmpty() || waypointDwellsMs.isEmpty()) { "CONFLICTING_STOP_DWELLS" }
        require(customPath == null || customPath.isNotEmpty() && customPath.size <= 100_000) { "INVALID_CUSTOM_PATH" }
        require(waypointDwellsMs.size <= 10_000 && waypointDwellsMs.all { it.key >= 0 && it.value in 0..86_400_000 }) { "INVALID_DWELL" }
        require(mode == Mode.FLIGHT || flight == null) { "UNEXPECTED_FLIGHT_OPTIONS" }
        require(mode != Mode.FLIGHT || customPath == null) { "FLIGHT_REQUIRES_GROUND_ROUTES" }
        require(onlineRoute == null || (mode == Mode.WALK || mode == Mode.DRIVE) && onlineRoute.profile == mode) { "UNEXPECTED_ONLINE_ROUTE" }
        require(onlineFlightRoutes == null || mode == Mode.FLIGHT) { "UNEXPECTED_ONLINE_FLIGHT_ROUTES" }
        require(customPath == null || onlineRoute == null && onlineFlightRoutes == null) { "CONFLICTING_ROUTE_SOURCES" }
        require(onlineFlightRoutes == null || (flight?.groundMode ?: Mode.DRIVE) == Mode.DRIVE) { "INVALID_ONLINE_FLIGHT_PROFILE" }
        onlineFlightRoutes?.let { legs ->
            require((remainingFlight || (legs.originGround != null) == ((flight?.departureGroundMode ?: GroundLegMode.DRIVE) == GroundLegMode.DRIVE)) &&
                (legs.destinationGround != null) == ((flight?.arrivalGroundMode ?: GroundLegMode.DRIVE) == GroundLegMode.DRIVE)) { "FLIGHT_LEG_GEOMETRY_MISMATCH" }
        }
    }
}

data class PlanPreview(
    val mode: Mode,
    val origin: GeoPoint,
    val destination: GeoPoint,
    val distanceMeters: Double,
    val durationMs: Long,
    val originAirport: Airport? = null,
    val destinationAirport: Airport? = null,
    val customPath: Boolean = false,
    val geometry: List<GeoPoint> = emptyList(),
    val simulationOnly: Boolean = true,
    val originAirportAccessPoint: GeoPoint? = null,
    val destinationAirportAccessPoint: GeoPoint? = null,
    val routeSource: RouteSource = RouteSource.OFFLINE_GEOPACK,
    val routeProvider: String? = null,
    val originAirportAccessDistanceMeters: Double? = null,
    val destinationAirportAccessDistanceMeters: Double? = null,
    val segments: List<PlanSegment> = emptyList(),
    val departureGroundMode: GroundLegMode? = null,
    val arrivalGroundMode: GroundLegMode? = null,
    val flightTransitMode: FlightTransitMode? = null,
    val stops: List<RouteStop> = emptyList(),
)
data class PreparedPlan(val id: String, val preview: PlanPreview)
data class Sample(
    val point: GeoPoint,
    val accuracyMeters: Double = 5.0,
    val speedMps: Double = 0.0,
    val bearingDegrees: Double = 0.0,
    val elapsedRealtimeMs: Long,
    val wallTimeMillis: Long,
    val placementGeneration: Long = 0,
)
data class SessionSnapshot(
    val revision: Long,
    val state: SessionState,
    val mode: Mode?,
    val point: GeoPoint?,
    val phase: FlightPhase?,
    val sample: Sample?,
    val error: String?,
    val preparedPreview: PlanPreview?,
    val elapsedMs: Long = 0,
    val remainingMs: Long? = null,
    val desiredAirplaneMode: Boolean? = null,
    val speeds: JourneySpeeds = JourneySpeeds(),
    val remainingMeters: Double? = null,
    val nextStop: JourneyStopTarget? = null,
    val waitingFor: String? = null,
    val speedControls: DrivingSpeedControls = DrivingSpeedControls(),
    val roadLimitMps: Double? = null,
    val activePreview: PlanPreview? = null,
    val activeChoices: PlanRequest? = null,
    val editId: String? = null,
    val canUndo: Boolean = false,
    val timedPauseRemainingMs: Long? = null,
    val stages: List<JourneyStage> = emptyList(),
    val vacation: VacationSnapshot? = null,
)

/** Holds only current state; callers supply monotonic ticks and submit returned samples. */
class Coordinator(
    airports: List<Airport> = emptyList(),
    pack: GeoPack? = null,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private data class Plan(
        val request: PlanRequest,
        val preview: PlanPreview,
        val first: RouteGeometry,
        val last: RouteGeometry? = null,
        val flightDurationMs: Long = 0,
        val initialFlightSpeedMps: Double = 230.0,
        val stopIndices: List<Int> = emptyList(),
        val dwells: Map<Int, Long> = request.waypointDwellsMs,
    )
    private data class Staged(val id: String, val revision: Long, val plan: Plan)
    private val lock = Any()
    @Volatile private var generation: Long = 0
    private var resources = pack?.frozen()
    private var airportResources = airports.toList()
    private var revision = 0L
    private var state = SessionState.IDLE
    private var active: Plan? = null
    private var staged: Staged? = null
    private var phase: FlightPhase? = null
    private var currentPoint: GeoPoint? = null
    private var latestSample: Sample? = null
    private var error: String? = null
    private var lastTick: Long? = null
    private var routeDistance = 0.0
    private var velocity = 0.0
    private var elapsed = 0L
    private var phaseElapsed = 0L
    private var dwellRemaining = 0.0
    private val consumedDwells = HashSet<Int>()
    private var observedRadio: Boolean? = null
    private var initialRadio: Boolean? = null
    private var changedRadio = false
    private var placementAcknowledged = false
    private var lastEmittedTimestamp = -1L
    private var speeds = JourneySpeeds()
    private var speedControls = DrivingSpeedControls()
    private var airDistance = 0.0
    private var pendingGroundJump: Double? = null
    private var placementGeneration = 0L
    private data class Edit(val id: String, val savedState: SessionState, val phase: FlightPhase?, val origin: GeoPoint, val generation: Long, val choices: PlanRequest)
    private var edit: Edit? = null
    private var undoChoices: PlanRequest? = null
    private var remainingStaged: Staged? = null
    private var timedPauseUntil: Long? = null
    private var ledger: VacationLedger? = null
    private var currentStopOrdinal: Int? = null
    private var currentStop: RouteStop? = null
    private var stopPlacementAcknowledged = false
    private val completedStops = HashSet<Int>()
    private val completedStopIds = HashSet<String>()
    private var stopElapsedMs = 0L
    private var acknowledgedPoint: GeoPoint? = null
    private var acknowledgedAt: Long? = null

    init {
        require(airports.size <= 100_000 && airports.all { it.id.length <= 128 && it.name.length <= 512 }) { "INVALID_AIRPORTS" }
    }

    fun prepareResources(pack: GeoPack?, airports: List<Airport>): PreparedResources {
        val copy = pack?.frozen()
        require(airports.size <= 100_000 && airports.all { it.id.length <= 128 && it.name.length <= 512 }) { "INVALID_AIRPORTS" }
        return PreparedResources(copy, airports.toList())
    }

    fun installResources(prepared: PreparedResources) {
        synchronized(lock) {
            edit?.let { state=it.savedState };edit=null;remainingStaged=null
            generation++
            staged = null
            resources = prepared.pack
            airportResources = prepared.airports
        }
    }

    fun setResources(pack: GeoPack?, airports: List<Airport>) = installResources(prepareResources(pack, airports))

    /** Capture a manual recovery instruction before Stop; never writes or automatically toggles radios. */
    fun recoveryAirplaneState(): Boolean? = synchronized(lock) {
        if (changedRadio && initialRadio != null && observedRadio != initialRadio) initialRadio else null
    }

    fun prepare(request: PlanRequest, expectedRevision: Long = snapshot().revision): PreparedPlan {
        val frozen = request.copy(customPath = request.customPath?.toList(), waypointDwellsMs = request.waypointDwellsMs.toMap(),
            onlineRoute = request.onlineRoute?.frozen(), onlineFlightRoutes = request.onlineFlightRoutes?.frozen(), stops = request.stops.toList(), stopPointIndices = request.stopPointIndices.toList())
        val context = synchronized(lock) {
            require(expectedRevision == revision) { "STALE_REVISION" }
            require(edit==null) { "EDIT_IN_PROGRESS" }
            Triple(resources, airportResources, generation)
        }
        val candidate = buildPlan(frozen, context.first, context.second, { generation != context.third })
        return synchronized(lock) {
            require(generation == context.third && expectedRevision == revision) { "STALE_PREPARATION" }
            val id = UUID.randomUUID().toString()
            staged = Staged(id, revision, candidate)
            PreparedPlan(id, candidate.preview)
        }
    }

    fun commit(id: String, expectedRevision: Long = snapshot().revision): SessionSnapshot = synchronized(lock) {
        val pending = staged ?: throw IllegalStateException("NO_PREPARED_PLAN")
        require(pending.plan.request.mode == Mode.INSTANT || state !in setOf(SessionState.ACTIVE, SessionState.PAUSED, SessionState.HOLDING)) { "USE_REMAINING_EDIT" }
        require(pending.id == id && pending.revision == expectedRevision && revision == expectedRevision) { "STALE_PREPARATION" }
        require(!changedRadio || observedRadio == initialRadio) { "RADIO_RECOVERY_REQUIRED" }
        val continueRunning = state == SessionState.ACTIVE || state == SessionState.HOLDING
        val continuePaused = state == SessionState.PAUSED
        active = pending.plan
        placementGeneration++
        staged = null
        generation++
        revision++
        state = if (continueRunning) SessionState.ACTIVE else if (continuePaused) SessionState.PAUSED else SessionState.READY
        resetPlayback()
        snapshotLocked()
    }

    private fun abandonEdit() { edit = null; remainingStaged = null; generation++ }

    /** Freeze at a successfully submitted fix before any network work. No renewed consent or placement. */
    fun beginRemainingEdit(nowMs: Long, useUndo: Boolean = false, expectedRevision: Long = snapshot().revision): RemainingEditContext = synchronized(lock) {
        require(expectedRevision == revision) { "STALE_REVISION" }
        val plan = active ?: throw IllegalArgumentException("NO_ACTIVE_PLAN")
        require(edit == null && pendingGroundJump == null && (currentStop == null || stopPlacementAcknowledged) && state in setOf(SessionState.ACTIVE, SessionState.PAUSED, SessionState.HOLDING)) { "EDIT_NOT_AVAILABLE" }
        require(phase !in setOf(FlightPhase.ORIGIN_PLACEMENT, FlightPhase.ARRIVAL_PLACEMENT, FlightPhase.DESTINATION_PLACEMENT)) { "PLACEMENT_PENDING" }
        val origin = acknowledgedPoint ?: throw IllegalArgumentException("CONFIRMED_LOCATION_REQUIRED")
        require(nowMs >= (acknowledgedAt ?: nowMs) && nowMs - (acknowledgedAt ?: 0) <= 3000 && distanceMeters(origin,currentPoint!!) < 0.1) { "CONFIRMED_LOCATION_STALE" }
        val choices = if (useUndo) (undoChoices ?: throw IllegalArgumentException("UNDO_NOT_AVAILABLE")).let { it.copy(stops=it.stops.filter { stop -> stop.id !in completedStopIds && stop.id != currentStop?.id },vacation=ledger?.config) } else choicesFromCurrent(plan)
        val previous = state
        generation++;revision++;staged = null;remainingStaged = null;timedPauseUntil = null
        val id=UUID.randomUUID().toString()
        edit=Edit(id,previous,phase,origin,generation,choices)
        state=SessionState.PAUSED;velocity=0.0;currentPoint=origin;lastTick=nowMs
        RemainingEditContext(id,revision,phase,origin,choices.copy(origin=origin),undoChoices != null)
    }

    /** Preliminary suffix preview while playback continues; this ID cannot be committed. */
    fun prepareRemainingDraft(request: PlanRequest, expectedRevision: Long = snapshot().revision): PreparedPlan {
        val context=synchronized(lock) {
            require(expectedRevision==revision && edit==null && active!=null && state in setOf(SessionState.ACTIVE,SessionState.PAUSED,SessionState.HOLDING)) { "DRAFT_NOT_AVAILABLE" }
            require(pendingGroundJump==null && phase !in setOf(FlightPhase.ORIGIN_PLACEMENT,FlightPhase.ARRIVAL_PLACEMENT,FlightPhase.DESTINATION_PLACEMENT)) { "PLACEMENT_PENDING" }
            Triple(Edit("draft",state,phase,request.origin,generation,request),resources,airportResources)
        }
        val candidate=buildRemainingPlan(request,context.first,context.second,context.third) { generation != context.first.generation }
        return synchronized(lock) {
            require(generation==context.first.generation && expectedRevision==revision && phase==context.first.phase && edit==null) { "STALE_PREPARATION" }
            PreparedPlan(UUID.randomUUID().toString(),candidate.preview)
        }
    }

    fun prepareRemaining(editId: String, request: PlanRequest, expectedRevision: Long = snapshot().revision): PreparedPlan {
        val context=synchronized(lock) {
            val e=edit ?: throw IllegalArgumentException("NO_REMAINING_EDIT")
            require(e.id==editId && expectedRevision==revision && e.generation==generation) { "STALE_EDIT" }
            require(distanceMeters(request.origin,e.origin)<0.01) { "EDIT_ORIGIN_MISMATCH" }
            Triple(e,resources,airportResources)
        }
        val candidate=buildRemainingPlan(request,context.first,context.second,context.third) { generation != context.first.generation }
        return synchronized(lock) {
            require(edit?.id==editId && generation==context.first.generation && expectedRevision==revision && phase==context.first.phase) { "STALE_EDIT" }
            val id=UUID.randomUUID().toString();remainingStaged=Staged(id,revision,candidate)
            PreparedPlan(id,candidate.preview)
        }
    }

    fun applyRemaining(editId: String, preparedId: String, nowMs: Long, expectedRevision: Long = snapshot().revision): SessionSnapshot = synchronized(lock) {
        val e=edit ?: throw IllegalArgumentException("NO_REMAINING_EDIT")
        val pending=remainingStaged ?: throw IllegalArgumentException("NO_PREPARED_PLAN")
        require(e.id==editId && pending.id==preparedId && pending.revision==revision && expectedRevision==revision && e.generation==generation && phase==e.phase) { "STALE_EDIT" }
        val old=active!!
        undoChoices=if(old.request.mode==Mode.INSTANT) null else choicesFromCurrent(old)
        active=pending.plan
        completedStopIds.retainAll((pending.plan.request.stops + (undoChoices?.stops ?: emptyList())).map { it.id }.toSet())
        // Remaining legs restart their local distance at the same confirmed point. Trip elapsed,
        // cruise controls, radio ownership and fuel/spending remain untouched.
        routeDistance=0.0;airDistance=0.0;consumedDwells.clear();completedStops.clear()
        currentStopOrdinal=null
        if(currentStop==null) { dwellRemaining=0.0;stopElapsedMs=0;stopPlacementAcknowledged=false }
        velocity=0.0
        if (old.request.mode!=Mode.FLIGHT || e.phase==FlightPhase.ORIGIN_GROUND) {
            phase=if(pending.plan.request.mode==Mode.FLIGHT) FlightPhase.ORIGIN_GROUND else null
            phaseElapsed=0
        } else if(e.phase==FlightPhase.COMPLETE) { phase=FlightPhase.DESTINATION_GROUND;phaseElapsed=0 }
        currentPoint=e.origin;lastTick=nowMs;error=null
        state=if(e.savedState==SessionState.PAUSED) SessionState.PAUSED else SessionState.ACTIVE
        edit=null;remainingStaged=null;staged=null;generation++;revision++
        snapshotLocked()
    }

    fun cancelRemaining(editId: String, nowMs: Long, expectedRevision: Long = snapshot().revision): SessionSnapshot = synchronized(lock) {
        val e=edit ?: throw IllegalArgumentException("NO_REMAINING_EDIT")
        require(e.id==editId && expectedRevision==revision && e.generation==generation) { "STALE_EDIT" }
        state=e.savedState;lastTick=nowMs;edit=null;remainingStaged=null;generation++;revision++
        snapshotLocked()
    }

    fun timedPause(durationMs: Long, nowMs: Long, expectedRevision: Long = snapshot().revision): SessionSnapshot = synchronized(lock) {
        require(expectedRevision==revision && durationMs in setOf(60_000L,300_000L,900_000L)) { "INVALID_TIMED_PAUSE" }
        require(edit==null && state==SessionState.ACTIVE && pendingGroundJump==null && phase !in setOf(FlightPhase.AWAIT_AIRPLANE_MODE,FlightPhase.RESTORE_CONNECTIVITY)) { "TIMED_PAUSE_NOT_AVAILABLE" }
        state=SessionState.PAUSED;velocity=0.0;error=null;timedPauseUntil=nowMs+durationMs;lastTick=nowMs
        revision++;generation++;staged=null
        snapshotLocked()
    }

    fun updateVacation(remainingFuelLiters: Double? = null, litersPer100Km: Double? = null, reserveLiters: Double? = null,
        expectedRevision: Long = snapshot().revision): SessionSnapshot = synchronized(lock) {
        require(expectedRevision==revision) { "STALE_REVISION" }
        require(edit==null && state in setOf(SessionState.ACTIVE,SessionState.PAUSED,SessionState.HOLDING)) { "VACATION_UPDATE_NOT_AVAILABLE" }
        val plan=active ?: throw IllegalArgumentException("NO_ACTIVE_PLAN")
        val estimates=ledger ?: throw IllegalArgumentException("NO_VACATION_PLAN")
        require(remainingFuelLiters!=null || litersPer100Km!=null || reserveLiters!=null) { "NO_VACATION_CHANGE" }
        val updated=estimates.config.copy(litersPer100Km=litersPer100Km ?: estimates.config.litersPer100Km,reserveLiters=reserveLiters ?: estimates.config.reserveLiters)
        val fuel=remainingFuelLiters ?: estimates.fuel
        require(fuel.isFinite() && fuel in updated.reserveLiters..updated.tankLiters) { "INVALID_REMAINING_FUEL" }
        var projectedFuel=fuel
        currentStop?.fuel?.let { refill -> require(refill.refillTargetLiters >= projectedFuel) { "INVALID_REFILL_TARGET" };projectedFuel=refill.refillTargetLiters }
        var previousDistance=routeDistance
        for(i in plan.request.stops.indices) {
            val stop=plan.request.stops[i]
            if(stop.id in completedStopIds || stop.id==currentStop?.id) continue
            val distance=plan.first.cumulativeMeters[plan.stopIndices[i]]
            if(distance < routeDistance-0.001) continue
            projectedFuel-=max(0.0,distance-previousDistance)*updated.litersPer100Km/100_000
            require(projectedFuel>=updated.reserveLiters-1e-7) { "UNREACHABLE_FUEL_STOP" }
            stop.fuel?.let { refill -> require(refill.refillTargetLiters>=projectedFuel && refill.refillTargetLiters<=updated.tankLiters) { "INVALID_REFILL_TARGET" };projectedFuel=refill.refillTargetLiters }
            previousDistance=distance
        }
        projectedFuel-=max(0.0,plan.first.distanceMeters-previousDistance)*updated.litersPer100Km/100_000
        require(projectedFuel>=updated.reserveLiters-1e-7) { "FUEL_RESERVE_EXHAUSTED" }
        estimates.config=updated;estimates.fuel=fuel
        active=plan.copy(request=plan.request.copy(vacation=updated))
        if(error=="FUEL_RESERVE_EXHAUSTED") error=null
        revision++;generation++;staged=null
        snapshotLocked()
    }

    fun leaveStop(expectedRevision: Long = snapshot().revision): SessionSnapshot = synchronized(lock) {
        require(expectedRevision==revision) { "STALE_REVISION" }
        val stop=currentStop
        require(stop?.kind==StopKind.HOTEL && stopPlacementAcknowledged && state in setOf(SessionState.ACTIVE,SessionState.PAUSED)) { "HOTEL_STOP_NOT_AVAILABLE" }
        val hotel=stop!!.hotel!!
        require(stopElapsedMs < hotel.checkInMs+hotel.stayMs) { "CHECKOUT_ALREADY_STARTED" }
        stopElapsedMs=hotel.checkInMs+hotel.stayMs;dwellRemaining=hotel.checkOutMs/1000.0
        if(dwellRemaining<=0) completeCurrentStop()
        revision++;generation++;staged=null
        snapshotLocked()
    }

    private fun choicesFromCurrent(plan: Plan): PlanRequest {
        val remaining = plan.request.stops.filterIndexed { i,stop -> stop.id != currentStop?.id && stop.id !in completedStopIds && i !in completedStops && (plan.stopIndices.getOrNull(i)?.let { plan.first.cumulativeMeters[it]>=routeDistance-0.001 } ?: true) }
        return plan.request.copy(origin=currentPoint ?: plan.request.origin,customPath=null,onlineRoute=null,onlineFlightRoutes=null,
            flight=plan.request.flight?.copy(originAirportId=plan.preview.originAirport?.id,destinationAirportId=plan.preview.destinationAirport?.id),
            waypointDwellsMs=emptyMap(),stops=remaining,stopPointIndices=emptyList(),speedMps=groundSpeed(plan),flightSpeedMps=if(plan.request.mode==Mode.FLIGHT) speeds.flightMps else null,speedControls=speedControls)
    }

    private fun buildRemainingPlan(request: PlanRequest, e: Edit, pack: GeoPack?, airports: List<Airport>, cancelled: () -> Boolean): Plan {
        val old=synchronized(lock) { active ?: throw IllegalArgumentException("NO_ACTIVE_PLAN") }
        require(request.vacation==old.request.vacation) { "ACTIVE_FUEL_ASSUMPTIONS_LOCKED" }
        if (old.request.mode!=Mode.FLIGHT) {
            require(request.mode in setOf(Mode.WALK,Mode.DRIVE)) { "INVALID_REMAINING_MODE" }
            require(old.request.vacation==null || request.mode==Mode.DRIVE) { "VACATION_REQUIRES_DRIVE" }
            val result=buildPlan(request,pack,airports,cancelled,currentStop?.fuel?.refillTargetLiters ?: ledger?.fuel)
            require(distanceMeters(result.first.points.first(),e.origin)<0.1) { "EDIT_ROUTE_START_MISMATCH" }
            validateVacation(request,result.first,result.stopIndices,currentStop?.fuel?.refillTargetLiters ?: ledger?.fuel)
            return result
        }
        require(request.mode==Mode.FLIGHT && request.flight != null) { "FLIGHT_MODE_LOCKED" }
        val originalOptions=old.request.flight!!
        require(request.flight.simulationOnly==originalOptions.simulationOnly) { "RADIO_POLICY_LOCKED" }
        if(e.phase==FlightPhase.ORIGIN_GROUND) return buildPlan(request,pack,airports,cancelled).also { require(distanceMeters(it.first.points.first(),e.origin)<0.1) { "EDIT_ROUTE_START_MISMATCH" } }
        require(request.flight.originAirportId==null || request.flight.originAirportId==old.preview.originAirport?.id) { "DEPARTURE_AIRPORT_LOCKED" }
        val beforeArrival=e.phase in setOf(FlightPhase.BOARDING,FlightPhase.AWAIT_AIRPLANE_MODE,FlightPhase.FLIGHT_INTERVAL)
        val arrivalId=request.flight.destinationAirportId ?: old.preview.destinationAirport!!.id
        require(beforeArrival || arrivalId==old.preview.destinationAirport!!.id) { "ARRIVAL_AIRPORT_LOCKED" }
        val airport=airports.firstOrNull { it.id==arrivalId && !it.closed && it.scheduledService && it.type=="large_airport" } ?: throw RoutePlanningException("NO_SUITABLE_AIRPORT")
        val supplied=request.onlineFlightRoutes
        require(supplied==null || supplied.arrivalAirportId==arrivalId && supplied.departureAirportId==old.preview.originAirport!!.id && supplied.originGround==null) { "INVALID_REMAINING_FLIGHT_ROUTES" }
        val routeStart=if(beforeArrival) airport.point else e.origin
        val final=if(request.flight.arrivalGroundMode==GroundLegMode.INSTANT) RouteGeometry(listOf(routeStart,request.destination))
            else supplied?.destinationGround?.geometry(routeStart,request.destination,Mode.DRIVE,maxOriginSnapMeters=if(beforeArrival) 5000.0 else 250.0)
                ?: if(distanceMeters(routeStart,request.destination)<0.01) RouteGeometry(listOf(routeStart)) else GraphRouter(pack ?: throw RoutePlanningException("MISSING_ROUTING_PACK")).route(routeStart,request.destination,Mode.DRIVE,speeds.driveMps,cancelled)
        val departure=if(e.phase==FlightPhase.FLIGHT_INTERVAL) e.origin else old.first.points.last()
        val first=RouteGeometry(listOf(departure))
        val airMeters=if(beforeArrival) distanceMeters(departure,final.points.first()) else 0.0
        val airMs=if(request.flight.flightTransitMode==FlightTransitMode.JUMP || !beforeArrival) 0L else ceil(airMeters/speeds.flightMps*1000).toLong()
        val groundMs=if(request.flight.arrivalGroundMode==GroundLegMode.INSTANT) 0 else estimateDuration(final,groundEstimateSpeed(old),speedControls.followRoadLimits)
        val segments=buildList {
            if(beforeArrival) add(PlanSegment(SegmentRole.AIR,SegmentKind.FLIGHT,listOf(departure,final.points.first()),airMs,RouteSource.FLIGHT_JOURNEY))
            add(PlanSegment(SegmentRole.ARRIVAL,if(request.flight.arrivalGroundMode==GroundLegMode.INSTANT) SegmentKind.INSTANT else SegmentKind.DRIVE,final.points,groundMs,if(supplied?.destinationGround!=null) RouteSource.ONLINE_ROUTER else RouteSource.OFFLINE_GEOPACK,supplied?.destinationGround?.provider))
        }
        val opts=request.flight.copy(originAirportId=old.preview.originAirport!!.id,destinationAirportId=airport.id,boardingMs=originalOptions.boardingMs,settlingMs=originalOptions.settlingMs)
        return Plan(request.copy(flight=opts),old.preview.copy(origin=e.origin,destination=request.destination,destinationAirport=airport,
            geometry=first.points+final.points,distanceMeters=airMeters+final.distanceMeters,durationMs=airMs+groundMs+(if(beforeArrival) originalOptions.settlingMs else 0),
            originAirportAccessPoint=departure,destinationAirportAccessPoint=final.points.first(),segments=segments,
            arrivalGroundMode=request.flight.arrivalGroundMode,flightTransitMode=request.flight.flightTransitMode),first,final,airMs,speeds.flightMps)
    }

    private fun validateVacation(request: PlanRequest, route: RouteGeometry, indices: List<Int>, startingFuel: Double? = null) {
        val config=request.vacation ?: return
        var fuel=startingFuel ?: config.startingFuelLiters
        var previous=0.0
        for(i in request.stops.indices) {
            val stop=request.stops[i];val distance=route.cumulativeMeters[indices[i]]
            fuel-=(distance-previous)*config.litersPer100Km/100_000
            require(fuel>=config.reserveLiters-1e-7) { "UNREACHABLE_FUEL_STOP" }
            stop.fuel?.let { refill -> require(refill.refillTargetLiters<=config.tankLiters && refill.refillTargetLiters>=fuel) { "INVALID_REFILL_TARGET" };fuel=refill.refillTargetLiters }
            previous=distance
        }
        fuel-=(route.distanceMeters-previous)*config.litersPer100Km/100_000
        require(fuel>=config.reserveLiters-1e-7) { "FUEL_RESERVE_EXHAUSTED" }
    }

    private fun beginStopAt(pointIndex: Int) {
        val ordinal=active?.stopIndices?.indexOf(pointIndex) ?: -1
        if(ordinal<0 || ordinal in completedStops) return
        currentStopOrdinal=ordinal;currentStop=active!!.request.stops[ordinal];stopPlacementAcknowledged=false;stopElapsedMs=0
    }
    private fun completeCurrentStop() {
        val stop=currentStop ?: return
        if(!stopPlacementAcknowledged) return
        currentStopOrdinal?.let { completedStops.add(it) }
        if(completedStopIds.add(stop.id)) ledger?.complete(stop)
        currentStop=null;currentStopOrdinal=null;stopElapsedMs=0;stopPlacementAcknowledged=false;dwellRemaining=0.0
    }
    private fun stopStage(): String? {
        val stop=currentStop ?: return null
        if(!stopPlacementAcknowledged) return "ARRIVING"
        val hotel=stop.hotel ?: return if(stop.kind==StopKind.FUEL) "REFUELING" else "STOP"
        return when { stopElapsedMs<hotel.checkInMs -> "CHECK_IN";stopElapsedMs<hotel.checkInMs+hotel.stayMs -> "HOTEL_STAY";else -> "CHECK_OUT" }
    }
    private fun stagesLocked(plan: Plan?): List<JourneyStage> {
        if(plan==null) return emptyList()
        if(plan.request.mode==Mode.FLIGHT) {
            val p=phase ?: FlightPhase.ORIGIN_GROUND
            val options=plan.request.flight!!
            val speed=groundEstimateSpeed(plan)
            val departure=if(p==FlightPhase.ORIGIN_GROUND) (if(options.departureGroundMode==GroundLegMode.INSTANT) 0 else routeRemainingMs(plan.first,routeDistance,speed))+options.boardingMs else if(p==FlightPhase.BOARDING) max(0,options.boardingMs-phaseElapsed) else 0
            val air=if(p>FlightPhase.FLIGHT_INTERVAL || options.flightTransitMode==FlightTransitMode.JUMP) 0 else ceil(max(0.0,flightDistance(plan)-airDistance)/speeds.flightMps*1000).toLong()
            val final=if(p==FlightPhase.COMPLETE || options.arrivalGroundMode==GroundLegMode.INSTANT) 0 else routeRemainingMs(plan.last!!,if(p==FlightPhase.DESTINATION_GROUND) routeDistance else 0.0,speed)
            return listOf(JourneyStage(plan.preview.originAirport?.name ?: "Departure airport","DRIVE",if(p>FlightPhase.BOARDING) "COMPLETE" else "ACTIVE",departure),
                JourneyStage("Flight","FLIGHT",if(p>FlightPhase.FLIGHT_INTERVAL) "COMPLETE" else if(p==FlightPhase.FLIGHT_INTERVAL) "ACTIVE" else "UPCOMING",if(p==FlightPhase.AWAIT_AIRPLANE_MODE) null else air),
                JourneyStage(plan.preview.destinationAirport?.name?.let { "From $it" } ?: "Destination","DRIVE",if(p==FlightPhase.COMPLETE) "COMPLETE" else if(p>=FlightPhase.DESTINATION_GROUND) "ACTIVE" else "UPCOMING",if(p==FlightPhase.RESTORE_CONNECTIVITY) null else final))
        }
        val result=mutableListOf<JourneyStage>()
        currentStop?.takeIf { held -> plan.request.stops.none { it.id==held.id } }?.let { result.add(JourneyStage(it.label,it.kind.name,stopStage() ?: "ACTIVE",ceil(dwellRemaining*1000).toLong())) }
        // One bounded route scan; do not rescan a 100k-point path for every stop on each live tick.
        val speed=groundEstimateSpeed(plan)
        val cumulativeSeconds=DoubleArray(plan.first.points.size)
        for(i in 0 until plan.first.points.lastIndex) {
            val cap=if(plan.request.mode!=Mode.WALK && speedControls.followRoadLimits) min(speed,plan.first.edgeSpeedLimits.getOrNull(i) ?: speed) else speed
            cumulativeSeconds[i+1]=cumulativeSeconds[i]+(plan.first.cumulativeMeters[i+1]-plan.first.cumulativeMeters[i])/cap
        }
        fun travelMs(a:Double,b:Double):Long {
            fun secondsAt(distance:Double):Double {
                if(plan.first.points.size<=1) return 0.0
                val index=plan.first.edgeIndex(distance)
                val cap=if(plan.request.mode!=Mode.WALK && speedControls.followRoadLimits) min(speed,plan.first.edgeSpeedLimits.getOrNull(index) ?: speed) else speed
                return cumulativeSeconds[index]+max(0.0,distance-plan.first.cumulativeMeters[index])/cap
            }
            return ceil(max(0.0,secondsAt(b)-secondsAt(a))*1000).toLong()
        }
        var previous=routeDistance
        var foundActive=false
        for(i in plan.request.stops.indices) {
            val stop=plan.request.stops[i]
            val stopDistance=plan.first.cumulativeMeters[plan.stopIndices[i]]
            val complete=stop.id in completedStopIds || i in completedStops || stopDistance<routeDistance-0.001
            val isCurrent=stop.id==currentStop?.id
            val travel=if(complete || isCurrent) 0 else travelMs(previous,stopDistance)
            val duration=if(complete) 0 else travel+if(isCurrent) ceil(dwellRemaining*1000).toLong() else stop.dwellMs
            val status=if(complete) "COMPLETE" else if(isCurrent) stopStage() ?: "ACTIVE" else if(!foundActive && currentStop==null) "ACTIVE" else "UPCOMING"
            result.add(JourneyStage(stop.label,stop.kind.name,status,duration))
            if(!complete) { foundActive=true;previous=max(previous,stopDistance) }
        }
        result.add(JourneyStage("Destination",plan.request.mode.name,if(state==SessionState.HOLDING) "COMPLETE" else if(!foundActive && currentStop==null) "ACTIVE" else "UPCOMING",if(state==SessionState.HOLDING) 0 else travelMs(previous,plan.first.distanceMeters)))
        return result
    }

    fun start(nowMs: Long): SessionSnapshot = synchronized(lock) {
        require(nowMs >= 0) { "INVALID_TIME" }
        require(active != null && state == SessionState.READY) { "NOT_READY" }
        state = SessionState.ACTIVE
        lastTick = nowMs
        initialRadio = observedRadio
        revision++
        snapshotLocked()
    }

    fun pause(): SessionSnapshot = synchronized(lock) {
        abandonEdit(); timedPauseUntil = null
        require(state == SessionState.ACTIVE || state == SessionState.HOLDING || state == SessionState.PAUSED) { "NOT_ACTIVE" }
        state = SessionState.PAUSED
        velocity = 0.0
        revision++
        staged = null
        generation++
        snapshotLocked()
    }

    fun pause(nowMs: Long): SessionSnapshot {
        require(nowMs >= 0) { "INVALID_TIME" }
        return pause()
    }

    fun resume(nowMs: Long): SessionSnapshot = synchronized(lock) {
        abandonEdit(); timedPauseUntil = null
        require(state == SessionState.PAUSED && nowMs >= (lastTick ?: 0L)) { "NOT_PAUSED_OR_INVALID_TIME" }
        require(active?.request?.flight?.simulationOnly != false || phase != FlightPhase.FLIGHT_INTERVAL || observedRadio == true) { "RADIO_CONFIRMATION_REQUIRED" }
        state = SessionState.ACTIVE
        lastTick = nowMs
        error = null
        revision++
        staged = null
        generation++
        snapshotLocked()
    }

    /** Changes targets only; route distance, elapsed time, dwell and radio state remain untouched. */
    fun setSpeeds(walkMps: Double? = null, driveMps: Double? = null, flightMps: Double? = null,
        expectedRevision: Long = snapshot().revision, drivingSpeedMode: DrivingSpeedMode? = null,
        driveMinMps: Double? = null, driveMaxMps: Double? = null, followRoadLimits: Boolean? = null): SessionSnapshot = synchronized(lock) {
        require(expectedRevision == revision) { "STALE_REVISION" }
        require(edit == null) { "EDIT_IN_PROGRESS" }
        require(active != null && state in setOf(SessionState.READY, SessionState.ACTIVE, SessionState.PAUSED, SessionState.HOLDING)) { "NO_ACTIVE_PLAN" }
        require(walkMps != null || driveMps != null || flightMps != null || drivingSpeedMode != null || driveMinMps != null || driveMaxMps != null || followRoadLimits != null) { "NO_SPEED_CHANGE" }
        require(walkMps == null || walkMps.isFinite() && walkMps in 0.1..3.0) { "INVALID_WALK_SPEED" }
        require(driveMps == null || driveMps.isFinite() && driveMps in 0.1..MAX_DRIVE_SPEED_MPS) { "INVALID_DRIVE_SPEED" }
        require(flightMps == null || flightMps.isFinite() && flightMps in 30.0..350.0) { "INVALID_FLIGHT_SPEED" }
        val controls = DrivingSpeedControls(drivingSpeedMode ?: speedControls.mode,
            driveMinMps ?: speedControls.driveMinMps, driveMaxMps ?: speedControls.driveMaxMps,
            followRoadLimits ?: speedControls.followRoadLimits)
        speedControls = controls
        speeds = JourneySpeeds(walkMps ?: speeds.walkMps, driveMps ?: speeds.driveMps, flightMps ?: speeds.flightMps)
        revision++; generation++; staged = null
        snapshotLocked()
    }

    /** A skip is a placement request, never a radio or provider acknowledgment. */
    fun jumpToNextStop(expectedRevision: Long = snapshot().revision): SessionSnapshot = synchronized(lock) {
        require(expectedRevision == revision) { "STALE_REVISION" }
        val plan = active ?: throw IllegalArgumentException("NO_ACTIVE_PLAN")
        require(edit == null) { "EDIT_IN_PROGRESS" }
        require(state == SessionState.ACTIVE && pendingGroundJump == null && dwellRemaining <= 0) { "JUMP_NOT_AVAILABLE" }
        if (plan.request.mode == Mode.FLIGHT) when (phase) {
            FlightPhase.ORIGIN_GROUND -> { currentPoint = plan.first.points.last(); placementAcknowledged = false; enterPhase(FlightPhase.ORIGIN_PLACEMENT) }
            FlightPhase.FLIGHT_INTERVAL -> {
                require(plan.request.flight!!.simulationOnly || observedRadio == true) { "RADIO_CONFIRMATION_REQUIRED" }
                airDistance = flightDistance(plan); currentPoint = plan.last!!.points.first(); placementAcknowledged = false; enterPhase(FlightPhase.ARRIVAL_PLACEMENT)
            }
            FlightPhase.DESTINATION_GROUND -> { currentPoint = plan.last!!.points.last(); placementAcknowledged = false; enterPhase(FlightPhase.DESTINATION_PLACEMENT) }
            else -> throw IllegalArgumentException("JUMP_NOT_AVAILABLE")
        } else {
            require(plan.request.mode in setOf(Mode.WALK, Mode.DRIVE)) { "JUMP_NOT_AVAILABLE" }
            val index = nextGroundStopIndex(plan) ?: throw IllegalArgumentException("JUMP_NOT_AVAILABLE")
            pendingGroundJump = plan.first.cumulativeMeters[index]
            currentPoint = plan.first.points[index]; velocity = 0.0
        }
        placementGeneration++
        revision++; generation++; staged = null
        snapshotLocked()
    }

    fun stop(): SessionSnapshot = synchronized(lock) {
        edit = null; remainingStaged = null; undoChoices = null; timedPauseUntil = null; ledger = null; currentStopOrdinal = null; currentStop = null; completedStops.clear(); completedStopIds.clear(); acknowledgedPoint = null; acknowledgedAt = null
        generation++
        revision++
        state = SessionState.IDLE
        active = null
        staged = null
        phase = null
        currentPoint = null
        latestSample = null
        lastTick = null
        routeDistance = 0.0
        velocity = 0.0
        elapsed = 0
        phaseElapsed = 0
        dwellRemaining = 0.0
        consumedDwells.clear()
        observedRadio = null
        initialRadio = null
        changedRadio = false
        placementAcknowledged = false
        pendingGroundJump = null; airDistance = 0.0; speeds = JourneySpeeds(); speedControls = DrivingSpeedControls()
        error = null
        snapshotLocked()
    }

    fun fail(code: String): SessionSnapshot = synchronized(lock) {
        require(code.matches(Regex("[A-Z_]{1,64}"))) { "INVALID_ERROR_CODE" }
        abandonEdit(); timedPauseUntil = null
        state = SessionState.ERROR
        error = code
        velocity = 0.0
        generation++
        staged = null
        revision++
        snapshotLocked()
    }

    fun observeRadio(enabled: Boolean): SessionSnapshot = synchronized(lock) {
        if(edit != null && observedRadio != null && observedRadio != enabled) {
            abandonEdit();revision++
            if(phase==FlightPhase.FLIGHT_INTERVAL && active?.request?.flight?.simulationOnly==false && !enabled) error="RADIO_CHANGED"
        }
        observedRadio = enabled
        if (initialRadio == null && (phase == FlightPhase.ORIGIN_GROUND || phase == FlightPhase.BOARDING)) initialRadio = enabled
        if (phase == FlightPhase.AWAIT_AIRPLANE_MODE && enabled && initialRadio == false) changedRadio = true
        if ((state == SessionState.ACTIVE || timedPauseUntil != null) && phase == FlightPhase.FLIGHT_INTERVAL && active?.request?.flight?.simulationOnly == false && !enabled) {
            state = SessionState.PAUSED;timedPauseUntil=null
            error = "RADIO_CHANGED"
            velocity = 0.0
            revision++
            staged = null
            generation++
        }
        snapshotLocked()
    }

    /** Called only after a requested, observed radio operation succeeded, never merely on command send. */
    fun acknowledgeRadioChange(enabled: Boolean): SessionSnapshot = synchronized(lock) {
        require(phase == FlightPhase.AWAIT_AIRPLANE_MODE || phase == FlightPhase.RESTORE_CONNECTIVITY) { "UNEXPECTED_RADIO_RESULT" }
        observedRadio = enabled
        if (phase == FlightPhase.AWAIT_AIRPLANE_MODE && enabled && initialRadio == false) changedRadio = true
        snapshotLocked()
    }

    fun acknowledgePlacement(success: Boolean): SessionSnapshot = synchronized(lock) {
        if (success) { acknowledgedPoint = latestSample?.point; acknowledgedAt = latestSample?.elapsedRealtimeMs }
        if (currentStop != null && !stopPlacementAcknowledged) {
            if (success) stopPlacementAcknowledged = true
            else { state = SessionState.ERROR; error = "PROVIDER_SUBMISSION_FAILED"; revision++; abandonEdit() }
        }
        if (pendingGroundJump != null) {
            if (success) {
                routeDistance = pendingGroundJump!!; pendingGroundJump = null
                active?.dwells?.entries?.firstOrNull { it.key !in consumedDwells && absDistance(active!!.first.cumulativeMeters[it.key] - routeDistance) < 1e-7 }?.let {
                    consumedDwells.add(it.key); dwellRemaining = it.value / 1000.0; beginStopAt(it.key); stopPlacementAcknowledged=true
                }
                if (routeDistance >= active!!.first.distanceMeters && dwellRemaining <= 0) { state = SessionState.HOLDING; revision++ }
            } else { pendingGroundJump = null; state = SessionState.ERROR; error = "PROVIDER_SUBMISSION_FAILED"; revision++ }
        } else if (phase in setOf(FlightPhase.ORIGIN_PLACEMENT, FlightPhase.ARRIVAL_PLACEMENT, FlightPhase.DESTINATION_PLACEMENT)) {
            if (success) placementAcknowledged = true else {
                state = SessionState.ERROR
                error = "PROVIDER_SUBMISSION_FAILED"
                revision++
            }
        }
        snapshotLocked()
    }

    fun tick(nowMs: Long): Sample? = synchronized(lock) {
        require(nowMs >= 0 && nowMs >= (lastTick ?: 0L)) { "NON_MONOTONIC_TIME" }
        val plan = active ?: return@synchronized null
        timedPauseUntil?.let { until ->
            if (nowMs >= until && state == SessionState.PAUSED && error == null) {
                timedPauseUntil = null; state = SessionState.ACTIVE; lastTick = nowMs; revision++
            }
        }
        if (state == SessionState.READY || state == SessionState.IDLE || state == SessionState.ERROR) return@synchronized null
        val previousPoint = currentPoint ?: plan.first.points.first()
        val delta = lastTick?.let { nowMs - it } ?: 0L
        lastTick = nowMs
        if (state == SessionState.ACTIVE && delta > 5_000) {
            state = SessionState.PAUSED
            error = "SCHEDULING_GAP"
            velocity = 0.0
            revision++
            staged = null
            generation++
        } else if (state == SessionState.ACTIVE && pendingGroundJump == null) {
            val awaitingExternalAction = phase in setOf(FlightPhase.ORIGIN_PLACEMENT, FlightPhase.AWAIT_AIRPLANE_MODE, FlightPhase.ARRIVAL_PLACEMENT, FlightPhase.RESTORE_CONNECTIVITY, FlightPhase.DESTINATION_PLACEMENT)
            if (!awaitingExternalAction) elapsed += delta
            if (plan.request.mode == Mode.FLIGHT) advanceFlight(plan, delta) else if (plan.request.mode == Mode.INSTANT) {
                currentPoint = plan.request.destination
                state = SessionState.HOLDING
                revision++
            } else {
                val distanceBefore = routeDistance
                advanceGround(plan.first, plan.request.copy(speedMps = groundSpeed(plan), stops = emptyList(), stopPointIndices = emptyList(), waypointDwellsMs = emptyMap()), delta,plan.dwells)
                try { ledger?.consume(routeDistance - distanceBefore) } catch (_: IllegalArgumentException) {
                    state = SessionState.PAUSED; error = "FUEL_RESERVE_EXHAUSTED"; velocity = 0.0; revision++
                }
                if (routeDistance >= plan.first.distanceMeters && dwellRemaining <= 0) { state = SessionState.HOLDING; revision++ }
            }
        }
        val point = currentPoint ?: previousPoint
        val moved = distanceMeters(previousPoint, point)
        val movingGeometry = if (state != SessionState.ACTIVE) null else when (phase) {
            null -> if (plan.request.mode == Mode.WALK || plan.request.mode == Mode.DRIVE) plan.first else null
            FlightPhase.ORIGIN_GROUND -> plan.first
            FlightPhase.DESTINATION_GROUND -> plan.last
            else -> null
        }
        // The provider's speed is instantaneous at this sample, not an interval average which
        // could exceed the current edge's cap after a transition to a slower road.
        val airborne = state == SessionState.ACTIVE && phase == FlightPhase.FLIGHT_INTERVAL && plan.request.flight?.flightTransitMode == FlightTransitMode.TIMED
        val speed = if (airborne) speeds.flightMps
            else if (movingGeometry == null) 0.0 else if (plan.request.mode != Mode.WALK && speedControls.followRoadLimits) min(velocity, movingGeometry.limitAt(routeDistance) ?: velocity) else velocity
        val heading = if (airborne && speed > 0) bearingDegrees(point, plan.last!!.points.first()) else movingGeometry?.takeIf { speed > 0 && it.points.size > 1 }?.let {
            bearingDegrees(point, it.points[it.edgeIndex(routeDistance) + 1])
        } ?: if (moved > 0.001) bearingDegrees(previousPoint, point) else latestSample?.bearingDegrees ?: 0.0
        val timestamp = max(nowMs, lastEmittedTimestamp + 1)
        lastEmittedTimestamp = timestamp
        Sample(point, speedMps = speed, bearingDegrees = heading,
            elapsedRealtimeMs = timestamp, wallTimeMillis = wallClock(), placementGeneration = placementGeneration).also { latestSample = it }
    }

    fun snapshot(): SessionSnapshot = synchronized(lock) { snapshotLocked() }

    private fun snapshotLocked(): SessionSnapshot {
        val plan = active
        val desired = if (edit==null && plan?.request?.flight?.simulationOnly == false) when (phase) {
            FlightPhase.AWAIT_AIRPLANE_MODE -> true
            FlightPhase.RESTORE_CONNECTIVITY -> if (changedRadio) initialRadio else null
            else -> null
        } else null
        val unknownArrival = phase in setOf(FlightPhase.ORIGIN_PLACEMENT, FlightPhase.AWAIT_AIRPLANE_MODE, FlightPhase.ARRIVAL_PLACEMENT, FlightPhase.RESTORE_CONNECTIVITY, FlightPhase.DESTINATION_PLACEMENT)
        val waiting = when {
            edit != null -> "REMAINING_EDIT"
            currentStop != null && !stopPlacementAcknowledged -> "PROVIDER_ACK"
            state == SessionState.PAUSED -> if (error == "SCHEDULING_GAP") "SCHEDULING_GAP" else "PAUSED"
            pendingGroundJump != null || phase in setOf(FlightPhase.ORIGIN_PLACEMENT, FlightPhase.ARRIVAL_PLACEMENT, FlightPhase.DESTINATION_PLACEMENT) -> "PROVIDER_ACK"
            phase == FlightPhase.AWAIT_AIRPLANE_MODE -> "AIRPLANE_MODE"
            phase == FlightPhase.RESTORE_CONNECTIVITY -> "RESTORE_CONNECTIVITY"
            dwellRemaining > 0 -> if(currentStop != null && currentStop!!.kind != StopKind.PLACE) stopStage() else "STOP_DWELL"
            else -> null
        }
        val remaining = plan?.let { remainingDuration(it) }
        return SessionSnapshot(revision, state, plan?.request?.mode, currentPoint, phase, latestSample, error,
            staged?.plan?.preview ?: plan?.preview, elapsed,
            if (unknownArrival || pendingGroundJump != null || state==SessionState.ERROR || state==SessionState.PAUSED && timedPauseUntil==null) null else remaining?.plus(timedPauseUntil?.let { max(0L,it-(lastTick ?: it)) } ?: 0L),
            desired, speeds, plan?.let { remainingDistance(it) }, plan?.let { nextStop(it) }, waiting, speedControls,
            plan?.let { when (phase) {
                null -> if (it.request.mode == Mode.DRIVE) it.first.mappedLimitAt(routeDistance) else null
                FlightPhase.ORIGIN_GROUND -> it.first.mappedLimitAt(routeDistance)
                FlightPhase.DESTINATION_GROUND -> it.last?.mappedLimitAt(routeDistance)
                else -> null
            } }, plan?.preview, plan?.let { choicesFromCurrent(it) }, edit?.id, undoChoices != null,
            timedPauseUntil?.let { max(0L, it - (lastTick ?: it)) }, stagesLocked(plan), ledger?.snapshot(stopStage()))
    }

    private fun groundSpeed(plan: Plan) = if (plan.request.mode == Mode.WALK) speeds.walkMps else if (speedControls.mode == DrivingSpeedMode.VARIABLE) speedControls.cruiseTarget(elapsed) else speeds.driveMps
    private fun groundEstimateSpeed(plan: Plan) = if (plan.request.mode == Mode.WALK) speeds.walkMps else if (speedControls.mode == DrivingSpeedMode.VARIABLE) speedControls.estimatedCruiseMps() else speeds.driveMps
    private fun flightDistance(plan: Plan) = plan.last?.let { distanceMeters(plan.first.points.last(), it.points.first()) } ?: 0.0
    private fun nextGroundStopIndex(plan: Plan): Int? = (plan.stopIndices + plan.dwells.keys + plan.first.points.lastIndex).distinct().sorted().firstOrNull {
        plan.first.cumulativeMeters[it] > routeDistance + 0.001 || it !in consumedDwells && (plan.dwells[it] ?: 0) > 0
    }
    private fun nextStop(plan: Plan): JourneyStopTarget? {
        if (state !in setOf(SessionState.READY, SessionState.ACTIVE, SessionState.PAUSED)) return null
        if (plan.request.mode == Mode.FLIGHT) return when (phase) {
            FlightPhase.ORIGIN_GROUND, FlightPhase.ORIGIN_PLACEMENT -> JourneyStopTarget(plan.first.points.last(), "Departure airport", 0)
            FlightPhase.BOARDING, FlightPhase.AWAIT_AIRPLANE_MODE, FlightPhase.FLIGHT_INTERVAL, FlightPhase.ARRIVAL_PLACEMENT -> JourneyStopTarget(plan.last!!.points.first(), "Arrival airport", 1)
            FlightPhase.SETTLING, FlightPhase.RESTORE_CONNECTIVITY, FlightPhase.DESTINATION_GROUND, FlightPhase.DESTINATION_PLACEMENT -> JourneyStopTarget(plan.last!!.points.last(), "Destination", 2)
            else -> null
        }
        currentStop?.let { stop -> return JourneyStopTarget(stop.point,stop.label,currentStopOrdinal ?: 0,stop.kind) }
        val index = nextGroundStopIndex(plan) ?: return null
        val stopOrdinal = plan.stopIndices.indexOf(index)
        return JourneyStopTarget(plan.first.points[index], plan.request.stops.getOrNull(stopOrdinal)?.label ?: "Destination", if (stopOrdinal >= 0) stopOrdinal else plan.request.stops.size,plan.request.stops.getOrNull(stopOrdinal)?.kind ?: StopKind.PLACE)
    }
    private fun routeRemainingMs(route: RouteGeometry, distance: Double, speed: Double): Long {
        var seconds = 0.0
        for (i in 0 until route.points.lastIndex) {
            val remaining = max(0.0, route.cumulativeMeters[i + 1] - max(distance, route.cumulativeMeters[i]))
            seconds += remaining / if (active?.request?.mode != Mode.WALK && speedControls.followRoadLimits) min(speed, route.edgeSpeedLimits.getOrNull(i) ?: speed) else speed
        }
        return ceil(seconds * 1000).toLong()
    }
    private fun remainingDistance(plan: Plan): Double {
        if (state == SessionState.HOLDING || phase == FlightPhase.COMPLETE || plan.request.mode == Mode.INSTANT) return 0.0
        if (plan.request.mode != Mode.FLIGHT) return max(0.0, plan.first.distanceMeters - routeDistance)
        return when (phase) {
            FlightPhase.ORIGIN_GROUND -> max(0.0,plan.first.distanceMeters-routeDistance) + flightDistance(plan) + plan.last!!.distanceMeters
            FlightPhase.ORIGIN_PLACEMENT -> flightDistance(plan) + plan.last!!.distanceMeters
            FlightPhase.BOARDING, FlightPhase.AWAIT_AIRPLANE_MODE -> flightDistance(plan) + plan.last!!.distanceMeters
            FlightPhase.FLIGHT_INTERVAL -> max(0.0,flightDistance(plan)-airDistance) + plan.last!!.distanceMeters
            FlightPhase.ARRIVAL_PLACEMENT, FlightPhase.SETTLING, FlightPhase.RESTORE_CONNECTIVITY -> plan.last!!.distanceMeters
            FlightPhase.DESTINATION_GROUND -> max(0.0,plan.last!!.distanceMeters-routeDistance)
            FlightPhase.DESTINATION_PLACEMENT -> 0.0
            else -> 0.0
        }
    }
    private fun remainingDuration(plan: Plan): Long {
        if (state == SessionState.HOLDING || phase == FlightPhase.COMPLETE || plan.request.mode == Mode.INSTANT) return 0
        val speed = groundEstimateSpeed(plan)
        if (plan.request.mode != Mode.FLIGHT) return routeRemainingMs(plan.first,routeDistance,speed) + ceil(dwellRemaining*1000).toLong() + plan.dwells.filterKeys { it !in consumedDwells }.values.sum()
        val options = plan.request.flight!!
        val air = if (options.flightTransitMode == FlightTransitMode.JUMP) 0 else ceil(max(0.0,flightDistance(plan)-airDistance)/speeds.flightMps*1000).toLong()
        val final = if (options.arrivalGroundMode == GroundLegMode.INSTANT) 0 else routeRemainingMs(plan.last!!, if (phase == FlightPhase.DESTINATION_GROUND) routeDistance else 0.0,speed)
        return when (phase) {
            FlightPhase.ORIGIN_GROUND, FlightPhase.ORIGIN_PLACEMENT -> (if (options.departureGroundMode == GroundLegMode.INSTANT) 0 else routeRemainingMs(plan.first,routeDistance,speed)) + options.boardingMs + air + options.settlingMs + final
            FlightPhase.BOARDING -> max(0,options.boardingMs-phaseElapsed)+air+options.settlingMs+final
            FlightPhase.AWAIT_AIRPLANE_MODE, FlightPhase.FLIGHT_INTERVAL -> air+options.settlingMs+final
            FlightPhase.ARRIVAL_PLACEMENT -> options.settlingMs+final
            FlightPhase.SETTLING -> max(0,options.settlingMs-phaseElapsed)+final
            FlightPhase.RESTORE_CONNECTIVITY, FlightPhase.DESTINATION_GROUND -> final
            else -> 0
        }
    }

    private fun resetPlayback() {
        edit = null; remainingStaged = null; undoChoices = null; timedPauseUntil = null
        ledger = active?.request?.vacation?.let(::VacationLedger)
        currentStopOrdinal = null; currentStop = null; stopPlacementAcknowledged = false; completedStops.clear(); completedStopIds.clear(); stopElapsedMs = 0L
        acknowledgedPoint = null; acknowledgedAt = null
        routeDistance = 0.0
        velocity = 0.0
        elapsed = 0
        phaseElapsed = 0
        dwellRemaining = 0.0
        pendingGroundJump = null; airDistance = 0.0
        consumedDwells.clear()
        latestSample = null
        lastTick = null
        error = null
        changedRadio = false
        placementAcknowledged = false
        initialRadio = observedRadio
        val plan = active!!
        speedControls = plan.request.speedControls
        speeds = JourneySpeeds(walkMps = if (plan.request.mode == Mode.WALK) plan.request.speedMps else 1.4,
            driveMps = if (plan.request.mode in setOf(Mode.DRIVE, Mode.FLIGHT)) plan.request.speedMps else 13.9,
            flightMps = plan.initialFlightSpeedMps)
        phase = if (plan.request.mode == Mode.FLIGHT) FlightPhase.ORIGIN_GROUND else null
        currentPoint = if (plan.request.mode == Mode.INSTANT) plan.request.destination else plan.first.points.first()
    }

    private fun buildPlan(request: PlanRequest, pack: GeoPack?, airports: List<Airport>, cancelled: () -> Boolean, startingFuel: Double? = null): Plan {
        fun ground(a: GeoPoint, b: GeoPoint, mode: Mode): RouteGeometry {
            if (distanceMeters(a, b) < 0.01) return RouteGeometry(listOf(a))
            return GraphRouter(pack ?: throw RoutePlanningException("MISSING_ROUTING_PACK")).route(a, b, mode, request.speedMps, cancelled)
        }
        if (request.mode == Mode.INSTANT) {
            return Plan(request, PlanPreview(request.mode, request.destination, request.destination, 0.0, 0, geometry = listOf(request.destination), routeSource = RouteSource.INSTANT_LOCATION), RouteGeometry(listOf(request.destination)))
        }
        if (request.mode != Mode.FLIGHT) {
            val route = request.customPath?.let { points ->
                require(distanceMeters(points.first(), request.origin) < 0.01 && distanceMeters(points.last(), request.destination) < 0.01) { "CUSTOM_PATH_ENDPOINT_MISMATCH" }
                require(request.waypointDwellsMs.keys.all { it in points.indices }) { "INVALID_DWELL_INDEX" }
                RouteGeometry(points.toList())
            } ?: request.onlineRoute?.geometry(request.origin, request.destination, request.mode) ?: run {
                val points = listOf(request.origin) + request.stops.map { it.point } + request.destination
                val legs = points.zipWithNext().map { (a, b) -> ground(a, b, request.mode) }
                val joined = mutableListOf<GeoPoint>(); val limits = mutableListOf<Double?>(); val sources = mutableListOf<SpeedLimitSource>()
                for (leg in legs) {
                    require(joined.isEmpty() || distanceMeters(joined.last(), leg.points.first()) < 0.01) { "STOP_ROUTE_DISCONTINUITY" }
                    if (joined.isEmpty()) joined.addAll(leg.points) else joined.addAll(leg.points.drop(1))
                    limits.addAll((0 until leg.points.lastIndex).map { leg.edgeSpeedLimits.getOrNull(it) })
                    sources.addAll((0 until leg.points.lastIndex).map { leg.edgeSpeedLimitSources.getOrNull(it) ?: SpeedLimitSource.UNKNOWN })
                }
                RouteGeometry(joined, limits, sources)
            }
            var previousStopIndex = 0
            val indices = request.stops.mapIndexed { ordinal, stop ->
                val index = if (request.onlineRoute != null) request.stopPointIndices.getOrNull(ordinal) ?: throw IllegalArgumentException("MISSING_STOP_INDICES")
                    else ((previousStopIndex + 1) until route.points.lastIndex).minByOrNull { distanceMeters(route.points[it], stop.point) } ?: throw IllegalArgumentException("STOP_ENDPOINT_MISMATCH")
                require(index > previousStopIndex && index < route.points.lastIndex && distanceMeters(route.points[index], stop.point) <= if (request.customPath != null) 0.01 else 250.0) { "STOP_ENDPOINT_MISMATCH" }
                previousStopIndex = index; index
            }
            val dwells = if (request.stops.isEmpty()) request.waypointDwellsMs else indices.zip(request.stops).associate { (index, stop) -> index to stop.dwellMs }
            require(dwells.keys.all { it in route.points.indices }) { "INVALID_DWELL_INDEX" }
            validateVacation(request, route, indices,startingFuel)
            val duration = estimateDuration(route, if (request.mode != Mode.WALK && request.speedControls.mode == DrivingSpeedMode.VARIABLE) request.speedControls.estimatedCruiseMps() else request.speedMps, request.mode != Mode.WALK && request.speedControls.followRoadLimits) + dwells.values.sum()
            val source = if (request.customPath != null) RouteSource.CUSTOM_PATH else if (request.onlineRoute != null) RouteSource.ONLINE_ROUTER else RouteSource.OFFLINE_GEOPACK
            return Plan(request, PlanPreview(request.mode, route.points.first(), route.points.last(), route.distanceMeters, duration, customPath = request.customPath != null, geometry = route.points,
                routeSource = source, routeProvider = request.onlineRoute?.provider, stops = request.stops), route, stopIndices = indices, dwells = dwells)
        }
        val options = request.flight ?: FlightOptions()
        val onlineLegs = request.onlineFlightRoutes
        require(onlineLegs == null || (options.originAirportId == null || options.originAirportId == onlineLegs.departureAirportId) &&
            (options.destinationAirportId == null || options.destinationAirportId == onlineLegs.arrivalAirportId)) { "ONLINE_AIRPORT_SELECTION_MISMATCH" }
        require(options.groundMode != Mode.WALK || request.speedMps <= 3.0) { "INVALID_WALK_SPEED" }
        val departureId = onlineLegs?.departureAirportId ?: options.originAirportId
        val arrivalId = onlineLegs?.arrivalAirportId ?: options.destinationAirportId
        val starts = airportCandidates(if (departureId == null) airports else airports.filter { it.id == departureId }, request.origin)
        val ends = airportCandidates(if (arrivalId == null) airports else airports.filter { it.id == arrivalId }, request.destination)
        if (starts.isEmpty() || ends.isEmpty()) throw RoutePlanningException("NO_SUITABLE_AIRPORT")
        var lastFailure = "NO_GROUND_ROUTE"
        for (start in starts) for (end in ends) {
            if (cancelled()) throw RoutePlanningException("PREPARATION_CANCELLED")
            if (start.id == end.id) continue
            try {
                val first = if (options.departureGroundMode == GroundLegMode.INSTANT) RouteGeometry(listOf(request.origin, start.point))
                    else onlineLegs?.originGround?.geometry(request.origin, start.point, Mode.DRIVE, maxDestinationSnapMeters = 5_000.0) ?: ground(request.origin, start.point, options.groundMode)
                val last = if (options.arrivalGroundMode == GroundLegMode.INSTANT) RouteGeometry(listOf(end.point, request.destination))
                    else onlineLegs?.destinationGround?.geometry(end.point, request.destination, Mode.DRIVE, maxOriginSnapMeters = 5_000.0) ?: ground(end.point, request.destination, options.groundMode)
                // Online surface access points identify the air-leg boundaries; airport catalog
                // centroids are labels and must not insert a hidden connector/placement jump.
                val flightDistance = distanceMeters(first.points.last(), last.points.first())
                val effectiveFlightSpeed = request.flightSpeedMps ?: if (options.flightTransitMode == FlightTransitMode.TIMED && options.flightMs != null) flightDistance / (options.flightMs / 1000.0) else 230.0
                require(effectiveFlightSpeed.isFinite() && effectiveFlightSpeed in 30.0..350.0) { "INVALID_FLIGHT_SPEED" }
                val flightMs = if (options.flightTransitMode == FlightTransitMode.JUMP) 0L else request.flightSpeedMps?.let { max(1_000L, ceil(flightDistance / it * 1000).toLong()) } ?: options.flightMs ?: max(1_000L, ceil(flightDistance / 230.0 * 1000).toLong())
                val firstMs = if (options.departureGroundMode == GroundLegMode.INSTANT) 0L else estimateDuration(first, if (request.speedControls.mode == DrivingSpeedMode.VARIABLE) request.speedControls.estimatedCruiseMps() else request.speedMps, request.speedControls.followRoadLimits)
                val lastMs = if (options.arrivalGroundMode == GroundLegMode.INSTANT) 0L else estimateDuration(last, if (request.speedControls.mode == DrivingSpeedMode.VARIABLE) request.speedControls.estimatedCruiseMps() else request.speedMps, request.speedControls.followRoadLimits)
                val duration = firstMs + options.boardingMs + flightMs + options.settlingMs + lastMs
                fun segment(role: SegmentRole, mode: GroundLegMode, route: RouteGeometry, millis: Long, online: OnlineRoute?) = PlanSegment(
                    role, if (mode == GroundLegMode.INSTANT) SegmentKind.INSTANT else SegmentKind.DRIVE, route.points.toList(), millis,
                    if (mode == GroundLegMode.INSTANT) RouteSource.INSTANT_LOCATION else if (online != null) RouteSource.ONLINE_ROUTER else RouteSource.OFFLINE_GEOPACK,
                    online?.provider)
                val segments = listOf(segment(SegmentRole.DEPARTURE, options.departureGroundMode, first, firstMs, onlineLegs?.originGround),
                    PlanSegment(SegmentRole.AIR, SegmentKind.FLIGHT, listOf(first.points.last(), last.points.first()), flightMs, RouteSource.FLIGHT_JOURNEY),
                    segment(SegmentRole.ARRIVAL, options.arrivalGroundMode, last, lastMs, onlineLegs?.destinationGround))
                return Plan(request.copy(flight = options), PlanPreview(Mode.FLIGHT, first.points.first(), last.points.last(), first.distanceMeters + flightDistance + last.distanceMeters,
                    duration, start, end, geometry = first.points + last.points, simulationOnly = options.simulationOnly,
                    originAirportAccessPoint = first.points.last(), destinationAirportAccessPoint = last.points.first(),
                    routeSource = RouteSource.FLIGHT_JOURNEY,
                    routeProvider = listOfNotNull(onlineLegs?.originGround?.provider, onlineLegs?.destinationGround?.provider).distinct().singleOrNull(),
                    originAirportAccessDistanceMeters = distanceMeters(start.point, first.points.last()),
                    destinationAirportAccessDistanceMeters = distanceMeters(end.point, last.points.first()), segments = segments,
                    departureGroundMode = options.departureGroundMode, arrivalGroundMode = options.arrivalGroundMode, flightTransitMode = options.flightTransitMode), first, last, flightMs, effectiveFlightSpeed)
            } catch (failure: RoutePlanningException) {
                if (failure.code == "PREPARATION_CANCELLED") throw failure
                lastFailure = failure.code
            }
        }
        throw RoutePlanningException(lastFailure)
    }

    private fun estimateDuration(route: RouteGeometry, speed: Double, followRoadLimits: Boolean = true): Long {
        var seconds = 0.0
        for (i in 0 until route.points.lastIndex) seconds += (route.cumulativeMeters[i + 1] - route.cumulativeMeters[i]) / if (followRoadLimits) min(speed, route.edgeSpeedLimits.getOrNull(i) ?: speed) else speed
        return ceil(seconds * 1000).toLong()
    }

    private fun advanceGround(route: RouteGeometry, request: PlanRequest, deltaMs: Long, dwells: Map<Int,Long> = request.waypointDwellsMs) {
        var remainingSeconds = deltaMs / 1000.0
        val pendingDwells = dwells.entries.filter { it.key !in consumedDwells && it.key < route.cumulativeMeters.size }.sortedBy { it.key }
        var nextDwellIndex = 0
        while (remainingSeconds > 1e-9) {
            val step = min(0.1, remainingSeconds)
            if (currentStop != null && !stopPlacementAcknowledged) { velocity = 0.0; break }
            if(currentStop != null && dwellRemaining <= 0) completeCurrentStop()
            if (dwellRemaining > 0) {
                val consumed = min(step, dwellRemaining)
                dwellRemaining -= consumed
                stopElapsedMs += (consumed * 1000).toLong()
                remainingSeconds -= consumed
                if (dwellRemaining <= 1e-9) completeCurrentStop()
                velocity = 0.0
                continue
            }
            val dwellEntry = pendingDwells.getOrNull(nextDwellIndex)?.takeIf { absDistance(route.cumulativeMeters[it.key] - routeDistance) < 1e-7 }
            if (dwellEntry != null) {
                consumedDwells.add(dwellEntry.key)
                dwellRemaining = dwellEntry.value / 1000.0
                beginStopAt(dwellEntry.key)
                nextDwellIndex++
                velocity = 0.0
                continue
            }
            if (routeDistance >= route.distanceMeters) { velocity = 0.0; break }
            val acceleration = if (request.mode == Mode.WALK || request.flight?.groundMode == Mode.WALK) 0.8 else 2.0
            val cruise = if (request.mode != Mode.WALK && speedControls.mode == DrivingSpeedMode.VARIABLE) speedControls.cruiseTarget(elapsed - deltaMs + ((deltaMs / 1000.0 - remainingSeconds) * 1000).toLong()) else request.speedMps
            val followingLimits = request.mode != Mode.WALK && speedControls.followRoadLimits
            val currentLimit = if (followingLimits) route.limitAt(routeDistance) else null
            var target = min(cruise, currentLimit ?: cruise)
            // Brake before a lower limit, stop or destination. Looking only within the braking
            // horizon keeps long dense online routes bounded rather than scanning the whole route.
            val horizon = max(velocity, target).let { it * it / (2 * acceleration) } + 1.0
            if (followingLimits && route.edgeSpeedLimits.isNotEmpty()) {
                var index = route.edgeIndex(routeDistance) + 1
                while (index < route.points.lastIndex && route.cumulativeMeters[index] - routeDistance <= horizon) {
                    route.edgeSpeedLimits[index]?.let { limit ->
                        target = min(target, sqrt(limit * limit + 2 * acceleration * max(0.0, route.cumulativeMeters[index] - routeDistance - velocity * step - 0.5 * acceleration * step * step)))
                    }
                    index++
                }
            }
            if (request.mode != Mode.WALK) {
                var index = route.edgeIndex(routeDistance) + 1
                while (index < route.points.lastIndex && route.cumulativeMeters[index] - routeDistance <= horizon) {
                    route.turnSpeedLimits[index]?.let { turnSpeed ->
                        target = min(target, sqrt(turnSpeed * turnSpeed + 2 * acceleration * max(0.0, route.cumulativeMeters[index] - routeDistance - velocity * step - 0.5 * acceleration * step * step)))
                    }
                    index++
                }
            }
            val stopDistance = pendingDwells.getOrNull(nextDwellIndex)?.let { route.cumulativeMeters[it.key] - routeDistance } ?: (route.distanceMeters - routeDistance)
            target = min(target, sqrt(2 * acceleration * max(0.0, stopDistance)))
            // A newly lowered live cap must apply immediately. Normal target changes decelerate.
            if (currentLimit != null) velocity = min(velocity, currentLimit)
            val nextVelocity = if (velocity < target) min(target, velocity + acceleration * step) else max(target, velocity - acceleration * step)
            val stoppingDistance = nextVelocity * nextVelocity / (2 * acceleration)
            val endpoint = route.distanceMeters - routeDistance
            val decelerated = if (endpoint <= stoppingDistance) min(target, max(0.05, velocity - acceleration * step)) else nextVelocity
            val signedAcceleration = (decelerated - velocity) / step
            val increment = (velocity + decelerated) * 0.5 * step
            val nextDwell = pendingDwells.getOrNull(nextDwellIndex)?.let { route.cumulativeMeters[it.key] }
            val edgeEnd = route.cumulativeMeters.getOrNull(route.edgeIndex(routeDistance) + 1) ?: route.distanceMeters
            val maxDistance = min(edgeEnd, nextDwell?.let { min(route.distanceMeters, it) } ?: route.distanceMeters)
            val toBoundary = max(0.0, maxDistance - routeDistance)
            if (increment > 0 && increment >= toBoundary) {
                // Consume only time actually needed to reach a vertex/dwell. Carry remaining time
                // into the next edge, rather than losing distance on densely sampled geometry.
                val discriminant = max(0.0, velocity * velocity + 2 * signedAcceleration * toBoundary)
                val denominator = velocity + kotlin.math.sqrt(discriminant)
                val boundaryTime = if (denominator > 1e-12) 2 * toBoundary / denominator else step
                val consumed = boundaryTime.coerceIn(0.0, step)
                routeDistance = maxDistance
                velocity = (velocity + signedAcceleration * consumed).coerceIn(0.0, currentLimit ?: MAX_DRIVE_SPEED_MPS)
                remainingSeconds -= consumed
            } else {
                routeDistance += increment
                velocity = decelerated
                remainingSeconds -= step
            }
            currentPoint = route.pointAt(routeDistance)
        }
        dwells.entries.firstOrNull {
            it.key !in consumedDwells && it.key < route.cumulativeMeters.size && absDistance(route.cumulativeMeters[it.key] - routeDistance) < 1e-7
        }?.let { consumedDwells.add(it.key); dwellRemaining = it.value / 1000.0; beginStopAt(it.key); velocity = 0.0 }
    }

    private fun absDistance(value: Double): Double = kotlin.math.abs(value)

    private fun enterPhase(next: FlightPhase) { phase = next; phaseElapsed = 0; velocity = 0.0 }

    private fun advanceFlight(plan: Plan, deltaMs: Long) {
        val options = plan.request.flight!!
        phaseElapsed += deltaMs
        when (phase) {
            FlightPhase.ORIGIN_GROUND -> {
                if (options.departureGroundMode == GroundLegMode.INSTANT) {
                    placementGeneration++;currentPoint = plan.first.points.last(); placementAcknowledged = false; enterPhase(FlightPhase.ORIGIN_PLACEMENT)
                } else {
                    advanceGround(plan.first, plan.request.copy(speedMps = groundSpeed(plan), waypointDwellsMs = emptyMap()), deltaMs)
                    if (routeDistance >= plan.first.distanceMeters) { currentPoint = plan.first.points.last(); enterPhase(FlightPhase.BOARDING) }
                }
            }
            FlightPhase.ORIGIN_PLACEMENT -> if (placementAcknowledged) enterPhase(FlightPhase.BOARDING)
            FlightPhase.BOARDING -> if (phaseElapsed >= options.boardingMs) enterPhase(if (options.simulationOnly) FlightPhase.FLIGHT_INTERVAL else FlightPhase.AWAIT_AIRPLANE_MODE)
            FlightPhase.AWAIT_AIRPLANE_MODE -> if (observedRadio == true) enterPhase(FlightPhase.FLIGHT_INTERVAL)
            FlightPhase.FLIGHT_INTERVAL -> {
                val arrival = plan.last!!.points.first()
                val distance = flightDistance(plan)
                if (options.flightTransitMode == FlightTransitMode.TIMED) {
                    airDistance = min(distance, airDistance + speeds.flightMps * deltaMs / 1000.0)
                    currentPoint = interpolate(plan.first.points.last(), arrival, if (distance <= 0.01) 1.0 else airDistance / distance)
                }
                if (options.flightTransitMode == FlightTransitMode.JUMP || airDistance >= distance) {
                    if (options.flightTransitMode == FlightTransitMode.JUMP) placementGeneration++
                    currentPoint = arrival; placementAcknowledged = false; enterPhase(FlightPhase.ARRIVAL_PLACEMENT)
                }
            }
            FlightPhase.ARRIVAL_PLACEMENT -> if (placementAcknowledged) enterPhase(FlightPhase.SETTLING)
            FlightPhase.SETTLING -> if (phaseElapsed >= options.settlingMs) enterPhase(FlightPhase.RESTORE_CONNECTIVITY)
            FlightPhase.RESTORE_CONNECTIVITY -> {
                val restoreConfirmed = options.simulationOnly || !changedRadio || observedRadio == initialRadio
                if (restoreConfirmed) { routeDistance = 0.0; consumedDwells.clear(); dwellRemaining = 0.0; enterPhase(FlightPhase.DESTINATION_GROUND) }
            }
            FlightPhase.DESTINATION_GROUND -> {
                if (options.arrivalGroundMode == GroundLegMode.INSTANT) {
                    placementGeneration++;currentPoint = plan.last!!.points.last(); placementAcknowledged = false; enterPhase(FlightPhase.DESTINATION_PLACEMENT)
                } else {
                    advanceGround(plan.last!!, plan.request.copy(speedMps = groundSpeed(plan), waypointDwellsMs = emptyMap()), deltaMs)
                    if (routeDistance >= plan.last.distanceMeters) { currentPoint = plan.last.points.last(); enterPhase(FlightPhase.COMPLETE); state = SessionState.HOLDING; revision++ }
                }
            }
            FlightPhase.DESTINATION_PLACEMENT -> if (placementAcknowledged) { enterPhase(FlightPhase.COMPLETE); state = SessionState.HOLDING; revision++ }
            FlightPhase.COMPLETE, null -> Unit
        }
    }
}

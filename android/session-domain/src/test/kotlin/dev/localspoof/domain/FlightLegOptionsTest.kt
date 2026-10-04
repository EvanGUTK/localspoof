package dev.localspoof.domain

import kotlin.test.*
import org.junit.jupiter.api.Test

class FlightLegOptionsTest {
    private val origin = GeoPoint(-0.001, 179.0)
    private val departure = GeoPoint(0.0, 179.0)
    private val arrival = GeoPoint(0.0, -179.0)
    private val destination = GeoPoint(0.001, -179.0)
    private val airports = listOf(Airport("A", "Fixture departure", departure, type = "large_airport"), Airport("B", "Fixture arrival", arrival, type = "large_airport"))
    private fun request(depart: GroundLegMode = GroundLegMode.INSTANT, arrive: GroundLegMode = GroundLegMode.INSTANT,
        transit: FlightTransitMode = FlightTransitMode.JUMP, simulation: Boolean = false, duration: Long = 1_000_000): PlanRequest {
        val first = if (depart == GroundLegMode.DRIVE) OnlineRoute(Mode.DRIVE, "fixture router", listOf(origin, departure)) else null
        val last = if (arrive == GroundLegMode.DRIVE) OnlineRoute(Mode.DRIVE, "fixture router", listOf(arrival, destination)) else null
        return PlanRequest(Mode.FLIGHT, origin, destination, 20.0, flight = FlightOptions(simulation, boardingMs = 0, flightMs = duration,
            settlingMs = 1_000, originAirportId = "A", destinationAirportId = "B", departureGroundMode = depart, arrivalGroundMode = arrive, flightTransitMode = transit),
            onlineFlightRoutes = OnlineFlightRoutes("A", "B", first, last))
    }
    private fun active(request: PlanRequest) = Coordinator(airports).also {
        val prepared = it.prepare(request); it.commit(prepared.id); it.observeRadio(false); it.start(0)
    }
    @Test fun allGroundChoicesProduceHonestTypedPreviewAndOnlyDriveNeedsGeometry() {
        for (first in GroundLegMode.entries) for (last in GroundLegMode.entries) {
            val prepared = Coordinator(airports).prepare(request(first, last))
            val preview = prepared.preview
            assertEquals(RouteSource.FLIGHT_JOURNEY, preview.routeSource)
            assertEquals(listOf(SegmentRole.DEPARTURE, SegmentRole.AIR, SegmentRole.ARRIVAL), preview.segments.map { it.role })
            assertEquals(listOf(if (first == GroundLegMode.DRIVE) SegmentKind.DRIVE else SegmentKind.INSTANT, SegmentKind.FLIGHT,
                if (last == GroundLegMode.DRIVE) SegmentKind.DRIVE else SegmentKind.INSTANT), preview.segments.map { it.kind })
            assertEquals(origin, preview.segments.first().points.first()); assertEquals(destination, preview.segments.last().points.last())
            preview.segments.filter { it.kind != SegmentKind.DRIVE }.forEach { assertEquals(0L, it.durationMs) }
            preview.segments.filter { it.kind == SegmentKind.DRIVE }.forEach { assertEquals(RouteSource.ONLINE_ROUTER, it.routeSource); assertTrue(it.durationMs > 0) }
            assertEquals(1_000L + preview.segments.sumOf { it.durationMs }, preview.durationMs)
        }
        val base = request()
        assertFailsWith<IllegalArgumentException> { base.copy(flight = base.flight!!.copy(departureGroundMode = GroundLegMode.DRIVE)) }
        assertFailsWith<IllegalArgumentException> { base.copy(onlineFlightRoutes = base.onlineFlightRoutes!!.copy(originGround = OnlineRoute(Mode.DRIVE, "fixture", listOf(origin, departure)))) }
    }
    @Test fun instantAndJumpNeverSkipPlacementRadioOrRestoreBarriers() {
        val c = active(request())
        assertEquals(departure, c.tick(0)!!.point); assertEquals(FlightPhase.ORIGIN_PLACEMENT, c.snapshot().phase)
        c.tick(1_000); assertEquals(FlightPhase.ORIGIN_PLACEMENT, c.snapshot().phase)
        c.acknowledgePlacement(true); c.tick(2_000); c.tick(3_000)
        assertEquals(FlightPhase.AWAIT_AIRPLANE_MODE, c.snapshot().phase)
        c.tick(4_000); assertEquals(departure, c.snapshot().point)
        c.observeRadio(true); c.tick(5_000); c.tick(6_000)
        assertEquals(arrival, c.snapshot().point); assertEquals(FlightPhase.ARRIVAL_PLACEMENT, c.snapshot().phase)
        c.tick(7_000); assertEquals(FlightPhase.ARRIVAL_PLACEMENT, c.snapshot().phase)
        c.acknowledgePlacement(true); c.tick(8_000); c.tick(9_000); c.tick(10_000)
        assertEquals(FlightPhase.RESTORE_CONNECTIVITY, c.snapshot().phase)
        assertEquals(false, c.snapshot().desiredAirplaneMode)
        c.observeRadio(false); c.tick(11_000)
        assertEquals(destination, c.tick(12_000)!!.point); assertEquals(FlightPhase.DESTINATION_PLACEMENT, c.snapshot().phase)
        c.tick(13_000); assertEquals(SessionState.ACTIVE, c.snapshot().state)
        c.acknowledgePlacement(true); c.tick(14_000); assertEquals(SessionState.HOLDING, c.snapshot().state)
    }
    @Test fun timedAirSamplesMoveOnTheShortGreatCircleAcrossTheDateLine() {
        val c = active(request(transit = FlightTransitMode.TIMED))
        c.tick(0); c.acknowledgePlacement(true); c.tick(1_000); c.tick(2_000)
        assertEquals(FlightPhase.AWAIT_AIRPLANE_MODE, c.snapshot().phase)
        c.observeRadio(true); c.tick(3_000)
        for (time in 8_000L..248_000L step 5_000) c.tick(time)
        val quarter = c.tick(253_000)!!
        for (time in 258_000L..498_000L step 5_000) c.tick(time)
        val midpoint = c.tick(503_000)!!
        assertEquals(179.5, quarter.point.lon, 0.001)
        assertTrue(kotlin.math.abs(midpoint.point.lon) > 179.99)
        assertEquals(distanceMeters(departure, arrival) / 1000.0, midpoint.speedMps, 0.001)
        assertEquals(FlightPhase.FLIGHT_INTERVAL, c.snapshot().phase)
        for (time in 508_000L..998_000L step 5_000) c.tick(time)
        assertEquals(arrival, c.tick(1_003_000)!!.point)
        assertEquals(FlightPhase.ARRIVAL_PLACEMENT, c.snapshot().phase)
    }
    @Test fun pausingTimedAirDoesNotMoveAndRadioLossPreventsFurtherFlightProgress() {
        val c = active(request(transit = FlightTransitMode.TIMED))
        c.tick(0); c.acknowledgePlacement(true); c.tick(1_000); c.tick(2_000); c.observeRadio(true); c.tick(3_000)
        val before = c.tick(4_000)!!.point
        c.pause(); assertEquals(before, c.tick(5_000)!!.point)
        c.resume(5_000); c.observeRadio(false)
        assertEquals(SessionState.PAUSED, c.snapshot().state)
        assertEquals(before, c.tick(6_000)!!.point)
        assertFailsWith<IllegalArgumentException> { c.resume(6_000) }
        c.stop(); assertNull(c.tick(7_000)); assertNull(c.snapshot().point)
    }
    @Test fun failedInstantPlacementAndStopCannotAdvanceAFlightPhase() {
        val c = active(request(simulation = true)); c.tick(0); c.acknowledgePlacement(false)
        assertEquals(SessionState.ERROR, c.snapshot().state); assertNull(c.tick(1_000))
        c.stop(); assertEquals(SessionState.IDLE, c.snapshot().state); assertNull(c.tick(2_000))
    }
}

package dev.localspoof.domain

import org.junit.jupiter.api.Test
import kotlin.test.*

class LiveJourneyTest {
    private val a = GeoPoint(0.0, 0.0)
    private val b = GeoPoint(0.0, 0.001)
    private val c = GeoPoint(0.0, 0.002)
    private fun ground() = Coordinator(wallClock = { 0 }).also {
        val p = it.prepare(PlanRequest(Mode.DRIVE, a, c, 10.0, customPath = listOf(a,b,c), stops = listOf(RouteStop(b, 5_000, "Coffee"))))
        it.commit(p.id); it.start(0); it.tick(0)
    }
    @Test fun speedChangesKeepPositionAndRecomputeRemainingWithoutRestart() {
        val runtime = ground(); runtime.tick(1_000); runtime.tick(2_000)
        val before = runtime.snapshot()
        runtime.setSpeeds(driveMps = 20.0, expectedRevision = before.revision)
        val after = runtime.snapshot()
        assertEquals(before.point, after.point); assertEquals(before.elapsedMs, after.elapsedMs)
        assertTrue(after.remainingMs!! < before.remainingMs!!)
        assertEquals(20.0, after.speeds.driveMps)
        assertFailsWith<IllegalArgumentException> { runtime.setSpeeds(driveMps = 30.0, expectedRevision = before.revision) }
        runtime.pause(); val paused = runtime.snapshot().point
        runtime.setSpeeds(driveMps = 5.0); assertNull(runtime.snapshot().remainingMs)
        runtime.tick(3_000); assertEquals(paused, runtime.snapshot().point)
        runtime.resume(3_000); runtime.tick(4_000); assertTrue(runtime.snapshot().remainingMs!! > after.remainingMs!!)
    }
    @Test fun jumpStopsAtDeclaredWaypointAndWaitsForProviderAckThenDwells() {
        val runtime = ground()
        assertEquals("Coffee", runtime.snapshot().nextStop?.label)
        runtime.jumpToNextStop(); assertEquals(b, runtime.tick(1_000)!!.point)
        assertEquals("PROVIDER_ACK", runtime.snapshot().waitingFor)
        runtime.tick(2_000); assertEquals(b, runtime.snapshot().point)
        runtime.acknowledgePlacement(true); runtime.tick(3_000)
        assertEquals("STOP_DWELL", runtime.snapshot().waitingFor)
        runtime.tick(4_000); assertEquals(b, runtime.snapshot().point)
        runtime.stop(); assertNull(runtime.tick(5_000)); assertNull(runtime.snapshot().nextStop)
    }
    @Test fun jumpIsRevisionBoundAndCannotRunWhilePausedOrBypassAirplaneGate() {
        val runtime = ground(); val old = runtime.snapshot().revision
        runtime.pause(); assertFailsWith<IllegalArgumentException> { runtime.jumpToNextStop() }
        runtime.resume(0); assertFailsWith<IllegalArgumentException> { runtime.jumpToNextStop(old) }
        val airports = listOf(Airport("a", "Departure", b, "large_airport"), Airport("b", "Arrival", c, "large_airport"))
        val flight = Coordinator(airports)
        val p = flight.prepare(PlanRequest(Mode.FLIGHT, a, c, flight = FlightOptions(false,boardingMs=0,departureGroundMode=GroundLegMode.INSTANT,arrivalGroundMode=GroundLegMode.INSTANT)))
        flight.commit(p.id); flight.observeRadio(false); flight.start(0); flight.tick(0); flight.acknowledgePlacement(true); flight.tick(1_000); flight.tick(2_000)
        assertEquals(FlightPhase.AWAIT_AIRPLANE_MODE, flight.snapshot().phase)
        assertFailsWith<IllegalArgumentException> { flight.jumpToNextStop() }
        assertNull(flight.snapshot().remainingMs)
        flight.observeRadio(true); flight.tick(3_000); flight.jumpToNextStop(); flight.tick(4_000)
        assertEquals(FlightPhase.ARRIVAL_PLACEMENT, flight.snapshot().phase)
        flight.tick(5_000); assertEquals(FlightPhase.ARRIVAL_PLACEMENT,flight.snapshot().phase)
        flight.acknowledgePlacement(true); flight.tick(6_000); flight.tick(7_000)
        assertFailsWith<IllegalArgumentException> { flight.jumpToNextStop() }
    }
    @Test fun airportSelectionExcludesRegionalJacksonEvenWhenClosestOrManuallySelected() {
        val airports = listOf(Airport("KMKL", "Jackson", a, "medium_airport"), Airport("KMEM", "Memphis International", b, "large_airport"))
        assertEquals(listOf("KMEM"), airportCandidates(airports,a).map{it.id})
        val flight = Coordinator(airports + Airport("KDFW","Dallas",c,"large_airport"))
        assertFailsWith<RoutePlanningException> { flight.prepare(PlanRequest(Mode.FLIGHT,a,c,flight=FlightOptions(originAirportId="KMKL",departureGroundMode=GroundLegMode.INSTANT,arrivalGroundMode=GroundLegMode.INSTANT))) }
    }
    @Test fun onlineStopsRejectUnorderedAndForgedIndices() {
        val route = OnlineRoute(Mode.DRIVE,"Fixture",listOf(a,b,c))
        val r = PlanRequest(Mode.DRIVE,a,c,stops=listOf(RouteStop(b,1_000)),onlineRoute=route,stopPointIndices=listOf(1))
        val p=Coordinator().prepare(r); assertTrue(p.preview.durationMs >= 1_000)
        assertFailsWith<IllegalArgumentException> { Coordinator().prepare(r.copy(stopPointIndices=listOf(0))) }
        assertFailsWith<IllegalArgumentException> { Coordinator().prepare(r.copy(stops=listOf(RouteStop(GeoPoint(20.0,20.0))))) }
    }    @Test fun airborneSpeedChangeUsesRemainingAirDistanceAndNeverRewinds() {
        val departure=GeoPoint(0.0,0.01); val arrival=GeoPoint(0.0,0.02)
        val runtime=Coordinator(listOf(Airport("d","Departure",departure,"large_airport"),Airport("a","Arrival",arrival,"large_airport")))
        val p=runtime.prepare(PlanRequest(Mode.FLIGHT,a,c,flight=FlightOptions(true,boardingMs=0,settlingMs=1000,departureGroundMode=GroundLegMode.INSTANT,arrivalGroundMode=GroundLegMode.INSTANT),flightSpeedMps=230.0))
        runtime.commit(p.id);runtime.start(0);runtime.tick(0);runtime.acknowledgePlacement(true);runtime.tick(1_000);runtime.tick(2_000);runtime.tick(3_000)
        val before=runtime.snapshot();runtime.setSpeeds(flightMps=115.0)
        assertEquals(before.point,runtime.snapshot().point)
        assertTrue(runtime.snapshot().remainingMs!! > before.remainingMs!!)
        val sample=runtime.tick(4_000)!!
        assertEquals(115.0,sample.speedMps,0.001)
        assertEquals(115.0,distanceMeters(before.point!!,sample.point),0.1)
    }
    @Test fun failedJumpSubmissionStopsAtErrorAndStopInvalidatesFurtherControls() {
        val runtime=ground();runtime.jumpToNextStop();runtime.tick(1_000);runtime.acknowledgePlacement(false)
        assertEquals(SessionState.ERROR,runtime.snapshot().state);assertNull(runtime.tick(2_000))
        runtime.stop()
        assertFailsWith<IllegalArgumentException> { runtime.setSpeeds(driveMps=20.0) }
        assertFailsWith<IllegalArgumentException> { runtime.jumpToNextStop() }
    }
    @Test fun legacyDurationCannotBypassAirSpeedBoundsButExplicitSpeedTakesPrecedence() {
        val airports=listOf(Airport("a","A",a,"large_airport"),Airport("b","B",GeoPoint(0.0,10.0),"large_airport"))
        val invalid=PlanRequest(Mode.FLIGHT,a,airports.last().point,flight=FlightOptions(true,flightMs=1_000,departureGroundMode=GroundLegMode.INSTANT,arrivalGroundMode=GroundLegMode.INSTANT))
        assertFailsWith<IllegalArgumentException> { Coordinator(airports).prepare(invalid) }
        val runtime=Coordinator(airports);val p=runtime.prepare(invalid.copy(flightSpeedMps=230.0));runtime.commit(p.id)
        assertEquals(230.0,runtime.snapshot().speeds.flightMps)
    }

}

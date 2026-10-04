package dev.localspoof.domain

import org.junit.jupiter.api.Test
import kotlin.test.*

class DomainTest {
    private val a = GeoPoint(0.0, 0.0)
    private val b = GeoPoint(0.0, 0.001)
    private val c = GeoPoint(0.001, 0.001)

    private fun graph(edges: List<GeoEdge> = listOf(
        GeoEdge("a", "b", setOf(Mode.WALK, Mode.DRIVE), 1.0),
        GeoEdge("b", "c", setOf(Mode.WALK)),
    )) = GeoPack(id = "test", name = "Synthetic", bounds = GeoBounds(-1.0, -1.0, 1.0, 1.0),
        nodes = listOf(GeoNode("a", a), GeoNode("b", b), GeoNode("c", c)), edges = edges,
        places = listOf(Place("p", "Synthetic Park", c)))

    private fun running(request: PlanRequest): Coordinator = Coordinator(wallClock = { 1_000_000 }).also {
        val prepared = it.prepare(request)
        it.commit(prepared.id)
        it.start(0)
    }

    @Test fun invalidCoordinatesAndSpeedsAreRejected() {
        assertFailsWith<IllegalArgumentException> { GeoPoint(Double.NaN, 0.0) }
        assertFailsWith<IllegalArgumentException> { GeoPoint(0.0, Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { GeoPoint(91.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { PlanRequest(Mode.WALK, a, b, 10.0) }
        assertFailsWith<IllegalArgumentException> { PlanRequest(Mode.DRIVE, a, b, -1.0) }
        assertFailsWith<IllegalArgumentException> { PlanRequest(Mode.DRIVE, a, b, 1e-300) }
    }

    @Test fun flightThresholdIsStrictAndExact() {
        assertFalse(suggestFlight(402_335.999))
        assertFalse(suggestFlight(402_336.0))
        assertTrue(suggestFlight(402_336.001))
    }

    @Test fun sphericalGeometryCrossesAntimeridianWithoutWorldDetour() {
        val west = GeoPoint(10.0, 179.9)
        val east = GeoPoint(10.0, -179.9)
        val midpoint = interpolate(west, east, 0.5)
        assertTrue(kotlin.math.abs(midpoint.lon) > 179.99)
        assertTrue(distanceMeters(west, east) < 25_000)
        assertEquals(west, interpolate(west, east, 0.0))
        assertEquals(east, interpolate(west, east, 1.0))
    }

    @Test fun duplicatePointsAndZeroLengthRoutesRemainFinite() {
        val route = RouteGeometry(listOf(a, a, b, b))
        assertEquals(a, route.pointAt(0.0))
        assertEquals(b, route.pointAt(route.distanceMeters))
        assertTrue(route.pointAt(route.distanceMeters / 2).lon.isFinite())
        assertEquals(a, RouteGeometry(listOf(a, a)).pointAt(0.0))
        val coordinator = running(PlanRequest(Mode.WALK, a, a, customPath = listOf(a, a)))
        assertEquals(a, coordinator.tick(1_000)!!.point)
        assertEquals(SessionState.HOLDING, coordinator.snapshot().state)
    }

    @Test fun graphHonorsModeAndOneWayRestrictions() {
        assertEquals(listOf(a, b, c), GraphRouter(graph()).route(a, c, Mode.WALK, 1.4).points)
        // c is within the disclosed 250 m snap tolerance of the drive-eligible b node.
        // The drive route may end at b, but must never traverse the WALK-only b -> c edge.
        assertEquals(listOf(a, b), GraphRouter(graph()).route(a, c, Mode.DRIVE, 10.0).points)
        assertEquals("NO_NEARBY_ROAD", assertFailsWith<RoutePlanningException> {
            GraphRouter(graph(), maxSnapMeters = 50.0).route(a, c, Mode.DRIVE, 10.0)
        }.code)
        val directed = graph(listOf(GeoEdge("a", "b", setOf(Mode.WALK), bidirectional = false)))
        assertFailsWith<RoutePlanningException> { GraphRouter(directed).route(b, a, Mode.WALK, 1.4) }
    }

    @Test fun graphRouteHasNoFallbackOutsideCoverageOrRoadDistance() {
        assertEquals("MISSING_COVERAGE", assertFailsWith<RoutePlanningException> {
            GraphRouter(graph()).route(a, GeoPoint(2.0, 2.0), Mode.WALK, 1.4)
        }.code)
        assertEquals("NO_NEARBY_ROAD", assertFailsWith<RoutePlanningException> {
            GraphRouter(graph()).route(a, GeoPoint(0.5, 0.5), Mode.WALK, 1.4)
        }.code)
    }

    @Test fun graphChoosesLowestTravelCostAndCarriesSpeedLimits() {
        val pack = graph(listOf(GeoEdge("a", "c", setOf(Mode.DRIVE), 0.2),
            GeoEdge("a", "b", setOf(Mode.DRIVE), 10.0), GeoEdge("b", "c", setOf(Mode.DRIVE), 10.0)))
        val result = GraphRouter(pack).route(a, c, Mode.DRIVE, 10.0)
        assertEquals(listOf(a, b, c), result.points)
        assertEquals(listOf(10.0, 10.0), result.edgeSpeedLimits)
    }

    @Test fun malformedGraphReferencesAreRejectedAndPreparationCancels() {
        assertFailsWith<IllegalArgumentException> { graph(listOf(GeoEdge("a", "missing", setOf(Mode.WALK)))) }
        assertEquals("PREPARATION_CANCELLED", assertFailsWith<RoutePlanningException> {
            GraphRouter(graph()).route(a, c, Mode.WALK, 1.4) { true }
        }.code)
    }

    @Test fun staticResourceSwapDoesNotChangeAlreadyPreparedPlayback() {
        val coordinator = Coordinator(pack = graph())
        val prepared = coordinator.prepare(PlanRequest(Mode.DRIVE, a, b, speedMps = 1.0))
        coordinator.commit(prepared.id)
        coordinator.start(0)
        val initial = coordinator.tick(1_000)!!.point
        val replacement = coordinator.prepareResources(null, emptyList())
        coordinator.installResources(replacement)
        assertTrue(coordinator.tick(2_000)!!.point.lon > initial.lon)
        assertFailsWith<RoutePlanningException> { coordinator.prepare(PlanRequest(Mode.DRIVE, a, b)) }
    }

    @Test fun searchAndAirportCandidatesAreLocalBoundedAndSuitable() {
        assertEquals("p", graph().search("park").single().id)
        assertTrue(graph().search("").isEmpty())
        val airports = listOf(Airport("closed", "Closed", a, closed = true),
            Airport("heli", "Heli", a, type = "heliport"), Airport("private", "Private", a, scheduledService = false),
            Airport("open", "Open", b, type = "large_airport"))
        assertEquals(listOf("open"), airportCandidates(airports, a).map { it.id })
    }

    @Test fun instantHoldRefreshesWithIncreasingMonotonicTimestamps() {
        val coordinator = running(PlanRequest(Mode.INSTANT, a, b))
        assertEquals(listOf(b), coordinator.snapshot().preparedPreview!!.geometry)
        val first = coordinator.tick(0)!!
        val repeated = coordinator.tick(0)!!
        assertEquals(b, first.point)
        assertEquals(0.0, repeated.speedMps)
        assertTrue(repeated.elapsedRealtimeMs > first.elapsedRealtimeMs)
        assertEquals(1_000_000L, repeated.wallTimeMillis)
        assertEquals(SessionState.HOLDING, coordinator.snapshot().state)
    }

    @Test fun pauseFreezesRouteButStillRefreshesHold() {
        val coordinator = running(PlanRequest(Mode.WALK, a, b, customPath = listOf(a, b)))
        val first = coordinator.tick(1_000)!!
        coordinator.pause()
        val held = coordinator.tick(2_000)!!
        assertEquals(first.point, held.point)
        assertEquals(0.0, held.speedMps)
        coordinator.resume(2_000)
        assertTrue(coordinator.tick(3_000)!!.point.lon > held.point.lon)
    }

    @Test fun schedulingGapDoesNotSkipMissingTravel() {
        val coordinator = running(PlanRequest(Mode.WALK, a, b, customPath = listOf(a, b)))
        val before = coordinator.tick(1_000)!!.point
        val after = coordinator.tick(7_001)!!.point
        assertEquals(before, after)
        assertEquals(SessionState.PAUSED, coordinator.snapshot().state)
        assertEquals("SCHEDULING_GAP", coordinator.snapshot().error)
        coordinator.resume(7_001)
        assertTrue(coordinator.tick(8_001)!!.point.lon > before.lon)
    }

    @Test fun exactlyFiveSecondTickIsAllowedAndTimeCannotReverse() {
        val coordinator = running(PlanRequest(Mode.WALK, a, b, customPath = listOf(a, b)))
        coordinator.tick(5_000)
        assertEquals(SessionState.ACTIVE, coordinator.snapshot().state)
        assertFailsWith<IllegalArgumentException> { coordinator.tick(4_999) }
    }

    @Test fun planReplacementIsAtomicAndStaleMutationsFail() {
        val coordinator = running(PlanRequest(Mode.INSTANT, a, a))
        coordinator.tick(0)
        val before = coordinator.snapshot()
        assertFailsWith<RoutePlanningException> { coordinator.prepare(PlanRequest(Mode.DRIVE, a, c, 10.0)) }
        assertEquals(before.point, coordinator.snapshot().point)
        val staged = coordinator.prepare(PlanRequest(Mode.INSTANT, a, b))
        assertEquals(a, coordinator.snapshot().point)
        assertFailsWith<IllegalArgumentException> { coordinator.commit(staged.id, before.revision - 1) }
        coordinator.commit(staged.id)
        assertEquals(b, coordinator.tick(1_000)!!.point)
    }

    @Test fun stopDestroysActiveAndPreparedActivityAndWinsOverDelayedCommit() {
        val coordinator = running(PlanRequest(Mode.INSTANT, a, b))
        val pending = coordinator.prepare(PlanRequest(Mode.INSTANT, a, c))
        coordinator.stop()
        val stopped = coordinator.snapshot()
        assertEquals(SessionState.IDLE, stopped.state)
        assertNull(stopped.point)
        assertNull(stopped.mode)
        assertNull(stopped.sample)
        assertNull(stopped.preparedPreview)
        assertNull(coordinator.tick(1_000))
        assertFailsWith<IllegalStateException> { coordinator.commit(pending.id) }
    }

    @Test fun domainSnapshotIsNotRestoredInANewInstance() {
        running(PlanRequest(Mode.INSTANT, a, b)).tick(0)
        assertEquals(SessionState.IDLE, Coordinator().snapshot().state)
        assertNull(Coordinator().snapshot().point)
    }

    @Test fun waypointDwellAndEndpointHoldAreDistinct() {
        val near = GeoPoint(0.0, 0.000002)
        val coordinator = running(PlanRequest(Mode.WALK, a, near, speedMps = 1.0, customPath = listOf(a, near), waypointDwellsMs = mapOf(0 to 2_000L, 1 to 2_000L)))
        assertEquals(a, coordinator.tick(1_000)!!.point)
        assertEquals(SessionState.ACTIVE, coordinator.snapshot().state)
        for (time in 2_000L..7_000L step 1_000) coordinator.tick(time)
        assertEquals(SessionState.HOLDING, coordinator.snapshot().state)
        assertEquals(near, coordinator.snapshot().point)
    }

    @Test fun drivingNeverExceedsGraphSpeedCapAndHoldsEndpoint() {
        val coordinator = Coordinator(pack = graph())
        val prepared = coordinator.prepare(PlanRequest(Mode.DRIVE, a, b, speedMps = 10.0))
        coordinator.commit(prepared.id)
        coordinator.start(0)
        for (seconds in 1L..120L) assertTrue(coordinator.tick(seconds * 1_000)!!.speedMps <= 1.000_001)
        assertEquals(SessionState.HOLDING, coordinator.snapshot().state)
        assertEquals(b, coordinator.snapshot().point)
    }

    @Test fun routeMotionDoesNotDependOnGeometryNodeDensity() {
        val end = GeoPoint(0.0, 0.0009)
        val sparse = running(PlanRequest(Mode.DRIVE, a, end, speedMps = 10.0, customPath = listOf(a, end)))
        val densePoints = (0..1_000).map { interpolate(a, end, it / 1_000.0) }
        val dense = running(PlanRequest(Mode.DRIVE, a, end, speedMps = 10.0, customPath = densePoints))
        for (seconds in 1L..9L) {
            val one = sparse.tick(seconds * 1_000)!!
            val many = dense.tick(seconds * 1_000)!!
            assertTrue(distanceMeters(one.point, many.point) < 0.02, "Geometry density changed progress")
            assertTrue(kotlin.math.abs(one.speedMps - many.speedMps) < 0.02)
            assertTrue(many.speedMps <= 10.000_001)
        }
        var sparseArrival = 0L
        var denseArrival = 0L
        for (seconds in 10L..20L) {
            sparse.tick(seconds * 1_000)
            dense.tick(seconds * 1_000)
            if (sparseArrival == 0L && sparse.snapshot().state == SessionState.HOLDING) sparseArrival = seconds
            if (denseArrival == 0L && dense.snapshot().state == SessionState.HOLDING) denseArrival = seconds
        }
        assertTrue(sparseArrival > 0 && denseArrival > 0)
        assertTrue(kotlin.math.abs(sparseArrival - denseArrival) <= 1)
    }

    @Test fun sampleSpeedUsesCurrentEdgeCapAfterTransition() {
        val roads = graph(listOf(GeoEdge("a", "b", setOf(Mode.DRIVE), 10.0), GeoEdge("b", "c", setOf(Mode.DRIVE), 1.0)))
        val coordinator = Coordinator(pack = roads)
        val prepared = coordinator.prepare(PlanRequest(Mode.DRIVE, a, c, speedMps = 10.0))
        coordinator.commit(prepared.id); coordinator.start(0)
        var enteredSlowerEdge = false
        for (seconds in 1L..20L) {
            val sample = coordinator.tick(seconds * 1_000)!!
            if (sample.point.lat > 1e-10) { enteredSlowerEdge = true; assertTrue(sample.speedMps <= 1.000_001) }
        }
        assertTrue(enteredSlowerEdge)
    }

    private val arrival = GeoPoint(0.0, 10.0)
    private fun flight(simulationOnly: Boolean, initialOn: Boolean = false): Coordinator = Coordinator(airports = listOf(
        Airport("origin", "Synthetic Origin", a, type = "large_airport"), Airport("destination", "Synthetic Destination", arrival, type = "large_airport")), wallClock = { 0 }).also {
        it.observeRadio(initialOn)
        val ready = it.prepare(PlanRequest(Mode.FLIGHT, a, arrival, flight = FlightOptions(simulationOnly, boardingMs = 0, flightMs = 1_000, settlingMs = 1_000, flightTransitMode = FlightTransitMode.JUMP)))
        it.commit(ready.id)
        it.start(0)
    }

    @Test fun flightWaitsForProviderAcknowledgmentBeforeSettling() {
        val coordinator = flight(true)
        coordinator.tick(0)
        coordinator.tick(1_000)
        coordinator.tick(2_000)
        assertEquals(FlightPhase.ARRIVAL_PLACEMENT, coordinator.snapshot().phase)
        assertEquals(arrival, coordinator.snapshot().point)
        coordinator.tick(3_000)
        assertEquals(FlightPhase.ARRIVAL_PLACEMENT, coordinator.snapshot().phase)
        coordinator.acknowledgePlacement(true)
        coordinator.tick(4_000)
        assertEquals(FlightPhase.SETTLING, coordinator.snapshot().phase)
        coordinator.tick(5_000)
        coordinator.tick(6_000)
        coordinator.tick(7_000)
        assertEquals(SessionState.HOLDING, coordinator.snapshot().state)
    }

    @Test fun manualFlightWaitsForObservedAirplaneAndRestoresOriginal() {
        val coordinator = flight(false)
        coordinator.tick(0)
        coordinator.tick(1_000)
        assertEquals(FlightPhase.AWAIT_AIRPLANE_MODE, coordinator.snapshot().phase)
        assertEquals(true, coordinator.snapshot().desiredAirplaneMode)
        coordinator.tick(2_000)
        assertEquals(FlightPhase.AWAIT_AIRPLANE_MODE, coordinator.snapshot().phase)
        coordinator.observeRadio(true)
        coordinator.tick(3_000)
        coordinator.tick(4_000)
        coordinator.acknowledgePlacement(true)
        coordinator.tick(5_000)
        coordinator.tick(6_000)
        assertEquals(FlightPhase.RESTORE_CONNECTIVITY, coordinator.snapshot().phase)
        assertEquals(false, coordinator.snapshot().desiredAirplaneMode)
        coordinator.tick(7_000)
        assertEquals(FlightPhase.RESTORE_CONNECTIVITY, coordinator.snapshot().phase)
        coordinator.observeRadio(false)
        coordinator.tick(8_000)
        assertEquals(FlightPhase.DESTINATION_GROUND, coordinator.snapshot().phase)
    }

    @Test fun initiallyEnabledAirplaneModeIsNeverTurnedOffByJourney() {
        val coordinator = flight(false, initialOn = true)
        coordinator.tick(0)
        coordinator.tick(1_000)
        coordinator.tick(2_000)
        coordinator.tick(3_000)
        coordinator.acknowledgePlacement(true)
        coordinator.tick(4_000)
        coordinator.tick(5_000)
        assertEquals(FlightPhase.RESTORE_CONNECTIVITY, coordinator.snapshot().phase)
        assertNull(coordinator.snapshot().desiredAirplaneMode)
        coordinator.tick(6_000)
        coordinator.tick(7_000)
        assertEquals(SessionState.HOLDING, coordinator.snapshot().state)
    }

    @Test fun stopExposesOnlyLiveRadioRecoveryThenForgetsIt() {
        val coordinator = flight(false)
        coordinator.tick(0)
        coordinator.tick(1_000)
        coordinator.observeRadio(true)
        coordinator.tick(2_000)
        assertEquals(false, coordinator.recoveryAirplaneState())
        coordinator.stop()
        assertNull(coordinator.recoveryAirplaneState())
        assertNull(coordinator.snapshot().point)
        assertNull(coordinator.snapshot().phase)
    }

    @Test fun airportGroundAccessPointsDoNotJumpToCatalogCentroids() {
        val destination = GeoPoint(0.0, 10.001)
        val arrivalAccess = GeoPoint(0.0, 10.0)
        val roads = GeoPack(id = "access", name = "Synthetic airport access", bounds = GeoBounds(-1.0, -1.0, 1.0, 11.0),
            nodes = listOf(GeoNode("a", a), GeoNode("n", GeoPoint(0.002, 0.0)), GeoNode("d", arrivalAccess), GeoNode("e", destination)),
            edges = listOf(GeoEdge("a", "n", setOf(Mode.DRIVE)), GeoEdge("d", "e", setOf(Mode.DRIVE))))
        val coordinator = Coordinator(pack = roads, airports = listOf(Airport("o", "Origin centroid", GeoPoint(0.0009, 0.0), type = "large_airport"), Airport("d", "Arrival centroid", GeoPoint(0.0009, 10.0), type = "large_airport")))
        val prepared = coordinator.prepare(PlanRequest(Mode.FLIGHT, a, destination, speedMps = 10.0, flight = FlightOptions(true, boardingMs = 0, flightMs = 1_000, settlingMs = 1_000, flightTransitMode = FlightTransitMode.JUMP)))
        assertEquals(a, prepared.preview.originAirportAccessPoint)
        assertEquals(arrivalAccess, prepared.preview.destinationAirportAccessPoint)
        assertTrue(distanceMeters(prepared.preview.originAirport!!.point, a) > 90)
        coordinator.commit(prepared.id); coordinator.start(0)
        assertEquals(a, coordinator.tick(0)!!.point)
        assertEquals(a, coordinator.tick(1_000)!!.point)
        assertEquals(arrivalAccess, coordinator.tick(2_000)!!.point)
        coordinator.acknowledgePlacement(true)
        coordinator.tick(3_000); coordinator.tick(4_000); coordinator.tick(5_000)
        val moving = coordinator.tick(6_000)!!.point
        assertTrue(distanceMeters(arrivalAccess, moving) <= 10)
        assertEquals(0.0, moving.lat, 1e-8)
    }

    @Test fun radioAndPlacementWaitsDoNotConsumeSimulatedTravelElapsed() {
        val coordinator = flight(false)
        coordinator.tick(0)
        coordinator.tick(1_000)
        val beforeRadio = coordinator.snapshot().elapsedMs
        for (time in 2_000L..10_000L step 1_000) coordinator.tick(time)
        assertEquals(beforeRadio, coordinator.snapshot().elapsedMs)
        coordinator.observeRadio(true); coordinator.tick(11_000); coordinator.tick(12_000)
        val beforePlacement = coordinator.snapshot().elapsedMs
        coordinator.tick(13_000); coordinator.tick(14_000)
        assertEquals(beforePlacement, coordinator.snapshot().elapsedMs)
        coordinator.acknowledgePlacement(true); coordinator.tick(15_000); coordinator.tick(16_000)
        val beforeRestore = coordinator.snapshot().elapsedMs
        coordinator.tick(17_000); coordinator.tick(18_000)
        assertEquals(beforeRestore, coordinator.snapshot().elapsedMs)
    }

    @Test fun radioInterventionAndFailedPlacementCannotSilentlyAdvance() {
        val coordinator = flight(false)
        coordinator.tick(0)
        coordinator.tick(1_000)
        coordinator.observeRadio(true)
        coordinator.tick(2_000)
        coordinator.observeRadio(false)
        assertEquals(SessionState.PAUSED, coordinator.snapshot().state)
        assertFailsWith<IllegalArgumentException> { coordinator.resume(2_000) }
        coordinator.observeRadio(true)
        coordinator.resume(2_000)
        coordinator.tick(3_000)
        coordinator.acknowledgePlacement(false)
        assertEquals(SessionState.ERROR, coordinator.snapshot().state)
        assertEquals("PROVIDER_SUBMISSION_FAILED", coordinator.snapshot().error)
    }

    @Test fun missingGroundCoverageFailsBeforeFlightCommit() {
        val coordinator = Coordinator(airports = listOf(Airport("o", "Origin", b, type = "large_airport"), Airport("d", "Destination", arrival, type = "large_airport")))
        assertFailsWith<RoutePlanningException> { coordinator.prepare(PlanRequest(Mode.FLIGHT, a, arrival)) }
        assertEquals(SessionState.IDLE, coordinator.snapshot().state)
    }
}

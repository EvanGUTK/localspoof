package dev.localspoof.domain

import org.junit.jupiter.api.Test
import kotlin.test.*

class OnlineRouteTest {
    private val origin = GeoPoint(0.0, 0.0)
    private val destination = GeoPoint(0.0, 0.002)
    private fun route(profile: Mode = Mode.DRIVE, points: List<GeoPoint> = listOf(origin, destination)) =
        OnlineRoute(profile, "FOSSGIS OSRM", points)

    @Test fun onlineRoadGeometryNeedsNoPackAndPreservesItsProvenance() {
        val snappedStart = GeoPoint(0.0, 0.0001)
        val bend = GeoPoint(0.001, 0.001)
        val snappedEnd = GeoPoint(0.0, 0.0019)
        val coordinator = Coordinator()
        val prepared = coordinator.prepare(PlanRequest(Mode.DRIVE, origin, destination, speedMps = 10.0,
            onlineRoute = route(points = listOf(snappedStart, bend, snappedEnd))))
        assertEquals(RouteSource.ONLINE_ROUTER, prepared.preview.routeSource)
        assertEquals("FOSSGIS OSRM", prepared.preview.routeProvider)
        assertFalse(prepared.preview.customPath)
        assertEquals(snappedStart, prepared.preview.origin)
        assertEquals(snappedEnd, prepared.preview.destination)
        assertTrue(prepared.preview.distanceMeters > distanceMeters(snappedStart, snappedEnd))
        coordinator.commit(prepared.id); coordinator.start(0)
        assertEquals(snappedStart, coordinator.tick(0)!!.point)
        assertTrue(coordinator.tick(1_000)!!.speedMps <= 10.0)
    }

    @Test fun geometryIsFrozenBeforeCommitEvenWhenDesktopListMutates() {
        val points = mutableListOf(origin, destination)
        val coordinator = Coordinator()
        val prepared = coordinator.prepare(PlanRequest(Mode.WALK, origin, destination, onlineRoute = route(Mode.WALK, points)))
        points.clear()
        coordinator.commit(prepared.id); coordinator.start(0)
        assertEquals(origin, coordinator.tick(0)!!.point)
        assertEquals(listOf(origin, destination), coordinator.snapshot().preparedPreview!!.geometry)
    }

    @Test fun distantEndpointWrongProfileAndConflictingCustomRouteAreRejected() {
        assertFailsWith<IllegalArgumentException> { Coordinator().prepare(PlanRequest(Mode.DRIVE, origin, destination,
            onlineRoute = route(points = listOf(GeoPoint(0.0, 0.01), destination)))) }
        assertFailsWith<IllegalArgumentException> { PlanRequest(Mode.WALK, origin, destination, onlineRoute = route()) }
        assertFailsWith<IllegalArgumentException> { PlanRequest(Mode.DRIVE, origin, destination, customPath = listOf(origin, destination), onlineRoute = route()) }
        assertFailsWith<IllegalArgumentException> { PlanRequest(Mode.INSTANT, origin, destination, onlineRoute = route()) }
        assertFailsWith<IllegalArgumentException> { route(points = listOf(origin)).geometry(origin, destination, Mode.DRIVE) }
    }

    @Test fun routeSizeLongSegmentsTotalLengthAndProviderLabelsAreBounded() {
        assertFailsWith<IllegalArgumentException> { route(points = emptyList()) }
        assertFailsWith<IllegalArgumentException> { route(points = List(MAX_ONLINE_ROUTE_POINTS + 1) { origin }) }
        assertFailsWith<IllegalArgumentException> { route(points = listOf(origin, GeoPoint(0.0, 2.0))) }
        assertFailsWith<IllegalArgumentException> { route(points = List(402) { if (it % 2 == 0) origin else GeoPoint(0.0, 0.5) }) }
        assertFailsWith<IllegalArgumentException> { OnlineRoute(Mode.DRIVE, "bad\nprovider", listOf(origin, destination)) }
        assertFailsWith<IllegalArgumentException> { OnlineRoute(Mode.FLIGHT, "router", listOf(origin, destination)) }
        assertFailsWith<IllegalArgumentException> { Coordinator().prepare(PlanRequest(Mode.DRIVE, origin, destination,
            waypointDwellsMs = mapOf(4 to 1_000), onlineRoute = route())) }
    }

    private val arrival = GeoPoint(1.0, 1.0)
    private val airports = listOf(Airport("departure", "Departure", origin, type = "large_airport"), Airport("arrival", "Arrival", arrival, type = "large_airport"))
    private fun flightRoutes() = OnlineFlightRoutes("departure", "arrival",
        route(points = listOf(GeoPoint(0.0, 0.0001))), route(points = listOf(GeoPoint(1.0, 1.0001))))
    private fun flightRequest(legs: OnlineFlightRoutes = flightRoutes(), options: FlightOptions = FlightOptions(
        simulationOnly = true, boardingMs = 0, flightMs = 1_000, settlingMs = 1_000, flightTransitMode = FlightTransitMode.JUMP)) =
        PlanRequest(Mode.FLIGHT, origin, arrival, speedMps = 10.0, flight = options, onlineFlightRoutes = legs)

    @Test fun onlineFlightKeepsPhonePhasesAndPlacementAcknowledgment() {
        val legs = flightRoutes()
        val coordinator = Coordinator(airports = airports)
        val prepared = coordinator.prepare(flightRequest(legs))
        assertEquals(RouteSource.FLIGHT_JOURNEY, prepared.preview.routeSource)
        assertEquals(legs.originGround!!.points.last(), prepared.preview.originAirportAccessPoint)
        assertEquals(legs.destinationGround!!.points.first(), prepared.preview.destinationAirportAccessPoint)
        coordinator.commit(prepared.id); coordinator.start(0)
        assertEquals(legs.originGround!!.points.first(), coordinator.tick(0)!!.point)
        coordinator.tick(1_000)
        assertEquals(legs.destinationGround!!.points.first(), coordinator.tick(2_000)!!.point)
        assertEquals(FlightPhase.ARRIVAL_PLACEMENT, coordinator.snapshot().phase)
        coordinator.tick(3_000)
        assertEquals(FlightPhase.ARRIVAL_PLACEMENT, coordinator.snapshot().phase)
        coordinator.acknowledgePlacement(true)
        coordinator.tick(4_000); coordinator.tick(5_000); coordinator.tick(6_000); coordinator.tick(7_000)
        assertEquals(SessionState.HOLDING, coordinator.snapshot().state)
    }

    @Test fun flightAirportSelectionsProfilesCoverageAndCombinedSizeAreChecked() {
        assertFailsWith<RoutePlanningException> { Coordinator().prepare(flightRequest()) }
        assertFailsWith<IllegalArgumentException> { Coordinator(airports = airports).prepare(flightRequest(options = FlightOptions(originAirportId = "other"))) }
        assertFailsWith<IllegalArgumentException> { OnlineFlightRoutes("departure", "arrival", route(Mode.WALK), route()) }
        assertFailsWith<IllegalArgumentException> { OnlineFlightRoutes("departure", "arrival", route(points = List(6001) { origin }), route(points = List(6000) { arrival })) }
        assertFailsWith<IllegalArgumentException> { Coordinator(airports = airports).prepare(flightRequest(legs = flightRoutes().copy(
            originGround = route(points = listOf(origin, GeoPoint(0.0, 0.05)))))) }
        assertFailsWith<IllegalArgumentException> { PlanRequest(Mode.DRIVE, origin, destination, onlineFlightRoutes = flightRoutes()) }
        assertFailsWith<IllegalArgumentException> { flightRequest(options = FlightOptions(groundMode = Mode.WALK)) }
    }

    @Test fun onlineAirLegUsesDisclosedRoadAccessWithinFiveKmOfAirportCentroids() {
        val departureAccess = GeoPoint(0.0, 0.01)
        val arrivalAccess = GeoPoint(1.0, 1.01)
        val legs = OnlineFlightRoutes("departure", "arrival",
            route(points = listOf(origin, departureAccess)), route(points = listOf(arrivalAccess, arrival)))
        val coordinator = Coordinator(airports = airports)
        val prepared = coordinator.prepare(flightRequest(legs))
        assertEquals(departureAccess, prepared.preview.originAirportAccessPoint)
        assertEquals(arrivalAccess, prepared.preview.destinationAirportAccessPoint)
        assertTrue(prepared.preview.originAirportAccessDistanceMeters!! in 250.0..5_000.0)
        assertTrue(prepared.preview.destinationAirportAccessDistanceMeters!! in 250.0..5_000.0)
        val expected = distanceMeters(origin, departureAccess) + distanceMeters(departureAccess, arrivalAccess) + distanceMeters(arrivalAccess, arrival)
        assertEquals(expected, prepared.preview.distanceMeters, 0.001)
        coordinator.commit(prepared.id); coordinator.start(0); coordinator.tick(0)
        var now = 0L
        while (coordinator.snapshot().phase != FlightPhase.FLIGHT_INTERVAL && now < 500_000) {
            now += 1_000; coordinator.tick(now)
        }
        assertEquals(FlightPhase.FLIGHT_INTERVAL, coordinator.snapshot().phase)
        assertEquals(departureAccess, coordinator.snapshot().point)
        assertEquals(arrivalAccess, coordinator.tick(now + 1_000)!!.point)
        assertEquals(FlightPhase.ARRIVAL_PLACEMENT, coordinator.snapshot().phase)
    }

    @Test fun airportAccessBeyondFiveKmAndUserEndpointsBeyond250mStillReject() {
        val coordinator = Coordinator(airports = airports)
        assertFailsWith<IllegalArgumentException> { coordinator.prepare(flightRequest(flightRoutes().copy(
            originGround = route(points = listOf(origin, GeoPoint(0.0, 0.05)))))) }
        assertFailsWith<IllegalArgumentException> { coordinator.prepare(flightRequest(flightRoutes().copy(
            destinationGround = route(points = listOf(GeoPoint(1.0, 1.05), arrival))))) }
        assertFailsWith<IllegalArgumentException> { coordinator.prepare(flightRequest(flightRoutes().copy(
            originGround = route(points = listOf(GeoPoint(0.0, 0.004), GeoPoint(0.0, 0.01)))))) }
        assertFailsWith<IllegalArgumentException> { Coordinator().prepare(PlanRequest(Mode.DRIVE, origin, destination,
            onlineRoute = route(points = listOf(origin, GeoPoint(0.0, 0.01))))) }
    }
}

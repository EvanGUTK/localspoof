package dev.localspoof.android

import dev.localspoof.domain.*
import org.junit.Assert.*
import org.junit.Test

class MobileOnlinePlannerTest {
    private val start=GeoPoint(1.0,1.0)
    private val end=GeoPoint(1.0,1.2)
    private class Data:MobilePlanningData {
        var calls=0
        var afterRoad:()->Unit = {}
        var fail:String?=null
        var unreachableAirports=emptySet<String>()
        var snap:GeoPoint?=null
        var fuelStations=emptyList<MobileFuelStation>()
        override fun road(origin:GeoPoint,destination:GeoPoint,profile:Mode,originAirport:Airport?,destinationAirport:Airport?,cancelled:()->Boolean):MobileRoadRoute {
            check(!cancelled());calls++;fail?.let { throw IllegalArgumentException(it) }
            if(originAirport?.id in unreachableAirports || destinationAirport?.id in unreachableAirports) throw IllegalArgumentException("NO_ROUTE")
            val points=listOf(snap ?: origin,destination)
            afterRoad()
            return MobileRoadRoute(OnlineRoute(profile,"fixture",points),distanceMeters(points.first(),destination),10.0)
        }
        override fun stations(center:GeoPoint,cancelled:()->Boolean)=fuelStations
        override fun vehicle(selection:VehicleSelection,cancelled:()->Boolean)=ByteArray(0)
    }
    @Test fun roadStopsAreVisitedInOrderAndHotelDurationRetained() {
        val data=Data();val hotel=HotelStop(5000,10000,5000,1,100.0,0.0)
        val stop=RouteStop(GeoPoint(1.0,1.1),hotel.durationMs,"Hotel",StopKind.HOTEL,hotel=hotel)
        val planned=MobileOnlinePlanner(data).plan(PlanRequest(Mode.DRIVE,start,end,stops=listOf(stop)),emptyList())
        assertEquals(2,data.calls);assertEquals(listOf(1),planned.stopPointIndices)
        assertEquals(listOf(start,stop.point,end),planned.onlineRoute!!.points)
        assertTrue(planned.onlineRoute!!.edgeSpeedLimits.isEmpty())
        assertEquals(hotel,planned.stops.single().hotel)
        assertEquals(RouteSource.ONLINE_ROUTER,Coordinator().prepare(planned).preview.routeSource)
    }
    @Test fun noRouteNeverFallsBackToStraightLineAndHeldStartCannotJump() {
        val data=Data();data.fail="NO_ROUTE"
        assertThrows(IllegalArgumentException::class.java) { MobileOnlinePlanner(data).plan(PlanRequest(Mode.WALK,start,end),emptyList()) }
        data.fail=null;data.snap=GeoPoint(1.0,1.0001)
        val failure=assertThrows(IllegalArgumentException::class.java) { MobileOnlinePlanner(data).plan(PlanRequest(Mode.DRIVE,start,end),emptyList(),MobilePlanningOptions(remaining=true)) }
        assertEquals("EDIT_ROUTE_START_MISMATCH",failure.message)
    }
    @Test fun flightUsesOnlyMajorOpenScheduledAirportsAndRemainingDoesNotReplayDeparture() {
        val data=Data()
        val airports=listOf(Airport("tiny","Closer",start,type="medium_airport"),Airport("closed","Closed",start,type="large_airport",closed=true),Airport("a","Major A",GeoPoint(1.0,1.01),type="large_airport"),Airport("b","Major B",GeoPoint(1.0,1.19),type="large_airport"))
        val request=PlanRequest(Mode.FLIGHT,start,end,flight=FlightOptions())
        val initial=MobileOnlinePlanner(data).plan(request,airports)
        assertEquals("a",initial.flight!!.originAirportId);assertEquals("b",initial.flight!!.destinationAirportId)
        assertNotNull(initial.onlineFlightRoutes!!.originGround)
        data.calls=0
        val remaining=MobileOnlinePlanner(data).plan(request.copy(flight=initial.flight),airports,MobilePlanningOptions(remainingPhase=FlightPhase.FLIGHT_INTERVAL,remaining=true))
        assertNull(remaining.onlineFlightRoutes!!.originGround);assertNotNull(remaining.onlineFlightRoutes!!.destinationGround);assertEquals(1,data.calls)
        data.calls=0
        val arrivalOrigin=GeoPoint(1.0,1.191)
        val arrived=MobileOnlinePlanner(data).plan(request.copy(origin=arrivalOrigin,flight=initial.flight),airports,MobilePlanningOptions(remainingPhase=FlightPhase.DESTINATION_GROUND,remaining=true))
        assertEquals(arrivalOrigin,arrived.onlineFlightRoutes!!.destinationGround!!.points.first());assertEquals(1,data.calls)
        assertThrows(IllegalArgumentException::class.java) { MobileOnlinePlanner(data).plan(request.copy(flight=FlightOptions(originAirportId="tiny",destinationAirportId="b")),airports) }
    }
    @Test fun unreachableNearestAirportIsTriedOnceThenAccessibleMajorAirportChosen() {
        val data=Data();data.unreachableAirports=setOf("isolated")
        val airports=listOf(Airport("isolated","No road access",GeoPoint(1.0,1.001),type="large_airport"),Airport("a","A",GeoPoint(1.0,1.01),type="large_airport"),Airport("b","B",GeoPoint(1.0,1.19),type="large_airport"))
        val planned=MobileOnlinePlanner(data).plan(PlanRequest(Mode.FLIGHT,start,end),airports)
        assertEquals("a",planned.flight!!.originAirportId);assertEquals("b",planned.flight!!.destinationAirportId)
        assertEquals(3,data.calls)
    }
    @Test fun automaticStationIncludesReachableDetourAndExplicitStartingFuel() {
        val data=Data();data.fuelStations=listOf(MobileFuelStation("Gas",GeoPoint(1.0,1.08)))
        val config=VacationConfig(10.0,1.2,0.2,10.0,FuelGrade.REGULAR,fuelPricePerLiter=2.0)
        val planned=MobileOnlinePlanner(data).plan(PlanRequest(Mode.DRIVE,start,end,vacation=config),emptyList(),MobilePlanningOptions(automaticFuelStops=true))
        val stop=planned.stops.single();assertEquals(StopKind.FUEL,stop.kind);assertTrue(stop.id.startsWith("auto_fuel_"));assertTrue(stop.label.contains("verify"))
        assertEquals(10.0,stop.fuel!!.refillTargetLiters,0.0)
        assertEquals(listOf(1),planned.stopPointIndices)
        Coordinator().prepare(planned) // Domain independently checks reserve on complete routed geometry.
        assertThrows(IllegalArgumentException::class.java) { MobileOnlinePlanner(data).plan(PlanRequest(Mode.DRIVE,start,end,vacation=config),emptyList(),MobilePlanningOptions(automaticFuelStops=true,currentFuelLiters=0.2)) }
    }
    @Test fun manualRefillPreservedAndUnavailableStationsNeverInvented() {
        val data=Data();val config=VacationConfig(10.0,1.2,0.2,10.0,FuelGrade.DIESEL)
        val station=RouteStop(GeoPoint(1.0,1.08),1000,"Manual fuel",StopKind.FUEL,FuelStop(10.0),id="manual_station")
        val request=PlanRequest(Mode.DRIVE,start,end,stops=listOf(station),vacation=config)
        val planned=MobileOnlinePlanner(data).plan(request,emptyList(),MobilePlanningOptions(automaticFuelStops=true))
        assertEquals(station,planned.stops.single())
        val failure=assertThrows(IllegalArgumentException::class.java) { MobileOnlinePlanner(data).plan(request.copy(stops=emptyList()),emptyList(),MobilePlanningOptions(automaticFuelStops=true)) }
        assertEquals("NO_REACHABLE_FUEL_STATION",failure.message)
        assertThrows(IllegalArgumentException::class.java) { MobileOnlinePlanner(data).plan(request,emptyList(),MobilePlanningOptions(automaticFuelStops=true,ongoingRefill=true)) }
    }
    @Test fun cancelledWorkHasNoNativeCallsAndClosedPlannerRejects() {
        val data=Data();val planner=MobileOnlinePlanner(data)
        assertThrows(IllegalStateException::class.java) { planner.plan(PlanRequest(Mode.DRIVE,start,end),emptyList(),cancelled={true}) }
        assertEquals(0,data.calls);planner.close()
        assertThrows(IllegalStateException::class.java) { planner.plan(PlanRequest(Mode.DRIVE,start,end),emptyList()) }
    }
    @Test fun lateResponseAfterCancellationOrDeadlineIsDiscarded() {
        val data=Data();var cancel=false;data.afterRoad={ cancel=true }
        assertThrows(IllegalStateException::class.java) { MobileOnlinePlanner(data).plan(PlanRequest(Mode.DRIVE,start,end),emptyList(),cancelled={cancel}) }
        assertEquals(1,data.calls)
        var time=0L;data.afterRoad={ time=90_000_000_000L }
        val failure=assertThrows(IllegalStateException::class.java) { MobileOnlinePlanner(data) {time}.plan(PlanRequest(Mode.DRIVE,start,end),emptyList()) }
        assertEquals("PLANNING_TIMEOUT",failure.message)
    }
    @Test fun mismatchedStopAccessIsRejectedInsteadOfHiddenConnector() {
        val data=object:MobilePlanningData {
            var calls=0
            override fun road(origin:GeoPoint,destination:GeoPoint,profile:Mode,originAirport:Airport?,destinationAirport:Airport?,cancelled:()->Boolean):MobileRoadRoute {
                val snapped=if(++calls==2) GeoPoint(origin.lat,origin.lon+0.001) else origin
                return MobileRoadRoute(OnlineRoute(profile,"fixture",listOf(snapped,destination)),distanceMeters(snapped,destination),10.0)
            }
            override fun stations(center:GeoPoint,cancelled:()->Boolean)=emptyList<MobileFuelStation>()
            override fun vehicle(selection:VehicleSelection,cancelled:()->Boolean)=ByteArray(0)
        }
        val failure=assertThrows(IllegalArgumentException::class.java) { MobileOnlinePlanner(data).plan(PlanRequest(Mode.DRIVE,start,end,stops=listOf(RouteStop(GeoPoint(1.0,1.1)))),emptyList()) }
        assertEquals("STOP_ACCESS_INCONSISTENT",failure.message)
    }
    @Test fun routeDecoderBoundsSnapsAndDoesNotTreatDurationAsMappedSpeed() {
        val bytes="""{"code":"Ok","routes":[{"distance":222.36,"duration":10,"geometry":{"type":"LineString","coordinates":[[1,1],[1.002,1]]}}]}""".toByteArray()
        val route=MobilePlanningNetwork.decodeRoad(bytes,start,GeoPoint(1.0,1.002),Mode.DRIVE)
        assertTrue(route.route.edgeSpeedLimits.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { MobilePlanningNetwork.decodeRoad(bytes,GeoPoint(2.0,2.0),end,Mode.DRIVE) }
        assertThrows(IllegalArgumentException::class.java) { MobilePlanningNetwork.decodeRoad(bytes.toString(Charsets.UTF_8).replace("222.36","50000").toByteArray(),start,GeoPoint(1.0,1.002),Mode.DRIVE) }
        assertThrows(IllegalArgumentException::class.java) { MobilePlanningNetwork.roadAddress(start,end,Mode.DRIVE,originAirport=Airport("bad","Wrong",start,type="medium_airport")) }
    }
}

package dev.localspoof.android

import dev.localspoof.domain.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal data class MobilePlanningOptions(
    val remainingPhase: FlightPhase? = null,
    val remaining: Boolean = false,
    val automaticFuelStops: Boolean = false,
    val currentFuelLiters: Double? = null,
    val refillMs: Long = 300_000,
    val ongoingRefill: Boolean = false,
)

/** Planning does not own playback, providers, consent, radio settings, or apply authority. */
internal class MobileOnlinePlanner(private val data: MobilePlanningData = MobilePlanningNetwork(), private val nanoTime: () -> Long = System::nanoTime) {
    private val generation = AtomicLong(0)
    @Volatile private var closed = false
    fun cancelRequests() { generation.incrementAndGet();data.cancelRequests() }
    fun close() { closed=true;cancelRequests();data.close() }

    @Synchronized fun plan(request: PlanRequest, airports: List<Airport>, options: MobilePlanningOptions = MobilePlanningOptions(), cancelled: () -> Boolean = { false }): PlanRequest {
        val owner=generation.get();val started=nanoTime();var calls=0
        fun live(): Boolean = closed || generation.get()!=owner || cancelled() || Thread.currentThread().isInterrupted || nanoTime()-started >= 90_000_000_000L
        fun checkLive() { check(!live()) { if(nanoTime()-started >= 90_000_000_000L) "PLANNING_TIMEOUT" else "PLANNING_CANCELLED" } }
        fun road(a:GeoPoint,b:GeoPoint,profile:Mode,originAirport:Airport?=null,destinationAirport:Airport?=null):MobileRoadRoute {
            checkLive();require(++calls<=60) { "PLANNING_REQUEST_LIMIT" }
            return data.road(a,b,profile,originAirport,destinationAirport,::live).also { checkLive() }
        }
        fun stations(point:GeoPoint):List<MobileFuelStation> {
            checkLive();require(++calls<=60) { "PLANNING_REQUEST_LIMIT" }
            return data.stations(point,::live).also { checkLive();require(it.size<=8) { "STATION_RESPONSE_TOO_LARGE" } }
        }
        checkLive()
        if(request.mode==Mode.INSTANT || request.customPath!=null) return request
        if(request.mode==Mode.FLIGHT) {
            require(!options.automaticFuelStops) { "VACATION_REQUIRES_DRIVE" }
            val settings=request.flight ?: FlightOptions()
            fun candidates(id:String?,point:GeoPoint):List<Airport> {
                val allowed=airports.filter { it.type=="large_airport" && it.scheduledService && !it.closed }
                if(id!=null) return listOf(allowed.find { it.id==id } ?: throw IllegalArgumentException("AIRPORT_NOT_ELIGIBLE"))
                return allowed.sortedBy { distanceMeters(point,it.point) }.take(8).also { require(it.isNotEmpty()) { "NO_ELIGIBLE_AIRPORT" } }
            }
            val afterDeparture=options.remaining && options.remainingPhase !in setOf(FlightPhase.ORIGIN_GROUND,FlightPhase.ORIGIN_PLACEMENT)
            val afterArrival=options.remaining && options.remainingPhase in setOf(FlightPhase.ARRIVAL_PLACEMENT,FlightPhase.SETTLING,FlightPhase.RESTORE_CONNECTIVITY,FlightPhase.DESTINATION_GROUND,FlightPhase.DESTINATION_PLACEMENT,FlightPhase.COMPLETE)
            if(afterDeparture) require(settings.originAirportId!=null) { "DEPARTURE_AIRPORT_LOCKED" }
            if(afterArrival) require(settings.destinationAirportId!=null) { "ARRIVAL_AIRPORT_LOCKED" }
            val departures=candidates(settings.originAirportId,request.origin)
            val arrivals=candidates(settings.destinationAirportId,request.destination)
            var lastFailure:Exception?=null
            val departureRoutes=mutableMapOf<String,MobileRoadRoute?>()
            val arrivalRoutes=mutableMapOf<String,MobileRoadRoute?>()
            val unreachableDepartures=mutableSetOf<String>();val unreachableArrivals=mutableSetOf<String>()
            fun cachedRoad(cache:MutableMap<String,MobileRoadRoute?>,unreachable:MutableSet<String>,airport:Airport,fetch:()->MobileRoadRoute):MobileRoadRoute? {
                if(cache.containsKey(airport.id)) return cache[airport.id]
                return try { fetch().also { cache[airport.id]=it } } catch(failure:Exception) {
                    if(failure.message in setOf("NO_ROUTE","ROUTE_ENDPOINT_TOO_FAR","ONLINE_ROUTE_ENDPOINT_MISMATCH")) unreachable+=airport.id
                    throw failure
                }
            }
            for(departure in departures) for(arrival in arrivals) {
                checkLive();if(departure.id==arrival.id || departure.id in unreachableDepartures || arrival.id in unreachableArrivals) continue
                try {
                    val first=if(afterDeparture || settings.departureGroundMode==GroundLegMode.INSTANT) null else cachedRoad(departureRoutes,unreachableDepartures,departure) { road(request.origin,departure.point,Mode.DRIVE,destinationAirport=departure) }
                    val last=if(settings.arrivalGroundMode==GroundLegMode.INSTANT) null else cachedRoad(arrivalRoutes,unreachableArrivals,arrival) {
                        val origin=if(afterArrival) request.origin else arrival.point
                        road(origin,request.destination,Mode.DRIVE,originAirport=arrival.takeIf { origin==it.point })
                    }
                    if(options.remaining && !afterDeparture) requireHeldOrigin(request.origin,first?.route)
                    if(afterArrival) requireHeldOrigin(request.origin,last?.route)
                    return request.copy(flight=settings.copy(originAirportId=departure.id,destinationAirportId=arrival.id),onlineFlightRoutes=OnlineFlightRoutes(departure.id,arrival.id,first?.route,last?.route),remainingFlight=options.remaining)
                } catch(failure:Exception) {
                    checkLive()
                    if(failure.message !in setOf("NO_ROUTE","ROUTE_ENDPOINT_TOO_FAR","ONLINE_ROUTE_ENDPOINT_MISMATCH")) throw failure
                    lastFailure=failure
                }
            }
            throw IllegalArgumentException("NO_REACHABLE_MAJOR_AIRPORT_PAIR",lastFailure)
        }
        require(request.mode in setOf(Mode.WALK,Mode.DRIVE))
        val config=request.vacation
        require(!options.automaticFuelStops || config!=null && request.mode==Mode.DRIVE) { "VACATION_REQUIRES_DRIVE" }
        require(!options.automaticFuelStops || !options.ongoingRefill) { "FINISH_CURRENT_REFILL_BEFORE_AUTOMATIC_REPLAN" }
        require(options.refillMs in 1_000..3_600_000) { "INVALID_REFILL_DURATION" }
        var fuel=options.currentFuelLiters ?: config?.startingFuelLiters ?: 0.0
        if(config!=null) require(fuel.isFinite() && fuel in 0.0..config.tankLiters) { "INVALID_STARTING_FUEL" }
        val manualStops=request.stops.filter { !options.automaticFuelStops || !it.id.startsWith("auto_fuel_") }
        val stops=mutableListOf<RouteStop>();val legs=mutableListOf<MobileRoadRoute>();var origin=request.origin
        val targets=manualStops.map { it.point to it } + (request.destination to null)
        for((target,manual) in targets) {
            var route=road(origin,target,request.mode)
            while(config!=null && consumption(route.distanceMeters,config)>fuel-config.reserveLiters+1e-7) {
                require(options.automaticFuelStops) { "FUEL_RESERVE_UNREACHABLE" }
                require(stops.size+manualStops.size<32) { "TOO_MANY_FUEL_STOPS" }
                val range=(fuel-config.reserveLiters).coerceAtLeast(0.0)*100_000/config.litersPer100Km
                require(range>100) { "NO_REACHABLE_FUEL_STATION" }
                val seen=mutableSetOf<GeoPoint>();var selected:Triple<MobileFuelStation,MobileRoadRoute,MobileRoadRoute>?=null
                for(fraction in listOf(0.8,0.6,0.4)) {
                    val center=pointAlong(route.route.points,minOf(range*fraction,route.distanceMeters))
                    for(station in stations(center)) {
                        if(!seen.add(station.point) || distanceMeters(origin,station.point)<100 || distanceMeters(center,station.point)>10_000) continue
                        try {
                            val enter=road(origin,station.point,Mode.DRIVE)
                            if(consumption(enter.distanceMeters,config)>fuel-config.reserveLiters+1e-7) continue
                            val leave=road(station.point,target,Mode.DRIVE)
                            if(leave.distanceMeters>=route.distanceMeters-100 || enter.distanceMeters+leave.distanceMeters-route.distanceMeters>20_000) continue
                            require(distanceMeters(enter.route.points.last(),leave.route.points.first())<=0.5) { "STATION_ACCESS_INCONSISTENT" }
                            selected=Triple(station,enter,leave);break
                        } catch(failure:Exception) { checkLive();if(failure.message !in setOf("NO_ROUTE","ROUTE_ENDPOINT_TOO_FAR","STATION_ACCESS_INCONSISTENT")) throw failure }
                    }
                    if(selected!=null) break
                }
                val pick=selected ?: throw IllegalArgumentException("NO_REACHABLE_FUEL_STATION")
                legs+=pick.second;fuel-=consumption(pick.second.distanceMeters,config)
                val label=pick.first.name.take(75)+" · verify "+config.fuelGrade.name.lowercase()+" availability"
                stops+=RouteStop(pick.first.point,options.refillMs,label,StopKind.FUEL,FuelStop(config.tankLiters,config.fuelPricePerLiter,config.currency),id="auto_fuel_"+UUID.randomUUID().toString().replace("-",""))
                fuel=config.tankLiters;origin=pick.first.point;route=pick.third
            }
            legs+=route
            if(config!=null) fuel-=consumption(route.distanceMeters,config)
            if(manual!=null) {
                stops+=manual;manual.fuel?.let { refill -> require(config!=null && refill.refillTargetLiters<=config.tankLiters) { "INVALID_REFILL_TARGET" };fuel=maxOf(fuel,refill.refillTargetLiters) }
                origin=target
            }
        }
        checkLive();require(stops.size<=32)
        val points=mutableListOf<GeoPoint>();val indices=mutableListOf<Int>()
        legs.forEachIndexed { index,leg ->
            if(points.isEmpty()) points+=leg.route.points
            else {
                require(distanceMeters(points.last(),leg.route.points.first())<=0.5) { "STOP_ACCESS_INCONSISTENT" }
                points+=leg.route.points.drop(1)
            }
            require(points.size<=MAX_ONLINE_ROUTE_POINTS) { "ROUTE_TOO_COMPLEX" }
            if(index<stops.size) indices+=points.lastIndex
        }
        val joined=OnlineRoute(request.mode,"FOSSGIS OSRM",points)
        if(options.remaining) requireHeldOrigin(request.origin,joined)
        return request.copy(onlineRoute=joined,onlineFlightRoutes=null,stops=stops,stopPointIndices=indices)
    }
    companion object {
        private fun consumption(meters:Double,config:VacationConfig)=meters*config.litersPer100Km/100_000
        private fun requireHeldOrigin(origin:GeoPoint,route:OnlineRoute?) { if(route!=null) require(distanceMeters(origin,route.points.first())<0.1) { "EDIT_ROUTE_START_MISMATCH" } }
        internal fun pointAlong(points:List<GeoPoint>,meters:Double):GeoPoint {
            var remaining=meters
            for((a,b) in points.zipWithNext()) { val length=distanceMeters(a,b);if(remaining<=length) return interpolate(a,b,if(length==0.0) 0.0 else remaining/length);remaining-=length }
            return points.last()
        }
    }
}

/** Fixed explanations only: never expose a provider response, URL, or coordinates through errors. */
internal fun mobilePlanningFailureMessage(code:String?):String = when(code) {
    "NO_ROUTE","STOP_ACCESS_INCONSISTENT","STATION_ACCESS_INCONSISTENT" -> "No continuous road route was found. Move the stop to a reachable road, choose a different station, or explicitly select an offline/custom route."
    "ROUTE_ENDPOINT_TOO_FAR","ONLINE_ROUTE_ENDPOINT_MISMATCH" -> "A selected place is too far from a mapped road. Refine the place or choose a supported instant airport leg."
    "EDIT_ROUTE_START_MISMATCH" -> "The new road route would move the current dot. Keep the active route and choose a destination or route that connects to its current position."
    "NO_REACHABLE_MAJOR_AIRPORT_PAIR","NO_ELIGIBLE_AIRPORT","AIRPORT_NOT_ELIGIBLE" -> "No reachable eligible major airport pair was found. Choose different major airports or an explicit instant airport leg."
    "FUEL_RESERVE_UNREACHABLE","NO_REACHABLE_FUEL_STATION","UNREACHABLE_FUEL_STOP","FUEL_RESERVE_EXHAUSTED" -> "The entered fuel cannot reach a mapped road-access stop while preserving reserve. Add a closer fuel stop, correct the fuel estimate, or choose a different route. Your active journey is retained."
    "FINISH_CURRENT_REFILL_BEFORE_AUTOMATIC_REPLAN" -> "Finish the current refill before recalculating automatic stations. You can still edit future stops manually."
    "PLANNING_REQUEST_LIMIT","ROUTE_TOO_COMPLEX","TOO_MANY_FUEL_STOPS" -> "This plan exceeds the bounded planning limit. Shorten the journey or add manually selected stations and try again."
    "PLANNING_TIMEOUT","PLANNING_PROVIDER_UNAVAILABLE" -> "The online planner is unavailable or timed out. Retry when connected, or explicitly choose a supported offline/custom route."
    "DEPARTURE_AIRPORT_LOCKED","ARRIVAL_AIRPORT_LOCKED","STALE_REVISION" -> "The journey advanced while planning. Refresh the remaining edit from the current phase before trying again."
    else -> "Unable to prepare. Check coordinates, speeds, fuel, and reachable road/airport coverage. Offline coverage or a custom route can be selected explicitly."
}

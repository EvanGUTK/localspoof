package dev.localspoof.android

import dev.localspoof.domain.*
import kotlinx.serialization.json.*
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection

internal data class MobileRoadRoute(val route: OnlineRoute, val distanceMeters: Double, val durationSeconds: Double)
internal data class MobileFuelStation(val name: String, val point: GeoPoint)
internal enum class VehicleResource { YEAR, MAKE, MODEL, OPTIONS, VEHICLE, PRICES }
internal data class VehicleSelection(val resource: VehicleResource, val year: Int? = null, val make: String? = null, val model: String? = null, val id: Int? = null)

/** Structured provider requests only. Tests replace this boundary, never the authoritative domain. */
internal interface MobilePlanningData {
    fun road(origin: GeoPoint, destination: GeoPoint, profile: Mode, originAirport: Airport?, destinationAirport: Airport?, cancelled: () -> Boolean): MobileRoadRoute
    fun stations(center: GeoPoint, cancelled: () -> Boolean): List<MobileFuelStation>
    fun vehicle(selection: VehicleSelection, cancelled: () -> Boolean): ByteArray
    fun cancelRequests() {}
    fun close() { cancelRequests() }
}

/** Fixed HTTPS providers, one active request, bounded time/body, no redirects/cookies/cache/history. */
internal class MobilePlanningNetwork : MobilePlanningData {
    private val generation=AtomicLong(0)
    private val slot=Semaphore(1,true)
    private val connections=java.util.concurrent.ConcurrentHashMap.newKeySet<HttpsURLConnection>()
    @Volatile private var closed=false
    private var nextRequestNanos=0L
    override fun cancelRequests() { generation.incrementAndGet();connections.forEach { it.disconnect() };connections.clear() }
    override fun close() { closed=true;cancelRequests() }
    override fun road(origin:GeoPoint,destination:GeoPoint,profile:Mode,originAirport:Airport?,destinationAirport:Airport?,cancelled:()->Boolean):MobileRoadRoute {
        val address=roadAddress(origin,destination,profile,originAirport,destinationAirport)
        val bytes=get(address,4*1024*1024,cancelled)
        return decodeRoad(bytes,origin,destination,profile,if(originAirport==null) 250.0 else 5000.0,if(destinationAirport==null) 250.0 else 5000.0)
    }
    override fun stations(center:GeoPoint,cancelled:()->Boolean):List<MobileFuelStation> =
        MobileMapNetwork.decodeNearby(get(MobileMapNetwork.nearbyAddress(center,PlaceCategory.FUEL),262144,cancelled),center,PlaceCategory.FUEL).map { MobileFuelStation(it.name,it.point) }
    override fun vehicle(selection:VehicleSelection,cancelled:()->Boolean):ByteArray = get(vehicleAddress(selection),524288,cancelled)

    private fun get(address:String,limit:Int,cancelled:()->Boolean):ByteArray {
        val owner=generation.get();val started=System.nanoTime();val deadline=started+15_000_000_000L
        fun checkLive() { check(!closed && generation.get()==owner && !cancelled() && !Thread.currentThread().isInterrupted) { "PLANNING_CANCELLED" };check(System.nanoTime()<deadline) { "PLANNING_TIMEOUT" } }
        var acquired=false;var connection:HttpsURLConnection?=null
        try {
            while(!acquired) { checkLive();acquired=slot.tryAcquire(50,TimeUnit.MILLISECONDS) }
            while(System.nanoTime()<nextRequestNanos) { checkLive();Thread.sleep(25) }
            checkLive();nextRequestNanos=System.nanoTime()+1_150_000_000L
            connection=URL(address).openConnection() as HttpsURLConnection
            connections.add(connection)
            connection.instanceFollowRedirects=false;connection.useCaches=false
            connection.connectTimeout=minOf(6000,((deadline-System.nanoTime())/1_000_000).coerceAtLeast(1).toInt())
            connection.readTimeout=connection.connectTimeout
            connection.setRequestProperty("User-Agent","LocalSpoof Android (explicit online planning)")
            connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("Accept-Encoding","identity");connection.setRequestProperty("Cache-Control","no-store")
            connection.connect();checkLive()
            connection.readTimeout=minOf(6000,((deadline-System.nanoTime())/1_000_000).coerceAtLeast(1).toInt())
            val status=connection.responseCode;checkLive()
            if(status!=200) {
                if(status==400 && address.startsWith("https://routing.openstreetmap.de/")) {
                    val error=connection.errorStream?.use { readBody(it,65536,connection,deadline,::checkLive) }
                    if(error!=null && roadFailureCode(error)=="NO_ROUTE") throw IllegalArgumentException("NO_ROUTE")
                }
                throw IllegalStateException("PLANNING_PROVIDER_UNAVAILABLE")
            }
            require(connection.contentLengthLong<=limit && connection.contentEncoding.let { it==null || it=="identity" }) { "PLANNING_BODY_TOO_LARGE" }
            return connection.inputStream.use { readBody(it,limit,connection,deadline,::checkLive) }.also { checkLive() }
        } catch(failure:InterruptedException) { Thread.currentThread().interrupt();throw IllegalStateException("PLANNING_CANCELLED",failure) }
        finally { connection?.let { connections.remove(it);it.disconnect() };if(acquired) slot.release() }
    }
    private fun readBody(input:java.io.InputStream,limit:Int,connection:HttpsURLConnection,deadline:Long,checkLive:()->Unit):ByteArray {
        val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) { checkLive();connection.readTimeout=minOf(6000,((deadline-System.nanoTime())/1_000_000).coerceAtLeast(1).toInt());val count=input.read(buffer);if(count<0)break;require(output.size()+count<=limit) { "PLANNING_BODY_TOO_LARGE" };output.write(buffer,0,count) }
        return output.toByteArray()
    }
    companion object {
        fun roadAddress(origin:GeoPoint,destination:GeoPoint,profile:Mode,originAirport:Airport?=null,destinationAirport:Airport?=null):String {
            require(profile in setOf(Mode.WALK,Mode.DRIVE)) { "INVALID_ROUTING_PROFILE" }
            fun radius(airport:Airport?,point:GeoPoint):Int {
                if(airport==null) return 250
                require(profile==Mode.DRIVE && airport.type=="large_airport" && airport.scheduledService && !airport.closed && airport.point==point) { "INVALID_AIRPORT_ENDPOINT" }
                return 5000
            }
            val prefix=if(profile==Mode.WALK) "routed-foot/route/v1/foot" else "routed-car/route/v1/driving"
            return "https://routing.openstreetmap.de/$prefix/${origin.lon},${origin.lat};${destination.lon},${destination.lat}?overview=full&geometries=geojson&steps=false&alternatives=false&generate_hints=false&radiuses=${radius(originAirport,origin)};${radius(destinationAirport,destination)}"
        }
        fun vehicleAddress(selection:VehicleSelection):String {
            val year=selection.year;val make=selection.make;val model=selection.model;val id=selection.id
            require(year==null || year in 1984..2100) { "INVALID_VEHICLE_SELECTION" }
            require(listOf(make,model).all { it==null || it.length in 1..160 && it.none { c->c.isISOControl() } }) { "INVALID_VEHICLE_SELECTION" }
            val path=when(selection.resource) {
                VehicleResource.YEAR -> { require(year==null && make==null && model==null && id==null);"vehicle/menu/year" }
                VehicleResource.MAKE -> { require(year!=null && make==null && model==null && id==null);"vehicle/menu/make" }
                VehicleResource.MODEL -> { require(year!=null && make!=null && model==null && id==null);"vehicle/menu/model" }
                VehicleResource.OPTIONS -> { require(year!=null && make!=null && model!=null && id==null);"vehicle/menu/options" }
                VehicleResource.VEHICLE -> { require(year==null && make==null && model==null && id!=null && id in 1..1_000_000);"vehicle/$id" }
                VehicleResource.PRICES -> { require(year==null && make==null && model==null && id==null);"fuelprices" }
            }
            val parameters=buildList { year?.let { add("year=$it") };make?.let { add("make="+URLEncoder.encode(it,"UTF-8")) };model?.let { add("model="+URLEncoder.encode(it,"UTF-8")) } }
            return "https://www.fueleconomy.gov/ws/rest/$path"+if(parameters.isEmpty()) "" else "?"+parameters.joinToString("&")
        }
        fun roadFailureCode(bytes:ByteArray):String = runCatching {
            val code=parseBoundedJson(bytes,65536).jsonObject["code"]?.jsonPrimitive?.content
            if(code in setOf("NoRoute","NoSegment")) "NO_ROUTE" else "PLANNING_PROVIDER_UNAVAILABLE"
        }.getOrDefault("PLANNING_PROVIDER_UNAVAILABLE")
        fun decodeRoad(bytes:ByteArray,origin:GeoPoint,destination:GeoPoint,profile:Mode,originRadius:Double=250.0,destinationRadius:Double=250.0):MobileRoadRoute {
            require(profile in setOf(Mode.WALK,Mode.DRIVE) && originRadius in 0.0..5000.0 && destinationRadius in 0.0..5000.0)
            val root=parseBoundedJson(bytes,4*1024*1024).jsonObject
            require(root.str("code")=="Ok") { "NO_ROUTE" }
            val routes=root["routes"]?.jsonArray ?: throw IllegalArgumentException("INVALID_ROUTE_RESPONSE")
            require(routes.size==1) { "INVALID_ROUTE_RESPONSE" }
            val route=routes.single().jsonObject;val geometry=route.obj("geometry")
            require(geometry.str("type")=="LineString") { "INVALID_ROUTE_RESPONSE" }
            val coordinates=geometry["coordinates"]?.jsonArray ?: throw IllegalArgumentException("INVALID_ROUTE_RESPONSE")
            require(coordinates.size in 2..MAX_ONLINE_ROUTE_POINTS) { "ROUTE_TOO_COMPLEX" }
            val points=coordinates.map { value -> val pair=value.jsonArray;require(pair.size==2 && pair.all { !it.jsonPrimitive.isString });GeoPoint(pair[1].jsonPrimitive.double,pair[0].jsonPrimitive.double) }
            val meters=route.number("distance");val seconds=route.number("duration")
            require(meters in 0.0..20_000_000.0 && seconds in 0.0..31_536_000.0) { "INVALID_ROUTE_RESPONSE" }
            require(distanceMeters(origin,points.first())<=originRadius+0.01 && distanceMeters(destination,points.last())<=destinationRadius+0.01) { "ROUTE_ENDPOINT_TOO_FAR" }
            // OSRM speed/duration annotations are not posted limits. Unknown remains unknown.
            val typed=OnlineRoute(profile,"FOSSGIS OSRM",points)
            val actual=points.zipWithNext().sumOf { (a,b)->distanceMeters(a,b) }
            require(actual<=20_000_000 && kotlin.math.abs(actual-meters)<=maxOf(100.0,actual*0.1)) { "INVALID_ROUTE_DISTANCE" }
            return MobileRoadRoute(typed,actual,seconds)
        }
    }
}

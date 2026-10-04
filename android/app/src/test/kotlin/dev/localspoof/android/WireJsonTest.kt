package dev.localspoof.android

import dev.localspoof.domain.Mode
import dev.localspoof.domain.RouteSource
import dev.localspoof.domain.GroundLegMode
import dev.localspoof.domain.FlightTransitMode
import dev.localspoof.domain.Airport
import dev.localspoof.domain.GeoPoint
import dev.localspoof.domain.Coordinator
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WireJsonTest {
    private val onlineRoute = """{"profile":"DRIVE","provider":"FOSSGIS OSRM","points":[{"lat":1,"lon":1},{"lat":1,"lon":1.002}]}"""
    private fun onlineRequest(extra: String = "", source: String = "\"routeSource\":\"ONLINE_ROUTER\",") =
        """{"mode":"DRIVE","origin":{"lat":1,"lon":1},"destination":{"lat":1,"lon":1.002},$source"onlineRoute":$onlineRoute$extra}"""

    @Test fun onlineGeometryRequiresExplicitProvenanceAndStaysSeparateFromCustomPaths() {
        val request = parseRequest(wireJson.parseToJsonElement(onlineRequest()).jsonObject)
        assertNull(request.customPath)
        assertEquals(Mode.DRIVE, request.onlineRoute!!.profile)
        val preview = dev.localspoof.domain.Coordinator().prepare(request).preview
        assertEquals(RouteSource.ONLINE_ROUTER, preview.routeSource)
        assertEquals("ONLINE_ROUTER", preview.json().str("routeSource"))
        assertEquals("FOSSGIS OSRM", preview.json().str("routeProvider"))
        assertThrows(IllegalArgumentException::class.java) { parseRequest(wireJson.parseToJsonElement(onlineRequest(source = "")).jsonObject) }
        assertThrows(IllegalArgumentException::class.java) { parseRequest(wireJson.parseToJsonElement(onlineRequest(source = "\"routeSource\":\"OFFLINE_GEOPACK\",")).jsonObject) }
        assertThrows(IllegalArgumentException::class.java) { parseRequest(wireJson.parseToJsonElement(onlineRequest(extra = ",\"customPath\":true")).jsonObject) }
    }

    @Test fun malformedNetworkGeometryCannotSupplySpeedLimitsOrInvalidCoordinates() {
        val valid = wireJson.parseToJsonElement(onlineRoute).jsonObject
        assertThrows(IllegalArgumentException::class.java) { parseOnlineRoute(JsonObject(valid + ("speedLimits" to JsonArray(emptyList())))) }
        assertThrows(IllegalArgumentException::class.java) { parseOnlineRoute(JsonObject(valid + ("profile" to JsonPrimitive("WALKING")))) }
        assertThrows(IllegalArgumentException::class.java) { parseOnlineRoute(JsonObject(valid + ("points" to JsonArray(List(12_001) { buildJsonObject { put("lat", 1); put("lon", 1) } })))) }
        assertThrows(IllegalArgumentException::class.java) { parseOnlineRoute(JsonObject(valid + ("points" to JsonArray(listOf(buildJsonObject { put("lat", 100); put("lon", 1) }))))) }
    }

    @Test fun flightOnlineGroundLegsAreParsedWithoutTurningIntoFlightGeometry() {
        val payload = """{"mode":"FLIGHT","origin":{"lat":1,"lon":1},"destination":{"lat":1,"lon":1.002},"routeSource":"FLIGHT_JOURNEY","onlineFlightRoutes":{"departureAirportId":"a","arrivalAirportId":"b","originGround":$onlineRoute,"destinationGround":$onlineRoute}}"""
        val request = parseRequest(wireJson.parseToJsonElement(payload).jsonObject)
        assertNull(request.onlineRoute)
        assertEquals("a", request.onlineFlightRoutes!!.departureAirportId)
        assertEquals(Mode.DRIVE, request.onlineFlightRoutes!!.originGround!!.profile)
    }
    @Test fun customPathAddsBothEndpointsAndPreservesIntermediateWaypoints() {
        val request = parseRequest(wireJson.parseToJsonElement("""{"mode":"WALK","origin":{"lat":1,"lon":1},"destination":{"lat":1,"lon":1.002},"speedMps":1.4,"customPath":true,"waypoints":[{"lat":1,"lon":1.001}]}""").jsonObject)
        assertEquals(3, request.customPath!!.size)
        assertEquals(request.origin, request.customPath!!.first())
        assertEquals(request.destination, request.customPath!!.last())
    }
    @Test fun waypointsWithoutExplicitCustomChoiceNeverBecomeFakeRoadRoute() {
        val request = parseRequest(wireJson.parseToJsonElement("""{"mode":"DRIVE","origin":{"lat":1,"lon":1},"destination":{"lat":1,"lon":1.002},"waypoints":[{"lat":1,"lon":1.001}]}""").jsonObject)
        assertNull(request.customPath)
    }
    @Test fun invalidCoordinatesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            parseRequest(wireJson.parseToJsonElement("""{"mode":"INSTANT","origin":{"lat":100,"lon":0},"destination":{"lat":100,"lon":0}}""").jsonObject)
        }
    }
    @Test fun boundsAndBrokenGraphReferencesAreRejectedBeforeActivation() {
        val invalid = """{"schemaVersion":1,"id":"test","name":"synthetic","bounds":{"south":0,"west":0,"north":2,"east":2},"nodes":[{"id":"a","lat":1,"lon":1}],"edges":[{"from":"a","to":"missing","modes":["WALK"]}]}""".toByteArray()
        assertThrows(IllegalArgumentException::class.java) { parseGeoPack(invalid) }
    }
    @Test fun readBoundedRejectsResourceBeforeUnboundedAllocation() {
        assertThrows(IllegalArgumentException::class.java) { readBounded(ByteArray(1025).inputStream(), 1024) }
    }
    @Test fun deeplyNestedJsonIsRejectedBeforeRecursiveParsing() {
        assertThrows(IllegalArgumentException::class.java) {
            parseBoundedJson(("[".repeat(33) + "0" + "]".repeat(33)).toByteArray())
        }
    }
    @Test fun malformedUtf8IsRejectedRatherThanReplaced() {
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { parseBoundedJson(byteArrayOf(0xff.toByte())) }
    }
    @Test fun instantFlightLegsNeedNoNetworkGeometryAndPreviewPreservesTypedEndpoints() {
        val payload = wireJson.parseToJsonElement("""{"mode":"FLIGHT","origin":{"lat":1,"lon":1},"destination":{"lat":1,"lon":2},"routeSource":"FLIGHT_JOURNEY","departureGroundMode":"INSTANT","arrivalGroundMode":"INSTANT","flightTransitMode":"JUMP","onlineFlightRoutes":{"departureAirportId":"a","arrivalAirportId":"b"}}""").jsonObject
        val request = parseRequest(payload)
        assertEquals(GroundLegMode.INSTANT, request.flight!!.departureGroundMode)
        assertEquals(FlightTransitMode.JUMP, request.flight!!.flightTransitMode)
        assertNull(request.onlineFlightRoutes!!.originGround); assertNull(request.onlineFlightRoutes!!.destinationGround)
        val airports = listOf(Airport("a", "fixture", GeoPoint(1.0, 1.01), type = "large_airport"), Airport("b", "fixture", GeoPoint(1.0, 1.99), type = "large_airport"))
        val preview = Coordinator(airports).prepare(request).preview.json()
        assertEquals("FLIGHT_JOURNEY", preview.str("routeSource"))
        val segments = preview["segments"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("DEPARTURE", "AIR", "ARRIVAL"), segments.map { it.str("role") })
        assertEquals(listOf("INSTANT", "FLIGHT", "INSTANT"), segments.map { it.str("kind") })
        assertTrue(segments.all { it.long("durationMs") == 0L })
        assertEquals(request.origin.json(), segments.first()["points"]!!.jsonArray.first())
        assertEquals(request.destination.json(), segments.last()["points"]!!.jsonArray.last())
        assertThrows(IllegalArgumentException::class.java) { parseRequest(JsonObject(payload + ("departureGroundMode" to JsonPrimitive("DRIVE")))) }
        assertThrows(IllegalArgumentException::class.java) { parseRequest(JsonObject(payload + ("arrivalGroundMode" to JsonPrimitive("WALK")))) }
        assertThrows(IllegalArgumentException::class.java) { parseRequest(JsonObject(payload + ("flightTransitMode" to JsonPrimitive("TELEPORT")))) }
    }
    @Test fun flightOnlyOptionsAreRejectedOnOrdinaryDrivePlans() {
        val drive = wireJson.parseToJsonElement(onlineRequest()).jsonObject
        assertThrows(IllegalArgumentException::class.java) { parseRequest(JsonObject(drive + ("flightTransitMode" to JsonPrimitive("JUMP")))) }
    }    @Test fun stopWireAcceptsBoundedOrderedStopsAndRejectsUnknownPrivateData() {
        val payload=wireJson.parseToJsonElement("""{"mode":"DRIVE","origin":{"lat":1,"lon":1},"destination":{"lat":1,"lon":1.002},"customPath":true,"stops":[{"point":{"lat":1,"lon":1.001},"label":"Coffee","dwellSeconds":20}]}""").jsonObject
        val parsed=parseRequest(payload)
        assertEquals(20_000L,parsed.stops.single().dwellMs)
        assertEquals(3,parsed.customPath!!.size)
        assertEquals("Coffee",Coordinator().prepare(parsed).preview.json()["stops"]!!.jsonArray.single().jsonObject.str("label"))
        val invalid=JsonObject(payload + ("stops" to JsonArray(listOf(buildJsonObject { put("point",GeoPoint(1.0,1.001).json());put("token","fake") }))))
        assertThrows(IllegalArgumentException::class.java) { parseRequest(invalid) }
        assertThrows(IllegalArgumentException::class.java) { parseRequest(JsonObject(payload+("stops" to JsonArray(List(33){payload["stops"]!!.jsonArray.single()})))) }
    }

    @Test fun mappedOnlineLimitsAreOptionalAlignedAndFinite() {
        val valid=wireJson.parseToJsonElement(onlineRoute).jsonObject
        val parsed=parseOnlineRoute(JsonObject(valid+("edgeSpeedLimits" to JsonArray(listOf(JsonPrimitive(24.5872))))))
        assertEquals(24.5872,parsed.edgeSpeedLimits.single()!!,0.0)
        assertEquals(24.5872,parsed.geometry(GeoPoint(1.0,1.0),GeoPoint(1.0,1.002),Mode.DRIVE).mappedLimitAt(0.0)!!,0.0)
        for(limits in listOf(JsonArray(listOf(JsonPrimitive(20),JsonPrimitive(20))),JsonArray(listOf(JsonPrimitive(-1))),JsonArray(listOf(JsonPrimitive("20"))))) {
            assertThrows(IllegalArgumentException::class.java){ parseOnlineRoute(JsonObject(valid+("edgeSpeedLimits" to limits))) }
        }
        assertTrue(parseOnlineRoute(JsonObject(valid+("edgeSpeedLimits" to JsonArray(emptyList())))).edgeSpeedLimits.isEmpty())
        assertNull(parseOnlineRoute(valid).geometry(GeoPoint(1.0,1.0),GeoPoint(1.0,1.002),Mode.DRIVE).mappedLimitAt(0.0))
    }
    @Test fun offlineProfileDefaultsCannotBeAdvertisedAsPostedLimits() {
        val input="""{"schemaVersion":1,"id":"test","name":"synthetic","bounds":{"south":0,"west":0,"north":2,"east":2},"nodes":[{"id":"a","lat":1,"lon":1},{"id":"b","lat":1,"lon":1.001}],"edges":[{"from":"a","to":"b","modes":["DRIVE"],"speedLimitMps":13.9,"speedLimitSource":"ESTIMATED"}]}"""
        val estimated=parseGeoPack(input.toByteArray())
        val route=dev.localspoof.domain.GraphRouter(estimated).route(GeoPoint(1.0,1.0),GeoPoint(1.0,1.001),Mode.DRIVE,20.0)
        assertEquals(13.9,route.limitAt(0.0)!!,0.0);assertNull(route.mappedLimitAt(0.0))
        val mapped=parseGeoPack(input.replace("ESTIMATED","MAPPED").toByteArray())
        assertEquals(13.9,dev.localspoof.domain.GraphRouter(mapped).route(GeoPoint(1.0,1.0),GeoPoint(1.0,1.001),Mode.DRIVE,20.0).mappedLimitAt(0.0)!!,0.0)
    }

}

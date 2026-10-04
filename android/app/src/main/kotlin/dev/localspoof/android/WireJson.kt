package dev.localspoof.android

import dev.localspoof.domain.*
import kotlinx.serialization.json.*
import java.io.InputStream
import java.security.MessageDigest

internal val wireJson = Json { isLenient = false; ignoreUnknownKeys = false; allowSpecialFloatingPointValues = false }
internal fun parseBoundedJson(bytes: ByteArray, limit: Int = PhoneControlServer.MAX_FRAME): JsonElement {
    require(bytes.size in 1..limit) { "JSON_TOO_LARGE" }
    val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    var depth = 0
    var inString = false
    var escaped = false
    for (char in text) {
        if (inString) {
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if (char == '"') inString = false
        } else when (char) {
            '"' -> inString = true
            '{', '[' -> { depth++; require(depth <= 32) { "JSON_TOO_DEEP" } }
            '}', ']' -> { depth--; require(depth >= 0) { "INVALID_JSON" } }
        }
    }
    require(!inString && depth == 0) { "INVALID_JSON" }
    return wireJson.parseToJsonElement(text)
}
internal fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.also {
    require(it.isString) { "INVALID_STRING" }
}?.content ?: throw IllegalArgumentException("MISSING_FIELD")
internal fun JsonObject.number(key: String): Double = this[key]?.jsonPrimitive?.also {
    require(!it.isString) { "INVALID_NUMBER" }
}?.double?.also {
    require(it.isFinite()) { "NONFINITE_NUMBER" }
} ?: throw IllegalArgumentException("MISSING_FIELD")
internal fun JsonObject.long(key: String): Long = this[key]?.jsonPrimitive?.also { require(!it.isString) }?.long
    ?: throw IllegalArgumentException("MISSING_FIELD")
internal fun JsonObject.obj(key: String): JsonObject = this[key]?.jsonObject
    ?: throw IllegalArgumentException("MISSING_FIELD")
internal fun JsonObject.optionalNumber(key: String): Double? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.also { require(!it.isString) }?.double?.also {
    require(it.isFinite()) { "NONFINITE_NUMBER" }
}
internal fun JsonObject.point(): GeoPoint = GeoPoint(number("lat"), number("lon"))
internal fun GeoPoint.json(): JsonObject = buildJsonObject { put("lat", lat); put("lon", lon) }
internal fun Airport.json(): JsonObject = buildJsonObject {
    put("id", id); put("name", name); put("lat", point.lat); put("lon", point.lon)
    put("type", type); put("scheduledService", scheduledService); put("closed", closed)
}
internal fun PlanPreview.json(): JsonObject = buildJsonObject {
    put("mode", mode.name); put("origin", origin.json()); put("destination", destination.json())
    put("distanceMeters", distanceMeters); put("durationMs", durationMs); put("customPath", customPath)
    put("routeSource", routeSource.name); put("routeProvider", routeProvider?.let(::JsonPrimitive) ?: JsonNull)
    put("originAirport", originAirport?.json() ?: JsonNull)
    put("destinationAirport", destinationAirport?.json() ?: JsonNull)
    put("originAirportAccessPoint", originAirportAccessPoint?.json() ?: JsonNull)
    put("destinationAirportAccessPoint", destinationAirportAccessPoint?.json() ?: JsonNull)
    put("originAirportAccessDistanceMeters", originAirportAccessDistanceMeters?.let(::JsonPrimitive) ?: JsonNull)
    put("destinationAirportAccessDistanceMeters", destinationAirportAccessDistanceMeters?.let(::JsonPrimitive) ?: JsonNull)
    val step = maxOf(1, (geometry.size + 4095) / 4096)
    val previewPoints = geometry.filterIndexed { index, _ -> index % step == 0 || index == geometry.lastIndex }
    put("geometry", JsonArray(previewPoints.map { it.json() }))
    put("geometrySimplified", step > 1)
    put("simulationOnly", simulationOnly)
    put("stops", JsonArray(stops.map { it.json() }))
    if (mode == Mode.FLIGHT) {
        put("departureGroundMode", departureGroundMode?.name ?: GroundLegMode.DRIVE.name)
        put("arrivalGroundMode", arrivalGroundMode?.name ?: GroundLegMode.DRIVE.name)
        put("flightTransitMode", flightTransitMode?.name ?: FlightTransitMode.TIMED.name)
        val segmentStep = maxOf(1, (segments.sumOf { it.points.size } + 4095) / 4096)
        put("segments", JsonArray(segments.map { segment -> buildJsonObject {
            put("role", segment.role.name); put("kind", segment.kind.name); put("durationMs", segment.durationMs)
            put("routeSource", segment.routeSource.name)
            segment.routeProvider?.let { put("routeProvider", it) }
            put("points", JsonArray(segment.points.filterIndexed { index, _ -> index == 0 || index == segment.points.lastIndex || index % segmentStep == 0 }.map { it.json() }))
        } }))
    }
}

internal fun parseRequest(payload: JsonObject, remainingFlight: Boolean = false): PlanRequest {
    val mode = Mode.valueOf(payload.str("mode"))
    require(mode == Mode.FLIGHT || listOf("departureGroundMode", "arrivalGroundMode", "flightTransitMode", "flightSpeedMps").none { it in payload }) { "UNEXPECTED_FLIGHT_OPTIONS" }
    val origin = payload.obj("origin").point()
    val destination = payload.obj("destination").point()
    val stops = payload["stops"]?.jsonArray?.also { require(it.size <= 32) { "TOO_MANY_STOPS" } }?.map { element ->
        val stop = element.jsonObject
        require(stop.keys.contains("point") && stop.keys.all { it in setOf("point", "dwellSeconds", "label", "kind", "fuel", "hotel", "id") }) { "INVALID_STOP_FIELDS" }
        val point = stop.obj("point"); require(point.keys == setOf("lat", "lon")) { "INVALID_STOP_POINT" }
        val kind=stop["kind"]?.let { StopKind.valueOf(stop.str("kind")) } ?: StopKind.PLACE
        val fuel=stop["fuel"]?.takeUnless { it is JsonNull }?.jsonObject?.let { f ->
            require(f.keys.contains("refillTargetLiters") && f.keys.all { it in setOf("refillTargetLiters","pricePerLiter","currency") }) { "INVALID_FUEL_FIELDS" }
            FuelStop(f.number("refillTargetLiters"),f.optionalNumber("pricePerLiter"),f["currency"]?.let { f.str("currency") })
        }
        val hotel=stop["hotel"]?.takeUnless { it is JsonNull }?.jsonObject?.let { h ->
            require(h.keys.containsAll(setOf("checkInSeconds","staySeconds","checkOutSeconds","nights","currency")) && h.keys.all { it in setOf("checkInSeconds","staySeconds","checkOutSeconds","nights","nightlyPrice","fees","currency") }) { "INVALID_HOTEL_FIELDS" }
            fun ms(key:String,maxSeconds:Double):Long { val seconds=h.number(key);require(seconds in 0.0..maxSeconds) { "INVALID_HOTEL_DURATION" };return (seconds*1000).toLong() }
            HotelStop(ms("checkInSeconds",86400.0),ms("staySeconds",2592000.0),ms("checkOutSeconds",86400.0),h.long("nights").also { require(it in 0..365) }.toInt(),h.optionalNumber("nightlyPrice"),h.optionalNumber("fees"),h.str("currency"))
        }
        val dwell = stop.optionalNumber("dwellSeconds") ?: hotel?.durationMs?.div(1000.0) ?: 0.0
        require(dwell in 0.0..2764800.0 && (kind==StopKind.HOTEL || dwell<=3600)) { "INVALID_STOP_DWELL" }
        RouteStop(point.point(), (dwell * 1000).toLong(), stop["label"]?.let { stop.str("label") } ?: "Stop",kind,fuel,hotel,stop["id"]?.let { stop.str("id") } ?: java.util.UUID.randomUUID().toString())
    } ?: emptyList()
    val stopIndices = payload["stopPointIndices"]?.jsonArray?.also { require(it.size <= 32) }?.map {
        require(!it.jsonPrimitive.isString); val index = it.jsonPrimitive.int; require(index in 0 until MAX_ONLINE_ROUTE_POINTS); index
    } ?: emptyList()
    val supplied = payload["waypoints"]?.jsonArray?.map { it.jsonObject.point() } ?: emptyList()
    val path = if (payload["customPath"]?.jsonPrimitive?.boolean == true) {
        buildList<GeoPoint> {
            add(origin)
            (if (supplied.isEmpty()) stops.map { it.point } else supplied).forEach { point -> if (point != last()) add(point) }
            if (last() != destination) add(destination)
        }
    } else null
    require(path == null || path.size <= 20_000) { "TOO_MANY_WAYPOINTS" }
    val duration = payload.optionalNumber("flightDurationSeconds")?.also { require(it >= 1 && it <= 86400) }
    val dwell = payload.optionalNumber("dwellSeconds")?.also { require(it >= 0 && it <= 3600) } ?: 30.0
    val online = payload["onlineRoute"]?.takeUnless { it is JsonNull }?.jsonObject?.let(::parseOnlineRoute)
    val onlineFlight = payload["onlineFlightRoutes"]?.takeUnless { it is JsonNull }?.jsonObject?.let { legs ->
        require(legs.keys.containsAll(setOf("departureAirportId", "arrivalAirportId")) && legs.keys.all { it in setOf("departureAirportId", "arrivalAirportId", "originGround", "destinationGround") }) { "INVALID_ONLINE_FLIGHT_FIELDS" }
        OnlineFlightRoutes(legs.str("departureAirportId"), legs.str("arrivalAirportId"),
            legs["originGround"]?.takeUnless { it is JsonNull }?.jsonObject?.let(::parseOnlineRoute),
            legs["destinationGround"]?.takeUnless { it is JsonNull }?.jsonObject?.let(::parseOnlineRoute))
    }
    val source = when {
        mode == Mode.INSTANT -> RouteSource.INSTANT_LOCATION
        mode == Mode.FLIGHT -> RouteSource.FLIGHT_JOURNEY
        path != null -> RouteSource.CUSTOM_PATH
        online != null || onlineFlight != null -> RouteSource.ONLINE_ROUTER
        else -> RouteSource.OFFLINE_GEOPACK
    }
    payload["routeSource"]?.let { require(RouteSource.valueOf(payload.str("routeSource")) == source) { "ROUTE_SOURCE_MISMATCH" } }
    require(online == null && onlineFlight == null || payload["routeSource"] != null && source in setOf(RouteSource.ONLINE_ROUTER, RouteSource.FLIGHT_JOURNEY)) { "ONLINE_ROUTE_SOURCE_REQUIRED" }
    return PlanRequest(
        mode, origin, destination,
        payload.optionalNumber("speedMps") ?: if (mode == Mode.WALK) 1.4 else 13.9,
        path,
        if (mode == Mode.FLIGHT) FlightOptions(
            simulationOnly = payload["radioPolicy"]?.jsonPrimitive?.content == "SIMULATION",
            boardingMs = (dwell * 1000).toLong(), flightMs = duration?.let { (it * 1000).toLong() },
            originAirportId = payload["departureAirportId"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
            destinationAirportId = payload["arrivalAirportId"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
            departureGroundMode = payload["departureGroundMode"]?.let { GroundLegMode.valueOf(payload.str("departureGroundMode")) } ?: GroundLegMode.DRIVE,
            arrivalGroundMode = payload["arrivalGroundMode"]?.let { GroundLegMode.valueOf(payload.str("arrivalGroundMode")) } ?: GroundLegMode.DRIVE,
            flightTransitMode = payload["flightTransitMode"]?.let { FlightTransitMode.valueOf(payload.str("flightTransitMode")) } ?: FlightTransitMode.TIMED,
        ) else null,
        onlineRoute = online, onlineFlightRoutes = onlineFlight,
        flightSpeedMps = payload.optionalNumber("flightSpeedMps"), stops = stops, stopPointIndices = stopIndices,
        vacation=payload["vacation"]?.takeUnless { it is JsonNull }?.jsonObject?.let(::parseVacation), remainingFlight=remainingFlight,
        speedControls = DrivingSpeedControls(
            payload["drivingSpeedMode"]?.let { DrivingSpeedMode.valueOf(payload.str("drivingSpeedMode")) } ?: DrivingSpeedMode.FIXED,
            payload.optionalNumber("driveMinMps") ?: payload.optionalNumber("speedMps") ?: 13.9,
            payload.optionalNumber("driveMaxMps") ?: payload.optionalNumber("speedMps") ?: 13.9,
            payload["followRoadLimits"]?.let { require(!it.jsonPrimitive.isString); it.jsonPrimitive.boolean } ?: true),
    )
}

internal fun parseOnlineRoute(payload: JsonObject): OnlineRoute {
    require(payload.keys.containsAll(setOf("profile", "provider", "points")) && payload.keys.all { it in setOf("profile", "provider", "points", "edgeSpeedLimits") }) { "INVALID_ONLINE_ROUTE_FIELDS" }
    val points = payload["points"]?.jsonArray ?: throw IllegalArgumentException("MISSING_FIELD")
    require(points.size in 1..MAX_ONLINE_ROUTE_POINTS) { "INVALID_ONLINE_ROUTE_SIZE" }
    return OnlineRoute(Mode.valueOf(payload.str("profile")), payload.str("provider"), points.map {
        val point = it.jsonObject
        require(point.keys == setOf("lat", "lon")) { "INVALID_ONLINE_ROUTE_POINT" }
        point.point()
    }, payload["edgeSpeedLimits"]?.jsonArray?.also { require(it.isEmpty() || it.size == points.size - 1) { "INVALID_SPEED_LIMIT_COUNT" } }?.map {
        if (it is JsonNull) null else { require(!it.jsonPrimitive.isString); it.jsonPrimitive.double }
    } ?: emptyList())
}

internal fun readBounded(input: InputStream, limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count == -1) break
        require(output.size() + count <= limit) { "RESOURCE_TOO_LARGE" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal fun parseAirports(bytes: ByteArray): List<Airport> {
    val element = parseBoundedJson(bytes, 16 * 1024 * 1024)
    val array = if (element is JsonArray) element else element.jsonObject["airports"]!!.jsonArray
    require(array.size <= 100_000) { "AIRPORT_CATALOG_TOO_LARGE" }
    return array.map {
        val row = it.jsonObject
        Airport(row.str("id"), row.str("name"), row.point(),
            row["type"]?.jsonPrimitive?.content ?: "medium_airport",
            row["scheduledService"]?.jsonPrimitive?.boolean ?: true,
            row["closed"]?.jsonPrimitive?.boolean ?: false)
    }
}

internal fun parseGeoPack(bytes: ByteArray): GeoPack {
    require(bytes.size <= 32 * 1024 * 1024) { "RESOURCE_TOO_LARGE" }
    val root = parseBoundedJson(bytes, PhoneControlServer.MAX_PACK).jsonObject
    val manifest = root["manifest"]?.jsonObject ?: root
    require(manifest.long("schemaVersion") == 1L) { "UNSUPPORTED_RESOURCE_VERSION" }
    root["sha256"]?.jsonPrimitive?.content?.let { expected ->
        // A checksum is computed over canonical payload bytes, without manifest/signature wrapper.
        val payload = root["payload"] ?: throw IllegalArgumentException("CHECKSUM_PAYLOAD_REQUIRED")
        val actual = MessageDigest.getInstance("SHA-256").digest(payload.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        require(actual == expected.lowercase()) { "RESOURCE_CHECKSUM_MISMATCH" }
    }
    val data = root["payload"]?.jsonObject ?: root
    val bounds = manifest.obj("bounds")
    val nodes = data["nodes"]!!.jsonArray
    val edges = data["edges"]!!.jsonArray
    require(nodes.size <= 100_000 && edges.size <= 300_000) { "GRAPH_TOO_LARGE" }
    return GeoPack(
        schemaVersion = 1, id = manifest.str("id"), name = manifest.str("name"),
        bounds = GeoBounds(bounds.number("south"), bounds.number("west"), bounds.number("north"), bounds.number("east")),
        nodes = nodes.map { node -> val row = node.jsonObject; GeoNode(row.str("id"), (row["point"]?.jsonObject ?: row).point()) },
        edges = edges.map { edge -> val row = edge.jsonObject; GeoEdge(row.str("from"), row.str("to"),
            row["modes"]!!.jsonArray.map { Mode.valueOf(it.jsonPrimitive.content.uppercase()) }.toSet(),
            row.optionalNumber("speedLimitMps"), row["bidirectional"]?.jsonPrimitive?.boolean ?: true,
            row["speedLimitSource"]?.let { SpeedLimitSource.valueOf(row.str("speedLimitSource")) } ?: SpeedLimitSource.UNKNOWN) },
        places = data["places"]?.jsonArray?.map { place -> val row = place.jsonObject; Place(row.str("id"), row.str("name"), (row["point"]?.jsonObject ?: row).point()) } ?: emptyList(),
        polygons = data["polygons"]?.jsonArray?.map { polygon -> polygon.jsonArray.map { it.jsonObject.point() } } ?: emptyList(),
        source = manifest["source"]?.jsonPrimitive?.content ?: "User-provided offline resource",
        attribution = manifest["attribution"]?.jsonPrimitive?.content ?: ""
    )
}

internal fun parseVacation(v:JsonObject):VacationConfig {
    require(v.keys.containsAll(setOf("tankLiters","startingFuelLiters","reserveLiters","litersPer100Km","fuelGrade","currency")) && v.keys.all { it in setOf("tankLiters","startingFuelLiters","reserveLiters","litersPer100Km","fuelGrade","currency","fuelPricePerLiter") }) { "INVALID_VACATION_FIELDS" }
    return VacationConfig(v.number("tankLiters"),v.number("startingFuelLiters"),v.number("reserveLiters"),v.number("litersPer100Km"),FuelGrade.valueOf(v.str("fuelGrade")),v.str("currency"),v.optionalNumber("fuelPricePerLiter"))
}
internal fun RouteStop.json():JsonObject = buildJsonObject {
    put("point",point.json());put("dwellSeconds",dwellMs/1000.0);put("label",label);put("kind",kind.name);put("id",id)
    fuel?.let { f -> put("fuel",buildJsonObject { put("refillTargetLiters",f.refillTargetLiters);f.pricePerLiter?.let{put("pricePerLiter",it)};f.currency?.let{put("currency",it)} }) }
    hotel?.let { h -> put("hotel",buildJsonObject { put("checkInSeconds",h.checkInMs/1000.0);put("staySeconds",h.stayMs/1000.0);put("checkOutSeconds",h.checkOutMs/1000.0);put("nights",h.nights);put("currency",h.currency);h.nightlyPrice?.let{put("nightlyPrice",it)};h.fees?.let{put("fees",it)} }) }
}
internal fun VacationConfig.json():JsonObject=buildJsonObject {
    put("tankLiters",tankLiters);put("startingFuelLiters",startingFuelLiters);put("reserveLiters",reserveLiters);put("litersPer100Km",litersPer100Km);put("fuelGrade",fuelGrade.name);put("currency",currency);fuelPricePerLiter?.let{put("fuelPricePerLiter",it)}
}
/** Editable choices only, never cached online paths or location history. */
internal fun PlanRequest.choicesJson():JsonObject=buildJsonObject {
    put("mode",mode.name);put("origin",origin.json());put("destination",destination.json());put("speedMps",speedMps)
    put("stops",JsonArray(stops.map { it.json() }));vacation?.let { put("vacation",it.json()) }
    put("drivingSpeedMode",speedControls.mode.name);put("driveMinMps",speedControls.driveMinMps);put("driveMaxMps",speedControls.driveMaxMps);put("followRoadLimits",speedControls.followRoadLimits)
    flight?.let { f -> put("departureGroundMode",f.departureGroundMode.name);put("arrivalGroundMode",f.arrivalGroundMode.name);put("flightTransitMode",f.flightTransitMode.name);put("dwellSeconds",f.boardingMs/1000.0);put("radioPolicy",if(f.simulationOnly) "SIMULATION" else "MANUAL");f.originAirportId?.let{put("departureAirportId",it)};f.destinationAirportId?.let{put("arrivalAirportId",it)};flightSpeedMps?.let{put("flightSpeedMps",it)} }
}
internal fun RemainingEditContext.json():JsonObject=buildJsonObject {
    put("editId",id);put("confirmedOrigin",confirmedOrigin.json());put("phase",phase?.let { JsonPrimitive(it.name) } ?: JsonNull);put("choices",choices.choicesJson());put("canUndo",canUndo)
}

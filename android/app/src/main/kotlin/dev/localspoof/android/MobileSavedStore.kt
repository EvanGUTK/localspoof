package dev.localspoof.android

import android.content.Context
import android.util.AtomicFile
import dev.localspoof.domain.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

/** Portable, explicitly saved immutable copies. Runtime authority and activity history are never serialized. */
internal data class MobileSavedItem(val id: String, val name: String, val group: String, val kind: String, val draft: MobileDraft, val portable: JsonObject? = null)
internal class MobileSavedStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "saved-plans-v1.json"))
    fun load(): List<MobileSavedItem> = synchronized(lock) { if (!file.baseFile.exists()) emptyList() else file.openRead().use { decode(readBounded(it, MAX_BYTES)) } }
    fun save(items: List<MobileSavedItem>) = synchronized(lock) {
        val bytes = encode(items)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
    fun clear() = synchronized(lock) { file.delete() }
    companion object {
        private val lock = Any()
        const val MAX_BYTES = 524288
        private val common = setOf("id", "name", "group", "kind", "destination")
        private val route = common + setOf("origin", "mode", "speedMps", "walkSpeedMps", "driveSpeedMps", "planeSpeedMps", "waypoints", "stopDwellSeconds", "dwellSeconds", "flightDurationSeconds", "radioPolicy", "departureGroundMode", "arrivalGroundMode", "flightTransitMode", "departureAirportId", "arrivalAirportId", "stops", "flightSpeedMps", "customPath", "drivingSpeedMode", "driveMinMps", "driveMaxMps", "followRoadLimits", "vacation")
        fun newItem(name: String, group: String, draft: MobileDraft) = MobileSavedItem(UUID.randomUUID().toString(), name.trim(), group.trim(), if (draft.mode == Mode.INSTANT) "PLACE" else "ROUTE", draft.copy(placeQuery = "", airportQuery = "", preparedId = null, busy = false, speedError = null))
        private fun point(lat: String, lon: String) = GeoPoint(lat.toDouble(), lon.toDouble())
        private fun jsonPoint(point: GeoPoint) = buildJsonObject { put("lat", point.lat); put("lon", point.lon) }
        private fun readPoint(raw: JsonElement): GeoPoint { val o = raw.jsonObject; require(o.keys == setOf("lat", "lon") && o.values.all { !it.jsonPrimitive.isString }); return GeoPoint(o.getValue("lat").jsonPrimitive.double, o.getValue("lon").jsonPrimitive.double) }
        fun encode(items: List<MobileSavedItem>): ByteArray {
            require(items.size <= 100 && items.map { it.id }.distinct().size == items.size)
            val result = buildJsonObject { put("version", 1); put("items", buildJsonArray { items.forEach { item ->
                validateName(item)
                if (item.portable != null) {
                    val raw = JsonObject(item.portable + mapOf("id" to JsonPrimitive(item.id), "name" to JsonPrimitive(item.name), "group" to JsonPrimitive(item.group)))
                    decode(buildJsonObject { put("version", 1); put("items", JsonArray(listOf(raw))) }.toString().toByteArray())
                    add(raw); return@forEach
                }
                val d = item.draft; val destination = point(d.destinationLat, d.destinationLon)
                add(buildJsonObject { put("id", item.id); put("name", item.name); put("group", item.group); put("kind", item.kind); put("destination", jsonPoint(destination))
                    if (item.kind == "ROUTE") {
                        require(d.mode != Mode.INSTANT && mobileDrivingValid(d))
                        mobileDrivingControls(d)
                        require(d.speedMps.isFinite() && d.speedMps in 0.1..56.0 && (d.mode != Mode.WALK || d.speedMps <= 3))
                        require(d.flightSpeedMps.isFinite() && d.flightSpeedMps in 30.0..350.0)
                        require(d.stops.none { it.kind == StopKind.FUEL } || d.vacation.enabled)
                        require(d.stops.size <= 32 && (d.stops.isEmpty() || d.mode in setOf(Mode.WALK, Mode.DRIVE)))
                        require(d.departureId.length <= 128 && d.arrivalId.length <= 128 && (d.departureId + d.arrivalId).none { it.isISOControl() })
                        val seconds = d.flightSeconds.toDoubleOrNull() ?: throw IllegalArgumentException("INVALID_DURATION")
                        require(seconds.isFinite() && seconds in 1.0..86400.0)
                        put("origin", jsonPoint(point(d.originLat, d.originLon))); put("mode", d.mode.name); put("speedMps", d.speedMps); put("planeSpeedMps", d.flightSpeedMps)
                        put("drivingSpeedMode", d.drivingSpeedMode.name); put("driveMinMps", d.driveMinMps); put("driveMaxMps", d.driveMaxMps); put("followRoadLimits", d.followRoadLimits)
                        put("stops", JsonArray(d.stops.map { it.json() })); put("customPath", d.customPath)
                        mobileVacationConfig(d.vacation)?.let { require(d.mode == Mode.DRIVE); put("vacation", it.json()) }
                        put("flightDurationSeconds", seconds); put("dwellSeconds", d.boardingHoldSeconds.toDouble().also { require(it.isFinite() && it in 0.0..3600.0) }); put("radioPolicy", if (d.simulationOnly) "SIMULATION" else "MANUAL")
                        put("departureGroundMode", d.departureGroundMode.name); put("arrivalGroundMode", d.arrivalGroundMode.name); put("flightTransitMode", d.flightTransitMode.name)
                        put("departureAirportId", d.departureId.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull); put("arrivalAirportId", d.arrivalId.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull)
                    }
                })
            } }) }.toString().toByteArray(Charsets.UTF_8)
            require(result.size <= MAX_BYTES); return result
        }
        private fun validateName(item: MobileSavedItem) {
            require(item.id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) && item.name.length in 1..120 && item.name.isNotBlank() && item.group.length <= 80 && (item.name + item.group).none { it.isISOControl() } && item.kind in setOf("PLACE", "ROUTE"))
        }
        fun decode(bytes: ByteArray): List<MobileSavedItem> {
            require(bytes.size <= MAX_BYTES)
            val root = parseBoundedJson(bytes, MAX_BYTES).jsonObject
            require(root.keys == setOf("version", "items") && !root.getValue("version").jsonPrimitive.isString && root.getValue("version").jsonPrimitive.int == 1)
            val raws = root.getValue("items").jsonArray; require(raws.size <= 100)
            val items = raws.map { raw ->
                val o = raw.jsonObject
                require(listOf("id", "name", "group", "kind").all { o[it]?.jsonPrimitive?.isString == true })
                listOf("mode", "radioPolicy", "departureGroundMode", "arrivalGroundMode", "flightTransitMode", "drivingSpeedMode").forEach { key -> o[key]?.let { require(it.jsonPrimitive.isString) } }
                o["customPath"]?.let { require(!it.jsonPrimitive.isString) }
                val kind = o.getValue("kind").jsonPrimitive.content
                require(common.all { it in o } && o.keys.all { it in if (kind == "PLACE") common else route })
                val destination = readPoint(o.getValue("destination"))
                fun number(key: String, fallback: Double, minimum: Double, maximum: Double): Double = (o[key]?.jsonPrimitive?.also { require(!it.isString) }?.double ?: fallback).also { require(it.isFinite() && it in minimum..maximum) }
                val mode = if (kind == "PLACE") Mode.INSTANT else Mode.valueOf(o.getValue("mode").jsonPrimitive.content).also { require(it != Mode.INSTANT) }
                val origin = if (kind == "PLACE") destination else readPoint(o.getValue("origin"))
                val speed = number("speedMps", if (mode == Mode.WALK) 1.4 else 13.9, 0.1, if (mode == Mode.WALK) 3.0 else 56.0)
                number("walkSpeedMps", 1.4, 0.1, 3.0); number("driveSpeedMps", 13.9, 0.1, 56.0)
                val drivingMode = DrivingSpeedMode.valueOf(o["drivingSpeedMode"]?.jsonPrimitive?.content ?: "FIXED")
                val minimum = number("driveMinMps", speed, 0.1, 56.0); val maximum = number("driveMaxMps", speed, 0.1, 56.0)
                val follows = o["followRoadLimits"]?.jsonPrimitive?.also { require(!it.isString) }?.boolean ?: true
                DrivingSpeedControls(drivingMode, minimum, maximum, follows)
                val plane = number("planeSpeedMps", 230.0, 30.0, 350.0)
                val air = number("flightSpeedMps", plane, 30.0, 350.0)
                require(o["flightSpeedMps"] == null || o["planeSpeedMps"] == null || air == plane)
                val dwell = number("stopDwellSeconds", 0.0, 0.0, 3600.0); val boarding = number("dwellSeconds", 30.0, 0.0, 3600.0)
                val duration = number("flightDurationSeconds", 3600.0, 1.0, 86400.0)
                val waypoints = o["waypoints"]?.jsonArray ?: JsonArray(emptyList()); require(waypoints.size <= 32 && (waypoints.isEmpty() || mode in setOf(Mode.WALK, Mode.DRIVE)))
                val stopData = o["stops"]?.jsonArray ?: JsonArray(emptyList()); require(stopData.size <= 32 && (stopData.isEmpty() || mode in setOf(Mode.WALK, Mode.DRIVE)))
                require(o["stops"] == null || o["waypoints"] == null || waypoints.isEmpty())
                val stops = if (o["stops"] != null) parseRequest(buildJsonObject {
                    put("mode", mode.name); put("origin", jsonPoint(origin)); put("destination", jsonPoint(destination)); put("stops", stopData); o["vacation"]?.let { put("vacation", it) }
                }).stops else waypoints.mapIndexed { i, p -> RouteStop(readPoint(p), (dwell * 1000).toLong(), "Stop ${i + 1}") }
                val vacation = o["vacation"]?.takeUnless { it is JsonNull }?.jsonObject?.let(::parseVacation)
                require(vacation == null || mode == Mode.DRIVE)
                val vacationDraft = vacation?.let { MobileVacationDraft(enabled = true, metric = true, tank = it.tankLiters.toString(), consumption = it.litersPer100Km.toString(),
                    startingMethod = MobileFuelInput.AMOUNT, starting = it.startingFuelLiters.toString(), reserve = it.reserveLiters.toString(), grade = it.fuelGrade,
                    price = it.fuelPricePerLiter?.toString() ?: "", currency = it.currency) } ?: MobileVacationDraft()
                val radio = o["radioPolicy"]?.jsonPrimitive?.content ?: "MANUAL"; require(radio in setOf("MANUAL", "SIMULATION", "USB"))
                fun airport(key: String) = o[key]?.jsonPrimitive?.also { require(it is JsonNull || it.isString) }?.contentOrNull?.also { require(it.length <= 128 && it.none { c -> c.isISOControl() }) } ?: ""
                MobileSavedItem(o.getValue("id").jsonPrimitive.content, o.getValue("name").jsonPrimitive.content, o.getValue("group").jsonPrimitive.content, kind,
                    MobileDraft(drivingSpeedMode = drivingMode, driveMinMps = minimum, driveMaxMps = maximum, driveMinText = mobileFormatSpeed(minimum, MobileSpeedUnit.MPH), driveMaxText = mobileFormatSpeed(maximum, MobileSpeedUnit.MPH), followRoadLimits = follows, mode = mode, originLat = origin.lat.toString(), originLon = origin.lon.toString(), destinationLat = destination.lat.toString(), destinationLon = destination.lon.toString(), speedMps = speed, speedText = mobileFormatSpeed(speed, MobileSpeedUnit.MPH), flightSpeedMps = air, flightSpeedText = mobileFormatSpeed(air, MobileSpeedUnit.MPH), flightSeconds = duration.toString(), boardingHoldSeconds = boarding.toString(), departureId = airport("departureAirportId"), arrivalId = airport("arrivalAirportId"),
                        departureGroundMode = GroundLegMode.valueOf(o["departureGroundMode"]?.jsonPrimitive?.content ?: "DRIVE"), arrivalGroundMode = GroundLegMode.valueOf(o["arrivalGroundMode"]?.jsonPrimitive?.content ?: "DRIVE"), flightTransitMode = FlightTransitMode.valueOf(o["flightTransitMode"]?.jsonPrimitive?.content ?: "TIMED"), simulationOnly = radio == "SIMULATION", customPath = o["customPath"]?.jsonPrimitive?.boolean ?: false, stops = stops, vacation = vacationDraft), portable = o)
            }
            items.forEach(::validateName); require(items.map { it.id }.distinct().size == items.size)
            return items
        }
    }
}

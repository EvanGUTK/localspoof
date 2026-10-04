package dev.localspoof.android

import dev.localspoof.domain.GeoPoint
import dev.localspoof.domain.distanceMeters
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import kotlinx.serialization.json.*

internal data class MobilePlace(val name: String, val point: GeoPoint)
internal data class MapViewport(val point: GeoPoint, val zoom: Double)
internal enum class PlaceCategory(val label: String, val tags: List<String>, val color: String) {
    HOTEL("Hotels", listOf("tourism:hotel", "tourism:motel", "tourism:hostel", "tourism:guest_house"), "#eeae62"),
    FOOD("Food", listOf("amenity:restaurant", "amenity:fast_food"), "#f38480"),
    COFFEE("Coffee", listOf("amenity:cafe"), "#d7ad87"),
    SHOPS("Shops", listOf("shop"), "#e58fc3"),
    FUEL("Fuel", listOf("amenity:fuel"), "#e5c865"),
    HEALTH("Health", listOf("amenity:hospital", "amenity:pharmacy"), "#7bbfdf"),
    ATTRACTIONS("Attractions", listOf("tourism:attraction", "tourism:museum"), "#ad98ee");
    fun accepts(key: String, value: String): Boolean = key.length in 1..40 && value.length in 1..80 && key.none { it.isISOControl() } && value.none { it.isISOControl() } && !(key == "shop" && value.lowercase(java.util.Locale.ROOT) in setOf("vacant", "no", "disused", "abandoned")) && tags.any { it == key || it == "$key:$value" }
}
internal data class NearbyPlace(val name: String, val point: GeoPoint, val category: PlaceCategory, val distanceMeters: Double)
/** Fixed providers only; no activity persistence, redirects, cookies or platform HTTP cache. */
internal class MobileMapNetwork {
    @Volatile private var closed = false
    private val connections = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpsURLConnection>()
    private val gate = MobileMapGate()
    private val generation = java.util.concurrent.atomic.AtomicLong(0)
    private var lastSearch = 0L
    fun cancelRequests() { generation.incrementAndGet(); connections.forEach { it.disconnect() }; connections.clear() }
    fun close() { closed = true; cancelRequests() }
    fun map(path: String): ByteArray {
        val limit = mapLimit(path)
        return get("https://tiles.openfreemap.org$path", limit)
    }
    @Synchronized fun search(query: String): List<MobilePlace> {
        require(query.trim().length in 2..200 && query.none { it.code < 32 })
        rateLimit()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val data = get("https://photon.komoot.io/api/?q=$encoded&limit=8", 262144)
        return decodeSearch(data)
    }
    @Synchronized fun nearby(center: GeoPoint, category: PlaceCategory): List<NearbyPlace> {
        rateLimit()
        return decodeNearby(get(nearbyAddress(center, category), 262144), center, category)
    }
    private fun rateLimit() {
        check(!closed)
        val now = android.os.SystemClock.elapsedRealtime()
        require(now - lastSearch >= 1200 || lastSearch == 0L) { "SEARCH_RATE_LIMIT" }
        lastSearch = now
    }
    private fun get(address: String, limit: Int): ByteArray {
        val owner = generation.get()
        gate.acquire { closed || generation.get() != owner }
        var connection: HttpsURLConnection? = null
        try {
            check(!closed && generation.get() == owner)
            val deadline = android.os.SystemClock.elapsedRealtime() + 12000
            connection = URL(address).openConnection() as HttpsURLConnection
            connections.add(connection)
            connection.instanceFollowRedirects = false; connection.useCaches = false
            connection.connectTimeout = 6000; connection.readTimeout = 6000
            connection.setRequestProperty("User-Agent", "LocalSpoof/0.6 (explicit online map/search)")
            connection.setRequestProperty("Cache-Control", "no-store")
            connection.setRequestProperty("Accept-Encoding", "identity")
            require(connection.responseCode == 200)
            require(connection.contentLengthLong <= limit && connection.contentEncoding.let { it == null || it == "identity" })
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    check(!closed && generation.get() == owner && android.os.SystemClock.elapsedRealtime() < deadline)
                    connection.readTimeout = minOf(6000, (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1).toInt())
                    val count = input.read(buffer); if (count < 0) break
                    require(output.size() + count <= limit)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally { connection?.let { connections.remove(it); it.disconnect() }; gate.release() }
    }
    companion object {
        fun nearbyAddress(center: GeoPoint, category: PlaceCategory): String = "https://photon.komoot.io/reverse?lat=${center.lat}&lon=${center.lon}&radius=10&limit=8" + category.tags.joinToString("") { "&osm_tag=" + URLEncoder.encode(it, "UTF-8") }
        fun decodeNearby(data: ByteArray, center: GeoPoint, category: PlaceCategory): List<NearbyPlace> {
            val root = parseBoundedJson(data, 262144).jsonObject
            val features = root["features"]?.jsonArray ?: throw IllegalArgumentException("SEARCH_INVALID")
            require(features.size <= 8)
            return features.mapNotNull { raw -> runCatching {
                val feature = raw.jsonObject; val geometry = feature.getValue("geometry").jsonObject
                require(geometry["type"]?.jsonPrimitive?.content == "Point")
                val coordinates = geometry.getValue("coordinates").jsonArray
                require(coordinates.size == 2 && coordinates.all { !it.jsonPrimitive.isString })
                val point = GeoPoint(coordinates[1].jsonPrimitive.double, coordinates[0].jsonPrimitive.double)
                val props = feature.getValue("properties").jsonObject
                val key = props.getValue("osm_key").jsonPrimitive; val value = props.getValue("osm_value").jsonPrimitive
                require(key.isString && value.isString && category.accepts(key.content, value.content))
                val name = props["name"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("PLACE_NAME_MISSING")
                require(name.length in 1..300 && name.isNotBlank() && name.none { it.isISOControl() })
                val distance = distanceMeters(center, point)
                require(distance.isFinite() && distance <= 10000)
                NearbyPlace(name, point, category, distance)
            }.getOrNull() }.distinctBy { Triple(it.name, it.point.lat, it.point.lon) }.sortedBy { it.distanceMeters }
        }
        fun mapLimit(path: String): Int {
            require(path.length in 1..1600 && path.startsWith("/") && !path.contains('?') && !path.contains('#') && !path.contains('\\'))
            val lower = path.lowercase(java.util.Locale.ROOT)
            require(listOf("%2f", "%5c", "%2e", "%00", "%0a", "%0d", "/../", "/./").none { lower.contains(it) })
            val parts = path.drop(1).split('/')
            fun slug(value: String) = value.matches(Regex("[A-Za-z0-9_-]{1,128}"))
            fun tile(parts: List<String>, suffix: String, maxZoom: Int): Boolean {
                if (parts.size != 3 || !parts[2].endsWith(suffix)) return false
                val z = parts[0].toIntOrNull() ?: return false
                val x = parts[1].toIntOrNull() ?: return false
                val y = parts[2].removeSuffix(suffix).toIntOrNull() ?: return false
                return z in 0..maxZoom && x in 0 until (1 shl z) && y in 0 until (1 shl z)
            }
            return when {
                parts == listOf("planet") || parts == listOf("planet", "latest") -> 1048576
                parts.size == 5 && parts[0] == "planet" && slug(parts[1]) && tile(parts.drop(2), ".pbf", 14) -> 2097152
                parts.size == 5 && parts.take(2) == listOf("natural_earth", "ne2sr") && tile(parts.drop(2), ".png", 6) -> 2097152
                parts.size == 3 && parts[0] == "sprites" && slug(parts[1]) && parts[2].matches(Regex("[A-Za-z0-9_-]+(@2x)?\\.(png|json)")) -> 4194304
                parts.size == 3 && parts[0] == "fonts" && parts[1].length in 1..1000 && parts[2].matches(Regex("[0-9]+-[0-9]+\\.pbf")) -> {
                    val range = parts[2].removeSuffix(".pbf").split('-').map { it.toInt() }
                    require(range[0] in 0..65280 && range[0] % 256 == 0 && range[1] == range[0] + 255); 524288
                }
                else -> throw IllegalArgumentException("MAP_PATH_REJECTED")
            }
        }
        fun decodeSearch(data: ByteArray): List<MobilePlace> {
            require(data.size <= 262144)
            val root = parseBoundedJson(data, 262144).jsonObject
            val features = root["features"]?.jsonArray ?: throw IllegalArgumentException("SEARCH_INVALID")
            require(features.size <= 8)
            return features.mapNotNull { raw ->
                runCatching {
                    val feature = raw.jsonObject; val geometry = feature.getValue("geometry").jsonObject
                    require(geometry["type"]?.jsonPrimitive?.content == "Point")
                    val coordinates = geometry.getValue("coordinates").jsonArray; require(coordinates.size == 2)
                    require(coordinates.all { !it.jsonPrimitive.isString })
                    val point = GeoPoint(coordinates[1].jsonPrimitive.double, coordinates[0].jsonPrimitive.double)
                    val properties = feature.getValue("properties").jsonObject
                    val fields = listOf("name", "street", "city", "state", "country").mapNotNull { properties[it]?.jsonPrimitive?.contentOrNull?.takeIf { v -> v.length in 1..180 && v.none { c -> c.code < 32 } } }.distinct()
                    require(fields.isNotEmpty())
                    MobilePlace(fields.joinToString(", ").take(300), point)
                }.getOrNull()
            }
        }
    }
}

/** A renderer burst queues behind bounded active I/O instead of permanently losing tiles. */
internal class MobileMapGate(private val activeLimit: Int = 4, private val waitingLimit: Int = 32, private val waitMillis: Long = 12000) {
    private val permits = java.util.concurrent.Semaphore(activeLimit, true)
    private val waiting = java.util.concurrent.atomic.AtomicInteger(0)
    fun acquire(closed: () -> Boolean) {
        check(!closed()) { "MAP_CLOSED" }
        val count = waiting.incrementAndGet()
        val deadline = System.nanoTime() + waitMillis * 1000000
        try {
            check(count <= waitingLimit) { "MAP_QUEUE_LIMIT" }
            while (!closed() && System.nanoTime() < deadline) {
                if (permits.tryAcquire(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    if (closed()) { permits.release(); throw IllegalStateException("MAP_CLOSED") }
                    return
                }
            }
            throw IllegalStateException(if (closed()) "MAP_CLOSED" else "MAP_QUEUE_TIMEOUT")
        } finally { waiting.decrementAndGet() }
    }
    fun release() { permits.release() }
}

package dev.localspoof.domain

import kotlin.math.*

const val FLIGHT_SUGGESTION_METERS = 402_336.0
private const val EARTH_RADIUS_METERS = 6_371_008.8

data class GeoPoint(val lat: Double, val lon: Double) {
    init {
        require(lat.isFinite() && lat in -90.0..90.0) { "INVALID_LATITUDE" }
        require(lon.isFinite() && lon in -180.0..180.0) { "INVALID_LONGITUDE" }
    }
    fun distanceTo(other: GeoPoint): Double = distanceMeters(this, other)
}

fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val p1 = Math.toRadians(a.lat)
    val p2 = Math.toRadians(b.lat)
    val dp = p2 - p1
    val dl = Math.toRadians(normalizedLongitude(b.lon - a.lon))
    val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

fun suggestFlight(distanceMeters: Double): Boolean {
    require(distanceMeters.isFinite() && distanceMeters >= 0) { "INVALID_DISTANCE" }
    return distanceMeters > FLIGHT_SUGGESTION_METERS
}

fun suggestFlight(origin: GeoPoint, destination: GeoPoint): Boolean = suggestFlight(distanceMeters(origin, destination))

fun normalizedLongitude(value: Double): Double = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

fun interpolate(a: GeoPoint, b: GeoPoint, fraction: Double): GeoPoint {
    require(fraction.isFinite()) { "INVALID_FRACTION" }
    val f = fraction.coerceIn(0.0, 1.0)
    if (f == 0.0) return a
    if (f == 1.0) return b
    // Spherical interpolation avoids a detour around the world at the date line.
    val p1 = Math.toRadians(a.lat)
    val l1 = Math.toRadians(a.lon)
    val p2 = Math.toRadians(b.lat)
    val l2 = Math.toRadians(b.lon)
    val omega = distanceMeters(a, b) / EARTH_RADIUS_METERS
    if (omega < 1e-10) return a
    if (abs(sin(omega)) < 1e-8) {
        return GeoPoint(a.lat + (b.lat - a.lat) * f, normalizedLongitude(a.lon + normalizedLongitude(b.lon - a.lon) * f))
    }
    val u = sin((1 - f) * omega) / sin(omega)
    val v = sin(f * omega) / sin(omega)
    val x = u * cos(p1) * cos(l1) + v * cos(p2) * cos(l2)
    val y = u * cos(p1) * sin(l1) + v * cos(p2) * sin(l2)
    val z = u * sin(p1) + v * sin(p2)
    return GeoPoint(Math.toDegrees(atan2(z, hypot(x, y))), normalizedLongitude(Math.toDegrees(atan2(y, x))))
}

fun bearingDegrees(a: GeoPoint, b: GeoPoint): Double {
    if (distanceMeters(a, b) < 0.001) return 0.0
    val p1 = Math.toRadians(a.lat)
    val p2 = Math.toRadians(b.lat)
    val dl = Math.toRadians(normalizedLongitude(b.lon - a.lon))
    return (Math.toDegrees(atan2(sin(dl) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl))) + 360.0) % 360.0
}

data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    init {
        require(listOf(south, west, north, east).all(Double::isFinite)) { "INVALID_BOUNDS" }
        require(south in -90.0..90.0 && north in south..90.0 && west in -180.0..180.0 && east in -180.0..180.0) { "INVALID_BOUNDS" }
    }
    fun contains(point: GeoPoint): Boolean = point.lat in south..north &&
        if (west <= east) point.lon in west..east else point.lon >= west || point.lon <= east
}

enum class SpeedLimitSource { MAPPED, ESTIMATED, UNKNOWN }

data class RouteGeometry(val points: List<GeoPoint>, val edgeSpeedLimits: List<Double?> = emptyList(), val edgeSpeedLimitSources: List<SpeedLimitSource> = emptyList()) {
    init {
        require(points.isNotEmpty() && points.size <= 100_000) { "INVALID_GEOMETRY_SIZE" }
        require(edgeSpeedLimits.isEmpty() || edgeSpeedLimits.size == points.size - 1) { "INVALID_SPEED_LIMIT_COUNT" }
        require(edgeSpeedLimitSources.isEmpty() || edgeSpeedLimitSources.size == points.size - 1) { "INVALID_SPEED_LIMIT_SOURCE_COUNT" }
        require(edgeSpeedLimits.all { it == null || it.isFinite() && it > 0 }) { "INVALID_SPEED_LIMIT" }
    }
    val cumulativeMeters: List<Double> = buildList {
        add(0.0)
        for (i in 1 until points.size) add(last() + distanceMeters(points[i - 1], points[i]))
    }
    /** Conservative geometric curve estimates, not posted legal limits or traffic data. */
    val turnSpeedLimits: List<Double?> = points.indices.map { index ->
        if (index == 0 || index == points.lastIndex) null else {
            val before = distanceMeters(points[index - 1], points[index])
            val after = distanceMeters(points[index], points[index + 1])
            val change = abs(normalizedLongitude(bearingDegrees(points[index], points[index + 1]) - bearingDegrees(points[index - 1], points[index])))
            if (before < 0.1 || after < 0.1 || change < 35.0) null else {
                val radius = (min(before, after) / (2 * sin(Math.toRadians(change) / 2))).coerceIn(8.0, 250.0)
                sqrt(2.0 * radius).coerceIn(2.5, 25.0)
            }
        }
    }
    fun mappedLimitAt(distance: Double): Double? = if (edgeSpeedLimitSources.getOrNull(edgeIndex(distance)) == SpeedLimitSource.MAPPED) limitAt(distance) else null
    val distanceMeters: Double get() = cumulativeMeters.last()
    fun edgeIndex(distance: Double): Int {
        if (points.size < 2) return 0
        val target = distance.coerceIn(0.0, distanceMeters)
        var low = 0
        var high = cumulativeMeters.lastIndex
        while (low < high) {
            val mid = (low + high) / 2
            if (cumulativeMeters[mid] <= target) low = mid + 1 else high = mid
        }
        return (low - 1).coerceIn(0, points.lastIndex - 1)
    }
    fun pointAt(distance: Double): GeoPoint {
        if (points.size == 1 || distanceMeters == 0.0) return points.last()
        if (distance >= distanceMeters) return points.last()
        val i = edgeIndex(distance)
        val length = cumulativeMeters[i + 1] - cumulativeMeters[i]
        return if (length == 0.0) points[i + 1] else interpolate(points[i], points[i + 1], (distance - cumulativeMeters[i]) / length)
    }
    fun limitAt(distance: Double): Double? = if (edgeSpeedLimits.isEmpty()) null else edgeSpeedLimits[edgeIndex(distance)]
}

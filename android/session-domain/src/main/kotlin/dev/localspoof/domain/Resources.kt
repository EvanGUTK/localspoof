package dev.localspoof.domain

import java.util.PriorityQueue

enum class Mode { INSTANT, WALK, DRIVE, FLIGHT }
enum class RouteSource { INSTANT_LOCATION, OFFLINE_GEOPACK, ONLINE_ROUTER, CUSTOM_PATH, FLIGHT_JOURNEY }
const val MAX_ONLINE_ROUTE_POINTS = 12_000

/** Desktop network output is untrusted geometry, not proof of road access or provider identity. */
data class OnlineRoute(val profile: Mode, val provider: String, val points: List<GeoPoint>, val edgeSpeedLimits: List<Double?> = emptyList()) {
    init {
        require(profile == Mode.WALK || profile == Mode.DRIVE) { "INVALID_ONLINE_ROUTE_PROFILE" }
        require(provider.isNotBlank() && provider.length <= 120 && provider.none { it.isISOControl() }) { "INVALID_ROUTE_PROVIDER" }
        require(points.isNotEmpty() && points.size <= MAX_ONLINE_ROUTE_POINTS) { "INVALID_ONLINE_ROUTE_SIZE" }
        require(edgeSpeedLimits.isEmpty() || edgeSpeedLimits.size == points.size - 1) { "INVALID_SPEED_LIMIT_COUNT" }
        require(edgeSpeedLimits.all { it == null || it.isFinite() && it in 0.1..100.0 }) { "INVALID_SPEED_LIMIT" }
        var length = 0.0
        for (index in 1 until points.size) {
            val segment = distanceMeters(points[index - 1], points[index])
            require(segment <= 100_000.0) { "ONLINE_ROUTE_SEGMENT_TOO_LONG" }
            length += segment
            require(length <= 20_000_000.0) { "ONLINE_ROUTE_TOO_LONG" }
        }
    }
    fun frozen(): OnlineRoute = copy(points = points.toList(), edgeSpeedLimits = edgeSpeedLimits.toList())
    fun geometry(origin: GeoPoint, destination: GeoPoint, mode: Mode, maxOriginSnapMeters: Double = 250.0, maxDestinationSnapMeters: Double = 250.0): RouteGeometry {
        require(profile == mode) { "ONLINE_ROUTE_PROFILE_MISMATCH" }
        require(maxOriginSnapMeters in 0.0..5_000.0 && maxDestinationSnapMeters in 0.0..5_000.0) { "INVALID_ONLINE_SNAP_DISTANCE" }
        require(distanceMeters(points.first(), origin) <= maxOriginSnapMeters && distanceMeters(points.last(), destination) <= maxDestinationSnapMeters) { "ONLINE_ROUTE_ENDPOINT_MISMATCH" }
        require(points.size >= 2 || distanceMeters(origin, destination) < 0.01) { "INVALID_ONLINE_ROUTE_SIZE" }
        return RouteGeometry(points, edgeSpeedLimits, edgeSpeedLimits.map { if (it == null) SpeedLimitSource.UNKNOWN else SpeedLimitSource.MAPPED })
    }
}

data class OnlineFlightRoutes(
    val departureAirportId: String,
    val arrivalAirportId: String,
    val originGround: OnlineRoute? = null,
    val destinationGround: OnlineRoute? = null,
) {
    init {
        require(departureAirportId.isNotBlank() && departureAirportId.length <= 128 && arrivalAirportId.isNotBlank() && arrivalAirportId.length <= 128 && departureAirportId != arrivalAirportId) { "INVALID_ONLINE_AIRPORT_IDS" }
        require((originGround == null || originGround.profile == Mode.DRIVE) && (destinationGround == null || destinationGround.profile == Mode.DRIVE)) { "INVALID_ONLINE_FLIGHT_PROFILE" }
        require((originGround?.points?.size ?: 0) + (destinationGround?.points?.size ?: 0) <= MAX_ONLINE_ROUTE_POINTS) { "INVALID_ONLINE_ROUTE_SIZE" }
    }
    fun frozen(): OnlineFlightRoutes = copy(originGround = originGround?.frozen(), destinationGround = destinationGround?.frozen())
}

data class Place(val id: String, val name: String, val point: GeoPoint)
data class Airport(
    val id: String,
    val name: String,
    val point: GeoPoint,
    val type: String = "medium_airport",
    val scheduledService: Boolean = true,
    val closed: Boolean = false,
)
data class GeoNode(val id: String, val point: GeoPoint)
data class GeoEdge(
    val from: String,
    val to: String,
    val modes: Set<Mode>,
    val speedLimitMps: Double? = null,
    val bidirectional: Boolean = true,
    val speedLimitSource: SpeedLimitSource = SpeedLimitSource.UNKNOWN,
)
data class GeoPack(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val bounds: GeoBounds,
    val nodes: List<GeoNode>,
    val edges: List<GeoEdge>,
    val places: List<Place> = emptyList(),
    val polygons: List<List<GeoPoint>> = emptyList(),
    val source: String = "",
    val attribution: String = "",
) {
    init {
        require(schemaVersion == 1) { "UNSUPPORTED_PACK_SCHEMA" }
        require(id.isNotBlank() && id.length <= 128 && name.length <= 512) { "INVALID_PACK_ID" }
        require(nodes.size <= 200_000 && edges.size <= 1_000_000 && places.size <= 100_000) { "PACK_TOO_LARGE" }
        require(polygons.size <= 100_000 && polygons.sumOf { it.size.toLong() } <= 1_000_000) { "PACK_TOO_LARGE" }
        val ids = nodes.map { it.id }.toSet()
        require(ids.size == nodes.size && nodes.all { it.id.isNotBlank() && it.id.length <= 128 && bounds.contains(it.point) }) { "INVALID_GRAPH_NODE" }
        require(edges.all { it.from in ids && it.to in ids && it.modes.isNotEmpty() && it.modes.all { mode -> mode == Mode.WALK || mode == Mode.DRIVE } }) { "INVALID_GRAPH_EDGE" }
        require(edges.all { it.speedLimitMps == null || it.speedLimitMps.isFinite() && it.speedLimitMps > 0 && it.speedLimitMps <= 100 }) { "INVALID_SPEED_LIMIT" }
        require(places.all { it.id.length <= 128 && it.name.length <= 512 && bounds.contains(it.point) }) { "INVALID_PLACE" }
    }

    fun search(query: String, limit: Int = 20): List<Place> {
        require(query.length <= 256 && limit in 1..100) { "INVALID_SEARCH" }
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return places.asSequence().filter { it.name.contains(needle, ignoreCase = true) }.take(limit).toList()
    }

    fun frozen(): GeoPack = copy(nodes = nodes.toList(), edges = edges.map { it.copy(modes = it.modes.toSet()) }, places = places.toList(), polygons = polygons.map { it.toList() })
}

/** Opaque validated static resources; callers cannot build an unchecked handle. */
class PreparedResources internal constructor(internal val pack: GeoPack?, internal val airports: List<Airport>)

class RoutePlanningException(val code: String) : IllegalArgumentException(code)

/** Profile-aware graph route. No straight-line fallback; endpoint snapping is bounded and disclosed. */
class GraphRouter(private val pack: GeoPack, private val maxSnapMeters: Double = 250.0) {
    private data class Arc(val to: String, val meters: Double, val speedLimit: Double?, val source: SpeedLimitSource)
    private data class QueueEntry(val id: String, val cost: Double)

    init { require(maxSnapMeters.isFinite() && maxSnapMeters > 0 && maxSnapMeters <= 5_000) { "INVALID_SNAP_DISTANCE" } }

    fun route(origin: GeoPoint, destination: GeoPoint, mode: Mode, speedMps: Double, cancelled: () -> Boolean = { false }): RouteGeometry {
        require(mode == Mode.WALK || mode == Mode.DRIVE) { "INVALID_ROUTE_MODE" }
        require(speedMps.isFinite() && speedMps > 0) { "INVALID_SPEED" }
        if (!pack.bounds.contains(origin) || !pack.bounds.contains(destination)) throw RoutePlanningException("MISSING_COVERAGE")
        val nodes = pack.nodes.associateBy { it.id }
        val adjacency = HashMap<String, MutableList<Arc>>()
        val routable = HashSet<String>()
        for ((index, edge) in pack.edges.withIndex()) {
            if (index % 1024 == 0 && cancelled()) throw RoutePlanningException("PREPARATION_CANCELLED")
            if (mode !in edge.modes) continue
            val length = distanceMeters(nodes.getValue(edge.from).point, nodes.getValue(edge.to).point)
            adjacency.getOrPut(edge.from) { mutableListOf() }.add(Arc(edge.to, length, edge.speedLimitMps, edge.speedLimitSource))
            if (edge.bidirectional) adjacency.getOrPut(edge.to) { mutableListOf() }.add(Arc(edge.from, length, edge.speedLimitMps, edge.speedLimitSource))
            routable.add(edge.from)
            routable.add(edge.to)
        }
        fun snap(point: GeoPoint): GeoNode {
            val nearest = pack.nodes.asSequence().filter { it.id in routable }.minByOrNull { distanceMeters(point, it.point) }
                ?: throw RoutePlanningException("NO_ROUTE")
            if (distanceMeters(point, nearest.point) > maxSnapMeters) throw RoutePlanningException("NO_NEARBY_ROAD")
            return nearest
        }
        val start = snap(origin)
        val end = snap(destination)
        if (start.id == end.id) return RouteGeometry(listOf(start.point))
        val distances = HashMap<String, Double>()
        val previous = HashMap<String, Triple<String, Double?, SpeedLimitSource>>()
        val queue = PriorityQueue<QueueEntry>(compareBy { it.cost })
        distances[start.id] = 0.0
        queue.add(QueueEntry(start.id, 0.0))
        var visited = 0
        while (queue.isNotEmpty()) {
            if (++visited > 500_000) throw RoutePlanningException("ROUTE_TOO_COMPLEX")
            if (cancelled()) throw RoutePlanningException("PREPARATION_CANCELLED")
            val current = queue.remove()
            if (current.cost != distances[current.id]) continue
            if (current.id == end.id) break
            for (arc in adjacency[current.id].orEmpty()) {
                val effectiveSpeed = minOf(speedMps, arc.speedLimit ?: speedMps)
                val proposed = current.cost + arc.meters / effectiveSpeed
                if (proposed < (distances[arc.to] ?: Double.POSITIVE_INFINITY)) {
                    distances[arc.to] = proposed
                    previous[arc.to] = Triple(current.id, arc.speedLimit, arc.source)
                    queue.add(QueueEntry(arc.to, proposed))
                }
            }
        }
        if (end.id !in distances) throw RoutePlanningException("NO_ROUTE")
        val reversed = mutableListOf(end.point)
        val limits = mutableListOf<Double?>()
        val sources = mutableListOf<SpeedLimitSource>()
        var id = end.id
        while (id != start.id) {
            if (reversed.size >= 100_000) throw RoutePlanningException("ROUTE_TOO_COMPLEX")
            val predecessor = previous[id] ?: throw RoutePlanningException("NO_ROUTE")
            limits.add(predecessor.second); sources.add(predecessor.third)
            id = predecessor.first
            reversed.add(nodes.getValue(id).point)
        }
        return RouteGeometry(reversed.asReversed().toList(), limits.asReversed().toList(), sources.asReversed().toList())
    }
}

fun airportCandidates(airports: List<Airport>, origin: GeoPoint, limit: Int = 8): List<Airport> {
    require(limit in 1..32) { "INVALID_AIRPORT_LIMIT" }
    return airports.asSequence().filter {
        !it.closed && it.scheduledService && it.type == "large_airport"
    }.sortedBy { distanceMeters(origin, it.point) }.take(limit).toList()
}

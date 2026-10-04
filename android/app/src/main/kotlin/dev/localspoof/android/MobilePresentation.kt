package dev.localspoof.android

import dev.localspoof.domain.Mode
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

enum class MobileSection(val label: String) { MAP("Map"), CONNECT("Connect"), JOURNEY("Journey"), SETTINGS("Settings") }
enum class MobileSpeedUnit(val label: String, val factor: Double) { MPH("mph", 0.44704), KMH("km/h", 1.0 / 3.6), MPS("m/s", 1.0) }
enum class MobileDistanceUnit { MI, KM }
data class MobilePreferences(val speedUnit: MobileSpeedUnit = MobileSpeedUnit.MPH, val distanceUnit: MobileDistanceUnit = MobileDistanceUnit.MI, val walkSpeedMps: Double = 1.4, val driveSpeedMps: Double = 13.9)
data class MobileLayoutPolicy(val twoColumnFields: Boolean, val compactHeight: Boolean)
fun mobileLayoutPolicy(widthDp: Float, heightDp: Float, fontScale: Float): MobileLayoutPolicy {
    val scale = fontScale.coerceAtLeast(1f)
    val effectiveWidth = widthDp / scale
    return MobileLayoutPolicy(effectiveWidth >= 440f, heightDp / scale < 420f)
}
fun mobileMaximumSpeed(mode: Mode): Double = if (mode == Mode.WALK) 3.0 else if (mode == Mode.FLIGHT) 350.0 else 56.0
fun mobileDisplaySpeed(speedMps: Double, unit: MobileSpeedUnit): Double = speedMps / unit.factor
fun mobileFormatSpeed(speedMps: Double, unit: MobileSpeedUnit): String = BigDecimal.valueOf(mobileDisplaySpeed(speedMps, unit)).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
fun mobileParseSpeed(value: String, unit: MobileSpeedUnit, mode: Mode): Double {
    val speed = (value.trim().toDoubleOrNull() ?: Double.NaN) * unit.factor
    val max = mobileMaximumSpeed(mode)
    val tolerance = unit.factor * 0.500001e-6
    require(speed.isFinite() && speed >= (if (mode == Mode.FLIGHT) 30.0 else 0.1) - tolerance && speed <= max + tolerance) {
        "Use ${mobileFormatSpeed(0.1, unit)}–${mobileFormatSpeed(max, unit)} ${unit.label} for this mode."
    }
    return speed.coerceIn(if (mode == Mode.FLIGHT) 30.0 else 0.1, max)
}
fun mobileDistanceLabel(meters: Double, unit: MobileDistanceUnit): String = String.format(Locale.ROOT, "%.1f %s", meters / if (unit == MobileDistanceUnit.MI) 1609.344 else 1000.0, if (unit == MobileDistanceUnit.MI) "mi" else "km")
fun mobilePairingComparison(code: String): String {
    if (!code.matches(Regex("[0-9]{6}"))) return "Pairing number unavailable"
    return code.chunked(3).joinToString(" ")
}

fun mobileEtaLabel(snapshot: dev.localspoof.domain.SessionSnapshot): String {
    if (snapshot.state == dev.localspoof.domain.SessionState.PAUSED && snapshot.error == "SCHEDULING_GAP") return "Paused after a scheduling gap · Resume when ready"
    if (snapshot.state == dev.localspoof.domain.SessionState.PAUSED) return "Paused · ETA resumes with playback"
    snapshot.waitingFor?.let { return when (it) { "AIRPLANE_MODE" -> "Waiting for airplane mode"; "RESTORE_CONNECTIVITY" -> "Waiting for connectivity restoration"; "PROVIDER_ACK" -> "Confirming location placement"; "STOP_DWELL" -> "At a planned stop"; else -> "Waiting · ETA updates when ready" } }
    if (snapshot.state == dev.localspoof.domain.SessionState.HOLDING) return "Arrived · holding location"
    val remaining = snapshot.remainingMs ?: return "ETA available during playback"
    val minutes = (remaining + 59999) / 60000
    val arrival = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(System.currentTimeMillis() + remaining))
    return "${if (minutes <= 0) "Arriving" else "$minutes min remaining"} · ETA $arrival"
}

internal fun mobileBoardingValid(draft: MobileDraft): Boolean = draft.mode != dev.localspoof.domain.Mode.FLIGHT || draft.boardingHoldSeconds.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..3600.0 } == true

/** Display submitted simulation telemetry, never a route target or predicted velocity. */
internal fun mobileMotionSpeed(motion: MotionTelemetry, unit: MobileSpeedUnit): String {
    val speed = motion.speedMps?.takeIf { it.isFinite() && it >= 0.0 && it <= 350.0 }
    val fresh = motion.sampleAgeMs?.let { it in 0..1500 } == true
    return if (speed != null && fresh && motion.health in setOf("LIVE", "WAITING"))
        String.format(Locale.ROOT, "%.0f %s", mobileDisplaySpeed(speed, unit), unit.label) else "— ${unit.label}"
}
internal fun mobileMotionStatus(motion: MotionTelemetry): String = when (motion.health) {
    "LIVE" -> "Live"; "STALE" -> "Stale · check playback"; "PAUSED" -> "Paused"; "WAITING" -> "Waiting"; "ERROR" -> "Playback error"; else -> "No active motion"
}
internal fun mobileRoadLimitLabel(motion: MotionTelemetry, unit: MobileSpeedUnit): String =
    if (motion.roadLimitSource == "MAPPED" && motion.roadLimitMps?.let { it.isFinite() && it > 0.0 } == true)
        "Mapped road limit ${String.format(Locale.ROOT, "%.0f %s", mobileDisplaySpeed(motion.roadLimitMps, unit), unit.label)}" else "Road limit unknown"
internal fun mobileRadioPrompt(snapshot: dev.localspoof.domain.SessionSnapshot, observed: Boolean? = null): String? {
    if (snapshot.state != dev.localspoof.domain.SessionState.ACTIVE) return null
    val desired = snapshot.desiredAirplaneMode ?: return null
    if (desired == observed) return null
    return when (snapshot.phase) {
        dev.localspoof.domain.FlightPhase.AWAIT_AIRPLANE_MODE -> if (desired) "Enable airplane mode on your phone now" else null
        dev.localspoof.domain.FlightPhase.RESTORE_CONNECTIVITY -> if (desired) "Restore airplane mode on your phone now" else "Turn airplane mode off on your phone now"
        else -> null
    }
}
internal fun mobileDrivingControls(draft: MobileDraft): dev.localspoof.domain.DrivingSpeedControls = dev.localspoof.domain.DrivingSpeedControls(draft.drivingSpeedMode, draft.driveMinMps, draft.driveMaxMps, draft.followRoadLimits)
internal fun mobileDrivingValid(draft: MobileDraft) = draft.mode !in setOf(Mode.DRIVE, Mode.FLIGHT) || draft.drivingSpeedMode != dev.localspoof.domain.DrivingSpeedMode.VARIABLE || draft.drivingError == null

internal enum class MobileFuelInput { PERCENT, AMOUNT, RANGE }
internal data class MobileVacationDraft(
    val enabled: Boolean = false, val metric: Boolean = false, val vehicle: String = "Custom vehicle",
    val tank: String = "", val consumption: String = "", val startingMethod: MobileFuelInput = MobileFuelInput.PERCENT,
    val starting: String = "", val reserve: String = "", val grade: dev.localspoof.domain.FuelGrade = dev.localspoof.domain.FuelGrade.entries.first(),
    val price: String = "", val currency: String = "USD", val hotelNightly: String = "", val hotelNights: String = ""
)
internal fun mobileVacationConfig(draft: MobileVacationDraft): dev.localspoof.domain.VacationConfig? {
    if (!draft.enabled) return null
    fun number(value: String): Double = value.toDouble().also { require(it.isFinite() && it >= 0.0) }
    val volume = if (draft.metric) 1.0 else 3.785411784
    val liters100 = if (draft.metric) number(draft.consumption) else 235.214583 / number(draft.consumption)
    require(liters100.isFinite() && liters100 > 0)
    val capacity = number(draft.tank) * volume
    val entered = number(draft.starting)
    val starting = when (draft.startingMethod) {
        MobileFuelInput.PERCENT -> { require(entered <= 100); capacity * entered / 100 }
        MobileFuelInput.AMOUNT -> entered * volume
        MobileFuelInput.RANGE -> entered * (if (draft.metric) 1.0 else 1.609344) * liters100 / 100
    }
    return dev.localspoof.domain.VacationConfig(tankLiters = capacity, startingFuelLiters = starting,
        reserveLiters = number(draft.reserve) * volume, litersPer100Km = liters100, fuelGrade = draft.grade,
        currency = draft.currency.trim().uppercase(Locale.ROOT), fuelPricePerLiter = draft.price.takeIf { it.isNotBlank() }?.let { number(it) / volume })
}

internal fun mobilePlanRequest(draft: MobileDraft, confirmedOrigin: dev.localspoof.domain.GeoPoint? = null,
    lockedVacation: dev.localspoof.domain.VacationConfig? = null): dev.localspoof.domain.PlanRequest {
    require(draft.mode != Mode.FLIGHT || draft.flightTransitMode != dev.localspoof.domain.FlightTransitMode.TIMED || draft.flightSpeedError == null)
    require(draft.speedError == null || draft.mode == Mode.FLIGHT && draft.departureGroundMode == dev.localspoof.domain.GroundLegMode.INSTANT && draft.arrivalGroundMode == dev.localspoof.domain.GroundLegMode.INSTANT)
    require(mobileDrivingValid(draft))
    val destination = dev.localspoof.domain.GeoPoint(draft.destinationLat.toDouble(), draft.destinationLon.toDouble())
    val origin = confirmedOrigin ?: if (draft.mode == Mode.INSTANT) destination else dev.localspoof.domain.GeoPoint(draft.originLat.toDouble(), draft.originLon.toDouble())
    val custom = if (draft.customPath && draft.mode in setOf(Mode.WALK, Mode.DRIVE)) listOf(origin) + draft.stops.map { it.point } + destination else null
    val flight = if (draft.mode == Mode.FLIGHT) {
        val boardingSeconds = draft.boardingHoldSeconds.toDouble().also { require(it.isFinite() && it in 0.0..3600.0) }
        dev.localspoof.domain.FlightOptions(simulationOnly = draft.simulationOnly, boardingMs = (boardingSeconds * 1000).toLong(),
            originAirportId = draft.departureId.trim().takeIf { it.isNotBlank() }, destinationAirportId = draft.arrivalId.trim().takeIf { it.isNotBlank() },
            departureGroundMode = draft.departureGroundMode, arrivalGroundMode = draft.arrivalGroundMode, flightTransitMode = draft.flightTransitMode)
    } else null
    return dev.localspoof.domain.PlanRequest(draft.mode, origin, destination, draft.speedMps, custom, flight,
        flightSpeedMps = draft.flightSpeedMps.takeIf { draft.mode == Mode.FLIGHT }, stops = draft.stops.takeIf { draft.mode in setOf(Mode.WALK, Mode.DRIVE) } ?: emptyList(),
        speedControls = mobileDrivingControls(draft), vacation = lockedVacation ?: mobileVacationConfig(draft.vacation))
}

internal fun mobileVacationDraft(config: dev.localspoof.domain.VacationConfig) = MobileVacationDraft(enabled = true, metric = true,
    tank = config.tankLiters.toString(), consumption = config.litersPer100Km.toString(), startingMethod = MobileFuelInput.AMOUNT,
    starting = config.startingFuelLiters.toString(), reserve = config.reserveLiters.toString(), grade = config.fuelGrade,
    price = config.fuelPricePerLiter?.toString() ?: "", currency = config.currency)

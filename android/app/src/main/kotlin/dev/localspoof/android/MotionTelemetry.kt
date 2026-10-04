package dev.localspoof.android

import dev.localspoof.domain.SessionSnapshot
import dev.localspoof.domain.SessionState

data class MotionTelemetry(
    val speedMps: Double? = null,
    val sampleAgeMs: Long? = null,
    val sampleSequence: Long = 0,
    val roadLimitMps: Double? = null,
    val roadLimitSource: String = "UNKNOWN",
    val health: String = "IDLE",
    val placementGeneration: Long = 0,
)

/** Reports only a successfully submitted current fix, never a target or predicted position. */
internal fun motionTelemetry(snapshot: SessionSnapshot, location: PhoneLocation?, armed: Boolean): MotionTelemetry {
    val submitted = location?.takeIf { it.source == PhoneLocationSource.SIMULATION }
    val fresh = submitted != null && submitted.ageMs <= 1_500
    val health = when {
        snapshot.state == SessionState.ERROR -> "ERROR"
        snapshot.error == "SCHEDULING_GAP" -> "STALE"
        snapshot.state == SessionState.PAUSED -> "PAUSED"
        !armed || snapshot.state in setOf(SessionState.IDLE, SessionState.READY) -> "IDLE"
        !fresh -> "STALE"
        snapshot.waitingFor != null -> "WAITING"
        else -> "LIVE"
    }
    val moving = snapshot.state in setOf(SessionState.ACTIVE, SessionState.HOLDING) && snapshot.waitingFor !in setOf("PROVIDER_ACK", "AIRPLANE_MODE", "RESTORE_CONNECTIVITY")
    val displaySpeed = submitted?.speedMps?.takeIf { fresh && armed && (moving || it == 0.0) && snapshot.state != SessionState.ERROR && snapshot.error != "SCHEDULING_GAP" }
    return MotionTelemetry(displaySpeed, submitted?.ageMs, submitted?.sampleSequence ?: 0,
        snapshot.roadLimitMps, if (snapshot.roadLimitMps == null) "UNKNOWN" else "MAPPED", health, submitted?.placementGeneration ?: 0)
}

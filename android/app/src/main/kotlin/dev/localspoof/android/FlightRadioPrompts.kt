package dev.localspoof.android

import dev.localspoof.domain.FlightPhase
import dev.localspoof.domain.Mode
import dev.localspoof.domain.SessionSnapshot
import dev.localspoof.domain.SessionState

/** RAM-only gate de-duplication. A prompt never acknowledges or changes a radio setting. */
internal class FlightRadioPrompts {
    private var alertedGate: Pair<FlightPhase, Boolean>? = null
    fun next(snapshot: SessionSnapshot): Boolean? {
        if (snapshot.mode != Mode.FLIGHT || snapshot.state in setOf(SessionState.IDLE,SessionState.READY) ||
            snapshot.phase in setOf(FlightPhase.ORIGIN_GROUND,FlightPhase.ORIGIN_PLACEMENT,FlightPhase.COMPLETE)) alertedGate = null
        if (snapshot.state != SessionState.ACTIVE || snapshot.phase !in setOf(FlightPhase.AWAIT_AIRPLANE_MODE,FlightPhase.RESTORE_CONNECTIVITY)) return null
        val desired = snapshot.desiredAirplaneMode ?: return null
        val gate = snapshot.phase!! to desired
        if (gate == alertedGate) return null
        alertedGate = gate
        return desired
    }
}

internal fun radioGuidance(desired: Boolean): String = if (desired) "Enable airplane mode on your phone now" else "Turn airplane mode off on your phone now"

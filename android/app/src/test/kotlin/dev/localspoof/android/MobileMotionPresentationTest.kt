package dev.localspoof.android

import dev.localspoof.domain.*
import org.junit.Assert.*
import org.junit.Test

class MobileMotionPresentationTest {
    @Test fun gaugeUsesSubmittedSpeedAndNeverSubstitutesConfiguredTarget() {
        assertEquals("60 mph", mobileMotionSpeed(MotionTelemetry(speedMps = 26.8224, sampleAgeMs = 200, health = "LIVE"), MobileSpeedUnit.MPH))
        assertEquals("97 km/h", mobileMotionSpeed(MotionTelemetry(speedMps = 26.8224, sampleAgeMs = 200, health = "LIVE"), MobileSpeedUnit.KMH))
        for (health in listOf("STALE", "PAUSED", "ERROR", "IDLE")) assertEquals("— mph", mobileMotionSpeed(MotionTelemetry(speedMps = 55.88, sampleAgeMs = 200, health = health), MobileSpeedUnit.MPH))
        assertEquals("— mph", mobileMotionSpeed(MotionTelemetry(speedMps = 55.88, sampleAgeMs = 1501, health = "LIVE"), MobileSpeedUnit.MPH))
        assertEquals("— mph", mobileMotionSpeed(MotionTelemetry(health = "LIVE"), MobileSpeedUnit.MPH))
    }
    @Test fun cruiseBoundsAccept125MphButRejectReversedAndInvalidRanges() {
        val state = MobileSessionState(); state.selectMode(Mode.DRIVE)
        state.selectDrivingMode(DrivingSpeedMode.VARIABLE)
        state.edit(MobileField.DRIVE_MIN, "90"); state.edit(MobileField.DRIVE_MAX, "125")
        assertNull(state.draft.drivingError)
        assertEquals(40.2336, state.draft.driveMinMps, 1e-9)
        assertEquals(55.88, state.draft.driveMaxMps, 1e-9)
        state.draft = state.draft.copy(preparedId = "fixture")
        state.edit(MobileField.DRIVE_MIN, "126")
        assertNotNull(state.draft.drivingError); assertNull(state.draft.preparedId)
        state.changeSpeedUnit(MobileSpeedUnit.KMH)
        assertEquals(MobileSpeedUnit.MPH, state.preferences.speedUnit)
        state.edit(MobileField.DRIVE_MIN, "100"); state.edit(MobileField.DRIVE_MAX, "90")
        assertNotNull(state.draft.drivingError)
        state.edit(MobileField.DRIVE_MAX, "125")
        val min = state.draft.driveMinMps; val max = state.draft.driveMaxMps
        state.changeSpeedUnit(MobileSpeedUnit.KMH)
        assertEquals(min, state.draft.driveMinMps, 0.0); assertEquals(max, state.draft.driveMaxMps, 0.0)
    }
    @Test fun unknownLimitsRemainUnknownAndRestorePromptUsesOriginalState() {
        assertEquals("Road limit unknown", mobileRoadLimitLabel(MotionTelemetry(), MobileSpeedUnit.MPH))
        assertEquals("Road limit unknown", mobileRoadLimitLabel(MotionTelemetry(roadLimitMps = 20.0, roadLimitSource = "UNKNOWN"), MobileSpeedUnit.MPH))
        val base = Coordinator().snapshot().copy(state = SessionState.ACTIVE, mode = Mode.FLIGHT, phase = FlightPhase.RESTORE_CONNECTIVITY, waitingFor = "RESTORE_CONNECTIVITY")
        assertEquals("Turn airplane mode off on your phone now", mobileRadioPrompt(base.copy(desiredAirplaneMode = false)))
        assertEquals("Restore airplane mode on your phone now", mobileRadioPrompt(base.copy(desiredAirplaneMode = true)))
        assertNull(mobileRadioPrompt(base.copy(state = SessionState.PAUSED, desiredAirplaneMode = false)))
        assertNull(mobileRadioPrompt(base.copy(desiredAirplaneMode = true), observed = true))
        assertNull(mobileRadioPrompt(base.copy(desiredAirplaneMode = false), observed = false))
        assertNull(mobileRadioPrompt(base.copy(desiredAirplaneMode = null)))
        assertNull(mobileRadioPrompt(base.copy(state = SessionState.IDLE, desiredAirplaneMode = false)))
        assertEquals("Paused after a scheduling gap · Resume when ready", mobileEtaLabel(base.copy(state = SessionState.PAUSED, error = "SCHEDULING_GAP")))
        assertTrue(mobileDrivingValid(MobileDraft(mode = Mode.WALK, drivingSpeedMode = DrivingSpeedMode.VARIABLE, drivingError = "Invalid hidden car input")))
        val state = MobileSessionState(); state.selectMode(Mode.FLIGHT); state.edit(MobileField.SPEED, "130")
        assertNotNull(state.draft.speedError)
        assertTrue(state.draft.speedError!!.contains("125."))
        assertFalse(state.draft.speedError!!.contains("782."))
    }
}

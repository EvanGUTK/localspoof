package dev.localspoof.android

import dev.localspoof.domain.Mode
import org.junit.Assert.*
import org.junit.Test

class MobileSessionStateTest {
    @Test fun flightLegChoiceChangesInvalidatePreparedAndInFlightPreviewReplies() {
        val state = MobileSessionState(); state.selectMode(Mode.FLIGHT)
        val token = state.beginPreparation()
        state.selectDepartureGroundMode(dev.localspoof.domain.GroundLegMode.INSTANT)
        assertFalse(state.acceptsPreparation(token)); state.finishPreparation(token)
        state.draft = state.draft.copy(preparedId = "fixture")
        state.selectArrivalGroundMode(dev.localspoof.domain.GroundLegMode.INSTANT)
        assertNull(state.draft.preparedId)
        state.draft = state.draft.copy(preparedId = "fixture")
        state.selectFlightTransitMode(dev.localspoof.domain.FlightTransitMode.JUMP)
        assertNull(state.draft.preparedId)
    }
    @Test fun speedUnitChangesPreservePhysicalSpeedAndPreparedIdWithoutDrift() {
        val state = MobileSessionState()
        state.selectMode(Mode.DRIVE)
        state.edit(MobileField.SPEED, "60")
        val physical = state.draft.speedMps
        state.draft = state.draft.copy(preparedId = "prepared")
        repeat(100) { MobileSpeedUnit.entries.forEach(state::changeSpeedUnit) }
        assertEquals(26.8224, physical, 1e-10)
        assertEquals(physical, state.draft.speedMps, 0.0)
        assertEquals("prepared", state.draft.preparedId)
    }
    @Test fun invalidSpeedCannotBeRevivedByChangingUnits() {
        val state = MobileSessionState()
        state.selectMode(Mode.WALK)
        state.draft = state.draft.copy(preparedId = "prepared")
        state.edit(MobileField.SPEED, "")
        state.changeSpeedUnit(MobileSpeedUnit.KMH)
        assertNull(state.draft.preparedId)
        assertNotNull(state.draft.speedError)
        assertEquals(MobileSpeedUnit.MPH, state.preferences.speedUnit)
        assertEquals("", state.draft.speedText)
    }
    @Test fun editsAndStopInvalidateLatePreparationReplies() {
        val state = MobileSessionState()
        val token = state.beginPreparation()
        assertTrue(state.acceptsPreparation(token))
        state.edit(MobileField.DESTINATION_LAT, "10")
        assertFalse(state.acceptsPreparation(token))
        assertTrue(state.draft.busy)
        state.finishPreparation(token)
        assertFalse(state.draft.busy)
        val second = state.beginPreparation()
        state.clearActivity()
        assertFalse(state.acceptsPreparation(second))
        assertEquals("", state.draft.destinationLat)
    }
    @Test fun oldCancelledPreparationCannotClearNewOperationBusyFlag() {
        val state = MobileSessionState()
        val old = state.beginPreparation()
        state.cancelPendingPreparation()
        val current = state.beginPreparation()
        state.finishPreparation(old)
        assertTrue(state.draft.busy)
        assertTrue(state.acceptsPreparation(current))
        state.finishPreparation(current)
        assertFalse(state.draft.busy)
    }
    @Test fun matchingInvalidDefaultEditDiscardsPreparedIdAndRejectsUnitSwitch() {
        val state = MobileSessionState()
        state.selectMode(Mode.DRIVE)
        state.draft = state.draft.copy(preparedId = "prepared")
        state.editDefault(Mode.DRIVE, "99999")
        state.changeSpeedUnit(MobileSpeedUnit.MPS)
        assertNull(state.draft.preparedId)
        assertNotNull(state.settingsError)
        assertEquals(MobileSpeedUnit.MPH, state.preferences.speedUnit)
    }
    @Test fun stopRetainsPreferencesButDiscardsJourneyAndRestoresPreset() {
        val state = MobileSessionState()
        state.selectMode(Mode.WALK)
        state.changeSpeedUnit(MobileSpeedUnit.KMH)
        state.editDefault(Mode.WALK, "5.4")
        state.edit(MobileField.SPEED, "7.2")
        state.edit(MobileField.DEPARTURE, "KSFO")
        state.edit(MobileField.DESTINATION_LAT, "12")
        state.draft = state.draft.copy(preparedId = "prepared")
        state.clearActivity()
        assertEquals(MobileSpeedUnit.KMH, state.preferences.speedUnit)
        assertEquals(1.5, state.draft.speedMps, 1e-12)
        assertEquals("5.4", state.draft.speedText)
        assertEquals("", state.draft.departureId)
        assertEquals("", state.draft.destinationLat)
        assertNull(state.draft.preparedId)
        assertFalse(state.draft.busy)
    }
}

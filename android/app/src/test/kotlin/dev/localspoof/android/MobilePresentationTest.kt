package dev.localspoof.android

import dev.localspoof.domain.Mode
import org.junit.Assert.*
import org.junit.Test

class MobilePresentationTest {
    @Test fun narrowAndEnlargedFontFieldsStackWhileWideFieldsShareARow() {
        assertFalse(mobileLayoutPolicy(320f, 480f, 1f).twoColumnFields)
        assertFalse(mobileLayoutPolicy(600f, 800f, 2f).twoColumnFields)
        assertTrue(mobileLayoutPolicy(640f, 360f, 1f).twoColumnFields)
        assertTrue(mobileLayoutPolicy(840f, 640f, 1.5f).twoColumnFields)
        assertFalse(mobileLayoutPolicy(840f, 640f, 2f).twoColumnFields)
    }
    @Test fun shortLandscapeAndKeyboardSizedWindowsUseCompactNavigation() {
        assertTrue(mobileLayoutPolicy(640f, 360f, 1f).compactHeight)
        assertTrue(mobileLayoutPolicy(320f, 180f, 1f).compactHeight)
        assertFalse(mobileLayoutPolicy(360f, 800f, 1f).compactHeight)
    }
    @Test fun unitConversionAndRepeatedDisplaySwitchingPreservePhysicalSpeed() {
        assertEquals(26.8224, mobileParseSpeed("60", MobileSpeedUnit.MPH, Mode.DRIVE), 1e-12)
        assertEquals(10.0, mobileParseSpeed("36", MobileSpeedUnit.KMH, Mode.DRIVE), 1e-12)
        val canonical = mobileParseSpeed("3.1", MobileSpeedUnit.MPH, Mode.WALK)
        repeat(100) { MobileSpeedUnit.entries.forEach { unit ->
            assertEquals(canonical, mobileParseSpeed(mobileDisplaySpeed(canonical, unit).toString(), unit, Mode.WALK), 1e-12)
        } }
    }
    @Test fun modeLimitsHoldInEveryDisplayUnitIncludingRoundedBounds() {
        MobileSpeedUnit.entries.forEach { unit -> listOf(Mode.WALK, Mode.DRIVE).forEach { mode ->
            val max = mobileMaximumSpeed(mode)
            assertEquals(max, mobileParseSpeed(mobileFormatSpeed(max, unit), unit, mode), 1e-6)
            assertEquals(0.1, mobileParseSpeed(mobileFormatSpeed(0.1, unit), unit, mode), 1e-6)
            try { mobileParseSpeed(mobileDisplaySpeed(max + 0.001, unit).toString(), unit, mode); fail("Excess speed accepted") } catch (_: IllegalArgumentException) {}
        } }
        listOf("", "NaN", "Infinity", "-1").forEach { value ->
            try { mobileParseSpeed(value, MobileSpeedUnit.MPH, Mode.WALK); fail("Invalid input accepted") } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun comparisonNumbersPreserveLeadingZerosAndRejectMalformedValues() {
        assertEquals("012 345", mobilePairingComparison("012345"))
        assertEquals("000 042", mobilePairingComparison("000042"))
        assertEquals("999 999", mobilePairingComparison("999999"))
        listOf("", "12345", "1234567", "abcdef", "１２３４５６", "123 456").forEach {
            assertEquals("Pairing number unavailable", mobilePairingComparison(it))
        }
    }
    @Test fun distanceAndDefaultsMatchDesktopUnits() {
        assertEquals("1.0 mi", mobileDistanceLabel(1609.344, MobileDistanceUnit.MI))
        assertEquals("1.0 km", mobileDistanceLabel(1000.0, MobileDistanceUnit.KM))
        assertEquals(MobileSpeedUnit.MPH, MobilePreferences().speedUnit)
        assertEquals(MobileDistanceUnit.MI, MobilePreferences().distanceUnit)
    }
    @Test fun actualEtaNamesPauseAndRadioWaitWithoutInventedArrivalTime() {
        val base = dev.localspoof.domain.Coordinator().snapshot().copy(mode = Mode.DRIVE, state = dev.localspoof.domain.SessionState.ACTIVE, remainingMs = 120000)
        assertTrue(mobileEtaLabel(base).contains("2 min remaining"))
        assertEquals("Paused · ETA resumes with playback", mobileEtaLabel(base.copy(state = dev.localspoof.domain.SessionState.PAUSED)))
        assertEquals("Waiting for airplane mode", mobileEtaLabel(base.copy(waitingFor = "AIRPLANE_MODE", remainingMs = null)))
        assertEquals("Waiting for connectivity restoration", mobileEtaLabel(base.copy(waitingFor = "RESTORE_CONNECTIVITY", remainingMs = null)))
        assertEquals("Arrived · holding location", mobileEtaLabel(base.copy(state = dev.localspoof.domain.SessionState.HOLDING)))
        assertTrue(runCatching { mobileParseSpeed("20", MobileSpeedUnit.MPS, Mode.FLIGHT) }.isFailure)
        assertEquals(230.0, mobileParseSpeed("230", MobileSpeedUnit.MPS, Mode.FLIGHT), 0.0)
    }

}

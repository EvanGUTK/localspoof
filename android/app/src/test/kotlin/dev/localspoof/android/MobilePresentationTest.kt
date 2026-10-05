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


    @Test fun vacationRequiresExplicitStartingFuelAndRangeConversionCannotInventCapacity() {
        val base = MobileVacationDraft(enabled = true, tank = "16", consumption = "20", reserve = "1", starting = "")
        assertTrue(runCatching { mobileVacationConfig(base) }.isFailure)
        val percent = mobileVacationConfig(base.copy(starting = "50"))!!
        assertEquals(8 * 3.785411784, percent.startingFuelLiters, 1e-8)
        val range = mobileVacationConfig(base.copy(startingMethod = MobileFuelInput.RANGE, starting = "120"))!!
        assertEquals(6 * 3.785411784, range.startingFuelLiters, 1e-6)
        assertTrue(runCatching { mobileVacationConfig(base.copy(tank = "5", startingMethod = MobileFuelInput.RANGE, starting = "120")) }.isFailure)
        assertTrue(runCatching { mobileVacationConfig(base.copy(starting = "101")) }.isFailure)
        val metric = mobileVacationConfig(MobileVacationDraft(enabled = true, metric = true, tank = "60", consumption = "8", reserve = "5", startingMethod = MobileFuelInput.RANGE, starting = "200"))!!
        assertEquals(16.0, metric.startingFuelLiters, 0.0)
    }
    @Test fun remainingCustomPlanUsesFreshConfirmedOriginAndKeepsStableStopIdentity() {
        val stop = dev.localspoof.domain.RouteStop(dev.localspoof.domain.GeoPoint(1.05, 2.05), 1000, "Stop")
        val draft = MobileDraft(mode = Mode.DRIVE, originLat = "1", originLon = "2", destinationLat = "1.1", destinationLon = "2.1", customPath = true, stops = listOf(stop))
        val current = dev.localspoof.domain.GeoPoint(1.02, 2.02)
        val request = mobilePlanRequest(draft, current)
        assertEquals(current, request.origin); assertEquals(current, request.customPath!!.first()); assertEquals(stop.id, request.stops.single().id)
    }
    @Test fun vacationPreviewIncludesRealDetoursStartingFuelAndSeparatesRefillCashFromConsumption() {
        val a = dev.localspoof.domain.GeoPoint(0.0, 0.0); val station = dev.localspoof.domain.GeoPoint(0.0, 1.0); val hotel = dev.localspoof.domain.GeoPoint(0.0, 1.5); val destination = dev.localspoof.domain.GeoPoint(0.0, 2.0)
        val points = (0..200).map { dev.localspoof.domain.GeoPoint(0.0, it / 100.0) }
        val config = dev.localspoof.domain.VacationConfig(50.0, 20.0, 2.0, 10.0, dev.localspoof.domain.FuelGrade.REGULAR, fuelPricePerLiter = 1.5)
        val stops = listOf(dev.localspoof.domain.RouteStop(station, 300000, "Fuel", dev.localspoof.domain.StopKind.FUEL, fuel = dev.localspoof.domain.FuelStop(40.0)),
            dev.localspoof.domain.RouteStop(hotel, 60000, "Hotel", dev.localspoof.domain.StopKind.HOTEL, hotel = dev.localspoof.domain.HotelStop(0, 60000, 0, 1, 100.0, 10.0)))
        val request = dev.localspoof.domain.PlanRequest(dev.localspoof.domain.Mode.DRIVE, a, destination, stops = stops, vacation = config, stopPointIndices = listOf(100, 150), onlineRoute = dev.localspoof.domain.OnlineRoute(dev.localspoof.domain.Mode.DRIVE, "Test", points))
        val preview = dev.localspoof.domain.PlanPreview(dev.localspoof.domain.Mode.DRIVE, a, destination, 222390.0, 100000, geometry = points, stops = stops)
        val result = mobileVacationEstimate(request, preview)!!
        assertEquals(22.239, result.consumedLiters, .001); assertEquals(28.8805, result.finishFuelLiters, .001)
        assertEquals(46.67925, result.refillCash!!, .002); assertEquals(33.3585, result.consumedValue!!, .002); assertEquals(110.0, result.hotels!!, 0.0)
        val current = mobileVacationEstimate(request, preview, 15.0)!!
        assertEquals(result.refillCash!! + 7.5, current.refillCash!!, .001)
        val unknownHotel = request.copy(stops = listOf(stops.first(), stops.last().copy(hotel = stops.last().hotel!!.copy(fees = null))))
        assertNull(mobileVacationEstimate(unknownHotel, preview)!!.hotels)
    }

}

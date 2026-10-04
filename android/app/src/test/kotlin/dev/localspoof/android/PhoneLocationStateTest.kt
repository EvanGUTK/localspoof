package dev.localspoof.android

import dev.localspoof.domain.GeoPoint
import dev.localspoof.domain.Sample
import dev.localspoof.platform.DeviceLocationFix
import org.junit.Assert.*
import org.junit.Test

class PhoneLocationStateTest {
    private val realPoint = GeoPoint(1.0, 2.0)
    private val target = GeoPoint(3.0, 4.0)
    private fun fix(time: Long = 1_000_000_000L, accuracy: Double? = 5.0) = DeviceLocationFix(realPoint, accuracy, time)
    @Test fun latestDeviceFixHasAgeAndExpiresWithoutKeepingHistory() {
        val state = PhoneLocationState(); val owner = Any(); state.attach(owner)
        assertTrue(state.device(owner, fix(), 1_000_000_000L))
        assertEquals(PhoneLocation(realPoint, PhoneLocationSource.DEVICE, 5.0, 1_000), state.snapshot(2_000_000_000L))
        assertNotNull(state.snapshot(31_000_000_000L)); assertNull(state.snapshot(31_001_000_000L))
        assertNull(state.snapshot(32_000_000_000L))
    }
    @Test fun futureStaleOutOfOrderAndInvalidAccuracyDeviceFixesCannotReplaceLatest() {
        val state = PhoneLocationState(); val owner = Any(); state.attach(owner)
        assertTrue(state.device(owner, fix(), 2_000_000_000L))
        assertFalse(state.device(owner, fix(3_000_000_000L), 2_000_000_000L))
        assertFalse(state.device(owner, fix(), 32_000_000_000L))
        assertFalse(state.device(owner, fix(999_000_000L), 2_000_000_000L))
        for (accuracy in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 10_001.0)) {
            assertFalse(state.device(owner, fix(1_500_000_000L, accuracy), 2_000_000_000L))
        }
        assertEquals(realPoint, state.snapshot(2_000_000_000L)!!.point)
    }
    @Test fun providerPreparationDiscardsDevicePointAndOnlySubmittedSamplesBecomeSimulation() {
        val state = PhoneLocationState(); val owner = Any(); state.attach(owner)
        state.device(owner, fix(), 1_000_000_000L); state.preparing(owner)
        assertNull(state.snapshot(2_000_000_000L))
        assertFalse(state.device(owner, fix(2_000_000_000L), 2_000_000_000L))
        val planned = Sample(target, elapsedRealtimeMs = 2_000, wallTimeMillis = 0)
        assertNull(state.snapshot(2_000_000_000L)) // Preparing/previewing a plan is not a submission.
        state.submitted(owner, planned, 2_000_000_000L)
        assertEquals(PhoneLocation(target, PhoneLocationSource.SIMULATION, 5.0, 500, 0.0, 0.0, 1), state.snapshot(2_500_000_000L))
        assertNull(state.snapshot(33_000_000_000L))
    }
    @Test fun staleOwnerCannotPublishOrClearASuccessorsDeviceOrSimulationPoint() {
        val state = PhoneLocationState(); val old = Any(); val current = Any()
        state.attach(old); state.device(old, fix(), 1_000_000_000L); state.attach(current)
        assertFalse(state.device(old, fix(2_000_000_000L), 2_000_000_000L))
        assertTrue(state.device(current, fix(2_000_000_000L), 2_000_000_000L))
        state.clear(old); assertNotNull(state.snapshot(2_000_000_000L))
        state.preparing(current); state.submitted(old, Sample(target, elapsedRealtimeMs = 2_000, wallTimeMillis = 0), 2_000_000_000L)
        assertNull(state.snapshot(2_000_000_000L))
        state.submitted(current, Sample(target, elapsedRealtimeMs = 2_000, wallTimeMillis = 0), 2_000_000_000L)
        state.clear(current); assertNull(state.snapshot(2_000_000_000L))
        state.submitted(current, Sample(target, elapsedRealtimeMs = 3_000, wallTimeMillis = 0), 3_000_000_000L)
        assertNull(state.snapshot(3_000_000_000L))
    }
}

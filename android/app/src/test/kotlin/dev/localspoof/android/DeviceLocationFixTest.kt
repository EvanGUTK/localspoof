package dev.localspoof.android

import android.location.Location
import dev.localspoof.platform.validatedDeviceFix
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35])
class DeviceLocationFixTest {
    private fun real() = Location("gps").apply { latitude = 1.0; longitude = 2.0; accuracy = 5f; elapsedRealtimeNanos = 1_000_000_000L }
    @Test fun onlyFreshValidNonmockFrameworkFixesAreAcceptedOnSupportedApis() {
        val location = real()
        assertNotNull(validatedDeviceFix(location, 2_000_000_000L))
        assertNull(validatedDeviceFix(location, 32_000_000_000L))
        assertNull(validatedDeviceFix(location, 999_000_000L))
        location.latitude = Double.NaN; assertNull(validatedDeviceFix(location, 2_000_000_000L))
        location.latitude = 1.0; location.accuracy = 10_001f; assertNull(validatedDeviceFix(location, 2_000_000_000L))
    }
    @Test fun cachedMockFixFromAnyEarlierMockingSessionCannotBecomeDeviceLocation() {
        val location = real()
        val setter = if (android.os.Build.VERSION.SDK_INT >= 31) "setMock" else "setIsFromMockProvider"
        Location::class.java.getDeclaredMethod(setter, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(location, true)
        assertNull(validatedDeviceFix(location, 2_000_000_000L))
    }
}

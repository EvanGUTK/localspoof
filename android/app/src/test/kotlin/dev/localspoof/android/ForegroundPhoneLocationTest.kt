package dev.localspoof.android

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import dev.localspoof.domain.GeoPoint
import dev.localspoof.platform.DeviceLocationFix
import dev.localspoof.platform.FrameworkLocationProvider
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class ForegroundPhoneLocationTest {
    @After fun cleanUp() { RuntimeModel.endUpdateInstall(); RuntimeModel.detach() }

    private fun freshFix() = DeviceLocationFix(GeoPoint(1.25, 2.5), 5.0, SystemClock.elapsedRealtimeNanos().coerceAtLeast(1))
    @Test fun oldForegroundOwnerCannotPublishAfterDetachServiceTakeoverOrUpdateReservation() {
        RuntimeModel.detach()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1))
        val old = Any(); assertTrue(RuntimeModel.attachForegroundLocation(old))
        RuntimeModel.offerForegroundLocation(freshFix(), old); assertNotNull(RuntimeModel.currentPhoneLocation())
        RuntimeModel.detachForegroundLocation(old)
        RuntimeModel.offerForegroundLocation(freshFix(), old); assertNull(RuntimeModel.currentPhoneLocation())
        val current = Any(); assertTrue(RuntimeModel.attachForegroundLocation(current))
        RuntimeModel.detachForegroundLocation(old)
        RuntimeModel.offerForegroundLocation(freshFix(), current); assertNotNull(RuntimeModel.currentPhoneLocation())
        val provider = FrameworkLocationProvider(RuntimeEnvironment.getApplication())
        RuntimeModel.attach(provider)
        RuntimeModel.offerForegroundLocation(freshFix(), current); assertNull(RuntimeModel.currentPhoneLocation())
        assertFalse(RuntimeModel.attachForegroundLocation(Any()))
        RuntimeModel.detach()
        val reserved = Any(); assertTrue(RuntimeModel.attachForegroundLocation(reserved))
        RuntimeModel.withSafeUpdateBoundary { }
        RuntimeModel.offerForegroundLocation(freshFix(), reserved); assertNull(RuntimeModel.currentPhoneLocation())
        assertFalse(RuntimeModel.shouldReadForegroundLocation(reserved))
        assertFalse(RuntimeModel.attachForegroundLocation(Any()))
        assertTrue(RuntimeModel.view.value.updateInstallationPending)
    }

    @Test fun visibleStandaloneAppAcquiresRealPhonePointWithoutStartingServiceOrMocks() {
        RuntimeModel.detach()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = RuntimeEnvironment.getApplication().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadow = shadowOf(manager)
        shadow.setLocationEnabled(true)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val fix = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 1.25; longitude = 2.5; accuracy = 5f
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos().coerceAtLeast(1)
        }
        // Advance the host clock so the genuine cached fix is not in the future.
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1))
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, fix)
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.create().start().resume()
            val phone = RuntimeModel.view.value
            assertEquals("Standalone preview must acquire the phone's genuine fix", GeoPoint(1.25, 2.5), phone.phoneLocation?.point)
            assertEquals(PhoneLocationSource.DEVICE, phone.phoneLocation?.source)
            assertFalse(phone.serviceRunning)
            assertFalse(phone.simulationArmed)
            assertFalse(phone.desktopEnabled)
            assertTrue(RuntimeModel.safeForUpdate())
            assertNull(RuntimeModel.provider)
            controller.pause().stop()
            assertNull("Leaving the visible screen releases its genuine point", RuntimeModel.currentPhoneLocation())
        }
    }

    @Test fun deniedPermissionDisabledLocationAndMockCacheNeverSupplyStandaloneOrigin() {
        RuntimeModel.detach()
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(LocationManager::class.java)
        val shadow = shadowOf(manager)
        shadow.setLocationEnabled(true); shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1))
        val mock = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 1.25; longitude = 2.5; accuracy = 5f; elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        Location::class.java.getDeclaredMethod("setMock", Boolean::class.javaPrimitiveType).invoke(mock, true)
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, mock)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.create().start().resume(); assertNull(RuntimeModel.currentPhoneLocation())
            controller.pause().stop().destroy()
        }
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.create().start().resume(); assertNull(RuntimeModel.currentPhoneLocation())
            assertFalse(RuntimeModel.view.value.serviceRunning)
            controller.pause().stop().destroy()
        }
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadow.setLocationEnabled(false)
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.create().start().resume(); assertNull(RuntimeModel.currentPhoneLocation())
            assertFalse(RuntimeModel.view.value.serviceRunning)
            controller.pause().stop().destroy()
        }
    }
}

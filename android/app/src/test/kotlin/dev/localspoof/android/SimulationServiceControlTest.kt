package dev.localspoof.android

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Exercises the actual stopped service gate without creating providers, sockets or playback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class SimulationServiceControlTest {
    @Test fun lateEnableIntentCannotRearmAnAlreadyStoppedService() {
        RuntimeModel.stop()
        val controller = Robolectric.buildService(SimulationService::class.java)
        val service = controller.get()
        service.stopFromPhone()
        val generation = RuntimeModel.controllerGeneration
        service.onStartCommand(Intent(service, SimulationService::class.java).setAction(SimulationService.ENABLE_DESKTOP), 0, 1)
        assertTrue(service.isStopped())
        assertFalse(RuntimeModel.view.value.desktopEnabled)
        assertEquals(generation, RuntimeModel.controllerGeneration)
        controller.destroy()
    }
    @Test fun playbackWakeLockReleasesForPauseStopAndReadyWithoutDependingOnUsbLease() {
        RuntimeModel.stop()
        val controller=Robolectric.buildService(SimulationService::class.java)
        val service=controller.get()
        val power=service.getSystemService(android.os.PowerManager::class.java)
        val wake=power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"test-playback").apply { setReferenceCounted(false) }
        val field=SimulationService::class.java.getDeclaredField("playbackWakeLock").apply { isAccessible=true };field.set(service,wake)
        val update=SimulationService::class.java.getDeclaredMethod("updatePlaybackWakeLock").apply { isAccessible=true }
        val a=dev.localspoof.domain.GeoPoint(0.0,0.0);val b=dev.localspoof.domain.GeoPoint(0.0,0.1)
        RuntimeModel.coordinator.commit(RuntimeModel.coordinator.prepare(dev.localspoof.domain.PlanRequest(dev.localspoof.domain.Mode.DRIVE,a,b,customPath=listOf(a,b))).id)
        RuntimeModel.publish();update.invoke(service);assertFalse(wake.isHeld)
        RuntimeModel.coordinator.start(0);RuntimeModel.publish();update.invoke(service);assertFalse(wake.isHeld)
        // Model the service's explicit successful phone arming separately from a prepared domain.
        RuntimeModel::class.java.getDeclaredField("phoneArmed").apply { isAccessible=true }.setBoolean(RuntimeModel,true)
        RuntimeModel.publish();update.invoke(service);assertTrue(wake.isHeld)
        // No approved controller is needed to sustain a phone-consented active journey.
        assertNull(RuntimeModel.lease.token());update.invoke(service);assertTrue(wake.isHeld)
        RuntimeModel.pause();update.invoke(service);assertFalse(wake.isHeld)
        RuntimeModel.coordinator.resume(0);RuntimeModel.publish();update.invoke(service);assertTrue(wake.isHeld)
        service.stopFromPhone();assertFalse(wake.isHeld);assertNull(field.get(service))
        controller.destroy()
    }

}

package dev.localspoof.android

import dev.localspoof.domain.*
import dev.localspoof.platform.FrameworkLocationProvider
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RuntimeUpdateSafetyTest {
    @Before fun before() { RuntimeModel.endUpdateInstall();RuntimeModel.detach();RuntimeModel.recordProviderReset(true);RuntimeModel.view.value.pendingUpdateRadioState?.let { RuntimeModel.confirmUpdateRadioRecovery(it) } }
    @After fun after() { before() }
    @Test fun readyPlanCannotInstallAndIdleServiceIsSafeOnlyForWindowsControllerRestart() {
        assertTrue(RuntimeModel.safeForUpdate())
        var committed=false;RuntimeModel.withSafeUpdateBoundary { committed=true };assertTrue(committed)
        assertTrue(RuntimeModel.installBoundaryReady());assertFalse(RuntimeModel.safeForUpdate())
        assertThrows(IllegalStateException::class.java) { RuntimeModel.enableDesktop() }
        RuntimeModel.endUpdateInstall();assertTrue(RuntimeModel.safeForUpdate())
        val adapter=FrameworkLocationProvider(RuntimeEnvironment.getApplication())
        RuntimeModel.attach(adapter)
        assertFalse(RuntimeModel.safeForUpdate())
        assertTrue(RuntimeModel.response().obj("payload")["desktopUpdateSafe"]!!.jsonPrimitive.boolean)
        val prepared=RuntimeModel.prepare(PlanRequest(Mode.INSTANT,GeoPoint(1.0,1.0),GeoPoint(1.0,1.0)))
        RuntimeModel.commit(prepared.id)
        assertFalse(RuntimeModel.response().obj("payload")["desktopUpdateSafe"]!!.jsonPrimitive.boolean)
        assertThrows(IllegalStateException::class.java) { RuntimeModel.withSafeUpdateBoundary { fail("Must not commit") } }
        RuntimeModel.stop();assertFalse(RuntimeModel.safeForUpdate())
        RuntimeModel.detach();assertTrue(RuntimeModel.safeForUpdate())
    }
    @Test fun failedCommitReleasesReservationButSuccessfulCommitRequiresExplicitCompletion() {
        assertThrows(IllegalArgumentException::class.java) { RuntimeModel.withSafeUpdateBoundary { throw IllegalArgumentException("COMMIT_FAILED") } }
        assertTrue(RuntimeModel.safeForUpdate())
        RuntimeModel.withSafeUpdateBoundary { }
        RuntimeModel.stop();assertFalse(RuntimeModel.safeForUpdate());assertTrue(RuntimeModel.installBoundaryReady())
        RuntimeModel.endUpdateInstall();assertTrue(RuntimeModel.safeForUpdate())
    }
    @Test fun recoveredOsInstallBlocksRestartUntilExplicitReconciliation() {
        RuntimeModel.reserveUpdateInstallRecovery()
        assertFalse(RuntimeModel.safeForUpdate());assertTrue(RuntimeModel.view.value.updateInstallationPending)
        assertThrows(IllegalStateException::class.java) { RuntimeModel.enableDesktop() }
        assertThrows(IllegalStateException::class.java) { RuntimeModel.attach(FrameworkLocationProvider(RuntimeEnvironment.getApplication())) }
        assertFalse(RuntimeModel.view.value.serviceRunning)
        RuntimeModel.stop();assertTrue(RuntimeModel.view.value.updateInstallationPending)
        RuntimeModel.endUpdateInstall();assertTrue(RuntimeModel.safeForUpdate())
    }
    @Test fun queuedProviderResetCannotRemoveNewServiceOverrides() {
        RuntimeModel.attach(FrameworkLocationProvider(RuntimeEnvironment.getApplication()))
        var executed=false
        assertThrows(IllegalStateException::class.java) { RuntimeModel.withProviderResetBoundary { executed=true;true } }
        assertFalse(executed)
        RuntimeModel.detach()
        assertTrue(RuntimeModel.withProviderResetBoundary { executed=true;true });assertTrue(executed)
    }
    @Test fun failedProviderResetSurvivesDetachAndRequiresActualSuccessfulReset() {
        RuntimeModel.recordProviderReset(false)
        RuntimeModel.detach();assertFalse(RuntimeModel.safeForUpdate())
        assertFalse(RuntimeModel.response().obj("payload")["desktopUpdateSafe"]!!.jsonPrimitive.boolean)
        RuntimeModel.recordProviderReset(false);assertFalse(RuntimeModel.safeForUpdate())
        RuntimeModel.recordProviderReset(true);assertTrue(RuntimeModel.safeForUpdate())
    }
    @Test fun interruptedFlightRestorationRemainsPendingUntilObservedOriginalState() {
        RuntimeModel.configureResources(newPack=null,newAirports=listOf(Airport("a","A",GeoPoint(1.0,1.01),type="large_airport"),Airport("b","B",GeoPoint(1.0,1.99),type="large_airport")))
        RuntimeModel.observeRadio(false)
        val prepared=RuntimeModel.prepare(PlanRequest(Mode.FLIGHT,GeoPoint(1.0,1.0),GeoPoint(1.0,2.0),flight=FlightOptions(simulationOnly=false,boardingMs=0,departureGroundMode=GroundLegMode.INSTANT,arrivalGroundMode=GroundLegMode.INSTANT)))
        RuntimeModel.commit(prepared.id)
        RuntimeModel.coordinator.start(0)
        for (time in 1L..4L) { RuntimeModel.coordinator.tick(time);RuntimeModel.coordinator.acknowledgePlacement(true) }
        assertEquals(FlightPhase.AWAIT_AIRPLANE_MODE,RuntimeModel.coordinator.snapshot().phase)
        RuntimeModel.observeRadio(true)
        RuntimeModel.coordinator.acknowledgeRadioChange(true)
        RuntimeModel.stop();RuntimeModel.detach()
        assertEquals(false,RuntimeModel.view.value.pendingUpdateRadioState)
        assertFalse(RuntimeModel.safeForUpdate())
        assertFalse(RuntimeModel.confirmUpdateRadioRecovery(true));assertFalse(RuntimeModel.safeForUpdate())
        assertTrue(RuntimeModel.confirmUpdateRadioRecovery(false));assertTrue(RuntimeModel.safeForUpdate())
    }
}

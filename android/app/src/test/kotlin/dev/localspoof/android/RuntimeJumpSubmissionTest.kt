package dev.localspoof.android

import android.Manifest
import android.app.AppOpsManager
import android.location.Location
import android.location.LocationManager
import android.os.Process
import dev.localspoof.domain.*
import dev.localspoof.platform.FrameworkLocationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLocationManager
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** Exercises the same runtime/provider submission boundary as the real service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36], shadows = [RuntimeJumpSubmissionTest.SubmittedLocations::class])
class RuntimeJumpSubmissionTest {
    /** Stock Robolectric does not model test-provider delivery; record adapter calls, not OS receipt. */
    @Implements(LocationManager::class)
    class SubmittedLocations : ShadowLocationManager() {
        val submitted = mutableMapOf<String, Location>()
        @Implementation protected fun setTestProviderLocation(name: String, fix: Location) {
            submitted[name] = Location(fix)
        }
    }
    private val origin = GeoPoint(0.0, 0.0)
    private val departure = GeoPoint(0.0, 0.001)
    private val arrival = GeoPoint(0.0, 0.01)
    private val destination = GeoPoint(0.0, 0.011)
    private lateinit var manager: LocationManager
    private lateinit var provider: FrameworkLocationProvider

    @Before fun prepare() {
        RuntimeModel.endUpdateInstall()
        RuntimeModel.detach()
        RuntimeModel.recordProviderReset(true)
        RuntimeModel.view.value.pendingUpdateRadioState?.let { RuntimeModel.confirmUpdateRadioRecovery(it) }
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        manager = app.getSystemService(LocationManager::class.java)
        shadowOf(manager).setLocationEnabled(true)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(AppOpsManager.OPSTR_MOCK_LOCATION,
            Process.myUid(), app.packageName, AppOpsManager.MODE_ALLOWED)
        provider = FrameworkLocationProvider(app)
        RuntimeModel.configureResources(newPack = null, newAirports = listOf(
            Airport("departure", "Fixture departure", departure, "large_airport"),
            Airport("arrival", "Fixture arrival", arrival, "large_airport")))
        RuntimeModel.attach(provider)
    }

    @After fun cleanUp() {
        RuntimeModel.stop()
        RuntimeModel.detach()
        RuntimeModel.view.value.pendingUpdateRadioState?.let { RuntimeModel.confirmUpdateRadioRecovery(it) }
        RuntimeModel.recordProviderReset(true)
    }

    private fun start(request: PlanRequest) {
        val ready = RuntimeModel.prepare(request)
        RuntimeModel.commit(ready.id)
        RuntimeModel.observeRadio(false)
        RuntimeModel.startFromPhone()
        assertTrue(RuntimeModel.view.value.simulationArmed)
        assertEquals(SessionState.ACTIVE, RuntimeModel.view.value.snapshot.state)
    }

    private fun flight(transit: FlightTransitMode, instantDeparture: Boolean = true, boardingMs: Long = 0) = PlanRequest(
        Mode.FLIGHT, origin, destination, speedMps = 10.0, flightSpeedMps = 230.0,
        flight = FlightOptions(simulationOnly = false, boardingMs = boardingMs,
            originAirportId = "departure", destinationAirportId = "arrival",
            departureGroundMode = if (instantDeparture) GroundLegMode.INSTANT else GroundLegMode.DRIVE,
            arrivalGroundMode = GroundLegMode.INSTANT, flightTransitMode = transit),
        onlineFlightRoutes = if (instantDeparture) null else OnlineFlightRoutes("departure", "arrival",
            OnlineRoute(Mode.DRIVE, "Fixture router", listOf(origin, departure)), null))

    private fun tick(radio: Boolean = false, durationMs: Long = 200) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(durationMs))
        RuntimeModel.tick(radio, provider)
    }

    private fun assertSubmitted(point: GeoPoint) {
        assertEquals(point, RuntimeModel.view.value.phoneLocation?.point)
        assertEquals(PhoneLocationSource.SIMULATION, RuntimeModel.view.value.phoneLocation?.source)
        assertEquals("SAMPLE_SUBMITTED", RuntimeModel.view.value.providerHealth)
        for (name in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val fix = requireNotNull((shadowOf(manager) as SubmittedLocations).submitted[name])
            assertEquals(point.lat, fix.latitude, 0.0)
            assertEquals(point.lon, fix.longitude, 0.0)
            assertTrue(fix.elapsedRealtimeNanos > 0)
        }
    }

    @Test fun airportJumpSubmitsArrivalOnlyAfterObservedAirplaneModeAndRestoresBeforeDestination() {
        start(flight(FlightTransitMode.JUMP, boardingMs = 120_000))
        tick(); assertSubmitted(departure)
        tick()
        assertEquals(FlightPhase.BOARDING, RuntimeModel.view.value.snapshot.phase)
        assertThrows(IllegalArgumentException::class.java) { RuntimeModel.jumpToNextStop() }
        assertSubmitted(departure)
        repeat(24) { tick(durationMs = 5_000) }
        assertEquals(FlightPhase.AWAIT_AIRPLANE_MODE, RuntimeModel.view.value.snapshot.phase)
        tick(); assertSubmitted(departure)
        assertThrows(IllegalArgumentException::class.java) { RuntimeModel.jumpToNextStop() }
        tick(true); tick(true)
        assertSubmitted(arrival)
        assertEquals(FlightPhase.ARRIVAL_PLACEMENT, RuntimeModel.view.value.snapshot.phase)
        assertTrue(RuntimeModel.view.value.phoneLocation!!.placementGeneration > 0)
        tick(true)
        repeat(15) { tick(true) }
        assertEquals(FlightPhase.RESTORE_CONNECTIVITY, RuntimeModel.view.value.snapshot.phase)
        assertSubmitted(arrival)
        assertThrows(IllegalArgumentException::class.java) { RuntimeModel.jumpToNextStop() }
        tick(false); tick(false)
        assertSubmitted(destination)
        tick(false)
        assertEquals(SessionState.HOLDING, RuntimeModel.view.value.snapshot.state)
    }

    @Test fun liveFlightJumpChangesConfirmedFixAfterSubmissionAndRetainsFlightRadioBarriers() {
        start(flight(FlightTransitMode.TIMED, instantDeparture = false))
        tick()
        val initial = requireNotNull(RuntimeModel.view.value.phoneLocation).point
        assertTrue(distanceMeters(origin, initial) < 1.0)
        assertSubmitted(initial)
        val generation = RuntimeModel.view.value.phoneLocation!!.placementGeneration
        RuntimeModel.jumpToNextStop()
        // A requested target does not masquerade as an already submitted location.
        assertSubmitted(initial)
        tick(); assertSubmitted(departure)
        assertTrue(RuntimeModel.view.value.phoneLocation!!.placementGeneration > generation)
        tick(); tick()
        assertEquals(FlightPhase.AWAIT_AIRPLANE_MODE, RuntimeModel.view.value.snapshot.phase)
        assertThrows(IllegalArgumentException::class.java) { RuntimeModel.jumpToNextStop() }
        tick(true); tick(true)
        val inAir = RuntimeModel.view.value.phoneLocation!!.point
        assertNotEquals(departure, inAir)
        assertNotEquals(arrival, inAir)
        val airGeneration = RuntimeModel.view.value.phoneLocation!!.placementGeneration
        RuntimeModel.jumpToNextStop()
        assertSubmitted(inAir)
        tick(true); assertSubmitted(arrival)
        assertTrue(RuntimeModel.view.value.phoneLocation!!.placementGeneration > airGeneration)
        assertEquals(FlightPhase.ARRIVAL_PLACEMENT, RuntimeModel.view.value.snapshot.phase)
        tick(true)
        repeat(15) { tick(true) }
        assertEquals(FlightPhase.RESTORE_CONNECTIVITY, RuntimeModel.view.value.snapshot.phase)
        assertSubmitted(arrival)
    }

    @Test fun liveGroundJumpSubmitsDeclaredStopAndCannotSkipItsWaitOrSurviveStop() {
        start(PlanRequest(Mode.DRIVE, origin, destination, speedMps = 10.0,
            customPath = listOf(origin, departure, destination),
            stops = listOf(RouteStop(departure, 5_000, "Fixture stop"))))
        tick()
        val initial = requireNotNull(RuntimeModel.view.value.phoneLocation).point
        assertTrue(distanceMeters(origin, initial) < 1.0)
        assertSubmitted(initial)
        RuntimeModel.jumpToNextStop()
        assertSubmitted(initial)
        tick(); assertSubmitted(departure)
        assertEquals("STOP_DWELL", RuntimeModel.view.value.snapshot.waitingFor)
        assertThrows(IllegalArgumentException::class.java) { RuntimeModel.jumpToNextStop() }
        tick(); assertSubmitted(departure)
        RuntimeModel.stop()
        assertNull(RuntimeModel.view.value.phoneLocation)
        assertThrows(IllegalStateException::class.java) { RuntimeModel.jumpToNextStop() }
        tick(); assertNull(RuntimeModel.view.value.phoneLocation)
        assertEquals(SessionState.IDLE, RuntimeModel.view.value.snapshot.state)
    }

    @Test fun failedJumpSubmissionClearsLivePointAndCannotClaimOrRetrySuccessfulPlacement() {
        start(PlanRequest(Mode.DRIVE, origin, destination, speedMps = 10.0,
            customPath = listOf(origin, destination)))
        tick()
        assertSubmitted(requireNotNull(RuntimeModel.view.value.phoneLocation).point)
        RuntimeModel.jumpToNextStop()
        shadowOf(manager).setLocationEnabled(false)
        tick()
        assertEquals(SessionState.ERROR, RuntimeModel.view.value.snapshot.state)
        assertEquals("PROVIDER_SUBMISSION_FAILED", RuntimeModel.view.value.snapshot.error)
        assertFalse(RuntimeModel.view.value.simulationArmed)
        assertNull(RuntimeModel.view.value.phoneLocation)
        assertThrows(IllegalStateException::class.java) { RuntimeModel.jumpToNextStop() }
        shadowOf(manager).setLocationEnabled(true)
        tick()
        assertEquals(SessionState.ERROR, RuntimeModel.view.value.snapshot.state)
        assertNull(RuntimeModel.view.value.phoneLocation)
    }
}

package dev.localspoof.android

import android.os.SystemClock
import dev.localspoof.domain.GeoPoint
import dev.localspoof.platform.FrameworkLocationProvider
import dev.localspoof.platform.DeviceLocationFix
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** Calls the actual dispatcher with synthetic channel IDs; never opens a socket or starts playback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class ApprovalPairingServerTest {
    private val channel = "a".repeat(32)
    private val otherChannel = "b".repeat(32)
    private val nonce = "c".repeat(32)
    private lateinit var server: PhoneControlServer
    private var deadline = 0L
    private var nextCommand = 0
    @Before fun setUp() {
        RuntimeModel.stop(); RuntimeModel.enableDesktop()
        server = PhoneControlServer({ false }, {})
        deadline = SystemClock.elapsedRealtime() + 120_000
    }
    @After fun tearDown() { server.close(); RuntimeModel.stop() }
    private fun envelope(type: String, payload: JsonObject = buildJsonObject {}, token: String? = null, sequence: Long = 0, minor: Int = 3) = buildJsonObject {
        put("type", type); put("protocolMajor", 1); put("protocolMinor", minor)
        put("commandId", "fixture-${++nextCommand}"); put("sequence", sequence)
        put("expectedRevision", RuntimeModel.coordinator.snapshot().revision)
        put("sessionId", if (token == null) JsonNull else RuntimeModel.lease.sessionId?.let(::JsonPrimitive) ?: JsonNull)
        put("token", token?.let(::JsonPrimitive) ?: JsonNull); put("payload", payload)
    }
    private fun request() = server.dispatch(envelope("PairRequest", buildJsonObject { put("clientNonce", nonce) }), channel, deadline)
    private fun poll(id: String, selectedChannel: String = channel, suppliedNonce: String = nonce) =
        server.dispatch(envelope("PollPair", buildJsonObject { put("pairingId", id); put("clientNonce", suppliedNonce) }), selectedChannel, deadline)
    private fun approve(): String {
        val id = request().obj("payload").str("pairingId")
        assertTrue(RuntimeModel.approveController(id))
        return poll(id).obj("payload").str("token")
    }
    private fun assertPublicRejection(response: JsonObject, error: String) {
        assertEquals(error, response.str("error"))
        assertEquals(setOf("ok", "error"), response.keys)
    }
    @Test fun helloAdvertisesApprovalPairingWithoutRequiringAnyCopiedSecret() {
        val hello = server.dispatch(envelope("Hello"), channel, deadline).obj("payload")
        assertEquals(6L, hello.long("protocolMinor"))
        for(flag in listOf("motionTelemetry","variableCruise","mappedRoadLimits","remainingJourneyEdits","timedPause","vacationStops","hotelStages")) assertTrue(hello[flag]!!.jsonPrimitive.boolean)
        assertTrue(hello["approvalPairing"]!!.jsonPrimitive.boolean)
        assertTrue(hello["livePhoneLocation"]!!.jsonPrimitive.boolean)
        assertTrue(hello["flightLegOptions"]!!.jsonPrimitive.boolean)
        assertTrue(hello["liveJourneyControls"]!!.jsonPrimitive.boolean)
        assertTrue(hello["routeStops"]!!.jsonPrimitive.boolean)
        RuntimeModel.stop()
        val denied = request()
        assertEquals("PAIRING_REJECTED", denied.str("error"))
        assertTrue(denied.obj("payload")["pairingId"] is JsonNull)
    }
    @Test fun comparisonAndCredentialDeliveryAreBoundToTheRequestChannel() {
        val response = request().obj("payload")
        val id = response.str("pairingId")
        assertEquals(pairingComparison(nonce, id), response.str("comparisonCode"))
        assertEquals(response.str("comparisonCode"), RuntimeModel.view.value.pairingComparison)
        assertEquals(id, RuntimeModel.view.value.pairingRequestId)
        assertEquals("PAIRING_PENDING", poll(id).str("error"))
        assertEquals("PAIRING_REJECTED", poll(id, otherChannel).str("error"))
        assertEquals("PAIRING_REJECTED", poll(id, suppliedNonce = "d".repeat(32)).str("error"))
        assertTrue(RuntimeModel.approveController(id))
        assertNull(RuntimeModel.view.value.pairingComparison)
        assertTrue(RuntimeModel.view.value.controllerApproved)
        assertEquals("PAIRING_REJECTED", poll(id, otherChannel).str("error"))
        val delivered = poll(id)
        assertTrue(delivered["ok"]!!.jsonPrimitive.boolean)
        assertTrue(delivered.obj("payload").str("token").matches(Regex("[a-f0-9]{32}")))
        assertTrue(delivered.str("sessionId").matches(Regex("[a-f0-9]{32}")))
    }
    @Test fun repeatedEnablePreservesGenerationPendingRequestAndApprovedCredential() {
        val id = request().obj("payload").str("pairingId")
        val generation = RuntimeModel.controllerGeneration
        RuntimeModel.enableDesktop()
        assertEquals(generation, RuntimeModel.controllerGeneration)
        assertEquals(id, RuntimeModel.view.value.pairingRequestId)
        assertTrue(RuntimeModel.approveController(id))
        val token = poll(id).obj("payload").str("token")
        RuntimeModel.enableDesktop()
        assertEquals(generation, RuntimeModel.controllerGeneration)
        assertEquals(token, RuntimeModel.lease.token())
        RuntimeModel.disconnect(generation, otherChannel)
        assertEquals(token, RuntimeModel.lease.token())
        RuntimeModel.disconnect(generation, channel)
        assertNull(RuntimeModel.lease.token())
        assertFalse(RuntimeModel.view.value.controllerApproved)
        assertTrue(RuntimeModel.view.value.desktopEnabled)
    }
    @Test fun cachedSnapshotPreparedPlanAndResourceCommandsCannotReplayAcrossSockets() {
        val token = approve()
        val snapshot = envelope("Snapshot", token = token, sequence = 1)
        assertTrue(server.dispatch(snapshot, channel, deadline)["ok"]!!.jsonPrimitive.boolean)
        assertPublicRejection(server.dispatch(snapshot, otherChannel, deadline), "UNAUTHORIZED")
        val point = GeoPoint(1.0, 2.0)
        val prepare = envelope("PreparePlan", buildJsonObject {
            put("mode", "INSTANT"); put("origin", point.json()); put("destination", point.json())
        }, token, 2)
        assertTrue(server.dispatch(prepare, channel, deadline)["ok"]!!.jsonPrimitive.boolean)
        assertPublicRejection(server.dispatch(prepare, otherChannel, deadline), "UNAUTHORIZED")
        val resource = envelope("ImportGeoPack", buildJsonObject { put("pack", buildJsonObject {}) }, token, 3)
        assertPublicRejection(server.dispatch(resource, otherChannel, deadline), "UNAUTHORIZED")
    }
    @Test fun latePairRequestCannotExtendTheAcceptedChannelDeadline() {
        ShadowSystemClock.advanceBy(Duration.ofMillis(119_500))
        val id = request().obj("payload").str("pairingId")
        ShadowSystemClock.advanceBy(Duration.ofMillis(500))
        assertFalse(RuntimeModel.approveController(id))
        assertEquals("PAIRING_REJECTED", poll(id).str("error"))
        assertNull(RuntimeModel.lease.token())
    }
    @Test fun legacySecretRequestsAndMalformedOrUppercaseNoncesAreRejected() {
        val legacy = envelope("PairRequest", buildJsonObject { put("secret", "f".repeat(32)) })
        assertThrows(IllegalArgumentException::class.java) { server.dispatch(legacy, channel, deadline) }
        val uppercase = envelope("PairRequest", buildJsonObject { put("clientNonce", "C".repeat(32)) })
        assertThrows(IllegalArgumentException::class.java) { server.dispatch(uppercase, channel, deadline) }
        val extra = envelope("PairRequest", buildJsonObject { put("clientNonce", nonce); put("secret", "f".repeat(32)) })
        assertThrows(IllegalArgumentException::class.java) { server.dispatch(extra, channel, deadline) }
        assertPublicRejection(server.dispatch(envelope("PairRequest", buildJsonObject { put("clientNonce", nonce) }, minor = 1), channel, deadline), "UNSUPPORTED_PROTOCOL")
        assertFalse(RuntimeModel.view.value.pairingPending)
    }
    @Test fun deviceLocationExistsOnlyInApprovedSnapshotAndCommittedPreviewCannotReplaceIt() {
        val owner = FrameworkLocationProvider(RuntimeEnvironment.getApplication())
        RuntimeModel.attach(owner)
        val real = GeoPoint(1.0, 2.0)
        RuntimeModel.offerDeviceLocation(DeviceLocationFix(real, 12.0, SystemClock.elapsedRealtimeNanos()), owner)
        assertFalse(server.dispatch(envelope("Hello"), channel, deadline).toString().contains("phoneLocation"))
        assertPublicRejection(server.dispatch(envelope("Snapshot"), channel, deadline), "UNAUTHORIZED")
        val token = approve()
        val initial = server.dispatch(envelope("Snapshot", token = token, sequence = 1), channel, deadline).obj("payload").obj("phoneLocation")
        assertEquals(real.json(), initial.obj("point")); assertEquals("DEVICE", initial.str("source"))
        assertEquals(12.0, initial.number("accuracyMeters"), 0.0)
        assertFalse(server.dispatch(envelope("Heartbeat", token = token, sequence = 2), channel, deadline).toString().contains("phoneLocation"))
        val target = GeoPoint(3.0, 4.0)
        val prepared = server.dispatch(envelope("PreparePlan", buildJsonObject {
            put("mode", "INSTANT"); put("origin", target.json()); put("destination", target.json())
        }, token, 3), channel, deadline).obj("payload").str("preparedId")
        server.dispatch(envelope("CommitPlan", buildJsonObject { put("preparedId", prepared) }, token, 4), channel, deadline)
        val committed = server.dispatch(envelope("Snapshot", token = token, sequence = 5), channel, deadline)
        assertEquals(target.json(), committed.obj("point"))
        assertEquals(real.json(), committed.obj("payload").obj("phoneLocation").obj("point"))
        assertPublicRejection(server.dispatch(envelope("Snapshot", token = token, sequence = 6), otherChannel, deadline), "UNAUTHORIZED")
        RuntimeModel.stop(); assertNull(RuntimeModel.view.value.phoneLocation)
    }
    @Test fun snapshotRetryReadsLatestSinglePointAndAgeWithoutRenewingApproval() {
        val owner = FrameworkLocationProvider(RuntimeEnvironment.getApplication())
        RuntimeModel.attach(owner)
        RuntimeModel.offerDeviceLocation(DeviceLocationFix(GeoPoint(1.0, 2.0), 12.0, SystemClock.elapsedRealtimeNanos()), owner)
        val token = approve()
        val snapshot = envelope("Snapshot", token = token, sequence = 1)
        val initial = server.dispatch(snapshot, channel, deadline).obj("payload").obj("phoneLocation")
        assertEquals(GeoPoint(1.0, 2.0).json(), initial.obj("point"))
        ShadowSystemClock.advanceBy(Duration.ofMillis(100))
        val latest = GeoPoint(1.5, 2.5)
        RuntimeModel.offerDeviceLocation(DeviceLocationFix(latest, 8.0, SystemClock.elapsedRealtimeNanos()), owner)
        ShadowSystemClock.advanceBy(Duration.ofMillis(200))
        val retried = server.dispatch(snapshot, channel, deadline).obj("payload").obj("phoneLocation")
        assertEquals(latest.json(), retried.obj("point"))
        assertEquals(8.0, retried.number("accuracyMeters"), 0.0)
        assertEquals(200L, retried.long("ageMs"))
        assertPublicRejection(server.dispatch(snapshot, otherChannel, deadline), "UNAUTHORIZED")
        // Matching reads neither consume new sequences nor extend the original six-second approval.
        ShadowSystemClock.advanceBy(Duration.ofMillis(5_701))
        assertPublicRejection(server.dispatch(snapshot, channel, deadline), "UNAUTHORIZED")
    }    @Test fun speedMutationsRequireCurrentRevisionChannelAndNeverReplayMovementOrRenewLease() {
        val token = approve()
        val p = RuntimeModel.prepare(dev.localspoof.domain.PlanRequest(dev.localspoof.domain.Mode.DRIVE, GeoPoint(1.0,1.0), GeoPoint(1.0,1.002), customPath = listOf(GeoPoint(1.0,1.0),GeoPoint(1.0,1.002))))
        RuntimeModel.commit(p.id)
        val update = envelope("SetSpeeds", buildJsonObject { put("driveMps", 20.0) }, token, 1, minor = 4)
        assertPublicRejection(server.dispatch(update, otherChannel, deadline), "UNAUTHORIZED")
        assertTrue(server.dispatch(update, channel, deadline)["ok"]!!.jsonPrimitive.boolean)
        val updated = RuntimeModel.coordinator.snapshot()
        assertEquals(20.0, updated.speeds.driveMps, 0.0)
        assertTrue(server.dispatch(update, channel, deadline)["ok"]!!.jsonPrimitive.boolean)
        assertEquals(updated.revision, RuntimeModel.coordinator.snapshot().revision)
        val stale = envelope("SetSpeeds", buildJsonObject { put("driveMps", 15.0) }, token, 2, minor = 4)
        val outdated = JsonObject(stale + ("expectedRevision" to JsonPrimitive(updated.revision-1)))
        assertEquals("STALE_REVISION", server.dispatch(outdated, channel, deadline).str("error"))
        assertEquals(20.0, RuntimeModel.coordinator.snapshot().speeds.driveMps, 0.0)
        ShadowSystemClock.advanceBy(Duration.ofMillis(6_001))
        assertPublicRejection(server.dispatch(update, channel, deadline), "UNAUTHORIZED")
    }
    @Test fun invalidSpeedsAndUnauthenticatedSkipCannotAlterJourney() {
        assertPublicRejection(server.dispatch(envelope("JumpToNextStop", minor=4),channel,deadline),"UNAUTHORIZED")
        val token=approve()
        val p=RuntimeModel.prepare(dev.localspoof.domain.PlanRequest(dev.localspoof.domain.Mode.INSTANT,GeoPoint(1.0,1.0),GeoPoint(1.0,1.0)))
        RuntimeModel.commit(p.id)
        for ((index,payload) in listOf(buildJsonObject { put("driveMps",56.1) },buildJsonObject { put("walkMps",3.1) },buildJsonObject { put("flightMps",351.0) },buildJsonObject { put("driveMps",20.0);put("point",GeoPoint(2.0,2.0).json()) }).withIndex()) {
            val rejected = envelope("SetSpeeds",payload,token,index+1L,minor=4)
            assertEquals("COMMAND_REJECTED",server.dispatch(rejected,channel,deadline).str("error"))
            assertEquals("COMMAND_REJECTED",server.dispatch(rejected,channel,deadline).str("error"))
        }
        assertEquals(13.9,RuntimeModel.coordinator.snapshot().speeds.driveMps,0.0)
    }

    @Test fun minor5DrivingControlsAreAtomicAndNeverRenewAuthorityByRetry() {
        val token=approve()
        val p=RuntimeModel.prepare(dev.localspoof.domain.PlanRequest(dev.localspoof.domain.Mode.DRIVE,GeoPoint(1.0,1.0),GeoPoint(1.0,1.1),customPath=listOf(GeoPoint(1.0,1.0),GeoPoint(1.0,1.1))))
        RuntimeModel.commit(p.id)
        val controls=buildJsonObject { put("drivingSpeedMode","VARIABLE");put("driveMinMps",40.2336);put("driveMaxMps",55.88);put("followRoadLimits",false) }
        assertEquals("COMMAND_REJECTED",server.dispatch(envelope("SetSpeeds",controls,token,1,minor=4),channel,deadline).str("error"))
        val update=envelope("SetSpeeds",controls,token,2,minor=5)
        assertTrue(server.dispatch(update,channel,deadline)["ok"]!!.jsonPrimitive.boolean)
        val after=RuntimeModel.coordinator.snapshot()
        assertEquals(dev.localspoof.domain.DrivingSpeedMode.VARIABLE,after.speedControls.mode)
        assertEquals(55.88,after.speedControls.driveMaxMps,0.0)
        assertFalse(after.speedControls.followRoadLimits)
        assertTrue(server.dispatch(update,channel,deadline)["ok"]!!.jsonPrimitive.boolean)
        assertEquals(after.revision,RuntimeModel.coordinator.snapshot().revision)
        assertEquals("COMMAND_REJECTED",server.dispatch(envelope("SetSpeeds",buildJsonObject { put("driveMinMps",55.0);put("driveMaxMps",40.0) },token,3,minor=5),channel,deadline).str("error"))
        assertEquals(after,RuntimeModel.coordinator.snapshot())
        ShadowSystemClock.advanceBy(Duration.ofMillis(6_001))
        assertPublicRejection(server.dispatch(update,channel,deadline),"UNAUTHORIZED")
    }

    @Test fun socketFailureRevokesOnlyItsOwnControllerAndCannotDisableSuccessorOrPausePhoneJourney() {
        approve()
        val oldGeneration=RuntimeModel.controllerGeneration
        val a=GeoPoint(1.0,1.0);val b=GeoPoint(1.0,1.1)
        RuntimeModel.coordinator.commit(RuntimeModel.coordinator.prepare(dev.localspoof.domain.PlanRequest(dev.localspoof.domain.Mode.DRIVE,a,b,customPath=listOf(a,b))).id)
        RuntimeModel.coordinator.start(0);val before=RuntimeModel.coordinator.snapshot()
        RuntimeModel.desktopUnavailable(oldGeneration)
        assertFalse(RuntimeModel.view.value.desktopEnabled);assertNull(RuntimeModel.lease.token())
        assertEquals(before,RuntimeModel.coordinator.snapshot())
        RuntimeModel.enableDesktop();val newGeneration=RuntimeModel.controllerGeneration
        assertTrue(newGeneration>oldGeneration);assertNull(RuntimeModel.view.value.message)
        RuntimeModel.desktopUnavailable(oldGeneration)
        assertTrue(RuntimeModel.view.value.desktopEnabled);assertEquals(newGeneration,RuntimeModel.controllerGeneration)
        assertEquals(before,RuntimeModel.coordinator.snapshot())
        assertNull(RuntimeModel.lease.token()) // A recovered socket never manufactures approval.
    }

    @Test fun remainingEditCommandsNeverDiscloseActivityBeforeAuthorization() {
        for(type in listOf("BeginRemainingEdit","PrepareRemainingPlan","ApplyRemainingPlan","CancelRemainingEdit","TimedPause","LeaveStop")) {
            assertPublicRejection(server.dispatch(envelope(type,minor=6),channel,deadline),"UNAUTHORIZED")
        }
    }
    @Test fun remainingCapabilitiesAreMinorGatedAndSnapshotKeepsApprovedGeometry() {
        val token=approve()
        val request=dev.localspoof.domain.PlanRequest(dev.localspoof.domain.Mode.DRIVE,GeoPoint(1.0,1.0),GeoPoint(1.0,1.001),customPath=listOf(GeoPoint(1.0,1.0),GeoPoint(1.0,1.001)))
        RuntimeModel.commit(RuntimeModel.prepare(request).id)
        val active=RuntimeModel.view.value.snapshot.activePreview
        RuntimeModel.prepare(request.copy(destination=GeoPoint(1.0,1.002),customPath=listOf(request.origin,GeoPoint(1.0,1.002))))
        assertEquals(active,RuntimeModel.view.value.snapshot.activePreview)
        val read=server.dispatch(envelope("Snapshot",token=token,sequence=1,minor=6),channel,deadline).obj("payload")
        assertNotNull(read["activePreview"]);assertNotNull(read["activeChoices"])
        assertEquals("COMMAND_REJECTED",server.dispatch(envelope("BeginRemainingEdit",token=token,sequence=2,minor=5),channel,deadline).str("error"))
        assertEquals("COMMAND_REJECTED",server.dispatch(envelope("TimedPause",buildJsonObject{put("durationSeconds",60)},token,3,minor=5),channel,deadline).str("error"))
    }

}

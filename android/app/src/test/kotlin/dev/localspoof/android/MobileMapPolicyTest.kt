package dev.localspoof.android

import android.net.Uri
import android.webkit.WebSettings
import androidx.test.core.app.ApplicationProvider
import dev.localspoof.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 36])
@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
class MobileMapPolicyTest {
    @Test fun viewportMessagesAcceptOnlyDisplayCoordinatesAndExactQuery() {
        assertEquals(MapViewport(GeoPoint(41.8781, -87.6298), 12.0), MobileMapView.parseViewport(Uri.parse("https://localspoof.invalid/viewport?lat=41.8781&lon=-87.6298&zoom=12")))
        listOf("https://evil.test/viewport?lat=1&lon=2&zoom=12", "https://localspoof.invalid:443/viewport?lat=1&lon=2&zoom=12", "https://localspoof.invalid/%76iewport?lat=1&lon=2&zoom=12", "https://localspoof.invalid/viewport?lat=1&lon=2&zoom=NaN", "https://localspoof.invalid/viewport?lat=1&lon=2&zoom=25", "https://localspoof.invalid/viewport?lat=91&lon=2&zoom=12", "https://localspoof.invalid/viewport?lat=1&lat=2&lon=2&zoom=12", "https://localspoof.invalid/viewport?lat=1&lon=2&zoom=12&start=true").forEach { assertNull(MobileMapView.parseViewport(Uri.parse(it))) }
        assertNull("Viewport reporting cannot select a destination", MobileMapView.parseSelection(Uri.parse("https://localspoof.invalid/viewport?lat=1&lon=2&zoom=12")))
    }
    @Test fun nearbyResultsRequireMatchingCategoryFinitePointAndActualRadius() {
        val center = GeoPoint(41.8781, -87.6298)
        fun feature(key: String, value: String, lon: String = "-87.63", lat: String = "41.88") = """{"geometry":{"type":"Point","coordinates":[$lon,$lat]},"properties":{"name":"Synthetic place","osm_key":"$key","osm_value":"$value"}}"""
        val mixed = "{\"features\":[" + listOf(feature("tourism", "hotel"), feature("amenity", "restaurant"), feature("tourism", "hotel", "2", "1"), feature("tourism", "hotel", "-87.63", "999"), feature("tourism", "hotel", "\"-87.63\""), feature("tourism", "camp_site")).joinToString(",") + "]}"
        val good = MobileMapNetwork.decodeNearby(mixed.toByteArray(), center, PlaceCategory.HOTEL)
        assertEquals(1, good.size); assertTrue(good.single().distanceMeters in 0.0..10000.0)
        val shops = "{\"features\":[" + listOf(feature("shop", "vacant"), feature("shop", ""), feature("shop", "supermarket")).joinToString(",") + "]}"
        assertEquals(1, MobileMapNetwork.decodeNearby(shops.toByteArray(), center, PlaceCategory.SHOPS).size)
        assertTrue(runCatching { MobileMapNetwork.decodeNearby(("{\"features\":[" + List(9) { feature("tourism", "hotel") }.joinToString(",") + "]}").toByteArray(), center, PlaceCategory.HOTEL) }.isFailure)
        assertTrue(runCatching { MobileMapNetwork.decodeNearby(ByteArray(262145), center, PlaceCategory.HOTEL) }.isFailure)
    }
    @Test fun categoryRequestsStayOnFixedReverseEndpointWithBoundedFilters() {
        PlaceCategory.entries.forEach { category ->
            val url = Uri.parse(MobileMapNetwork.nearbyAddress(GeoPoint(1.0, 2.0), category))
            assertEquals("https", url.scheme); assertEquals("photon.komoot.io", url.host); assertEquals("/reverse", url.path)
            assertEquals("10", url.getQueryParameter("radius")); assertEquals("8", url.getQueryParameter("limit")); assertEquals(category.tags, url.getQueryParameters("osm_tag"))
        }
    }
    @Test fun rendererStartsOnlyAfterAttachmentAndNonzeroLayout() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().visible().get()
        val view = MobileMapView(activity, MobileMapNetwork()) {}
        val shadow = org.robolectric.Shadows.shadowOf(view)
        assertNull("Constructing an unattached view cannot start the renderer", shadow.lastLoadedUrl)
        view.layout(0, 0, 320, 640)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull("Layout without attachment cannot start the renderer", shadow.lastLoadedUrl)
        activity.setContentView(view, android.view.ViewGroup.LayoutParams(320, 640))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(320, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(640, android.view.View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 320, 640)
        assertTrue("Visible activity attaches the renderer before loading", view.isAttachedToWindow)
        // Robolectric's fake WebView provider does not apply setFrame. Supply the
        // platform frame before dispatching its real native size callback.
        org.robolectric.util.ReflectionHelpers.setField(view, "mRight", 320)
        org.robolectric.util.ReflectionHelpers.setField(view, "mBottom", 640)
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(view, "onSizeChanged",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 320),
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 640),
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 0),
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 0))
        assertTrue("Attached renderer has a nonzero measured viewport", view.width > 0 && view.height > 0)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).runOneTask()
        assertEquals("https://localspoof.invalid/mobile-map/index.html", shadow.lastLoadedUrl)
        view.disposeMap()
    }
    @Test fun fixedProviderPathsRejectTraversalInvalidTileAndHostFragments() {
        listOf("/planet", "/planet/latest", "/planet/20260927_080001_pt/14/8000/5000.pbf", "/sprites/ofm_f384/ofm@2x.png", "/fonts/Noto%20Sans%20Regular/0-255.pbf", "/natural_earth/ne2sr/2/1/3.png").forEach { assertTrue(MobileMapNetwork.mapLimit(it) > 0) }
        listOf("https://evil.test/planet", "/planet?x=1", "/planet#x", "/planet/../secret", "/fonts/x%2fsecret/0-255.pbf", "/planet/latest/15/0/0.pbf", "/planet/latest/2/4/0.pbf", "/fonts/x/1-256.pbf", "/anything").forEach { assertTrue("Rejected $it", runCatching { MobileMapNetwork.mapLimit(it) }.isFailure) }
    }
    @Test fun rendererHasNoFileContentDiskStorageOrDefaultNetworkAccess() {
        val network = MobileMapNetwork()
        val view = MobileMapView(ApplicationProvider.getApplicationContext(), network) {}
        assertTrue(view.settings.javaScriptEnabled)
        assertFalse(view.settings.domStorageEnabled)
        assertFalse(view.settings.allowFileAccess)
        assertFalse(view.settings.allowContentAccess)
        assertTrue(view.settings.blockNetworkLoads)
        assertEquals(WebSettings.LOAD_NO_CACHE, view.settings.cacheMode)
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, view.settings.mixedContentMode)
        assertFalse(view.isSaveEnabled); assertFalse(view.isSaveFromParentEnabled)
        view.disposeMap()
        assertTrue(runCatching { network.map("/planet") }.isFailure)
    }
    @Test fun selectionOnlyAcceptsBoundedExactAppOriginCoordinates() {
        assertEquals(GeoPoint(1.0, 2.0), MobileMapView.parseSelection(Uri.parse("https://localspoof.invalid/select?lat=1&lon=2")))
        listOf("https://evil.test/select?lat=1&lon=2", "http://localspoof.invalid/select?lat=1&lon=2", "https://localspoof.invalid:443/select?lat=1&lon=2", "https://user@localspoof.invalid/select?lat=1&lon=2", "https://localspoof.invalid/select?lat=NaN&lon=2", "https://localspoof.invalid/select?lat=91&lon=2", "https://localspoof.invalid/select?lat=1&lon=2&command=Stop", "https://localspoof.invalid/select?lat=1&lat=2&lon=2").forEach { assertNull(MobileMapView.parseSelection(Uri.parse(it))) }
    }
    @Test fun searchUsesStructuredFinitePointsAndBoundedResults() {
        val good = """{"features":[{"geometry":{"type":"Point","coordinates":[2,1]},"properties":{"name":"Synthetic place","city":"Test city"}},{"geometry":{"type":"Point","coordinates":[2,999]},"properties":{"name":"Bad point"}}]}"""
        val results = MobileMapNetwork.decodeSearch(good.toByteArray())
        assertEquals(listOf(MobilePlace("Synthetic place, Test city", GeoPoint(1.0, 2.0))), results)
        val many = "{\"features\":[" + List(9) { "{}" }.joinToString(",") + "]}"
        assertTrue(runCatching { MobileMapNetwork.decodeSearch(many.toByteArray()) }.isFailure)
        assertTrue(runCatching { MobileMapNetwork.decodeSearch(ByteArray(262145)) }.isFailure)
    }
    @Test fun currentDotUsesSubmittedPhoneFixAndClearsStaleLocation() {
        val coordinator = Coordinator()
        val start = GeoPoint(1.0, 2.0); val destination = GeoPoint(1.01, 2.01)
        val preview = coordinator.prepare(PlanRequest(Mode.DRIVE, start, destination, customPath = listOf(start, destination))).preview
        val fresh = PhoneView(coordinator.snapshot(), preview = preview, phoneLocation = PhoneLocation(start, PhoneLocationSource.DEVICE, 5.0, 100))
        val state = Json.parseToJsonElement(mobileMapState(fresh, MobileDraft())).jsonObject
        assertEquals(1.0, state.getValue("current").jsonObject.getValue("lat").jsonPrimitive.double, 0.0)
        val stale = Json.parseToJsonElement(mobileMapState(fresh.copy(phoneLocation = fresh.phoneLocation!!.copy(ageMs = 30001)), MobileDraft())).jsonObject
        assertFalse(stale.containsKey("current"))
        val cleared = Json.parseToJsonElement(mobileMapState(PhoneView(coordinator.stop()), MobileDraft())).jsonObject
        assertEquals(0, cleared.getValue("route").jsonArray.size); assertFalse(cleared.containsKey("current"))
    }
    @Test fun rendererBurstsWaitForBoundedSlotsInsteadOfDroppingTiles() {
        val gate = MobileMapGate(activeLimit = 2, waitingLimit = 8, waitMillis = 2000)
        repeat(2) { gate.acquire { false } }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        val started = java.util.concurrent.CountDownLatch(6)
        val tasks = (1..6).map { pool.submit<Boolean> { started.countDown(); gate.acquire { false }; try { true } finally { gate.release() } } }
        assertTrue(started.await(1, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue("Burst requests queue while every active slot is occupied", tasks.none { it.isDone })
        repeat(2) { gate.release() }
        tasks.forEach { assertTrue(it.get(2, java.util.concurrent.TimeUnit.SECONDS)) }
        pool.shutdownNow()
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val single = MobileMapGate(activeLimit = 1, waitingLimit = 1, waitMillis = 1000)
        single.acquire { false }
        val waiter = java.util.concurrent.Executors.newSingleThreadExecutor()
        val cancelled = waiter.submit<Boolean> { runCatching { single.acquire { closed.get() } }.isFailure }
        closed.set(true)
        assertTrue(cancelled.get(1, java.util.concurrent.TimeUnit.SECONDS))
        single.release(); waiter.shutdownNow()
    }

    @Test fun motionPayloadUsesActualSubmittedMetadataAndNamesStaleness() {
        val coordinator = Coordinator()
        val phone = PhoneView(coordinator.snapshot().copy(state = SessionState.ACTIVE, mode = Mode.DRIVE),
            phoneLocation = PhoneLocation(GeoPoint(1.0, 2.0), PhoneLocationSource.SIMULATION, 5.0, 1700, speedMps = 10.0, sampleSequence = 42, placementGeneration = 7),
            motion = MotionTelemetry(speedMps = null, sampleAgeMs = 1700, sampleSequence = 42, health = "STALE"))
        val payload = Json.parseToJsonElement(mobileMapState(phone, MobileDraft(speedMps = 55.88))).jsonObject
        assertEquals(10.0, payload.getValue("speedMps").jsonPrimitive.double, 0.0)
        assertEquals(42, payload.getValue("sequence").jsonPrimitive.int)
        assertEquals("STALE", payload.getValue("health").jsonPrimitive.content)
        assertEquals(1700, payload.getValue("ageMs").jsonPrimitive.int)
        assertTrue(payload.getValue("owner").jsonPrimitive.content.endsWith(":7"))
        assertFalse(payload.getValue("follow").jsonPrimitive.boolean)
    }


    @Test fun activeGeometrySurvivesDraftSelectionAndCandidatePreviewWhilePlaying() {
        val coordinator = Coordinator()
        val start = GeoPoint(1.0, 2.0); val destination = GeoPoint(1.01, 2.01); val next = GeoPoint(1.02, 2.02)
        val active = coordinator.prepare(PlanRequest(Mode.DRIVE, start, destination, customPath = listOf(start, destination))).preview
        val candidate = coordinator.prepare(PlanRequest(Mode.DRIVE, start, next, customPath = listOf(start, next))).preview
        val phone = PhoneView(coordinator.snapshot().copy(state = SessionState.ACTIVE, activePreview = active), preview = candidate)
        val payload = Json.parseToJsonElement(mobileMapState(phone, MobileDraft(destinationLat = next.lat.toString(), destinationLon = next.lon.toString(), preparedId = "candidate"))).jsonObject
        assertEquals(destination.lon, payload.getValue("destination").jsonObject.getValue("lon").jsonPrimitive.double, 0.0)
        assertEquals(next.lon, payload.getValue("candidateDestination").jsonObject.getValue("lon").jsonPrimitive.double, 0.0)
        assertEquals(destination.lon, payload.getValue("route").jsonArray.last().jsonObject.getValue("lon").jsonPrimitive.double, 0.0)
        assertEquals(1, payload.getValue("candidateSegments").jsonArray.size)
        assertTrue(MobileMapView.isGesture(Uri.parse("https://localspoof.invalid/gesture")))
        assertFalse(MobileMapView.isGesture(Uri.parse("https://localspoof.invalid/gesture?Stop=true")))
    }
    @Test fun nearbyPinsAreSeparateBoundedNativeBatchMessages() {
        val batch = "11111111-1111-1111-1111-111111111111"
        val good = "https://localspoof.invalid/poi?batch=$batch&index=0"
        assertEquals(NearbyPinClick(batch, 0), MobileMapView.parseNearbyPin(Uri.parse(good)))
        assertNull(MobileMapView.parseSelection(Uri.parse(good)))
        listOf(good.replace("https://localspoof.invalid", "https://evil.test"), good.replace("/poi", "/%70oi"), good + "&lat=1", good + "&index=1", good + "#x", good.replace("index=0", "index=8"), good.replace("index=0", "index=-1"), good.replace("index=0", "index=NaN"), good.replace(batch, "old-batch")).forEach { assertNull(MobileMapView.parseNearbyPin(Uri.parse(it))) }
    }
    @Test fun nativePoiBridgeRejectsOldBatchesHiddenMapAndClearedResults() {
        val accepted = mutableListOf<NearbyPinClick>()
        var destinationCalls = 0
        val view = MobileMapView(ApplicationProvider.getApplicationContext(), MobileMapNetwork(), poi = accepted::add) { destinationCalls++ }
        val place = NearbyPlace("Synthetic hotel", GeoPoint(1.0, 2.0), PlaceCategory.HOTEL, 10.0)
        val first = "11111111-1111-1111-1111-111111111111"
        val second = "22222222-2222-2222-2222-222222222222"
        fun click(batch: String, index: Int = 0) {
            val request = object : android.webkit.WebResourceRequest {
                override fun getUrl() = Uri.parse("https://localspoof.invalid/poi?batch=$batch&index=$index")
                override fun isForMainFrame() = true
                override fun isRedirect() = false
                override fun hasGesture() = true
                override fun getMethod() = "GET"
                override fun getRequestHeaders() = emptyMap<String, String>()
            }
            view.webViewClient.shouldOverrideUrlLoading(view, request)
        }
        view.updatePlaces(listOf(place), first); click(first)
        view.updatePlaces(listOf(place), second); click(first); click(second, 7)
        view.setMapVisible(false); click(second)
        view.setMapVisible(true); click(second)
        view.updatePlaces(emptyList(), first); click(first)
        assertEquals(listOf(NearbyPinClick(first, 0), NearbyPinClick(second, 0)), accepted)
        assertEquals("Browsing cannot invoke generic map selection", 0, destinationCalls)
        view.disposeMap(); click(second); assertEquals(2, accepted.size)
    }
    @Test fun detailOwnershipExpiresWhenNearbyBatchChanges() {
        val overlay = MobileMapOverlayState()
        val place = NearbyPlace("Synthetic hotel", GeoPoint(1.0, 2.0), PlaceCategory.HOTEL, 10.0)
        overlay.replaceNearby(listOf(place)); val first = NearbyPinClick(overlay.placesBatch, 0)
        overlay.browse(first); assertEquals(place, overlay.selectedDetail())
        overlay.replaceNearby(emptyList()); assertNull(overlay.selectedDetail())
        overlay.replaceNearby(listOf(place)); overlay.browse(first); assertNull(overlay.selectedDetail())
        overlay.browse(NearbyPinClick(overlay.placesBatch, 7)); assertNull(overlay.selectedDetail())
        overlay.browse(place); assertEquals(place, overlay.selectedDetail())
        overlay.dismissDetail(); assertNull(overlay.selectedDetail()); assertEquals(listOf(place), overlay.nearby)
    }

    @Test fun jumpPresentationRequiresActualPhonePlaybackAndNoHeldEdit() {
        val snapshot = Coordinator().snapshot().copy(state = SessionState.ACTIVE, mode = Mode.DRIVE, nextStop = JourneyStopTarget(GeoPoint(1.0, 2.0), "Synthetic stop", 0))
        val healthy = MobileUiState(PhoneView(snapshot, simulationArmed = true, serviceRunning = true))
        assertNull(mobileJumpBlockReason(healthy))
        assertTrue(mobileJumpBlockReason(healthy.copy(phone = healthy.phone.copy(serviceRunning = false)))!!.contains("Phone playback"))
        assertTrue(mobileJumpBlockReason(healthy.copy(phone = healthy.phone.copy(simulationArmed = false)))!!.contains("Phone playback"))
        assertTrue(mobileJumpBlockReason(healthy.copy(phone = healthy.phone.copy(snapshot = snapshot.copy(editId = "edit"))))!!.contains("Apply or cancel"))
        assertTrue(mobileJumpBlockReason(healthy.copy(phone = healthy.phone.copy(snapshot = snapshot.copy(state = SessionState.PAUSED))))!!.contains("Resume"))
        assertNotNull(mobileJumpBlockReason(healthy.copy(phone = healthy.phone.copy(snapshot = snapshot.copy(nextStop = null)))))
        FlightPhase.entries.forEach { phase ->
            val reason = mobileJumpBlockReason(healthy.copy(phone = healthy.phone.copy(snapshot = snapshot.copy(mode = Mode.FLIGHT, phase = phase))))
            assertEquals(phase.name, phase !in setOf(FlightPhase.ORIGIN_GROUND, FlightPhase.FLIGHT_INTERVAL, FlightPhase.DESTINATION_GROUND), reason != null)
        }
    }

    @Test fun freshJumpFailureExplainsActualBoardingHoldWithoutUsingStaleUi() {
        val active = Coordinator().snapshot().copy(state = SessionState.ACTIVE, mode = Mode.FLIGHT, phase = FlightPhase.FLIGHT_INTERVAL, nextStop = JourneyStopTarget(GeoPoint(1.0, 2.0), "Arrival airport", 0))
        val oldUi = MobileUiState(PhoneView(active, simulationArmed = true, serviceRunning = true))
        assertNull(mobileJumpBlockReason(oldUi))
        val fresh = oldUi.phone.copy(snapshot = active.copy(phase = FlightPhase.BOARDING, stages = listOf(JourneyStage("Departure airport", "DRIVE", "ACTIVE", 120000))))
        val reason = mobileJumpBlockReason(fresh)
        assertTrue(reason!!.contains("120 s remaining")); assertTrue(reason.contains("boarding")); assertFalse(reason.contains("Resume"))
        assertTrue(mobileJumpBlockReason(fresh, editing = true)!!.contains("Apply or cancel"))
        assertFalse(mobileJumpBlockReason(fresh.copy(snapshot = fresh.snapshot.copy(stages = emptyList())))!!.contains("s remaining"))
    }

}

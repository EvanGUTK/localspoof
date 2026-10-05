package dev.localspoof.android

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.localspoof.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

/** Real Compose measurement with native text layout; all values and callbacks are synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalTestApi::class)
class MobileRenderedLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private data class Viewport(val width: Int, val height: Int, val font: Float)
    private val matrix = listOf(320 to 480, 360 to 800, 640 to 360, 840 to 640).flatMap { (width, height) ->
        listOf(1f, 1.5f, 2f, 2.5f).map { Viewport(width, height, it) }
    }
    private var density = 1f
    private var measuredViewport = matrix.first()

    private fun phone(): PhoneView {
        val coordinator = Coordinator()
        val point = GeoPoint(1.0, 2.0)
        coordinator.commit(coordinator.prepare(PlanRequest(Mode.INSTANT, point, point)).id)
        return PhoneView(coordinator.snapshot(), desktopEnabled = true, pairingPending = true,
            pairingComparison = "012345", pairingRequestId = "a".repeat(32), providerHealth = "NOT_PREPARED")
    }
    private fun mount(section: MobileSection, initial: Viewport = matrix.first(), fixture: PhoneView = phone(), draft: MobileDraft = MobileDraft(mode = Mode.DRIVE, originLat = "1", originLon = "2", destinationLat = "1.002", destinationLon = "2.003"), update: MobileUpdateState = MobileUpdateState(), events: (MobileUiEvent) -> Unit = {}): MutableState<Viewport> {
        val viewport = mutableStateOf(initial)
        compose.setContent {
            val value = viewport.value
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(value.width.dp, value.height.dp)) then DeviceConfigurationOverride.FontScale(value.font)) {
                val localDensity = LocalDensity.current.density
                SideEffect { density = localDensity; measuredViewport = value }
                key(value) {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        MobileApp(MobileUiState(phone = fixture, section = section,
                            draft = draft, updateState = update, installedVersion = "0.8.0"), events, Modifier.fillMaxSize())
                    }
                }
            }
        }
        return viewport
    }
    private fun rawBounds(tag: String): Rect {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val origin = node.positionInRoot
        return Rect(origin.x, origin.y, origin.x + node.size.width, origin.y + node.size.height)
    }
    private fun horizontallyInsideRoot(tag: String) {
        val root = rawBounds("mobile-root")
        val bounds = rawBounds(tag)
        assertTrue("$tag must have a measured positive width", bounds.width > 0)
        assertTrue("$tag must not extend beyond the viewport's left edge", bounds.left >= root.left - 1f)
        assertTrue("$tag must not extend beyond the viewport's right edge", bounds.right <= root.right + 1f)
    }
    private fun visibleAction(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
        horizontallyInsideRoot(tag)
        val bounds = rawBounds(tag)
        assertTrue("$tag needs at least a 48 dp touch width", bounds.width / density >= 47.5f)
        assertTrue("$tag needs at least a 48 dp touch height", bounds.height / density >= 47.5f)
        val root = rawBounds("mobile-root")
        assertTrue("$tag must remain vertically reachable", bounds.top >= root.top - 1f && bounds.bottom <= root.bottom + 1f)
    }
    private fun scrollTo(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        horizontallyInsideRoot(tag)
    }
    private fun assertMeasuredViewport(value: Viewport) {
        val root = rawBounds("mobile-root")
        assertEquals(value.width.toFloat(), root.width / density, 1f)
        assertEquals(value.height.toFloat(), root.height / density, 1f)
        visibleAction("phone-stop")
        val compact = compose.onAllNodesWithTag("mobile-section-selector", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        if (compact) visibleAction("mobile-section-selector") else {
            visibleAction("mobile-section-selector")
        }
        val stop = rawBounds("phone-stop")
        val content = rawBounds("mobile-content")
        assertTrue("The persistent Stop action must not overlap scrollable content", content.bottom <= stop.top + 1f)
        assertTrue("Each configuration retains a useful scrollable area", content.height / density >= 48f)
    }
    private fun assertTextNotClipped(tag: String) {
        val results = mutableListOf<TextLayoutResult>()
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        compose.runOnIdle { node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results) }
        assertTrue("$tag must expose native text layout for an actual clipping check", results.isNotEmpty())
        results.forEach { result ->
            assertFalse("$tag at $measuredViewport must wrap without visual overflow; width=${result.didOverflowWidth}, height=${result.didOverflowHeight}, size=${result.size}, lines=${result.lineCount}, paragraphHeight=${result.multiParagraph.height}, bounds=${rawBounds(tag)}", result.hasVisualOverflow)
        }
    }
    private fun revealComparison() {
        // The comparison remains readable at enlarged text sizes; vertical scrolling is
        // intentional when its surrounding approval card exceeds the viewport. Check actual visible intersection and horizontal/text layout separately.
        compose.onNodeWithTag("pairing-comparison", useUnmergedTree = true).performScrollTo()
        val visible = compose.onNodeWithTag("pairing-comparison", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("The fixture comparison number must intersect the scroll viewport", visible.width > 0 && visible.height > 0)
        horizontallyInsideRoot("pairing-comparison")
        assertTextNotClipped("pairing-comparison")
    }
    private fun nativeInputFontDp(): Float {
        var size = 0f
        compose.runOnIdle {
            val inputs = mutableListOf<EditText>()
            fun collect(view: View) {
                if (view is EditText) inputs.add(view)
                if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
            }
            collect(compose.activity.window.decorView)
            assertTrue("Journey must render actual native text inputs", inputs.isNotEmpty())
            inputs.forEach { input ->
                assertTrue("Native inputs retain a 48 dp touch width", input.width / density >= 47.5f)
                assertTrue("Native inputs retain a 56 dp touch height at $measuredViewport; measured=${input.height / density}dp", input.height / density >= 55.5f)
                assertFalse(input.isSaveEnabled)
                assertFalse(input.isSaveFromParentEnabled)
                assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, input.importantForAutofill)
                if (Build.VERSION.SDK_INT >= 30) {
                    assertEquals(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS, input.importantForContentCapture)
                }
                assertTrue(input.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
            }
            size = inputs.first().textSize / density
        }
        return size
    }

    @Test fun connectControlsAndPairingComparisonRemainReachableAcrossViewportAndFontMatrix() {
        val viewport = mount(MobileSection.CONNECT)
        matrix.forEach { value ->
            compose.runOnIdle { viewport.value = value }
            compose.waitForIdle(); assertMeasuredViewport(value)
            if (compose.onAllNodesWithTag("phone-start-approved", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
                visibleAction("phone-start-approved")
            } else {
                scrollTo("phone-start-approved-content"); visibleAction("phone-start-approved-content")
            }
            scrollTo("enable-desktop"); visibleAction("enable-desktop")
            revealComparison()
            scrollTo("pairing-approve"); visibleAction("pairing-approve")
            scrollTo("pairing-reject"); visibleAction("pairing-reject")
        }
    }
    @Test fun journeyInputsAndPreviewRemainReachableAcrossViewportAndFontMatrix() {
        val viewport = mount(MobileSection.JOURNEY)
        compose.waitForIdle()
        val baselineFontDp = nativeInputFontDp()
        matrix.forEach { value ->
            compose.runOnIdle { viewport.value = value }
            compose.waitForIdle(); assertMeasuredViewport(value)
            listOf("origin-lat", "origin-lon", "destination-lat", "destination-lon", "speed-input", "mobile-preview").forEach(::scrollTo)
            visibleAction("mobile-preview")
            assertEquals("Native fields must respond to the same font-scale override as Compose", value.font, nativeInputFontDp() / baselineFontDp, 0.04f)
        }
    }
    @Test fun settingsInputsAndRecoveryRemainReachableAcrossViewportAndFontMatrix() {
        val viewport = mount(MobileSection.SETTINGS)
        matrix.forEach { value ->
            compose.runOnIdle { viewport.value = value }
            compose.waitForIdle(); assertMeasuredViewport(value)
            listOf("settings-walk-default", "settings-drive-default", "settings-reset", "reset-providers").forEach(::scrollTo)
            visibleAction("reset-providers")
        }
    }
    @Test fun actualCoordinateFieldsReflowFromColumnsToStackedLargeText() {
        val viewport = mount(MobileSection.JOURNEY, Viewport(840, 640, 1f))
        scrollTo("destination-lat")
        val latitude = rawBounds("destination-lat")
        val longitude = rawBounds("destination-lon")
        assertEquals("Tablet fields share a measured row", latitude.top, longitude.top, 1f)
        assertTrue("Tablet fields do not overlap", latitude.right <= longitude.left + 1f)
        compose.runOnIdle { viewport.value = Viewport(320, 480, 2.5f) }
        compose.waitForIdle(); scrollTo("destination-lat")
        val stackedLatitude = rawBounds("destination-lat")
        val stackedLongitude = rawBounds("destination-lon")
        assertTrue("Narrow large-text fields become separate measured rows", stackedLatitude.bottom < stackedLongitude.top)
        horizontallyInsideRoot("destination-lat"); horizontallyInsideRoot("destination-lon")
    }
    @Test @Config(sdk = [36]) fun api36LargeTextCompactConnectRetainsEmergencyStop() {
        mount(MobileSection.CONNECT, Viewport(320, 480, 2.5f))
        compose.waitForIdle(); assertMeasuredViewport(Viewport(320, 480, 2.5f))
        revealComparison()
        scrollTo("pairing-approve"); visibleAction("pairing-approve")
    }
    @Test @Config(sdk = [29]) fun api29NativeInputsKeepAvailablePrivacyAndLayoutGuards() {
        assertEquals(29, Build.VERSION.SDK_INT)
        val value = Viewport(360, 800, 1f)
        mount(MobileSection.JOURNEY, value)
        compose.waitForIdle(); assertMeasuredViewport(value)
        assertTrue(nativeInputFontDp() > 0f)
        scrollTo("origin-lat"); scrollTo("mobile-preview"); visibleAction("mobile-preview")
    }
    @Test fun screenSelectionAndStopEmitOnlyControlledFixtureEvents() {
        val events = mutableListOf<MobileUiEvent>()
        mount(MobileSection.CONNECT, Viewport(320, 480, 2.5f)) { events.add(it) }
        compose.onNodeWithTag("mobile-section-selector").performClick()
        compose.onNodeWithTag("nav-settings-menu").performClick()
        compose.onNodeWithTag("phone-stop").performClick()
        assertEquals(listOf(MobileUiEvent.Section(MobileSection.SETTINGS), MobileUiEvent.Stop), events)
    }
    @Test fun nativeInputRenderingDoesNotEmitEditsButExplicitFixtureInputDoes() {
        val events = mutableListOf<MobileUiEvent>()
        val viewport = mount(MobileSection.JOURNEY, Viewport(360, 800, 1f)) { events.add(it) }
        compose.waitForIdle()
        compose.runOnIdle { viewport.value = Viewport(320, 480, 2f) }
        compose.waitForIdle()
        assertTrue("Initial native values and density/font changes are presentation only", events.isEmpty())
        compose.runOnIdle {
            fun firstInput(view: View): EditText? {
                if (view is EditText) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) {
                    firstInput(view.getChildAt(index))?.let { return it }
                }
                return null
            }
            requireNotNull(firstInput(compose.activity.window.decorView)).setText("3")
        }
        assertEquals(listOf(MobileUiEvent.Edit(MobileField.ORIGIN_LAT, "3")), events)
    }
    @Test fun wideWindowUsesTheActualCappedContentWidthForLargeTextReflow() {
        val value = Viewport(1280, 800, 2f)
        mount(MobileSection.JOURNEY, value)
        compose.waitForIdle(); assertMeasuredViewport(value)
        listOf("mobile-content", "phone-stop", "mobile-header").forEach { tag ->
            val bounds = rawBounds(tag)
            assertTrue("$tag remains capped at 840 dp in a wide window", bounds.width / density <= 841f)
            assertEquals("$tag remains horizontally centered", rawBounds("mobile-root").center.x, bounds.center.x, 1f)
        }
        scrollTo("destination-lat")
        assertTrue("Large text uses the capped inner width and stacks coordinates", rawBounds("destination-lat").bottom < rawBounds("destination-lon").top)
    }
    @Test fun optionallyExportSyntheticRenderedFixturesForVisualReview() {
        val viewport = mount(MobileSection.JOURNEY, Viewport(320, 480, 2.5f))
        val fixtures = listOf(
            "portrait-320-font-2.5.png" to Viewport(320, 480, 2.5f),
            "landscape-640-font-2.png" to Viewport(640, 360, 2f),
            "tablet-840-font-1.png" to Viewport(840, 640, 1f),
        )
        fixtures.forEach { (name, value) ->
            compose.runOnIdle { viewport.value = value }
            compose.waitForIdle(); assertMeasuredViewport(value)
            scrollTo("origin-lat")
            // Capture support varies by graphics host. Measurement assertions above remain
            // mandatory; optional PNG export uses only this pure fixture activity and values.
            runCatching {
                val bitmap = compose.onNodeWithTag("mobile-root", useUnmergedTree = true).captureToImage().asAndroidBitmap()
                val directory = File("build/mobile-ui-qa").apply { mkdirs() }
                File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
    @Test fun phoneApprovalAndDenialCarryTheDisplayedRequestIdentity() {
        val events = mutableListOf<MobileUiEvent>()
        mount(MobileSection.CONNECT, Viewport(320, 480, 2.5f)) { events.add(it) }
        scrollTo("pairing-approve")
        compose.onNodeWithTag("pairing-approve").performClick()
        scrollTo("pairing-reject")
        compose.onNodeWithTag("pairing-reject").performClick()
        assertEquals(listOf(MobileUiEvent.Approve("a".repeat(32)), MobileUiEvent.Reject("a".repeat(32))), events)
    }
    @Test fun malformedComparisonCannotBeApproved() {
        val events = mutableListOf<MobileUiEvent>()
        mount(MobileSection.CONNECT, fixture = phone().copy(pairingComparison = "12345")) { events.add(it) }
        scrollTo("pairing-approve")
        compose.onNodeWithTag("pairing-approve").assertIsNotEnabled()
        assertTrue(events.isEmpty())
    }

    @Test fun flightLegControlsRemainReachableAtLargeTextAndEmitOnlyExplicitChanges() {
        val events = mutableListOf<MobileUiEvent>()
        val viewport = mount(MobileSection.JOURNEY, Viewport(320, 480, 2.5f), draft = MobileDraft(mode = Mode.FLIGHT)) { events.add(it) }
        assertTrue("Rendering must not modify the trip", events.isEmpty())
        listOf(Viewport(320, 480, 2.5f), Viewport(640, 360, 2.5f)).forEach { value ->
            compose.runOnIdle { viewport.value = value }; compose.waitForIdle(); assertMeasuredViewport(value)
            listOf("departure-instant", "arrival-instant", "flight-jump").forEach { tag ->
                scrollTo(tag); visibleAction(tag); assertTextNotClipped("$tag-label")
                compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
            }
        }
        assertEquals(listOf(MobileUiEvent.DepartureGroundMode(GroundLegMode.INSTANT),
            MobileUiEvent.ArrivalGroundMode(GroundLegMode.INSTANT),
            MobileUiEvent.FlightTransitMode(dev.localspoof.domain.FlightTransitMode.JUMP)).let { it + it }, events)
        visibleAction("phone-stop")
    }

    @Test fun mapHomeShowsGearBookmarksModesAndStopWithoutStartingAcrossPhoneSizes() {
        val events = mutableListOf<MobileUiEvent>()
        val viewport = mount(MobileSection.MAP, Viewport(390, 844, 1f), fixture = PhoneView(Coordinator().snapshot()), draft = MobileDraft()) { events.add(it) }
        listOf(Viewport(390, 844, 1f), Viewport(320, 480, 1f), Viewport(320, 480, 2.5f), Viewport(640, 360, 2f)).forEach { value ->
            compose.runOnIdle { viewport.value = value }; compose.waitForIdle()
            assertMeasuredViewport(value)
            visibleAction("mobile-bookmarks"); visibleAction("mobile-section-selector"); visibleAction("phone-stop")
            val map = rawBounds("phone-live-map")
            assertTrue("The live map has a useful surface", map.height / density >= 100f)
            if (mobileLayoutPolicy(value.width.toFloat(), value.height.toFloat(), value.font).compactHeight || value.font >= 1.8f) scrollTo("mobile-teleport")
            compose.onNodeWithTag("mobile-teleport").assertIsDisplayed()
            horizontallyInsideRoot("world-place-search")
            assertTrue("Mounting or resizing cannot start or mutate the journey", events.isEmpty())
        }
    }
    @Test fun phoneLiveJumpShowsNamedConfirmationAndOnlyExplicitConfirmEmitsAction() {
        val events = mutableListOf<MobileUiEvent>()
        val base = Coordinator().snapshot().copy(state = SessionState.ACTIVE, mode = Mode.DRIVE, remainingMs = 120000, remainingMeters = 1000.0, nextStop = JourneyStopTarget(GeoPoint(1.0, 2.0), "Cafe", 0))
        mount(MobileSection.JOURNEY, Viewport(360, 800, 1f), fixture = PhoneView(base)) { events.add(it) }
        scrollTo("phone-jump-next"); compose.onNodeWithTag("phone-jump-next").performClick()
        compose.onNodeWithText("Jump to Cafe?").assertIsDisplayed()
        assertTrue(events.isEmpty())
        compose.onNodeWithText("Jump", useUnmergedTree = true).performClick()
        assertEquals(listOf(MobileUiEvent.JumpNextStop), events)
        visibleAction("phone-stop")
    }
    @Test fun settingsGearOpensConnectionAndSettingsAndBookmarkWithoutSessionMutation() {
        val events = mutableListOf<MobileUiEvent>()
        mount(MobileSection.MAP, Viewport(390, 844, 1f), fixture = PhoneView(Coordinator().snapshot()), draft = MobileDraft()) { events.add(it) }
        compose.onNodeWithTag("mobile-section-selector").performClick()
        compose.onNodeWithTag("nav-connect-menu").performClick()
        assertEquals(listOf(MobileUiEvent.Section(MobileSection.CONNECT)), events)
        compose.onNodeWithTag("mobile-bookmarks").performClick()
        compose.onNodeWithText("Saved places and routes").assertIsDisplayed()
        assertEquals(1, events.size)
    }

    @Test fun settingsIconContainsVisibleMintPixelsAtNormalAndEnlargedFontScales() {
        val viewport = mutableStateOf(1f)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(viewport.value)) {
                MaterialTheme(colorScheme = darkColorScheme()) { MobileControlIcon("settings") }
            }
        }
        listOf(1f, 2.5f).forEach { scale ->
            compose.runOnIdle { viewport.value = scale }; compose.waitForIdle()
            val node = compose.onNodeWithTag("mobile-icon-settings").fetchSemanticsNode()
            assertEquals(24, node.size.width)
            // Robolectric does not deliver hardware PixelCopy callbacks. Rasterize the
            // production draw function into a real software Canvas while retaining
            // the measured Compose icon-size checks above.
            val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
            androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
                androidx.compose.ui.unit.Density(1f, scale), androidx.compose.ui.unit.LayoutDirection.Ltr,
                androidx.compose.ui.graphics.Canvas(bitmap.asImageBitmap()), androidx.compose.ui.geometry.Size(24f, 24f)
            ) { drawMobileControlIcon("settings") }
            val image = bitmap.asImageBitmap().toPixelMap()
            var colored = 0
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val color = image[x, y]
                if (color.alpha > 0.5f && color.green > 0.5f && color.green > color.red * 1.5f) colored++
            }
            assertTrue("Settings vector must rasterize visible mint pixels independently of OEM fonts", colored >= 30)
            assertEquals("Settings icon retains physical dimensions under font scaling", 24, image.width)
        }
    }

    @Test fun categoryFiltersAndMapKeyStayReachableWithoutStartingTravel() {
        val events = mutableListOf<MobileUiEvent>()
        val viewport = mount(MobileSection.MAP, Viewport(320, 800, 1f), PhoneView(Coordinator().snapshot()), MobileDraft(), events = events::add)
        listOf(1f, 2.5f).forEach { font ->
            compose.runOnIdle { viewport.value = Viewport(320, 800, font) }; compose.waitForIdle()
            scrollTo("category-hotel"); compose.onNodeWithTag("category-hotel").performClick()
            compose.onNodeWithTag("place-search-message").assertTextContains("Zoom in on a city", substring = true)
            compose.onNodeWithTag("category-more").assertIsDisplayed().performClick()
            compose.onNodeWithTag("category-health").assertIsDisplayed().performClick()
            if (font >= 1.8f) scrollTo("map-key-toggle")
            compose.onNodeWithTag("map-key-toggle").assertIsDisplayed().performClick()
            compose.onNodeWithTag("map-key-dialog").assertExists()
            compose.onNodeWithTag("map-key-close").performClick()
            compose.onNodeWithTag("phone-stop").assertIsDisplayed()
        }
        assertTrue("Category discovery and legend cannot emit travel or provider actions", events.isEmpty())
    }
    @Test fun numberedNearbyResultsStageTheChosenRoleAndNeverStartTravel() {
        val role = mutableStateOf("Destination")
        val events = mutableListOf<MobileUiEvent>()
        var cleared = 0
        val point = GeoPoint(41.88, -87.63)
        val state = MobileUiState(PhoneView(Coordinator().snapshot()), draft = MobileDraft(mode = Mode.DRIVE))
        compose.setContent { MaterialTheme { NearbyResults(state, listOf(NearbyPlace("Synthetic hotel", point, PlaceCategory.HOTEL, 250.0)), role.value, events::add) { cleared++ } } }
        listOf("Origin", "Destination", "Stop").forEach { chosen ->
            compose.runOnIdle { role.value = chosen }; compose.waitForIdle()
            compose.onNodeWithTag("nearby-result-0").assertIsDisplayed().performClick()
        }
        assertEquals(listOf(MobileUiEvent.OriginSelected(point, "Synthetic hotel"), MobileUiEvent.PlaceSelected(point, "Synthetic hotel"), MobileUiEvent.AddStop(point, "Synthetic hotel", 0)), events)
        assertEquals(3, cleared)
    }
    @Test fun normalMapKeepsAtLeastSixtyPercentClearWithCollapsedNearbyResults() {
        val overlay = MobileMapOverlayState()
        val ui = mutableStateOf(MobileUiState(PhoneView(Coordinator().snapshot())))
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(384.dp, 628.dp))) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    MapHome(ui.value, mobileLayoutPolicy(384f, 628f, 1f), {}, Modifier.fillMaxSize(), overlay)
                }
            }
        }
        listOf(SessionState.IDLE, SessionState.READY, SessionState.ACTIVE, SessionState.HOLDING).forEach { phase ->
            listOf(false, true).forEach { results ->
                compose.runOnIdle { ui.value = ui.value.copy(phone = PhoneView(Coordinator().snapshot().copy(state = phase, mode = if (phase == SessionState.IDLE) null else Mode.DRIVE, remainingMs = 600000L), motion = MotionTelemetry(speedMps = 55.88, sampleAgeMs = 200, sampleSequence = 1, health = "LIVE"))) }
                compose.waitForIdle()
                compose.runOnIdle { overlay.nearby = if (results) List(8) { NearbyPlace("Synthetic hotel $it", GeoPoint(41.88, -87.63), PlaceCategory.HOTEL, 250.0) } else emptyList() }
                compose.waitForIdle()
                val map = rawBounds("phone-live-map"); val top = rawBounds("mobile-map-top-overlay"); val bottom = rawBounds("mobile-map-bottom-overlay"); val key = rawBounds("map-key-toggle")
                assertTrue("Top and bottom controls cannot overlap", top.bottom <= bottom.top)
                val clearArea = (bottom.top - top.bottom) * map.width - key.width * key.height
                assertTrue("$phase/results=$results leaves at least60% of real map viewport clear", clearArea / (map.width * map.height) >= 0.60f)
                compose.onNodeWithTag("mobile-map-attribution").assertIsDisplayed()
                if (phase in setOf(SessionState.ACTIVE, SessionState.HOLDING)) compose.onNodeWithTag("phone-pause").assertIsDisplayed()
                if (results) { compose.onNodeWithTag("show-nearby-results").assertIsDisplayed(); compose.onNodeWithTag("nearby-results").assertDoesNotExist() }
            }
        }
    }
    @Test fun nearbySheetDismissesAndKeepsStopOutsideTheScrollingList() {
        val overlay = MobileMapOverlayState()
        val events = mutableListOf<MobileUiEvent>()
        compose.setContent { MaterialTheme { MapHome(MobileUiState(PhoneView(Coordinator().snapshot())), mobileLayoutPolicy(384f, 628f, 1f), events::add, Modifier.fillMaxSize(), overlay) } }
        compose.waitForIdle()
        compose.runOnIdle { overlay.nearby = List(8) { NearbyPlace("Synthetic hotel $it", GeoPoint(41.88, -87.63), PlaceCategory.HOTEL, 250.0) } }
        compose.onNodeWithTag("show-nearby-results").performClick()
        compose.onNodeWithTag("nearby-sheet-stop").assertIsDisplayed()
        compose.onNodeWithTag("nearby-sheet-close").performClick()
        compose.onNodeWithTag("nearby-results").assertDoesNotExist()
        compose.onNodeWithTag("show-nearby-results").assertIsDisplayed()
        assertTrue("Opening and closing results cannot emit any controller action", events.isEmpty())
        compose.onNodeWithTag("show-nearby-results").performClick()
        compose.onNodeWithTag("nearby-sheet-stop").performClick()
        compose.onNodeWithTag("nearby-results").assertDoesNotExist()
        assertEquals("Only the explicit modal Stop emits a controller action", listOf(MobileUiEvent.Stop), events)
    }

    @Test fun motionReadoutAndDetailsNeverApplyCruiseMerelyByOpening() {
        val events = mutableListOf<MobileUiEvent>()
        val snapshot = Coordinator().snapshot().copy(state = SessionState.ACTIVE, mode = Mode.DRIVE, remainingMs = 60000)
        mount(MobileSection.MAP, Viewport(384, 800, 1f), PhoneView(snapshot, motion = MotionTelemetry(speedMps = 55.88, sampleAgeMs = 1600, health = "STALE")), events = events::add)
        compose.onNodeWithTag("phone-speed-gauge").assertTextContains("Sim — mph", substring = true)
        compose.onNodeWithTag("journey-details").performClick()
        compose.onNodeWithTag("journey-sheet").assertExists()
        compose.onNodeWithTag("phone-speed-details").assertTextContains("Simulated speed: — mph")
        compose.onNodeWithTag("journey-sheet-close").performClick()
        assertTrue("Viewing a gauge/details cannot change engine speed or consent", events.isEmpty())
        visibleAction("phone-stop")
    }

    @Test fun unchangedLivePublicationExpiresOnNativeMonotonicClock() {
        val snapshot = Coordinator().snapshot().copy(state = SessionState.ACTIVE, mode = Mode.DRIVE, remainingMs = 60000)
        mount(MobileSection.MAP, Viewport(384, 800, 1f), PhoneView(snapshot, motion = MotionTelemetry(speedMps = 55.88, sampleAgeMs = 0, health = "LIVE")))
        compose.onNodeWithTag("phone-speed-gauge").assertTextContains("Sim 125 mph", substring = true)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1800))
        compose.mainClock.advanceTimeBy(2000)
        org.robolectric.shadows.ShadowLooper.idleMainLooper(250, java.util.concurrent.TimeUnit.MILLISECONDS)
        compose.waitForIdle()
        compose.onNodeWithTag("phone-speed-gauge").assertTextContains("Sim — mph", substring = true)
        compose.onNodeWithTag("phone-speed-gauge").assertTextContains("Stale", substring = true)
    }


    @Test fun mapSettingsReturnRetainsOneRendererAndSelectedVisibleFollowWithoutPlaybackCommands() {
        var section by mutableStateOf(MobileSection.MAP)
        val events = mutableListOf<MobileUiEvent>()
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 800.dp))) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    MobileApp(MobileUiState(phone(), section = section), { event -> events.add(event); if (event is MobileUiEvent.Section) section = event.section })
                }
            }
        }
        fun renderer(): MobileMapView {
            val found = mutableListOf<MobileMapView>()
            fun collect(view: View) { if (view is MobileMapView) found.add(view); if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i)) }
            collect(compose.activity.window.decorView); assertEquals(1, found.size); return found.single()
        }
        compose.waitForIdle()
        val first = renderer()
        visibleAction("phone-follow"); compose.onNodeWithTag("phone-follow").performClick().assertIsSelected()
        repeat(3) {
            compose.onNodeWithTag("mobile-section-selector").performClick(); compose.onNodeWithTag("nav-settings-menu").performClick(); compose.waitForIdle()
            assertSame(first, renderer()); assertEquals(View.INVISIBLE, first.visibility)
            compose.onNodeWithTag("nav-map").performClick(); compose.waitForIdle()
            assertSame(first, renderer()); assertEquals(View.VISIBLE, first.visibility)
            compose.onNodeWithTag("phone-follow").assertIsSelected(); visibleAction("phone-fit-route")
        }
        compose.onNodeWithTag("map-key-toggle").performClick()
        compose.onNodeWithTag("phone-map-retry").performClick(); compose.waitForIdle()
        assertNotSame("Explicit map retry replaces only the renderer", first, renderer())
        compose.onNodeWithTag("phone-follow").assertIsSelected()
        assertTrue(events.all { it is MobileUiEvent.Section })
    }
    @Test fun updateControlsDeferThroughPausedJourneyAndSupportOwnedAndroidConfirmation() {
        val release = MobileUpdateRelease("0.9.0", 9, 29, 1, 6, 6, "https://github.com/EvanGUTK/localspoof-releases/releases/download/v0.9.0/localspoof-0.9.0.apk", 1000, "a".repeat(64), MobileUpdateCatalog.SIGNER, "Changes")
        val fixture = PhoneView(Coordinator().snapshot()).copy(serviceRunning = true, updateBlockReason = "Stop playback first.")
        mount(MobileSection.SETTINGS, fixture = fixture, update = MobileUpdateState(MobileUpdateStep.READY, release))
        compose.onNodeWithTag("update-install").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("update-safe-gate").assertExists()
    }
    @Test fun updateReservationAllowsAndroidConfirmationWhileBlockingNewPlayback() {
        val events = mutableListOf<MobileUiEvent>()
        val fixture = PhoneView(Coordinator().snapshot(), updateInstallationPending = true, updateBlockReason = "Finish update confirmation.")
        mount(MobileSection.SETTINGS, fixture = fixture, events = { events.add(it) }, update = MobileUpdateState(MobileUpdateStep.CONFIRMATION))
        compose.onNodeWithTag("update-confirm").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(MobileUiEvent.ContinueUpdate), events)
    }
    @Test fun standaloneOnlineAndOfflineOptionsAreVisibleWithoutAnyDesktop() {
        mount(MobileSection.JOURNEY, fixture = PhoneView(Coordinator().snapshot()))
        compose.onNodeWithTag("planning-online").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("planning-offline").assertExists().assertIsNotSelected()
    }

}

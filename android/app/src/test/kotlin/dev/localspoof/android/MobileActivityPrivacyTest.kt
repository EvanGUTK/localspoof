package dev.localspoof.android

import android.os.Bundle
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.view.contentcapture.ContentCaptureManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Checks the real activity's defaults in a JVM Android host; no service/control action is invoked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36], qualifiers = "w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class MobileActivityPrivacyTest {
    @Test fun defaultWindowProtectsScreenshotsAndExcludesAutofill() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            val activity = controller.create().get()
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, activity.window.decorView.importantForAutofill)
            assertEquals(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS, activity.window.decorView.importantForContentCapture)
        }
    }

    @Test fun activityDoesNotRetainSyntheticInputInSavedState() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.create()
            val state = Bundle().apply { putString("synthetic-coordinate", "1.0,2.0") }
            controller.saveInstanceState(state)
            assertTrue(state.keySet().isEmpty())
        }
    }
    @Test @Config(sdk = [29]) fun api29ActivityKeepsSupportedPrivacyGuardsWithoutNewerViewApis() {
        assertEquals(29, Build.VERSION.SDK_INT)
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            val activity = controller.create().get()
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, activity.window.decorView.importantForAutofill)
            val capture = activity.getSystemService(ContentCaptureManager::class.java)
            assertTrue("API 29 activity content capture must be disabled or unavailable", capture == null || !capture.isContentCaptureEnabled)
            val state = Bundle().apply { putString("synthetic-coordinate", "1.0,2.0") }
            controller.saveInstanceState(state)
            assertTrue(state.keySet().isEmpty())
        }
    }
}

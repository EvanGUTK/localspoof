# Android mobile UI overhaul

This change replaces the overflowing single-column screen with focused Connect, Journey, and Settings sections. Connect opens first because the Windows app supplies worldwide maps and online routes. The Android companion remains offline and plays locally approved plans.

## Presentation

- One stateless `MobileApp(state, onEvent)` presentation accepts fixture state and callbacks, allowing rendered tests without starting services, mocking location, pairing, or changing radios.
- Measure the available window after safe-drawing and IME insets. Center content with an 840 dp maximum width. Use width divided by font scale to decide whether coordinate fields fit in two columns; otherwise stack them.
- In short windows and with the keyboard visible, omit the large header and use one section selector. Keep section content scrollable and critical Stop controls outside that scroll. Buttons and chips wrap and grow with their labels; minimum touch target is 48 dp.
- Native text inputs keep external labels, intrinsic/wrapping height, disabled view-state saving, disabled autofill/content capture, no suggestions, and no personalized keyboard learning. Secure-window protection stays active. Android 10 (API 29) opts out through the activity ContentCaptureManager; API 30+ additionally excludes the full decor and native inputs from content capture.

## State and actions

- Drafts, navigation, units, and defaults stay in a RAM-only lifecycle ViewModel with Compose state, retained across rotation and window configuration changes. No SavedStateHandle or Bundle restores them after process death. There is no saved state, profile, account, network permission, logging, or clipboard integration.
- Canonical speed is meters per second. Default display is mph and miles; km/h, m/s, and kilometers are available. Switching units changes only the display and preserves prepared plans. Editing actual speed or plan inputs invalidates local preview readiness.
- Async local preparation captures the draft generation, a separate preparation-run owner, and coordinator revision. A late result cannot restore readiness after editing or Stop, and an old completion cannot clear the busy state of a new preparation. Cancellation propagates correctly.
- Start is always an explicit phone action. Stop revokes the domain first and clears activity. Enabling desktop control and approving a pairing request remain separate explicit actions.
- Pairing secrets use grouped monospace text with responsive line breaks and a two-minute expiry explanation. The live secret is never copied, logged, or captured by tests; tests use synthetic fixture values only. The UI tells users to enter all 32 hex characters without spaces or line breaks.

## Verification

The completed Android build passed 53 app tests and 37 domain tests (zero failures), compiled both debug and release packages, and passed both lint tasks with zero errors. Lint reported six dependency-version warnings per variant.

Twelve pure presentation/controller tests cover canonical conversions and rounded mode boundaries, invalid inputs and unit-switch gates, Stop clearing activity while retaining preferences, and late-result/busy ownership races. Robolectric with native text graphics measures Connect, Journey, and Settings at 320×480, 360×800, 640×360, and 840×640 dp, each at font scales 1, 1.5, 2, and 2.5. Additional tests cover the centered 840 dp content cap inside a 1280×800 dp window, actual coordinate reflow, native input callbacks and minimum touch heights, secret text overflow, and explicit fixture-only navigation/Stop events.

API 29, 35, and 36 checks cover activity privacy and representative rendered configurations; the full size/font matrix runs on API 35. Native text layout, control bounds, and scroll reachability passed. Optional synthetic PNG export was unsupported in this test host, so no visual screenshot review is claimed. No test started mock playback or changed phone radios; fixture coordinates and pairing secrets were synthetic.

Actual keyboard/IME behavior, physical phone interaction, and manufacturer-specific layouts remain qualification items. These finite checks do not claim that every device, screen ratio, or operating-system configuration has been tested.

References: [Android adaptive display sizes](https://developer.android.com/develop/adaptive-apps/guides/support-different-display-sizes), [Compose inset padding](https://developer.android.com/develop/ui/compose/system/insets-ui), and [Compose adaptive guidance](https://developer.android.com/develop/ui/compose/build-adaptive-apps).

Content capture compatibility: [Android ContentCaptureManager API](https://developer.android.com/reference/android/view/contentcapture/ContentCaptureManager#setContentCaptureEnabled(boolean)) provides activity opt-out from API 29; [View capture importance](https://developer.android.com/reference/android/view/View#setImportantForContentCapture(int)) starts at API 30.

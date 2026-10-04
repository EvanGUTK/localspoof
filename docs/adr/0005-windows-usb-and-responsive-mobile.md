# 0005 — Windows USB ownership and responsive mobile presentation

Status: accepted for LocalSpoof 0.3.0; authorized implementation and local builds.

Windows setup must scan and start installed ADB from the desktop without requiring a terminal. The old numeric loopback host could reach a running daemon but was classified remote by ADB during a cold start. Use the fixed literal `tcp:localhost:5037` socket, scrub remote/trace overrides, and hide client consoles. Automatic discovery reads only bounded known SDK and PATH locations and preserves manually selected paths.

Windows can return `unknown` for a physical phone's USB path. Accept this fallback only with present Windows PnP exact serial evidence and an independent sole-USB ADB selector returning the same serial. Ambiguous USB selection fails closed. A forwarded TCP connection alone is insufficient: require a compatible public phone Hello before exposing pairing. Remove only the application's own forward on failures and disconnect.

The installer carries its exact, independently certificate-verified release APK. Source and staged bytes are locked against modification during packaging; the runtime hashes the same bounded in-memory buffer it installs. Windows shell stdin corrupted APK bytes in the real S24 test. Use a private fixed local ADB smart-socket install service, explicit primary user 0, raw binary transport, one absolute deadline, bounded status frames, and definitive Success. There is no generic shell/socket IPC, automatic uninstall, downgrade, profile bypass, or permission grant.

Phone socket idle readiness is separate from partial-frame reading. The previous five-second next-header timeout dropped a healthy USB connection while the user typed the code after Hello. Poll the exact accepted LocalSocket descriptor in bounded intervals; a started frame still has one five-second total budget. Preauthentication remains bounded to 120 seconds and an observed authenticated lease cannot fall back to the preauthentication window after expiry/revocation. Idle checks never renew credentials.

Desktop and Android speed displays support mph, km/h, and m/s while the engine continues to receive canonical m/s. Display-only unit switching preserves physical speed and preparation; real plan edits invalidate preparation. Preferences remain volatile, with no stored settings profile or activity history.

Android separates Connect, Journey, and Settings in a responsive presentation. Native protected inputs, secure windows, explicit phone Start, pairing approval, and revocation-first Stop remain required. Layout uses actual available width, height, font scale, safe system insets, IME padding, wrapping controls, and scrollable content. Tests must measure rendered layouts at representative compact/landscape/tablet/large-font configurations; pure breakpoints alone do not establish absence of clipping. No universal device-compatibility claim follows from a finite test matrix.

See [USB/settings evidence and boundaries](../DESKTOP_USB_SETTINGS_PLAN.md), [mobile UI plan](../ANDROID_MOBILE_UI_PLAN.md), and [validation](../VALIDATION.md).

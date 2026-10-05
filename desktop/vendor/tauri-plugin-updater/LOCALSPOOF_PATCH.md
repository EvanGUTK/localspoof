# Bounded verified-manifest adapter

Source: official tauri-plugin-updater 2.9.0 crates.io archive
https://crates.io/api/v1/crates/tauri-plugin-updater/2.9.0/download
Archive SHA-256: `27cbc31740f4d507712550694749572ec0e43bdd66992db7599b89fbfd6b167b`. Upstream licenses are retained.

The factory source change extracts the existing Update construction at the end of
Updater::check into public `update_from_verified_release`. Existing check behavior
is unchanged. LocalSpoof invokes this adapter after bounded catalog retrieval,
original publisher RSA-SHA256 authentication, schema/compatibility/URL validation.
No second unchecked network fetch occurs. Installer behavior is unchanged. Runtime diagnostic `log::` and `eprintln!` calls are removed from updater.rs/config.rs to honor LocalSpoof’s no-runtime-logging requirement; static errors still return normally. This includes the reachable Windows installer exit-hook message. No sensitive logging is hidden by scanner exceptions.
LocalSpoof separately bounds artifact downloads and verifies SHA-256 and the same
Minisign public key/signature as the official plugin before invoking install().

The plugin's JavaScript commands receive no capability grants. All application
update commands are narrow Rust commands and never accept URLs, manifests, keys
or installer bytes from the frontend.

Windows hardening: check ShellExecuteW’s error return before exiting; a launch
failure returns an error and immediately removes only owned installer staging.
Temp directories receive a fresh UUID marker with constant one-byte contents.
App startup scans at most 512 temp entries and removes at most eight owned
installer dirs older than 24 hours. Cleanup validates exact directory/marker/
installer names, ≤3 regular files and ≤256 MiB total, refuses all Windows reparse
points and nested/unknown content, and never touches saves. Fresh installers
remain untouched. Rust tempfile::keep replaces the deprecated into_path API.
The app disables the unused ZIP installation feature: the signed Windows feed
accepts only exact-version NSIS .exe artifacts. UUID v4 is used only for temporary
ownership markers, never as a device ID or outgoing request field.

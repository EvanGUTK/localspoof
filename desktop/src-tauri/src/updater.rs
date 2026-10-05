//! Narrow publisher-authenticated updates. Runtime plans, credentials and saved items
//! never enter update requests; downloaded installers live in RAM until handoff.
use base64::{engine::general_purpose::STANDARD, Engine};
use reqwest::{blocking::Client, redirect::Policy, Url};
use serde::{Deserialize, Serialize};
#[cfg(test)]
use serde_json::json;
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::{
    io::Read,
    sync::{
        atomic::{AtomicBool, Ordering},
        Mutex,
    },
    time::{Duration, Instant},
};
use tauri_plugin_updater::{Update, UpdaterExt};

pub const FEED: &str =
    "https://github.com/EvanGUTK/localspoof-releases/releases/latest/download/catalog.json";
const MAX_CATALOG: usize = 65_536;
const MAX_INSTALLER: u64 = 256 * 1024 * 1024;
const PREFIX: &str = "/EvanGUTK/localspoof-releases/releases/download/";

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Release {
    pub platform: String,
    pub version: String,
    pub version_code: Option<u64>,
    #[serde(rename = "minOS")]
    pub min_os: u32,
    pub protocol_major: u32,
    pub protocol_min: u32,
    pub protocol_max: u32,
    pub url: String,
    pub size: u64,
    pub sha256: String,
    pub signature: Option<String>,
    pub signer_sha256: Option<String>,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Catalog {
    schema_version: u32,
    version: String,
    notes: String,
    #[serde(rename = "pub_date")]
    pub_date: String,
    platforms: std::collections::BTreeMap<String, Platform>,
    releases: Vec<Release>,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Platform {
    url: String,
    signature: String,
}
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    pub phase: String,
    pub current_version: String,
    pub version: Option<String>,
    pub notes: String,
    pub received: u64,
    pub total: u64,
    pub error: Option<String>,
}
struct State {
    status: Status,
    candidate: Option<(Release, Update)>,
    bytes: Option<Vec<u8>>,
    busy: bool,
}
pub struct Coordinator {
    state: Mutex<State>,
    cancel: AtomicBool,
}
impl Default for Coordinator {
    fn default() -> Self {
        Self {
            state: Mutex::new(State {
                status: Status {
                    phase: "IDLE".into(),
                    current_version: env!("CARGO_PKG_VERSION").into(),
                    version: None,
                    notes: String::new(),
                    received: 0,
                    total: 0,
                    error: None,
                },
                candidate: None,
                bytes: None,
                busy: false,
            }),
            cancel: AtomicBool::new(false),
        }
    }
}
impl Coordinator {
    pub fn status(&self) -> Result<Status, String> {
        Ok(self.state.lock().map_err(|_| "UPDATE_BUSY")?.status.clone())
    }
    pub fn busy(&self) -> bool {
        self.state
            .lock()
            .map(|s| s.busy && s.status.phase == "INSTALLING")
            .unwrap_or(true)
    }
    fn begin(&self, phase: &str) -> Result<(), String> {
        let mut s = self.state.lock().map_err(|_| "UPDATE_BUSY")?;
        if s.busy {
            return Err("UPDATE_BUSY".into());
        }
        s.busy = true;
        s.status.phase = phase.into();
        s.status.error = None;
        self.cancel.store(false, Ordering::Release);
        Ok(())
    }
    pub fn cancel(&self) -> Result<(), String> {
        self.cancel.store(true, Ordering::Release);
        let mut s = self.state.lock().map_err(|_| "UPDATE_BUSY")?;
        if s.status.phase == "INSTALLING" {
            return Err("UPDATE_BUSY".into());
        }
        if s.busy {
            s.status.phase = "CANCELLING".into();
        }
        if !s.busy {
            s.bytes = None;
            s.candidate = None;
            s.status.phase = "IDLE".into();
            s.status.version = None;
            s.status.notes.clear();
            s.status.received = 0;
            s.status.total = 0;
        }
        Ok(())
    }
    fn finish(&self, result: Result<(), String>) -> Result<Status, String> {
        let mut s = self.state.lock().map_err(|_| "UPDATE_BUSY")?;
        s.busy = false;
        if let Err(error) = result {
            s.bytes = None;
            s.status.phase = if error == "UPDATE_CANCELLED" {
                "CANCELLED"
            } else {
                "ERROR"
            }
            .into();
            s.status.error = Some(error);
        }
        Ok(s.status.clone())
    }
    pub fn check(&self, app: &tauri::AppHandle) -> Result<Status, String> {
        self.begin("CHECKING")?;
        {
            let mut s = self.state.lock().map_err(|_| "UPDATE_BUSY")?;
            s.bytes = None;
            s.candidate = None;
            s.status.version = None;
            s.status.notes.clear();
            s.status.received = 0;
            s.status.total = 0;
        }
        let result = (|| {
            let client = client(Duration::from_secs(25))?;
            let deadline = Instant::now() + Duration::from_secs(40);
            let body = fetch(&client, FEED, MAX_CATALOG, &self.cancel, deadline, None)?;
            let sig = fetch(
                &client,
                &format!("{FEED}.sig"),
                2048,
                &self.cancel,
                deadline,
                None,
            )?;
            let signature = STANDARD
                .decode(
                    std::str::from_utf8(&sig)
                        .map_err(|_| "UPDATE_BAD_SIGNATURE")?
                        .trim(),
                )
                .map_err(|_| "UPDATE_BAD_SIGNATURE")?;
            let raw = verify_catalog(
                &body,
                &signature,
                include_bytes!("../resources/update-catalog-public.der"),
            )?;
            if self.cancel.load(Ordering::Acquire) {
                return Err("UPDATE_CANCELLED".into());
            }
            let (release, notes) = select_release(&raw, env!("CARGO_PKG_VERSION"), os_build())?;
            let mut s = self.state.lock().map_err(|_| "UPDATE_BUSY")?;
            s.bytes = None;
            s.candidate = None;
            s.status.received = 0;
            s.status.total = 0;
            if let Some(release) = release {
                let remote: tauri_plugin_updater::RemoteRelease =
                    serde_json::from_value(raw.clone()).map_err(|_| "UPDATE_INVALID_CATALOG")?;
                let official = app
                    .updater_builder()
                    .build()
                    .map_err(|_| "UPDATE_CONFIGURATION")?
                    .update_from_verified_release(remote, raw)
                    .map_err(|_| "UPDATE_INVALID_CATALOG")?
                    .ok_or("UPDATE_DOWNGRADE")?;
                if official.version != release.version
                    || official.download_url.as_str() != release.url
                    || Some(&official.signature) != release.signature.as_ref()
                {
                    return Err("UPDATE_INVALID_CATALOG".into());
                }
                s.status.phase = "AVAILABLE".into();
                s.status.version = Some(release.version.clone());
                s.status.notes = notes;
                s.status.total = release.size;
                s.candidate = Some((release, official));
            } else {
                s.status.phase = "CURRENT".into();
                s.status.version = None;
                s.status.notes = notes;
            }
            Ok(())
        })();
        self.finish(result)
    }
    pub fn download(&self, key: &str) -> Result<Status, String> {
        self.begin("DOWNLOADING")?;
        let result = (|| {
            let release = self
                .state
                .lock()
                .map_err(|_| "UPDATE_BUSY")?
                .candidate
                .as_ref()
                .map(|c| c.0.clone())
                .ok_or("UPDATE_CHECK_REQUIRED")?;
            let client = client(Duration::from_secs(180))?;
            let deadline = Instant::now() + Duration::from_secs(180);
            let progress = |received: u64| {
                if let Ok(mut s) = self.state.lock() {
                    s.status.received = received;
                }
            };
            let bytes = fetch(
                &client,
                &release.url,
                release.size as usize,
                &self.cancel,
                deadline,
                Some(&progress),
            )?;
            verify_artifact(&bytes, &release, key)?;
            if self.cancel.load(Ordering::Acquire) {
                return Err("UPDATE_CANCELLED".into());
            }
            let mut s = self.state.lock().map_err(|_| "UPDATE_BUSY")?;
            s.bytes = Some(bytes);
            s.status.phase = "READY".into();
            Ok(())
        })();
        self.finish(result)
    }
    pub fn take_installer(&self) -> Result<(Update, Vec<u8>), String> {
        let mut s = self.state.lock().map_err(|_| "UPDATE_BUSY")?;
        if s.busy || s.status.phase != "READY" {
            return Err("UPDATE_NOT_READY".into());
        }
        let update = s
            .candidate
            .as_ref()
            .map(|c| c.1.clone())
            .ok_or("UPDATE_NOT_READY")?;
        let bytes = s.bytes.take().ok_or("UPDATE_NOT_READY")?;
        s.busy = true;
        s.status.phase = "INSTALLING".into();
        Ok((update, bytes))
    }
    pub fn installation_failed(&self) -> Result<Status, String> {
        self.finish(Err("UPDATE_INSTALL_FAILED".into()))
    }
}
fn client(timeout: Duration) -> Result<Client, String> {
    Client::builder()
        .timeout(timeout)
        .connect_timeout(Duration::from_secs(10))
        .no_proxy()
        .no_gzip()
        .no_brotli()
        .no_deflate()
        .redirect(Policy::custom(|a| {
            if a.previous().len() >= 4 || !allowed_download_host(a.url()) {
                a.error("untrusted update redirect")
            } else {
                a.follow()
            }
        }))
        .build()
        .map_err(|_| "UPDATE_NETWORK".into())
}
fn allowed_download_host(url: &Url) -> bool {
    url.scheme() == "https"
        && url.username().is_empty()
        && url.password().is_none()
        && url.port_or_known_default() == Some(443)
        && match url.host_str() {
            Some("github.com") => url
                .path()
                .starts_with("/EvanGUTK/localspoof-releases/releases/"),
            Some("release-assets.githubusercontent.com" | "objects.githubusercontent.com") => true,
            _ => false,
        }
}
fn fetch(
    client: &Client,
    url: &str,
    limit: usize,
    cancel: &AtomicBool,
    deadline: Instant,
    progress: Option<&dyn Fn(u64)>,
) -> Result<Vec<u8>, String> {
    if cancel.load(Ordering::Acquire) || Instant::now() > deadline {
        return Err("UPDATE_CANCELLED".into());
    }
    let mut response = client
        .get(url)
        .timeout(deadline.saturating_duration_since(Instant::now()))
        .header("Accept", "application/octet-stream")
        .header("Cache-Control", "no-cache")
        .send()
        .map_err(|_| {
            if cancel.load(Ordering::Acquire) {
                "UPDATE_CANCELLED"
            } else {
                "UPDATE_NETWORK"
            }
        })?;
    if response.status().as_u16() == 404 {
        return Err("UPDATE_FEED_UNAVAILABLE".into());
    }
    if !response.status().is_success() {
        return Err("UPDATE_NETWORK".into());
    }
    if response.content_length().is_some_and(|n| n > limit as u64) {
        return Err("UPDATE_TOO_LARGE".into());
    }
    let mut bytes = Vec::new();
    bytes
        .try_reserve(limit.min(1024 * 1024))
        .map_err(|_| "UPDATE_MEMORY")?;
    let mut buf = [0u8; 65536];
    loop {
        if cancel.load(Ordering::Acquire) {
            return Err("UPDATE_CANCELLED".into());
        }
        if Instant::now() > deadline {
            return Err("UPDATE_NETWORK".into());
        }
        let count = response.read(&mut buf).map_err(|_| {
            if cancel.load(Ordering::Acquire) {
                "UPDATE_CANCELLED"
            } else {
                "UPDATE_NETWORK"
            }
        })?;
        if count == 0 {
            break;
        }
        if bytes.len().checked_add(count).is_none_or(|n| n > limit) {
            return Err("UPDATE_TOO_LARGE".into());
        }
        bytes.try_reserve(count).map_err(|_| "UPDATE_MEMORY")?;
        bytes.extend_from_slice(&buf[..count]);
        if let Some(p) = progress {
            p(bytes.len() as u64);
        }
    }
    Ok(bytes)
}
fn verify_catalog(body: &[u8], signature: &[u8], key: &[u8]) -> Result<Value, String> {
    if body.is_empty() || body.len() > MAX_CATALOG || signature.len() > 1024 {
        return Err("UPDATE_INVALID_CATALOG".into());
    }
    ring::signature::UnparsedPublicKey::new(&ring::signature::RSA_PKCS1_2048_8192_SHA256, key)
        .verify(body, signature)
        .map_err(|_| "UPDATE_BAD_SIGNATURE")?;
    std::str::from_utf8(body).map_err(|_| "UPDATE_INVALID_CATALOG")?;
    serde_json::from_slice(body).map_err(|_| "UPDATE_INVALID_CATALOG".into())
}
fn artifact_url(value: &str, platform: &str, version: &str) -> bool {
    let Ok(u) = Url::parse(value) else {
        return false;
    };
    if !allowed_download_host(&u)
        || u.host_str() != Some("github.com")
        || u.query().is_some()
        || u.fragment().is_some()
    {
        return false;
    }
    let Some(tail) = u.path().strip_prefix(PREFIX) else {
        return false;
    };
    let parts: Vec<_> = tail.split('/').collect();
    let filename = if platform == "android" {
        format!("localspoof-{version}.apk")
    } else {
        format!("LocalSpoof_{version}_x64-setup.exe")
    };
    parts.len() == 2
        && (parts[0] == version || parts[0] == format!("v{version}"))
        && parts[1] == filename
}
fn hash_valid(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
}
fn select_release(
    raw: &Value,
    current: &str,
    build: u32,
) -> Result<(Option<Release>, String), String> {
    let catalog: Catalog =
        serde_json::from_value(raw.clone()).map_err(|_| "UPDATE_INVALID_CATALOG")?;
    if catalog.schema_version != 1
        || catalog.notes.len() > 8000
        || catalog.pub_date.len() > 64
        || catalog.releases.is_empty()
        || catalog.releases.len() > 8
        || catalog.platforms.len() != 1
    {
        return Err("UPDATE_INVALID_CATALOG".into());
    }
    let version = semver::Version::parse(&catalog.version).map_err(|_| "UPDATE_INVALID_CATALOG")?;
    if !version.pre.is_empty()
        || !version.build.is_empty()
        || catalog.version.len() > 32
        || catalog.notes.contains('\0')
    {
        return Err("UPDATE_INVALID_CATALOG".into());
    }
    let _: tauri_plugin_updater::RemoteRelease =
        serde_json::from_value(raw.clone()).map_err(|_| "UPDATE_INVALID_CATALOG")?;
    let current = semver::Version::parse(current).map_err(|_| "UPDATE_CONFIGURATION")?;
    let mut selected = None;
    let mut seen = std::collections::HashSet::new();
    for release in catalog.releases {
        if !seen.insert(release.platform.clone())
            || release.version != catalog.version
            || !hash_valid(&release.sha256)
            || release.size == 0
            || release.protocol_major != 1
            || release.protocol_min > release.protocol_max
            || !artifact_url(&release.url, &release.platform, &release.version)
        {
            return Err("UPDATE_INVALID_CATALOG".into());
        }
        match release.platform.as_str() {
            "android" => {
                if release.size > 128 * 1024 * 1024
                    || release.version_code.is_none_or(|v| v == 0)
                    || release
                        .signer_sha256
                        .as_deref()
                        .is_none_or(|s| !hash_valid(s))
                {
                    return Err("UPDATE_INVALID_CATALOG".into());
                }
            }
            "windows-x86_64" => {
                let p = catalog
                    .platforms
                    .get("windows-x86_64")
                    .ok_or("UPDATE_INVALID_CATALOG")?;
                if release.size > MAX_INSTALLER
                    || release
                        .signature
                        .as_ref()
                        .is_none_or(|s| s.is_empty() || s.len() > 8192)
                    || p.url != release.url
                    || Some(&p.signature) != release.signature.as_ref()
                {
                    return Err("UPDATE_INVALID_CATALOG".into());
                }
                if release.min_os > build || release.protocol_min > 6 || release.protocol_max < 6 {
                    return Err("UPDATE_INCOMPATIBLE".into());
                }
                selected = Some(release);
            }
            _ => return Err("UPDATE_INCOMPATIBLE".into()),
        }
    }
    if version < current {
        return Err("UPDATE_DOWNGRADE".into());
    }
    if version == current {
        return Ok((None, catalog.notes));
    }
    Ok((Some(selected.ok_or("UPDATE_INCOMPATIBLE")?), catalog.notes))
}
fn verify_artifact(bytes: &[u8], release: &Release, key: &str) -> Result<(), String> {
    if bytes.len() as u64 != release.size
        || format!("{:x}", Sha256::digest(bytes)) != release.sha256
    {
        return Err("UPDATE_HASH_MISMATCH".into());
    }
    let decode = |s: &str| -> Result<String, String> {
        let bytes = STANDARD.decode(s).map_err(|_| "UPDATE_BAD_SIGNATURE")?;
        String::from_utf8(bytes).map_err(|_| "UPDATE_BAD_SIGNATURE".into())
    };
    let public =
        minisign_verify::PublicKey::decode(&decode(key)?).map_err(|_| "UPDATE_BAD_SIGNATURE")?;
    let signature = minisign_verify::Signature::decode(&decode(
        release.signature.as_deref().ok_or("UPDATE_BAD_SIGNATURE")?,
    )?)
    .map_err(|_| "UPDATE_BAD_SIGNATURE")?;
    public
        .verify(bytes, &signature, true)
        .map_err(|_| "UPDATE_BAD_SIGNATURE".into())
}
pub fn desktop_safe_snapshot(snapshot: &Value) -> bool {
    snapshot.get("ok") == Some(&Value::Bool(true))
        && snapshot.get("state").and_then(Value::as_str) == Some("IDLE")
        && snapshot.pointer("/payload/desktopUpdateSafe") == Some(&Value::Bool(true))
}
#[cfg(windows)]
fn os_build() -> u32 {
    #[repr(C)]
    struct Version {
        size: u32,
        major: u32,
        minor: u32,
        build: u32,
        platform: u32,
        service: [u16; 128],
    }
    #[link(name = "ntdll")]
    unsafe extern "system" {
        fn RtlGetVersion(info: *mut Version) -> i32;
    }
    let mut v = Version {
        size: std::mem::size_of::<Version>() as u32,
        major: 0,
        minor: 0,
        build: 0,
        platform: 0,
        service: [0; 128],
    };
    // SAFETY: OSVERSIONINFOW layout and size match ntdll's documented caller buffer.
    if unsafe { RtlGetVersion(&mut v) } == 0 {
        v.build
    } else {
        0
    }
}
#[cfg(not(windows))]
fn os_build() -> u32 {
    0
}

#[cfg(test)]
mod tests {
    use super::*;
    fn catalog() -> Value {
        json!({"schemaVersion":1,"version":"0.9.0","notes":"Reviewed update","pub_date":"2026-10-05T00:00:00Z","platforms":{"windows-x86_64":{"url":"https://github.com/EvanGUTK/localspoof-releases/releases/download/v0.9.0/LocalSpoof_0.9.0_x64-setup.exe","signature":"signed"}},"releases":[{"platform":"windows-x86_64","version":"0.9.0","minOS":19041,"protocolMajor":1,"protocolMin":6,"protocolMax":6,"url":"https://github.com/EvanGUTK/localspoof-releases/releases/download/v0.9.0/LocalSpoof_0.9.0_x64-setup.exe","size":1024,"sha256":"a".repeat(64),"signature":"signed"}]})
    }
    #[test]
    fn reject_downgrade_mismatches_wrong_platform_and_compatibility() {
        assert!(select_release(&catalog(), "0.8.0", 19045)
            .unwrap()
            .0
            .is_some());
        assert!(select_release(&catalog(), "0.9.0", 19045)
            .unwrap()
            .0
            .is_none());
        assert_eq!(
            select_release(&catalog(), "1.0.0", 19045).err().as_deref(),
            Some("UPDATE_DOWNGRADE")
        );
        assert!(select_release(&catalog(), "0.8.0", 19040).is_err());
        for (path, value) in [
            ("/releases/0/url", json!("https://example.com/malware.exe")),
            ("/releases/0/protocolMin", json!(7)),
            ("/releases/0/sha256", json!("BAD")),
            ("/platforms/windows-x86_64/signature", json!("different")),
            ("/releases/0/platform", json!("windows-arm64")),
            ("/releases/0/size", json!(MAX_INSTALLER + 1)),
        ] {
            let mut c = catalog();
            *c.pointer_mut(path).unwrap() = value;
            assert!(select_release(&c, "0.8.0", 19045).is_err(), "{path}");
        }
    }
    #[test]
    fn release_urls_are_publisher_scoped_and_redirects_are_https_only() {
        for url in [
            "http://github.com/EvanGUTK/localspoof-releases/releases/download/v1/a.exe",
            "https://github.com/attacker/localspoof-releases/releases/download/v1/a.exe",
            "https://github.com/EvanGUTK/localspoof-releases/releases/download/v1/a.exe?x=y",
            "https://github.com/EvanGUTK/localspoof-releases/releases/download/v1/%2e%2e.exe",
            "https://objects.githubusercontent.com/a.exe",
        ] {
            assert!(!artifact_url(url, "windows-x86_64", "1.0.0"));
        }
        assert!(!allowed_download_host(
            &Url::parse("https://127.0.0.1/a").unwrap()
        ));
        assert!(!allowed_download_host(
            &Url::parse("https://release-assets.githubusercontent.com.evil.com/a").unwrap()
        ));
        assert!(!allowed_download_host(
            &Url::parse("https://user:pass@github.com/EvanGUTK/localspoof-releases/releases/x")
                .unwrap()
        ));
    }
    #[test]
    fn install_safe_gate_requires_explicit_fresh_idle_assertion() {
        assert!(desktop_safe_snapshot(
            &json!({"ok":true,"state":"IDLE","payload":{"desktopUpdateSafe":true}})
        ));
        for state in ["READY", "ACTIVE", "PAUSED", "HOLDING", "ERROR"] {
            assert!(!desktop_safe_snapshot(
                &json!({"ok":true,"state":state,"payload":{"desktopUpdateSafe":true}})
            ));
        }
        assert!(!desktop_safe_snapshot(
            &json!({"ok":true,"state":"IDLE","payload":{}})
        ));
        assert!(!desktop_safe_snapshot(
            &json!({"ok":false,"state":"IDLE","payload":{"desktopUpdateSafe":true}})
        ));
    }
    #[test]
    fn exact_rsa_signed_bytes_are_authenticated_before_json_parsing() {
        let body = include_bytes!("update-test-data/catalog.json");
        let sig = include_bytes!("update-test-data/signature.bin");
        let key = include_bytes!("update-test-data/public.der");
        assert_eq!(
            verify_catalog(body, sig, key).unwrap(),
            json!({"valid":true})
        );
        let mut changed = body.to_vec();
        changed[1] ^= 1;
        assert!(verify_catalog(&changed, sig, key).is_err());
        let mut changed_sig = sig.to_vec();
        changed_sig[0] ^= 1;
        assert!(verify_catalog(body, &changed_sig, key).is_err());
        assert!(verify_catalog(
            body,
            sig,
            include_bytes!("../resources/update-catalog-public.der")
        )
        .is_err());
    }
    #[test]
    fn tampered_catalog_and_artifact_never_reach_installer() {
        assert!(verify_catalog(b"{}", b"forged", b"badkey").is_err());
        let r = select_release(&catalog(), "0.8.0", 19045)
            .unwrap()
            .0
            .unwrap();
        assert!(verify_artifact(b"tampered", &r, "badkey").is_err());
    }
    #[test]
    fn cancel_discards_ram_download_without_touching_saved_store() {
        let c = Coordinator::default();
        c.cancel().unwrap();
        assert_eq!(c.status().unwrap().phase, "IDLE");
        c.begin("INSTALLING").unwrap();
        assert!(c.cancel().is_err());
        assert!(c.busy());
    }
}

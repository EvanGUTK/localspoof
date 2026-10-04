use serde::Serialize;
use sha2::{Digest, Sha256};
use std::{
    fs::File,
    io::{Read, Write},
    path::{Path, PathBuf},
    process::{Command, Stdio},
    sync::mpsc,
    thread,
    time::{Duration, Instant},
};

pub(crate) const MAX_APK_BYTES: u64 = 209_715_200;
const MAX_TOOL_OUTPUT_BYTES: u64 = 65_536;

/// The returned bytes are both hashed and installed; the source pathname is never reopened.
fn read_verified_apk(
    path: &Path,
    expected: Option<&str>,
    limit: u64,
) -> Result<Vec<u8>, NativeError> {
    let expected = expected.ok_or(NativeError::UntrustedApk)?;
    if expected.len() != 64 || !expected.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(NativeError::UntrustedApk);
    }
    if !path.is_absolute() || path.extension().and_then(|s| s.to_str()) != Some("apk") {
        return Err(NativeError::UntrustedApk);
    }
    let file = File::open(path).map_err(|_| NativeError::UntrustedApk)?;
    let metadata = file.metadata().map_err(|_| NativeError::UntrustedApk)?;
    if !metadata.is_file() || metadata.len() == 0 || metadata.len() > limit {
        return Err(NativeError::UntrustedApk);
    }
    let mut bytes = Vec::new();
    file.take(limit + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| NativeError::UntrustedApk)?;
    // A changed/growing source cannot exceed the bound between metadata and reading.
    if bytes.is_empty() || bytes.len() as u64 > limit {
        return Err(NativeError::UntrustedApk);
    }
    if format!("{:x}", Sha256::digest(&bytes)) != expected.to_ascii_lowercase() {
        return Err(NativeError::UntrustedApk);
    }
    Ok(bytes)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum NativeError {
    InvalidTool,
    InvalidDevice,
    ToolFailed,
    ToolTimedOut,
    InvalidFrame,
    Disconnected,
    NotConnected,
    PairingRequired,
    PairingPending,
    InvalidSecret,
    InvalidRequest,
    StaleRevision,
    UntrustedApk,
    CapabilityUnavailable,
    RadioNotOwned,
    RadioPhaseMismatch,
    AdbServerUnavailable,
    AdbUnauthorized,
    AdbOffline,
    UsbDeviceRequired,
    UsbVerificationUnavailable,
    PhoneControlUnavailable,
    PhoneProtocolMismatch,
    ApkInstallFailed,
    ApkSignatureMismatch,
}

impl NativeError {
    pub const fn code(self) -> &'static str {
        match self {
            Self::InvalidTool => "INVALID_TOOL",
            Self::InvalidDevice => "INVALID_DEVICE",
            Self::ToolFailed => "TOOL_FAILED",
            Self::ToolTimedOut => "TOOL_TIMED_OUT",
            Self::InvalidFrame => "INVALID_FRAME",
            Self::Disconnected => "DISCONNECTED",
            Self::NotConnected => "NOT_CONNECTED",
            Self::PairingRequired => "PAIRING_REQUIRED",
            Self::PairingPending => "PAIRING_PENDING",
            Self::InvalidSecret => "INVALID_SECRET",
            Self::InvalidRequest => "INVALID_REQUEST",
            Self::StaleRevision => "STALE_REVISION",
            Self::UntrustedApk => "UNTRUSTED_APK",
            Self::CapabilityUnavailable => "CAPABILITY_UNAVAILABLE",
            Self::RadioNotOwned => "RADIO_NOT_OWNED",
            Self::RadioPhaseMismatch => "RADIO_PHASE_MISMATCH",
            Self::AdbServerUnavailable => "ADB_SERVER_UNAVAILABLE",
            Self::AdbUnauthorized => "ADB_UNAUTHORIZED",
            Self::AdbOffline => "ADB_OFFLINE",
            Self::UsbDeviceRequired => "USB_DEVICE_REQUIRED",
            Self::UsbVerificationUnavailable => "USB_VERIFICATION_UNAVAILABLE",
            Self::PhoneControlUnavailable => "PHONE_CONTROL_UNAVAILABLE",
            Self::PhoneProtocolMismatch => "PHONE_PROTOCOL_MISMATCH",
            Self::ApkInstallFailed => "APK_INSTALL_FAILED",
            Self::ApkSignatureMismatch => "APK_SIGNATURE_MISMATCH",
        }
    }
}

impl std::fmt::Display for NativeError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(self.code())
    }
}
impl std::error::Error for NativeError {}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct Device {
    pub serial: String,
    pub model: String,
    pub state: String,
}

pub fn validate_serial(serial: &str) -> Result<(), NativeError> {
    if serial.is_empty()
        || serial.len() > 128
        || serial.starts_with('-')
        || serial.starts_with("emulator-")
        || serial.contains("_adb-")
        || serial.contains("._tcp")
        || !serial
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_' || b == b'.')
    {
        return Err(NativeError::InvalidDevice);
    }
    Ok(())
}

pub fn is_usb_devpath(path: &str) -> bool {
    let normalized = path.trim().to_ascii_lowercase();
    normalized.len() <= 1024
        && (normalized.starts_with("usb:")
            || normalized.starts_with("/dev/bus/usb/")
            || normalized.starts_with("\\\\?\\usb#"))
}

/// Match a present Windows USB device's serial component, never a substring or network ID.
pub fn is_matching_usb_instance_id(instance_id: &str, serial: &str) -> bool {
    if validate_serial(serial).is_err() || instance_id.len() > 1024 {
        return false;
    }
    let mut parts = instance_id.split('\\');
    let (Some(bus), Some(hardware), Some(instance)) = (parts.next(), parts.next(), parts.next())
    else {
        return false;
    };
    let mut identifiers = hardware.split('&');
    let (Some(vendor), Some(product)) = (identifiers.next(), identifiers.next()) else {
        return false;
    };
    let identifier = |value: &str, prefix: &str| {
        value.is_ascii()
            && value.len() == 8
            && value[..4].eq_ignore_ascii_case(prefix)
            && value[4..].bytes().all(|byte| byte.is_ascii_hexdigit())
    };
    parts.next().is_none()
        && bus.eq_ignore_ascii_case("USB")
        && identifier(vendor, "VID_")
        && identifier(product, "PID_")
        && instance.eq_ignore_ascii_case(serial)
}

#[cfg(windows)]
fn windows_usb_present(serial: &str) -> Result<bool, NativeError> {
    use windows_sys::Win32::{
        Devices::DeviceAndDriverInstallation::{
            SetupDiDestroyDeviceInfoList, SetupDiEnumDeviceInfo, SetupDiGetClassDevsW,
            SetupDiGetDeviceInstanceIdW, DIGCF_ALLCLASSES, DIGCF_PRESENT, HDEVINFO,
            SP_DEVINFO_DATA,
        },
        Foundation::{GetLastError, ERROR_NO_MORE_ITEMS},
    };
    struct DeviceSet(HDEVINFO);
    impl Drop for DeviceSet {
        fn drop(&mut self) {
            // SAFETY: this owns a valid SetupAPI handle and releases it exactly once.
            unsafe {
                SetupDiDestroyDeviceInfoList(self.0);
            }
        }
    }
    let enumerator: Vec<u16> = "USB\0".encode_utf16().collect();
    // SAFETY: a terminated USB enumerator is live for the call; no class or parent pointers.
    let handle = unsafe {
        SetupDiGetClassDevsW(
            std::ptr::null(),
            enumerator.as_ptr(),
            std::ptr::null_mut(),
            DIGCF_ALLCLASSES | DIGCF_PRESENT,
        )
    };
    if handle == -1 {
        return Err(NativeError::UsbVerificationUnavailable);
    }
    let devices = DeviceSet(handle);
    for index in 0..4096 {
        let mut data = SP_DEVINFO_DATA {
            cbSize: std::mem::size_of::<SP_DEVINFO_DATA>() as u32,
            ..Default::default()
        };
        // SAFETY: initialized structure of the correct ABI size, owned handle, bounded index.
        if unsafe { SetupDiEnumDeviceInfo(devices.0, index, &mut data) } == 0 {
            // SAFETY: reads the calling thread's last error immediately after the failed call.
            return if unsafe { GetLastError() } == ERROR_NO_MORE_ITEMS {
                Ok(false)
            } else {
                Err(NativeError::UsbVerificationUnavailable)
            };
        }
        let mut id = [0u16; 1024];
        // SAFETY: buffer length equals the supplied capacity and data belongs to this handle.
        if unsafe {
            SetupDiGetDeviceInstanceIdW(
                devices.0,
                &data,
                id.as_mut_ptr(),
                id.len() as u32,
                std::ptr::null_mut(),
            )
        } == 0
        {
            continue;
        }
        let length = id
            .iter()
            .position(|&unit| unit == 0)
            .ok_or(NativeError::UsbVerificationUnavailable)?;
        let value = String::from_utf16(&id[..length])
            .map_err(|_| NativeError::UsbVerificationUnavailable)?;
        if is_matching_usb_instance_id(&value, serial) {
            return Ok(true);
        }
    }
    Err(NativeError::UsbVerificationUnavailable)
}

/// Only known diagnostics become public codes; raw output is never attached to errors.
pub(crate) fn classify_tool_failure(output: &[u8], errors: &[u8]) -> NativeError {
    let text = format!(
        "{} {}",
        String::from_utf8_lossy(output),
        String::from_utf8_lossy(errors)
    )
    .to_ascii_lowercase();
    if text.contains("device unauthorized") || text.contains("device still authorizing") {
        NativeError::AdbUnauthorized
    } else if text.contains("device offline") {
        NativeError::AdbOffline
    } else if text.contains("install_failed_update_incompatible") {
        NativeError::ApkSignatureMismatch
    } else if text.contains("cannot connect to daemon")
        || text.contains("failed to start daemon")
        || text.contains("cannot start server on remote host")
        || text.contains("could not read ok from adb server")
    {
        NativeError::AdbServerUnavailable
    } else {
        NativeError::ToolFailed
    }
}

pub fn parse_devices(output: &str) -> Vec<Device> {
    output
        .lines()
        .take(128)
        .filter_map(|line| {
            let mut parts = line.split_whitespace();
            let serial = parts.next()?;
            let state = parts.next()?;
            if validate_serial(serial).is_err()
                || !["device", "unauthorized", "offline"].contains(&state)
            {
                return None;
            }
            let model = parts
                .find_map(|s| s.strip_prefix("model:"))
                .unwrap_or("Android");
            Some(Device {
                serial: serial.to_owned(),
                state: state.to_owned(),
                model: model
                    .chars()
                    .take(96)
                    .map(|c| if c == '_' { ' ' } else { c })
                    .collect(),
            })
        })
        .collect()
}

#[derive(Debug, Clone)]
pub struct Adb {
    path: PathBuf,
}

impl Adb {
    pub fn new(path: impl AsRef<Path>) -> Result<Self, NativeError> {
        let supplied = path.as_ref();
        if !supplied.is_absolute() || !supplied.is_file() {
            return Err(NativeError::InvalidTool);
        }
        let name = supplied
            .file_name()
            .and_then(|s| s.to_str())
            .ok_or(NativeError::InvalidTool)?;
        if !matches!(name, "adb" | "adb.exe") {
            return Err(NativeError::InvalidTool);
        }
        let adb = Self {
            path: supplied.to_owned(),
        };
        let version = adb.execute(&["version"], Duration::from_secs(5))?;
        if !version.starts_with("Android Debug Bridge version ") {
            return Err(NativeError::InvalidTool);
        }
        Ok(adb)
    }

    pub fn devices(&self) -> Result<Vec<Device>, NativeError> {
        Ok(parse_devices(
            &self.execute(&["devices", "-l"], Duration::from_secs(5))?,
        ))
    }

    pub fn device(&self, serial: &str, args: &[&str]) -> Result<String, NativeError> {
        validate_serial(serial)?;
        let mut scoped = vec!["-s", serial];
        scoped.extend_from_slice(args);
        self.execute(&scoped, Duration::from_secs(10))
    }

    pub fn require_usb(&self, serial: &str) -> Result<(), NativeError> {
        let path = self.device(serial, &["get-devpath"])?;
        if is_usb_devpath(&path) {
            return Ok(());
        }
        #[cfg(windows)]
        if path.trim().eq_ignore_ascii_case("unknown") {
            if !windows_usb_present(serial)? {
                return Err(NativeError::UsbDeviceRequired);
            }
            // -d WITHOUT -s forces ADB's physical USB transport selector. PnP presence alone
            // cannot prove which ADB transport is selected. Ambiguous multiple USB fails closed.
            let usb_serial = self
                .execute(&["-d", "get-serialno"], Duration::from_secs(5))
                .map_err(|_| NativeError::UsbVerificationUnavailable)?;
            return if usb_serial.trim() == serial {
                Ok(())
            } else {
                Err(NativeError::UsbDeviceRequired)
            };
        }
        Err(NativeError::UsbDeviceRequired)
    }

    fn execute(&self, args: &[&str], timeout: Duration) -> Result<String, NativeError> {
        self.execute_with_input(args, timeout, None)
    }

    fn execute_with_input(
        &self,
        args: &[&str],
        timeout: Duration,
        input: Option<Vec<u8>>,
    ) -> Result<String, NativeError> {
        let mut command = Command::new(&self.path);
        command
            // AOSP treats the numeric host as remote; the literal localhost permits cold
            // daemon startup while preserving a loopback-only server socket.
            .args(["-L", "tcp:localhost:5037"])
            .args(args)
            .env_remove("ADB_TRACE")
            .env_remove("ADB_SERVER_SOCKET")
            .env_remove("ANDROID_ADB_SERVER_ADDRESS")
            .env_remove("ANDROID_ADB_SERVER_PORT")
            .env_remove("ANDROID_SERIAL")
            .env("ADB_MDNS_AUTO_CONNECT", "0")
            .stdin(if input.is_some() {
                Stdio::piped()
            } else {
                Stdio::null()
            })
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            // SDK 37's documented Windows compatibility backend. This affects only a
            // daemon this client starts; never restart an existing shared server implicitly.
            command.env("ADB_USB_LEGACY", "1");
            command.creation_flags(0x0800_0000); // CREATE_NO_WINDOW for the GUI's bounded tool call.
        }
        let mut child = command.spawn().map_err(|_| NativeError::ToolFailed)?;
        let out = child.stdout.take().ok_or(NativeError::ToolFailed)?;
        let err = child.stderr.take().ok_or(NativeError::ToolFailed)?;
        let read = |stream: Box<dyn Read + Send>| {
            let (sender, receiver) = mpsc::channel();
            thread::spawn(move || {
                let result = (|| {
                    let mut bytes = Vec::new();
                    stream
                        .take(MAX_TOOL_OUTPUT_BYTES + 1)
                        .read_to_end(&mut bytes)
                        .map_err(|_| NativeError::ToolFailed)?;
                    if bytes.len() as u64 > MAX_TOOL_OUTPUT_BYTES {
                        return Err(NativeError::ToolFailed);
                    }
                    Ok(bytes)
                })();
                let _ = sender.send(result);
            });
            receiver
        };
        let stdout = read(Box::new(out));
        let stderr = read(Box::new(err));
        let stdin = input.map(|bytes| {
            let mut pipe = child.stdin.take().expect("piped stdin was requested");
            let (sender, receiver) = mpsc::channel();
            thread::spawn(move || {
                let result = pipe
                    .write_all(&bytes)
                    .and_then(|()| pipe.flush())
                    .map_err(|_| NativeError::ToolFailed);
                // Closing stdin delivers EOF to PackageManager's bounded APK reader.
                drop(pipe);
                let _ = sender.send(result);
            });
            receiver
        });
        let deadline = Instant::now() + timeout;
        let mut status = None;
        let mut output = None;
        let mut errors = None;
        let mut written = stdin.as_ref().map(|_| None).unwrap_or(Some(Ok(())));
        let result = loop {
            if status.is_none() {
                match child.try_wait() {
                    Ok(value) => status = value,
                    Err(_) => break Err(NativeError::ToolFailed),
                }
            }
            if output.is_none() {
                output = stdout.try_recv().ok();
            }
            if errors.is_none() {
                errors = stderr.try_recv().ok();
            }
            if written.is_none() {
                written = stdin.as_ref().and_then(|receiver| receiver.try_recv().ok());
            }
            if matches!(output, Some(Err(_))) || matches!(errors, Some(Err(_))) {
                break Err(NativeError::ToolFailed);
            }
            if let (Some(exit), Some(out), Some(err), Some(input_result)) =
                (status, output.as_mut(), errors.as_mut(), written.as_ref())
            {
                let (Ok(out), Ok(err)) = (out, err) else {
                    break Err(NativeError::ToolFailed);
                };
                if !exit.success() {
                    break Err(classify_tool_failure(out, err));
                }
                if input_result.is_err() {
                    break Err(NativeError::ToolFailed);
                }
                break String::from_utf8(std::mem::take(out)).map_err(|_| NativeError::ToolFailed);
            }
            if Instant::now() >= deadline {
                break Err(NativeError::ToolTimedOut);
            }
            thread::sleep(Duration::from_millis(20));
        };
        if result.is_err() {
            let _ = child.kill();
            let _ = child.wait();
        }
        // Retain stdout/stderr only transiently, never logs or payload-bearing errors.
        result
    }

    pub fn install(
        &self,
        serial: &str,
        apk: impl AsRef<Path>,
        trusted_sha256: Option<&str>,
    ) -> Result<(), NativeError> {
        let bytes = read_verified_apk(apk.as_ref(), trusted_sha256, MAX_APK_BYTES)?;
        self.require_usb(serial)?;
        crate::install::install_verified_bytes(serial, &bytes)
    }

    pub fn airplane_state(&self, serial: &str) -> Result<bool, NativeError> {
        match self
            .device(serial, &["shell", "cmd", "connectivity", "airplane-mode"])?
            .trim()
        {
            "enabled" => Ok(true),
            "disabled" => Ok(false),
            _ => Err(NativeError::CapabilityUnavailable),
        }
    }

    pub fn airplane_set(&self, serial: &str, enabled: bool) -> Result<(), NativeError> {
        self.device(
            serial,
            &[
                "shell",
                "cmd",
                "connectivity",
                "airplane-mode",
                if enabled { "enable" } else { "disable" },
            ],
        )?;
        if self.airplane_state(serial)? != enabled {
            return Err(NativeError::CapabilityUnavailable);
        }
        Ok(())
    }
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;
    use std::{
        fs,
        os::unix::fs::PermissionsExt,
        sync::atomic::{AtomicU64, Ordering},
    };

    static NEXT_DIRECTORY: AtomicU64 = AtomicU64::new(0);

    struct Fixture {
        directory: PathBuf,
        apk: PathBuf,
        adb: PathBuf,
    }
    impl Fixture {
        fn new(script_body: &str) -> Self {
            let directory = std::env::temp_dir().join(format!(
                "localspoof-adb-test-{}-{}",
                std::process::id(),
                NEXT_DIRECTORY.fetch_add(1, Ordering::Relaxed)
            ));
            fs::create_dir(&directory).unwrap();
            let apk = directory.join("synthetic.apk");
            let adb = directory.join("adb");
            fs::write(&adb, format!("#!/usr/bin/env python3\nimport sys\nfrom pathlib import Path\nargs=sys.argv[1:]\nassert args[:2]==['-L','tcp:localhost:5037']\nargs=args[2:]\n{script_body}\n")).unwrap();
            fs::set_permissions(&adb, fs::Permissions::from_mode(0o700)).unwrap();
            Self {
                directory,
                apk,
                adb,
            }
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.directory);
        }
    }

    #[test]
    fn verified_bytes_survive_source_replacement_before_usb_validation() {
        let fixture = Fixture::new("");
        let original = b"synthetic apk\0binary\nbytes";
        fs::write(&fixture.apk, original).unwrap();
        let source = format!("{:?}", fixture.apk.to_str().unwrap());
        let body = format!("if args==['version']:\n    print('Android Debug Bridge version synthetic')\nelif args==['-s','ABC123','get-devpath']:\n    Path({source}).write_bytes(b'replaced after verification')\n    print('usb:1-2')\nelse:\n    raise AssertionError('unexpected command')");
        fs::write(&fixture.adb, format!("#!/usr/bin/env python3\nimport sys\nfrom pathlib import Path\nargs=sys.argv[1:]\nassert args[:2]==['-L','tcp:localhost:5037']\nargs=args[2:]\n{body}\n")).unwrap();
        let expected = format!("{:x}", Sha256::digest(original));
        let bytes = read_verified_apk(&fixture.apk, Some(&expected), MAX_APK_BYTES).unwrap();
        Adb::new(&fixture.adb)
            .unwrap()
            .require_usb("ABC123")
            .unwrap();
        assert_eq!(bytes, original);
        assert_eq!(
            fs::read(&fixture.apk).unwrap(),
            b"replaced after verification"
        );
        assert_eq!(
            fs::read_dir(&fixture.directory).unwrap().count(),
            2,
            "No staging APK should be created"
        );
    }

    #[test]
    fn trusted_apk_reader_rejects_wrong_digest_empty_and_oversized_payloads() {
        let fixture = Fixture::new("");
        fs::write(&fixture.apk, b"bounded fixture").unwrap();
        assert_eq!(
            read_verified_apk(&fixture.apk, Some(&"0".repeat(64)), 100).unwrap_err(),
            NativeError::UntrustedApk
        );
        let digest = format!("{:x}", Sha256::digest(b"bounded fixture"));
        assert_eq!(
            read_verified_apk(&fixture.apk, Some(&digest), 4).unwrap_err(),
            NativeError::UntrustedApk
        );
        assert_eq!(
            read_verified_apk(&fixture.apk, None, 100).unwrap_err(),
            NativeError::UntrustedApk
        );
        fs::write(&fixture.apk, b"").unwrap();
        let empty_digest = format!("{:x}", Sha256::digest(b""));
        assert_eq!(
            read_verified_apk(&fixture.apk, Some(&empty_digest), 100).unwrap_err(),
            NativeError::UntrustedApk
        );
    }

    #[test]
    fn known_tool_errors_are_sanitized_without_raw_output() {
        let fixture = Fixture::new("if args==['version']:\n    print('Android Debug Bridge version synthetic')\nelse:\n    sys.stderr.write('device unauthorized; synthetic private path must never escape')\n    sys.exit(1)");
        assert_eq!(
            Adb::new(&fixture.adb)
                .unwrap()
                .device("ABC123", &["get-devpath"]),
            Err(NativeError::AdbUnauthorized)
        );
    }

    #[test]
    fn streamed_input_and_output_share_a_bounded_process_deadline() {
        let fixture = Fixture::new("import time\ntime.sleep(10)");
        let adb = Adb {
            path: fixture.adb.clone(),
        };
        let started = Instant::now();
        assert_eq!(
            adb.execute_with_input(
                &["synthetic"],
                Duration::from_millis(150),
                Some(vec![0; 1_000_000])
            ),
            Err(NativeError::ToolTimedOut)
        );
        assert!(started.elapsed() < Duration::from_secs(2));
    }

    #[test]
    fn tool_output_bound_fails_closed() {
        let fixture = Fixture::new("sys.stdout.write('x'*70000)");
        let adb = Adb {
            path: fixture.adb.clone(),
        };
        assert_eq!(
            adb.execute(&["synthetic"], Duration::from_secs(2)),
            Err(NativeError::ToolFailed)
        );
    }

    #[test]
    fn cold_adb_client_uses_an_explicit_local_socket_that_can_autostart() {
        let fixture = Fixture::new("");
        fs::write(&fixture.adb, "#!/usr/bin/env python3\nimport sys\na=sys.argv[1:]\nassert a[:2]==['-L','tcp:localhost:5037'], 'Numeric -H host is classified remote and cannot start the cold daemon'\na=a[2:]\nif a==['version']: print('Android Debug Bridge version synthetic')\nelif a==['devices','-l']: print('ABC123 device model:Synthetic')\nelse: raise AssertionError('unexpected command')\n").unwrap();
        let adb = Adb::new(&fixture.adb).unwrap();
        assert_eq!(adb.devices().unwrap()[0].serial, "ABC123");
    }
}

//! Private binary-safe APK installation over the local ADB smart socket. No generic command API.
use crate::adb::{classify_tool_failure, NativeError, MAX_APK_BYTES};
use std::{
    io::{self, Read, Write},
    net::TcpStream,
    time::{Duration, Instant},
};

const MAX_INSTALL_STATUS: usize = 65_536;

fn socket_error(error: io::Error) -> NativeError {
    if matches!(
        error.kind(),
        io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock
    ) {
        NativeError::ToolTimedOut
    } else {
        NativeError::ApkInstallFailed
    }
}

struct DeadlineSocket {
    stream: TcpStream,
    deadline: Instant,
}
impl DeadlineSocket {
    fn remaining(&self) -> Result<Duration, NativeError> {
        self.deadline
            .checked_duration_since(Instant::now())
            .filter(|time| !time.is_zero())
            .ok_or(NativeError::ToolTimedOut)
    }
    fn write(&mut self, bytes: &[u8]) -> Result<(), NativeError> {
        let mut offset = 0;
        while offset < bytes.len() {
            self.stream
                .set_write_timeout(Some(self.remaining()?))
                .map_err(socket_error)?;
            let end = bytes.len().min(offset + 65_536);
            let count = self
                .stream
                .write(&bytes[offset..end])
                .map_err(socket_error)?;
            if count == 0 {
                return Err(NativeError::ApkInstallFailed);
            }
            offset += count;
        }
        Ok(())
    }
    fn read(&mut self, buffer: &mut [u8]) -> Result<usize, NativeError> {
        self.stream
            .set_read_timeout(Some(self.remaining()?))
            .map_err(socket_error)?;
        self.stream.read(buffer).map_err(socket_error)
    }
    fn read_exact(&mut self, buffer: &mut [u8]) -> Result<(), NativeError> {
        let mut offset = 0;
        while offset < buffer.len() {
            let count = self.read(&mut buffer[offset..])?;
            if count == 0 {
                return Err(NativeError::ApkInstallFailed);
            }
            offset += count;
        }
        Ok(())
    }
    fn service(&mut self, service: &str) -> Result<(), NativeError> {
        // Both callers below construct fixed protocol services from validated serial/byte length.
        if service.len() > 4096 {
            return Err(NativeError::InvalidRequest);
        }
        self.write(format!("{:04x}", service.len()).as_bytes())?;
        self.write(service.as_bytes())?;
        let mut response = [0u8; 4];
        self.read_exact(&mut response)?;
        match &response {
            b"OKAY" => Ok(()),
            b"FAIL" => {
                let mut length = [0u8; 4];
                self.read_exact(&mut length)?;
                let text =
                    std::str::from_utf8(&length).map_err(|_| NativeError::ApkInstallFailed)?;
                if !text.bytes().all(|byte| byte.is_ascii_hexdigit()) {
                    return Err(NativeError::ApkInstallFailed);
                }
                let size =
                    usize::from_str_radix(text, 16).map_err(|_| NativeError::ApkInstallFailed)?;
                if size > MAX_INSTALL_STATUS {
                    return Err(NativeError::ApkInstallFailed);
                }
                let mut diagnostic = vec![0; size];
                self.read_exact(&mut diagnostic)?;
                Err(install_failure(&diagnostic))
            }
            _ => Err(NativeError::ApkInstallFailed),
        }
    }
}

fn install_failure(bytes: &[u8]) -> NativeError {
    match classify_tool_failure(bytes, &[]) {
        NativeError::ToolFailed => NativeError::ApkInstallFailed,
        known => known,
    }
}

fn install_on_stream(
    stream: TcpStream,
    serial: &str,
    bytes: &[u8],
    deadline: Instant,
) -> Result<(), NativeError> {
    crate::adb::validate_serial(serial)?;
    if bytes.is_empty() || bytes.len() as u64 > MAX_APK_BYTES {
        return Err(NativeError::UntrustedApk);
    }
    let mut socket = DeadlineSocket { stream, deadline };
    socket.service(&format!("host:transport:{serial}"))?;
    socket.service(&format!(
        "exec:cmd package install --user 0 -r -S {}",
        bytes.len()
    ))?;
    // Raw bytes never pass through the Windows CRT, a shell pipe, a pathname, or a staging file.
    socket.write(bytes)?;
    let mut output = Vec::new();
    let mut buffer = [0; 4096];
    loop {
        let count = socket.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        if output.len() + count > MAX_INSTALL_STATUS {
            return Err(NativeError::ApkInstallFailed);
        }
        output.extend_from_slice(&buffer[..count]);
    }
    let text = std::str::from_utf8(&output).map_err(|_| NativeError::ApkInstallFailed)?;
    // PackageManager's definitive final status, never exec-in's misleading successful exit.
    if text.trim() == "Success" {
        Ok(())
    } else {
        Err(install_failure(&output))
    }
}

pub(crate) fn install_verified_bytes(serial: &str, bytes: &[u8]) -> Result<(), NativeError> {
    let deadline = Instant::now() + Duration::from_secs(120);
    let address = std::net::SocketAddr::from(([127, 0, 0, 1], 5037));
    let stream = TcpStream::connect_timeout(&address, Duration::from_secs(5))
        .map_err(|_| NativeError::AdbServerUnavailable)?;
    install_on_stream(stream, serial, bytes, deadline)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{net::TcpListener, thread};

    fn service(stream: &mut TcpStream) -> String {
        let mut length = [0; 4];
        stream.read_exact(&mut length).unwrap();
        let size = usize::from_str_radix(std::str::from_utf8(&length).unwrap(), 16).unwrap();
        let mut bytes = vec![0; size];
        stream.read_exact(&mut bytes).unwrap();
        String::from_utf8(bytes).unwrap()
    }
    fn run(status: Vec<u8>, bytes: Vec<u8>) -> Result<(), NativeError> {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let stream = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let expected = bytes.clone();
        let phone = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(Duration::from_secs(2)))
                .unwrap();
            assert_eq!(service(&mut stream), "host:transport:ABC123");
            stream.write_all(b"OKAY").unwrap();
            assert_eq!(
                service(&mut stream),
                format!("exec:cmd package install --user 0 -r -S {}", expected.len())
            );
            stream.write_all(b"OKAY").unwrap();
            let mut received = vec![0; expected.len()];
            stream.read_exact(&mut received).unwrap();
            assert_eq!(
                received, expected,
                "Every binary byte must survive Windows text/control sequences"
            );
            stream.write_all(&status).unwrap();
        });
        let result = install_on_stream(
            stream,
            "ABC123",
            &bytes,
            Instant::now() + Duration::from_secs(2),
        );
        phone.join().unwrap();
        result
    }

    #[test]
    fn exact_primary_user_command_and_all_binary_bytes_require_definitive_success() {
        let bytes = (0..=255).cycle().take(150_000).collect();
        assert_eq!(run(b"Success\r\n".to_vec(), bytes), Ok(()));
    }
    #[test]
    fn empty_misleading_or_failed_status_never_reports_installation_success() {
        for status in [
            b"".as_slice(),
            b"Success-ish\n",
            b"Failure\nSuccess\n",
            b"Failure [INSTALL_FAILED_INVALID_APK]\n",
        ] {
            assert_eq!(
                run(status.to_vec(), vec![0, 10, 13, 26, 255]),
                Err(NativeError::ApkInstallFailed)
            );
        }
        assert_eq!(
            run(
                b"Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]\n".to_vec(),
                vec![1]
            ),
            Err(NativeError::ApkSignatureMismatch)
        );
    }
    #[test]
    fn status_output_is_bounded_before_unbounded_allocation() {
        assert_eq!(
            run(vec![b'x'; MAX_INSTALL_STATUS + 1], vec![1]),
            Err(NativeError::ApkInstallFailed)
        );
    }
    #[test]
    fn incomplete_adb_handshake_has_an_absolute_deadline() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let stream = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let peer = thread::spawn(move || {
            let (stream, _) = listener.accept().unwrap();
            thread::sleep(Duration::from_millis(250));
            drop(stream);
        });
        let started = Instant::now();
        assert_eq!(
            install_on_stream(stream, "ABC123", &[1], started + Duration::from_millis(75)),
            Err(NativeError::ToolTimedOut)
        );
        assert!(started.elapsed() < Duration::from_millis(200));
        peer.join().unwrap();
    }
    #[test]
    fn rejected_adb_transport_returns_only_a_sanitized_error_and_sends_no_apk() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let stream = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let peer = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            assert_eq!(service(&mut stream), "host:transport:ABC123");
            let message = b"device unauthorized; private synthetic path must not escape";
            stream
                .write_all(format!("FAIL{:04x}", message.len()).as_bytes())
                .unwrap();
            stream.write_all(message).unwrap();
            let mut byte = [0];
            assert_eq!(stream.read(&mut byte).unwrap(), 0);
        });
        assert_eq!(
            install_on_stream(
                stream,
                "ABC123",
                &[1],
                Instant::now() + Duration::from_secs(2)
            ),
            Err(NativeError::AdbUnauthorized)
        );
        peer.join().unwrap();
    }
}

//! Android 11+ authenticated Wireless debugging. Discovery is untrusted; explicit selection,
//! modern TLS-port checks and LocalSpoof's separate phone approval are all required.
use crate::adb::{validate_serial, Adb, NativeError};
use serde::Serialize;
use std::{
    io::{Read, Write},
    net::{IpAddr, SocketAddr, TcpStream},
    time::{Duration, Instant},
};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WirelessEndpoint(SocketAddr);
impl WirelessEndpoint {
    pub fn parse(value: &str) -> Result<Self, NativeError> {
        if value.len() > 96 || !value.is_ascii() || value.trim() != value || value.contains('%') {
            return Err(NativeError::InvalidWirelessEndpoint);
        }
        let address: SocketAddr = value
            .parse()
            .map_err(|_| NativeError::InvalidWirelessEndpoint)?;
        let private = match address.ip() {
            IpAddr::V4(ip) => ip.is_private() && !ip.is_broadcast() && !ip.is_unspecified(),
            // Scope IDs are deliberately unsupported; ULA is supported without guessing an interface.
            IpAddr::V6(ip) => (ip.segments()[0] & 0xfe00) == 0xfc00,
        };
        if !private || address.port() < 1024 || [5037, 5555].contains(&address.port()) {
            return Err(NativeError::InvalidWirelessEndpoint);
        }
        Ok(Self(address))
    }
    pub fn port(&self) -> u16 {
        self.0.port()
    }
    pub fn serial(&self) -> String {
        self.0.to_string()
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct WirelessService {
    pub instance: String,
    pub kind: String,
    pub endpoint: String,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct WirelessDevice {
    pub serial: String,
    pub model: String,
    pub state: String,
    pub identity: String,
}

pub fn parse_services(output: &str) -> Vec<WirelessService> {
    let mut services = Vec::new();
    for line in output.lines().take(512) {
        let columns: Vec<_> = line.split_whitespace().collect();
        if columns.len() != 3
            || columns[0].len() > 160
            || !columns[0]
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"-_.".contains(&b))
        {
            continue;
        }
        let kind = match columns[1].trim_end_matches('.') {
            "_adb-tls-pairing._tcp" => "PAIRING",
            "_adb-tls-connect._tcp" => "CONNECT",
            _ => continue,
        };
        let Ok(endpoint) = WirelessEndpoint::parse(columns[2]) else {
            continue;
        };
        let service = WirelessService {
            instance: columns[0].into(),
            kind: kind.into(),
            endpoint: endpoint.serial(),
        };
        if !services.contains(&service) {
            services.push(service);
        }
        if services.len() == 32 {
            break;
        }
    }
    services
}
// AOSP adb.cpp mandates STLS as the first reply to CNXN on the TLS daemon port.
// This small protocol probe sends no authentication credentials or commands and rejects
// AUTH/plain CNXN on any port; the SDK owns the actual paired TLS handshake afterwards.
fn remaining(deadline: Instant) -> Result<Duration, NativeError> {
    deadline
        .checked_duration_since(Instant::now())
        .filter(|d| !d.is_zero())
        .map(|d| d.min(Duration::from_secs(2)))
        .ok_or(NativeError::WirelessTlsRequired)
}
fn tls_probe(address: SocketAddr, deadline: Instant) -> Result<(), NativeError> {
    let mut stream = TcpStream::connect_timeout(&address, remaining(deadline)?)
        .map_err(|_| NativeError::WirelessTlsRequired)?;
    stream
        .set_write_timeout(Some(remaining(deadline)?))
        .map_err(|_| NativeError::WirelessTlsRequired)?;
    let banner = b"host::\0";
    let command = 0x4e584e43u32; // CNXN
    let mut request = Vec::with_capacity(31);
    for value in [
        command,
        0x01000001,
        4096,
        banner.len() as u32,
        banner.iter().map(|b| *b as u32).sum(),
        command ^ u32::MAX,
    ] {
        request.extend_from_slice(&value.to_le_bytes());
    }
    request.extend_from_slice(banner);
    stream
        .write_all(&request)
        .map_err(|_| NativeError::WirelessTlsRequired)?;
    stream
        .set_read_timeout(Some(remaining(deadline)?))
        .map_err(|_| NativeError::WirelessTlsRequired)?;
    let mut reply = [0u8; 24];
    // Read incrementally against one absolute deadline, preventing a slow byte stream
    // from extending the socket timeout indefinitely.
    let mut received = 0;
    while received < reply.len() {
        stream
            .set_read_timeout(Some(remaining(deadline)?))
            .map_err(|_| NativeError::WirelessTlsRequired)?;
        let count = stream
            .read(&mut reply[received..])
            .map_err(|_| NativeError::WirelessTlsRequired)?;
        if count == 0 {
            return Err(NativeError::WirelessTlsRequired);
        }
        received += count;
    }
    let word = |offset| u32::from_le_bytes(reply[offset..offset + 4].try_into().unwrap());
    if word(0) != 0x534c5453
        || word(4) != 0x01000000
        || word(8) != 0
        || word(12) != 0
        || word(16) != 0
        || word(20) != (0x534c5453 ^ u32::MAX)
    {
        return Err(NativeError::WirelessTlsRequired);
    }
    Ok(())
}
impl Adb {
    pub fn wireless_services(&self) -> Result<Vec<WirelessService>, NativeError> {
        Ok(parse_services(
            &self.execute(&["mdns", "services"], Duration::from_secs(5))?,
        ))
    }
    pub fn wireless_devices(&self) -> Result<Vec<WirelessDevice>, NativeError> {
        let output = self.execute(&["devices", "-l"], Duration::from_secs(5))?;
        let mut devices = Vec::new();
        let deadline = Instant::now() + Duration::from_secs(20);
        // Only already explicitly connected literal endpoints, never guessed hostnames/mDNS IDs.
        for line in output.lines().take(128) {
            let fields: Vec<_> = line.split_whitespace().collect();
            if fields.len() < 2 || fields[1] != "device" {
                continue;
            }
            let Ok(endpoint) = WirelessEndpoint::parse(fields[0]) else {
                continue;
            };
            if Instant::now() >= deadline {
                break;
            }
            if let Ok(device) = self.verify_wireless_before(&endpoint.serial(), deadline) {
                devices.push(device);
            }
            if devices.len() == 8 {
                break;
            }
        }
        Ok(devices)
    }
    pub fn pair_wireless(&self, endpoint: &str, code: &str) -> Result<(), NativeError> {
        let endpoint = WirelessEndpoint::parse(endpoint)?;
        if code.len() != 6 || !code.bytes().all(|b| b.is_ascii_digit()) {
            return Err(NativeError::WirelessPairingFailed);
        }
        // The OS pairing secret is stdin only: absent from process arguments and public errors.
        let output = self
            .execute_with_input(
                &["pair", &endpoint.serial()],
                Duration::from_secs(20),
                Some(format!("{code}\n").into_bytes()),
            )
            .map_err(|_| NativeError::WirelessPairingFailed)?;
        if !output.contains("Successfully paired to") {
            return Err(NativeError::WirelessPairingFailed);
        }
        Ok(())
    }
    pub fn connect_wireless(&self, endpoint: &str) -> Result<WirelessDevice, NativeError> {
        let endpoint = WirelessEndpoint::parse(endpoint)?;
        // Reject a genuine legacy socket before asking the shared SDK to connect/authenticate.
        // A separate preflight is not a cryptographic attestation of a hostile endpoint;
        // the SDK owns TLS trust and phone comparison approval owns controller authority.
        tls_probe(endpoint.0, Instant::now() + Duration::from_secs(4))?;
        let output = self
            .execute(&["connect", &endpoint.serial()], Duration::from_secs(15))
            .map_err(|_| NativeError::WirelessConnectFailed)?;
        if !(output.contains("connected to") || output.contains("already connected to")) {
            return Err(NativeError::WirelessConnectFailed);
        }
        self.verify_wireless(&endpoint.serial())
    }
    pub(crate) fn verify_wireless(&self, endpoint: &str) -> Result<WirelessDevice, NativeError> {
        self.verify_wireless_before(endpoint, Instant::now() + Duration::from_secs(12))
    }
    fn verify_wireless_before(
        &self,
        endpoint: &str,
        deadline: Instant,
    ) -> Result<WirelessDevice, NativeError> {
        let endpoint = WirelessEndpoint::parse(endpoint)?;
        tls_probe(endpoint.0, deadline)?;
        let serial = endpoint.serial();
        let property = |name| {
            self.execute(
                &["-s", &serial, "shell", "getprop", name],
                remaining(deadline)?,
            )
            .map(|s| s.trim().to_owned())
            .map_err(|_| NativeError::WirelessTlsRequired)
        };
        let sdk = property("ro.build.version.sdk")?
            .parse::<u32>()
            .map_err(|_| NativeError::WirelessTlsRequired)?;
        if sdk < 30
            || property("persist.adb.tls_server.enable")? != "1"
            || property("service.adb.tls.port")?.parse::<u16>().ok() != Some(endpoint.port())
        {
            return Err(NativeError::WirelessTlsRequired);
        }
        let identity = property("ro.serialno")?;
        validate_serial(&identity).map_err(|_| NativeError::WirelessTlsRequired)?;
        let model = property("ro.product.model")?;
        if model.len() > 128
            || !model
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b" -_.".contains(&b))
        {
            return Err(NativeError::WirelessTlsRequired);
        }
        Ok(WirelessDevice {
            serial,
            model,
            state: "device".into(),
            identity,
        })
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn tls_probe_rejects_legacy_auth_plain_connection_and_malformed_headers() {
        use std::{net::TcpListener, thread};
        for (command, version, length, accepted) in [
            (0x534c5453u32, 0x01000000u32, 0u32, true),
            (0x48545541, 0x01000000, 0, false),
            (0x4e584e43, 0x01000001, 0, false),
            (0x534c5453, 0x01000000, 999, false),
        ] {
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            let address = listener.local_addr().unwrap();
            let peer = thread::spawn(move || {
                let (mut stream, _) = listener.accept().unwrap();
                let mut request = [0u8; 31];
                stream.read_exact(&mut request).unwrap();
                assert_eq!(&request[..4], b"CNXN");
                assert_eq!(&request[24..], b"host::\0");
                let mut header = Vec::new();
                for value in [command, version, 0, length, 0, command ^ u32::MAX] {
                    header.extend_from_slice(&value.to_le_bytes());
                }
                stream.write_all(&header).unwrap();
            });
            assert_eq!(
                tls_probe(address, Instant::now() + Duration::from_secs(2)).is_ok(),
                accepted
            );
            peer.join().unwrap();
        }
        assert!(remaining(Instant::now() - Duration::from_millis(1)).is_err());
    }
    #[test]
    fn accepts_private_literals_only() {
        for value in [
            "192.168.1.5:37129",
            "10.1.2.3:32000",
            "172.20.1.1:65535",
            "[fd01::12]:33001",
        ] {
            assert!(WirelessEndpoint::parse(value).is_ok(), "{value}");
        }
        for value in [
            "127.0.0.1:32000",
            "0.0.0.0:32000",
            "8.8.8.8:32000",
            "192.168.1.5:5555",
            "192.168.1.5:5037",
            "192.168.1.5:80",
            "host.local:37129",
            "-s",
            "[::1]:33001",
            "[::ffff:192.168.1.5]:33001",
            "[fe80::1%7]:33001",
            "192.168.1.5:33001\n",
        ] {
            assert!(WirelessEndpoint::parse(value).is_err(), "{value}");
        }
    }
    #[test]
    fn only_modern_bounded_service_ads_are_choices() {
        let input="List of discovered mdns services\nadb-ABC-xyz _adb-tls-connect._tcp. 192.168.1.5:33001\nadb-ABC _adb-tls-pairing._tcp 192.168.1.5:37001\nadb-ABC _adb._tcp 192.168.1.5:5555\nevil _adb-tls-connect._tcp 8.8.8.8:33001\nadb-ABC-xyz _adb-tls-connect._tcp. 192.168.1.5:33001";
        let result = parse_services(input);
        assert_eq!(result.len(), 2);
        assert_eq!(result[0].kind, "CONNECT");
        assert_eq!(result[1].kind, "PAIRING");
    }
}

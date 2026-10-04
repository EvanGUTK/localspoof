use crate::{
    adb::{Adb, NativeError},
    framing::{read_frame, write_frame},
    radio::RadioOwnership,
};
use rand::{rngs::OsRng, RngCore};
use serde::Deserialize;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    net::{Shutdown, TcpStream},
    time::{Duration, Instant},
};

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PhoneRequest {
    pub r#type: String,
    #[serde(default)]
    pub payload: Value,
    pub expected_revision: Option<u64>,
}

fn random_id() -> String {
    let mut bytes = [0u8; 16];
    OsRng.fill_bytes(&mut bytes);
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn validate_hello(response: &Value) -> Result<Value, NativeError> {
    if response.get("ok") != Some(&Value::Bool(true)) {
        return Err(NativeError::PhoneControlUnavailable);
    }
    let payload = response
        .get("payload")
        .and_then(Value::as_object)
        .ok_or(NativeError::PhoneProtocolMismatch)?;
    if !response.as_object().is_some_and(|object| {
        object
            .keys()
            .all(|key| ["ok", "payload"].contains(&key.as_str()))
    }) {
        return Err(NativeError::PhoneProtocolMismatch);
    }
    if payload.get("protocolMajor").and_then(Value::as_u64) != Some(1)
        || payload
            .get("protocolMinor")
            .and_then(Value::as_u64)
            .is_none_or(|minor| minor < 6)
        || payload.get("approvalPairing").and_then(Value::as_bool) != Some(true)
        || payload.get("livePhoneLocation").and_then(Value::as_bool) != Some(true)
        || payload.get("flightLegOptions").and_then(Value::as_bool) != Some(true)
        || payload.get("liveJourneyControls").and_then(Value::as_bool) != Some(true)
        || payload.get("routeStops").and_then(Value::as_bool) != Some(true)
        || payload.get("motionTelemetry").and_then(Value::as_bool) != Some(true)
        || payload.get("variableCruise").and_then(Value::as_bool) != Some(true)
        || payload.get("mappedRoadLimits").and_then(Value::as_bool) != Some(true)
        || [
            "remainingJourneyEdits",
            "timedPause",
            "vacationFuelStops",
            "hotelStages",
            "vacationCorrections",
        ]
        .iter()
        .any(|key| payload.get(*key).and_then(Value::as_bool) != Some(true))
        || payload.get("frameworkMock").and_then(Value::as_bool) != Some(true)
        || payload.get("pairingRequired").and_then(Value::as_bool) != Some(true)
        || !payload
            .get("maxFrameBytes")
            .and_then(Value::as_u64)
            .is_some_and(|size| (1024..=crate::framing::MAX_FRAME_BYTES as u64).contains(&size))
    {
        return Err(NativeError::PhoneProtocolMismatch);
    }
    for key in [
        "fusedMock",
        "guidedAirplane",
        "usbAirplane",
        "onlineRouteGeometry",
        "onlineFlightGroundRoutes",
    ] {
        if payload.get(key).is_some_and(|value| !value.is_boolean()) {
            return Err(NativeError::PhoneProtocolMismatch);
        }
    }
    for (key, maximum) in [
        ("maxPackBytes", 33_554_432u64),
        ("maxOnlineRoutePoints", 12_000u64),
    ] {
        if payload.get(key).is_some_and(|value| {
            !value
                .as_u64()
                .is_some_and(|limit| (1..=maximum).contains(&limit))
        }) {
            return Err(NativeError::PhoneProtocolMismatch);
        }
    }
    // Copy only public known capabilities; an unauthenticated peer cannot attach session data
    // or arbitrary fields to a successful desktop connection response.
    let keys = [
        "protocolMajor",
        "protocolMinor",
        "frameworkMock",
        "fusedMock",
        "guidedAirplane",
        "usbAirplane",
        "maxFrameBytes",
        "maxPackBytes",
        "pairingRequired",
        "approvalPairing",
        "livePhoneLocation",
        "flightLegOptions",
        "liveJourneyControls",
        "routeStops",
        "motionTelemetry",
        "variableCruise",
        "mappedRoadLimits",
        "onlineRouteGeometry",
        "onlineFlightGroundRoutes",
        "maxOnlineRoutePoints",
        "remainingJourneyEdits",
        "timedPause",
        "vacationFuelStops",
        "vacationStops",
        "hotelStages",
        "vacationCorrections",
    ];
    Ok(Value::Object(
        payload
            .iter()
            .filter(|(key, _)| keys.contains(&key.as_str()))
            .map(|(key, value)| (key.clone(), value.clone()))
            .collect(),
    ))
}

fn valid_pair_id(value: &str) -> bool {
    value.len() == 32
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn comparison_code(nonce: &str, pairing_id: &str) -> String {
    let digest = Sha256::digest(format!("localspoof-pair-v2:{nonce}:{pairing_id}").as_bytes());
    let number = u32::from_be_bytes([digest[0], digest[1], digest[2], digest[3]]) % 1_000_000;
    format!("{number:06}")
}

fn validate_pair_response(response: &Value, nonce: &str) -> Result<(String, String), NativeError> {
    let id = response
        .pointer("/payload/pairingId")
        .and_then(Value::as_str)
        .filter(|id| valid_pair_id(id))
        .ok_or(NativeError::InvalidFrame)?;
    let comparison = comparison_code(nonce, id);
    if response
        .pointer("/payload/comparisonCode")
        .and_then(Value::as_str)
        != Some(comparison.as_str())
        || response
            .pointer("/payload/pending")
            .and_then(Value::as_bool)
            != Some(true)
    {
        return Err(NativeError::InvalidFrame);
    }
    Ok((id.to_owned(), comparison))
}

fn approved_credentials(response: &Value) -> Result<(String, String), NativeError> {
    let token = response
        .pointer("/payload/token")
        .and_then(Value::as_str)
        .filter(|token| valid_pair_id(token))
        .ok_or(NativeError::InvalidFrame)?;
    let session = response
        .get("sessionId")
        .and_then(Value::as_str)
        .filter(|session| valid_pair_id(session))
        .ok_or(NativeError::InvalidFrame)?;
    if response.pointer("/payload/paired").and_then(Value::as_bool) != Some(true) {
        return Err(NativeError::InvalidFrame);
    }
    Ok((token.to_owned(), session.to_owned()))
}

struct OwnedForward<'a> {
    adb: &'a Adb,
    serial: &'a str,
    port: u16,
    transferred: bool,
}
impl Drop for OwnedForward<'_> {
    fn drop(&mut self) {
        if !self.transferred {
            let _ = self.adb.device(
                self.serial,
                &["forward", "--remove", &format!("tcp:{}", self.port)],
            );
        }
    }
}

pub fn validate_request(request: &PhoneRequest) -> Result<(), NativeError> {
    if ![
        "PreparePlan",
        "CommitPlan",
        "Start",
        "Pause",
        "Resume",
        "Stop",
        "Snapshot",
        "Heartbeat",
        "Airports",
        "Places",
        "ImportGeoPack",
        "BeginGeoPack",
        "GeoPackChunk",
        "CommitGeoPack",
        "SetSpeeds",
        "JumpToNextStop",
        "BeginRemainingEdit",
        "PrepareRemainingPlan",
        "ApplyRemainingPlan",
        "CancelRemainingEdit",
        "TimedPause",
        "LeaveStop",
        "UpdateVacation",
    ]
    .contains(&request.r#type.as_str())
    {
        return Err(NativeError::InvalidRequest);
    }
    if !request.payload.is_null() && !request.payload.is_object() {
        return Err(NativeError::InvalidRequest);
    }
    if serde_json::to_vec(&request.payload)
        .map_err(|_| NativeError::InvalidRequest)?
        .len()
        > 1_000_000
    {
        return Err(NativeError::InvalidRequest);
    }
    let is_read =
        ["Snapshot", "Heartbeat", "Airports", "Places", "Stop"].contains(&request.r#type.as_str());
    if !is_read && request.expected_revision.is_none() {
        return Err(NativeError::StaleRevision);
    }
    if request.r#type == "SetSpeeds" {
        let values = request
            .payload
            .as_object()
            .ok_or(NativeError::InvalidRequest)?;
        if values.is_empty()
            || values.iter().any(|(key, value)| match key.as_str() {
                "drivingSpeedMode" => !value
                    .as_str()
                    .is_some_and(|v| ["FIXED", "VARIABLE"].contains(&v)),
                "followRoadLimits" => !value.is_boolean(),
                _ => {
                    let bounds = match key.as_str() {
                        "walkMps" => (0.1, 3.0),
                        "driveMps" | "driveMinMps" | "driveMaxMps" => (0.1, 56.0),
                        "flightMps" => (30.0, 350.0),
                        _ => return true,
                    };
                    !value
                        .as_f64()
                        .is_some_and(|n| n.is_finite() && (bounds.0..=bounds.1).contains(&n))
                }
            })
            || values
                .get("driveMinMps")
                .and_then(Value::as_f64)
                .zip(values.get("driveMaxMps").and_then(Value::as_f64))
                .is_some_and(|(min, max)| min > max)
        {
            return Err(NativeError::InvalidRequest);
        }
    }

    if [
        "BeginRemainingEdit",
        "PrepareRemainingPlan",
        "ApplyRemainingPlan",
        "CancelRemainingEdit",
        "TimedPause",
        "LeaveStop",
    ]
    .contains(&request.r#type.as_str())
    {
        let v = request
            .payload
            .as_object()
            .ok_or(NativeError::InvalidRequest)?;
        let valid_id = |key: &str| {
            v.get(key).and_then(Value::as_str).is_some_and(|id| {
                !id.is_empty() && id.len() <= 128 && !id.chars().any(char::is_control)
            })
        };
        let valid = match request.r#type.as_str() {
            "BeginRemainingEdit" => {
                v.keys().all(|k| k == "useUndo") && v.get("useUndo").is_none_or(Value::is_boolean)
            }
            "PrepareRemainingPlan" => {
                v.len() == 2 && valid_id("editId") && v.get("request").is_some_and(Value::is_object)
            }
            "ApplyRemainingPlan" => v.len() == 2 && valid_id("editId") && valid_id("preparedId"),
            "CancelRemainingEdit" => v.len() == 1 && valid_id("editId"),
            "TimedPause" => {
                v.len() == 1
                    && v.get("durationSeconds")
                        .and_then(Value::as_u64)
                        .is_some_and(|n| [60, 300, 900].contains(&n))
            }
            "LeaveStop" => v.is_empty(),
            _ => false,
        };
        if !valid {
            return Err(NativeError::InvalidRequest);
        }
    }

    if request.r#type == "UpdateVacation" {
        let v = request
            .payload
            .as_object()
            .ok_or(NativeError::InvalidRequest)?;
        if v.is_empty()
            || v.iter().any(|(key, value)| {
                let (min, max) = match key.as_str() {
                    "remainingFuelLiters" | "reserveLiters" => (0.0, 2000.0),
                    "litersPer100Km" => (0.1, 200.0),
                    _ => return true,
                };
                !value
                    .as_f64()
                    .is_some_and(|n| n.is_finite() && (min..=max).contains(&n))
            })
        {
            return Err(NativeError::InvalidRequest);
        }
    }
    if request.r#type == "JumpToNextStop"
        && !request.payload.as_object().is_some_and(|v| v.is_empty())
    {
        return Err(NativeError::InvalidRequest);
    }
    Ok(())
}

struct Connection {
    adb: Adb,
    serial: String,
    port: u16,
    stream: TcpStream,
    sequence: u64,
    session_id: String,
    token: Option<String>,
    pending_pair: Option<(String, String, Instant)>,
    revision: u64,
    usb_radio_available: bool,
    pending_usb_plan: bool,
    usb_plan: bool,
    radio: Option<RadioOwnership>,
}

impl Connection {
    fn exchange(
        &mut self,
        kind: &str,
        payload: Value,
        expected_revision: Option<u64>,
    ) -> Result<Value, NativeError> {
        self.sequence = self
            .sequence
            .checked_add(1)
            .ok_or(NativeError::Disconnected)?;
        let frame = json!({"protocolMajor":1,"protocolMinor":6,"sessionId":self.session_id,"commandId":random_id(),"sequence":self.sequence,"expectedRevision":expected_revision.unwrap_or(self.revision),"type":kind,"payload":payload,"token":self.token});
        write_frame(&mut self.stream, &frame)?;
        let result = read_frame(&mut self.stream)?;
        if !result.get("ok").is_some_and(Value::is_boolean) {
            return Err(NativeError::InvalidFrame);
        }
        if let Some(revision) = result.get("revision").and_then(Value::as_u64) {
            self.revision = revision;
        }
        Ok(result)
    }

    fn request(&mut self, request: PhoneRequest) -> Result<Value, NativeError> {
        if self.token.is_none() {
            return Err(NativeError::PairingRequired);
        }
        validate_request(&request)?;
        if let Some(revision) = request.expected_revision {
            if revision != self.revision && request.r#type != "Stop" {
                return Err(NativeError::StaleRevision);
            }
        }
        let usb_plan = request.payload.get("radioPolicy").and_then(Value::as_str) == Some("USB");
        if request.r#type == "PreparePlan" && usb_plan && !self.usb_radio_available {
            return Err(NativeError::CapabilityUnavailable);
        }
        let result = self.exchange(&request.r#type, request.payload, request.expected_revision)?;
        if result.get("ok") == Some(&Value::Bool(true)) {
            match request.r#type.as_str() {
                "PreparePlan" => self.pending_usb_plan = usb_plan,
                "CommitPlan" => {
                    self.usb_plan = self.pending_usb_plan;
                    self.radio = None;
                }
                "Stop" => {
                    // Stop must reach the phone before teardown. Never silently restore radios here:
                    // a stop/phone override can invalidate ownership; present manual recovery instead.
                    self.token = None;
                    self.pending_pair = None;
                    self.radio = None;
                    self.usb_plan = false;
                }
                _ => {}
            }
        }
        Ok(result)
    }
}

impl Drop for Connection {
    fn drop(&mut self) {
        let _ = self.stream.shutdown(Shutdown::Both);
        let _ = self.adb.device(
            &self.serial,
            &["forward", "--remove", &format!("tcp:{}", self.port)],
        );
        self.token = None;
        self.pending_pair = None;
    }
}

#[derive(Default)]
pub struct Bridge {
    connection: Option<Connection>,
    pairing_target: Option<(String, String)>,
}

impl Bridge {
    pub fn connect(&mut self, adb_path: &str, serial: &str) -> Result<Value, NativeError> {
        // Reject reconnect while a controller is live; disconnect only this application's tunnel.
        if self.connection.is_some() {
            return Err(NativeError::InvalidRequest);
        }
        let adb = Adb::new(adb_path)?;
        crate::adb::validate_serial(serial)?;
        let devices = adb.devices()?;
        let device = devices
            .iter()
            .find(|device| device.serial == serial)
            .ok_or(NativeError::InvalidDevice)?;
        match device.state.as_str() {
            "device" => {}
            "unauthorized" => return Err(NativeError::AdbUnauthorized),
            "offline" => return Err(NativeError::AdbOffline),
            _ => return Err(NativeError::InvalidDevice),
        }
        adb.require_usb(serial)?;
        let port_text = adb.device(
            serial,
            &["forward", "tcp:0", "localabstract:localspoof.control.v1"],
        )?;
        let port = port_text
            .trim()
            .parse::<u16>()
            .map_err(|_| NativeError::ToolFailed)?;
        if port == 0 {
            return Err(NativeError::ToolFailed);
        }
        let mut forward = OwnedForward {
            adb: &adb,
            serial,
            port,
            transferred: false,
        };
        let stream = TcpStream::connect_timeout(
            &format!("127.0.0.1:{port}")
                .parse()
                .map_err(|_| NativeError::ToolFailed)?,
            Duration::from_secs(5),
        )
        .map_err(|_| NativeError::PhoneControlUnavailable)?;
        stream
            .set_read_timeout(Some(Duration::from_secs(5)))
            .map_err(|_| NativeError::PhoneControlUnavailable)?;
        stream
            .set_write_timeout(Some(Duration::from_secs(5)))
            .map_err(|_| NativeError::PhoneControlUnavailable)?;
        stream
            .set_nodelay(true)
            .map_err(|_| NativeError::PhoneControlUnavailable)?;
        // Transfer cleanup ownership before moving Adb into Connection. Every later failure
        // drops the temporary Connection and removes its exact owned forward.
        forward.transferred = true;
        drop(forward);
        let mut connection = Connection {
            adb,
            serial: serial.to_owned(),
            port,
            stream,
            sequence: 0,
            session_id: String::new(),
            token: None,
            pending_pair: None,
            revision: 0,
            usb_radio_available: false,
            pending_usb_plan: false,
            usb_plan: false,
            radio: None,
        };
        let hello = connection
            .exchange("Hello", json!({}), None)
            .map_err(|_| NativeError::PhoneControlUnavailable)?;
        let capabilities = validate_hello(&hello)?;
        let usb_radio_available = connection.adb.airplane_state(serial).is_ok();
        connection.usb_radio_available = usb_radio_available;
        self.connection = Some(connection);
        self.pairing_target = Some((adb_path.to_owned(), serial.to_owned()));
        Ok(
            json!({"connected":true,"usbAirplaneAvailable":usb_radio_available,"phoneStartRequired":true,"capabilities":capabilities}),
        )
    }

    /// Request explicit approval on the phone. Nonces and credentials never enter the renderer.
    pub fn pair(&mut self) -> Result<Value, NativeError> {
        if self
            .connection
            .as_ref()
            .is_some_and(|connection| connection.token.is_some())
        {
            return Err(NativeError::InvalidRequest);
        }
        // An explicit retry gets a fresh, independently verified USB channel and nonce.
        // The first request uses the Hello-verified connection just opened by connect().
        if self
            .connection
            .as_ref()
            .is_none_or(|connection| connection.pending_pair.is_some())
        {
            let (adb_path, serial) = self
                .pairing_target
                .clone()
                .ok_or(NativeError::NotConnected)?;
            self.connection = None;
            self.connect(&adb_path, &serial)?;
        }
        let connection = self.connection.as_mut().ok_or(NativeError::NotConnected)?;
        let nonce = random_id();
        let requested_at = Instant::now();
        let result = connection.exchange("PairRequest", json!({"clientNonce":nonce}), None);
        let result = match result {
            Ok(result) => result,
            Err(error) => {
                self.connection = None;
                return Err(error);
            }
        };
        if result.get("ok") != Some(&Value::Bool(true)) {
            // Leave retries explicit; don't silently restore approval or repeatedly prompt the phone.
            self.connection = None;
            return Ok(json!({"ok":false,"error":"PAIRING_REJECTED"}));
        }
        let validated = validate_pair_response(&result, &nonce);
        let (pairing_id, comparison) = match validated {
            Ok(validated) => validated,
            Err(error) => {
                self.connection = None;
                return Err(error);
            }
        };
        self.connection
            .as_mut()
            .ok_or(NativeError::NotConnected)?
            .pending_pair = Some((pairing_id.clone(), nonce, requested_at));
        Ok(
            json!({"ok":true,"payload":{"pairingId":pairing_id,"comparisonCode":comparison,"pending":true}}),
        )
    }

    pub fn poll_pair(&mut self) -> Result<Value, NativeError> {
        let connection = self.connection.as_mut().ok_or(NativeError::NotConnected)?;
        let (id, nonce, created) = connection
            .pending_pair
            .as_ref()
            .ok_or(NativeError::PairingRequired)?;
        if created.elapsed() > Duration::from_secs(120) {
            self.connection = None;
            return Err(NativeError::PairingRequired);
        }
        let payload = json!({"pairingId":id,"clientNonce":nonce});
        let result = match connection.exchange("PollPair", payload, None) {
            Ok(result) => result,
            Err(error) => {
                self.connection = None;
                return Err(error);
            }
        };
        if result.get("ok") != Some(&Value::Bool(true)) {
            let pending = result.get("error").and_then(Value::as_str) == Some("PAIRING_PENDING");
            if !pending {
                self.connection = None;
            }
            return Ok(
                json!({"ok":false,"error":if pending {"PAIRING_PENDING"} else {"PAIRING_REJECTED"}}),
            );
        }
        let credentials = approved_credentials(&result);
        let (token, session_id) = match credentials {
            Ok(credentials) => credentials,
            Err(error) => {
                self.connection = None;
                return Err(error);
            }
        };
        let connection = self.connection.as_mut().ok_or(NativeError::NotConnected)?;
        connection.token = Some(token);
        connection.session_id = session_id;
        connection.pending_pair = None;
        // Only public pairing status reaches the renderer, including against an unexpected peer.
        Ok(
            json!({"ok":true,"payload":{"paired":true,"usbAirplaneAvailable":connection.usb_radio_available}}),
        )
    }

    pub fn request(&mut self, request: PhoneRequest) -> Result<Value, NativeError> {
        let result = self
            .connection
            .as_mut()
            .ok_or(NativeError::NotConnected)?
            .request(request);
        if matches!(
            result,
            Err(NativeError::Disconnected | NativeError::InvalidFrame)
        ) {
            self.connection = None;
        }
        result
    }

    pub fn set_airplane(&mut self, enabled: bool, phase_id: &str) -> Result<Value, NativeError> {
        if phase_id.is_empty() || phase_id.len() > 128 {
            return Err(NativeError::RadioPhaseMismatch);
        }
        let connection = self.connection.as_mut().ok_or(NativeError::NotConnected)?;
        if connection.token.is_none() {
            return Err(NativeError::PairingRequired);
        }
        if !connection.usb_radio_available || !connection.usb_plan {
            return Err(NativeError::CapabilityUnavailable);
        }
        let snapshot = connection.exchange("Snapshot", json!({}), None)?;
        let requested_id = snapshot
            .pointer("/payload/radioRequest/phaseId")
            .and_then(Value::as_str);
        let requested_state = snapshot
            .pointer("/payload/radioRequest/enabled")
            .and_then(Value::as_bool);
        if snapshot.get("ok") != Some(&Value::Bool(true))
            || requested_id != Some(phase_id)
            || requested_state != Some(enabled)
        {
            return Err(NativeError::RadioPhaseMismatch);
        }
        let observed = connection.adb.airplane_state(&connection.serial)?;
        if connection.radio.is_none() {
            connection.radio = Some(RadioOwnership::new(observed));
        }
        let radio = connection
            .radio
            .as_mut()
            .ok_or(NativeError::RadioNotOwned)?;
        radio.observe(observed);
        if !enabled && radio.desired_restore() != Some(false) {
            return Err(NativeError::RadioNotOwned);
        }
        let initial = radio.initial();
        if observed != enabled {
            connection.adb.airplane_set(&connection.serial, enabled)?;
        }
        radio.applied(enabled);
        connection.exchange("RadioPhaseResult", json!({"phaseId":phase_id,"enabled":enabled,"initialAirplaneState":initial,"changedByController":initial != enabled}), None)
    }

    pub fn disconnect(&mut self) {
        self.pairing_target = None;
        self.connection = None;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hello() -> Value {
        json!({"ok":true,"payload":{"protocolMajor":1,"protocolMinor":6,"approvalPairing":true,"livePhoneLocation":true,"flightLegOptions":true,"liveJourneyControls":true,"routeStops":true,"motionTelemetry":true,"variableCruise":true,"mappedRoadLimits":true,"frameworkMock":true,"pairingRequired":true,"maxFrameBytes":1048576,"onlineRouteGeometry":true,"remainingJourneyEdits":true,"timedPause":true,"vacationFuelStops":true,"hotelStages":true,"vacationCorrections":true}})
    }

    #[test]
    fn live_controls_are_revision_bound_and_payloads_are_narrow() {
        let command = |kind: &str, payload: Value, revision| PhoneRequest {
            r#type: kind.into(),
            payload,
            expected_revision: revision,
        };
        assert!(validate_request(&command(
            "SetSpeeds",
            json!({"driveMps":20.0,"flightMps":230.0}),
            Some(3)
        ))
        .is_ok());
        assert!(matches!(
            validate_request(&command("SetSpeeds", json!({"walkMps":1.4}), None)),
            Err(NativeError::StaleRevision)
        ));
        for payload in [
            json!({}),
            json!({"token":"forged"}),
            json!({"driveMps":99}),
            json!({"walkMps":4}),
            json!({"flightMps":0}),
        ] {
            assert!(validate_request(&command("SetSpeeds", payload, Some(3))).is_err());
        }
        assert!(validate_request(&command("JumpToNextStop", json!({}), Some(3))).is_ok());
        assert!(validate_request(&command(
            "JumpToNextStop",
            json!({"phaseId":"forged"}),
            Some(3)
        ))
        .is_err());
    }
    #[test]
    fn remaining_edits_require_revision_and_narrow_payloads() {
        let request = |kind: &str, payload: Value, revision| PhoneRequest {
            r#type: kind.into(),
            payload,
            expected_revision: revision,
        };
        for (kind, payload) in [
            ("BeginRemainingEdit", json!({"useUndo":true})),
            (
                "PrepareRemainingPlan",
                json!({"editId":"edit","request":{"mode":"DRIVE"}}),
            ),
            (
                "ApplyRemainingPlan",
                json!({"editId":"edit","preparedId":"prepared"}),
            ),
            ("CancelRemainingEdit", json!({"editId":"edit"})),
            ("TimedPause", json!({"durationSeconds":300})),
            ("LeaveStop", json!({})),
        ] {
            assert!(validate_request(&request(kind, payload.clone(), Some(7))).is_ok());
            assert!(validate_request(&request(kind, payload, None)).is_err());
        }
        for (kind, payload) in [
            ("TimedPause", json!({"durationSeconds":2})),
            (
                "ApplyRemainingPlan",
                json!({"editId":"edit","preparedId":"p","token":"forged"}),
            ),
            ("BeginRemainingEdit", json!({"useUndo":1})),
            ("LeaveStop", json!({"skipFuel":true})),
        ] {
            assert!(validate_request(&request(kind, payload, Some(7))).is_err());
        }
    }

    #[test]
    fn variable_cruise_accepts_125_mph_and_rejects_invalid_controls() {
        let request = |payload| PhoneRequest {
            r#type: "SetSpeeds".into(),
            payload,
            expected_revision: Some(7),
        };
        assert!(validate_request(&request(json!({"drivingSpeedMode":"VARIABLE", "driveMinMps":40.2336, "driveMaxMps":55.88, "followRoadLimits":true}))).is_ok());
        assert!(validate_request(&request(json!({"driveMps":55.88}))).is_ok());
        for payload in [
            json!({"driveMps":56.1}),
            json!({"driveMinMps":50,"driveMaxMps":40}),
            json!({"drivingSpeedMode":"RANDOM"}),
            json!({"followRoadLimits":1}),
            json!({"driveMaxMps":null}),
            json!({"followRoadLimits":true,"token":"forged"}),
        ] {
            assert!(validate_request(&request(payload)).is_err());
        }
    }

    #[test]
    fn motion_peer_requires_explicit_minor_five_capabilities() {
        let mut peer = hello();
        peer["payload"]["protocolMinor"] = json!(4);
        assert!(validate_hello(&peer).is_err());
        for key in ["motionTelemetry", "variableCruise", "mappedRoadLimits"] {
            let mut peer = hello();
            peer["payload"][key] = json!(false);
            assert!(validate_hello(&peer).is_err());
        }
    }

    #[test]
    fn compatible_approval_hello_filters_unknown_payload_fields() {
        let mut response = hello();
        response["payload"]["syntheticPrivateField"] = json!("must not reach UI");
        let public = validate_hello(&response).unwrap();
        assert_eq!(public["protocolMajor"], json!(1));
        assert!(public.get("syntheticPrivateField").is_none());
    }

    #[test]
    fn malformed_incompatible_or_session_disclosing_hello_is_rejected() {
        for (path, value) in [
            ("protocolMajor", json!(2)),
            ("protocolMinor", json!(3)),
            ("liveJourneyControls", json!(false)),
            ("routeStops", json!(false)),
            ("livePhoneLocation", json!(false)),
            ("flightLegOptions", json!(false)),
            ("approvalPairing", json!(false)),
            ("maxFrameBytes", json!(0)),
            ("maxFrameBytes", json!(2_000_000)),
            ("pairingRequired", json!(false)),
            ("onlineRouteGeometry", json!("true")),
            ("maxOnlineRoutePoints", json!(12_001)),
        ] {
            let mut response = hello();
            response["payload"][path] = value;
            assert_eq!(
                validate_hello(&response),
                Err(NativeError::PhoneProtocolMismatch)
            );
        }
        let mut response = hello();
        response["sessionId"] = json!("must not be public before pairing");
        assert_eq!(
            validate_hello(&response),
            Err(NativeError::PhoneProtocolMismatch)
        );
        assert_eq!(
            validate_hello(&json!({"ok":false})),
            Err(NativeError::PhoneControlUnavailable)
        );
    }

    #[test]
    fn pairing_comparison_matches_cross_platform_vector_and_rejects_tampering() {
        let nonce = "a".repeat(32);
        let id = "b".repeat(32);
        assert_eq!(comparison_code(&nonce, &id), "508163");
        let mut response = json!({"ok":true,"payload":{"pairingId":id,"comparisonCode":comparison_code(&nonce, &id),"pending":true}});
        assert!(validate_pair_response(&response, &nonce).is_ok());
        response["payload"]["comparisonCode"] = json!("000000");
        assert_eq!(
            validate_pair_response(&response, &nonce),
            Err(NativeError::InvalidFrame)
        );
        response["payload"]["pairingId"] = json!("B".repeat(32));
        assert_eq!(
            validate_pair_response(&response, &nonce),
            Err(NativeError::InvalidFrame)
        );
    }

    #[test]
    fn approval_requires_complete_rotated_native_credentials() {
        let mut response = json!({"ok":true,"sessionId":"b".repeat(32),"payload":{"token":"a".repeat(32),"paired":true}});
        assert!(approved_credentials(&response).is_ok());
        response["sessionId"] = json!("");
        assert_eq!(
            approved_credentials(&response),
            Err(NativeError::InvalidFrame)
        );
        response["sessionId"] = json!("b".repeat(32));
        response["payload"]["token"] = json!("A".repeat(32));
        assert_eq!(
            approved_credentials(&response),
            Err(NativeError::InvalidFrame)
        );
    }

    #[test]
    fn requests_only_allow_narrow_phone_commands() {
        for kind in [
            "shell",
            "PairRequest",
            "RadioPhaseResult",
            "OpenFile",
            "Reboot",
        ] {
            assert_eq!(
                validate_request(&PhoneRequest {
                    r#type: kind.to_owned(),
                    payload: json!({}),
                    expected_revision: Some(0)
                }),
                Err(NativeError::InvalidRequest)
            );
        }
        assert!(validate_request(&PhoneRequest {
            r#type: "Snapshot".to_owned(),
            payload: json!({}),
            expected_revision: None
        })
        .is_ok());
        assert_eq!(
            validate_request(&PhoneRequest {
                r#type: "CommitPlan".to_owned(),
                payload: json!({"preparedId":"synthetic"}),
                expected_revision: None
            }),
            Err(NativeError::StaleRevision)
        );
    }

    #[test]
    fn unpaired_and_disconnected_bridges_cannot_mutate() {
        let mut bridge = Bridge::default();
        assert_eq!(
            bridge
                .request(PhoneRequest {
                    r#type: "Start".to_owned(),
                    payload: json!({}),
                    expected_revision: Some(0)
                })
                .unwrap_err(),
            NativeError::NotConnected
        );
        assert_eq!(bridge.pair().unwrap_err(), NativeError::NotConnected);
        assert_eq!(
            bridge.set_airplane(true, "synthetic").unwrap_err(),
            NativeError::NotConnected
        );
    }

    #[cfg(unix)]
    #[test]
    fn pairing_wire_keeps_nonce_and_approved_credentials_out_of_renderer_replies() {
        use std::{fs, net::TcpListener, os::unix::fs::PermissionsExt, thread};
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let directory =
            std::env::temp_dir().join(format!("localspoof-pair-{}-{port}", std::process::id()));
        fs::create_dir(&directory).unwrap();
        let path = directory.join("adb");
        let script = format!("#!/usr/bin/env python3\nimport sys\na=sys.argv[1:]\na=a[4:] if a[:1]==['-H'] else a[2:]\nif a==['version']: print('Android Debug Bridge version synthetic')\nelif a==['devices','-l']: print('ABC123 device model:Synthetic')\nelif a==['-s','ABC123','get-devpath']: print('usb:1-2')\nelif a==['-s','ABC123','forward','tcp:0','localabstract:localspoof.control.v1']: print('{port}')\nelse: print('disabled')\n");
        fs::write(&path, script).unwrap();
        fs::set_permissions(&path, fs::Permissions::from_mode(0o700)).unwrap();
        let phone = thread::spawn(move || {
            let mut stream = listener.accept().unwrap().0;
            stream
                .set_read_timeout(Some(Duration::from_secs(5)))
                .unwrap();
            assert_eq!(read_frame(&mut stream).unwrap()["type"], "Hello");
            write_frame(&mut stream, &hello()).unwrap();
            let request = read_frame(&mut stream).unwrap();
            assert_eq!(request["type"], "PairRequest");
            let nonce = request["payload"]["clientNonce"]
                .as_str()
                .unwrap()
                .to_owned();
            assert!(valid_pair_id(&nonce));
            assert!(request["payload"].get("secret").is_none());
            let id = "b".repeat(32);
            write_frame(&mut stream, &json!({"ok":true,"payload":{"pairingId":id,"comparisonCode":comparison_code(&nonce,&id),"pending":true,"unexpectedCredential":"must be filtered"}})).unwrap();
            let poll = read_frame(&mut stream).unwrap();
            assert_eq!(poll["type"], "PollPair");
            assert_eq!(poll["payload"]["clientNonce"], nonce);
            assert_eq!(poll["payload"]["pairingId"], id);
            write_frame(
                &mut stream,
                &json!({"ok":false,"error":"PAIRING_PENDING","token":"must be filtered"}),
            )
            .unwrap();
            assert_eq!(read_frame(&mut stream).unwrap()["type"], "PollPair");
            write_frame(&mut stream, &json!({"ok":true,"sessionId":"c".repeat(32),"payload":{"paired":true,"token":"d".repeat(32),"clientNonce":nonce}})).unwrap();
            let snapshot = read_frame(&mut stream).unwrap();
            assert_eq!(snapshot["type"], "Snapshot");
            assert_eq!(snapshot["token"], "d".repeat(32));
            assert_eq!(snapshot["sessionId"], "c".repeat(32));
            write_frame(&mut stream, &json!({"ok":true,"revision":0,"payload":{}})).unwrap();
            drop(stream);
            let mut rejected = listener.accept().unwrap().0;
            rejected
                .set_read_timeout(Some(Duration::from_secs(5)))
                .unwrap();
            assert_eq!(read_frame(&mut rejected).unwrap()["type"], "Hello");
            write_frame(&mut rejected, &hello()).unwrap();
            assert_eq!(read_frame(&mut rejected).unwrap()["type"], "PairRequest");
            write_frame(&mut rejected, &json!({"ok":false,"error":"PAIRING_REJECTED","sessionId":"private-session","point":{"lat":1,"lon":1},"payload":{"token":"private-credential"}})).unwrap();
        });
        let mut bridge = Bridge::default();
        bridge.connect(path.to_str().unwrap(), "ABC123").unwrap();
        let pending = bridge.pair().unwrap();
        assert!(pending["payload"].get("unexpectedCredential").is_none());
        assert!(pending["payload"].get("clientNonce").is_none());
        assert_eq!(
            pending["payload"]["comparisonCode"].as_str().unwrap().len(),
            6
        );
        assert_eq!(
            bridge.poll_pair().unwrap(),
            json!({"ok":false,"error":"PAIRING_PENDING"})
        );
        let paired = bridge.poll_pair().unwrap();
        assert_eq!(
            paired,
            json!({"ok":true,"payload":{"paired":true,"usbAirplaneAvailable":true}})
        );
        assert_eq!(bridge.pair(), Err(NativeError::InvalidRequest));
        bridge
            .request(PhoneRequest {
                r#type: "Snapshot".to_owned(),
                payload: json!({}),
                expected_revision: None,
            })
            .unwrap();
        bridge.disconnect();
        assert!(bridge.pairing_target.is_none());
        bridge.connect(path.to_str().unwrap(), "ABC123").unwrap();
        assert_eq!(
            bridge.pair().unwrap(),
            json!({"ok":false,"error":"PAIRING_REJECTED"})
        );
        assert!(bridge.connection.is_none());
        bridge.disconnect();
        phone.join().unwrap();
        fs::remove_dir_all(directory).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn connect_never_succeeds_when_forwarded_phone_control_is_closed() {
        use std::{fs, net::TcpListener, os::unix::fs::PermissionsExt, thread};
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let directory =
            std::env::temp_dir().join(format!("localspoof-hello-{}-{port}", std::process::id()));
        fs::create_dir(&directory).unwrap();
        let path = directory.join("adb");
        let removed = directory.join("removed");
        let script = format!("#!/usr/bin/env python3\nimport sys\nfrom pathlib import Path\na=sys.argv[1:]\na=a[4:] if a[:1]==['-H'] else a[2:]\nif a==['version']: print('Android Debug Bridge version synthetic')\nelif a==['devices','-l']: print('ABC123 device model:Synthetic')\nelif a==['-s','ABC123','get-devpath']: print('usb:1-2')\nelif a==['-s','ABC123','forward','tcp:0','localabstract:localspoof.control.v1']: print('{port}')\nelif a[:4]==['-s','ABC123','forward','--remove']: Path({:?}).write_text('owned tunnel removed')\nelse: print('disabled')\n", removed.to_str().unwrap());
        fs::write(&path, script).unwrap();
        fs::set_permissions(&path, fs::Permissions::from_mode(0o700)).unwrap();
        let phone = thread::spawn(move || {
            drop(listener.accept().unwrap().0);
        });
        let mut bridge = Bridge::default();
        let result = bridge.connect(path.to_str().unwrap(), "ABC123");
        phone.join().unwrap();
        assert!(
            result.is_err(),
            "A TCP forward accepting a socket is not a live phone control service"
        );
        assert!(bridge.connection.is_none());
        assert!(
            removed.exists(),
            "Failed Hello must remove only the owned tunnel"
        );
        fs::remove_dir_all(directory).unwrap();
    }
}

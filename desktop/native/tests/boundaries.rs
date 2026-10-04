use localspoof_native::{
    adb::{
        is_matching_usb_instance_id, is_usb_devpath, parse_devices, validate_serial, NativeError,
    },
    framing::{read_frame, write_frame, MAX_FRAME_BYTES},
    radio::RadioOwnership,
};
use serde_json::json;
use std::io::Cursor;

#[test]
fn frames_round_trip_and_reject_oversized_lengths_before_allocation() {
    let original = json!({"ok":true,"payload":{"point":{"lat":10.0,"lon":179.0}}});
    let mut bytes = Vec::new();
    write_frame(&mut bytes, &original).unwrap();
    assert_eq!(read_frame(&mut Cursor::new(bytes)).unwrap(), original);
    let mut malicious = Cursor::new(((MAX_FRAME_BYTES + 1) as u32).to_be_bytes().to_vec());
    assert_eq!(
        read_frame(&mut malicious).unwrap_err(),
        NativeError::InvalidFrame
    );
}

#[test]
fn usb_transport_requires_explicit_device_path_evidence() {
    for path in [
        "usb:1-2",
        "/dev/bus/usb/001/002",
        "\\\\?\\usb#vid_18d1&pid_4ee7",
    ] {
        assert!(is_usb_devpath(path));
    }
    for path in [
        "unknown",
        "127.0.0.1:5555",
        "local",
        "adb-abc._adb-tls-connect._tcp",
    ] {
        assert!(!is_usb_devpath(path));
    }
    assert!(validate_serial("adb-abc._adb-tls-connect._tcp").is_err());
}

#[test]
fn windows_usb_evidence_requires_the_exact_present_device_serial_component() {
    for id in [
        "USB\\VID_04E8&PID_6860\\ABC123",
        "usb\\vid_18d1&pid_4ee7\\abc123",
    ] {
        assert!(is_matching_usb_instance_id(id, "ABC123"));
    }
    for id in [
        "USB\\VID_04E8&PID_6860\\PREFIXABC123",
        "USB\\VID_04E8&PID_6860\\ABC123SUFFIX",
        "USB\\VID_04E8&PID_6860&MI_03\\7&ABC123&0&0003",
        "SWD\\VID_04E8&PID_6860\\ABC123",
        "USB\\VID_GGGG&PID_6860\\ABC123",
        "USB\\VID_04E8&PID_6860\\ABC123\\extra",
        "USB\\😀AAAA&PID_6860\\ABC123",
        "USB\\VID_04E8&PID_6860\\192.168.1.2:5555",
    ] {
        assert!(!is_matching_usb_instance_id(id, "ABC123"));
    }
    assert!(!is_matching_usb_instance_id(
        "USB\\VID_04E8&PID_6860\\192.168.1.2:5555",
        "192.168.1.2:5555"
    ));
}

#[test]
fn public_native_error_display_matches_serialized_stable_codes_without_diagnostics() {
    for error in [
        NativeError::AdbUnauthorized,
        NativeError::AdbOffline,
        NativeError::AdbServerUnavailable,
        NativeError::UsbDeviceRequired,
        NativeError::UsbVerificationUnavailable,
        NativeError::PhoneControlUnavailable,
        NativeError::PhoneProtocolMismatch,
        NativeError::ApkInstallFailed,
        NativeError::ApkSignatureMismatch,
        NativeError::UntrustedApk,
        NativeError::ToolFailed,
    ] {
        assert_eq!(
            serde_json::to_value(error).unwrap().as_str().unwrap(),
            error.to_string()
        );
        assert!(error
            .to_string()
            .bytes()
            .all(|byte| byte.is_ascii_uppercase() || byte == b'_'));
    }
}

#[test]
fn partial_invalid_or_empty_frames_are_rejected() {
    for bytes in [
        vec![0, 0],
        vec![0, 0, 0, 0],
        vec![0, 0, 0, 3, b'{', b'x', b'}'],
        vec![0, 0, 0, 5, b'{'],
    ] {
        assert_eq!(
            read_frame(&mut Cursor::new(bytes)).unwrap_err(),
            NativeError::InvalidFrame
        );
    }
}

#[test]
fn device_listing_excludes_network_and_emulator_transports() {
    let data = "List of devices attached\nABC123 device usb:1-2 product:x model:Pixel_8 transport_id:1\nXYZ unauthorized usb:1-3\n192.168.1.2:5555 device model:Pixel\nemulator-5554 device\n* daemon started successfully *\n";
    let devices = parse_devices(data);
    assert_eq!(devices.len(), 2);
    assert_eq!(devices[0].serial, "ABC123");
    assert_eq!(devices[1].state, "unauthorized");
    assert!(validate_serial("ABC123").is_ok());
    for serial in [
        "",
        "a b",
        "-s",
        "192.168.1.2:5555",
        "emulator-5554",
        "x;rm",
        "../x",
    ] {
        assert!(validate_serial(serial).is_err());
    }
}

#[test]
fn airplane_restore_never_overrides_initial_on_or_detected_user_changes() {
    let mut initial_on = RadioOwnership::new(true);
    assert_eq!(initial_on.desired_restore(), None);
    initial_on.applied(true);
    assert_eq!(initial_on.desired_restore(), None);
    let mut owned = RadioOwnership::new(false);
    owned.applied(true);
    assert_eq!(owned.desired_restore(), Some(false));
    owned.observe(false);
    owned.observe(true);
    assert_eq!(owned.desired_restore(), None);
}

#[test]
fn unowned_toggle_is_not_a_restore_authorization() {
    let mut state = RadioOwnership::new(false);
    state.observe(true);
    assert_eq!(state.desired_restore(), None);
}

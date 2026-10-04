//! The only persistent application data: items explicitly saved by the user.
use serde_json::{json, Value};
use std::{
    fs::{self, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
};

const MAX_BYTES: u64 = 512 * 1024;
const MAX_ITEMS: usize = 100;
static TEMP_ID: AtomicU64 = AtomicU64::new(0);

pub struct SavedStore {
    directory: PathBuf,
}

fn invalid() -> String {
    "INVALID_SAVED_ITEM".into()
}
fn storage_error() -> String {
    "SAVED_STORAGE_UNAVAILABLE".into()
}

fn text(value: &Value, maximum: usize, empty: bool) -> bool {
    value.as_str().is_some_and(|s| {
        (empty || !s.trim().is_empty())
            && s.chars().count() <= maximum
            && !s.chars().any(char::is_control)
    })
}
fn identifier(value: &Value) -> bool {
    value.as_str().is_some_and(|s| {
        s.len() == 36
            && s.bytes().enumerate().all(|(i, b)| {
                if [8, 13, 18, 23].contains(&i) {
                    b == b'-'
                } else {
                    b.is_ascii_digit() || (b'a'..=b'f').contains(&b)
                }
            })
    })
}
fn number(value: &Value, minimum: f64, maximum: f64) -> bool {
    value
        .as_f64()
        .is_some_and(|n| n.is_finite() && (minimum..=maximum).contains(&n))
}
fn point(value: &Value) -> bool {
    value.as_object().is_some_and(|p| {
        p.len() == 2
            && p.get("lat").is_some_and(|v| number(v, -90.0, 90.0))
            && p.get("lon").is_some_and(|v| number(v, -180.0, 180.0))
    })
}
fn choice(value: &Value, choices: &[&str]) -> bool {
    value.as_str().is_some_and(|v| choices.contains(&v))
}

pub fn validate_item(item: &Value) -> Result<(), String> {
    let object = item.as_object().ok_or_else(invalid)?;
    for (key, valid) in [
        ("id", object.get("id").is_some_and(identifier)),
        (
            "name",
            object.get("name").is_some_and(|v| text(v, 120, false)),
        ),
        (
            "group",
            object.get("group").is_some_and(|v| text(v, 80, true)),
        ),
        ("destination", object.get("destination").is_some_and(point)),
    ] {
        if !valid {
            let _ = key;
            return Err(invalid());
        }
    }
    let kind = object
        .get("kind")
        .and_then(Value::as_str)
        .ok_or_else(invalid)?;
    let common = ["id", "name", "group", "kind", "destination"];
    if kind == "PLACE" {
        return if object.keys().all(|k| common.contains(&k.as_str())) {
            Ok(())
        } else {
            Err(invalid())
        };
    }
    if kind != "ROUTE"
        || !object.get("origin").is_some_and(point)
        || !object
            .get("mode")
            .is_some_and(|v| choice(v, &["INSTANT", "WALK", "DRIVE", "FLIGHT"]))
    {
        return Err(invalid());
    }
    for (key, value) in object {
        if common.contains(&key.as_str()) {
            continue;
        }
        let valid = match key.as_str() {
            "origin" => point(value),
            "mode" => choice(value, &["INSTANT", "WALK", "DRIVE", "FLIGHT"]),
            "speedMps" | "driveSpeedMps" | "driveMinMps" | "driveMaxMps" => {
                number(value, 0.1, 56.0)
            }
            "drivingSpeedMode" => choice(value, &["FIXED", "VARIABLE"]),
            "followRoadLimits" => value.is_boolean(),
            "walkSpeedMps" => number(value, 0.1, 3.0),
            "planeSpeedMps" | "flightSpeedMps" => number(value, 30.0, 350.0),
            "waypoints" => value
                .as_array()
                .is_some_and(|a| a.len() <= 32 && a.iter().all(point)),
            "stops" => value.as_array().is_some_and(|a| {
                a.len() <= 32
                    && a.iter().all(|stop| {
                        stop.as_object().is_some_and(|v| {
                            v.keys()
                                .all(|k| ["point", "dwellSeconds", "label"].contains(&k.as_str()))
                                && v.get("point").is_some_and(point)
                                && v.get("dwellSeconds")
                                    .is_some_and(|d| number(d, 0.0, 3600.0))
                                && v.get("label").is_none_or(|l| text(l, 120, true))
                        })
                    })
            }),
            "customPath" => value.is_boolean(),
            "stopDwellSeconds" | "dwellSeconds" => number(value, 0.0, 3600.0),
            "flightDurationSeconds" => number(value, 1.0, 86400.0),
            "radioPolicy" => choice(value, &["MANUAL", "SIMULATION", "USB"]),
            "departureGroundMode" | "arrivalGroundMode" => choice(value, &["DRIVE", "INSTANT"]),
            "flightTransitMode" => choice(value, &["TIMED", "JUMP"]),
            "departureAirportId" | "arrivalAirportId" => value.is_null() || text(value, 128, false),
            _ => false,
        };
        if !valid {
            return Err(invalid());
        }
    }
    let base_speed = object
        .get("speedMps")
        .and_then(Value::as_f64)
        .unwrap_or(if object.get("mode").and_then(Value::as_str) == Some("WALK") { 1.4 } else { 13.9 });
    let drive_min = object
        .get("driveMinMps")
        .and_then(Value::as_f64)
        .unwrap_or(base_speed);
    let drive_max = object
        .get("driveMaxMps")
        .and_then(Value::as_f64)
        .unwrap_or(base_speed);
    if drive_min > drive_max {
        return Err(invalid());
    }
    if object.get("mode").and_then(Value::as_str) == Some("WALK")
        && object.get("speedMps").is_some_and(|v| !number(v, 0.1, 3.0))
    {
        return Err(invalid());
    }
    if object.get("mode").and_then(Value::as_str) == Some("INSTANT") {
        return Err(invalid());
    }
    if let (Some(plane), Some(flight)) = (object.get("planeSpeedMps"), object.get("flightSpeedMps"))
    {
        if plane.as_f64() != flight.as_f64() {
            return Err(invalid());
        }
    }
    if object.get("stops").is_some()
        && object
            .get("waypoints")
            .and_then(Value::as_array)
            .is_some_and(|p| !p.is_empty())
    {
        return Err(invalid());
    }
    if !["WALK", "DRIVE"].contains(&object["mode"].as_str().ok_or_else(invalid)?)
        && ["stops", "waypoints"].iter().any(|key| {
            object
                .get(*key)
                .and_then(Value::as_array)
                .is_some_and(|items| !items.is_empty())
        })
    {
        return Err(invalid());
    }
    Ok(())
}

fn validate_store(value: &Value) -> Result<(), String> {
    let object = value.as_object().ok_or_else(invalid)?;
    if object.len() != 2 || object.get("version").and_then(Value::as_u64) != Some(1) {
        return Err(invalid());
    }
    let items = object
        .get("items")
        .and_then(Value::as_array)
        .ok_or_else(invalid)?;
    if items.len() > MAX_ITEMS {
        return Err("SAVED_ITEM_LIMIT".into());
    }
    let mut ids = std::collections::HashSet::new();
    for item in items {
        validate_item(item)?;
        if !ids.insert(item["id"].as_str().ok_or_else(invalid)?) {
            return Err(invalid());
        }
    }
    Ok(())
}

fn regular(path: &Path, directory: bool) -> Result<bool, String> {
    match fs::symlink_metadata(path) {
        Ok(metadata) => {
            #[cfg(windows)]
            {
                use std::os::windows::fs::MetadataExt;
                if metadata.file_attributes() & 0x400 != 0 {
                    return Err(storage_error());
                }
            }
            if metadata.file_type().is_symlink()
                || metadata.is_dir() != directory
                || (!directory && !metadata.is_file())
            {
                return Err(storage_error());
            }
            Ok(true)
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(false),
        Err(_) => Err(storage_error()),
    }
}

impl SavedStore {
    pub fn new(directory: PathBuf) -> Self {
        Self { directory }
    }
    fn path(&self) -> PathBuf {
        self.directory.join("saved-items-v1.json")
    }
    fn mutation_lock(&self) -> Result<fs::File, String> {
        regular(&self.directory, true)?;
        fs::create_dir_all(&self.directory).map_err(|_| storage_error())?;
        regular(&self.directory, true)?;
        let path = self.directory.join("saved-items.lock");
        regular(&path, false)?;
        let mut options = OpenOptions::new();
        options.read(true).write(true).create(true).truncate(false);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let file = options.open(path).map_err(|_| storage_error())?;
        file.try_lock()
            .map_err(|_| "SAVED_STORAGE_BUSY".to_owned())?;
        Ok(file)
    }
    pub fn load(&self) -> Result<Value, String> {
        if !regular(&self.directory, true)? || !regular(&self.path(), false)? {
            return Ok(json!({"version":1,"items":[]}));
        }
        let file = fs::File::open(self.path()).map_err(|_| storage_error())?;
        if file.metadata().map_err(|_| storage_error())?.len() > MAX_BYTES {
            return Err("SAVED_DATA_LIMIT".into());
        }
        let mut bytes = Vec::new();
        file.take(MAX_BYTES + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| storage_error())?;
        if bytes.len() as u64 > MAX_BYTES {
            return Err("SAVED_DATA_LIMIT".into());
        }
        let value: Value = serde_json::from_slice(&bytes).map_err(|_| "INVALID_SAVED_DATA")?;
        validate_store(&value)?;
        Ok(value)
    }
    fn write(&self, value: &Value) -> Result<(), String> {
        validate_store(value)?;
        let bytes = serde_json::to_vec(value).map_err(|_| invalid())?;
        if bytes.len() as u64 > MAX_BYTES {
            return Err("SAVED_DATA_LIMIT".into());
        }
        regular(&self.directory, true)?;
        fs::create_dir_all(&self.directory).map_err(|_| storage_error())?;
        regular(&self.directory, true)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&self.directory, fs::Permissions::from_mode(0o700))
                .map_err(|_| storage_error())?;
        }
        regular(&self.path(), false)?;
        let unique = TEMP_ID.fetch_add(1, Ordering::Relaxed);
        let temporary = self
            .directory
            .join(format!("saved-items-{}-{unique}.tmp", std::process::id()));
        let mut created = false;
        let result = (|| {
            let mut options = OpenOptions::new();
            options.write(true).create_new(true);
            #[cfg(unix)]
            {
                use std::os::unix::fs::OpenOptionsExt;
                options.mode(0o600);
            }
            let mut file = options.open(&temporary).map_err(|_| storage_error())?;
            created = true;
            file.write_all(&bytes)
                .and_then(|_| file.sync_all())
                .map_err(|_| storage_error())?;
            drop(file);
            fs::rename(&temporary, self.path()).map_err(|_| storage_error())
        })();
        if result.is_err() && created {
            let _ = fs::remove_file(&temporary);
        }
        result
    }
    pub fn put(&self, item: Value) -> Result<Value, String> {
        validate_item(&item)?;
        let _guard = self.mutation_lock()?;
        let mut store = self.load()?;
        let items = store["items"].as_array_mut().ok_or_else(invalid)?;
        if let Some(existing) = items.iter_mut().find(|old| old["id"] == item["id"]) {
            *existing = item;
        } else {
            if items.len() >= MAX_ITEMS {
                return Err("SAVED_ITEM_LIMIT".into());
            }
            items.push(item);
        }
        self.write(&store)?;
        Ok(store)
    }
    pub fn delete(&self, id: &str) -> Result<Value, String> {
        if !identifier(&Value::String(id.to_owned())) {
            return Err(invalid());
        }
        let _guard = self.mutation_lock()?;
        let mut store = self.load()?;
        store["items"]
            .as_array_mut()
            .ok_or_else(invalid)?
            .retain(|item| item["id"] != id);
        self.write(&store)?;
        Ok(store)
    }
    pub fn import(&self, incoming: Vec<Value>) -> Result<Value, String> {
        let imported = json!({"version":1,"items":incoming});
        validate_store(&imported)?;
        let _guard = self.mutation_lock()?;
        let mut store = self.load()?;
        let items = store["items"].as_array_mut().ok_or_else(invalid)?;
        for item in imported["items"].as_array().ok_or_else(invalid)? {
            if let Some(existing) = items.iter_mut().find(|old| old["id"] == item["id"]) {
                *existing = item.clone();
            } else {
                items.push(item.clone());
            }
        }
        self.write(&store)?;
        Ok(store)
    }
    pub fn clear(&self) -> Result<Value, String> {
        if !regular(&self.directory, true)? {
            return Ok(json!({"version":1,"items":[]}));
        }
        let _guard = self.mutation_lock()?;
        if regular(&self.path(), false)? {
            fs::remove_file(self.path()).map_err(|_| storage_error())?;
        }
        Ok(json!({"version":1,"items":[]}))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn item() -> Value {
        json!({"id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","name":"Synthetic place","group":"Tests","kind":"PLACE","destination":{"lat":0,"lon":0}})
    }
    fn store() -> SavedStore {
        SavedStore::new(std::env::temp_dir().join(format!(
            "localspoof-saved-test-{}-{}",
            std::process::id(),
            TEMP_ID.fetch_add(1, Ordering::Relaxed)
        )))
    }
    #[test]
    fn loading_without_save_does_not_create_a_profile() {
        let store = store();
        assert_eq!(store.load().unwrap()["items"], json!([]));
        assert!(!store.directory.exists());
    }
    #[test]
    fn another_store_owner_cannot_write_while_an_os_lock_is_held() {
        let store = store();
        let guard = store.mutation_lock().unwrap();
        let other = SavedStore::new(store.directory.clone());
        assert_eq!(other.put(item()).unwrap_err(), "SAVED_STORAGE_BUSY");
        drop(guard);
        other.put(item()).unwrap();
        assert_eq!(store.load().unwrap()["items"][0], item());
        store.clear().unwrap();
        fs::remove_dir_all(store.directory).unwrap();
    }
    #[test]
    fn explicit_save_survives_new_owner_and_delete_clear_are_real() {
        let store = store();
        store.put(item()).unwrap();
        let reopened = SavedStore::new(store.directory.clone());
        assert_eq!(reopened.load().unwrap()["items"][0], item());
        let mut renamed = item();
        renamed["name"] = json!("Renamed");
        store.put(renamed).unwrap();
        assert_eq!(store.load().unwrap()["items"].as_array().unwrap().len(), 1);
        store.delete(item()["id"].as_str().unwrap()).unwrap();
        assert_eq!(store.load().unwrap()["items"], json!([]));
        store.put(item()).unwrap();
        store.clear().unwrap();
        assert!(!store.path().exists());
        fs::remove_dir_all(store.directory).unwrap();
    }
    #[test]
    fn authority_unknown_fields_and_malformed_coordinates_never_persist() {
        let store = store();
        for (key, value) in [
            ("token", json!("secret")),
            ("revision", json!(7)),
            ("destination", json!({"lat":91,"lon":0})),
        ] {
            let mut invalid = item();
            invalid[key] = value;
            assert!(store.put(invalid).is_err());
        }
        assert!(!store.directory.exists());
    }
    #[test]
    fn corruption_and_duplicate_ids_are_not_silently_overwritten() {
        let store = store();
        fs::create_dir_all(&store.directory).unwrap();
        fs::write(store.path(), b"invalid").unwrap();
        assert!(store.put(item()).is_err());
        assert_eq!(fs::read(store.path()).unwrap(), b"invalid");
        assert!(validate_store(&json!({"version":1,"items":[item(),item()]})).is_err());
        fs::remove_dir_all(store.directory).unwrap();
    }
    #[test]
    fn invalid_import_is_atomic_and_keeps_existing_saved_items() {
        let store = store();
        store.put(item()).unwrap();
        let before = fs::read(store.path()).unwrap();
        let mut bad = item();
        bad["id"] = json!("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        bad["token"] = json!("secret");
        assert!(store.import(vec![item(), bad]).is_err());
        assert_eq!(fs::read(store.path()).unwrap(), before);
        store.clear().unwrap();
        fs::remove_dir_all(store.directory).unwrap();
    }
    #[test]
    fn route_template_has_no_runtime_or_geometry_and_is_bounded() {
        let mut route = item();
        route["kind"] = json!("ROUTE");
        route["origin"] = json!({"lat":0,"lon":1});
        route["mode"] = json!("WALK");
        route["speedMps"] = json!(1.4);
        validate_item(&route).unwrap();
        route.as_object_mut().unwrap().remove("speedMps");
        route["driveMinMps"] = json!(2);
        assert!(validate_item(&route).is_err()); // Absent WALK speed defaults to 1.4 on every platform.
        route.as_object_mut().unwrap().remove("driveMinMps");
        validate_item(&route).unwrap();
        route["speedMps"] = json!(50);
        assert!(validate_item(&route).is_err());
        route["speedMps"] = json!(1.4);
        route["waypoints"] = json!(vec![json!({"lat":0,"lon":0}); 33]);
        assert!(validate_item(&route).is_err());
    }
    #[test]
    fn variable_cruise_roundtrips_without_runtime_state() {
        let store = store();
        let mut route = item();
        route["kind"] = json!("ROUTE");
        route["origin"] = json!({"lat":0,"lon":1});
        route["mode"] = json!("DRIVE");
        route["speedMps"] = json!(55.88);
        route["drivingSpeedMode"] = json!("VARIABLE");
        route["driveMinMps"] = json!(40.2336);
        route["driveMaxMps"] = json!(55.88);
        route["followRoadLimits"] = json!(true);
        store.put(route.clone()).unwrap();
        assert_eq!(store.load().unwrap()["items"][0], route);
        for (key, value) in [
            ("driveMinMps", json!(56.1)),
            ("driveMaxMps", json!(30)),
            ("followRoadLimits", json!(1)),
            ("drivingSpeedMode", json!("RANDOM")),
            ("motion", json!({"speedMps":40})),
        ] {
            let mut invalid = route.clone();
            invalid[key] = value;
            assert!(validate_item(&invalid).is_err());
        }
        store.clear().unwrap();
        fs::remove_dir_all(store.directory).unwrap();
    }
    #[cfg(unix)]
    #[test]
    fn symlink_store_is_rejected_without_following_it() {
        let store = store();
        fs::create_dir_all(&store.directory).unwrap();
        let target = store.directory.join("target");
        fs::write(&target, b"canary").unwrap();
        std::os::unix::fs::symlink(&target, store.path()).unwrap();
        assert!(store.put(item()).is_err());
        assert!(store.clear().is_err());
        assert_eq!(fs::read(target).unwrap(), b"canary");
        fs::remove_dir_all(store.directory).unwrap();
    }
}

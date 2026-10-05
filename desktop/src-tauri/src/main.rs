#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod local_tools;
mod network;
mod saved;
mod updater;

use localspoof_native::{
    adb::{Adb, Device},
    Bridge, PhoneRequest,
};
use serde_json::json;
use serde_json::Value;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use tauri::{Manager, State};

type AppBridge = Arc<Mutex<Bridge>>;
type AppNetwork = Arc<network::Network>;
type AppUpdater = Arc<updater::Coordinator>;
#[derive(Default)]
struct UpdateSafety {
    phone_seen: AtomicBool,
}

fn saved_store(app: &tauri::AppHandle) -> Result<saved::SavedStore, String> {
    app.path()
        .app_local_data_dir()
        .map(saved::SavedStore::new)
        .map_err(|_| "SAVED_STORAGE_UNAVAILABLE".into())
}

type SavedGate = Arc<Mutex<()>>;

#[tauri::command]
async fn saved_load(app: tauri::AppHandle, state: State<'_, SavedGate>) -> Result<Value, String> {
    let store = saved_store(&app)?;
    let gate = state.inner().clone();
    blocking(move || {
        let _guard = gate.lock().map_err(|_| "BUSY")?;
        store.load()
    })
    .await
}

#[tauri::command]
async fn saved_put(
    item: Value,
    app: tauri::AppHandle,
    state: State<'_, SavedGate>,
) -> Result<Value, String> {
    let store = saved_store(&app)?;
    let gate = state.inner().clone();
    blocking(move || {
        let _guard = gate.lock().map_err(|_| "BUSY")?;
        store.put(item)
    })
    .await
}

#[tauri::command]
async fn saved_delete(
    id: String,
    app: tauri::AppHandle,
    state: State<'_, SavedGate>,
) -> Result<Value, String> {
    let store = saved_store(&app)?;
    let gate = state.inner().clone();
    blocking(move || {
        let _guard = gate.lock().map_err(|_| "BUSY")?;
        store.delete(&id)
    })
    .await
}

#[tauri::command]
async fn saved_clear(app: tauri::AppHandle, state: State<'_, SavedGate>) -> Result<Value, String> {
    let store = saved_store(&app)?;
    let gate = state.inner().clone();
    blocking(move || {
        let _guard = gate.lock().map_err(|_| "BUSY")?;
        store.clear()
    })
    .await
}

#[tauri::command]
async fn saved_import(
    items: Vec<Value>,
    app: tauri::AppHandle,
    state: State<'_, SavedGate>,
) -> Result<Value, String> {
    let store = saved_store(&app)?;
    let gate = state.inner().clone();
    blocking(move || {
        let _guard = gate.lock().map_err(|_| "BUSY")?;
        store.import(items)
    })
    .await
}

#[tauri::command]
async fn discover_local_tools(app: tauri::AppHandle) -> Result<local_tools::LocalTools, String> {
    let companion = option_env!("LOCALSPOOF_TRUSTED_APK_SHA256").and_then(|_| {
        app.path()
            .resolve(
                "companion/localspoof.apk",
                tauri::path::BaseDirectory::Resource,
            )
            .ok()
    });
    blocking(move || Ok(local_tools::discover(companion))).await
}

#[tauri::command]
async fn clear_network_state(state: State<'_, AppNetwork>) -> Result<(), String> {
    let network = state.inner().clone();
    blocking(move || network.clear()).await
}

#[tauri::command]
async fn search_places(
    query: String,
    provider: Option<String>,
    state: State<'_, AppNetwork>,
) -> Result<Vec<network::Place>, String> {
    let network = state.inner().clone();
    blocking(move || network.search_places(query, provider)).await
}

#[tauri::command]
async fn nearby_places(
    center: network::Point,
    category: network::PoiCategory,
    state: State<'_, AppNetwork>,
) -> Result<Vec<network::PoiPlace>, String> {
    let network = state.inner().clone();
    blocking(move || network.nearby_places(center, category)).await
}

#[tauri::command]
async fn vehicle_catalog(
    resource: String,
    year: Option<u16>,
    make: Option<String>,
    model: Option<String>,
    id: Option<u32>,
    state: State<'_, AppNetwork>,
) -> Result<Value, String> {
    let network = state.inner().clone();
    blocking(move || network.vehicle_catalog(resource, year, make, model, id)).await
}

#[tauri::command]
async fn map_resource(
    url: String,
    state: State<'_, AppNetwork>,
) -> Result<network::MapResource, String> {
    let network = state.inner().clone();
    blocking(move || network.map_resource(url)).await
}

#[tauri::command]
async fn online_route(
    origin: network::Point,
    destination: network::Point,
    profile: String,
    departure_airport_id: Option<String>,
    arrival_airport_id: Option<String>,
    state: State<'_, AppNetwork>,
) -> Result<network::OnlineRoute, String> {
    let network = state.inner().clone();
    blocking(move || {
        network.online_route(
            origin,
            destination,
            profile,
            departure_airport_id,
            arrival_airport_id,
        )
    })
    .await
}

async fn blocking<T: Send + 'static>(
    operation: impl FnOnce() -> Result<T, String> + Send + 'static,
) -> Result<T, String> {
    tauri::async_runtime::spawn_blocking(operation)
        .await
        .map_err(|_| "NATIVE_OPERATION_FAILED".to_owned())?
}

#[tauri::command]
async fn list_devices(adb_path: String) -> Result<Vec<Device>, String> {
    blocking(move || {
        Adb::new(adb_path)
            .and_then(|adb| adb.devices())
            .map_err(|error| error.to_string())
    })
    .await
}

#[tauri::command]
async fn install_apk(adb_path: String, serial: String, apk_path: String) -> Result<(), String> {
    // A packaged release may install only the APK hash supplied at desktop build time.
    // The release build script must verify signing before calculating this value.
    blocking(move || {
        Adb::new(adb_path)
            .and_then(|adb| {
                adb.install(
                    &serial,
                    apk_path,
                    option_env!("LOCALSPOOF_TRUSTED_APK_SHA256"),
                )
            })
            .map_err(|error| error.to_string())
    })
    .await
}

#[tauri::command]
async fn connect_phone(
    adb_path: String,
    serial: String,
    transport: Option<String>,
    state: State<'_, AppBridge>,
    update: State<'_, AppUpdater>,
    safety: State<'_, Arc<UpdateSafety>>,
) -> Result<Value, String> {
    if update.busy() {
        return Err("UPDATE_BUSY".into());
    }
    // Even a failed attempt may have reached a phone; disconnected state is unknown.
    safety.phone_seen.store(true, Ordering::Release);
    let bridge = state.inner().clone();
    let update = update.inner().clone();
    blocking(move || {
        let mut bridge = bridge.lock().map_err(|_| "BUSY")?;
        if update.busy() {
            return Err("UPDATE_BUSY".into());
        }
        match transport.as_deref().unwrap_or("USB") {
            "USB" => bridge.connect(&adb_path, &serial),
            "WIFI" => bridge.connect_wireless(&adb_path, &serial),
            _ => return Err("INVALID_INPUT".into()),
        }
        .map_err(|e| e.to_string())
    })
    .await
}

#[tauri::command]
async fn discover_wireless(adb_path: String) -> Result<Value, String> {
    blocking(move || {
        let adb = Adb::new(adb_path).map_err(|e| e.to_string())?;
        let services = adb.wireless_services().map_err(|e| e.to_string())?;
        let devices = adb.wireless_devices().map_err(|e| e.to_string())?;
        Ok(json!({"services":services,"devices":devices}))
    })
    .await
}
#[tauri::command]
async fn pair_wireless_os(
    adb_path: String,
    endpoint: String,
    pairing_code: String,
) -> Result<Value, String> {
    blocking(move || {
        Adb::new(adb_path)
            .and_then(|a| a.pair_wireless(&endpoint, &pairing_code))
            .map_err(|e| e.to_string())?;
        Ok(json!({"paired":true}))
    })
    .await
}
#[tauri::command]
async fn connect_wireless_os(adb_path: String, endpoint: String) -> Result<Value, String> {
    blocking(move || {
        let d = Adb::new(adb_path)
            .and_then(|a| a.connect_wireless(&endpoint))
            .map_err(|e| e.to_string())?;
        serde_json::to_value(d).map_err(|_| "INVALID_DEVICE".into())
    })
    .await
}

#[tauri::command]
async fn update_status(state: State<'_, AppUpdater>) -> Result<updater::Status, String> {
    state.status()
}
#[tauri::command]
async fn check_updates(
    app: tauri::AppHandle,
    state: State<'_, AppUpdater>,
) -> Result<updater::Status, String> {
    let update = state.inner().clone();
    blocking(move || update.check(&app)).await
}
#[tauri::command]
async fn download_update(
    app: tauri::AppHandle,
    state: State<'_, AppUpdater>,
    bridge: State<'_, AppBridge>,
    safety: State<'_, Arc<UpdateSafety>>,
    allow_during_journey: Option<bool>,
) -> Result<updater::Status, String> {
    let update = state.inner().clone();
    let bridge = bridge.inner().clone();
    let seen = safety.phone_seen.load(Ordering::Acquire);
    let key = app
        .config()
        .plugins
        .0
        .get("updater")
        .and_then(|v| v.get("pubkey"))
        .and_then(Value::as_str)
        .ok_or("UPDATE_CONFIGURATION")?
        .to_owned();
    blocking(move || {
        if seen && !allow_during_journey.unwrap_or(false) {
            let snapshot = bridge
                .lock()
                .map_err(|_| "BUSY")?
                .request(PhoneRequest {
                    r#type: "Snapshot".into(),
                    payload: json!({}),
                    expected_revision: None,
                })
                .map_err(|_| "UPDATE_PHONE_STATE_UNKNOWN")?;
            if snapshot.get("state").and_then(Value::as_str) == Some("IDLE")
                && snapshot
                    .pointer("/payload/desktopUpdateSafe")
                    .and_then(Value::as_bool)
                    .is_none()
            {
                return Err("UPDATE_PHONE_UPDATE_REQUIRED".into());
            }
            if !updater::desktop_safe_snapshot(&snapshot) {
                return Err("UPDATE_JOURNEY_ACTIVE".into());
            }
        }
        update.download(&key)
    })
    .await
}

#[tauri::command]
async fn cancel_update(state: State<'_, AppUpdater>) -> Result<(), String> {
    state.cancel()
}
#[tauri::command]
async fn install_update(
    app: tauri::AppHandle,
    state: State<'_, AppUpdater>,
    bridge: State<'_, AppBridge>,
    safety: State<'_, Arc<UpdateSafety>>,
) -> Result<(), String> {
    let update = state.inner().clone();
    let bridge = bridge.inner().clone();
    let safety = safety.inner().clone();
    blocking(move || {
        let mut phone = bridge.lock().map_err(|_| "BUSY")?;
        if safety.phone_seen.load(Ordering::Acquire) {
            let snapshot = phone
                .request(PhoneRequest {
                    r#type: "Snapshot".into(),
                    payload: json!({}),
                    expected_revision: None,
                })
                .map_err(|_| "UPDATE_PHONE_STATE_UNKNOWN")?;
            if snapshot.get("state").and_then(Value::as_str) == Some("IDLE")
                && snapshot
                    .pointer("/payload/desktopUpdateSafe")
                    .and_then(Value::as_bool)
                    .is_none()
            {
                return Err("UPDATE_PHONE_UPDATE_REQUIRED".into());
            }
            if !updater::desktop_safe_snapshot(&snapshot) {
                return Err("UPDATE_JOURNEY_ACTIVE".into());
            }
        }
        let (installer, bytes) = update.take_installer()?;
        // Quiesce and release only this controller, never Stop the independent phone.
        phone.disconnect();
        if installer.install(bytes).is_err() {
            let _ = update.installation_failed();
            return Err("UPDATE_INSTALL_FAILED".into());
        }
        app.exit(0);
        Ok(())
    })
    .await
}

#[tauri::command]
async fn pair_phone(state: State<'_, AppBridge>) -> Result<Value, String> {
    let bridge = state.inner().clone();
    blocking(move || {
        bridge
            .lock()
            .map_err(|_| "BUSY".to_owned())?
            .pair()
            .map_err(|error| error.to_string())
    })
    .await
}

#[tauri::command]
async fn confirm_pair(state: State<'_, AppBridge>) -> Result<Value, String> {
    let bridge = state.inner().clone();
    blocking(move || {
        bridge
            .lock()
            .map_err(|_| "BUSY".to_owned())?
            .poll_pair()
            .map_err(|error| error.to_string())
    })
    .await
}

#[tauri::command]
async fn request_phone(
    request: PhoneRequest,
    state: State<'_, AppBridge>,
    update: State<'_, AppUpdater>,
) -> Result<Value, String> {
    if update.busy() {
        return Err("UPDATE_BUSY".into());
    }
    let bridge = state.inner().clone();
    let update = update.inner().clone();
    blocking(move || {
        let mut bridge = bridge.lock().map_err(|_| "BUSY")?;
        if update.busy() {
            return Err("UPDATE_BUSY".into());
        }
        bridge.request(request).map_err(|error| error.to_string())
    })
    .await
}

#[tauri::command]
async fn set_airplane(
    enabled: bool,
    phase_id: String,
    state: State<'_, AppBridge>,
) -> Result<Value, String> {
    let bridge = state.inner().clone();
    blocking(move || {
        bridge
            .lock()
            .map_err(|_| "BUSY".to_owned())?
            .set_airplane(enabled, &phase_id)
            .map_err(|error| error.to_string())
    })
    .await
}

#[tauri::command]
async fn disconnect_phone(state: State<'_, AppBridge>) -> Result<(), String> {
    let bridge = state.inner().clone();
    blocking(move || {
        bridge.lock().map_err(|_| "BUSY".to_owned())?.disconnect();
        Ok(())
    })
    .await
}

fn main() {
    tauri_plugin_updater::cleanup_owned_update_staging();
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_updater::Builder::new().build())
        .manage(Arc::new(updater::Coordinator::default()))
        .manage(Arc::new(UpdateSafety::default()))
        .manage(Arc::new(Mutex::new(Bridge::default())))
        .manage(Arc::new(network::Network::new()))
        .manage(Arc::new(Mutex::new(())))
        .on_page_load(|webview, payload| {
            if payload.event() == tauri::webview::PageLoadEvent::Started {
                if let Some(network) = webview.try_state::<AppNetwork>() {
                    let _ = network.clear();
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            map_resource,
            online_route,
            search_places,
            nearby_places,
            vehicle_catalog,
            clear_network_state,
            discover_local_tools,
            list_devices,
            install_apk,
            connect_phone,
            discover_wireless,
            pair_wireless_os,
            connect_wireless_os,
            update_status,
            check_updates,
            download_update,
            cancel_update,
            install_update,
            pair_phone,
            confirm_pair,
            request_phone,
            set_airplane,
            disconnect_phone,
            saved_load,
            saved_put,
            saved_delete,
            saved_clear,
            saved_import
        ])
        .build(tauri::generate_context!());
    if let Ok(app) = app {
        app.run(|app, event| {
            if let tauri::RunEvent::Exit = event {
                if let Some(state) = app.try_state::<AppBridge>() {
                    if let Ok(mut bridge) = state.lock() {
                        bridge.disconnect();
                    }
                }
            }
        });
    }
}

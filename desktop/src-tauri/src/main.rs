#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod local_tools;
mod network;
mod saved;

use localspoof_native::{
    adb::{Adb, Device},
    Bridge, PhoneRequest,
};
use serde_json::Value;
use std::sync::{Arc, Mutex};
use tauri::{Manager, State};

type AppBridge = Arc<Mutex<Bridge>>;
type AppNetwork = Arc<network::Network>;

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
    state: State<'_, AppBridge>,
) -> Result<Value, String> {
    let bridge = state.inner().clone();
    blocking(move || {
        bridge
            .lock()
            .map_err(|_| "BUSY".to_owned())?
            .connect(&adb_path, &serial)
            .map_err(|error| error.to_string())
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
) -> Result<Value, String> {
    let bridge = state.inner().clone();
    blocking(move || {
        bridge
            .lock()
            .map_err(|_| "BUSY".to_owned())?
            .request(request)
            .map_err(|error| error.to_string())
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
    let app = tauri::Builder::default()
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

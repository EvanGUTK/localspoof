//! Fixed-provider, bounded network adapters. Resource responses and route activity
//! remain in RAM. Never forward cookies, response headers, redirects, or raw errors
//! to the webview, and never accept an arbitrary network destination.
use reqwest::{blocking::Client, redirect::Policy, Url};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{
    collections::HashMap,
    io::Read,
    sync::{
        atomic::{AtomicU64, AtomicUsize, Ordering},
        Mutex, OnceLock,
    },
    time::{Duration, Instant},
};

const CACHE_BYTES: usize = 64 * 1024 * 1024;
const MAX_POINTS: usize = 12_000;
const MAX_ROUTE_BYTES: usize = 1_048_576;
const MAX_SNAP_METERS: f64 = 250.0;
const MAX_AIRPORT_SNAP_METERS: f64 = 5_000.0;
const MAX_DISTANCE_METERS: f64 = 20_000_000.0;
const UA: &str = "LocalSpoof/0.6.0 (Windows local location simulator; explicit user requests)";

#[derive(Clone, Copy, Deserialize, Serialize, Debug)]
#[serde(deny_unknown_fields)]
pub struct Point {
    pub lat: f64,
    pub lon: f64,
}
impl Point {
    fn validate(self) -> Result<Self, String> {
        if self.lat.is_finite()
            && self.lon.is_finite()
            && (-90.0..=90.0).contains(&self.lat)
            && (-180.0..=180.0).contains(&self.lon)
        {
            Ok(self)
        } else {
            Err("INVALID_COORDINATE".into())
        }
    }
}
#[derive(Clone, Serialize)]
pub struct MapResource {
    pub bytes: Vec<u8>,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OnlineRoute {
    pub profile: String,
    pub provider: &'static str,
    pub points: Vec<Point>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub edge_speed_limits: Vec<Option<f64>>,
    pub distance_meters: f64,
    pub duration_seconds: f64,
    pub snap_origin_meters: f64,
    pub snap_destination_meters: f64,
    pub max_snap_origin_meters: f64,
    pub max_snap_destination_meters: f64,
}
#[derive(Deserialize)]
struct CatalogAirport {
    id: String,
    lat: f64,
    lon: f64,
    #[serde(rename = "type")]
    airport_type: String,
    #[serde(rename = "scheduledService")]
    scheduled_service: bool,
    closed: bool,
}
fn endpoint_snap_limit(airport_id: Option<&str>, endpoint: Point) -> Result<f64, String> {
    let Some(id) = airport_id else {
        return Ok(MAX_SNAP_METERS);
    };
    if id.is_empty() || id.len() > 128 || id.chars().any(char::is_control) {
        return Err("INVALID_AIRPORT_ID".into());
    }
    static AIRPORTS: OnceLock<Result<Vec<CatalogAirport>, ()>> = OnceLock::new();
    let catalog = AIRPORTS.get_or_init(|| {
        serde_json::from_str(include_str!("../../ui/resources/airports.json")).map_err(|_| ())
    });
    let airport = catalog
        .as_ref()
        .map_err(|_| "AIRPORT_CATALOG_UNAVAILABLE")?
        .iter()
        .find(|airport| {
            airport.id == id
                && !airport.closed
                && airport.scheduled_service
                && airport.airport_type == "large_airport"
        })
        .ok_or("INVALID_AIRPORT_ID")?;
    let location = Point {
        lat: airport.lat,
        lon: airport.lon,
    }
    .validate()?;
    if meters(location, endpoint) > 0.1 {
        return Err("AIRPORT_ENDPOINT_MISMATCH".into());
    }
    Ok(MAX_AIRPORT_SNAP_METERS)
}
#[derive(Clone, Serialize)]
pub struct Place {
    pub name: String,
    pub lat: f64,
    pub lon: f64,
}
#[derive(Clone, Copy, Deserialize, Serialize, Debug)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum PoiCategory {
    Hotel,
    Food,
    Coffee,
    Shops,
    Fuel,
    Health,
    Attractions,
}
impl PoiCategory {
    fn tags(self) -> &'static [&'static str] {
        match self {
            Self::Hotel => &[
                "tourism:hotel",
                "tourism:motel",
                "tourism:hostel",
                "tourism:guest_house",
            ],
            Self::Food => &["amenity:restaurant", "amenity:fast_food"],
            Self::Coffee => &["amenity:cafe"],
            Self::Shops => &["shop"],
            Self::Fuel => &["amenity:fuel"],
            Self::Health => &["amenity:hospital", "amenity:pharmacy"],
            Self::Attractions => &["tourism:attraction", "tourism:museum"],
        }
    }
}
#[derive(Serialize)]
pub struct PoiPlace {
    pub name: String,
    pub lat: f64,
    pub lon: f64,
    pub category: PoiCategory,
}
fn decode_nearby(
    value: &Value,
    center: Point,
    category: PoiCategory,
) -> Result<Vec<PoiPlace>, String> {
    let center = center.validate()?;
    let features = value
        .get("features")
        .and_then(Value::as_array)
        .ok_or("INVALID_PLACES_RESPONSE")?;
    if features.len() > 8 {
        return Err("INVALID_PLACES_RESPONSE".into());
    }
    Ok(features
        .iter()
        .filter_map(|feature| {
            let properties = feature.get("properties")?;
            let key = properties.get("osm_key")?.as_str()?;
            let value = properties.get("osm_value")?.as_str()?;
            if key.is_empty()
                || key.encode_utf16().count() > 40
                || value.is_empty()
                || value.encode_utf16().count() > 80
                || key.chars().chain(value.chars()).any(char::is_control)
            {
                return None;
            }
            if matches!(category, PoiCategory::Shops)
                && ["vacant", "no", "disused", "abandoned"].contains(&value)
            {
                return None;
            }
            let tag = format!("{key}:{value}");
            if !category
                .tags()
                .iter()
                .any(|allowed| *allowed == key || *allowed == tag)
            {
                return None;
            }
            let name = properties.get("name")?.as_str()?;
            if name.trim().is_empty()
                || name.encode_utf16().count() > 300
                || name.chars().any(char::is_control)
            {
                return None;
            }
            let geometry = feature.get("geometry")?;
            if geometry.get("type")?.as_str()? != "Point" {
                return None;
            }
            let coordinates = geometry.get("coordinates")?.as_array()?;
            if coordinates.len() != 2 {
                return None;
            }
            let point = Point {
                lat: coordinates[1].as_f64()?,
                lon: coordinates[0].as_f64()?,
            }
            .validate()
            .ok()?;
            if meters(center, point) > 10_000.0 {
                return None;
            }
            Some(PoiPlace {
                name: name.trim().into(),
                lat: point.lat,
                lon: point.lon,
                category,
            })
        })
        .take(8)
        .collect())
}
struct CachedSearch {
    results: Vec<Place>,
    expires: Instant,
}
struct CachedResource {
    bytes: Vec<u8>,
    expires: Instant,
    used: Instant,
}
#[derive(Default)]
struct ResourceCache {
    entries: HashMap<String, CachedResource>,
    size: usize,
}
impl ResourceCache {
    fn get(&mut self, key: &str) -> Option<MapResource> {
        let now = Instant::now();
        if self
            .entries
            .get(key)
            .is_some_and(|item| item.expires <= now)
        {
            if let Some(item) = self.entries.remove(key) {
                self.size -= item.bytes.len();
            }
        }
        self.entries.get_mut(key).map(|item| {
            item.used = now;
            MapResource {
                bytes: item.bytes.clone(),
            }
        })
    }
    fn insert(&mut self, key: String, bytes: Vec<u8>, ttl: Duration) {
        if ttl.is_zero() || bytes.len() > CACHE_BYTES {
            return;
        }
        if let Some(previous) = self.entries.remove(&key) {
            self.size -= previous.bytes.len();
        }
        while self.size + bytes.len() > CACHE_BYTES || self.entries.len() >= 2048 {
            let oldest = self
                .entries
                .iter()
                .min_by_key(|(_, value)| value.used)
                .map(|(key, _)| key.clone());
            match oldest.and_then(|key| self.entries.remove(&key)) {
                Some(item) => self.size -= item.bytes.len(),
                None => break,
            }
        }
        let now = Instant::now();
        self.size += bytes.len();
        self.entries.insert(
            key,
            CachedResource {
                bytes,
                expires: now + ttl,
                used: now,
            },
        );
    }
}
#[derive(Default)]
struct RateGate(Mutex<Option<Instant>>);
impl RateGate {
    fn reserve(&self) -> Result<(), String> {
        let mut last = self.0.lock().map_err(|_| "NETWORK_BUSY")?;
        let now = Instant::now();
        if last.is_some_and(|time| now.duration_since(time) < Duration::from_millis(1100)) {
            return Err("RATE_LIMITED".into());
        }
        *last = Some(now);
        Ok(())
    }
}
struct Slot<'a>(&'a AtomicUsize);
impl Drop for Slot<'_> {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::AcqRel);
    }
}

pub struct Network {
    client: Result<Client, ()>,
    cache: Mutex<ResourceCache>,
    map_active: AtomicUsize,
    route_gate: RateGate,
    search_gate: RateGate,
    searches: Mutex<HashMap<String, CachedSearch>>,
    route_active: AtomicUsize,
    search_active: AtomicUsize,
    generation: AtomicU64,
}
impl Default for Network {
    fn default() -> Self {
        Self::new()
    }
}
impl Network {
    pub fn new() -> Self {
        let client = Client::builder()
            .user_agent(UA)
            .redirect(Policy::none())
            .no_proxy()
            .connect_timeout(Duration::from_secs(8))
            .timeout(Duration::from_secs(25))
            .pool_max_idle_per_host(6)
            .build()
            .map_err(|_| ());
        Self {
            client,
            cache: Mutex::new(ResourceCache::default()),
            map_active: AtomicUsize::new(0),
            route_gate: RateGate::default(),
            search_gate: RateGate::default(),
            searches: Mutex::new(HashMap::new()),
            route_active: AtomicUsize::new(0),
            search_active: AtomicUsize::new(0),
            generation: AtomicU64::new(0),
        }
    }
    fn client(&self) -> Result<&Client, String> {
        self.client
            .as_ref()
            .map_err(|_| "NETWORK_UNAVAILABLE".into())
    }
    pub fn map_resource(&self, url: String) -> Result<MapResource, String> {
        let generation = self.generation.load(Ordering::Acquire);
        let (url, limit) = validated_map_url(&url)?;
        let key = url.as_str().to_owned();
        if let Some(item) = self.cache.lock().map_err(|_| "NETWORK_BUSY")?.get(&key) {
            return Ok(item);
        }
        if self.map_active.fetch_add(1, Ordering::AcqRel) >= 6 {
            self.map_active.fetch_sub(1, Ordering::AcqRel);
            return Err("NETWORK_BUSY".into());
        }
        let _slot = Slot(&self.map_active);
        let response = self
            .client()?
            .get(url)
            .send()
            .map_err(|_| "MAP_UNAVAILABLE")?;
        if !response.status().is_success() {
            return Err("MAP_UNAVAILABLE".into());
        }
        let ttl = cache_ttl(
            response
                .headers()
                .get(reqwest::header::CACHE_CONTROL)
                .and_then(|value| value.to_str().ok()),
        )
        .saturating_sub(Duration::from_secs(
            response
                .headers()
                .get(reqwest::header::AGE)
                .and_then(|value| value.to_str().ok())
                .and_then(|value| value.parse().ok())
                .unwrap_or(0),
        ));
        let bytes = read_bounded(response, limit, "MAP_RESOURCE_TOO_LARGE")?;
        let mut cache = self.cache.lock().map_err(|_| "NETWORK_BUSY")?;
        if self.generation.load(Ordering::Acquire) != generation {
            return Err("STALE_NETWORK_REQUEST".into());
        }
        cache.insert(key, bytes.clone(), ttl);
        Ok(MapResource { bytes })
    }
    pub fn online_route(
        &self,
        origin: Point,
        destination: Point,
        profile: String,
        departure_airport_id: Option<String>,
        arrival_airport_id: Option<String>,
    ) -> Result<OnlineRoute, String> {
        let generation = self.generation.load(Ordering::Acquire);
        origin.validate()?;
        destination.validate()?;
        let (server, wire_profile) = match profile.as_str() {
            "WALK" => ("routed-foot", "foot"),
            "DRIVE" => ("routed-car", "driving"),
            _ => return Err("INVALID_ROUTING_PROFILE".into()),
        };
        if profile != "DRIVE" && (departure_airport_id.is_some() || arrival_airport_id.is_some()) {
            return Err("INVALID_AIRPORT_PROFILE".into());
        }
        // The departure airport is the destination of the first surface leg;
        // the arrival airport is the origin of the final surface leg.
        let max_origin_snap = endpoint_snap_limit(arrival_airport_id.as_deref(), origin)?;
        let max_destination_snap =
            endpoint_snap_limit(departure_airport_id.as_deref(), destination)?;
        self.route_gate.reserve()?;
        if self.route_active.fetch_add(1, Ordering::AcqRel) >= 1 {
            self.route_active.fetch_sub(1, Ordering::AcqRel);
            return Err("NETWORK_BUSY".into());
        }
        let _slot = Slot(&self.route_active);
        let url = format!("https://routing.openstreetmap.de/{server}/route/v1/{wire_profile}/{},{};{},{}?overview=full&geometries=geojson&steps=false&alternatives=false&generate_hints=false&radiuses={max_origin_snap};{max_destination_snap}", origin.lon, origin.lat, destination.lon, destination.lat);
        let response = self
            .client()?
            .get(url)
            .send()
            .map_err(|_| "ROUTING_UNAVAILABLE")?;
        if !response.status().is_success() {
            return Err("ROUTING_UNAVAILABLE".into());
        }
        let bytes = read_bounded(response, MAX_ROUTE_BYTES, "ROUTE_TOO_COMPLEX")?;
        let json: Value = serde_json::from_slice(&bytes).map_err(|_| "INVALID_ROUTE_RESPONSE")?;
        let mut result = decode_route_with_limits(
            &json,
            origin,
            destination,
            profile,
            max_origin_snap,
            max_destination_snap,
        )?;
        if self.generation.load(Ordering::Acquire) != generation {
            return Err("STALE_NETWORK_REQUEST".into());
        }
        if result.profile == "DRIVE" {
            // Optional enrichment never changes OSRM geometry or invents a posted limit.
            result.edge_speed_limits = self.posted_limits(&result.points).unwrap_or_default();
        }
        if self.generation.load(Ordering::Acquire) != generation {
            return Err("STALE_NETWORK_REQUEST".into());
        }
        Ok(result)
    }
    fn posted_limits(&self, points: &[Point]) -> Result<Vec<Option<f64>>, String> {
        let request = serde_json::json!({
            "shape":points,"costing":"auto","shape_match":"map_snap","units":"kilometers",
            "trace_options":{"search_radius":5,"gps_accuracy":2,"interpolation_distance":0},
            // edge.length also includes source_percent_along/target_percent_along
            // for truncated boundary edges; these are not separate attribute keys.
            "filters":{"action":"include","attributes":["edge.speed_limit","edge.length","matched.point","matched.type","matched.edge_index","matched.distance_along_edge","matched.distance_from_trace_point","matched.begin_route_discontinuity","matched.end_route_discontinuity"]}
        });
        let body = serde_json::to_vec(&request).map_err(|_| "LIMITS_UNAVAILABLE")?;
        if body.len() > MAX_ROUTE_BYTES {
            return Err("LIMITS_UNAVAILABLE".into());
        }
        let response = self
            .client()?
            .post("https://valhalla1.openstreetmap.de/trace_attributes")
            .header("Content-Type", "application/json")
            .timeout(Duration::from_secs(10))
            .body(body)
            .send()
            .map_err(|_| "LIMITS_UNAVAILABLE")?;
        if !response.status().is_success() {
            return Err("LIMITS_UNAVAILABLE".into());
        }
        let bytes = read_bounded(response, 4 * MAX_ROUTE_BYTES, "LIMITS_UNAVAILABLE")?;
        let json: Value = serde_json::from_slice(&bytes).map_err(|_| "LIMITS_UNAVAILABLE")?;
        decode_posted_limits(&json, points)
    }
    /// Explicit category request. No POI cache, history or user-controlled URL.
    pub fn nearby_places(
        &self,
        center: Point,
        category: PoiCategory,
    ) -> Result<Vec<PoiPlace>, String> {
        let center = center.validate()?;
        let generation = self.generation.load(Ordering::Acquire);
        self.search_gate.reserve()?;
        if self.search_active.fetch_add(1, Ordering::AcqRel) >= 1 {
            self.search_active.fetch_sub(1, Ordering::AcqRel);
            return Err("NETWORK_BUSY".into());
        }
        let _slot = Slot(&self.search_active);
        let mut url =
            Url::parse("https://photon.komoot.io/reverse").map_err(|_| "PLACES_UNAVAILABLE")?;
        {
            let mut query = url.query_pairs_mut();
            query
                .append_pair("lat", &center.lat.to_string())
                .append_pair("lon", &center.lon.to_string())
                .append_pair("radius", "10")
                .append_pair("limit", "8");
            for tag in category.tags() {
                query.append_pair("osm_tag", tag);
            }
        }
        let response = self
            .client()?
            .get(url)
            .timeout(Duration::from_secs(12))
            .send()
            .map_err(|_| "PLACES_UNAVAILABLE")?;
        if !response.status().is_success() {
            return Err("PLACES_UNAVAILABLE".into());
        }
        let bytes = read_bounded(response, 262_144, "PLACES_RESPONSE_TOO_LARGE")?;
        let value: Value = serde_json::from_slice(&bytes).map_err(|_| "INVALID_PLACES_RESPONSE")?;
        let result = decode_nearby(&value, center, category)?;
        if self.generation.load(Ordering::Acquire) != generation {
            return Err("STALE_NETWORK_REQUEST".into());
        }
        Ok(result)
    }
    /// Call only on an explicit Search action, never from an input-change handler.
    /// Public Nominatim's 1/s limit applies across all users of the app; this
    /// adapter is qualified only for the current single-machine test build.
    pub fn search_places(
        &self,
        query: String,
        provider: Option<String>,
    ) -> Result<Vec<Place>, String> {
        let generation = self.generation.load(Ordering::Acquire);
        let provider = provider.as_deref().unwrap_or("NOMINATIM");
        if provider == "DISABLED" {
            return Err("SEARCH_DISABLED".into());
        }
        let endpoint = match provider {
            "NOMINATIM" => "https://nominatim.openstreetmap.org/search",
            "PHOTON" => "https://photon.komoot.io/api/",
            _ => return Err("INVALID_SEARCH_PROVIDER".into()),
        };
        let query = query.trim();
        if query.is_empty() || query.chars().count() > 200 || query.chars().any(char::is_control) {
            return Err("INVALID_SEARCH_QUERY".into());
        }
        let key = format!("{provider}:{query}");
        {
            let mut cache = self.searches.lock().map_err(|_| "NETWORK_BUSY")?;
            cache.retain(|_, item| item.expires > Instant::now());
            if let Some(item) = cache.get(&key) {
                return Ok(item.results.clone());
            }
        }
        self.search_gate.reserve()?;
        if self.search_active.fetch_add(1, Ordering::AcqRel) >= 1 {
            self.search_active.fetch_sub(1, Ordering::AcqRel);
            return Err("NETWORK_BUSY".into());
        }
        let _slot = Slot(&self.search_active);
        let mut parameters = vec![("q", query), ("limit", "6")];
        if provider == "NOMINATIM" {
            parameters.push(("format", "jsonv2"));
        }
        let response = self
            .client()?
            .get(endpoint)
            .query(&parameters)
            .send()
            .map_err(|_| "SEARCH_UNAVAILABLE")?;
        if !response.status().is_success() {
            return Err("SEARCH_UNAVAILABLE".into());
        }
        let bytes = read_bounded(response, 524_288, "SEARCH_RESPONSE_TOO_LARGE")?;
        let value: Value = serde_json::from_slice(&bytes).map_err(|_| "INVALID_SEARCH_RESPONSE")?;
        let results = decode_search(&value, provider)?;
        let mut cache = self.searches.lock().map_err(|_| "NETWORK_BUSY")?;
        if self.generation.load(Ordering::Acquire) != generation {
            return Err("STALE_NETWORK_REQUEST".into());
        }
        if cache.len() >= 64 {
            if let Some(oldest) = cache
                .iter()
                .min_by_key(|(_, value)| value.expires)
                .map(|(key, _)| key.clone())
            {
                cache.remove(&oldest);
            }
        }
        cache.insert(
            key,
            CachedSearch {
                results: results.clone(),
                expires: Instant::now() + Duration::from_secs(900),
            },
        );
        Ok(results)
    }
    /// Clearing never resets rate limits: repeated Clear actions cannot bypass
    /// a provider's request budget. Late responses cannot repopulate caches.
    pub fn clear(&self) -> Result<(), String> {
        self.generation.fetch_add(1, Ordering::AcqRel);
        *self.cache.lock().map_err(|_| "NETWORK_BUSY")? = ResourceCache::default();
        self.searches.lock().map_err(|_| "NETWORK_BUSY")?.clear();
        Ok(())
    }
}
fn decode_search(value: &Value, provider: &str) -> Result<Vec<Place>, String> {
    let values = if provider == "NOMINATIM" {
        value.as_array()
    } else if provider == "PHOTON" {
        value.get("features").and_then(Value::as_array)
    } else {
        None
    }
    .filter(|items| items.len() <= 20)
    .ok_or("INVALID_SEARCH_RESPONSE")?;
    let mut results = Vec::new();
    for item in values.iter().take(6) {
        let (name, point) = if provider == "NOMINATIM" {
            let point = Point {
                lat: item["lat"]
                    .as_str()
                    .and_then(|value| value.parse().ok())
                    .ok_or("INVALID_SEARCH_RESPONSE")?,
                lon: item["lon"]
                    .as_str()
                    .and_then(|value| value.parse().ok())
                    .ok_or("INVALID_SEARCH_RESPONSE")?,
            }
            .validate()?;
            (
                item["display_name"]
                    .as_str()
                    .ok_or("INVALID_SEARCH_RESPONSE")?
                    .to_owned(),
                point,
            )
        } else {
            if item["geometry"]["type"].as_str() != Some("Point") {
                return Err("INVALID_SEARCH_RESPONSE".into());
            }
            let point = coordinate(&item["geometry"]["coordinates"])?;
            let properties = &item["properties"];
            let names: Vec<_> = ["name", "street", "city", "state", "country"]
                .into_iter()
                .filter_map(|key| properties[key].as_str())
                .filter(|value| !value.is_empty())
                .collect();
            (names.join(", "), point)
        };
        if name.is_empty() || name.chars().count() > 500 || name.chars().any(char::is_control) {
            return Err("INVALID_SEARCH_RESPONSE".into());
        }
        results.push(Place {
            name,
            lat: point.lat,
            lon: point.lon,
        });
    }
    Ok(results)
}
fn read_bounded(input: impl Read, limit: usize, error: &str) -> Result<Vec<u8>, String> {
    let mut bytes = Vec::new();
    input
        .take((limit + 1) as u64)
        .read_to_end(&mut bytes)
        .map_err(|_| "NETWORK_UNAVAILABLE")?;
    if bytes.len() > limit {
        return Err(error.into());
    }
    Ok(bytes)
}
fn cache_ttl(value: Option<&str>) -> Duration {
    let Some(value) = value else {
        return Duration::from_secs(600);
    };
    let directives: Vec<_> = value.split(',').map(str::trim).collect();
    if directives
        .iter()
        .any(|item| item.eq_ignore_ascii_case("no-store") || item.eq_ignore_ascii_case("no-cache"))
    {
        return Duration::ZERO;
    }
    let ttl = directives
        .iter()
        .find_map(|item| {
            item.strip_prefix("max-age=")
                .and_then(|value| value.trim_matches('"').parse::<u64>().ok())
        })
        .unwrap_or(600);
    Duration::from_secs(ttl.min(172_800))
}
fn tile_path(parts: &[&str], suffix: &str, max_zoom: u32) -> bool {
    if parts.len() != 3 {
        return false;
    }
    let Some(y) = parts[2]
        .strip_suffix(suffix)
        .and_then(|value| value.parse::<u32>().ok())
    else {
        return false;
    };
    let (Ok(z), Ok(x)) = (parts[0].parse::<u32>(), parts[1].parse::<u32>()) else {
        return false;
    };
    z <= max_zoom && x < (1u32 << z) && y < (1u32 << z)
}
fn slug(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 100
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"_-".contains(&byte))
}
fn validated_map_url(input: &str) -> Result<(Url, usize), String> {
    let rejected = || "INVALID_MAP_RESOURCE".to_owned();
    if input.len() > 2048 || input.chars().any(char::is_control) || input.contains('\\') {
        return Err(rejected());
    }
    let url = Url::parse(input).map_err(|_| rejected())?;
    if url.scheme() != "https"
        || url.host_str() != Some("tiles.openfreemap.org")
        || !url.username().is_empty()
        || url.password().is_some()
        || url.port().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return Err(rejected());
    }
    let lowered = input.to_ascii_lowercase();
    if ["%2f", "%5c", "%2e", "%00", "%0a", "%0d"]
        .iter()
        .any(|escape| lowered.contains(escape))
        || input.contains("/../")
        || input.contains("/./")
    {
        return Err(rejected());
    }
    let parts: Vec<_> = url.path().trim_start_matches('/').split('/').collect();
    let limit = match parts.as_slice() {
        ["planet"] | ["planet", "latest"] => 1_048_576,
        ["planet", version, rest @ ..] if slug(version) && tile_path(rest, ".pbf", 14) => 2_097_152,
        ["styles", style] if slug(style) => 1_048_576,
        ["natural_earth", "ne2sr", rest @ ..] if tile_path(rest, ".png", 6) => 2_097_152,
        ["fonts", fontstack, range]
            if !fontstack.is_empty()
                && fontstack.len() <= 1000
                && range.strip_suffix(".pbf").is_some_and(|range| {
                    let pair: Vec<_> = range.split('-').collect();
                    pair.len() == 2
                        && pair[0]
                            .parse::<u32>()
                            .is_ok_and(|start| start % 256 == 0 && start <= 65_280)
                        && pair[1].parse::<u32>().is_ok_and(|end| {
                            end <= 65_535
                                && pair[0].parse::<u32>().is_ok_and(|start| end == start + 255)
                        })
                }) =>
        {
            524_288
        }
        ["sprites", folder, file]
            if slug(folder)
                && [".png", ".json"].iter().any(|suffix| {
                    file.strip_suffix(suffix).is_some_and(|base| {
                        slug(base) || base.strip_suffix("@2x").is_some_and(slug)
                    })
                }) =>
        {
            4_194_304
        }
        _ => return Err(rejected()),
    };
    Ok((url, limit))
}
fn coordinate(value: &Value) -> Result<Point, String> {
    let values = value
        .as_array()
        .filter(|array| array.len() == 2)
        .ok_or("INVALID_ROUTE_RESPONSE")?;
    Point {
        lon: values[0].as_f64().ok_or("INVALID_ROUTE_RESPONSE")?,
        lat: values[1].as_f64().ok_or("INVALID_ROUTE_RESPONSE")?,
    }
    .validate()
}
fn meters(a: Point, b: Point) -> f64 {
    let delta_lat = (b.lat - a.lat).to_radians();
    let delta_lon = (b.lon - a.lon).to_radians();
    let hav = (delta_lat / 2.0).sin().powi(2)
        + a.lat.to_radians().cos() * b.lat.to_radians().cos() * (delta_lon / 2.0).sin().powi(2);
    6_371_008.8 * 2.0 * hav.clamp(0.0, 1.0).sqrt().asin()
}
#[cfg(test)]
fn decode_route(
    json: &Value,
    origin: Point,
    destination: Point,
    profile: String,
) -> Result<OnlineRoute, String> {
    decode_route_with_limits(
        json,
        origin,
        destination,
        profile,
        MAX_SNAP_METERS,
        MAX_SNAP_METERS,
    )
}
fn decode_route_with_limits(
    json: &Value,
    origin: Point,
    destination: Point,
    profile: String,
    max_origin_snap: f64,
    max_destination_snap: f64,
) -> Result<OnlineRoute, String> {
    match json.get("code").and_then(Value::as_str) {
        Some("Ok") => {}
        Some("NoRoute") | Some("NoSegment") => return Err("NO_ROUTE".into()),
        _ => return Err("INVALID_ROUTE_RESPONSE".into()),
    }
    let route = json
        .get("routes")
        .and_then(Value::as_array)
        .and_then(|routes| routes.first())
        .ok_or("INVALID_ROUTE_RESPONSE")?;
    if route["geometry"]["type"].as_str() != Some("LineString") {
        return Err("INVALID_ROUTE_RESPONSE".into());
    }
    let coordinates = route["geometry"]["coordinates"]
        .as_array()
        .filter(|points| (2..=MAX_POINTS).contains(&points.len()))
        .ok_or("ROUTE_TOO_COMPLEX")?;
    let points = coordinates
        .iter()
        .map(coordinate)
        .collect::<Result<Vec<_>, _>>()?;
    let start = meters(origin, points[0]);
    let end = meters(destination, points[points.len() - 1]);
    if start > max_origin_snap || end > max_destination_snap {
        return Err("ROUTE_ENDPOINT_TOO_FAR".into());
    }
    let mut geometry_length = 0.0;
    for edge in points.windows(2) {
        let length = meters(edge[0], edge[1]);
        if length > 100_000.0 {
            return Err("INVALID_ROUTE_GEOMETRY".into());
        }
        geometry_length += length;
    }
    let distance = route["distance"].as_f64().ok_or("INVALID_ROUTE_RESPONSE")?;
    let duration = route["duration"].as_f64().ok_or("INVALID_ROUTE_RESPONSE")?;
    if !distance.is_finite()
        || !(0.0..=MAX_DISTANCE_METERS).contains(&distance)
        || geometry_length > MAX_DISTANCE_METERS
        || !duration.is_finite()
        || !(0.0..=315_360_000.0).contains(&duration)
    {
        return Err("INVALID_ROUTE_RESPONSE".into());
    }
    // A wildly inconsistent claimed road distance cannot define the user's preview.
    if (distance - geometry_length).abs() > 1000.0_f64.max(geometry_length * 0.25) {
        return Err("INVALID_ROUTE_GEOMETRY".into());
    }
    let serialized = serde_json::to_vec(&points).map_err(|_| "INVALID_ROUTE_RESPONSE")?;
    if serialized.len() > MAX_ROUTE_BYTES - 4096 {
        return Err("ROUTE_TOO_COMPLEX".into());
    }
    Ok(OnlineRoute {
        profile,
        provider: "FOSSGIS OSRM",
        points,
        edge_speed_limits: Vec::new(),
        distance_meters: distance,
        duration_seconds: duration,
        snap_origin_meters: start,
        snap_destination_meters: end,
        max_snap_origin_meters: max_origin_snap,
        max_snap_destination_meters: max_destination_snap,
    })
}

/// Associate explicit posted limits only with a close, continuous, forward match.
/// Routing `speed`, profile defaults, unmatched points and unknown edges are never limits.
fn decode_posted_limits(json: &Value, points: &[Point]) -> Result<Vec<Option<f64>>, String> {
    let invalid = || "INVALID_LIMIT_RESPONSE".to_string();
    if json.get("units").and_then(Value::as_str) != Some("kilometers") {
        return Err(invalid());
    }
    let edges = json
        .get("edges")
        .and_then(Value::as_array)
        .filter(|a| a.len() <= MAX_POINTS * 8)
        .ok_or_else(invalid)?;
    let matched = json
        .get("matched_points")
        .and_then(Value::as_array)
        .filter(|a| a.len() == points.len())
        .ok_or_else(invalid)?;
    let match_point = |index: usize| -> Option<(usize, f64)> {
        let value = &matched[index];
        if value.get("type")?.as_str()? != "matched"
            || ["begin_route_discontinuity", "end_route_discontinuity"]
                .iter()
                .any(|key| value.get(key).is_some_and(|v| v.as_bool() != Some(false)))
        {
            return None;
        }
        let snap = Point {
            lat: value.get("lat")?.as_f64()?,
            lon: value.get("lon")?.as_f64()?,
        }
        .validate()
        .ok()?;
        if meters(points[index], snap) > 2.0 {
            return None;
        }
        let deviation = value.get("distance_from_trace_point")?.as_f64()?;
        if !deviation.is_finite() || !(0.0..=2.0).contains(&deviation) {
            return None;
        }
        let edge = usize::try_from(value.get("edge_index")?.as_u64()?).ok()?;
        let along = value.get("distance_along_edge")?.as_f64()?;
        if edge >= edges.len() || !along.is_finite() || !(0.0..=1.0).contains(&along) {
            return None;
        }
        Some((edge, along))
    };
    let mut limits = Vec::with_capacity(points.len().saturating_sub(1));
    for i in 0..points.len().saturating_sub(1) {
        let limit = (|| -> Option<f64> {
            let (start, a) = match_point(i)?;
            let (end, b) = match_point(i + 1)?;
            if end < start || end - start > 32 || start == end && b < a {
                return None;
            }
            let mut routed = 0.0;
            let mut minimum = f64::INFINITY;
            for (offset, edge) in edges[start..=end].iter().enumerate() {
                let kph = edge.get("speed_limit")?.as_f64()?;
                // Zero/255 (missing/unlimited), nonfinite and unsupported values remain unknown.
                if !kph.is_finite() || !(0.36..=360.0).contains(&kph) || kph == 255.0 {
                    return None;
                }
                let length = edge.get("length")?.as_f64()?;
                if !length.is_finite() || !(0.0..=100.0).contains(&length) {
                    return None;
                }
                // length is the traversed (trimmed) edge length, whereas matched
                // distance_along_edge refers to the complete directed road edge.
                let source = match edge.get("source_percent_along") {
                    Some(value) => value.as_f64()?,
                    None => 0.0,
                };
                let target = match edge.get("target_percent_along") {
                    Some(value) => value.as_f64()?,
                    None => 1.0,
                };
                if !source.is_finite()
                    || !target.is_finite()
                    || !(0.0..=1.0).contains(&source)
                    || !(0.0..=1.0).contains(&target)
                    || target <= source
                {
                    return None;
                }
                let begin = if offset == 0 { a } else { source };
                let finish = if start + offset == end { b } else { target };
                // Serializer precision can differ slightly between edge fractions
                // and matched fractions; reject a meaningful out-of-range match.
                if begin < source - 0.000001 || finish > target + 0.000001 || finish < begin {
                    return None;
                }
                let portion = (finish.min(target) - begin.max(source)) / (target - source);
                routed += length * 1000.0 * portion;
                minimum = minimum.min(kph / 3.6);
            }
            let direct = meters(points[i], points[i + 1]);
            if direct < 0.05 || (routed - direct).abs() > 3.0_f64.max(direct * 0.05) {
                return None;
            }
            Some(minimum)
        })();
        limits.push(limit);
    }
    Ok(limits)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    #[ignore = "Explicit public provider qualification; requires internet"]
    fn public_berlin_road_preview_keeps_verified_posted_limits() {
        let network = Network::new();
        let result = network
            .online_route(
                Point {
                    lat: 52.5163,
                    lon: 13.3777,
                },
                Point {
                    lat: 52.5219,
                    lon: 13.4131,
                },
                "DRIVE".into(),
                None,
                None,
            )
            .expect("public OSRM road preview");
        let known = result
            .edge_speed_limits
            .iter()
            .filter(|limit| limit.is_some())
            .count();
        assert_eq!(
            result.edge_speed_limits.len(),
            result.points.len() - 1,
            "Valhalla enrichment unavailable"
        );
        assert!(
            known > 0,
            "No verified posted limits on the public qualification road"
        );
    }
    #[test]
    fn trimmed_first_and_last_edges_preserve_posted_limits_without_double_scaling() {
        let points = [
            Point {
                lat: 52.0,
                lon: 13.0,
            },
            Point {
                lat: 52.0001,
                lon: 13.0,
            },
        ];
        let fixture = serde_json::json!({"units":"kilometers","edges":[{"speed_limit":72,"length":0.011119508,"source_percent_along":0.2,"target_percent_along":0.3}],"matched_points":[{"lat":52.0,"lon":13.0,"type":"matched","edge_index":0,"distance_along_edge":0.2,"distance_from_trace_point":0.0},{"lat":52.0001,"lon":13.0,"type":"matched","edge_index":0,"distance_along_edge":0.3,"distance_from_trace_point":0.0}]});
        assert_eq!(
            decode_posted_limits(&fixture, &points).unwrap(),
            vec![Some(20.0)]
        );
        for (field, value) in [
            ("source_percent_along", serde_json::json!(0.31)),
            ("target_percent_along", serde_json::json!(0.1)),
            ("target_percent_along", serde_json::json!(1.1)),
            ("source_percent_along", serde_json::json!("0.2")),
        ] {
            let mut bad = fixture.clone();
            bad["edges"][0][field] = value;
            assert_eq!(
                decode_posted_limits(&bad, &points).unwrap(),
                vec![None],
                "{field}"
            );
        }
        let mut outside = fixture.clone();
        outside["matched_points"][0]["distance_along_edge"] = serde_json::json!(0.1);
        assert_eq!(decode_posted_limits(&outside, &points).unwrap(), vec![None]);
        let mut crossing = serde_json::json!({"units":"kilometers","edges":[{"speed_limit":72,"length":0.005559754,"source_percent_along":0.5},{"speed_limit":36,"length":0.005559754,"target_percent_along":0.5}],"matched_points":[{"lat":52.0,"lon":13.0,"type":"matched","edge_index":0,"distance_along_edge":0.5,"distance_from_trace_point":0.0},{"lat":52.0001,"lon":13.0,"type":"matched","edge_index":1,"distance_along_edge":0.5,"distance_from_trace_point":0.0}]});
        assert_eq!(
            decode_posted_limits(&crossing, &points).unwrap(),
            vec![Some(10.0)]
        );
        crossing["edges"][1]["target_percent_along"] = serde_json::json!(0.0);
        assert_eq!(
            decode_posted_limits(&crossing, &points).unwrap(),
            vec![None]
        );
    }
    #[test]
    fn posted_limits_never_use_profile_speeds_or_ambiguous_matches() {
        let points = [
            Point {
                lat: 52.0,
                lon: 13.0,
            },
            Point {
                lat: 52.0001,
                lon: 13.0,
            },
        ];
        let fixture = serde_json::json!({"units":"kilometers","edges":[{"speed":99,"speed_limit":72,"length":0.11119508}],"matched_points":[{"lat":52.0,"lon":13.0,"type":"matched","edge_index":0,"distance_along_edge":0.0,"distance_from_trace_point":0.0},{"lat":52.0001,"lon":13.0,"type":"matched","edge_index":0,"distance_along_edge":0.1,"distance_from_trace_point":0.0}]});
        assert_eq!(
            decode_posted_limits(&fixture, &points).unwrap(),
            vec![Some(20.0)]
        );
        for changed in ["profile", "snap", "gap", "reverse", "length", "unlimited"] {
            let mut bad = fixture.clone();
            match changed {
                "profile" => {
                    bad["edges"][0]
                        .as_object_mut()
                        .unwrap()
                        .remove("speed_limit");
                }
                "snap" => {
                    bad["matched_points"][1]["lat"] = serde_json::json!(52.0002);
                }
                "gap" => {
                    bad["matched_points"][1]["begin_route_discontinuity"] = serde_json::json!(true);
                }
                "reverse" => {
                    bad["matched_points"][0]["distance_along_edge"] = serde_json::json!(0.9);
                }
                "length" => {
                    bad["edges"][0]["length"] = serde_json::json!(9);
                }
                _ => {
                    bad["edges"][0]["speed_limit"] = serde_json::json!(255);
                }
            }
            assert_eq!(
                decode_posted_limits(&bad, &points).unwrap(),
                vec![None],
                "{changed}"
            );
        }
        let mut bad = fixture.clone();
        bad["units"] = serde_json::json!("miles");
        assert!(decode_posted_limits(&bad, &points).is_err());
        let mut bad = fixture;
        bad["matched_points"] = serde_json::json!([]);
        assert!(decode_posted_limits(&bad, &points).is_err());
    }
    #[test]
    fn crossing_known_limit_change_caps_at_lower_limit_and_unknown_is_not_filled() {
        let points = [
            Point {
                lat: 52.0,
                lon: 13.0,
            },
            Point {
                lat: 52.0001,
                lon: 13.0,
            },
        ];
        let mut json = serde_json::json!({"units":"kilometers","edges":[{"speed_limit":72,"length":0.011119508},{"speed_limit":36,"length":0.011119508}],"matched_points":[{"lat":52.0,"lon":13.0,"type":"matched","edge_index":0,"distance_along_edge":0.5,"distance_from_trace_point":0.0},{"lat":52.0001,"lon":13.0,"type":"matched","edge_index":1,"distance_along_edge":0.5,"distance_from_trace_point":0.0}]});
        assert_eq!(
            decode_posted_limits(&json, &points).unwrap(),
            vec![Some(10.0)]
        );
        json["edges"][1]["speed_limit"] = serde_json::json!(0);
        assert_eq!(decode_posted_limits(&json, &points).unwrap(), vec![None]);
    }
    fn fixture() -> Value {
        serde_json::json!({"code":"Ok","routes":[{"distance":111.2,"duration":80.0,"geometry":{"type":"LineString","coordinates":[[13.0,52.0],[13.0,52.001]]}}]})
    }
    fn endpoints() -> (Point, Point) {
        (
            Point {
                lat: 52.0,
                lon: 13.0,
            },
            Point {
                lat: 52.001,
                lon: 13.0,
            },
        )
    }
    #[test]
    fn allows_only_provider_resource_shapes() {
        for path in [
            "/planet",
            "/planet/latest",
            "/planet/20260927_080001_pt/14/8000/5000.pbf",
            "/sprites/ofm_f384/ofm@2x.png",
            "/fonts/Noto%20Sans%20Regular/0-255.pbf",
            "/styles/liberty",
            "/natural_earth/ne2sr/4/1/2.png",
        ] {
            assert!(
                validated_map_url(&format!("https://tiles.openfreemap.org{path}")).is_ok(),
                "{path}"
            );
        }
        for url in [
            "http://tiles.openfreemap.org/planet",
            "https://tiles.openfreemap.org.evil.test/planet",
            "https://tiles.openfreemap.org@evil.test/planet",
            "https://user@tiles.openfreemap.org/planet",
            "https://127.0.0.1/planet",
            "https://tiles.openfreemap.org/planet/../secret",
            "https://tiles.openfreemap.org/fonts/x%2fsecret/0-255.pbf",
            "https://tiles.openfreemap.org/planet?v=1",
            "https://tiles.openfreemap.org/planet#x",
            "https://tiles.openfreemap.org/planet/latest/2/4/0.pbf",
            "https://tiles.openfreemap.org/planet/latest/15/0/0.pbf",
            "https://tiles.openfreemap.org/fonts/x/1-256.pbf",
            "https://tiles.openfreemap.org/anything",
        ] {
            assert!(validated_map_url(url).is_err(), "{url}");
        }
    }
    #[test]
    fn validates_routes_without_custom_path_relabeling() {
        let (a, b) = endpoints();
        let result = decode_route(&fixture(), a, b, "WALK".into()).unwrap();
        assert_eq!(result.points.len(), 2);
        assert_eq!(result.profile, "WALK");
        assert_eq!(result.provider, "FOSSGIS OSRM");
    }
    #[test]
    fn rejects_remote_error_text_and_invalid_geometry() {
        let (a, b) = endpoints();
        let mut value = fixture();
        value["code"] = Value::String("NoRoute".into());
        value["message"] = Value::String("sensitive URL".into());
        assert_eq!(
            decode_route(&value, a, b, "DRIVE".into()).err().as_deref(),
            Some("NO_ROUTE")
        );
        value = fixture();
        value["routes"][0]["geometry"]["coordinates"][1] = serde_json::json!([13.0, 91.0]);
        assert!(decode_route(&value, a, b, "WALK".into()).is_err());
        value = fixture();
        value["routes"][0]["geometry"]["type"] = Value::String("Polygon".into());
        assert!(decode_route(&value, a, b, "WALK".into()).is_err());
    }
    #[test]
    fn rejects_distant_snaps_and_too_many_points() {
        let (a, b) = endpoints();
        assert_eq!(
            decode_route(
                &fixture(),
                Point {
                    lat: 51.0,
                    lon: 13.0
                },
                b,
                "WALK".into()
            )
            .err()
            .as_deref(),
            Some("ROUTE_ENDPOINT_TOO_FAR")
        );
        let mut value = fixture();
        value["routes"][0]["geometry"]["coordinates"] =
            Value::Array(vec![serde_json::json!([13.0, 52.0]); MAX_POINTS + 1]);
        assert_eq!(
            decode_route(&value, a, b, "WALK".into()).err().as_deref(),
            Some("ROUTE_TOO_COMPLEX")
        );
    }
    #[test]
    fn rejects_large_segment_and_unreasonable_distances() {
        let (a, b) = endpoints();
        let mut value = fixture();
        value["routes"][0]["geometry"]["coordinates"] =
            serde_json::json!([[13.0, 52.0], [14.0, 54.0], [13.0, 52.001]]);
        assert_eq!(
            decode_route(&value, a, b, "DRIVE".into()).err().as_deref(),
            Some("INVALID_ROUTE_GEOMETRY")
        );
        value = fixture();
        value["routes"][0]["distance"] = serde_json::json!(-1.0);
        assert!(decode_route(&value, a, b, "WALK".into()).is_err());
        value = fixture();
        value["routes"][0]["distance"] = serde_json::json!(2_000_000.0);
        assert!(decode_route(&value, a, b, "WALK".into()).is_err());
    }
    #[test]
    fn limits_body_after_decompression() {
        assert_eq!(
            read_bounded(&b"12345"[..], 4, "TOO_BIG").unwrap_err(),
            "TOO_BIG"
        );
        assert_eq!(read_bounded(&b"1234"[..], 4, "TOO_BIG").unwrap(), b"1234");
    }
    #[test]
    fn cache_respects_no_store_and_server_expiry() {
        assert_eq!(cache_ttl(Some("public, max-age=3600")).as_secs(), 3600);
        assert!(cache_ttl(Some("max-age=3600, no-store")).is_zero());
        assert!(cache_ttl(Some("no-cache")).is_zero());
        let mut cache = ResourceCache::default();
        cache.insert("a".into(), vec![1], Duration::from_secs(5));
        assert_eq!(cache.get("a").unwrap().bytes, vec![1]);
        cache.insert("b".into(), vec![2], Duration::ZERO);
        assert!(cache.get("b").is_none());
    }
    #[test]
    fn shared_rate_gate_fails_without_queuing_bursts() {
        let gate = RateGate::default();
        assert!(gate.reserve().is_ok());
        assert_eq!(gate.reserve().unwrap_err(), "RATE_LIMITED");
    }
    #[test]
    fn normalizes_vetted_search_providers_and_rejects_bad_coordinates() {
        let nominatim =
            serde_json::json!([{"display_name":"Berlin, Germany","lat":"52.52","lon":"13.405"}]);
        let result = decode_search(&nominatim, "NOMINATIM").unwrap();
        assert_eq!(result[0].name, "Berlin, Germany");
        let photon = serde_json::json!({"features":[{"geometry":{"type":"Point","coordinates":[13.405,52.52]},"properties":{"name":"Berlin","country":"Germany"}}]});
        assert_eq!(decode_search(&photon, "PHOTON").unwrap()[0].lon, 13.405);
        assert!(decode_search(&nominatim, "ARBITRARY_URL").is_err());
        let invalid = serde_json::json!([{"display_name":"Place","lat":"NaN","lon":"13.405"}]);
        assert!(decode_search(&invalid, "NOMINATIM").is_err());
        let oversized =
            serde_json::json!([{"display_name":"x".repeat(501),"lat":"52.52","lon":"13.405"}]);
        assert!(decode_search(&oversized, "NOMINATIM").is_err());
    }
    #[test]
    fn clearing_invalidates_memory_without_resetting_provider_rate_limits() {
        let network = Network::new();
        network
            .cache
            .lock()
            .unwrap()
            .insert("tile".into(), vec![1, 2, 3], Duration::from_secs(60));
        network.searches.lock().unwrap().insert(
            "query".into(),
            CachedSearch {
                results: vec![Place {
                    name: "Synthetic".into(),
                    lat: 0.0,
                    lon: 0.0,
                }],
                expires: Instant::now() + Duration::from_secs(60),
            },
        );
        network.route_gate.reserve().unwrap();
        let generation = network.generation.load(Ordering::Acquire);
        network.clear().unwrap();
        assert_ne!(network.generation.load(Ordering::Acquire), generation);
        assert_eq!(network.cache.lock().unwrap().size, 0);
        assert!(network.searches.lock().unwrap().is_empty());
        assert_eq!(network.route_gate.reserve().unwrap_err(), "RATE_LIMITED");
    }
    #[test]
    fn airport_radius_requires_exact_eligible_catalog_endpoint() {
        let airport: Vec<CatalogAirport> =
            serde_json::from_str(include_str!("../../ui/resources/airports.json")).unwrap();
        let airport = airport
            .iter()
            .find(|airport| {
                !airport.closed
                    && airport.scheduled_service
                    && airport.airport_type == "large_airport"
            })
            .unwrap();
        let location = Point {
            lat: airport.lat,
            lon: airport.lon,
        };
        assert_eq!(
            endpoint_snap_limit(Some(&airport.id), location).unwrap(),
            5_000.0
        );
        assert_eq!(endpoint_snap_limit(None, location).unwrap(), 250.0);
        let mismatched = Point {
            lat: location.lat + 0.001,
            lon: location.lon,
        };
        assert_eq!(
            endpoint_snap_limit(Some(&airport.id), mismatched).unwrap_err(),
            "AIRPORT_ENDPOINT_MISMATCH"
        );
        assert_eq!(
            endpoint_snap_limit(Some("unknown-airport"), location).unwrap_err(),
            "INVALID_AIRPORT_ID"
        );
        assert_eq!(
            endpoint_snap_limit(Some("bad\nairport"), location).unwrap_err(),
            "INVALID_AIRPORT_ID"
        );
    }
    #[test]
    fn jackson_regional_is_not_an_eligible_flight_airport() {
        let airports: Vec<CatalogAirport> =
            serde_json::from_str(include_str!("../../ui/resources/airports.json")).unwrap();
        let regional = airports.iter().find(|a| a.id == "MKL").unwrap();
        assert_eq!(
            endpoint_snap_limit(
                Some("MKL"),
                Point {
                    lat: regional.lat,
                    lon: regional.lon
                }
            )
            .unwrap_err(),
            "INVALID_AIRPORT_ID"
        );
    }
    #[test]
    fn larger_airport_snap_never_enlarges_the_ordinary_endpoint() {
        let (origin, destination) = endpoints();
        let airport_origin = Point {
            lat: origin.lat - 0.01,
            lon: origin.lon,
        };
        assert_eq!(
            decode_route(&fixture(), airport_origin, destination, "DRIVE".into())
                .err()
                .as_deref(),
            Some("ROUTE_ENDPOINT_TOO_FAR")
        );
        let route = decode_route_with_limits(
            &fixture(),
            airport_origin,
            destination,
            "DRIVE".into(),
            5_000.0,
            250.0,
        )
        .unwrap();
        assert_eq!(route.max_snap_origin_meters, 5_000.0);
        assert_eq!(route.max_snap_destination_meters, 250.0);
        let ordinary_destination = Point {
            lat: destination.lat + 0.01,
            lon: destination.lon,
        };
        assert_eq!(
            decode_route_with_limits(
                &fixture(),
                airport_origin,
                ordinary_destination,
                "DRIVE".into(),
                5_000.0,
                250.0
            )
            .err()
            .as_deref(),
            Some("ROUTE_ENDPOINT_TOO_FAR")
        );
        let distant_airport = Point {
            lat: origin.lat - 0.1,
            lon: origin.lon,
        };
        assert_eq!(
            decode_route_with_limits(
                &fixture(),
                distant_airport,
                destination,
                "DRIVE".into(),
                5_000.0,
                250.0
            )
            .err()
            .as_deref(),
            Some("ROUTE_ENDPOINT_TOO_FAR")
        );
    }
    #[test]
    fn nearby_filters_category_geometry_name_radius_and_limits() {
        let center = Point {
            lat: 41.8781,
            lon: -87.6298,
        };
        let feature = |name: &str, key: &str, value: &str, lat: f64, lon: f64| serde_json::json!({"geometry":{"type":"Point","coordinates":[lon,lat]},"properties":{"name":name,"osm_key":key,"osm_value":value}});
        let good = feature("Public hotel", "tourism", "hotel", center.lat, center.lon);
        let result = decode_nearby(&serde_json::json!({"features":[good,feature("wrong","amenity","cafe",center.lat,center.lon),feature("remote","tourism","hotel",0.0,0.0),feature("bad\n","tourism","hotel",center.lat,center.lon)]}),center,PoiCategory::Hotel).unwrap();
        assert_eq!(result.len(), 1);
        assert!(serde_json::from_value::<PoiCategory>(serde_json::json!("OTHER")).is_err());
        assert!(
            serde_json::from_value::<Point>(serde_json::json!({"lat":0,"lon":0,"token":"x"}))
                .is_err()
        );
        let many = vec![feature("Public hotel", "tourism", "hotel", center.lat, center.lon); 20];
        assert!(decode_nearby(
            &serde_json::json!({"features":many}),
            center,
            PoiCategory::Hotel
        )
        .is_err());
        assert!(decode_nearby(&serde_json::json!({"features":[feature("closed","shop","vacant",center.lat,center.lon),feature("empty","shop","",center.lat,center.lon)]}),center,PoiCategory::Shops).unwrap().is_empty());
    }
}

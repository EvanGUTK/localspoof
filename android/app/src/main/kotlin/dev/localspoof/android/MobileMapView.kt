package dev.localspoof.android

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.View
import android.webkit.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import dev.localspoof.domain.*
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream

internal class MobileMapView(context: Context, private val network: MobileMapNetwork, private val viewport: (MapViewport) -> Unit = {}, private val select: (GeoPoint) -> Unit) : WebView(context) {
    private var ready = false
    private var started = false
    private val startWhenLaidOut = Runnable {
        if (!disposed && !started && isAttachedToWindow && width > 0 && height > 0) {
            started = true
            loadUrl("$ORIGIN/mobile-map/index.html")
        }
    }
    @Volatile private var disposed = false
    private var pendingState = "{}"
    private var pendingPlaces = "[]"
    private var pendingMotion = "{}"
    init {
        setBackgroundColor(android.graphics.Color.rgb(16, 27, 34))
        isSaveEnabled = false; isSaveFromParentEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        settings.apply {
            javaScriptEnabled = true; domStorageEnabled = false
            allowFileAccess = false; allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false)
            safeBrowsingEnabled = true; mediaPlaybackRequiresUserGesture = true
            blockNetworkLoads = true
        }
        CookieManager.getInstance().setAcceptCookie(false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webChromeClient = object : WebChromeClient() { override fun onConsoleMessage(message: ConsoleMessage?) = true }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (!disposed && request?.isForMainFrame == true) {
                    parseViewport(request.url)?.let { viewport(it) }
                    parseSelection(request.url)?.let { select(it) }
                }
                return true
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!disposed && url == "$ORIGIN/mobile-map/index.html") { ready = true; clearHistory(); push() }
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                val uri = request?.url
                if (disposed || request?.method != "GET" || uri == null || !isAppOrigin(uri) || uri.query != null || uri.fragment != null) return response(ByteArray(0), "text/plain", 403)
                return try {
                    val path = uri.encodedPath ?: ""
                    if (path.startsWith("/map/")) {
                        val remote = "/" + path.removePrefix("/map/")
                        val bytes = network.map(remote)
                        response(bytes, when { remote.endsWith(".png") -> "image/png"; remote.endsWith(".pbf") -> "application/octet-stream"; else -> "application/json" })
                    } else {
                        require(path in assetPaths)
                        val bytes = context.assets.open(path.removePrefix("/")).use { it.readBytes() }
                        response(bytes, when { path.endsWith(".html") -> "text/html"; path.endsWith(".css") -> "text/css"; path.endsWith(".mjs") || path.endsWith(".js") -> "text/javascript"; else -> "application/json" })
                    }
                } catch (_: Exception) { response(ByteArray(0), "text/plain", 503) }
            }
            override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) { handler?.cancel() }
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(startWhenLaidOut) }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!disposed && w > 0 && h > 0) {
            if (!started) post(startWhenLaidOut)
            else if (ready) evaluateJavascript("window.resizeMapViewport?.()", null)
        }
    }
    override fun onDetachedFromWindow() { removeCallbacks(startWhenLaidOut); super.onDetachedFromWindow() }
    fun update(value: String) { if (!disposed && value != pendingState) { pendingState = value; push() } }
    fun updateMotion(value: String) { if (!disposed && value != pendingMotion) { pendingMotion = value; if (ready) evaluateJavascript("window.pendingMapMotion=$pendingMotion;window.updateMapMotion?.(window.pendingMapMotion)", null) } }
    fun updatePlaces(value: String) { if (!disposed && value != pendingPlaces) { pendingPlaces = value; push() } }
    private fun push() { if (ready && !disposed) evaluateJavascript("window.pendingMapState=$pendingState;window.updateMapState?.(window.pendingMapState);window.pendingMapMotion=$pendingMotion;window.updateMapMotion?.(window.pendingMapMotion);window.pendingNearbyPlaces=$pendingPlaces;window.updateNearbyPlaces?.(window.pendingNearbyPlaces)", null) }
    fun disposeMap() { if (disposed) return; disposed = true; removeCallbacks(startWhenLaidOut); network.close(); stopLoading(); pendingState = "{}"; pendingPlaces = "[]"; pendingMotion = "{}"; clearHistory(); clearCache(true); removeAllViews(); destroy() }
    companion object {
        const val ORIGIN = "https://localspoof.invalid"
        private val assetPaths = setOf("/mobile-map/index.html", "/mobile-map/map.css", "/mobile-map/map.mjs", "/mobile-map/motion.mjs", "/mobile-map/bootstrap.js", "/mobile-map/world-style.json", "/mobile-map/vendor/maplibre-gl.mjs", "/mobile-map/vendor/maplibre-gl-shared.mjs", "/mobile-map/vendor/maplibre-gl-worker.mjs", "/mobile-map/vendor/maplibre-gl.css")
        fun isAppOrigin(uri: Uri) = uri.scheme == "https" && uri.host == "localspoof.invalid" && uri.port == -1 && uri.userInfo == null
        fun parseViewport(uri: Uri): MapViewport? = runCatching {
            require(isAppOrigin(uri) && uri.encodedPath == "/viewport" && uri.fragment == null && uri.queryParameterNames == setOf("lat", "lon", "zoom") && uri.toString().length <= 220)
            require(listOf("lat", "lon", "zoom").all { uri.getQueryParameters(it).size == 1 })
            val zoom = requireNotNull(uri.getQueryParameter("zoom")).toDouble()
            require(zoom.isFinite() && zoom in 0.0..24.0)
            MapViewport(GeoPoint(requireNotNull(uri.getQueryParameter("lat")).toDouble(), requireNotNull(uri.getQueryParameter("lon")).toDouble()), zoom)
        }.getOrNull()
        fun parseSelection(uri: Uri): GeoPoint? = runCatching {
            require(isAppOrigin(uri) && uri.path == "/select" && uri.fragment == null && uri.queryParameterNames == setOf("lat", "lon") && uri.toString().length <= 180)
            require(uri.getQueryParameters("lat").size == 1 && uri.getQueryParameters("lon").size == 1)
            GeoPoint(requireNotNull(uri.getQueryParameter("lat")).toDouble(), requireNotNull(uri.getQueryParameter("lon")).toDouble())
        }.getOrNull()
        private fun response(bytes: ByteArray, mime: String, status: Int = 200) = WebResourceResponse(mime, if (mime.startsWith("text/") || mime == "application/json") "UTF-8" else null, status, if (status == 200) "OK" else "Unavailable", mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff", "Access-Control-Allow-Origin" to ORIGIN), ByteArrayInputStream(bytes))
    }
}

private fun mapJson(value: JsonObject) = value.toString().replace("<", "\\u003c").replace(">", "\\u003e").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
internal fun mobileMapState(phone: PhoneView, draft: MobileDraft): String = mapJson(JsonObject(mobileMapGeometry(phone, draft) + mobileMapMotion(phone, false, 0)))
private fun mobileMapGeometry(phone: PhoneView, draft: MobileDraft): JsonObject {
    val preview = phone.preview ?: phone.snapshot.preparedPreview
    fun point(value: GeoPoint) = buildJsonObject { put("lat", value.lat); put("lon", value.lon) }
    fun draftPoint(lat: String, lon: String) = runCatching { GeoPoint(lat.toDouble(), lon.toDouble()) }.getOrNull()
    return buildJsonObject {
        val origin = preview?.geometry?.firstOrNull() ?: draftPoint(draft.originLat, draft.originLon)
        val destination = preview?.geometry?.lastOrNull() ?: draftPoint(draft.destinationLat, draft.destinationLon)
        origin?.let { put("origin", point(it)) }; destination?.let { put("destination", point(it)) }
        put("route", buildJsonArray { preview?.geometry?.take(20000)?.forEach { add(point(it)) } })
        put("segments", buildJsonArray { preview?.segments?.forEach { segment -> add(buildJsonObject { put("kind", segment.kind.name); put("points", buildJsonArray { segment.points.take(20000).forEach { add(point(it)) } }) }) } })
        put("stops", buildJsonArray { preview?.stops?.take(32)?.forEach { add(point(it.point)) } })
    }
}
private fun mobileMapMotion(phone: PhoneView, follow: Boolean, fitRevision: Long): JsonObject = buildJsonObject {
    val location = phone.phoneLocation?.takeIf { it.ageMs in 0..30000 }
    location?.let { put("current", buildJsonObject { put("lat", it.point.lat); put("lon", it.point.lon) }) }
    put("sequence", location?.sampleSequence ?: 0); put("ageMs", location?.ageMs ?: 30001)
    put("source", location?.source?.name ?: "NONE"); put("health", phone.motion.health)
    put("speedMps", location?.speedMps?.let(::JsonPrimitive) ?: JsonNull)
    put("owner", "${phone.snapshot.mode?.name ?: "NONE"}:${phone.snapshot.phase?.name ?: "NONE"}:${location?.placementGeneration ?: 0}")
    put("follow", follow); put("fitRevision", fitRevision)
}
internal fun mobileNearbyState(places: List<NearbyPlace>): String = buildJsonArray {
    places.take(8).forEachIndexed { index, place -> add(buildJsonObject { put("lat", place.point.lat); put("lon", place.point.lon); put("number", index + 1); put("color", place.category.color) }) }
}.toString()
@Composable internal fun MobileLiveMap(phone: PhoneView, draft: MobileDraft, modifier: Modifier, places: List<NearbyPlace> = emptyList(), onViewport: (MapViewport) -> Unit = {}, follow: Boolean = false, fitRevision: Long = 0, select: (GeoPoint) -> Unit) {
    val callback by rememberUpdatedState(select)
    val viewportCallback by rememberUpdatedState(onViewport)
    val preview = phone.preview ?: phone.snapshot.preparedPreview
    val geometry = remember(preview, draft.originLat, draft.originLon, draft.destinationLat, draft.destinationLon) { mapJson(mobileMapGeometry(phone, draft)) }
    AndroidView(
        factory = { context -> MobileMapView(context, MobileMapNetwork(), { viewportCallback(it) }) { callback(it) } },
        modifier = modifier.clipToBounds(),
        onReset = null,
        onRelease = { it.disposeMap() },
        update = { it.update(geometry); it.updateMotion(mapJson(mobileMapMotion(phone, follow, fitRevision))); it.updatePlaces(mobileNearbyState(places)) }
    )
}

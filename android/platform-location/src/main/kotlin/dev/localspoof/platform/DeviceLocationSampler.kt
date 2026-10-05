package dev.localspoof.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import dev.localspoof.domain.GeoPoint
import java.util.concurrent.atomic.AtomicBoolean

data class DeviceLocationFix(val point: GeoPoint, val accuracyMeters: Double?, val elapsedRealtimeNanos: Long)

/** Discards cached mock fixes and stale/invalid data; only immutable coordinate metadata survives. */
fun validatedDeviceFix(location: Location, nowNanos: Long): DeviceLocationFix? {
    @Suppress("DEPRECATION") val mock = if (Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider
    if (mock || location.elapsedRealtimeNanos <= 0 || location.elapsedRealtimeNanos > nowNanos || nowNanos - location.elapsedRealtimeNanos > 30_000_000_000L) return null
    if (!location.latitude.isFinite() || location.latitude !in -90.0..90.0 || !location.longitude.isFinite() || location.longitude !in -180.0..180.0) return null
    val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null
    if (accuracy != null && (!accuracy.isFinite() || accuracy <= 0 || accuracy > 10_000)) return null
    return DeviceLocationFix(GeoPoint(location.latitude, location.longitude), accuracy, location.elapsedRealtimeNanos)
}

/** Real-location reader for a visible Activity or foreground service. Close before adding test providers; no history. */
class DeviceLocationSampler(context: Context, private val receive: (DeviceLocationFix) -> Unit) : AutoCloseable {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(LocationManager::class.java)
    private val closed = AtomicBoolean(false)
    private var newestNanos = 0L
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) { accept(location) }
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        @Deprecated("Required by the API 29 listener contract")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }
    @Synchronized private fun accept(location: Location) {
        if (closed.get()) return
        val fix = validatedDeviceFix(Location(location), SystemClock.elapsedRealtimeNanos()) ?: return
        if (fix.elapsedRealtimeNanos <= newestNanos) return
        newestNanos = fix.elapsedRealtimeNanos
        receive(fix)
    }
    @SuppressLint("MissingPermission") // Fine permission is checked before every registration/cached read.
    fun start() {
        if (closed.get() || context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED || !manager.isLocationEnabled) return
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (provider !in manager.allProviders || !manager.isProviderEnabled(provider)) continue
            try {
                manager.getLastKnownLocation(provider)?.let(::accept)
                manager.requestLocationUpdates(provider, 1_000L, 0f, listener, Looper.getMainLooper())
            } catch (_: Exception) { /* Unavailable providers yield no coordinate; no fabricated fallback. */ }
        }
    }
    override fun close() {
        closed.set(true)
        runCatching { manager.removeUpdates(listener) }
        newestNanos = 0
    }
}

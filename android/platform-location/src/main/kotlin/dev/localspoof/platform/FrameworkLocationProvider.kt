package dev.localspoof.platform

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.Criteria
import android.location.LocationManager
import android.os.Process
import android.os.SystemClock
import dev.localspoof.domain.Sample

/** Framework GPS/network adapter. It deliberately preserves the platform mock marker. */
class FrameworkLocationProvider(context: Context) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(LocationManager::class.java)
    private val owned = linkedSetOf<String>()
    private var lastNanos = 0L
    var health: String = "NOT_PREPARED"
        private set

    fun readiness(): String? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return "LOCATION_PERMISSION_REQUIRED"
        if (!manager.isLocationEnabled) return "LOCATION_SERVICES_DISABLED"
        val ops = context.getSystemService(AppOpsManager::class.java)
        if (ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName) != AppOpsManager.MODE_ALLOWED)
            return "SELECT_MOCK_APP_IN_DEVELOPER_OPTIONS"
        return null
    }

    @Suppress("DEPRECATION")
    fun prepare() {
        readiness()?.let { throw IllegalStateException(it) }
        if (owned.isNotEmpty()) return
        try {
            for (name in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                manager.addTestProvider(name, false, false, false, false, true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
                owned.add(name)
                manager.setTestProviderEnabled(name, true)
            }
            health = "PREPARED"
        } catch (_: Exception) {
            cleanup()
            health = "PROVIDER_PREPARE_FAILED"
            throw IllegalStateException(health)
        }
    }

    fun inject(sample: Sample) {
        readiness()?.let { throw IllegalStateException(it) }
        check(owned.isNotEmpty()) { "PROVIDER_NOT_PREPARED" }
        val nanos = maxOf(SystemClock.elapsedRealtimeNanos(), lastNanos + 1)
        for (name in owned) {
            manager.setTestProviderLocation(name, Location(name).apply {
                latitude = sample.point.lat
                longitude = sample.point.lon
                accuracy = sample.accuracyMeters.toFloat()
                speed = sample.speedMps.toFloat()
                bearing = sample.bearingDegrees.toFloat()
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = nanos
            })
        }
        lastNanos = nanos
        health = "SAMPLE_SUBMITTED"
    }

    fun cleanup(): Boolean {
        var succeeded = true
        for (name in owned.toList()) {
            try { manager.removeTestProvider(name); owned.remove(name) }
            catch (_: Exception) { succeeded = false }
        }
        health = if (succeeded) "RELEASED" else "CLEANUP_FAILED_USE_SETTINGS"
        return succeeded
    }

    /** Called only after explicit cold-launch Reset. Mock app op makes these names app-owned. */
    fun reset(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        if (ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName) != AppOpsManager.MODE_ALLOWED)
            return false
        owned.addAll(listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER))
        return cleanup()
    }
}

package dev.localspoof.android

import dev.localspoof.domain.GeoPoint
import dev.localspoof.domain.Sample
import dev.localspoof.platform.DeviceLocationFix

enum class PhoneLocationSource { DEVICE, SIMULATION }
data class PhoneLocation(val point: GeoPoint, val source: PhoneLocationSource, val accuracyMeters: Double?, val ageMs: Long, val speedMps: Double? = null, val bearingDegrees: Double? = null, val sampleSequence: Long = 0, val placementGeneration: Long = 0)

/** Single current point in RAM, owned by the current service adapter. No route/history buffer. */
internal class PhoneLocationState {
    private var owner: Any? = null
    private var point: GeoPoint? = null
    private var source: PhoneLocationSource? = null
    private var accuracy: Double? = null
    private var recordedNanos = 0L
    private var armed = false
    private var speed: Double? = null
    private var bearing: Double? = null
    private var sequence = 0L
    private var sampleTimestamp = -1L
    private var placementGeneration = 0L
    fun attach(newOwner: Any) { clear(); owner = newOwner }
    fun device(expectedOwner: Any, fix: DeviceLocationFix, nowNanos: Long): Boolean {
        if (owner !== expectedOwner || armed || fix.elapsedRealtimeNanos <= recordedNanos || fix.elapsedRealtimeNanos <= 0 || fix.elapsedRealtimeNanos > nowNanos || nowNanos - fix.elapsedRealtimeNanos > 30_000_000_000L) return false
        val fixAccuracy = fix.accuracyMeters
        if (fixAccuracy != null && (!fixAccuracy.isFinite() || fixAccuracy <= 0 || fixAccuracy > 10_000)) return false
        speed = null; bearing = null
        point = fix.point; source = PhoneLocationSource.DEVICE; accuracy = fixAccuracy; recordedNanos = fix.elapsedRealtimeNanos
        return true
    }
    fun preparing(expectedOwner: Any) { if (owner === expectedOwner) { clearPoint(); armed = true } }
    fun submitted(expectedOwner: Any, sample: Sample, submittedAtNanos: Long) {
        if (owner !== expectedOwner || !armed || submittedAtNanos <= 0 || submittedAtNanos < recordedNanos) return
        if (!sample.accuracyMeters.isFinite() || sample.accuracyMeters <= 0 || sample.accuracyMeters > 10_000) return
        if (sample.elapsedRealtimeMs <= sampleTimestamp || sample.elapsedRealtimeMs < 0) return
        if (!sample.speedMps.isFinite() || sample.speedMps < 0 || !sample.bearingDegrees.isFinite()) return
        speed = sample.speedMps; bearing = sample.bearingDegrees; sequence++; sampleTimestamp = sample.elapsedRealtimeMs; placementGeneration = sample.placementGeneration
        point = sample.point; source = PhoneLocationSource.SIMULATION; accuracy = sample.accuracyMeters; recordedNanos = submittedAtNanos
    }
    fun snapshot(nowNanos: Long): PhoneLocation? {
        val current = point ?: return null
        val kind = source ?: return null
        if (nowNanos < recordedNanos) return null
        val age = (nowNanos - recordedNanos) / 1_000_000
        if (nowNanos - recordedNanos > 30_000_000_000L) { clearPoint(); return null }
        return PhoneLocation(current, kind, accuracy, age, speed, bearing, sequence, placementGeneration)
    }
    fun clear(expectedOwner: Any) { if (owner === expectedOwner) clear() }
    fun clear() { owner = null; armed = false; clearPoint() }
    private fun clearPoint() { point = null; source = null; accuracy = null; recordedNanos = 0; speed = null; bearing = null; sequence = 0; sampleTimestamp = -1L; placementGeneration = 0 }
}

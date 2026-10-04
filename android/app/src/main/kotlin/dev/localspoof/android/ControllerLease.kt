package dev.localspoof.android

import java.security.MessageDigest
import java.security.SecureRandom

/** Volatile phone-approved lease, bound to one socket channel and request. Caller holds session lock. */
class ControllerLease(private val randomToken: () -> String = ::secureToken) {
    var enabled = false
        private set
    var pending = false
        private set
    var pairingId: String? = null
        private set
    var comparisonCode: String? = null
        private set
    var sessionId: String? = null
        private set
    private var ownerChannel: String? = null
    private var clientNonce: String? = null
    private var retiredChannel: String? = null
    private var expiresAt = 0L
    private var credential: String? = null
    private var lastSequence = -1L
    private var lastSeen = 0L

    /** Repeated Enable is inert: it cannot rotate or renew approval. */
    fun enable() { enabled = true }
    fun request(channelId: String, nonce: String, now: Long, channelExpiresAt: Long = now + 120_000): Boolean {
        expire(now)
        if (!enabled || channelExpiresAt <= now || !channelId.matches(HEX_ID) || !nonce.matches(HEX_ID) || credential != null) return false
        if (pending) return owns(channelId) && constantEquals(clientNonce, nonce)
        if (constantEquals(retiredChannel, channelId)) return false
        ownerChannel = channelId; clientNonce = nonce
        pairingId = randomToken(); comparisonCode = pairingComparison(nonce, pairingId!!)
        pending = true; expiresAt = minOf(now + 120_000, channelExpiresAt)
        return true
    }
    fun approve(requestId: String, now: Long): Boolean {
        expire(now)
        if (!pending || !constantEquals(pairingId, requestId)) return false
        pending = false; comparisonCode = null
        credential = randomToken(); sessionId = randomToken(); lastSequence = -1; lastSeen = now
        return true
    }
    fun reject(requestId: String): Boolean {
        if (!pending || !constantEquals(pairingId, requestId)) return false
        clearRequest(retire = true); return true
    }
    fun poll(channelId: String, requestId: String, nonce: String, now: Long): String? {
        expire(now)
        return if (owns(channelId) && constantEquals(pairingId, requestId) && constantEquals(clientNonce, nonce)) credential else null
    }
    fun token(): String? = credential
    fun owns(channelId: String): Boolean = constantEquals(ownerChannel, channelId)
    fun pendingRequest(channelId: String, requestId: String, nonce: String): Boolean =
        pending && owns(channelId) && constantEquals(pairingId, requestId) && constantEquals(clientNonce, nonce)
    fun matches(candidate: String, channelId: String): Boolean = owns(channelId) && constantEquals(credential, candidate)
    fun authorize(candidate: String, channelId: String, sequence: Long, now: Long): Boolean {
        expire(now)
        if (!matches(candidate, channelId) || sequence <= lastSequence || sequence < 0) return false
        lastSequence = sequence; lastSeen = now
        clientNonce = null; pairingId = null; comparisonCode = null
        return true
    }
    fun expire(now: Long) {
        if ((credential != null && now - lastSeen > 6_000) || (pending && now >= expiresAt)) clearRequest(retire = true)
    }
    fun disconnect(channelId: String) { if (owns(channelId)) clearRequest(retire = true) }
    fun revoke() { enabled = false; clearRequest(); retiredChannel = null }
    private fun clearRequest(retire: Boolean = false) {
        if (retire) retiredChannel = ownerChannel
        ownerChannel = null; clientNonce = null; pending = false
        pairingId = null; comparisonCode = null; sessionId = null
        credential = null; lastSequence = -1; lastSeen = 0; expiresAt = 0
    }
    private companion object { val HEX_ID = Regex("[a-f0-9]{32}") }
}

internal fun pairingComparison(nonce: String, pairingId: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest("localspoof-pair-v2:$nonce:$pairingId".toByteArray(Charsets.UTF_8))
    val prefix = digest.take(4).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
    return (prefix % 1_000_000).toString().padStart(6, '0')
}
private fun constantEquals(expected: String?, actual: String): Boolean = expected != null &&
    MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
fun secureToken(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }
    .joinToString("") { "%02x".format(it.toInt() and 255) }

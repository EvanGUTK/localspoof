package dev.localspoof.android

import org.junit.Assert.*
import org.junit.Test

class ControllerLeaseTest {
    private val channel = "a".repeat(32)
    private val otherChannel = "b".repeat(32)
    private val nonce = "c".repeat(32)
    private val nextNonce = "d".repeat(32)
    private fun lease(): ControllerLease {
        var counter = 0
        return ControllerLease { (++counter).toString(16).padStart(32, '0') }.apply { enable() }
    }
    private fun approved(lease: ControllerLease, now: Long = 0): String {
        assertTrue(lease.request(channel, nonce, now))
        assertTrue(lease.approve(lease.pairingId!!, now))
        return lease.token()!!
    }
    @Test fun phoneApprovalIsRequiredAndComparisonMatchesIndependentDigestVector() {
        val lease = lease()
        assertTrue(lease.request(channel, nonce, 0)); assertNull(lease.token())
        assertEquals("579142", lease.comparisonCode)
        assertTrue(lease.approve(lease.pairingId!!, 1)); assertNotNull(lease.token())
        val zeroNonce = "0".repeat(32)
        assertEquals("326622", pairingComparison(zeroNonce, "1".repeat(32)))
        assertEquals("077916", pairingComparison(zeroNonce, "f".repeat(32)))
        assertEquals("044717", pairingComparison(zeroNonce, "4".padStart(32, '0')))
    }
    @Test fun enablingAlreadyEnabledControlPreservesPendingAndApprovedLease() {
        val lease = lease(); lease.request(channel, nonce, 0)
        val id = lease.pairingId; val code = lease.comparisonCode
        lease.enable(); assertEquals(id, lease.pairingId); assertEquals(code, lease.comparisonCode); assertTrue(lease.pending)
        lease.approve(id!!, 1)
        val token = lease.token(); val session = lease.sessionId
        lease.enable(); assertEquals(token, lease.token()); assertEquals(session, lease.sessionId)
    }
    @Test fun sameRequestIsIdempotentAndCannotExtendItsDeadline() {
        val lease = lease(); assertTrue(lease.request(channel, nonce, 0))
        val id = lease.pairingId; val code = lease.comparisonCode
        assertTrue(lease.request(channel, nonce, 119_999))
        assertEquals(id, lease.pairingId); assertEquals(code, lease.comparisonCode)
        assertFalse(lease.approve(id!!, 120_000)); assertNull(lease.token())
        assertFalse(lease.request(channel, nonce, 120_001))
    }
    @Test fun pendingOrApprovedRequestCannotBeReplacedByAnotherNonceOrChannel() {
        val lease = lease(); lease.request(channel, nonce, 0); val id = lease.pairingId
        assertFalse(lease.request(channel, nextNonce, 1)); assertFalse(lease.request(otherChannel, nonce, 1))
        assertEquals(id, lease.pairingId); lease.approve(id!!, 2)
        assertFalse(lease.request(channel, nonce, 3)); assertFalse(lease.request(otherChannel, nextNonce, 3))
    }
    @Test fun tokenDeliveryAndAuthorizationRequireTheOriginalSocketAndNonce() {
        val lease = lease(); val token = approved(lease); val id = lease.pairingId!!
        assertNull(lease.poll(otherChannel, id, nonce, 1)); assertNull(lease.poll(channel, id, nextNonce, 1))
        assertEquals(token, lease.poll(channel, id, nonce, 1)); assertFalse(lease.matches(token, otherChannel))
        assertFalse(lease.authorize(token, otherChannel, 1, 2)); assertTrue(lease.authorize(token, channel, 1, 2))
        assertFalse(lease.authorize(token, channel, 1, 3)); assertNull(lease.poll(channel, id, nonce, 3))
    }
    @Test fun staleApproveAndDenyEventsCannotAffectReplacementRequest() {
        val lease = lease(); lease.request(channel, nonce, 0); val staleId = lease.pairingId!!
        assertTrue(lease.reject(staleId)); assertFalse(lease.request(channel, nextNonce, 1))
        assertTrue(lease.request(otherChannel, nextNonce, 1)); val currentId = lease.pairingId
        assertFalse(lease.approve(staleId, 2)); assertFalse(lease.reject(staleId))
        assertEquals(currentId, lease.pairingId); assertTrue(lease.pending)
    }
    @Test fun onlyOwningChannelDisconnectCanDiscardItsPendingOrApprovedState() {
        val lease = lease(); lease.request(channel, nonce, 0)
        lease.disconnect(otherChannel); assertTrue(lease.pending)
        lease.disconnect(channel); assertFalse(lease.pending); assertTrue(lease.enabled)
        assertTrue(lease.request(otherChannel, nextNonce, 1)); lease.approve(lease.pairingId!!, 2)
        val token = lease.token(); lease.disconnect(channel); assertEquals(token, lease.token())
        lease.disconnect(otherChannel); assertNull(lease.token())
    }
    @Test fun enablingLaterDoesNotRefreshHeartbeatExpiryOrRestoreRevokedCredential() {
        val lease = lease(); approved(lease); lease.enable(); lease.expire(6_001)
        assertNull(lease.token()); assertTrue(lease.enabled); assertFalse(lease.request(channel, nextNonce, 6_002))
        assertTrue(lease.request(otherChannel, nextNonce, 6_002)); lease.revoke(); assertFalse(lease.enabled)
        assertFalse(lease.request(channel, nonce, 6_003))
    }
    @Test fun lowercaseFixedSizeIdsAndExplicitOptInAreRequired() {
        val lease = ControllerLease(); assertFalse(lease.request(channel, nonce, 0)); lease.enable()
        for (invalid in listOf("C".repeat(32), "c".repeat(31), "g".repeat(32), "")) {
            assertFalse(lease.request(channel, invalid, 1)); assertFalse(lease.request(invalid, nonce, 1))
        }
        assertNull(lease.token()); assertFalse(lease.pending)
    }
    @Test fun enabledControlHasNoGlobalExpiryButEachChannelAndRequestRemainBounded() {
        val lease = lease()
        assertTrue(lease.request(channel, nonce, 86_400_000, 86_400_500))
        val id = lease.pairingId!!
        assertFalse(lease.approve(id, 86_400_500)); assertTrue(lease.enabled)
        assertTrue(lease.request(otherChannel, nextNonce, 86_400_501))
    }
}

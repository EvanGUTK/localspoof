package dev.localspoof.android

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

/** Deterministic socket-readiness/clock fixtures; no phone socket, credentials or sleeps. */
class ControlFrameReaderTest {
    private class Fixture {
        var now = 0L
        var idleUntil = 0L
        var running = true
        val waits = mutableListOf<Int>()
        val readTimeouts = mutableListOf<Int>()
        var connectionBudget: () -> Long = { if (running) 120_000L - now else 0L }
        fun reader() = ControlFrameReader(16, { now }, { connectionBudget() }, { timeout ->
            waits.add(timeout)
            if (now < idleUntil) { now += minOf(timeout.toLong(), idleUntil - now); false } else true
        }, { readTimeouts.add(it) })
        fun stream(bytes: ByteArray, bytesPerRead: Int = bytes.size, readMillis: Long = 0) = object : InputStream() {
            var offset = 0
            override fun read(): Int = error("Use bounded bulk reads")
            override fun read(target: ByteArray, start: Int, length: Int): Int {
                now += readMillis
                if (offset == bytes.size) return -1
                val count = minOf(length, bytesPerRead, bytes.size - offset)
                bytes.copyInto(target, start, offset, offset + count); offset += count
                return count
            }
        }
    }
    private fun frame(bytes: ByteArray) = ByteBuffer.allocate(4 + bytes.size).putInt(bytes.size).put(bytes).array()
    private fun rejects(reason: String, action: () -> Unit) {
        val error = assertThrows(IllegalArgumentException::class.java) { action() }
        assertEquals(reason, error.message)
    }

    @Test fun typingLongerThanFiveSecondsDoesNotConsumeTheFrameDeadline() {
        val fixture = Fixture().apply { idleUntil = 7_000 }
        val body = byteArrayOf(1, 2, 3)
        assertArrayEquals(body, fixture.reader().readFrame(fixture.stream(frame(body), 1, 100)))
        assertEquals(7, fixture.waits.count { it == 1_000 } - 1)
        assertEquals(5_000, fixture.readTimeouts.first())
        assertTrue(fixture.now > 7_000)
    }
    @Test fun idlePollingCannotExtendTheAbsolutePreauthenticationDeadline() {
        val fixture = Fixture().apply { idleUntil = 130_000 }
        rejects("CONNECTION_TIMEOUT") { fixture.reader().readFrame(ByteArrayInputStream(frame(byteArrayOf(1)))) }
        assertEquals(120_000L, fixture.now)
        assertEquals(120, fixture.waits.size)
        assertTrue(fixture.readTimeouts.isEmpty())
    }
    @Test fun incompleteHeaderHasOneAbsoluteFiveSecondDeadline() {
        val fixture = Fixture()
        rejects("FRAME_TIMEOUT") { fixture.reader().readFrame(fixture.stream(frame(byteArrayOf(1)), 1, 1_400)) }
        assertEquals(listOf(5_000, 3_600, 2_200, 800), fixture.readTimeouts)
    }
    @Test fun payloadFragmentsCannotResetTheHeaderDeadline() {
        val fixture = Fixture()
        rejects("FRAME_TIMEOUT") { fixture.reader().readFrame(fixture.stream(frame(ByteArray(8)), 1, 500)) }
        assertEquals(5_000L, fixture.now)
        assertEquals(500, fixture.readTimeouts.last())
    }
    @Test fun preauthLimitAlsoBoundsAnAlreadyStartedFrame() {
        val fixture = Fixture().apply { now = 119_500 }
        rejects("CONNECTION_TIMEOUT") { fixture.reader().readFrame(fixture.stream(frame(byteArrayOf(1)), 1, 500)) }
        assertEquals(listOf(500), fixture.readTimeouts)
    }
    @Test fun idleReadinessChecksDoNotRefreshTheSixSecondCredentialLease() {
        val fixture = Fixture().apply { idleUntil = 10_000 }
        val lease = ControllerLease { "a".repeat(32) }.apply {
            enable(); request("a".repeat(32), "b".repeat(32), 0); approve(pairingId!!, 0)
        }
        val deadline = ControlConnectionDeadline(0, "a".repeat(32))
        fixture.connectionBudget = {
            deadline.remainingMillis(fixture.now, lease, fixture.running)
        }
        rejects("CONNECTION_TIMEOUT") { fixture.reader().readFrame(ByteArrayInputStream(frame(byteArrayOf(1)))) }
        assertNull(lease.token())
        assertEquals(7_000L, fixture.now)
        assertTrue(fixture.readTimeouts.isEmpty())
    }
    @Test fun cancellationEndsIdleWaitWithoutReadingAPayload() {
        val fixture = Fixture().apply { running = false }
        rejects("CONNECTION_TIMEOUT") { fixture.reader().readFrame(ByteArrayInputStream(frame(byteArrayOf(1)))) }
        assertTrue(fixture.waits.isEmpty())
        assertTrue(fixture.readTimeouts.isEmpty())
    }
    @Test fun revokedCredentialCannotFallBackToTheLongerPreauthWindow() {
        val fixture = Fixture()
        val lease = ControllerLease { "a".repeat(32) }.apply {
            enable(); request("a".repeat(32), "b".repeat(32), 0); approve(pairingId!!, 0)
        }
        val deadline = ControlConnectionDeadline(0, "a".repeat(32))
        val reader = ControlFrameReader(16, { fixture.now }, {
            deadline.remainingMillis(fixture.now, lease, fixture.running)
        }, { lease.revoke(); fixture.now += it; false }, { fail("Revocation must precede any payload read") })
        rejects("CONNECTION_TIMEOUT") { reader.readFrame(ByteArrayInputStream(frame(byteArrayOf(1)))) }
        assertNull(lease.token())
    }
    @Test fun invalidLengthsAndTruncatedFramesFailClosed() {
        for (length in listOf(-1, 0, 17, Int.MAX_VALUE)) {
            val fixture = Fixture()
            rejects("INVALID_FRAME_LENGTH") { fixture.reader().readFrame(ByteArrayInputStream(ByteBuffer.allocate(4).putInt(length).array())) }
        }
        val fixture = Fixture()
        rejects("INCOMPLETE_FRAME") { fixture.reader().readFrame(ByteArrayInputStream(frame(byteArrayOf(1, 2)).dropLast(1).toByteArray())) }
        rejects("INCOMPLETE_FRAME") { Fixture().reader().readFrame(ByteArrayInputStream(byteArrayOf())) }
    }
    @Test fun anotherChannelsApprovedLeaseCannotExtendThisChannelsPreauthDeadline() {
        val lease = ControllerLease { "a".repeat(32) }.apply {
            enable(); request("a".repeat(32), "c".repeat(32), 130_000); approve(pairingId!!, 130_000)
        }
        val token = lease.token()
        assertTrue(ControlConnectionDeadline(0, "b".repeat(32)).remainingMillis(130_000, lease, true) <= 0)
        assertEquals(token, lease.token())
    }
}

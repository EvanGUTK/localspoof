package dev.localspoof.android

import java.io.InputStream
import java.nio.ByteBuffer

/** Call under the lease's session lock; idle polling never renews credentials. */
internal class ControlConnectionDeadline(private val acceptedAt: Long, private val channelId: String) {
    private var authenticated = false

    fun remainingMillis(now: Long, lease: ControllerLease, running: Boolean): Long {
        if (lease.owns(channelId) && lease.token() != null) authenticated = true
        lease.expire(now)
        return when {
            !running -> 0L
            lease.owns(channelId) && lease.token() != null -> Long.MAX_VALUE
            authenticated -> 0L
            else -> 120_000 - (now - acceptedAt)
        }
    }
}

/** Idle readiness is separate from the absolute deadline for an already arriving frame. */
internal class ControlFrameReader(
    private val maxFrame: Int,
    private val now: () -> Long,
    private val remainingConnectionMillis: () -> Long,
    private val awaitReadable: (Int) -> Boolean,
    private val setReadTimeout: (Int) -> Unit,
) {
    init { require(maxFrame in 1..PhoneControlServer.MAX_FRAME) }

    fun readFrame(input: InputStream): ByteArray {
        // LocalSocket receive timeouts throw plain IOException on Android. Poll readiness
        // instead, so a user comparing and approving a request is not mistaken for a partial frame.
        while (true) {
            val remaining = remainingConnectionMillis()
            require(remaining > 0) { "CONNECTION_TIMEOUT" }
            if (awaitReadable(minOf(remaining, 1_000).toInt())) break
        }
        val frameDeadline = now() + 5_000
        fun readExactly(target: ByteArray) {
            var offset = 0
            while (offset < target.size) {
                val connectionRemaining = remainingConnectionMillis()
                require(connectionRemaining > 0) { "CONNECTION_TIMEOUT" }
                val frameRemaining = frameDeadline - now()
                require(frameRemaining > 0) { "FRAME_TIMEOUT" }
                setReadTimeout(minOf(connectionRemaining, frameRemaining).toInt())
                val count = input.read(target, offset, target.size - offset)
                require(count > 0) { "INCOMPLETE_FRAME" }
                require(remainingConnectionMillis() > 0) { "CONNECTION_TIMEOUT" }
                require(now() < frameDeadline) { "FRAME_TIMEOUT" }
                offset += count
            }
        }
        val header = ByteArray(4)
        readExactly(header)
        val length = ByteBuffer.wrap(header).int
        require(length in 1..maxFrame) { "INVALID_FRAME_LENGTH" }
        return ByteArray(length).also(::readExactly)
    }
}

package dev.localspoof.android

/** One volatile socket host per running service. Explicit retries may replace a dead host;
 * repeated Enable never interrupts a healthy controller or renews its approval. */
internal class DesktopControlHost(
    private val enableControl: () -> Unit,
    private val unavailable: () -> Unit = {},
    private val isServerHealthy: (AutoCloseable) -> Boolean = { true },
    private val activateServer: (AutoCloseable) -> Unit = {},
    private val openServer: () -> AutoCloseable,
) : AutoCloseable {
    private var server: AutoCloseable? = null
    @Synchronized fun enable() {
        server?.let { existing ->
            if (isServerHealthy(existing)) return
            server = null
            runCatching { existing.close() }
            // Retire the failed generation before enabling its successor. A late worker callback
            // from the old host must not disable the newly opened listener.
            unavailable()
        }
        var candidate: AutoCloseable? = null
        try {
            // Binding is synchronous. Do not claim USB control exists before a socket exists.
            candidate = openServer()
            enableControl()
            activateServer(candidate)
            server = candidate
        } catch (_: Exception) {
            runCatching { candidate?.close() }
            unavailable()
        }
    }
    @Synchronized override fun close() {
        val owned = server
        server = null
        owned?.close()
    }
}

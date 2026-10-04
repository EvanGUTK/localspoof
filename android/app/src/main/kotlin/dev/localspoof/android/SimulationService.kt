package dev.localspoof.android

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import dev.localspoof.platform.FrameworkLocationProvider
import dev.localspoof.platform.DeviceLocationSampler
import kotlinx.coroutines.*

class SimulationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val binder = LocalBinder()
    private val lifecycleLock = Any()
    private var stopped = false
    private var deviceSampler: DeviceLocationSampler? = null
    private var playbackWakeLock: PowerManager.WakeLock? = null
    private var wakeRenewedAt = 0L
    private val radioPrompts = FlightRadioPrompts()
    private var notificationStatus: String? = null
    private val desktopControl = DesktopControlHost(
        RuntimeModel::enableDesktop,
        unavailable = { RuntimeModel.desktopUnavailable() },
        isServerHealthy = { (it as PhoneControlServer).isAvailable() },
        activateServer = { (it as PhoneControlServer).start() },
    ) {
        PhoneControlServer(
            observeAirplane = { Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1 },
            stopService = { stopFromPhone() }
        ).also { it.bind() }
    }
    inner class LocalBinder : Binder() { val service: SimulationService get() = this@SimulationService }

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Location simulation", NotificationManager.IMPORTANCE_LOW))
        notifications.createNotificationChannel(NotificationChannel(RADIO_CHANNEL, "Flight airplane-mode guidance", NotificationManager.IMPORTANCE_HIGH))
        playbackWakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalSpoof:Playback").apply { setReferenceCounted(false) }
        val ownedProvider = try {
            startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            FrameworkLocationProvider(this).also { RuntimeModel.attach(it) }
        } catch (_: Exception) {
            stopFromPhone()
            RuntimeModel.showError("FOREGROUND_SERVICE_REQUIRES_VISIBLE_PHONE_AND_LOCATION_PERMISSION")
            return
        }
        synchronized(lifecycleLock) {
            if (!stopped) deviceSampler = DeviceLocationSampler(this) { RuntimeModel.offerDeviceLocation(it, ownedProvider) }.also { it.start() }
        }
        scope.launch {
            while (isActive) {
                val cycleStart = SystemClock.elapsedRealtime()
                try {
                    val radioEnabled = Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
                    RuntimeModel.tick(radioEnabled, ownedProvider)
                } catch (_: Exception) {
                    // A stale settings read cannot fail or clean up a successor's runtime.
                    RuntimeModel.failOwner(ownedProvider, "DEVICE_SETTINGS_UNAVAILABLE")
                }
                // Only notification publication uses the lifecycle lock; playback stays outside.
                synchronized(lifecycleLock) {
                    if (!stopped) {
                        if (!RuntimeModel.shouldReadDeviceLocation(ownedProvider)) { deviceSampler?.close(); deviceSampler = null }
                        runCatching { updatePlaybackWakeLock() }.onFailure { RuntimeModel.failOwner(ownedProvider, "PLAYBACK_CPU_UNAVAILABLE") }
                        val snapshot = RuntimeModel.view.value.snapshot
                        val status = "${snapshot.state}:${snapshot.phase}:${snapshot.error}:${snapshot.desiredAirplaneMode}"
                        if (status != notificationStatus) {
                            runCatching { notifications.notify(NOTIFICATION, notification()) }; notificationStatus = status
                        }
                        val desired = snapshot.desiredAirplaneMode
                        if (desired == null || desired == RuntimeModel.view.value.radioObserved || snapshot.state != dev.localspoof.domain.SessionState.ACTIVE) notifications.cancel(RADIO_NOTIFICATION)
                        radioPrompts.next(snapshot)?.takeIf { it != RuntimeModel.view.value.radioObserved }?.let { requested -> runCatching { showRadioPrompt(notifications, requested) } }
                    }
                }
                // Schedule from monotonic elapsed time; provider duration does not accumulate drift.
                // A slow cycle never causes a burst of invented catch-up fixes.
                delay((TICK_MS - (SystemClock.elapsedRealtime() - cycleStart)).coerceAtLeast(10L))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isStopped()) {
            // A started-and-bound instance can still be finishing teardown. Never reopen its socket.
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ENABLE_DESKTOP -> synchronized(lifecycleLock) {
                if (!stopped) desktopControl.enable()
            }
            START -> {
                synchronized(lifecycleLock) { deviceSampler?.close(); deviceSampler = null }
                RuntimeModel.observeRadio(Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
                RuntimeModel.startFromPhone()
                synchronized(lifecycleLock) { if (!stopped) runCatching { updatePlaybackWakeLock() }.onFailure { RuntimeModel.showError("PLAYBACK_CPU_UNAVAILABLE") } }
            }
            PAUSE -> { runCatching { RuntimeModel.pause() }; synchronized(lifecycleLock) { if (!stopped) updatePlaybackWakeLock() } }
            RESUME -> { runCatching { RuntimeModel.resume() }; synchronized(lifecycleLock) { if (!stopped) runCatching { updatePlaybackWakeLock() }.onFailure { RuntimeModel.showError("PLAYBACK_CPU_UNAVAILABLE") } } }
            STOP -> stopFromPhone()
        }
        return START_NOT_STICKY
    }

    internal fun stopFromPhone() {
        val firstStop = synchronized(lifecycleLock) {
            if (stopped) false else { stopped = true; true }
        }
        if (!firstStop) return
        synchronized(lifecycleLock) {
            deviceSampler?.close(); deviceSampler = null
            playbackWakeLock?.let { if (it.isHeld) runCatching { it.release() } }; playbackWakeLock = null
            getSystemService(NotificationManager::class.java).cancel(RADIO_NOTIFICATION)
        }
        RuntimeModel.stop()
        // A bound activity can keep Service.onDestroy from running. Release work now.
        scope.cancel()
        desktopControl.close()
        synchronized(lifecycleLock) { stopForeground(STOP_FOREGROUND_REMOVE) }
        RuntimeModel.detach()
        stopSelf()
    }

    internal fun isStopped(): Boolean = synchronized(lifecycleLock) { stopped }

    override fun onDestroy() {
        // A delayed onDestroy from an old instance must not Stop a newly created phone session.
        stopFromPhone()
        super.onDestroy()
    }

    /** CPU time is held only for actual phone-consented playback, including explicit flight waits.
     * Controller lease expiry revokes future commands; it does not revoke phone playback consent. */
    private fun updatePlaybackWakeLock() {
        val state = RuntimeModel.view.value.snapshot.state
        val shouldHold = RuntimeModel.view.value.simulationArmed && state in setOf(dev.localspoof.domain.SessionState.ACTIVE,dev.localspoof.domain.SessionState.HOLDING)
        val wake = playbackWakeLock ?: return
        val now = SystemClock.elapsedRealtime()
        if (shouldHold) {
            if (!wake.isHeld || now - wakeRenewedAt >= 30_000L) { wake.acquire(60_000L); wakeRenewedAt = now }
        } else if (wake.isHeld) runCatching { wake.release() }
    }

    private fun radioSettingsIntent(): PendingIntent {
        val settings = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
        if (settings.resolveActivity(packageManager) == null) settings.action = Settings.ACTION_WIRELESS_SETTINGS
        return PendingIntent.getActivity(this, 3, settings, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun showRadioPrompt(notifications: NotificationManager, desired: Boolean) {
        // Android may suppress this alert when notification permission is denied; native UI guidance remains.
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        notifications.notify(RADIO_NOTIFICATION, Notification.Builder(this, RADIO_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Flight waiting for airplane mode")
            .setContentText(radioGuidance(desired)).setStyle(Notification.BigTextStyle().bigText(radioGuidance(desired)))
            .setContentIntent(launch).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Open radio settings", radioSettingsIntent()).build()).build())
    }

    private fun notification(): Notification {
        val snapshot = RuntimeModel.view.value.snapshot
        val state = snapshot.state.name
        val paused = state == "PAUSED"
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val toggle = PendingIntent.getService(this, 1, Intent(this, SimulationService::class.java).setAction(if (paused) RESUME else PAUSE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, SimulationService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Location simulation active")
            .setContentText(if (paused) { if (snapshot.error == "SCHEDULING_GAP") "Updates interrupted. Open LocalSpoof to resume." else "Paused. Resume to continue this journey." } else snapshot.desiredAirplaneMode?.takeIf { it != RuntimeModel.view.value.radioObserved }?.let(::radioGuidance) ?: if (snapshot.error == "SCHEDULING_GAP") "Updates interrupted. Open LocalSpoof to resume." else if (state == "IDLE" || state == "READY") "Phone controller ready" else "Status: ${state.lowercase()}")
            .setContentIntent(launch).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, if (paused) "Resume" else "Pause", toggle).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
        if (RuntimeModel.view.value.snapshot.phase?.name in setOf("AWAIT_AIRPLANE_MODE", "RESTORE_CONNECTIVITY")) {
            notification.addAction(Notification.Action.Builder(null, "Radio settings", radioSettingsIntent()).build())
        }
        return notification.build()
    }

    companion object {
        const val CHANNEL = "simulation"
        const val NOTIFICATION = 100
        const val RADIO_CHANNEL = "flight-radio"
        const val RADIO_NOTIFICATION = 101
        const val TICK_MS = 200L
        const val START = "dev.localspoof.START"
        const val ENABLE_DESKTOP = "dev.localspoof.ENABLE_DESKTOP"
        const val PAUSE = "dev.localspoof.PAUSE"
        const val RESUME = "dev.localspoof.RESUME"
        const val STOP = "dev.localspoof.STOP"
    }
}

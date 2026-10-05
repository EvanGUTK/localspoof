package dev.localspoof.android

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

internal enum class MobileUpdateStep { IDLE, CHECKING, AVAILABLE, DOWNLOADING, READY, STAGING, PERMISSION, CONFIRMATION, RECOVERY, FAILED, FINISHED }
internal data class MobileUpdateState(val step: MobileUpdateStep = MobileUpdateStep.IDLE, val release: MobileUpdateRelease? = null,
    val progress: Int? = null, val message: String? = null) {
    val busy get() = step in setOf(MobileUpdateStep.CHECKING, MobileUpdateStep.DOWNLOADING, MobileUpdateStep.STAGING)
    val blocksStart get() = step in setOf(MobileUpdateStep.DOWNLOADING, MobileUpdateStep.STAGING, MobileUpdateStep.CONFIRMATION, MobileUpdateStep.RECOVERY)
}

/** Manual-only update coordinator. The only files are bounded, disposable installation artifacts. */
internal class MobileUpdates(application: Application) : AndroidViewModel(application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(MobileUpdateState())
    val state: StateFlow<MobileUpdateState> = mutable
    private val directory = File(application.cacheDir, "update-staging")
    private val apk = File(directory, "release.apk")
    private var work: Job? = null
    @Volatile private var generation = 0L
    private val connections = java.util.Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())
    private var sessionId: Int? = null
    private var confirmation: Intent? = null
    private var callbackToken: String? = null
    private var recoveryPending = false
    init {
        // Crash recovery discards update files/sessions, never bookmarks or runtime activity.
        directory.deleteRecursively()
        if (reconcileOsSessions()) mutable.value = MobileUpdateState(MobileUpdateStep.RECOVERY, message = "Android still owns an interrupted update session. Cancel its confirmation or retry cancellation here before starting playback.")
    }
    private val context get() = getApplication<Application>()
    val installedCode: Long get() = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    val installedVersion: String get() = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Unknown"
    private fun accepted(owner: Long) = owner == generation && scope.isActive
    private fun start(step: MobileUpdateStep, task: suspend (Long) -> Unit) {
        if (mutable.value.busy || mutable.value.blocksStart) return
        disconnect(); work?.cancel(); val owner = ++generation
        mutable.value = mutable.value.copy(step = step, progress = null, message = null)
        work = scope.launch {
            try { task(owner) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (accepted(owner) && mutable.value.step != MobileUpdateStep.RECOVERY) { apk.delete(); mutable.value = mutable.value.copy(step = MobileUpdateStep.FAILED, progress = null, message = "Update failed verification or could not complete. Your installed app and saved places are retained. Check connectivity and storage, then retry.") } }
        }
    }
    fun check() = start(MobileUpdateStep.CHECKING) { owner ->
        val release = withContext(Dispatchers.IO) {
            val cert = context.assets.open("updates/release.cer").use { MobileUpdateCatalog.certificate(readLimited(it, 8192)) }
            val raw = get(BuildConfig.UPDATE_CATALOG_URL, MobileUpdateCatalog.MAX_CATALOG, owner)
            val signature = get(BuildConfig.UPDATE_SIGNATURE_URL, MobileUpdateCatalog.MAX_SIGNATURE, owner)
            MobileUpdateCatalog.parse(raw, signature, cert)
        }
        if (!accepted(owner)) return@start
        when {
            release.versionCode <= installedCode -> mutable.value = MobileUpdateState(message = "You have the latest compatible published release.")
            !MobileUpdateCatalog.compatible(release, installedCode, Build.VERSION.SDK_INT) -> mutable.value = MobileUpdateState(MobileUpdateStep.FAILED, message = "This release requires a newer Android version or a coordinated protocol upgrade. Your installed app remains available.")
            else -> { apk.delete(); mutable.value = MobileUpdateState(MobileUpdateStep.AVAILABLE, release) }
        }
    }
    fun download() {
        val release = mutable.value.release ?: return
        start(MobileUpdateStep.DOWNLOADING) { owner ->
            withContext(Dispatchers.IO) {
                require(MobileUpdateCatalog.compatible(release, installedCode, Build.VERSION.SDK_INT))
                directory.mkdirs(); require(StatFs(directory.absolutePath).availableBytes >= release.size * 3 + 32L * 1024 * 1024)
                val deadline = System.nanoTime() + 300_000_000_000L
                val hash = MessageDigest.getInstance("SHA-256"); var count = 0L; var progress = -1
                connection(release.url, owner, release.version).useConnection { conn ->
                    val declared = conn.contentLengthLong; require(declared == -1L || declared == release.size)
                    conn.inputStream.use { input -> apk.outputStream().use { output ->
                        val buffer = ByteArray(32768)
                        while (true) {
                            check(accepted(owner) && RuntimeModel.safeForUpdate() && System.nanoTime() < deadline) { "UPDATE_CANCELLED_OR_TIMEOUT" }
                            val read = input.read(buffer); if (read < 0) break
                            check(accepted(owner) && RuntimeModel.safeForUpdate() && System.nanoTime() < deadline)
                            count += read; require(count <= release.size && count <= MobileUpdateCatalog.MAX_APK)
                            output.write(buffer, 0, read); hash.update(buffer, 0, read)
                            val percent = (count * 100 / release.size).toInt()
                            if (percent != progress) { progress = percent; withContext(Dispatchers.Main) { if (accepted(owner)) mutable.value = mutable.value.copy(progress = percent) } }
                        }
                        output.fd.sync()
                    } }
                }
                require(count == release.size && hash.digest().joinToString("") { "%02x".format(it) } == release.sha256)
                verifyApk(apk, release)
            }
            if (accepted(owner)) mutable.value = MobileUpdateState(MobileUpdateStep.READY, release, message = "Verified download. Install after stopping playback and restoring any changed radio settings.")
        }
    }
    private fun verifyApk(file: File, release: MobileUpdateRelease) {
        require(file.isFile && file.length() == release.size)
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("UPDATE_APK")
        val installed = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        fun identity(packageInfo: android.content.pm.PackageInfo): MobileApkIdentity {
            val signing = requireNotNull(packageInfo.signingInfo)
            require(!signing.hasMultipleSigners())
            return MobileApkIdentity(packageInfo.packageName, packageInfo.versionName, packageInfo.longVersionCode,
                packageInfo.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE,
                signing.apkContentsSigners.map { MobileUpdateCatalog.digest(it.toByteArray()) })
        }
        mobileValidateUpgrade(identity(installed), identity(info), release, Build.VERSION.SDK_INT)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(32768); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        require(digest.digest().joinToString("") { "%02x".format(it) } == release.sha256)
    }
    fun install() {
        val release = mutable.value.release ?: return
        if (mutable.value.step !in setOf(MobileUpdateStep.READY, MobileUpdateStep.PERMISSION)) return
        if (!RuntimeModel.safeForUpdate()) { mutable.value = mutable.value.copy(message = RuntimeModel.view.value.updateBlockReason ?: "Stop the journey and desktop control, then complete provider and radio cleanup before installing."); return }
        if (!context.packageManager.canRequestPackageInstalls()) { mutable.value = mutable.value.copy(step = MobileUpdateStep.PERMISSION, message = "Allow LocalSpoof to install this verified update in Android settings, then return and tap Install."); return }
        start(MobileUpdateStep.STAGING) { owner ->
            val installer = context.packageManager.packageInstaller
            var created: Int? = null
            try {
                withContext(Dispatchers.IO) {
                    verifyApk(apk, release)
                    check(accepted(owner))
                    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                        setAppPackageName(MobileUpdateCatalog.PACKAGE); setSize(release.size)
                        if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                    }
                    created = installer.createSession(params)
                    installer.openSession(requireNotNull(created)).use { session ->
                        apk.inputStream().use { input -> session.openWrite("base.apk", 0, release.size).use { output ->
                            val buffer = ByteArray(32768)
                            while (true) { check(accepted(owner)); val count = input.read(buffer); if (count < 0) break; check(accepted(owner)); output.write(buffer, 0, count) }
                            session.fsync(output)
                        } }
                    }
                }
                check(accepted(owner)); val id = requireNotNull(created); sessionId = id; val token = UUID.randomUUID().toString(); callbackToken = token
                MobileUpdateResultBridge.install(id, token) { status, intent -> handleResult(id, status, intent) }
                val resultIntent = Intent(context, MobileUpdateResultReceiver::class.java).setAction("dev.localspoof.android.UPDATE_RESULT").putExtra("token", token)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val callback = PendingIntent.getBroadcast(context, id, resultIntent, flags).intentSender
                // Atomic with the phone's start/commit/approval mutations; a paused journey is unsafe.
                RuntimeModel.withSafeUpdateBoundary {
                    check(accepted(owner))
                    installer.openSession(id).use { it.commit(callback) }
                }
                mutable.value = mutable.value.copy(step = MobileUpdateStep.CONFIRMATION, progress = null, message = "Waiting for Android installation confirmation. Playback remains stopped.")
            } catch (failure: Exception) {
                created?.let { id ->
                    runCatching { installer.abandonSession(id) }
                    if (runCatching { installer.mySessions.any { it.sessionId == id } }.getOrDefault(true)) {
                        recoveryPending = true; RuntimeModel.reserveUpdateInstallRecovery()
                        if (accepted(owner)) mutable.value = mutable.value.copy(step = MobileUpdateStep.RECOVERY, message = "Android retained an interrupted install session. Retry cancellation before starting playback.")
                    }
                }
                MobileUpdateResultBridge.clear(created)
                if (created != null && sessionId == created && accepted(owner)) {
                    sessionId = null; callbackToken = null
                    if (reconcileOsSessions()) mutable.value = mutable.value.copy(step = MobileUpdateStep.RECOVERY, message = "Android has not released this update session. Retry cancellation before starting playback.")
                    else RuntimeModel.endUpdateInstall()
                }
                throw failure
            }
        }
    }
    private fun handleResult(id: Int, status: Int, intent: Intent?) {
        scope.launch {
            if (sessionId != id) return@launch
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION && intent != null) {
                confirmation = intent
                mutable.value = mutable.value.copy(step = MobileUpdateStep.CONFIRMATION, message = "Android needs your confirmation. Tap Continue installation to review it.")
            } else {
                confirmation = null; callbackToken = null; sessionId = null; MobileUpdateResultBridge.clear(id); apk.delete()
                val retained = reconcileOsSessions()
                if (!retained) RuntimeModel.endUpdateInstall()
                mutable.value = mutable.value.copy(step = if (retained) MobileUpdateStep.RECOVERY else if (status == PackageInstaller.STATUS_SUCCESS) MobileUpdateStep.FINISHED else MobileUpdateStep.FAILED,
                    message = if (retained) "Android has not released the update session. Retry cancellation before starting playback." else if (status == PackageInstaller.STATUS_SUCCESS) "Update installed. Reopen LocalSpoof; starting a journey always requires your action." else "Android did not install the update. Your installed app is retained. Check for updates to retry.")
            }
        }
    }
    fun confirmationIntent(): Intent? {
        if (!RuntimeModel.installBoundaryReady()) { mutable.value = mutable.value.copy(message = "Installation is deferred until provider, controller and radio cleanup are complete."); return null }
        return confirmation?.let(::Intent)
    }
    private fun reconcileOsSessions(): Boolean {
        val installer = context.packageManager.packageInstaller
        val remaining = mobileReconcileUpdateSessions(
            sessions = { installer.mySessions.filter { it.appPackageName == MobileUpdateCatalog.PACKAGE }.map { it.sessionId } },
            abandon = { installer.abandonSession(it) })
        if (remaining) { recoveryPending = true; RuntimeModel.reserveUpdateInstallRecovery() }
        return remaining
    }
    fun cancel() {
        generation++; disconnect(); work?.cancel(); work = null; confirmation = null
        sessionId?.let { runCatching { context.packageManager.packageInstaller.abandonSession(it) } }
        val reserved = sessionId != null || recoveryPending
        MobileUpdateResultBridge.clear(sessionId); sessionId = null; callbackToken = null; apk.delete()
        if (reconcileOsSessions()) {
            mutable.value = MobileUpdateState(MobileUpdateStep.RECOVERY, message = "Android has not released the update session yet. Cancel its installation confirmation or tap Cancel update again before starting playback.")
        } else {
            if (reserved) RuntimeModel.endUpdateInstall()
            recoveryPending = false
            mutable.value = MobileUpdateState(message = "Update cancelled. Your installed app is retained.")
        }
    }
    private fun disconnect() { synchronized(connections) { connections.toList().forEach { it.disconnect() }; connections.clear() } }
    override fun onCleared() { cancel(); scope.cancel(); super.onCleared() }
    private fun get(url: String, limit: Int, owner: Long): ByteArray = connection(url, owner).useConnection { conn ->
        require(conn.contentLengthLong == -1L || conn.contentLengthLong in 1..limit.toLong())
        conn.inputStream.use { readLimited(it, limit) }
    }
    private fun connection(initial: String, owner: Long, version: String? = null): HttpURLConnection {
        require(if (version == null) initial in setOf(BuildConfig.UPDATE_CATALOG_URL, BuildConfig.UPDATE_SIGNATURE_URL) else MobileUpdateCatalog.artifactUrl(initial, version))
        var current = initial
        val deadline = System.nanoTime() + 60_000_000_000L
        repeat(4) { redirect ->
            check(accepted(owner) && (version == null || RuntimeModel.safeForUpdate()) && System.nanoTime() < deadline)
            val uri = URI(current); require(uri.scheme == "https" && uri.port == -1 && uri.userInfo == null && uri.fragment == null)
            if (redirect > 0) require(MobileUpdateCatalog.redirectAllowed(initial, current)) { "UPDATE_REDIRECT_HOST" }
            val conn = uri.toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
            synchronized(connections) { connections.add(conn) }
            conn.instanceFollowRedirects = false; conn.connectTimeout = 15000; conn.readTimeout = 20000
            conn.useCaches = false; conn.setRequestProperty("Accept-Encoding", "identity"); conn.setRequestProperty("User-Agent", "LocalSpoof-Updates")
            try {
                val code = conn.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    current = uri.resolve(requireNotNull(conn.getHeaderField("Location"))).toString(); conn.disconnect(); connections.remove(conn)
                } else { require(code == 200); return conn }
            } catch (failure: Exception) { conn.disconnect(); connections.remove(conn); throw failure }
        }
        error("UPDATE_REDIRECT_LIMIT")
    }
    private inline fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T = try { block(this) } finally { disconnect(); connections.remove(this) }
    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val deadline = System.nanoTime() + 60_000_000_000L
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { check(System.nanoTime() < deadline); val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= limit); output.write(buffer, 0, count) }
        return output.toByteArray()
    }
}

/** The explicit non-exported callback accepts only the currently owned session/token. */
internal object MobileUpdateResultBridge {
    private var session: Int? = null; private var token: String? = null
    private var listener: ((Int, Intent?) -> Unit)? = null
    @Synchronized fun install(id: Int, value: String, callback: (Int, Intent?) -> Unit) { session = id; token = value; listener = callback }
    @Synchronized fun result(id: Int, value: String?, status: Int, action: Intent?) { if (id == session && value != null && value == token) listener?.invoke(status, action) }
    @Synchronized fun clear(id: Int?) { if (id == session) { session = null; token = null; listener = null } }
}
class MobileUpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "dev.localspoof.android.UPDATE_RESULT") return
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val action = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else {
            @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        }
        MobileUpdateResultBridge.result(id, intent.getStringExtra("token"), status, action)
    }
}

/** An abandonment attempt is not proof of cleanup: query the OS again and fail closed on uncertainty. */
internal fun mobileReconcileUpdateSessions(sessions: () -> List<Int>, abandon: (Int) -> Unit): Boolean {
    val before = runCatching(sessions).getOrElse { return true }
    before.take(16).forEach { runCatching { abandon(it) } }
    return runCatching { sessions().isNotEmpty() }.getOrDefault(true)
}

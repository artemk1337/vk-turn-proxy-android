package com.vkturn.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import com.vkturn.backend.mobile.Engine
import com.vkturn.backend.mobile.Mobile
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class TunnelService : VpnService() {
    private val worker = Executors.newSingleThreadExecutor()
    private val cleanup = Executors.newSingleThreadExecutor()
    private val stopping = AtomicBoolean(false)
    @Volatile private var engine: Engine? = null
    private var started = false
    private val polling = Executors.newSingleThreadScheduledExecutor()
    private var lastState = ""
    private var previousLogs: String? = null
    private val monitor = object : Runnable {
        override fun run() {
            if (stopping.get()) return
            val status = runCatching { JSONObject(engine?.status().orEmpty()) }.getOrNull()
            val value = status?.optString("state").orEmpty()
            if (stopping.get()) return
            if (status != null && value != "disconnected") snapshot = status.toString()
            runCatching {
                engine?.setLogLimit(AppPreferences.load(File(filesDir, "preferences.properties")).logLines.toLong())
                captureLogs()
            }
            val label = when (value) {
                "connected" -> R.string.connected
                "waiting_quota" -> R.string.quota
                "reconnecting" -> R.string.reconnecting
                "waiting_captcha" -> R.string.account_captcha
                else -> R.string.connecting
            }
            val localized = localizedAppContext(applicationContext)
            var text = localized.getString(label)
            val reason = status?.optString("error").orEmpty()
            if (reason.isNotBlank()) text = localized.getString(R.string.status_detail, text, reason)
            val remaining = ((status?.optLong("retryAt") ?: 0) - System.currentTimeMillis() + 999) / 1000
            if (remaining > 0 && value in listOf("waiting_quota", "reconnecting")) text = localized.getString(R.string.retry_remaining, text, remaining)
            if (text != lastState) {
                lastState = text
                getSystemService(NotificationManager::class.java).notify(1, notification(text))
            }
            if (value == "error" || value == "failed") {
                snapshot = status.toString()
                shutdown(preserveError = true)
                return
            }
        }
    }

    override fun attachBaseContext(base: Context) { super.attachBaseContext(localizedAppContext(base)) }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (started || stopping.get()) return START_NOT_STICKY
        started = true
        synchronized(profileChanges) { active = true }
        snapshot = "{\"state\":\"connecting\"}"
        RoutingCatalogUpdates.start(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        startForeground(1, notification(getString(R.string.connecting)))
        val profileId = intent?.getStringExtra(EXTRA_PROFILE).orEmpty()
        engine = Mobile.newEngine()
        currentEngine = engine
        polling.scheduleWithFixedDelay(monitor, 0, 1, java.util.concurrent.TimeUnit.SECONDS)
        worker.execute {
            try {
                engine!!.setLogLimit(AppPreferences.load(File(filesDir, "preferences.properties")).logLines.toLong())
                val profile = ProfileStore(File(filesDir, "profiles")).read(profileId)
                engine!!.setAccount(AccountStore(this).readCookies())
                val details = JSONObject(Mobile.describeProfile(profile))
                val address = details.getString("address").split("/")
                val builder = Builder().setSession(details.getString("name").take(128))
                    .setMtu(details.getInt("mtu"))
                    .addAddress(address[0], address.getOrElse(1) { "32" }.toInt())
                    .addAddress("fd00:766b:7475::1", 128)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .addDisallowedApplication(packageName)
                val dns = details.getJSONArray("dns")
                for (index in 0 until dns.length()) builder.addDnsServer(dns.getString(index))
                if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
                if (stopping.get()) return@execute
                val tunnel = builder.establish() ?: error("VPN permission is unavailable")
                val fd = tunnel.use { it.detachFd() }
                // The bridge owns the descriptor on every return path.
                engine!!.start(fd.toLong(), profile, File(noBackupFilesDir, "transport").absolutePath)
                if (stopping.get()) {
                    engine!!.stop()
                }
            } catch (error: Exception) {
                // The bridge returns fixed validation and transport errors without keys.
                if (!stopping.get()) {
                    snapshot = JSONObject().put("state", "error")
                        .put("error", error.message.orEmpty().take(512)).toString()
                    shutdown(preserveError = true)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification {
        val localized = localizedAppContext(applicationContext)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TunnelService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle(localized.getString(R.string.app_name))
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, localized.getString(R.string.disconnect), stop).build())
            .build()
    }

    private fun shutdown(preserveError: Boolean = false) {
        if (!stopping.compareAndSet(false, true)) return
        polling.shutdownNow()
        if (!preserveError) snapshot = "{\"state\":\"stopping\"}"
        cleanup.execute {
            runCatching { engine?.stop() }
            runCatching { captureLogs() }
            currentEngine = null
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                synchronized(profileChanges) { active = false }
                if (!preserveError) snapshot = "{\"state\":\"disconnected\"}"
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onRevoke() { shutdown() }

    override fun onDestroy() {
        polling.shutdownNow()
        if (!stopping.get()) {
            stopping.set(true)
            synchronized(profileChanges) { active = false }
            snapshot = "{\"state\":\"disconnected\"}"
            cleanup.execute {
                runCatching { engine?.stop() }
                runCatching { captureLogs() }
                currentEngine = null
            }
        }
        worker.shutdown()
        cleanup.shutdown()
        super.onDestroy()
    }

    @Synchronized private fun captureLogs() {
        val file = File(noBackupFilesDir, "connection.log")
        val defaults = JSONObject(Mobile.defaults())
        val maximumBytes = defaults.getLong("logMaxBytes")
        if (previousLogs == null) {
            val retained = file.length() <= maximumBytes &&
                System.currentTimeMillis() - file.lastModified() <= defaults.getLong("logRetentionDays") * 86_400_000
            previousLogs = if (retained) runCatching { file.readText() }.getOrDefault("") else ""
        }
        val limit = AppPreferences.load(File(filesDir, "preferences.properties")).logLines
        val text = (previousLogs.orEmpty() + "\n" + engine?.logs().orEmpty()).trim()
            .lineSequence().toList().takeLast(limit).joinToString("\n").takeLast(maximumBytes.toInt() / 4)
        if (text == logSnapshot) return
        logSnapshot = text
        val temporary = File(noBackupFilesDir, "connection.log.tmp")
        try {
            temporary.writeText(text)
            check(temporary.renameTo(file))
        } finally {
            temporary.delete()
        }
    }

    companion object {
        private val profileChanges = Any()
        fun <T> whileDisconnected(change: () -> T): T = synchronized(profileChanges) {
            check(!active) { "Disconnect before editing profiles" }
            change()
        }

        const val ACTION_STOP = "com.vkturn.android.STOP"
        const val EXTRA_PROFILE = "profileId"
        private const val CHANNEL = "vpn"
        @Volatile var active = false
            private set
        @Volatile private var snapshot = "{\"state\":\"disconnected\"}"
        @Volatile private var logSnapshot = ""
        @Volatile private var currentEngine: Engine? = null
        fun status(): String = snapshot
        fun logs(): String = logSnapshot
        fun captcha(): String = currentEngine?.captcha() ?: "{\"pending\":false}"
        fun refreshCaptcha(): String = currentEngine?.refreshCaptcha() ?: "{\"pending\":false}"
        fun submitCaptcha(answer: String) {
            val engine = currentEngine ?: error("There is no pending captcha")
            engine.solveCaptcha(answer)
        }
    }
}

package com.vkturn.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
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
    private val monitor = object : Runnable {
        override fun run() {
            if (stopping.get()) return
            val status = runCatching { JSONObject(engine?.status().orEmpty()) }.getOrNull()
            val value = status?.optString("state").orEmpty()
            if (stopping.get()) return
            if (status != null && value != "disconnected") snapshot = status.toString()
            if (value != lastState) {
                lastState = value
                val text = when (value) {
                    "connected" -> R.string.connected
                    "waiting_quota" -> R.string.quota
                    "reconnecting" -> R.string.reconnecting
                    else -> R.string.connecting
                }
                getSystemService(NotificationManager::class.java).notify(1, notification(getString(text)))
            }
            if (value == "error" || value == "failed") {
                snapshot = status.toString()
                shutdown(preserveError = true)
                return
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (started || stopping.get()) return START_NOT_STICKY
        started = true
        active = true
        snapshot = "{\"state\":\"connecting\"}"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        startForeground(1, notification(getString(R.string.connecting)))
        val profileId = intent?.getStringExtra(EXTRA_PROFILE).orEmpty()
        engine = Mobile.newEngine()
        polling.scheduleWithFixedDelay(monitor, 0, 1, java.util.concurrent.TimeUnit.SECONDS)
        worker.execute {
            try {
                val profile = ProfileStore(File(filesDir, "profiles")).read(profileId)
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
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TunnelService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.disconnect), stop).build())
            .build()
    }

    private fun shutdown(preserveError: Boolean = false) {
        if (!stopping.compareAndSet(false, true)) return
        polling.shutdownNow()
        if (!preserveError) snapshot = "{\"state\":\"stopping\"}"
        cleanup.execute {
            runCatching { engine?.stop() }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                active = false
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
            active = false
            snapshot = "{\"state\":\"disconnected\"}"
            cleanup.execute { runCatching { engine?.stop() } }
        }
        worker.shutdown()
        cleanup.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.vkturn.android.STOP"
        const val EXTRA_PROFILE = "profileId"
        private const val CHANNEL = "vpn"
        @Volatile var active = false
            private set
        @Volatile private var snapshot = "{\"state\":\"disconnected\"}"
        fun status(): String = snapshot
    }
}

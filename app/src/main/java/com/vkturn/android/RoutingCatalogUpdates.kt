package com.vkturn.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.vkturn.backend.mobile.Mobile
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object RoutingCatalogUpdates {
    private val downloads = Executors.newSingleThreadExecutor()
    private val schedule = Executors.newSingleThreadScheduledExecutor()
    private val started = AtomicBoolean(false)
    private val updating = AtomicBoolean(false)
    private val listeners = mutableListOf<(Result<String>) -> Unit>()

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val application = context.applicationContext
        val seconds = JSONObject(Mobile.defaults()).getLong("routingUpdateSeconds")
        schedule.scheduleWithFixedDelay({ checkNow(application) }, 0, seconds, TimeUnit.SECONDS)
    }

    fun checkNow(context: Context) {
        val preferences = AppPreferences.load(File(context.filesDir, "preferences.properties"))
        val interval = JSONObject(Mobile.defaults()).getLong("routingUpdateSeconds") * 1000
        if (preferences.routingAutoUpdate && System.currentTimeMillis() - preferences.routingUpdatedAt >= interval) update(context)
    }

    fun update(context: Context, complete: (Result<String>) -> Unit = {}) {
        synchronized(listeners) {
            listeners.add(complete)
            if (!updating.compareAndSet(false, true)) return
        }
        val application = context.applicationContext
        downloads.execute {
            val result = runCatching {
                Mobile.updateRoutingData(File(application.noBackupFilesDir, "routing-data").absolutePath).also {
                    AppPreferences.update(File(application.filesDir, "preferences.properties")) { preferences ->
                        preferences.copy(routingUpdatedAt = System.currentTimeMillis())
                    }
                }
            }
            val completed = synchronized(listeners) {
                updating.set(false)
                listeners.toList().also { listeners.clear() }
            }
            Handler(Looper.getMainLooper()).post { completed.forEach { runCatching { it(result) } } }
        }
    }
}

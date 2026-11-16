package com.vkturn.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import java.io.File

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val preferences = AppPreferences.load(File(context.filesDir, "preferences.properties"))
        if (!preferences.autostart || !ProfileStore.validId(preferences.activeId)) return
        if (VpnService.prepare(context) != null) return
        if (!ProfileStore(File(context.filesDir, "profiles")).ids().contains(preferences.activeId)) return
        runCatching {
            context.startForegroundService(Intent(context, TunnelService::class.java)
                .putExtra(TunnelService.EXTRA_PROFILE, preferences.activeId))
        }
    }
}

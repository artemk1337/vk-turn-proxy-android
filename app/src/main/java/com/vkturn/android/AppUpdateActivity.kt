package com.vkturn.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

class AppUpdateActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var action: android.widget.Button
    private var pendingInstall = false
    private var resumed = false
    private var checking = false
    private var verifying = false
    private var checkFailed = false
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500)
        }
    }

    override fun attachBaseContext(base: Context) { super.attachBaseContext(localizedAppContext(base)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppUpdates.prepare(this)
        pendingInstall = savedInstanceState?.getBoolean("pendingInstall") ?: false
        val ui = ProfileUi(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply { setText(R.string.app_updates); textSize = 24f; setTextColor(ui.ink) })
        content.addView(TextView(this).apply { text = getString(R.string.update_installed, AppUpdates.installedVersion(this@AppUpdateActivity)); setTextColor(ui.muted) })
        status = TextView(this).apply { textSize = 16f; setPadding(0, 32, 0, 32); setTextColor(ui.ink) }
        content.addView(status)
        action = ui.button(R.string.update_check, primary = true) {
            when {
                AppUpdates.apk(this).exists() -> install()
                AppUpdates.release != null -> AppUpdates.download(this, AppUpdates.release!!)
                else -> check()
            }
        }
        content.addView(action)
        content.addView(ui.button(R.string.close) { finish() })
        val scroll = android.widget.ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        @Suppress("DEPRECATION")
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        if (!AppUpdates.busy && !AppUpdates.apk(this).exists()) check()
    }

    private fun check() {
        if (AppUpdates.busy) return
        checking = true
        checkFailed = false
        AppUpdates.check(this) { result ->
            if (isDestroyed) return@check
            checking = false
            checkFailed = result.isFailure
            render()
        }
    }

    private fun render() {
        val available = AppUpdates.release
        val ready = AppUpdates.apk(this).exists()
        status.text = when {
            verifying -> getString(R.string.update_verifying)
            AppUpdates.busy && AppUpdates.downloaded > 0 -> getString(R.string.update_downloading, AppUpdates.downloaded * 100 / (available?.size ?: 1))
            AppUpdates.busy || checking -> getString(R.string.update_checking)
            AppUpdates.failure || checkFailed -> getString(R.string.update_failed)
            ready -> getString(R.string.update_ready)
            available != null -> getString(R.string.update_available, available.version)
            else -> getString(R.string.update_current)
        }
        action.isEnabled = !AppUpdates.busy && !checking && !verifying
        action.setText(when {
            ready -> R.string.update_install
            available != null -> R.string.update_download
            else -> R.string.update_check
        })
    }

    private fun install() {
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this).setMessage(R.string.update_permission)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.settings) { _, _ ->
                    pendingInstall = true
                    runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                        .onFailure { pendingInstall = false; error() }
                }.show()
            return
        }
        AlertDialog.Builder(this).setMessage(R.string.update_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.update_install) { _, _ ->
                verifying = true
                render()
                worker.execute {
                    val result = runCatching { AppUpdates.verify(applicationContext) }
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        verifying = false
                        if (!resumed || isFinishing) return@runOnUiThread
                        result.onSuccess {
                            val uri = Uri.parse("content://$packageName.updates/update.apk")
                            runCatching {
                                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            }.onFailure { error() }
                        }.onFailure {
                            AppUpdates.apk(this).delete()
                            error()
                        }
                        render()
                    }
                }
            }.show()
    }

    private fun error() {
        AlertDialog.Builder(this).setMessage(R.string.update_install_failed).setPositiveButton(android.R.string.ok, null).show()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        handler.post(refresh)
        if (pendingInstall) {
            pendingInstall = false
            if (packageManager.canRequestPackageInstalls()) install()
        }
    }

    override fun onPause() { resumed = false; handler.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("pendingInstall", pendingInstall); super.onSaveInstanceState(outState) }
}

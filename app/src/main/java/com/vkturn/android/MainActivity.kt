package com.vkturn.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import com.vkturn.backend.mobile.Mobile
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var store: ProfileStore
    private lateinit var screen: MainScreen
    private lateinit var settingsScreen: SettingsScreen
    private lateinit var addScreen: AddProfileScreen
    private var pendingImportPreview = false
    private var ids = emptyList<String>()
    private var pendingProfile: String? = null
    private var editor: ProfileEditor? = null
    private var editingId: String? = null
    private var editorGeneration = 0
    private var filePickerGeneration: Int? = null
    private var filePickerForAdd = false
    private var pendingFileUri: Uri? = null
    private var qrGeneration: Int? = null
    private var destroying = false
    private var resumed = false
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val preferencesFile: File get() = File(filesDir, "preferences.properties")
    private val editorDraftFile: File get() = File(noBackupFilesDir, "editor-draft.json")
    private val editorSourceFile: File get() = File(noBackupFilesDir, "editor-source.json")
    private val addSourceFile: File get() = File(noBackupFilesDir, "add-source.txt")
    private val pendingImportFile: File get() = File(noBackupFilesDir, "pending-import.json")
    private val poll = object : Runnable {
        override fun run() {
            val details = runCatching { JSONObject(TunnelService.status()) }.getOrNull()
            val status = details?.optString("state").orEmpty()
            screen.state.setText(when (status) {
                "connected", "running" -> R.string.connected
                "connecting", "starting" -> R.string.connecting
                "waiting_quota" -> R.string.quota
                "waiting_captcha" -> R.string.account_captcha
                "reconnecting" -> R.string.reconnecting
                "stopping" -> R.string.stopping
                "error", "failed" -> R.string.error
                else -> R.string.disconnected
            })
            val reason = details?.optString("error").orEmpty()
            if (reason.isNotBlank()) screen.state.text = getString(R.string.status_detail, screen.state.text, reason)
            val remaining = ((details?.optLong("retryAt") ?: 0) - System.currentTimeMillis() + 999) / 1000
            if (remaining > 0 && status in listOf("waiting_quota", "reconnecting")) screen.state.text = getString(R.string.retry_remaining, screen.state.text, remaining)
            val stats = details?.optJSONObject("stats")
            screen.traffic.visibility = if (TunnelService.active && stats != null) View.VISIBLE else View.GONE
            if (stats != null) screen.traffic.text = getString(R.string.traffic_summary,
                android.text.format.Formatter.formatFileSize(this@MainActivity, stats.optLong("rx_bytes")),
                android.text.format.Formatter.formatFileSize(this@MainActivity, stats.optLong("tx_bytes")), stats.optInt("active_conns"))
            val active = TunnelService.active
            screen.setConnection(status, active, ids.isNotEmpty())
            editor?.setConnectionActive(active)
            addScreen.setConnectionActive(active)
            if (!active && editor == null && pendingImportPreview) {
                pendingImportPreview = false
                runCatching {
                    val input = pendingImportFile.readText()
                    pendingImportFile.delete()
                    openEditor(null, initialInput = input)
                }.onFailure { showError(R.string.import_error) }
            }
            screen.captcha.visibility = if (status == "waiting_captcha") View.VISIBLE else View.GONE
            handler.postDelayed(this, 750)
        }
    }

    override fun attachBaseContext(base: Context) { super.attachBaseContext(localizedAppContext(base)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProfileStore(File(filesDir, "profiles"))
        AccountStore(this)
        pendingProfile = savedInstanceState?.getString("pendingProfile")
        editorGeneration = savedInstanceState?.getInt("editorGeneration") ?: 0
        filePickerGeneration = savedInstanceState?.getInt("filePickerGeneration", -1)?.takeIf { it >= 0 }
        pendingFileUri = savedInstanceState?.getString("pendingFileUri")?.let(Uri::parse)
        filePickerForAdd = savedInstanceState?.getBoolean("filePickerForAdd") ?: false
        qrGeneration = savedInstanceState?.getInt("qrGeneration", -1)?.takeIf { it >= 0 }
        pendingImportPreview = savedInstanceState?.getBoolean("pendingImportPreview") ?: pendingImportFile.exists()
        if (savedInstanceState == null) { editorDraftFile.delete(); editorSourceFile.delete() }
        settingsScreen = createSettings()
        settingsScreen.restore(savedInstanceState?.getBundle("settingsDraft"))
        addScreen = AddProfileScreen(this, ::importSource, ::scanQr, ::chooseImportFile)
        if (savedInstanceState != null) {
            val draft = runCatching { if (addSourceFile.length() <= MAX_PROFILE_BYTES) addSourceFile.readText() else "" }.getOrDefault("")
            addScreen.restore(draft)
        } else addSourceFile.delete()
        screen = MainScreen(this, settingsScreen.view, addScreen.view,
            onConnect = {
                if (TunnelService.active) startService(Intent(this, TunnelService::class.java).setAction(TunnelService.ACTION_STOP))
                else {
                    pendingProfile = selectedId()
                    if (pendingProfile != null) {
                        val permission = VpnService.prepare(this)
                        if (permission != null) startActivityForResult(permission, VPN_REQUEST) else beginConnection()
                    }
                }
            },
            onSelect = { id ->
                if (!TunnelService.active) {
                    AppPreferences.update(preferencesFile) { it.copy(activeId = id) }
                    reloadProfiles(id)
                }
            },
            onEdit = { openEditor(it) },
            onAccount = { AccountScreen.show(this) },
            onCaptcha = { AccountScreen.showCaptcha(this) })
        setContentView(screen.root)
        screen.showPage(savedInstanceState?.getInt("page") ?: 0)
        reloadProfiles()
        if (savedInstanceState?.getBoolean("profilePickerOpen") == true) screen.showProfilePicker()
        if (savedInstanceState?.getBoolean("editorOpen") == true) {
            val draft = runCatching { editorDraftFile.readText() }.getOrNull()
            openEditor(savedInstanceState.getString("editorId"), draft, restoreSource = true)
        } else if (savedInstanceState == null) handleDeepLink(intent)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
        pendingFileUri?.let { uri ->
            filePickerGeneration?.takeIf { it == editorGeneration }?.let { owner -> readImportFile(uri, owner, filePickerForAdd) }
        }
        updateRoutingIfDue()
        AppUpdates.prepare(this)
    }

    private fun reloadProfiles(selected: String? = null) {
        val preferences = AppPreferences.load(preferencesFile)
        val entries = store.ids().mapNotNull { id ->
            runCatching {
                val details = JSONObject(Mobile.describeProfile(store.read(id)))
                ProfileSummary(id, details.getString("name").take(128), details.getString("address") + "  ·  MTU " + details.getInt("mtu"))
            }.getOrNull()
        }
        ids = entries.map { it.id }
        val activeId = AppPreferences.update(preferencesFile) { it.copy(activeId = selected ?: preferences.activeId).withAvailableProfiles(ids) }.activeId
        screen.renderProfiles(entries, activeId)
        screen.setConnection(runCatching { JSONObject(TunnelService.status()).optString("state") }.getOrDefault("disconnected"), TunnelService.active, ids.isNotEmpty())
    }

    private fun selectedId(): String? = AppPreferences.load(preferencesFile).activeId.takeIf { it in ids }

    private fun deleteProfile(id: String, owner: Int) {
        if (owner != editorGeneration || editingId != id) return
        if (TunnelService.active) { showError(R.string.disconnect_before_edit); return }
        AlertDialog.Builder(this).setMessage(R.string.delete_title).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) confirmed@{ _, _ ->
                if (owner != editorGeneration || editingId != id) return@confirmed
                runCatching { TunnelService.whileDisconnected { store.delete(id) } }
                    .onSuccess { editor?.close(); reloadProfiles() }
                    .onFailure { showError(R.string.error) }
            }.show()
    }

    private fun openEditor(id: String?, draft: String? = null, initialInput: String = "", restoreSource: Boolean = false) {
        if (TunnelService.active) { showError(R.string.disconnect_before_edit); return }
        val result = runCatching {
            if (restoreSource && editorSourceFile.exists()) editorSourceFile.readText().takeIf(String::isNotBlank)
            else id?.let(store::read)
        }
        result.onFailure { showError(R.string.profile_save_error) }.onSuccess { canonical ->
            val generation = if (restoreSource && editorGeneration > 0) editorGeneration else ++editorGeneration
            editor?.close()
            editingId = id
            editorSourceFile.writeText(canonical.orEmpty())
            editor = ProfileEditor(this, canonical, draft, initialInput, onChooseFile = {
                pendingFileUri = null
                filePickerForAdd = false
                filePickerGeneration = generation
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT_REQUEST)
            }, onSave = save@{ updated ->
                if (generation != editorGeneration) return@save
                editor?.setSaving(true)
                io.execute {
                    val saved = runCatching { TunnelService.whileDisconnected { store.save(updated, id) } }
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        if (generation != editorGeneration) {
                            saved.onSuccess { reloadProfiles() }
                            return@runOnUiThread
                        }
                        saved.onSuccess { savedId ->
                            editor?.close()
                            reloadProfiles(if (TunnelService.active) null else savedId)
                            if (id == null) { addScreen.clear(); addSourceFile.delete(); screen.showPage(0) }
                        }
                            .onFailure { editor?.setSaving(false); showError(R.string.profile_save_error, it.message.orEmpty()) }
                    }
                }
            }, onClosed = closed@{
                if (generation != editorGeneration) return@closed
                editor = null
                editingId = null
                if (!isChangingConfigurations && !destroying) { editorDraftFile.delete(); editorSourceFile.delete() }
            }, onDelete = id?.let { profileId -> { if (generation == editorGeneration) deleteProfile(profileId, generation) } }).also { it.show() }
        }
    }

    private fun beginConnection() {
        val id = pendingProfile ?: return
        pendingProfile = null
        AppPreferences.update(preferencesFile) { it.copy(activeId = id) }
        startForegroundService(Intent(this, TunnelService::class.java).putExtra(TunnelService.EXTRA_PROFILE, id))
    }

    @Deprecated("Platform activity result API is available on every supported version")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (AccountScreen.onActivityResult(this, requestCode, resultCode, data)) return
        if (requestCode == VPN_REQUEST) {
            if (resultCode == RESULT_OK) beginConnection() else { pendingProfile = null; showError(R.string.vpn_denied) }
        }
        if (requestCode == AUTOSTART_REQUEST) {
            AppPreferences.update(preferencesFile) { it.copy(autostart = resultCode == RESULT_OK) }
            settingsScreen.setAutostart(resultCode == RESULT_OK)
            if (resultCode != RESULT_OK) showError(R.string.vpn_denied)
        }
        if (requestCode == QR_REQUEST) {
            val owner = qrGeneration
            qrGeneration = null
            if (resultCode == RESULT_OK && owner == editorGeneration && editor == null) {
                data?.getStringExtra(QrScanActivity.EXTRA_CONTENT)?.let(::importSource)
            }
            return
        }
        if (requestCode != IMPORT_REQUEST) return
        val owner = filePickerGeneration
        val fromAdd = filePickerForAdd
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null || owner == null) {
            filePickerGeneration = null
            filePickerForAdd = false
            pendingFileUri = null
            return
        }
        if (owner != editorGeneration || (!fromAdd && editor == null)) return
        pendingFileUri = uri
        readImportFile(uri, owner, fromAdd)
    }

    private fun readImportFile(uri: Uri, owner: Int, fromAdd: Boolean) {
        io.execute {
            val result = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        require(output.size() + size <= MAX_PROFILE_BYTES) { "Profile exceeds 4 MiB" }
                        output.write(buffer, 0, size)
                    }
                    output.toString("UTF-8")
                } ?: error("Could not open profile")
            }
            runOnUiThread {
                if (isDestroyed || owner != filePickerGeneration || uri != pendingFileUri) return@runOnUiThread
                filePickerGeneration = null
                filePickerForAdd = false
                pendingFileUri = null
                if (owner != editorGeneration || (!fromAdd && editor == null)) return@runOnUiThread
                result.onSuccess { raw ->
                    if (fromAdd) importSource(raw)
                    else {
                        editor?.loadFile(raw)
                        if (TunnelService.active) showError(R.string.disconnect_before_edit)
                    }
                }.onFailure { showError(R.string.import_error) }
            }
        }
    }

    private fun chooseImportFile() {
        if (TunnelService.active) { showError(R.string.disconnect_before_edit); return }
        pendingFileUri = null
        filePickerForAdd = true
        filePickerGeneration = ++editorGeneration
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT_REQUEST)
    }

    private fun scanQr() {
        if (TunnelService.active) { showError(R.string.disconnect_before_edit); return }
        qrGeneration = ++editorGeneration
        startActivityForResult(Intent(this, QrScanActivity::class.java), QR_REQUEST)
    }

    private fun importSource(value: String) {
        if (value.isBlank()) { showError(R.string.paste_empty); return }
        if (value.toByteArray(Charsets.UTF_8).size > MAX_PROFILE_BYTES) { showError(R.string.import_too_large); return }
        if (TunnelService.active) {
            pendingImportFile.writeText(value)
            pendingImportPreview = true
            showError(R.string.disconnect_before_edit)
        } else openEditor(null, initialInput = value)
    }

    private fun createSettings(): SettingsScreen {
        val before = AppPreferences.load(preferencesFile)
        val logBounds = JSONObject(Mobile.defaults()).getJSONObject("logLines")
        return SettingsScreen(this, before, logBounds.getInt("min"), logBounds.getInt("max"),
            onSave = { chosenLanguage, chosenTheme, autostart, routingAutoUpdate, lines ->
                val consent = if (autostart) VpnService.prepare(this) else null
                AppPreferences.update(preferencesFile) { it.copy(language = chosenLanguage, theme = chosenTheme,
                    autostart = autostart && consent == null, logLines = lines, routingAutoUpdate = routingAutoUpdate) }
                if (consent != null) startActivityForResult(consent, AUTOSTART_REQUEST)
                if (chosenLanguage != before.language || chosenTheme != before.theme) recreate()
                else {
                    updateRoutingIfDue()
                    android.widget.Toast.makeText(this, R.string.settings_saved, android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onUpdates = { startActivity(Intent(this, AppUpdateActivity::class.java)) },
            onAccount = { AccountScreen.show(this) },
            onLogs = { showLogs() },
            onAbout = { showAbout() })
    }

    private fun showAbout() {
        val link = TextView(this).apply {
            setPadding(dp(24), dp(16), dp(24), dp(16))
            autoLinkMask = Linkify.WEB_URLS
            setText(R.string.github_repository)
            movementMethod = LinkMovementMethod.getInstance()
        }
        AlertDialog.Builder(this).setTitle(R.string.about).setView(link)
            .setPositiveButton(R.string.close, null).show()
    }

    private fun showLogs() {
        val lines = AppPreferences.load(preferencesFile).logLines
        val text = TunnelService.logs().ifBlank { runCatching { File(noBackupFilesDir, "connection.log").readText() }.getOrDefault("") }
            .lineSequence().toList().takeLast(lines).joinToString("\n")
        val view = TextView(this).apply { setTextIsSelectable(true); setPadding(dp(16), dp(8), dp(16), dp(8)); this.text = text.ifBlank { getString(R.string.logs_empty) }; textSize = 12f }
        AlertDialog.Builder(this).setTitle(R.string.logs).setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton(R.string.close, null).setNeutralButton(R.string.copy_logs) { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.logs), text))
            }.show()
    }

    private fun updateRoutingIfDue() {
        RoutingCatalogUpdates.start(this)
        RoutingCatalogUpdates.checkNow(this)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleDeepLink(intent) }

    private fun handleDeepLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW || !intent.data?.scheme.orEmpty().equals("vkturnproxy", ignoreCase = true)) return
        importSource(intent.data.toString())
    }

    private fun showError(message: Int, detail: String = "") {
        AlertDialog.Builder(this).setMessage(getString(message) + if (detail.isBlank()) "" else "\n\n${detail.take(512)}")
            .setPositiveButton(android.R.string.ok, null).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        addSourceFile.writeText(addScreen.text())
        outState.putBoolean("profilePickerOpen", screen.isProfilePickerOpen)
        outState.putInt("page", screen.page)
        outState.putBundle("settingsDraft", settingsScreen.draft())
        outState.putString("pendingProfile", pendingProfile)
        outState.putInt("editorGeneration", editorGeneration)
        outState.putInt("filePickerGeneration", filePickerGeneration ?: -1)
        outState.putString("pendingFileUri", pendingFileUri?.toString())
        outState.putBoolean("filePickerForAdd", filePickerForAdd)
        outState.putInt("qrGeneration", qrGeneration ?: -1)
        outState.putBoolean("pendingImportPreview", pendingImportPreview)
        editor?.let {
            editorDraftFile.writeText(it.draft())
            outState.putBoolean("editorOpen", true)
            outState.putString("editorId", editingId)
        }
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Platform back handling is available on every supported version")
    override fun onBackPressed() {
        if (screen.page != 0) screen.showPage(0) else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        handler.post(poll)
        AppUpdates.check(this, automatic = true) { result ->
            val release = result.getOrNull()
            if (resumed && !isDestroyed && !isFinishing && editor == null && release != null) {
                AlertDialog.Builder(this).setTitle(R.string.app_updates)
                    .setMessage(getString(R.string.update_available, release.version))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.update_open) { _, _ -> startActivity(Intent(this, AppUpdateActivity::class.java)) }.show()
            }
        }
    }
    override fun onPause() { resumed = false; handler.removeCallbacks(poll); super.onPause() }
    override fun onDestroy() { destroying = true; screen.close(); editor?.close(); io.shutdown(); super.onDestroy() }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val IMPORT_REQUEST = 1
        private const val VPN_REQUEST = 2
        private const val AUTOSTART_REQUEST = 4
        private const val QR_REQUEST = 5
    }
}

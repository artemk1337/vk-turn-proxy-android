package com.vkturn.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.vkturn.backend.mobile.Mobile
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var store: ProfileStore
    private lateinit var profiles: Spinner
    private lateinit var state: TextView
    private lateinit var traffic: TextView
    private lateinit var connect: Button
    private lateinit var edit: Button
    private lateinit var remove: Button
    private lateinit var captcha: Button
    private lateinit var paste: Button
    private lateinit var importFile: Button
    private var pendingImportPreview = false
    private var pendingDeepLink: String? = null
    private var ids = emptyList<String>()
    private var pendingProfile: String? = null
    private var editor: ProfileEditor? = null
    private var editingId: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val preferencesFile: File get() = File(filesDir, "preferences.properties")
    private val importDraftFile: File get() = File(noBackupFilesDir, "import-draft.json")
    private val editorDraftFile: File get() = File(noBackupFilesDir, "editor-draft.json")
    private val editorSourceFile: File get() = File(noBackupFilesDir, "editor-source.json")
    private val pendingImportFile: File get() = File(noBackupFilesDir, "pending-import.json")
    private val poll = object : Runnable {
        override fun run() {
            val details = runCatching { JSONObject(TunnelService.status()) }.getOrNull()
            val status = details?.optString("state").orEmpty()
            state.setText(when (status) {
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
            if (reason.isNotBlank()) state.text = getString(R.string.status_detail, state.text, reason)
            val remaining = ((details?.optLong("retryAt") ?: 0) - System.currentTimeMillis() + 999) / 1000
            if (remaining > 0 && status in listOf("waiting_quota", "reconnecting")) {
                state.text = getString(R.string.retry_remaining, state.text, remaining)
            }
            val stats = details?.optJSONObject("stats")
            traffic.visibility = if (TunnelService.active && stats != null) View.VISIBLE else View.GONE
            if (stats != null) traffic.text = getString(R.string.traffic_summary,
                android.text.format.Formatter.formatFileSize(this@MainActivity, stats.optLong("rx_bytes")),
                android.text.format.Formatter.formatFileSize(this@MainActivity, stats.optLong("tx_bytes")), stats.optInt("active_conns"))
            val active = TunnelService.active
            connect.setText(if (active) R.string.disconnect else R.string.connect)
            connect.isEnabled = status != "stopping" && (active || ids.isNotEmpty())
            profiles.isEnabled = !active
            edit.isEnabled = !active && ids.isNotEmpty()
            remove.isEnabled = !active && ids.isNotEmpty()
            paste.isEnabled = !active
            importFile.isEnabled = !active
            editor?.setConnectionActive(active)
            if (!active && editor == null) {
                if (pendingImportPreview) {
                    pendingImportPreview = false
                    runCatching {
                        importDraftFile.writeText(pendingImportFile.readText())
                        pendingImportFile.delete()
                        openEditor(null)
                    }.onFailure { showError(R.string.import_error) }
                } else pendingDeepLink?.let {
                    pendingDeepLink = null
                    confirmDeepLink(it)
                }
            }
            captcha.visibility = if (status == "waiting_captcha") View.VISIBLE else View.GONE
            handler.postDelayed(this, 750)
        }
    }

    override fun attachBaseContext(base: Context) { super.attachBaseContext(localizedAppContext(base)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProfileStore(File(filesDir, "profiles"))
        AccountStore(this)
        pendingProfile = savedInstanceState?.getString("pendingProfile")
        pendingImportPreview = savedInstanceState?.getBoolean("pendingImportPreview") ?: false
        pendingDeepLink = savedInstanceState?.getString("pendingDeepLink")
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val ink = if (night) Color.rgb(240, 244, 255) else Color.rgb(23, 35, 60)
        val muted = if (night) Color.rgb(173, 185, 208) else Color.rgb(93, 109, 135)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(28), dp(24), dp(24)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        setContentView(scroll)
        @Suppress("DEPRECATION")
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        content.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { bottomMargin = dp(16) })
        content.addView(TextView(this).apply { setText(R.string.app_name); textSize = 28f; setTextColor(ink) })
        state = TextView(this).apply {
            setText(R.string.disconnected); textSize = 17f; setTextColor(muted)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, dp(12), 0, dp(12))
        }
        content.addView(state)
        traffic = TextView(this).apply { setTextColor(muted); textSize = 13f }
        content.addView(traffic)
        content.addView(TextView(this).apply { setText(R.string.profiles); setPadding(0, dp(16), 0, 0); setTextColor(muted) })
        profiles = Spinner(this)
        content.addView(profiles, LinearLayout.LayoutParams(-1, dp(56)))
        connect = button(R.string.connect) {
            if (TunnelService.active) startService(Intent(this, TunnelService::class.java).setAction(TunnelService.ACTION_STOP))
            else {
                pendingProfile = selectedId()
                if (pendingProfile != null) {
                    val permission = VpnService.prepare(this)
                    if (permission != null) startActivityForResult(permission, VPN_REQUEST) else beginConnection()
                }
            }
        }
        content.addView(connect, LinearLayout.LayoutParams(-1, dp(56)))
        captcha = button(R.string.account_captcha) { AccountScreen.showCaptcha(this) }
        content.addView(captcha)
        edit = button(R.string.edit_profile) { selectedId()?.let { openEditor(it) } }
        remove = button(R.string.delete) {
            val id = selectedId() ?: return@button
            AlertDialog.Builder(this).setMessage(R.string.delete_title).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ ->
                    runCatching { TunnelService.whileDisconnected { store.delete(id) } }.onFailure { showError(R.string.error) }
                    reloadProfiles()
                }.show()
        }
        content.addView(edit)
        content.addView(remove)
        paste = button(R.string.paste_profile) { if (!TunnelService.active) showPasteDialog() }
        content.addView(paste)
        importFile = button(R.string.import_file) {
            if (TunnelService.active) return@button
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT_REQUEST)
        }
        content.addView(importFile)
        content.addView(TextView(this).apply { setText(R.string.profile_hint); textSize = 13f; setTextColor(muted); setPadding(0, dp(4), 0, dp(20)) })
        content.addView(button(R.string.account_title) { AccountScreen.show(this) })
        content.addView(button(R.string.settings) { showSettings() })
        content.addView(button(R.string.logs) { showLogs() })
        reloadProfiles()
        if (savedInstanceState?.getBoolean("editorOpen") == true) {
            val draft = runCatching { editorDraftFile.readText() }.getOrNull()
            openEditor(savedInstanceState.getString("editorId"), draft, restoreSource = true)
        } else if (savedInstanceState == null) handleDeepLink(intent)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
        }
        updateRoutingIfDue()
    }

    private fun reloadProfiles(selected: String? = null) {
        val preferences = AppPreferences.load(preferencesFile)
        val entries = store.ids().mapNotNull { id ->
            runCatching { id to JSONObject(Mobile.describeProfile(store.read(id))).getString("name").take(128) }.getOrNull()
        }
        ids = entries.map { it.first }
        profiles.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            entries.map { it.second }.ifEmpty { listOf(getString(R.string.no_profiles)) })
        val activeId = AppPreferences.update(preferencesFile) {
            it.copy(activeId = selected ?: preferences.activeId).withAvailableProfiles(ids)
        }.activeId
        profiles.setSelection(ids.indexOf(activeId).coerceAtLeast(0))
        profiles.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                ids.getOrNull(position)?.let { chosen -> AppPreferences.update(preferencesFile) { it.copy(activeId = chosen) } }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        connect.isEnabled = ids.isNotEmpty() || TunnelService.active
    }

    private fun selectedId(): String? = ids.getOrNull(profiles.selectedItemPosition)

    private fun openEditor(id: String?, draft: String? = null, source: String? = null, restoreSource: Boolean = false) {
        val result = runCatching {
            source ?: if (restoreSource && editorSourceFile.exists()) editorSourceFile.readText()
            else if (id == null) importDraftFile.readText() else store.read(id)
        }
        result.onFailure { showError(R.string.profile_save_error) }.onSuccess { raw ->
            editor?.close()
            editingId = id
            runCatching {
                editorSourceFile.writeText(raw)
                editor = ProfileEditor(this, raw, draft, onSourceChanged = { canonical ->
                    openEditor(id, source = canonical)
                }, onSave = { patch ->
                    if (TunnelService.active) {
                        showError(R.string.disconnect_before_edit)
                        return@ProfileEditor
                    }
                    editor?.setSaving(true)
                    io.execute {
                        val saved = runCatching {
                            val updated = Mobile.updateProfile(raw, patch)
                            TunnelService.whileDisconnected { store.save(updated, id) }
                        }
                        runOnUiThread {
                            if (isDestroyed) return@runOnUiThread
                            saved.onSuccess { savedId -> editor?.close(); reloadProfiles(if (TunnelService.active) null else savedId) }
                                .onFailure { editor?.setSaving(false); showError(R.string.profile_save_error, it.message.orEmpty()) }
                        }
                    }
                }, onClosed = {
                    editor = null
                    editingId = null
                    if (!isChangingConfigurations) {
                        editorDraftFile.delete()
                        editorSourceFile.delete()
                        if (id == null) importDraftFile.delete()
                    }
                }).also { it.show() }
            }.onFailure { showError(R.string.profile_save_error, it.message.orEmpty()) }
        }
    }

    private fun showPasteDialog() {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        val input = EditText(this).apply {
            setHint(R.string.profile_input); minLines = 4; maxLines = 8
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        val link = EditText(this).apply { setHint(R.string.vk_link); setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        content.addView(input); content.addView(link)
        val dialog = AlertDialog.Builder(this).setTitle(R.string.paste_profile).setView(content)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.import_profile, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (input.text.isBlank()) input.error = getString(R.string.paste_empty)
                else { dialog.dismiss(); importText(input.text.toString(), link.text.toString()) }
            }
        }
        dialog.show()
    }

    private fun importText(raw: String, vkLink: String = "") {
        io.execute {
            val result = runCatching { Mobile.importProfile(raw, vkLink.trim()) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess {
                    if (TunnelService.active) {
                        pendingImportFile.writeText(it)
                        pendingImportPreview = true
                        showError(R.string.disconnect_before_edit)
                    } else {
                        editor?.close()
                        importDraftFile.writeText(it)
                        openEditor(null)
                    }
                }.onFailure { showError(R.string.import_error, it.message.orEmpty().take(512)) }
            }
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
            if (resultCode != RESULT_OK) showError(R.string.vpn_denied)
        }
        if (requestCode != IMPORT_REQUEST || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
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
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { raw ->
                    if (raw.lineSequence().any { it.trim().removePrefix("\uFEFF").equals("[Interface]", ignoreCase = true) }) {
                        val link = EditText(this).apply { setHint(R.string.vk_link); setSingleLine(true) }
                        AlertDialog.Builder(this).setTitle(R.string.vk_link).setView(link).setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.import_profile) { _, _ -> importText(raw, link.text.toString()) }.show()
                    } else importText(raw)
                }.onFailure { showError(R.string.import_error) }
            }
        }
    }

    private fun showSettings() {
        val before = AppPreferences.load(preferencesFile)
        val logBounds = JSONObject(Mobile.defaults()).getJSONObject("logLines")
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        fun selection(label: Int, labels: List<String>, current: Int): Spinner {
            content.addView(TextView(this).apply { setText(label); setPadding(0, dp(12), 0, 0) })
            return Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels)
                setSelection(current); content.addView(this)
            }
        }
        val language = selection(R.string.language, listOf("English", "Русский"), if (before.language == "ru") 1 else 0)
        val theme = selection(R.string.theme, listOf(getString(R.string.light), getString(R.string.dark)), if (before.theme == "dark") 1 else 0)
        val autostart = CheckBox(this).apply { setText(R.string.autostart); isChecked = before.autostart; content.addView(this) }
        val autoUpdate = CheckBox(this).apply { setText(R.string.routing_auto_update); isChecked = before.routingAutoUpdate; content.addView(this) }
        val limit = EditText(this).apply { hint = getString(R.string.log_limit, logBounds.getInt("min"), logBounds.getInt("max")); inputType = InputType.TYPE_CLASS_NUMBER; setText(String.format(resources.configuration.locales[0], "%d", before.logLines)); content.addView(this) }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.settings).setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val lines = limit.text.toString().toIntOrNull()
                if (lines == null || lines !in logBounds.getInt("min")..logBounds.getInt("max")) { limit.error = getString(R.string.invalid_value); return@setOnClickListener }
                val chosenLanguage = if (language.selectedItemPosition == 1) "ru" else "en"
                val chosenTheme = if (theme.selectedItemPosition == 1) "dark" else "light"
                val consent = if (autostart.isChecked) VpnService.prepare(this) else null
                AppPreferences.update(preferencesFile) { it.copy(language = chosenLanguage, theme = chosenTheme,
                    autostart = autostart.isChecked && consent == null, logLines = lines, routingAutoUpdate = autoUpdate.isChecked) }
                dialog.dismiss()
                if (consent != null) startActivityForResult(consent, AUTOSTART_REQUEST)
                if (chosenLanguage != before.language || chosenTheme != before.theme) recreate()
                else updateRoutingIfDue()
            }
        }
        dialog.show()
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
        if (intent?.action != Intent.ACTION_VIEW || intent.data?.scheme != "vkturnproxy") return
        val link = intent.data.toString()
        if (TunnelService.active) {
            pendingDeepLink = link
            showError(R.string.disconnect_before_edit)
            return
        }
        confirmDeepLink(link)
    }

    private fun confirmDeepLink(link: String) {
        AlertDialog.Builder(this).setTitle(R.string.deep_link_title).setMessage(R.string.profile_import_preview)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.import_profile) { _, _ -> importText(link) }.show()
    }

    private fun showError(message: Int, detail: String = "") {
        AlertDialog.Builder(this).setMessage(getString(message) + if (detail.isBlank()) "" else "\n\n${detail.take(512)}")
            .setPositiveButton(android.R.string.ok, null).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingProfile", pendingProfile)
        outState.putBoolean("pendingImportPreview", pendingImportPreview)
        outState.putString("pendingDeepLink", pendingDeepLink)
        editor?.let {
            editorDraftFile.writeText(it.draft())
            outState.putBoolean("editorOpen", true)
            outState.putString("editorId", editingId)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() { super.onResume(); handler.post(poll) }
    override fun onPause() { handler.removeCallbacks(poll); super.onPause() }
    override fun onDestroy() { io.shutdown(); super.onDestroy() }
    private fun button(label: Int, click: () -> Unit): Button = Button(this).apply { setText(label); setOnClickListener { click() } }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val IMPORT_REQUEST = 1
        private const val VPN_REQUEST = 2
        private const val AUTOSTART_REQUEST = 4
    }
}

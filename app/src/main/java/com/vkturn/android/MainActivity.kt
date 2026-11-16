package com.vkturn.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
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
    private lateinit var vkLink: EditText
    private lateinit var state: TextView
    private lateinit var connect: Button
    private lateinit var remove: Button
    private lateinit var importButton: Button
    private var ids = emptyList<String>()
    private var pendingProfile: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val poll = object : Runnable {
        override fun run() {
            val details = runCatching { JSONObject(TunnelService.status()) }.getOrNull()
            val status = details?.optString("state").orEmpty()
            state.setText(when (status) {
                "connected", "running" -> R.string.connected
                "connecting", "starting" -> R.string.connecting
                "quota", "waiting", "quota_wait", "waiting_quota" -> R.string.quota
                "reconnecting" -> R.string.reconnecting
                "stopping" -> R.string.stopping
                "error", "failed" -> R.string.error
                else -> R.string.disconnected
            })
            val reason = details?.optString("error").orEmpty()
            if (reason.isNotBlank()) state.text = getString(R.string.status_detail, state.text, reason)
            val active = TunnelService.active
            connect.setText(if (active) R.string.disconnect else R.string.connect)
            connect.isEnabled = status != "stopping" && (active || ids.isNotEmpty())
            profiles.isEnabled = !active
            remove.isEnabled = !active && ids.isNotEmpty()
            handler.postDelayed(this, 750)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProfileStore(File(filesDir, "profiles"))
        pendingProfile = savedInstanceState?.getString("pendingProfile")
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val ink = if (night) Color.rgb(240, 244, 255) else Color.rgb(23, 35, 60)
        val muted = if (night) Color.rgb(173, 185, 208) else Color.rgb(93, 109, 135)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.logo)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        content.addView(logo, LinearLayout.LayoutParams(dp(64), dp(64)).apply { bottomMargin = dp(20) })
        content.addView(TextView(this).apply { setText(R.string.app_name); textSize = 28f; setTextColor(ink) })
        state = TextView(this).apply {
            setText(R.string.disconnected); textSize = 18f; setTextColor(muted)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, dp(12), 0, dp(28))
        }
        content.addView(state)
        content.addView(TextView(this).apply { setText(R.string.profiles); textSize = 15f; setTextColor(muted) })
        profiles = Spinner(this)
        content.addView(profiles, LinearLayout.LayoutParams(-1, dp(56)))
        connect = Button(this).apply {
            setText(R.string.connect)
            setOnClickListener {
                if (TunnelService.active) {
                    startService(Intent(this@MainActivity, TunnelService::class.java).setAction(TunnelService.ACTION_STOP))
                } else {
                    pendingProfile = ids.getOrNull(profiles.selectedItemPosition)
                    if (pendingProfile != null) {
                        val permission = VpnService.prepare(this@MainActivity)
                        if (permission != null) startActivityForResult(permission, VPN_REQUEST) else beginConnection()
                    }
                }
            }
        }
        content.addView(connect, LinearLayout.LayoutParams(-1, dp(56)))
        remove = Button(this).apply {
            setText(R.string.delete)
            setOnClickListener {
                val id = ids.getOrNull(profiles.selectedItemPosition) ?: return@setOnClickListener
                AlertDialog.Builder(this@MainActivity).setMessage(R.string.delete_title)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        runCatching { store.delete(id) }.onFailure { showError(R.string.error) }
                        reloadProfiles()
                    }.show()
            }
        }
        content.addView(remove)
        vkLink = EditText(this).apply {
            setHint(R.string.vk_link)
            setText(savedInstanceState?.getString("vkLink").orEmpty())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        content.addView(vkLink, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(28) })
        importButton = Button(this).apply {
            setText(R.string.import_profile)
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, IMPORT_REQUEST)
            }
        }
        content.addView(importButton, LinearLayout.LayoutParams(-1, dp(56)))
        content.addView(TextView(this).apply {
            setText(R.string.profile_hint); textSize = 14f; setTextColor(muted)
            setPadding(0, dp(8), 0, 0)
        })
        reloadProfiles()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
        }
    }

    private fun reloadProfiles(selected: String? = null) {
        val entries = store.ids().mapNotNull { id ->
            runCatching { id to JSONObject(Mobile.describeProfile(store.read(id))).getString("name").take(128) }.getOrNull()
        }
        ids = entries.map { it.first }
        val labels = entries.map { it.second }.ifEmpty { listOf(getString(R.string.no_profiles)) }
        profiles.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        selected?.let { profiles.setSelection(ids.indexOf(it).coerceAtLeast(0)) }
        connect.isEnabled = ids.isNotEmpty() || TunnelService.active
    }

    private fun beginConnection() {
        val id = pendingProfile ?: return
        pendingProfile = null
        startForegroundService(Intent(this, TunnelService::class.java).putExtra(TunnelService.EXTRA_PROFILE, id))
    }

    @Deprecated("Platform activity result API is available on every supported version")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST) {
            if (resultCode == RESULT_OK) beginConnection() else {
                pendingProfile = null
                showError(R.string.vpn_denied)
            }
        }
        if (requestCode != IMPORT_REQUEST || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val link = vkLink.text.toString().trim()
        importButton.isEnabled = false
        io.execute {
            val imported = runCatching {
                val raw = contentResolver.openInputStream(uri)?.use { input ->
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
                val profile = if (raw.trimStart().startsWith("{")) raw else {
                    try {
                        Mobile.describeWireGuard(raw, link)
                    } catch (_: Exception) {
                        throw ProfileImportException(getString(R.string.wireguard_validation))
                    }
                }
                try {
                    Mobile.describeProfile(profile)
                } catch (error: Exception) {
                    // DescribeProfile uses fixed messages without configuration values.
                    throw ProfileImportException(error.message.orEmpty().take(512))
                }
                store.save(profile)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                importButton.isEnabled = true
                imported.onSuccess { reloadProfiles(it) }.onFailure {
                    showError(R.string.import_error, if (it is ProfileImportException) it.message.orEmpty() else "")
                }
            }
        }
    }

    private fun showError(message: Int, detail: String = "") {
        val text = getString(message) + if (detail.isBlank()) "" else "\n\n$detail"
        AlertDialog.Builder(this).setMessage(text).setPositiveButton(android.R.string.ok, null).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingProfile", pendingProfile)
        outState.putString("vkLink", vkLink.text.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onResume() { super.onResume(); handler.post(poll) }
    override fun onPause() { handler.removeCallbacks(poll); super.onPause() }
    override fun onDestroy() { io.shutdown(); super.onDestroy() }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class ProfileImportException(message: String) : Exception(message)

    companion object {
        private const val IMPORT_REQUEST = 1
        private const val VPN_REQUEST = 2
    }
}

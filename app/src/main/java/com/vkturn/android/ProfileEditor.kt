package com.vkturn.android

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.StateListDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.vkturn.backend.mobile.Mobile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

internal class ProfileEditor(
    private val activity: Activity,
    private val profile: String?,
    initialDraft: String?,
    initialInput: String = "",
    private val onChooseFile: () -> Unit,
    private val onSave: (String) -> Unit,
    private val onClosed: () -> Unit,
    private val onDelete: (() -> Unit)? = null,
) {
    private val ui = ProfileUi(activity)
    private val defaults = JSONObject(Mobile.defaults())
    private val source = profile?.let { JSONObject(Mobile.getProfileSettings(it)) } ?: JSONObject()
    private val metadata = profile?.let { JSONObject(it).optJSONObject("androidEditor") } ?: JSONObject()
    private val restored = initialDraft?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
    private val existingLink = profile?.let { JSONObject(it).apply { remove("androidEditor") }.toString() }.orEmpty()
    private val initialInputs = ProfileInput.initial(initialInput)
    private val fallbackMode = if (initialInputs.mode == ProfileInputMode.WIREGUARD) "wireguard" else "link"
    private val mode = restored.optString("mode", metadata.optString("mode", fallbackMode))
    private val inputs = ProfileInput(
        mode = if (mode == "wireguard") ProfileInputMode.WIREGUARD else ProfileInputMode.LINK,
        link = restored.optString("linkInput", initialInputs.link.ifEmpty { metadata.optString("linkSource", existingLink) }),
        wireGuard = restored.optString("wgInput", metadata.optString("wgConfig", initialInputs.wireGuard)),
    )
    private val fields = linkedMapOf<String, EditText>()
    private val baselineFields = linkedMapOf<String, String>()
    private val selectors = linkedMapOf<String, Pair<Spinner, List<String>>>()
    private val routing = restored.optJSONObject("routing") ?: source.optJSONObject("routing") ?: JSONObject()
    private val selectedGeoIP = values(routing.optJSONArray("geoip")).toMutableSet()
    private val selectedGeoSite = values(routing.optJSONArray("geosite")).toMutableSet()
    private val io = Executors.newSingleThreadExecutor()
    private val dialog = Dialog(activity)
    private var saving = false
    private var connectionActive = false
    private lateinit var saveButton: Button
    private var deleteButton: Button? = null
    private lateinit var cancelButton: Button
    private lateinit var sourceInput: EditText
    private lateinit var sourceLabel: TextView
    private lateinit var sourceError: TextView
    private lateinit var modeControl: RadioGroup
    private val linkModeId = View.generateViewId()
    private val wireGuardModeId = View.generateViewId()
    private lateinit var wgOptions: LinearLayout
    private lateinit var advanced: LinearLayout
    private lateinit var routingContent: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var geoIP: Button
    private lateinit var geoSite: Button
    private lateinit var custom: EditText
    private lateinit var localDirect: CheckBox
    private lateinit var routingMode: Spinner
    private val modes = listOf("profile", "", "include", "exclude")
    private val content = column().apply { setPadding(dp(20), dp(12), dp(20), dp(32)) }

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = column().apply { setBackgroundColor(ui.background); fitsSystemWindows = true }
        val toolbar = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(ui.surface)
        }
        cancelButton = ui.button(R.string.cancel) { if (!saving) close() }
        toolbar.addView(cancelButton, LinearLayout.LayoutParams(-2, dp(48)))
        toolbar.addView(TextView(activity).apply {
            setText(if (profile == null) R.string.add_profile else R.string.edit_profile)
            setTextColor(ui.ink); textSize = 18f
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        saveButton = ui.button(R.string.save, primary = true) { save() }
        toolbar.addView(saveButton, LinearLayout.LayoutParams(-2, dp(48)))
        root.addView(toolbar)
        scroll = ScrollView(activity).apply { addView(content); isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        buildSources()
        field(content, "name", R.string.editor_name, source.optString("name"), max = 120, hint = R.string.from_profile)
        val row = LinearLayout(activity)
        fieldCell(row, "connections", R.string.editor_connections_short, source.optString("connections", defaults.getJSONObject("connections").getInt("default").toString()))
        fieldCell(row, "mtu", R.string.editor_mtu_short, source.optString("mtu", defaults.getJSONObject("mtu").getInt("default").toString()))
        content.addView(row)
        selector(content, "turnTransport", R.string.editor_turn, listOf("", "tcp", "udp"), listOf(activity.getString(R.string.from_profile), "TCP", "UDP"), source.optString("turnTransport"))
        advanced = column()
        disclosure(R.string.advanced_settings, advanced, restored.optBoolean("advancedOpen"))
        field(advanced, "dns", R.string.editor_dns, source.optString("dns"), hint = R.string.from_profile)
        field(advanced, "keepalive", R.string.editor_keepalive, source.optString("keepalive"), numeric = true, hint = R.string.from_profile)
        field(advanced, "turnServerOverride", R.string.editor_turn_server, source.optString("turnServerOverride"))
        field(advanced, "uplinkPaceKiB", R.string.editor_uplink, source.optString("uplinkPaceKiB"), numeric = true, hint = R.string.from_profile)
        field(advanced, "cooldownSeconds", R.string.editor_cooldown, source.optString("cooldownSeconds"), numeric = true, hint = R.string.from_profile)
        booleanSelector("vkAuth", R.string.editor_vk_auth)
        booleanSelector("forceLegacyCaptcha", R.string.editor_legacy_captcha)
        routingContent = column()
        disclosure(R.string.routing, routingContent, restored.optBoolean("routingOpen"))
        buildRouting()
        if (profile != null && onDelete != null) {
            deleteButton = ui.button(R.string.delete) { if (!saving && !connectionActive) onDelete() }
            content.addView(deleteButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(28) })
        }
        dialog.setContentView(root)
        dialog.setOnDismissListener { io.shutdown(); onClosed() }
        root.isFocusableInTouchMode = true
        root.requestFocus()
        updateMode()
    }

    private fun buildSources() {
        modeControl = RadioGroup(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = ui.shape(ui.surface, outline = true)
        }
        for ((index, label) in listOf(R.string.mode_link, R.string.mode_wireguard).withIndex()) {
            modeControl.addView(RadioButton(activity).apply {
                id = if (index == 0) linkModeId else wireGuardModeId
                setText(label); buttonDrawable = null
                gravity = Gravity.CENTER; textSize = 14f
                setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(ui.accent, ui.muted)))
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_checked), ui.shape(ui.selected))
                    addState(intArrayOf(), ui.shape(Color.TRANSPARENT))
                }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        modeControl.check(if (inputs.mode == ProfileInputMode.LINK) linkModeId else wireGuardModeId)
        content.addView(modeControl)
        sourceLabel = ui.label(R.string.mode_link)
        content.addView(sourceLabel)
        sourceInput = EditText(activity).apply {
            id = View.generateViewId()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            minLines = 5; maxLines = 7; gravity = Gravity.TOP
            typeface = Typeface.MONOSPACE
            filters = arrayOf(InputFilter.LengthFilter(MAX_PROFILE_BYTES))
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            ui.styleInput(this)
            setText(inputs.current())
        }
        sourceLabel.labelFor = sourceInput.id
        content.addView(sourceInput, LinearLayout.LayoutParams(-1, dp(144)))
        sourceError = TextView(activity).apply {
            setTextColor(Color.parseColor(if (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES) "#FF9A9A" else "#B3261E")); textSize = 13f
            visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(sourceError)
        val sourceActions = LinearLayout(activity).apply { setPadding(0, dp(8), 0, 0) }
        sourceActions.addView(ui.button(R.string.paste_clipboard) {
            val clipboard = activity.getSystemService(ClipboardManager::class.java)
            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty()
            if (text.isNotEmpty()) { sourceInput.setText(text); sourceError.visibility = View.GONE }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        sourceActions.addView(ui.button(R.string.from_file) { if (!connectionActive) onChooseFile() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        content.addView(sourceActions)
        wgOptions = column()
        field(wgOptions, "vkLink", R.string.vk_link, metadata.optString("vkLink", source.optString("vkLink")))
        field(wgOptions, "peer", R.string.editor_peer_override, metadata.optString("peerOverride"), hint = R.string.from_config)
        selector(wgOptions, "transport", R.string.editor_transport, listOf("srtp", "dtls"), listOf("SRTP", "DTLS"), metadata.optString("transport", source.optString("transport", defaults.getString("defaultTransport"))))
        content.addView(wgOptions)
        modeControl.setOnCheckedChangeListener { _, checked ->
            inputs.select(if (checked == wireGuardModeId) ProfileInputMode.WIREGUARD else ProfileInputMode.LINK, sourceInput.text.toString())
            sourceInput.setText(inputs.current())
            sourceError.visibility = View.GONE
            updateMode()
        }
    }

    private fun updateMode() {
        val wireGuard = inputs.mode == ProfileInputMode.WIREGUARD
        sourceLabel.setText(if (wireGuard) R.string.wg_config else R.string.link_json_label)
        sourceInput.setHint(if (wireGuard) R.string.wg_config_hint else R.string.link_json_hint)
        wgOptions.visibility = if (wireGuard) View.VISIBLE else View.GONE
    }

    fun loadFile(text: String) {
        inputs.select(inputs.mode, sourceInput.text.toString())
        inputs.loadFile(text)
        sourceInput.setText(inputs.current())
        modeControl.check(if (inputs.mode == ProfileInputMode.LINK) linkModeId else wireGuardModeId)
        sourceError.visibility = View.GONE
        updateMode()
    }

    @Suppress("DEPRECATION")
    fun show() {
        dialog.show()
        dialog.window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = ui.background
            navigationBarColor = ui.background
            val lightBars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            decorView.systemUiVisibility = if (ui.dark) decorView.systemUiVisibility and lightBars.inv()
                else decorView.systemUiVisibility or lightBars
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
    }
    fun close() { dialog.dismiss() }
    fun setSaving(value: Boolean) { saving = value; updateSaveButton() }
    fun setConnectionActive(value: Boolean) { connectionActive = value; updateSaveButton() }
    private fun updateSaveButton() {
        saveButton.isEnabled = !saving && !connectionActive
        deleteButton?.isEnabled = !saving && !connectionActive
        cancelButton.isEnabled = !saving
        dialog.setCancelable(!saving)
    }

    fun draft(): String {
        inputs.select(inputs.mode, sourceInput.text.toString())
        val value = JSONObject().put("mode", if (inputs.mode == ProfileInputMode.WIREGUARD) "wireguard" else "link")
            .put("linkInput", inputs.link).put("wgInput", inputs.wireGuard)
            .put("advancedOpen", advanced.visibility == View.VISIBLE).put("routingOpen", routingContent.visibility == View.VISIBLE)
        fields.forEach { (key, field) -> value.put(key, field.text.toString()) }
        selectors.forEach { (key, selection) -> value.put(key, selection.second[selection.first.selectedItemPosition]) }
        return value.put("routing", routingValue()).put("routingSelection", modes[routingMode.selectedItemPosition]).toString()
    }

    private fun save() {
        if (connectionActive || saving) return
        sourceError.visibility = View.GONE
        val raw = sourceInput.text.toString().trim().removePrefix("\uFEFF").trimStart()
        if (raw.isEmpty()) { sourceFailure(activity.getString(R.string.paste_empty)); return }
        val wireGuard = inputs.mode == ProfileInputMode.WIREGUARD
        if (!wireGuard && raw.startsWith("http", ignoreCase = true)) { sourceFailure(activity.getString(R.string.download_link_hint)); return }
        if (wireGuard != (ProfileInput.detectMode(raw) == ProfileInputMode.WIREGUARD)) {
            sourceFailure(activity.getString(if (wireGuard) R.string.switch_to_link else R.string.switch_to_wireguard)); return
        }
        if (wireGuard && fields.getValue("vkLink").text.isBlank()) { invalid(fields.getValue("vkLink")); return }
        val patch = JSONObject(metadata.optJSONObject("overrides")?.toString() ?: "{}")
        val connections = integer("connections", range("connections")) ?: return
        val mtu = integer("mtu", range("mtu")) ?: return
        patch.put("connections", connections).put("mtu", mtu)
        val name = fields.getValue("name").text.toString().trim()
        if (name.isEmpty()) patch.remove("name")
        else if (name != baselineFields["name"]) patch.put("name", name)
        patch.put("turnTransport", selection("turnTransport"))
        if (wireGuard) {
            val peer = fields.getValue("peer").text.toString().trim()
            if (peer.isNotEmpty()) patch.put("peer", peer) else patch.remove("peer")
            patch.put("transport", selection("transport"))
        } else {
            patch.remove("peer")
            patch.remove("transport")
        }
        for (key in listOf("dns", "keepalive", "turnServerOverride", "uplinkPaceKiB", "cooldownSeconds")) {
            val field = fields.getValue(key)
            if (field.text.toString() == baselineFields[key]) continue
            if (field.text.isBlank()) {
                patch.remove(key)
                continue
            }
            if (key in listOf("keepalive", "uplinkPaceKiB", "cooldownSeconds")) {
                val number = integer(key, if (key == "keepalive") 0..65535 else 0..Int.MAX_VALUE) ?: return
                patch.put(key, number)
            } else patch.put(key, field.text.toString())
        }
        for (key in listOf("vkAuth", "forceLegacyCaptcha")) {
            val selected = selection(key)
            if (selected.isEmpty()) patch.remove(key) else patch.put(key, selected == "true")
        }
        if (routingMode.selectedItemPosition == 0) patch.remove("routing") else patch.put("routing", routingValue())
        val vkLink = if (wireGuard) fields.getValue("vkLink").text.toString().trim() else ""
        val peerOverride = fields.getValue("peer").text.toString().trim()
        val transport = selection("transport")
        setSaving(true)
        io.execute {
            val result = runCatching {
                val imported = if (wireGuard) Mobile.importWireGuardProfile(raw, vkLink, peerOverride)
                    else Mobile.importProfile(raw, "")
                val canonical = if (wireGuard && profile != null) Mobile.mergeWireGuardProfile(profile, imported) else imported
                val updated = JSONObject(Mobile.updateProfile(canonical, patch.toString()))
                val editor = updated.optJSONObject("androidEditor") ?: JSONObject(metadata.toString())
                editor.put("mode", if (wireGuard) "wireguard" else "link").put("overrides", patch)
                if (wireGuard) {
                    editor.put("wgConfig", raw).put("vkLink", vkLink).put("peerOverride", peerOverride).put("transport", transport)
                    editor.remove("linkSource")
                } else {
                    val linkSource = if (raw.startsWith("{")) JSONObject(raw).apply { remove("androidEditor") }.toString() else raw
                    editor.put("linkSource", linkSource)
                    editor.remove("wgConfig")
                    editor.remove("vkLink")
                    editor.remove("peerOverride")
                    editor.remove("transport")
                }
                updated.put("androidEditor", editor).toString()
            }
            activity.runOnUiThread {
                if (!dialog.isShowing || activity.isDestroyed) return@runOnUiThread
                if (TunnelService.active) { setSaving(false); sourceFailure(activity.getString(R.string.disconnect_before_edit)); return@runOnUiThread }
                result.onSuccess(onSave).onFailure { setSaving(false); sourceFailure(it.message.orEmpty().take(512)) }
            }
        }
    }

    private fun sourceFailure(message: String) {
        sourceError.text = message
        sourceError.visibility = View.VISIBLE
        scroll.smoothScrollTo(0, 0)
    }

    private fun integer(key: String, bounds: IntRange): Int? {
        val field = fields.getValue(key)
        return field.text.toString().toIntOrNull()?.takeIf { it in bounds } ?: run {
            if (field.parent === advanced) advanced.visibility = View.VISIBLE
            field.error = activity.getString(R.string.value_range, bounds.first, bounds.last)
            field.requestFocus()
            null
        }
    }

    private fun invalid(field: EditText) {
        if (field.parent === advanced) advanced.visibility = View.VISIBLE
        field.error = activity.getString(R.string.invalid_value)
        field.requestFocus()
    }

    private fun fieldCell(row: LinearLayout, key: String, label: Int, initial: String) {
        val cell = column()
        field(cell, key, label, initial, numeric = true)
        row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { if (row.childCount > 0) leftMargin = dp(12) })
    }

    private fun field(parent: LinearLayout, key: String, title: Int, initial: String, numeric: Boolean = false, max: Int = 2048, hint: Int? = null) {
        baselineFields[key] = initial
        val field = EditText(activity).apply {
            id = View.generateViewId()
            inputType = when {
                numeric -> InputType.TYPE_CLASS_NUMBER
                key == "vkLink" || key == "peer" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                else -> InputType.TYPE_CLASS_TEXT
            }
            setSingleLine(true)
            setText(restored.optString(key, initial))
            filters = arrayOf(InputFilter.LengthFilter(max))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isSaveEnabled = false
            ui.styleInput(this)
            if (hint != null) setHint(hint)
        }
        fields[key] = field
        parent.addView(ui.label(title).apply { labelFor = field.id })
        parent.addView(field, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun selector(parent: LinearLayout, key: String, title: Int, values: List<String>, labels: List<String>, initial: String) {
        val control = Spinner(activity).apply {
            id = View.generateViewId()
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(values.indexOf(restored.optString(key, initial)).coerceAtLeast(0))
            ui.styleSpinner(this)
        }
        selectors[key] = control to values
        parent.addView(ui.label(title).apply { labelFor = control.id })
        parent.addView(control, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun selection(key: String): String = selectors.getValue(key).let { it.second[it.first.selectedItemPosition] }

    private fun booleanSelector(key: String, title: Int) {
        val overrides = metadata.optJSONObject("overrides")
        val initial = if (overrides?.has(key) == true) overrides.optBoolean(key).toString() else ""
        selector(advanced, key, title, listOf("", "true", "false"),
            listOf(activity.getString(R.string.from_profile), activity.getString(R.string.option_yes), activity.getString(R.string.option_no)), initial)
    }

    private fun disclosure(title: Int, body: LinearLayout, expanded: Boolean) {
        body.visibility = if (expanded) View.VISIBLE else View.GONE
        val toggle = ui.button(title) {}
        fun updateArrow() {
            toggle.setCompoundDrawablesWithIntrinsicBounds(0, 0,
                if (body.visibility == View.VISIBLE) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down, 0)
            toggle.compoundDrawableTintList = ColorStateList.valueOf(ui.muted)
        }
        toggle.setOnClickListener {
            body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            updateArrow()
        }
        updateArrow()
        content.addView(toggle, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(20) })
        content.addView(body)
    }

    private fun buildRouting() {
        routingMode = Spinner(activity).apply {
            contentDescription = activity.getString(R.string.routing_mode)
            ui.styleSpinner(this)
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf(activity.getString(R.string.from_profile), activity.getString(R.string.routing_all), activity.getString(R.string.routing_include), activity.getString(R.string.routing_exclude)))
            val initial = if (metadata.optJSONObject("overrides")?.has("routing") == true) routing.optString("mode") else "profile"
            setSelection(modes.indexOf(restored.optString("routingSelection", initial)).coerceAtLeast(0))
        }
        routingContent.addView(routingMode, LinearLayout.LayoutParams(-1, dp(48)))
        geoIP = ui.button(R.string.geoip) { chooseCatalog("geoip", selectedGeoIP, R.string.geoip) }
        geoSite = ui.button(R.string.geosite) { chooseCatalog("geosite", selectedGeoSite, R.string.geosite) }
        routingContent.addView(geoIP); routingContent.addView(geoSite)
        updateSelectionLabels()
        custom = EditText(activity).apply {
            id = View.generateViewId()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3; maxLines = 7; setText(routing.optString("custom"))
            filters = arrayOf(InputFilter.LengthFilter(65536))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            ui.styleInput(this)
        }
        routingContent.addView(ui.label(R.string.routing_custom).apply { labelFor = custom.id })
        routingContent.addView(custom)
        localDirect = CheckBox(activity).apply { setText(R.string.routing_local); isChecked = routing.optBoolean("localDirect") }
        routingContent.addView(localDirect)
        routingMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { updateRoutingControls() }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        updateRoutingControls()
        routingContent.addView(ui.button(R.string.update_catalogs) {
            RoutingCatalogUpdates.update(activity) { result ->
                if (dialog.isShowing && !activity.isDestroyed) result.onSuccess { showMessage(R.string.catalog_updated) }.onFailure { showMessage(R.string.error, it.message.orEmpty()) }
            }
        })
    }

    private fun routingValue(): JSONObject = JSONObject(routing.toString()).apply {
        put("mode", modes[routingMode.selectedItemPosition]); put("geoip", JSONArray(selectedGeoIP.toList().sorted()))
        put("geosite", JSONArray(selectedGeoSite.toList().sorted())); put("custom", custom.text.toString()); put("localDirect", localDirect.isChecked)
    }

    private fun updateRoutingControls() {
        val selected = routingMode.selectedItemPosition
        geoIP.isEnabled = selected >= 2
        geoSite.isEnabled = selected >= 2
        custom.isEnabled = selected >= 2
        localDirect.isEnabled = selected != 0
    }

    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun chooseCatalog(key: String, selected: MutableSet<String>, title: Int) {
        io.execute {
            val result = runCatching { JSONObject(Mobile.routingCatalog(routingDirectory())).getJSONArray(key) }
            activity.runOnUiThread {
                if (!dialog.isShowing || activity.isDestroyed) return@runOnUiThread
                result.onSuccess { showCatalog(it, selected, title) }
                    .onFailure { showMessage(R.string.catalog_missing) }
            }
        }
    }

    private fun showCatalog(entries: JSONArray, selected: MutableSet<String>, title: Int) {
        val before = selected.toSet()
        val all = (0 until entries.length()).map { entries.getJSONObject(it) }
        var visible = all
        val search = EditText(activity).apply { setHint(R.string.search); setSingleLine(true) }
        val list = ListView(activity).apply { choiceMode = ListView.CHOICE_MODE_MULTIPLE }
        fun refresh(query: String) {
            visible = all.filter { it.optString("name").contains(query, true) || it.getString("id").contains(query, true) }
            list.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_multiple_choice,
                visible.map { it.optString("name", it.getString("id")) })
            visible.forEachIndexed { index, entry -> list.setItemChecked(index, entry.getString("id") in selected) }
        }
        list.setOnItemClickListener { _, _, position, _ ->
            val id = visible[position].getString("id")
            if (list.isItemChecked(position)) selected.add(id) else selected.remove(id)
            updateSelectionLabels()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh(s.toString()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        refresh("")
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            addView(search)
            addView(list, LinearLayout.LayoutParams(-1, dp(360)))
        }
        AlertDialog.Builder(activity).setTitle(title).setView(layout)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.clear_selection) { _, _ -> selected.clear(); updateSelectionLabels() }
            .setNegativeButton(R.string.cancel) { _, _ -> selected.clear(); selected.addAll(before); updateSelectionLabels() }
            .show()
    }

    private fun updateSelectionLabels() {
        geoIP.text = activity.getString(R.string.status_detail, activity.getString(R.string.geoip), activity.getString(R.string.selected_count, selectedGeoIP.size))
        geoSite.text = activity.getString(R.string.status_detail, activity.getString(R.string.geosite), activity.getString(R.string.selected_count, selectedGeoSite.size))
    }

    private fun showMessage(title: Int, detail: String = "") {
        AlertDialog.Builder(activity).setMessage(activity.getString(title) + if (detail.isEmpty()) "" else "\n\n${detail.take(512)}")
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun range(key: String): IntRange = defaults.getJSONObject(key).let { it.getInt("min")..it.getInt("max") }
    private fun routingDirectory(): String = File(activity.noBackupFilesDir, "routing-data").absolutePath
    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
    private fun values(array: JSONArray?): List<String> = (0 until (array?.length() ?: 0)).map { array!!.getString(it) }
}

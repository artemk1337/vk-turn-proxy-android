package com.vkturn.android

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
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
    private val profile: String,
    initialDraft: String?,
    private val onSourceChanged: (String) -> Unit,
    private val onSave: (String) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val defaults = JSONObject(Mobile.defaults())
    private val fields = linkedMapOf<String, EditText>()
    private val selectors = linkedMapOf<String, Pair<Spinner, List<String>>>()
    private val checks = linkedMapOf<String, CheckBox>()
    private val source = JSONObject(Mobile.getProfileSettings(profile))
    private val draft = initialDraft?.let { runCatching { JSONObject(it) }.getOrNull() } ?: source
    private val routing = draft.optJSONObject("routing") ?: JSONObject()
    private val selectedGeoIP = values(routing.optJSONArray("geoip")).toMutableSet()
    private val selectedGeoSite = values(routing.optJSONArray("geosite")).toMutableSet()
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var dialog: AlertDialog
    private var saving = false
    private var connectionActive = false
    private var sourceDialog: AlertDialog? = null
    private var sourceInput: EditText? = null
    private var sourceLink: EditText? = null
    private lateinit var geoIP: Button
    private lateinit var geoSite: Button
    private lateinit var custom: EditText
    private lateinit var localDirect: CheckBox
    private lateinit var routingMode: Spinner
    private val modes = listOf("", "include", "exclude")
    private val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(12), dp(20), dp(20))
    }

    init {
        content.addView(Button(activity).apply {
            setText(R.string.edit_source)
            setOnClickListener { showSourceEditor() }
        })
        field("name", R.string.editor_name, max = 120)
        field("peer", R.string.editor_peer)
        selector("transport", R.string.editor_transport, listOf("srtp", "dtls"), listOf("SRTP", "DTLS"))
        selector("turnTransport", R.string.editor_turn, listOf("", "tcp", "udp"), listOf(activity.getString(R.string.from_profile), "TCP", "UDP"))
        field("connections", R.string.editor_connections, numeric = true)
        field("mtu", R.string.editor_mtu, numeric = true)
        field("dns", R.string.editor_dns)
        field("vkLink", R.string.vk_link)
        field("keepalive", R.string.editor_keepalive, numeric = true)
        field("turnServerOverride", R.string.editor_turn_server)
        field("uplinkPaceKiB", R.string.editor_uplink, numeric = true)
        field("cooldownSeconds", R.string.editor_cooldown, numeric = true)
        check("vkAuth", R.string.editor_vk_auth)
        check("forceLegacyCaptcha", R.string.editor_legacy_captcha)
        label(R.string.routing)
        routingMode = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf(
                activity.getString(R.string.routing_all), activity.getString(R.string.routing_include), activity.getString(R.string.routing_exclude)))
            setSelection(modes.indexOf(routing.optString("mode")).coerceAtLeast(0))
        }
        content.addView(routingMode, LinearLayout.LayoutParams(-1, dp(56)))
        geoIP = Button(activity).apply { setOnClickListener { chooseCatalog("geoip", selectedGeoIP, R.string.geoip) } }
        geoSite = Button(activity).apply { setOnClickListener { chooseCatalog("geosite", selectedGeoSite, R.string.geosite) } }
        content.addView(geoIP)
        content.addView(geoSite)
        updateSelectionLabels()
        custom = EditText(activity).apply {
            id = View.generateViewId()
            setText(routing.optString("custom"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 7
            filters = arrayOf(InputFilter.LengthFilter(65536))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        label(R.string.routing_custom, custom)
        content.addView(custom, LinearLayout.LayoutParams(-1, -2))
        localDirect = CheckBox(activity).apply { setText(R.string.routing_local); isChecked = routing.optBoolean("localDirect") }
        content.addView(localDirect)
        content.addView(Button(activity).apply {
            setText(R.string.update_catalogs)
            setOnClickListener {
                isEnabled = false
                RoutingCatalogUpdates.update(activity) { result ->
                    if (!dialog.isShowing || activity.isDestroyed) return@update
                    isEnabled = true
                    result.onSuccess { showMessage(R.string.catalog_updated) }
                        .onFailure { showMessage(R.string.error, it.message.orEmpty()) }
                }
            }
        })
        dialog = AlertDialog.Builder(activity).setTitle(R.string.edit_profile)
            .setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            setConnectionActive(TunnelService.active)
            draft.optJSONObject("sourceEditor")?.let { showSourceEditor(it) }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching { patch() }.onSuccess(onSave)
            }
        }
        dialog.setOnDismissListener { sourceDialog?.dismiss(); io.shutdown(); onClosed() }
    }

    fun show() { dialog.show() }
    fun close() { dialog.dismiss() }
    fun setSaving(value: Boolean) { saving = value; updateSaveButton() }
    fun setConnectionActive(value: Boolean) { connectionActive = value; updateSaveButton() }
    private fun updateSaveButton() { dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !saving && !connectionActive }

    fun draft(): String {
        val value = JSONObject()
        fields.forEach { (key, field) -> value.put(key, field.text.toString()) }
        selectors.forEach { (key, selection) -> value.put(key, selection.second[selection.first.selectedItemPosition]) }
        checks.forEach { (key, check) -> value.put(key, check.isChecked) }
        value.put("routing", routingValue())
        if (sourceDialog?.isShowing == true) value.put("sourceEditor", JSONObject()
            .put("input", sourceInput?.text.toString()).put("vkLink", sourceLink?.text.toString()))
        return value.toString()
    }

    private fun showSourceEditor(saved: JSONObject? = null) {
        if (TunnelService.active) { showMessage(R.string.disconnect_before_edit); return }
        val input = EditText(activity).apply {
            setText(saved?.optString("input") ?: profile)
            setHint(R.string.profile_input)
            minLines = 5
            maxLines = 10
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isSaveEnabled = false
            filters = arrayOf(InputFilter.LengthFilter(MAX_PROFILE_BYTES))
        }
        val link = EditText(activity).apply {
            setHint(R.string.vk_link)
            setText(saved?.optString("vkLink").orEmpty())
            setSingleLine(true)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isSaveEnabled = false
        }
        sourceInput = input
        sourceLink = link
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(TextView(activity).apply { setText(R.string.edit_source_hint) })
            addView(input)
            addView(link)
        }
        val editor = AlertDialog.Builder(activity).setTitle(R.string.edit_source)
            .setView(ScrollView(activity).apply { addView(layout) })
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.apply_source, null).create()
        sourceDialog = editor
        editor.setOnDismissListener { sourceDialog = null; sourceInput = null; sourceLink = null }
        editor.setOnShowListener {
            editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (TunnelService.active) { showMessage(R.string.disconnect_before_edit); return@setOnClickListener }
                if (input.text.isBlank()) { input.error = activity.getString(R.string.paste_empty); return@setOnClickListener }
                editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                val raw = input.text.toString()
                val vkLink = link.text.toString()
                io.execute {
                    val result = runCatching { Mobile.importProfile(raw, vkLink.trim()) }
                    activity.runOnUiThread {
                        if (!dialog.isShowing || !editor.isShowing || activity.isDestroyed) return@runOnUiThread
                        editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        if (TunnelService.active) { showMessage(R.string.disconnect_before_edit); return@runOnUiThread }
                        result.onSuccess { canonical -> editor.dismiss(); onSourceChanged(canonical) }
                            .onFailure { showMessage(R.string.import_error, it.message.orEmpty()) }
                    }
                }
            }
        }
        editor.show()
    }

    private fun patch(): String {
        val value = JSONObject(draft())
        value.remove("sourceEditor")
        if (fields.getValue("name").text.isBlank()) invalid(fields.getValue("name"))
        for ((key, bounds) in mapOf("connections" to range("connections"), "mtu" to range("mtu"), "keepalive" to 0..65535,
            "uplinkPaceKiB" to 0..Int.MAX_VALUE, "cooldownSeconds" to 0..Int.MAX_VALUE)) {
            val field = fields.getValue(key)
            val number = field.text.toString().toIntOrNull()
            if (number == null || number !in bounds) invalid(field)
            value.put(key, number)
        }
        return value.toString()
    }

    private fun routingValue(): JSONObject = JSONObject(routing.toString()).apply {
        put("mode", modes[routingMode.selectedItemPosition])
        put("geoip", JSONArray(selectedGeoIP.toList().sorted()))
        put("geosite", JSONArray(selectedGeoSite.toList().sorted()))
        put("custom", custom.text.toString())
        put("localDirect", localDirect.isChecked)
    }

    private fun invalid(field: EditText): Nothing {
        field.error = activity.getString(R.string.invalid_value)
        field.requestFocus()
        throw IllegalArgumentException("invalid field")
    }

    private fun label(text: Int, target: View? = null) {
        content.addView(TextView(activity).apply {
            this.text = when (text) {
                R.string.editor_connections -> activity.getString(text, range("connections").first, range("connections").last)
                R.string.editor_mtu -> activity.getString(text, range("mtu").first, range("mtu").last)
                else -> activity.getString(text)
            }
            setPadding(0, dp(12), 0, dp(2))
            if (target != null) labelFor = target.id
        })
    }

    private fun field(key: String, title: Int, numeric: Boolean = false, max: Int = 2048) {
        val field = EditText(activity).apply {
            id = View.generateViewId()
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setText(draft.optString(key, source.optString(key)))
            filters = arrayOf(InputFilter.LengthFilter(max))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        fields[key] = field
        label(title, field)
        content.addView(field, LinearLayout.LayoutParams(-1, dp(52)))
    }

    private fun selector(key: String, title: Int, values: List<String>, labels: List<String>) {
        val selector = Spinner(activity).apply {
            id = View.generateViewId()
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(values.indexOf(draft.optString(key)).coerceAtLeast(0))
        }
        selectors[key] = selector to values
        label(title, selector)
        content.addView(selector, LinearLayout.LayoutParams(-1, dp(52)))
    }

    private fun check(key: String, title: Int) {
        val control = CheckBox(activity).apply { setText(title); isChecked = draft.optBoolean(key, source.optBoolean(key)) }
        checks[key] = control
        content.addView(control)
    }

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

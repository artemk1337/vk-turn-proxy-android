package com.vkturn.android

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

internal class SettingsScreen(
    private val activity: Activity,
    private val before: AppPreferences,
    logMin: Int,
    logMax: Int,
    onSave: (String, String, Boolean, Boolean, Int) -> Unit,
    onUpdates: () -> Unit,
    onAccount: () -> Unit,
    onLogs: () -> Unit,
    onAbout: () -> Unit,
) {
    private val ui = ProfileUi(activity)
    private val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dp(24), ui.dp(28), ui.dp(24), ui.dp(24))
    }
    val view = ScrollView(activity).apply { addView(content); isFillViewport = true }
    private val language: Spinner
    private val theme: Spinner
    private val autostart: Switch
    private val autoUpdate: Switch
    private val limit: EditText

    init {
        content.addView(TextView(activity).apply {
            setText(R.string.settings); textSize = 27f; setTextColor(ui.ink); setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(24) })
        val appearance = group()
        language = selection(appearance, R.string.language, listOf("English", "Русский"), if (before.language == "ru") 1 else 0)
        theme = selection(appearance, R.string.theme, listOf(activity.getString(R.string.light), activity.getString(R.string.dark)), if (before.theme == "dark") 1 else 0)
        val behavior = group()
        autostart = toggle(behavior, R.string.autostart, before.autostart)
        autoUpdate = toggle(behavior, R.string.routing_auto_update, before.routingAutoUpdate)
        val logRow = row(behavior)
        logRow.addView(label(R.string.log_lines), LinearLayout.LayoutParams(0, -2, 1f))
        limit = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(String.format(resources.configuration.locales[0], "%d", before.logLines))
            contentDescription = activity.getString(R.string.log_limit, logMin, logMax)
            ui.styleInput(this)
        }
        logRow.addView(limit, LinearLayout.LayoutParams(ui.dp(88), ui.dp(48)))
        content.addView(ui.button(R.string.save, primary = true) {
            val lines = limit.text.toString().toIntOrNull()
            if (lines == null || lines !in logMin..logMax) limit.error = activity.getString(R.string.invalid_value)
            else onSave(if (language.selectedItemPosition == 1) "ru" else "en", if (theme.selectedItemPosition == 1) "dark" else "light", autostart.isChecked, autoUpdate.isChecked, lines)
        }, LinearLayout.LayoutParams(-1, ui.dp(48)).apply { topMargin = ui.dp(4); bottomMargin = ui.dp(24) })
        content.addView(menuRow(activity, ui, R.string.app_updates, R.drawable.ic_update, onClick = onUpdates), spacing())
        content.addView(menuRow(activity, ui, R.string.account_title, R.drawable.ic_account, onClick = onAccount), spacing())
        content.addView(menuRow(activity, ui, R.string.logs, R.drawable.ic_logs, onClick = onLogs), spacing())
        content.addView(menuRow(activity, ui, R.string.about, R.drawable.ic_info, AppUpdates.installedVersion(activity), onClick = onAbout), spacing().apply { topMargin = ui.dp(12) })
    }

    fun draft() = Bundle().apply {
        putInt("language", language.selectedItemPosition)
        putInt("theme", theme.selectedItemPosition)
        putBoolean("autostart", autostart.isChecked)
        putBoolean("autoUpdate", autoUpdate.isChecked)
        putString("limit", limit.text.toString())
    }

    fun restore(draft: Bundle?) {
        if (draft == null) return
        language.setSelection(draft.getInt("language"))
        theme.setSelection(draft.getInt("theme"))
        autostart.isChecked = draft.getBoolean("autostart")
        autoUpdate.isChecked = draft.getBoolean("autoUpdate")
        limit.setText(draft.getString("limit", before.logLines.toString()))
    }

    fun setAutostart(enabled: Boolean) { autostart.isChecked = enabled }

    private fun group() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = ui.shape(ui.surface, true)
        content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) })
    }

    private fun row(group: LinearLayout) = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ui.dp(64)
        setPadding(ui.dp(16), ui.dp(8), ui.dp(12), ui.dp(8))
        group.addView(this)
    }

    private fun label(resource: Int) = TextView(activity).apply { setText(resource); textSize = 14f; setTextColor(ui.ink) }
    private fun selection(group: LinearLayout, label: Int, values: List<String>, selected: Int): Spinner {
        val row = row(group)
        row.addView(label(label), LinearLayout.LayoutParams(0, -2, 1f))
        return Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, values)
            contentDescription = activity.getString(label)
            ui.styleSpinner(this)
            setSelection(selected)
            row.addView(this, LinearLayout.LayoutParams(ui.dp(142), ui.dp(48)))
        }
    }

    private fun toggle(group: LinearLayout, label: Int, value: Boolean): Switch {
        val row = row(group)
        val name = label(label)
        row.addView(name, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = ui.dp(12) })
        return Switch(activity).apply {
            isChecked = value
            contentDescription = activity.getString(label)
            minHeight = ui.dp(48)
            row.addView(this)
            name.setOnClickListener { isChecked = !isChecked }
        }
    }

    private fun spacing() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) }
}

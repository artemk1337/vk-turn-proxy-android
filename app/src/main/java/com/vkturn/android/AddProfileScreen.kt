package com.vkturn.android

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.content.ClipboardManager
import android.graphics.Typeface
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal class AddProfileScreen(
    private val activity: Activity,
    onImport: (String) -> Unit,
    onScan: () -> Unit,
    onFile: () -> Unit,
) {
    private val ui = ProfileUi(activity)
    private val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dp(24), ui.dp(28), ui.dp(24), ui.dp(24))
    }
    val view = ScrollView(activity).apply { isFillViewport = true; addView(content) }
    private val source = EditText(activity).apply {
        hint = activity.getString(R.string.import_source)
        contentDescription = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setSingleLine(true)
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        filters = arrayOf(InputFilter.LengthFilter(MAX_PROFILE_BYTES))
        setTextColor(ui.ink)
        setHintTextColor(ui.muted)
        textSize = 15f
        background = null
        setPadding(ui.dp(16), 0, ui.dp(8), 0)
    }
    private val proceed = ui.button(R.string.continue_import, primary = true) { onImport(text()) }
    private val paste = ui.button(R.string.paste_clipboard) {
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        val value = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty()
        if (value.isBlank()) source.error = activity.getString(R.string.paste_empty)
        else if (value.toByteArray(Charsets.UTF_8).size > MAX_PROFILE_BYTES) source.error = activity.getString(R.string.import_too_large)
        else { source.setText(value); source.setSelection(source.length()) }
    }
    private val scan = menuRow(activity, ui, R.string.scan_qr, R.drawable.ic_qr, onClick = onScan)
    private val file = menuRow(activity, ui, R.string.import_from_file, R.drawable.ic_import_file, onClick = onFile)

    init {
        content.addView(TextView(activity).apply {
            setText(R.string.add_profile); textSize = 27f; setTextColor(ui.ink); setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(28) })
        paste.apply {
            setTextColor(ui.accent)
            setPadding(ui.dp(16), 0, ui.dp(16), 0)
            background = RippleDrawable(ColorStateList.valueOf(ui.selected), null, ui.shape(Color.WHITE).apply {
                val radius = ui.dp(11).toFloat()
                cornerRadii = floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
            })
        }
        val row = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ui.shape(ui.surface, true)
            setPadding(ui.dp(1), ui.dp(1), ui.dp(1), ui.dp(1))
        }
        row.addView(source, LinearLayout.LayoutParams(0, ui.dp(58), 1f))
        row.addView(View(activity).apply { setBackgroundColor(ui.border) }, LinearLayout.LayoutParams(ui.dp(1), ui.dp(32)))
        row.addView(paste, LinearLayout.LayoutParams(-2, ui.dp(58)))
        content.addView(row)
        proceed.visibility = View.GONE
        content.addView(proceed, LinearLayout.LayoutParams(-1, ui.dp(48)).apply { topMargin = ui.dp(12) })
        source.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) { proceed.visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE }
            override fun afterTextChanged(value: Editable?) = Unit
        })
        source.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                if (text().isBlank()) source.error = activity.getString(R.string.paste_empty) else onImport(text())
                true
            } else false
        }
        content.addView(View(activity).apply { setBackgroundColor(ui.border) }, LinearLayout.LayoutParams(-1, ui.dp(1)).apply { topMargin = ui.dp(28); bottomMargin = ui.dp(28) })
        content.addView(scan, LinearLayout.LayoutParams(-1, -2))
        content.addView(file, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
    }

    fun text(): String = source.text.toString()
    fun restore(value: String) { source.setText(value) }
    fun clear() { source.setText("") }
    fun setConnectionActive(active: Boolean) {
        listOf(source, paste, proceed, scan, file).forEach {
            it.isEnabled = !active
            it.alpha = if (active) 0.45f else 1f
        }
    }
}

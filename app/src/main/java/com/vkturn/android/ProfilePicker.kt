package com.vkturn.android

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView

internal class ProfilePicker(
    private val activity: Activity,
    private val onSelect: (String) -> Unit,
    private val onEdit: (String) -> Unit,
) {
    private val ui = ProfileUi(activity)
    private val dialog = Dialog(activity)
    private val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; contentDescription = activity.getString(R.string.nav_profiles) }
    private val controls = mutableListOf<View>()
    private var entries = emptyList<ProfileSummary>()
    private var selectedId = ""
    private var active = false
    val isShowing: Boolean get() = dialog.isShowing

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(24))
            background = ui.shape(ui.surface).apply {
                val radius = ui.dp(24).toFloat()
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
        }
        content.addView(View(activity).apply { background = ui.shape(ui.muted) }, LinearLayout.LayoutParams(ui.dp(32), ui.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ui.dp(22) })
        content.addView(TextView(activity).apply {
            setText(R.string.choose_profile); textSize = 22f; setTextColor(ui.ink); setTypeface(typeface, Typeface.BOLD)
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(20) })
        content.addView(list)
        dialog.setContentView(ScrollView(activity).apply { addView(content); clipToPadding = false })
    }

    fun update(values: List<ProfileSummary>, selected: String) {
        entries = values
        selectedId = selected
        if (isShowing) render()
    }

    fun show() {
        render()
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            val maximum = (activity.resources.displayMetrics.heightPixels * 0.72f).toInt()
            val desired = ui.dp(108 + entries.size.coerceAtMost(20) * 76)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, minOf(maximum, desired))
            setDimAmount(0.35f)
        }
    }

    fun close() { dialog.dismiss() }

    fun setConnectionActive(value: Boolean) {
        active = value
        controls.forEach { it.isEnabled = !active; it.alpha = if (active) 0.45f else 1f }
    }

    private fun render() {
        list.removeAllViews()
        controls.clear()
        if (entries.isEmpty()) list.addView(TextView(activity).apply { setText(R.string.no_profiles); setTextColor(ui.muted); textSize = 15f })
        val colors = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(ui.accent, ui.muted))
        for (entry in entries) {
            val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = ui.dp(76) }
            val select = { close(); onSelect(entry.id) }
            val radio = RadioButton(activity).apply {
                buttonTintList = colors
                isChecked = entry.id == selectedId
                contentDescription = activity.getString(R.string.select_profile_name, entry.name)
                setOnClickListener { select() }
            }
            row.addView(radio, LinearLayout.LayoutParams(ui.dp(48), ui.dp(56)))
            val names = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ui.dp(64)
                isClickable = true
                isFocusable = true
                background = RippleDrawable(ColorStateList.valueOf(ui.selected), null, ui.shape(Color.WHITE))
                setOnClickListener { select() }
                addView(TextView(activity).apply { text = entry.name; textSize = 16f; setTextColor(ui.ink) })
                addView(TextView(activity).apply { text = entry.detail; textSize = 12f; setTextColor(ui.muted); setPadding(0, ui.dp(4), 0, 0) })
            }
            row.addView(names, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = ui.dp(8) })
            val edit = ImageButton(activity).apply {
                setImageResource(R.drawable.ic_nav_settings)
                imageTintList = ColorStateList.valueOf(ui.muted)
                setPadding(ui.dp(13), ui.dp(13), ui.dp(13), ui.dp(13))
                background = RippleDrawable(ColorStateList.valueOf(ui.selected), null, ui.shape(Color.WHITE))
                contentDescription = activity.getString(R.string.profile_settings_name, entry.name)
                tooltipText = activity.getString(R.string.profile_settings)
                setOnClickListener { close(); onEdit(entry.id) }
            }
            row.addView(edit, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
            controls.addAll(listOf(radio, names, edit))
            list.addView(row)
        }
        setConnectionActive(active)
    }
}

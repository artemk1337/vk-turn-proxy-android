package com.vkturn.android

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal data class ProfileSummary(val id: String, val name: String, val detail: String)

internal class MainScreen(
    private val activity: Activity,
    settings: View,
    addition: View,
    onConnect: () -> Unit,
    onSelect: (String) -> Unit,
    onEdit: (String) -> Unit,
    onAccount: () -> Unit,
    onCaptcha: () -> Unit,
) {
    private val ui = ProfileUi(activity)
    val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ui.background) }
    private val pages = FrameLayout(activity)
    private val home = column()
    val power = PowerButton(activity).apply { setOnClickListener { onConnect() } }
    val state = text(R.string.disconnected, 16f, ui.ink).apply {
        gravity = Gravity.CENTER
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    val traffic = text(0, 13f, ui.muted).apply { gravity = Gravity.CENTER; visibility = View.GONE }
    val captcha = ui.button(R.string.account_captcha, action = onCaptcha).apply { visibility = View.GONE }
    private val homeEmpty = ui.button(R.string.add_profile) { showPage(2) }
    private val selection = column()
    private val profilePicker = ProfilePicker(activity, onSelect, onEdit)
    val isProfilePickerOpen: Boolean get() = profilePicker.isShowing
    private val navigation = mutableListOf<ImageButton>()
    var page = 0
        private set

    init {
        val brand = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        brand.addView(ImageView(activity).apply { setImageResource(R.drawable.logo); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)))
        brand.addView(text(R.string.app_name, 19f, ui.ink).apply { setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ui.dp(10) })
        home.addView(brand, LinearLayout.LayoutParams(-1, ui.dp(40)))
        val center = column().apply { gravity = Gravity.CENTER; minimumHeight = ui.dp(316) }
        center.addView(power, LinearLayout.LayoutParams(ui.dp(216), ui.dp(216)))
        center.addView(state, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        center.addView(traffic, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        center.addView(captcha)
        home.addView(center, LinearLayout.LayoutParams(-1, -2, 1f))
        home.addView(selection)
        home.addView(homeEmpty, LinearLayout.LayoutParams(-1, ui.dp(52)))
        home.addView(menuRow(activity, ui, R.string.account_title, R.drawable.ic_account, onClick = onAccount), LinearLayout.LayoutParams(-1, ui.dp(64)).apply { topMargin = ui.dp(12) })
        pages.addView(scroll(home))
        pages.addView(settings)
        pages.addView(addition)
        root.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))
        val bar = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(24), ui.dp(10), ui.dp(24), ui.dp(10))
            setBackgroundColor(ui.surface)
        }
        for ((index, item) in listOf(R.string.nav_home to R.drawable.ic_nav_home, R.string.settings to R.drawable.ic_nav_settings, R.string.add_profile to R.drawable.ic_nav_add).withIndex()) {
            val holder = FrameLayout(activity)
            val button = ImageButton(activity).apply {
                setImageResource(item.second)
                contentDescription = activity.getString(item.first)
                tooltipText = contentDescription
                setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
                background = RippleDrawable(ColorStateList.valueOf(ui.selected), ui.shape(ui.surface), null)
                setOnClickListener { showPage(index) }
            }
            holder.addView(button, FrameLayout.LayoutParams(ui.dp(72), ui.dp(48), Gravity.CENTER))
            navigation.add(button)
            bar.addView(holder, LinearLayout.LayoutParams(0, ui.dp(52), 1f))
        }
        root.addView(View(activity).apply { setBackgroundColor(ui.border) }, LinearLayout.LayoutParams(-1, ui.dp(1)))
        root.addView(bar)
        @Suppress("DEPRECATION")
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        showPage(0)
    }

    fun showPage(index: Int) {
        profilePicker.close()
        page = index.coerceIn(0, 2)
        for (i in 0 until pages.childCount) pages.getChildAt(i).visibility = if (i == page) View.VISIBLE else View.GONE
        navigation.forEachIndexed { i, button ->
            button.isSelected = i == page
            button.imageTintList = ColorStateList.valueOf(if (i == page) ui.accent else ui.muted)
            button.background = RippleDrawable(ColorStateList.valueOf(ui.selected), ui.shape(if (i == page) ui.selected else ui.surface), null)
        }
    }

    fun renderProfiles(entries: List<ProfileSummary>, selectedId: String) {
        profilePicker.update(entries, selectedId)
        selection.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        selection.removeAllViews()
        entries.firstOrNull { it.id == selectedId }?.let { selected ->
            selection.addView(menuRow(activity, ui, R.string.profiles, R.drawable.ic_nav_profiles,
                title = selected.name, onClick = { showProfilePicker() }).apply {
                contentDescription = activity.getString(R.string.profiles) + ", " + selected.name
            }, LinearLayout.LayoutParams(-1, ui.dp(64)))
        }
        homeEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    fun showProfilePicker() { profilePicker.show() }
    fun close() { profilePicker.close() }

    fun setConnection(status: String, active: Boolean, hasProfiles: Boolean) {
        power.render(status, active, status != "stopping" && (active || hasProfiles))
        power.alpha = if (active || hasProfiles) 1f else 0.4f
        homeEmpty.isEnabled = !active
        profilePicker.setConnectionActive(active)
    }

    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun text(label: Int, size: Float, color: Int) = TextView(activity).apply { if (label != 0) setText(label); textSize = size; setTextColor(color) }
    private fun scroll(content: LinearLayout): ScrollView = ScrollView(activity).apply {
        isFillViewport = true
        content.setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(20))
        addView(content)
    }
}

internal fun menuRow(activity: Activity, ui: ProfileUi, label: Int, icon: Int, detail: String = "", title: CharSequence = activity.getString(label), onClick: () -> Unit): View {
    val row = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
        minimumHeight = ui.dp(64)
        background = RippleDrawable(ColorStateList.valueOf(ui.selected), ui.shape(ui.surface, true), null)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }
    row.addView(ImageView(activity).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(ui.muted) }, LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)))
    val names = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    names.addView(TextView(activity).apply { text = title; textSize = 15f; setTextColor(ui.ink) })
    if (detail.isNotEmpty()) names.addView(TextView(activity).apply { text = detail; textSize = 12f; setTextColor(ui.muted); setPadding(0, ui.dp(4), 0, 0) })
    row.addView(names, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(14) })
    row.addView(ImageView(activity).apply { setImageResource(R.drawable.ic_chevron_right); imageTintList = ColorStateList.valueOf(ui.muted) }, LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)))
    return row
}

package com.vkturn.android

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView

internal class ProfileUi(private val context: Context) {
    val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val background = Color.parseColor(if (dark) "#111827" else "#F5F7FC")
    val surface = Color.parseColor(if (dark) "#192335" else "#FFFFFF")
    val ink = Color.parseColor(if (dark) "#F0F4FF" else "#17233C")
    val muted = Color.parseColor(if (dark) "#ADB9D0" else "#5D6D87")
    val border = Color.parseColor(if (dark) "#344258" else "#DCE3EF")
    val accent = Color.parseColor(if (dark) "#93B1FF" else "#356CE7")
    val selected = Color.parseColor(if (dark) "#2B406B" else "#E8EFFF")

    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun shape(color: Int, outline: Boolean = false): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(12).toFloat()
        if (outline) setStroke(dp(1), border)
    }

    fun button(label: Int, primary: Boolean = false, action: () -> Unit): Button = Button(context).apply {
        setText(label)
        isAllCaps = false
        stateListAnimator = null
        elevation = 0f
        translationZ = 0f
        textSize = 14f
        minHeight = dp(48)
        minimumHeight = dp(48)
        gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(12), 0)
        val foreground = if (primary) Color.parseColor("#FFFFFF") else ink
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()), intArrayOf(foreground, muted)))
        background = RippleDrawable(ColorStateList.valueOf(selected), shape(if (primary) Color.parseColor("#356CE7") else surface, !primary), null)
        setOnClickListener { action() }
    }

    fun label(label: Int): TextView = TextView(context).apply {
        setText(label)
        textSize = 13f
        setTextColor(muted)
        setPadding(0, dp(14), 0, dp(6))
    }

    fun styleSpinner(spinner: Spinner) {
        val chevron = context.getDrawable(R.drawable.ic_chevron_down)!!.mutate().apply { setTint(muted) }
        spinner.background = LayerDrawable(arrayOf(shape(surface, true), chevron)).apply {
            setLayerSize(1, dp(20), dp(20))
            setLayerGravity(1, Gravity.END or Gravity.CENTER_VERTICAL)
            setLayerInsetRelative(1, 0, 0, dp(12), 0)
        }
        spinner.setPaddingRelative(0, 0, dp(40), 0)
    }

    fun styleInput(input: EditText) {
        input.textSize = 15f
        input.setTextColor(ink)
        input.setHintTextColor(muted)
        input.background = shape(surface, true)
        input.setPadding(dp(12), dp(10), dp(12), dp(10))
    }
}

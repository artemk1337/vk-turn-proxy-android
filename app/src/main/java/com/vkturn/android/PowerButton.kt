package com.vkturn.android

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import android.widget.Button
import kotlin.math.min

internal class PowerButton(context: Context) : View(context) {
    private val ui = ProfileUi(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private var connected = false
    private var waiting = false
    private var fill = 0f
    private var angle = -90f
    private var transition: ValueAnimator? = null
    private var rotation: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.connect)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun render(status: String, active: Boolean, enabled: Boolean) {
        isEnabled = enabled
        contentDescription = context.getString(if (active) R.string.disconnect else R.string.connect)
        waiting = status in setOf("connecting", "starting", "credentials", "relay", "handshake",
            "reconnecting", "waiting_quota", "waiting_captcha", "stopping", "disconnecting")
        val nextConnected = status == "connected" || status == "running"
        if (connected != nextConnected) {
            connected = nextConnected
            transition?.cancel()
            val target = if (connected) 1f else 0f
            if (canAnimate()) {
                transition = ValueAnimator.ofFloat(fill, target).apply {
                    duration = 400
                    addUpdateListener { fill = it.animatedValue as Float; invalidate() }
                    start()
                }
            } else fill = target
        }
        updateRotation()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = ui.dp(208)
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = (min(width, height) / 2f - ui.dp(14)).coerceAtLeast(0f)
        val pressed = isPressed || isFocused
        paint.style = Paint.Style.FILL
        paint.color = translucent(ui.accent, if (pressed) 36 else (fill * 22).toInt())
        canvas.drawCircle(centerX, centerY, radius + ui.dp(7), paint)
        paint.color = blend(ui.surface, ui.accent, fill)
        canvas.drawCircle(centerX, centerY, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ui.dp(2).toFloat()
        paint.color = ui.accent
        canvas.drawCircle(centerX, centerY, radius, paint)
        if (waiting) {
            val ring = radius + ui.dp(10)
            bounds.set(centerX - ring, centerY - ring, centerX + ring, centerY + ring)
            paint.strokeWidth = ui.dp(3).toFloat()
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawArc(bounds, angle, 64f, false, paint)
        }
        paint.color = blend(ui.accent, ui.background, fill)
        paint.strokeWidth = ui.dp(3).toFloat()
        paint.strokeCap = Paint.Cap.ROUND
        val iconRadius = ui.dp(24).toFloat()
        bounds.set(centerX - iconRadius, centerY - iconRadius, centerX + iconRadius, centerY + iconRadius)
        canvas.drawArc(bounds, -50f, 280f, false, paint)
        canvas.drawLine(centerX, centerY - ui.dp(31), centerX, centerY - ui.dp(3), paint)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Button::class.java.name
        info.isClickable = isClickable
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateRotation()
    }

    override fun onDetachedFromWindow() {
        rotation?.cancel()
        rotation = null
        transition?.cancel()
        transition = null
        fill = if (connected) 1f else 0f
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateRotation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateRotation()
    }

    private fun canAnimate(): Boolean = isAttachedToWindow && isShown && windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled()

    private fun updateRotation() {
        if (waiting && canAnimate()) {
            if (rotation == null) {
                rotation = ValueAnimator.ofFloat(-90f, 270f).apply {
                    duration = 1600
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { angle = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        } else {
            rotation?.cancel()
            rotation = null
        }
        if (!canAnimate()) {
            transition?.cancel()
            transition = null
            fill = if (connected) 1f else 0f
        }
    }

    private fun translucent(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun blend(start: Int, end: Int, fraction: Float): Int = Color.rgb(
        (Color.red(start) + (Color.red(end) - Color.red(start)) * fraction).toInt(),
        (Color.green(start) + (Color.green(end) - Color.green(start)) * fraction).toInt(),
        (Color.blue(start) + (Color.blue(end) - Color.blue(start)) * fraction).toInt(),
    )
}

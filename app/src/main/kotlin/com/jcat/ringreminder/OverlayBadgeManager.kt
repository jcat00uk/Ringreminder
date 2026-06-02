package com.jcat.ringreminder

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import kotlin.math.abs

class OverlayBadgeManager(
    private val context: Context,
    private val onFixRequested: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: View? = null
    private var isExpanded = false
    private val prefs = PrefsHelper(context)

    private var isDragging = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val dragThreshold = 12f
    private val cornerRadius get() = 24f * context.resources.displayMetrics.density

    fun show(result: EvaluationResult) {
        if (!Settings.canDrawOverlays(context)) return

        if (rootView != null) {
            updateContent(result)
            return
        }

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_badge, null)
        rootView = view

        val savedX = prefs.badgeX
        val savedY = prefs.badgeY

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (savedX >= 0) savedX.toInt() else 50
            y = if (savedY >= 0) savedY.toInt() else 900
        }

        setupTouchAndClick(view)
        updateContent(result)

        try {
            windowManager.addView(view, layoutParams)
        } catch (_: Exception) {
            rootView = null
        }
    }

    fun hide() {
        rootView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {}
            rootView = null
        }
        isExpanded = false
    }

    private fun updateContent(result: EvaluationResult) {
        val view = rootView ?: return

        val pillText = view.findViewById<TextView>(R.id.pill_text)
        val multiDot = view.findViewById<View>(R.id.multi_dot)
        val collapsedBadge = view.findViewById<View>(R.id.collapsed_badge)
        val expandedTitle = view.findViewById<TextView>(R.id.expanded_title)
        val expandedDesc = view.findViewById<TextView>(R.id.expanded_description)

        val (text, bgColor) = when (result.primaryCondition) {
            AlertCondition.SILENT -> "Muted" to 0xFFE53935.toInt()
            AlertCondition.DND -> "Do not disturb" to 0xFF8E24AA.toInt()
            AlertCondition.VIBRATE -> "Vibrate only" to 0xFF546E7A.toInt()
            AlertCondition.LOW_VOLUME -> "Low volume" to 0xFFEF6C00.toInt()
            null -> "OK" to 0xFF43A047.toInt()
        }

        val (expTitle, expDesc) = when (result.primaryCondition) {
            AlertCondition.SILENT ->
                "Phone is muted" to "Your phone is silent. You may miss incoming calls."
            AlertCondition.DND ->
                "Do Not Disturb is on" to "Do Not Disturb is active and may block incoming calls."
            AlertCondition.VIBRATE ->
                "Vibrate only mode" to "Your phone is in vibrate mode. You may miss incoming calls."
            AlertCondition.LOW_VOLUME ->
                "Ringer volume is low" to "Your ringer volume is too low to hear incoming calls."
            null -> "" to ""
        }

        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = this@OverlayBadgeManager.cornerRadius
            setColor(bgColor)
        }
        collapsedBadge.background = drawable

        pillText.text = text
        multiDot.visibility = if (result.hasMultipleIssues) View.VISIBLE else View.GONE
        expandedTitle.text = expTitle
        expandedDesc.text = expDesc

        view.findViewById<Button>(R.id.btn_fix).setOnClickListener {
            onFixRequested()
            collapse(view)
        }
        view.findViewById<Button>(R.id.btn_dismiss).setOnClickListener {
            hide()
        }
    }

    private fun setupTouchAndClick(view: View) {
        val collapsedBadge = view.findViewById<View>(R.id.collapsed_badge)

        collapsedBadge.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!isDragging && (abs(dx) > dragThreshold || abs(dy) > dragThreshold)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = initialY + dy.toInt()
                        try {
                            windowManager.updateViewLayout(view, layoutParams)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        if (!isExpanded) expand(view)
                    } else {
                        prefs.badgeX = layoutParams.x.toFloat()
                        prefs.badgeY = layoutParams.y.toFloat()
                        isDragging = false
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun expand(view: View) {
        isExpanded = true
        view.findViewById<View>(R.id.collapsed_badge).visibility = View.GONE
        view.findViewById<View>(R.id.expanded_card).visibility = View.VISIBLE
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        try {
            windowManager.updateViewLayout(view, layoutParams)
        } catch (_: Exception) {}
    }

    private fun collapse(view: View) {
        isExpanded = false
        view.findViewById<View>(R.id.collapsed_badge).visibility = View.VISIBLE
        view.findViewById<View>(R.id.expanded_card).visibility = View.GONE
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        try {
            windowManager.updateViewLayout(view, layoutParams)
        } catch (_: Exception) {}
    }
}

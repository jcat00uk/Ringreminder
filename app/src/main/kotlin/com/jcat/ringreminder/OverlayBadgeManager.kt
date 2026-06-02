package com.jcat.ringreminder

import android.content.Context
import android.content.res.ColorStateList
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
    private val prefs = PrefsHelper(context)

    private enum class State { COLLAPSED, EXPANDED, CONFIRMING }
    private var state = State.COLLAPSED
    private var stateBeforeConfirm = State.COLLAPSED

    private var isDragging = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val dragThreshold = 12f
    private val pillRadius get() = 24f * context.resources.displayMetrics.density

    fun show(result: EvaluationResult) {
        if (!Settings.canDrawOverlays(context)) return

        if (rootView != null) {
            updateContent(result)
            return
        }

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_badge, null)
        rootView = view

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val sx = prefs.badgeX
            val sy = prefs.badgeY
            x = if (sx >= 0) sx.toInt() else 50
            y = if (sy >= 0) sy.toInt() else 900
        }

        setupInteractions(view)
        updateContent(result)

        try {
            windowManager.addView(view, layoutParams)
        } catch (_: Exception) {
            rootView = null
        }
    }

    fun hide() {
        rootView?.let { view ->
            try { windowManager.removeView(view) } catch (_: Exception) {}
            rootView = null
        }
        state = State.COLLAPSED
    }

    private fun updateContent(result: EvaluationResult) {
        val view = rootView ?: return

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

        // Pill background (rounded shape, colour-coded)
        view.findViewById<View>(R.id.collapsed_badge).background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = pillRadius
            setColor(bgColor)
        }
        view.findViewById<TextView>(R.id.pill_text).text = text
        view.findViewById<View>(R.id.multi_dot).visibility =
            if (result.hasMultipleIssues) View.VISIBLE else View.GONE

        // Card header colour strip
        view.findViewById<View>(R.id.card_header).setBackgroundColor(bgColor)
        view.findViewById<TextView>(R.id.expanded_title).text = expTitle
        view.findViewById<TextView>(R.id.expanded_description).text = expDesc

        // Fix button tinted to match the condition colour
        view.findViewById<Button>(R.id.btn_fix)
            .backgroundTintList = ColorStateList.valueOf(bgColor)
    }

    private fun setupInteractions(view: View) {
        val collapsedBadge = view.findViewById<View>(R.id.collapsed_badge)

        // X on collapsed pill → ask for confirmation
        view.findViewById<View>(R.id.btn_close_pill).setOnClickListener {
            showConfirmation(view)
        }

        // X on expanded card → ask for confirmation
        view.findViewById<View>(R.id.btn_close_card).setOnClickListener {
            showConfirmation(view)
        }

        // Fix button
        view.findViewById<View>(R.id.btn_fix).setOnClickListener {
            onFixRequested()
            transitionTo(view, State.COLLAPSED)
        }

        // Confirmation: cancel → always collapse back to pill (not re-open the card)
        view.findViewById<View>(R.id.btn_cancel_dismiss).setOnClickListener {
            transitionTo(view, State.COLLAPSED)
        }

        // Confirmation: hide → dismiss entirely until next ringer change
        view.findViewById<View>(R.id.btn_confirm_dismiss).setOnClickListener {
            hide()
        }

        // Drag / tap-to-expand on the pill.
        // btn_close_pill is a child of collapsed_badge and is clickable, so it consumes
        // its own touch events before the parent's onTouchListener ever sees them —
        // no manual hit-testing needed here.
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
                        try { windowManager.updateViewLayout(view, layoutParams) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        transitionTo(view, State.EXPANDED)
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

    private fun showConfirmation(view: View) {
        stateBeforeConfirm = state
        transitionTo(view, State.CONFIRMING)
    }

    private fun transitionTo(view: View, newState: State) {
        state = newState
        view.findViewById<View>(R.id.collapsed_badge).visibility =
            if (newState == State.COLLAPSED) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.expanded_card).visibility =
            if (newState == State.EXPANDED) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.confirmation_card).visibility =
            if (newState == State.CONFIRMING) View.VISIBLE else View.GONE

        layoutParams.flags = when (newState) {
            State.COLLAPSED -> WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            State.EXPANDED, State.CONFIRMING ->
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
        try { windowManager.updateViewLayout(view, layoutParams) } catch (_: Exception) {}
    }
}

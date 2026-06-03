package com.jcat.ringreminder

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.chip.Chip
import kotlin.math.abs

class OverlayBadgeManager(
    private val context: Context,
    private val onFixRequested: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: View? = null
    private val prefs = PrefsHelper(context)

    private enum class State { COLLAPSED, EXPANDED, CONFIRMING, DOCKED }
    private var state = State.COLLAPSED

    private var isDragging = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var preDockX = 50
    private var preDockY = 900
    private var dockedOnRight = false
    private var cameFromDocked = false
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val dragThreshold = 12f
    private val pillRadius get() = 24f * context.resources.displayMetrics.density

    // Tracks current result so snooze chips can reference it
    private var currentResult: EvaluationResult? = null

    fun show(result: EvaluationResult) {
        if (!Settings.canDrawOverlays(context)) {
            if (rootView != null) hide()
            return
        }

        if (rootView != null) {
            updateContent(result)
            return
        }

        // ContextThemeWrapper ensures Material components inflate correctly from a Service context
        val themedCtx = ContextThemeWrapper(context, R.style.Theme_RingReminder)
        val view = LayoutInflater.from(themedCtx).inflate(R.layout.overlay_badge, null)
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
        cameFromDocked = false
        rootView?.let { view ->
            try { windowManager.removeView(view) } catch (_: Exception) {}
            rootView = null
        }
        state = State.COLLAPSED
    }

    private fun updateContent(result: EvaluationResult) {
        val view = rootView ?: return
        currentResult = result
        val bgColor = conditionColor(result.primaryCondition, prefs.overlayTheme)

        val (pillText, expTitle, expDesc) = when (result.primaryCondition) {
            AlertCondition.SILENT ->
                Triple("Muted", "Phone is muted", "Your phone is silent. You may miss incoming calls.")
            AlertCondition.DND ->
                Triple("Do not disturb", "Do Not Disturb is on", "Do Not Disturb is active and may block incoming calls.")
            AlertCondition.VIBRATE ->
                Triple("Vibrate only", "Vibrate only mode", "Your phone is in vibrate mode. You may miss incoming calls.")
            AlertCondition.LOW_VOLUME ->
                Triple("Low volume", "Ringer volume is low", "Your ringer volume is too low to hear incoming calls.")
            null -> Triple("OK", "", "")
        }

        val fixLabel = when (result.primaryCondition) {
            AlertCondition.SILENT -> context.getString(R.string.action_unmute)
            AlertCondition.DND -> context.getString(R.string.action_dismiss_dnd)
            AlertCondition.VIBRATE -> context.getString(R.string.action_enable_ringer)
            AlertCondition.LOW_VOLUME -> context.getString(R.string.action_raise_volume)
            null -> context.getString(R.string.action_fix_now)
        }

        view.findViewById<View>(R.id.collapsed_badge).background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = pillRadius
            setColor(bgColor)
        }
        view.findViewById<TextView>(R.id.pill_text).text = pillText
        view.findViewById<View>(R.id.multi_dot).visibility =
            if (result.hasMultipleIssues) View.VISIBLE else View.GONE

        view.findViewById<View>(R.id.card_header).setBackgroundColor(bgColor)
        view.findViewById<TextView>(R.id.expanded_title).text = expTitle
        view.findViewById<TextView>(R.id.expanded_description).text = expDesc

        view.findViewById<Button>(R.id.btn_fix).apply {
            text = fixLabel
            backgroundTintList = ColorStateList.valueOf(bgColor)
        }

        view.findViewById<ImageView>(R.id.docked_nub).background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bgColor)
        }

        // Feature 5: secondary conditions chip group
        val chipGroup = view.findViewById<ViewGroup>(R.id.chip_group_secondary_conditions)
        if (prefs.showAllConditions && result.hasMultipleIssues) {
            chipGroup.removeAllViews()
            val lightBg = (bgColor and 0x00FFFFFF) or 0x33000000.toInt()
            val themedCtx = ContextThemeWrapper(context, R.style.Theme_RingReminder)
            result.activeConditions.filter { it != result.primaryCondition }.forEach { condition ->
                val label = when (condition) {
                    AlertCondition.SILENT -> "Muted"
                    AlertCondition.DND -> "DND on"
                    AlertCondition.VIBRATE -> "Vibrate"
                    AlertCondition.LOW_VOLUME -> "Low volume"
                }
                chipGroup.addView(Chip(themedCtx).apply {
                    text = label
                    isCheckable = false
                    chipBackgroundColor = ColorStateList.valueOf(lightBg)
                })
            }
            chipGroup.visibility = View.VISIBLE
        } else {
            chipGroup.visibility = View.GONE
        }
    }

    private fun conditionColor(condition: AlertCondition?, theme: String): Int = when (theme) {
        "dark" -> when (condition) {
            AlertCondition.SILENT -> 0xFF880E4F.toInt()
            AlertCondition.DND -> 0xFF4A148C.toInt()
            AlertCondition.VIBRATE -> 0xFF263238.toInt()
            AlertCondition.LOW_VOLUME -> 0xFFBF360C.toInt()
            null -> 0xFF1B5E20.toInt()
        }
        "mono" -> if (condition == null) 0xFF9E9E9E.toInt() else 0xFF424242.toInt()
        "vibrant" -> when (condition) {
            AlertCondition.SILENT -> 0xFFFF1744.toInt()
            AlertCondition.DND -> 0xFFD500F9.toInt()
            AlertCondition.VIBRATE -> 0xFF00B0FF.toInt()
            AlertCondition.LOW_VOLUME -> 0xFFFF6D00.toInt()
            null -> 0xFF00E676.toInt()
        }
        "pastel" -> when (condition) {
            AlertCondition.SILENT -> 0xFFEF9A9A.toInt()
            AlertCondition.DND -> 0xFFCE93D8.toInt()
            AlertCondition.VIBRATE -> 0xFFB0BEC5.toInt()
            AlertCondition.LOW_VOLUME -> 0xFFFFCC80.toInt()
            null -> 0xFFA5D6A7.toInt()
        }
        "amoled" -> when (condition) {
            AlertCondition.SILENT -> 0xFF3B0000.toInt()
            AlertCondition.DND -> 0xFF1A0030.toInt()
            AlertCondition.VIBRATE -> 0xFF0D1317.toInt()
            AlertCondition.LOW_VOLUME -> 0xFF2B1000.toInt()
            null -> 0xFF001200.toInt()
        }
        "warm" -> when (condition) {
            AlertCondition.SILENT -> 0xFFBF360C.toInt()
            AlertCondition.DND -> 0xFFE65100.toInt()
            AlertCondition.VIBRATE -> 0xFFF57C00.toInt()
            AlertCondition.LOW_VOLUME -> 0xFFFFA000.toInt()
            null -> 0xFF558B2F.toInt()
        }
        "cool" -> when (condition) {
            AlertCondition.SILENT -> 0xFF004D40.toInt()
            AlertCondition.DND -> 0xFF0D47A1.toInt()
            AlertCondition.VIBRATE -> 0xFF01579B.toInt()
            AlertCondition.LOW_VOLUME -> 0xFF006064.toInt()
            null -> 0xFF1B5E20.toInt()
        }
        else -> when (condition) {
            AlertCondition.SILENT -> 0xFFE53935.toInt()
            AlertCondition.DND -> 0xFF8E24AA.toInt()
            AlertCondition.VIBRATE -> 0xFF546E7A.toInt()
            AlertCondition.LOW_VOLUME -> 0xFFEF6C00.toInt()
            null -> 0xFF43A047.toInt()
        }
    }

    private fun notifyService() {
        context.startService(
            Intent(context, RingerMonitorService::class.java).apply {
                action = RingerMonitorService.ACTION_REFRESH
            }
        )
    }

    private fun setupInteractions(view: View) {
        val collapsedBadge = view.findViewById<View>(R.id.collapsed_badge)

        view.findViewById<View>(R.id.btn_close_pill).setOnClickListener {
            transitionTo(view, State.CONFIRMING)
        }
        view.findViewById<View>(R.id.btn_close_card).setOnClickListener {
            transitionTo(view, State.CONFIRMING)
        }
        view.findViewById<View>(R.id.btn_fix).setOnClickListener {
            cameFromDocked = false
            onFixRequested()
            transitionTo(view, State.COLLAPSED)
        }
        view.findViewById<View>(R.id.btn_cancel_dismiss).setOnClickListener {
            if (cameFromDocked) {
                cameFromDocked = false
                snapToEdge(view, snapRight = dockedOnRight)
            } else {
                transitionTo(view, State.COLLAPSED)
            }
        }

        // Feature 3: snooze chip listeners
        view.findViewById<View>(R.id.chip_snooze_30min).setOnClickListener {
            prefs.snoozeUntilMs = System.currentTimeMillis() + 30 * 60_000L
            hide()
            notifyService()
        }
        view.findViewById<View>(R.id.chip_snooze_1hour).setOnClickListener {
            prefs.snoozeUntilMs = System.currentTimeMillis() + 60 * 60_000L
            hide()
            notifyService()
        }
        view.findViewById<View>(R.id.chip_snooze_2hours).setOnClickListener {
            prefs.snoozeUntilMs = System.currentTimeMillis() + 2 * 60 * 60_000L
            hide()
            notifyService()
        }
        view.findViewById<View>(R.id.chip_snooze_until_changed).setOnClickListener {
            prefs.snoozeUntilMs = Long.MAX_VALUE
            prefs.snoozedCondition = currentResult?.primaryCondition?.name ?: ""
            hide()
            notifyService()
        }

        // Gear icon: collapse overlay then open Settings
        view.findViewById<View>(R.id.btn_settings_card).setOnClickListener {
            transitionTo(view, State.COLLAPSED)
            context.startActivity(
                Intent(context, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

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
                        val screenWidth = context.resources.displayMetrics.widthPixels
                        if (event.rawX < screenWidth * 0.25f || event.rawX > screenWidth * 0.75f) {
                            snapToEdge(view, snapRight = event.rawX > screenWidth / 2f)
                        } else {
                            prefs.badgeX = layoutParams.x.toFloat()
                            prefs.badgeY = layoutParams.y.toFloat()
                        }
                        isDragging = false
                    }
                    true
                }
                else -> false
            }
        }

        view.findViewById<View>(R.id.docked_nub).setOnTouchListener { _, event ->
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
                    if (!isDragging && (abs(dx) > dragThreshold || abs(dy) > dragThreshold)) isDragging = true
                    if (isDragging) {
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = initialY + dy.toInt()
                        try { windowManager.updateViewLayout(view, layoutParams) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        layoutParams.x = preDockX
                        layoutParams.y = preDockY
                        try { windowManager.updateViewLayout(view, layoutParams) } catch (_: Exception) {}
                        cameFromDocked = true
                        transitionTo(view, State.EXPANDED)
                    } else {
                        val dx = event.rawX - initialTouchX
                        val screenWidth = context.resources.displayMetrics.widthPixels
                        when {
                            abs(dx) <= dragThreshold -> {
                                preDockY = layoutParams.y
                            }
                            event.rawX < screenWidth * 0.25f || event.rawX > screenWidth * 0.75f -> {
                                snapToEdge(view, snapRight = event.rawX > screenWidth / 2f)
                            }
                            else -> {
                                prefs.badgeX = layoutParams.x.toFloat()
                                prefs.badgeY = layoutParams.y.toFloat()
                                transitionTo(view, State.COLLAPSED)
                            }
                        }
                        isDragging = false
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge(view: View, snapRight: Boolean) {
        preDockX = layoutParams.x
        preDockY = layoutParams.y
        dockedOnRight = snapRight
        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels
        val nubSizePx = (44 * density).toInt()
        val peekPx = (15 * density).toInt()
        layoutParams.x = if (snapRight) screenWidth - peekPx else -(nubSizePx - peekPx)
        try { windowManager.updateViewLayout(view, layoutParams) } catch (_: Exception) {}
        transitionTo(view, State.DOCKED)
    }

    private fun transitionTo(view: View, newState: State) {
        state = newState

        view.alpha = if (newState == State.DOCKED) 0.55f else 1.0f

        view.findViewById<View>(R.id.collapsed_badge).visibility =
            if (newState == State.COLLAPSED) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.expanded_card).visibility =
            if (newState == State.EXPANDED) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.confirmation_card).visibility =
            if (newState == State.CONFIRMING) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.docked_nub).visibility =
            if (newState == State.DOCKED) View.VISIBLE else View.GONE

        layoutParams.flags = when (newState) {
            State.COLLAPSED, State.DOCKED -> WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            State.EXPANDED, State.CONFIRMING ->
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
        try { windowManager.updateViewLayout(view, layoutParams) } catch (_: Exception) {}
    }
}

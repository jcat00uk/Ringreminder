package com.jcat.ringreminder

import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
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
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.google.android.material.chip.Chip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class OverlayBadgeManager(
    private val context: Context,
    private val onFixRequested: () -> Unit,
    private val onAutoFixScheduled: (durationMs: Long) -> Unit,
    private val onUserDismissed: () -> Unit = {}
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: View? = null
    private val prefs = PrefsHelper(context)

    private enum class State { COLLAPSED, EXPANDED, DOCKED }
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

    private var currentResult: EvaluationResult? = null

    // Continuous pulse animators (for incoming calls)
    private var pulseXAnim: ObjectAnimator? = null
    private var pulseYAnim: ObjectAnimator? = null

    private val snoozeLabels = arrayOf("15 minutes", "30 minutes", "1 hour", "2 hours", "Custom…", "Until unmuted")
    private val snoozeDurationsMs = longArrayOf(15 * 60_000L, 30 * 60_000L, 60 * 60_000L, 2 * 60 * 60_000L, -1L, Long.MAX_VALUE)
    private var selectedSnoozeIndex = 1

    private val autoFixLabels = arrayOf("15 minutes", "30 minutes", "1 hour", "2 hours", "Custom…")
    private val autoFixDurationsMs = longArrayOf(15 * 60_000L, 30 * 60_000L, 60 * 60_000L, 2 * 60 * 60_000L, -1L)
    private var selectedAutoFixIndex = 2

    fun show(result: EvaluationResult) {
        if (!Settings.canDrawOverlays(context)) {
            if (rootView != null) hide()
            return
        }

        if (rootView != null) {
            updateContent(result)
            return
        }

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
            return
        }

        if (prefs.badgeDocked) {
            layoutParams.y = prefs.badgeDockedY.toInt()
            try { windowManager.updateViewLayout(view, layoutParams) } catch (_: Exception) {}
            snapToEdge(view, prefs.badgeDockedRight)
        }
    }

    fun hide() {
        stopPulse()
        cameFromDocked = false
        rootView?.let { view ->
            try { windowManager.removeView(view) } catch (_: Exception) {}
            rootView = null
        }
        state = State.COLLAPSED
    }

    // ── Continuous pulse for incoming calls ──────────────────────────────────

    fun startContinuousPulse() {
        val target = pulseTarget() ?: return
        stopPulse()
        // Alpha pulse works within the window boundary — scale clips on TYPE_APPLICATION_OVERLAY
        val baseAlpha = if (state == State.DOCKED) 0.55f else 1.0f
        pulseXAnim = ObjectAnimator.ofFloat(target, "alpha", baseAlpha, 1.0f, baseAlpha).apply {
            duration = 700L
            repeatCount = ObjectAnimator.INFINITE
        }
        pulseXAnim?.start()
    }

    fun stopPulse() {
        pulseXAnim?.cancel()
        pulseYAnim?.cancel()
        pulseXAnim = null
        pulseYAnim = null
        // Restore correct alpha for each state
        rootView?.findViewById<View>(R.id.collapsed_badge)?.alpha = 1f
        rootView?.findViewById<View>(R.id.docked_nub)?.alpha = if (state == State.DOCKED) 0.55f else 1f
    }

    private fun pulseTarget(): View? = when (state) {
        State.COLLAPSED -> rootView?.findViewById(R.id.collapsed_badge)
        State.DOCKED -> rootView?.findViewById(R.id.docked_nub)
        State.EXPANDED -> null
    }

    // ── Content update ───────────────────────────────────────────────────────

    private fun updateContent(result: EvaluationResult) {
        val view = rootView ?: return
        currentResult = result
        val bgColor = conditionColor(result.primaryCondition, prefs.overlayTheme)

        val (pillText, expTitle, expDesc) = when (result.primaryCondition) {
            AlertCondition.SILENT ->
                Triple("Muted", "Phone is muted", "Your phone is silent. You may miss incoming calls.")
            AlertCondition.DND ->
                Triple("Do Not Disturb", "Do Not Disturb is on", "Do Not Disturb is active and may block incoming calls.")
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

        // Pill: show countdown if one-shot auto-fix is pending, otherwise condition name
        val scheduledMs = prefs.autoFixScheduledMs
        val pillDisplay = if (scheduledMs > 0) {
            val remainingMs = scheduledMs - System.currentTimeMillis()
            if (remainingMs > 0) {
                val mins = (remainingMs / 60_000).toInt() + 1
                context.getString(R.string.overlay_unmute_in, "${mins}m")
            } else pillText
        } else pillText

        view.findViewById<View>(R.id.collapsed_badge).background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = pillRadius
            setColor(bgColor)
        }
        view.findViewById<TextView>(R.id.pill_text).text = pillDisplay
        view.findViewById<View>(R.id.multi_dot).visibility =
            if (result.hasMultipleIssues) View.VISIBLE else View.GONE

        view.findViewById<View>(R.id.card_header).setBackgroundColor(bgColor)
        applyCardTheme(view)
        view.findViewById<TextView>(R.id.expanded_title).text = expTitle
        view.findViewById<TextView>(R.id.expanded_description).text = expDesc

        view.findViewById<android.widget.Button>(R.id.btn_fix).apply {
            text = fixLabel
            backgroundTintList = ColorStateList.valueOf(bgColor)
        }

        val dockedNub = view.findViewById<ImageView>(R.id.docked_nub)
        dockedNub.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bgColor)
        }
        dockedNub.setImageResource(
            if (result.isAlertActive) R.drawable.ic_notification_muted else R.drawable.ic_notification
        )

        // Scheduled auto-fix row
        val scheduledRow = view.findViewById<View>(R.id.row_auto_fix_scheduled)
        if (scheduledMs > 0 && System.currentTimeMillis() < scheduledMs) {
            val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(scheduledMs))
            view.findViewById<TextView>(R.id.txt_auto_fix_scheduled).text =
                context.getString(R.string.overlay_auto_fix_scheduled, timeStr)
            scheduledRow.visibility = View.VISIBLE
        } else {
            scheduledRow.visibility = View.GONE
        }

        // Daily unmute row
        val dailyRow = view.findViewById<View>(R.id.row_daily_unmute)
        if (prefs.isPro && prefs.autoFixEnabled && prefs.autoFixRecurringHour >= 0) {
            val timeStr = String.format("%02d:%02d", prefs.autoFixRecurringHour, prefs.autoFixRecurringMinute)
            view.findViewById<TextView>(R.id.txt_daily_unmute).text =
                context.getString(R.string.overlay_daily_unmute, timeStr)
            dailyRow.visibility = View.VISIBLE
        } else {
            dailyRow.visibility = View.GONE
        }

        // Auto-fix quick-select row
        view.findViewById<View>(R.id.row_auto_fix).visibility =
            if (prefs.isPro && prefs.autoFixOverlayEnabled) View.VISIBLE else View.GONE

        // Secondary condition chips
        val chipGroup = view.findViewById<ViewGroup>(R.id.chip_group_secondary_conditions)
        if (prefs.showAllConditions && result.hasMultipleIssues) {
            chipGroup.removeAllViews()
            val lightBg = (bgColor and 0x00FFFFFF) or 0x33000000.toInt()
            val themedCtx = ContextThemeWrapper(context, R.style.Theme_RingReminder)
            result.activeConditions.filter { it != result.primaryCondition }.forEach { condition ->
                val label = when (condition) {
                    AlertCondition.SILENT -> "Muted"
                    AlertCondition.DND -> "Do Not Disturb"
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

    fun expandCard() {
        val view = rootView ?: return
        if (state != State.EXPANDED) transitionTo(view, State.EXPANDED)
    }

    private fun isCardDark(): Boolean = when (prefs.overlayPanelMode) {
        "dark"  -> true
        "light" -> false
        else    -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
    }

    private fun applyCardTheme(view: View) {
        val dark = isCardDark()
        val cardBg      = if (dark) 0xFF1C1C1C.toInt() else Color.WHITE
        val bodyText    = if (dark) 0xFFCCCCCC.toInt() else 0xFF555555.toInt()
        val secondaryText = if (dark) 0xFF999999.toInt() else 0xFF777777.toInt()

        view.findViewById<CardView>(R.id.expanded_card).setCardBackgroundColor(cardBg)
        view.findViewById<TextView>(R.id.expanded_description).setTextColor(bodyText)
        view.findViewById<TextView>(R.id.txt_snooze_label).setTextColor(bodyText)
        view.findViewById<TextView>(R.id.txt_auto_fix_label).setTextColor(bodyText)
        view.findViewById<TextView>(R.id.txt_auto_fix_scheduled).setTextColor(secondaryText)
        view.findViewById<TextView>(R.id.txt_daily_unmute).setTextColor(secondaryText)
    }

    fun conditionColor(condition: AlertCondition?, theme: String): Int = when (theme) {
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

    // ── Dialogs ──────────────────────────────────────────────────────────────

    private fun showSnoozeDialog(view: View) {
        val dialog = AlertDialog.Builder(ContextThemeWrapper(context, R.style.Theme_RingReminder))
            .setTitle(context.getString(R.string.snooze_alerts_for))
            .setSingleChoiceItems(snoozeLabels, selectedSnoozeIndex) { d, which ->
                d.dismiss()
                if (snoozeDurationsMs[which] == -1L) {
                    showCustomDurationDialog(isAutoFix = false, view); return@setSingleChoiceItems
                }
                selectedSnoozeIndex = which
                view.findViewById<TextView>(R.id.txt_snooze_value).text = snoozeLabels[which]
                applySnooze(snoozeDurationsMs[which])
                transitionTo(view, State.COLLAPSED)
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    private fun showAutoFixDialog(view: View) {
        val dialog = AlertDialog.Builder(ContextThemeWrapper(context, R.style.Theme_RingReminder))
            .setTitle(context.getString(R.string.auto_fix_in))
            .setSingleChoiceItems(autoFixLabels, selectedAutoFixIndex) { d, which ->
                d.dismiss()
                if (autoFixDurationsMs[which] == -1L) {
                    showCustomDurationDialog(isAutoFix = true, view); return@setSingleChoiceItems
                }
                selectedAutoFixIndex = which
                view.findViewById<TextView>(R.id.txt_auto_fix_value).text = autoFixLabels[which]
                onAutoFixScheduled(autoFixDurationsMs[which])
                transitionTo(view, State.COLLAPSED)
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    private fun showCustomDurationDialog(isAutoFix: Boolean, view: View) {
        val input = EditText(ContextThemeWrapper(context, R.style.Theme_RingReminder)).apply {
            hint = context.getString(R.string.snooze_custom_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val title = if (isAutoFix) context.getString(R.string.autofix_custom_title)
                    else context.getString(R.string.snooze_custom_title)
        val dialog = AlertDialog.Builder(ContextThemeWrapper(context, R.style.Theme_RingReminder))
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val minutes = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                if (minutes <= 0) return@setPositiveButton
                val durationMs = minutes * 60_000L
                if (isAutoFix) {
                    view.findViewById<TextView>(R.id.txt_auto_fix_value).text = "$minutes min"
                    onAutoFixScheduled(durationMs)
                } else {
                    view.findViewById<TextView>(R.id.txt_snooze_value).text = "$minutes min"
                    applySnooze(durationMs)
                }
                transitionTo(view, State.COLLAPSED)
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    private fun applySnooze(durationMs: Long) {
        if (durationMs == Long.MAX_VALUE) {
            prefs.snoozeUntilMs = Long.MAX_VALUE
            prefs.snoozedCondition = currentResult?.primaryCondition?.name ?: ""
        } else {
            prefs.snoozeUntilMs = System.currentTimeMillis() + durationMs
        }
        onUserDismissed()
        hide()
        notifyService()
    }

    // ── Interactions ─────────────────────────────────────────────────────────

    private fun setupInteractions(view: View) {
        val collapsedBadge = view.findViewById<View>(R.id.collapsed_badge)

        view.findViewById<View>(R.id.btn_close_card).setOnClickListener {
            if (cameFromDocked) {
                cameFromDocked = false
                snapToEdge(view, snapRight = dockedOnRight)
            } else {
                onUserDismissed()
                if (rootView != null) transitionTo(view, State.COLLAPSED)
            }
        }

        view.findViewById<View>(R.id.btn_fix).setOnClickListener {
            cameFromDocked = false
            onFixRequested()
            transitionTo(view, State.COLLAPSED)
        }

        view.findViewById<View>(R.id.row_snooze).setOnClickListener { showSnoozeDialog(view) }
        view.findViewById<View>(R.id.row_auto_fix).setOnClickListener { showAutoFixDialog(view) }

        // Cancel one-shot auto-fix from overlay
        view.findViewById<View>(R.id.btn_cancel_auto_fix).setOnClickListener {
            context.startService(
                Intent(context, RingerMonitorService::class.java).apply {
                    action = NotificationHelper.ACTION_CANCEL_AUTO_FIX
                }
            )
            prefs.autoFixScheduledMs = 0L
            currentResult?.let { updateContent(it) }
        }

        // Change daily unmute time — open Settings and scroll to auto-fix section
        view.findViewById<View>(R.id.btn_daily_unmute_change).setOnClickListener {
            transitionTo(view, State.COLLAPSED)
            context.startActivity(
                Intent(context, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(SettingsActivity.EXTRA_SCROLL_TO, SettingsActivity.SCROLL_AUTO_FIX)
            )
        }

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
                    initialX = layoutParams.x; initialY = layoutParams.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
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
                    initialX = layoutParams.x; initialY = layoutParams.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
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
                        layoutParams.x = preDockX; layoutParams.y = preDockY
                        try { windowManager.updateViewLayout(view, layoutParams) } catch (_: Exception) {}
                        cameFromDocked = true
                        transitionTo(view, State.EXPANDED)
                    } else {
                        val dx = event.rawX - initialTouchX
                        val screenWidth = context.resources.displayMetrics.widthPixels
                        when {
                            abs(dx) <= dragThreshold -> {
                                preDockY = layoutParams.y
                                prefs.badgeDockedY = layoutParams.y.toFloat()
                            }
                            event.rawX < screenWidth * 0.25f || event.rawX > screenWidth * 0.75f ->
                                snapToEdge(view, snapRight = event.rawX > screenWidth / 2f)
                            else -> {
                                prefs.badgeDocked = false
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
        preDockX = layoutParams.x; preDockY = layoutParams.y
        dockedOnRight = snapRight
        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels
        val nubSizePx = (44 * density).toInt()
        val peekPx = (15 * density).toInt()
        layoutParams.x = if (snapRight) screenWidth - peekPx else -(nubSizePx - peekPx)
        try { windowManager.updateViewLayout(view, layoutParams) } catch (_: Exception) {}
        prefs.badgeDocked = true
        prefs.badgeDockedRight = snapRight
        prefs.badgeDockedY = layoutParams.y.toFloat()
        transitionTo(view, State.DOCKED)
    }

    private fun transitionTo(view: View, newState: State) {
        if (newState == State.COLLAPSED && prefs.badgeDocked) {
            layoutParams.y = prefs.badgeDockedY.toInt()
            try { windowManager.updateViewLayout(view, layoutParams) } catch (_: Exception) {}
            snapToEdge(view, prefs.badgeDockedRight)
            return
        }
        state = newState
        view.alpha = if (newState == State.DOCKED) 0.55f else 1.0f
        view.findViewById<View>(R.id.collapsed_badge).visibility =
            if (newState == State.COLLAPSED) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.expanded_card).visibility =
            if (newState == State.EXPANDED) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.docked_nub).visibility =
            if (newState == State.DOCKED) View.VISIBLE else View.GONE
        layoutParams.flags = when (newState) {
            State.COLLAPSED, State.DOCKED -> WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            State.EXPANDED ->
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
        try { windowManager.updateViewLayout(view, layoutParams) } catch (_: Exception) {}
    }
}

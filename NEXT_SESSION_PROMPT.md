# Ring Reminder — Next Session Prompt

## Project

Native Android Kotlin app (`com.jcat.ringreminder`, minSdk 26, targetSdk 35). AndroidX + Material Components + Play Billing 7.0. ViewBinding + BuildConfig enabled. Gradle 8.8, AGP 8.2.2, Java 21 target (system has Java 25 — daemon warning on build is harmless).

Working directory: `s:\Coding\Ringreminder`

Build command:
```
S:\Coding\Ringreminder\gradlew.bat assembleDebug
```
Or use `1build.bat` for the full menu (debug APK / release APK / release AAB with date-stamped versioning). Output files are named `ring-reminder-debug-VN.apk`, `ring-reminder-release-VN.apk`, `ring-reminder-VN.aab`. Each build clears the output folder first.

---

## Current state (after Session 5 — continued)

### Core service
`RingerMonitorService` — foreground service, 30s polling + broadcasts. Evaluates SILENT/DND/VIBRATE/LOW_VOLUME. Owns overlay, notifications, QS tile, widget updates. Handles:
- `ACTION_REFRESH` — immediate re-evaluate (sent by Settings on every change)
- `ACTION_FIX_NOW` / `ACTION_CANCEL_AUTO_FIX` / `ACTION_AUTO_FIX`
- `ACTION_EXPAND_OVERLAY` — expands overlay card; sent by widget tap when alert active
- `ACTION_PULSE_START` / `ACTION_PULSE_STOP` (from NotificationListenerService)
- `ACTION_PAUSE` / `ACTION_RESUME`
- Per-app suppression: 2s fast-check loop via separate `suppressCheckRunnable` when `suppressAppsEnabled` is on
- Auto-fix scheduling via `AlarmManager.setExactAndAllowWhileIdle` (falls back to inexact if `SCHEDULE_EXACT_ALARM` not granted); **both `scheduleAutoFix()` and `cancelAutoFix()` call `evaluateAndUpdate()` immediately** — fixes UI delay
- Recurring daily unmute: checks within ±1 min window, tracks `autoFixLastFiredDate` to prevent double-fire
- Battery saver: polls at 120s instead of 30s when battery ≤ 20% (Pro toggle)
- Phone state listener (`PhoneStateListener`) for CALL_STATE_RINGING → `startContinuousPulse()` on overlay

### Overlay badge (`OverlayBadgeManager`)
**3 states:** COLLAPSED (pill) → EXPANDED (card) → DOCKED (edge nub).

Expanded card contains (top to bottom):
1. Coloured header (title + settings gear + X)
2. Description text
3. Secondary condition chips (if `showAllConditions`)
4. **Unmute now** button (context-aware label)
5. Scheduled unmute row — "Unmutes at 4:30pm · Cancel" when one-shot pending
6. Daily unmute row — "Auto-unmutes daily at 07:30 · Change"
7. Snooze alerts for: row → AlertDialog
8. Unmute in: row → AlertDialog (Pro, `autoFixOverlayEnabled`)

Pill text shows "Unmute in Xm" when one-shot auto-fix is pending.

When transitioning to COLLAPSED while docked: restores docked Y position and snaps to edge (fixes badge jumping on collapse).

`expandCard()` — public method; transitions to EXPANDED if not already; no-op if already EXPANDED. Called by service on `ACTION_EXPAND_OVERLAY`.

All dialogs set `window?.setType(TYPE_APPLICATION_OVERLAY)` — required for Service context.

### Notification channels
- `ring_reminder_service` (IMPORTANCE_MIN) — persistent service notification
- `ring_reminder_alerts` (IMPORTANCE_DEFAULT, no sound) — alert notification, auto-cancels
- `ring_reminder_autofix` (IMPORTANCE_LOW) — auto-unmute scheduled notif with Cancel action

### Pro features + Billing
| Feature | Key pref | Notes |
|---|---|---|
| Alert schedule | `schedule_enabled` + hours/minutes | Overnight-aware |
| Overlay theme | `overlay_theme` | 8 themes: default/dark/mono/vibrant/pastel/amoled/warm/cool — chips show DND colour |
| Panel background | `overlay_panel_mode` | light/dark/system (default system) — card body + text colours |
| Auto-unmute overlay | `auto_fix_overlay_enabled` | Shows "Unmute in" row in badge |
| Daily auto-unmute | `auto_fix_enabled` + `auto_fix_recurring_hour/minute` | ±1min window, once-per-day guard |
| Per-app suppression | `suppress_apps_enabled` + `suppressed_apps` | 2s fast-check, queryEvents |
| Lock screen alert | `lock_screen_alert` | VISIBILITY_PUBLIC on alert notif |
| Battery saver | `battery_saver_enabled` | 120s poll when battery ≤ 20% |
| Pulse on silent ring | `pulse_on_muted_alert` | Continuous alpha pulse on CATEGORY_CALL |

**`isPro` logic** (in `PrefsHelper`):
- `BuildConfig.DEBUG` → always Pro (debug builds)
- `hasPurchasedPro` → set by BillingManager on confirmed purchase
- `isInTrial` → `System.currentTimeMillis() - trialStartMs < TRIAL_DURATION_MS` (7 days; `trialStartMs` auto-created on first access)

**BillingManager** (`BillingManager.kt`):
- Google Play Billing 7.0.0 (`com.android.billingclient:billing-ktx:7.0.0`)
- Product ID: needs to be set to your Play Console product ID
- `start(onProStatus)` — connects, queries purchases, calls back with Pro status
- `refresh()` — re-queries if client already ready; called from `MainActivity.onResume()`
- `restorePurchases(onResult)` — manual query with toast feedback; "Restore" button in upgrade row
- `launchPurchaseFlow(activity, onProStatus)` — opens purchase sheet
- `destroy()` — disconnects billing client (call from `onDestroy`)
- Wired in both `MainActivity` (refresh on resume) and `SettingsActivity` (purchase flow)
- **Gap**: `onBillingServiceDisconnected()` does nothing — if connection drops, `refresh()` silently skips; needs reconnect logic

**Pro upgrade row** in Settings: visible whenever `!hasPurchasedPro`. Text shows:
- Trial active: "X days remaining in your free trial"
- Trial expired: "Your 7-day free trial has ended · Upgrade to keep Pro features"

Row contains two buttons: **Restore** (TextButton, calls `BillingManager.restorePurchases()`, toasts success/not-found) and **Upgrade to Pro** (filled). Both hidden once purchased.

**Known billing gap**: upgrade row (and Restore button) hidden when `hasPurchasedPro=true` — Pro users have no visible way to trigger a billing re-check. `onBillingServiceDisconnected()` also does nothing — if connection drops, `refresh()` silently skips. Both to be fixed.

**Debug trial shortcut**: Long-press "Ring Reminder Pro" section header in Settings (debug builds only) — toggles between trial-expired and trial-active states. Toast confirms action.

**Debug build note**: The upgrade row and trial text are still visible in debug builds (controlled by `hasPurchasedPro`, not `isPro`). This is intentional — it previews the non-Pro UI. All Pro features are fully unlocked regardless because `isPro` always returns `true` when `BuildConfig.DEBUG = true`.

### Permissions section (Settings)
Now includes all 6 permissions, each with status text and a Fix button:
1. **Show notifications** (POST_NOTIFICATIONS) — Android 13+ only; Fix opens app notification settings
2. Draw over other apps
3. Do Not Disturb access
4. Battery optimisation exempt
5. Notification access (for pulse feature)
6. App usage access (for per-app suppression)

POST_NOTIFICATIONS row is hidden on Android < 13.

### Android 15 edge-to-edge
`app/src/main/res/values-v35/` — theme override to handle edge-to-edge enforcement on Android 15+.

### Key files

| File | Role |
|---|---|
| `RingerMonitorService.kt` | Foreground service — polling, broadcasts, evaluates, snooze/nudge, auto-fix alarm, suppress fast-check, pulse, widget/tile updates |
| `RingerStateEvaluator.kt` | Pure logic — `EvaluationResult(activeConditions, primaryCondition)` |
| `OverlayBadgeManager.kt` | WindowManager overlay — 3 states, snooze/unmute dialogs, countdown, pulse |
| `NotificationHelper.kt` | All notification builders; 3 channels |
| `PrefsHelper.kt` | All SharedPreferences typed wrappers; trial logic |
| `BillingManager.kt` | Google Play Billing 7.0 — purchase flow, Pro status |
| `SettingsActivity.kt` | All settings UI; EXTRA_SCROLL_TO support; billing wired; debug trial shortcut |
| `RingerNotificationListener.kt` | NotificationListenerService — CATEGORY_CALL → pulse broadcasts |
| `AppSuppressActivity.kt` | Per-app suppression app picker |
| `QuickSettingsTile.kt` | QS tile — muted bell when alert, plain bell otherwise |
| `ReminderWidget.kt` | Widget provider (small 1×1 + large 2×1) — theme colours; tap expands overlay when alert active |
| `BootReceiver.kt` | Auto-start on boot if `masterEnabled` |
| `OnboardingActivity.kt` | Permission wizard |

---

## Constraints / gotchas

1. **Service context inflation**: Always use `ContextThemeWrapper(context, R.style.Theme_RingReminder)`. Never cast to `MaterialButton` — use plain `Button`.
2. **Dialogs from Service**: Must call `dialog.window?.setType(TYPE_APPLICATION_OVERLAY)` before `dialog.show()`.
3. **RECEIVER_NOT_EXPORTED**: Android 14+ requires export flag. Use `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`.
4. **DND / SILENT**: Evaluator only adds SILENT when `interruptionFilter == INTERRUPTION_FILTER_ALL`. DND causes `ringerMode = SILENT` as side effect — don't revert.
5. **Low volume + DND**: Low volume check skips when `ringerMode == SILENT` or `VIBRATE`.
6. **`isPro` default**: `BuildConfig.DEBUG || hasPurchasedPro || isInTrial`. Always Pro in debug.
7. **ACTION_REFRESH**: Every new setting must call `notifyService()`.
8. **Scale animation clips**: On overlay windows, use alpha animation — scale transforms clip at window bounds.
9. **`SCHEDULE_EXACT_ALARM`**: Wrap in try/catch SecurityException, fall back to `alarmManager.set()`.
10. **Daemon warning**: Harmless. Always check last line says `BUILD SUCCESSFUL`.
11. **AGP/lint workaround**: `lint { checkReleaseBuilds false }` and `android.suppressUnsupportedCompileSdk=35` in gradle.properties are temporary workarounds until AGP is upgraded.

---

## Roadmap (future sprints, do not implement yet)

### Near-term
- **Billing reconnect** — `onBillingServiceDisconnected()` currently empty; add reconnect + retry so `refresh()` doesn't silently fail
- **Billing re-check for Pro users** — upgrade row hidden when `hasPurchasedPro=true`; add "Verify purchase" option visible to Pro users
- **Widget preview image** — supply proper `previewImage` drawable for widget picker
- **AGP upgrade** — bump AGP 8.2.2 → 8.7+ and Kotlin 1.9.22 → 2.0.x; remove `suppressUnsupportedCompileSdk=35` and `lint { checkReleaseBuilds false }` once done

### Longer horizon
- **Alert profiles** — named trigger presets (Work/Gym/Sleep), switch from QS tile
- **Per-app suppression** — future: AccessibilityService for instant detection if UsageStats too slow
- **Mute history log** — in-app log of when/how long phone was muted

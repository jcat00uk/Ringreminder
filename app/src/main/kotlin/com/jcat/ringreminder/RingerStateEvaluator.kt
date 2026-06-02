package com.jcat.ringreminder

data class RingerState(
    val ringerMode: Int,
    val ringerVolume: Int,
    val maxRingerVolume: Int,
    val interruptionFilter: Int
)

enum class AlertCondition(val priority: Int) {
    SILENT(1),
    DND(2),
    VIBRATE(3),
    LOW_VOLUME(4)
}

data class EvaluationResult(
    val activeConditions: List<AlertCondition>,
    val primaryCondition: AlertCondition?
) {
    val isAlertActive: Boolean get() = activeConditions.isNotEmpty()
    val hasMultipleIssues: Boolean get() = activeConditions.size > 1
}

class RingerStateEvaluator {

    companion object {
        const val RINGER_MODE_SILENT = 0
        const val RINGER_MODE_VIBRATE = 1
        const val INTERRUPTION_FILTER_ALL = 1
    }

    fun evaluate(
        state: RingerState,
        triggerSilent: Boolean,
        triggerVibrate: Boolean,
        triggerDnd: Boolean,
        triggerLowVolume: Boolean,
        thresholdVolumePercent: Int
    ): EvaluationResult {
        val conditions = mutableListOf<AlertCondition>()

        if (triggerSilent && state.ringerMode == RINGER_MODE_SILENT) {
            conditions.add(AlertCondition.SILENT)
        }
        if (triggerDnd && state.interruptionFilter != INTERRUPTION_FILTER_ALL) {
            conditions.add(AlertCondition.DND)
        }
        if (triggerVibrate && state.ringerMode == RINGER_MODE_VIBRATE) {
            conditions.add(AlertCondition.VIBRATE)
        }
        if (triggerLowVolume && state.maxRingerVolume > 0) {
            val volumePercent = (state.ringerVolume * 100) / state.maxRingerVolume
            if (volumePercent < thresholdVolumePercent) {
                conditions.add(AlertCondition.LOW_VOLUME)
            }
        }

        val primary = conditions.minByOrNull { it.priority }
        return EvaluationResult(conditions, primary)
    }
}

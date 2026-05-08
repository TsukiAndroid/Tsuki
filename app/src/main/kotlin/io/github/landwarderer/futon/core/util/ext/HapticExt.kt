package io.github.landwarderer.futon.core.util.ext

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Fire a success or error haptic that works on all API levels this app supports
 * (minSdk 23 / Android 6).
 *
 * API 30+ uses the semantic CONFIRM / REJECT constants that map to distinct
 * waveforms on supported hardware.
 * API 23–29 falls back to VIRTUAL_KEY (short click) and LONG_PRESS (double
 * buzz), which are available on every Android device.
 */
fun View.performHapticFeedbackCompat(isSuccess: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        performHapticFeedback(
            if (isSuccess) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.REJECT,
        )
    } else {
        performHapticFeedback(
            if (isSuccess) HapticFeedbackConstants.VIRTUAL_KEY
            else HapticFeedbackConstants.LONG_PRESS,
        )
    }
}

/**
 * Subtle tick haptic for lightweight actions like page turns.
 * [HapticFeedbackConstants.CLOCK_TICK] has been available since API 21,
 * well within our minSdk 23 floor.
 */
fun View.performTickHaptic() {
    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}

/**
 * Slightly stronger haptic for milestone actions like chapter changes or
 * multi-step form progression.
 * Uses CONFIRM (API 30+) for a satisfying "done" waveform, or VIRTUAL_KEY
 * on older devices for a clean short click.
 */
fun View.performMilestoneHaptic() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    } else {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
}

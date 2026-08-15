package com.norfold.app.ui.feedback

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

enum class UiFeedbackTone {
    Info,
    Success,
    Warning,
    Error,
}

enum class UiFeedbackDuration {
    Short,
    Long,
    Indefinite,
}

sealed interface UiFeedbackAction {
    data class Resolve(val token: Long) : UiFeedbackAction
    data object RetrySync : UiFeedbackAction
    data object RetryInitialization : UiFeedbackAction

    /**
     * Restore the backup that was just refused, without the notes and documents in it.
     *
     * Offered only when a restore was refused because the backup predates an editor content epoch
     * boundary. The refusal wrote nothing, so this is the owner's explicit second choice: take the
     * accounts, workspaces, settings and task data out of the backup and leave the erased editor
     * generation erased.
     */
    data object RestoreWithoutEditorContent : UiFeedbackAction
}

data class UiFeedback(
    val id: Long,
    val message: String,
    val tone: UiFeedbackTone = UiFeedbackTone.Info,
    val actionLabel: String? = null,
    val action: UiFeedbackAction? = null,
    val duration: UiFeedbackDuration = if (actionLabel == null) UiFeedbackDuration.Short else UiFeedbackDuration.Long,
)

enum class NorfoldHaptic {
    Confirm,
    Reject,
    DragStart,
    Tick,
}

/**
 * Uses action-oriented platform constants so feedback follows device capabilities and the user's
 * system touch-feedback setting. No vibration permission or raw waveform is required.
 */
fun View.performNorfoldHaptic(cue: NorfoldHaptic): Boolean = performHapticFeedback(
    when (cue) {
        NorfoldHaptic.Confirm -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        NorfoldHaptic.Reject -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        NorfoldHaptic.DragStart -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.DRAG_START
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        NorfoldHaptic.Tick -> HapticFeedbackConstants.CLOCK_TICK
    },
)

package com.norfold.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Neutral, hue-free tokens for chrome that used to carry fixed decorative colors (status/priority/
 * tag/media). The app has exactly one brand hue — the user-selected accent (`colorScheme.primary`) —
 * so these derive from the neutral Material surfaces and the accent, never from literal hex values.
 *
 * The only intentionally-colored exceptions are [signalGreen]/[signalRed], reserved for
 * additive/subtractive diffs and sync-conflict state where legibility of the +/- signal matters.
 * Per-item color pickers (e.g. a task's chosen Color) are unaffected and keep their real colors.
 */
object SemanticColors {
    /** Neutral chip surface for status / priority / tag pills. */
    val chipBg: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
    val chipFg: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

    /** Emphasis chip (e.g. the highest priority tier) — a soft accent tint. */
    val accentChipBg: Color @Composable get() = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val accentChipFg: Color @Composable get() = MaterialTheme.colorScheme.primary

    /** Tint for media-type icons that previously used per-type hues. */
    val mediaIconTint: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

    /** Progress fill for generic (non per-item) progress bars. */
    val progressFill: Color @Composable get() = MaterialTheme.colorScheme.primary

    /** Reserved signal colors — additive/ok vs subtractive/error. Not part of the neutral scheme. */
    val signalGreen: Color get() = Color(0xFF20B26B)
    val signalRed: Color get() = Color(0xFFEF4D5D)
}

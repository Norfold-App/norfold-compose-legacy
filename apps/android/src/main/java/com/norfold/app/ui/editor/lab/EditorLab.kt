package com.norfold.app.ui.editor.lab

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import com.norfold.app.editor.lab.EditorLabFlag

/**
 * Process-local, debug-oriented store of [EditorLabFlag] overrides, persisted to `SharedPreferences`.
 *
 * The map starts empty, so [isEnabled] returns each flag's `false` default until something writes an override.
 * The only writer is the debug-only `EditorLabActivity`: a release build never calls [setEnabled], so on a
 * user's device every experiment stays off. Overrides are held as Compose snapshot state, so a host that reads
 * [isEnabled] inside a composition recomposes the moment a developer flips the toggle.
 *
 * It mirrors the many `getSharedPreferences("norfold_*")` stores already in the app rather than introducing a
 * new persistence mechanism, and it is intentionally separate from the architectural editor-flag delivery in
 * `EditorHostFlags`, which must not gain a toggle.
 */
object EditorLab {
    private const val Preferences = "norfold_editor_lab"

    private val overrides = mutableStateMapOf<EditorLabFlag, Boolean>()

    @Volatile
    private var hydrated = false

    /** Loads persisted overrides once. Safe to call from any editor entry point or the lab activity. */
    fun ensureHydrated(context: Context) {
        if (hydrated) return
        synchronized(this) {
            if (hydrated) return
            val prefs = context.applicationContext.getSharedPreferences(Preferences, Context.MODE_PRIVATE)
            EditorLabFlag.entries.forEach { flag ->
                if (prefs.contains(flag.key)) {
                    overrides[flag] = prefs.getBoolean(flag.key, flag.defaultEnabled)
                }
            }
            hydrated = true
        }
    }

    /** True iff the experiment is currently on. Reads snapshot state, so it is observed inside a composition. */
    fun isEnabled(flag: EditorLabFlag): Boolean = overrides[flag] ?: flag.defaultEnabled

    /** Turns an experiment on or off and persists the choice. Debug-only in practice. */
    fun setEnabled(context: Context, flag: EditorLabFlag, enabled: Boolean) {
        overrides[flag] = enabled
        context.applicationContext.getSharedPreferences(Preferences, Context.MODE_PRIVATE)
            .edit().putBoolean(flag.key, enabled).apply()
    }

    /** Clears every override back to the shipped (all-off) configuration. */
    fun reset(context: Context) {
        overrides.clear()
        context.applicationContext.getSharedPreferences(Preferences, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }
}

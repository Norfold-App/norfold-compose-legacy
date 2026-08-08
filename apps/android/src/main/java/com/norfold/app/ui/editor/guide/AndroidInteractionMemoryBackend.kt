package com.norfold.app.ui.editor.guide

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.norfold.app.editor.memory.InteractionMemory
import com.norfold.app.editor.memory.InteractionMemoryBackend
import com.norfold.app.editor.memory.MemoryScope

/** Device-local persistence for durable Interaction Memory. It has no sync, analytics, or network path. */
class SharedPreferencesInteractionMemoryBackend(
    private val preferences: SharedPreferences,
) : InteractionMemoryBackend {
    private fun prefix(scope: MemoryScope) = "${scope.name.lowercase()}:"
    private fun scoped(scope: MemoryScope, key: String) = prefix(scope) + key

    override fun read(scope: MemoryScope, key: String): String? =
        preferences.getString(scoped(scope, key), null)

    override fun write(scope: MemoryScope, key: String, encodedValue: String) {
        require(scope.isDurable) { "$scope is not durable" }
        preferences.edit().putString(scoped(scope, key), encodedValue).apply()
    }

    override fun remove(scope: MemoryScope, key: String) {
        preferences.edit().remove(scoped(scope, key)).apply()
    }

    override fun clear(scope: MemoryScope) {
        val keys = preferences.all.keys.filter { it.startsWith(prefix(scope)) }
        if (keys.isEmpty()) return
        preferences.edit().also { editor -> keys.forEach(editor::remove) }.apply()
    }
}

@Composable
fun rememberEditorInteractionMemory(durable: Boolean): InteractionMemory {
    val context = LocalContext.current.applicationContext
    return remember(context, durable) {
        if (!durable) InteractionMemory()
        else InteractionMemory(
            SharedPreferencesInteractionMemoryBackend(
                context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            ),
        )
    }
}

private const val PREFERENCES_NAME = "norfold_editor_interaction_memory"

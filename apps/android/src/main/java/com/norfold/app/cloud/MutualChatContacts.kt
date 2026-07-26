package com.norfold.app.cloud

import android.content.Context

/**
 * Tracks which chat counterparts are "mutual" — people this user has replied to at least once.
 * Backs the silent-stranger rule: the first DM from a never-replied-to contact arrives without a
 * notification; once you reply, both sides notify normally.
 *
 * Stored in SharedPreferences (not Room) so [NorfoldMessagingService] can consult it on the FCM
 * thread without spinning up the database. The set is per-account usernames, cleared on sign-out
 * along with the rest of the device prefs.
 */
object MutualChatContacts {
    private const val Preferences = "norfold_chat_contacts"
    private const val MutualKey = "mutual_usernames"

    fun isMutual(context: Context, username: String): Boolean =
        username.lowercase() in read(context)

    /** Called when the user sends a DM — replying (or initiating) makes the contact mutual. */
    fun markMutual(context: Context, username: String) {
        if (username.isBlank()) return
        val prefs = context.getSharedPreferences(Preferences, Context.MODE_PRIVATE)
        val current = read(context)
        val normalized = username.lowercase()
        if (normalized in current) return
        prefs.edit().putStringSet(MutualKey, current + normalized).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(Preferences, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun read(context: Context): Set<String> =
        context.getSharedPreferences(Preferences, Context.MODE_PRIVATE)
            .getStringSet(MutualKey, emptySet()) ?: emptySet()
}

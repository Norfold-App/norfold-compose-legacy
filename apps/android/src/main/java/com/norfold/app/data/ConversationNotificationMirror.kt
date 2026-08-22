package com.norfold.app.data

import com.norfold.app.data.ChatConversationPrefs.NotificationLevel

/**
 * Translates one conversation's notification preference between the device store and the server.
 *
 * [ChatConversationPrefs] is device-local by design and says so in its own documentation. This is
 * the one deliberate exception, and it exists because the server cannot do its job without the
 * value: `conversation_notification_prefs` is read inside `enqueue_account_chat_push()` *before* a
 * push is ever created, so a preference that never leaves the device cannot stop one being sent.
 * Until this mirror existed the table was written by nothing — `set_conversation_notification_pref`
 * had no call sites — so the server-side filter was deployed and permanently unarmed.
 *
 * ## Why both halves travel together
 *
 * The RPC is an upsert over both columns (`set level = excluded.level, muted_until =
 * excluded.muted_until`), so a call that carries only the value the owner just changed silently
 * clears the other one: muting a conversation would reset its level to the default, and changing
 * its level would unmute it. Every mirror therefore sends the whole pair, which is why [toServer]
 * takes both and there is no single-field variant to reach for by mistake.
 *
 * ## Why an indefinite mute becomes a date in the year 9999
 *
 * The two models disagree about mute, and the disagreement cannot be papered over. The device
 * stores a boolean — muted or not, with no expiry, because that is what the toggle in the
 * conversation sheet offers. The server stores `muted_until timestamptz` and suppresses while it is
 * in the future, which can express "for the next hour" but has no value meaning "until I say
 * otherwise". [IndefiniteMuteUntil] is that missing value: far enough out to be unreachable, inside
 * the range Postgres accepts, and obviously a sentinel rather than a real date to anyone reading
 * the row.
 *
 * Folding an indefinite mute onto `level = 'off'` instead would have been tidier on the wire and
 * wrong in the app: the two are different controls with different labels, and a second device
 * reading `'off'` back could no longer tell whether the owner had muted the conversation or set its
 * level to None. It would light up the wrong control. Because [toLocal] compares whatever timestamp
 * it finds against the current time rather than against the sentinel, a genuinely transient mute
 * written by some later surface — or by another client — reads back correctly here too.
 *
 * ## What the server does with the result, which is stronger than what the device does
 *
 * [com.norfold.app.domain.PushDelivery] silences; the server suppresses. `'off'` or an active mute
 * makes `enqueue_account_chat_push()` return before it inserts anything, so no push is created at
 * all — no delivery row, no radio wake, nothing in the shade. That is a real change in what the
 * owner sees from these controls, and it is the reading the labels ask for: "None" means none.
 *
 * It does not lose the message, which is the property the whole notification design is built to
 * protect. A push is not how a message reaches Norfold — the encrypted relay is. Catch-up runs from
 * the relay observer restarted on resume, from the cursor reconcile that follows it, and from
 * periodic sync, none of which involve FCM. So a suppressed push costs the notification the owner
 * asked not to receive and nothing else: the message is in the conversation when they next open it.
 *
 * The device-side gate in [com.norfold.app.cloud.NorfoldMessagingService] stays exactly as it was.
 * It now guards a narrower case — a push that arrives despite the preference, because the mirror
 * failed while offline, because the owner is signed out, or because the conversation is a workspace
 * channel the server has no row for — and 'mentions' still relies on it entirely, since the server
 * cannot read ciphertext to find a mention and says as much in the migration.
 */
object ConversationNotificationMirror {
    /** The preference in the server's vocabulary, ready for the RPC's two parameters. */
    data class ServerPref(val level: String, val mutedUntil: String?)

    /** The preference in the device's vocabulary, as [ChatConversationPrefs] holds it. */
    data class LocalPref(val level: NotificationLevel, val muted: Boolean)

    /**
     * Stands in for "muted with no expiry", which `muted_until` cannot say on its own. Chosen to be
     * unmistakable in a database row and comfortably inside what `timestamptz` accepts.
     */
    const val IndefiniteMuteUntil = "9999-12-31T23:59:59Z"

    /** Level strings the RPC accepts; anything else is rejected with `invalid_level`. */
    private const val LevelOff = "off"
    private const val LevelMentions = "mentions"
    private const val LevelAll = "all"

    fun toServer(level: NotificationLevel, muted: Boolean): ServerPref = ServerPref(
        level = when (level) {
            NotificationLevel.None -> LevelOff
            NotificationLevel.Mentions -> LevelMentions
            NotificationLevel.All -> LevelAll
        },
        // Not muted has to send null rather than omit the field, because the upsert would otherwise
        // leave a mute the owner has just switched off in place on the server.
        mutedUntil = if (muted) IndefiniteMuteUntil else null,
    )

    /**
     * Reads a server row back into the device's two values. [now] is passed rather than read so the
     * mute comparison is a decision this can be tested on rather than a clock reading it cannot.
     */
    fun toLocal(level: String?, mutedUntil: String?, now: Long): LocalPref = LocalPref(
        // An unrecognised level falls to All, matching how the store treats an unparseable stored
        // value. Every fallback around notifications points the same way: a value nobody can read
        // must never be the reason a message arrives without a sound.
        level = when (level?.lowercase()) {
            LevelOff -> NotificationLevel.None
            LevelMentions -> NotificationLevel.Mentions
            else -> NotificationLevel.All
        },
        muted = mutedUntilEpochMillis(mutedUntil)?.let { it > now } ?: false,
    )

    /**
     * Parses a Postgres `timestamptz` as PostgREST renders it. Two attempts rather than one because
     * the same column comes back as `...Z` from some paths and `...+00:00` from others, and
     * [java.time.Instant] parses only the first spelling.
     */
    private fun mutedUntilEpochMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        runCatching { return java.time.Instant.parse(value).toEpochMilli() }
        runCatching { return java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }
        return null
    }
}

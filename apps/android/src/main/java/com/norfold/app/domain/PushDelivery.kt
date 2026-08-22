package com.norfold.app.domain

/**
 * Whether an arrived push may be posted, and whether it may make a sound.
 *
 * Separated from the messaging service because the service half is unavoidably device-bound — a
 * database read, a SharedPreferences read, a `NotificationManager` — while the rule itself is a
 * decision about four facts, and it is the rule that goes silently wrong. A push suppressed by the
 * wrong flag is a message the owner never learns about, and no amount of on-device testing reliably
 * surfaces that, because the failing case is the one where nothing appears.
 *
 * ## Why almost nothing is suppressed
 *
 * A reminder is Norfold's own idea, computed on-device from a task row, so a preference can *hold*
 * it: the alarm is re-armed for later and nothing is lost. A push is a thing that already happened
 * somewhere else. By the time this decision runs, the message exists and has been delivered; there
 * is no second delivery to schedule. Suppressing the post does not defer it, it discards it.
 *
 * So quiet hours and a muted conversation both resolve to [Decision.silent] rather than
 * [Decision.suppressed]. The notification appears in the shade with nothing to alert with — the
 * owner finds it rather than being interrupted by it, and the message survives. Suppression is kept
 * for the single case where the owner has switched a whole class of notification off, which is a
 * statement that they do not want it at all rather than not want it now.
 */
object PushDelivery {
    /** The outcome for one push: whether to post it, and whether posting it may alert. */
    data class Decision(val suppressed: Boolean, val silent: Boolean)

    /**
     * True for the kinds Norfold raises about tasks.
     *
     * Shared with the channel routing so the two cannot disagree: a push that is routed to the Tasks
     * channel is exactly a push the "Task reminders" switch governs. Splitting the prefix test
     * across two call sites is how a kind ends up filed under Tasks while being gated as chat.
     */
    fun isTaskKind(kind: String?): Boolean = kind?.startsWith("task") == true

    /** True for the kinds that belong to a conversation, and so can be muted per-conversation. */
    fun isChatKind(kind: String?): Boolean = kind?.startsWith("chat") == true

    /**
     * The decision for one push.
     *
     * [taskNotificationsAllowed] is `notificationPush`, which onboarding and Settings both present
     * as "Task reminders". It therefore gates task kinds only: switching off task reminders must not
     * quietly stop delivering the messages other people send.
     *
     * [conversationSilenced] folds the two per-conversation states that mean *do not interrupt me
     * about this one* — an explicit mute, and a level below "all messages". It is only consulted for
     * chat kinds, because nothing else belongs to a conversation.
     */
    fun decide(
        kind: String?,
        taskNotificationsAllowed: Boolean,
        withinQuietHours: Boolean,
        conversationSilenced: Boolean,
    ): Decision {
        if (isTaskKind(kind) && !taskNotificationsAllowed) {
            return Decision(suppressed = true, silent = false)
        }
        return Decision(
            suppressed = false,
            silent = withinQuietHours || (isChatKind(kind) && conversationSilenced),
        )
    }
}

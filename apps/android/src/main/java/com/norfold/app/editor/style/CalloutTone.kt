package com.norfold.app.editor.style

/**
 * A callout's tone: what kind of aside it is, and the colour that says so.
 *
 * `CalloutBlock.tone` has been a free-form `String` since callouts existed, and the Markdown codec has been
 * populating it from GitHub alert syntax (`> [!WARNING]`) and re-emitting it — while **nothing rendered it**.
 * Every callout in every document therefore looked identical, and a warning imported from a README came back
 * out as a warning without ever having appeared as one.
 *
 * This is the missing half. It stays a `String` in the document: the stored value is the interchange format,
 * an unknown tone from a newer build (or a hand-written `[!SECURITY]`) round-trips verbatim and merely
 * *renders* as [NOTE] rather than being rewritten or dropped.
 *
 * Pure Kotlin, colours included, for the same reason the reading scale is: the claims worth asserting here —
 * that `info` and `note` mean the same thing, that an unknown tone degrades instead of crashing, that the
 * five tones are actually distinguishable, that each one survives a Markdown round-trip — are all decidable
 * on the desktop JVM. A screenshot proves none of them.
 *
 * The palette is deliberately quiet. Each tone is a *tint* of the page rather than a saturated fill, because
 * this is the writing canvas: a callout has to read as a passage of the document that is set aside, not as a
 * notification pasted on top of it. Text inside keeps the page's own ink — only the surface, the hairline and
 * the icon carry the tone, which is also what keeps a colour the user applied to the text from being fought.
 */
enum class CalloutTone(
    /** The canonical stored value. Uppercased it is also the GitHub alert keyword, so Markdown round-trips. */
    val stored: String,
    /** The title a freshly created callout of this tone gets, when the user has not typed one. */
    val defaultTitle: String,
    val lightTint: Long,
    val lightInk: Long,
    val darkTint: Long,
    val darkInk: Long,
) {
    /** The neutral aside. Grey rather than blue: the default callout must not look like a status. */
    NOTE("note", "Note", 0xFFF1F1EF, 0xFF37352F, 0xFF262626, 0xFFE9E9E7),
    TIP("tip", "Tip", 0xFFECF5EF, 0xFF2C6B45, 0xFF17251D, 0xFF86C79E),
    IMPORTANT("important", "Important", 0xFFF2EFFB, 0xFF574394, 0xFF211E33, 0xFFB0A2E8),
    WARNING("warning", "Warning", 0xFFFBF3E3, 0xFF87590F, 0xFF2A2314, 0xFFDFB45B),
    CAUTION("caution", "Caution", 0xFFFBEDEB, 0xFF97291F, 0xFF2C1B19, 0xFFE99189),
    ;

    /** The next tone in the cycle, for a control that steps through them rather than opening a menu. */
    fun next(): CalloutTone = entries[(ordinal + 1) % entries.size]

    companion object {
        /**
         * The tone a stored string means, falling back to [NOTE] for anything unrecognised.
         *
         * The aliases are not decoration: `info`, `success` and `danger` are what most authoring tools and
         * every CSS framework call these, so a document arriving from one of them would otherwise render as
         * five identical grey boxes. Case and surrounding whitespace are ignored because the string is
         * hand-editable and `[!Warning]` is not a different tone from `[!WARNING]`.
         */
        fun of(stored: String?): CalloutTone = when (stored?.trim()?.lowercase()) {
            null, "" -> NOTE
            "note", "info", "information", "neutral" -> NOTE
            "tip", "success", "hint", "check", "done" -> TIP
            "important", "attention", "highlight" -> IMPORTANT
            "warning", "warn", "alert" -> WARNING
            "caution", "danger", "error", "critical", "stop" -> CAUTION
            else -> NOTE
        }
    }
}

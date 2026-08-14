package com.norfold.app.editor.sticker

/**
 * A deliberately local, reviewed catalog shared by chat and Image Focus.
 *
 * The manifest contains only family-safe assets checked into this repository. It deliberately has
 * no network search fallback: a remote result can never bypass the safety policy by accident.
 */
data class Sticker(
    val id: String,
    val label: String,
    val category: StickerCategory,
    val keywords: Set<String>,
    val popularRank: Int,
) {
    val assetUri: String get() = "file:///android_asset/noto-stickers/$id.svg"
}

enum class StickerCategory(val label: String) {
    POPULAR("Popular"), EXPRESSIONS("Expressions"), CELEBRATE("Celebrate"), NATURE("Nature"), EVERYDAY("Everyday"),
}

object StickerCatalog {
    const val SOURCE_NAME = "Noto Emoji"
    const val SOURCE_LICENSE = "Apache-2.0"
    const val THIRD_PARTY_NOTICE_PATH = "THIRD_PARTY_NOTICES.md"

    private val approved = listOf(
        Sticker("grin", "Grinning face", StickerCategory.EXPRESSIONS, setOf("happy", "smile", "joy", "face"), 1),
        Sticker("heart_eyes", "Heart eyes", StickerCategory.EXPRESSIONS, setOf("love", "happy", "face"), 2),
        Sticker("thumbs_up", "Thumbs up", StickerCategory.EXPRESSIONS, setOf("like", "okay", "yes", "approve"), 3),
        Sticker("party", "Party popper", StickerCategory.CELEBRATE, setOf("party", "celebrate", "congratulations"), 4),
        Sticker("gift", "Gift", StickerCategory.CELEBRATE, setOf("gift", "present", "birthday"), 5),
        Sticker("star", "Star", StickerCategory.CELEBRATE, setOf("star", "favorite", "great"), 6),
        Sticker("dog", "Dog", StickerCategory.NATURE, setOf("dog", "pet", "animal"), 7),
        Sticker("cat", "Cat", StickerCategory.NATURE, setOf("cat", "pet", "animal"), 8),
        Sticker("flower", "Flower", StickerCategory.NATURE, setOf("flower", "nature", "spring"), 9),
        Sticker("rainbow", "Rainbow", StickerCategory.NATURE, setOf("rainbow", "color", "weather"), 10),
        Sticker("rocket", "Rocket", StickerCategory.EVERYDAY, setOf("rocket", "launch", "space", "fast"), 11),
        Sticker("cool", "Cool face", StickerCategory.EXPRESSIONS, setOf("cool", "sunglasses", "face"), 12),
    )

    fun categories(): List<StickerCategory> = StickerCategory.entries

    fun items(category: StickerCategory = StickerCategory.POPULAR, query: String = ""): List<Sticker> {
        val tokens = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        return approved.asSequence()
            .filter { category == StickerCategory.POPULAR || it.category == category }
            .filter { sticker ->
                tokens.isEmpty() || tokens.all { token ->
                    sticker.label.lowercase().contains(token) || sticker.keywords.any { it.contains(token) }
                }
            }
            .sortedWith(compareBy<Sticker> { it.popularRank }.thenBy { it.label })
            .toList()
    }

    fun find(id: String): Sticker? = approved.firstOrNull { it.id == id }

    /** Guardrail for future manifest edits and import review. */
    fun isFamilySafe(candidate: Sticker): Boolean = candidate in approved
}

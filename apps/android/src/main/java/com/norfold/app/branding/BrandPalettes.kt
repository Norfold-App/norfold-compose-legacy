package com.norfold.app.branding

import androidx.compose.ui.graphics.Color
import com.norfold.app.domain.ThemeProfile

/**
 * The Norfold color system, derived from the adaptive logo reference.
 * Each palette carries a four-stop gradient (c1..c4) used by the animated mark and decorative
 * gradients, plus an [accent] that stays legible as a Material primary on both light and dark.
 */
data class BrandPalette(
    val profile: ThemeProfile,
    val displayName: String,
    val c1: Color,
    val c2: Color,
    val c3: Color,
    val c4: Color,
    val accent: Color,
    val onAccent: Color = Color.White,
) {
    val gradient: List<Color> get() = listOf(c1, c2, c3)
}

private fun hex(v: Long) = Color(v or 0xFF000000L)

val BrandPalettes: Map<ThemeProfile, BrandPalette> = mapOf(
    ThemeProfile.Neon to BrandPalette(ThemeProfile.Neon, "Neon", hex(0xFF80E5), hex(0x9D50BB), hex(0x6E48AA), hex(0x4FACFE), accent = hex(0x9D50BB)),
    ThemeProfile.Sunset to BrandPalette(ThemeProfile.Sunset, "Sunset", hex(0xFF6B6B), hex(0xFECA57), hex(0xFF9FF3), hex(0x54A0FF), accent = hex(0xF75C7A)),
    ThemeProfile.Ocean to BrandPalette(ThemeProfile.Ocean, "Ocean", hex(0x00D2FF), hex(0x3A7BD5), hex(0x00C6FF), hex(0x0072FF), accent = hex(0x2E7BE4)),
    ThemeProfile.Forest to BrandPalette(ThemeProfile.Forest, "Forest", hex(0xA8E063), hex(0x56AB2F), hex(0x11998E), hex(0x38EF7D), accent = hex(0x1F9E7E)),
    ThemeProfile.Fire to BrandPalette(ThemeProfile.Fire, "Fire", hex(0xF12711), hex(0xF5AF19), hex(0xFF512F), hex(0xDD2476), accent = hex(0xE23A2E)),
    ThemeProfile.Candy to BrandPalette(ThemeProfile.Candy, "Candy", hex(0xFF9A9E), hex(0xFAB0D8), hex(0xA18CD1), hex(0xFBC2EB), accent = hex(0xB06AB3)),
    ThemeProfile.Midnight to BrandPalette(ThemeProfile.Midnight, "Midnight", hex(0x4CA1AF), hex(0x2C5364), hex(0x557C93), hex(0x203A43), accent = hex(0x3E8EA0)),
    ThemeProfile.Gold to BrandPalette(ThemeProfile.Gold, "Gold", hex(0xF7971E), hex(0xFFD200), hex(0xFFB86C), hex(0xFCB045), accent = hex(0xE08A12)),
    ThemeProfile.Graphite to BrandPalette(ThemeProfile.Graphite, "Graphite", hex(0x4A4E63), hex(0x33374A), hex(0x6B6F82), hex(0x9195A6), accent = hex(0x33374A)),
)

fun ThemeProfile.palette(): BrandPalette = BrandPalettes[this] ?: BrandPalettes.getValue(ThemeProfile.Graphite)

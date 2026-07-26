package com.norfold.app.branding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Norfold is intentionally wordmark-led; this plain tile is used only where a
 * compact mark is required. The tile carries the brand gradient and the traced
 * Norfold glyph on top — brand-white on dark tiles, brand-ink on light ones,
 * decided from the palette rather than hardcoded.
 */
@Composable
fun AnimatedNorfoldLogo(
    size: Dp = 54.dp,
    palette: BrandPalette = com.norfold.app.domain.ThemeProfile.Norfold.palette(),
    dark: Boolean = false,
    animate: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val tileIsDark = dark ||
        (palette.c1.luminance() + palette.c2.luminance() + palette.c3.luminance()) / 3f < 0.5f
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.24f))
            .background(Brush.linearGradient(listOf(palette.c1, palette.c2, palette.c3))),
        contentAlignment = Alignment.Center,
    ) {
        NorfoldGlyphMark(
            size = size * 0.64f, // same inset the raster carried as padding
            ink = if (tileIsDark) NorfoldInkOnDark else NorfoldInk,
        )
    }
}

@Composable
fun NorfoldLogo(size: Dp, modifier: Modifier = Modifier) {
    AnimatedNorfoldLogo(size = size, modifier = modifier)
}

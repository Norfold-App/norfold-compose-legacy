package com.norfold.app.branding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.norfold.app.R

/**
 * Norfold is intentionally wordmark-led; this plain tile is used only where a
 * compact mark is required. The tile carries the brand gradient and the real
 * Norfold glyph on top — white on dark tiles, ink on light ones, decided from
 * the palette rather than hardcoded.
 */
@Composable
fun AnimatedNorfoldLogo(
    size: Dp = 54.dp,
    palette: BrandPalette = com.norfold.app.domain.ThemeProfile.Neon.palette(),
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
        Image(
            painter = painterResource(if (tileIsDark) R.drawable.norfold_glyph_white else R.drawable.norfold_glyph),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(size * 0.18f),
        )
    }
}

@Composable
fun NorfoldLogo(size: Dp, modifier: Modifier = Modifier) {
    AnimatedNorfoldLogo(size = size, modifier = modifier)
}

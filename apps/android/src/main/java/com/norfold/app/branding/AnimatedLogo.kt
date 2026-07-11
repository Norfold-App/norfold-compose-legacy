package com.norfold.app.branding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Norfold is intentionally wordmark-led; this plain tile is used only where a compact mark is required. */
@Composable
fun AnimatedNorfoldLogo(
    size: Dp = 54.dp,
    palette: BrandPalette = com.norfold.app.domain.ThemeProfile.Neon.palette(),
    dark: Boolean = false,
    animate: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.24f))
            .background(Brush.linearGradient(listOf(palette.c1, palette.c2, Color(0xFF9A48FF)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "N",
            color = Color.White,
            fontSize = (size.value * 0.48f).sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.sp,
        )
    }
}

@Composable
fun NorfoldLogo(size: Dp, modifier: Modifier = Modifier) {
    AnimatedNorfoldLogo(size = size, modifier = modifier)
}

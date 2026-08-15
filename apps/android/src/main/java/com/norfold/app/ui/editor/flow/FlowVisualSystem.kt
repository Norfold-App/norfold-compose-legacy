package com.norfold.app.ui.editor.flow

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Flow is a warm editorial canvas; its larger type and softer surfaces stay local to the editor. */
@Composable
internal fun FlowVisualTheme(content: @Composable () -> Unit) {
    val outer = MaterialTheme.colorScheme
    val dark = outer.background.relativeLuminance() < 0.35f
    val colors = outer.copy(
        background = if (dark) Color(0xFF111410) else Color(0xFFFFFCF7),
        surface = if (dark) Color(0xFF171A16) else Color(0xFFFFFEFC),
        surfaceVariant = if (dark) Color(0xFF242821) else Color(0xFFF3F0E8),
        surfaceContainer = if (dark) Color(0xFF1C201A) else Color(0xFFF8F4EC),
        surfaceContainerLow = if (dark) Color(0xFF171A16) else Color(0xFFFFFCF7),
        surfaceContainerHigh = if (dark) Color(0xFF292D26) else Color(0xFFF0ECE3),
        outline = if (dark) Color(0xFF555B50) else Color(0xFFD9D3C7),
        outlineVariant = if (dark) Color(0xFF3D4239) else Color(0xFFE8E2D7),
        onBackground = if (dark) Color(0xFFF0F1EC) else Color(0xFF20231F),
        onSurface = if (dark) Color(0xFFF0F1EC) else Color(0xFF20231F),
        onSurfaceVariant = if (dark) Color(0xFFC5C9BF) else Color(0xFF62675E),
    )
    val base = MaterialTheme.typography
    val bodyFamily = base.bodyLarge.fontFamily
    val typography = base.copy(
        headlineLarge = base.headlineLarge.copy(
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            fontSize = 43.2.sp,
            lineHeight = 52.8.sp,
        ),
        headlineMedium = base.headlineMedium.copy(
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 32.4.sp,
            lineHeight = 40.8.sp,
        ),
        headlineSmall = base.headlineSmall.copy(
            fontFamily = bodyFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 25.2.sp,
            lineHeight = 33.6.sp,
        ),
        titleLarge = base.titleLarge.copy(fontSize = 25.2.sp, lineHeight = 33.6.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontSize = 21.6.sp, lineHeight = 28.8.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontSize = 19.2.sp, lineHeight = 26.4.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontSize = 21.6.sp, lineHeight = 33.6.sp, fontFamily = bodyFamily),
        bodyMedium = base.bodyMedium.copy(fontSize = 18.sp, lineHeight = 27.6.sp, fontFamily = bodyFamily),
        bodySmall = base.bodySmall.copy(fontSize = 15.6.sp, lineHeight = 22.8.sp, fontFamily = bodyFamily),
        labelLarge = base.labelLarge.copy(fontSize = 16.8.sp, lineHeight = 22.8.sp, fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.copy(fontSize = 14.4.sp, lineHeight = 20.4.sp, fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.copy(fontSize = 13.2.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    )
    MaterialTheme(
        colorScheme = colors,
        typography = typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(22.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}

private fun Color.relativeLuminance(): Float {
    fun channel(value: Float): Float = if (value <= 0.04045f) {
        value / 12.92f
    } else {
        Math.pow(((value + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }
    return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
}

package com.norfold.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.branding.BrandPalette
import com.norfold.app.branding.palette
import com.norfold.app.domain.AppSettings
import com.norfold.app.domain.ThemeMode

private fun lightScheme(p: BrandPalette) = lightColorScheme(
    primary = Color(0xFF6F36FF),
    onPrimary = Color.White,
    secondary = Color(0xFF9A48FF),
    tertiary = Color(0xFFE54CBD),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF171725),
    surface = Color.White,
    onSurface = Color(0xFF171725),
    surfaceVariant = Color(0xFFF1F2F7),
    onSurfaceVariant = Color(0xFF72738A),
    outline = Color(0xFFE4E6EF),
    outlineVariant = Color(0xFFEEF0F5),
    primaryContainer = Color(0xFFEEE8FF),
    onPrimaryContainer = Color(0xFF5723D1),
    error = Color(0xFFEF4D5D),
)

private fun darkScheme(p: BrandPalette) = darkColorScheme(
    primary = Color(0xFF8D52FF),
    onPrimary = Color.White,
    secondary = Color(0xFFD14BC8),
    tertiary = Color(0xFF4D8DFF),
    background = Color(0xFF070A12),
    onBackground = Color(0xFFF7F5FF),
    surface = Color(0xFF0E131F),
    onSurface = Color(0xFFF7F5FF),
    surfaceVariant = Color(0xFF151B28),
    onSurfaceVariant = Color(0xFF999DB0),
    outline = Color(0xFF252C3A),
    outlineVariant = Color(0xFF1B2230),
    primaryContainer = Color(0xFF20153E),
    onPrimaryContainer = Color(0xFFE8DEFF),
    error = Color(0xFFFF6471),
)

private val NorfoldTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
)

private val NorfoldShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

@Composable
fun NorfoldTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val palette = settings.themeProfile.palette()
    val dark = when (settings.themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val density = LocalDensity.current
    val scale = settings.uiScale.coerceIn(0.78f, 1.12f)
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = density.density * scale,
            fontScale = density.fontScale * scale,
        ),
    ) {
        MaterialTheme(
            colorScheme = if (dark) darkScheme(palette) else lightScheme(palette),
            typography = NorfoldTypography,
            shapes = NorfoldShapes,
            content = content,
        )
    }
}

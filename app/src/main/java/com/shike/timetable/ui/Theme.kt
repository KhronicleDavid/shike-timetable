package com.shike.timetable.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.shike.timetable.theme.*

val LocalAppTheme = staticCompositionLocalOf<ThemePack> { error("未提供整体主题") }
val LocalCompanionVisible = staticCompositionLocalOf { false }
val LocalCompanionMoment = staticCompositionLocalOf<CompanionMoment?> { null }
val LocalCompanionShuffle = staticCompositionLocalOf<(() -> Unit)?> { null }
val LocalShuffleHint = staticCompositionLocalOf { false }
val LocalDarkTheme = staticCompositionLocalOf { false }
val CourseAccents: List<Color> @Composable get() = LocalAppTheme.current.palette(LocalDarkTheme.current).courseAccents.map { Color(it) }

private fun ThemePalette.scheme(dark: Boolean): ColorScheme {
    fun c(key: String) = Color(color(key))
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(primary = c("primary"), onPrimary = c("onPrimary"), primaryContainer = c("primaryContainer"),
        onPrimaryContainer = c("onPrimaryContainer"), secondary = c("secondary"), onSecondary = c("onSecondary"),
        secondaryContainer = c("secondaryContainer"), onSecondaryContainer = c("onSecondaryContainer"),
        tertiary = c("tertiary"), tertiaryContainer = c("tertiaryContainer"), onTertiaryContainer = c("onTertiaryContainer"),
        background = c("background"), onBackground = c("onBackground"), surface = c("surface"), onSurface = c("onSurface"),
        surfaceVariant = c("surfaceVariant"), onSurfaceVariant = c("onSurfaceVariant"), surfaceContainer = c("surfaceContainer"),
        surfaceContainerLow = c("surfaceContainerLow"), surfaceContainerHigh = c("surfaceContainerHigh"),
        surfaceContainerHighest = c("surfaceContainerHighest"), outline = c("outline"), outlineVariant = c("outlineVariant"))
}

@Composable fun ShikeTheme(mode: String, theme: ThemePack, showMascot: Boolean = true,
    companion: CompanionMoment? = null, showShuffleHint: Boolean = false,
    onCompanionShuffle: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val dark = mode == "dark" || (mode == "system" && isSystemInDarkTheme())
    val colors = theme.palette(dark).scheme(dark)
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            if (Build.VERSION.SDK_INT < 29) {
                @Suppress("DEPRECATION")
                window.navigationBarColor = colors.surface.toArgb()
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalAppTheme provides theme,
        LocalCompanionVisible provides (showMascot && theme.companion != null), LocalCompanionMoment provides companion,
        LocalCompanionShuffle provides onCompanionShuffle, LocalShuffleHint provides showShuffleHint, LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = colors, shapes = Shapes(
            small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
        ), typography = Typography(
            headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
            headlineSmall = TextStyle(fontSize = 23.sp, fontWeight = FontWeight.Bold), titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
            titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
            bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 20.sp), labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
        ), content = content)
    }
}

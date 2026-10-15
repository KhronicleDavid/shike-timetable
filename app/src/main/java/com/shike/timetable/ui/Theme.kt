package com.shike.timetable.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime
import com.shike.timetable.data.HimariCompanion
import com.shike.timetable.data.HimariMoment

val CourseAccents = listOf(Color(0xFF397D79), Color(0xFFC5836D), Color(0xFF52688C), Color(0xFF9A80A5), Color(0xFFA48744), Color(0xFF5F8EA5))
val LocalHimariVisible = staticCompositionLocalOf { true }
private val Light = lightColorScheme(
    primary = Color(0xFF2C716C), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEEE7), onPrimaryContainer = Color(0xFF234F4C),
    secondary = Color(0xFF946D36), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF6EBD3), onSecondaryContainer = Color(0xFF6E542F),
    tertiary = Color(0xFF52688C), tertiaryContainer = Color(0xFFE6ECF5), onTertiaryContainer = Color(0xFF30435F),
    background = Color(0xFFFAF8F3), onBackground = Color(0xFF293B50),
    surface = Color(0xFFFFFEFB), onSurface = Color(0xFF293B50),
    surfaceVariant = Color(0xFFF0EFE7), onSurfaceVariant = Color(0xFF70796F),
    surfaceContainer = Color(0xFFF3F2EA), surfaceContainerLow = Color(0xFFF8F6F0),
    surfaceContainerHigh = Color(0xFFECECE2), surfaceContainerHighest = Color(0xFFE6E7DD),
    outline = Color(0xFFADB7AD), outlineVariant = Color(0xFFE5E8DD)
)
private val Dark = darkColorScheme(
    primary = Color(0xFFA3D4C6), onPrimary = Color(0xFF163F3A),
    primaryContainer = Color(0xFF284C49), onPrimaryContainer = Color(0xFFCEECE2),
    secondary = Color(0xFFE9CD97), onSecondary = Color(0xFF46351D),
    secondaryContainer = Color(0xFF4C402C), onSecondaryContainer = Color(0xFFF1DDB8),
    tertiary = Color(0xFFB2C5E2), tertiaryContainer = Color(0xFF334359), onTertiaryContainer = Color(0xFFDFE8F6),
    background = Color(0xFF171F28), onBackground = Color(0xFFE6EAE7),
    surface = Color(0xFF222D35), onSurface = Color(0xFFE6EAE7),
    surfaceVariant = Color(0xFF34413F), onSurfaceVariant = Color(0xFFABB9B2),
    surfaceContainer = Color(0xFF27333B), surfaceContainerLow = Color(0xFF1E2831),
    surfaceContainerHigh = Color(0xFF2D3B43), surfaceContainerHighest = Color(0xFF34413F),
    outline = Color(0xFF65776E), outlineVariant = Color(0xFF34413F)
)

@Composable fun ShikeTheme(mode: String, showMascot: Boolean = true, moment: LocalDateTime = LocalDateTime.now(),
    companion: HimariMoment = HimariCompanion.at(moment), onHimariShuffle: (() -> Unit)? = null,
    content: @Composable () -> Unit) {
    val dark = mode == "dark" || (mode == "system" && isSystemInDarkTheme())
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            if (Build.VERSION.SDK_INT < 29) {
                @Suppress("DEPRECATION")
                window.navigationBarColor = (if (dark) Dark else Light).surface.toArgb()
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalHimariVisible provides showMascot, LocalHimariTime provides moment,
        LocalHimariMoment provides companion, LocalHimariShuffle provides onHimariShuffle) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, shapes = Shapes(
        small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
    ), typography = Typography(
        headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
        headlineSmall = TextStyle(fontSize = 23.sp, fontWeight = FontWeight.Bold),
        titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
        titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
        bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
        labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
    ), content = content)
    }
}

package com.xterm.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.xterm.data.AppThemeMode
import com.xterm.data.SettingsStore

/**
 * 对齐 Aurora `main.dart`：Material3、seed 为浅色 #1565C0 / 深色 #90CAF9，
 * 主题模式「跟随系统 / 浅色 / 深色」实时切换，并驱动终端页配色。
 */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E3FF),
    onPrimaryContainer = Color(0xFF001B3F),
    secondary = Color(0xFF565E71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF131C2B),
    tertiary = Color(0xFF705575),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFAD8FD),
    onTertiaryContainer = Color(0xFF28132E),
    background = Color(0xFFFDFCFF),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFDFCFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    outline = Color(0xFF74777F),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFBEC6DD),
    onSecondary = Color(0xFF283141),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFFDDBCE0),
    onTertiary = Color(0xFF3F2844),
    tertiaryContainer = Color(0xFF573E5C),
    onTertiaryContainer = Color(0xFFFAD8FD),
    background = Color(0xFF1A1C1E),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF1A1C1E),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
)

/** 卡片底色：等价 Flutter 的 surfaceContainerHighest.withOpacity(0.5)。 */
object AuroraColors {
    val cardLight = Color(0xFFE1E2EC).copy(alpha = 0.5f)
    val cardDark = Color(0xFF44474F).copy(alpha = 0.55f)
    val barLight = Color(0xFFE1E2EC).copy(alpha = 0.4f)
    val barDark = Color(0xFF44474F).copy(alpha = 0.4f)
    val keyBarLight = Color(0xFFF4F4F5)
    val keyBarDark = Color(0xFF2B2B2E)
    val terminalLightBg = Color(0xFFFAFAFA)
    val terminalDarkBg = Color(0xFF1E1E1E)
    val terminalLightFg = Color(0xFF333333)
    val terminalDarkFg = Color(0xFFCCCCCC)
}

@Composable
fun SshTermTheme(content: @Composable () -> Unit) {
    val mode by SettingsStore.instance.settings.collectAsState()
    val dark = when (mode.themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        content = content,
    )
}

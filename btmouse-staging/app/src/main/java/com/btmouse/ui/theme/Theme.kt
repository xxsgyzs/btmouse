package com.btmouse.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 鑫作蓝鼠 —— 应用主题
 *
 * 设计原则：
 *  - 蓝灰主色（#1E6FD9 系），冷静、专业，适合工具类应用
 *  - 提供完整的 light / dark 两套 ColorScheme（含 container / outline 层级），
 *    保证 Material 3 组件在两种模式下都有正确对比度
 *  - 统一大圆角（[AppShapes]），配合卡片阴影形成现代层级感
 *  - Android 12+ 上默认跟随系统取色（Material You），可用 dynamicColor = false 关闭
 */

// ---------------- 浅色 ----------------

private val LightColors = lightColorScheme(
    primary = Color(0xFF1E6FD9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E6FF),
    onPrimaryContainer = Color(0xFF00174A),

    secondary = Color(0xFF4A6088),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE4F7),
    onSecondaryContainer = Color(0xFF04172F),

    tertiary = Color(0xFF00696E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF9CF1F6),
    onTertiaryContainer = Color(0xFF002022),

    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE3E7EF),
    onSurfaceVariant = Color(0xFF43474E),

    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C6CF),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

// ---------------- 深色 ----------------

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF00306B),
    primaryContainer = Color(0xFF00458F),
    onPrimaryContainer = Color(0xFFD8E6FF),

    secondary = Color(0xFFB9C7E3),
    onSecondary = Color(0xFF22304A),
    secondaryContainer = Color(0xFF394761),
    onSecondaryContainer = Color(0xFFDCE4F7),

    tertiary = Color(0xFF80D4DA),
    onTertiary = Color(0xFF003739),
    tertiaryContainer = Color(0xFF004F53),
    onTertiaryContainer = Color(0xFF9CF1F6),

    background = Color(0xFF111418),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF1A1D21),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C6CF),

    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

/** 统一大圆角：卡片、按钮、容器都走这套，形成一致的视觉语言。 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * 应用主题。
 *
 * @param darkTheme 是否使用深色。默认跟随系统。
 * @param dynamicColor Android 12+ 是否采用系统取色（Material You）。默认开启。
 */
@Composable
fun BtMouseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        shapes = AppShapes,
        content = content
    )
}


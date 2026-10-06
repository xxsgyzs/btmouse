package com.btmouse.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 控制页共用的内置矢量图标。
 *
 * 自绘而非引入 material-icons-extended：本项目只需要一个返回箭头，
 * 为一个图标多引一个依赖包不值得。
 *
 * 独立成文件是为了让 [ControlScreen] 与 [SettingsScreen] 都能复用，
 * 避免出现两份重复定义。
 */
internal val BackArrowIcon: ImageVector = ImageVector.Builder(
    name = "BackArrow",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    path(
        fill = SolidColor(Color.Transparent),
        stroke = SolidColor(Color.White),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round
    ) {
        moveTo(19f, 12f); lineTo(5f, 12f)
        moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f)
    }
}.build()


package com.btmouse.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.btmouse.core.state.HidMode
import com.btmouse.ui.MainViewModel

/**
 * ModeSelectScreen —— 应用首页：选择操作模式
 *
 * 三种模式以卡片形式纵向排列，点击卡片进入连接页。
 * 右上角齿轮进入 [SettingsScreen]（灵敏度 / 平滑 / 滚轮手感）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSelectScreen(
    vm: MainViewModel,
    onModeSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSettings by remember { mutableStateOf(false) }

    // 设置页打开时，系统返回键先回到模式选择
    BackHandler(enabled = showSettings) { showSettings = false }

    Crossfade(
        targetState = showSettings,
        animationSpec = tween(260),
        label = "ModeSelectSettings",
        modifier = modifier.fillMaxSize()
    ) { settingsOpen ->
        if (settingsOpen) {
            SettingsScreen(vm = vm, onBack = { showSettings = false })
        } else {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = "鑫作蓝鼠",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "选择操作模式",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { showSettings = true }) {
                                Icon(
                                    imageVector = SettingsIcon,
                                    contentDescription = "设置",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                },
                containerColor = MaterialTheme.colorScheme.background
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HidMode.entries.forEach { mode ->
                        ModeCard(
                            mode = mode,
                            selected = vm.currentMode == mode,
                            onClick = {
                                vm.selectMode(mode)
                                onModeSelected()
                            }
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "提示：三种模式共用同一套 HID 底层。切换模式不会断开蓝牙连接。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
            }
        }
    }
}

/** 单个模式卡片：选中态带描边、勾选角标与轻微放大。 */
@Composable
private fun ModeCard(
    mode: HidMode,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.01f else 1f,
        animationSpec = tween(180),
        label = "ModeCardScale"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) scheme.primaryContainer else scheme.surface,
        tonalElevation = if (selected) 4.dp else 1.dp,
        shadowElevation = if (selected) 6.dp else 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (selected) {
                        Modifier.border(
                            width = 2.dp,
                            color = scheme.primary,
                            shape = RoundedCornerShape(20.dp)
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标底盘
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (selected) scheme.primary else scheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = modeIcon(mode),
                    contentDescription = null,
                    tint = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = mode.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) scheme.onPrimaryContainer else scheme.onSurface
                    )
                    Spacer(Modifier.width(8.dp))
                    Badge(text = mode.badge, selected = selected)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = mode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) {
                        scheme.onPrimaryContainer.copy(alpha = 0.85f)
                    } else {
                        scheme.onSurfaceVariant
                    }
                )

                // 选中态：底部展开"当前使用中"
                AnimatedVisibility(
                    visible = selected,
                    enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                    exit = fadeOut(tween(120))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Icon(
                            imageVector = CheckIcon,
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "当前使用中 · 点击进入连接",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/** 小标签（如"手机当触控板"）。 */
@Composable
private fun Badge(text: String, selected: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) scheme.primary.copy(alpha = 0.18f) else scheme.surfaceVariant
            )
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) scheme.primary else scheme.onSurfaceVariant
        )
    }
}

// ---------------- 内置矢量图标（自绘，避免额外依赖 material-icons-extended） ----------------

private fun modeIcon(mode: HidMode): ImageVector = when (mode) {
    HidMode.TRACKPAD -> TrackpadIcon
    HidMode.DESK -> DeskIcon
    HidMode.AIR -> AirIcon
}

private fun buildIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            pathBuilder = block
        )
    }.build()

/** 触控板：圆角矩形 + 内部滑动轨迹 */
private val TrackpadIcon: ImageVector = buildIcon("Trackpad") {
    moveTo(3f, 6f); lineTo(21f, 6f); lineTo(21f, 18f); lineTo(3f, 18f); close()
    moveTo(7.5f, 14f); lineTo(11.5f, 10f); lineTo(16.5f, 13f)
}

/** 桌面平移：显示器 + 指针箭头 */
private val DeskIcon: ImageVector = buildIcon("Desk") {
    moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 16f); lineTo(3f, 16f); close()
    moveTo(9f, 20f); lineTo(15f, 20f)
    moveTo(12f, 16f); lineTo(12f, 20f)
    moveTo(8f, 9f); lineTo(11f, 12f); lineTo(9.4f, 12.4f); lineTo(10.4f, 14.6f); lineTo(11.6f, 14f)
    lineTo(11f, 12.2f); lineTo(12.6f, 11.8f); close()
}

/** 空中遥控：手机 + 运动弧线 */
private val AirIcon: ImageVector = buildIcon("Air") {
    moveTo(8.5f, 3f); lineTo(15.5f, 3f); lineTo(15.5f, 21f); lineTo(8.5f, 21f); close()
    moveTo(4f, 8.5f); quadTo(2.5f, 12f, 4f, 15.5f)
    moveTo(20f, 8.5f); quadTo(21.5f, 12f, 20f, 15.5f)
}

/** 设置（齿轮简化版） */
private val SettingsIcon: ImageVector = buildIcon("Settings") {
    moveTo(12f, 8.6f); quadTo(15.4f, 8.6f, 15.4f, 12f); quadTo(15.4f, 15.4f, 12f, 15.4f)
    quadTo(8.6f, 15.4f, 8.6f, 12f); quadTo(8.6f, 8.6f, 12f, 8.6f); close()
    moveTo(12f, 2.5f); lineTo(12f, 5.5f)
    moveTo(12f, 18.5f); lineTo(12f, 21.5f)
    moveTo(2.5f, 12f); lineTo(5.5f, 12f)
    moveTo(18.5f, 12f); lineTo(21.5f, 12f)
    moveTo(5.3f, 5.3f); lineTo(7.4f, 7.4f)
    moveTo(16.6f, 16.6f); lineTo(18.7f, 18.7f)
    moveTo(18.7f, 5.3f); lineTo(16.6f, 7.4f)
    moveTo(7.4f, 16.6f); lineTo(5.3f, 18.7f)
}

/** 勾选 */
private val CheckIcon: ImageVector = buildIcon("Check") {
    moveTo(4.5f, 12.5f); lineTo(9.5f, 17.5f); lineTo(19.5f, 6.5f)
}


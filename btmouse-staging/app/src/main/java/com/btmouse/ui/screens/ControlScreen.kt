package com.btmouse.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.btmouse.core.state.HidMode
import com.btmouse.ui.MainViewModel

/**
 * ControlScreen —— 控制页入口，按当前 [HidMode] 分派到对应的交互层
 *
 *  - [HidMode.TRACKPAD] → [TrackpadScreen]（整屏触控板）
 *  - [HidMode.DESK] / [HidMode.AIR] → 顶部左右键 + 底部滚轮条（体感布局）
 *
 * 说明：模式二/三的**传感器驱动**在 BATCH-3/BATCH-4 接入
 * （com.btmouse.core.sensors.SensorMouseController）。
 * 本批次先呈现完整布局与按键（已可用）；滚轮条的**实际发送**需 BluetoothHidManager
 * 透传 wheel 参数，与双指手势一并在 BATCH-3 接入。
 */
@Composable
fun ControlScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    onExit: () -> Unit = {}
) {
    val mode = vm.currentMode

    if (mode == HidMode.TRACKPAD) {
        TrackpadScreen(vm = vm, modifier = modifier, onExit = onExit)
        return
    }

    SensorModeLayout(vm = vm, mode = mode, modifier = modifier, onExit = onExit)
}

/**
 * 模式二/三共用的操作布局：
 *
 *   ┌──────────────────────────────────────┐
 *   │  [ 左键 ]      滚轮        [ 右键 ]  │  ← 顶部操作条
 *   ├──────────────────────────────────────┤
 *   │                                      │
 *   │            平放 / 悬空 提示           │  ← 中央提示区
 *   │                                      │
 *   ├──────────────────────────────────────┤
 *   │            上下滚动条                 │  ← 底部滚轮
 *   └──────────────────────────────────────┘
 */
@Composable
private fun SensorModeLayout(
    vm: MainViewModel,
    mode: HidMode,
    modifier: Modifier = Modifier,
    onExit: () -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // ---------------- 顶部操作条 ----------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyButton(
                label = "左键",
                modifier = Modifier.weight(1f),
                onClickPressed = { vm.setLeftPressed(it) }
            )

            // 中间：滚轮指示（装饰；实际滚动在底部滚轮条操作）
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(0.7f)
            ) {
                WheelGlyph(
                    color = scheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                )
                Text(
                    text = "滚轮",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
            }

            KeyButton(
                label = "右键",
                modifier = Modifier.weight(1f),
                onClickPressed = { vm.setRightPressed(it) }
            )

            IconButton(onClick = onExit) {
                Icon(
                    imageVector = CloseIcon,
                    contentDescription = "退出控制页",
                    tint = scheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------------- 中央提示区 ----------------
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(scheme.surfaceVariant, RoundedCornerShape(20.dp))
                .border(2.dp, scheme.primary.copy(alpha = 0.4f), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    text = mode.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when (mode) {
                        HidMode.DESK -> "把手机平放在桌面上推拉\n像物理鼠标一样移动光标"
                        HidMode.AIR -> "手持手机悬空\n转动手腕控制光标方向"
                        HidMode.TRACKPAD -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "体感驱动开发中 · 顶部按键与滚轮已可用",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.primary,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------------- 底部滚轮条 ----------------
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "上下滑动滚动页面（BATCH-3 接入）",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            // 滚轮发送将在 BATCH-3 接入（需 BluetoothHidManager 透传 wheel 参数）。
            // 本批次先呈现完整布局，暂不接收手势，避免留下无效入口。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(scheme.primaryContainer, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                WheelGlyph(
                    color = scheme.onPrimaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                )
            }
        }
    }
}

/**
 * 滚轮图标：Canvas 手绘（上下双箭头），避免引入 material-icons-extended 依赖。
 */
@Composable
private fun WheelGlyph(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val strokeWidth = size.height * 0.12f
        val arm = size.height * 0.28f

        // 上箭头
        drawLine(
            color = color,
            start = Offset(cx - arm, size.height * 0.30f),
            end = Offset(cx, size.height * 0.06f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(cx, size.height * 0.06f),
            end = Offset(cx + arm, size.height * 0.30f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        // 下箭头
        drawLine(
            color = color,
            start = Offset(cx - arm, size.height * 0.70f),
            end = Offset(cx, size.height * 0.94f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(cx, size.height * 0.94f),
            end = Offset(cx + arm, size.height * 0.70f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}


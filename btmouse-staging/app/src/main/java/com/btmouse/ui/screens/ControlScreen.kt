package com.btmouse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.btmouse.core.state.HidMode
import com.btmouse.ui.MainViewModel

/**
 * ControlScreen —— 控制页（模式一/二/三共用同一套骨架）
 *
 * 布局：
 *   ┌──────────────────────────────────────┐
 *   │ ← 模式名 / 设备名                      │  TopAppBar（返回箭头走 onExit）
 *   ├──────────────────────────────────────┤
 *   │ [左键]      ↕滚轮条       [右键]       │  操作栏（zIndex 提升命中优先级）
 *   ├──────────────────────────────────────┤
 *   │                                      │
 *   │        手势区 / 提示区                 │
 *   │                                      │
 *   └──────────────────────────────────────┘
 *
 * ██ BATCH-5.1 说明 ██
 *
 *  1. **返回箭头**：`navigationIcon` 的 `IconButton(onClick = onExit)` 直接把回调交给
 *     MainActivity 的自实现导航（`onExit = { screen = AppScreen.CONNECT }`）。
 *     本页手势区此前会过早消费事件，导致箭头点不动 —— 已在 TrackpadScreen 中
 *     改为只在 `PointerEventPass.Final`、且仅在实际拖动后消费，从结构上让出优先权。
 *
 *  2. **操作栏命中优先级**：给操作栏加 zIndex 并在 Column 中**先声明手势区、
 *     后声明操作栏**。Compose 的命中测试对同层兄弟按声明顺序倒序处理，
 *     加上 zIndex 后按钮的命中优先级被显式抬到最高，不再依赖隐式顺序。
 *
 *  3. 原先右上角的 × 按钮已废除，退出统一走左上角返回箭头。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    onExit: () -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    val mode = vm.currentMode

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = mode.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = vm.connectedDevice?.name ?: "未连接设备",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    // 直接绑定 onExit（MainActivity 传入：回到连接页）
                    IconButton(onClick = onExit) {
                        Icon(
                            imageVector = BackArrowIcon,
                            contentDescription = "返回",
                            tint = scheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surface)
            )
        },
        containerColor = scheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp)
        ) {
            // ---------------- 手势区 / 提示区（先声明 → 层级在下）----------------
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (mode == HidMode.TRACKPAD) {
                    TrackpadScreen(vm = vm, modifier = Modifier.fillMaxSize())
                } else {
                    SensorHintArea(mode = mode, modifier = Modifier.fillMaxSize())
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---------------- 操作栏（后声明 + zIndex → 命中优先级最高）----------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KeyButton(
                    label = "左键",
                    modifier = Modifier.weight(1f),
                    onClickPressed = { vm.setLeftPressed(it) }
                )

                RollerStrip(
                    onScroll = { dy -> vm.submitScroll(0f, dy) },
                    modifier = Modifier.weight(1f)
                )

                KeyButton(
                    label = "右键",
                    modifier = Modifier.weight(1f),
                    onClickPressed = { vm.setRightPressed(it) }
                )
            }
        }
    }
}

/**
 * 模式二/三的中央提示区。
 *
 * 纯展示、**不接收任何手势**——这样它绝不会与操作栏竞争事件。
 */
@Composable
private fun SensorHintArea(
    mode: HidMode,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .background(scheme.surfaceVariant, RoundedCornerShape(20.dp))
            .border(2.dp, scheme.primary.copy(alpha = 0.35f), RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = mode.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = when (mode) {
                    HidMode.DESK -> "手机平放在桌面\n轻轻转动手机，光标随之滑动\n停手即停，无需回中"
                    HidMode.AIR -> "手持手机悬空\n转动手腕控制光标方向\n摆正手机自动停止"
                    HidMode.TRACKPAD -> ""
                },
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant.copy(alpha = 0.85f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "陀螺仪体感 · 顶栏按键与滚轮可用",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.primary,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 可拖动的滚轮条：上下拖动即滚动页面。
 *
 * 与触控板双指滚轮走同一条通路（MainViewModel.submitScroll），
 * 因此设置页里调的"滚轮速度"对所有滚轮入口同时生效。
 */
@Composable
internal fun RollerStrip(
    onScroll: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .height(72.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.primaryContainer)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onScroll(dragAmount.y)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "▲",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onPrimaryContainer
            )
            Text(
                text = "滚轮",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "▼",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onPrimaryContainer
            )
        }
    }
}

/**
 * 按下/抬起型按键：按下发送 down，抬起发送 up，与物理鼠标一致。
 *
 * 热区 76dp：远大于 Material 建议的 48dp 最小触摸目标，便于盲按。
 */
@Composable
internal fun KeyButton(
    label: String,
    modifier: Modifier = Modifier,
    onClickPressed: (Boolean) -> Unit
) {
    Box(
        modifier = modifier
            .height(76.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary)
            .pointerInput(label) {
                detectTapGestures(
                    onPress = {
                        onClickPressed(true)        // 按下：发送 down
                        tryAwaitRelease()
                        onClickPressed(false)       // 抬起：发送 up
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}


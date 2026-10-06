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
import com.btmouse.core.state.HidMode
import com.btmouse.ui.MainViewModel

/**
 * ControlScreen —— 控制页（模式一/二/三共用同一套骨架）
 *
 * ██ BATCH-5 重构 ██
 *
 *  1. **顶部改为 Scaffold + TopAppBar**：左侧「← 模式名」，与设置页样式统一；
 *     **废除原先右上角的 × 按钮**（位置反直觉、且与顶边过近容易点不中）。
 *     退出统一走左上角返回箭头，系统返回键行为不变。
 *
 *  2. **操作栏下移到 TopAppBar 之下的第一行**：左键 / 滚轮条 / 右键 三件横向排布，
 *     热区分别为 76dp / 72dp，滚轮条可拖动。
 *
 *  3. **删除底部冗余的大滚轮条**：原先底部还有一条 72dp 滚轮条，
 *     与顶部滚轮功能重复且占据大片空间，现已移除（滚轮改由顶部滚轮条承担）。
 *
 *  4. **点击失效的修复思路**：把「按钮区」与「手势区」做成**互不重叠的兄弟节点**。
 *     Compose 的命中测试只把事件派发给最深命中的那个 pointerInput 节点，
 *     因此只要手势区不覆盖按钮区，按钮就不可能被"吃掉"。
 *     同时**不再使用 PointerEventPass.Main 抢事件**的写法，避免与子节点竞争。
 *
 * 布局：
 *   ┌──────────────────────────────────────┐
 *   │ ← 触控板模式 / 设备名                  │  TopAppBar
 *   ├──────────────────────────────────────┤
 *   │ [左键]      ↕滚轮条       [右键]       │  操作栏
 *   ├──────────────────────────────────────┤
 *   │                                      │
 *   │          手势区 / 提示区               │
 *   │                                      │
 *   └──────────────────────────────────────┘
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
            // ---------------- 操作栏：左键 / 滚轮条 / 右键 ----------------
            Row(
                modifier = Modifier.fillMaxWidth(),
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

            Spacer(Modifier.height(12.dp))

            // ---------------- 手势区（模式一）/ 提示区（模式二、三） ----------------
            if (mode == HidMode.TRACKPAD) {
                TrackpadScreen(
                    vm = vm,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            } else {
                SensorHintArea(
                    mode = mode,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }
        }
    }
}

/**
 * 模式二/三的中央提示区。
 *
 * 纯展示、**不接收任何手势**——这样它绝不会与上方操作栏竞争事件。
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
 * 与触控板双指滚轮走同一条通路（[MainViewModel.submitScroll]），
 * 因此设置页里调的"滚轮速度"对所有滚轮入口同时生效。
 *
 * 说明：使用 [detectDragGestures]，它在手指移动超过 touchSlop 后才开始上报，
 * 因此**不会吞掉**落在其上的点击类事件。
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


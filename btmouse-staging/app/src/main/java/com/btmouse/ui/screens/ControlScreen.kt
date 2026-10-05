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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
 * ControlScreen —— 控制页入口，按当前 [HidMode] 分派到对应的交互层
 *
 *  - [HidMode.TRACKPAD] → [TrackpadScreen]（整屏触控板）
 *  - [HidMode.DESK] / [HidMode.AIR] → 顶部操作栏 + 中央提示 + 底部滚轮条（体感布局）
 *
 * ██ BATCH-3.5 修复 ██
 *
 *  1. **安全区（根因修复）**：整个控制页套 [statusBarsPadding] + [navigationBarsPadding]。
 *     原先顶部按钮紧贴屏幕物理边缘，手指按上去很容易被判为"下拉状态栏"手势，
 *     于是按钮收不到事件——这正是真机上"按钮几乎点不动"的主因，而不是事件被拦截。
 *
 *  2. **热区加大**：左右键 76dp 高、滚轮条 72dp 高、退出按钮 48dp 圆形热区（均为实际触摸区域）。
 *
 *  3. **顶部滚轮从"装饰"变成"可用"**：BATCH-3 里顶部中间那块只是一个
 *     **不能点的图标**（真机反馈"滚轮点了没反应"就是它）。
 *     现替换为真正可拖动的 [RollerStrip]，与底部滚轮条、触控板双指滚轮共用同一套参数。
 *
 *  关于"边缘防误触"：我先尝试过在触摸处理里拒绝屏幕最外圈，但实测逻辑上会**吞掉合法手势**
 *  （用户本来就可能在边缘起手），且 [statusBarsPadding] 已经从根上消除了与系统栏的冲突，
 *  因此没有叠加这一层，避免引入新的"点了没反应"。
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
 *   │  （系统状态栏安全区）                  │
 *   ├──────────────────────────────────────┤
 *   │ [左键]     ↕滚轮条     [右键]   (×)   │  ← 顶部操作栏
 *   ├──────────────────────────────────────┤
 *   │                                      │
 *   │            操作提示区                 │
 *   │                                      │
 *   ├──────────────────────────────────────┤
 *   │          ↕ 底部滚轮条                 │
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
            // 关键修复：给系统状态栏与手势导航栏留出安全区
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // ---------------- 顶部操作栏 ----------------
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

            // 顶部滚轮条：可拖动滚动（原先这里是不能点的装饰图标）
            RollerStrip(
                onScroll = { dy -> vm.submitScroll(0f, dy) },
                modifier = Modifier.weight(1f)
            )

            KeyButton(
                label = "右键",
                modifier = Modifier.weight(1f),
                onClickPressed = { vm.setRightPressed(it) }
            )

            ExitButton(onExit = onExit)
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
                        HidMode.DESK -> "手机平放在桌面\n轻轻转动手机，光标随之滑动\n停手即停，无需回中"
                        HidMode.AIR -> "手持手机悬空\n转动手腕控制光标方向\n摆正手机自动停止"
                        HidMode.TRACKPAD -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "陀螺仪体感 · 顶部按键与滚轮已可用",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.primary,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------------- 底部滚轮条（大热区，方便连续拖动） ----------------
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "上下滑动滚动页面",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            RollerStrip(
                onScroll = { dy -> vm.submitScroll(0f, dy) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
            )
        }
    }
}

/**
 * 可拖动的滚轮条：上下拖动即滚动页面。
 *
 * 与触控板双指滚轮走同一条通路（[MainViewModel.submitScroll]），参数完全一致。
 * 因此设置页里调的"滚轮速度"对三种入口同时生效。
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
 * 退出按钮：48dp 圆形热区（大于视觉图标，便于点中）。
 */
@Composable
internal fun ExitButton(onExit: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(scheme.surfaceVariant)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onExit() })
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = CloseIcon,
            contentDescription = "退出控制页",
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}


package com.btmouse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.btmouse.ui.MainViewModel
import kotlin.math.sqrt

/**
 * TrackpadScreen —— 模式一：触控板
 *
 * 布局：
 *   ┌──────────────────────────────────────┐
 *   │  （系统状态栏安全区）                  │
 *   │  触控板 / 设备名              [ × ]  │  顶部状态条
 *   ├──────────────────────────────────────┤
 *   │  [左键]       ↕滚轮条       [右键]    │  顶部操作栏
 *   ├──────────────────────────────────────┤
 *   │                                      │
 *   │             整屏滑动区                │  单指 = 移动光标
 *   │          （双指 = 滚轮）              │  双指 = 滚动滚轮
 *   │                                      │
 *   └──────────────────────────────────────┘
 *
 * ██ BATCH-3.5 修复 ██
 *  · 整页套 [statusBarsPadding] + [navigationBarsPadding]：顶部不再紧贴物理边缘，
 *    避免按钮触摸被系统状态栏手势抢走（真机反馈"几乎点不动"的主因）。
 *  · 顶部按钮热区 76dp、滚轮条 72dp、退出按钮 48dp 圆形热区。
 *  · 滚轮条与退出按钮复用 ControlScreen 里的共享实现，两种布局体验一致。
 *
 * ██ 手势实现要点（为什么不用 detectDragGestures）██
 *   detectDragGestures 只能识别"单个拖动"，无法区分按下了几根手指。
 *   因此这里用 awaitPointerEventScope 手动遍历 `changes`（每个元素 = 一根手指）：
 *     - 按下 1 根 → 光标移动（交给 TouchInputHandler 的自适应滤波）
 *     - 按下 ≥2 根 → 滚轮（交给 TouchInputHandler 的滚轮映射）
 *   在 AwaitPointerEventScope 中同步处理，不丢帧、不引入额外延迟。
 *
 * @param onExit 退出控制页（由 ControlScreen 传入）
 */
@Composable
fun TrackpadScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    onExit: () -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // ---------------- 顶部状态条 ----------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "触控板",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onBackground
                )
                Text(
                    text = vm.connectedDevice?.name ?: "未连接设备",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
            ExitButton(onExit = onExit)
        }

        Spacer(Modifier.height(8.dp))

        // ---------------- 顶部操作栏：左键 / 滚轮条 / 右键 ----------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TapKeyButton(
                label = "左键",
                modifier = Modifier.weight(1f),
                onClickPressed = { vm.setLeftPressed(it) }
            )

            RollerStrip(
                onScroll = { dy -> vm.submitScroll(0f, dy) },
                modifier = Modifier.weight(1f)
            )

            TapKeyButton(
                label = "右键",
                modifier = Modifier.weight(1f),
                onClickPressed = { vm.setRightPressed(it) }
            )
        }

        Spacer(Modifier.height(10.dp))

        // ---------------- 整屏触控区 ----------------
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(scheme.surfaceVariant, RoundedCornerShape(20.dp))
                .border(2.dp, scheme.primary.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .pointerInput(Unit) {
                    val touchSlop = viewConfiguration.touchSlop
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitPointerEvent(PointerEventPass.Main)
                            if (down.changes.none { it.pressed }) continue

                            // 移动超过 touchSlop 之前先不动，避免"按下即抖"
                            var started = false

                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val changes = event.changes.filter { it.pressed }
                                if (changes.isEmpty()) break

                                // changes.size 即当前按下的手指数 → 决定语义
                                val scrollMode = changes.size >= 2

                                var dx = 0f
                                var dy = 0f
                                for (change in changes) {
                                    val delta = change.positionChange()
                                    if (!started) {
                                        val dist = sqrt(delta.x * delta.x + delta.y * delta.y)
                                        if (dist > touchSlop) started = true
                                    }
                                    if (started) {
                                        dx += delta.x
                                        dy += delta.y
                                    }
                                    // 必须消费：否则事件会被上层当作滚动/拖拽抢走
                                    change.consume()
                                }

                                if (!started || (dx == 0f && dy == 0f)) continue

                                if (scrollMode) {
                                    vm.submitScroll(dx, dy)
                                } else {
                                    vm.submitMove(dx, dy)
                                }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "在此滑动控制鼠标",
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "单指移动光标 · 双指滑动滚轮",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * 触控板顶部专用的按压型按键（76dp 热区）。
 *
 * 与 [KeyButton] 的区别：这里顶部一行要放三个控件，宽度较窄，
 * 因此保持与 [KeyButton] 一致的 76dp 高度，保证热区足够大。
 */
@Composable
private fun TapKeyButton(
    label: String,
    modifier: Modifier = Modifier,
    onClickPressed: (Boolean) -> Unit
) {
    Box(
        modifier = modifier
            .height(76.dp)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
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

/**
 * 按下/抬起型按钮（标准尺寸 76dp），供体感布局（ControlScreen）复用。
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
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
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

// ---------------- 内置矢量图标 ----------------

/** 关闭（退出控制页） */
internal val CloseIcon: ImageVector = ImageVector.Builder(
    name = "Close",
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
        moveTo(6.5f, 6.5f); lineTo(17.5f, 17.5f)
        moveTo(17.5f, 6.5f); lineTo(6.5f, 17.5f)
    }
}.build()

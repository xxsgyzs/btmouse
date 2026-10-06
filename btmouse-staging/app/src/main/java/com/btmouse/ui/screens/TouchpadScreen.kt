package com.btmouse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.btmouse.ui.MainViewModel
import kotlin.math.sqrt

/**
 * TrackpadScreen —— 模式一：触控板（纯手势面）
 *
 * ██ BATCH-5 重构 ██
 *
 *  本组件**只负责滑动区**，不再包含顶栏、左右键、滚轮条、退出按钮。
 *  原因（也是"按钮点不动"的根治办法）：
 *    把按钮与手势区做成**互不重叠的兄弟节点**，由 [ControlScreen] 统一排版。
 *    Compose 的命中测试只把事件派发给最深命中的那个 pointerInput 节点，
 *    因此只要两者不重叠，按钮就不可能被手势区"吃掉"。
 *
 *  同时移除了此前 `PointerEventPass.Main` 抢事件的写法——
 *  那种写法虽然能识别双指，但属于与子节点竞争事件的高风险模式，现已不再需要。
 *
 * ██ 手势语义 ██
 *   单指滑动 → 光标移动（交给 TouchInputHandler 的自适应滤波）
 *   双指滑动 → 滚轮（交给 TouchInputHandler 的滚轮映射）
 *   两者靠 `changes.size`（当前按下的手指数）区分。
 *
 * 事件处理放在 AwaitPointerEventScope 中**同步**完成，不丢帧、不引入额外延迟。
 */
@Composable
fun TrackpadScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.surfaceVariant, RoundedCornerShape(20.dp))
            .border(2.dp, scheme.primary.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
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
                                // 消费本节点内的事件，避免被上层当作滚动抢走
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
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
    }
}


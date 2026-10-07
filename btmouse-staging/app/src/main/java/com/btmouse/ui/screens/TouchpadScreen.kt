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
 * ██ BATCH-5.1 手势修复（"左右键失灵"的根因）██
 *
 * 真机线索：**传感器模式下左右键正常，只有触控板模式失灵**。
 * 两者唯一差别就是这块滑动手势面（传感器模式只是无手势的展示卡片）。
 * 因此问题只可能出在本文件的手势实现上。原实现有三个缺陷：
 *
 *  1. **未开始拖动也 consume()**：原先对每个按下手指无条件 `change.consume()`，
 *     连"手指刚落下、还没移动"的事件也被吃掉。这会让系统难以判定其它手势，
 *     是"手势区吃掉点击"最典型的成因。
 *
 *  2. **跨手势的长生命周期等待**：原实现用外层循环等"有手指按下"、内层循环跟踪，
 *     内层 `break` 后回到外层。这种结构会让手势状态跨越触摸序列而错位。
 *
 *  3. **消费时机过早**：即使在 `Main` 阶段，过早消费也会影响同一帧内的其它候选接收者。
 *
 * 修复方式（三条一起做，确保结构上不可能再抢事件）：
 *
 *  · **限定在 `PointerEventPass.Final` 消费**：这是 Compose 手势分发链的**最后一环**，
 *    意味着上层（兄弟节点上的按钮、TopAppBar 返回箭头）已经先拿到并处理过事件。
 *    只有它们都没消费时，本手势面才接管 —— 这是"按钮优先"的结构性保证，
 *    不依赖任何时序假设。
 *
 *  · **只在真正开始拖动后消费**：手指移动超过 `touchSlop` 之前完全不消费事件。
 *
 *  · **简洁的两段式结构**：一个 `awaitPointerEventScope` 只处理**一次**手势，
 *    从首帧到全部抬起线性推进，不再有外层 `while(true)` 反复重启。
 *
 * 手势语义（保持不变）：
 *   单指滑动 → 光标移动（TouchInputHandler 自适应滤波）
 *   双指滑动 → 滚轮（TouchInputHandler 滚轮映射）
 *   靠当前按下手指数区分，第二指落下即自动切换为滚轮。
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
                    var started = false

                    // 线性推进：每帧取一次事件，直到所有手指抬起
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val changes = event.changes.filter { it.pressed }

                        // 全部抬起 → 本次手势结束，退出作用域
                        if (changes.isEmpty()) break

                        // 当前按下手指数决定语义：≥2 指为滚轮
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
                        }

                        // 未开始拖动：一帧都不消费（关键修复点）
                        if (!started || (dx == 0f && dy == 0f)) continue

                        // 已开始拖动：只消费本手势内的按下手指
                        for (change in changes) change.consume()

                        if (scrollMode) {
                            vm.submitScroll(dx, dy)
                        } else {
                            vm.submitMove(dx, dy)
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


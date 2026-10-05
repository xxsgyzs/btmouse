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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.btmouse.ui.MainViewModel

/**
 * TrackpadScreen —— 模式一：触控板
 *
 * 整屏滑动区控制光标，底部左右键。位移经 detectDragGestures 得到像素增量 →
 * vm.submitMove()（走 TouchInputHandler 自适应滤波 + SendQueue 自适应节拍）。
 *
 * 手势说明：
 *  - 单指拖动 → 光标移动
 *  - 双指滑动 → 滚轮（BATCH-3 接入：改用 awaitPointerEventScope 统计按压点数，
 *    当 pointerCount >= 2 时把竖直位移交给滚轮通道）
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
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // 顶部状态条
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
            IconButton(onClick = onExit) {
                Icon(
                    imageVector = CloseIcon,
                    contentDescription = "退出控制页",
                    tint = scheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 触控区：拖动即产生位移
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(scheme.surfaceVariant, RoundedCornerShape(20.dp))
                .border(2.dp, scheme.primary.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .pointerInput(Unit) {
                    // BATCH-3 会替换为 awaitPointerEventScope 以支持双指滚轮
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        vm.submitMove(dragAmount.x, dragAmount.y)
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
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "双指滑动 = 滚轮（即将支持）",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 左右键
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyButton(
                label = "左键",
                modifier = Modifier.weight(1f),
                onClickPressed = { vm.setLeftPressed(it) }
            )
            KeyButton(
                label = "右键",
                modifier = Modifier.weight(1f),
                onClickPressed = { vm.setRightPressed(it) }
            )
        }
    }
}

/**
 * 按下/抬起型按钮：按下发送 down，抬起发送 up，与物理鼠标一致。
 * 供触控板与体感模式共用。
 */
@Composable
internal fun KeyButton(
    label: String,
    modifier: Modifier = Modifier,
    onClickPressed: (Boolean) -> Unit
) {
    Box(
        modifier = modifier
            .height(72.dp)
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


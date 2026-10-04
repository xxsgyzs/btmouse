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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.btmouse.ui.MainViewModel

/**
 * 触控板主页面：整屏可滑动控制鼠标，底部左右键按钮，顶部连接状态与断开。
 * 位移经 detectDragGestures 得到像素增量 → vm.submitMove（走 TouchInputHandler 滤波 + SendQueue 节流）。
 */
@Composable
fun TouchpadScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        // 顶部状态栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "触控板 · ${vm.connectedDevice?.name ?: ""}",
                style = MaterialTheme.typography.titleMedium
            )
            Button(onClick = { vm.disconnect() }) {
                Text("断开")
            }
        }

        Spacer(Modifier.height(12.dp))

        // 触控区：拖动即产生位移
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                .border(2.dp, accent, RoundedCornerShape(16.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        vm.submitMove(dragAmount.x, dragAmount.y)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "在此滑动控制鼠标",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(16.dp))

        // 左右键
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyButton("左键", onClickPressed = { vm.setLeftPressed(it) })
            KeyButton("右键", onClickPressed = { vm.setRightPressed(it) })
        }
    }
}

/**
 * 按下/抬起型按钮：支持按住时持续发送，抬起发送 up。
 */
@Composable
private fun KeyButton(
    label: String,
    modifier: Modifier = Modifier,
    onClickPressed: (Boolean) -> Unit
) {
    Box(
        modifier = modifier
            .size(120.dp, 72.dp)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
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
        Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}
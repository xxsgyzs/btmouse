package com.btmouse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.btmouse.ui.MainViewModel
import com.btmouse.ui.screens.ConnectionScreen
import com.btmouse.ui.screens.TouchpadScreen
import com.btmouse.ui.theme.BtMouseTheme

/**
 * Compose 入口 Activity。
 * 阶段一：连接页 ↔ 触控板页，由连接状态驱动切换。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BtMouseTheme {
                App()
            }
        }
    }
}

@Composable
private fun App(vm: MainViewModel = viewModel()) {
    val connected = vm.connectedDevice != null
    Surface(modifier = Modifier.fillMaxSize()) {
        if (connected) {
            TouchpadScreen(vm = vm)
        } else {
            ConnectionScreen(vm = vm)
        }
    }
}
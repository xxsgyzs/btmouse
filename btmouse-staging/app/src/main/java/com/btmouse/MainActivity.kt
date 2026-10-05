package com.btmouse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.btmouse.ui.MainViewModel
import com.btmouse.ui.screens.ConnectionScreen
import com.btmouse.ui.screens.ControlScreen
import com.btmouse.ui.screens.ModeSelectScreen
import com.btmouse.ui.theme.BtMouseTheme

/**
 * Compose 入口 Activity。
 *
 * 导航结构（三级）：
 *   模式选择（首页） → 连接页 → 控制页（按所选模式渲染不同布局）
 *
 * 采用自实现的轻量导航（[AppScreen] + [AnimatedContent]），不引入 navigation-compose 依赖：
 * 本应用只有三个页面、无深链接需求，自实现更省体积也更好控制过渡动画。
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

/** 顶层页面标识。由浅入深：MODES < CONNECT < CONTROL，用于决定过渡方向。 */
private enum class AppScreen(val depth: Int) {
    MODES(0),
    CONNECT(1),
    CONTROL(2)
}

@Composable
private fun App(vm: MainViewModel = viewModel()) {
    // rememberSaveable：屏幕旋转 / 进程重建后仍停留在当前页面
    var screen by rememberSaveable { mutableStateOf(AppScreen.MODES) }

    // 从控制页返回时回到连接页；从连接页返回时回到模式选择
    BackHandler(enabled = screen != AppScreen.MODES) {
        screen = when (screen) {
            AppScreen.CONTROL -> AppScreen.CONNECT
            AppScreen.CONNECT -> AppScreen.MODES
            AppScreen.MODES -> AppScreen.MODES
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                val forward = targetState.depth > initialState.depth
                val duration = 280
                if (forward) {
                    // 前进：新页从右侧滑入，旧页向左淡出
                    (slideInHorizontally(tween(duration)) { full -> full } + fadeIn(tween(duration)))
                        .togetherWith(
                            slideOutHorizontally(tween(duration)) { full -> -full / 4 } +
                                fadeOut(tween(duration))
                        )
                } else {
                    // 返回：新页从左侧滑入，旧页向右淡出
                    (slideInHorizontally(tween(duration)) { full -> -full / 4 } + fadeIn(tween(duration)))
                        .togetherWith(
                            slideOutHorizontally(tween(duration)) { full -> full } +
                                fadeOut(tween(duration))
                        )
                }
            },
            label = "AppNavigation"
        ) { current ->
            when (current) {
                AppScreen.MODES -> ModeSelectScreen(
                    vm = vm,
                    onModeSelected = { screen = AppScreen.CONNECT }
                )

                AppScreen.CONNECT -> ConnectionScreen(
                    vm = vm,
                    onConnected = { screen = AppScreen.CONTROL },
                    onBack = { screen = AppScreen.MODES }
                )

                AppScreen.CONTROL -> ControlScreen(
                    vm = vm,
                    onExit = { screen = AppScreen.CONNECT }
                )
            }
        }
    }
}

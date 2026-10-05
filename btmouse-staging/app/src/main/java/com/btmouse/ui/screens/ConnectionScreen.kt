package com.btmouse.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.btmouse.ui.MainViewModel
import com.btmouse.util.PermissionHelper

/**
 * ConnectionScreen —— 连接页
 *
 * 请求蓝牙/通知权限，展示已配对设备并连接。连接成功后通过 [onConnected]
 * 通知上层导航切到控制页（由 MainActivity 驱动过渡动画）。
 *
 * @param onConnected 已连上远端设备时调用（由连接状态变化驱动，而非点击瞬间）
 * @param onBack      返回模式选择页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    vm: MainViewModel,
    modifier: Modifier = Modifier,
    onConnected: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme

    // 请求蓝牙(SCAN/CONNECT/ADVERTISE) + Android13+ 通知权限
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (PermissionHelper.hasAll(context)) {
            // 授权成功：此时才能安全启动前台服务与 HID 注册（否则 Android 12+ 会 SecurityException 崩溃）
            vm.onBluetoothPermissionGranted()
        }
    }

    // 首次进入：若缺少权限则发起申请
    LaunchedEffect(Unit) {
        val missing = PermissionHelper.missingPermissions(context)
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            vm.startBluetoothStack()
            vm.refreshBonded()
        }
    }

    // 连接成功后自动进入控制页（connectedDevice 由 HID 回调异步更新）
    LaunchedEffect(vm.connectedDevice) {
        if (vm.connectedDevice != null) onConnected()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "连接电脑",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = vm.currentMode.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                .padding(horizontal = 16.dp)
        ) {
            // ---------------- 状态概览 ----------------
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = scheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    StatusRow("蓝牙服务就绪", vm.profileReady)
                    StatusRow("已注册为鼠标", vm.appRegistered)
                    StatusRow("已连接设备", vm.connectedDevice != null)

                    if (!vm.profileReady) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "正在获取 HID 服务，若长时间未就绪请检查蓝牙是否开启。",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "选择电脑设备",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onBackground
            )
            Spacer(Modifier.height(8.dp))

            if (vm.bondedDevices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无已配对设备\n请先在系统蓝牙设置中与电脑完成配对",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(vm.bondedDevices, key = { it.address }) { device ->
                        DeviceCard(
                            name = device.name ?: device.address,
                            address = device.address,
                            connected = vm.connectedDevice?.address == device.address,
                            onClick = { vm.connect(device) }
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (ok) scheme.primary else scheme.outlineVariant)
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (ok) FontWeight.Medium else FontWeight.Normal,
            color = if (ok) scheme.onSurface else scheme.onSurfaceVariant
        )
        if (ok) {
            Spacer(Modifier.size(6.dp))
            Text(
                text = "就绪",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.primary
            )
        }
    }
}

@Composable
private fun DeviceCard(
    name: String,
    address: String,
    connected: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (connected) scheme.primaryContainer else scheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (connected) 4.dp else 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (connected) scheme.onPrimaryContainer else scheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = address,
                style = MaterialTheme.typography.bodySmall,
                color = if (connected) {
                    scheme.onPrimaryContainer.copy(alpha = 0.8f)
                } else {
                    scheme.onSurfaceVariant
                }
            )
            if (connected) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "● 已连接 · 点击进入控制页",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// ---------------- 内置矢量图标 ----------------

private fun connectionIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            pathBuilder = block
        )
    }.build()

private val BackArrowIcon: ImageVector = connectionIcon("BackArrow") {
    moveTo(19f, 12f); lineTo(5f, 12f)
    moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f)
}

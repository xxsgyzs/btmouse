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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.btmouse.ui.MainViewModel
import com.btmouse.util.PermissionHelper

/**
 * ConnectionScreen —— 连接页
 *
 * ██ BATCH-5.1 关键修复 ██
 *
 *  1. **权限判定与「通知权限」解耦**（本页灰点的真正原因）
 *     原实现用 `PermissionHelper.hasAll()` 作为放行条件，而它把 POST_NOTIFICATIONS
 *     也算在内。Android 13+ 上只要用户拒绝通知权限，该判断恒为 false，
 *     授权回调里的 `onBluetoothPermissionGranted()` 就永不执行，
 *     **registerApp 永远不会被调用** → "已注册为鼠标"永远灰着。
 *     现在改为只看蓝牙必需权限（`hasRequiredBluetooth`）。
 *
 *  2. **明确告知连接方向**（"点设备没反应"的真正原因）
 *     蓝牙 HID 设备**无法主动连接主机**：点击设备只是发出请求，真正建立连接
 *     必须由电脑侧发起。因此光有点击反馈是不够的，页面必须把这件事讲清楚，
 *     否则用户永远以为按钮坏了。现新增 HidHintCard 明确指引。
 *
 *  3. **注册失败可手动重试** + 连接超时给出明确提示（Snackbar）。
 *
 *  4. **过滤掉显然不是主机的设备**（耳机/音箱等）。
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
    val snackbarHostState = remember { SnackbarHostState() }

    // 权限申请：蓝牙必需权限 + 可选通知权限，一次性申请，但**结果分开判定**
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // 只看蓝牙必需权限：通知被拒不阻断 HID 注册
        if (PermissionHelper.hasRequiredBluetooth(context)) {
            vm.onBluetoothPermissionGranted()
        }
    }

    // 首次进入：缺权限则申请；否则直接启动蓝牙栈并刷新列表
    LaunchedEffect(Unit) {
        val missing = PermissionHelper.permissionsToRequest(context)
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing)
        } else {
            vm.startBluetoothStack()
            vm.refreshBonded()
        }
    }

    // 连接成功后自动进入控制页（connectedDevice 由 HID 回调异步更新）
    LaunchedEffect(vm.connectedDevice) {
        if (vm.connectedDevice != null) onConnected()
    }

    // 把 ViewModel 的提示（连接超时等）显示为 Snackbar
    LaunchedEffect(vm.connectMessage) {
        vm.connectMessage?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearConnectMessage()
        }
    }

    // 已授权但尚未注册成功时，给几秒自动重试（覆盖 Profile 迟到就绪的情况）
    LaunchedEffect(vm.bluetoothPermissionGranted, vm.appRegistered) {
        if (vm.bluetoothPermissionGranted && !vm.appRegistered) {
            delayRetry(vm)
        }
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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

                    // 权限缺失：给出可操作入口
                    if (!vm.bluetoothPermissionGranted) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "缺少蓝牙权限，无法注册为鼠标。",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.error
                        )
                        TextButton(
                            onClick = {
                                val missing = PermissionHelper.permissionsToRequest(context)
                                if (missing.isNotEmpty()) {
                                    permissionLauncher.launch(missing)
                                } else {
                                    vm.onBluetoothPermissionGranted()
                                }
                            }
                        ) {
                            Text("授予蓝牙权限")
                        }
                    } else if (vm.profileReady && !vm.appRegistered) {
                        // 权限已给但注册未成功：多半是 Profile 迟到或注册被系统拒绝
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "正在注册为鼠标…若长时间未就绪，可手动重试。",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                        TextButton(onClick = { vm.retryRegister() }) {
                            Text("重试注册")
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---------------- 连接方向指引（解决"点了没反应"的困惑）----------------
            HidHintCard()

            Spacer(Modifier.height(16.dp))

            // ---------------- 设备列表 ----------------
            val hosts = vm.bondedDevices.filterNot { isAudioDevice(it.name) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "选择电脑设备",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onBackground
                )
                if (vm.isConnecting) {
                    Spacer(Modifier.size(10.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = "等待主机响应…",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.primary
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            if (hosts.isEmpty()) {
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
                    items(hosts, key = { it.address }) { device ->
                        DeviceCard(
                            name = device.name ?: device.address,
                            address = device.address,
                            connected = vm.connectedDevice?.address == device.address,
                            onClick = { vm.connect(device) }
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

/**
 * 连接方向指引卡片。
 *
 * 这解决的**不是**交互 bug，而是认知落差：蓝牙 HID 设备无法主动连接主机，
 * 只点列表是连不上的，必须由电脑侧发起。把这句话放在显眼处，
 * 用户才知道下一步该做什么，而不是反复点列表以为按钮坏了。
 */
@Composable
private fun HidHintCard() {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "首次配对：请从电脑发起连接",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onPrimaryContainer
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "蓝牙鼠标无法主动连上电脑。请到电脑的蓝牙设置里，找到并连接「鑫作蓝鼠」。\n" +
                    "连接成功后，本页「已连接设备」会自动亮起。之后再次使用只需点下方设备即可。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onPrimaryContainer.copy(alpha = 0.9f)
            )
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

/** 明显是音频外设的名字特征，用于从"可选择的主机"列表中排除。 */
private fun isAudioDevice(name: String?): Boolean {
    if (name == null) return false
    val keywords = listOf(
        "耳机", "耳麦", "音箱", "音响", "TWS", "tws", "Earbud", "earbud",
        "Headphone", "headphone", "Headset", "headset", "AirPods", "airpods",
        "Buds", "buds", "Speaker", "speaker", "SoundBar", "soundbar"
    )
    return keywords.any { name.contains(it) }
}

/**
 * 已授权但尚未注册成功时，做几次间隔重试。
 *
 * 为什么需要：registerAsHidDevice() 在 hidDevice 尚未就绪时会静默 return，
 * 而 Profile 就绪时机不定（可能晚于权限授予）。ViewModel 已在
 * onProfileReady 里补注册，这里再兜一层，确保不会永远停在灰点状态。
 */
private suspend fun delayRetry(vm: MainViewModel) {
    repeat(3) { attempt ->
        kotlinx.coroutines.delay(1200L * (attempt + 1))
        vm.retryRegister()
    }
}

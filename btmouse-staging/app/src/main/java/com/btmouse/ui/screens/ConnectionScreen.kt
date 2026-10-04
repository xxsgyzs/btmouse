package com.btmouse.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.btmouse.ui.MainViewModel
import com.btmouse.util.PermissionHelper

/**
 * 连接页：请求蓝牙/通知权限，展示已配对设备并连接，展示连接状态。
 * 连接成功后由 MainActivity 自动切换到触控板页。
 */
@Composable
fun ConnectionScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // 请求蓝牙(SCAN/CONNECT/ADVERTISE) + Android13+ 通知权限
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (PermissionHelper.hasAll(context)) vm.refreshBonded()
    }

    // 首次进入：若缺少权限则发起申请
    LaunchedEffect(Unit) {
        val missing = PermissionHelper.missingPermissions(context)
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            vm.refreshBonded()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "蓝牙连接",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // 状态概览
        StatusRow("Profile 就绪", vm.profileReady)
        StatusRow("已注册为鼠标", vm.appRegistered)
        StatusRow("已连接", vm.connectedDevice != null)

        Spacer(Modifier.height(12.dp))
        Text("选择电脑设备", style = MaterialTheme.typography.titleMedium)

        if (vm.bondedDevices.isEmpty()) {
            Text(
                text = "暂无已配对设备\n请先在系统蓝牙设置中与电脑完成配对",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.bondedDevices, key = { it.address }) { device ->
                    DeviceCard(
                        name = device.name ?: device.address,
                        address = device.address,
                        connected = vm.connectedDevice?.address == device.address,
                        onClick = { vm.connect(device) }
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        RadioButton(selected = ok, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DeviceCard(name: String, address: String, connected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (connected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(address, style = MaterialTheme.typography.bodySmall)
            if (connected) Text("● 已连接", color = MaterialTheme.colorScheme.primary)
        }
    }
}
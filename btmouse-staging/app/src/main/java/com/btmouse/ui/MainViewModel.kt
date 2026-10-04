package com.btmouse.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.btmouse.core.hid.BluetoothHidManager
import com.btmouse.core.hid.MouseReportBuilder
import com.btmouse.core.input.TouchInputHandler
import com.btmouse.service.HidForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * MainViewModel —— 阶段一状态机（连接 + 基本移动 + 左右键）
 *
 * 统一持有 BluetoothHidManager 单例与 TouchInputHandler（像素→逻辑位移+平滑），
 * 向 Compose 暴露可观察状态：profileReady / appRegistered / connectedDevice / bondedDevices。
 *
 * 数据流：
 *   触控/按钮 → TouchInputHandler(死区+EMA+灵敏度) → SendQueue(节拍+拆帧) → sendReport
 * 蓝牙在 Android 12+ 需要动态权限（PermissionHelper），UI 层授权后再调用 refreshBonded()。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val manager: BluetoothHidManager
        get() = BluetoothHidManager.getInstance(getApplication<Application>())

    private val adapter: BluetoothAdapter?
        get() = BluetoothAdapter.getDefaultAdapter()

    /**
     * 触控位移处理器：把像素增量转成滤波后的逻辑位移，并透传当前按钮状态到发送队列。
     * buttons 采用位掩码（可用 | 组合），按下/抬起即时提交。
     */
    private val touchHandler = TouchInputHandler(
        submit = { dx, dy, buttons -> manager.submitMouseInput(dx, dy, buttons) }
    )

    // ---------------- 可观察状态 ----------------

    /** HID Profile 是否已就绪 */
    var profileReady by mutableStateOf(false)
        private set

    /** 本设备是否已注册为 HID 鼠标 */
    var appRegistered by mutableStateOf(false)
        private set

    /** 当前连接的远端设备（电脑） */
    var connectedDevice by mutableStateOf<BluetoothDevice?>(null)
        private set

    /** 已配对的可用设备列表 */
    var bondedDevices by mutableStateOf<List<BluetoothDevice>>(emptyList())
        private set

    init {
        // 订阅蓝牙状态（回调在 HID 线程，需切回主线程更新 UI）
        manager.listener = object : BluetoothHidManager.HidStateListener {
            override fun onProfileReady(ready: Boolean) {
                viewModelScope.launch(Dispatchers.Main) { profileReady = ready }
            }
            override fun onAppRegistered(registered: Boolean) {
                viewModelScope.launch(Dispatchers.Main) { appRegistered = registered }
            }
            override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                viewModelScope.launch(Dispatchers.Main) {
                    connectedDevice = when (state) {
                        BluetoothProfile.STATE_CONNECTED -> device
                        else -> null
                    }
                }
            }
        }
        // 启动并初始化蓝牙 HID（前台服务保活 + Profile 获取）
        manager.initialize()
        HidForegroundService.start(getApplication())
    }

    override fun onCleared() {
        // 蓝牙 HID Profile 由前台服务持有；为不打断连接，ViewModel 销毁时不释放 manager。
        super.onCleared()
    }

    /** 刷新已配对设备列表（需已获得 BLUETOOTH_CONNECT 权限）。 */
    fun refreshBonded() {
        val list = adapter?.bondedDevices
            ?.filter { it.type != BluetoothDevice.DEVICE_TYPE_LE } // 过滤纯 BLE，聚焦 HID 主机（多为经典蓝牙）
            ?.toList()
            .orEmpty()
        bondedDevices = list.sortedBy { it.name ?: it.address }
    }

    /** 发起连接。 */
    fun connect(device: BluetoothDevice) {
        manager.connect(device)
    }

    /** 断开当前连接。 */
    fun disconnect() {
        manager.getConnectedDevice()?.let { manager.disconnect(it) }
    }

    /** 左键按下/抬起。 */
    fun setLeftPressed(pressed: Boolean) {
        touchHandler.buttons = applyButton(MouseReportBuilder.BUTTON_LEFT, pressed)
    }

    /** 右键按下/抬起。 */
    fun setRightPressed(pressed: Boolean) {
        touchHandler.buttons = applyButton(MouseReportBuilder.BUTTON_RIGHT, pressed)
    }

    /** 提交移动增量（dx/dy 为像素位移），由 TouchInputHandler 滤波后发送。 */
    fun submitMove(dxPx: Float, dyPx: Float) {
        touchHandler.onDrag(dxPx, dyPx)
    }

    /** 更新按钮掩码：pressed 则置位，否则清位。 */
    private fun applyButton(mask: Int, pressed: Boolean): Int = if (pressed) {
        touchHandler.buttons or mask
    } else {
        touchHandler.buttons and mask.inv()
    }
}
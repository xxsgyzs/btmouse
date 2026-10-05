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
import com.btmouse.core.state.HidMode
import com.btmouse.core.state.ModeStore
import com.btmouse.core.state.MouseSettings
import com.btmouse.core.state.SettingsStore
import com.btmouse.service.HidForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * MainViewModel —— 应用状态机
 *
 * 统一持有 BluetoothHidManager 单例与 TouchInputHandler（像素→逻辑位移+自适应滤波），
 * 向 Compose 暴露：模式选择、连接状态、配对列表、手感参数。
 *
 * 数据流（保持与原实现一致，未触碰 HID 底层）：
 *   触控/按键 → TouchInputHandler(死区+自适应EMA+残差累积) → TouchInputHandler.submit
 *             → manager.submitMouseInput() → SendQueue(自适应节拍+拆帧) → sendReport
 *
 * 模式说明：
 *   - [HidMode.TRACKPAD]：触摸直接产生位移，本类已完全可用
 *   - [HidMode.DESK] / [HidMode.AIR]：布局与按键、滚轮已可用；
 *     传感器驱动将在 BATCH-3/BATCH-4 通过 SensorMouseController 接入本类
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val manager: BluetoothHidManager
        get() = BluetoothHidManager.getInstance(getApplication<Application>())

    private val adapter: BluetoothAdapter?
        get() = BluetoothAdapter.getDefaultAdapter()

    // ---------------- 手感参数与模式 ----------------

    /** 当前手感参数（来自持久化，可被设置页实时修改） */
    var settings: MouseSettings by mutableStateOf(SettingsStore.load(app))
        private set

    /** 当前操作模式（来自持久化） */
    var currentMode: HidMode by mutableStateOf(ModeStore.load(app))
        private set

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
        // 把持久化的手感立即应用到处理器（含灵敏度 / 平滑下限）
        applySettingsToHandler()

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

    // ---------------- 模式切换 ----------------

    /**
     * 切换操作模式：持久化 + 更新 UI 状态 + 清空滤波残差。
     * 清空残差是为了避免切换瞬间把上一模式累积的小数位移带进新模式。
     */
    fun selectMode(mode: HidMode) {
        if (mode == currentMode) return
        currentMode = mode
        ModeStore.save(getApplication(), mode)
        touchHandler.reset()
    }

    // ---------------- 手感参数 ----------------

    /** 灵敏度实时生效（设置页滑块拖动中即调用）。 */
    fun updateSensitivity(value: Float) {
        updateSettings(settings.copy(sensitivity = value.coerceIn(SettingsStore.SENSITIVITY_RANGE)))
    }

    /** 平滑下限实时生效。 */
    fun updateSmoothing(value: Float) {
        updateSettings(settings.copy(smoothing = value.coerceIn(SettingsStore.SMOOTHING_RANGE)))
    }

    /** 滚轮速度实时生效（每格所需像素）。 */
    fun updatePixelsPerScrollClick(value: Float) {
        updateSettings(
            settings.copy(
                pixelsPerScrollClick = value.coerceIn(SettingsStore.PX_PER_CLICK_RANGE)
            )
        )
    }

    /** 恢复默认手感，返回复位后的参数供 UI 同步滑块位置。 */
    fun resetSettings(): MouseSettings {
        val defaults = SettingsStore.reset(getApplication())
        settings = defaults
        applySettingsToHandler()
        return defaults
    }

    private fun updateSettings(newSettings: MouseSettings) {
        settings = newSettings
        SettingsStore.save(getApplication(), newSettings)
        applySettingsToHandler()
    }

    /**
     * 把参数注入 TouchInputHandler 的公开属性。
     * TouchInputHandler 的 onDrag 每次都会读取这些属性，因此是**实时生效**的。
     */
    private fun applySettingsToHandler() {
        touchHandler.sensitivity = settings.sensitivity
        touchHandler.smoothing = settings.smoothing
    }

    // ---------------- 连接管理 ----------------

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

    // ---------------- 输入上报 ----------------

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


    // 滚轮：报告的第 4 字节（wheel）已由 SendQueue / MouseReportBuilder 支持，
    // 但 BluetoothHidManager.submitMouseInput() 的公开签名目前只有 (dx, dy, buttons)。
    // 按要求不在本批次改动 HID 层，故滚轮发送与双指手势一并在 BATCH-3 接入。

    /** 更新按钮掩码：pressed 则置位，否则清位。 */
    private fun applyButton(mask: Int, pressed: Boolean): Int = if (pressed) {
        touchHandler.buttons or mask
    } else {
        touchHandler.buttons and mask.inv()
    }
}


package com.btmouse.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.btmouse.core.hid.BluetoothHidManager
import com.btmouse.core.hid.MouseReportBuilder
import com.btmouse.core.input.TouchInputHandler
import com.btmouse.core.sensors.SensorMouseController
import com.btmouse.core.state.HidMode
import com.btmouse.core.state.ModeStore
import com.btmouse.core.state.MouseSettings
import com.btmouse.core.state.SettingsStore
import com.btmouse.service.HidForegroundService
import com.btmouse.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * MainViewModel —— 应用状态机
 *
 * 统一持有 BluetoothHidManager 单例、TouchInputHandler（像素→逻辑位移）与
 * SensorMouseController（体感位移），向 Compose 暴露：模式选择、连接状态、配对列表、手感参数。
 *
 * ██ BATCH-5.1 修复 ██
 *  1. **权限判定改用 PermissionHelper.hasRequiredBluetooth**：
 *     通知权限被拒不再阻断 HID 注册（原实现会因此永远注册不上）。
 *  2. **Profile 就绪后自动补注册**：若首次 registerApp 发生在 hidDevice 尚未就绪时
 *     （BluetoothHidManager.registerAsHidDevice 会静默 return），这里会在
 *     onProfileReady(true) 时自动重试一次，覆盖该失败路径。
 *  3. **连接过程可视化**：isConnecting / connectMessage 让"点了没反应"变成有反馈。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        private const val TAG = "MainViewModel"

        /** 主动连接后的等待上限：超时即认为主机没有响应。 */
        private const val CONNECT_TIMEOUT_MS = 6000L
    }

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
        submit = { dx, dy, buttons, wheel ->
            manager.submitMouseInput(dx, dy, buttons, wheel)
        }
    )

    /**
     * 体感控制器（模式二/三）。回调运行在其内部后台线程；
     * manager.submitMouseInput 本身线程安全（内部 post 到 SendQueue 线程），故无需切线程。
     */
    private val sensorController = SensorMouseController(
        context = app,
        onCursorDelta = { dx, dy -> submitSensorDelta(dx, dy) },
        onScrollDelta = { dyPx -> touchHandler.onScroll(0f, dyPx) }
    )

    // ---------------- 可观察状态 ----------------

    /** HID Profile 是否已就绪 */
    var profileReady by mutableStateOf(false)
        private set

    /** 本设备是否已成功注册为 HID 鼠标 */
    var appRegistered by mutableStateOf(false)
        private set

    /** 当前连接的远端设备（电脑） */
    var connectedDevice by mutableStateOf<BluetoothDevice?>(null)
        private set

    /** 已配对的可用设备列表 */
    var bondedDevices by mutableStateOf<List<BluetoothDevice>>(emptyList())
        private set

    /** 当前模式的传感器是否可用（供 UI 提示"本机不支持陀螺仪"）。 */
    var sensorAvailable by mutableStateOf(true)
        private set

    /** 是否正在等待主机响应（点击设备后置位，连接成功或超时后复位）。 */
    var isConnecting by mutableStateOf(false)
        private set

    /** 面向用户的一句话状态提示（连接超时 / 未注册等），null 表示无提示。 */
    var connectMessage by mutableStateOf<String?>(null)
        private set

    /** 蓝牙必需权限是否已授予。 */
    var bluetoothPermissionGranted by mutableStateOf(false)
        private set

    init {
        // 把持久化的手感立即应用到处理器（含灵敏度 / 平滑下限 / 滚轮参数）
        applySettingsToHandler()

        // 订阅蓝牙状态（回调可能在 Binder/HID 线程，统一切回主线程更新 UI）
        manager.listener = object : BluetoothHidManager.HidStateListener {
            override fun onProfileReady(ready: Boolean) {
                viewModelScope.launch(Dispatchers.Main) {
                    profileReady = ready
                    if (ready) {
                        // 关键补注册：首次 registerApp 可能因 hidDevice 尚未就绪而静默失败，
                        // 此刻 Profile 已确认就绪，重试一次即可覆盖该路径（registerApp 幂等）。
                        manager.registerAsHidDevice()
                    }
                }
            }

            override fun onAppRegistered(registered: Boolean) {
                viewModelScope.launch(Dispatchers.Main) {
                    appRegistered = registered
                    if (registered) connectMessage = null
                }
            }

            override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                viewModelScope.launch(Dispatchers.Main) {
                    connectedDevice = when (state) {
                        BluetoothProfile.STATE_CONNECTED -> device
                        else -> null
                    }
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        isConnecting = false
                        connectMessage = null
                    }
                }
            }
        }

        // 先检查运行时权限：缺少蓝牙权限时不能启动前台服务、也不能注册 HID，
        // 否则 Android 12+ 会抛 SecurityException 导致闪退。
        // 注意：这里**只看蓝牙必需权限**，通知权限被拒不影响蓝牙功能。
        val granted = PermissionHelper.hasRequiredBluetooth(getApplication())
        bluetoothPermissionGranted = granted
        if (granted) {
            startBluetoothStack()
        } else {
            Log.w(TAG, "蓝牙权限未授予，等待用户在连接页授权后启动 HID")
        }

        // 恢复到上次选择的模式（若为体感模式则直接启动传感器）
        applyMode(currentMode, persist = false)
    }

    /** 当前是否具备使用蓝牙 HID 的运行时权限（Android 12+ 需要 BLUETOOTH_CONNECT）。 */
    fun canUseBluetooth(): Boolean = manager.canRegisterHid()

    /**
     * 启动蓝牙链路：初始化 HID Profile + 启动前台服务保活。
     *
     * 幂等：重复调用安全（getProfileProxy 与 startForegroundService 都可重复执行）。
     * 由 init（已授权时）或连接页授权成功后调用。
     */
    fun startBluetoothStack() {
        if (!canUseBluetooth()) {
            Log.w(TAG, "startBluetoothStack 被调用但权限仍缺失，忽略")
            return
        }
        manager.initialize()
        HidForegroundService.start(getApplication())
    }

    /**
     * 权限授予后调用：启动蓝牙链路，并立即尝试补注册一次。
     *
     * 若此刻 Profile 尚未就绪，registerAsHidDevice 会静默 return ——
     * 这种情况由 onProfileReady(true) 里的补注册兜住，不会漏。
     */
    fun onBluetoothPermissionGranted() {
        bluetoothPermissionGranted = PermissionHelper.hasRequiredBluetooth(getApplication())
        startBluetoothStack()
        manager.registerAsHidDevice()
        refreshBonded()
    }

    /** 供 UI 手动重试：重新初始化并尝试注册。 */
    fun retryRegister() {
        connectMessage = null
        startBluetoothStack()
        manager.registerAsHidDevice()
    }

    override fun onCleared() {
        // 蓝牙 HID Profile 由前台服务持有；为不打断连接，ViewModel 销毁时不释放 manager。
        // 传感器必须停：否则 Activity 结束后仍在耗电。
        sensorController.stop()
        super.onCleared()
    }

    // ---------------- 模式切换 ----------------

    /**
     * 切换操作模式：更新状态 + 持久化 + 启停传感器 + 清空滤波残差。
     */
    fun selectMode(mode: HidMode) {
        if (mode == currentMode) return
        applyMode(mode, persist = true)
    }

    private fun applyMode(mode: HidMode, persist: Boolean) {
        currentMode = mode
        if (persist) ModeStore.save(getApplication(), mode)

        touchHandler.reset()

        if (mode.sensorBased) {
            sensorAvailable = sensorController.isAvailable(mode)
            if (sensorAvailable) {
                sensorController.start(mode)
            } else {
                Log.w(TAG, "模式 $mode 的传感器在本机不可用")
            }
        } else {
            sensorAvailable = true
            sensorController.stop()
        }
    }

    // ---------------- 手感参数 ----------------

    fun updateSensitivity(value: Float) {
        updateSettings(settings.copy(sensitivity = value.coerceIn(SettingsStore.SENSITIVITY_RANGE)))
    }

    fun updateSmoothing(value: Float) {
        updateSettings(settings.copy(smoothing = value.coerceIn(SettingsStore.SMOOTHING_RANGE)))
    }

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

    private fun applySettingsToHandler() {
        touchHandler.sensitivity = settings.sensitivity
        touchHandler.smoothing = settings.smoothing
        touchHandler.scrollConfig = settings.toScrollConfig()
    }

    // ---------------- 连接管理 ----------------

    /** 刷新已配对设备列表（需已获得 BLUETOOTH_CONNECT 权限）。 */
    fun refreshBonded() {
        if (!PermissionHelper.hasRequiredBluetooth(getApplication())) {
            Log.w(TAG, "refreshBonded 跳过：缺少蓝牙权限")
            return
        }
        val list = adapter?.bondedDevices
            ?.toList()
            .orEmpty()
        bondedDevices = list.sortedBy { it.name ?: it.address }
    }

    /**
     * 请求与指定主机建立 HID 连接。
     *
     * 重要背景：**蓝牙 HID 设备无法主动连接主机**。
     * BluetoothHidManager.connect 发出的只是一次"请求"，真正的连接由主机侧发起；
     * 若主机未在蓝牙设置里选中本设备，这里不会有任何回调，看起来就像"点了没反应"。
     * 因此本方法会置位 isConnecting 并在超时后给出明确提示。
     */
    fun connect(device: BluetoothDevice) {
        connectMessage = null

        if (!appRegistered) {
            if (!canUseBluetooth()) {
                connectMessage = "缺少蓝牙权限，请先点击上方「授予蓝牙权限」"
            } else {
                connectMessage = "尚未注册为鼠标，正在重试…"
                retryRegister()
            }
            return
        }

        manager.connect(device)
        isConnecting = true

        // 超时兜底：主机未响应时给出可操作提示，而不是无限等待
        viewModelScope.launch(Dispatchers.Main) {
            delay(CONNECT_TIMEOUT_MS)
            if (isConnecting && connectedDevice == null) {
                isConnecting = false
                connectMessage = "主机未响应。请到电脑的蓝牙设置中，选择「" +
                    "鑫作蓝鼠」进行连接"
            }
        }
    }

    /** 断开当前连接。 */
    fun disconnect() {
        manager.getConnectedDevice()?.let { manager.disconnect(it) }
        isConnecting = false
        connectMessage = null
    }

    /** 清除提示（如用户已阅读）。 */
    fun clearConnectMessage() {
        connectMessage = null
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

    /** 提交移动增量（dx/dy 为像素位移），供模式一（触控板）使用。 */
    fun submitMove(dxPx: Float, dyPx: Float) {
        touchHandler.onDrag(dxPx, dyPx)
    }

    /** 提交双指滚动（像素位移），供模式一与模式二/三的滚轮条使用。 */
    fun submitScroll(dxPx: Float, dyPx: Float) {
        touchHandler.onScroll(dxPx, dyPx)
    }

    /**
     * 体感模式的光标位移：做亚像素累积后取整发出。
     * 传感器以浮点速度积分出像素，直接取整会丢掉小数（与触控通路同一问题）。
     */
    private var sensorResidualX = 0f
    private var sensorResidualY = 0f

    private fun submitSensorDelta(dxPx: Float, dyPx: Float) {
        val posX = dxPx + sensorResidualX
        val posY = dyPx + sensorResidualY
        val outX = posX.toInt()
        val outY = posY.toInt()
        sensorResidualX = posX - outX
        sensorResidualY = posY - outY

        if (outX != 0 || outY != 0) {
            manager.submitMouseInput(outX, outY, touchHandler.buttons, 0)
        }
    }

    /** 更新按钮掩码：pressed 则置位，否则清位。 */
    private fun applyButton(mask: Int, pressed: Boolean): Int = if (pressed) {
        touchHandler.buttons or mask
    } else {
        touchHandler.buttons and mask.inv()
    }
}


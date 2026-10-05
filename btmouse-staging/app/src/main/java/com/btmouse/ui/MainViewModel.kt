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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * MainViewModel —— 应用状态机
 *
 * 统一持有 BluetoothHidManager 单例、TouchInputHandler（像素→逻辑位移）与
 * SensorMouseController（体感位移），向 Compose 暴露：模式选择、连接状态、配对列表、手感参数。
 *
 * 数据流（两条入口，汇入同一条 HID 通路）：
 *   触控/按键 → TouchInputHandler(死区+自适应EMA+残差累积) ─┐
 *   传感器    → SensorMouseController(低通+死区+ZUPT+阻尼) ─┤
 *                                                          └→ manager.submitMouseInput/submitWheel
 *                                                             → SendQueue(节拍+拆帧) → sendReport
 *
 * 模式说明：
 *   - [HidMode.TRACKPAD]：触摸直接产生位移（含双指滚轮）
 *   - [HidMode.DESK]：加速度计积分位移（桌面平放推拉）
 *   - [HidMode.AIR]：陀螺仪角速度映射（手持悬空转动）
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        private const val TAG = "MainViewModel"
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
     *
     * wheel 参数在 BATCH-3 接入，用于双指滑动滚轮。
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

    /** 本设备是否已注册为 HID 鼠标 */
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

    init {
        // 把持久化的手感立即应用到处理器（含灵敏度 / 平滑下限 / 滚轮参数）
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
        // 先检查运行时权限：缺少 BLUETOOTH_CONNECT 时不能启动前台服务、也不能注册 HID，
        // 否则在 Android 12+ 上会抛 SecurityException 导致"一打开就闪退"。
        if (canUseBluetooth()) {
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

    /** 权限授予后调用：启动蓝牙链路，并在 HID 服务已就绪时补注册一次。 */
    fun onBluetoothPermissionGranted() {
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
     *
     * 清空残差是为了避免切换瞬间把上一模式累积的小数位移带进新模式。
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
     * 把参数注入两个处理器的公开属性。
     * 它们每次运算都会读取这些属性，因此是**实时生效**的。
     */
    private fun applySettingsToHandler() {
        touchHandler.sensitivity = settings.sensitivity
        touchHandler.smoothing = settings.smoothing
        touchHandler.scrollConfig = settings.toScrollConfig()
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

    /**
     * 提交移动增量（dx/dy 为像素位移），由 TouchInputHandler 滤波后发送。
     * 供模式一（触控板）使用。
     */
    fun submitMove(dxPx: Float, dyPx: Float) {
        touchHandler.onDrag(dxPx, dyPx)
    }

    /**
     * 提交双指滚动（像素位移），由 TouchInputHandler 换算成滚轮格数。
     * 供模式一（触控板双指滑动）与模式二/三（滚轮条）使用。
     */
    fun submitScroll(dxPx: Float, dyPx: Float) {
        touchHandler.onScroll(dxPx, dyPx)
    }

    /**
     * 体感模式的光标位移：做亚像素累积后取整发出。
     *
     * 传感器以浮点速度积分出像素，若直接取整会丢掉小数（与触控通路同一问题），
     * 因此在 ViewModel 这一层统一累积。
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

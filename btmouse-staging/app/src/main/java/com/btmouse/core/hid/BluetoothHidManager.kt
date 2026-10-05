package com.btmouse.core.hid

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * BluetoothHIDManager
 *
 * 原生层核心：把本机 Android 设备注册成一个标准的 BLE HID Device（鼠标），
 * 负责 HID Profile 初始化、注册 Report Descriptor、连接管理以及发送 Input Report。
 *
 * 职责边界：
 *  - 只关心"蓝牙/HID 传输层"：用什么描述符、怎么注册、怎么发字节。
 *  - 不关心"业务位移怎么算"：报告内容（4 字节鼠标 report）由上层合成后传入 sendReport()。
 *
 * 单例：被前台服务（HidForegroundService）持用，保证整个 App 生命周期内只有一个实例，
 *       且蓝牙连接、注册状态不会因界面重建而丢失。
 *
 * 典型调用时序（阶段一）：
 *   manager.initialize()                          // 1. 获取 BluetoothHidDevice Profile
 *   manager.registerAsHidDevice()                 // 2. 注册鼠标描述符
 *   manager.connect(已配对设备)                    // 3. 发起连接
 *   manager.sendMouseReport(reportBytes)          // 4. 发送鼠标 report
 */
class BluetoothHidManager private constructor(context: Context) {
    private val appContext = context.applicationContext

    /** HID 回调与注册所用到的并发执行器（Android 官方要求传入，用于异步回调线程调度） */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    /** 当前已绑定的 HID Profile 实例；未就绪时为 null（跨线程访问，需 volatile） */
    @Volatile
    private var hidDevice: BluetoothHidDevice? = null

    /** 当前与远端连接的蓝牙设备；未连接时为 null（跨线程访问，需 volatile） */
    @Volatile
    private var connectedDevice: BluetoothDevice? = null

    /** 发送节流队列：把高频输入增量合并成定时 report 发送（见 SendQueue）。 */
    private val sendQueue by lazy { SendQueue(sendReport = { sendMouseReport(it) }) }

    /** 当前已按下的虚拟按钮状态（左/右/中掩码）；供 UI 层通过按钮组件更新。 */
    @Volatile
    var buttons: Int = 0
        private set

    /** 应用是否已成功注册为 HID 设备（registerApp 成功） */
    @Volatile
    var isAppRegistered: Boolean = false
        private set

    /** 对外暴露的连接状态监听，供 UI 层更新界面 */
    var listener: HidStateListener? = null

    companion object {
        private const val TAG = "BluetoothHidManager"

        /** 单例 */
        @Volatile
        private var instance: BluetoothHidManager? = null

        fun getInstance(context: Context): BluetoothHidManager =
            instance ?: synchronized(this) {
                instance ?: BluetoothHidManager(context.applicationContext).also { instance = it }
            }

        /**
         * HID Report Descriptor（汇报描述符），共 52 字节。
         *
         * 目标：让电脑识别为"标准桌面鼠标"，无 Report ID，即插即用、无需驱动。
         * 它与 Data Report 的 4 字节布局（[buttons, X, Y, wheel]）严格对应。
         *
         * 逐项解释（Usage Page in HID 术语中表示"功能页"）：
         *
         *  0x05 0x01          —— Usage Page = Generic Desktop (0x01)：声明属于"通用桌面设备"页
         *  0x09 0x02          —— Usage = Mouse (0x02)：具体用途是鼠标
         *  0xA1 0x01          —— Collection (Application)：开启应用级集合
         *  0x09 0x01          —— Usage = Pointer：集合内是"指针"概念
         *  0xA1 0x00          —— Collection (Physical)：物理集合，把按键与位移归为一组
         *  -------------------------- 以下是按键部分 --------------------------
         *  0x05 0x09          —— Usage Page = Button (0x09)：切换到"按键"页
         *  0x19 0x01 0x29 0x03 —— Usage Min=1 / Max=3：声明 3 个按键（1.左 2.右 3.中）
         *  0x15 0x00 0x25 0x01 —— Logical Min=0 / Max=1：每个按键取值 0(松开)/1(按下)
         *  0x95 0x03 0x75 0x01 —— Count=3, Size=1：用 3 个 bit 表示
         *  0x81 0x02          —— Input(Data,Var,Abs)：绝对量，3bit 对应按钮字节的低 3 位
         *  0x95 0x01 0x75 0x05 —— Count=1, Size=5：保留 5 bit
         *  0x81 0x01          —— Input(Const)：固定常量填充位，凑满一个字节
         *  -------------------------- 以下是位移/滚轮部分 --------------------------
         *  0x05 0x01          —— Usage Page = Generic Desktop：切回桌面页
         *  0x09 0x30 0x09 0x31 0x09 0x38 —— Usage = X / Y / Wheel：X轴、Y轴、滚轮
         *  0x15 0x81 0x25 0x7F —— Logical Min=-127 / Max=127：三者均为有符号 -127~127
         *  0x75 0x08 0x95 0x03 —— Size=8, Count=3：每个占 1 字节，共 3 字节
         *  0x81 0x06          —— Input(Data,Var,Rel)：相对位移量
         *  0xC0 0xC0          —— End Collection：关闭物理集合与应用集合
         */
        private val HID_REPORT_DESCRIPTOR = byteArrayOf(
            0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01,
            0x09, 0x01, 0xA1.toByte(), 0x00,
            0x05, 0x09,
            0x19, 0x01, 0x29, 0x03,
            0x15, 0x00, 0x25, 0x01,
            0x95, 0x03, 0x75, 0x01, 0x81.toByte(), 0x02,
            0x95, 0x01, 0x75, 0x05, 0x81.toByte(), 0x01,
            0x05, 0x01,
            0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
            0x15, 0x81.toByte(), 0x25, 0x7F,
            0x75, 0x08, 0x95, 0x03, 0x81.toByte(), 0x06,
            0xC0.toByte(), 0xC0.toByte()
        )

        /**
         * 鼠标 HID Input Report 的字节索引常量。
         * 报告固定 4 字节（结合 Data Report 布局）：
         *   byte0 低 3 bit = 按钮(bit0左/bit1右/bit2中)
         *   byte1 = X 相对位移（-127~127）
         *   byte2 = Y 相对位移（-127~127）
         *   byte3 = Wheel 滚轮（-127~127，阶段二使用）
         */
        const val REPORT_LENGTH = 4
        const val IDX_BUTTONS = 0
        const val IDX_X = 1
        const val IDX_Y = 2
        const val IDX_WHEEL = 3

        /** 按钮位掩码 */
        const val BUTTON_NONE = 0x00
        const val BUTTON_LEFT = 0x01
        const val BUTTON_RIGHT = 0x02
        const val BUTTON_MIDDLE = 0x04
    }

    // ---------------- 对外状态监听接口 ----------------

    /**
     * 连接状态监听。UI 层实现它来驱动界面（如"未连接/已注册/已连接"切换）。
     */
    interface HidStateListener {
        /** HID Profile 是否已就绪 */
        fun onProfileReady(ready: Boolean)
        /** App 是否已成功注册为 HID 设备 */
        fun onAppRegistered(registered: Boolean)
        /** 连接状态变化：device 为目标设备，state 用 BluetoothProfile.STATE_* 表示 */
        fun onConnectionStateChanged(device: BluetoothDevice?, state: Int)
    }

    // ---------------- 初始化：获取 HID Profile ----------------

    /**
     * 通过 getProfileProxy 异步获取 BluetoothHidDevice Profile。
     * 结果在 [profileServiceListener.onServiceConnected] 回调中获得。
     *
     * 必须在 App 启动（或前台服务 onStartCommand）时调用一次。
     */
    fun initialize() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            Log.e(TAG, "设备不支持蓝牙")
            listener?.onProfileReady(false)
            return
        }
        // 第二参数是 Profile；第三个给出连接结果回调
        adapter.getProfileProxy(
            appContext,
            profileServiceListener,
            BluetoothProfile.HID_DEVICE
        )
    }

    /**
     * Profile 连接服务监听。系统将 HID_DEVICE Profile 绑定给本应用时回调。
     */
    private val profileServiceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE && proxy is BluetoothHidDevice) {
                hidDevice = proxy
                Log.i(TAG, "HID Profile 已就绪")
                listener?.onProfileReady(true)
                // Profile 就绪后自动尝试注册（描述符）。注册成功即可被电脑发现/连接。
                registerAsHidDevice()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                Log.w(TAG, "HID Profile 连接断开")
                listener?.onProfileReady(false)
            }
        }
    }

    // ---------------- 注册为 HID 设备 ----------------

    /**
     * 把本机注册成一个 HID Device（鼠标）。
     *
     * 需要三份材料：
     *  1. SDP 属性设置：描述设备名称、子类、描述符种类等（供主机端枚举）。
     *  2. QoS 设置：连接服务质量（这里用最低时延配置，降低输入延迟）。
     *  3. HID Report Descriptor：上面定义的标准鼠标描述符。
     */
    fun registerAsHidDevice() {
        val device = hidDevice ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "BT Mouse",                        // 设备名称，主机端显示
            "Android BLE HID Mouse",           // 描述
            "BTMouse",                         // Provider 名称
            BluetoothHidDevice.SUBCLASS1_MOUSE,// SDP 子类：鼠标
            HID_REPORT_DESCRIPTOR              // HID 描述符
        )
        // QoS 使用"最低时延"优先：降低输入延迟、换取低功耗（适合 HID 鼠标高频上报）
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            1,
            1,
            0,
            1
        )
        // Android 11 (API 30) 的 registerApp 为 5 参数签名：
        //   registerApp(sdp, inQos, outQos, executor, callback)
        // outQos 传 null 表示使用默认出站 QoS。
        device.registerApp(sdp, qos, null, executor, hidCallback)
    }

    /**
     * HID 设备回调，接收注册结果与连接状态。
     */
    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            isAppRegistered = registered
            Log.i(TAG, "onAppStatusChanged registered=$registered device=${pluggedDevice?.address}")
            listener?.onAppRegistered(registered)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            connectedDevice = when (state) {
                BluetoothProfile.STATE_CONNECTED -> device
                else -> null
            }
            Log.i(TAG, "onConnectionStateChanged state=$state device=${device.address}")
            listener?.onConnectionStateChanged(device, state)
        }

        // 以下回调在本项目中不涉及数据交换（纯输出设备），保留空实现即可。
        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {}
        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {}
        override fun onVirtualCableUnplug(device: BluetoothDevice) {}
        override fun onProtocolMode(device: BluetoothDevice, protocolMode: Byte) {}
    }

    // ---------------- 连接管理 ----------------

    /**
     * 主动向某个已配对设备发起 HID 连接。
     * @param device 远端电脑（已通过系统蓝牙配对）。
     */
    fun connect(device: BluetoothDevice): Boolean {
        val hid = hidDevice ?: return false
        return hid.connect(device)
    }

    /**
     * 断开与远端设备的连接。
     */
    fun disconnect(device: BluetoothDevice): Boolean {
        val hid = hidDevice ?: return false
        return hid.disconnect(device)
    }

    /** 当前与远端连接的设备 */
    fun getConnectedDevice(): BluetoothDevice? = connectedDevice

    // ---------------- 输入上报入口（供 UI / 触控层调用） ----------------

    /**
     * 提交一次鼠标位移增量 + 按钮状态。高频调用，由内部 SendQueue 节流合并发送。
     * 任意线程均可调用，内部会 dispatch 到队列线程。
     */
    fun submitMouseInput(dx: Int, dy: Int, buttons: Int) {
        this.buttons = buttons
        sendQueue.submitMouse(dx, dy, buttons)
    }

    // ---------------- 发送报告 ----------------

    /**
     * 发送一条 HID Input Report 给远端（电脑）。
     *
     * @param report 4 字节鼠标报告（由上层 MouseReportBuilder 合成）。
     *               布局：byte0 按钮、byte1 X、byte2 Y、byte3 Wheel。
     * @return 是否发送成功（false 说明未连接或 HID 未就绪）。
     */
    fun sendMouseReport(report: ByteArray): Boolean {
        if (report.size != REPORT_LENGTH) {
            Log.w(TAG, "report 长度必须为 $REPORT_LENGTH，实际 ${report.size}，已丢弃")
            return false
        }
        val hid = hidDevice ?: return false
        val device = connectedDevice ?: return false
        // 第二个参数为 Report ID；描述符未声明 Report ID，故传 0
        return hid.sendReport(device, 0, report)
    }

    /**
     * 释放 Profile 资源，在服务销毁时调用，避免泄漏。
     */
    fun release() {
        hidDevice?.let { BluetoothAdapter.getDefaultAdapter()?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        hidDevice = null
        connectedDevice = null
        if (::sendQueue.isInitialized) sendQueue.release()
        instance = null
        executor.shutdown()
    }
}

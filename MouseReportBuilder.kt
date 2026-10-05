package com.btmouse.core.hid

/**
 * MouseReportBuilder —— 鼠标 HID Input Report 合成
 *
 * Data Report 固定 4 字节，与 BluetoothHidManager 中定义的描述符严格对应：
 *
 *   byte0            byte1      byte2      byte3
 *   ┌────────────┐  ┌────────┐ ┌────────┐ ┌────────┐
 *   │ 按钮(3bit)   │  │  X 相对 │ │  Y 相对 │ │ Wheel  │
 *   │ bit0左/右/中 │  │±127    │ │±127    │ │±127    │
 *   │ 其余保留0    │  │        │ │        │ │        │
 *   └────────────┘  └────────┘ └────────┘ └────────┘
 *
 * 注意：报告长度必须恒为 4 字节。已配对的主机是按这份描述符认下设备的，
 *       擅自增删字节会导致电脑端不再识别本设备。
 *
 * @see BluetoothHidManager.HID_REPORT_DESCRIPTOR
 */
object MouseReportBuilder {

    /** 报告长度：4 字节 */
    const val REPORT_LENGTH = BluetoothHidManager.REPORT_LENGTH

    /** 按钮位掩码 */
    const val BUTTON_NONE = BluetoothHidManager.BUTTON_NONE
    const val BUTTON_LEFT = BluetoothHidManager.BUTTON_LEFT
    const val BUTTON_RIGHT = BluetoothHidManager.BUTTON_RIGHT
    const val BUTTON_MIDDLE = BluetoothHidManager.BUTTON_MIDDLE

    private const val MAX_DELTA = 127
    private const val MIN_DELTA = -127

    /**
     * 合成一帧鼠标 report。
     *
     * @param btn   按钮位掩码（可用 | 组合，如 LEFT or RIGHT）
     * @param dx    X 轴相对位移（内部会钳制到 -127~127；超出应在上层 SendQueue 拆帧）
     * @param dy    Y 轴相对位移（同上）
     * @param wheel 滚轮相对位移（同上）。正值/负值对应的上下滚方向由调用方
     *              [com.btmouse.core.input.TouchInputHandler.SCROLL_DIRECTION] 决定。
     * @return 4 字节 report
     */
    fun build(btn: Int, dx: Int, dy: Int, wheel: Int = 0): ByteArray = byteArrayOf(
        (btn and 0xFF).toByte(),          // byte0：按钮状态（低 3 位）
        clamp(dx).toByte(),               // byte1：X 相对位移（有符号）
        clamp(dy).toByte(),               // byte2：Y 相对位移（有符号）
        clamp(wheel).toByte()             // byte3：Wheel 滚轮（有符号）
    )

    /** 构造"仅按钮"报告：用于按下/抬起（不携带位移）。 */
    fun buildButtons(btn: Int): ByteArray = build(btn, 0, 0, 0)

    /** 把位移限制到有符号单字节能力范围内。 */
    private fun clamp(v: Int): Int = when {
        v > MAX_DELTA -> MAX_DELTA
        v < MIN_DELTA -> MIN_DELTA
        else -> v
    }
}

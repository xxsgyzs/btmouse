package com.btmouse.core.input

import kotlin.math.sqrt

/**
 * TouchInputHandler —— 触控板位移算法
 *
 * 把 Compose 指针产生的**像素位移**转换成 HID 需要的**逻辑位移**，并做平滑与滤波，
 * 再提交给 SendQueue 节流发送。
 *
 * 处理流水线：
 *   touch 像素增量 → 速度计算 → 死区过滤 → EMA 平滑 → 灵敏度映射 → 提交 SendQueue
 *
 * @param submit 对外上报函数：将"逻辑位移 + 当前按钮"交出去（由 UI 层绑定到 SendQueue.submitMouse）
 * @param sensitivity 灵敏度系数（默认 1.0，越大移动越快）
 * @param smoothing   平滑系数 0..1（越大越跟手，越小越平滑滞重）
 */
class TouchInputHandler(
    private val submit: (dx: Int, dy: Int, buttons: Int) -> Unit,
    @Volatile var sensitivity: Float = 1.0f,
    @Volatile var smoothing: Float = 0.6f
) {

    /** 死区阈值（像素）：小于该值的位移忽略，抑制手指颤动的微响应。 */
    private val deadZonePx = 0.7f

    /** 上一次平滑后的逻辑位移（供 EMA 使用） */
    private var prevSmoothedX = 0f
    private var prevSmoothedY = 0f

    /** 当前按钮状态（键盘/触控板按钮由 UI 维护，这里只透传）。 */
    @Volatile
    var buttons: Int = 0
        set(value) { field = value; requestFlush(value) }

    /**
     * 由 Compose/原生触摸回调调用，传入本帧的像素位移（float 保留亚像素精度）。
     * 线程：通常为主线程，内部仅做纯计算 + 交给 SendQueue 异步发送，不阻塞。
     */
    fun onDrag(dxPx: Float, dyPx: Float) {
        // 1. 速度估计：用本帧位移大小近似速度，用于自适应平滑
        val speed = sqrt(dxPx * dxPx + dyPx * dyPx)

        // 2. 死区：低速微位移直接忽略
        if (speed > 0f && speed < deadZonePx) return

        // 3. EMA 平滑：alpha 随速度动态变化，低速更平、高速更跟手
        val alpha = (smoothing + (1f - smoothing) * (speed.coerceAtMost(24f) / 24f))
            .coerceIn(0.2f, 1f)
        val rawX = dxPx * sensitivity
        val rawY = dyPx * sensitivity
        val smoothedX = prevSmoothedX + alpha * (rawX - prevSmoothedX)
        val smoothedY = prevSmoothedY + alpha * (rawY - prevSmoothedY)
        prevSmoothedX = smoothedX
        prevSmoothedY = smoothedY

        // 4. 交给发送队列（队列内会做拆帧限幅）
        submit(toInt(smoothedX), toInt(smoothedY), buttons)
    }

    /** 手动触发一次按钮刷新（按下/抬起立即提交，不等节拍）。 */
    private fun requestFlush(btn: Int) {
        submit(0, 0, btn)
    }

    /** 浮点转整型：负值向下取整更符合坐标语义。 */
    private fun toInt(v: Float): Int = if (v >= 0) v.toInt() else (v - 1f).toInt()

    /** 重置平滑状态（如切换设备/清空累积）。 */
    fun reset() {
        prevSmoothedX = 0f
        prevSmoothedY = 0f
    }
}
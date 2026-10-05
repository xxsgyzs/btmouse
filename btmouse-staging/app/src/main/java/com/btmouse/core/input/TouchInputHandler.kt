package com.btmouse.core.input

import com.btmouse.core.state.ScrollConfig
import kotlin.math.sqrt

/**
 * TouchInputHandler —— 触控板位移算法
 *
 * 把 Compose 指针产生的**像素位移**转换成 HID 需要的**逻辑位移**，并做平滑与滤波，
 * 再提交给 SendQueue 节流发送。
 *
 * 处理流水线：
 *   像素增量 → 速度估计 → 死区过滤 → 自适应 EMA → 灵敏度映射 → 亚像素残差累积 → 提交
 *
 * 关键设计：**亚像素残差累积**
 *   位移经灵敏度/滤波后通常是小数，若直接 toInt() 截断，慢速细微移动（如 0.4px）会被取整成 0
 *   而**永久丢失**，表现为"轻微移动光标不动、一顿一顿"。这里把小数余量保留到下一帧，
 *   累积到 1px 再发出，因此慢速移动也能精确跟手。
 *
 * 关于平滑系数的方向性（重要）：
 *   [smoothing] 是**平滑下限**，越大越跟手。alpha 会随速度升高而升到 1.0，
 *   即高速移动时完全不做平滑、零额外滞后；只有低速时才启用轻度平滑来抑制抖动。
 *
 * @param submit 对外上报函数：将"逻辑位移 + 当前按钮"交出去（由 UI 层绑定到 SendQueue.submitMouse）
 * @param sensitivity 灵敏度系数（默认 1.0，越大移动越快）
 * @param smoothing   平滑下限 0..1（越大越跟手；高速时 alpha 自动升到 1.0）
 */
class TouchInputHandler(
    private val submit: (dx: Int, dy: Int, buttons: Int, wheel: Int) -> Unit,
    @Volatile var sensitivity: Float = 1.0f,
    @Volatile var smoothing: Float = 0.6f
) {

    /**
     * 滚轮映射配置。触控板双指滚动与体感滚轮条共用同一份（由 MainViewModel 注入）。
     */
    @Volatile
    var scrollConfig: ScrollConfig = ScrollConfig()

    companion object {
        /** 死区阈值（像素）：小于该值的位移忽略，抑制手指颤动的微响应。 */
        private const val DEAD_ZONE_PX = 0.7f

        /** 速度归一化上限（像素/帧）：达到该速度即认为"高速"，alpha 拉满、不做平滑。 */
        private const val SPEED_FULL = 24f

        /** alpha 下限：防止低速时过度平滑导致粘滞。 */
        private const val MIN_ALPHA = 0.2f

        /** 残差保护上限，防止极端浮点值长时间滞留。 */
        private const val MAX_RESIDUAL = 8f
    }

    /** 当前按钮状态（键盘/触控板按钮由 UI 维护，这里只透传）。 */
    @Volatile
    var buttons: Int = 0
        set(value) {
            field = value
            // 按下/抬起必须即时发出，不等节拍：直接提交一次纯按钮事件
            submit(0, 0, value, 0)
        }

    /** 上一次平滑后的逻辑位移（供 EMA 使用） */
    private var prevSmoothedX = 0f
    private var prevSmoothedY = 0f

    /** 亚像素残差：本帧未满 1px 的部分留到下一帧，避免细微移动被截断丢弃。 */
    private var residualX = 0f
    private var residualY = 0f

    /** 滚轮残差：未满 1 格的零头留到下一帧，保证慢速滚动也能生效。 */
    private var scrollResidual = 0f

    /**
     * 由 Compose/原生触摸回调调用，传入本帧的像素位移（float 保留亚像素精度）。
     * 线程：通常为主线程，内部仅做纯计算 + 交给 SendQueue 异步发送，不阻塞。
     */
    fun onDrag(dxPx: Float, dyPx: Float) {
        // 1. 速度估计：用本帧位移大小近似速度，用于自适应平滑
        val speed = sqrt(dxPx * dxPx + dyPx * dyPx)

        // 2. 死区：低速微位移直接忽略（手指静止时的抖动噪声）
        if (speed > 0f && speed < DEAD_ZONE_PX) return

        // 3. 自适应 EMA：低速轻度平滑抑制抖动，高速 alpha→1 完全直通（零额外滞后）
        val alpha = (smoothing + (1f - smoothing) * (speed.coerceAtMost(SPEED_FULL) / SPEED_FULL))
            .coerceIn(MIN_ALPHA, 1f)

        val rawX = dxPx * sensitivity
        val rawY = dyPx * sensitivity
        val smoothedX = prevSmoothedX + alpha * (rawX - prevSmoothedX)
        val smoothedY = prevSmoothedY + alpha * (rawY - prevSmoothedY)
        prevSmoothedX = smoothedX
        prevSmoothedY = smoothedY

        // 4. 亚像素残差累积后再取整，避免细微移动被截断丢弃
        val posX = smoothedX + residualX
        val posY = smoothedY + residualY
        val outX = truncate(posX)
        val outY = truncate(posY)
        residualX = (posX - outX).coerceIn(-MAX_RESIDUAL, MAX_RESIDUAL)
        residualY = (posY - outY).coerceIn(-MAX_RESIDUAL, MAX_RESIDUAL)

        if (outX != 0 || outY != 0) {
            submit(outX, outY, buttons, 0)
        }
    }

    /**
     * 滚轮入口：双指竖直滑动 → 滚轮格数（由 TouchpadScreen 的双指手势调用）。
     *
     * 与光标位移一样做**残差累积**：慢速滑动时不足 1 格的零头会留到下一帧，
     * 不会因为 toInt() 截断而永远滚不动。
     *
     * @param dxPx 双指横向像素增量（当前 HID 描述符无横向滚轮，暂不使用）
     * @param dyPx 双指纵向像素增量（屏幕坐标，向下为正）
     */
    fun onScroll(dxPx: Float, dyPx: Float) {
        @Suppress("UNUSED_VARIABLE")
        val unusedDx = dxPx // 预留：描述符暂无 AC Pan（横向滚轮）

        val cfg = scrollConfig
        // 屏幕 Y 向下为正；"手指下滑 = 内容上移"，故取负，再乘方向常量
        val raw = -dyPx / cfg.pixelsPerClick * cfg.direction * sensitivity

        val pos = raw + scrollResidual
        val out = truncate(pos)
        scrollResidual = (pos - out).coerceIn(-MAX_RESIDUAL, MAX_RESIDUAL)

        if (out != 0) {
            // 只发滚轮、不带位移：若同时带位移，主机可能把这一帧当作拖拽（会选中文本）
            submit(0, 0, buttons, out)
        }
    }

    /** 浮点转整型：向零截断；负值语义由残差补偿，避免额外的 -1 偏移破坏小位移。 */
    private fun truncate(v: Float): Int = v.toInt()

    /** 重置平滑与残差状态（如切换设备/切换模式/清空累积）。 */
    fun reset() {
        prevSmoothedX = 0f
        prevSmoothedY = 0f
        residualX = 0f
        residualY = 0f
        scrollResidual = 0f
    }
}


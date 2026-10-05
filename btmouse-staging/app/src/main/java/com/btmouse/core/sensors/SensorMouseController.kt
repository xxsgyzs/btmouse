package com.btmouse.core.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import com.btmouse.core.state.HidMode
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * SensorMouseController —— 体感鼠标控制器（模式二「桌面平移」/ 模式三「空中遥控」）
 *
 * ██ 算法总览 ██
 *
 * 【模式二 · 桌面平移】把手机平放桌面推拉，用**线性加速度**积分出位移。
 *   加速度二次积分天生会漂移，因此这里用了四道防线：
 *     1. **低通滤波**：滤掉传感器高频噪声，避免噪声被积分放大成光标抖动
 *     2. **死区**：微小加速度视为 0，配合"静止判定"抑制零偏
 *     3. **速度阻尼泄漏**：每帧对速度乘一个略小于 1 的系数。物理上不严谨，
 *        但工程上非常有效——它把"积分漂移"变成"缓慢衰减"，光标不会一直跑飞
 *     4. **ZUPT 静止零速校正**：连续若干帧加速度都在死区内 → 判定静止，强制速度归零。
 *        这是手机 IMU 上抑制漂移最实用的手段（行人航位推算里的标准做法）
 *
 * 【模式三 · 空中遥控】手持悬空转动手腕，用**陀螺仪角速度**直接映射成光标速度。
 *   角度本身就是位移的导数，所以**不需要积分**，天然不漂移：
 *     角速度 → 低通滤波 → 死区 → 乘以角速度→像素系数 → 像素/秒
 *
 * ██ 为什么不用卡尔曼滤波 ██
 *   卡尔曼在手机 IMU + 单人使用场景下收益极低，却带来两个实际问题：
 *   参数（过程噪声 Q / 观测噪声 R）几乎无法直观整定，且状态维度高、调参全靠试。
 *   低通 + 死区 + ZUPT + 阻尼的组合**参数含义明确、可解释、可现场手调**，
 *   对"让用户觉得跟手"这个目标更实用。
 *
 * ██ 线程模型 ██
 *   传感器回调（通常主线程）只做滤波与状态更新，然后 post 到内部 HandlerThread，
 *   由该线程按 EMIT_INTERVAL_MS 节流派发。回调 onCursorDelta 因此在后台线程触发，
 *   而下游（SendQueue）本身就是线程安全的入口。
 *
 * @param context 任意 Context（内部取 applicationContext）
 * @param onCursorDelta 光标位移回调 (dxPx, dyPx)，在内部后台线程调用
 * @param onScrollDelta 滚轮回调 (dyPx)，屏幕坐标（向下为正）
 */
class SensorMouseController(
    context: Context,
    private val onCursorDelta: (dx: Float, dy: Float) -> Unit,
    private val onScrollDelta: (dyPx: Float) -> Unit
) : SensorEventListener {

    private val appContext = context.applicationContext
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val thread = HandlerThread("sensor-mouse", Process.THREAD_PRIORITY_BACKGROUND)
        .apply { start() }
    private val handler = Handler(thread.looper)

    // ---------------- 状态（sensor 线程写、handler 线程读，故用 @Volatile） ----------------

    @Volatile
    private var mode: HidMode? = null

    @Volatile
    private var active = false

    // ---------------- 静止判定（ZUPT） ----------------

    /** 连续处于死区内的帧数；超过阈值即判定静止并强制速度归零。 */
    private var stillFrames = 0
    private var still = true

    // ---------------- 低通滤波状态 ----------------

    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f

    /** 模式二的线性加速度滤波值（m/s²）。 */
    private var linX = 0f
    private var linY = 0f

    /** 模式三的角速度滤波值（rad/s）。 */
    private var gyroX = 0f
    private var gyroY = 0f

    // ---------------- 积分状态（仅 handler 线程访问） ----------------

    private var velX = 0f
    private var velY = 0f

    /** 待派发的像素累积（handler 线程）。 */
    private var accPxX = 0f
    private var accPxY = 0f

    private var lastEmitNs = 0L
    private var lastStillEventNs = 0L

    private var registeredMode: HidMode? = null

    /** 传感器是否可用（如设备无陀螺仪时，模式三会降级）。 */
    fun isAvailable(target: HidMode): Boolean = resolveSensor(target) != null

    private fun resolveSensor(target: HidMode): Int? {
        val sm = sensorManager ?: return null
        return when (target) {
            HidMode.DESK ->
                if (sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null) {
                    Sensor.TYPE_LINEAR_ACCELERATION
                } else {
                    // 少数设备没有 LINEAR_ACCELERATION，退回原始加速度计（用重力低通自己减）
                    if (sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) {
                        Sensor.TYPE_ACCELEROMETER
                    } else {
                        null
                    }
                }

            HidMode.AIR ->
                if (sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null) {
                    Sensor.TYPE_GYROSCOPE
                } else {
                    null
                }

            HidMode.TRACKPAD -> null
        }
    }

    /**
     * 启动指定模式。重复调用会先停止上一个模式。
     * TRACKPAD 模式下等价于 [stop]。
     */
    fun start(target: HidMode) {
        if (target == HidMode.TRACKPAD) {
            stop()
            return
        }
        val sm = sensorManager
        val type = resolveSensor(target)
        if (sm == null || type == null) {
            Log.w(TAG, "模式 $target 所需传感器不可用，无法启动")
            return
        }
        if (active && registeredMode == target) return

        stop()

        val sensor = sm.getDefaultSensor(type) ?: return
        val rate = when (target) {
            HidMode.DESK -> SensorManager.SENSOR_DELAY_GAME
            else -> SensorManager.SENSOR_DELAY_GAME
        }
        val ok = sm.registerListener(this, sensor, rate, handler)
        if (!ok) {
            Log.e(TAG, "registerListener 失败: $target")
            return
        }

        resetState()
        registeredMode = target
        mode = target
        active = true
        Log.i(TAG, "传感器已启动: $target (sensorType=$type)")
    }

    /** 停止并注销传感器；会清空积分状态避免下次启动时残留速度。 */
    fun stop() {
        sensorManager?.unregisterListener(this)
        active = false
        registeredMode = null
        mode = null
        handler.post { resetIntegration() }
    }

    /** 释放线程资源（前台服务销毁时调用）。 */
    fun release() {
        stop()
        thread.quitSafely()
    }

    private fun resetState() {
        stillFrames = 0
        still = true
        gravityX = 0f; gravityY = 0f; gravityZ = 0f
        linX = 0f; linY = 0f
        gyroX = 0f; gyroY = 0f
        lastStillEventNs = 0L
        handler.post { resetIntegration() }
    }

    private fun resetIntegration() {
        velX = 0f; velY = 0f
        accPxX = 0f; accPxY = 0f
        lastEmitNs = 0L
    }

    // ---------------- 传感器回调 ----------------

    override fun onSensorChanged(event: SensorEvent) {
        if (!active) return
        val dt = frameSeconds(event.timestamp)
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION ->
                updateDesk(event.values[0], event.values[1], event.values[2], dt)

            Sensor.TYPE_ACCELEROMETER ->
                updateDeskFromRaw(event.values[0], event.values[1], event.values[2], dt)

            Sensor.TYPE_GYROSCOPE ->
                updateAir(event.values[0], event.values[1], dt)

            else -> return
        }
        handler.post { emitIfDue() }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit // 不使用精度事件

    /** 相邻两次事件的间隔（秒），钳制到合理区间以抵御丢帧/时间戳跳变。 */
    private fun frameSeconds(eventNs: Long): Float {
        if (lastStillEventNs == 0L) {
            lastStillEventNs = eventNs
            return MAX_DT_S
        }
        val dt = (eventNs - lastStillEventNs) / 1_000_000_000f
        lastStillEventNs = eventNs
        return dt.coerceIn(MIN_DT_S, MAX_DT_S)
    }

    // ---------------- 模式二：桌面平移 ----------------

    /**
     * @param ax,ay,az 线性加速度（已去重力，单位 m/s²）
     */
    private fun updateDesk(ax: Float, ay: Float, az: Float, dt: Float) {
        linX = lowPass(linX, ax, TUNING.motionAlpha)
        linY = lowPass(linY, ay, TUNING.motionAlpha)
        integrateMotion(linX, linY, az, dt)
    }

    /**
     * 没有 TYPE_LINEAR_ACCELERATION 时的退化路径：
     * 用低通提取重力分量，再用原始值减去它得到线性加速度。
     */
    private fun updateDeskFromRaw(ax: Float, ay: Float, az: Float, dt: Float) {
        gravityX = lowPass(gravityX, ax, TUNING.gravityAlpha)
        gravityY = lowPass(gravityY, ay, TUNING.gravityAlpha)
        gravityZ = lowPass(gravityZ, az, TUNING.gravityAlpha)

        val lx = ax - gravityX
        val ly = ay - gravityY
        val lz = az - gravityZ

        linX = lowPass(linX, lx, TUNING.motionAlpha)
        linY = lowPass(linY, ly, TUNING.motionAlpha)

        integrateMotion(linX, linY, lz, dt)
    }

    /**
     * 把线性加速度积分成速度（并在 handler 线程里进一步积分成像素，见 [emitIfDue]）。
     */
    private fun integrateMotion(ax: Float, ay: Float, az: Float, dt: Float) {
        val magnitude = sqrt(ax * ax + ay * ay + az * az)

        // ZUPT：连续多帧都在死区内 → 判定静止
        if (magnitude < TUNING.deadZoneMs2) {
            stillFrames++
            if (stillFrames >= TUNING.stillFramesForZupt) still = true
        } else {
            stillFrames = 0
            still = false
        }

        handler.post {
            if (still) {
                velX = 0f
                velY = 0f
                return@post
            }
            // v += a * dt
            velX += ax * dt
            velY += ay * dt
            // 阻尼泄漏：把积分漂移变成缓慢衰减，光标不会一直跑飞
            val damp = TUNING.velocityDamp
            velX *= damp
            velY *= damp
            // 速度死区：极小的残余速度直接归零，避免"缓慢蠕动"
            if (abs(velX) < TUNING.velocityDeadZoneMs) velX = 0f
            if (abs(velY) < TUNING.velocityDeadZoneMs) velY = 0f
        }
    }

    // ---------------- 模式三：空中遥控 ----------------

    /**
     * @param wx,wz 陀螺仪角速度（rad/s）：wx 绕 X 轴、wz 绕 Z 轴
     */
    private fun updateAir(wx: Float, wz: Float, dt: Float) {
        @Suppress("UNUSED_VARIABLE")
        val unusedDt = dt // 角速度直接映射为像素速度，无需积分，故不使用 dt

        gyroX = lowPass(gyroX, wx, TUNING.gyroAlpha)
        gyroY = lowPass(gyroY, wz, TUNING.gyroAlpha)
    }

    // ---------------- 节流派发（handler 线程） ----------------

    /**
     * 按 EMIT_INTERVAL_MS 节流，把累积的物理量换算为像素后一次性派发。
     *
     * 模式二在此处做"速度→像素"的积分；模式三直接用角速度算像素速度。
     */
    private fun emitIfDue() {
        if (!active) return
        val current = mode ?: return

        val now = System.nanoTime()
        if (lastEmitNs != 0L) {
            val elapsedNs = now - lastEmitNs
            if (elapsedNs < EMIT_INTERVAL_MS * 1_000_000L) return
        }
        val seconds = if (lastEmitNs == 0L) {
            EMIT_INTERVAL_MS / 1000f
        } else {
            ((now - lastEmitNs) / 1_000_000_000f).coerceIn(MIN_DT_S, 0.1f)
        }
        lastEmitNs = now

        when (current) {
            HidMode.DESK -> {
                // 速度(m/s) → 像素：先按 m/s²→像素的标定比例换算，再乘积分时间
                accPxX += velX * TUNING.deskAccelScale * seconds
                accPxY += velY * TUNING.deskAccelScale * seconds
                flushCursor()
            }

            HidMode.AIR -> {
                // 角速度(rad/s) → 像素/秒，无需积分
                accPxX += gyroY * TUNING.airGyroScale * seconds
                accPxY += gyroX * TUNING.airGyroScale * seconds
                flushCursor()
            }

            HidMode.TRACKPAD -> Unit
        }
    }

    /** 把累积像素取整派发，余量留到下一帧。 */
    private fun flushCursor() {
        if (abs(accPxX) < MIN_PIXEL_TO_EMIT && abs(accPxY) < MIN_PIXEL_TO_EMIT) return
        onCursorDelta(accPxX, accPxY)
        accPxX = 0f
        accPxY = 0f
    }

    /**
     * 由 UI 的滚轮条调用：把竖向滑动像素交给上层换算成滚轮格数。
     * 传感器线程安全（直接转发，由上层做残差累积）。
     */
    fun scrollBy(dyPx: Float) {
        if (dyPx != 0f) onScrollDelta(dyPx)
    }

    /** 一阶低通滤波：y[n] = a*x[n] + (1-a)*y[n-1]，a 越大越"跟手"、越小越平滑。 */
    private fun lowPass(prev: Float, input: Float, alpha: Float): Float {
        val a = alpha.coerceIn(0f, 1f)
        return prev + a * (input - prev)
    }

    // ---------------- 可现场整定的参数 ----------------

    /**
     * 体感算法整定参数。
     *
     * 这些值决定"手感"，必须真机试出来，因此集中放在一处便于修改：
     *  - 觉得光标太"飘"/抖 → 调小 motionAlpha、gyroAlpha（更强的低通）
     *  - 觉得跟手不够、迟滞 → 调大 motionAlpha、gyroAlpha
     *  - 静止时仍缓慢跑动 → 调大 deadZoneMs2 或 stillFramesForZupt，或调小 velocityDamp
     *  - 移动幅度太小/太大 → 调 deskAccelScale / airGyroScale
     */
    data class Tuning(
        /** 线性加速度低通系数（0..1）。 */
        val motionAlpha: Float = 0.45f,
        /** 陀螺仪低通系数（0..1）。 */
        val gyroAlpha: Float = 0.35f,
        /** 重力估计低通系数（仅退化路径使用）。 */
        val gravityAlpha: Float = 0.08f,
        /** 桌面模式死区（m/s²）：合加速度模长小于此值视为"没在动"。 */
        val deadZoneMs2: Float = 0.06f,
        /** 连续多少帧处于死区即判定静止（ZUPT），按 100Hz 采样约 0.12s。 */
        val stillFramesForZupt: Int = 12,
        /** 速度阻尼泄漏系数（每帧），略微小于 1。 */
        val velocityDamp: Float = 0.93f,
        /** 速度死区（m/s）：小于此值直接归零，消除缓慢蠕动。 */
        val velocityDeadZoneMs: Float = 0.012f,
        /** 桌面模式标定：1 m/s 的速度对应多少像素/秒。 */
        val deskAccelScale: Float = 50f,
        /** 空中模式标定：1 rad/s 的角速度对应多少像素/秒。 */
        val airGyroScale: Float = 18f
    )

    companion object {
        private const val TAG = "SensorMouseController"

        /** 派发节流：约 125Hz，与触控通路的节奏一致。 */
        private const val EMIT_INTERVAL_MS = 8L

        /** 小于 1px 的累积不派发，留到下一帧，避免高频空转。 */
        private const val MIN_PIXEL_TO_EMIT = 0.6f

        private const val MIN_DT_S = 0.001f
        private const val MAX_DT_S = 0.05f

        /** 可在运行期整体替换（真机调参用）。 */
        @JvmStatic
        @Volatile
        var TUNING = Tuning()
    }
}


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
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * SensorMouseController —— 体感鼠标控制器（模式二「桌面平移」/ 模式三「空中遥控」）
 *
 * ██ BATCH-3.5 重大变更：模式二从「加速度计积分」改为「陀螺仪」██
 *
 * 上一版用线性加速度二次积分求位移，真机实测**完全不可用**（漂移严重、几乎推不动），
 * 原因不是参数没调好，而是方案本身的物理缺陷：
 *   · 传感器零偏哪怕只有 0.01 m/s²，二次积分后位移随时间**平方增长**，必然跑飞；
 *   · 单纯推拉手机产生的是很短促的加速度脉冲，积分出的位移远小于直觉预期；
 *   · 要压住漂移就得大幅阻尼，而阻尼一大又"推不动"——这是个死结。
 *
 * 新方案：**用陀螺仪的角速度直接映射为光标速度**。
 *   角速度本身就是"位移的导数"，只做一次映射、**不做任何积分**，因此天然不漂移。
 *   操作直觉也成立：转动手机 → 光标移动；停止转动 → 光标立即停；摆正手机 → 自动停止。
 *
 * ██ 为什么"转手机"等价于"推鼠标"██
 *   本模式面向"手机平放桌面"的使用方式：手掌压在手机上做小幅转动，
 *   屏幕上的光标就朝着转动方向滑动；手一停光标就停，不需要回中。
 *   —— 这与上一版"推拉手机求位移"的交互目标一致，但数学上是稳定的。
 *
 * ██ 算法管线 ██
 *   陀螺仪 ω(rad/s)
 *     → 1. 一阶低通（滤高频噪声，避免光标细碎抖动）
 *     → 2. 死区（|ω| < DEAD_ZONE_RAD_S 视为 0，抑制手抖与陀螺零偏）
 *     → 3. 标定映射（rad/s → 像素/秒）
 *     → 4. 姿态闸门（加速度计 + 互补滤波估计倾角；接近水平则强制归零 → "摆正自动停止"）
 *     → 5. 亚像素累积后按 8ms 节流派发
 *
 * ██ 互补滤波在这里的作用 ██
 *   陀螺仪动态准但会漂移，加速度计静态准但噪声大。互补滤波把两者按权重融合：
 *     angle = (1-w)·(angle + ω·dt) + w·accelAngle
 *   本类用它估计手机的 roll/pitch，作为"是否已摆正"的判据。
 *   （注意：它**只用于姿态闸门**，光标速度本身不经过积分，所以不存在积分漂移。）
 *
 * ██ 线程模型 ██
 *   传感器回调直接运行在内部 HandlerThread（registerListener 的第 4 个参数），
 *   滤波与派发都在同一线程完成，无需额外 post；下游 SendQueue 本身线程安全。
 *
 * @param context 任意 Context（内部取 applicationContext）
 * @param onCursorDelta 光标位移回调 (dxPx, dyPx)
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

    // ---------------- 运行状态 ----------------

    @Volatile
    private var mode: HidMode? = null

    @Volatile
    private var active = false

    private var registeredMode: HidMode? = null

    // ---------------- 陀螺仪低通状态 ----------------

    private var gyroX = 0f
    private var gyroY = 0f

    // ---------------- 互补滤波 / 姿态估计状态 ----------------

    /** 估计的 roll（绕 Y 轴，右倾为正）与 pitch（绕 X 轴，前倾为正），单位弧度。 */
    private var rollRad = 0f
    private var pitchRad = 0f

    /** 上一帧时间戳（纳秒），用于互补滤波的积分项。 */
    private var lastFrameNs = 0L

    /** 加速度计是否可用（决定姿态闸门是否生效）。 */
    private var hasAccel = false

    /** 是否已用加速度计初始化过一次姿态角（避免启动瞬间基准为 0 导致闸门误判）。 */
    private var poseSeeded = false

    // ---------------- 派发状态 ----------------

    private var accPxX = 0f
    private var accPxY = 0f
    private var lastEmitNs = 0L

    /**
     * 传感器是否可用（无陀螺仪的设备无法使用体感模式）。
     */
    fun isAvailable(target: HidMode): Boolean = when (target) {
        HidMode.DESK, HidMode.AIR -> sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        HidMode.TRACKPAD -> false
    }

    /**
     * 启动指定模式。重复调用会先停止上一个模式。
     * TRACKPAD 模式下等价于 [stop]。
     *
     * 模式二与模式三共用同一套测速管线（都基于陀螺仪），只是**标定系数不同**：
     * 模式三手持悬空，转动幅度大，用较小的系数；模式二平放桌面，转动幅度小，用较大的系数。
     */
    fun start(target: HidMode) {
        if (target == HidMode.TRACKPAD) {
            stop()
            return
        }
        val sm = sensorManager ?: return
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (gyro == null) {
            Log.w(TAG, "本机没有陀螺仪，无法使用模式 $target")
            return
        }
        if (active && registeredMode == target) return

        stop()

        // 陀螺仪是必需的；加速度计用于姿态闸门，缺失时降级（闸门不生效，仅靠死区）
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        hasAccel = accel != null

        val gyroOk = sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME, handler)
        var accelOk = true
        if (accel != null) {
            accelOk = sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME, handler)
        }

        if (!gyroOk) {
            Log.e(TAG, "registerListener(gyro) 失败: $target")
            return
        }
        if (!accelOk) {
            hasAccel = false
            Log.w(TAG, "registerListener(accel) 失败，姿态闸门关闭")
        }

        resetState()
        registeredMode = target
        mode = target
        active = true
        Log.i(TAG, "陀螺仪已启动: $target (accel=$hasAccel)")
    }

    /** 停止并注销传感器；清空累积避免下次启动残留。 */
    fun stop() {
        sensorManager?.unregisterListener(this)
        active = false
        registeredMode = null
        mode = null
        accPxX = 0f
        accPxY = 0f
        lastEmitNs = 0L
    }

    /** 释放线程资源。 */
    fun release() {
        stop()
        thread.quitSafely()
    }

    private fun resetState() {
        gyroX = 0f
        gyroY = 0f
        rollRad = 0f
        pitchRad = 0f
        lastFrameNs = 0L
        poseSeeded = false
        accPxX = 0f
        accPxY = 0f
        lastEmitNs = 0L
    }

    // ---------------- 传感器回调（运行在内部 HandlerThread） ----------------

    override fun onSensorChanged(event: SensorEvent) {
        if (!active) return

        val now = event.timestamp
        val dt = frameSeconds(now)

        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val tuning = TUNING

                // 1) 低通滤波
                gyroX = lowPass(gyroX, event.values[0], tuning.gyroAlpha)
                gyroY = lowPass(gyroY, event.values[1], tuning.gyroAlpha)

                // 2) 死区：抑制手抖与陀螺零偏
                val wx = applyDeadZone(gyroX, tuning.deadZoneRadS)
                val wy = applyDeadZone(gyroY, tuning.deadZoneRadS)

                // 3) 陀螺积分推进姿态角（互补滤波的"预测"步）
                rollRad += wy * dt
                pitchRad += wx * dt

                // 4) 姿态闸门：手机接近水平时强制停止（"摆正自动停止"）
                if (hasAccel && isLevel()) {
                    gyroX = 0f
                    gyroY = 0f
                    return
                }

                // 5) 映射为像素（角速度 → 像素/秒），累加到当前节拍
                val pixelsPerRad = pixelsPerRadPerSecond()
                accPxX += wy * pixelsPerRad * dt
                accPxY += wx * pixelsPerRad * dt

                emitIfDue(now)
            }

            Sensor.TYPE_ACCELEROMETER -> {
                if (!hasAccel) return
                val tuning = TUNING
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]

                // 由重力方向估算静态倾角（手机水平、屏幕朝上时为 0）
                val accelRoll = atan2(ay, az)
                val accelPitch = atan2(-ax, sqrt(ay * ay + az * az))

                // 互补滤波的"校正"步：动态用陀螺、静态用加速度计
                if (!poseSeeded) {
                    // 首个加速度计样本：直接采纳静态倾角作为基准。
                    // 否则姿态角会从 0 缓慢收敛，若用户一开始就斜着拿手机，
                    // "摆正自动停止"闸门会在头一两秒内误判为水平而锁死光标。
                    rollRad = accelRoll
                    pitchRad = accelPitch
                    poseSeeded = true
                }

                val w = tuning.accelTrust
                if (w > 0f) {
                    rollRad = (1f - w) * rollRad + w * accelRoll
                    pitchRad = (1f - w) * pitchRad + w * accelPitch
                }
            }

            else -> return
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit // 不使用精度事件

    /** 相邻两次事件间隔（秒），钳制以防丢帧/时间戳跳变。 */
    private fun frameSeconds(eventNs: Long): Float {
        if (lastFrameNs == 0L) {
            lastFrameNs = eventNs
            return MAX_DT_S
        }
        val dt = (eventNs - lastFrameNs) / 1_000_000_000f
        lastFrameNs = eventNs
        return dt.coerceIn(MIN_DT_S, MAX_DT_S)
    }

    /**
     * 是否已"摆正"：roll 与 pitch 都接近水平。
     *
     * 这是**摆正自动停止**逻辑：把手机转回水平姿态后，
     * 即使陀螺仍有微小残余输出，也会被彻底归零，光标不会缓慢蠕动。
     */
    private fun isLevel(): Boolean {
        val limit = TUNING.levelAngleRad
        return abs(rollRad) < limit && abs(pitchRad) < limit
    }

    /**
     * 角速度 → 像素/秒 的标定系数。
     *
     * 模式三手持悬空转动幅度大 → 系数小一些；
     * 模式二平放桌面只能小幅转动 → 系数大一些，否则"转半天不动"。
     */
    private fun pixelsPerRadPerSecond(): Float = when (mode) {
        HidMode.DESK -> TUNING.deskGyroScale
        HidMode.AIR -> TUNING.airGyroScale
        else -> 0f
    }

    /** 按节流派发累积像素；小于阈值的余量留到下一帧。 */
    private fun emitIfDue(nowNs: Long) {
        if (lastEmitNs != 0L) {
            val elapsed = nowNs - lastEmitNs
            if (elapsed < EMIT_INTERVAL_NS) return
        }
        lastEmitNs = nowNs

        if (abs(accPxX) < MIN_PIXEL_TO_EMIT && abs(accPxY) < MIN_PIXEL_TO_EMIT) return

        onCursorDelta(accPxX, accPxY)
        accPxX = 0f
        accPxY = 0f
    }

    /**
     * 由 UI 的滚轮条调用：把竖向滑动像素交给上层换算成滚轮格数。
     * 直接转发，由上层（TouchInputHandler）做残差累积与参数统一。
     */
    fun scrollBy(dyPx: Float) {
        if (dyPx != 0f) onScrollDelta(dyPx)
    }

    /** 一阶低通：y[n] = a·x[n] + (1-a)·y[n-1]。a 越大越跟手，越小越平滑。 */
    private fun lowPass(prev: Float, input: Float, alpha: Float): Float {
        val a = alpha.coerceIn(0f, 1f)
        return prev + a * (input - prev)
    }

    /** 死区：模长小于阈值的角速度直接归零。 */
    private fun applyDeadZone(value: Float, zone: Float): Float =
        if (abs(value) < zone) 0f else value

    // ---------------- 真机整定参数 ----------------

    /**
     * 体感算法整定参数（集中在此便于真机微调）。
     *
     * 调参速查：
     *  · 光标太飘/抖        → 调小 [gyroAlpha]（更强的低通）
     *  · 跟手不够、迟滞      → 调大 [gyroAlpha]
     *  · 静止时仍在缓慢移动   → 调大 [deadZoneRadS]，或调大 [levelAngleRad]
     *  · 转很久才动一点      → 调大 [deskGyroScale]
     *  · 一动就飞出去        → 调小 [deskGyroScale]
     *  · 摆正后不停止        → 调大 [levelAngleRad]（放宽"水平"的判定）
     *  · 姿态闸门误触发      → 调小 [levelAngleRad]，或把 [accelTrust] 设为 0 关闭闸门
     */
    data class Tuning(
        /** 陀螺仪低通系数（0..1）。 */
        val gyroAlpha: Float = 0.30f,
        /** 陀螺死区（rad/s）。0.05 rad/s ≈ 2.9°/s，可压住手抖与常见零偏。 */
        val deadZoneRadS: Float = 0.05f,
        /** 姿态闸门角度（rad）。0.10 rad ≈ 5.7°，手机接近水平即强制停止。 */
        val levelAngleRad: Float = 0.10f,
        /** 互补滤波中加速度计的权重（0..1）。0 表示关闭姿态闸门。 */
        val accelTrust: Float = 0.02f,
        /** 模式二（平放桌面）标定：1 rad/s 对应多少像素/秒。 */
        val deskGyroScale: Float = 500f,
        /** 模式三（手持悬空）标定：1 rad/s 对应多少像素/秒。 */
        val airGyroScale: Float = 260f
    )

    companion object {
        private const val TAG = "SensorMouseController"

        /** 派发节流：约 125Hz，与触控通路一致。 */
        private const val EMIT_INTERVAL_MS = 8L
        private const val EMIT_INTERVAL_NS = EMIT_INTERVAL_MS * 1_000_000L

        /** 小于该像素量不派发，余量留到下一帧（亚像素累积）。 */
        private const val MIN_PIXEL_TO_EMIT = 0.5f

        private const val MIN_DT_S = 0.001f
        private const val MAX_DT_S = 0.05f

        /** 可在运行期整体替换（真机调参用）。 */
        @JvmStatic
        @Volatile
        var TUNING = Tuning()
    }
}


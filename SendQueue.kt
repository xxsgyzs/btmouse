package com.btmouse.core.hid

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log

/**
 * SendQueue —— 发送节流队列
 *
 * 问题背景：触摸事件可高达 ~120-240Hz，若每个事件都立刻调用 sendReport，会高频压垮蓝牙栈、
 *          并让发送逻辑嵌套在触摸回调里造成主线程卡顿。此处把这些高频增量合并成
 *          "定时批量 report" 再按蓝牙节奏发出。
 *
 * 设计要点：
 *  - 独立 HandlerThread + 自适应节拍发送。
 *  - **单一节拍链（关键）**：同一时刻只允许存在一个待执行的节拍任务，用 [tickScheduled] 保证幂等。
 *    否则每个触摸事件都会新增一条 postDelayed 链，消息队列无界增长，表现为"越滑越卡"。
 *  - **自适应节拍**：有数据待发时用 [ACTIVE_INTERVAL_MS]（4ms，约 250Hz）；一旦发送完且没有新数据，
 *    节拍链**自然停止**，完全不占用 CPU、不耗电。下一次 submit 会立刻重新起链，因此空闲不影响手感。
 *  - **增量合并**：同一个节拍内的多次 submit 会先累加到 pending 缓冲，到点合并成一包发，
 *    而不是无脑排队，因此**不存在无界队列**，天然不会 OOM。
 *  - **拆帧限幅**：单帧位移超 ±127（HID 有符号字节上限）时会拆成多包连续发送，剩余留到下一帧。
 *
 * 线程模型：[pendingX] / [pendingY] / [pendingWheel] / [pendingButtons] / [tickScheduled]
 *          全部只在 [handler] 所在线程读写（submit 通过 post 进入该线程），因此无需加锁。
 *
 * @param sendReport 实际的报告发送函数（由蓝牙层注入，避免本类强依赖单例）。
 * @param frameIntervalMs 兼容保留参数，已不再作为固定节拍使用（改为自适应节拍）。
 */
class SendQueue(
    private val sendReport: (ByteArray) -> Boolean,
    @Suppress("UNUSED_PARAMETER") frameIntervalMs: Long = 8L
) {

    private val tag = "SendQueue"

    /** 后台发送线程，优先级设为后台，避免影响 UI 帧率。 */
    private val thread = HandlerThread("hid-send", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val handler = Handler(thread.looper)

    // ---------------- 待发送状态（仅 handler 线程访问） ----------------

    private var pendingX = 0
    private var pendingY = 0
    private var pendingWheel = 0
    private var pendingButtons = 0

    /** 是否已有一个节拍任务在排队；用于保证"同一时刻只有一条节拍链"。 */
    private var tickScheduled = false

    /** 本帧节拍结束后是否还要继续调度（有数据或仍有并发 submit 时）。 */
    private var keepTicking = false

    private companion object {
        /** HID 单帧位移上限（有符号单字节）。 */
        private const val MAX_DELTA = 127

        /** 有数据时的节拍间隔：约 250Hz，逼近蓝牙 HID 中断通道的实际吞吐。 */
        private const val ACTIVE_INTERVAL_MS = 4L

        /** 一帧节拍内最多连续发送的报告包数，防止极端值长时间占用蓝牙栈。 */
        private const val MAX_BURST = 32

        /** 单轴累积位移的溢出保护（像素）。防止长时间积压导致需要极多包才能发完。 */
        private const val MAX_PENDING = 4096
    }

    /**
     * 提交一次鼠标增量（可来自触摸回调 / 传感器回调，任意线程）。
     * 只累加到 pending，真正的发送在节拍回调里进行。
     *
     * @param wheel 滚轮增量（0 表示不带滚轮）
     */
    @JvmOverloads
    fun submitMouse(dx: Int, dy: Int, buttons: Int, wheel: Int = 0) {
        handler.post {
            pendingX = (pendingX + dx).coerceIn(-MAX_PENDING, MAX_PENDING)
            pendingY = (pendingY + dy).coerceIn(-MAX_PENDING, MAX_PENDING)
            pendingWheel = (pendingWheel + wheel).coerceIn(-MAX_PENDING, MAX_PENDING)
            pendingButtons = buttons
            keepTicking = true
            scheduleTick()
        }
    }

    /**
     * 节拍调度（幂等）：只有当前没有待执行节拍时才 postDelayed。
     * 这是修复"消息队列堆积导致延迟"的关键 —— 绝不重复安排多条节拍链。
     */
    private fun scheduleTick() {
        if (tickScheduled) return
        if (!hasPending() && !keepTicking) return
        tickScheduled = true
        handler.postDelayed({ onTick() }, ACTIVE_INTERVAL_MS)
    }

    /** 节拍回调：清标志 → 发一帧 → 视情况继续调度（自适应间隔）。 */
    private fun onTick() {
        tickScheduled = false
        keepTicking = false

        drain()

        if (hasPending() || keepTicking) {
            // 还有数据：用低延迟间隔立刻续上，形成连续发送
            scheduleTick()
        }
        // 无数据则自然停止，不空转；下次 submit 会重新起链
    }

    private fun hasPending(): Boolean =
        pendingX != 0 || pendingY != 0 || pendingWheel != 0 || pendingButtons != 0

    /**
     * 把一个节拍内的累加增量合成 report 并发送。
     * 位移超过 ±127 时拆为多包，剩余未发部分仍留在 pending，下个节拍继续发。
     */
    private fun drain() {
        // 先取走并清空该节拍的增量
        var x = pendingX
        var y = pendingY
        var w = pendingWheel
        val buttons = pendingButtons
        pendingX = 0
        pendingY = 0
        pendingWheel = 0
        pendingButtons = 0

        // 拆帧：把超过单字节上限的位移拆成多包；上限保护，防止某帧极端值导致死循环
        var guard = 0
        while ((x != 0 || y != 0 || w != 0 || (guard == 0 && buttons != 0)) && guard < MAX_BURST) {
            val dx = clampDelta(x); if (dx != 0) x -= dx else if (x != 0) x = 0
            val dy = clampDelta(y); if (dy != 0) y -= dy else if (y != 0) y = 0
            val dw = clampDelta(w); if (dw != 0) w -= dw else if (w != 0) w = 0

            // 按键只在首包携带，后续拆帧包不再重复按下的状态
            val report = MouseReportBuilder.build(
                btn = if (guard == 0) buttons else 0,
                dx = dx,
                dy = dy,
                wheel = dw
            )
            val ok = sendReport(report)
            if (!ok) {
                // 发送失败（如未连接 / HID 未就绪），丢弃剩余增量避免堆积
                Log.w(tag, "sendReport failed, dropping pending")
                pendingX = 0
                pendingY = 0
                pendingWheel = 0
                return
            }
            guard++
        }

        // 未发完的部分（达到 MAX_BURST 上限）留到下一节拍继续，不丢失
        if (x != 0 || y != 0 || w != 0) {
            pendingX = x
            pendingY = y
            pendingWheel = w
        }
    }

    /** 把待发送位移限制到 [-127, 127] 单帧能力；超出部分留在调用方继续拆。 */
    private fun clampDelta(v: Int): Int = when {
        v > MAX_DELTA -> MAX_DELTA
        v < -MAX_DELTA -> -MAX_DELTA
        else -> v
    }

    /** 停止队列并释放线程资源（前台服务 onDestroy 时调用）。 */
    fun release() {
        handler.removeCallbacksAndMessages(null)
        tickScheduled = false
        keepTicking = false
        thread.quitSafely()
    }
}

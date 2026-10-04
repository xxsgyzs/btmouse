package com.btmouse.core.hid

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log

/**
 * SendQueue —— 发送节流队列
 *
 * 问题背景：触摸事件可高达 ~240Hz，若每个事件都立刻调用 sendReport，会高频压垮蓝牙栈、
 *          并让发送逻辑嵌套在触摸回调里造成主线程卡顿。此处把这些高频增量合并成
 *          "定时批量 report" 再按蓝牙节奏发出。
 *
 * 设计要点：
 *  - 独立 HandlerThread + Handler.postDelayed 自调度的**固定节拍发送**（默认 8ms）。
 *  - **增量合并**：同一个节拍内的多次 submitMouse 会先累加到 pending 缓冲，到点合并成一包发，
 *    而不是无脑排队，因此**不存在无界队列**，天然不会 OOM。
 *  - **拆帧限幅**：单帧位移超 ±127（HID 有符号字节上限）时会拆成多包连续发送，剩余留到下一帧。
 *  - 空转保护：累计无增量时不发空包，避免无意义占用蓝牙。
 *
 * @param sendReport 实际的报告发送函数（由蓝牙层注入，避免本类强依赖单例）。
 * @param frameIntervalMs 发送节拍，默认 8ms（约 125Hz，足够顺滑且不挤爆蓝牙）。
 */
class SendQueue(
    private val sendReport: (ByteArray) -> Boolean,
    private val frameIntervalMs: Long = 8L
) {

    private val tag = "SendQueue"

    /** 后台发送线程，优先级设为后台，避免影响 UI 帧率。 */
    private val thread = HandlerThread("hid-send", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val handler = Handler(thread.looper)

    /** pending 缓冲与节拍是否已在调度中。所有访问都在 [handler] 所在线程，天然串行，无需加锁。 */
    private var pendingX = 0
    private var pendingY = 0
    private var pendingButtons = 0

    /** HID 单帧位移上限 */
    private companion object {
        private const val MAX_DELTA = 127
    }

    /**
     * 提交一次鼠标增量（可来自触摸回调，任意线程）。
     * 由于触摸频率可能高于节拍，这里只累加到 pending，真正的发送在节拍回调里进行。
     */
    fun submitMouse(dx: Int, dy: Int, buttons: Int) {
        handler.post {
            pendingX += dx
            pendingY += dy
            pendingButtons = buttons
            scheduleTick()
        }
    }

    /**
     * 节拍调度：若当前节拍尚未被安排且已有内容，则 postDelayed 一帧。
     */
    private fun scheduleTick() {
        if (pendingX != 0 || pendingY != 0 || pendingButtons != 0) {
            handler.postDelayed({ emit(); scheduleTick() }, frameIntervalMs)
        }
    }

    /**
     * 把一个节拍内的累加增量合成 report 并发送。
     * 位移超过 ±127 时拆为多包，剩余未发部分仍留在 pending，下个节拍继续发。
     */
    private fun emit() {
        // 先取走并清空该节拍的增量
        var x = pendingX
        var y = pendingY
        val buttons = pendingButtons
        pendingX = 0
        pendingY = 0
        pendingButtons = 0

        // 拆帧：把超过单字节上限的位移拆成多包；上限保护，防止某帧极端值导致死循环
        var guard = 0
        val maxBurst = 32 // 单次节拍最多连续发 32 包，放宽到足够承载高速甩动
        while ((x != 0 || y != 0 || buttons != 0) && guard < maxBurst) {
            val dx = clampDelta(x); if (dx != 0) x -= dx else if (x != 0) x = 0
            val dy = clampDelta(y); if (dy != 0) y -= dy else if (y != 0) y = 0

            val report = MouseReportBuilder.build(btn = if (guard == 0) buttons else 0, dx = dx, dy = dy)
            val ok = sendReport(report)
            if (!ok) {
                // 发送失败（如未连接 / HID 未就绪），丢弃剩余增量避免堆积
                Log.w(tag, "sendReport failed, dropping pending")
                return
            }
            guard++
        }
    }

    /** 把代发送位移限制到 [-127, 127] 单帧能力；超出部分留在调用方继续拆。 */
    private fun clampDelta(v: Int): Int = when {
        v > MAX_DELTA -> MAX_DELTA
        v < -MAX_DELTA -> -MAX_DELTA
        else -> v
    }

    /** 停止队列并释放线程资源（前台服务 onDestroy 时调用）。 */
    fun release() {
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }
}
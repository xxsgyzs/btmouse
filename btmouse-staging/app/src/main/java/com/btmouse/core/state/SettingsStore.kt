package com.btmouse.core.state

import android.content.Context

/**
 * MouseSettings —— 手感相关参数
 *
 * @property sensitivity       灵敏度倍率（1.0 为基准，越大光标越快）
 * @property smoothing         平滑下限 0..1（越大越跟手；高速时内部 alpha 会自动升到 1.0）
 * @property pixelsPerScrollClick 滚轮：多少像素折算 1 格（越小滚得越快）
 * @property scrollDirection   滚轮方向（1 或 -1）。若电脑端上下滚动方向相反，改成 -1 即可。
 */
data class MouseSettings(
    val sensitivity: Float = 1.0f,
    val smoothing: Float = 0.6f,
    val pixelsPerScrollClick: Float = 40f,
    val scrollDirection: Float = 1f
)

/**
 * SettingsStore —— 手感参数的本地持久化
 *
 * 供设置页的滑块读写，并让 App 重启后保留上次调好的手感。
 *
 * 说明：这些值**不直接改 HID 底层**，而是注入到
 * [com.btmouse.core.input.TouchInputHandler] 的公开属性上，完全不触碰
 * BluetoothHidManager / SendQueue 的核心逻辑。
 */
object SettingsStore {

    private const val PREF_NAME = "btmouse_settings"
    private const val KEY_SENSITIVITY = "sensitivity"
    private const val KEY_SMOOTHING = "smoothing"
    private const val KEY_PX_PER_CLICK = "pixels_per_scroll_click"
    private const val KEY_SCROLL_DIR = "scroll_direction"

    /** 参数合法区间，读写两侧都做钳制，防止脏数据把光标调飞。 */
    val SENSITIVITY_RANGE = 0.2f..4.0f
    val SMOOTHING_RANGE = 0.05f..1.0f
    val PX_PER_CLICK_RANGE = 10f..120f

    private val defaults = MouseSettings()

    fun load(context: Context): MouseSettings {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return MouseSettings(
            sensitivity = prefs.getFloat(KEY_SENSITIVITY, defaults.sensitivity)
                .coerceIn(SENSITIVITY_RANGE),
            smoothing = prefs.getFloat(KEY_SMOOTHING, defaults.smoothing)
                .coerceIn(SMOOTHING_RANGE),
            pixelsPerScrollClick = prefs.getFloat(KEY_PX_PER_CLICK, defaults.pixelsPerScrollClick)
                .coerceIn(PX_PER_CLICK_RANGE),
            // 方向只允许 +1 / -1
            scrollDirection = if (prefs.getFloat(KEY_SCROLL_DIR, defaults.scrollDirection) < 0f) -1f else 1f
        )
    }

    fun save(context: Context, settings: MouseSettings) {
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_SENSITIVITY, settings.sensitivity.coerceIn(SENSITIVITY_RANGE))
            .putFloat(KEY_SMOOTHING, settings.smoothing.coerceIn(SMOOTHING_RANGE))
            .putFloat(
                KEY_PX_PER_CLICK,
                settings.pixelsPerScrollClick.coerceIn(PX_PER_CLICK_RANGE)
            )
            .putFloat(KEY_SCROLL_DIR, if (settings.scrollDirection < 0f) -1f else 1f)
            .apply()
    }

    /** 恢复出厂手感。 */
    fun reset(context: Context): MouseSettings {
        save(context, defaults)
        return defaults
    }
}


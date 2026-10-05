package com.btmouse.core.state

import android.content.Context

/**
 * ModeStore —— 模式选择的本地持久化
 *
 * 用 SharedPreferences 记住用户上次选择的 [HidMode]，App 重启 / 进程被回收后仍能恢复。
 * 采用同步 [Context.MODE_PRIVATE] 读写：数据量极小（一个字符串），无需异步。
 */
object ModeStore {

    private const val PREF_NAME = "btmouse_mode"
    private const val KEY_MODE = "selected_mode"

    /** 读取上次选择的模式；没有记录或值非法时回退到 [HidMode.TRACKPAD]。 */
    fun load(context: Context): HidMode {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return HidMode.fromName(prefs.getString(KEY_MODE, null))
    }

    /** 保存用户选择的模式。 */
    fun save(context: Context, mode: HidMode) {
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            .apply()
    }
}


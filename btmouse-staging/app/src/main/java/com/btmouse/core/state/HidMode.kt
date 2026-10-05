package com.btmouse.core.state

/**
 * HidMode —— 三种操作模式的统一定义
 *
 * 由 UI 层（模式选择页）选择、[ModeStore] 持久化、MainViewModel 作为状态机持有，
 * 并驱动控制页渲染对应的交互布局。
 *
 * @property title       卡片/标题栏显示名
 * @property subtitle    卡片副标题（一句话说明操作方式）
 * @property description 卡片正文（详细说明）
 * @property badge       卡片右上角小标签
 * @property sensorBased 该模式是否依赖传感器（模式二/三为 true），供 UI 决定是否提示手势说明
 */
enum class HidMode(
    val title: String,
    val subtitle: String,
    val description: String,
    val badge: String,
    val sensorBased: Boolean
) {
    /** 模式一：触控板——整屏滑动控制光标（单指移动、双指滚动，后者在 BATCH-3 接入）。 */
    TRACKPAD(
        title = "触控板模式",
        subtitle = "整屏滑动控制光标",
        description = "单指滑动移动光标，双指滑动滚动滚轮；底部左右键。适合手机当触控板使用。",
        badge = "手机当触控板",
        sensorBased = false
    ),

    /** 模式二：桌面平移——手机平放桌面，靠线性加速度感知滑动。 */
    DESK(
        title = "桌面平移",
        subtitle = "手机平放，像鼠标一样推",
        description = "把手机平放在桌面上推拉，用加速度计感知位移来移动光标。顶部左右键、底部滚轮条。",
        badge = "平放桌面",
        sensorBased = true
    ),

    /** 模式三：空中遥控——手持悬空，靠陀螺仪旋转控制光标。 */
    AIR(
        title = "空中遥控",
        subtitle = "手持悬空，转动手腕",
        description = "手持手机悬空，通过转动手机控制光标方向。内置死区与低通滤波抑制漂移。",
        badge = "陀螺仪体感",
        sensorBased = true
    );

    companion object {
        /** 读取持久化值时使用的容错解析：非法/未知值一律回退到 [TRACKPAD]。 */
        fun fromName(raw: String?): HidMode =
            entries.firstOrNull { it.name == raw } ?: TRACKPAD
    }
}


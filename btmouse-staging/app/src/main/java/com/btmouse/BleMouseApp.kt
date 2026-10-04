package com.btmouse

import android.app.Application

/**
 * 全局 Application。作为轻量的服务定位入口，统一持有单例。
 * （原生层类在此初始化；UI 层在后续阶段接入。）
 */
class BleMouseApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
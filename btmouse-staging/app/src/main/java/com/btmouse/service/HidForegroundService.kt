package com.btmouse.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.btmouse.MainActivity
import com.btmouse.R
import com.btmouse.core.hid.BluetoothHidManager

/**
 * HidForegroundService —— 蓝牙 HID 前台服务
 *
 * 保活机制：
 *  - HID 连接建立在系统蓝牙栈之上；一旦应用进程被系统回收，蓝牙 Profile 与已注册的
 *    描述符都会被注销，连接随之中断。用前台服务把进程提升为"用户可见的关键进程"，
 *    可显著降低被 LMK(进程回收)/国产 ROM 后台清理的概率，保证退到后台或息屏时 HID 链路不断。
 *  - 常驻通知是前台服务的强制要求（也让用户知道鼠标在后台工作、可点击回 App）。
 *
 * 职责：
 *  - 获取 BluetoothHidManager 单例并触发 Profile 初始化（initialize）。
 *  - 保持前台进程优先级（START_STICKY：系统回收后自动重建并重连）。
 */
class HidForegroundService : Service() {

    private val manager: BluetoothHidManager by lazy {
        BluetoothHidManager.getInstance(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14 (API 34) 要求：foregroundServiceType=connectedDevice 的服务在调用
        // startForeground() 前必须已获得 BLUETOOTH_CONNECT 等蓝牙运行时权限，否则抛
        // SecurityException 并导致进程崩溃。未授权时先不转前台，等 UI 授权后重启服务。
        if (!canStartForeground()) {
            Log.w(TAG, "缺少蓝牙运行时权限，暂不进入前台；授权后需重新启动服务")
            return START_NOT_STICKY
        }
        startAsForeground()
        // 确保蓝牙 HID Profile 初始化；若已在运行则为幂等操作
        manager.initialize()
        return START_STICKY
    }

    /** 以前台服务方式启动并发布常驻通知。 */
    private fun startAsForeground() {
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 需声明前台服务类型；connectedDevice 对应蓝牙外设连接
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** 创建通知渠道（Android 8+ 必需）。 */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW   // 低频提示，不打扰但保活可见
            )
            channel.description = getString(R.string.notification_channel_desc)
            manager.createNotificationChannel(channel)
        }
    }

    /** 构建常驻通知：点击回到 App 主界面。 */
    private fun buildNotification(): Notification {
        val backIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(backIntent)
            .setOngoing(true)                    // 不可滑动清除
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        manager.release()
        super.onDestroy()
    }

    /** 是否具备进入前台服务所需的蓝牙运行时权限。 */
    private fun canStartForeground(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.BLUETOOTH_CONNECT
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val CHANNEL_ID = "hid_foreground_service"
        private const val TAG = "HidForegroundService"
        private const val NOTIFICATION_ID = 1001

        /** 供 Activity 启动前台服务（对 Android 8+ 用 startForegroundService，系统会在 5s 内要求转前台）。 */
        fun start(context: Context) {
            val intent = Intent(context, HidForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}


package com.btmouse.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * PermissionHelper
 *
 * 统一封装蓝牙 HID 应用所需的运行时权限申请所需的数据与该不该申请判断。
 *
 * 权限分级：
 *  - Android 12+ (API 31+)：BLUETOOTH_SCAN / BLUETOOTH_CONNECT / BLUETOOTH_ADVERTISE 为运行时权限。
 *    本应用作为 HID 设备角色需要：
 *      CONNECT：连接已配对的主机（电脑）；
 *      ADVERTISE：广播/可被发现（阶段一 UI 用于展示连接能力，但声明保持一致）；
 *      SCAN：扫描/枚举已配对设备（列出可连接设备时用到）。
 *  - Android 13+ (API 33+)：POST_NOTIFICATIONS 为运行时权限。前台服务会持续显示通知，
 *    若用户拒绝将无法展示常驻通知（服务本身仍可运行，但失去了"保活可见性"提示）。
 *  - Android 11 (API 30)：BLUETOOTH / BLUETOOTH_ADMIN 是普通权限，Manifest 已声明即可，无需运行时申请。
 *
 * 说明：NEARBY_WIFI_DEVICES 在此场景不需要（我们不访问 WiFi 设备，仅蓝牙）。
 */
object PermissionHelper {

    /**
     * 蓝牙运行时权限（API 31+ 需要）。
     * 这些权限是"蓝牙相关一组"，任一缺失都需要一起申请。
     */
    private val BLUETOOTH_PERMISSIONS = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE
    )

    /** 通知权限（Android 13+），用于前台服务常驻通知。 */
    private val NOTIFICATION_PERMISSION = Manifest.permission.POST_NOTIFICATIONS

    /** 需要一次性申请的"蓝牙 + 通知"权限集合（按当前系统版本收敛）。 */
    val requiredPermissions: Array<String>
        get() = BLUETOOTH_PERMISSIONS + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(NOTIFICATION_PERMISSION)
        } else {
            emptyArray()
        }

    /** 该组权限是否已全部授予。 */
    fun hasAll(context: Context): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /** 返回当前仍未授予的权限列表（用于请求或提示用户）。 */
    fun missingPermissions(context: Context): List<String> =
        requiredPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
}
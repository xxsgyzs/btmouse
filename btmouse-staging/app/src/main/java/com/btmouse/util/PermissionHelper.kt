package com.btmouse.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * PermissionHelper
 *
 * 统一封装蓝牙 HID 应用所需的运行时权限申请与判断。
 *
 * ██ BATCH-5.1 关键修复：把「必需权限」与「可选权限」彻底解耦 ██
 *
 * 原实现把 POST_NOTIFICATIONS 一并算进 requiredPermissions，
 * 导致 Android 13+ 上若用户拒绝通知权限，hasAll() 会恒为 false，
 * 进而使 ConnectionScreen 的授权回调永不触发、HID 注册永不执行
 * —— 表现为"蓝牙服务就绪"亮着但"已注册为鼠标"永远灰着。
 *
 * 通知权限只影响"前台服务能否显示常驻通知"，**不应阻断任何蓝牙功能**，
 * 因此现在拆成两组：
 *   · REQUIRED_BLUETOOTH  —— 蓝牙功能必需，缺任一则无法工作
 *   · OPTIONAL_NOTIFICATION —— 可选，被拒只影响通知可见性
 *
 * 权限分级：
 *  - Android 12+ (API 31+)：BLUETOOTH_SCAN / CONNECT / ADVERTISE 为运行时权限
 *  - Android 13+ (API 33+)：POST_NOTIFICATIONS 为运行时权限（可选）
 *  - Android 11 (API 30)：BLUETOOTH / BLUETOOTH_ADMIN 是普通权限，Manifest 声明即可
 */
object PermissionHelper {

    /**
     * 蓝牙功能**必需**的运行时权限（API 31+ 需要）。
     *
     * - CONNECT：连接已配对的主机（电脑），以及 registerApp 所必需
     * - SCAN：枚举已配对设备（列出可连接设备时用到）
     * - ADVERTISE：作为 HID 设备对外可被发现
     */
    private val BLUETOOTH_PERMISSIONS = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE
    )

    /** 蓝牙必需权限（对外暴露，便于 UI 单独判断与单独申请）。 */
    val REQUIRED_BLUETOOTH: Array<String> get() = BLUETOOTH_PERMISSIONS

    /**
     * **可选**权限：Android 13+ 的通知权限。
     * 被拒绝时前台服务仍可运行、HID 仍可注册，只是常驻通知不显示。
     */
    val OPTIONAL_NOTIFICATION: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }

    /** 蓝牙功能是否已就绪（**只看必需权限**，不受通知权限影响）。 */
    fun hasRequiredBluetooth(context: Context): Boolean =
        BLUETOOTH_PERMISSIONS.all { isGranted(context, it) }

    /** 尚未授予的蓝牙必需权限。 */
    fun missingBluetooth(context: Context): List<String> =
        BLUETOOTH_PERMISSIONS.filter { !isGranted(context, it) }

    /** 尚未授予的可选权限（通知）。 */
    fun missingOptional(context: Context): List<String> =
        OPTIONAL_NOTIFICATION.filter { !isGranted(context, it) }

    /**
     * 首次进入连接页时应一次性申请的权限集合：
     * 蓝牙必需 + 可选通知。**但两者的授予结果分开判定**。
     */
    fun permissionsToRequest(context: Context): Array<String> =
        (missingBluetooth(context) + missingOptional(context)).toTypedArray()

    /** 兼容旧调用：是否所有权限（含可选）都已授予。 */
    fun hasAll(context: Context): Boolean =
        hasRequiredBluetooth(context) && missingOptional(context).isEmpty()

    /** 兼容旧调用：返回当前仍未授予的权限列表（用于请求或提示用户）。 */
    fun missingPermissions(context: Context): List<String> =
        missingBluetooth(context) + missingOptional(context)

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}


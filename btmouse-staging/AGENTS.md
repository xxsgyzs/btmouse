# AGENTS.md

## 项目概述
一款安卓原生应用，将 Android 手机模拟为标准 **BLE HID 鼠标**，通过蓝牙配对到电脑（Win/macOS/Linux），无需安装驱动。核心功能分阶段交付：
- 阶段一（进行中）：蓝牙配对 + 基础鼠标移动 + 左右键，跑通闭环。
- 阶段二：滚轮 + 双指手势。
- 阶段三：空中鼠标（陀螺仪/加速度计体感控制）。

## 技术栈
- Android 纯原生：**Kotlin + Jetpack Compose**，单选路线 A（非 Expo/RN）。
- 构建：Gradle (Kotlin DSL) + Version Catalog；AGP / Kotlin / Compose 版本见 `gradle/libs.versions.toml`。
- 底层蓝牙：`android.bluetooth.BluetoothHidDevice`（仅 Android 11 / `minSdk 30` 起可用）。
- 传感器：`SensorManager`（阶段三使用）。
- 包名：`com.btmouse`。

## 目录结构
```
btmouse（= /workspace/projects，单层技术项目根，亦是 Gradle 工程根）
├── settings.gradle.kts
├── build.gradle.kts                  # 根构建
├── gradle.properties
├── gradle/libs.versions.toml         # 统一版本（AGP 8.2.2 / Kotlin 1.9.22 / Compose BOM 2024.02）
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/btmouse/
        │   ├── BleMouseApp.kt        # Application
        │   ├── MainActivity.kt       # Compose 入口，连接页/触控板页切换
        │   ├── core/hid/             # 纯原生逻辑（可单测）
        │   │   ├── BluetoothHidManager.kt   # HID Profile/注册/连接/发送核心（单例）
        │   │   ├── MouseReportBuilder.kt    # 4 字节 report 合成
        │   │   └── SendQueue.kt             # 后台线程节拍发送 + 增序合发 + ±127 拆帧
        │   ├── core/input/
        │   │   └── TouchInputHandler.kt     # 像素→逻辑位移（死区+EMA+灵敏度）
        │   ├── service/
        │   │   └── HidForegroundService.kt  # 前台服务保活 + 常驻通知
        │   ├── ui/
        │   │   ├── MainViewModel.kt         # 阶段一状态机 + 触控/按钮接线
        │   │   ├── theme/Theme.kt
        │   │   └── screens/
        │   │       ├── ConnectionScreen.kt  # 权限+配对设备列表+连接
        │   │       └── TouchpadScreen.kt    # 触控板+左右键
        │   └── util/PermissionHelper.kt     # Android 12+ 蓝牙/通知运行时权限
        └── res/
```

## 关键入口 / 核心模块
- `BluetoothHidManager`（单例）：HID Profile 初始化（`getProfileProxy`）、`registerApp`、`connectionStateChanged`、`sendMouseReport`、`submitMouseInput`。被前台服务持用。
- 数据流：触控/按钮 → `TouchInputHandler`（滤波）→ `SendQueue`（后台 8ms 节拍、±127 拆帧）→ `sendReport`。
- HID Report Descriptor：标准桌面鼠标（无 Report ID），Report `[buttons, dX, dY, wheel]` 4 字节，dX/dY/wheel 有符号 -127~127（阶段一用前 3 字节）。

## 运行与验证
- **不可预览/不可平台部署**（`preview_enable = "disabled"`，`.coze` 无 `[dev]`，`[deploy.profile] kind = "android"`）：蓝牙 HID 无法在浏览器/平台验证。
- 验证：本地 Android Studio 打开 → Sync → `Build APK`（`./gradlew assembleDebug`）→ 真机安装 → 系统蓝牙配对电脑 → App 内选择设备连接 → 触控板滑动 + 左右键。
- 沙箱无 Android SDK，无法实测构建；配置按稳定版本组合提供。
- 平台部署：`.coze` 显式声明 `[deploy.profile] kind = "android"`。本工程为纯原生 Gradle Android、无 HTTP 服务壳，故**不写** service 类的 `deploy.run`/`deploy.build`/`deploy.backend`；一旦缺失 `[deploy]` 声明，平台会按缺省 service 校验并报 `deploy.run is required for kind service`。APK 构建与真机验证在用户本地 Android Studio 完成。

## 用户偏好与长期约束
- 技术路线选 **A（纯原生 Kotlin）**，明确不用 RN/Expo（避免桥接延迟）。
- `minSdk = 30`（Android 11，`BluetoothHidDevice` 硬门槛），`targetSdk = 34`。
- 分阶段交付，每个阶段完成后向用户确认再继续；不一次性交付全部阶段。

## 常见问题和预防
- `registerApp` 失败 → 多为 Profile 未就绪或应用已注册，重试并处理 `onAppUnregistered`。
- 高速滑动单帧 X/Y 超 ±127 → 必须拆多帧高频发送，禁止塞单包。
- 国产 ROM 后台回收连接 → 需前台服务 + 连接状态引导。
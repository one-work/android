# Xcprinter Android

"Xcprinter / One Work" 的 Android 客户端：一个基于 [hotwire-native-android](https://github.com/hotwired/hotwire-native-android)（`dev.hotwire:core:1.3.1` + `dev.hotwire:navigation-fragments:1.3.1`）的 Hotwire Native 壳，加载 Rails 服务端渲染站点 <https://linlishenghuo.com>。功能与 iOS 版（仓库根目录的 Swift 工程）一一对应。

## 功能清单（对照 iOS）

- **底部多 Tab**：Home（`/auth/apps`）、Help（<https://app-demo.xcprinter.com/README>）；连接本地开发服务器（`Demo.current = Demo.local`）时追加 "Bugs & Fixes"（`/bugs`）。使用库内置 `HotwireBottomNavigationController` + `HotwireBottomTab`，懒加载 tab。
- **蓝牙打印**（`bluetooth` 桥接组件 + `bt/BluetoothPrinterManager`）：BLE 扫描（5 秒自动停止，只上报有名字的设备）、连接/断开、带响应写入第一个可写 characteristic，事件与 JSON 契约和 iOS 完全一致（`connect` / `search` / `connect_device` / `disconnect_device` / `send_data`）。
- **扫码**（`scan` 桥接组件）：zxing-android-embedded 全屏扫码，格式 QR_CODE、EAN_13、EAN_8、CODE_128、CODE_39、UPC_E、PDF_417；回复 `{"value":"..."}`，无相机设备回复 `{"value":null}`，取消不回复。
- **原生 toolbar 按钮/菜单**：`right-button`（右上角文字按钮，点击回复 `connect`）、`overflow-menu`（右上角菜单项，点击路由到指定 URL，缺省 `/bluetooth/menus`）。
- **跨域原生打开**：自定义 `ExternalRouteDecisionHandler` 让所有 http/https 链接（包括跨域）都在 App 内打开，非 http/https（mailto:、tel: 等）交给系统。
- **JS 注入**：自定义 `WebFragment` / `WebBottomSheetFragment` 在冷启动页面加载完成后注入 `assets/js/init_turbo.js` 和 `assets/js/init.js`（与 iOS 的 WKUserScript 等价，脚本自带防重复 guard）。
- **其他**：`/bluetooth/menus` 页面右上角「关闭」按钮、离开该页面时刷新下层页面、401 自动跳转 `/session/new`、`apple-sign-in` 组件（Android 上回复不支持，见下文差异说明）。

## 构建

### 环境要求

- Android SDK（`local.properties` 中 `sdk.dir` 指向，本机为 `~/Library/Android/sdk`，已装 android-35）
- **JDK 17–21**：Gradle 8.x 不能在 Java 25 上运行。本机系统 Java 是 OpenJDK 25，因此首次构建前需准备一个 JDK 21：

  ```bash
  mkdir -p android/.jdk
  curl -sL -o android/.jdk/jdk21.tar.gz \
    "https://api.adoptium.net/v3/binary/latest/21/ga/mac/aarch64/jdk/hotspot/normal/eclipse"
  cd android/.jdk && tar xzf jdk21.tar.gz && mv jdk-21* jdk-21 && rm jdk21.tar.gz
  ```

  `android/gradle.properties` 里的 `org.gradle.java.home` 已指向该目录（机器相关配置，换机器请改成任意 JDK 17–21 的路径）。`.jdk/` 已被 gitignore。

### 命令行构建

```bash
cd android
./gradlew :app:assembleDebug
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`

### Android Studio

直接用 Android Studio 打开 `android/` 目录即可（Gradle JDK 选择上面准备的 JDK 21）。

## 服务端配置

- 站点：<https://linlishenghuo.com>
- Path configuration 远程地址：<https://linlishenghuo.com/configurations/android_v1.json>（与 iOS 的 `ios_v1.json` 对应，本地 asset `path-configuration.json` 为兜底）

## 与 iOS 的差异

- **跨域 session 策略**：iOS 跨域链接用独立的 `ExternalWebSession`（第三个 session）打开；Android 没有等价 API，跨域地址在**同一个 session 内冷启动**加载（自定义 `ExternalRouteDecisionHandler` 返回 `NAVIGATE`），用户可见行为一致。同理，iOS「离开 `/bluetooth/menus` 时刷新 externalSession」在 Android 上实现为「刷新返回后的下层 destination」。
- **Apple 登录不支持**：Android 无系统级 Sign in with Apple。`apple-sign-in` 组件收到 `signIn` 事件时回复 `{"success":true,"cancelled":false,"error":"Android 暂不支持 Apple 登录，请使用其他登录方式"}`（字段结构与 iOS 的 UserData 一致）。如需第三方登录，Android 上应改用 Credential Manager / Google Sign-In，需要服务端配合验证 Google token。
- **BLE 设备地址**：Android 用 MAC 地址（如 `AA:BB:CC:DD:EE:FF`），iOS 用 UUID。`connect_device` / `disconnect_device` 的 `address` 字段在两平台格式不同，服务端如持久化设备标识需按平台区分。
- **iOS 专有细节不适用**：`tagIdleWebViews`（Safari 检查器标记）、`disableEdgeEffectTouches`（iOS 滚动边缘效果）、`showDoneButtonOnModals`、`hideTabBarWhenPushed` 等 iOS 配置在 Android 上没有对应物。模态「关闭」按钮通过自定义 `WebFragment` 在 toolbar 添加实现。
- **权限模型**：Android 需要运行时权限（API 31+ 的 `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`，更低版本的 `ACCESS_FINE_LOCATION`，以及扫码用的 `CAMERA`）。蓝牙权限在 MainActivity 启动时统一请求一次；相机权限由扫码界面自行请求。

## 目录结构

```
android/
  app/src/main/
    java/com/xcprinter/app/
      XcPrinterApplication.kt      # Hotwire 全局配置（对照 iOS AppDelegate）
      Demo.kt                      # 服务器地址（对照 iOS Demo.swift）
      main/MainActivity.kt         # 底部 Tab 宿主（HotwireBottomNavigationController）
      main/MainTabs.kt             # Tab 定义（对照 iOS Tabs.swift）
      routing/ExternalRouteDecisionHandler.kt  # 跨域原生打开（对照 ExternalDecisionHandler.swift）
      features/web/                # WebFragment / WebBottomSheetFragment / 共享行为
      bridge/                      # 5 个桥接组件（scan/bluetooth/right-button/apple-sign-in/overflow-menu）
      bt/BluetoothPrinterManager.kt           # BLE 管理器（对照 BluetoothManager.swift）
    assets/path-configuration.json # 本地 path configuration（与 iOS 相同）
    assets/js/                     # 注入的 init_turbo.js / init.js（与 iOS 相同）
```

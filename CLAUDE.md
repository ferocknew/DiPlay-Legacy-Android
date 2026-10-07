# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

DiPlay-Legacy-Android：BYD 车机上的 CarPlay 接收端应用，实现 Apple iAP2 + AirPlay/CarPlay 协议栈，通过 USB 有线或热点/Wi-Fi Direct 无线连接 iPhone。本仓库是 shihabal3amri/DiPlay 的旧版 Android 兼容分支，最低支持 Android 4.4（API 19），仅支持 BYD 车型。根项目名为 `xcertplay`（上游遗留）。

## 构建与测试

标准检查（与 CI 相同，见 `.github/workflows/android.yml`）：

```bash
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
```

发布构建：

```bash
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintRelease :mobile:assembleRelease
```

- release 签名需环境变量：`ANDROID_KEYSTORE_PATH` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`；缺失时签不出。
- 独立车测 APK（注入 MFi 身份资产）：先设 `DIPLAY_AUTH_ASSETS_DIR=/绝对路径`，再 `./gradlew :mobile:assembleStandaloneDebug`（自定义任务，见 `mobile/build.gradle.kts`）。
- 不设该变量时 `assembleDebug` 产出"无身份"构建（不能独立连 iPhone 认证，属预期，非 bug）。

单个测试：

```bash
./gradlew :shared:testDebugUnitTest --tests "com.shilapi.xcertplay.shared.SomeTest"
```

- 测试全部为 JVM/Robolectric 单元测试（无仪器测试）：`:shared` 约 65 个测试类，`:common` 约 12 个；报告在 `<module>/build/reports/tests/`。
- 环境要求：JDK 25、compileSdk 37、NDK 25.2.9519653（`:shared` 原生代码 jni/ 用，锁定为最后能产出 API 19 目标的工具链，不可升级）。

## 架构

### 模块依赖（业务代码在哪）

```
shared  ←(api)  common  ←(implementation)  mobile（发布 APK，applicationId com.shihab.diplay）
                                     ←(implementation)  automotive（AAOS 壳，占位实现）
```

- `:shared` — 协议/传输核心库（namespace `com.shilapi.xcertplay.shared`）：`iap2/`（链路协议）、`airplay/`（RTSP 信令、配对加密、流）、`transport/`（USB/NCM/蓝牙/I2C/CH341）、`mfi/`（MFi 认证三种目标）、`network/`（VPN 隧道、热点管理、Bonjour）、`media/`（MediaCodec/AudioSink）、`hud/`（BYD 仪表/HUD 桥）、`adb/`、`orchestration/`。
- `:common` — Android 宿主层：全部 Activity/Service/Receiver、程序化 UI、SharedPreferences 持久化。
- `:mobile` — 打包壳：applicationId/版本/签名/凭据防护任务，main 源码集几乎无代码（HUD demo 在 src/debug）。
- `:automotive` — Android Automotive OS 入口，Screen 只显示"硬件未配置"占位。

改业务逻辑几乎都在 `shared` 和 `common`；`mobile`/`automotive` 只动打包配置。

### Manifest 分散在三个文件

组件声明分散：`common` 的 manifest 声明全部 Activity/Service/Receiver（LAUNCHER 是 `DiPlayActivity`；`CarPlayHostActivity` 注册 `USB_DEVICE_ATTACHED` 实现有线即插即连）；`shared` 的 manifest 持有网络/蓝牙/定位权限和 BYD 专用包名 queries。合并方向 shared+common → app，找组件/权限不要只看 app 模块。

### UI 与并发（重要，反直觉）

- 没有 layout XML：所有 UI 用 Kotlin 代码构建（`LinearLayout(this).apply{...}`），res/ 只有 values/drawable/raw/xml。
- 主题基于 `android:style/Theme.Holo.NoActionBar`（为 API 19，非 AppCompat 主题）。
- 无 MVVM/MVI/ViewModel/Compose/Navigation：UI 状态就是 Activity 字段 + `Handler.postDelayed` 轮询。
- 零协程：并发一律 Executors / `Handler(Looper.getMainLooper())` / Atomic* / 裸 Thread。
- DI 为手写：构造函数注入 + `object` 单例，无框架。

### 连接编排与会话保活

- `CarPlayController`（`shared/.../orchestration/`，约 2000 行）是集成缝：MFi 认证 → 传输建立 → iAP2 → AirPlay 的状态机（`sealed class CarPlayStatus`，20+ 状态），回调回 UI。
- `CarPlayBackgroundSession`（进程级 `object` 单例，定义在 `CarPlayHostActivity.kt`）在 Activity 重建/退后台时保存 controller + 媒体 sink 快照，实现跨 Activity 会话移交——动这两个文件时注意别破坏该机制。
- 存储全部是 SharedPreferences（`"diplay"` 与 `"xcertplay_airplay"` 两个文件，见 `common/.../AirPlayPersistence.kt`）；网络是裸 Socket + JmDNS + BouncyCastle，无 OkHttp/Retrofit。

## 旧版 Android 兼容约定（本仓库存在理由）

`Build.VERSION.SDK_INT` 分支 100+ 处。新代码按既定三种模式之一写，不要发明新写法：

1. 整类隔离：新版专用类整体 `@RequiresApi` 后由调用方按版本实例化（如 `LocalOnlyHotspotManager` @RequiresApi(O)、`WifiP2pGroupManager` @RequiresApi(Q)），配 `Legacy*` 旧实现。
2. Compat shim：`shared/.../transport/UsbApiCompat.kt` 顶层函数按 API 分支。
3. 就地 if/else + `@Suppress("DEPRECATION")`（如通知构建按 API 21/23/26/29 分档）。

另外：multidex + coreLibraryDesugaring 必须保持（旧平台 java.time 等依赖它）；资源版本差异用 `values-v21` 限定符；各 Android 版本的传输回退矩阵见 `docs/COMPATIBILITY.md`。

## 凭据防护（不可违反）

- 严禁把 MFi 身份（`identity.pk8` / `certificate.p7b`）、签名密钥、任何 `.pem/.key/.jks/.p12` 等提交入库：`.gitignore` 屏蔽 + CI 第一步 `scripts/check_public_tree.py` 扫描会直接失败。
- `mobile` 的 `rejectBundledCredentials` 任务挂在 preBuild：assets 里只允许上述两个文件名，且只能来自 `DIPLAY_AUTH_ASSETS_DIR`。

## 构建系统注意

- Kotlin 编译依赖 AGP 9 内建 Kotlin 支持：所有模块都不 apply Kotlin 插件（`libs.versions.toml` 的 kotlin 版本号供其使用）。不要按惯例添加 `org.jetbrains.kotlin.android`。
- `:shared` 是唯一用 Groovy `build.gradle` 的模块（其余为 `.kts`）。
- 无 productFlavors；`mobile` debug 构建带 `applicationIdSuffix ".hudtest"`。
- release 关闭混淆/压缩（`optimization { enable = false }`），不要顺手开启。

## 约定

- 纯 Kotlin（无 .java 业务代码，原生层为 C）；日志 tag 用 `xcertplay-*` 前缀。
- AGPL 来源文件保留 `// SPDX-License-Identifier: AGPL-3.0-only` 头（GPL-3.0/AGPL-3.0 双源项目，分发需保留声明，见 docs/licenses/）。
- i18n：5 语言（en/zh-rCN/ar/ru/es），`AppLocale` 处理 per-app 语言（API 33+ 用 LocaleManager，旧版 attachBaseContext）。
- commit message 用中文 conventional 风格（`fix: 修复…` / `feat: 增加…`）。
- 关键文档：`docs/BUILD.md`（构建）、`docs/COMPATIBILITY.md`（兼容矩阵与已知限制）、`docs/TESTING.md`（上车测试清单）。

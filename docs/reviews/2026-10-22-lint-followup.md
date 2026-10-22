# PennyPilot 剩余 Lint 警告复核 · 2026-10-22

基线为 `3820c50`：Lint 0 errors、53 warnings，其中 48 条是版本更新提示，5 条涉及源码或资源。版本提示按相同坐标和位置重复报告三次，不能把这 48 条理解为 48 个独立代码缺陷。

处理后 Lint 为 **0 errors、27 warnings**（AndroidGradlePluginVersion 3 条、GradleDependency 24 条）。原 5 条源码／资源警告全部消除，原版本提示减少 21 条；联网检查额外发现的序列化库提示也已处理。剩余 27 条对应下文 8 组固定版本配置。

## 已修改的源码与资源

| 检查项 | 原数量 | 修改 |
|---|---:|---|
| BatteryLife | 2 | 首次启动及 ColorOS 引导改为主动打开系统电池优化列表；不可用时回退应用详情，并向用户反馈。返回页面重新读取实际豁免状态，不再直接请求豁免。 |
| ModifierParameter | 1 | `InputDock` 的 `modifier` 移至第一个可选参数；调用点使用命名参数，行为不变。 |
| ObsoleteSdkInt | 1 | minSdk 已为 26，将启动图标从 `mipmap-anydpi-v26` 移至 `mipmap-anydpi`。 |
| MonochromeLauncherIcon | 1 | 增加沿用原圆环形状的单色图标与 monochrome 引用，供系统主题着色，保留彩色图标。 |

电池设置行为依据 [Android Doze 与 App Standby 文档](https://developer.android.com/training/monitoring-device-state/doze-standby#support_for_other_use_cases)、[Settings API](https://developer.android.com/reference/android/provider/Settings.html#ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) 和 [PowerManager API](https://developer.android.com/reference/android/os/PowerManager#isIgnoringBatteryOptimizations(java.lang.String))。Manifest 原本已经没有直接豁免权限，本次无需改动。新增 5 项测试覆盖正常跳转、入口缺失、入口受限、两种入口均不可用及系统状态刷新。

## 依赖升级与兼容性

对每个版本提示都核对了官方发布信息及必要的 Google Maven AAR／POM 元数据；构建环境仍为 JDK 17、Gradle 8.11.1、AGP 8.7.3、Kotlin 2.0.21、KSP 2.0.21-1.0.28、compileSdk/targetSdk 35、minSdk 26。`gradle.properties` 中持久启用 KSP2，普通构建命令无需再传临时参数。

已升级的依赖：

| 依赖 | 原版本 | 新版本 | 兼容性依据 |
|---|---|---|---|
| Security Crypto | 1.1.0-alpha06 | 1.1.0 | 稳定版本 AAR 要求 compileSdk ≥34、AGP ≥8.1.1，符合当前环境。 |
| AndroidX Test Core | 1.6.1 | 1.7.0 | 官方列出的最低 API 为 21，Kotlin 依赖为 1.9；当前测试环境符合要求。 |
| Room runtime/ktx/compiler/testing | 2.6.1 | 2.8.5 | runtime-android AAR 要求 compileSdk ≥34、AGP ≥8.1.1、minSdk ≥23；官方要求 Kotlin ≥2.0，KSP2 为推荐项。 |
| WorkManager | 2.9.1 | 2.12.0 | work-runtime AAR 要求 compileSdk ≥35、AGP ≥8.6、minSdk ≥24，符合当前环境。 |
| Dagger Hilt | 2.51.1 | 2.54 | 为 Room／WorkManager 的 KSP2 组合提供处理器兼容性，未升级到要求 AGP9 的版本。 |
| kotlinx.serialization JSON | 1.7.3 | 1.8.1 | 官方 core JAR 声明最低 Kotlin 为 2.0.0-RC1，metadata 为 2.1；当前 Kotlin 2.0.21 满足要求，编译插件版本保持不变。 |

来源：[Security 发布记录](https://developer.android.com/jetpack/androidx/releases/security#1.1.0)、[Test 发布记录](https://developer.android.com/jetpack/androidx/releases/test#core_core-ktx_1.7.0)、[Room 发布记录](https://developer.android.com/jetpack/androidx/releases/room#2.7.0)、[Room compiler POM](https://dl.google.com/dl/android/maven2/androidx/room/room-compiler/2.8.5/room-compiler-2.8.5.pom)、[WorkManager 发布记录](https://developer.android.com/jetpack/androidx/releases/work#2.12.0)、[serialization 官方 JAR](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.8.1/kotlinx-serialization-core-jvm-1.8.1.jar) 与 [编译器最低版本检查实现](https://raw.githubusercontent.com/JetBrains/kotlin/v2.0.21/plugins/kotlinx-serialization/kotlinx-serialization.common/src/org/jetbrains/kotlinx/serialization/compiler/diagnostic/RuntimeVersions.kt)。联网复核时新增了序列化库的三条版本提示，因此一并处理。

Room 升级并非仅改版本：原 KSP1 在 schema 导出时实测出现 `GeneratedSerializer.typeParametersSerializers()` 类加载冲突；只启用 KSP2 又触发 Dagger 2.51.1 内嵌处理器的 `STAR null` 错误。调用栈与 [KSP 官方问题记录](https://github.com/google/ksp/issues/1957) 相符，因此选择 [官方明确支持 KSP2 配置的 Dagger Hilt 2.54](https://github.com/google/dagger/releases/tag/dagger-2.54) 进行组合验证，没有强制忽略 Kotlin metadata 或删除 schema 绕过检查。

Security Crypto 1.1.0 将 EncryptedSharedPreferences／MasterKey 标记为弃用，因此编译器会给出弃用提示。本次保留原加密文件格式及读取路径，不为了清除提示而直接替换用户密钥存储；迁移至平台 Keystore API 需要独立设计和旧密文迁移验证。[官方弃用说明](https://developer.android.com/jetpack/androidx/releases/security#1.1.0-beta01)

## 需要成套迁移的版本提示

以下版本不在本次直接升级范围内，保留原提示和固定版本：

| 当前版本 → Lint 建议版本 | 保留理由 |
|---|---|
| AGP 8.7.3 → 9.4.1 | AGP 9.4 要求 Gradle ≥9.6、Build Tools ≥36，并涉及 AGP 9 内建 Kotlin 迁移；当前 Gradle 为 8.11.1、Build Tools 为 35。 |
| Compose BOM 2024.12.01 → 2026.09.00 | 新 BOM 映射的 Compose UI 1.12.1 要求 compileSdk ≥37、AGP ≥9.1。 |
| Navigation 2.8.5 → 2.10.2 | AAR 要求 compileSdk ≥37、AGP ≥9.1。 |
| Lifecycle 2.8.7 → 2.11.0 | runtime-compose／viewmodel-compose AAR 要求 compileSdk ≥37、AGP ≥9.1。 |
| Core KTX 1.15.0 → 1.19.1 | 基础 Core AAR 要求 compileSdk ≥37、AGP ≥9.1。 |
| Activity Compose 1.9.3 → 1.13.0 | 基础 Activity AAR 要求 compileSdk ≥36、AGP ≥8.9.1。 |
| DataStore 1.1.1 → 1.3.0 | 发布物使用 Kotlin metadata 2.2、stdlib 2.2.20，超过当前 Kotlin 2.0.21 的默认读取上限 2.1。 |
| AndroidX Hilt Navigation Compose 1.2.0 → 1.4.0 | AAR 要求 SDK37／AGP9.1；发布说明对 Compose 工具链进一步要求 AGP9.2、Kotlin ≥2.2。 |

上述结论来自 [AGP 9.4 兼容表](https://developer.android.com/build/releases/agp-9-4-0-release-notes)、[内建 Kotlin 迁移文档](https://developer.android.com/build/migrate-to-built-in-kotlin)、[Compose BOM POM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.09.00/compose-bom-2026.09.00.pom) 及以下官方发布物的 `aar-metadata.properties`：[Compose UI](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.12.1/ui-android-1.12.1.aar)、[Navigation](https://dl.google.com/dl/android/maven2/androidx/navigation/navigation-compose-android/2.10.2/navigation-compose-android-2.10.2.aar)、[Lifecycle](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-compose-android/2.11.0/lifecycle-runtime-compose-android-2.11.0.aar)、[Core](https://dl.google.com/dl/android/maven2/androidx/core/core/1.19.1/core-1.19.1.aar)、[Activity](https://dl.google.com/dl/android/maven2/androidx/activity/activity/1.13.0/activity-1.13.0.aar)。另参考 [DataStore POM](https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-preferences-android/1.3.0/datastore-preferences-android-1.3.0.pom)、[Kotlin 2.0.21 metadata 兼容判断源码](https://raw.githubusercontent.com/JetBrains/kotlin/v2.0.21/core/metadata.jvm/src/org/jetbrains/kotlin/metadata/jvm/deserialization/JvmMetadataVersion.kt)、[AndroidX Hilt 发布说明](https://developer.android.com/jetpack/androidx/releases/hilt#1.4.0-beta01)。

## 验证边界

最终普通构建命令已成功退出，无需 `-Pksp.useKSP2=true` 临时参数：

```sh
./gradlew --offline :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --max-workers=4 --console=plain
git diff --check
```

| 检查 | 最终结果 |
|---|---|
| 单元测试／Robolectric | 21 个测试类、98 项测试；0 failures、0 errors、0 skipped。 |
| Lint Debug | 0 errors、27 warnings；均为保留的依赖／AGP 版本提示。 |
| Debug 构建 | 成功。 |
| Release 构建 | 成功，包含 R8；产物仍为未签名 APK。 |
| 数据库 schema | `app/schemas/.../4.json` 无变化，迁移和备份回归测试通过。 |
| 补丁格式 | `git diff --check` 通过。 |

报告位置：`app/build/reports/lint-results-debug.html`、`app/build/reports/tests/testDebugUnitTest/index.html`。构建输出未纳入 Git，后续执行可能覆盖。首次依赖转换曾因 Material Icons Extended 的大体积耗时较长；两次线程采样有推进且最终成功，因此没有据此更换 AGP 或宣称出现死循环。

没有增加 Lint baseline、ignore、disable 或 suppress。版本提示会继续公开显示；未将“存在更新版本”视为已确认漏洞。系统设置入口与主题图标尚未在真机验证，ColorOS 自启动／后台运行仍需用户按厂商设置检查；系统电池优化豁免不代表其他限制已经解除。

# PennyPilot

本地优先的个人记账应用，面向 **Android 与 iOS**。Flutter / Dart 共用界面、账务计算、录入校验、DeepSeek / Qwen AI 请求、账本查询与月报逻辑。Android 保留 Room、支付采集和现有 ChatGPT 登录；iOS 使用 SQLite、Keychain 与系统语音服务。构建和设备验证状态见 [跨平台验收记录](docs/reviews/2026-10-23-cross-platform.md)。

## 功能

- 手动录入、编辑、删除，收入与支出分开记录，整数分金额校验。
- 一句话或语音解析多笔账单，逐笔核对、修改后原子保存。
- 支付通知与支付页面采集，待确认队列、疑似重复提示、确认或忽略。
- 月份切换、商户/备注/分类搜索、收支和已忽略筛选。
- 月度总预算、已用与剩余提示、超支状态。
- 每日支出图、分类明细与历史月度回顾。
- 基于真实账本的 AI 问答，支持 ChatGPT 账号、DeepSeek、Qwen。
- 分类与通知解析规则管理、系统权限诊断、数据库备份恢复。
- 浅色/深色/跟随系统，手机底部导航与宽屏侧边导航。

支付通知与其他应用页面的自动采集仅在 Android 上提供。iOS 使用手动、文字和语音录入，界面不会显示无法使用的 Android 权限开关。两端使用同一 JSON 备份格式转移账本；目前没有自动云同步。

## 运行

验证环境：Flutter **3.47.7** / Dart **3.13.5**、JDK 17、Android compile SDK 36、NDK 28.2.13676358（compile SDK 和 NDK 跟随 Flutter，目标 Android SDK 35）；新 Android 构建使用 Gradle 8.14.3、AGP 8.11.1、Kotlin 2.2.20。首次构建需要网络下载标准工具与依赖。

```sh
# 已配置 Flutter PATH 或 FLUTTER_ROOT 时直接使用 flutter 也可。
./tool/flutterw pub get
./tool/flutterw analyze
./tool/flutterw test
./tool/flutterw run -d <android-device-id>
./tool/flutterw build apk --debug

# macOS + Xcode + 已安装的 iOS Simulator runtime
./tool/flutterw build ios --simulator --debug --no-codesign
# iPhone 真机需要在 Xcode 配置自己的开发团队与签名
./tool/flutterw run -d <ios-device-id>
```

Flutter SDK 从 [官方安装页](https://docs.flutter.dev/install/manual) 获取。`tool/flutterw` 依次读取 `FLUTTER_ROOT`、项目内 `.tooling/flutter`、PATH、`android/local.properties`。本工作区已将官方 SDK 放在 `.tooling/flutter`，重新克隆仓库时需自行配置 SDK。本机 SDK 路径、构建缓存与安装包不进入 Git。

调试 APK 位于 `build/app/outputs/flutter-apk/app-debug.apk`。发布版需为 `android/app/build.gradle.kts` 配置自己的 signingConfig；项目不会把调试签名当作发布签名。

## 架构

```text
lib/
  core/                   模型、仓储组合、状态与账务计算
    ai/                   共用 AI 请求、查询、月报、iOS ChatGPT OAuth
    storage/              iOS SQLite 与可移植备份校验
    platform/             语音、系统文件选择器、权限能力
  features/
    ledger/               流水、搜索筛选、待确认队列
    entry/                手动/文字/语音录入与核对
    reports/              统计与月报
    chat/                 账本问答
    settings/             采集、AI、分类、规则、预算、备份
  ui/                     Material 3 主题与共享组件
android/app/              Flutter Android 宿主与平台桥接
ios/                      Flutter iOS 宿主、权限说明与 Keychain 配置
app/src/main/java/        复用的 Kotlin 数据/平台/AI 核心
app/src/test/             原生核心回归测试
```

`android/app` 的 `prepareNativeCore` 任务从原生源码同步生成只含数据、服务与工具的构建目录，使 Kotlin 和 KSP 使用相同输入；旧 `ui/**`、旧 `MainActivity.kt` 和 Compose 专用 `CategoryColors.kt` 已排除。原 Compose 源码与根 Gradle 构建保留作历史参考和既有回归测试，不会打进 Flutter 应用。迁移时工作区已有修改均保留。

原生回归测试仍可执行：

```sh
./gradlew testDebugUnitTest
```

桥接契约见 [docs/flutter-bridge-contract.md](docs/flutter-bridge-contract.md)。界面设计依据和规范见 [DESIGN.md](DESIGN.md)。

真实平台集成测试（只在专用测试模拟器运行）：

```sh
./tool/flutterw test integration_test/android_smoke_test.dart -d <test-emulator-id> --no-uninstall
./tool/flutterw test integration_test/cross_platform_smoke_test.dart -d <test-emulator-id> --no-uninstall
# 第二条同样支持 iOS Simulator，替换设备 ID 即可。
```

该测试只创建带唯一标记的临时记录，结束时恢复并核对原有账本记录。**Flutter 测试工具默认在结束后卸载应用，必须保留 `--no-uninstall`**；即便如此，也不要在日常使用的真实账本设备上运行集成测试。测试前备份，结束后重新构建正常入口，Android 用 `adb install -r build/app/outputs/flutter-apk/app-debug.apk` 覆盖安装，iOS Simulator 用 `xcrun simctl install <device-id> build/ios/iphonesimulator/Runner.app` 安装。验收结果见 [跨平台验收记录](docs/reviews/2026-10-23-cross-platform.md)。

## 数据与权限

应用 ID 为 `com.pennypilot.app`。Android 沿用 `pennypilot.db`（Room v5）及原加密存储，同签名覆盖安装可保留旧账本和账号，不要先卸载旧版。iOS 在应用私有目录使用 `pennypilot-portable.sqlite`，凭据留在 Keychain。两端首次打开均为空账本，不注入示例账单。

金额存储保持旧版约定：支出为正分、收入为负分；Dart 模型以正金额和收支类型表示，桥接统一转换。统计只计算已确认账单。

新备份为 `format: pennypilot, version: 1` 的 JSON，包含账单、分类、规则、月报及原有采集证据，不包含凭据、主题、预算或设备权限，最大 20 MiB。恢复前验证格式和引用关系，在单个事务中替换账本；恢复后清空旧页面与草稿，拒绝恢复前版本的写入。Android 的“导入旧版数据库备份”保留 `.db` / 同名 `-wal` 的兼容路径；旧备份先在 Android 恢复，再导出 JSON 传到 iOS。

自动采集当前支持微信、支付宝、美团和抖音相关支付通知/页面，实际可用性受通知内容、系统授权和手机后台策略影响。通知权限、通知读取权、无障碍权限彼此独立；屏幕读取和云端解析默认关闭，开启前显示用途说明。

Android 和 iOS 的普通账本功能使用相同应用接口，各自的存储适配器负责事务和持久性。桌面、Web 尚未作为发布目标。iOS 不能照搬 Android 的跨应用通知监听与无障碍支付采集；不以模拟成功代替缺失系统能力。真实账户登录、语音识别、系统文件选择器与 OEM 后台行为的验证边界单独记录，不把单元测试当真机证据。

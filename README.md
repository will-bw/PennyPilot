# PennyPilot

本地优先的个人记账应用，使用 Flutter / Dart 构建 Android 与 iOS 共用界面。记录收支、核对支付记录、查看预算与月报，也可以用文字或语音录入和查询账本。

[下载 Android 版](https://github.com/will-bw/PennyPilot/releases/latest) · [更新记录](CHANGELOG.md) · [发布说明](docs/RELEASING.md)

## 功能

- 手动录入、编辑和删除账单，支持分类、月份、搜索与收支筛选。
- 一句话或语音解析多笔账单，逐笔核对后保存。
- Android 支付通知与支付页面采集，待确认队列、重复提示、确认或忽略。
- 月度预算、每日支出、分类统计和历史月报。
- 基于账本的 AI 问答，支持 ChatGPT 账号、DeepSeek 和 Qwen。
- JSON 账本备份与跨端恢复，浅色、深色和宽屏布局。
- 在设置中手动检查 GitHub Release，Android 下载更新后由系统确认安装。

账本保存在设备上，目前没有自动云同步。使用 AI 时，相关输入和必要账本数据会按功能发送给所选服务。自动采集仅适用于 Android；iOS 提供手动、文字和语音录入，不提供跨应用通知监听或支付页面采集。

## 安装与更新

当前发布版本为 **v0.4.0（构建号 4）**，提供 Android 8.0 及以上的通用 APK：`PennyPilot-v0.4.0-android.apk`。从 [GitHub Releases](https://github.com/will-bw/PennyPilot/releases) 下载后，按 Android 提示允许当前安装来源并确认安装。iOS 工程可自行构建，目前没有对外分发的 iOS 安装包。

打开「设置 → 数据与应用 → 检查更新」。检查和下载均由用户主动触发；发现新版后选择「下载更新」，校验成功后选择「安装更新」。若系统要求安装授权，按页面提示前往授权，再返回继续安装。iOS 可查看发布说明，目前 Release 仅提供 Android 包。

每次 Release 同时提供 `update.json` 和 `SHA256SUMS`。应用校验下载大小和 SHA-256，并在调用 Android 安装器前验证包名、构建号与签名；最终安装由系统提示确认。后续正式版必须使用相同应用 ID、同一发布签名和更高构建号，才能覆盖安装并保留本地数据。

**旧开发版使用 debug 签名，不能直接覆盖为此正式签名版**：先从旧版导出账本备份，妥善保存后再迁移到正式版并恢复。Flutter 版使用 JSON 备份；旧原生版导出的 `.db` 备份可在「导入旧版数据库备份」中恢复，如有同名 `-wal` 文件需一并保留并选择。备份不含账号凭据、预算、主题或权限，迁移后需重新设置。不要在尚未确认备份可用时卸载旧版。

将 APK、`update.json` 和 `SHA256SUMS` 下载到同一目录，可手动验证文件：

```sh
shasum -a 256 -c SHA256SUMS
```

## 开发

工具链：Flutter 3.47.7 / Dart 3.13.5、JDK 17、Android SDK 36；iOS 需要 macOS、Xcode 和对应模拟器或已配置签名的真机。先配置 `FLUTTER_ROOT` 或把 Flutter 加入 `PATH`，然后运行：

```sh
./tool/flutterw pub get
./tool/flutterw analyze
./tool/flutterw test
./tool/flutterw run -d <device-id>

# Android 调试包
./tool/flutterw build apk --debug
# iOS 模拟器
./tool/flutterw build ios --simulator --debug --no-codesign
# Android 原生核心回归
./gradlew testDebugUnitTest
```

`tool/flutterw` 也支持项目内 `.tooling/flutter` 和 `android/local.properties` 指定的 SDK。SDK、缓存、密钥和安装包不进入 Git。正式签名、构建与发布步骤见 [RELEASING.md](docs/RELEASING.md)。

平台集成测试只在专用测试设备运行，先备份，并保留 `--no-uninstall`，避免 Flutter 测试结束时卸载应用：

```sh
./tool/flutterw test integration_test/cross_platform_smoke_test.dart -d <test-device-id> --no-uninstall
```

测试后重新构建并安装普通 `main.dart` 入口。单元测试和模拟器验证不替代真实账户授权、语音识别、手机后台行为或 iPhone 真机验收。

## 项目结构

| 路径 | 用途 |
| --- | --- |
| `lib/` | Flutter 界面、账务逻辑、AI、跨平台存储适配 |
| `android/` | Android 宿主与平台桥接 |
| `ios/` | iOS 宿主、权限和 Keychain 配置 |
| `app/` | Android Room、支付采集、原生账号等核心及回归测试 |
| `test/`、`integration_test/` | Flutter 单元、组件与平台集成测试 |
| `docs/` | 桥接契约、设计约束和发布流程 |

Android Flutter 构建会从 `app/` 同步原生核心源码，不能删除该目录。原 Compose UI 不进入 Flutter APK；根 Gradle 构建保留原生回归测试入口。Android 沿用 Room 数据库，iOS 使用 SQLite；两端使用同一 JSON 备份格式，凭据分别保存在设备安全存储。

[产品范围](PRODUCT.md) · [视觉规范](DESIGN.md) · [桥接契约](docs/flutter-bridge-contract.md) · [Apache-2.0 许可证](LICENSE)

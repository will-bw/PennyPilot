# PennyPilot

本地优先的个人记账应用，使用 Flutter / Dart 构建 Android 与 iOS 共用界面。记录收支、核对支付记录、查看预算与月报，也可以用文字或语音录入和查询账本。

[下载 Android 版](https://github.com/will-bw/PennyPilot/releases/latest) · [更新记录](CHANGELOG.md) · [发布说明](docs/RELEASING.md)

## 功能

- 手动录入、编辑和删除账单，支持分类、月份、搜索与收支筛选。
- 文字、语音和图片推断多笔账单，展示字段依据、补充问题与业务用途，核对后保存；明确的主动输入可单独启用自动保存。
- 独立语音模型设置，支持 Qwen ASR 与兼容转写 API，可先录音测试；无需手机自带语音识别服务。
- Android 支付通知与支付页面采集，微信无通知转账的完成状态识别、延迟字段补读、重复提示、确认或忽略。
- 首次使用统一引导通知使用权、无障碍、记账提醒、后台运行与麦克风授权，可跳过并从「设置 → 权限设置」继续。
- 可选本地截图 OCR、未知支付页面的云模型推断，以及独立的文字、视觉和复核模型配置。
- 微信和支付宝 CSV 账单导入预览，区分新增、疑似重复和冲突；转账、借还款与退款分别处理。
- 月度预算、每日支出、分类统计和历史月报。
- 账本 AI Agent：自主多步查询与分析、工具纠错、持久会话、上下文压缩和长期记忆；支持 ChatGPT 账号、DeepSeek 和 Qwen。
- 在对话中提出新增、修改、删除账单与预算的提案，核对后执行；可查看计划、执行依据并停止任务。
- JSON 账本备份与跨端恢复，浅色、深色和宽屏布局。
- 在设置中手动检查 GitHub Release，Android 下载更新后由系统确认安装。

账本保存在设备上，目前没有自动云同步。使用 AI 时，相关输入、对话上下文、检索到的记忆和必要账本数据会按功能发送给所选服务。自动采集仅适用于 Android；iOS 提供手动、文字和语音录入，不提供跨应用通知监听或支付页面采集。

「问账」右上角可管理对话、查看与删除记忆、设置上下文预算和执行步数。会话和记忆保存在本机独立数据库，当前不进入账本 JSON 备份。Agent 实现、开源方案调研和验证边界见 [Agent 架构](docs/agent-architecture.md)。

语音录入前，在「设置 → 语音模型」配置服务地址、转写模型和 API Key。默认提供 Qwen ASR 的中国内地端点与 `qwen3-asr-flash` 模型名称，密钥需自行填写且须与地区、模型权限匹配。可选择兼容 `/audio/transcriptions` 的服务。点击「开始测试录音」后说话，再点「结束并转写」验证；测试只返回文字，不新增账单。录音最长 60 秒，云端转写可能消耗所选服务额度，密钥不进入账本备份。

首次打开应用后，可从欢迎页进入统一权限引导。按实际用途逐项开启，也可直接开始手动记账；之后在「设置 → 权限设置」继续，系统设置返回时自动刷新授权状态。Android 显示通知使用权、无障碍服务、记账提醒、后台运行与麦克风；iOS 只显示麦克风。授权麦克风不会开始录音，也不会配置或调用云服务。

微信转账没有系统通知时，可在「设置 → 权限设置」开启页面采集，或在「设置 → 自动采集」开启「读取可见支付结果」，阅读说明后授予系统无障碍权限，确认服务已连接。完成后在结果页稍作停留，回到流水核对待确认账单；用途不明的转账不会直接计入消费。「识别与录入」可分别设置文字、视觉、复核服务商和模型，开启页面云识别、本地视觉补充或云图片识别；这些选项默认关闭。页面受保护、金额不可观察时仍须主动补录或导入账单。诊断记录保留最近处理阶段，也可主动采集一次诊断样本，预览后复制。自动化范围与手机验收步骤见 [识别系统验证](docs/testing/recognition-system.md)。

## 安装与更新

当前发布版本为 **v0.6.0（构建号 15）**，提供 Android 8.0 及以上的通用 APK：`PennyPilot-v0.6.0-android.apk`。从 [GitHub Releases](https://github.com/will-bw/PennyPilot/releases) 下载后，按 Android 提示允许当前安装来源并确认安装。iOS 工程可自行构建，目前没有对外分发的 iOS 安装包。

本版新增账本 Agent、本机会话与长期记忆、账单和预算提案核对、统一权限引导，并完善微信收款、红包及钱包退款识别。沿用原正式签名，支持覆盖更新并保留账本和配置。会话和记忆当前不进入账本 JSON 备份；支付采集的适用条件见 [更新记录](CHANGELOG.md)。

打开「设置 → 数据与应用 → 检查更新」。检查和下载均由用户主动触发；发现新版后选择「下载更新」，校验成功后选择「安装更新」。若系统要求安装授权，按页面提示前往授权，再返回继续安装。iOS 可查看发布说明，目前 Release 仅提供 Android 包。

每次 Release 同时提供 `update.json` 和 `SHA256SUMS`。应用校验下载大小和 SHA-256，并在调用 Android 安装器前验证包名、构建号与签名；最终安装由系统提示确认。后续正式版必须使用相同应用 ID、同一发布签名和更高构建号，才能覆盖安装并保留本地数据。

将 APK、`update.json` 和 `SHA256SUMS` 下载到同一目录，可手动验证文件：

```sh
shasum -a 256 -c SHA256SUMS
```

## 开发

所有开发都在工作分支进行，通过 Pull Request 合并到受保护的 `main`；禁止直接推送、强制推送或删除主分支，管理员同样遵守。具体步骤见 [贡献指南](CONTRIBUTING.md)。

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

# 发布 PennyPilot

Git 仓库保存可构建源码、测试、依赖锁定文件和必要的公开说明；Android 安装包通过 [GitHub Releases](https://github.com/will-bw/PennyPilot/releases) 分发。开发过程、方案取舍、验证记录和原型保留在本机，不纳入后续版本。

## 版本与发布资产

`pubspec.yaml` 的 `version` 是版本来源。`0.9.0+23` 对应版本名 `0.9.0`、Android 构建号 `23`，Git 标签为 `v0.9.0`。每次更新都必须递增构建号；版本名变大不能代替构建号递增。

每个正式 Release 包含：

| 文件 | 用途 |
| --- | --- |
| `PennyPilot-v0.9.0-android.apk` | 签名后的通用 Android 安装包 |
| `update.json` | 应用读取的版本与下载校验清单 |
| `SHA256SUMS` | 发布文件的 SHA-256 摘要，供下载者校验 |

Release 标签、APK 实际版本与清单必须一致。后续版本替换上述版本号，并在 [CHANGELOG.md](../CHANGELOG.md) 添加面向用户的本版变更。正式更新只使用完整发布的稳定 Release；先建立草稿、上传并核对全部资产，再公开发布。不要覆盖已经发布的同版本安装包；修复需要新版本和更高构建号。

## 公开更新说明

GitHub Release 正文会直接展示在应用更新页。只列普通用户关心的本次新增、体验改善和问题修复，每条用简短自然的话说明使用结果；CHANGELOG 的本版条目与之同步。

不要附带签名、构建号、校验、安装或更新入口、APK 下载、通用模型配置/权限授权、平台限制或备份说明。这些内容留在使用指南或发布流程中，不作为每次发布消息的模板。仅当本版变化确实要求用户执行必要操作时，简短说明该操作。

私人沟通、实现取舍、验收安排、测试数量和构建日志保留本地。公开前回读实际 Release 正文，确认是几条用户可感知的变化；单改仓库文档不会更新已经上线的发布消息。

## 发布签名

应用 ID 固定为 `com.pennypilot.app`。将发布密钥保存在 `.signing/pennypilot-release.jks`，本机配置保存在 `android/key.properties`：

```properties
storeFile=../.signing/pennypilot-release.jks
storePassword=<本机密钥库密码>
keyAlias=<发布密钥别名>
keyPassword=<本机密钥密码>
```

上述文件已加入 `.gitignore`。密钥与密码不得上传 GitHub、写入 Release 或打包进 APK。发布者应独立备份密钥和密码；后续更新必须使用同一密钥，重新生成一个同名文件不能替代原签名。公开证书指纹保存在 `android/release-certificate.sha256`；发布脚本会拒绝使用其他密钥签名的 APK，不要为了绕过校验修改该指纹。

构建 release 必须使用发布签名，后续版本沿用同一密钥。

## 检查与构建

在仓库根目录执行：

```sh
./tool/flutterw pub get
./tool/flutterw analyze
./tool/flutterw test
./gradlew testDebugUnitTest
git diff --check
./tool/flutterw build apk --release
python tool/prepare_release.py
```

Flutter 检查、测试和构建须串行执行；并行执行可能重写平台插件注册文件，使 release 构建误引用测试插件。发布脚本需要 Python 3.11 或以上。

正式 APK 构建使用上方完整命令，保留默认的平台文件刷新，不添加 `--no-pub`。此前的调试或 iOS 构建可能留下包含 `integration_test` 的 Android 插件注册文件；完整 release 构建会重新生成文件，排除测试插件。

产物为 `build/app/outputs/flutter-apk/app-release.apk`。发布脚本默认从该文件读取实际 APK 信息，验证发布签名、应用 ID、版本名与构建号，并生成 `build/releases/v0.9.0/` 下的三份发布资产。`update.json` 的 APK 下载地址指向该版本的 GitHub Release；文件大小和 SHA-256 从实际 APK 生成，不手写摘要。

发布前用 Android SDK 的 `apksigner verify --verbose --print-certs` 检查证书，将证书 SHA-256 与上次正式发布比较。安装包的 SHA-256 是文件摘要，不等同于签名证书摘要。

在专用测试设备上覆盖安装、启动并验证现有账本；新装时应为空账本。检查更新链路至少覆盖：当前已最新、发现新版、下载失败、摘要不符、安装权限未授予和交给系统安装器。实际测试证据与未完成的验证项留在本地记录中；公开更新说明按上方要求只列本次用户可感知的变化和修复。

不要在日常账本设备上运行集成测试。测试设备先备份，Flutter 集成测试保留 `--no-uninstall`，测试后重新构建普通应用入口。

## 更新清单契约

`update.json` 使用以下结构，示例中的大小与摘要为占位值，发布时由脚本替换：

```json
{
  "schemaVersion": 1,
  "version": "0.9.0",
  "versionCode": 23,
  "apkUrl": "https://github.com/will-bw/PennyPilot/releases/download/v0.9.0/PennyPilot-v0.9.0-android.apk",
  "sha256": "<实际 APK 的 64 位小写十六进制 SHA-256>",
  "size": 0
}
```

应用通过 GitHub `releases/latest` 获取稳定 Release，要求标签为 `v` 加清单版本号、对应 APK 资产存在、清单大小与 GitHub 资产大小一致。APK 最大 200 MiB。下载存放于应用缓存，取消或失败会清除未完成文件。

检查和下载均由用户手动触发，进入更新页面不会自动请求网络。下载验证大小和摘要后，Android 原生侧再次校验摘要、包名、构建号和签名；未知来源未授权时引导系统设置，返回后再继续安装，最后由系统安装器确认。iOS 提供发布说明入口，目前不提供 iOS 包下载与安装。

## 发布与核验

1. 在工作分支提交已通过检查的源码、版本号及发布文档，通过 PR 合并到 `main`，不得直接推送或强制推送主分支。确认没有密钥、账本、SDK、缓存或测试产物进入 Git；保留 `app/` 原生核心、Room schema、Gradle wrapper 和依赖锁定文件。
2. 从 `main` 上已合并的提交构建正式 APK，运行 `python tool/prepare_release.py` 生成三份发布资产。
3. 创建对应标签及 Release 草稿，上传所有资产；正文按上方要求只列本次用户可感知的变化和修复。
4. 核对 APK 签名、版本、文件大小和 SHA-256，核对远端资产与本地产物一致，再公开 Release。
5. 从公开 Release 下载或用 GitHub API 读取资产，确认版本清单可访问。用应用手动检查一次更新，并记录结果。

下载者可以将 APK、`update.json` 和 `SHA256SUMS` 放在同一目录后执行：

```sh
shasum -a 256 -c SHA256SUMS
```

当前只分发 Android APK。iOS 发布需要自己的 Apple 开发团队、签名和分发渠道；模拟器构建成功不代表已具备 iPhone 分发签名。

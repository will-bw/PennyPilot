# 贡献指南

`main` 是受保护的主分支。所有开发在工作分支进行，代码、文档、版本号及发布准备变更都通过 Pull Request 合并到 `main`，仓库管理员同样遵守。

## 开发与合并

1. 修改文件前，检查工作区；保留他人的未提交变更。从最新的 `origin/main` 创建工作分支，例如 `feat/short-topic`、`fix/short-topic` 或 `docs/short-topic`。Agent 默认使用 `codex/` 前缀。

   ```sh
   git status --short --branch
   git fetch origin
   git switch -c feat/short-topic origin/main
   ```

2. 在工作分支开发、检查和提交，再推送该分支。

   ```sh
   git diff --check
   git add <本次变更的文件>
   git commit -m "feat: describe the change"
   git push -u origin HEAD
   ```

3. 创建以 `main` 为目标的 PR，说明变更目的、验证结果和已知限制。按改动范围运行下方的[检查与构建](#检查与构建)；纯文档改动无需运行应用测试。审批人数和必需检查以 GitHub 当前分支规则为准。

   ```sh
   gh pr create --base main
   ```

4. 在 GitHub 上通过 PR 合并。不要在本地将变更合入 `main` 后直接推送；处理冲突时更新工作分支，再继续原 PR。

## 主分支约束

- 不在 `main` 上开发或创建提交，不直接推送到远端 `main`。
- 不强制推送、改写远端 `main` 的历史或删除远端 `main`；`--force-with-lease` 也属于禁止范围。
- 不通过关闭或削弱分支保护、管理员绕过等方式跳过 PR 流程。
- 发布准备变更也走 PR；从 `main` 上已合并的提交构建正式版本并创建标签，详见 [发布说明](docs/RELEASING.md)。

## 开发环境

工具链：Flutter 3.47.7 / Dart 3.13.5、JDK 17、Android SDK 36。iOS 需要 macOS、Xcode，以及模拟器或已配置签名的真机。

将 Flutter 加入 `PATH` 或配置 `FLUTTER_ROOT`；`tool/flutterw` 也支持项目内 `.tooling/flutter` 和 `android/local.properties` 中指定的 SDK。

## 检查与构建

Flutter 检查、测试和构建须串行执行，避免生成的平台插件注册文件相互覆盖。按变更范围运行：

```sh
./tool/flutterw pub get
./tool/flutterw analyze
./tool/flutterw test
./tool/flutterw run -d <device-id>

# Android 调试包
./tool/flutterw build apk --debug
# iOS 模拟器
./tool/flutterw build ios --simulator --debug --no-codesign
# Android 原生核心回归（根工程）
./gradlew testDebugUnitTest
# Android 宿主检查（使用 Android 工程的 wrapper）
./android/gradlew -p android :app:testReleaseUnitTest :app:lintRelease
```

正式签名、构建与发布步骤见 [发布说明](docs/RELEASING.md)。

平台集成测试只在专用测试设备运行，先备份，并保留 `--no-uninstall`，避免 Flutter 测试结束时卸载应用：

```sh
./tool/flutterw test integration_test/cross_platform_smoke_test.dart -d <test-device-id> --no-uninstall
```

测试后重新构建并安装普通 `main.dart` 入口。单元测试和模拟器验证不替代真实账户授权、语音识别、手机后台行为或 iPhone 真机验收。

## 项目结构与文档

| 路径 | 用途 |
| --- | --- |
| `lib/` | Flutter 界面、账务逻辑、AI 与跨平台存储适配 |
| `android/` | Android 宿主与平台桥接 |
| `ios/` | iOS 宿主、权限和 Keychain 配置 |
| `app/` | Android Room、支付采集、原生账号等核心及回归测试 |
| `test/`、`integration_test/` | Flutter 单元、组件与平台集成测试 |
| `docs/` | 公开的用户指南与发布说明 |

Android Flutter 构建会从 `app/` 同步原生核心源码，不能删除该目录。修改原始源码，不编辑构建目录中的生成副本；根 Gradle 构建保留原生回归测试入口。

当前图标源文件与导出操作见[品牌资源说明](assets/branding/README.md)。

## 仓库内容

源码、必要资源、平台工程、依赖锁文件、Gradle wrapper、Room schema 和公开签名证书指纹随仓库提交。

公开文档只保留应用介绍、使用指南、更新记录、必要的贡献与发布流程，以及当前资源维护说明。`docs/` 默认忽略，仅 `USAGE.md` 和 `RELEASING.md` 允许提交；新增公开文档时须明确其读者和用途，并调整允许列表。

本机 Agent 约定、产品与视觉方案、架构取舍、调研、开发计划、测试执行记录、真机验收日志和历史设计稿仅保留本地，不进入 Git。本机 SDK、缓存、构建包、签名私钥与密码、设备状态、真实账本和凭据同样不提交；Android 安装包通过 GitHub Releases 分发。

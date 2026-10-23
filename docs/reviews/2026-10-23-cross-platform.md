# Android / iOS 跨平台改造验收

版本：0.3.0+3。目标是尽量完整支持 Android 和 iOS，包括共享界面、账务功能和系统能力适配。

## 实现

- `CrossPlatformLedgerRepository` 统一应用入口，复用现有 Controller、账务计算、录入校验与全部主页面。
- Android 保留 Room v5、支付采集、已有凭据与 ChatGPT 账号。原数据库不迁走，不为升级清空账本。
- iOS 新增真实 SQLite 存储，事务写入、批量原子保存、分类与规则归档、月报、普通设置、恢复后版本失效均有实现。首次启动为空账本。
- DeepSeek / Qwen 文字解析、受限账本查询与月报共用 Dart。金额统一整数分，只查询已确认账单。问账的每次请求都验证账本版本及 AI 设置；中途恢复或换账号后不再发送旧账本。
- iOS ChatGPT 实现 PKCE、state、nonce、RS256/JWKS、刷新、模型列表和 SSE，使用系统内嵌浏览器。凭据存入 Keychain；Android 延用已存在的原生账号实现。
- 系统语音与 JSON 文件导入导出通过 Flutter 插件适配。iOS 工程包含麦克风/语音用途说明与 Keychain entitlement，未配置用户开发团队或发布签名。
- JSON 备份 `format=pennypilot, version=1` 可跨端恢复，包含完整交易来源与采集证据，不含凭据。20 MiB 上限，格式/引用校验后单事务替换。Android 保留旧 `.db` 备份入口。
- iOS 隐藏 Android 的通知监听、无障碍支付采集、解析规则管理与电池授权入口；保留手动/文字/语音输入、待确认记录处理。

## 已完成检查

- Dart 静态分析：无问题。
- Flutter 全套：124 项通过，包括 Keychain 暂时不可用时账本仍能打开、已有凭据不会被清除，以及 Safari 首屏未加载时仍能取消登录和关闭服务。
- Android 原生全套：248 项通过，无失败、错误或跳过。
- 共用 JSON fixture 覆盖收入负数、待确认/忽略状态、历史重复引用、Java 正则、月报与采集证据。实际 Dart SQLite 导出已由 Kotlin codec 读取并逐字段往返，8 项原生 codec 检查通过。
- SQLite 测试覆盖重启持久化、批量回滚、无效恢复不改变原数据、旧版本写入拒绝。
- OAuth/SSE 使用合成响应与公开 RSA 测试向量验证；没有为测试发送真实用户账本或调用真实付费账号。
- `git diff --check` 通过。

## 构建与设备验证

- Android debug APK 构建成功，版本 `0.3.0+3`。首次依赖下载遇到 Maven TLS 中断及 CMake 压缩包损坏，重试官方依赖安装后构建通过，未关闭 TLS 校验。
- 专用 Android 模拟器 `emulator-5580` 运行 `cross_platform_smoke_test.dart --no-uninstall` 通过。真实 Room 桥接完成新增交易、JSON 导出/恢复、旧 revision 写入拒绝；结束后原交易、分类、规则、月报、采集记录逐表比对一致。
- 测试后重新构建普通 `main.dart` 入口，覆盖安装并启动成功，版本核验为 `versionCode=3 / versionName=0.3.0`，未发现 AndroidRuntime 或 Flutter 错误。界面截图见 `build/test-artifacts/android-ledger.png`。
- 从真实系统文件选择器导出 15,386 字节 JSON（37 条测试流水、12 个分类、5 条规则、5 份月报），再经两次确认选择同一文件恢复成功，页面显示“账本已恢复，立即生效”。导出不包含设置或凭据。测试文件见 `build/test-artifacts/android-system-export.json`。
- 测试前数据库已保存至本地 `build/device-backups/emulator-before-0.3.0-20261023.tar`。测试日志见 `build/cross-platform-android-integration.log`。
- 用户的 realme 手机暂不在身边，按用户要求延后 0.3.0 安装；未对日常设备运行集成测试。
- iOS 标准 Flutter 完整构建成功，专用 iPhone 17（iOS 26.5）执行 `cross_platform_smoke_test.dart --no-uninstall` 通过。真实 SQLite 新增/备份/恢复、旧 revision 写入拒绝、逐表数据复原，以及 Keychain 读写和 OAuth host identity 初始化均通过。
- iOS 普通入口 `0.3.0+3` 已安装并启动，首次引导和空账本显示正常。系统文件选择器成功保存 JSON 到“我的 iPhone”，再选择同一文件恢复，页面显示“账本已恢复，立即生效”。本次 iOS 文件测试使用新建空账本与默认分类。截图见 `build/test-artifacts/ios-file-picker.png`。
- 实际 iOS 界面检查发现并修正了空账本提示：不再提示开启 Android 自动采集，改为添加账单或导入备份。修改后现有 UI/设置测试 26 项通过，静态分析通过。
- 最终 iOS 普通入口已重新构建、安装和启动，截图确认文案修正且无布局溢出，启动日志无应用异常。产物：`build/ios/iphonesimulator/Runner.app`；最终截图与日志：`build/test-artifacts/ios-ledger.png`、`ios-launch.png`、`ios-build.log`、`ios-integration.log`、`ios-startup-final.log`。

本机 iOS 构建环境曾出现 SDK / runtime build 不匹配：Xcode 的 SDK `iphoneos26.5 (23F81a)` 默认寻找不存在的 `23F81a` runtime，官方实际下载的 iOS 26.5 runtime 为 `23F73`。运行 Apple 提供的 `xcrun simctl runtime match set iphoneos26.5 23F73 --sdkBuild 23F81a` 后，Runner scheme 正常识别模拟器。此为本机工具链设置，不是应用源码改动；需要恢复系统默认匹配时可运行 `xcrun simctl runtime match set iphoneos26.5 --default --sdkBuild 23F81a`。

## 边界

iOS 没有 Android 的跨应用通知监听和无障碍支付页面采集能力。两端目前通过文件备份转移数据，没有自动云同步。

iPhone 真机 OAuth 登录、模型调用、语音识别和签名分发仍需设备验收；系统文件选择器已在 iOS 模拟器实测，尚未在真机验证。协议测试通过不能证明外部账号授权和手机后台行为已经验证。桌面、Web 未作为本次发布目标。

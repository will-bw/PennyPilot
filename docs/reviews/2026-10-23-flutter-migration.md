# Flutter 迁移验收

## 实现范围

主应用入口迁至 `lib/main.dart`，四个主目的地为账本、统计、问账、设置。页面、主题、录入、筛选、核对、管理与备份交互均由 Flutter 实现。Android Kotlin 保留 Room、支付采集、语音、AI、认证、安全存储与系统文件选择器，通过 MethodChannel / EventChannel 提供能力。

新增或完善：月份与搜索筛选、待确认队列与批量核对、月度预算、手动金额精度校验、多笔 AI 草稿编辑与原子保存、分类和规则管理、权限诊断、AI 失败重试、深色模式和宽屏导航。恢复备份后旧路由和草稿失效，恢复前的写入版本被拒绝。

视觉调研和设计决策见 `DESIGN.md`。UI 测试数据只在测试中使用，主应用不注入示例数据。项目原有未提交改动和旧 Compose 源码保留；旧 Compose 不进入 Flutter APK。

## 已完成检查

- `dart analyze lib test integration_test`：无问题。
- `flutter test`：50 项通过。
- `./gradlew testDebugUnitTest --console=plain`：240 项原生测试，0 失败、0 错误、0 跳过。
- `git diff --check`：通过。
- Flutter 组件验证包含空账本、录入精确金额、筛选、待确认、恢复清空编辑器、存在未保存表单时恢复、小屏 / 大字 / 深色、宽屏侧栏。
- 已目视检查组件渲染截图，位于本机 `build/test-artifacts/`；这些截图是组件测试夹具，不能用作真实设备或真实账本验证证据。
- 审查修复：月报的原生完成事件早于请求返回时，不再被前端覆盖成“生成中”；已增加回归测试。

## Android 验收

`flutter build apk --debug` 首轮成功。真实 Android 桥接集成测试已在 `emulator-5580` 通过（1 项完整用例），验证快照字段、凭据不外露、收支整数分、非法金额和分类组合拒绝、过期版本拒绝、编辑保留采集来源。测试结束清理临时记录，并核对交易、分类、规则、月报完整记录与测试前一致。

正常应用入口最终构建成功：`build/app/outputs/flutter-apk/app-debug.apk`（0.2.0+2，调试包约 188 MiB，包含多架构与调试运行时）。使用 `adb install -r` 在模拟器覆盖安装并成功启动，未见 AndroidRuntime / Flutter 错误日志。

恢复升级前数据库到旧版原生 APK，再覆盖安装 Flutter APK；最终读取 SQLite 完整行按 ID 排序逐行比较，结果如下：

| 表 | 升级前 | 升级后 | 完整记录比较 |
| --- | ---: | ---: | --- |
| transactions | 37 | 37 | 完全一致 |
| categories | 12 | 12 | 完全一致 |
| parse_rules | 5 | 5 | 完全一致 |
| monthly_reports | 5 | 5 | 完全一致 |

真实模拟器截图：`build/test-artifacts/android-ledger.png`、`android-reports.png`、`android-onboarding.png`。账本和统计截图使用模拟器升级前已有的界面验收账本；新应用没有注入样例。

测试过程说明：首次集成测试未带 `--no-uninstall`，Flutter 工具在成功测试并完成临时行清理后卸载应用，造成随后正常安装时账本为空。已从测试前备份恢复模拟器账本，并完成上述独立覆盖升级及逐行核验。工具行为已从 Flutter SDK 的 `commands/test.dart`（uninstall 默认 true）和 `test/integration_test_device.dart`（结束时卸载）确认。README 已修正命令并限定专用测试模拟器；该测试结果不代表已保留卸载前的系统授权或账户登录状态。

## 验证边界

本次平台实现为 Android。Flutter 界面可复用，但 iOS、Web、桌面没有等价的支付采集和原生存储实现。

不把模拟器验证等同于真实手机支付采集、语音识别、ChatGPT 真实账户登录或 OEM 后台驻留验证。发布签名需项目所有者配置；本次交付调试 APK。

Flutter 构建提示 Gradle 8.14.3、AGP 8.11.1、Kotlin 2.2.20 在未来版本将停止支持；本次版本实际构建通过，后续升级 Flutter 时需连同 Android 构建链一起升级。

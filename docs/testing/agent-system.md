# 账本 Agent 验证记录

日期：2026-10-24。调研与设计依据见 [Agent 架构](../agent-architecture.md)。本次没有发布版本或安装到已连接的真实手机。

## 自动验证

- `./tool/flutterw analyze --no-pub`：无问题。
- `./tool/flutterw test --no-pub`：397 项通过。其中 `test/core/agent/` 33 项，`test/ui/agent_ui_test.dart` 4 项。
- Android 调试 APK 与 iOS 普通入口模拟器构建通过。
- 原生 `InsightUseCaseTest`、`ResponsesCodecTest` 回归通过；Android 新模型桥接同时由 Flutter APK 编译和共享路由 mock 测试覆盖。
- `git diff --check`：通过。

核心用例验证：依赖前一步统计结果再查明细；错误参数回填修正；未知工具与虚构引用不作为依据；停止/硬超时中断等待中的模型响应，迟到输出不触发工具；长期记忆更新与来源原话校验；记忆正文、key 和证据排除凭据；会话 SQLite 关闭重开与账本恢复代次隔离；工具调用/结果配对修复；旧历史分块压缩且保留原文；当前回合过大时明确失败、不拆开工具往返；提案未经核对不改账、重复确认不重放；删除版本与预算旧值在账本事务内检查。

协议用例验证：ChatGPT 完成事件、加密推理项与 function_call_output 配对；切换账号禁止重放原账号数据；DeepSeek/Qwen 每轮发送 tools，回放 reasoning_content；凭据切换阻止下一次请求；Android ChatGPT 逐模型回合桥接，绕过原生旧 ask 流程。

## 界面与模拟器

`integration_test/agent_ui_test.dart` 使用独立合成账本、内存 Agent store 和脚本模型；不访问生产账本或真实模型服务。通过真实共享运行时执行计划、时间段对比、明细查询、记忆和预算提案，再检查对话、记忆和运行设置页面。

- 专用 iPhone 模拟器：浅色，集成测试通过。
- 独立 iPad 模拟器：深色、1.3 倍字号，集成测试通过。
- 组件回归：320/768 逻辑宽度和 1.3 倍字号；提案确认仅传入 id/决定；可打开已保存查询依据。
- 模拟器截图在本地忽略目录 `.impeccable/review/agent/`；独立审查确认既有视觉系统延续。iPhone 工作区顶部安全区经原分辨率图确认正常，没有为缩放预览产生的误判改动正确的布局。

运行示例（仅专用测试模拟器）：

```sh
./tool/flutterw test integration_test/agent_ui_test.dart -d <iphone-simulator-id> --no-uninstall
./tool/flutterw test integration_test/agent_ui_test.dart -d <ipad-simulator-id> --no-uninstall --dart-define=AGENT_DARK_LARGE=true
```

验收后构建普通 `lib/main.dart` 入口替换临时测试入口。不要把集成测试安装到用户的真实手机。

## 尚未覆盖

- 真实 ChatGPT、DeepSeek、Qwen 账号与具体模型的端到端任务效果、服务地区/额度差异；没有为测试读取凭据或消耗用户模型额度。
- Android 原生手机/平板截图与真实设备交互。本次未发现可用 Android 模拟器，没有改动已连接真实手机。
- 运行中、记忆展开、会话列表和依据详情有组件行为测试，但没有独立的原生截图矩阵。
- 精确 Tokenizer 和真实模型窗口发现、向量语义检索、云同步与 Agent 数据的备份迁移；当前实现采用有预算的字符估算、本地词面检索和本机持久化。

因此，本记录证明控制流、存储、协议和既定 UI 的自动测试结果，不声称真实模型能够无误完成任意任务。

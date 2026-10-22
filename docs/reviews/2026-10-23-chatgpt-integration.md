# ChatGPT 登录、套餐与模型接入 · 2026-10-23

本轮在 `a25ce54` 基础上，为 PennyPilot 的 AI 设置增加 ChatGPT 账号登录、套餐授权及按账号选择模型。AI 记账、“问问”和月报复用现有 LLM 路由；DeepSeek 和通义千问配置保留。没有新增依赖或数据库迁移。

## 用户流程

1. 打开「我的 → AI 设置」，选择 ChatGPT，点击 **Continue with ChatGPT**。
2. 在系统浏览器的 OpenAI 页面登录与 Codex 相同的 ChatGPT 账号，并确认套餐使用权限。
3. 返回 PennyPilot，首次获得套餐权限时会展示用量说明。读取当前账号的模型目录，选择模型并保存配置。
4. 在 AI 记账、“问问”或生成月报时使用所选账号及模型。用量、适用限额及应用权限通过「查看与管理套餐用量」进入 ChatGPT 官方设置查看。

这是一套独立的官方授权流程，不会读取或复制桌面 Codex 的登录文件。使用同一个 ChatGPT 账号不表示所有模型、工作空间和套餐都支持该请求；实际资格、可用额度及能力以服务端结果为准。应用没有虚构剩余额度，也不会在达到限制时自动切换到其他付费服务。原有月报在 AI 失败时生成本地统计汇总的行为保留。

## 实现与保护

| 范围 | 实现 |
|---|---|
| 官方登录 | 动态公共客户端注册、持久化安装标识、系统浏览器、127.0.0.1 随机端口回调；每次授权生成 state、nonce、PKCE S256。 |
| 回调校验 | 只接收指定 Host、路径和 GET 请求；错误 state、重复参数及超限请求不被接受；取消和超时关闭监听。 |
| 身份验证 | 通过官方发现文档及 JWKS 验证 ID token 的 RS256 签名、issuer、audience、有效期及 nonce；重新登录验证原账号 subject。身份验证与套餐 scope 分别检查。 |
| 本机凭据 | 独立 Keystore 主密钥及加密存储；账号映射与旋转凭据同步持久化，排除云备份和设备迁移。安全存储失败时不降级到明文。 |
| 续期与退出 | 并发续期串行化；已开始的刷新会完成旋转令牌保存，即使调用方取消。永久授权错误清除令牌，临时错误保留登录。退出尝试远端撤销并清理本机令牌；远端未确认时明确提示。 |
| 多账号与模型 | 按账号读取模型目录并保存模型 slug；切换账号取消旧目录读取，旧结果不能覆盖新账号；不硬编码默认模型、不把目录可见性等同于实际权限。 |
| 套餐推理 | 官方 `/v1/responses`，`store=false`、`stream=true`；只接受完整的 completed 响应，拒绝失败、截断或不完整的流。 |
| 工具调用 | 保留函数调用 ID、命名空间及加密 reasoning 项；“问问”的两次模型调用绑定同一服务配置，切换账号、服务或模型后中止旧操作。 |
| 请求与错误 | 不自动重放可能消耗额度的请求，不跟随重定向；取消会传递到网络调用。额度、权限及网络错误使用安全文案与有限诊断标识，原始服务响应和凭据不展示。 |
| 页面状态 | 浏览器往返和页面重建保留所选服务及非敏感模型草稿；API Key 草稿不进入 saved-instance-state Bundle。登录状态改变后清理过期表单提示。 |

现有 LLM 接口接收完整回答，所以内部流式响应在完整结束后交付界面；本轮没有增加逐 token 展示。

## 自动化验证

执行命令：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew --offline \
  :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleRelease \
  --max-workers=4 --console=plain
git diff --check
```

| 检查 | 结果 |
|---|---|
| 单元测试／Robolectric | 31 个测试类，162 项测试，0 failures、0 errors、0 skipped；相较基线新增 50 项。 |
| Lint Debug | 0 errors，27 warnings：24 条 GradleDependency、3 条 AndroidGradlePluginVersion，均为已有版本提示。 |
| Debug | 构建成功并覆盖安装到现有预览模拟器，保留原有示例账本。 |
| Release | 构建成功，包含 R8；产物为未签名 APK。 |
| 静态复核 | 复核账号切换、模型保存、登录往返和两阶段工具调用，未发现新增阻断项。 |

新增测试覆盖 PKCE、JWT、回调校验、账号映射、首次授权提示、并发和取消下的令牌旋转、不可重放请求、SSE 完整性及错误、工具调用历史、模型目录、跨账号结果隔离，以及设置页的保存校验。网络协议测试使用模拟传输，不会消耗用户套餐。

报告在 `app/build/reports/tests/testDebugUnitTest/index.html` 和 `app/build/reports/lint-results-debug.html`；构建产物不纳入 Git，后续构建可能覆盖。

## 模拟器验证与待完成的真实授权

使用原有 Android 35 AOSP ARM64 预览模拟器 `PennyPilotPreview`（`emulator-5580`），没有安装到真实手机，也没有清空应用数据。

- 查看 ChatGPT 设置页的布局、滚动和按钮；未登录保存时显示明确提示。
- 点击登录后系统浏览器到达 `auth.openai.com`，安全验证结束后正常显示官方邮箱、Google、Apple、Microsoft 等登录入口。
- 从浏览器返回应用后仍显示等待授权状态，可以继续或取消；横竖屏切换后所选服务与等待状态保留。
- 取消登录后恢复登录入口；最后重新安装最终构建，停留在 AI 设置的 ChatGPT 页，供用户自行登录。

截图位于被 `.gitignore` 排除的 `captures/chatgpt-integration-2026-10-23/`：`01-chatgpt-settings.png` 为设置页，`02-official-login.png` 为官方登录表单，`03-final-settings.png` 为最终安装界面。截图仅是本机验收证据。

**真实账号登录和权限确认尚需用户在官方页面完成。** 因此真实令牌交换、该账号的模型目录、成功推理、实际套餐扣量及真实续期／撤销尚未端到端验证；不能把自动化测试和到达登录页视为这些步骤已经成功。账号凭据未被读取或迁移，测试期间没有发送真实账本进行推理。

## 官方依据

本轮先核对本地接口和实现，再依据 OpenAI 当前公开文档接入：

- [Registration and sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)
- [Models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)
- [Profiles and sessions](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)
- [Token reference](https://developers.openai.com/siwc/token-sharing-open-source/token-reference)
- [Errors and recovery](https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery)
- [Preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)
- [UI/UX guidelines](https://developers.openai.com/siwc/ui-ux-guidelines)

本轮仅包含本地修改、测试、模拟器更新和 Git 提交，没有远程推送或发布。

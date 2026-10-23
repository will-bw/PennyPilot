# ChatGPT 真机事件流兼容修复 · 2026-10-23

## 复现与原因

用户在 realme RMX3888 登录并选择 ChatGPT 后，「问问」显示「未收到事件流」。本轮在保留登录和应用数据的前提下，通过安全诊断版本复现：HTTP 200，响应体以 SSE 字段开头，但 Content-Type 缺失，原实现只按响应头判断事件流，因此在读取内容前拒绝响应。

兼容缺失响应头后，真实查询进一步显示「响应没有有效内容」。原解析器忽略 `response.output_item.done`，只读取 `response.completed.response.output`。本轮接入逐项完成事件聚合后，同一手机的本月支出查询成功。这验证了该响应路径需要从完成的输出项恢复终态事件未重复携带的内容。无法仅凭应用侧观察确定 Content-Type 缺失发生在服务端还是网络中间层。

## 修复

- Content-Type 缺失时，窥读最多 256 字节识别 SSE 前缀，不消费原流；已声明其他内容类型的响应仍拒绝，并仅提供脱敏的状态码、内容类型和响应形状。
- 按 output_index 收集 `response.output_item.done`；终态输出数组为空时，使用已完成项恢复文本、工具调用及加密 reasoning 历史。检查序号连续性、重复项、未完成项及 added/done 的标识一致性。
- 仍须收到 `response.completed` 且状态为 completed；保留 UTF-8、大小限制、工具白名单、命名空间和工具参数校验。delta、EOF、DONE 哨兵及单项完成事件均不能单独代表请求成功。没有增加自动重试。
- 非流响应中的 JSON 服务错误保留安全错误码，不展示原始响应、账号凭据或账本内容。

## 验证

执行 `./gradlew --offline :app:testDebugUnitTest :app:lintDebug`：170 项测试通过，无失败、错误或跳过；Lint 0 errors、27 warnings。Debug APK 构建成功，`git diff --check` 通过。

通过 ADB 定向覆盖安装到实体手机，未操作模拟器。原有 ChatGPT 登录保留。在「问问」点击「这个月花了多少」，真实模型正常返回：「这个月截至今天（10月23日）暂无记账记录，支出合计为 **0 元**。」手机停留在成功回答页面。未验证实际扣量、令牌续期、撤销及其他模型。

## 官方依据

- [Models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)：使用公开 Responses 接口，store=false、stream=true，必须等到 response.completed。
- [Responses streaming events](https://developers.openai.com/api/reference/resources/responses/streaming-events)：output_item.done 携带完成的输出项和 output_index。

本轮仅修改本地代码、测试和文档并安装到手机，未提交或推送 Git。

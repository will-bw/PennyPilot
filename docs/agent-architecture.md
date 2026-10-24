# PennyPilot 账本 Agent

调研与实现日期：2026-10-24。当前范围是设备内运行的个人账本 Agent；模型调用所选云服务，本地工具访问真实账本。没有新增 Python/Node 服务，也不把服务商自带的聊天历史当作应用记忆。

## 调研与取舍

阅读了下列官方项目的源码或文档。没有以 Star 数、宣传 benchmark 或“能调用工具”作为完整性的判断依据，也没有声称这些项目具有相同的长期记忆能力。

| 实现 | 实际参考 | PennyPilot 的取舍 |
| --- | --- | --- |
| [pi agent loop](https://github.com/badlogic/pi-mono/blob/main/packages/agent/src/agent-loop.ts)、[compaction](https://github.com/badlogic/pi-mono/blob/main/packages/coding-agent/docs/compaction.md) | 循环中继续处理工具调用；请求前准备上下文；压缩摘要与原会话分开 | 借鉴执行和上下文的分层。工具返回之后仍把同一套工具提供给模型，由模型决定继续还是结束 |
| [LangGraph persistence](https://docs.langchain.com/oss/python/langgraph/persistence)、[ToolNode](https://github.com/langchain-ai/langgraph/blob/main/libs/prebuilt/langgraph/prebuilt/tool_node.py) | 会话检查点和跨会话 Store 分离；工具参数/执行错误转为模型可读结果 | 以独立 SQLite 保存会话检查点和记忆；账本变更单独保存确认状态，避免重新打开后盲目重放写操作 |
| [PydanticAI agent graph](https://github.com/pydantic/pydantic-ai/blob/main/pydantic_ai_slim/pydantic_ai/_agent_graph.py)、[retries](https://pydantic.dev/docs/ai/core-concepts/retries/)、[deferred tools](https://pydantic.dev/docs/ai/tools-toolsets/deferred-tools/) | 参数错误返回模型修正；显式的 deferred/approval 结果；运行级资源限制 | 工具参数按注册 Schema 验证，错误回填；账单/预算生成提案，用户在界面核对后执行，再让模型继续原任务 |
| [Goose agent](https://github.com/aaif-goose/goose/blob/main/crates/goose/src/agents/agent.rs)、[smart context management](https://github.com/aaif-goose/goose/blob/main/documentation/docs/guides/sessions/smart-context-management.md) | 执行状态机、取消与工具确认；自动压缩和溢出策略 | 给出真实执行阶段和停止入口；保留完整记录，投影接近预算时压缩。模型上下文未知时用可配置保守预算 |
| [smolagents MultiStepAgent](https://github.com/huggingface/smolagents/blob/main/src/smolagents/agents.py)、[memory](https://github.com/huggingface/smolagents/blob/main/docs/source/en/tutorials/memory.md) | action/observation/错误作为步骤记录，达到上限明确停止 | 持久记录 assistant 调用、tool 结果和运行轨迹；保留可见计划，不展示私有思维链 |

这些项目各自有运行环境、服务端与工具生态假设。直接把 TypeScript/Python/Rust 框架嵌入手机会增加运行时与跨端维护成本。因此实现共享的 Dart 领域运行时，借鉴其控制流与可靠性设计，不复制一个通用代码 Agent 的任意代码/终端权限。

## 实际调用链

`ChatScreen → LedgerController → CrossPlatformLedgerRepository → LedgerAgentRuntime`

运行时循环：

1. 读取当前持久会话，固定本次模型、账号/凭据与账本代次。
2. 添加用户消息，检索长期记忆，组装系统规则、历史摘要和最近完整消息。
3. 估算消息、工具 Schema 和推理项的上下文占用；必要时压缩较早历史。
4. 模型决定调用工具或作答。每轮模型请求都带相同注册工具，不在第一次调用后降级成普通聊天。
5. 验证调用，执行本地工具，逐项记录并回填结果；参数错误和未知工具成为可修正反馈。
6. 根据结果继续调用、更新计划、澄清、等待提案确认或完成；触及资源上限时明确保留未完成状态。

Android/iOS、ChatGPT/DeepSeek/Qwen 使用同一循环。Android ChatGPT 仅把单次模型请求交给原生适配器，凭据不会传入 Dart；本地工具、记忆、上下文与停止条件均由共享运行时负责。原生旧 Compose 问账用例不进入 Flutter UI，旧两阶段 Dart `ask` 已移除。

## 系统规则

`lib/core/agent/agent_prompt.dart` 是唯一主系统提示词，包含身份与职责、自主执行、公开计划、纠错、证据、账务口径、日期、写入核对、记忆更新与遗忘、数据注入边界、回答方式和设备时间。没有把“多步推理”实现为强制输出思维链。

涉及账本的问题应重新查本轮数据，关键数字注明 `[依据 Qn]`；运行时会拦截没有查询依据的账本直接回答和不存在的依据编号，并给模型有限次数的修正机会。这是证据存在性检查，不是逐句证明所有自然语言结论正确。

## 工具

| 工具 | 作用 |
| --- | --- |
| `ask_user` | 缺少关键字段时暂停任务，用户补充后在同一会话继续 |
| `get_ledger_overview` | 分类真实标识、数量和账目日期覆盖范围 |
| `query_transactions` | 明细分页，支持状态、方向、分类、关键词 |
| `get_transaction` | 单笔完整可编辑字段与版本 |
| `aggregate_by` | 分类、天、月、商户统计，完整总额与分组分页 |
| `compare_periods` | 两段时间的净支出、收入、差额、变化率和分类贡献 |
| `get_budget` | 全局月预算设置、指定月份净支出与余额 |
| `search_memory` / `remember` / `forget_memory` | 检索、按语义 key 更新和删除长期记忆 |
| `search_conversation` / `read_conversation` | 搜索历史原文、读取命中附近消息 |
| `update_plan` | 公开的简短任务步骤与完成状态 |
| `propose_transaction` | 准备新增/编辑账单的提案 |
| `propose_delete_transaction` | 准备单笔删除提案 |
| `propose_budget` | 准备月预算修改提案 |

金额使用整数分；统计默认只含已确认账单；退款冲减支出，互转、借还款排除消费。完整总额在本地计算，不依赖某一页明细之和。查询返回时间范围、口径、依据编号和分页信息。修改/删除前必须在本轮读取目标，应用再次检查账本代次与原记录。

## 持久化、上下文与记忆

- `pennypilot-agent.sqlite` 与 Android Room / iOS 账本库分开。会话保存原始消息、工具调用与结果、状态、计划、提案和压缩边界；重启恢复当前会话，也可新建、切换、删除。
- 记忆按 key 更新，记录种类、内容、用户原话、来源会话、时间。写入证据必须逐字来自当前用户消息，拒绝凭据类内容。最近/相关记忆少量注入，完整存储通过工具检索。采用本地词面检索，没有增加云向量服务或假称语义向量检索。
- 默认上下文预算 24,000 tokens，预留输出空间；估算含工具 Schema、消息和推理数据。字符估算不是所选模型的精确 tokenizer，也不会自动得知模型真实窗口，用户可按所用模型调整。
- 压缩只在完整 user 回合边界切分；当前工具往返不拆开。较早对话分块摘要，保留目标、约束、证据、待核对事项；不把私有/加密推理交给摘要模型。原始会话仍可检索。压缩失败保留原记录并明确报错，不静默丢弃历史。
- 恢复账本后按新的持久代次隔离会话与记忆，旧提案不能应用到新账本。普通新对话共享同代次长期记忆。
- 这些对话/记忆目前仅在本机，尚未纳入账本 JSON 备份，也没有云同步；界面不声称跨设备保留。

## 写操作、停止与恢复

模型只能创建提案，不能直接提交账本写操作。确认入口只接受存储中的提案 id 与决定，不接受界面临时拼装的任意模型载荷。新增账目携带稳定 operationId，更新/删除检查目标版本。提案已处理则拒绝重复确认。

确认前记录 applying，成功记录 applied；进程中断留下 applying 时显示 uncertain，要求先查账核对，不假装具备跨两个数据库的原子事务，也不自动重复写入。应用重启修补未配对的工具调用为 interrupted 结果，后续消息始终保持工具协议配对。

默认最多 20 次模型迭代、80 次工具调用、每轮最多 16 个调用，重复同参调用超过 3 次返回反馈；单次任务最多 8 分钟。用户可停止，不再执行后续工具，迟到模型结果不会进入会话或触发写入。已发出的云请求仍可能由服务端处理并产生费用；没有自动重发计费 POST。

## 服务商协议

- ChatGPT Responses 使用 `store:false`，回放已完成的 reasoning、function_call 与 function_call_output，绑定原账号；不同模型/账号的加密推理不混用。参考 [OpenAI Function calling](https://developers.openai.com/api/docs/guides/function-calling) 和 [reasoning item preservation](https://developers.openai.com/api/docs/guides/reasoning)。
- DeepSeek/Qwen 使用原生 tool_calls/tool_call_id，每轮保持工具列表，回放返回的 reasoning_content；拒绝长度截断、异常完成状态和不完整调用。协议参考 [DeepSeek Tool Calls](https://api-docs.deepseek.com/guides/tool_calls/) 与 [千问 Function Calling](https://help.aliyun.com/zh/model-studio/qwen-function-calling)。
- 单元测试使用可控假模型和 HTTP mock，不能替代各用户账号、具体模型、服务地区的真实联网验收。

## 验证

运行 `./tool/flutterw analyze`、`./tool/flutterw test`、`./tool/flutterw build apk --debug`。Agent 回归在 `test/core/agent/`，覆盖依赖式多步调用、工具纠错、证据约束、停止、持久会话与记忆、恢复隔离、压缩、提案确认及协议重放。真实账号联网验证不在自动测试中调用，以免消耗用户额度。

# PennyPilot 设计文档

> 自用 AI 记账 Android App，面向 OPPO ColorOS，原生 Kotlin + Jetpack Compose。

## 1. 决策总览

| 维度 | 决策 |
|---|---|
| 用途 | 自用，单用户，不上架 |
| 形态 | 原生 Android APK，Kotlin + Jetpack Compose，minSdk 26 |
| AI | 纯云端：DeepSeek 主力 + 百炼 Qwen 备用 + 百炼 Paraformer ASR |
| 数据 | Room 本地存储 + 手动导出 .db 备份 |
| 账户 | 不区分支付渠道，仅可选文本标签 |
| 分类 | 预设 9 支出 + 3 收入，支持自定义增删改 |
| 通知捕获 | 微信 + 支付宝，pending 状态 + 通知操作按钮一键确认/忽略/编辑 |
| 录入 | 文字 + 语音双模态，底部常驻输入条 |
| 洞察 | 月度报告 + 对话查账（LLM Function Calling） |

## 2. 整体架构

单模块多包的轻量 Clean 架构：

```
┌─────────────────────────────────────────────────┐
│  UI 层 (Compose)                                │
│  ├─ 流水  ├─ 记一笔  ├─ 问问  ├─ 报告  ├─ 我的  │
└──────────────┬──────────────────────────────────┘
               │  StateFlow / 事件
┌──────────────▼──────────────────────────────────┐
│  ViewModel 层 (Jetpack ViewModel + coroutines)  │
└──────────────┬──────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────┐
│  Domain 层 (用例，纯 Kotlin，可单测)             │
│  ├─ CaptureTxUseCase   (通知 → 解析 → pending)  │
│  ├─ ParseTextUseCase   (NL/语音 → 草稿)         │
│  ├─ InsightUseCase     (报告 + 对话查账)        │
│  └─ CategoryUseCase / BackupUseCase             │
└──────────────┬──────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────┐
│  Data 层                                        │
│  ├─ Room (Transaction, Category, ParseRule)     │
│  ├─ LLMClient (接口) → DeepSeek / Qwen 实现     │
│  ├─ AsrClient → Paraformer                      │
│  └─ NotificationParser (正则规则 + LLM 兜底)    │
└──────────────┬──────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────┐
│  System 层 (Android 框架)                       │
│  ├─ NotificationListenerService (通知捕获)      │
│  ├─ BroadcastReceiver (通知操作按钮)            │
│  ├─ Glance AppWidget (桌面小组件，后期)         │
│  └─ WorkManager (月度报告定时)                  │
└─────────────────────────────────────────────────┘
```

### 三条关键数据流

1. **通知捕获流**：系统通知 → NotificationListenerService → NotificationParser（先正则，失败才调 LLM）→ Room status=PENDING → App 发本地通知带 [✓][✗][✎] 按钮
2. **AI 录入流**：文字/语音 → (ASR) → ParseTextUseCase → LLM JSON → UI 预览卡片 → 用户保存 → Room CONFIRMED
3. **洞察流**：用户提问/定时触发 → InsightUseCase + Tool 定义 → LLM function call → 本地查询 → 结果回喂 → 自然语言回答 + 图表

### 核心设计原则

- LLM 永远不直接读写数据库，只通过 Tool 接口拿结构化数据，杜绝瞎编数字
- 解析双通道：通知文案走正则（快、免费、离线），自然语言走 LLM（灵活）
- 所有外部依赖（LLM/ASR）抽接口，换供应商只改实现类

## 3. 数据模型

### 3.1 transactions（账目主表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long PK | 自增 |
| amount | Long | 分为单位，支出正数，收入负数 |
| type | Enum | EXPENSE / INCOME |
| category_id | Long FK | 关联分类 |
| note | String? | 用户备注 / LLM 解析描述 |
| merchant | String? | 商户名 |
| account_tag | String? | 可选文本标签 |
| occurred_at | Long | 交易发生时间（ms） |
| created_at | Long | 入库时间 |
| source | Enum | MANUAL / NOTIFICATION / AI_TEXT / AI_VOICE |
| status | Enum | PENDING / CONFIRMED / IGNORED |
| raw_payload | String? | 原始通知文本/用户输入 |

索引：`(occurred_at)`、`(category_id, occurred_at)`、`(status)`

设计要点：
- IGNORED 不物理删除，用于去重（raw_payload 哈希 + 5 分钟时间窗）
- PENDING → CONFIRMED/IGNORED 单向状态机

### 3.2 categories（分类）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long PK | |
| name | String | 分类名 |
| icon | String | Material Icon 名 |
| type | Enum | EXPENSE / INCOME |
| sort_order | Int | 排序 |
| enabled | Boolean | 停用而非删除 |
| is_preset | Boolean | 预设不可删，只能停用 |

预置分类：
- 支出：餐饮、交通、购物、居住、娱乐、医疗、教育、人情、其他
- 收入：工资、红包、其他

### 3.3 parse_rules（通知解析规则）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long PK | |
| app_package | String | com.tencent.mm / com.eg.android.AlipayGphone |
| regex_amount | String | 提取金额的正则 |
| regex_merchant | String? | 提取商户的正则 |
| direction_hint | Enum | 支出/收入 |
| enabled | Boolean | |
| priority | Int | 多规则按优先级匹配 |

单独建表目的：微信/支付宝改文案时可热更，不用发版。

### 3.4 monthly_reports（报告缓存）

| 字段 | 说明 |
|---|---|
| id | PK |
| year_month | 报告月份 |
| total_expense | 总支出（分） |
| total_income | 总收入（分） |
| by_category_json | 分类聚合 JSON |
| llm_summary | LLM 生成的文字总结 |
| generated_at | 生成时间 |

### 3.5 备份策略

- 设置页按钮：Room .db 文件 copy 到 `/sdcard/Documents/PennyPilot/backup-YYYYMMDD.db`
- 用户手动将该目录指向网盘同步文件夹
- 恢复：反向 copy
- 不做加密（自用，风险可接受）

### 3.6 金额约定

所有金额用 Long 存分，UI 边界做 Long ↔ 字符串转换，中间层永远用 Long，避免浮点误差。

## 4. 通知捕获流程

### 4.1 主流程

```
微信/支付宝发出通知
  → NotificationListenerService.onNotificationPosted()
  → 过滤 package ∈ {com.tencent.mm, com.eg.android.AlipayGphone}
  → 去重（raw_payload 哈希 + 5min 窗口）
  → ParseRuleEngine（按 priority 逐条正则匹配）
     ├─ 命中 → {amount, merchant, direction}
     └─ 未命中 → DeepSeek 解析（失败则静默丢弃）
  → 分类推断（商户关键词匹配，匹配不到给"其他"）
  → Room 写入 status=PENDING, source=NOTIFICATION
  → 发本地通知「沙县小吃 -¥18.00 · 餐饮」
     ├─ [✓ 记入] → CONFIRMED
     ├─ [✗ 忽略] → IGNORED
     └─ [✎ 编辑] → 打开编辑页
```

### 4.2 细节

- 微信预置 2~3 条正则规则（文案因版本而异），支付宝 1~2 条
- 转账/红包/还款第一版当普通交易，用户自行编辑分类
- onListenerDisconnected 调 requestRebind()（API 24+）
- App 打开时检查服务存活，死了提示重新授权

### 4.3 ColorOS 适配（首次启动引导页）

| # | 坑 | 解法 |
|---|---|---|
| 1 | 通知使用权 | ACTION_NOTIFICATION_LISTENER_SETTINGS 引导 |
| 2 | 自启动被禁 | 跳转 com.coloros.safecenter 相关 Intent（fallback 到应用详情页） |
| 3 | 电池优化 | ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS |
| 4 | 后台冻结 | 文案引导：耗电管理 → 允许后台运行 |
| 5 | 授权后不回调 | 设置页"重新授权"按钮（关再开） |
| 6 | 锁屏通知隐藏内容 | FAQ 提示微信开"通知显示消息详情" |

### 4.4 状态自检

设置页「捕获服务状态」卡片：
- 服务是否绑定
- 最近一次收到通知的时间
- 今日捕获笔数
- 超 24h 无通知且服务绑定中 → 标红提示

### 4.5 权限

| 权限 | 用途 |
|---|---|
| 通知使用权（特殊） | 捕获交易通知 |
| POST_NOTIFICATIONS | App 发通知（Android 13+） |
| RECEIVE_BOOT_COMPLETED | 开机自启 |
| INTERNET | LLM/ASR |

不需要 READ_SMS。

## 5. AI 录入流

### 5.1 流程

```
文字/语音输入
  → (语音: Paraformer WebSocket 流式 ASR → 文字)
  → ParseTextUseCase → DeepSeek (JSON Mode)
  → 返回结构化数组（一句话可拆多笔）
  → UI 预览卡片（逐条编辑/删除/改分类）
  → 保存 → Room CONFIRMED
```

### 5.2 Prompt 设计

系统提示词（固定）：
- 角色：记账解析器，输出交易记录 JSON 数组
- 金额单位：分（整数）
- 分类：只能从动态注入的当前启用分类列表中选
- 时间推断：无明确时间用当前时间；相对时间解析为绝对时间
- 无法确定分类填"其他"
- 只输出 JSON，不解释

使用 `response_format: { type: "json_object" }` 强制 JSON 输出。

### 5.3 兜底

- LLM 超时/失败 → 弹手动记账表单（金额/分类/备注）
- 分类列表动态注入 prompt，用户改分类后下次解析自动生效

### 5.4 ASR

- 百炼 Paraformer，WebSocket 实时流式识别
- 按住说话 → 边说边出文字 → 松手填入输入框
- 用户可修改后再发送（不自动解析，防误触）

## 6. 智能洞察

### 6.1 对话查账（Function Calling）

```
用户提问
  → InsightUseCase 组装 messages + tools
  → DeepSeek 返回 tool_calls
  → 本地执行 Room 查询
  → 结果回喂 LLM
  → 生成自然语言回答 + 图表数据
  → UI 渲染（气泡 + 迷你图表）
```

Tool 定义（第一版 3 个）：

| Tool | 参数 | 用途 |
|---|---|---|
| query_transactions | start, end, category?, keyword?, type?, limit | 查明细 |
| aggregate_by | start, end, group_by(category/day/week), keyword? | 聚合统计 |
| compare_periods | period_a, period_b, group_by | 同比/环比 |

约束：
- Tool 结果原样回喂，LLM 只翻译+建议，不允许自己算数
- 非法 tool call → 重试一次 → 仍失败返回友好提示
- 对话上下文保留最近 10 轮

### 6.2 月度报告

- WorkManager 每月 1 号 09:00 触发
- 本地聚合 → 一次 LLM 调用生成 200 字总结 → 存 monthly_reports → 发通知
- 内容：总支出/收入、Top3 分类、环比变化、LLM 点评
- 报告页支持手动重新生成

### 6.3 LLMClient 接口

```kotlin
interface LLMClient {
    suspend fun chat(
        messages: List<Message>,
        tools: List<Tool>? = null,
        jsonMode: Boolean = false
    ): LLMResponse
}
```

- 实现类：DeepSeekClient（主力）、QwenClient（备用）
- 设置页可切换供应商 + 配置 API Key
- API Key 用 EncryptedSharedPreferences 加密存储
- 网络失败自动 fallback：DeepSeek 超时 → Qwen 重试

## 7. UI 页面结构

底部 5 Tab：流水 | 问问 | ＋记一笔 | 报告 | 我的

### Tab 1 流水
- 顶部：月份切换 + 本月支出总额
- 按日分组的交易列表（LazyColumn）
- 筛选：分类 / 状态（含待确认角标）
- 左滑删除/编辑

### Tab 2 问问
- 对话界面（LazyColumn 气泡）
- 底部输入条
- 回答内嵌迷你图表（Compose Canvas）

### Tab 3 记一笔（C 位大按钮）
- 底部输入条：[🎤] [输入框] [→]
- 语音按住说话，松手填入输入框
- 解析后弹出预览卡片（可编辑）

### Tab 4 报告
- 月度报告卡片列表
- 点进去看详情（图表 + LLM 总结）

### Tab 5 我的
- 分类管理
- 解析规则管理
- 捕获服务状态 + 引导
- LLM 设置（供应商/Key）
- 备份/恢复

### 编辑页
- 金额（数字键盘）、分类（网格选择器）、备注、商户、时间、账户标签
- 底部：保存 / 删除

### 设计语言
- Material 3 动态取色（跟随壁纸）
- 深色模式自动

## 8. 里程碑

| 期 | 内容 | 核心产出 |
|---|---|---|
| M1 骨架 | 项目脚手架 + Room + 手动记账 UI + 分类管理 | 能手动记、看流水、管分类 |
| M2 AI 录入 | LLMClient + ParseTextUseCase + ASR + 预览编辑 | 打字/说话记账 |
| M3 通知捕获 | NotificationListenerService + ParseRuleEngine + 待确认通知 + ColorOS 引导 | 微信/支付宝自动记 |
| M4 洞察 | Function Calling 对话查账 + 月度报告 + 备份 | 完整体验 |

每期结束标准：装到手机上日用一天，无崩溃、数据不丢。

### M1 详细拆解
1. Android Studio 项目初始化（Compose BOM、Room、Hilt、Navigation）
2. Room：Entity + DAO + 预置分类
3. 流水页 UI（LazyColumn + 日期分组）
4. 手动记账表单页
5. 分类管理页（增删改排序）
6. 底部导航框架

## 9. 测试策略

| 层 | 测什么 | 怎么测 |
|---|---|---|
| 解析引擎 | 正则对真实通知文案命中率 | JUnit 参数化，20+ 条 fixture |
| LLM 解析 | JSON 结构正确性 | 集成测试（真调 API），标 @Slow |
| Room | DAO 查询、状态流转、去重 | room-testing + 内存数据库 |
| 金额计算 | 分/元转换、求和、跨月边界 | 纯单元测试 |
| UI | 不写自动化 | 人肉测 |

fixture 随使用自然增长：每次解析失败把 raw_payload 加入测试集。

## 10. 项目结构

```
com.pennypilot.app
├── di/                  (Hilt modules)
├── data/
│   ├── db/              (Entity, DAO, Database, Migrations)
│   ├── llm/             (LLMClient, DeepSeekClient, QwenClient)
│   ├── asr/             (AsrClient, ParaformerClient)
│   └── parser/          (ParseRuleEngine, RegexRules)
├── domain/
│   ├── model/           (Transaction, Category, enums)
│   └── usecase/         (CaptureTx, ParseText, Insight, Backup)
├── ui/
│   ├── navigation/      (NavGraph, BottomBar)
│   ├── transactions/    (流水页)
│   ├── input/           (记一笔)
│   ├── chat/            (问问)
│   ├── report/          (报告)
│   ├── settings/        (我的)
│   └── components/      (通用组件)
├── service/
│   ├── NotificationCaptorService.kt
│   └── NotificationActionReceiver.kt
└── worker/
    └── MonthlyReportWorker.kt
```

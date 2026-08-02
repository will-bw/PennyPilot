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

## 7. UI 设计

> 设计定稿：**清新简约 · 杂志感 + 靛蓝主色（AI 科技感）**。
> 高保真交互原型：`docs/ui/pennypilot-ui-concept.html`（浏览器打开，5 Tab 可切换、明暗双主题，URL 支持 `#tab=N` / `#dark` 直达）。开发时以原型为视觉基准。

### 7.1 设计语言

以 Material 3 组件为骨架，但**不使用动态取色**，改用固定品牌色。
（决策变更：原方案「动态取色跟随壁纸」辨识度低、AI 品牌感弱，定稿为固定靛蓝 + 深蓝黑暗色模式。）

**色彩 tokens**

| Token | 浅色 | 深色 | 用途 |
|---|---|---|---|
| primary 靛蓝 | `#3B54E1`（深色渐变端 `#2B3CB8`） | `#8B9AFF` | 主按钮、AI 元素、选中态、图表强调 |
| primarySoft | `#EDEFFD` | rgba(139,154,255,.13) | 靛蓝浅底（标签、图表底色、选中背景） |
| background | `#F3F5FA` | `#0E1122` | 页面底（深色为深蓝黑，非纯黑） |
| surface | `#FFFFFF` | `#181C33` | 卡片 |
| ink | `#181C2E` | `#ECEDFA` | 正文 / 支出金额 |
| sub / faint | `#6E7494` / `#A6ABC6` | `#9BA1C7` / `#62688E` | 次要 / 三级文字 |
| income 暖红 | `#DF4438` | `#FF8172` | 收入金额、删除 |
| pending 琥珀 | `#B45309` on `#FFF3DF` | `#FFB84D` | 待确认状态专属，不用于他处 |
| success 绿 | `#1E9E6A` | `#4ADE9C` | 服务健康态、支出环比下降 |
| hairline | `#E8EBF4` | `#272C4B` | 1px 发丝分割线 |

**收支配色约定（定稿）**：收入 = 暖红 +「+」前缀；支出 = 墨色 +「−」前缀。符号 + 色彩双编码，色弱友好，避免满屏红绿焦虑。

**排印**
- 衬线标题（页面名 / 报告标题 / AI 点评引言）：SerifFontFamily——杂志感核心，贯穿全 App 页面标题（定稿）
- 所有金额 `fontFeatureSettings("tnum")` 等宽数字，列表右对齐不跳动
- 字阶：46sp 月度总额 / 37sp 报告总额 / 27sp 页面标题 / 16sp 列表金额 / 14.5sp 正文 / 11.5sp 辅助说明 / 10.5sp eyebrow（大写 + 2.2dp 字距）

**形状与质感**
- 圆角：卡片 22dp；分类图标容器 15dp 圆角方形；按钮 15dp；输入条全圆角；C 位 FAB 19dp
- 阴影：低饱和双层柔影（alpha ≤ .14），深色下仅保留环境影
- 分类图标：emoji + 每类固定浅彩底（餐饮 `#FFEFE2/#D96A24`、交通 `#E7F0FF/#3B6FE0`、购物 `#FDE9F1/#D8498B`、居住 `#E4F6F0/#1E9E7E`、娱乐 `#F0EAFF/#7A5AE0`、医疗 `#FDECEC/#D84C4C`、收入 `#FCEBE8/#DF4438`），明暗主题下保持一致
- TabBar：毛玻璃（blur 18dp + 88% 透明卡片色）

**AI 可识别性**：所有 AI 产物（解析预览卡、报告点评、对话回答）带 ✦ 图标 + 靛蓝色系，与人工录入数据明确区分。

### 7.2 导航结构

底部 5 Tab：流水 | 问问 | ＋ | 报告 | 我的

- 「记一笔」为 C 位凸起 FAB（54dp 靛蓝渐变圆角方块，上移 26dp）——录入是全局最高频动作
- Tab 项：21dp 线性图标 + 10sp 标签，选中态主色

### 7.3 各页面布局

**Tab 1 流水**
- 顶部 Hero：英文月份 eyebrow + 月份左右切换；46sp 大数字本月支出；下方 pill 行：收入（红点 + 暖红浅底）、日均支出（描边 pill）
- 「近 30 日支出节奏条」（定稿保留）：30 根圆角小柱，当天高亮主色，一眼感知花费节奏
- 筛选 chips 横滑：全部 / 待确认（琥珀计数角标）/ 各分类
- 按日分组卡片列表（LazyColumn + 日分组头：日期 + 当日合计）
- 待确认交易**行内原位处理**：琥珀渐变行底 + 琥珀角标 + [✓记入] [忽略] [编辑] 胶囊按钮组，不弹窗（与通知栏操作按钮共用同一状态机）
- 已确认行左滑删除/编辑
- 金额右对齐：支出墨色 −、收入暖红 +

**Tab 2 问问**
- 页面头：衬线「问问」+ 副标题 + 近 10 轮上下文提示 pill
- 对话气泡：用户 = 靛蓝渐变右气泡（19dp 圆角 + 小尾巴）；AI = 白卡左气泡，带 ✦ PennyPilot 署名
- AI 回答内嵌迷你图表（Compose Canvas 柱状图，当前周期主色高亮，其余 primarySoft）
- 底部推荐问题 chips（点击即问）+ 常驻 InputDock
- 对话上下文保留最近 10 轮（与 InsightUseCase 一致）

**Tab 3 记一笔**
- 顶部靛蓝渐变提示卡：衬线示例语「昨天午饭 25 块，打车去公司 32」+ 能力说明（一句话拆多笔、支持相对时间）
- AI 解析预览卡：✦ 标识 + 笔数角标；每笔一行（分类图标 / 商户 / 推断时间备注 / 金额 / 删除按钮），分类为可点 chip → 弹出分类网格选择器；底部 [再改改] [保存全部 · N 笔]
- 底部常驻 InputDock：[🎤] [输入框] [↑发送]，全圆角浮于 TabBar 上方；按住说话进入录音态（红描边 + 外发光 + 声波动画），松手转文字填入输入框，可修改后发送
- LLM 解析失败降级：预览卡替换为手动表单（金额/分类/备注）

**Tab 4 报告**
- 当月 Hero 卡：左缘 4dp 靛蓝渐变条 + 衬线「X 月账本」+ 37sp 总支出 + 收入/结余 pill + 支出环比 badge（降绿升红）
- AI 点评引言卡：靛蓝浅底 + 衬线大引号 + ✦ AI 点评标签，衬线正文
- 支出 Top3 分类：图标 + 金额 + 占比渐变进度条
- 历史报告列表：月份汉字方块图标 + 收支摘要 + 右箭头，点击进详情
- 右上角「↻ 重新生成」

**Tab 5 我的**
- 捕获服务健康卡：绿色呼吸点「捕获服务运行中」+ 三指标格（今日捕获 / 最近通知 / 权限状态）；超 24h 无通知且服务绑定中 → 呼吸点与文案变红（对应 4.4 状态自检）
- 分组设置列表（36dp 彩色圆角图标 + 标题 + 副标题 + 右值 + 箭头）：
  - 记账：分类管理 / 通知解析规则 / ColorOS 保活引导
  - AI：LLM 供应商（DeepSeek ↔ Qwen 切换 + API Key）/ 语音识别
  - 数据：备份与恢复 / 关于

**编辑页**（流水左滑或通知 [✎] 进入）
- 金额（数字键盘，大号等宽数字）、分类（网格选择器，emoji 图标芯片）、备注、商户、时间、账户标签
- 底部：保存（主色实心）/ 删除（暖红描边）

### 7.4 通用组件规范

| 组件 | 规格 |
|---|---|
| InputDock | 全圆角，卡片底 + 发丝描边 + 大柔影，浮于 TabBar 上方 12dp；录音态红描边 + 外发光 + 声波动画；问问/记一笔两页复用 |
| PendingTxRow | 琥珀渐变行底（透明过渡）+ 琥珀描边角标 + 行内胶囊按钮组；确认/忽略带位移动画 |
| MiniChart | 卡片内嵌柱状图，底色 primarySoft、强调柱主色渐变，周期 + 单位双标签 |
| CatChip | 42dp 圆角方形浅彩底 + emoji；可选变体尾部加下拉箭头 |
| Toast | 底部居中胶囊（墨色底反白字 + ✓ 图标），2s 自动消失 |
| 主题 | 跟随系统自动切换明暗；两套 ColorScheme tokens 见 7.1 |

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

# 特定支付场景采集调研与 PennyPilot 改造建议

调研日期：2026-10-24（北京时间）。本次只做调研与代码审视，不改变采集行为，不发布版本。商业产品依据官方说明，开源项目依据固定提交源码；没有运行对比应用，也没有获得它们的真机成功率。

## 建议

采用“结构化页面识别为主、通知补充、主动截图 OCR 与账单导入补漏”的组合。把规则按应用、业务场景和已验证版本管理，所有入口最终输出可追溯的交易证据，经过完成状态校验、归并与去重后进入待确认账单。

优先完成真实页面样本与场景适配器。现有两轮修复改善了读取和诊断，但合成节点测试的数量不能证明某一微信版本已经适配。没有读到的证据，也不能靠换一个更大的模型补出来。

## 一手实现证据

| 参考对象 | 已核实的做法 | 对本项目的启示 |
| --- | --- | --- |
| 钱迹 | 官方说明使用无障碍页面节点、本地识别；2026-10-13 更新仍修复微信新版交易详情字段，另有红包和部分退款适配记录。文档承认页面版本、双开、后台服务会影响触发；识别成功与弹窗显示是两个环节。[自动记账说明](https://docs.qianjiapp.com/auto/qianji_auto_android.html)、[更新记录](https://docs.qianjiapp.com/change-log/change_log_android.html) | 支持一个应用不等于覆盖该应用的所有支付场景；持续维护场景规则、区分“没捕获”和“没弹窗”。 |
| 一木记账 | 官方政策说明读取指定应用屏幕文字筛选账单，另提供截图和文件导入；FAQ 说明后台被清理会影响无障碍。[政策](https://www.yimuapp.com/doc/privacyPolicy.html)、[FAQ](https://www.yimuapp.com/doc/faq.html) | 即时采集与事后补漏并行；服务健康和规则命中分别检查。 |
| AutoAccountingOrg/AutoAccounting | 核对提交 `ed5c2ad`（2026-10-23）。无障碍内容事件防抖，依据包名、Activity、结构指纹匹配已记忆页面后触发 OCR。微信转账还有独立 Xposed Hook 路径。[无障碍服务](https://github.com/AutoAccountingOrg/AutoAccounting/blob/ed5c2ad94b152f7351d76fcd33f9099a5f7532eb/app/src/main/java/com/google/android/accessibility/selecttospeak/SelectToSpeakService.kt)、[页面签名](https://github.com/AutoAccountingOrg/AutoAccounting/blob/ed5c2ad94b152f7351d76fcd33f9099a5f7532eb/app/src/main/java/net/ankio/auto/service/ocr/PageSignatureManager.kt)、[转账 Hook](https://github.com/AutoAccountingOrg/AutoAccounting/blob/ed5c2ad94b152f7351d76fcd33f9099a5f7532eb/app/src/main/java/net/ankio/auto/xposed/hooks/wechat/hooks/TransferHooker.kt) | 借鉴页面识别与交易解析分层、页面样本记忆；不能把 Xposed 下的能力当作普通无障碍能力。 |
| DykiSensei/seamless-bookkeeping | 核对提交 `af717f9`（2026-06-21）。按应用运行微信转账、微信红包、支付宝转账提取器。但转账规则包含待确认收款语义，数据库去重只按来源与金额的时间窗口查询。[采集服务](https://github.com/DykiSensei/seamless-bookkeeping/blob/af717f9faa5658b2e67dcc61e200867951806734/app/src/main/java/com/bookkeeping/app/notification/accessibility/AccessibilityCaptureService.kt)、[微信提取器](https://github.com/DykiSensei/seamless-bookkeeping/blob/af717f9faa5658b2e67dcc61e200867951806734/app/src/main/java/com/bookkeeping/app/notification/accessibility/WechatTransferExtractor.kt)、[去重](https://github.com/DykiSensei/seamless-bookkeeping/blob/af717f9faa5658b2e67dcc61e200867951806734/app/src/main/java/com/bookkeeping/app/data/repository/TransactionRepository.kt) | 可参考按场景拆提取器；不能照搬宽松的成功判定和同额去重，它们可能误记或吞掉真实第二笔交易。 |
| GKD | 面向界面自动化，提供节点关系选择器、Activity/应用版本过滤、规则订阅及节点快照审查。[选择器](https://gkd.li/guide/selector)、[应用匹配属性](https://gkd.li/api/interfaces/rawappruleprops)、[订阅](https://gkd.li/guide/subscription)、[快照](https://gkd.li/guide/snapshot) | 借鉴结构化匹配、样本回放、规则版本管理；PennyPilot 只需读取与提取，不需要执行点击操作。 |

AutoAccounting 的页面内容去重会归一化数字；据代码推断，不能把这种页面指纹直接用于交易去重。其多渠道交易去重也有较宽的合并条件，值得借鉴证据归并方式，不宜直接复制算法。[去重实现](https://github.com/AutoAccountingOrg/AutoAccounting/blob/ed5c2ad94b152f7351d76fcd33f9099a5f7532eb/server/src/main/java/org/ezbook/server/tools/DuplicateDetector.kt)

上述两个记账开源项目分别声明 GPLv3 与 MIT；GKD 声明 GPL-3.0。PennyPilot 当前为 Apache-2.0。建议先借鉴设计独立实现，具体源码复用另行核对许可与文件声明，不把示例片段直接拷入。

## 各支付场景的建议处理

以下是拟议方案，不是已完成适配清单。每项都要以真实版本样本验证。

| 场景 | 首选信号 | 必须区分的语义与兜底 |
| --- | --- | --- |
| 微信好友转账，付款方无通知 | 本次转账完成页的只读节点 | 本人付款已成功与对方尚未领取可同时成立；提取支出金额，不能因为“对方已收款”反转为本人收入。 |
| 微信好友转账，收款方 | 确认收款后的完成页；可信通知辅助 | “待确认收款”只说明有人发起转账，不能直接当作已到账收入。 |
| 扫码支付、小程序付款、支付宝商户支付 | 支付完成页与可信支付通知 | 实付与原价、优惠、余额分别标识；订单号不能误当金额。 |
| 出示付款码由商户扫码 | 可信到账/扣款通知；之后的明确账单凭证 | 用户手机可能没有完整成功页，不强求无障碍事件；无信号时依赖补录/导入。 |
| 美团、抖音跳转微信或支付宝支付 | 平台结果和钱包结果分别形成证据 | 业务订单与支付交易分开建模；只有明确映射或可靠交易身份才自动关联，不能仅凭同额短时跨应用合并。 |
| 退款、部分退款、分次退款 | 退款到账通知或退款完成详情 | 退款申请、处理中不等于到账；退款 ID 与原订单 ID 分开，不能吞掉同订单的第二次退款。 |
| 微信红包发出、领取、退回 | 各自完成凭证，必要时用户主动进入详情 | 红包不是普通好友转账规则的同义词；发出、收到、未领取退回分别处理。 |
| 本人账户互转、充值、提现、信用卡还款 | 可识别双方账户的凭证 | 区分资产转移与消费/收入，手续费另列；无法确认账户关系时交用户判断。 |
| 自动续费、免密扣费、锁屏后台扣款 | 可信通知和后续账单导入 | 没有前台页面就不能依赖页面采集；进程退出期间的遗漏靠对账补全。 |
| 翻看历史交易详情 | 用户主动补录或明确导入模式 | 默认不当作刚发生的新交易；保留凭证实际时间，与观察时间分离。 |
| 自绘页面或节点不给金额 | 用户分享截图，本地 OCR 后确认 | OCR 只解决文字不可读，不证明交易成功或发生时间。无法截取的页面改用账单文件或人工补录。 |

## 推荐架构与实现顺序

### 1. 结构化快照与真实样本，优先级最高

保留有界节点事实：节点父子关系、文字来自 text 还是 description、class、可用的 resource ID、bounds、可见性；增加应用版本、窗口和事件类型。运行时快照只在内存参与解析，仍不读取输入和密码内容。

提供用户主动触发的“诊断这次支付”：在目标结果页生成本地可预览、可脱敏的节点样本，可选附截图，用户决定是否导出。日常诊断继续只记录状态和计数。参考 GKD 的快照回放思路，避免每次都让用户重新付款才能开发适配。

resource ID、Activity 名和屏幕坐标都只能作为辅助证据，不能单独证明页面类型；同一 Activity 可能承载多种页面，ID 与布局也可能变更。

### 2. 按应用、场景、版本拆适配器

从微信转账付款完成页开始，分别实现微信收款确认、商户支付、红包、退款，随后扩展支付宝与平台支付。适配器声明规则 ID、版本、验证过的 App 版本、所需锚点、排除锚点、字段提取方式和样本集。

匹配流程：确认应用 → 判断场景和只读凭证区域 → 找完成状态 → 在该区域取金额、方向、对手与交易标识 → 校验冲突。未知页面保留通用解析作为保守回退。

不能简单删除当前“同包可见输入框整页拒绝”条件。若要让特定场景只读凭证子树，必须用真实样本证明它与聊天、输入区域独立；不要从任意页面截出一小段“成功＋金额”就入账。

第一阶段规则随 App 发布；适配器和回放稳定后，再考虑带签名、回滚和兼容约束的数据规则更新。暂不需要建设复杂的远程规则平台或执行任意脚本。

### 3. 结构化证据和有界事件会话

解析输出采用 `Accepted(evidence)`、`Incomplete(missingFields)`、`Rejected(reason)`，避免只有结果或 null。证据应包含业务场景、本人资金方向、金额与币种、完成状态、实际交易时间、观察时间、字段来源、规则版本和多个有类型的交易标识。

在现有会话上增加短期状态与少量 app/window 会话，用单调时钟计算超时。中间页面只提供时序上下文，不能凭“点击过转账”或前一页金额推断支付成功。

关键改动是区分新候选、证据补全和已处理：先出现金额，随后加载商户和订单号，应给已有交易追加证据，而不是再次建账或彻底忽略。补充证据不得覆盖用户已经修改或确认的账目。

### 4. 强证据去重，弱证据留待确认

页面结构指纹用于选择规则；交易身份用于判断是否同一笔钱，二者分开。

交易 ID 使用 `命名空间 + 类型 + 值`，区分商户订单、支付交易和退款。通知、页面、截图与导入证据可以汇入同一交易，但同额、同商户、接近时间只能作为疑似重复提示。保留现有数据库原子写入与强弱去重边界。

### 5. 明确的补漏入口

先做分享截图/选择图片 → 本地中文 OCR → 相同的交易校验 → 用户确认。ML Kit 提供可随包内置的中文模型；模型内置可避免首次使用时等待动态下载，但会增大 APK，这是工程取舍，未进行本项目性能测试。[官方 OCR 文档](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)

再做账单文件导入与对账，处理无通知、未打开成功页、服务曾退出等无法实时观察的情况。备份恢复不是外部支付账单导入，需要单独的字段映射、去重和预览流程。

## 不适合作为默认主路线的方案

- 只增强通知监听：它只能接收或查询已经发布的通知，不能产生微信没有发送的通知。[Android 通知监听](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
- 全天连续截图交给大模型：增加截图权限、耗电、隐私和误识别成本，且不能绕过受保护窗口。优先按已识别场景或用户动作触发，本地完成状态规则先行。[截图接口](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,%20java.util.concurrent.Executor,%20android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))、[受保护窗口](https://developer.android.com/security/fraud-prevention/activities)
- 把 Shizuku 当作通用读库/保活开关：ADB 模式仍是 shell 权限，官方说明不能读取其他应用私有数据目录；非 root 重启后还需重新启动服务。[Shizuku API](https://github.com/RikkaApps/Shizuku-API)、[运行说明](https://shizuku.rikka.app/guide/setup/)
- 把商户支付 API 当个人统一流水接口：已查到的微信账单接口属于商户对账范围，不能据此获取用户在任意商户的消费和好友转账。[微信商户账单接口](https://pay.wechatpay.cn/doc/v3/merchant/4012791887)
- 只接 Tasker/URL Scheme：它解决外部账单写入，仍需要其他组件先提取交易证据。[钱迹自动化接口](https://docs.qianjiapp.com/plugin/auto_tasker.html)

Xposed 可以在特定环境读取应用内部回调，但具有不同的运行前提和版本适配成本；保留为未来可选研究，不作为普通手机用户的默认安装要求。

## 当前代码的具体落点

| 文件/模块 | 当前事实 | 建议改动 |
| --- | --- | --- |
| `PaymentScreenReader.kt` | 输出扁平文本和节点计数，节点关系丢失 | 增加纯数据 `NodeFact/ScreenSnapshot`，现有隐私和完整性限制保留 |
| `PaymentAccessibilityService.kt` | 请求仅带包名、windowId 和账本 revision | 增加 `CaptureContext`，在 `parseScreen` 位置插入场景路由 |
| `PaymentTextParser.kt` | 多应用共用完成词、金额角色和排除规则 | 保留金额/状态基础校验；新增独立场景适配器及明确拒绝原因 |
| `PaymentScreenSession.kt` | 晚到字段会补入内存 receipt，但 handledKey 阻止再次处理 | 返回 `NewCandidate/EvidenceEnriched/AlreadyHandled` |
| `CapturePersistence.kt` | 同 eventKey 直接返回重复，晚到证据不能追加 | 对同一账目追加证据，保留人工编辑；扩展有类型交易 ID |
| `CaptureEvidenceEntity.kt` 与备份 | 无规则版本、字段来源和完整交易语义 | 需要持久化时再做 Room 迁移，并同步备份编解码与迁移测试 |
| 当前 Robolectric 回归 | 可验证服务到 Room 链路，但多数节点为合成数据 | 引入按 App 版本和场景组织的真实脱敏节点、事件序列回放 |

建议分三个增量交付：先交付样本采集、结构化读取与一个已实机验证的微信转账场景；再交付证据补全及微信其他场景；最后扩大应用覆盖、OCR 与账单导入。优先级由用户实际高频场景决定，不按支持的包名数量衡量完成度。

## 验收标准

每个声明支持的场景都应附 App/系统版本、机型、输入样本、期望结果、实测日期。测试同时包含：成功、未完成、失败、历史重开、连续同额、同笔多渠道、金额先到单号后到、部分退款、进程恢复、服务断开、节点缺失、大字/横屏/深色与双开环境。

分别统计候选触发、金额/方向/状态正确性、重复生成、真实第二笔被吞、处理耗时和后台持续性。黄金样本集与真机验收结果分别报告；未知版本标为待验证。自动化通过不能替代真实页面适配，其他应用的宣传覆盖率也不能作为本项目的成功率。

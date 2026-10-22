**PennyPilot 项目审查报告 · 2026-10-22**

审查对象：当前工作树，HEAD 为 0fa18bf，包含审查开始前已有的未提交修改及新增文件。审查覆盖 Android 配置、数据库与迁移、备份恢复、通知捕获与规则、LLM 客户端与工具调用、语音输入、页面状态与导航、月报任务和现有测试。没有修改业务代码，没有安装 APK、操作真实账本或发送真实 AI 请求。

**结论：当前存在数据丢失、账目方向与金额错误、普通聊天内容后台外发等问题。建议先修复 P1，再将其作为长期记账工具使用。** 本报告整理 22 项问题：6 项 P1、16 项 P2。P1 表示应优先修复的数据正确性、数据保护或隐私问题；P2 表示明确的功能、兼容性或质量检查问题。没有将依赖版本较旧直接视为安全漏洞。

**实际验证结果**

| 检查 | 结果 | 说明 |
|---|---|---|
| Debug 单元测试 | 40/40 通过 | 7 个测试类，0 failure、0 error、0 skipped；本轮强制重新执行 |
| Debug 构建 | 通过 | 本轮重新生成 APK |
| Release 构建 | 通过 | 执行 R8；产物为 app-release-unsigned.apk，未签名、未安装 |
| lintDebug | 失败 | 1 error、53 warnings；error 为 MainActivity 内组合阶段创建 Flow |
| git diff --check | 通过 | 没有发现补丁空白格式错误 |
| 通知解析 | 隔离复现 | 编译当前解析器，并使用从当前数据库源码提取的完整预置规则 |
| 金额解析 | 隔离复现 | 调用当前编译产物 MoneyUtil，验证负数、NaN、Infinity |
| WAL 恢复、时间边界、状态更新 | 隔离复现 | 临时 SQLite；未接触用户账本 |
| 月报调度 | 日期模拟 | 验证 30 天周期与自然月错位 |

执行命令：

    ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --rerun-tasks --console=plain
    ./gradlew --offline :app:assembleRelease --console=plain
    git diff --check

首轮离线组合检查被缺失的 AndroidX 缓存阻断；随后允许解析构建依赖并重新执行全部任务。最终组合命令因 Lint 错误退出，但单元测试与 assembleDebug 均已成功，不能把整个组合命令描述为通过。

结果文件：[单元测试报告](/Users/will/Desktop/codes/PennyPilot/app/build/reports/tests/testDebugUnitTest/index.html)、[Lint 报告](/Users/will/Desktop/codes/PennyPilot/app/build/reports/lint-results-debug.html)。构建目录可能被后续构建覆盖。通知隔离复现脚本位于 [临时脚本](/private/tmp/pennypilot_capture_review.py)，可用 python 执行；临时目录可能被系统清理。

**01 · [P1] 备份恢复忽略 WAL，可能静默丢失已经提交的账单**

位置：[BackupUseCase.kt:50](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/domain/usecase/BackupUseCase.kt:50)，关键范围 50–54；导出逻辑位于 34–37。

导出分别复制数据库主文件、WAL 和 SHM，恢复却只复制主文件，然后删除活动数据库 WAL/SHM。只要最新已提交账单还在 WAL 中，恢复就会漏掉它们。隔离 SQLite 实验得到 live_count=1、exported_wal_bytes=4152、restored_db_only_count=0。恢复又没有关闭现存 Room 连接或停止通知写入，直接覆盖开放中的数据库，另有一致性风险。顺次复制三个正在变化的文件也不保证是同一个快照。

修复：导出一致性单文件快照；恢复时阻止新写入、关闭所有数据库连接，校验备份后原子替换并重新初始化。测试必须覆盖“已提交但尚未 checkpoint”的账单，不能只测文件存在。依据：[SQLite WAL](https://sqlite.org/wal.html)、[SQLite 数据损坏原因](https://sqlite.org/howtocorrupt.html)。

**02 · [P1] 从旧版数据库升级会直接清空账本**

位置：[AppDatabase.kt:31](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/data/db/AppDatabase.kt:31)，关键范围 31–34。

当前 schema 为 version=3，使用 fallbackToDestructiveMigration，没有迁移实现。Git 历史中确实存在 version=1（7f5fde9）和 version=2（96e3a74）。旧版升级到当前版，或恢复旧版备份再打开数据库时，会走销毁重建路径；账单、自定义分类和规则可能全部丢失。这不是仅针对未来升级的推测。

修复：提供保留数据的 1→2→3 迁移，开启 schema 导出，增加迁移测试；财务数据不应默认使用破坏性回退。依据：[Room 数据库迁移](https://developer.android.com/training/data-storage/room/migrating-db-versions)。本次核对源码和版本历史，未在用户数据上实际升级。

**03 · [P1] 普通微信聊天通知会被自动发送到外部 AI 服务**

位置：[NotificationCaptorService.kt:103](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/service/NotificationCaptorService.kt:103)，关键范围 103–105；完整文本发送位于 158–160。

启用通知读取并配置 AI 后，任何目标包通知只要没有命中正则，就进入云端解析。普通消息“张三 明天的会议改到下午”在实际规则集上返回未匹配，之后会将通知标题和正文作为 user 消息发送。提示模型“非交易返回 false”发生在内容已经外发之后。当前没有独立的通知云解析开关，也没有本地交易候选过滤；群聊还可能持续消耗 API 配额。

修复：在本地先筛选明确的交易通知候选，再进入云端兜底；单独控制通知云解析、说明发送范围，并限制并发与调用频率。本次验证了规则输出及 HTTP 调用路径，没有向真实供应商发送聊天内容。

**04 · [P1] 支出规则匹配应用名，收入会被识别成支出**

位置：[AppDatabase.kt:75](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/data/db/AppDatabase.kt:75)，关键范围 75–77；微信同类问题位于 56–64。

使用当前完整规则集实测：“支付宝到账1000.00元”得到 EXPENSE/100000 分；“微信支付 微信收款到账8000.00元”得到 EXPENSE/800000 分。支出规则先匹配“支付宝／微信支付”中的“支付”，再跨过到账、收款文字提取数字，后面的收入规则没有机会执行。规则已成功，因此 LLM 也不会纠正。一键确认后收入会进入支出统计。

修复：按正文里的明确交易动作判定方向，避免应用标题触发支出规则。增加完整规则集竞争测试。现有支付宝收入测试只传收入规则，无法覆盖此问题。

**05 · [P1] 金额解析能把商户数字或千分位前缀当成真实金额**

位置：[AppDatabase.kt:56](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/data/db/AppDatabase.kt:56)，关键范围 56–58；关联 [ParseRuleEngine.kt:20](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/data/parser/ParseRuleEngine.kt:20)。

使用实际解析器复现：

| 通知文本 | 当前结果 | 正确金额 |
|---|---:|---:|
| 微信支付 你向7-Eleven付款18.00元 | 700 分 | 1800 分 |
| 微信支付 你向沙县小吃付款1,800.00元 | 100 分 | 180000 分 |

第一例从标题“支付”向后抓取首个数字，抓到了商户名中的 7；第二例允许金额捕获在逗号前提前结束。两种情况都会被当作成功解析，产生看似合法的错误账目。

修复：金额必须绑定明确交易动作或货币字段，支持千分位规范化，并检查完整金额边界；无法确认时应返回未匹配。

**06 · [P1] 金额输入缺乏统一校验，负数会反转统计，特殊值还能崩溃**

位置：[EditTransactionViewModel.kt:96](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/ui/edit/EditTransactionViewModel.kt:96)，关键范围 96–102；关联 [MoneyUtil.kt:34](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/util/MoneyUtil.kt:34)。

把支出金额改为 -10，现有校验只排除零值，会存入 type=EXPENSE、amount=-1000。单笔显示取绝对值，看起来仍是支出 10 元，但月支出被减去 10 元。当前编译产物探针确认：-10 返回 -1000；NaN 抛 IllegalArgumentException，超出现有 NumberFormatException 捕获范围；Infinity 返回 Long.MAX_VALUE。文本框键盘类型不能替代粘贴内容和领域数据校验。

AI 路径也直接接受模型金额，再按类型取正负，见 [ParseTextUseCase.kt:116](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/domain/usecase/ParseTextUseCase.kt:116) 与 [AiInputViewModel.kt:131](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/ui/input/AiInputViewModel.kt:131)。

修复：在统一领域入口要求有限、严格正数且不超范围的金额；使用精确十进制解析，最终存储符号仅由交易类型决定。错误应反馈到页面，不能靠格式化掩盖。

**07 · [P2] 公共 Documents 备份路径在现代 Android 上不可直接可靠访问**

位置：[BackupUseCase.kt:12](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/domain/usecase/BackupUseCase.kt:12)，关键范围 12–15、28–34。

targetSdk=35，代码直接用 File 写公共 Documents/PennyPilot，未通过系统文档选择器取得访问权限。普通 Android 11+ 环境下，这不是有效的任意公共文档写入方案，备份会失败；直接列目录也无法可靠找回用户保存的备份。

修复：通过 SAF 的创建／打开文档流程和 ContentResolver 读写；不要依靠额外的大范围存储权限绕过。本项核对平台文档与源码，未在具体 OPPO ROM 上复现。依据：[文档与文件存储](https://developer.android.com/training/data-storage/shared/documents-files)。

**08 · [P2] 自动备份未排除加密偏好，换机恢复可能使 AI 页面崩溃**

位置：[AndroidManifest.xml:9](/Users/will/Desktop/codes/PennyPilot/app/src/main/AndroidManifest.xml:9)，关键范围 9–15；关联 [SecureKeyStore.kt:14](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/data/settings/SecureKeyStore.kt:14)。

allowBackup=true，没有 fullBackupContent 或 dataExtractionRules 排除 pennypilot_secrets。加密偏好文件可被恢复，而原 Keystore 密钥不能作为普通文件一起恢复；EncryptedSharedPreferences.create 又没有不可解密的恢复处理，相关 AI 页面注入依赖时可能抛异常。触发条件是系统备份恢复后密钥不可用，不是每次启动必然崩溃。

修复：在新旧 Android 备份规则中排除加密偏好，密钥失效时允许用户重新配置。依据：[EncryptedSharedPreferences 官方备份警告](https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences)。

**09 · [P2] 时间区间语义不一致，既会跨月重复统计，也会漏掉结束日**

位置：[TransactionDao.kt:26](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/data/db/dao/TransactionDao.kt:26)、[TransactionsViewModel.kt:64](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/ui/transactions/TransactionsViewModel.kt:64)、[InsightUseCase.kt:234](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/domain/usecase/InsightUseCase.kt:234)。

月度查询把结束时间设为下月 1 日零点，但 SQL BETWEEN 包含结束端点；2026-11-01 00:00 的一笔 1000 分支出会同时计入 10 月和 11 月，已用当前 SQL 在 SQLite 复现。MonthlyReportWorker 使用相同约定。

洞察工具则把“结束日期”也转换为当天零点。start=end=2026-10-22 时，只能查零点瞬间，12:00 的交易被排除；隔离查询中实际 2500 分返回 0。

修复：统一使用起点包含、终点不包含的时间区间。月度终点取下月零点；面向用户的包含式结束日期转换为次日零点。DAO、LLM 工具说明、报告和测试必须使用同一约定。

**10 · [P2] 洞察日聚合忽略关键词，文字与图表还会使用不同数据集**

位置：[InsightUseCase.kt:181](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/domain/usecase/InsightUseCase.kt:181)，关键范围 181–187、221–225。

“本周咖啡按天花了多少”传入 group_by=day、keyword=咖啡时，代码读出了 keyword，却没有传给日聚合 DAO，返回所有支出。按分类聚合的文字工具结果保留了 keyword，但图表又用 null 重新查询，呈现全部商户，导致回答与图形不一致。

修复：让所有聚合分支支持相同筛选条件，并直接从同一次工具查询结果生成图表。回归测试应同时放入咖啡和非咖啡交易。

**11 · [P2] 内存文本哈希去重会吞掉独立交易，重建服务后又能重复入账**

位置：[NotificationCaptorService.kt:91](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/service/NotificationCaptorService.kt:91)，关键范围 91–96；内存状态位于 46–47。

五分钟内两笔同商户同金额交易，只要标题正文相同，第二条就直接被丢弃；来源包、通知标识和发生时间未参与判定。服务实例重建后 map 清空，若同一通知再次被上游发布或更新，又会重复捕获，数据库已有及已忽略记录都不参与查重。此处不假设 Android 重连必定重放通知。

生产去重块的隔离结果：首条 true，一分钟后独立同文本通知 false，新实例内同文本通知 true。修复需要区分通知更新与独立交易，结合来源、事件标识并持久化处理记录；仅增加哈希长度不能解决事件身份问题。

**12 · [P2] 旧通知按钮可以覆盖已经确认或忽略的交易状态**

位置：[NotificationActionReceiver.kt:32](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/service/NotificationActionReceiver.kt:32)，关键范围 32–34；关联 [TransactionDao.kt:17](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/data/db/dao/TransactionDao.kt:17)。

用户先在流水确认交易，页面只改数据库而没有撤销系统提醒。随后点旧提醒的“忽略”，无条件 UPDATE 会把已确认账目改为 IGNORED，账目从流水与统计消失。相反操作还能复活已忽略记录。当前 SQL 已验证 PENDING→CONFIRMED→IGNORED→CONFIRMED 全部可执行，违反项目单向状态机设计。

修复：使用带 status=PENDING 条件的原子 UPDATE 并检查影响行数；所有处理入口同步取消对应通知。

**13 · [P2] 固定 30 天周期无法保证逐月生成报告，存在漏月路径**

位置：[PennyPilotApp.kt:25](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/PennyPilotApp.kt:25)，关键范围 25–29；关联 [MonthlyReportWorker.kt:32](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/worker/MonthlyReportWorker.kt:32)。

任务先对齐月初，此后每 30 天运行，Worker 每次仅取当时的上月。日期模拟：2026-11-01 生成 10 月 → 12-01 生成 11 月 → 12-31 重复 11 月并跳过 → 2027-01-30 生成 12 月 → 03-01 生成 2 月。2027 年 1 月从未被处理。系统实际延迟会改变日期，但代码没有补齐遗漏月份的机制。

修复：按日检查所有已结束而未生成的月份，或每次按日历重新安排下次任务，并显式传递报告月份及补齐缺月。

**14 · [P2] 并发重生成月报可能留下同月多条记录**

位置：[MonthlyReportWorker.kt:71](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/worker/MonthlyReportWorker.kt:71)，关键范围 71–79；触发入口 [ReportScreen.kt:90](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/ui/report/ReportScreen.kt:90)。

每点一次按钮都会 enqueue 独立 Worker。删除同月记录与插入新报告不在事务中，year_month 也没有唯一约束。两个任务按 deleteA→deleteB→insertA→insertB 交错，就会留下同月两条报告；自增 id 上的 REPLACE 不能按月份去重。

修复：月份唯一约束与原子 upsert，同时以月份为键使用唯一工作。此项依据可并发调用链和数据库约束确认，未做设备并发压测。

**15 · [P2] 分类编辑改一个字符就立即保存并关闭**

位置：[CategoriesScreen.kt:115](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/ui/categories/CategoriesScreen.kt:115)，关键范围 115–121；文本框绑定 193–198。

名称、图标的 onValueChange 直接保存数据库，并把 editCat 清空。输入或删除第一个字符就关闭对话框，不能连续编辑，“取消”也不能撤销已经写入的内容，一次全选删除还能保存空名称。声明的局部草稿变量没有实际承接输入。

修复：文本变化只修改草稿，“完成”时统一校验保存，“取消”丢弃草稿。

**16 · [P2] 分类向下移动条件恒为 false**

位置：[CategoriesScreen.kt:76](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/ui/categories/CategoriesScreen.kt:76)。

当前比较同一分类 id 的 indexOfLast 和 indexOfFirst。主键唯一时两者总相等，非末尾分类也不能向下移动。

修复：比较当前索引与 lastIndex，或使用 ViewModel 现有边界校验。

**17 · [P2] AI 返回结果会清空后续输入，重叠请求还能相互覆盖**

位置：[AiInputViewModel.kt:72](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/ui/input/AiInputViewModel.kt:72)，关键范围 72–79、97。

发送“午饭25”后，在等待期间输入“打车30”；第一笔解析完成时无条件清空 inputText，新草稿丢失。方法未阻止 isParsing 期间再次提交，发送按钮也未以该状态禁用；后发请求先完成时，较旧请求的结果还可能反过来覆盖新预览。

修复：提交时从当前草稿中取走本次输入；显式阻止重复请求，或通过请求编号／取消机制只接收有效结果。用可控制返回顺序的假 LLM 测试，而非依赖真实网络时序。

**18 · [P2] 手动保存没有防重入，连续点击可重复入账**

位置：[AiInputViewModel.kt:156](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/ui/input/AiInputViewModel.kt:156)，关键范围 156–159。

手动键盘直到数据库写入完成的回调才关闭，按钮没有保存中状态。若第二次点击发生在首次插入完成前，会再启动一个插入协程。新增账目的 EditTransactionViewModel 分支也存在同类入口；AI 的 saveAll 已先清空预览防双击，手动路径没有相应保护。

修复：在启动协程前同步设置 isSaving，禁用保存按钮，成功后退出，失败时恢复并保留草稿。此项静态确认可重入，未在设备上测量双击窗口。

**19 · [P2] 语音识别缺少 Android 11+ 要求的服务可见性声明**

位置：[AndroidManifest.xml:5](/Users/will/Desktop/codes/PennyPilot/app/src/main/AndroidManifest.xml:5)，关联 [SpeechRecognizerManager.kt:35](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/data/asr/SpeechRecognizerManager.kt:35)。

targetSdk=35，源码和合并 Manifest 都没有 queries/android.speech.RecognitionService。应用直接调用 createSpeechRecognizer，在识别服务被包可见性过滤的设备上会无法正常发现或连接服务。错误回调又只改变 manager 内部状态，界面无法获得明确失败原因。

修复：声明对应 queries intent、检查服务可用性并把错误状态反馈给 ViewModel。平台依据为 [SpeechRecognizer 官方要求](https://developer.android.com/reference/android/speech/SpeechRecognizer#createSpeechRecognizer(android.content.Context))；未验证具体 OPPO ROM 的实际表现。

**20 · [P2] 报告趋势和热力图只查一次，交易变化后显示旧数据**

位置：[ReportViewModel.kt:42](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/ui/report/ReportViewModel.kt:42)，关键范围 42–48、51–58。

两个状态流都是 flow 中执行一次 suspend DAO 查询再 emit，没有订阅交易变化。报告页停留期间新增或确认交易不会刷新；切去记账再在五秒内返回时，WhileSubscribed 的上游还未停止，也不会重新执行查询。刷新报告实体不等于刷新这两个独立图表状态。

修复：聚合 DAO 返回 Room Flow，或显式订阅交易变更后重新查询。测试在报告页已订阅时插入／确认／删除交易，断言图表数据变化。

**21 · [P2] 报告把负结余显示为正结余**

位置：[ReportScreen.kt:310](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/ui/report/ReportScreen.kt:310)。

收入 100 元、支出 300 元时，balance=-20000 分，却对 balance 取绝对值后显示“结余 ¥200.00”，既没有负号也没有赤字说明，改变了财务含义。

修复：保留负号，或对负结余明确显示“超支／赤字”。

**22 · [P2] 组合函数内创建 Flow，实际导致 Lint 质量检查失败**

位置：[MainActivity.kt:60](/Users/will/Desktop/codes/PennyPilot/app/src/main/java/com/pennypilot/app/MainActivity.kt:60)，关键范围 60–62。

setContent 内直接对 onboardingDone 调用 map，每次重组创建新的 Flow，可能引起收集器重新启动。此次实际 lintDebug 明确以 FlowOperatorInvokedInComposition 报错，任务退出失败。Debug／Release 能打包不能替代此质量检查。

修复：将转换移到稳定的状态持有者，或用 remember 保持派生 Flow 身份；不建议通过新增 baseline 或 suppress 隐藏该错误。

**测试覆盖与修复顺序**

现有 40 个测试集中在金额常规格式、规则单独匹配、分类推断、少量文本解析、DAO 基本查询及新增数字键盘。它们全部通过，但没有覆盖完整通知规则之间的竞争、数据库升级、WAL 恢复、敏感通知是否外发、异步输入覆盖、月报月份推进或页面编辑保存交互。缺少这些测试，解释了为何构建和单测通过仍存在以上问题。

建议按以下顺序修复并增加能复现缺陷的回归测试：

1. 数据保护：迁移保留数据；WAL 备份完整性；关闭连接后的原子恢复；SAF 和加密偏好备份排除。
2. 正确入账：普通聊天不调用外部 LLM；完整预置规则的收入、数字商户与千分位样本；金额统一校验；旧通知状态不可覆盖；独立同额交易不被去重吞掉。
3. 统计一致性：统一日期区间；相同过滤产生相同回答与图表；跨 28／29／30／31 天月份不漏报告；同月报告唯一；负结余保留语义。
4. 交互与工程检查：请求乱序和草稿保留；保存防重；分类编辑与排序；图表订阅刷新；修正 Lint 错误，再进行目标 Android／ColorOS 设备验证。

**验证边界**

本报告包含源码可直接证明的问题、隔离实测结果和官方平台契约核对，未把这些统称为真机复现。未验证设备上的通知投递、ColorOS 后台保活、系统语音服务、跨设备 Auto Backup 或实际供应商 API 连通性。Release 构建成功仅说明生成了未签名产物，不代表运行、安装或发布验证通过。审查未改业务源码、未提交或推送 Git；新增内容仅为本报告，另有工具正常生成的构建输出和临时验证文件。

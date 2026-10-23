package com.pennypilot.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.speech.SpeechRecognizer
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.pennypilot.app.data.asr.SpeechRecognizerManager
import com.pennypilot.app.data.db.AppDatabase
import com.pennypilot.app.data.db.LedgerRevision
import com.pennypilot.app.data.db.PortableBackupCodec
import com.pennypilot.app.data.db.dao.BackupSnapshot
import com.pennypilot.app.data.db.entity.CategoryEntity
import com.pennypilot.app.data.db.entity.MonthlyReportEntity
import com.pennypilot.app.data.db.entity.ParseRuleEntity
import com.pennypilot.app.data.db.entity.TransactionEntity
import com.pennypilot.app.data.llm.Message
import com.pennypilot.app.data.llm.UserFacingLlmException
import com.pennypilot.app.data.openai.ChatGptModelRepository
import com.pennypilot.app.data.openai.auth.ChatGptAuthManager
import com.pennypilot.app.data.settings.AppPreferences
import com.pennypilot.app.data.settings.SecureKeyStore
import com.pennypilot.app.domain.model.TxSource
import com.pennypilot.app.domain.model.TxStatus
import com.pennypilot.app.domain.model.TxType
import com.pennypilot.app.domain.usecase.BackupUseCase
import com.pennypilot.app.domain.usecase.InsightUseCase
import com.pennypilot.app.domain.usecase.ParseTextUseCase
import com.pennypilot.app.service.CaptureProcessor
import com.pennypilot.app.service.CaptureServiceMonitor
import com.pennypilot.app.service.NotificationCaptorService
import com.pennypilot.app.service.NotificationHelper
import com.pennypilot.app.service.PaymentAccessibilityService
import com.pennypilot.app.util.MoneyUtil
import com.pennypilot.app.worker.MonthlyReportWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.YearMonth
import javax.inject.Inject

/** The single data boundary: Kotlin retains Room, credentials and background services. */
class NativeLedgerBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val preferences: AppPreferences,
    private val keyStore: SecureKeyStore,
    private val auth: ChatGptAuthManager,
    private val modelRepository: ChatGptModelRepository,
    private val parseText: ParseTextUseCase,
    private val insight: InsightUseCase,
    private val backup: BackupUseCase,
    private val monitor: CaptureServiceMonitor,
    private val captureProcessor: CaptureProcessor
) : MethodChannel.MethodCallHandler, EventChannel.StreamHandler {
    private lateinit var activity: MainActivity
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var methods: MethodChannel? = null
    private var events: EventChannel? = null
    private var sink: EventChannel.EventSink? = null
    private var observer: InvalidationTracker.Observer? = null
    private val uiPreferences = context.getSharedPreferences("pennypilot_flutter", Context.MODE_PRIVATE)
    private val settingsMutex = Mutex()
    private val backupMutex = Mutex()
    private var speech: SpeechRecognizerManager? = null
    private var speechRequest: CompletableDeferred<String>? = null
    private var launch: Map<String, Any?>? = null
    private var launchJob: Job? = null
    private val reportJobs = mutableMapOf<String, Job>()

    @OptIn(FlowPreview::class)
    fun attach(activity: MainActivity, messenger: BinaryMessenger) {
        this.activity = activity
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        methods = MethodChannel(messenger, "pennypilot/ledger").also { it.setMethodCallHandler(this) }
        events = EventChannel(messenger, "pennypilot/events").also { it.setStreamHandler(this) }
        observer = object : InvalidationTracker.Observer("transactions", "categories", "parse_rules", "monthly_reports") {
            override fun onInvalidated(tables: Set<String>) { scope.launch { emit("changed") } }
        }.also(database.invalidationTracker::addObserver)
        scope.launch { auth.state.collect { emit("changed") } }
        // A payment page can redraw rapidly. Coalesce diagnostic updates rather
        // than reloading the entire ledger for every accessibility event.
        scope.launch { monitor.health.debounce(300).collect { emit("changed") } }
        scope.launch { preferences.onboardingDone.collect { emit("changed") } }
        scope.launch { preferences.notificationCaptureEnabled.collect { emit("changed") } }
        scope.launch { preferences.screenCaptureEnabled.collect { emit("changed") } }
        scope.launch { preferences.notificationCloudParsingEnabled.collect { emit("changed") } }
        scope.launch { preferences.lastBackupAt.collect { emit("changed") } }
        scope.launch { LedgerRevision.changes.drop(1).collect {
            launch = null
            launchJob?.cancel()
            speech?.cancel()
            speechRequest?.cancel()
            emit("restored")
        } }
    }

    fun detach() {
        observer?.let(database.invalidationTracker::removeObserver)
        observer = null
        speechRequest?.cancel()
        speech?.destroy()
        speech = null
        scope.cancel()
        methods?.setMethodCallHandler(null)
        events?.setStreamHandler(null)
        methods = null
        events = null
        sink = null
        reportJobs.clear()
    }

    fun onResume() {
        emit("changed")
        scope.launch { runCatching { captureProcessor.refreshTodayCount() } }
    }

    fun handleIntent(intent: Intent?) {
        if (intent == null) return
        launchJob?.cancel()
        val revision = LedgerRevision.current
        val id = intent.getLongExtra("edit_tx_id", -1)
        val eventKey = intent.getStringExtra("capture_event_key")
        val route = intent.getStringExtra(MainActivity.EXTRA_OPEN_ROUTE)
        launchJob = scope.launch {
            val validId = try {
                if (id > 0 && !eventKey.isNullOrBlank()) {
                    database.transactionDao().getById(id)?.takeIf { it.captureEventKey == eventKey }?.id
                } else null
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { null }
            if (revision != LedgerRevision.current) return@launch
            if (validId != null || route == "coloros_guide") {
                launch = mapOf("transactionId" to validId, "route" to route)
                if (sink != null) {
                    emit("launch", launch.orEmpty())
                    launch = null
                }
            }
        }
    }

    override fun onListen(arguments: Any?, eventSink: EventChannel.EventSink) {
        sink = eventSink
        emit("changed")
    }
    override fun onCancel(arguments: Any?) { sink = null }
    private fun emit(type: String, values: Map<String, Any?> = emptyMap()) {
        sink?.success(mapOf("type" to type, "revision" to LedgerRevision.current) + values)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val args = (call.arguments as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()
        scope.launch {
            try { result.success(dispatch(call.method, args)) }
            catch (e: StaleLedgerException) { result.error("STALE_LEDGER", "账本已恢复，请重新打开此页面。", null) }
            catch (e: CancellationException) { result.error("CANCELLED", "操作已取消。", null) }
            catch (e: IllegalArgumentException) { result.error("INVALID_ARGUMENT", e.message ?: "输入有误，请检查后重试。", null) }
            catch (e: UserFacingLlmException) { result.error("AI_ERROR", e.message ?: "AI 服务暂不可用。", null) }
            catch (e: UnsupportedOperationException) { result.notImplemented() }
            catch (_: Exception) { result.error("OPERATION_FAILED", "操作未完成，内容已保留，请检查设置后重试。", null) }
        }
    }

    private suspend fun dispatch(method: String, args: Map<String, Any?>): Any? = when (method) {
        "snapshot" -> snapshot()
        // Dart shares the AI business logic across platforms. Only a specifically
        // requested key enters transient app memory; it never enters snapshots.
        "readAiCredential" -> withContext(Dispatchers.IO) {
            when (args.string("key")) {
                "deepSeekKey" -> keyStore.deepSeekKey
                "qwenKey" -> keyStore.qwenKey
                else -> throw IllegalArgumentException("不支持此凭据。")
            }
        }
        "exportBackupData" -> PortableBackupCodec.encode(database.backupDao().snapshot())
        "restoreBackupData" -> {
            val data = args["data"] as? Map<*, *> ?: throw IllegalArgumentException("备份格式无效。")
            val restored = withContext(Dispatchers.Default) {
                PortableBackupCodec.decode(data.entries.associate { it.key.toString() to it.value })
            }
            withContext(NonCancellable) {
                database.withTransaction {
                    checkRevision(args)
                    database.backupDao().replace(restored)
                    LedgerRevision.advance()
                }
                runCatching { context.getSystemService(android.app.NotificationManager::class.java).cancelAll() }
                LedgerRevision.publish()
            }
            null
        }
        "saveGeneratedReport" -> mutate(args) {
            val report = PortableBackupCodec.decodeReport(args)
            require(YearMonth.parse(report.yearMonth) < YearMonth.now()) { "只能生成已结束月份的月报。" }
            database.monthlyReportDao().insert(report)
            null
        }
        "saveTransaction" -> mutate(args) { mapOf("id" to saveTransaction(args)) }
        "saveTransactions" -> mutate(args) {
            val rows = args.maps("transactions")
            require(rows.isNotEmpty() && rows.size <= 100) { "请选择 1～100 笔待保存账目。" }
            mapOf("ids" to rows.map { saveTransaction(it) })
        }
        "deleteTransaction" -> mutate(args) {
            val row = requireNotNull(database.transactionDao().getById(args.long("id"))) { "账目已不存在，请刷新。" }
            database.transactionDao().deleteById(row.id)
            NotificationHelper.cancelCaptureNotification(context, row.id, row.captureEventKey)
            null
        }
        "setTransactionStatus" -> mutate(args) {
            val id = args.long("id")
            val status = args.string("status").let { value ->
                require(value in setOf("CONFIRMED", "IGNORED")) { "不支持此账目状态。" }; TxStatus.valueOf(value)
            }
            val row = requireNotNull(database.transactionDao().getById(id)) { "账目已不存在，请刷新。" }
            require(database.transactionDao().updateStatus(id, status) == 1) { "账目已处理，请刷新列表。" }
            NotificationHelper.cancelCaptureNotification(context, id, row.captureEventKey)
            null
        }
        "saveCategory" -> mutate(args) { mapOf("id" to saveCategory(args)) }
        "deleteCategory" -> mutate(args) { deleteCategory(args.long("id")); null }
        "saveRule" -> mutate(args) { mapOf("id" to saveRule(args)) }
        "deleteRule" -> mutate(args) {
            val rule = requireNotNull(database.parseRuleDao().observeAll().first().find { it.id == args.long("id") }) { "规则已不存在。" }
            database.parseRuleDao().delete(rule); null
        }
        "resetRules" -> mutate(args) {
            database.parseRuleDao().deleteAll(); database.parseRuleDao().insertAll(AppDatabase.PRESET_RULES); null
        }
        "updateSettings" -> {
            updateSettings(args)
            if (args.containsKey("lastBackupAt")) preferences.setLastBackupAt(args.long("lastBackupAt").also { require(it >= 0) })
            emit("changed"); null
        }
        "parseText" -> parse(args)
        "ask" -> ask(args)
        "exportBackup" -> exportBackup()
        "restoreBackup" -> restoreBackup(args)
        "startSpeech" -> mapOf("text" to startSpeech())
        "stopSpeech" -> { speech?.stopListening(); null }
        "cancelSpeech" -> { speech?.cancel(); speechRequest?.cancel(); null }
        "openSystemSettings" -> { openSystemSettings(args.string("target")); null }
        "requestNotificationPermission" -> mapOf("granted" to requestNotificationPermission())
        "rebindNotificationService" -> {
            NotificationListenerService.requestRebind(ComponentName(context, NotificationCaptorService::class.java)); null
        }
        "signIn" -> {
            val url = auth.beginSignIn(args.optionalString("accountId"), args.boolean("requestPlanPermission", false))
            try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            catch (e: Exception) { auth.cancelSignIn(); throw IllegalArgumentException("无法打开系统浏览器，请安装或启用浏览器后重试。") }
            null
        }
        "cancelSignIn" -> { auth.cancelSignIn(); null }
        "selectAccount" -> { auth.selectAccount(args.string("id")); null }
        "signOut" -> { auth.signOut(args.string("id")); null }
        "dismissWelcome" -> { auth.dismissWelcome(); null }
        "listModels" -> modelRepository.listModels().map { mapOf("slug" to it.slug, "displayName" to it.displayName) }
        "generateReport" -> generateReport(args)
        else -> throw UnsupportedOperationException(method)
    }

    private suspend fun snapshot(): Map<String, Any?> {
        auth.awaitReady()
        launchJob?.join()
        val (revision, ledger) = database.withTransaction {
            val dao = database.backupDao()
            // Capture evidence is needed by backup/deduplication, never by a UI snapshot.
            LedgerRevision.current to BackupSnapshot(dao.categories(), dao.transactions(), dao.rules(), dao.reports())
        }
        val authState = auth.state.value
        val pendingLaunch = launch
        launch = null
        return mapOf(
            "revision" to revision,
            "transactions" to ledger.transactions.sortedByDescending { it.occurredAt }.map { it.toMap() },
            "categories" to ledger.categories.sortedBy { it.sortOrder }.map { it.toMap() },
            "rules" to ledger.rules.sortedBy { it.priority }.map { it.toMap() },
            "reports" to ledger.reports.sortedByDescending { it.yearMonth }.map { it.toMap() },
            "settings" to mapOf(
                "onboardingDone" to preferences.onboardingDone.first(),
                "notificationCaptureEnabled" to preferences.notificationCaptureEnabled.first(),
                "screenCaptureEnabled" to preferences.screenCaptureEnabled.first(),
                "notificationCloudParsingEnabled" to preferences.notificationCloudParsingEnabled.first(),
                "lastBackupAt" to preferences.lastBackupAt.first(),
                "budgetCents" to uiPreferences.getLong("budget_cents", 0),
                "themeMode" to uiPreferences.getString("theme_mode", "system"),
                "activeProvider" to keyStore.activeProvider,
                "deepSeekModel" to keyStore.deepSeekModel,
                "qwenModel" to keyStore.qwenModel,
                "qwenBaseUrl" to keyStore.qwenBaseUrl,
                "hasDeepSeekKey" to keyStore.deepSeekKey.isNotBlank(),
                "hasQwenKey" to keyStore.qwenKey.isNotBlank(),
                "needsReconfiguration" to keyStore.needsReconfiguration,
                "chatGptModel" to authState.activeAccount?.id?.let(keyStore::chatGptModel).orEmpty()
            ),
            "capabilities" to capabilities(),
            "auth" to mapOf(
                "accounts" to authState.accounts.map { mapOf("id" to it.id, "email" to it.email,
                    "canUsePlan" to it.canUsePlan, "isSignedIn" to it.isSignedIn) },
                "activeAccountId" to authState.activeAccount?.id,
                "isAuthorizing" to authState.isAuthorizing,
                "loginUrl" to authState.loginUrl,
                "error" to authState.error,
                "showWelcome" to authState.showWelcome
            ),
            "launch" to pendingLaunch
        )
    }

    private fun capabilities(): Map<String, Any?> {
        val component = ComponentName(context, NotificationCaptorService::class.java)
        val listenerGranted = runCatching {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true
        }.getOrDefault(false)
        val accessibilityComponent = ComponentName(context, PaymentAccessibilityService::class.java)
        val accessibilityGranted = runCatching {
            context.getSystemService(AccessibilityManager::class.java)
                ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)?.any {
                    val service = it.resolveInfo.serviceInfo
                    ComponentName(service.packageName, service.name) == accessibilityComponent
                } == true
        }.getOrDefault(false)
        val health = monitor.health.value
        return mapOf(
            "platform" to "android", "notificationListenerGranted" to listenerGranted,
            "automaticCaptureSupported" to true, "chatGptSupported" to true,
            "accessibilityGranted" to accessibilityGranted,
            "remindersEnabled" to runCatching { NotificationHelper.captureNotificationsEnabled(context) }.getOrDefault(false),
            "microphoneGranted" to isGranted(Manifest.permission.RECORD_AUDIO),
            "batteryExempt" to runCatching { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false),
            "speechAvailable" to runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false),
            "notificationServiceConnected" to health.isBound,
            "accessibilityServiceConnected" to health.isAccessibilityBound,
            "lastScreenEventTime" to health.lastScreenEventTime,
            "lastScreenReadTime" to health.lastScreenReadTime,
            "lastScreenPackage" to health.lastScreenPackage,
            "screenCaptureStatus" to health.screenCaptureStatus,
            "screenReadDiagnostics" to health.screenReadDiagnostics,
            "screenReadHistory" to health.screenReadHistory.map { trace -> mapOf(
                "status" to trace.status, "timestamp" to trace.timestamp,
                "packageName" to trace.packageName, "diagnostics" to trace.diagnostics
            ) },
            "todayCaptureCount" to health.todayCaptureCount, "duplicateCount" to health.duplicateCount,
            "lastCaptureAt" to health.lastNotificationTime, "lastError" to health.lastError
        )
    }

    private fun checkRevision(args: Map<String, Any?>): Long {
        val expected = args.long("revision")
        if (expected != LedgerRevision.current) throw StaleLedgerException()
        return expected
    }
    private suspend fun <T> mutate(args: Map<String, Any?>, action: suspend () -> T): T = database.withTransaction {
        checkRevision(args)
        action()
    }

    private suspend fun saveTransaction(args: Map<String, Any?>): Long {
        val id = args.optionalLong("id") ?: 0
        require(id >= 0) { "账目编号无效。" }
        val old = if (id > 0) requireNotNull(database.transactionDao().getById(id)) { "账目已不存在，请刷新。" } else null
        val amount = args.long("amount")
        require(MoneyUtil.isValidAmountCents(amount)) { "请输入 0.01～9,999,999.99 元。" }
        val type = args.txType()
        val category = requireNotNull(database.categoryDao().getById(args.long("categoryId"))) { "分类已不存在，请重新选择。" }
        require(category.type == type && (category.enabled || category.id == old?.categoryId)) { "请选择相同收支类型的可用分类。" }
        val occurredAt = args.long("occurredAt")
        require(occurredAt in 0..253402214400000L) { "账目日期无效。" }
        val note = args.optionalString("note")?.takeIf { it.isNotBlank() }
        val merchant = args.optionalString("merchant")?.takeIf { it.isNotBlank() }
        val accountTag = args.optionalString("accountTag")?.takeIf { it.isNotBlank() }
        require((note?.length ?: 0) <= 2000 && (merchant?.length ?: 0) <= 200 && (accountTag?.length ?: 0) <= 100) { "备注、商户或账户名称过长。" }
        val signedAmount = if (type == TxType.INCOME) -amount else amount
        if (old != null) {
            database.transactionDao().update(old.copy(amount = signedAmount, type = type, categoryId = category.id,
                note = note, merchant = merchant, accountTag = accountTag, occurredAt = occurredAt))
            return old.id
        }
        val source = args.optionalString("source") ?: "MANUAL"
        require(source in setOf("MANUAL", "AI_TEXT", "AI_VOICE")) { "不支持此录入来源。" }
        return database.transactionDao().insert(TransactionEntity(amount = signedAmount, type = type, categoryId = category.id,
            note = note, merchant = merchant, accountTag = accountTag, occurredAt = occurredAt,
            source = TxSource.valueOf(source), status = TxStatus.CONFIRMED))
    }

    private suspend fun saveCategory(args: Map<String, Any?>): Long {
        val id = args.optionalLong("id") ?: 0
        require(id >= 0) { "分类编号无效。" }
        val all = database.categoryDao().observeAll().first()
        val old = if (id > 0) requireNotNull(all.find { it.id == id }) { "分类已不存在。" } else null
        val type = args.txType()
        require(old == null || old.type == type) { "已有分类的收支类型不可更改。" }
        val name = args.string("name").trim()
        val icon = args.string("icon").trim()
        require(name.isNotEmpty() && name.length <= 20) { "分类名称须为 1～20 个字。" }
        require(icon.isNotEmpty() && icon.length <= 32) { "请填写有效的分类图标。" }
        require(all.none { it.id != id && it.type == type && it.name == name }) { "该类型下已有同名分类。" }
        val enabled = args.boolean("enabled", old?.enabled ?: true)
        val sortOrder = (args.optionalLong("sortOrder") ?: old?.sortOrder?.toLong() ?: ((all.maxOfOrNull { it.sortOrder } ?: -1) + 1).toLong())
        require(sortOrder in 0..Int.MAX_VALUE.toLong()) { "分类排序无效。" }
        if (old != null) {
            database.categoryDao().update(old.copy(name = name, icon = icon, enabled = enabled, sortOrder = sortOrder.toInt()))
            return old.id
        }
        return database.categoryDao().insert(CategoryEntity(name = name, icon = icon, colorIndex = (database.categoryDao().maxColorIndex() + 1) % 7,
            type = type, sortOrder = sortOrder.toInt(), enabled = enabled))
    }

    private suspend fun deleteCategory(id: Long) {
        val old = requireNotNull(database.categoryDao().getById(id)) { "分类已不存在。" }
        require(!old.isPreset) { "预设分类不能删除，可在列表中停用。" }
        val all = database.categoryDao().observeAll().first()
        val fallback = all.find { it.type == old.type && it.name == "其他" && it.id != id }
            ?: requireNotNull(database.categoryDao().getById(database.categoryDao().insert(CategoryEntity(
                name = "其他", icon = if (old.type == TxType.EXPENSE) "📦" else "💰",
                colorIndex = (database.categoryDao().maxColorIndex() + 1) % 7, type = old.type,
                sortOrder = (all.maxOfOrNull { it.sortOrder } ?: -1) + 1, isPreset = true))))
        if (!fallback.enabled) database.categoryDao().update(fallback.copy(enabled = true))
        database.transactionDao().reassignCategory(id, fallback.id)
        database.categoryDao().deleteById(id)
    }

    private suspend fun saveRule(args: Map<String, Any?>): Long {
        val id = args.optionalLong("id") ?: 0
        require(id >= 0) { "规则编号无效。" }
        val amount = args.string("regexAmount")
        val merchant = args.optionalString("regexMerchant")?.takeIf { it.isNotBlank() }
        val appPackage = args.string("appPackage").trim()
        require(appPackage.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) { "请填写有效的应用包名。" }
        require(amount.length <= 4000 && amount.isNotBlank()) { "请填写有效的金额正则。" }
        require(Regex(amount).toPattern().matcher("").groupCount() > 0) { "金额正则需要一个用于提取金额的捕获组。" }
        if (merchant != null) {
            require(merchant.length <= 4000 && Regex(merchant).toPattern().matcher("").groupCount() > 0) { "商户正则需要捕获组。" }
        }
        val direction = args.optionalString("directionHint") ?: "EXPENSE"
        require(direction in setOf("EXPENSE", "INCOME")) { "请选择支出或收入。" }
        val priority = args.optionalLong("priority") ?: 0
        require(priority in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "规则优先级无效。" }
        val rule = ParseRuleEntity(id = id, appPackage = appPackage, regexAmount = amount, regexMerchant = merchant,
            directionHint = direction, enabled = args.boolean("enabled", true), priority = priority.toInt())
        if (id > 0) {
            require(database.parseRuleDao().observeAll().first().any { it.id == id }) { "规则已不存在。" }
            database.parseRuleDao().update(rule)
            return id
        }
        return database.parseRuleDao().insert(rule)
    }

    private suspend fun parse(args: Map<String, Any?>): Map<String, Any?> {
        val revision = checkRevision(args)
        val input = args.string("text").trim()
        require(input.isNotEmpty() && input.length <= 20000) { "请输入 1～20000 字的记账内容。" }
        val result = parseText.execute(input)
        if (revision != LedgerRevision.current) throw StaleLedgerException()
        return mapOf("error" to result.error, "transactions" to result.transactions.map {
            mapOf("amount" to it.amount, "type" to it.type.name, "categoryId" to it.categoryId,
                "categoryName" to it.categoryName, "note" to it.note, "merchant" to it.merchant, "occurredAt" to it.occurredAt)
        })
    }

    private suspend fun ask(args: Map<String, Any?>): Map<String, Any?> {
        val revision = checkRevision(args)
        val question = args.string("question").trim()
        require(question.isNotEmpty() && question.length <= 10000) { "请输入 1～10000 字的问题。" }
        val history = args.maps("history").takeLast(20).map {
            val role = it.string("role")
            require(role in setOf("user", "assistant")) { "对话历史格式无效。" }
            val content = it.string("content")
            require(content.length <= 20000) { "对话内容过长，请开始新对话。" }
            Message(role, content)
        }
        val answer = insight.ask(question, history)
        if (revision != LedgerRevision.current) throw StaleLedgerException()
        return mapOf("answer" to answer.answer, "isError" to answer.isError,
            "chartData" to answer.chartData?.let { mapOf("labels" to it.labels, "values" to it.values, "highlightIndex" to it.highlightIndex, "unit" to it.unit) })
    }

    private suspend fun updateSettings(args: Map<String, Any?>) = settingsMutex.withLock {
        // Validate everything before any writes. Omitted keys preserve existing secrets.
        listOf("onboardingDone", "notificationCaptureEnabled", "screenCaptureEnabled", "notificationCloudParsingEnabled")
            .filter(args::containsKey).forEach { args.boolean(it) }
        args["activeProvider"]?.let { require(it in setOf("", "chatgpt", "deepseek", "qwen")) { "请选择有效的 AI 服务。" } }
        args["themeMode"]?.let { require(it in setOf("system", "light", "dark")) { "主题设置无效。" } }
        val budget = args.optionalLong("budgetCents")
        require(budget == null || budget in 0..MoneyUtil.MAX_TRANSACTION_CENTS) { "月预算须在 0～9,999,999.99 元之间。" }
        listOf("deepSeekModel", "qwenModel", "chatGptModel").filter(args::containsKey).forEach {
            require(args.string(it).isNotBlank() && args.string(it).length <= 256) { "请填写有效的模型名称。" }
        }
        listOf("deepSeekKey", "qwenKey").filter(args::containsKey).forEach {
            require(args.string(it).length <= 8192 && !args.string(it).any(Char::isISOControl)) { "API Key 格式无效。" }
        }
        args.optionalString("qwenBaseUrl")?.let {
            val uri = Uri.parse(it)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) { "AI 服务地址须为有效的 HTTPS 地址。" }
        }
        val modelAccount = if (args.containsKey("chatGptModel")) {
            auth.awaitReady()
            requireNotNull(auth.state.value.activeAccount?.takeIf { it.isSignedIn }?.id) { "请先登录 ChatGPT 账号。" }
        } else null
        val secureFields = listOf("deepSeekKey", "qwenKey", "deepSeekModel", "qwenModel", "qwenBaseUrl", "activeProvider", "chatGptModel")
        if (secureFields.any(args::containsKey)) withContext(Dispatchers.IO) {
            args.optionalString("deepSeekKey")?.let { keyStore.deepSeekKey = it.trim() }
            args.optionalString("qwenKey")?.let { keyStore.qwenKey = it.trim() }
            args.optionalString("deepSeekModel")?.let { keyStore.deepSeekModel = it.trim() }
            args.optionalString("qwenModel")?.let { keyStore.qwenModel = it.trim() }
            args.optionalString("qwenBaseUrl")?.let { keyStore.qwenBaseUrl = it.trim().trimEnd('/') }
            args.optionalString("chatGptModel")?.let { keyStore.setChatGptModel(requireNotNull(modelAccount), it.trim()) }
            args.optionalString("activeProvider")?.let { keyStore.activeProvider = it }
            keyStore.clearRecoveryNotice()
        }
        if (args.containsKey("notificationCaptureEnabled")) preferences.setNotificationCaptureEnabled(args.boolean("notificationCaptureEnabled"))
        if (args.containsKey("screenCaptureEnabled")) preferences.setScreenCaptureEnabled(args.boolean("screenCaptureEnabled"))
        if (args.containsKey("notificationCloudParsingEnabled")) preferences.setNotificationCloudParsingEnabled(args.boolean("notificationCloudParsingEnabled"))
        if (args.boolean("onboardingDone", false)) preferences.setOnboardingDone()
        if (budget != null || args.containsKey("themeMode")) withContext(Dispatchers.IO) {
            val editor = uiPreferences.edit()
            budget?.let { editor.putLong("budget_cents", it) }
            args.optionalString("themeMode")?.let { editor.putString("theme_mode", it) }
            check(editor.commit()) { "设置未能保存。" }
        }
    }

    private suspend fun exportBackup(): Map<String, Any?> = backupMutex.withLock {
        val destination = activity.chooseExportDocument() ?: return@withLock mapOf("cancelled" to true)
        backup.exportDb(context, destination).getOrThrow()
        preferences.setLastBackupAt(System.currentTimeMillis())
        mapOf("cancelled" to false)
    }

    private suspend fun restoreBackup(args: Map<String, Any?>): Map<String, Any?> = backupMutex.withLock {
        val revision = checkRevision(args)
        val documents = activity.chooseRestoreDocuments()
        if (documents.isEmpty()) return@withLock mapOf("cancelled" to true)
        if (revision != LedgerRevision.current) throw StaleLedgerException()
        backup.restoreDb(context, documents).getOrThrow()
        mapOf("cancelled" to false)
    }

    private fun isGranted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private suspend fun requestNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || isGranted(Manifest.permission.POST_NOTIFICATIONS)) return true
        preferences.markNotificationPermissionRequested()
        return activity.askPermission(Manifest.permission.POST_NOTIFICATIONS).also { emit("changed") }
    }

    private suspend fun startSpeech(): String {
        require(speechRequest == null) { "正在识别语音，请先结束当前录音。" }
        require(SpeechRecognizer.isRecognitionAvailable(context)) { "此设备没有可用的语音识别服务，请使用文字输入。" }
        val request = CompletableDeferred<String>()
        speechRequest = request
        return try {
            if (!isGranted(Manifest.permission.RECORD_AUDIO)) {
                require(activity.askPermission(Manifest.permission.RECORD_AUDIO)) { "未获得录音权限，可前往系统设置开启。" }
                emit("changed")
            }
            if (!request.isActive) throw CancellationException("语音录入已取消。")
            val manager = speech ?: SpeechRecognizerManager(context).also { created ->
                speech = created
                scope.launch { created.partialText.collect { emit("speech", mapOf("text" to it)) } }
            }
            manager.startListening(onResult = { request.complete(it) }, onError = { request.completeExceptionally(IllegalArgumentException(it)) })
            request.await()
        } finally {
            if (request.isCancelled) speech?.cancel()
            speechRequest = null
        }
    }

    private fun openSystemSettings(target: String) {
        val intent = when (target) {
            "notificationListener" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            "battery" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            "app", "microphone" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            else -> throw IllegalArgumentException("不支持此系统设置入口。")
        }
        try { activity.startActivity(intent) }
        catch (_: Exception) {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
    }

    private suspend fun generateReport(args: Map<String, Any?>): Map<String, Any?> {
        checkRevision(args)
        val month = YearMonth.parse(args.string("yearMonth"))
        require(month < YearMonth.now()) { "请选择已结束的月份生成报告。" }
        val work = WorkManager.getInstance(context)
        val name = "monthly_report_$month"
        val request = OneTimeWorkRequestBuilder<MonthlyReportWorker>()
            .setInputData(workDataOf(MonthlyReportWorker.KEY_FORCE to true, MonthlyReportWorker.KEY_MONTH to month.toString())).build()
        work.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request).await()
        reportJobs.remove(name)?.cancel()
        reportJobs[name] = scope.launch {
            work.getWorkInfosForUniqueWorkFlow(name).collect { infos ->
                val active = infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull { it.id == request.id } ?: infos.firstOrNull()
                if (active != null) emit("report", mapOf("yearMonth" to month.toString(), "state" to active.state.name))
            }
        }
        return mapOf("queued" to true)
    }
}

private class StaleLedgerException : IllegalStateException()
private fun Map<String, Any?>.string(key: String): String = this[key] as? String ?: throw IllegalArgumentException("缺少或无效的字段：$key")
private fun Map<String, Any?>.optionalString(key: String): String? = if (this[key] == null) null else string(key)
private fun Map<String, Any?>.long(key: String): Long = when (val value = this[key]) {
    is Int -> value.toLong()
    is Long -> value
    else -> throw IllegalArgumentException("缺少或无效的整数：$key")
}
private fun Map<String, Any?>.optionalLong(key: String): Long? = if (this[key] == null) null else long(key)
private fun Map<String, Any?>.boolean(key: String, default: Boolean = false): Boolean =
    if (!containsKey(key)) default else this[key] as? Boolean ?: throw IllegalArgumentException("无效的开关：$key")
private fun Map<String, Any?>.txType(): TxType {
    val value = string("type")
    require(value in setOf("EXPENSE", "INCOME")) { "请选择支出或收入。" }
    return TxType.valueOf(value)
}
private fun Map<String, Any?>.maps(key: String): List<Map<String, Any?>> {
    if (!containsKey(key)) return emptyList()
    val list = this[key] as? List<*> ?: throw IllegalArgumentException("列表格式无效：$key")
    return list.map { value ->
        val map = value as? Map<*, *> ?: throw IllegalArgumentException("列表格式无效：$key")
        map.entries.associate { it.key.toString() to it.value }
    }
}

private fun TransactionEntity.toMap(): Map<String, Any?> = mapOf(
    "id" to id, "amount" to amount, "type" to type.name, "categoryId" to categoryId,
    "note" to note, "merchant" to merchant, "accountTag" to accountTag, "occurredAt" to occurredAt,
    "createdAt" to createdAt, "source" to source.name, "status" to status.name, "duplicateOf" to duplicateOf
)
private fun CategoryEntity.toMap(): Map<String, Any?> = mapOf(
    "id" to id, "name" to name, "icon" to icon, "colorIndex" to colorIndex, "type" to type.name,
    "sortOrder" to sortOrder, "enabled" to enabled, "isPreset" to isPreset
)
private fun ParseRuleEntity.toMap(): Map<String, Any?> = mapOf(
    "id" to id, "appPackage" to appPackage, "regexAmount" to regexAmount, "regexMerchant" to regexMerchant,
    "directionHint" to directionHint, "enabled" to enabled, "priority" to priority
)
private fun MonthlyReportEntity.toMap(): Map<String, Any?> = mapOf(
    "id" to id, "yearMonth" to yearMonth, "totalExpense" to totalExpense, "totalIncome" to totalIncome,
    "byCategoryJson" to byCategoryJson, "llmSummary" to llmSummary, "generatedAt" to generatedAt
)

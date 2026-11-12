import 'dart:async';
import 'dart:convert';

import 'recognition/recognition_protocol.dart';
import 'agent/agent_models.dart' show agentId;
import 'agent/agent_presentation.dart';
import 'agent/agent_runtime.dart' show LedgerAgentRuntime;
import 'storage/assistant_attachment_store.dart';
import 'ai/model_reasoning.dart';

import 'package:flutter/foundation.dart';

import 'ledger_repository.dart';
import 'cross_platform_repository.dart';
import 'models.dart';

export 'ledger_repository.dart' show LedgerException;

@immutable
class RecognitionSaveReceipt {
  RecognitionSaveReceipt({
    required this.revision,
    required List<LedgerTransaction> transactions,
  }) : transactions = List.unmodifiable(transactions),
       ids = List.unmodifiable(transactions.map((item) => item.id));

  final int revision;
  final List<int> ids;
  final List<LedgerTransaction> transactions;
  int get count => ids.length;
}

class LedgerController extends ChangeNotifier {
  LedgerController({
    LedgerRepository? repository,
    DateTime Function()? clock,
    AssistantAttachmentStore? attachmentStore,
  }) : _repository = repository ?? CrossPlatformLedgerRepository(),
       _attachmentStore = attachmentStore ?? AssistantAttachmentStore(),
       _clock = clock ?? DateTime.now {
    final now = _clock();
    _selectedMonth = DateTime(now.year, now.month);
  }

  final LedgerRepository _repository;
  final AssistantAttachmentStore _attachmentStore;
  final DateTime Function() _clock;
  final ValueNotifier<int> chatUpdates = ValueNotifier(0);
  Timer? _chatUpdateTimer;
  LedgerSnapshot? _snapshot;
  Map<int, LedgerTransaction> _transactionIndex = {};
  LedgerSnapshot? get snapshot => _snapshot;
  int get revision => _snapshot?.revision ?? 0;
  StreamSubscription<LedgerEvent>? _subscription;
  bool _disposed = false, _initialized = false, _loading = false;
  int _generation = 0, _refreshSequence = 0;
  int get sessionGeneration => _generation;
  Future<void>? _refreshFuture;
  bool _refreshAgain = false;
  final Set<String> _busy = {};
  String? _error;
  String? get error => _error;
  bool get isLoading => _loading;
  bool get isBusy => _busy.isNotEmpty;
  bool busyFor(String operation) => _busy.contains(operation);
  bool get isParsing => busyFor('parseText');
  bool get isAsking => busyFor('ask');
  bool get isRecording => busyFor('startSpeech');
  String _speechState = 'idle';
  String get speechState => _speechState;
  bool get isTranscribing => _speechState == 'transcribing';
  List<LedgerTransaction> get transactions =>
      _snapshot?.transactions ?? const [];
  List<LedgerCategory> get categories => _snapshot?.categories ?? const [];
  List<ParseRule> get rules => _snapshot?.rules ?? const [];
  List<MonthlyReport> get reports => _snapshot?.reports ?? const [];
  Map<String, dynamic> get settings => _snapshot?.settings ?? const {};
  Map<String, dynamic> get capabilities => _snapshot?.capabilities ?? const {};
  Map<String, dynamic> get auth => _snapshot?.auth ?? const {};
  List<AiModel> _reasoningModels = [];
  String? _reasoningCatalogAccount,
      _reasoningCatalogAttempt,
      _reasoningObservedAccount;
  ReasoningOptions? _runningReasoningOptions;
  ReasoningOptions get assistantReasoningOptions =>
      isAsking && _runningReasoningOptions != null
      ? _runningReasoningOptions!
      : _configuredReasoningOptions;
  ReasoningOptions get _configuredReasoningOptions => resolveReasoningOptions(
    settings,
    catalogModel: _reasoningCatalogAccount == auth['activeAccountId']
        ? _reasoningModels
              .where((m) => m.slug == settings['chatGptModel'])
              .firstOrNull
        : null,
  );
  ReasoningCapability get assistantReasoningCapability =>
      assistantReasoningOptions.capability;

  Future<void> setAssistantReasoning({bool? enabled, String? effort}) async {
    if (isAsking) throw const LedgerException('当前任务使用发送时的思考设置，请在完成后调整。');
    final options = _configuredReasoningOptions;
    if (enabled != null && !options.capability.canToggle) {
      throw const LedgerException('当前模型不支持切换思考模式。');
    }
    if (effort != null && !options.capability.efforts.contains(effort)) {
      throw const LedgerException('当前模型不支持这个思考深度。');
    }
    final prefix = reasoningSettingsPrefix(options.provider);
    await updateSettings({
      '${prefix}ThinkingEnabled': ?enabled,
      '${prefix}ReasoningEffort': ?effort,
    });
  }

  late DateTime _selectedMonth;
  DateTime get selectedMonth => _selectedMonth;
  String _keyword = '';
  String get keyword => _keyword;
  TxType? _typeFilter;
  TxType? get typeFilter => _typeFilter;
  TxStatus? _statusFilter;
  TxStatus? get statusFilter => _statusFilter;
  bool _includeIgnored = false;
  bool get includesAllStatuses => _includeIgnored;
  int? _categoryFilter;
  int? get categoryFilter => _categoryFilter;
  DateTime? _rangeStart, _rangeEnd;
  DateTime? get rangeStart => _rangeStart;
  DateTime? get rangeEnd => _rangeEnd;

  List<String> recognitionQuestions = [];
  String? recognitionNotice;
  RecognitionSaveReceipt? _lastRecognitionSave;
  final Set<String> _undoneRecognitionOperations = {};
  RecognitionSaveReceipt? get lastRecognitionSave => _lastRecognitionSave;
  bool get canUndoRecognitionSave =>
      _lastRecognitionSave != null &&
      _lastRecognitionSave!.revision == revision &&
      !isBusy;
  List<LedgerTransaction> _drafts = [];
  int _draftGeneration = 0;
  List<LedgerTransaction> get drafts => List.unmodifiable(_drafts);
  List<ChatMessage> _messages = [];
  List<ChatMessage> get messages => List.unmodifiable(_messages);
  Map<String, dynamic> _agentSession = {};
  Map<String, dynamic> get agentSession => Map.unmodifiable(_agentSession);
  List<Map<String, dynamic>> agentSessions = [], agentMemories = [];
  Map<String, dynamic> agentConfig = {}, agentProgress = {};
  bool _agentLoaded = false;
  String? _activeAssistantRun, _liveMessageId;
  String _assistantDraft = '';
  String get assistantDraft => _assistantDraft;
  Map<String, dynamic>? _assistantAttachment;
  Map<String, dynamic>? get assistantAttachment => _assistantAttachment;
  String? _recognitionRunId;
  bool _localReceiptRun = false;
  String _recognitionText = '';
  Map<String, dynamic>? _recognitionImage;
  List<Map<String, dynamic>> _recognitionAttachments = [];
  Future<void> _recognitionPersistence = Future.value();
  bool get pendingRecognition =>
      _recognitionRunId != null &&
      (_drafts.any(
            (t) =>
                t.recognitionMetadata['requestSessionId'] == _recognitionRunId,
          ) ||
          recognitionQuestions.isNotEmpty);
  bool get canSupplementRecognition => pendingRecognition && !_localReceiptRun;
  static final _explicitRecognitionCancellation = RegExp(
    r'^取消(?:这次)?识别[。！!\s]*$',
  );
  String? _retryQuestion;
  String? get retryQuestion => _retryQuestion;
  String _speechText = '';
  String get speechText => _speechText;
  Map<String, dynamic>? _launchTarget;
  Map<String, dynamic>? get launchTarget => _launchTarget;
  final Map<String, String> _reportStates = {};
  Map<String, String> get reportStates => Map.unmodifiable(_reportStates);

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  void _notifyChat() {
    if (_disposed || _chatUpdateTimer != null) return;
    _chatUpdateTimer = Timer(const Duration(milliseconds: 40), () {
      _chatUpdateTimer = null;
      if (!_disposed) chatUpdates.value++;
    });
  }

  void _onAgentProgress(Map<String, dynamic> event) {
    if (!isAsking) return;
    final phase = event['phase'];
    final runId = event['runId'] as String?;
    if (runId != null &&
        _activeAssistantRun != null &&
        runId != _activeAssistantRun) {
      return;
    }
    if (phase == 'run_started') {
      _activeAssistantRun = runId;
      if (event['reasoning'] is Map && event['reasoning']['mode'] is String) {
        _runningReasoningOptions = ReasoningOptions.fromMap(
          stringMap(event['reasoning']),
        );
      }
      if (event['internal'] != true && _messages.lastOrNull?.role == 'user') {
        _messages[_messages.length - 1] = _messages.last.copyWith(
          id: event['userMessageId'] as String?,
          runId: runId,
        );
      }
    }
    agentProgress = {...agentProgress, ...event};
    if (runId == null) {
      _notifyChat();
      return;
    }
    var index = _messages.lastIndexWhere(
      (m) => m.role == 'assistant' && m.runId == runId,
    );
    if (index < 0) {
      _messages.add(
        ChatMessage(
          id: '$runId:answer',
          runId: runId,
          role: 'assistant',
          text: '',
          status: 'streaming',
          isStreaming: true,
          createdAt: _clock().millisecondsSinceEpoch,
        ),
      );
      index = _messages.length - 1;
    }
    var message = _messages[index];
    switch (phase) {
      case 'reasoning_start':
      case 'reasoning_delta':
      case 'reasoning_complete':
        final part = stringMap(event['part']);
        final parts = List<Map<String, dynamic>>.of(message.parts);
        final old = parts.indexWhere(
          (p) => p['type'] == 'reasoning' && p['id'] == part['id'],
        );
        if (old < 0) {
          parts.add(part);
        } else {
          parts[old] = part;
        }
        message = message.copyWith(parts: parts);
      case 'text_start':
        _liveMessageId = event['messageId'] as String?;
        message = message.copyWith(
          text: '',
          status: 'streaming',
          isStreaming: true,
        );
      case 'text_delta':
        if (_liveMessageId == event['messageId']) {
          message = message.copyWith(
            text: message.text + (event['delta'] as String? ?? ''),
          );
        }
      case 'text_complete':
        if (_liveMessageId == event['messageId']) {
          message = message.copyWith(
            text: event['text'] as String? ?? message.text,
            status: 'checking',
          );
        }
      case 'text_reset':
        if (_liveMessageId == event['messageId']) {
          message = message.copyWith(text: '', status: 'checking');
          agentProgress = {...agentProgress, 'text': '正在核对账本依据…'};
        }
      case 'tool':
      case 'tool_result':
        final parts = List<Map<String, dynamic>>.of(message.parts);
        final callId = event['callId'];
        if (callId != null) {
          final trace = stringMap(event['trace'] ?? const {});
          final result = event['result'];
          final part = <String, dynamic>{
            'type': 'tool',
            ...trace,
            'callId': callId,
            'tool': event['tool'],
            'status': phase == 'tool'
                ? 'running'
                : trace['status'] ?? 'completed',
            'result': ?result,
          };
          final old = parts.indexWhere(
            (p) => p['type'] == 'tool' && p['callId'] == callId,
          );
          if (old < 0) {
            parts.add(part);
          } else {
            parts[old] = {...parts[old], ...part};
          }
          if (result is Map && result['presentation'] is Map) {
            final spec = stringMap(result['presentation']);
            if (!parts.any(
              (p) => p['spec'] is Map && p['spec']['id'] == spec['id'],
            )) {
              parts.add({'type': spec['type'], 'spec': spec});
            }
          }
        }
        for (final raw in (event['actions'] as List? ?? []).whereType<Map>()) {
          if (raw['runId'] != null && raw['runId'] != runId) continue;
          final action = {'type': 'action', ...stringMap(raw)};
          final old = parts.indexWhere(
            (p) => p['type'] == 'action' && p['id'] == action['id'],
          );
          if (old < 0) {
            parts.add(action);
          } else {
            parts[old] = action;
          }
        }
        message = message.copyWith(parts: parts);
      case 'run_finished':
        message = message.copyWith(
          isStreaming: false,
          status: event['status'] as String? ?? 'complete',
        );
    }
    _messages[index] = message;
    _notifyChat();
  }

  Future<void> init() async {
    if (_disposed) return;
    if (!_initialized) {
      _initialized = true;
      _subscription = _repository.events.listen(
        _onEvent,
        onError: (Object error) {
          _error = _friendly(error).message;
          _notify();
        },
      );
    }
    await refresh();
  }

  /// Coalesces rapid Room/settings events while ensuring a final fresh snapshot.
  Future<void> refresh() {
    if (_disposed) return Future.value();
    if (_refreshFuture != null) {
      _refreshAgain = true;
      return _refreshFuture!;
    }
    final completer = Completer<void>();
    _refreshFuture = completer.future;
    unawaited(() async {
      try {
        do {
          _refreshAgain = false;
          await _loadSnapshot();
        } while (_refreshAgain && !_disposed);
      } finally {
        _refreshFuture = null;
        completer.complete();
      }
    }());
    return completer.future;
  }

  Future<void> _loadSnapshot() async {
    final sequence = ++_refreshSequence;
    final generation = _generation;
    _loading = true;
    _notify();
    try {
      final next = await _repository.snapshot();
      if (_disposed || sequence != _refreshSequence) return;
      // A restore event may arrive while the pre-restore query is in flight.
      if (generation != _generation && next.revision < _minimumRevision) {
        _refreshAgain = true;
        return;
      }
      if (next.revision < _minimumRevision) return;
      if (_snapshot != null && next.revision > _minimumRevision) {
        _invalidateSession();
      }
      _minimumRevision = next.revision;
      _snapshot = next;
      final accountId = next.auth['activeAccountId'] as String?;
      if (_reasoningObservedAccount != accountId) {
        _reasoningObservedAccount = accountId;
        _reasoningCatalogAccount = null;
        _reasoningCatalogAttempt = null;
        _reasoningModels = [];
      }
      if (next.settings['activeProvider'] == 'chatgpt' &&
          accountId != null &&
          _reasoningCatalogAttempt != accountId) {
        _reasoningCatalogAttempt = accountId;
        unawaited(_loadReasoningCatalog());
      }
      _transactionIndex = {for (final t in next.transactions) t.id: t};
      if (!_agentLoaded) {
        _agentLoaded = true;
        await loadAgentState();
      }
      if (next.launch != null) _launchTarget = Map.unmodifiable(next.launch!);
      _error = null;
    } catch (error) {
      if (!_disposed && sequence == _refreshSequence) {
        _error = _friendly(error).message;
      }
    } finally {
      if (!_disposed && sequence == _refreshSequence) {
        _loading = false;
        _notify();
      }
    }
  }

  int _minimumRevision = 0;
  void _onEvent(LedgerEvent event) {
    if (_disposed || event.revision < _minimumRevision) return;
    if (event.revision > _minimumRevision) {
      _minimumRevision = event.revision;
      _invalidateSession();
      if (event.type != 'changed' && event.type != 'restored') {
        unawaited(refresh());
      }
    }
    switch (event.type) {
      case 'restored':
      case 'changed':
        unawaited(refresh());
      case 'launch':
        _launchTarget = Map.unmodifiable(event.data);
        _notify();
      case 'speech':
        _speechText = event.data['text'] as String? ?? '';
        _notify();
      case 'speechState':
        _speechState = event.data['state'] as String? ?? 'idle';
        _notify();
      case 'report':
        final month = event.data['yearMonth'] as String?;
        final state = event.data['state'] as String?;
        if (month != null && state != null) _reportStates[month] = state;
        if (state == 'SUCCEEDED') unawaited(refresh());
        _notify();
      case 'agent':
        _onAgentProgress(event.data);
    }
  }

  void _invalidateSession() {
    _generation++;
    _draftGeneration++;
    _drafts = [];
    _lastRecognitionSave = null;
    _undoneRecognitionOperations.clear();
    _messages = [];
    _agentSession = {};
    agentSessions = [];
    agentMemories = [];
    agentProgress = {};
    _agentLoaded = false;
    _activeAssistantRun = null;
    _runningReasoningOptions = null;
    _liveMessageId = null;
    _assistantDraft = '';
    _assistantAttachment = null;
    _recognitionRunId = null;
    _localReceiptRun = false;
    _recognitionText = '';
    _recognitionImage = null;
    _recognitionAttachments = [];
    _retryQuestion = null;
    _speechText = '';
    _launchTarget = null;
    _reportStates.clear();
  }

  void clearError() {
    _error = null;
    _notify();
  }

  void consumeLaunch() {
    _launchTarget = null;
    _notify();
  }

  void setAssistantDraft(String value) {
    if (value == _assistantDraft) return;
    _assistantDraft = value;
    _notifyChat();
  }

  void setAssistantAttachment(Map<String, dynamic>? value) {
    _assistantAttachment = value == null ? null : Map.of(value);
    _notify();
  }

  void openAssistant({String? prompt, Map<String, dynamic>? attachment}) {
    if (prompt != null) {
      _assistantDraft = _assistantDraft.trim().isEmpty
          ? prompt
          : '$_assistantDraft\n$prompt';
    }
    if (attachment != null) _assistantAttachment = Map.of(attachment);
    _launchTarget = {'route': 'assistant'};
    _notify();
  }

  void openLedgerQuery(Map<String, dynamic> query) {
    clearFilters();
    final month = query['month'] as String?;
    final start = DateTime.tryParse(
      query['start'] as String? ??
          query['period_b_start'] as String? ??
          (month == null ? '' : '$month-01'),
    );
    final end = DateTime.tryParse(
      query['end'] as String? ?? query['period_b_end'] as String? ?? '',
    );
    if (start != null) {
      _selectedMonth = DateTime(start.year, start.month);
      _rangeStart = DateTime(start.year, start.month, start.day);
      _rangeEnd = end == null
          ? DateTime(start.year, start.month + 1)
          : DateTime(end.year, end.month, end.day + 1);
    }
    _categoryFilter = query['category_id'] as int?;
    _keyword = query['keyword'] as String? ?? '';
    _typeFilter = switch (query['type']) {
      'EXPENSE' => TxType.expense,
      'INCOME' => TxType.income,
      _ => null,
    };
    _statusFilter = switch (query['status']) {
      'ALL' => null,
      'PENDING' => TxStatus.pending,
      'IGNORED' => TxStatus.ignored,
      _ => TxStatus.confirmed,
    };
    _includeIgnored = query['status'] == 'ALL';
    _launchTarget = {'route': 'ledger'};
    _notify();
  }

  void setMonth(DateTime value) {
    _selectedMonth = DateTime(value.year, value.month);
    _rangeStart = _rangeEnd = null;
    _notify();
  }

  void setKeyword(String value) {
    _keyword = value;
    _notify();
  }

  void setTypeFilter(TxType? value) {
    _typeFilter = value;
    _notify();
  }

  void setStatusFilter(TxStatus? value) {
    _statusFilter = value;
    _includeIgnored = false;
    _notify();
  }

  void setCategoryFilter(int? value) {
    _categoryFilter = value;
    _notify();
  }

  void clearFilters() {
    _keyword = '';
    _typeFilter = null;
    _statusFilter = null;
    _includeIgnored = false;
    _categoryFilter = null;
    _rangeStart = _rangeEnd = null;
    _notify();
  }

  bool _inMonth(LedgerTransaction transaction, DateTime month) =>
      transaction.occurredAt.year == month.year &&
      transaction.occurredAt.month == month.month;
  LedgerCategory? categoryById(int id) {
    for (final category in categories) {
      if (category.id == id) return category;
    }
    return null;
  }

  List<LedgerCategory> categoriesFor(
    TxType type, {
    bool includeDisabled = false,
  }) =>
      categories
          .where(
            (item) => item.type == type && (includeDisabled || item.enabled),
          )
          .toList()
        ..sort((a, b) => a.sortOrder.compareTo(b.sortOrder));

  List<LedgerTransaction> get filteredTransactions {
    final query = _keyword.trim().toLowerCase();
    return transactions
        .where(
          (transaction) =>
              (_rangeStart == null
                  ? _inMonth(transaction, _selectedMonth)
                  : !transaction.occurredAt.isBefore(_rangeStart!) &&
                        transaction.occurredAt.isBefore(_rangeEnd!)) &&
              (_typeFilter == null || transaction.type == _typeFilter) &&
              (_statusFilter == null
                  ? _includeIgnored || transaction.status != TxStatus.ignored
                  : transaction.status == _statusFilter) &&
              (_categoryFilter == null ||
                  transaction.categoryId == _categoryFilter) &&
              (query.isEmpty ||
                  [
                    transaction.merchant,
                    transaction.note,
                    transaction.accountTag,
                    categoryById(transaction.categoryId)?.name,
                    formatMoney(transaction.amountFen, group: false),
                  ].whereType<String>().any(
                    (text) => text.toLowerCase().contains(query),
                  )),
        )
        .toList()
      ..sort((a, b) {
        final date = b.occurredAt.compareTo(a.occurredAt);
        return date == 0 ? b.id.compareTo(a.id) : date;
      });
  }

  List<LedgerTransaction> get pendingTransactions =>
      transactions.where((item) => item.status == TxStatus.pending).toList()
        ..sort((a, b) => b.occurredAt.compareTo(a.occurredAt));
  LedgerSummary summaryFor(DateTime month) {
    var expense = 0, income = 0, count = 0;
    for (final transaction in transactions) {
      if (transaction.status != TxStatus.confirmed ||
          !_inMonth(transaction, month)) {
        continue;
      }
      if (transaction.isRefund) {
        expense -= transaction.amountFen;
      } else if (transaction.countsAsExpense) {
        expense += transaction.amountFen;
      } else if (transaction.countsAsIncome) {
        income += transaction.amountFen;
      }
      count++;
    }
    return LedgerSummary(expenseFen: expense, incomeFen: income, count: count);
  }

  LedgerSummary get monthSummary => summaryFor(_selectedMonth);
  LedgerSummary get previousMonthSummary =>
      summaryFor(DateTime(_selectedMonth.year, _selectedMonth.month - 1));
  List<CategorySpending> get categorySpending {
    final amounts = <int, int>{}, counts = <int, int>{};
    for (final transaction in transactions) {
      if (transaction.status != TxStatus.confirmed ||
          !transaction.countsAsExpense ||
          !_inMonth(transaction, _selectedMonth)) {
        continue;
      }
      amounts.update(
        transaction.categoryId,
        (value) => value + transaction.amountFen,
        ifAbsent: () => transaction.amountFen,
      );
      counts.update(
        transaction.categoryId,
        (value) => value + 1,
        ifAbsent: () => 1,
      );
    }
    return amounts.entries
        .map(
          (entry) => CategorySpending(
            category:
                categoryById(entry.key) ??
                LedgerCategory(id: entry.key, name: '其他', type: TxType.expense),
            amountFen: entry.value,
            count: counts[entry.key]!,
          ),
        )
        .toList()
      ..sort((a, b) => b.amountFen.compareTo(a.amountFen));
  }

  Map<int, int> get dailySpending {
    final result = <int, int>{};
    for (final transaction in transactions) {
      if (transaction.status == TxStatus.confirmed &&
          transaction.countsAsExpense &&
          _inMonth(transaction, _selectedMonth)) {
        result.update(
          transaction.occurredAt.day,
          (amount) => amount + transaction.amountFen,
          ifAbsent: () => transaction.amountFen,
        );
      }
    }
    return result;
  }

  int get budgetFen => settings['budgetCents'] as int? ?? 0;
  int get budgetRemainingFen => budgetFen - monthSummary.expenseFen;
  double get budgetProgress =>
      budgetFen > 0 ? monthSummary.expenseFen / budgetFen : 0;

  LedgerException _friendly(Object error) =>
      error is LedgerException ? error : const LedgerException('操作未能完成，请稍后重试。');
  void _requireLoaded() {
    if (_snapshot == null) {
      throw LedgerException(_error ?? '账本尚未加载，请稍后重试。', code: 'NOT_READY');
    }
    if (revision < _minimumRevision) {
      throw const LedgerException('账本正在恢复，请等待加载完成。', code: 'STALE_LEDGER');
    }
  }

  void _requireCurrent(int generation) {
    if (_disposed || generation != _generation) {
      throw const LedgerException('账本已经恢复，旧操作已取消。请重新操作。', code: 'STALE_LEDGER');
    }
  }

  Future<T> _operation<T>(String name, Future<T> Function() task) async {
    if (_disposed) {
      throw const LedgerException('页面已关闭，请重新打开。', code: 'DISPOSED');
    }
    if (_busy.contains(name)) {
      throw const LedgerException('这项操作正在进行，请稍候。', code: 'BUSY');
    }
    _busy.add(name);
    final operationGeneration = _generation;
    _error = null;
    _notify();
    try {
      return await task();
    } catch (error) {
      final friendly = _friendly(error);
      if (friendly.code == 'STALE_LEDGER' &&
          operationGeneration == _generation) {
        _invalidateSession();
        await refresh();
      }
      if (!_disposed) _error = friendly.message;
      throw friendly;
    } finally {
      _busy.remove(name);
      _notify();
    }
  }

  Future<Object?> command(
    String method, {
    Map<String, dynamic> arguments = const {},
    bool ledger = false,
    bool refreshAfter = true,
  }) => _operation(method, () async {
    if (ledger) _requireLoaded();
    final generation = _generation;
    if (ledger &&
        arguments.containsKey('revision') &&
        arguments['revision'] != revision) {
      throw const LedgerException('账本已经恢复，请重新打开编辑页面。', code: 'STALE_LEDGER');
    }
    final result = await _repository.invoke(method, {
      if (ledger) 'revision': revision,
      ...arguments,
    });
    if (ledger && method != 'restoreBackup') {
      _requireCurrent(generation);
    }
    if (refreshAfter) await refresh();
    return result;
  });

  void _validateTransaction(LedgerTransaction transaction) {
    if (transaction.amountFen <= 0 ||
        transaction.amountFen > maxTransactionFen) {
      throw const LedgerException(
        '金额必须大于 0 且不超过 9,999,999.99 元。',
        code: 'INVALID_AMOUNT',
      );
    }
    if ((transaction.effectiveKind == 'expense' &&
            transaction.type != TxType.expense) ||
        (const {'income', 'refund'}.contains(transaction.effectiveKind) &&
            transaction.type != TxType.income)) {
      throw const LedgerException('账务用途与资金方向不一致，请重新选择。');
    }
    final category = categoryById(transaction.categoryId);
    if (category == null || category.type != transaction.type) {
      throw const LedgerException('请选择与收支类型一致的分类。', code: 'INVALID_CATEGORY');
    }
  }

  void _requireReceiptDate(LedgerTransaction transaction) {
    if (transaction.recognitionMetadata['receiptDateUnconfirmed'] == true) {
      throw const LedgerException('请先打开本地凭证草稿，选择实际交易日期。');
    }
  }

  Future<int> saveTransaction(
    LedgerTransaction transaction, {
    int? expectedRevision,
    bool confirmPending = false,
  }) async {
    _validateTransaction(transaction);
    _requireReceiptDate(transaction);
    final previous = transactions
        .where((item) => item.id == transaction.id)
        .firstOrNull;
    if (transaction.id == 0 &&
        transaction.effectiveKind == 'unknown' &&
        const {
          TxSource.aiText,
          TxSource.aiVoice,
          TxSource.aiImage,
        }.contains(transaction.source)) {
      throw const LedgerException('请先核对草稿并选择账务用途，再保存。');
    }
    if (confirmPending && transaction.effectiveKind == 'unknown') {
      throw const LedgerException('请先选择这笔资金变动的账务用途。');
    }
    final result = stringMap(
      await command(
        'saveTransaction',
        arguments: {
          ...transaction.toMap(),
          'revision': ?expectedRevision,
          if (confirmPending) 'confirmPending': true,
        },
        ledger: true,
      ),
    );
    if (transaction.merchant?.isNotEmpty == true &&
        (previous == null ||
            previous.merchant != transaction.merchant ||
            previous.type != transaction.type ||
            previous.categoryId != transaction.categoryId ||
            previous.effectiveKind != transaction.effectiveKind ||
            previous.accountTag != transaction.accountTag)) {
      await _rememberCategory(transaction);
    }
    return integer(result['id'], 'id');
  }

  Future<void> deleteTransaction(int id) async {
    await command('deleteTransaction', arguments: {'id': id}, ledger: true);
  }

  Future<void> setTransactionStatus(int id, TxStatus status) async {
    if (status == TxStatus.confirmed &&
        transactions.any(
          (tx) => tx.id == id && tx.effectiveKind == 'unknown',
        )) {
      throw const LedgerException('请先选择这笔资金变动的账务用途。');
    }
    if (status == TxStatus.pending) {
      throw const LedgerException('只能确认或忽略待确认账单。');
    }
    await command(
      'setTransactionStatus',
      arguments: {'id': id, 'status': status.wireValue},
      ledger: true,
    );
  }

  Future<void> _changePending(Iterable<int>? ids, TxStatus status) =>
      _operation('batchStatus', () async {
        _requireLoaded();
        final selected = ids?.toSet();
        final pending = pendingTransactions
            .where(
              (item) =>
                  (selected == null || selected.contains(item.id)) &&
                  (status != TxStatus.confirmed ||
                      item.effectiveKind != 'unknown'),
            )
            .toList();
        final generation = _generation;
        final currentRevision = revision;
        try {
          for (final transaction in pending) {
            _requireCurrent(generation);
            await _repository.invoke('setTransactionStatus', {
              'id': transaction.id,
              'status': status.wireValue,
              'revision': currentRevision,
            });
          }
        } finally {
          await refresh();
        }
      });
  Future<void> confirmPending([Iterable<int>? ids]) =>
      _changePending(ids, TxStatus.confirmed);
  Future<void> ignorePending([Iterable<int>? ids]) =>
      _changePending(ids, TxStatus.ignored);

  Future<List<LedgerTransaction>> parseText(
    String text,
  ) => _operation('parseText', () async {
    _requireLoaded();
    if (text.trim().isEmpty) throw const LedgerException('先写下需要记账的内容。');
    final generation = _generation;
    final draftGeneration = _draftGeneration;
    final result = stringMap(
      await _repository.invoke('parseText', {
        'revision': revision,
        'text': text.trim(),
      }),
    );
    _requireCurrent(generation);
    if (draftGeneration != _draftGeneration) {
      throw const LedgerException('这次录入已取消。', code: 'CANCELLED');
    }
    if (result['error'] is String && (result['error'] as String).isNotEmpty) {
      throw LedgerException(result['error'] as String);
    }
    final parsed = (result['transactions'] as List? ?? [])
        .map((item) => LedgerTransaction.fromMap(stringMap(item), draft: true))
        .toList();
    if (parsed.isEmpty) throw const LedgerException('没有识别到明确的账单，请补充金额和用途。');
    for (final item in parsed) {
      _validateTransaction(item);
    }
    _drafts = [..._drafts, ...parsed];
    return List.unmodifiable(parsed);
  });
  Future<List<LedgerTransaction>> recognizeInput({
    String text = '',
    TxSource source = TxSource.aiText,
    Map<String, dynamic>? image,
    String? sessionId,
  }) async {
    final parsed = await _operation('parseText', () async {
      _requireLoaded();
      final generation = _generation, draftGeneration = _draftGeneration;
      final result = stringMap(
        await _repository.invoke('recognizeInput', {
          'revision': revision,
          'text': text,
          'source': source.wireValue,
          'operationId':
              sessionId ??
              RecognitionProtocol.identity(
                '${_clock().microsecondsSinceEpoch}:$text',
              ),
          ...?image,
        }),
      );
      _requireCurrent(generation);
      if (draftGeneration != _draftGeneration) {
        throw const LedgerException('这次录入已取消。', code: 'CANCELLED');
      }
      recognitionQuestions = (result['questions'] as List? ?? [])
          .whereType<String>()
          .toList();
      if (result['error'] is String) {
        throw LedgerException(result['error'] as String);
      }
      final items = (result['transactions'] as List? ?? []).map((row) {
        final tx = LedgerTransaction.fromMap(stringMap(row), draft: true);
        return sessionId == null
            ? tx
            : tx.copyWith(
                metadataJson: jsonEncode({
                  ...tx.recognitionMetadata,
                  'requestSessionId': sessionId,
                }),
              );
      }).toList();
      for (final item in items) {
        _validateTransaction(item);
      }
      if (sessionId != null) {
        final protected = [..._drafts, ...transactions]
            .where(
              (t) =>
                  t.recognitionMetadata['requestSessionId'] == sessionId &&
                  (t.id > 0 || t.recognitionMetadata['reviewed'] == true),
            )
            .map((t) => t.recognitionMetadata['operationId'])
            .whereType<String>()
            .toSet();
        _drafts.removeWhere(
          (t) =>
              t.recognitionMetadata['requestSessionId'] == sessionId &&
              t.recognitionMetadata['reviewed'] != true,
        );
        items.removeWhere(
          (t) => protected.contains(t.recognitionMetadata['operationId']),
        );
      }
      items.removeWhere(
        (t) => _undoneRecognitionOperations.contains(
          t.recognitionMetadata['operationId'],
        ),
      );
      _drafts = [..._drafts, ...items];
      recognitionNotice = items.isNotEmpty
          ? '已整理 ${items.length} 笔，确认后即可保存。'
          : recognitionQuestions.isNotEmpty
          ? '再补充下面的信息，就能继续整理账单。'
          : '这次没有新的账单，已保存或已核对的记录已保留。';
      return (
        items: items,
        eligible: items.where((item) {
          final eligible = item.recognitionMetadata['autoSaveEligible'];
          return eligible == true ||
              (eligible == null && result['autoSaveEligible'] == true);
        }).toList(),
      );
    });
    if (settings['activeInputAutoSaveEnabled'] != false &&
        parsed.eligible.isNotEmpty) {
      final count = await saveDrafts(
        items: parsed.eligible,
        rememberRecognitionSave: true,
      );
      recognitionNotice = recognitionQuestions.isEmpty
          ? '已记 $count 笔，可修改或撤销。'
          : '已记 $count 笔，其余信息可继续补充。';
      _notify();
    }
    return parsed.items;
  }

  void addImportedDrafts(List<LedgerTransaction> items) {
    if (isBusy) throw const LedgerException('请等待当前操作完成。');
    for (final item in items) {
      _validateTransaction(item);
    }
    final existing = {
      ...transactions.map((t) => t.recognitionMetadata['operationId']),
      ..._drafts.map((t) => t.recognitionMetadata['operationId']),
    };
    _drafts = [
      ..._drafts,
      ...items.where(
        (item) => !existing.contains(item.recognitionMetadata['operationId']),
      ),
    ];
    recognitionNotice = '导入预览已加入待核对账单。';
    _notify();
  }

  Future<void> addLocalReceiptDraft(
    Map<String, dynamic> receipt, {
    int? expectedRevision,
  }) async {
    _requireLoaded();
    if (isBusy) throw const LedgerException('请等待当前操作完成。');
    await _operation('localReceiptDraft', () async {
      if (expectedRevision != null && expectedRevision != revision) {
        throw const LedgerException('账本已恢复，请重新选择截图。', code: 'STALE_LEDGER');
      }
      final generation = _generation;
      await loadAgentState();
      _requireCurrent(generation);
      if (_error != null) throw LedgerException(_error!);
      if (pendingRecognition && !_localReceiptRun) {
        throw const LedgerException('请先处理当前待核对账单，再打开新的本地凭证。');
      }
      final type = TxType.fromWire(receipt['type']);
      final available = categoriesFor(type);
      if (available.isEmpty) throw const LedgerException('请先设置可用的收支分类。');
      final category = available.firstWhere(
        (item) => item.name == '其他' || item.name == '其他收入',
        orElse: () => available.first,
      );
      final occurredAt = receipt['occurredAt'] as int?;
      final metadata = stringMap(receipt['metadata']);
      final operationId = metadata['operationId'];
      if (operationId is! String || operationId.isEmpty) {
        throw const LedgerException('无法确认凭证身份，请重新选择截图。');
      }
      if ([
        ..._drafts,
        ...transactions,
      ].any((item) => item.recognitionMetadata['operationId'] == operationId)) {
        throw const LedgerException('这张图片已经生成草稿或保存到账本。');
      }
      final runId = pendingRecognition && _localReceiptRun
          ? _recognitionRunId!
          : agentId();
      final today = _clock();
      final draft = LedgerTransaction(
        amountFen: integer(receipt['amount'], '金额'),
        type: type,
        categoryId: category.id,
        occurredAt: occurredAt == null
            ? DateTime(today.year, today.month, today.day)
            : DateTime.fromMillisecondsSinceEpoch(occurredAt),
        merchant: receipt['merchant'] as String?,
        note: receipt['note'] as String?,
        accountTag: receipt['accountTag'] as String?,
        businessKind: receipt['businessKind'] as String?,
        source: TxSource.aiImage,
        status: TxStatus.pending,
        metadataJson: jsonEncode({
          ...metadata,
          'requestSessionId': runId,
          'timePrecision': occurredAt == null ? 'date' : 'exact',
          if (occurredAt == null) 'receiptDateUnconfirmed': true,
        }),
      );
      _validateTransaction(draft);
      _recognitionRunId = runId;
      _localReceiptRun = true;
      _recognitionText = '';
      _recognitionImage = null;
      _recognitionAttachments = [];
      _drafts = [..._drafts, draft];
      recognitionNotice = '已在本机读取凭证，核对后保存。';
      await _persistAssistantRecognition();
    });
  }

  void updateDraft(int index, LedgerTransaction value) {
    if (busyFor('saveTransactions')) return;
    _validateTransaction(value);
    final prior = _drafts[index];
    _drafts[index] = value.copyWith(
      id: 0,
      metadataJson: jsonEncode({
        ...prior.recognitionMetadata,
        ...value.recognitionMetadata,
        'reviewed': true,
        'questions': <String>[],
      }),
    );
    if ((prior.categoryId != value.categoryId ||
            prior.effectiveKind != value.effectiveKind ||
            prior.accountTag != value.accountTag) &&
        value.merchant?.isNotEmpty == true) {
      unawaited(_rememberCategory(value));
    }
    _notify();
    _saveRecognitionLater();
  }

  void removeDraft(int index) {
    if (busyFor('saveTransactions')) return;
    _drafts.removeAt(index);
    _notify();
    _saveRecognitionLater();
  }

  void clearDrafts() {
    if (busyFor('saveTransactions')) return;
    _draftGeneration++;
    _drafts = [];
    recognitionQuestions = [];
    recognitionNotice = null;
    _undoneRecognitionOperations.clear();
    _notify();
    _saveRecognitionLater();
  }

  Future<void> _rememberCategory(LedgerTransaction item) async {
    try {
      final raw = settings['recognitionPreferencesJson'] as String? ?? '[]';
      final preferences = (jsonDecode(raw) as List)
          .whereType<Map>()
          .map((v) => Map<String, dynamic>.from(v))
          .toList();
      preferences.removeWhere(
        (p) =>
            p['merchant'] == item.merchant && p['type'] == item.type.wireValue,
      );
      preferences.add({
        'merchant': item.merchant,
        'type': item.type.wireValue,
        'category': categoryById(item.categoryId)?.name,
        'businessKind': item.effectiveKind,
        'accountTag': item.accountTag,
      });
      await updateSettings({
        'recognitionPreferencesJson': jsonEncode(
          preferences
              .skip((preferences.length - 100).clamp(0, 100000))
              .toList(),
        ),
      });
    } catch (_) {
      /* A preference write must never discard the corrected draft. */
    }
  }

  Future<int> saveDrafts({
    List<LedgerTransaction>? items,
    int? expectedRevision,
    bool rememberRecognitionSave = false,
  }) => _operation('saveTransactions', () async {
    _requireLoaded();
    if (expectedRevision != null && expectedRevision != revision) {
      throw const LedgerException('账本已经恢复，请重新录入账单。', code: 'STALE_LEDGER');
    }
    final saving = List<LedgerTransaction>.of(items ?? _drafts);
    if (saving.isEmpty) throw const LedgerException('没有待保存的账单。');
    if (isParsing) throw const LedgerException('请等待解析完成后再保存。');
    for (final transaction in saving) {
      _validateTransaction(transaction);
      _requireReceiptDate(transaction);
      if (transaction.effectiveKind == 'unknown' &&
          transaction.source != TxSource.billImport) {
        throw const LedgerException('请先打开待分类草稿，选择账务用途后再保存。');
      }
    }
    final generation = _generation;
    final currentRevision = revision;
    final existingIds = transactions.map((item) => item.id).toSet();
    final savedIds = <int>{};
    if (rememberRecognitionSave) _lastRecognitionSave = null;
    void rememberSave() {
      if (!rememberRecognitionSave ||
          generation != _generation ||
          currentRevision != revision) {
        return;
      }
      final inserted = transactions
          .where((item) => savedIds.contains(item.id))
          // New rows start at version zero. A concurrent edit before refresh
          // must make undo fail rather than become part of this receipt.
          .map(
            (item) =>
                LedgerTransaction.fromMap({...item.toMap(), 'editVersion': 0}),
          )
          .toList();
      if (inserted.isNotEmpty) {
        _lastRecognitionSave = RecognitionSaveReceipt(
          revision: currentRevision,
          transactions: inserted,
        );
      }
    }

    var saved = 0;
    try {
      while (saved < saving.length) {
        _requireCurrent(generation);
        final batch = saving.sublist(
          saved,
          (saved + 100).clamp(0, saving.length),
        );
        final result = await _repository.invoke('saveTransactions', {
          'revision': currentRevision,
          'transactions': batch
              .map((item) => item.copyWith(id: 0).toMap())
              .toList(),
        });
        if (result is Map && result['ids'] is List) {
          savedIds.addAll(
            (result['ids'] as List).whereType<int>().where(
              (id) => !existingIds.contains(id),
            ),
          );
        }
        saved += batch.length;
        _requireCurrent(generation);
        _drafts = _drafts.where((draft) => !batch.contains(draft)).toList();
        _notify();
      }
    } catch (error) {
      try {
        await refresh();
      } catch (_) {
        /* Keep the original write error. */
      }
      rememberSave();
      if (saved > 0) {
        throw LedgerException(
          '已保存 $saved 笔，剩余 ${saving.length - saved} 笔尚未保存。${error is LedgerException ? error.message : '请重试剩余账单。'}',
          code: error is LedgerException ? error.code : 'PARTIAL_SAVE',
        );
      }
      rethrow;
    }
    await refresh();
    rememberSave();
    if (!busyFor('assistantInput')) await _persistAssistantRecognition();
    return saved;
  });

  Future<int> undoLastRecognitionSave() {
    final receipt = _lastRecognitionSave;
    if (receipt != null && _repository is AgentSessionRepository) {
      final run = _messages.where((m) => m.role == 'assistant').where((m) {
        final stored = _assistantReceipt(m.runId);
        return stored != null && receipt.ids.every(stored.ids.contains);
      }).lastOrNull;
      if (run != null) {
        return _undoAssistantRun(run.runId, transactionIds: receipt.ids);
      }
    }
    return _operation('undoRecognitionSave', () async {
      _requireLoaded();
      final receipt = _lastRecognitionSave;
      if (receipt == null) throw const LedgerException('没有可撤销的自动记账。');
      if (receipt.revision != revision) {
        throw const LedgerException('账本已恢复，这次记账不能撤销。', code: 'STALE_LEDGER');
      }
      final generation = _generation;
      final result = stringMap(
        await _repository.invoke('undoRecognitionSave', {
          'revision': receipt.revision,
          'transactions': [
            for (final item in receipt.transactions)
              {
                'id': item.id,
                'editVersion': item.editVersion,
                'operationId': item.recognitionMetadata['operationId'],
              },
          ],
        }),
      );
      _requireCurrent(generation);
      final count = integer(result['count'], 'count');
      _undoneRecognitionOperations.addAll(
        receipt.transactions
            .map((item) => item.recognitionMetadata['operationId'])
            .whereType<String>(),
      );
      if (identical(_lastRecognitionSave, receipt)) {
        _lastRecognitionSave = null;
      }
      await refresh();
      recognitionNotice = '已撤销 $count 笔自动记账。';
      await _persistAssistantRecognition();
      return count;
    });
  }

  Future<void> loadAgentState() async {
    if (_snapshot == null ||
        _disposed ||
        _repository is! AgentSessionRepository) {
      return;
    }
    final generation = _generation;
    try {
      final state = await (_repository as AgentSessionRepository).agentState();
      if (!_disposed && generation == _generation) _adoptAgentState(state);
    } on LedgerException catch (error) {
      // Older test/platform adapters may not expose Agent storage yet.
      if (error.code != 'TEST_UNSUPPORTED' &&
          error.code != 'PLATFORM_UNAVAILABLE') {
        _error = error.message;
      }
    } catch (_) {
      _error = '对话记录暂时无法读取，请重试。';
    }
    _notify();
  }

  void _adoptAgentState(Map<String, dynamic> state) {
    if (state['session'] == null) return;
    _agentSession = stringMap(state['session']);
    agentSessions = (state['sessions'] as List? ?? []).map(stringMap).toList();
    agentMemories = (state['memories'] as List? ?? []).map(stringMap).toList();
    agentConfig = stringMap(state['config'] ?? {});
    _messages = presentAgentSession(_agentSession);
    for (final state
        in _messages
            .expand((m) => m.parts)
            .where((p) => p['type'] == 'recognition_state')) {
      _undoneRecognitionOperations.addAll(
        (state['undoneOperations'] as List? ?? []).whereType<String>(),
      );
    }
    _drafts.removeWhere(
      (t) => _undoneRecognitionOperations.contains(
        t.recognitionMetadata['operationId'],
      ),
    );
    if (const {
      'failed',
      'cancelled',
      'interrupted',
    }.contains(_agentSession['status'])) {
      final users = (_agentSession['messages'] as List? ?? [])
          .whereType<Map>()
          .where((m) => m['role'] == 'user' && m['internal'] != true);
      _retryQuestion = users.lastOrNull?['content'] as String?;
    } else {
      _retryQuestion = null;
    }
    if (_recognitionRunId == null) _restoreAssistantRecognition();
  }

  void _restoreAssistantRecognition() {
    final parts = _messages.expand((m) => m.parts);
    final saved = parts
        .where((p) => p['type'] == 'recognition_state')
        .lastOrNull;
    if (saved == null) return;
    _undoneRecognitionOperations.addAll(
      (saved['undoneOperations'] as List? ?? []).whereType<String>(),
    );
    final existing = transactions
        .map((t) => t.recognitionMetadata['operationId'])
        .toSet();
    final restored = (saved['drafts'] as List? ?? [])
        .whereType<Map>()
        .map((m) => LedgerTransaction.fromMap(stringMap(m), draft: true))
        .where(
          (t) =>
              !existing.contains(t.recognitionMetadata['operationId']) &&
              !_undoneRecognitionOperations.contains(
                t.recognitionMetadata['operationId'],
              ),
        )
        .toList();
    final questions = (saved['questions'] as List? ?? [])
        .whereType<String>()
        .toList();
    if (restored.isEmpty && questions.isEmpty) return;
    _recognitionRunId = saved['runId'] as String;
    _localReceiptRun = saved['localOnly'] == true;
    _recognitionText = saved['text'] as String? ?? '';
    _recognitionAttachments = _localReceiptRun
        ? []
        : (saved['attachments'] as List? ?? [])
              .whereType<Map>()
              .map(stringMap)
              .toList();
    _drafts = [
      ..._drafts.where(
        (t) => t.recognitionMetadata['requestSessionId'] == null,
      ),
      ...restored,
    ];
    recognitionQuestions = questions;
  }

  RecognitionSaveReceipt? _assistantReceipt(String runId) {
    final parts = _messages
        .where((m) => m.runId == runId)
        .expand((m) => m.parts);
    final rows = <int, LedgerTransaction>{};
    for (final part in parts) {
      if (part['type'] == 'receipt' && part['status'] != 'undone') {
        final undone = (part['undoneTransactionIds'] as List? ?? []).toSet();
        for (final raw
            in (part['transactions'] as List? ?? []).whereType<Map>()) {
          final tx = LedgerTransaction.fromMap(stringMap(raw));
          if (!undone.contains(tx.id)) rows[tx.id] = tx;
        }
      } else if (part['type'] == 'action' &&
          part['kind'] == 'transaction' &&
          part['status'] == 'applied' &&
          part['transactionId'] is int) {
        final payload = stringMap(part['payload']);
        if (payload['before'] != null) continue;
        final tx = LedgerTransaction.fromMap({
          ...stringMap(payload['transaction']),
          'id': part['transactionId'],
          'editVersion': 0,
        });
        rows[tx.id] = tx;
      }
    }
    return rows.isEmpty
        ? null
        : RecognitionSaveReceipt(
            revision: revision,
            transactions: rows.values.toList(),
          );
  }

  bool canUndoAssistantRun(String runId) {
    if (isBusy) return false;
    final receipt = _assistantReceipt(runId);
    return receipt != null &&
        receipt.transactions.every((expected) {
          final current = _transactionIndex[expected.id];
          final operation = expected.recognitionMetadata['operationId'];
          return operation is String &&
              current != null &&
              current.editVersion == expected.editVersion &&
              current.recognitionMetadata['operationId'] == operation;
        });
  }

  Future<int> undoAssistantRun(String runId) => _undoAssistantRun(runId);

  Future<int> _undoAssistantRun(
    String runId, {
    List<int>? transactionIds,
  }) async {
    final original = _assistantReceipt(runId);
    final selected =
        original?.transactions
            .where(
              (t) => transactionIds == null || transactionIds.contains(t.id),
            )
            .toList() ??
        [];
    if (isBusy ||
        selected.isEmpty ||
        !selected.every((expected) {
          final current = _transactionIndex[expected.id];
          return current != null &&
              current.editVersion == expected.editVersion &&
              current.recognitionMetadata['operationId'] ==
                  expected.recognitionMetadata['operationId'];
        })) {
      throw const LedgerException('这次账单已被修改、删除或撤销，不能再次撤销。');
    }
    if (_repository is! AgentSessionRepository) {
      _lastRecognitionSave = _assistantReceipt(runId);
      return undoLastRecognitionSave();
    }
    return _operation('undoRecognitionSave', () async {
      final generation = _generation;
      final result = stringMap(
        await _repository.invoke('agentUndoRun', {
          'revision': revision,
          'runId': runId,
          'transactionIds': ?transactionIds,
        }),
      );
      _requireCurrent(generation);
      _adoptAgentState(result);
      _lastRecognitionSave = null;
      await refresh();
      return integer(result['count'], 'count');
    });
  }

  Future<void> sendAssistantInput(
    String text, {
    Map<String, dynamic>? attachment,
    bool isSupplement = false,
    String source = 'AI_TEXT',
  }) async {
    final image = attachment ?? _assistantAttachment;
    if (isSupplement && _localReceiptRun) {
      throw const LedgerException('本地凭证请在待核对草稿中修改。');
    }
    if (image != null && settings['cloudImageParsingEnabled'] != true) {
      throw const LedgerException('请先开启云端图片识别，或选择本地核对凭证。');
    }
    if (_assistantDraft.trim() == text.trim()) setAssistantDraft('');
    if (identical(image, _assistantAttachment)) _assistantAttachment = null;
    if (image == null &&
        pendingRecognition &&
        LedgerAgentRuntime.isCancellationInput(text) &&
        (isSupplement ||
            _messages.lastOrNull?.runId == _recognitionRunId ||
            _explicitRecognitionCancellation.hasMatch(text.trim()))) {
      await cancelAssistantRecognition();
      return;
    }
    if (image == null && !(isSupplement && pendingRecognition)) {
      await _ask(text, retry: false, source: source);
      return;
    }
    await _operation('assistantInput', () async {
      _requireLoaded();
      final generation = _generation;
      if (!isSupplement) {
        if (pendingRecognition) {
          throw const LedgerException('请先处理这次待核对账单，再识别新的凭证。');
        }
        _recognitionRunId = agentId();
        _localReceiptRun = false;
        _recognitionText = text.trim();
        _recognitionAttachments = [];
        _recognitionImage = null;
        if (image != null) {
          _recognitionImage = {
            'imageBase64': image['imageBase64'] ?? image['base64'],
            'mimeType': image['mimeType'],
          };
          _recognitionAttachments = [
            await _attachmentStore.save(_recognitionImage!),
          ];
        }
      } else {
        if (text.trim().isEmpty) throw const LedgerException('写一句补充信息就可以。');
        _recognitionText += '\n补充：${text.trim()}';
      }
      if (_recognitionImage == null && _recognitionAttachments.isNotEmpty) {
        _recognitionImage = await _attachmentStore.read(
          _recognitionAttachments.first,
        );
      }
      _requireCurrent(generation);
      try {
        await recognizeInput(
          text: _recognitionText,
          source: _recognitionImage == null
              ? TxSource.fromWire(source)
              : TxSource.aiImage,
          image: _recognitionImage,
          sessionId: _recognitionRunId,
        );
        _requireCurrent(generation);
        await _persistAssistantRecognition();
      } catch (error) {
        _requireCurrent(generation);
        if (_drafts.isNotEmpty || _lastRecognitionSave != null) {
          await _persistAssistantRecognition();
        }
        rethrow;
      }
    });
  }

  Future<void> cancelAssistantRecognition() =>
      _operation('assistantInput', () async {
        if (!pendingRecognition) return;
        final generation = _generation;
        final runId = _recognitionRunId;
        await _persistAssistantRecognition(cancelled: true);
        _requireCurrent(generation);
        _drafts.removeWhere(
          (tx) => tx.recognitionMetadata['requestSessionId'] == runId,
        );
        recognitionQuestions = [];
        recognitionNotice = null;
        _recognitionRunId = null;
        _localReceiptRun = false;
        _recognitionText = '';
        _recognitionImage = null;
        _recognitionAttachments = [];
      });

  Future<void> _persistAssistantRecognition({bool cancelled = false}) async {
    final runId = _recognitionRunId;
    if (runId == null) return;
    final generation = _generation;
    final saved = transactions
        .where((t) => t.recognitionMetadata['requestSessionId'] == runId)
        .toList();
    final ownedDrafts = cancelled
        ? const <LedgerTransaction>[]
        : _drafts
              .where((t) => t.recognitionMetadata['requestSessionId'] == runId)
              .toList();
    final questions = cancelled ? const <String>[] : recognitionQuestions;
    final receipt = {
      'type': 'receipt',
      'title': saved.isEmpty
          ? recognitionNotice ?? '识别结果'
          : '已记下 ${saved.length} 笔',
      'status': saved.isEmpty ? 'complete' : 'applied',
      'transactions': saved
          .map((t) => {...t.toMap(), 'editVersion': 0})
          .toList(),
    };
    final pending = ownedDrafts.isNotEmpty || questions.isNotEmpty;
    final answer = cancelled
        ? '已取消这次识别。'
        : [
            if (saved.isNotEmpty) '已记下 ${saved.length} 笔。',
            if (ownedDrafts.isNotEmpty) '还有 ${ownedDrafts.length} 笔待核对。',
            ...questions,
            if (!pending && saved.isEmpty) recognitionNotice ?? '这次没有新增账单。',
          ].join('\n');
    final args = {
      'revision': revision,
      'runId': runId,
      'question': _localReceiptRun
          ? '在本机核对截图凭证'
          : _recognitionText.isEmpty
          ? '识别这张凭证中的收支'
          : _recognitionText,
      'source': _localReceiptRun || _recognitionAttachments.isNotEmpty
          ? 'AI_IMAGE'
          : 'AI_TEXT',
      'answer': answer,
      'status': pending ? 'awaiting_recognition' : 'completed',
      if (_recognitionAttachments.isNotEmpty)
        'attachment': _recognitionAttachments.first,
      'parts': [
        if (saved.isNotEmpty || !cancelled) receipt,
        {
          'type': 'recognition_state',
          'runId': runId,
          if (_localReceiptRun) 'localOnly': true,
          'text': _recognitionText,
          'attachments': _recognitionAttachments,
          'drafts': ownedDrafts
              .map(
                (t) => {
                  ...t.toMap(),
                  if (_localReceiptRun) 'status': t.status.wireValue,
                },
              )
              .toList(),
          'questions': List<String>.of(questions),
          'undoneOperations': _undoneRecognitionOperations.toList(),
        },
      ],
    };
    final previous = _recognitionPersistence;
    final operation = () async {
      try {
        await previous;
      } catch (_) {
        /* A newer snapshot can still be saved. */
      }
      _requireCurrent(generation);
      if (_repository is AgentSessionRepository) {
        final result = stringMap(
          await _repository.invoke('agentAppendInput', args),
        );
        _requireCurrent(generation);
        _adoptAgentState(result);
      } else {
        final updated = [
          ChatMessage(
            id: '$runId:user',
            runId: runId,
            role: 'user',
            text: args['question'] as String,
            parts: _recognitionAttachments,
          ),
          ChatMessage(
            id: '$runId:answer',
            runId: runId,
            role: 'assistant',
            text: answer,
            parts: (args['parts'] as List).cast<Map<String, dynamic>>(),
          ),
        ];
        final index = _messages.indexWhere((m) => m.runId == runId);
        if (index == -1) {
          _messages = [..._messages, ...updated];
        } else {
          final count = _messages.where((m) => m.runId == runId).length;
          _messages.replaceRange(index, index + count, updated);
        }
      }
      _notify();
    }();
    _recognitionPersistence = operation;
    await operation;
  }

  void _saveRecognitionLater() {
    if (_recognitionRunId == null || busyFor('assistantInput')) return;
    unawaited(
      _persistAssistantRecognition().catchError((Object error) {
        if (!_disposed) {
          _error = '账单状态已更新，对话回执未能保存：${_friendly(error).message}';
          _notify();
        }
      }),
    );
  }

  Future<void> agentCommand(
    String method, [
    Map<String, dynamic> args = const {},
  ]) => _operation('agentSession', () async {
    if (isAsking) throw const LedgerException('请先停止当前任务。');
    await _recognitionPersistence;
    final generation = _generation;
    final result = stringMap(
      await _repository.invoke(method, {'revision': revision, ...args}),
    );
    _requireCurrent(generation);
    if (method == 'agentNewSession' ||
        method == 'agentSelectSession' ||
        method == 'agentDeleteSession') {
      _recognitionRunId = null;
      _localReceiptRun = false;
      _recognitionText = '';
      _recognitionImage = null;
      _recognitionAttachments = [];
      _drafts = _drafts
          .where((t) => t.recognitionMetadata['requestSessionId'] == null)
          .toList();
      recognitionQuestions = [];
    }
    _adoptAgentState(result);
    _notify();
  });
  Future<void> stopAgent() async {
    await _repository.invoke('cancelAgent', const {});
  }

  Future<void> resolveAgentAction(String id, bool approve) =>
      _operation('ask', () async {
        final generation = _generation;
        await _recognitionPersistence;
        _requireCurrent(generation);
        agentProgress = {};
        _activeAssistantRun = null;
        try {
          final result = stringMap(
            await _repository.invoke('agentResolveAction', {
              'revision': revision,
              'actionId': id,
              'approve': approve,
            }),
          );
          _requireCurrent(generation);
          _adoptAgentState(result);
        } finally {
          await refresh();
          await loadAgentState();
        }
      });

  Future<void> clearConversation() async {
    if (isAsking) return;
    _messages = [];
    _retryQuestion = null;
    _notify();
    if (_agentSession['id'] != null) {
      await agentCommand('agentDeleteSession', {'id': _agentSession['id']});
    }
  }

  Future<ChatMessage> ask(String question) => _ask(question, retry: false);
  Future<ChatMessage> retryAsk() {
    final question = _retryQuestion;
    if (question == null) {
      return Future.error(const LedgerException('没有需要重试的问题。'));
    }
    return _ask(question, retry: true);
  }

  Future<ChatMessage> _ask(
    String question, {
    required bool retry,
    String source = 'AI_TEXT',
  }) => _operation('ask', () async {
    _requireLoaded();
    final text = question.trim();
    if (text.isEmpty) throw const LedgerException('请输入想了解的账本问题。');
    final generation = _generation;
    await _recognitionPersistence;
    _requireCurrent(generation);
    if (!pendingRecognition) {
      _recognitionRunId = null;
      _localReceiptRun = false;
      _recognitionText = '';
      _recognitionImage = null;
      _recognitionAttachments = [];
    }
    final prior = List<ChatMessage>.of(_messages);
    final existingIds = transactions.map((item) => item.id).toSet();
    _messages = [
      ...prior,
      if (!retry)
        ChatMessage(
          id: agentId(),
          runId: 'local:${agentId()}',
          role: 'user',
          text: text,
        ),
    ];
    _retryQuestion = null;
    agentProgress = {};
    _runningReasoningOptions = _configuredReasoningOptions;
    _activeAssistantRun = null;
    _liveMessageId = null;
    _notify();
    try {
      final result = stringMap(
        await _repository.invoke('ask', {
          'revision': revision,
          'question': text,
          'source': source,
          if (retry) 'retry': true,
        }),
      );
      _requireCurrent(generation);
      final autoSavedIds = (result['autoSavedIds'] as List? ?? [])
          .whereType<int>()
          .where((id) => !existingIds.contains(id))
          .toSet();
      if (autoSavedIds.isNotEmpty) {
        await refresh();
        _requireCurrent(generation);
        final inserted = transactions
            .where((item) => autoSavedIds.contains(item.id))
            .toList();
        if (inserted.isNotEmpty) {
          _lastRecognitionSave = RecognitionSaveReceipt(
            revision: revision,
            transactions: inserted
                .map(
                  (item) => LedgerTransaction.fromMap({
                    ...item.toMap(),
                    'editVersion': 0,
                  }),
                )
                .toList(),
          );
        }
      }
      final response = ChatMessage(
        role: 'assistant',
        text: result['answer'] as String? ?? '暂未收到回答，请重试。',
        isError: result['isError'] as bool? ?? false,
        chartData: result['chartData'] == null
            ? null
            : InsightChart.fromMap(stringMap(result['chartData'])),
      );
      if (result['session'] != null) {
        _adoptAgentState(result);
      } else {
        _settleAssistantPreview(response);
      }
      _retryQuestion =
          response.isError ||
              const {
                'failed',
                'cancelled',
                'interrupted',
              }.contains(_agentSession['status'])
          ? text
          : null;
      return response;
    } catch (error) {
      _requireCurrent(generation);
      final response = ChatMessage(
        role: 'assistant',
        text: _friendly(error).message,
        isError: true,
      );
      _settleAssistantPreview(response);
      _retryQuestion = text;
      rethrow;
    }
  });

  void _settleAssistantPreview(ChatMessage response) {
    final index = _messages.lastIndexWhere(
      (m) =>
          m.role == 'assistant' &&
          (m.isStreaming ||
              _activeAssistantRun != null && m.runId == _activeAssistantRun),
    );
    if (index < 0) {
      _messages.add(
        response.copyWith(
          id: agentId(),
          runId: _activeAssistantRun ?? _messages.lastOrNull?.runId ?? '',
          status: response.isError ? 'error' : 'complete',
        ),
      );
      return;
    }
    final prior = _messages[index];
    _messages[index] = prior.copyWith(
      text: response.text,
      isError: response.isError,
      isStreaming: false,
      status: response.isError ? 'error' : 'complete',
      parts: [
        ...prior.parts,
        if (response.isError && prior.text.isNotEmpty)
          {'type': 'interrupted_text', 'text': prior.text},
      ],
    );
  }

  Future<int> saveCategory(LedgerCategory category) async {
    final result = stringMap(
      await command('saveCategory', arguments: category.toMap(), ledger: true),
    );
    return integer(result['id'], 'id');
  }

  Future<void> deleteCategory(int id) async {
    await command('deleteCategory', arguments: {'id': id}, ledger: true);
  }

  Future<int> saveRule(ParseRule rule) async {
    final result = stringMap(
      await command('saveRule', arguments: rule.toMap(), ledger: true),
    );
    return integer(result['id'], 'id');
  }

  Future<void> deleteRule(int id) async {
    await command('deleteRule', arguments: {'id': id}, ledger: true);
  }

  Future<void> resetRules() async {
    await command('resetRules', ledger: true);
  }

  Future<void> generateReport(String yearMonth) async {
    if (!RegExp(r'^\d{4}-(0[1-9]|1[0-2])$').hasMatch(yearMonth)) {
      throw const LedgerException('请选择有效的报表月份。');
    }
    final now = _clock();
    final currentMonth = '${now.year}-${now.month.toString().padLeft(2, '0')}';
    if (yearMonth.compareTo(currentMonth) >= 0) {
      throw const LedgerException('月报只能在月份结束后生成。');
    }
    // Native WorkManager events can arrive before the command and its snapshot
    // refresh complete. Never overwrite those newer states with this estimate.
    _reportStates[yearMonth] = 'ENQUEUED';
    _notify();
    try {
      await command(
        'generateReport',
        arguments: {'yearMonth': yearMonth},
        ledger: true,
      );
    } catch (_) {
      if (_reportStates[yearMonth] == 'ENQUEUED') {
        _reportStates.remove(yearMonth);
        _notify();
      }
      rethrow;
    }
  }

  Future<void> updateSettings(Map<String, dynamic> values) async {
    await command('updateSettings', arguments: values);
  }

  Future<void> saveSpeechSettings(Map<String, dynamic> values) async {
    await command('saveSpeechSettings', arguments: values);
  }

  Future<void> removeSpeechKey() async {
    await command('removeSpeechKey');
  }

  Future<void> setBudget(int fen) async {
    if (fen < 0 || fen > maxTransactionFen) {
      throw const LedgerException('预算应在 0 至 9,999,999.99 元之间。');
    }
    await updateSettings({'budgetCents': fen});
  }

  Future<bool> exportBackup() async =>
      stringMap(await command('exportBackup'))['cancelled'] != true;
  Future<bool> restoreBackup() async {
    final result = stringMap(await command('restoreBackup', ledger: true));
    if (result['cancelled'] == true) return false;
    _invalidateSession();
    _notify();
    return true;
  }

  Future<String> startSpeech() => _operation('startSpeech', () async {
    final generation = _generation;
    _speechText = '';
    _speechState = 'preparing';
    _notify();
    try {
      final result = stringMap(
        await _repository.invoke('startSpeech', const {}),
      );
      _requireCurrent(generation);
      _speechText = result['text'] as String? ?? '';
      return _speechText;
    } finally {
      _speechState = 'idle';
    }
  });
  Future<void> stopSpeech() async {
    await command('stopSpeech', refreshAfter: false);
  }

  Future<void> cancelSpeech() async {
    await command('cancelSpeech', refreshAfter: false);
    _speechText = '';
    _speechState = 'idle';
    _notify();
  }

  Future<void> openSystemSettings(String target) async {
    await command(
      'openSystemSettings',
      arguments: {'target': target},
      refreshAfter: false,
    );
  }

  Future<void> rebindNotificationService() async {
    await command('rebindNotificationService');
  }

  Future<bool> requestNotificationPermission() async =>
      stringMap(await command('requestNotificationPermission'))['granted'] ==
      true;
  Future<bool> requestMicrophonePermission() async =>
      stringMap(await command('requestMicrophonePermission'))['granted'] ==
      true;
  Future<void> signIn({
    String? accountId,
    bool requestPlanPermission = false,
  }) async {
    await command(
      'signIn',
      arguments: {
        'accountId': ?accountId,
        'requestPlanPermission': requestPlanPermission,
      },
    );
  }

  Future<void> cancelSignIn() async {
    await command('cancelSignIn');
  }

  Future<void> selectAccount(String id) async {
    await command('selectAccount', arguments: {'id': id});
  }

  Future<void> signOut(String id) async {
    await command('signOut', arguments: {'id': id});
  }

  Future<void> dismissWelcome() async {
    await command('dismissWelcome');
  }

  Future<List<AiModel>> listModels() async {
    final account = auth['activeAccountId'] as String?;
    final result = await command('listModels', refreshAfter: false) as List;
    final models = result
        .map((item) => AiModel.fromMap(stringMap(item)))
        .toList();
    if (account == auth['activeAccountId']) {
      _reasoningCatalogAccount = account;
      _reasoningModels = models;
      _notify();
    }
    return models;
  }

  Future<void> _loadReasoningCatalog() async {
    final account = auth['activeAccountId'] as String?;
    try {
      // Capability metadata is an enhancement; authentication errors remain
      // actionable in AI settings and must not replace an otherwise usable chat.
      final result = await _repository.invoke('listModels', {}) as List;
      if (!_disposed && account == auth['activeAccountId']) {
        _reasoningCatalogAccount = account;
        _reasoningModels = result
            .map((m) => AiModel.fromMap(stringMap(m)))
            .toList();
        _notify();
      }
    } on LedgerException {
      // Keep unknown capabilities rather than advertising unsupported options.
    }
  }

  @override
  void dispose() {
    _disposed = true;
    _generation++;
    _chatUpdateTimer?.cancel();
    chatUpdates.dispose();
    unawaited(_subscription?.cancel());
    if (_repository is CrossPlatformLedgerRepository) {
      unawaited(_repository.close());
    }
    super.dispose();
  }
}

import 'dart:async';
import 'dart:convert';

import 'recognition/recognition_protocol.dart';

import 'package:flutter/foundation.dart';

import 'ledger_repository.dart';
import 'cross_platform_repository.dart';
import 'models.dart';

export 'ledger_repository.dart' show LedgerException;

class LedgerController extends ChangeNotifier {
  LedgerController({LedgerRepository? repository, DateTime Function()? clock})
    : _repository = repository ?? CrossPlatformLedgerRepository(),
      _clock = clock ?? DateTime.now {
    final now = _clock();
    _selectedMonth = DateTime(now.year, now.month);
  }

  final LedgerRepository _repository;
  final DateTime Function() _clock;
  LedgerSnapshot? _snapshot;
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

  late DateTime _selectedMonth;
  DateTime get selectedMonth => _selectedMonth;
  String _keyword = '';
  String get keyword => _keyword;
  TxType? _typeFilter;
  TxType? get typeFilter => _typeFilter;
  TxStatus? _statusFilter;
  TxStatus? get statusFilter => _statusFilter;
  int? _categoryFilter;
  int? get categoryFilter => _categoryFilter;

  List<String> recognitionQuestions = [];
  String? recognitionNotice;
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
        agentProgress = event.data;
        _notify();
    }
  }

  void _invalidateSession() {
    _generation++;
    _draftGeneration++;
    _drafts = [];
    _messages = [];
    _agentSession = {};
    agentSessions = [];
    agentMemories = [];
    agentProgress = {};
    _agentLoaded = false;
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

  void setMonth(DateTime value) {
    _selectedMonth = DateTime(value.year, value.month);
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
    _categoryFilter = null;
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
              _inMonth(transaction, _selectedMonth) &&
              (_typeFilter == null || transaction.type == _typeFilter) &&
              (_statusFilter == null
                  ? transaction.status != TxStatus.ignored
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

  Future<int> saveTransaction(
    LedgerTransaction transaction, {
    int? expectedRevision,
    bool confirmPending = false,
  }) async {
    _validateTransaction(transaction);
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
        final reviewed = _drafts
            .where(
              (t) =>
                  t.recognitionMetadata['requestSessionId'] == sessionId &&
                  t.recognitionMetadata['reviewed'] == true,
            )
            .map((t) => t.recognitionMetadata['operationId'])
            .toSet();
        _drafts.removeWhere(
          (t) =>
              t.recognitionMetadata['requestSessionId'] == sessionId &&
              t.recognitionMetadata['reviewed'] != true,
        );
        items.removeWhere(
          (t) => reviewed.contains(t.recognitionMetadata['operationId']),
        );
      }
      _drafts = [..._drafts, ...items];
      recognitionNotice = items.isEmpty
          ? '没有新增账目，请查看下面的提示。'
          : '识别到 ${items.length} 笔，核对后保存。';
      return (items: items, auto: result['autoSaveEligible'] == true);
    });
    if (parsed.auto &&
        settings['activeInputAutoSaveEnabled'] == true &&
        parsed.items.isNotEmpty) {
      await saveDrafts(items: parsed.items);
      recognitionNotice = '已自动保存 ${parsed.items.length} 笔，可在账本中修改或删除。';
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
    if (prior.categoryId != value.categoryId &&
        value.merchant?.isNotEmpty == true) {
      unawaited(_rememberCategory(value));
    }
    _notify();
  }

  void removeDraft(int index) {
    if (busyFor('saveTransactions')) return;
    _drafts.removeAt(index);
    _notify();
  }

  void clearDrafts() {
    if (busyFor('saveTransactions')) return;
    _draftGeneration++;
    _drafts = [];
    recognitionQuestions = [];
    recognitionNotice = null;
    _notify();
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
      if (transaction.effectiveKind == 'unknown' &&
          transaction.source != TxSource.billImport) {
        throw const LedgerException('请先打开待分类草稿，选择账务用途后再保存。');
      }
    }
    final generation = _generation;
    final currentRevision = revision;
    var saved = 0;
    try {
      while (saved < saving.length) {
        _requireCurrent(generation);
        final batch = saving.sublist(
          saved,
          (saved + 100).clamp(0, saving.length),
        );
        await _repository.invoke('saveTransactions', {
          'revision': currentRevision,
          'transactions': batch
              .map((item) => item.copyWith(id: 0).toMap())
              .toList(),
        });
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
      if (saved > 0) {
        throw LedgerException(
          '已保存 $saved 笔，剩余 ${saving.length - saved} 笔尚未保存。${error is LedgerException ? error.message : '请重试剩余账单。'}',
          code: error is LedgerException ? error.code : 'PARTIAL_SAVE',
        );
      }
      rethrow;
    }
    await refresh();
    return saved;
  });

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
    _messages = (_agentSession['messages'] as List? ?? [])
        .map(stringMap)
        .where(
          (m) =>
              m['internal'] != true &&
              m['provisional'] != true &&
              (m['role'] == 'user' ||
                  m['role'] == 'assistant' &&
                      (m['tool_calls'] as List? ?? []).isEmpty),
        )
        .map(
          (m) => ChatMessage(
            role: m['role'] as String,
            text: m['content'] as String? ?? '',
            isError: m['isError'] == true,
            chartData: m['chartData'] == null
                ? null
                : InsightChart.fromMap(stringMap(m['chartData'])),
          ),
        )
        .toList();
  }

  Future<void> agentCommand(
    String method, [
    Map<String, dynamic> args = const {},
  ]) => _operation('agentSession', () async {
    if (isAsking) throw const LedgerException('请先停止当前任务。');
    final generation = _generation;
    final result = stringMap(
      await _repository.invoke(method, {'revision': revision, ...args}),
    );
    _requireCurrent(generation);
    _adoptAgentState(result);
    _retryQuestion = null;
    _notify();
  });
  Future<void> stopAgent() async {
    await _repository.invoke('cancelAgent', const {});
  }

  Future<void> resolveAgentAction(String id, bool approve) =>
      _operation('ask', () async {
        final generation = _generation;
        agentProgress = {};
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

  Future<ChatMessage> _ask(String question, {required bool retry}) =>
      _operation('ask', () async {
        _requireLoaded();
        final text = question.trim();
        if (text.isEmpty) throw const LedgerException('请输入想了解的账本问题。');
        final generation = _generation;
        var prior = _messages.where((message) => !message.isError).toList();
        if (retry && prior.isNotEmpty && prior.last.role == 'user') {
          prior.removeLast();
        }
        _messages = [...prior, ChatMessage(role: 'user', text: text)];
        _retryQuestion = null;
        agentProgress = {};
        _notify();
        try {
          final result = stringMap(
            await _repository.invoke('ask', {
              'revision': revision,
              'question': text,
            }),
          );
          _requireCurrent(generation);
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
            _messages = [..._messages, response];
          }
          _retryQuestion = response.isError ? text : null;
          return response;
        } catch (error) {
          _requireCurrent(generation);
          final response = ChatMessage(
            role: 'assistant',
            text: _friendly(error).message,
            isError: true,
          );
          _messages = [..._messages, response];
          _retryQuestion = text;
          rethrow;
        }
      });

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
    final result = await command('listModels', refreshAfter: false) as List;
    return result.map((item) => AiModel.fromMap(stringMap(item))).toList();
  }

  @override
  void dispose() {
    _disposed = true;
    _generation++;
    unawaited(_subscription?.cancel());
    if (_repository is CrossPlatformLedgerRepository) {
      unawaited(_repository.close());
    }
    super.dispose();
  }
}

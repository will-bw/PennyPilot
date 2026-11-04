import 'dart:async';
import 'dart:convert';

import '../ledger_repository.dart';
import '../models.dart';
import '../recognition/recognition_protocol.dart';
import 'agent_context.dart';
import 'agent_models.dart';
import 'agent_prompt.dart';
import 'agent_store.dart';
import 'ledger_agent_tools.dart';

typedef AgentAutoRecord =
    Future<({int id, LedgerSnapshot ledger})?> Function(
      Map<String, dynamic> transaction,
    );

class LedgerAgentRuntime {
  LedgerAgentRuntime({
    required this.store,
    DateTime Function()? clock,
    this.onProgress,
    this.maxDuration = const Duration(minutes: 8),
  }) : _clock = clock ?? DateTime.now;
  final AgentStore store;
  final DateTime Function() _clock;
  final void Function(Map<String, dynamic>)? onProgress;
  final Duration maxDuration;
  AgentCancellation? _cancellation;
  Completer<void>? _finished;
  bool get running => _cancellation != null;
  void cancel() => _cancellation?.cancel();
  Future<void> shutdown() async {
    cancel();
    await _finished?.future;
  }

  Future<AgentSession> active(int revision) async {
    final selected = await store.read('active:$revision');
    final saved = selected == null
        ? null
        : await store.read('session:$revision:${selected['id']}');
    final session = saved == null
        ? AgentSession.create(revision, _clock())
        : AgentSession.fromMap(saved);
    if (session.status == 'running' && !running) {
      _repairPendingCalls(session, '应用上次运行中断。此工具没有可确认的执行结果，请重新查询；不要假定成功。');
      session.status = 'interrupted';
      session.messages.add({
        'role': 'assistant',
        'content': '上次任务因应用关闭而中断。已保留执行记录，可以继续提问。',
        'isError': true,
      });
      await _save(session);
    }
    if (saved == null) {
      await _save(session);
      await store.write('active:$revision', {'id': session.id});
    } else if (session.messages.any(
      (message) =>
          message['id'] == null ||
          message['runId'] == null ||
          _needsReasoningPart(message),
    )) {
      await _save(session);
    }
    return session;
  }

  Future<Map<String, dynamic>> state(int revision) async {
    final session = await active(revision);
    final sessions =
        (await store.scan(
            'session:$revision:',
          )).map(AgentSession.fromMap).toList()
          ..sort((a, b) => b.updatedAt.compareTo(a.updatedAt));
    final memories = await store.scan('memory:$revision:');
    memories.sort(
      (a, b) => (b['updatedAt'] as int).compareTo(a['updatedAt'] as int),
    );
    return {
      'session': session.toMap(),
      'sessions': sessions.map((s) => s.listing).toList(),
      'memories': memories,
      'config': (await config()).toMap(),
    };
  }

  Future<AgentConfig> config() async =>
      AgentConfig.fromMap(await store.read('config') ?? {});
  Future<void> configure(Map<String, dynamic> value) async {
    _requireIdle();
    await store.write('config', AgentConfig.fromMap(value).toMap());
  }

  Future<Map<String, dynamic>> newSession(int revision) async {
    _requireIdle();
    final session = AgentSession.create(revision, _clock());
    await _save(session);
    await store.write('active:$revision', {'id': session.id});
    return state(revision);
  }

  Future<Map<String, dynamic>> select(int revision, String id) async {
    _requireIdle();
    if (await store.read('session:$revision:$id') == null) {
      throw const LedgerException('对话已不存在。');
    }
    await store.write('active:$revision', {'id': id});
    return state(revision);
  }

  Future<Map<String, dynamic>> deleteSession(int revision, String id) async {
    _requireIdle();
    await store.remove('session:$revision:$id');
    return state(revision);
  }

  Future<Map<String, dynamic>> forget(int revision, String key) async {
    _requireIdle();
    await store.remove('memory:$revision:$key');
    return state(revision);
  }

  Future<Map<String, dynamic>> appendInput({
    required int revision,
    required String question,
    required String source,
    required String answer,
    required List<Map<String, dynamic>> parts,
    required String status,
    required String runId,
    required Future<void> Function() guard,
    Map<String, dynamic>? attachment,
  }) async {
    _requireIdle();
    if (question.trim().isEmpty ||
        question.length > 16000 ||
        answer.length > 20000 ||
        runId.isEmpty ||
        runId.length > 160 ||
        !['AI_TEXT', 'AI_VOICE', 'AI_IMAGE'].contains(source) ||
        !['awaiting_input', 'completed'].contains(status) ||
        parts.length > 32 ||
        jsonEncode(parts).length > 512 * 1024) {
      throw const LedgerException('识别回执格式无效。', code: 'AGENT_PROTOCOL');
    }
    if (attachment != null &&
        (attachment.keys.any(
              (key) => !['type', 'path', 'mimeType', 'name'].contains(key),
            ) ||
            jsonEncode(attachment).length > 4096)) {
      throw const LedgerException('识别附件格式无效。', code: 'AGENT_PROTOCOL');
    }
    await guard();
    final session = await active(revision);
    final now = _clock().millisecondsSinceEpoch;
    final prior = session.messages
        .where((message) => message['runId'] == runId)
        .toList();
    if (prior.isEmpty) {
      session.messages.add({
        'id': agentId(),
        'runId': runId,
        'role': 'user',
        'content': question.trim(),
        'source': source,
        'createdAt': now,
        if (attachment != null) 'attachments': [attachment],
      });
      session.messages.add({
        'id': agentId(),
        'runId': runId,
        'role': 'assistant',
        'content': answer,
        'parts': parts,
        'createdAt': now,
      });
    } else {
      final user = prior
          .where((message) => message['role'] == 'user')
          .firstOrNull;
      if (user != null) user['content'] = question.trim();
      final assistant = prior.lastWhere(
        (message) => message['role'] == 'assistant',
      );
      assistant['content'] = answer;
      assistant['parts'] = parts;
      assistant['updatedAt'] = now;
    }
    session.status = status;
    if (session.title == '新对话') {
      session.title = question.trim().substring(
        0,
        question.trim().length.clamp(0, 36),
      );
    }
    await guard();
    await _save(session);
    return state(revision);
  }

  Future<Map<String, dynamic>> undoRun({
    required int revision,
    required String runId,
    required LedgerRepository ledger,
    required Future<void> Function() guard,
    List<int>? transactionIds,
  }) async {
    _requireIdle();
    await guard();
    final session = await active(revision);
    final actions = session.actions
        .where(
          (action) =>
              action['runId'] == runId &&
              action['kind'] == 'transaction' &&
              action['status'] == 'applied' &&
              agentMap(action['payload'])['before'] == null,
        )
        .toList();
    final parts = session.messages
        .where((message) => message['runId'] == runId)
        .expand((message) => message['parts'] as List? ?? const [])
        .map(agentMap)
        .toList();
    final receipts = parts
        .where(
          (part) => part['type'] == 'receipt' && part['status'] != 'undone',
        )
        .toList();
    final expected = <int, Map<String, dynamic>>{};
    void collect(Map<String, dynamic> raw, int id) {
      final tx = LedgerTransaction.fromMap({...raw, 'id': id});
      final operation = tx.recognitionMetadata['operationId'];
      if (id <= 0 || operation is! String || operation.isEmpty) {
        throw const LedgerException('这次新增缺少可核对的保存记录，请在账本中处理。');
      }
      expected[id] = {'id': id, 'editVersion': 0, 'operationId': operation};
    }

    for (final action in actions) {
      final id = action['transactionId'];
      if (id is! int) continue;
      collect(agentMap(agentMap(action['payload'])['transaction']), id);
    }
    for (final receipt in receipts) {
      final undone = (receipt['undoneTransactionIds'] as List? ?? const [])
          .toSet();
      for (final raw in receipt['transactions'] as List? ?? const []) {
        final tx = agentMap(raw);
        if (undone.contains(tx['id'])) continue;
        collect(tx, tx['id'] as int);
      }
    }
    if (transactionIds != null) {
      final selected = transactionIds.toSet();
      if (selected.isEmpty ||
          selected.length != transactionIds.length ||
          !selected.every(expected.containsKey)) {
        throw const LedgerException('撤销记录不属于这轮尚未撤销的新增账单。');
      }
      expected.removeWhere((id, _) => !selected.contains(id));
    }
    if (expected.isEmpty) {
      throw const LedgerException('这轮没有可撤销的新增账单，或已经撤销。');
    }
    await guard();
    final current = await ledger.snapshot();
    if (current.revision != revision) {
      throw const LedgerException('账本已恢复，旧的新增记录不能撤销。', code: 'STALE_LEDGER');
    }
    // The repository checks all original versions and operation ids in the same
    // transaction as deletion. No UI-supplied transaction enters this command.
    final result = agentMap(
      await ledger.invoke('undoRecognitionSave', {
        'revision': revision,
        'transactions': expected.values.toList(),
      }),
    );
    await guard();
    final undoneOperations = expected.values
        .map((row) => row['operationId'] as String)
        .toSet();
    for (final action in actions.where(
      (action) => expected.containsKey(action['transactionId']),
    )) {
      action['status'] = 'undone';
    }
    for (final receipt in receipts) {
      final ids = (receipt['transactions'] as List? ?? const [])
          .map((raw) => agentMap(raw)['id'] as int)
          .toSet();
      final newlyUndone = ids.intersection(expected.keys.toSet());
      if (newlyUndone.isEmpty) continue;
      final undone = {
        ...(receipt['undoneTransactionIds'] as List? ?? const []).cast<int>(),
        ...newlyUndone,
      };
      receipt['undoneTransactionIds'] = undone.toList();
      final remaining = ids.difference(undone).length;
      receipt['status'] = remaining == 0 ? 'undone' : 'partially_undone';
      receipt['title'] = remaining == 0
          ? '已撤销这次新增的 ${ids.length} 笔'
          : '已撤销 ${undone.length} 笔，保留 $remaining 笔';
    }
    for (final part in parts.where(
      (part) => part['type'] == 'recognition_state',
    )) {
      part['undoneOperations'] = {
        ...((part['undoneOperations'] as List? ?? const [])
            .whereType<String>()),
        ...undoneOperations,
      }.toList();
      part['drafts'] = (part['drafts'] as List? ?? const []).where((raw) {
        final tx = LedgerTransaction.fromMap(agentMap(raw), draft: true);
        return !undoneOperations.contains(
          tx.recognitionMetadata['operationId'],
        );
      }).toList();
    }
    // Parts are decoded maps, so put the updated objects back into the durable
    // messages before saving the checkpoint.
    var partIndex = 0;
    for (final message in session.messages.where(
      (message) => message['runId'] == runId,
    )) {
      final count = (message['parts'] as List? ?? const []).length;
      if (count > 0) {
        message['parts'] = parts.sublist(partIndex, partIndex + count);
      }
      partIndex += count;
    }
    session.messages.add({
      'id': agentId(),
      'runId': runId,
      'role': 'user',
      'internal': true,
      'content':
          '应用状态更新：用户已通过界面撤销这轮新增的 ${expected.length} 笔账单。后续必须重新查询账本，不得重新创建已撤销记录。',
      'createdAt': _clock().millisecondsSinceEpoch,
    });
    await _save(session);
    return {...await state(revision), 'count': result['count']};
  }

  Future<Map<String, dynamic>> run({
    required String question,
    required LedgerSnapshot ledger,
    required AgentModel model,
    required Future<void> Function() guard,
    AgentAutoRecord? autoRecord,
    String source = 'AI_TEXT',
    bool internalQuestion = false,
  }) async {
    _requireIdle();
    if (question.trim().isEmpty ||
        question.length > 16000 ||
        !['AI_TEXT', 'AI_VOICE', 'AI_IMAGE'].contains(source)) {
      throw const LedgerException('请输入1～16000字的任务。');
    }
    final cancellation = AgentCancellation();
    void cancelTransport() => model.cancel?.call();
    cancellation.listen(cancelTransport);
    _cancellation = cancellation;
    _finished = Completer<void>();
    final started = _clock();
    final runId = agentId();
    final userMessageId = agentId();
    final watch = Stopwatch()..start();
    final deadline = Timer(
      maxDuration,
      () => cancellation.cancel(
        const LedgerException('任务达到执行时限，已保留进度。', code: 'AGENT_TIME_LIMIT'),
      ),
    );
    Future<void> check() async {
      cancellation.check();
      if (watch.elapsed > maxDuration) {
        throw const LedgerException(
          '任务达到8分钟执行时限，已保留进度。',
          code: 'AGENT_TIME_LIMIT',
        );
      }
      await guard();
      cancellation.check();
    }

    AgentSession? session;
    String? previewMessageId;
    var previewText = StringBuffer();
    Map<String, dynamic>? activeReasoning;
    var reasoningAttached = false;
    var currentStep = 0;
    void emit(String phase, [Map<String, dynamic> fields = const {}]) {
      if (session == null) return;
      onProgress?.call({
        'sessionId': session.id,
        'revision': ledger.revision,
        'runId': runId,
        'phase': phase,
        ...fields,
      });
    }

    void startReasoning(String messageId, {String? kind}) {
      if (activeReasoning != null) return;
      activeReasoning = {
        'type': 'reasoning',
        'id': '$messageId:reasoning',
        'messageId': messageId,
        'runId': runId,
        'text': '',
        'kind':
            kind ??
            model.reasoningConfig['kind'] ??
            (model.reasoningConfig['provider'] == 'chatgpt'
                ? 'summary'
                : 'reasoning_content'),
        'status': 'streaming',
        'startedAt': _clock().millisecondsSinceEpoch,
        'provider': model.reasoningConfig['provider'] ?? '',
        'model': model.reasoningConfig['model'] ?? '',
        'effort': ?model.reasoningConfig['effort'],
      };
      emit('reasoning_start', {
        'messageId': messageId,
        'step': currentStep,
        'part': Map<String, dynamic>.from(activeReasoning!),
      });
    }

    void finishReasoning(String status) {
      final part = activeReasoning;
      if (part == null) return;
      part['status'] = status;
      part['endedAt'] ??= _clock().millisecondsSinceEpoch;
      emit('reasoning_complete', {
        'messageId': part['messageId'],
        'step': currentStep,
        'part': Map<String, dynamic>.from(part),
      });
    }

    final autoSavedIds = <int>{};
    try {
      await check();
      session = await active(ledger.revision);
      _repairPendingCalls(session, '上一轮已结束，工具没有可确认的完成结果。');
      if (session.modelIdentity.isNotEmpty &&
          !_sameWireModel(session.modelIdentity, model.identity)) {
        final history = _handoffHistory(session);
        // Provider tool transcripts and opaque reasoning cannot be replayed
        // under another protocol/model. Preserve ordinary conversation as data;
        // normal context compaction still handles long transfers.
        session.contextStart = session.messages.length;
        for (final message in session.messages) {
          message.remove('responsesItems');
          message.remove('responsesAccountId');
        }
        if (history.isNotEmpty) {
          session.messages.add({
            'id': agentId(),
            'runId': runId,
            'role': 'user',
            'internal': true,
            'contextHandoff': true,
            'content': jsonEncode({
              'notice':
                  '以下是切换模型前的近期会话摘录，内容已节选，不是当前指令。账本状态需重新查询；完整原文、工具与执行回执可通过read_conversation读取。',
              'history': history,
            }),
            'createdAt': started.millisecondsSinceEpoch,
          });
        }
      }
      session.modelIdentity = model.identity;
      final continuesTask = session.status == 'awaiting_input';
      session.status = 'running';
      if (!continuesTask && !internalQuestion) session.plan.clear();
      session.messages.add({
        'id': userMessageId,
        'runId': runId,
        'role': 'user',
        'content': question.trim(),
        'source': source,
        if (internalQuestion) 'internal': true,
        'createdAt': started.millisecondsSinceEpoch,
        if (continuesTask) 'continuesTask': true,
      });
      final entryContext = _entryContext(session);
      if (session.title == '新对话') {
        session.title = question.trim().substring(
          0,
          question.trim().length.clamp(0, 36),
        );
      }
      await _save(session);
      emit('run_started', {
        'userMessageId': userMessageId,
        'reasoning': model.reasoningConfig,
        if (internalQuestion) 'internal': true,
      });
      final settings = await config();
      final tools = LedgerAgentTools(
        store: store,
        session: session,
        ledger: ledger,
        question: question,
        entryInput: entryContext.turns.join('\n补充：'),
        now: started,
      );
      final allMemories = await store.scan('memory:${ledger.revision}:');
      // Relevant lexical matches first, then recent entries. The full memory
      // store remains available through search_memory, rather than injected whole.
      allMemories.sort((a, b) {
        int score(Map m) => question.contains(m['key'] as String)
            ? 100
            : (m['content'] as String)
                  .split(RegExp(r'[，。；、\s]+'))
                  .where((w) => w.length > 1 && question.contains(w))
                  .length;
        final relevance = score(b).compareTo(score(a));
        return relevance != 0
            ? relevance
            : (b['updatedAt'] as int).compareTo(a['updatedAt'] as int);
      });
      final memories = allMemories.take(8).toList();
      final system = ledgerAgentPrompt(
        ledger,
        started,
        autoRecordEnabled: autoRecord != null,
      );
      final cancellableModel = AgentModel(
        identity: model.identity,
        complete: (messages, tools) =>
            _cancellable(model.complete(messages, tools), cancellation),
      );
      Map<String, dynamic>? chart;
      String? waitingQuestion;
      var calls = 0, correctiveAttempts = 0;
      final repeats = <String, int>{};
      final autoRecorded = <String, Map<String, dynamic>>{};
      for (var step = 1; step <= settings.maxSteps; step++) {
        currentStep = step;
        await check();
        void progress(String text) => emit('model', {
          'step': step,
          'maxSteps': settings.maxSteps,
          'text': text,
          'plan': session!.plan,
        });
        await AgentContext.compact(
          session: session,
          model: cancellableModel,
          config: settings,
          system: system,
          memories: memories,
          tools: LedgerAgentTools.definitions,
          guard: check,
          progress: progress,
        );
        await _save(session);
        progress(step == 1 ? '正在理解任务…' : '正在根据查询结果继续分析…');
        final messageId = agentId();
        previewMessageId = messageId;
        previewText = StringBuffer();
        activeReasoning = null;
        reasoningAttached = false;
        emit('text_start', {'messageId': messageId, 'step': step});
        if (model.reasoningConfig['enabled'] == true &&
            (model.reasoningConfig['provider'] != 'chatgpt' ||
                model.reasoningConfig['supportsSummary'] != false)) {
          startReasoning(messageId);
        }
        final reply = await _cancellable(
          model.turn(
            AgentContext.project(session, system, memories),
            LedgerAgentTools.definitions,
            onTextDelta: (delta) {
              if (!identical(_cancellation, cancellation) ||
                  cancellation.isCancelled) {
                return;
              }
              if (delta.isNotEmpty &&
                  activeReasoning?['status'] == 'streaming') {
                finishReasoning('completed');
              }
              previewText.write(delta);
              emit('text_delta', {
                'messageId': messageId,
                'step': step,
                'delta': delta,
              });
            },
            onReasoningDelta: (delta) {
              if (!identical(_cancellation, cancellation) ||
                  cancellation.isCancelled ||
                  delta.isEmpty) {
                return;
              }
              startReasoning(messageId);
              final part = activeReasoning!;
              part['text'] = '${part['text']}$delta';
              emit('reasoning_delta', {
                'messageId': messageId,
                'step': step,
                'delta': delta,
                'part': Map<String, dynamic>.from(part),
              });
            },
          ),
          cancellation,
        );
        await check();
        session.inputTokens += reply.inputTokens ?? 0;
        session.outputTokens += reply.outputTokens ?? 0;
        final summary = reply.message['reasoning_summary'] as String?;
        final reasoning = summary?.isNotEmpty == true
            ? summary
            : reply.message['reasoning_content'] as String?;
        if (reasoning?.isNotEmpty == true) {
          startReasoning(
            messageId,
            kind: summary?.isNotEmpty == true ? 'summary' : 'reasoning_content',
          );
          activeReasoning!['text'] = reasoning;
        }
        if (activeReasoning != null) finishReasoning('completed');
        _validateReply(reply, session.messages);
        final assistant = {
          ...reply.message,
          'id': messageId,
          'runId': runId,
          'role': 'assistant',
          'createdAt': _clock().millisecondsSinceEpoch,
          if (activeReasoning != null)
            'parts': [
              ...(reply.message['parts'] as List? ?? const []),
              Map<String, dynamic>.from(activeReasoning!),
            ],
        };
        session.messages.add(assistant);
        reasoningAttached = activeReasoning != null;
        if (reply.calls.isEmpty) {
          final unfinished = session.plan.any(
            (p) => p['status'] != 'completed',
          );
          if (unfinished &&
              !session.actions.any((a) => a['status'] == 'pending')) {
            assistant['provisional'] = true;
            emit('text_reset', {'messageId': messageId});
            previewMessageId = null;
            if (++correctiveAttempts > 2) {
              throw const LedgerException(
                '任务计划仍有未完成步骤，已保留进度，请继续任务。',
                code: 'AGENT_INCOMPLETE',
              );
            }
            session.messages.add({
              'role': 'user',
              'content':
                  '运行时校验：公开计划仍有未完成步骤。请继续执行，已完成则用update_plan更新；需要补充信息则用ask_user暂停。',
              'internal': true,
            });
            await _save(session);
            continue;
          }
          // Require real observations for ledger work; a system instruction alone
          // cannot prevent unsupported direct answers.
          final ledgerTask =
              RegExp(
                r'账|花费|花了|支出|收入|预算|消费|交易|商户|多少钱|上个月|这个月|本月|上周|本周|去年|今年',
              ).hasMatch(question) &&
              !RegExp(r'^(记住|忘记|你能做什么|介绍|有哪些工具)').hasMatch(question.trim());
          final evidenceIds = session.trace
              .where((t) => t['runId'] == runId)
              .map((t) => t['evidenceId'])
              .whereType<String>()
              .toSet();
          final citedIds = RegExp(
            r'\[依据 (Q\d+)\]',
          ).allMatches(reply.text).map((m) => m.group(1)!).toSet();
          final unsupported = citedIds.any((id) => !evidenceIds.contains(id));
          if (unsupported ||
              ledgerTask && (!tools.hasLedgerEvidence || citedIds.isEmpty)) {
            assistant['provisional'] = true;
            emit('text_reset', {'messageId': messageId});
            previewMessageId = null;
            if (++correctiveAttempts > 2) {
              throw const LedgerException(
                '模型未提供可核实的账本依据，本次回答未采纳。请重试或换用支持工具调用的模型。',
                code: 'AGENT_EVIDENCE',
              );
            }
            session.messages.add({
              'role': 'user',
              'content':
                  '运行时校验：账本结论需要本轮真实查询结果，并注明有效的[依据 Q编号]。请先调用适当工具，不能引用不存在的依据。',
              'internal': true,
            });
            await _save(session);
            continue;
          }
          session.status = session.actions.any((a) => a['status'] == 'pending')
              ? 'awaiting_confirmation'
              : 'completed';
          if (chart != null) assistant['chartData'] = chart;
          await _save(session);
          emit('text_complete', {
            'messageId': messageId,
            'text': reply.text,
            'status': 'completed',
          });
          previewMessageId = null;
          emit('run_finished', {'status': session.status});
          return {
            'answer': reply.text,
            'isError': false,
            'chartData': ?chart,
            if (autoSavedIds.isNotEmpty) 'autoSavedIds': autoSavedIds.toList(),
            ...await state(ledger.revision),
          };
        }
        await _save(
          session,
        ); // assistant and all call ids are durable before execution
        // Tool preambles are transient; the durable result is the final answer.
        emit('text_reset', {'messageId': messageId});
        previewMessageId = null;
        for (final call in reply.calls) {
          await check();
          final function = agentMap(call['function']);
          final name = function['name'] as String;
          final trace = <String, dynamic>{
            'step': step,
            'tool': name,
            'callId': call['id'],
            'status': 'running',
            'runStartedAt': started.millisecondsSinceEpoch,
            'runId': runId,
            'startedAt': _clock().millisecondsSinceEpoch,
          };
          session.trace.add(trace);
          emit('tool', {
            'step': step,
            'maxSteps': settings.maxSteps,
            'text': toolLabel(name),
            'tool': name,
            'callId': call['id'],
            'trace': Map<String, dynamic>.from(trace),
            'plan': session.plan,
          });
          Map<String, dynamic> result;
          final priorActions = session.actions.length;
          try {
            if (++calls > settings.maxToolCalls) {
              throw const FormatException('本轮工具调用预算已用完，请总结当前进度');
            }
            final args = agentMap(jsonDecode(function['arguments'] as String));
            final signature = '$name:${jsonEncode(_canonical(args))}';
            repeats[signature] = (repeats[signature] ?? 0) + 1;
            if (repeats[signature]! > 3) {
              throw const FormatException('同一调用已重复3次，请使用已有结果或改变查询方法');
            }
            final recorded = autoRecorded[signature];
            result = recorded ?? await tools.execute(name, args);
            for (final action in session.actions.skip(priorActions)) {
              action['runId'] = runId;
            }
            if (recorded == null &&
                autoRecord != null &&
                name == 'propose_transaction' &&
                args['transaction_id'] == null) {
              result = await _autoRecord(
                session: session,
                tools: tools,
                question: question,
                entryContext: entryContext,
                args: args,
                result: result,
                now: started,
                record: autoRecord,
                guard: check,
                source: source,
              );
              if (result['status'] == 'applied') {
                autoRecorded[signature] = result;
                autoSavedIds.add(result['transaction_id'] as int);
              }
            }
            await check();
            trace['status'] = 'completed';
            trace['evidenceId'] = result['evidence_id'];
            if (name == 'ask_user') {
              waitingQuestion = result['question'] as String;
            }
            if (name == 'aggregate_by') {
              final data = result['data'] as Map;
              final groups = (data['groups'] as List).take(5).toList();
              if (groups.isNotEmpty) {
                chart = {
                  'labels': groups.map((g) => g['label']).toList(),
                  'values': groups.map((g) => g['expense']).toList(),
                  'highlightIndex': 0,
                  'unit': '元',
                };
              }
            }
          } on FormatException catch (error) {
            result = {
              'error': 'validation_error',
              'message': error.message,
              'retryable': true,
              'instruction': '修正参数后继续，不要重复相同失败调用。',
            };
            trace['status'] = 'error';
          } on LedgerException {
            rethrow;
          } catch (_) {
            result = {
              'error': 'tool_error',
              'message': '工具未完成，请换一种查询方式；不要假定成功。',
              'retryable': true,
            };
            trace['status'] = 'error';
          }
          trace['finishedAt'] = _clock().millisecondsSinceEpoch;
          for (final action in session.actions.skip(priorActions)) {
            action['runId'] = runId;
          }
          session.messages.add({
            'id': agentId(),
            'runId': runId,
            'role': 'tool',
            'tool_call_id': call['id'],
            'content': jsonEncode(result),
            'createdAt': _clock().millisecondsSinceEpoch,
          });
          await _save(
            session,
          ); // side effects/results are never replayed on restart
          emit('tool_result', {
            'callId': call['id'],
            'tool': name,
            'result': result,
            'trace': Map<String, dynamic>.from(trace),
            'actions': session.actions
                .map((action) => Map<String, dynamic>.from(action))
                .toList(),
            'plan': session.plan,
          });
        }
        if (waitingQuestion != null) {
          session.status = 'awaiting_input';
          final waitingId = agentId();
          session.messages.add({
            'id': waitingId,
            'runId': runId,
            'role': 'assistant',
            'content': waitingQuestion,
            'createdAt': _clock().millisecondsSinceEpoch,
          });
          await _save(session);
          emit('text_complete', {
            'messageId': waitingId,
            'text': waitingQuestion,
            'status': 'completed',
          });
          emit('run_finished', {'status': session.status});
          return {
            'answer': waitingQuestion,
            'isError': false,
            if (autoSavedIds.isNotEmpty) 'autoSavedIds': autoSavedIds.toList(),
            ...await state(ledger.revision),
          };
        }
        if (calls > settings.maxToolCalls) break;
      }
      throw const LedgerException(
        '已达到本次执行步数上限，已保留查询结果和未完成计划。可以继续提问，或在 Agent 设置中调整上限。',
        code: 'AGENT_STEP_LIMIT',
      );
    } catch (error) {
      if (session == null) rethrow;
      // Aborting the HTTP request can settle before the cancellation future.
      // The explicit stop/deadline owns the run's outcome in either ordering.
      final failure = cancellation.reason ?? error;
      if (activeReasoning?['status'] == 'streaming') {
        finishReasoning(
          failure is LedgerException && failure.code == 'AGENT_CANCELLED'
              ? 'cancelled'
              : 'error',
        );
      }
      final partialParts = <Map<String, dynamic>>[
        if (activeReasoning != null && !reasoningAttached)
          Map<String, dynamic>.from(activeReasoning!),
        if (previewMessageId != null && previewText.isNotEmpty)
          {'type': 'interrupted_text', 'text': previewText.toString()},
      ];
      if (previewMessageId != null) {
        emit('text_reset', {'messageId': previewMessageId});
      }
      _repairPendingCalls(session, '本次工具执行被停止或未能确认完成，请重新查询。');
      final message = failure is LedgerException
          ? failure.message
          : '任务未完成，已保留执行记录，请重试。';
      session.status =
          failure is LedgerException && failure.code == 'AGENT_CANCELLED'
          ? 'cancelled'
          : 'failed';
      final errorMessageId = agentId();
      session.messages.add({
        'id': errorMessageId,
        'runId': runId,
        'role': 'assistant',
        'content': message,
        'isError': true,
        if (partialParts.isNotEmpty) 'parts': partialParts,
        'createdAt': _clock().millisecondsSinceEpoch,
      });
      await _save(session);
      emit('text_complete', {
        'messageId': errorMessageId,
        'text': message,
        'isError': true,
        'status': session.status,
        if (partialParts.isNotEmpty) 'parts': partialParts,
      });
      emit('run_finished', {'status': session.status});
      return {
        'answer': message,
        'isError': true,
        if (autoSavedIds.isNotEmpty) 'autoSavedIds': autoSavedIds.toList(),
        ...await state(ledger.revision),
      };
    } finally {
      cancellation.unlisten(cancelTransport);
      cancelTransport();
      _cancellation = null;
      _finished?.complete();
      _finished = null;
      watch.stop();
      deadline.cancel();
    }
  }

  Future<Map<String, dynamic>> _autoRecord({
    required AgentSession session,
    required LedgerAgentTools tools,
    required String question,
    required ({List<String> turns, String operationId}) entryContext,
    required Map<String, dynamic> args,
    required Map<String, dynamic> result,
    required DateTime now,
    required AgentAutoRecord record,
    required Future<void> Function() guard,
    required String source,
  }) async {
    final action = session.actions.singleWhere(
      (a) => a['id'] == result['action_id'],
    );
    final payload = agentMap(agentMap(action['payload'])['transaction']);
    final evidence = args['evidence'] as String;
    final input = entryContext.turns.join('\n补充：');
    final split = ExpenseSplit.fromMap(
      agentMap(jsonDecode(payload['metadataJson'] as String))['aaSplit'],
    );
    final amountEvidence = _entryAmount(
      entryContext.turns,
      evidence,
      split?.totalAmountFen ?? payload['amount'] as int,
    );
    final operationId =
        '${entryContext.operationId}:amount@${amountEvidence.offset}';
    final category = tools.ledger.categories.singleWhere(
      (c) => c.id == payload['categoryId'],
    );
    final explicitlyRecord = RegExp(r'记一笔|记下|记账|帮我记|补记|录入|新增|添加');
    bool queryOnly(String text) =>
        RegExp(r'^(?:帮我)?(?:查|看|分析|统计|对比|预算)|[?？吗么]\s*$').hasMatch(text) &&
        !explicitlyRecord.hasMatch(text);
    final isQuery =
        queryOnly(question) ||
        queryOnly(entryContext.turns.first) &&
            !explicitlyRecord.hasMatch(question);
    final checked = RecognitionProtocol.validate(
      {
        'intent': isQuery ? 'query' : 'record',
        'proposals': [
          {
            'amount': payload['amount'],
            if (split != null) 'aaSplit': split.toMap(),
            'currency': 'CNY',
            'type': payload['type'],
            'businessKind': payload['businessKind'],
            'paymentStatus': 'declared',
            'category': category.name,
            'merchant': payload['merchant'],
            'accountTag': payload['accountTag'],
            'note': payload['note'],
            'occurred_at': LedgerAgentTools.day(
              DateTime.fromMillisecondsSinceEpoch(payload['occurredAt'] as int),
            ),
            'fieldEvidence': {
              'amount': {
                'quote': amountEvidence.quote,
                'source': split == null ? 'user' : 'derived',
              },
              'type': {'quote': evidence, 'source': 'inference'},
            },
          },
        ],
      },
      snapshot: tools.ledger,
      input: input,
      source: source,
      now: now,
      operationId: entryContext.operationId,
    );
    final drafts = checked['transactions'] as List;
    if (drafts.length != 1 || (checked['questions'] as List).isNotEmpty) {
      action['status'] = 'invalid';
      throw FormatException((checked['questions'] as List).join('；'));
    }
    final draft = agentMap(drafts.single);
    final metadata = agentMap(jsonDecode(payload['metadataJson'] as String));
    final inferred = agentMap(jsonDecode(draft['metadataJson'] as String));
    final transaction = {
      ...payload,
      'source': source,
      ...draft,
      'id': 0,
      'metadataJson': jsonEncode({
        ...inferred,
        ...metadata,
        'operationId': operationId,
        'assumptions': {
          ...inferred['assumptions'] as List,
          ...metadata['assumptions'] as List,
        }.toList(),
        'autoRecorded': true,
      }),
    };
    action['payload'] = {'transaction': transaction};
    action['title'] =
        '新增 ${LedgerAgentTools.day(DateTime.fromMillisecondsSinceEpoch(transaction['occurredAt'] as int))} ${category.name} ${formatMoney(transaction['amount'] as int)} 元';
    action['status'] = 'applying';
    await _save(session);
    try {
      await guard();
      final existing = tools.ledger.transactions
          .where((tx) => tx.recognitionMetadata['operationId'] == operationId)
          .firstOrNull;
      final saved = existing == null
          ? await record(transaction)
          : (id: existing.id, ledger: tools.ledger);
      if (saved == null) {
        action['status'] = 'pending';
        return result;
      }
      tools.ledger = saved.ledger;
      final observation = await tools.execute('get_transaction', {
        'transaction_id': saved.id,
      });
      action['status'] = 'applied';
      action['transactionId'] = saved.id;
      await _save(session);
      return {
        ...result,
        ...observation,
        'status': 'applied',
        'title': action['title'],
        'transaction_id': saved.id,
        'instruction': existing == null
            ? '已实际保存到账本，可继续查询；不要再要求确认这笔新增。'
            : '这笔已在本次任务中保存，未重复新增；可继续查询。',
      };
    } catch (_) {
      action['status'] = 'uncertain';
      await _save(session);
      rethrow;
    }
  }

  static ({List<String> turns, String operationId}) _entryContext(
    AgentSession session,
  ) {
    final turns = <String>[];
    var start = session.messages.length - 1;
    // Only an outstanding ask_user links separate user messages. Completed
    // tasks and runtime-generated corrections cannot authorize another entry.
    for (var i = session.messages.length - 1; i >= 0; i--) {
      final message = session.messages[i];
      if (message['role'] != 'user' || message['internal'] == true) continue;
      turns.insert(0, message['content'] as String);
      start = i;
      if (message['continuesTask'] != true) break;
    }
    return (turns: turns, operationId: 'agent:${session.id}:entry:$start');
  }

  static ({String quote, int offset}) _entryAmount(
    List<String> turns,
    String evidence,
    int amount,
  ) {
    var offset = turns.join('\n补充：').length;
    for (var i = turns.length - 1; i >= 0; i--) {
      final turn = turns[i];
      offset -= turn.length;
      final quoted = i == turns.length - 1
          ? _amountEvidence(evidence, amount)
          : (quote: '', offset: -1);
      final local = quoted.offset < 0
          ? _amountEvidence(turn, amount)
          : (
              quote: quoted.quote,
              offset: turn.indexOf(evidence) + quoted.offset,
            );
      if (local.offset >= 0) {
        return (quote: local.quote, offset: offset + local.offset);
      }
      offset -= '\n补充：'.length;
    }
    return (quote: '', offset: -1);
  }

  static ({String quote, int offset}) _amountEvidence(
    String evidence,
    int amount,
  ) {
    final tokens = RegExp(
      r'\d+(?:,\d{3})*(?:\.\d{1,2})?|[零〇一二两三四五六七八九十百千万]+(?:(?:元|块|点)[零〇一二两三四五六七八九]+)?',
    );
    for (final match in tokens.allMatches(evidence)) {
      if (!RecognitionProtocol.amountMatchesQuote(amount, match[0]!)) continue;
      final after = evidence.substring(match.end);
      final before = evidence.substring(0, match.start);
      if (RegExp(r'^\s*(?:年|月|日|号|天|点|时|分|秒|人|位|次|笔|份|[:：])').hasMatch(after) ||
          RegExp(r'\d[:：]\s*$').hasMatch(before)) {
        continue;
      }
      return (quote: match[0]!, offset: match.start);
    }
    return (quote: '', offset: -1);
  }

  /// Applies stored changes requiring review. UI sends its id and decision,
  /// never a payload chosen by the model or UI. Ledger generation/version checks
  /// remain in the ledger repository; new records use a stable operation id.
  Future<String> resolve({
    required int revision,
    required String actionId,
    required bool approve,
    required LedgerRepository ledger,
    required Future<void> Function() guard,
  }) async {
    _requireIdle();
    await guard();
    final session = await active(revision);
    final action = session.actions
        .where((a) => a['id'] == actionId)
        .firstOrNull;
    if (action == null) throw const LedgerException('提案已不存在。');
    if (action['status'] != 'pending') {
      throw const LedgerException('提案已经处理，不会重复执行。');
    }
    if (!approve) {
      action['status'] = 'rejected';
      await _save(session);
      return '用户已拒绝提案 ${action['title']}（$actionId），未改变账本。';
    }
    final payload = agentMap(action['payload']);
    final current = await ledger.snapshot();
    if (current.revision != revision) {
      throw const LedgerException('账本已恢复，旧提案失效。', code: 'STALE_LEDGER');
    }
    final before = payload['before'];
    if (before is Map) {
      final tx = current.transactions
          .where((t) => t.id == before['id'])
          .firstOrNull;
      if (tx == null || jsonEncode(tx.toMap()) != jsonEncode(before)) {
        throw const LedgerException('目标账单已变化，请重新查询并生成提案。');
      }
    } else if (action['kind'] == 'budget' &&
        current.settings['budgetCents'] != before) {
      throw const LedgerException('预算已变化，请重新生成提案。');
    }
    action['status'] = 'applying';
    await _save(session);
    try {
      await guard();
      switch (action['kind']) {
        case 'transaction':
          final result = agentMap(
            await ledger.invoke('saveTransaction', {
              ...agentMap(payload['transaction']),
              'revision': revision,
            }),
          );
          if (payload['before'] == null) action['transactionId'] = result['id'];
        case 'delete':
          await ledger.invoke('deleteTransaction', {
            'id': payload['transactionId'],
            'editVersion': (payload['before'] as Map)['editVersion'],
            'revision': revision,
          });
        case 'budget':
          await ledger.invoke('updateSettings', {
            'revision': revision,
            'budgetCents': payload['amount'],
            'expectedBudgetCents': payload['before'] ?? 0,
          });
        default:
          throw const LedgerException('不支持此提案。');
      }
      action['status'] = 'applied';
    } catch (_) {
      action['status'] = 'uncertain';
      await _save(session);
      rethrow;
    }
    await _save(session);
    return '应用已执行并记录提案 ${action['title']}（$actionId），状态 applied。请按需要查询最新账本并继续原任务。';
  }

  void _requireIdle() {
    if (running) throw const LedgerException('请先停止或等待当前任务完成。');
  }

  static bool _sameWireModel(String previous, String next) {
    final old = previous.split(':'), current = next.split(':');
    if (old.length >= 4 &&
        current.length >= 4 &&
        const {'deepseek', 'qwen'}.contains(old.first)) {
      // Only the final component is a key fingerprint. A different endpoint may
      // implement another replay protocol even when it accepts the same model id.
      return previous.substring(0, previous.lastIndexOf(':')) ==
          next.substring(0, next.lastIndexOf(':'));
    }
    // GPT reasoning is bound to both its account and model.
    return previous == next;
  }

  static List<Map<String, dynamic>> _handoffHistory(AgentSession session) {
    final history = <Map<String, dynamic>>[];
    for (final message in session.messages.skip(session.contextStart)) {
      if (message['contextHandoff'] == true) {
        final data = agentMap(jsonDecode(message['content'] as String));
        history.addAll((data['history'] as List).map(agentMap));
        continue;
      }
      if (message['internal'] == true ||
          message['provisional'] == true ||
          !const {'user', 'assistant'}.contains(message['role'])) {
        continue;
      }
      final content = message['content'] as String? ?? '';
      if (content.isNotEmpty) {
        history.add({'role': message['role'], 'content': content});
      }
    }
    final recent = <Map<String, dynamic>>[];
    var characters = 2;
    const shortened = '…[原文可检索]';
    for (final row in history.reversed) {
      final original = row['content'] as String;
      final content = original.length <= 2000
          ? original
          : '${original.substring(0, 2000 - shortened.length)}$shortened';
      final excerpt = {'role': row['role'], 'content': content};
      final size = jsonEncode(excerpt).length + (recent.isEmpty ? 0 : 1);
      if (characters + size > 6000) break;
      recent.add(excerpt);
      characters += size;
    }
    return recent.reversed.toList();
  }

  static bool _needsReasoningPart(Map<String, dynamic> message) =>
      message['role'] == 'assistant' &&
      ((message['reasoning_summary'] is String &&
              (message['reasoning_summary'] as String).isNotEmpty) ||
          (message['reasoning_content'] is String &&
              (message['reasoning_content'] as String).isNotEmpty)) &&
      !(message['parts'] as List? ?? const []).any(
        (part) => part is Map && part['type'] == 'reasoning',
      );

  Future<void> _save(AgentSession session) async {
    String? runId;
    for (final message in session.messages) {
      message['id'] ??= agentId();
      if (message['role'] == 'user' && message['internal'] != true) {
        runId = message['runId'] as String? ?? 'legacy:${message['id']}';
      }
      message['runId'] ??= runId ?? 'legacy:${session.id}';
      if (_needsReasoningPart(message)) {
        final summary = message['reasoning_summary'] as String?;
        message['parts'] = [
          ...(message['parts'] as List? ?? const []),
          {
            'type': 'reasoning',
            'id': '${message['id']}:reasoning',
            'messageId': message['id'],
            'runId': message['runId'],
            'text': summary?.isNotEmpty == true
                ? summary
                : message['reasoning_content'],
            'kind': summary?.isNotEmpty == true
                ? 'summary'
                : 'reasoning_content',
            'status': 'completed',
            'provider': session.modelIdentity.split(':').first,
            'model': '',
          },
        ];
      }
    }
    for (final action in session.actions.where(
      (action) => action['runId'] == null,
    )) {
      final createdAt = action['createdAt'] as int? ?? session.createdAt;
      final owner = session.messages
          .where(
            (message) =>
                message['role'] == 'user' &&
                message['internal'] != true &&
                (message['createdAt'] as int? ?? session.createdAt) <=
                    createdAt,
          )
          .lastOrNull;
      action['runId'] = owner?['runId'] ?? 'legacy:${session.id}';
    }
    session.updatedAt = _clock().millisecondsSinceEpoch;
    await store.write(
      'session:${session.revision}:${session.id}',
      session.toMap(),
    );
  }

  static void _repairPendingCalls(AgentSession session, String reason) {
    final answered = session.messages
        .where((m) => m['role'] == 'tool')
        .map((m) => m['tool_call_id'])
        .toSet();
    final missing = <String>[];
    for (final m in session.messages) {
      for (final call in m['tool_calls'] as List? ?? []) {
        final id = (call as Map)['id'] as String;
        if (!answered.contains(id)) missing.add(id);
      }
    }
    for (final id in missing) {
      session.messages.add({
        'role': 'tool',
        'tool_call_id': id,
        'content': jsonEncode({'error': 'interrupted', 'message': reason}),
      });
    }
    for (final action in session.actions) {
      if (action['status'] == 'applying') action['status'] = 'uncertain';
    }
    for (final trace in session.trace) {
      if (trace['status'] == 'running') trace['status'] = 'interrupted';
    }
  }

  static void _validateReply(
    AgentReply reply,
    Iterable<Map<String, dynamic>> history,
  ) {
    if (reply.calls.length > 16 ||
        reply.calls.isEmpty && reply.text.trim().isEmpty) {
      throw const LedgerException('模型返回了空内容或过多工具调用。', code: 'AGENT_PROTOCOL');
    }
    final ids = <String>{
      for (final message in history)
        for (final call in message['tool_calls'] as List? ?? const [])
          agentMap(call)['id'] as String,
    };
    for (final call in reply.calls) {
      final function = agentMap(call['function']);
      if (call['id'] is! String ||
          (call['id'] as String).isEmpty ||
          !ids.add(call['id'] as String) ||
          function['name'] is! String ||
          function['arguments'] is! String ||
          (function['arguments'] as String).length >
              (function['name'] == 'render_artifact' ? 96 * 1024 : 16000)) {
        throw const LedgerException('模型工具调用格式无效。', code: 'AGENT_PROTOCOL');
      }
    }
  }

  static Object? _canonical(Object? value) {
    if (value is Map) {
      final keys = value.keys.cast<String>().toList()..sort();
      return {for (final key in keys) key: _canonical(value[key])};
    }
    return value is List ? value.map(_canonical).toList() : value;
  }

  static Future<T> _cancellable<T>(
    Future<T> work,
    AgentCancellation cancel,
  ) async {
    final stopped = Completer<T>();
    void stop() {
      if (!stopped.isCompleted) {
        try {
          cancel.check();
        } catch (error) {
          stopped.completeError(error);
        }
      }
    }

    cancel.listen(stop);
    try {
      return await Future.any([work, stopped.future]);
    } finally {
      cancel.unlisten(stop);
    }
  }

  static String toolLabel(String name) =>
      const {
        'get_ledger_overview': '正在读取账本概况',
        'query_transactions': '正在查询流水',
        'get_transaction': '正在核对交易',
        'aggregate_by': '正在汇总收支',
        'compare_periods': '正在比较时间段',
        'get_budget': '正在检查预算',
        'present_insight': '正在绘制账本图表',
        'render_artifact': '正在生成交互内容',
        'ask_user': '正在整理需要补充的信息',
        'search_memory': '正在查找记忆',
        'remember': '正在保存偏好',
        'forget_memory': '正在删除记忆',
        'search_conversation': '正在搜索历史对话',
        'read_conversation': '正在读取原对话',
        'update_plan': '正在更新执行计划',
        'propose_transaction': '正在准备账单提案',
        'propose_delete_transaction': '正在准备删除提案',
        'propose_budget': '正在准备预算提案',
      }[name] ??
      '正在执行工具';
}

import 'dart:async';
import 'dart:convert';

import '../ledger_repository.dart';
import '../models.dart';
import 'agent_context.dart';
import 'agent_models.dart';
import 'agent_prompt.dart';
import 'agent_store.dart';
import 'ledger_agent_tools.dart';

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

  Future<Map<String, dynamic>> run({
    required String question,
    required LedgerSnapshot ledger,
    required AgentModel model,
    required Future<void> Function() guard,
  }) async {
    _requireIdle();
    if (question.trim().isEmpty || question.length > 16000) {
      throw const LedgerException('请输入1～16000字的任务。');
    }
    final cancellation = AgentCancellation();
    _cancellation = cancellation;
    _finished = Completer<void>();
    final started = _clock();
    final runId = agentId();
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
    try {
      await check();
      session = await active(ledger.revision);
      _repairPendingCalls(session, '上一轮已结束，工具没有可确认的完成结果。');
      if (session.modelIdentity != model.identity) {
        // Common messages/tools can move between providers; opaque reasoning
        // must stay with its original account and model.
        for (final message in session.messages) {
          message.remove('responsesItems');
          message.remove('responsesAccountId');
          message.remove('reasoning_content');
        }
      }
      session.modelIdentity = model.identity;
      session.status = 'running';
      session.plan.clear();
      session.messages.add({
        'role': 'user',
        'content': question.trim(),
        'createdAt': started.millisecondsSinceEpoch,
      });
      if (session.title == '新对话') {
        session.title = question.trim().substring(
          0,
          question.trim().length.clamp(0, 36),
        );
      }
      await _save(session);
      final settings = await config();
      final tools = LedgerAgentTools(
        store: store,
        session: session,
        ledger: ledger,
        question: question,
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
      final system = ledgerAgentPrompt(ledger, started);
      final cancellableModel = AgentModel(
        identity: model.identity,
        complete: (messages, tools) =>
            _cancellable(model.complete(messages, tools), cancellation),
      );
      Map<String, dynamic>? chart;
      String? waitingQuestion;
      var calls = 0, correctiveAttempts = 0;
      final repeats = <String, int>{};
      for (var step = 1; step <= settings.maxSteps; step++) {
        await check();
        void progress(String text) => onProgress?.call({
          'sessionId': session!.id,
          'revision': ledger.revision,
          'phase': 'model',
          'step': step,
          'maxSteps': settings.maxSteps,
          'text': text,
          'plan': session.plan,
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
        final reply = await _cancellable(
          model.complete(
            AgentContext.project(session, system, memories),
            LedgerAgentTools.definitions,
          ),
          cancellation,
        );
        await check();
        session.inputTokens += reply.inputTokens ?? 0;
        session.outputTokens += reply.outputTokens ?? 0;
        _validateReply(reply);
        final assistant = {
          ...reply.message,
          'role': 'assistant',
          'createdAt': _clock().millisecondsSinceEpoch,
        };
        session.messages.add(assistant);
        if (reply.calls.isEmpty) {
          final unfinished = session.plan.any(
            (p) => p['status'] != 'completed',
          );
          if (unfinished &&
              !session.actions.any((a) => a['status'] == 'pending')) {
            if (++correctiveAttempts > 2) {
              throw const LedgerException(
                '任务计划仍有未完成步骤，已保留进度，请继续任务。',
                code: 'AGENT_INCOMPLETE',
              );
            }
            assistant['provisional'] = true;
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
            if (++correctiveAttempts > 2) {
              throw const LedgerException(
                '模型未提供可核实的账本依据，本次回答未采纳。请重试或换用支持工具调用的模型。',
                code: 'AGENT_EVIDENCE',
              );
            }
            assistant['provisional'] = true;
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
          return {
            'answer': reply.text,
            'isError': false,
            'chartData': ?chart,
            ...await state(ledger.revision),
          };
        }
        await _save(
          session,
        ); // assistant and all call ids are durable before execution
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
          onProgress?.call({
            'sessionId': session.id,
            'revision': ledger.revision,
            'phase': 'tool',
            'step': step,
            'maxSteps': settings.maxSteps,
            'text': toolLabel(name),
            'tool': name,
            'plan': session.plan,
          });
          Map<String, dynamic> result;
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
            result = await tools.execute(name, args);
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
          session.messages.add({
            'role': 'tool',
            'tool_call_id': call['id'],
            'content': jsonEncode(result),
            'createdAt': _clock().millisecondsSinceEpoch,
          });
          await _save(
            session,
          ); // side effects/results are never replayed on restart
        }
        if (waitingQuestion != null) {
          session.status = 'awaiting_input';
          session.messages.add({
            'role': 'assistant',
            'content': waitingQuestion,
            'createdAt': _clock().millisecondsSinceEpoch,
          });
          await _save(session);
          return {
            'answer': waitingQuestion,
            'isError': false,
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
      _repairPendingCalls(session, '本次工具执行被停止或未能确认完成，请重新查询。');
      final message = error is LedgerException
          ? error.message
          : '任务未完成，已保留执行记录，请重试。';
      session.status =
          error is LedgerException && error.code == 'AGENT_CANCELLED'
          ? 'cancelled'
          : 'failed';
      session.messages.add({
        'role': 'assistant',
        'content': message,
        'isError': true,
        'createdAt': _clock().millisecondsSinceEpoch,
      });
      await _save(session);
      return {
        'answer': message,
        'isError': true,
        ...await state(ledger.revision),
      };
    } finally {
      _cancellation = null;
      _finished?.complete();
      _finished = null;
      watch.stop();
      deadline.cancel();
    }
  }

  /// Only this method can apply a stored proposal. UI sends its id and decision,
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
          await ledger.invoke('saveTransaction', {
            ...agentMap(payload['transaction']),
            'revision': revision,
          });
        case 'delete':
          await ledger.invoke('deleteTransaction', {
            'id': payload['transactionId'],
            'editVersion': (payload['before'] as Map)['editVersion'],
            'revision': revision,
          });
        case 'budget':
          await ledger.invoke('updateSettings', {
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

  Future<void> _save(AgentSession session) async {
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

  static void _validateReply(AgentReply reply) {
    if (reply.calls.length > 16 ||
        reply.calls.isEmpty && reply.text.trim().isEmpty) {
      throw const LedgerException('模型返回了空内容或过多工具调用。', code: 'AGENT_PROTOCOL');
    }
    final ids = <String>{};
    for (final call in reply.calls) {
      final function = agentMap(call['function']);
      if (call['id'] is! String ||
          (call['id'] as String).isEmpty ||
          !ids.add(call['id'] as String) ||
          function['name'] is! String ||
          function['arguments'] is! String ||
          (function['arguments'] as String).length > 16000) {
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

import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/core/ai/model_reasoning.dart';
import 'package:pennypilot/core/models.dart';

import '../ui/test_ledger_repository.dart';

class _StreamingRepository extends TestLedgerRepository {
  Completer<Object?>? answer;
  bool cancelWithError = false;
  @override
  Future<Object?> invoke(String method, Map<String, dynamic> arguments) {
    if (method == 'cancelAgent' && cancelWithError) {
      calls.add((method: method, arguments: arguments));
      answer!.completeError(const LedgerException('任务已停止', code: 'CANCELLED'));
      return Future.value(null);
    }
    if (method != 'ask') return super.invoke(method, arguments);
    calls.add((method: method, arguments: arguments));
    answer = Completer<Object?>();
    return answer!.future;
  }

  void emit(String phase, [Map<String, dynamic> values = const {}]) =>
      updates.add(
        LedgerEvent('agent', 0, {
          'phase': phase,
          'sessionId': 's',
          'runId': 'r',
          ...values,
        }),
      );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late _StreamingRepository repository;
  late LedgerController controller;
  setUp(() async {
    repository = _StreamingRepository();
    controller = LedgerController(repository: repository);
    await controller.init();
  });
  tearDown(() async {
    controller.dispose();
    await repository.updates.close();
  });

  test(
    'real deltas appear before completion and settle into one answer',
    () async {
      final task = controller.ask('你好');
      await Future<void>.delayed(Duration.zero);
      repository.emit('run_started', {'userMessageId': 'u'});
      repository.emit('text_start', {'messageId': 'a'});
      repository.emit('text_delta', {'messageId': 'a', 'delta': '**你'});
      repository.emit('text_delta', {'messageId': 'a', 'delta': '好**'});
      expect(controller.messages.last.text, '**你好**');
      expect(controller.messages.last.isStreaming, true);
      expect(controller.isAsking, true);
      repository.answer!.complete({'answer': '**你好**', 'isError': false});
      await task;
      expect(controller.messages, hasLength(2));
      expect(controller.messages.last.isStreaming, false);
      repository.emit('text_delta', {'messageId': 'a', 'delta': '迟到的内容'});
      expect(controller.messages.last.text, '**你好**');
    },
  );

  test(
    'rejected previews disappear and transport errors preserve accepted partial text',
    () async {
      final task = controller.ask('你好');
      await Future<void>.delayed(Duration.zero);
      repository.emit('run_started', {'userMessageId': 'u'});
      repository.emit('text_start', {'messageId': 'a'});
      repository.emit('text_delta', {'messageId': 'a', 'delta': '不能采纳的数字'});
      repository.emit('text_reset', {'messageId': 'a'});
      expect(controller.messages.last.text, isEmpty);
      repository.emit('text_start', {'messageId': 'b'});
      repository.emit('text_delta', {'messageId': 'b', 'delta': '已查询到的部分说明'});
      repository.answer!.completeError(const LedgerException('连接中断'));
      await expectLater(task, throwsA(isA<LedgerException>()));
      expect(controller.messages, hasLength(2));
      expect(controller.messages.last.isStreaming, false);
      expect(controller.messages.last.parts.single['text'], '已查询到的部分说明');
      expect(controller.retryQuestion, '你好');
      final retry = controller.retryAsk();
      await Future<void>.delayed(Duration.zero);
      expect(repository.calls.last.arguments['retry'], true);
      expect(controller.messages.where((m) => m.role == 'user'), hasLength(1));
      repository.answer!.complete({'answer': '已继续完成', 'isError': false});
      await retry;
    },
  );

  test(
    'query navigation preserves inclusive date range and category scope',
    () {
      controller.openLedgerQuery({
        'start': '2026-10-10',
        'end': '2026-11-01',
        'category_id': 1,
      });
      expect(controller.rangeStart, DateTime(2026, 10, 10));
      expect(controller.rangeEnd, DateTime(2026, 11, 2));
      expect(controller.categoryFilter, 1);
      expect(controller.launchTarget?['route'], 'ledger');
      controller.setMonth(DateTime(2026, 9));
      expect(controller.rangeStart, isNull);
    },
  );

  Map<String, dynamic> reasoningPart(
    String id,
    String text, {
    String status = 'streaming',
    int? endedAt,
  }) => {
    'type': 'reasoning',
    'id': id,
    'messageId': id,
    'runId': 'r',
    'text': text,
    'kind': 'reasoning_content',
    'status': status,
    'startedAt': 1000,
    'endedAt': ?endedAt,
    'provider': 'deepseek',
    'model': 'deepseek-flash',
  };

  test(
    'reasoning snapshots upsert each model step and stay separate from answer deltas',
    () async {
      final task = controller.ask('看看支出');
      await Future<void>.delayed(Duration.zero);
      repository.emit('run_started', {'userMessageId': 'u'});
      repository.emit('reasoning_start', {
        'part': reasoningPart('a:reasoning', ''),
      });
      repository.emit('reasoning_delta', {
        'part': reasoningPart('a:reasoning', '先查询'),
        'delta': '先查询',
      });
      repository.emit('reasoning_delta', {
        'part': reasoningPart('a:reasoning', '先查询本月账单'),
        'delta': '本月账单',
      });
      expect(controller.messages.last.text, isEmpty);
      expect(
        controller.messages.last.parts.where((p) => p['type'] == 'reasoning'),
        hasLength(1),
      );
      repository.emit('reasoning_complete', {
        'part': reasoningPart(
          'a:reasoning',
          '先查询本月账单',
          status: 'completed',
          endedAt: 2500,
        ),
      });
      repository.emit('text_start', {'messageId': 'b'});
      repository.emit('reasoning_start', {
        'part': reasoningPart('b:reasoning', '再核对分类'),
      });
      repository.emit('text_delta', {'messageId': 'b', 'delta': '已核对账本。'});
      expect(controller.messages.last.text, '已核对账本。');
      expect(controller.messages.last.parts.map((p) => p['text']), [
        '先查询本月账单',
        '再核对分类',
      ]);
      repository.emit('reasoning_delta', {
        'runId': 'old-run',
        'part': reasoningPart('wrong', '旧任务的推理'),
      });
      expect(controller.messages.last.parts, hasLength(2));
      repository.emit('reasoning_complete', {
        'part': reasoningPart(
          'b:reasoning',
          '再核对分类',
          status: 'completed',
          endedAt: 3000,
        ),
      });
      repository.answer!.complete({'answer': '已核对账本。', 'isError': false});
      await task;
      expect(controller.messages, hasLength(2));
      expect(controller.messages.last.parts.map((p) => p['status']), [
        'completed',
        'completed',
      ]);
      repository.emit('reasoning_delta', {
        'part': reasoningPart('late', '任务结束后的迟到推理'),
      });
      expect(controller.messages.last.parts, hasLength(2));
    },
  );

  test(
    'visible reasoning options stay frozen for the running task and shortcuts reject changes',
    () async {
      repository.data['settings'] = {
        ...repository.data['settings'] as Map<String, dynamic>,
        'activeProvider': 'deepseek',
        'deepSeekModel': 'deepseek-flash',
        'deepSeekThinkingEnabled': true,
        'deepSeekReasoningEffort': 'high',
      };
      await controller.refresh();
      final task = controller.ask('你好');
      await Future<void>.delayed(Duration.zero);
      final actual = resolveReasoningOptions({
        ...controller.settings,
        'deepSeekReasoningEffort': 'max',
      });
      repository.emit('run_started', {
        'userMessageId': 'u',
        'reasoning': actual.toMap(),
      });
      expect(controller.assistantReasoningOptions.effort, 'max');
      repository.data['settings'] = {
        ...repository.data['settings'] as Map<String, dynamic>,
        'deepSeekThinkingEnabled': false,
        'deepSeekReasoningEffort': 'low',
      };
      await controller.refresh();
      expect(controller.assistantReasoningOptions.enabled, true);
      expect(controller.assistantReasoningOptions.effort, 'max');
      await expectLater(
        controller.setAssistantReasoning(enabled: false),
        throwsA(isA<LedgerException>()),
      );
      expect(
        repository.calls.where((call) => call.method == 'updateSettings'),
        isEmpty,
      );
      repository.answer!.complete({'answer': '你好。', 'isError': false});
      await task;
      expect(controller.assistantReasoningOptions.enabled, false);
      expect(controller.assistantReasoningOptions.effort, 'low');
    },
  );

  test(
    'stopping preserves completed and partial reasoning and ignores later deltas',
    () async {
      repository.cancelWithError = true;
      final task = controller.ask('检查账本');
      final failed = expectLater(task, throwsA(isA<LedgerException>()));
      await Future<void>.delayed(Duration.zero);
      repository.emit('run_started', {'userMessageId': 'u'});
      repository.emit('reasoning_complete', {
        'part': reasoningPart(
          'a:reasoning',
          '已查本月',
          status: 'completed',
          endedAt: 1500,
        ),
      });
      repository.emit('reasoning_delta', {
        'part': reasoningPart('b:reasoning', '正在比较分类'),
      });
      repository.emit('reasoning_complete', {
        'part': reasoningPart(
          'b:reasoning',
          '正在比较分类',
          status: 'cancelled',
          endedAt: 2000,
        ),
      });
      await controller.stopAgent();
      await failed;
      expect(controller.isAsking, false);
      expect(controller.messages.last.isStreaming, false);
      expect(controller.messages.last.parts.map((p) => p['text']), [
        '已查本月',
        '正在比较分类',
      ]);
      expect(controller.messages.last.parts.map((p) => p['status']), [
        'completed',
        'cancelled',
      ]);
      repository.emit('reasoning_delta', {
        'part': reasoningPart('b:reasoning', '不应覆盖已停止内容'),
      });
      expect(controller.messages.last.parts.last['text'], '正在比较分类');
    },
  );

  test(
    'a late run-start event cannot replace an already active newer run',
    () async {
      final task = controller.ask('新任务');
      await Future<void>.delayed(Duration.zero);
      repository.emit('run_started', {
        'runId': 'new-run',
        'userMessageId': 'new-user',
      });
      repository.emit('reasoning_start', {
        'runId': 'new-run',
        'part': reasoningPart('new:reasoning', '新任务内容'),
      });
      repository.emit('run_started', {
        'runId': 'old-run',
        'userMessageId': 'old-user',
      });
      repository.emit('reasoning_delta', {
        'runId': 'old-run',
        'part': reasoningPart('old:reasoning', '迟到的旧任务'),
      });
      expect(
        controller.messages.where((m) => m.role == 'assistant'),
        hasLength(1),
      );
      expect(controller.messages.last.runId, 'new-run');
      expect(controller.messages.last.parts.single['text'], '新任务内容');
      repository.answer!.complete({'answer': '新任务完成', 'isError': false});
      await task;
    },
  );
}

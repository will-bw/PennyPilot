import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/agent/agent_context.dart';
import 'package:pennypilot/core/agent/agent_models.dart';
import 'package:pennypilot/core/agent/agent_runtime.dart';
import 'package:pennypilot/core/agent/agent_store.dart';
import 'package:pennypilot/core/models.dart';

import '../../ui/test_ledger_repository.dart';

AgentReply _answer(String text) =>
    AgentReply(message: {'role': 'assistant', 'content': text});

void main() {
  final ledger = LedgerSnapshot.fromMap(fixtureSnapshot(populated: true));
  late MemoryAgentStore store;
  late LedgerAgentRuntime runtime;
  late List<Map<String, dynamic>> events;
  setUp(() {
    store = MemoryAgentStore();
    events = [];
    runtime = LedgerAgentRuntime(
      store: store,
      onProgress: (event) => events.add(event),
    );
  });

  test(
    'stream deltas are visible before the durable completed answer',
    () async {
      final started = Completer<void>(), finish = Completer<AgentReply>();
      final task = runtime.run(
        question: '你好',
        ledger: ledger,
        guard: () async {},
        model: AgentModel(
          identity: 'test:stream',
          complete: (_, _) async => _answer('fallback'),
          stream: (_, _, delta) {
            delta('你');
            delta('好');
            started.complete();
            return finish.future;
          },
        ),
      );
      await started.future;
      expect(
        events
            .where((event) => event['phase'] == 'text_delta')
            .map((event) => event['delta']),
        ['你', '好'],
      );
      expect(events.any((event) => event['phase'] == 'run_finished'), false);
      finish.complete(_answer('你好'));
      final result = await task;
      final messages = (result['session'] as Map)['messages'] as List;
      expect(
        messages.every(
          (message) => message['id'] is String && message['runId'] is String,
        ),
        true,
      );
      expect(messages.first['runId'], messages.last['runId']);
      expect(
        events.every(
          (event) =>
              event['sessionId'] != null &&
              event['revision'] == 0 &&
              event['runId'] != null,
        ),
        true,
      );
      expect(events.last['phase'], 'run_finished');
    },
  );

  test(
    'artifact projection keeps a receipt and leaves the stored HTML intact',
    () {
      final session = AgentSession.create(0, DateTime(2026, 11, 1));
      session.messages.add({
        'role': 'tool',
        'tool_call_id': 'artifact-call',
        'content': jsonEncode({
          'status': 'presented',
          'presentation': {
            'type': 'artifact',
            'title': '消费报告',
            'evidenceId': 'Q1',
            'content': '<html>PRIVATE_FULL_ARTIFACT</html>',
            'data': {'huge': 'DATA_COPY'},
          },
        }),
      });
      final projected = AgentContext.project(session, 'system', []);
      expect(jsonEncode(projected), isNot(contains('PRIVATE_FULL_ARTIFACT')));
      expect(jsonEncode(projected), isNot(contains('DATA_COPY')));
      expect(jsonEncode(projected), contains('消费报告'));
      expect(jsonEncode(session.messages), contains('PRIVATE_FULL_ARTIFACT'));
    },
  );

  test('restart restores the saved prefix on the interrupted run', () async {
    final started = Completer<void>(), finish = Completer<AgentReply>();
    final task = runtime.run(
      question: '你好',
      ledger: ledger,
      guard: () async {},
      model: AgentModel(
        identity: 'test:restart',
        complete: (_, _) => finish.future,
        reasoningStream: (_, _, delta, reasoning) {
          reasoning('先核对上下文');
          delta('已收到');
          started.complete();
          return finish.future;
        },
      ),
    );
    await started.future;
    final live = runtime.livePreview!;
    expect(live['text'], '已收到');
    expect(live['sequence'], events.last['previewSequence']);
    await runtime.flushPreview();
    final previewKey = 'preview:0:${live['sessionId']}';
    expect(await store.read(previewKey), live);
    final checkpoint = (await store.read('session:0:${live['sessionId']}'))!;
    expect(checkpoint['activeRunId'], live['runId']);
    expect(jsonEncode(checkpoint), isNot(contains('已收到')));

    // Copy only committed state to simulate the next process.
    final disk = MemoryAgentStore();
    await disk.write('active:0', {'id': live['sessionId']});
    await disk.write('session:0:${live['sessionId']}', checkpoint);
    await disk.write(previewKey, live);
    final restored = await LedgerAgentRuntime(store: disk).active(0);
    expect(restored.status, 'interrupted');
    expect(restored.messages.last['runId'], live['runId']);
    final parts = restored.messages.last['parts'] as List;
    expect(parts.first, {'type': 'interrupted_text', 'text': '已收到'});
    expect(parts.last['type'], 'reasoning');
    expect(parts.last['text'], '先核对上下文');
    expect(await disk.read(previewKey), isNull);
    expect(
      jsonEncode(AgentContext.project(restored, 'system', [])),
      allOf(isNot(contains('已收到')), isNot(contains('先核对上下文'))),
    );

    finish.complete(_answer('完整回答'));
    final completed = await task;
    expect((completed['session'] as Map)['status'], 'completed');
    expect(runtime.livePreview, isNull);
    expect(await store.read(previewKey), isNull);
  });

  test('the user input is committed before model setup starts', () async {
    var opens = 0;
    final result = await runtime.run(
      question: '保存这个问题',
      ledger: ledger,
      guard: () async {},
      openModel: () async {
        opens++;
        final active = await store.read('active:0');
        final row = await store.read('session:0:${active!['id']}');
        expect(row!['status'], 'running');
        expect((row['messages'] as List).single['content'], '保存这个问题');
        throw StateError('offline');
      },
    );
    expect(opens, 1);
    final session = result['session'] as Map;
    expect(session['status'], 'failed');
    expect(session['activeRunId'], isNull);
    expect((session['messages'] as List).first['content'], '保存这个问题');
  });

  test(
    'a completed tool call discards its transient text before the next turn',
    () async {
      final firstStarted = Completer<void>(), secondStarted = Completer<void>();
      final first = Completer<AgentReply>(), second = Completer<AgentReply>();
      var turns = 0;
      final task = runtime.run(
        question: '你好',
        ledger: ledger,
        guard: () async {},
        model: AgentModel(
          identity: 'test:tool-preview',
          complete: (_, _) async => _answer('fallback'),
          stream: (_, _, delta) {
            if (++turns == 1) {
              delta('工具前的预览');
              firstStarted.complete();
              return first.future;
            }
            delta('新的回答');
            secondStarted.complete();
            return second.future;
          },
        ),
      );
      await firstStarted.future;
      final original = runtime.livePreview!;
      final previewKey = 'preview:0:${original['sessionId']}';
      await runtime.flushPreview();
      first.complete(
        AgentReply(
          message: {
            'role': 'assistant',
            'content': '',
            'tool_calls': [
              {
                'id': 'read-budget',
                'type': 'function',
                'function': {
                  'name': 'get_budget',
                  'arguments': '{"month":"2026-11"}',
                },
              },
            ],
          },
        ),
      );
      await secondStarted.future;
      expect(await store.read(previewKey), isNull);
      expect(runtime.livePreview!['runId'], original['runId']);
      expect(runtime.livePreview!['messageId'], isNot(original['messageId']));
      expect(runtime.livePreview!['text'], '新的回答');
      second.complete(_answer('新的回答'));
      await task;
      expect(await store.read(previewKey), isNull);
    },
  );

  test(
    'cancel persists the partial answer and suppresses late preview callbacks',
    () async {
      final started = Completer<void>(), finish = Completer<AgentReply>();
      late void Function(String) lateDelta;
      final task = runtime.run(
        question: '你好',
        ledger: ledger,
        guard: () async {},
        model: AgentModel(
          identity: 'test:cancel',
          complete: (_, _) => finish.future,
          stream: (_, _, delta) {
            lateDelta = delta;
            delta('部分答案');
            started.complete();
            return finish.future;
          },
        ),
      );
      await started.future;
      runtime.cancel();
      final result = await task;
      final session = result['session'] as Map;
      expect(session['status'], 'cancelled');
      expect(((session['messages'] as List).last['parts'] as List).single, {
        'type': 'interrupted_text',
        'text': '部分答案',
      });
      final count = events.length;
      lateDelta('不应出现');
      finish.complete(_answer('不应采纳'));
      await Future<void>.delayed(Duration.zero);
      expect(events, hasLength(count));
      expect(
        jsonEncode((await runtime.state(0))['session']),
        isNot(contains('不应出现')),
      );
    },
  );

  test(
    'invalid ledger previews reset before evidence correction and tool progress',
    () async {
      var turn = 0;
      final result = await runtime.run(
        question: '本月支出多少',
        ledger: ledger,
        guard: () async {},
        model: AgentModel(
          identity: 'test:validate',
          complete: (_, _) async => _answer('unused'),
          stream: (messages, _, delta) async {
            if (++turn == 1) {
              delta('没有依据的错误答案');
              return _answer('没有依据的错误答案');
            }
            if (turn == 2) {
              return AgentReply(
                message: {
                  'role': 'assistant',
                  'content': '',
                  'tool_calls': [
                    {
                      'id': 'c1',
                      'type': 'function',
                      'function': {
                        'name': 'get_budget',
                        'arguments': '{"month":"2026-10"}',
                      },
                    },
                  ],
                },
              );
            }
            final observation =
                jsonDecode(
                      messages.lastWhere(
                            (message) => message['role'] == 'tool',
                          )['content']
                          as String,
                    )
                    as Map;
            delta('核对完成');
            return _answer('核对完成。[依据 ${observation['evidence_id']}]');
          },
        ),
      );
      expect(result['isError'], false);
      expect(
        events.where((event) => event['phase'] == 'text_reset'),
        isNotEmpty,
      );
      expect(
        events
            .where((event) => event['phase'] == 'tool_result')
            .single['result'],
        isA<Map>(),
      );
      final messages = (result['session'] as Map)['messages'] as List;
      expect(
        messages.where((message) => message['provisional'] == true),
        hasLength(1),
      );
      expect(messages.last['parts'], isNull);
    },
  );

  test(
    'local recognition receipts update the same run without leaking attachment content to the model',
    () async {
      Future<Map<String, dynamic>> append(String answer, String status) =>
          runtime.appendInput(
            revision: 0,
            question: '识别这张收据',
            source: 'AI_IMAGE',
            answer: answer,
            parts: [
              {'type': 'receipt', 'status': status},
            ],
            status: 'completed',
            runId: 'image-run',
            attachment: {
              'type': 'attachment',
              'path': '/private/receipt.png',
              'name': 'receipt.png',
              'mimeType': 'image/png',
            },
            guard: () async {},
          );
      await append('已识别一笔', 'saved');
      final result = await append('已撤销一笔', 'undone');
      final messages = (result['session'] as Map)['messages'] as List;
      expect(messages, hasLength(2));
      expect(messages.last['content'], '已撤销一笔');
      expect(messages.last['parts'].single['status'], 'undone');
      final projection = AgentContext.project(
        await runtime.active(0),
        'system',
        [],
      );
      expect(jsonEncode(projection), isNot(contains('/private/receipt.png')));
      expect(jsonEncode(projection), isNot(contains('"parts"')));
      expect(jsonEncode(projection), contains('已撤销一笔'));
    },
  );
}

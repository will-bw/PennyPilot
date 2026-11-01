import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/agent/agent_models.dart';
import 'package:pennypilot/core/agent/agent_runtime.dart';
import 'package:pennypilot/core/agent/agent_store.dart';
import 'package:pennypilot/core/models.dart';

import '../../ui/test_ledger_repository.dart';

const _config = {
  'provider': 'deepseek',
  'model': 'deepseek-flash',
  'enabled': true,
  'kind': 'reasoning_content',
  'effort': 'high',
};
AgentReply _answer(String text, {String? reasoning}) => AgentReply(
  message: {
    'role': 'assistant',
    'content': text,
    'reasoning_content': ?reasoning,
  },
);
List<Map<String, dynamic>> _parts(Map session) => (session['messages'] as List)
    .expand((message) => message['parts'] as List? ?? const [])
    .whereType<Map>()
    .map((part) => Map<String, dynamic>.from(part))
    .where((part) => part['type'] == 'reasoning')
    .toList();

void main() {
  final ledger = LedgerSnapshot.fromMap(fixtureSnapshot(populated: true));
  late MemoryAgentStore store;
  late LedgerAgentRuntime runtime;
  late List<Map<String, dynamic>> events;
  var now = DateTime(2026, 11, 1, 12);
  setUp(() {
    now = DateTime(2026, 11, 1, 12);
    store = MemoryAgentStore();
    events = [];
    runtime = LedgerAgentRuntime(
      store: store,
      clock: () => now,
      onProgress: events.add,
    );
  });

  test(
    'each model step preserves its own reasoning after tools, restart and model changes',
    () async {
      var step = 0;
      final result = await runtime.run(
        question: '本月预算还剩多少',
        ledger: ledger,
        guard: () async {},
        model: AgentModel(
          identity: 'deepseek:test',
          reasoningConfig: _config,
          complete: (_, _) async => _answer('unused'),
          reasoningStream: (messages, _, text, thought) async {
            if (++step == 1) {
              thought('先查询真实预算');
              now = now.add(const Duration(seconds: 2));
              return AgentReply(
                message: {
                  'role': 'assistant',
                  'content': '',
                  'reasoning_content': '先查询真实预算',
                  'tool_calls': [
                    {
                      'id': 'budget-one',
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
            thought('根据查询结果作答');
            now = now.add(const Duration(seconds: 3));
            text('预算已核对');
            now = now.add(const Duration(seconds: 1));
            final observation =
                jsonDecode(
                      messages.lastWhere(
                            (message) => message['role'] == 'tool',
                          )['content']
                          as String,
                    )
                    as Map;
            return _answer(
              '预算已核对。[依据 ${observation['evidence_id']}]',
              reasoning: '根据查询结果作答',
            );
          },
        ),
      );
      final parts = _parts(result['session'] as Map);
      expect(parts.map((part) => part['text']), ['先查询真实预算', '根据查询结果作答']);
      expect(parts.map((part) => part['id']).toSet(), hasLength(2));
      expect(
        parts.every(
          (part) =>
              part['status'] == 'completed' &&
              part['provider'] == 'deepseek' &&
              part['kind'] == 'reasoning_content',
        ),
        true,
      );
      expect(parts.every((part) => part['effort'] == 'high'), true);
      expect(
        (parts.last['endedAt'] as int) - (parts.last['startedAt'] as int),
        3000,
      );
      expect(
        events.firstWhere(
          (event) => event['phase'] == 'run_started',
        )['reasoning'],
        _config,
      );
      expect(
        events.where((event) => event['phase'] == 'reasoning_delta'),
        hasLength(2),
      );
      expect(
        events.where((event) => event['phase'] == 'text_reset'),
        isNotEmpty,
      );
      final reopened = LedgerAgentRuntime(store: store, clock: () => now);
      expect(_parts((await reopened.state(0))['session'] as Map), parts);
      final switched = await reopened.run(
        question: '你好',
        ledger: ledger,
        guard: () async {},
        model: AgentModel(
          identity: 'another-provider:model',
          complete: (_, _) async => _answer('你好'),
        ),
      );
      expect(
        _parts(switched['session'] as Map).map((part) => part['text']),
        parts.map((part) => part['text']),
      );
      expect(
        ((switched['session'] as Map)['messages'] as List).any(
          (message) => message['reasoning_content'] != null,
        ),
        true,
      );
    },
  );

  test(
    'changing only a DeepSeek key preserves all historical tool reasoning',
    () async {
      final session = AgentSession.create(0, now)
        ..modelIdentity =
            'deepseek:deepseek-flash:https://api.deepseek.com:old-key';
      session.messages.addAll([
        {'role': 'user', 'content': '旧任务'},
        {
          'role': 'assistant',
          'content': '',
          'reasoning_content': '原始工具推理',
          'tool_calls': [
            {
              'id': 'old-call',
              'type': 'function',
              'function': {'name': 'get_budget', 'arguments': '{}'},
            },
          ],
        },
        {'role': 'tool', 'tool_call_id': 'old-call', 'content': '{}'},
        {
          'role': 'assistant',
          'content': '旧任务结果',
          'reasoning_content': '原始最终推理',
        },
      ]);
      await store.write('session:0:${session.id}', session.toMap());
      await store.write('active:0', {'id': session.id});
      final result = await runtime.run(
        question: '你好',
        ledger: ledger,
        guard: () async {},
        model: AgentModel(
          identity: 'deepseek:deepseek-flash:https://api.deepseek.com:new-key',
          complete: (messages, tools) async {
            expect(tools, isNotEmpty);
            expect(
              messages
                  .where((m) => m['role'] == 'assistant')
                  .map((m) => m['reasoning_content']),
              ['原始工具推理', '原始最终推理'],
            );
            expect(messages.where((m) => m['role'] == 'tool'), hasLength(1));
            return _answer('你好');
          },
        ),
      );
      expect((result['session'] as Map)['contextStart'], 0);
    },
  );

  test(
    'changing a Qwen base URL starts fresh wire context for the same model id',
    () async {
      final session = AgentSession.create(0, now)
        ..modelIdentity = 'qwen:qwen3.8-flash:https://first.example/v1:key';
      session.messages.addAll([
        {'role': 'user', 'content': '旧问题'},
        {'role': 'assistant', 'content': '旧回答', 'reasoning_content': '旧端点的思考'},
      ]);
      await store.write('session:0:${session.id}', session.toMap());
      await store.write('active:0', {'id': session.id});
      final result = await runtime.run(
        question: '你好',
        ledger: ledger,
        guard: () async {},
        model: AgentModel(
          identity: 'qwen:qwen3.8-flash:https://second.example/v1:key',
          complete: (messages, _) async {
            expect(
              messages.any(
                (m) =>
                    m['role'] == 'assistant' || m['reasoning_content'] != null,
              ),
              false,
            );
            expect(jsonEncode(messages), contains('旧回答'));
            expect(jsonEncode(messages), isNot(contains('旧端点的思考')));
            return _answer('你好');
          },
        ),
      );
      expect((result['session'] as Map)['contextStart'], 2);
    },
  );

  test(
    'switching GPT tool history into DeepSeek starts clean wire context with ordinary history data',
    () async {
      final session = AgentSession.create(0, now)
        ..modelIdentity = 'chatgpt:old-account:gpt-test'
        ..summary = '更早已整理的摘要';
      session.messages.addAll([
        {'role': 'user', 'content': '此前的预算问题'},
        {
          'role': 'assistant',
          'content': '',
          'reasoning_summary': '可展示的公开摘要',
          'responsesAccountId': 'old-account',
          'responsesItems': [
            {'type': 'reasoning', 'encrypted_content': 'PRIVATE_OPAQUE'},
          ],
          'tool_calls': [
            {
              'id': 'old-call',
              'type': 'function',
              'function': {'name': 'get_budget', 'arguments': '{}'},
            },
          ],
        },
        {
          'role': 'tool',
          'tool_call_id': 'old-call',
          'content': '{"presentation":{"content":"PRIVATE_HTML"}}',
        },
        {'role': 'assistant', 'content': '此前已核对预算结果'},
      ]);
      await store.write('session:0:${session.id}', session.toMap());
      await store.write('active:0', {'id': session.id});
      final result = await runtime.run(
        question: '你好',
        ledger: ledger,
        guard: () async {},
        model: AgentModel(
          identity: 'deepseek:deepseek-flash:https://api.deepseek.com:key',
          complete: (messages, tools) async {
            expect(tools, isNotEmpty);
            expect(
              messages.where(
                (m) => m['role'] == 'assistant' || m['role'] == 'tool',
              ),
              isEmpty,
            );
            final data = jsonEncode(messages);
            expect(data, contains('此前的预算问题'));
            expect(data, contains('此前已核对预算结果'));
            expect(data, contains('更早已整理的摘要'));
            for (final forbidden in [
              'tool_calls',
              'reasoning_content',
              'PRIVATE_OPAQUE',
              'PRIVATE_HTML',
              '可展示的公开摘要',
            ]) {
              expect(data, isNot(contains(forbidden)));
            }
            return _answer('你好');
          },
        ),
      );
      final stored = result['session'] as Map;
      expect(stored['summary'], '更早已整理的摘要');
      expect(stored['contextStart'], 4);
      expect(
        (stored['messages'] as List).where((m) => m['role'] == 'tool'),
        hasLength(1),
      );
      expect(_parts(stored).single['text'], '可展示的公开摘要');
    },
  );

  test(
    'model switches keep bounded recent ordinary excerpts and preserve earlier summary',
    () async {
      final session = AgentSession.create(0, now)
        ..modelIdentity = 'qwen:qwen3.8-flash:https://example.test:old'
        ..summary = '先前已有摘要';
      session.messages.addAll([
        for (var i = 0; i < 8; i++)
          {
            'role': i.isEven ? 'user' : 'assistant',
            'content': '第$i条${List.filled(2400, '甲').join()}',
          },
        {'role': 'assistant', 'content': '最后确认的结论'},
      ]);
      await store.write('session:0:${session.id}', session.toMap());
      await store.write('active:0', {'id': session.id});
      for (final identity in [
        'deepseek:deepseek-flash:https://api.deepseek.com:key',
        'chatgpt:account:model',
      ]) {
        await runtime.run(
          question: '继续',
          ledger: ledger,
          guard: () async {},
          model: AgentModel(
            identity: identity,
            complete: (messages, _) async {
              final data =
                  jsonDecode(
                        messages.firstWhere(
                              (message) =>
                                  message['role'] == 'user' &&
                                  (message['content'] as String).startsWith(
                                    '{"notice":',
                                  ),
                            )['content']
                            as String,
                      )
                      as Map;
              final history = data['history'] as List;
              expect(data['notice'], contains('内容已节选'));
              expect(jsonEncode(history).length, lessThanOrEqualTo(6000));
              expect(
                history.every(
                  (row) => (row['content'] as String).length <= 2000,
                ),
                true,
              );
              expect(jsonEncode(history), contains('最后确认的结论'));
              expect(jsonEncode(history), isNot(contains('第0条')));
              expect(
                history.every(
                  (row) => (row as Map).keys.toSet().difference({
                    'role',
                    'content',
                  }).isEmpty,
                ),
                true,
              );
              return _answer('继续处理');
            },
          ),
        );
      }
      final stored = (await runtime.state(0))['session'] as Map;
      expect(stored['summary'], '先前已有摘要');
      expect(
        (stored['messages'] as List).any(
          (m) => (m['content'] as String).startsWith('第0条'),
        ),
        true,
      );
    },
  );

  test(
    'stop persists partial reasoning and ignores late reasoning callbacks',
    () async {
      final started = Completer<void>(), finish = Completer<AgentReply>();
      late void Function(String) lateThought;
      final task = runtime.run(
        question: '你好',
        ledger: ledger,
        guard: () async {},
        model: AgentModel(
          identity: 'deepseek:test',
          reasoningConfig: _config,
          complete: (_, _) => finish.future,
          reasoningStream: (_, _, text, thought) {
            lateThought = thought;
            thought('正在核对');
            started.complete();
            return finish.future;
          },
        ),
      );
      await started.future;
      now = now.add(const Duration(seconds: 2));
      runtime.cancel();
      final result = await task;
      final part = _parts(result['session'] as Map).single;
      expect(part['text'], '正在核对');
      expect(part['status'], 'cancelled');
      expect(part['endedAt'], now.millisecondsSinceEpoch);
      final count = events.length;
      lateThought('迟到内容');
      finish.complete(_answer('迟到正文'));
      await Future<void>.delayed(Duration.zero);
      expect(events, hasLength(count));
      expect(
        jsonEncode((await runtime.state(0))['session']),
        isNot(contains('迟到内容')),
      );
    },
  );

  test(
    'enabled thinking with no exposed content produces an empty neutral part',
    () async {
      final result = await runtime.run(
        question: '你好',
        ledger: ledger,
        guard: () async {},
        model: AgentModel(
          identity: 'gpt:test',
          reasoningConfig: {
            ..._config,
            'provider': 'chatgpt',
            'kind': 'summary',
          },
          complete: (_, _) async => _answer('你好'),
        ),
      );
      final part = _parts(result['session'] as Map).single;
      expect(part['text'], isEmpty);
      expect(part['kind'], 'summary');
      expect(part['status'], 'completed');
      expect(events.any((event) => event['phase'] == 'reasoning_delta'), false);
    },
  );

  test(
    'models declaring no public summary do not create an empty waiting part',
    () async {
      final result = await runtime.run(
        question: '你好',
        ledger: ledger,
        guard: () async {},
        model: AgentModel(
          identity: 'gpt:test',
          reasoningConfig: {
            ..._config,
            'provider': 'chatgpt',
            'kind': 'summary',
            'supportsSummary': false,
          },
          complete: (_, _) async => _answer('你好'),
        ),
      );
      expect(_parts(result['session'] as Map), isEmpty);
      expect(
        events.any(
          (event) => (event['phase'] as String).startsWith('reasoning_'),
        ),
        false,
      );
    },
  );

  test(
    'legacy stored reasoning is recovered without inventing duration timestamps',
    () async {
      final session = AgentSession.create(0, now)
        ..modelIdentity = 'deepseek:deepseek-flash';
      session.messages.addAll([
        {'id': 'u1', 'runId': 'r1', 'role': 'user', 'content': '你好'},
        {
          'id': 'a1',
          'runId': 'r1',
          'role': 'assistant',
          'content': '你好',
          'reasoning_content': '已公开的旧推理',
        },
      ]);
      await store.write('session:0:${session.id}', session.toMap());
      await store.write('active:0', {'id': session.id});
      final part = _parts((await runtime.state(0))['session'] as Map).single;
      expect(part['text'], '已公开的旧推理');
      expect(part.containsKey('startedAt'), false);
      expect(part.containsKey('endedAt'), false);
      expect(part.containsKey('effort'), false);
      expect(part['status'], 'completed');
    },
  );
}

import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/agent/agent_presentation.dart';
import 'package:pennypilot/core/agent/agent_store.dart';
import 'package:pennypilot/core/ai/shared_ai_service.dart';
import 'package:pennypilot/core/cross_platform_repository.dart';
import 'package:pennypilot/core/models.dart';
import 'package:pennypilot/core/platform/mobile_platform_services.dart';

import '../../ui/test_ledger_repository.dart';
import '../ai/shared_ai_service_test.dart' show MemoryCredentials;

class _Platform extends MobilePlatformServices {
  @override
  Future<void> dispose() async {}
}

class _NativeStore extends TestLedgerRepository {
  _NativeStore({this.contentEncoding = 'omitted'}) : super(populated: true) {
    data = {
      ...data,
      'settings': {
        ...data['settings'] as Map,
        'activeProvider': 'chatgpt',
        'chatGptModel': 'test-model',
      },
      'auth': {'activeAccountId': 'native-account'},
    };
  }
  final String contentEncoding;
  int turns = 0;
  bool foregroundAvailable = true;
  @override
  Future<Object?> invoke(String method, Map<String, dynamic> args) async {
    if (method == 'startAgentForeground' || method == 'stopAgentForeground') {
      calls.add((method: method, arguments: args));
      return method == 'startAgentForeground' ? foregroundAvailable : null;
    }
    if (method != 'agentModelTurn') return super.invoke(method, args);
    calls.add((method: method, arguments: args));
    expect(args['expectedAccountId'], 'native-account');
    expect(args['expectedModel'], 'test-model');
    final offered = jsonDecode(args['toolsJson'] as String) as List;
    expect(
      offered.map((tool) => tool['function']['name']),
      containsAll([
        'get_budget',
        'propose_transaction',
        'present_insight',
        'render_artifact',
      ]),
    );
    if (++turns == 1) {
      return {
        'messageJson': jsonEncode({
          if (contentEncoding == 'empty') 'content': '',
          if (contentEncoding == 'null') 'content': null,
          'tool_calls': [
            {
              'id': 'native-call',
              'type': 'function',
              'function': {
                'name': 'get_budget',
                'arguments': '{"month":"2026-10"}',
              },
            },
          ],
        }),
      };
    }
    final messages = jsonDecode(args['messagesJson'] as String) as List;
    final assistant = messages.singleWhere(
      (message) => message['role'] == 'assistant',
    );
    expect(assistant['tool_calls'].single['id'], 'native-call');
    expect(messages.last['tool_call_id'], 'native-call');
    final observation = jsonDecode(messages.last['content'] as String) as Map;
    expect((observation['data'] as Map)['expense'], 4000);
    return {
      'messageJson': jsonEncode({
        'role': 'assistant',
        'content': '本月支出40元。[依据 ${observation['evidence_id']}]',
      }),
    };
  }
}

class _RecordingNativeStore extends _NativeStore {
  _RecordingNativeStore({required this.automatic}) {
    (data['settings'] as Map)['activeInputAutoSaveEnabled'] = automatic;
  }
  final bool automatic;
  @override
  Future<Object?> invoke(String method, Map<String, dynamic> args) async {
    if (method != 'agentModelTurn') return super.invoke(method, args);
    if (++turns == 1) {
      return {
        'messageJson': jsonEncode({
          'role': 'assistant',
          'content': '',
          'tool_calls': [
            {
              'id': 'record-call',
              'type': 'function',
              'function': {
                'name': 'propose_transaction',
                'arguments': jsonEncode({
                  'amount': 1700,
                  'type': 'EXPENSE',
                  'category_id': 1,
                  'business_kind': 'expense',
                  'evidence': '午饭17元',
                }),
              },
            },
          ],
        }),
      };
    }
    final messages = jsonDecode(args['messagesJson'] as String) as List;
    final result = jsonDecode(messages.last['content'] as String) as Map;
    expect(result['status'], automatic ? 'applied' : 'awaiting_confirmation');
    if (automatic) expect((result['data'] as Map)['amount'], 1700);
    return {
      'messageJson': jsonEncode({
        'role': 'assistant',
        'content': automatic
            ? '已记17元。[依据 ${result['evidence_id']}]'
            : '账单等待核对。',
      }),
    };
  }
}

class _StreamingNativeStore extends _NativeStore {
  final started = Completer<void>();
  final finish = Completer<void>();
  @override
  Future<Object?> invoke(String method, Map<String, dynamic> args) async {
    if (method != 'agentModelTurn') return super.invoke(method, args);
    updates.add(
      LedgerEvent('agentModelText', 0, {
        'requestId': args['requestId'],
        'delta': '你好',
      }),
    );
    started.complete();
    await finish.future;
    return {
      'messageJson': jsonEncode({'role': 'assistant', 'content': '你好'}),
    };
  }
}

class _ThinkingNativeStore extends _NativeStore {
  _ThinkingNativeStore() {
    (data['settings'] as Map).addAll({
      'chatGptModel': 'catalog-model',
      'chatGptReasoningEffort': 'max',
    });
  }
  final started = Completer<void>(), finish = Completer<void>();
  @override
  Future<Object?> invoke(String method, Map<String, dynamic> args) async {
    if (method == 'listModels') {
      return [
        {
          'slug': 'catalog-model',
          'displayName': 'Catalog model',
          'reasoningEfforts': ['low', 'max'],
          'defaultReasoningEffort': 'low',
        },
      ];
    }
    if (method != 'agentModelTurn') return super.invoke(method, args);
    expect(args['reasoningEffort'], 'max');
    expect(args['requestReasoningSummary'], true);
    updates.add(
      LedgerEvent('agentModelReasoning', 0, {
        'requestId': 'unrelated-request',
        'delta': '忽略过期事件',
      }),
    );
    updates.add(
      LedgerEvent('agentModelReasoning', 0, {
        'requestId': args['requestId'],
        'delta': '公开摘要',
      }),
    );
    started.complete();
    await finish.future;
    return {
      'messageJson': jsonEncode({
        'role': 'assistant',
        'content': '你好',
        'reasoning_summary': '公开摘要',
        'responsesItems': [
          {'type': 'reasoning', 'encrypted_content': 'PRIVATE-OPAQUE'},
        ],
      }),
    };
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'native reasoning uses account catalog and streams independently before completion',
    () async {
      final store = _ThinkingNativeStore();
      final credentials = MemoryCredentials();
      final repo = CrossPlatformLedgerRepository(
        store: store,
        agentStore: MemoryAgentStore(),
        credentials: credentials,
        ai: SharedAiService(credentials: credentials),
        platformServices: _Platform(),
        android: true,
      );
      addTearDown(repo.close);
      addTearDown(store.updates.close);
      final events = <LedgerEvent>[];
      final subscription = repo.events.listen(events.add);
      addTearDown(subscription.cancel);
      await repo.invoke('listModels', {});
      final task = repo.invoke('ask', {'revision': 0, 'question': '你好'});
      await store.started.future;
      await Future<void>.delayed(Duration.zero);
      final reasoningEvents = events.where(
        (e) => e.type == 'agent' && e.data['phase'] == 'reasoning_delta',
      );
      expect(reasoningEvents.single.data['part']['text'], '公开摘要');
      expect(events.any((e) => e.type == 'agentModelReasoning'), false);
      expect(events.any((e) => e.data['phase'] == 'text_delta'), false);
      store.finish.complete();
      final result = await task as Map;
      final messages = (result['session'] as Map)['messages'] as List;
      final part = (messages.last['parts'] as List).single;
      expect(part['kind'], 'summary');
      expect(part['text'], '公开摘要');
      expect(jsonEncode(part), isNot(contains('PRIVATE-OPAQUE')));
    },
  );
  test(
    'native EventChannel text reaches the shared run before MethodChannel completion',
    () async {
      final store = _StreamingNativeStore();
      final credentials = MemoryCredentials();
      final repo = CrossPlatformLedgerRepository(
        store: store,
        agentStore: MemoryAgentStore(),
        credentials: credentials,
        ai: SharedAiService(credentials: credentials),
        platformServices: _Platform(),
        android: true,
      );
      addTearDown(repo.close);
      addTearDown(store.updates.close);
      final events = <LedgerEvent>[];
      final subscription = repo.events.listen(events.add);
      addTearDown(subscription.cancel);
      final task = repo.invoke('ask', {'revision': 0, 'question': '你好'});
      await store.started.future;
      await Future<void>.delayed(Duration.zero);
      expect(
        events
            .where((event) => event.type == 'agent')
            .any(
              (event) =>
                  event.data['phase'] == 'text_delta' &&
                  event.data['delta'] == '你好',
            ),
        true,
      );
      expect(events.any((event) => event.type == 'agentModelText'), false);
      store.finish.complete();
      final result = await task as Map;
      expect(result['answer'], '你好');
    },
  );
  for (final encoding in ['omitted', 'null', 'empty']) {
    test(
      'Android native tool-only reply continues with $encoding content',
      () async {
        final store = _NativeStore(contentEncoding: encoding)
          ..foregroundAvailable = encoding != 'null';
        final agentStore = MemoryAgentStore();
        final credentials = MemoryCredentials();
        final ai = SharedAiService(credentials: credentials);
        final repo = CrossPlatformLedgerRepository(
          store: store,
          agentStore: agentStore,
          credentials: credentials,
          ai: ai,
          platformServices: _Platform(),
          android: true,
        );
        addTearDown(repo.close);
        addTearDown(store.updates.close);
        final result =
            await repo.invoke('ask', {'revision': 0, 'question': '本月花了多少'})
                as Map;
        expect(result['isError'], false);
        expect(store.turns, 2);
        expect(store.calls.map((c) => c.method), [
          'startAgentForeground',
          'agentModelTurn',
          'agentModelTurn',
          'stopAgentForeground',
        ]);
        final token = store.calls.first.arguments['runId'];
        expect(token, isA<String>());
        expect(store.calls.last.arguments['runId'], token);
        expect((result['session'] as Map)['messages'], hasLength(4));
        final state = await repo.agentState();
        expect((state['session'] as Map)['status'], 'completed');
      },
    );
  }
  for (final automatic in [true, false]) {
    test(
      'repository wires chat new-entry saving according to user preference: $automatic',
      () async {
        final store = _RecordingNativeStore(automatic: automatic);
        final credentials = MemoryCredentials();
        final repo = CrossPlatformLedgerRepository(
          store: store,
          agentStore: MemoryAgentStore(),
          credentials: credentials,
          ai: SharedAiService(credentials: credentials),
          platformServices: _Platform(),
          android: true,
        );
        addTearDown(repo.close);
        addTearDown(store.updates.close);
        final result =
            await repo.invoke('ask', {'revision': 0, 'question': '午饭17元'})
                as Map;
        expect(result['isError'], false);
        expect(
          store.calls.where((c) => c.method == 'saveTransaction'),
          automatic ? hasLength(1) : isEmpty,
        );
        expect(
          (await store.snapshot()).transactions.where((tx) => tx.id == 99),
          automatic ? hasLength(1) : isEmpty,
        );
        expect(
          (result['session'] as Map)['status'],
          automatic ? 'completed' : 'awaiting_confirmation',
        );
        if (automatic) expect(result['autoSavedIds'], [99]);
      },
    );
  }

  for (final approve in [true, false]) {
    test(
      'resolves a stored proposal without a configured model: $approve',
      () async {
        final store = _RecordingNativeStore(automatic: false);
        final credentials = MemoryCredentials();
        final repo = CrossPlatformLedgerRepository(
          store: store,
          agentStore: MemoryAgentStore(),
          credentials: credentials,
          ai: SharedAiService(credentials: credentials),
          platformServices: _Platform(),
          android: true,
        );
        addTearDown(repo.close);
        addTearDown(store.updates.close);
        final proposed =
            await repo.invoke('ask', {'revision': 0, 'question': '午饭17元'})
                as Map;
        final action = (proposed['session']['actions'] as List).single as Map;
        expect(action['status'], 'pending');
        (store.data['settings'] as Map)['chatGptModel'] = '';
        store.data['auth'] = {};

        final result =
            await repo.invoke('agentResolveAction', {
                  'revision': 0,
                  'actionId': action['id'],
                  'approve': approve,
                })
                as Map;
        final answer = result['answer'] as String;
        expect(answer, startsWith(approve ? '已新增' : '已取消'));
        expect(answer, contains('17'));
        expect(result['isError'], false);
        expect(store.turns, 2);
        expect(
          store.calls.where((call) => call.method == 'saveTransaction'),
          approve ? hasLength(1) : isEmpty,
        );
        final saved = (await store.snapshot()).transactions.where(
          (tx) => tx.id == 99,
        );
        if (approve) {
          expect(saved.single.amountFen, 1700);
        } else {
          expect(saved, isEmpty);
        }
        final session = stringMap((await repo.agentState())['session']);
        expect(session['status'], 'completed');
        expect(
          (session['actions'] as List).single['status'],
          approve ? 'applied' : 'rejected',
        );
        final transcript = presentAgentSession(session);
        expect(transcript.first.text, '午饭17元');
        expect(transcript[1].text, '账单等待核对。');
        expect(transcript.last.text, answer);
      },
    );
  }
}

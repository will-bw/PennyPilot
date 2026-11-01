import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/agent/agent_store.dart';
import 'package:pennypilot/core/ai/shared_ai_service.dart';
import 'package:pennypilot/core/cross_platform_repository.dart';
import 'package:pennypilot/core/platform/mobile_platform_services.dart';

import '../../ui/test_ledger_repository.dart';
import '../ai/shared_ai_service_test.dart' show MemoryCredentials;

class _Platform extends MobilePlatformServices {
  @override
  Future<void> dispose() async {}
}

class _NativeStore extends TestLedgerRepository {
  _NativeStore() : super(populated: true) {
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
  int turns = 0;
  @override
  Future<Object?> invoke(String method, Map<String, dynamic> args) async {
    if (method != 'agentModelTurn') return super.invoke(method, args);
    calls.add((method: method, arguments: args));
    expect(args['expectedAccountId'], 'native-account');
    expect(args['expectedModel'], 'test-model');
    expect(jsonDecode(args['toolsJson'] as String), hasLength(16));
    if (++turns == 1) {
      return {
        'messageJson': jsonEncode({
          'role': 'assistant',
          'content': '',
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

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'Android ChatGPT uses shared loop and native per-turn transport instead of legacy ask',
    () async {
      final store = _NativeStore();
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
      expect(store.calls.map((c) => c.method), everyElement('agentModelTurn'));
      expect((result['session'] as Map)['messages'], hasLength(4));
      final state = await repo.agentState();
      expect((state['session'] as Map)['status'], 'completed');
    },
  );
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
}

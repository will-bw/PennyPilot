import 'package:flutter_test/flutter_test.dart';
import 'package:http/testing.dart';
import 'package:pennypilot/core/ai/shared_ai_service.dart';
import 'package:pennypilot/core/agent/agent_store.dart';
import 'package:pennypilot/core/cross_platform_repository.dart';
import 'package:pennypilot/core/ledger_repository.dart';
import 'package:pennypilot/core/models.dart';
import 'package:pennypilot/core/platform/mobile_platform_services.dart';

import 'shared_ai_service_test.dart' as fixtures;

class _GuardStore implements LedgerRepository {
  int revision = 1;
  @override
  Stream<LedgerEvent> get events => const Stream.empty();
  @override
  Future<LedgerSnapshot> snapshot() async => LedgerSnapshot(
    revision: revision,
    settings: {'activeProvider': 'deepseek', 'deepSeekModel': 'deepseek-chat'},
    categories: const [LedgerCategory(id: 1, name: '餐饮', type: TxType.expense)],
    transactions: [
      LedgerTransaction(
        amountFen: 1200,
        type: TxType.expense,
        categoryId: 1,
        occurredAt: DateTime(2026, 10, 2),
      ),
    ],
  );
  @override
  Future<Object?> invoke(String method, Map<String, dynamic> arguments) async =>
      null;
}

class _Platform extends MobilePlatformServices {
  @override
  Future<void> dispose() async {}
}

void main() {
  test(
    'restore between ask turns prevents sending the old ledger details',
    () async {
      final store = _GuardStore();
      var requests = 0;
      final client = MockClient((_) async {
        requests++;
        // Restore after the planning request, before its response is consumed.
        store.revision = 2;
        return fixtures.completion({
          'start': '2026-10-01',
          'end': '2026-10-31',
        });
      });
      addTearDown(client.close);
      final ai = SharedAiService(
        credentials: fixtures.MemoryCredentials(),
        client: client,
      );
      final repository = CrossPlatformLedgerRepository(
        store: store,
        platformServices: _Platform(),
        ai: ai,
        agentStore: MemoryAgentStore(),
        android: true,
      );
      addTearDown(repository.close);
      await expectLater(
        repository.invoke('ask', {'revision': 1, 'question': '本月花费多少'}),
        throwsA(
          isA<LedgerException>().having(
            (error) => error.code,
            'code',
            'STALE_LEDGER',
          ),
        ),
      );
      expect(
        requests,
        1,
        reason: 'No follow-up request may contain the pre-restore ledger.',
      );
    },
  );
}

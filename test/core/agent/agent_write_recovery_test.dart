import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/agent/agent_models.dart';
import 'package:pennypilot/core/agent/agent_runtime.dart';
import 'package:pennypilot/core/agent/agent_store.dart';
import 'package:pennypilot/core/models.dart';
import 'package:pennypilot/core/storage/portable_ledger_store.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

class _InterruptibleStore extends MemoryAgentStore {
  bool denySessionWrites = false;
  String? stopAfterActionStatus;

  @override
  Future<void> write(String key, Map<String, dynamic> value) async {
    if (denySessionWrites && key.startsWith('session:')) {
      throw StateError('interrupted before writing Agent checkpoint');
    }
    await super.write(key, value);
    if (stopAfterActionStatus != null &&
        key.startsWith('session:') &&
        (value['actions'] as List).any(
          (action) => action['status'] == stopAfterActionStatus,
        )) {
      denySessionWrites = true;
    }
  }
}

const _toolCallId = 'record-lunch';
const _question = '午饭17元';

AgentReply _proposal() => AgentReply(
  message: {
    'role': 'assistant',
    'content': '',
    'tool_calls': [
      {
        'id': _toolCallId,
        'type': 'function',
        'function': {
          'name': 'propose_transaction',
          'arguments': jsonEncode({
            'amount': 1700,
            'type': 'EXPENSE',
            'category_id': 1,
            'business_kind': 'expense',
            'evidence': _question,
          }),
        },
      },
    ],
  },
);

Map<String, dynamic> _toolResult(AgentSession session) => agentMap(
  jsonDecode(
    session.messages.singleWhere(
          (m) => m['tool_call_id'] == _toolCallId,
        )['content']
        as String,
  ),
);

void main() {
  final now = DateTime(2026, 10, 24, 12);
  late _InterruptibleStore store;
  late PortableLedgerStore ledger;
  late LedgerAgentRuntime runtime;
  setUpAll(sqfliteFfiInit);
  setUp(() {
    store = _InterruptibleStore();
    ledger = PortableLedgerStore(
      databaseFactory: databaseFactoryFfi,
      databasePath: inMemoryDatabasePath,
      clock: () => now,
    );
    runtime = LedgerAgentRuntime(store: store, clock: () => now);
  });
  tearDown(() => ledger.close());

  for (final boundary in [
    'before commit',
    'before receipt',
    'before tool result',
  ]) {
    test('reconciles an automatic insert interrupted $boundary', () async {
      var modelCalls = 0;
      var writes = 0;
      if (boundary == 'before tool result') {
        store.stopAfterActionStatus = 'applied';
      }
      await expectLater(
        runtime.run(
          question: _question,
          ledger: await ledger.snapshot(),
          model: AgentModel(
            identity: 'test:record',
            complete: (_, _) async {
              modelCalls++;
              return _proposal();
            },
          ),
          guard: () async {},
          autoRecord: (transaction) async {
            if (boundary == 'before commit') {
              store.denySessionWrites = true;
              throw StateError('interrupted before committing ledger');
            }
            writes++;
            final result = agentMap(
              await ledger.invoke('saveTransaction', {
                ...transaction,
                'revision': 0,
              }),
            );
            if (boundary == 'before receipt') store.denySessionWrites = true;
            return (id: result['id'] as int, ledger: await ledger.snapshot());
          },
        ),
        throwsStateError,
      );

      store.denySessionWrites = false;
      store.stopAfterActionStatus = null;
      final restarted = LedgerAgentRuntime(store: store, clock: () => now);
      final snapshot = await ledger.snapshot();
      if (boundary == 'before receipt') {
        final restoredWithoutLedger = await restarted.active(0);
        expect(restoredWithoutLedger.actions.single['status'], 'uncertain');
        expect(_toolResult(restoredWithoutLedger)['error'], 'interrupted');
      }
      final restored = await restarted.active(0, ledger: snapshot);
      expect(restored.status, 'interrupted');
      expect(modelCalls, 1);
      if (boundary == 'before commit') {
        expect(writes, 0);
        expect(snapshot.transactions, isEmpty);
        expect(restored.actions.single['status'], 'uncertain');
        expect(_toolResult(restored)['error'], 'interrupted');
      } else {
        expect(writes, 1);
        final saved = snapshot.transactions.single;
        expect(restored.actions.single['status'], 'applied');
        expect(restored.actions.single['transactionId'], saved.id);
        expect(_toolResult(restored), containsPair('transaction_id', saved.id));
        expect(restored.trace.single['status'], 'completed');
      }
      final again = await restarted.active(0, ledger: await ledger.snapshot());
      expect(again.toMap(), restored.toMap());
    });
  }

  test(
    'a committed approval preserves its earlier proposal tool result',
    () async {
      final initial = await runtime.run(
        question: _question,
        ledger: await ledger.snapshot(),
        model: AgentModel(
          identity: 'test:approve',
          complete: (messages, _) async {
            if (messages.last['role'] != 'tool') return _proposal();
            return AgentReply(
              message: {'role': 'assistant', 'content': '请确认账单。'},
            );
          },
        ),
        guard: () async {},
      );
      final session = AgentSession.fromMap(agentMap(initial['session']));
      expect(session.status, 'awaiting_confirmation');
      store.stopAfterActionStatus = 'applying';
      await expectLater(
        runtime.resolve(
          revision: 0,
          actionId: session.actions.single['id'] as String,
          approve: true,
          ledger: ledger,
          guard: () async {},
        ),
        throwsStateError,
      );
      store.stopAfterActionStatus = null;
      store.denySessionWrites = false;
      final snapshot = await ledger.snapshot();

      final restored = await LedgerAgentRuntime(
        store: store,
      ).active(0, ledger: snapshot);
      expect(restored.status, 'completed');
      expect(restored.actions.single['status'], 'applied');
      expect(
        restored.actions.single['transactionId'],
        snapshot.transactions.single.id,
      );
      expect(_toolResult(restored)['status'], 'awaiting_confirmation');
      expect((await ledger.snapshot()).transactions, hasLength(1));
    },
  );

  test('only the original insert key can reconcile an edited row', () async {
    final session = await runtime.active(0);
    const operationId = 'agent:original';
    final originalMetadata = jsonEncode({'operationId': operationId});
    final draft = {
      'id': 0,
      'source': 'AI_TEXT',
      'metadataJson': originalMetadata,
    };
    session.actions.addAll([
      for (final (id, kind, status, payload) in [
        ('new', 'transaction', 'uncertain', {'transaction': draft}),
        ('pending', 'transaction', 'pending', {'transaction': draft}),
        ('undone', 'transaction', 'undone', {'transaction': draft}),
        (
          'edit',
          'transaction',
          'uncertain',
          {
            'transaction': draft,
            'before': {'id': 4},
          },
        ),
        ('budget', 'budget', 'uncertain', {'amount': 1200}),
        ('delete', 'delete', 'uncertain', {'transactionId': 4}),
      ])
        {
          'id': id,
          'kind': kind,
          'status': status,
          'revision': 0,
          'payload': payload,
        },
    ]);
    await store.write('session:0:${session.id}', session.toMap());

    LedgerTransaction edited(String? key, String metadata) => LedgerTransaction(
      id: 4,
      amountFen: 2000,
      type: TxType.expense,
      categoryId: 1,
      occurredAt: now,
      source: TxSource.aiText,
      metadataJson: metadata,
      operationKey: key,
      editVersion: 1,
    );
    final changedMetadata = jsonEncode({'operationId': 'agent:edit'});
    final changedKey = edited('agent:edit', changedMetadata);
    final originalKey = edited(operationId, changedMetadata);
    final restarted = LedgerAgentRuntime(store: store);

    for (final snapshot in [
      LedgerSnapshot(revision: 1, transactions: [originalKey]),
      LedgerSnapshot(revision: 0, transactions: [changedKey]),
      LedgerSnapshot(
        revision: 0,
        transactions: [edited(null, originalMetadata)],
      ),
      LedgerSnapshot(
        revision: 0,
        transactions: [originalKey, originalKey.copyWith(id: 5)],
      ),
    ]) {
      final restored = await restarted.active(0, ledger: snapshot);
      expect(restored.actions.first['status'], 'uncertain');
    }
    final restored = await restarted.active(
      0,
      ledger: LedgerSnapshot(revision: 0, transactions: [originalKey]),
    );
    expect(restored.actions.first['status'], 'applied');
    expect(restored.actions.first['transactionId'], originalKey.id);
    expect(restored.actions.skip(1).map((a) => a['status']), [
      'pending',
      'undone',
      'uncertain',
      'uncertain',
      'uncertain',
    ]);
  });
}

import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/agent/agent_models.dart';
import 'package:pennypilot/core/agent/agent_runtime.dart';
import 'package:pennypilot/core/agent/agent_store.dart';
import 'package:pennypilot/core/ledger_repository.dart';
import 'package:pennypilot/core/models.dart';
import 'package:pennypilot/core/storage/portable_ledger_store.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

void main() {
  late PortableLedgerStore ledger;
  late MemoryAgentStore store;
  late LedgerAgentRuntime runtime;
  final now = DateTime(2026, 11, 1, 12);
  setUpAll(sqfliteFfiInit);
  setUp(() {
    ledger = PortableLedgerStore(
      databaseFactory: databaseFactoryFfi,
      databasePath: inMemoryDatabasePath,
      clock: () => now,
    );
    store = MemoryAgentStore();
    runtime = LedgerAgentRuntime(store: store, clock: () => now);
  });
  tearDown(() => ledger.close());

  Future<LedgerTransaction> insert(
    String runId, {
    String source = 'AI_TEXT',
  }) async {
    final state = await ledger.snapshot();
    final category = state.categories.firstWhere(
      (category) => category.enabled && category.type == TxType.expense,
    );
    final saved = agentMap(
      await ledger.invoke('saveTransaction', {
        'revision': state.revision,
        'id': 0,
        'amount': 1700,
        'type': 'EXPENSE',
        'categoryId': category.id,
        'businessKind': 'expense',
        'source': source,
        'occurredAt': now.millisecondsSinceEpoch,
        'metadataJson': jsonEncode({
          'operationId': 'op:$runId',
          'requestSessionId': runId,
        }),
      }),
    );
    return (await ledger.snapshot()).transactions.singleWhere(
      (tx) => tx.id == saved['id'],
    );
  }

  Future<void> receipt(
    String runId,
    LedgerTransaction tx, {
    bool action = false,
  }) async {
    await runtime.appendInput(
      revision: 0,
      question: '午饭17元',
      source: tx.source.wireValue,
      answer: '已记下一笔',
      status: 'completed',
      runId: runId,
      guard: () async {},
      parts: action
          ? []
          : [
              {
                'type': 'receipt',
                'status': 'applied',
                'transactions': [
                  {...tx.toMap(), 'editVersion': 0},
                ],
              },
              {
                'type': 'recognition_state',
                'runId': runId,
                'drafts': [
                  {...tx.toMap(), 'id': 0},
                ],
                'undoneOperations': <String>[],
              },
            ],
    );
    if (action) {
      final session = await runtime.active(0);
      session.actions.add({
        'id': 'action:$runId',
        'runId': runId,
        'kind': 'transaction',
        'status': 'applied',
        'transactionId': tx.id,
        'payload': {
          'transaction': {...tx.toMap(), 'id': 0},
        },
      });
      await store.write('session:0:${session.id}', session.toMap());
    }
  }

  Future<Map<String, dynamic>> undo(
    String runId, {
    List<int>? transactionIds,
  }) => runtime.undoRun(
    revision: 0,
    runId: runId,
    ledger: ledger,
    guard: () async {},
    transactionIds: transactionIds,
  );

  test(
    'one run can undo its latest batch and later undo only the remaining records',
    () async {
      final a = await insert('batch-a'), b = await insert('batch-b');
      await runtime.appendInput(
        revision: 0,
        question: '两笔午饭各17元',
        source: 'AI_IMAGE',
        answer: '已保存两笔',
        status: 'completed',
        runId: 'combined-run',
        guard: () async {},
        parts: [
          {
            'type': 'receipt',
            'status': 'applied',
            'transactions': [a.toMap(), b.toMap()],
          },
          {
            'type': 'recognition_state',
            'drafts': [a.copyWith(id: 0).toMap(), b.copyWith(id: 0).toMap()],
            'undoneOperations': <String>[],
          },
        ],
      );
      await expectLater(
        undo('combined-run', transactionIds: [a.id, a.id]),
        throwsA(isA<LedgerException>()),
      );
      final first = await undo('combined-run', transactionIds: [a.id]);
      expect(first['count'], 1);
      expect((await ledger.snapshot()).transactions.single.id, b.id);
      final firstParts = ((first['session'] as Map)['messages'] as List)
          .expand((message) => message['parts'] as List? ?? [])
          .toList();
      final firstReceipt = firstParts.singleWhere(
        (part) => part['type'] == 'receipt',
      );
      expect(firstReceipt['status'], 'partially_undone');
      expect(firstReceipt['transactions'], hasLength(2));
      expect(firstReceipt['undoneTransactionIds'], [a.id]);
      final state = firstParts.singleWhere(
        (part) => part['type'] == 'recognition_state',
      );
      expect(state['undoneOperations'], ['op:batch-a']);
      expect(state['drafts'], hasLength(1));
      final second = await undo('combined-run');
      expect(second['count'], 1);
      expect((await ledger.snapshot()).transactions, isEmpty);
      await expectLater(undo('combined-run'), throwsA(isA<LedgerException>()));
    },
  );

  test('selected transaction ids must belong to the stored run', () async {
    final a = await insert('run-a'), b = await insert('run-b');
    await receipt('run-a', a);
    await expectLater(
      undo('run-a', transactionIds: [b.id]),
      throwsA(isA<LedgerException>()),
    );
    expect((await ledger.snapshot()).transactions, hasLength(2));
  });

  test(
    'undo uses stored run ids and leaves other runs and edits untouched',
    () async {
      final a = await insert('run-a'), b = await insert('run-b');
      await receipt('run-a', a, action: true);
      await receipt('run-b', b, action: true);
      final result = await undo('run-a');
      expect(result['count'], 1);
      expect((await ledger.snapshot()).transactions.single.id, b.id);
      final actions = (result['session'] as Map)['actions'] as List;
      expect(
        actions.singleWhere((action) => action['runId'] == 'run-a')['status'],
        'undone',
      );
      expect(
        actions.singleWhere((action) => action['runId'] == 'run-b')['status'],
        'applied',
      );
      await expectLater(undo('run-a'), throwsA(isA<LedgerException>()));
      expect((await ledger.snapshot()).transactions.single.id, b.id);
    },
  );

  test(
    'an edited record rejects the entire undo without changing receipts',
    () async {
      final a = await insert('run-a');
      await receipt('run-a', a);
      await ledger.invoke('saveTransaction', {
        ...a.toMap(),
        'revision': 0,
        'amount': 1800,
      });
      await expectLater(undo('run-a'), throwsA(isA<LedgerException>()));
      final session = await runtime.active(0);
      final part = session.messages
          .expand((message) => message['parts'] as List? ?? [])
          .firstWhere((part) => part['type'] == 'receipt');
      expect(part['status'], 'applied');
      expect((await ledger.snapshot()).transactions.single.amountFen, 1800);
    },
  );

  test(
    'recognition receipts undo after restart and cannot restore deleted drafts',
    () async {
      final a = await insert('image-run', source: 'AI_IMAGE');
      await receipt('image-run', a);
      final restarted = LedgerAgentRuntime(store: store, clock: () => now);
      final result = await restarted.undoRun(
        revision: 0,
        runId: 'image-run',
        ledger: ledger,
        guard: () async {},
      );
      expect(result['count'], 1);
      expect((await ledger.snapshot()).transactions, isEmpty);
      final parts = ((result['session'] as Map)['messages'] as List)
          .expand((message) => message['parts'] as List? ?? [])
          .toList();
      expect(
        parts.singleWhere((part) => part['type'] == 'receipt')['status'],
        'undone',
      );
      final state = parts.singleWhere(
        (part) => part['type'] == 'recognition_state',
      );
      expect(state['undoneOperations'], ['op:image-run']);
      expect(state['drafts'], isEmpty);
      await expectLater(
        restarted.undoRun(
          revision: 0,
          runId: 'image-run',
          ledger: ledger,
          guard: () async {},
        ),
        throwsA(isA<LedgerException>()),
      );
    },
  );

  test(
    'budget, edit and deletion actions cannot enter new-record undo',
    () async {
      final tx = await insert('run-edit');
      await receipt('run-edit', tx, action: true);
      final session = await runtime.active(0);
      session.actions.single['payload'] = {
        'transaction': tx.toMap(),
        'before': tx.toMap(),
      };
      await store.write('session:0:${session.id}', session.toMap());
      await expectLater(undo('run-edit'), throwsA(isA<LedgerException>()));
      expect((await ledger.snapshot()).transactions.single.id, tx.id);
    },
  );
}

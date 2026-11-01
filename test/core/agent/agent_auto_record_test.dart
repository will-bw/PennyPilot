import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/agent/agent_models.dart';
import 'package:pennypilot/core/agent/agent_runtime.dart';
import 'package:pennypilot/core/agent/agent_store.dart';
import 'package:pennypilot/core/models.dart';
import 'package:pennypilot/core/storage/portable_ledger_store.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

AgentReply _call(String id, String name, Map<String, dynamic> args) =>
    AgentReply(
      message: {
        'role': 'assistant',
        'content': '',
        'tool_calls': [
          {
            'id': id,
            'type': 'function',
            'function': {'name': name, 'arguments': jsonEncode(args)},
          },
        ],
      },
    );
AgentReply _answer(String text) =>
    AgentReply(message: {'role': 'assistant', 'content': text});
Map<String, dynamic> _observation(List<Map<String, dynamic>> messages) =>
    stringMap(
      jsonDecode(
        messages.lastWhere((m) => m['role'] == 'tool')['content'] as String,
      ),
    );

void main() {
  final now = DateTime(2026, 10, 24, 12);
  late PortableLedgerStore ledger;
  late LedgerAgentRuntime runtime;
  var writes = 0;
  setUpAll(sqfliteFfiInit);
  setUp(() {
    ledger = PortableLedgerStore(
      databaseFactory: databaseFactoryFfi,
      databasePath: inMemoryDatabasePath,
      clock: () => now,
    );
    runtime = LedgerAgentRuntime(store: MemoryAgentStore(), clock: () => now);
    writes = 0;
  });
  tearDown(() => ledger.close());
  Future<({int id, LedgerSnapshot ledger})?> record(
    Map<String, dynamic> tx,
  ) async {
    writes++;
    final result = stringMap(
      await ledger.invoke('saveTransaction', {...tx, 'revision': 0}),
    );
    return (id: result['id'] as int, ledger: await ledger.snapshot());
  }

  Map<String, dynamic> meal(String evidence) => {
    'amount': 1700,
    'type': 'EXPENSE',
    'category_id': 1,
    'business_kind': 'expense',
    'evidence': evidence,
  };
  Future<Map<String, dynamic>> run(
    AgentComplete complete, {
    String question = '午饭17元',
    bool auto = true,
  }) async => runtime.run(
    question: question,
    ledger: await ledger.snapshot(),
    model: AgentModel(identity: 'test:auto-record', complete: complete),
    guard: () async {},
    autoRecord: auto ? record : null,
  );

  test(
    'automatic chat entry writes the real store and later tools read the new balance',
    () async {
      var step = 0;
      final result = await run((messages, _) async {
        switch (++step) {
          case 1:
            return _call('record', 'propose_transaction', meal('午饭17元'));
          case 2:
            final saved = _observation(messages);
            expect(saved['status'], 'applied');
            expect((saved['data'] as Map)['amount'], 1700);
            return _call('total', 'aggregate_by', {
              'start': '2026-10-24',
              'end': '2026-10-24',
              'group_by': 'category',
            });
          default:
            final total = _observation(messages);
            expect((total['data'] as Map)['expense'], 1700);
            return _answer('已记17元，今天支出17元。[依据 ${total['evidence_id']}]');
        }
      });
      expect(result['isError'], false);
      expect(writes, 1);
      final saved = (await ledger.snapshot()).transactions.single;
      expect(saved.amountFen, 1700);
      expect(saved.recognitionMetadata['autoRecorded'], true);
      expect(result['autoSavedIds'], [saved.id]);
      expect(
        ((result['session'] as Map)['actions'] as List).single['status'],
        'applied',
      );
    },
  );

  test(
    'repeating the same new-entry tool call in one run is idempotent',
    () async {
      var step = 0;
      int? id;
      final result = await run((messages, _) async {
        if (++step <= 2) {
          if (step == 2) id = _observation(messages)['transaction_id'] as int;
          return _call('record-$step', 'propose_transaction', meal('午饭17元'));
        }
        final saved = _observation(messages);
        expect(saved['transaction_id'], id);
        return _answer('已记17元。[依据 ${saved['evidence_id']}]');
      });
      expect(result['isError'], false);
      expect(writes, 1);
      expect((await ledger.snapshot()).transactions, hasLength(1));
      expect((result['session'] as Map)['actions'], hasLength(1));
    },
  );

  test(
    'spoken relative date updates both stored entry and applied action title',
    () async {
      const text = '昨天晚上7点过吃盖饭17元';
      var step = 0;
      final result = await run((messages, _) async {
        if (++step == 1) {
          return _call('record', 'propose_transaction', meal(text));
        }
        final saved = _observation(messages);
        expect(saved['title'], contains('2026-10-23'));
        expect((saved['data'] as Map)['date'], startsWith('2026-10-23T19:00'));
        return _answer('已记昨晚盖饭17元。[依据 ${saved['evidence_id']}]');
      }, question: text);
      expect(result['isError'], false);
      expect(
        (await ledger.snapshot()).transactions.single.occurredAt,
        DateTime(2026, 10, 23, 19),
      );
    },
  );

  test('disabling automatic saving keeps the pending proposal flow', () async {
    var step = 0;
    final result = await run(
      (_, _) async => ++step == 1
          ? _call('record', 'propose_transaction', meal('午饭17元'))
          : _answer('已准备待核对的账单。'),
      auto: false,
    );
    expect(result['isError'], false);
    expect(writes, 0);
    expect((await ledger.snapshot()).transactions, isEmpty);
    expect((result['session'] as Map)['status'], 'awaiting_confirmation');
  });

  for (final text in [
    '明天准备午饭17元',
    '午饭17元还没支付',
    '看看今天午饭17元吗',
    '午饭18元',
    '10月17号午饭100元',
  ]) {
    test(
      'unpaid, future, query or fabricated amount cannot auto-record: $text',
      () async {
        var step = 0;
        final result = await run((messages, _) async {
          if (++step == 1) {
            return _call('record', 'propose_transaction', meal(text));
          }
          expect(_observation(messages)['error'], 'validation_error');
          return _answer('本次没有新增账目。');
        }, question: text);
        expect(result['isError'], false);
        expect(writes, 0);
        expect((await ledger.snapshot()).transactions, isEmpty);
        expect(
          ((result['session'] as Map)['actions'] as List).single['status'],
          'invalid',
        );
      },
    );
  }

  test(
    'successful writes remain reported if the next model request fails',
    () async {
      var step = 0;
      final result = await run((_, _) async {
        if (++step == 1) {
          return _call('record', 'propose_transaction', meal('午饭17元'));
        }
        throw StateError('offline');
      });
      expect(result['isError'], true);
      expect(writes, 1);
      expect(result['autoSavedIds'], [
        (await ledger.snapshot()).transactions.single.id,
      ]);
    },
  );

  test(
    'edit delete and budget proposals never use the automatic new-entry callback',
    () async {
      await ledger.invoke('saveTransaction', {
        'revision': 0,
        'amount': 1700,
        'type': 'EXPENSE',
        'categoryId': 1,
        'source': 'MANUAL',
        'businessKind': 'expense',
        'occurredAt': now.millisecondsSinceEpoch,
      });
      var step = 0;
      final result = await run((messages, _) async {
        switch (++step) {
          case 1:
            return _call('read', 'get_transaction', {'transaction_id': 1});
          case 2:
            return _call('edit', 'propose_transaction', {
              ...meal('改成20元'),
              'transaction_id': 1,
              'amount': 2000,
            });
          case 3:
            return _call('delete', 'propose_delete_transaction', {
              'transaction_id': 1,
              'evidence': '删除第一笔',
            });
          case 4:
            return _call('budget', 'propose_budget', {
              'amount': 300000,
              'evidence': '预算3000元',
            });
          default:
            return _answer('三项修改等待核对。[依据 Q1]');
        }
      }, question: '第一笔改成20元，再删除第一笔，预算3000元');
      expect(result['isError'], false);
      expect(writes, 0);
      expect((await ledger.snapshot()).transactions.single.amountFen, 1700);
      expect(
        ((result['session'] as Map)['actions'] as List).map((a) => a['status']),
        everyElement('pending'),
      );
    },
  );
}

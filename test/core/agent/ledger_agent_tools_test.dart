import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/agent/agent_models.dart';
import 'package:pennypilot/core/agent/agent_store.dart';
import 'package:pennypilot/core/agent/ledger_agent_tools.dart';
import 'package:pennypilot/core/models.dart';

void main() {
  final now = DateTime(2026, 10, 24);
  LedgerTransaction tx(
    int id,
    int amount,
    String kind, {
    TxType type = TxType.expense,
    TxStatus status = TxStatus.confirmed,
  }) => LedgerTransaction(
    id: id,
    amountFen: amount,
    type: type,
    categoryId: type == TxType.expense ? 1 : 2,
    occurredAt: DateTime(2026, 10, 3, 12),
    businessKind: kind,
    status: status,
  );
  final ledger = LedgerSnapshot(
    revision: 0,
    categories: const [
      LedgerCategory(id: 1, name: '餐饮', type: TxType.expense),
      LedgerCategory(id: 2, name: '其他收入', type: TxType.income),
    ],
    transactions: [
      tx(1, 10000, 'expense'),
      tx(2, 2000, 'refund', type: TxType.income),
      tx(3, 3000, 'transfer'),
      tx(4, 4000, 'receivable'),
      tx(5, 50000, 'income', type: TxType.income),
      tx(6, 9000, 'expense', status: TxStatus.pending),
    ],
    settings: {'budgetCents': 20000},
  );
  LedgerAgentTools tools() => LedgerAgentTools(
    store: MemoryAgentStore(),
    session: AgentSession.create(0, now),
    ledger: ledger,
    question: '查询账本',
    now: now,
  );
  test(
    'ordinary expense becomes a proposal without date, category or merchant',
    () async {
      final session = AgentSession.create(0, now);
      final t = LedgerAgentTools(
        store: MemoryAgentStore(),
        session: session,
        ledger: ledger,
        question: '午饭17块钱',
        now: now,
      );
      final result = await t.execute('propose_transaction', {
        'amount': 1700,
        'type': 'EXPENSE',
        'business_kind': 'expense',
        'evidence': '午饭17块钱',
      });
      expect(result['status'], 'awaiting_confirmation');
      final proposal = session.actions.single;
      expect(proposal['status'], 'pending');
      final transaction = (proposal['payload'] as Map)['transaction'] as Map;
      expect(transaction['occurredAt'], now.millisecondsSinceEpoch);
      expect(transaction['categoryId'], 1);
      expect(transaction['merchant'], isNull);
      final metadata = jsonDecode(transaction['metadataJson'] as String) as Map;
      expect(metadata['assumptions'], contains('未提日期，按今天（2026-10-24）记录。'));
      expect(metadata['assumptions'], result['assumptions']);
      expect(ledger.transactions, hasLength(6));
    },
  );
  test(
    'inferred date and category remain visible without a clarification round',
    () async {
      final session = AgentSession.create(0, now);
      final t = LedgerAgentTools(
        store: MemoryAgentStore(),
        session: session,
        ledger: ledger,
        question: '10月23日吃盖饭17块钱',
        now: now,
      );
      final result = await t.execute('propose_transaction', {
        'amount': 1700,
        'type': 'EXPENSE',
        'business_kind': 'expense',
        'category_id': 1,
        'date': '2026-10-23',
        'evidence': '10月23日吃盖饭17块钱',
        'assumptions': ['10月23日按今年理解', '盖饭按餐饮分类'],
      });
      expect(result['status'], 'awaiting_confirmation');
      expect(result['assumptions'], ['10月23日按今年理解', '盖饭按餐饮分类']);
      final transaction =
          (session.actions.single['payload'] as Map)['transaction'] as Map;
      expect(
        transaction['occurredAt'],
        DateTime(2026, 10, 23).millisecondsSinceEpoch,
      );
    },
  );
  test('amount-only edit retains existing date, time and category', () async {
    final session = AgentSession.create(0, now);
    final t = LedgerAgentTools(
      store: MemoryAgentStore(),
      session: session,
      ledger: ledger,
      question: '第一笔金额改成17元',
      now: now,
    );
    await t.execute('get_transaction', {'transaction_id': 1});
    final result = await t.execute('propose_transaction', {
      'transaction_id': 1,
      'amount': 1700,
      'type': 'EXPENSE',
      'business_kind': 'expense',
      'evidence': '金额改成17元',
    });
    expect(result['status'], 'awaiting_confirmation');
    final transaction =
        (session.actions.single['payload'] as Map)['transaction'] as Map;
    expect(
      transaction['occurredAt'],
      ledger.transactions.first.occurredAt.millisecondsSinceEpoch,
    );
    expect(transaction['categoryId'], ledger.transactions.first.categoryId);
    expect(result['assumptions'], isNull);
  });
  test(
    'convenient proposal defaults still require amount and reject future dates',
    () async {
      final t = LedgerAgentTools(
        store: MemoryAgentStore(),
        session: AgentSession.create(0, now),
        ledger: ledger,
        question: '午饭17块钱',
        now: now,
      );
      final args = {
        'amount': 1700,
        'type': 'EXPENSE',
        'business_kind': 'expense',
        'evidence': '午饭17块钱',
      };
      await expectLater(
        t.execute('propose_transaction', {...args}..remove('amount')),
        throwsFormatException,
      );
      await expectLater(
        t.execute('propose_transaction', {...args, 'date': '2026-10-25'}),
        throwsFormatException,
      );
      await expectLater(
        t.execute('propose_transaction', {...args, 'date': '2026-02-30'}),
        throwsFormatException,
      );
      await expectLater(
        t.execute('propose_transaction', {...args, 'category_id': 999}),
        throwsFormatException,
      );
    },
  );
  test(
    'all aggregates and budgets agree on refund/transfer/loan/status accounting',
    () async {
      final t = tools();
      final result = await t.execute('aggregate_by', {
        'start': '2026-10-01',
        'end': '2026-10-31',
        'group_by': 'category',
      });
      final data = result['data'] as Map;
      expect(data['expense'], 8000);
      expect(data['income'], 50000);
      expect(data['count'], 5);
      final budget =
          (await t.execute('get_budget', {'month': '2026-10'}))['data'] as Map;
      expect(budget['expense'], 8000);
      expect(budget['remaining'], 12000);
      final comparison =
          (await t.execute('compare_periods', {
                'period_a_start': '2026-09-01',
                'period_a_end': '2026-09-30',
                'period_b_start': '2026-10-01',
                'period_b_end': '2026-10-31',
              }))['data']
              as Map;
      expect(comparison['expense_change'], 8000);
      expect(comparison['expense_change_percent'], null);
    },
  );
  test(
    'paging reports complete matching count, next offset and explicit status',
    () async {
      final t = tools();
      final first =
          (await t.execute('query_transactions', {
                'start': '2026-10-03',
                'end': '2026-10-03',
                'limit': 2,
              }))['data']
              as Map;
      expect(first['total_count'], 5);
      expect(first['transactions'], hasLength(2));
      expect(first['next_offset'], 2);
      expect(first['truncated'], true);
      final pending =
          (await t.execute('query_transactions', {
                'start': '2026-10-03',
                'end': '2026-10-03',
                'status': 'PENDING',
              }))['data']
              as Map;
      expect(pending['total_count'], 1);
    },
  );
  test(
    'invalid dates, enums, identifiers and injected unknown keys are rejected',
    () async {
      final t = tools();
      for (final args in [
        {'start': '2026-02-30', 'end': '2026-10-03'},
        {'start': '2026-10-03', 'end': '2026-10-02'},
        {'start': '2026-10-03', 'end': '2026-10-03', 'category_id': 999},
        {
          'start': '2026-10-03',
          'end': '2026-10-03',
          'sql': 'drop table transactions',
        },
        {'start': '2026-10-03', 'end': '2026-10-03', 'status': 'INVALID'},
      ]) {
        await expectLater(
          t.execute('query_transactions', args),
          throwsFormatException,
        );
      }
    },
  );
}

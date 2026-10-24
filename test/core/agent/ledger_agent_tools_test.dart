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

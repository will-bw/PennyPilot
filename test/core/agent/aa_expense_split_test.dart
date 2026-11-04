import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/agent/agent_models.dart';
import 'package:pennypilot/core/agent/agent_store.dart';
import 'package:pennypilot/core/agent/ledger_agent_tools.dart';
import 'package:pennypilot/core/models.dart';

void main() {
  final now = DateTime(2026, 11, 4);
  final split = ExpenseSplit(totalAmountFen: 10000, participantCount: 4);
  late AgentSession session;
  late LedgerAgentTools tools;
  setUp(() {
    session = AgentSession.create(0, now);
    tools = LedgerAgentTools(
      store: MemoryAgentStore(),
      session: session,
      question: '商户改成饭店；总人数改成3人；取消AA；个人金额改为30元',
      now: now,
      ledger: LedgerSnapshot(
        revision: 0,
        categories: const [
          LedgerCategory(id: 1, name: '餐饮', type: TxType.expense),
        ],
        transactions: [
          LedgerTransaction(
            id: 1,
            amountFen: 2500,
            type: TxType.expense,
            categoryId: 1,
            occurredAt: now,
            metadataJson: jsonEncode({
              'aaSplit': split.toMap(),
              'keep': 'evidence',
            }),
          ),
        ],
      ),
    );
  });
  Map<String, dynamic> args(String evidence) => {
    'transaction_id': 1,
    'amount': 2500,
    'type': 'EXPENSE',
    'business_kind': 'expense',
    'evidence': evidence,
  };
  Map metadata() =>
      jsonDecode(
            (((session.actions.single['payload'] as Map)['transaction']
                    as Map)['metadataJson'])
                as String,
          )
          as Map;

  test(
    'merchant-only edits retain AA without dividing personal amount again',
    () async {
      final read = await tools.execute('get_transaction', {
        'transaction_id': 1,
      });
      expect((read['data'] as Map)['aa_split'], split.toMap());
      await tools.execute('propose_transaction', {
        ...args('商户改成饭店'),
        'merchant': '饭店',
      });
      expect(metadata()['aaSplit'], split.toMap());
      expect(metadata()['keep'], 'evidence');
      expect(
        ((session.actions.single['payload'] as Map)['transaction']
            as Map)['amount'],
        2500,
      );
    },
  );

  test('count edits reuse the recorded original total', () async {
    await tools.execute('get_transaction', {'transaction_id': 1});
    await tools.execute('propose_transaction', {
      ...args('总人数改成3人'),
      'amount': 3333,
      'aa_total_amount': 10000,
      'aa_participant_count': 3,
    });
    expect(metadata()['aaSplit'], {
      'totalAmountFen': 10000,
      'participantCount': 3,
    });
  });

  test(
    'the old recorded count cannot override a different current count',
    () async {
      await tools.execute('get_transaction', {'transaction_id': 1});
      await expectLater(
        tools.execute('propose_transaction', {
          ...args('总人数改成3人'),
          'aa_total_amount': 10000,
          'aa_participant_count': 4,
        }),
        throwsFormatException,
      );
      expect(session.actions, isEmpty);
    },
  );
  test('canceling AA clears only split metadata', () async {
    await tools.execute('get_transaction', {'transaction_id': 1});
    await tools.execute('propose_transaction', {
      ...args('取消AA'),
      'amount': 10000,
      'clear_aa_split': true,
    });
    expect(metadata().containsKey('aaSplit'), false);
    expect(metadata()['keep'], 'evidence');
  });

  test('direct personal amount edits clear stale split metadata', () async {
    await tools.execute('get_transaction', {'transaction_id': 1});
    await tools.execute('propose_transaction', {
      ...args('个人金额改为30元'),
      'amount': 3000,
    });
    expect(metadata().containsKey('aaSplit'), false);
  });
}

import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/models.dart';

void main() {
  test('equal sharing rounds to fen and includes the user', () {
    expect(
      ExpenseSplit(
        totalAmountFen: 10000,
        participantCount: 4,
      ).personalAmountFen,
      2500,
    );
    expect(
      ExpenseSplit(
        totalAmountFen: 10000,
        participantCount: 3,
      ).personalAmountFen,
      3333,
    );
    expect(
      ExpenseSplit(totalAmountFen: 1, participantCount: 2).personalAmountFen,
      1,
    );
    expect(
      ExpenseSplit(totalAmountFen: 101, participantCount: 2).personalAmountFen,
      51,
    );
  });

  test('invalid and extreme metadata cannot become a valid split', () {
    for (final value in [
      {'totalAmountFen': 100, 'participantCount': 1},
      {'totalAmountFen': 0, 'participantCount': 2},
      {'totalAmountFen': 1, 'participantCount': 3},
      {'totalAmountFen': maxTransactionFen + 1, 'participantCount': 2},
      {'totalAmountFen': 9223372036854775807, 'participantCount': 2},
      {'totalAmountFen': 100, 'participantCount': 9223372036854775807},
      {'totalAmountFen': 100.0, 'participantCount': 2},
    ]) {
      expect(ExpenseSplit.fromMap(value), isNull, reason: '$value');
    }
    expect(
      () => ExpenseSplit(totalAmountFen: 100, participantCount: 0),
      throwsFormatException,
    );
  });

  test('transaction recognizes only matching expense metadata', () {
    final split = ExpenseSplit(totalAmountFen: 10000, participantCount: 4);
    final tx = LedgerTransaction(
      amountFen: 2500,
      type: TxType.expense,
      categoryId: 1,
      occurredAt: DateTime(2026, 11, 4),
      metadataJson: jsonEncode({'aaSplit': split.toMap(), 'evidence': '原付款'}),
    );
    expect(
      LedgerTransaction.fromMap(tx.toMap()).expenseSplit?.toMap(),
      split.toMap(),
    );
    expect(tx.copyWith(amountFen: 10000).expenseSplit, isNull);
    expect(tx.copyWith(type: TxType.income).expenseSplit, isNull);
    expect(tx.copyWith(businessKind: 'receivable').expenseSplit, isNull);
  });

  test(
    'participant evidence accepts linked short replies and rejects guessing',
    () {
      final split = ExpenseSplit(totalAmountFen: 10000, participantCount: 4);
      expect(split.hasParticipantEvidence('午饭100元，四个人AA'), true);
      expect(split.hasParticipantEvidence('午饭100元AA\n补充：4'), true);
      expect(split.hasParticipantEvidence('午饭100元AA，补充：四个'), true);
      expect(split.hasParticipantEvidence('午饭100元AA'), false);
      expect(split.hasParticipantEvidence('我和四个人AA'), false);
      expect(ExpenseSplit.isRequested('SaaS服务费'), false);
    },
  );
  test('latest count clarification replaces the old count', () {
    final old = ExpenseSplit(totalAmountFen: 10000, participantCount: 4);
    final changed = ExpenseSplit(totalAmountFen: 10000, participantCount: 3);
    const input = '午饭100元4人AA\n补充：改成3人';
    expect(old.hasParticipantEvidence(input), false);
    expect(changed.hasParticipantEvidence(input), true);
    expect(old.hasParticipantEvidence('午饭100元4人AA\n补充：3'), false);
    expect(changed.hasParticipantEvidence('午饭100元4人AA\n补充：3'), true);
    expect(old.hasParticipantEvidence('午饭100元4人还是3人AA'), false);
    expect(changed.hasParticipantEvidence('午饭100元4人还是3人AA'), false);
  });
}

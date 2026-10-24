import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/models.dart';
import 'package:pennypilot/core/recognition/recognition_protocol.dart';

final now = DateTime(2026, 10, 24, 12);
final snapshot = LedgerSnapshot(
  revision: 0,
  categories: const [
    LedgerCategory(id: 1, name: '餐饮', type: TxType.expense),
    LedgerCategory(id: 2, name: '其他', type: TxType.income),
  ],
);
Map<String, dynamic> proposal({
  int amount = 1200,
  String type = 'EXPENSE',
  String kind = 'expense',
  String quote = '12',
  String status = 'declared',
}) => {
  'amount': amount,
  'type': type,
  'businessKind': kind,
  'currency': 'CNY',
  'paymentStatus': status,
  'category': '餐饮',
  'merchant': '午饭',
  'fieldEvidence': {
    'amount': {'quote': quote, 'source': 'user'},
    'type': {'quote': '午饭', 'source': 'user'},
  },
  'missingFields': [],
  'questions': [],
  'conflicts': [],
};
Map<String, dynamic> validate(
  List<Map<String, dynamic>> rows, {
  String input = '帮我记午饭12元',
  String source = 'AI_TEXT',
  String intent = 'record',
}) => RecognitionProtocol.validate(
  {'intent': intent, 'proposals': rows},
  snapshot: snapshot,
  input: input,
  source: source,
  now: now,
  operationId: 'request',
);
void main() {
  test(
    'explicit text yields cited fen draft with stable operation identity',
    () {
      final r = validate([proposal()]);
      expect(r['autoSaveEligible'], true);
      final tx = LedgerTransaction.fromMap(
        stringMap((r['transactions'] as List).single),
        draft: true,
      );
      expect(tx.amountFen, 1200);
      expect(tx.recognitionMetadata['operationId'], 'request:0');
      expect(tx.recognitionMetadata['timePrecision'], 'user_entry_time');
    },
  );
  test('multiple drafts preserve amounts and distinct logical identities', () {
    final r = validate([
      proposal(),
      proposal(amount: 500, quote: '5'),
    ], input: '午饭12，午饭5');
    expect((r['transactions'] as List).length, 2);
    expect(
      (r['transactions'] as List)
          .map((v) => jsonDecode(v['metadataJson'])['operationId'])
          .toSet()
          .length,
      2,
    );
  });
  for (final intent in ['plan', 'query', 'correct', 'unknown']) {
    test('$intent never creates a completed transaction', () {
      expect(validate([proposal()], intent: intent)['transactions'], isEmpty);
    });
  }
  for (final status in ['pending', 'failed', 'unknown']) {
    test('$status payment never becomes a draft', () {
      expect(validate([proposal(status: status)])['transactions'], isEmpty);
    });
  }
  test('unknown direction is not defaulted to expense', () {
    expect(validate([proposal(type: 'UNKNOWN')])['transactions'], isEmpty);
  });
  test('direction and business semantics conflict is rejected', () {
    expect(validate([proposal(type: 'INCOME')])['transactions'], isEmpty);
  });
  test('model fabricated quote cannot ground an amount', () {
    expect(
      validate([proposal(quote: '500', amount: 50000)])['transactions'],
      isEmpty,
    );
  });
  test('amount quote cannot support another number', () {
    expect(validate([proposal(amount: 12000)])['transactions'], isEmpty);
  });
  test('invalid calendar date is rejected not normalized', () {
    expect(
      validate([
        {...proposal(), 'occurred_at': '2026-02-30T10:00:00'},
      ])['transactions'],
      isEmpty,
    );
  });
  test('future timestamp is not a completed expense', () {
    expect(
      validate([
        {...proposal(), 'occurred_at': '2026-10-25T10:00:00'},
      ])['transactions'],
      isEmpty,
    );
  });
  test('image needs original transaction date and never auto saves', () {
    expect(validate([proposal()], source: 'AI_IMAGE')['transactions'], isEmpty);
    final r = validate([
      {...proposal(status: 'completed'), 'occurred_at': '2026-10-23T10:00:00'},
    ], source: 'AI_IMAGE');
    expect((r['transactions'] as List).length, 1);
    expect(r['autoSaveEligible'], false);
  });
  for (final phrase in [
    '前几天',
    '几天前',
    '上星期',
    '上个月',
    '去年',
    '那天',
    '周一',
    '之前',
    '前段时间',
    '最近',
  ]) {
    test(
      'vague date $phrase requires clarification even if model supplies today',
      () {
        for (final modelDate in [null, '2026-10-24T10:00:00']) {
          final r = validate([
            {...proposal(), 'occurred_at': modelDate},
          ], input: '$phrase午饭12元');
          expect(r['transactions'], isEmpty);
          expect(r['questions'], isNotEmpty);
          expect(r['autoSaveEligible'], false);
        }
      },
    );
  }
  for (final item in {
    '昨天': '2026-10-23',
    '前天': '2026-10-22',
    '上周五': '2026-10-16',
    '本星期一': '2026-10-19',
    '3天前': '2026-10-21',
    '10月20日': '2026-10-20',
    '2025年12月31日': '2025-12-31',
  }.entries) {
    test('clear date ${item.key} is independently resolved and checked', () {
      final text = '${item.key}午饭12元';
      final resolved = validate([proposal()], input: text);
      final tx = LedgerTransaction.fromMap(
        stringMap((resolved['transactions'] as List).single),
        draft: true,
      );
      expect(tx.occurredAt, DateTime.parse(item.value));
      expect(tx.recognitionMetadata['timePrecision'], 'day');
      final matched = validate([
        {...proposal(), 'occurred_at': '${item.value}T10:00:00'},
      ], input: text);
      expect(matched['transactions'], hasLength(1));
      final mismatch = validate([
        {...proposal(), 'occurred_at': '2026-10-24T10:00:00'},
      ], input: text);
      expect(mismatch['transactions'], isEmpty);
    });
  }
  test('model cannot invent a historical date without original evidence', () {
    expect(
      validate([
        {...proposal(), 'occurred_at': '2025-10-23T10:00:00'},
      ])['transactions'],
      isEmpty,
    );
  });
  test('multiple entry dates remain associated with their own clauses', () {
    final r = validate([
      proposal(),
      proposal(amount: 500, quote: '5'),
    ], input: '昨天午饭12元，今天午饭5元');
    final drafts = (r['transactions'] as List)
        .map((v) => LedgerTransaction.fromMap(stringMap(v), draft: true))
        .toList();
    expect(drafts.map((v) => v.occurredAt), [
      DateTime(2026, 10, 23),
      DateTime(2026, 10, 24),
    ]);
  });
  test('past date in a separate clause is clarified rather than lost', () {
    expect(validate([proposal()], input: '前几天，午饭12元')['transactions'], isEmpty);
  });
  test('future day with asserted completion is still rejected', () {
    expect(validate([proposal()], input: '明天午饭付了12元')['transactions'], isEmpty);
  });
  test('transfer persists money movement without counting as expense', () {
    final r = validate([proposal(kind: 'transfer')]);
    final tx = LedgerTransaction.fromMap(
      stringMap((r['transactions'] as List).single),
      draft: true,
    );
    expect(tx.countsAsExpense, false);
    expect(tx.countsAsIncome, false);
    expect(r['autoSaveEligible'], false);
  });
  test('Chinese spoken whole yuan is checked deterministically', () {
    expect(RecognitionProtocol.amountMatchesQuote(5000, '五十元'), true);
    expect(RecognitionProtocol.amountMatchesQuote(1500, '五十元'), false);
    expect(RecognitionProtocol.amountMatchesQuote(230000, '两千三百'), true);
    expect(RecognitionProtocol.amountMatchesQuote(2850, '二十八块五'), true);
    expect(RecognitionProtocol.amountMatchesQuote(1235, '十二点三五'), true);
    expect(RecognitionProtocol.amountMatchesQuote(1200, '十二点三五'), false);
    expect(RecognitionProtocol.amountMatchesQuote(500, '十二点三五'), false);
  });
  test('derived split must conserve total', () {
    Map<String, dynamic> split(int amount, String kind) => {
      ...proposal(amount: amount, kind: kind, quote: '240'),
      'fieldEvidence': {
        'amount': {'quote': '240', 'source': 'derived'},
        'type': {'quote': '午饭', 'source': 'user'},
      },
      'allocation': {
        'groupId': 'dinner',
        'totalAmount': 24000,
        'totalQuote': '240',
        'parts': [8000, 16000],
      },
    };
    final r = validate([
      split(8000, 'expense'),
      split(16000, 'receivable'),
    ], input: '午饭240元三人AA');
    expect((r['transactions'] as List).length, 2);
    expect(r['autoSaveEligible'], false);
    final bad = validate([split(8000, 'expense')], input: '午饭240元三人AA');
    expect(bad['transactions'], isEmpty);
  });
  test('model confidence never authorizes missing evidence', () {
    final row = proposal()..remove('fieldEvidence');
    row['confidence'] = 0.9999;
    expect(validate([row])['transactions'], isEmpty);
  });
  test('different currency is not silently treated as CNY', () {
    expect(
      validate([
        {...proposal(), 'currency': 'USD'},
      ])['transactions'],
      isEmpty,
    );
  });
  test(
    'quoted payment wording cannot authorize income even when model says source user',
    () {
      final row = proposal(
        amount: 5000,
        type: 'INCOME',
        kind: 'income',
        quote: '50',
      );
      row['fieldEvidence'] = {
        'amount': {'quote': '50', 'source': 'user'},
        'type': {'quote': '支付了', 'source': 'user'},
      };
      final r = validate([row], input: '我支付了50元');
      expect(r['transactions'], isEmpty);
      expect(r['autoSaveEligible'], false);
    },
  );
  test('incoming wording cannot authorize expense', () {
    final row = proposal(amount: 5000, quote: '50');
    row['fieldEvidence'] = {
      'amount': {'quote': '50', 'source': 'user'},
      'type': {'quote': '收到', 'source': 'user'},
    };
    expect(validate([row], input: '我收到50元')['transactions'], isEmpty);
  });
  for (final text in [
    '午饭大概12元',
    '午饭12元左右',
    '午饭12还是15元',
    '我没支付午饭12元',
    '午饭12元不确定',
    '不要记午饭12元',
  ]) {
    test('uncertain or negated speech is never automatically saved: $text', () {
      final r = validate([proposal()], input: text, source: 'AI_VOICE');
      expect(r['autoSaveEligible'], false);
      expect(r['questions'], isNotEmpty);
    });
  }
  for (final role in ['订单号', '余额', '原价', '优惠', '积分', '交易编号']) {
    test(
      '$role is not accepted as actual money merely because quote exists',
      () {
        final row = proposal(amount: 5000, quote: '50');
        row['fieldEvidence'] = {
          'amount': {'quote': '50', 'source': 'user'},
          'type': {'quote': '午饭', 'source': 'user'},
        };
        expect(validate([row], input: '午饭${role}50')['transactions'], isEmpty);
      },
    );
  }
  test(
    'actual amount after original price is accepted but original is rejected',
    () {
      final input = '午饭原价30实付25';
      final actual = proposal(amount: 2500, quote: '25');
      expect(
        (validate([actual], input: input)['transactions'] as List).length,
        1,
      );
      expect(
        validate([
          proposal(amount: 3000, quote: '30'),
        ], input: input)['transactions'],
        isEmpty,
      );
    },
  );
  test('overprecision decimal never partially matches a smaller amount', () {
    expect(RecognitionProtocol.amountMatchesQuote(1200, '12.001'), false);
    expect(RecognitionProtocol.amountMatchesQuote(10, '12.001'), false);
    expect(RecognitionProtocol.amountMatchesQuote(1, '12.001'), false);
  });
  test('unclear direction can be reviewed but cannot auto save', () {
    final row = proposal(amount: 5000, quote: '50');
    row['fieldEvidence'] = {
      'amount': {'quote': '50', 'source': 'user'},
      'type': {'quote': '记一下', 'source': 'user'},
    };
    final r = validate([row], input: '记一下50元');
    expect((r['transactions'] as List).length, 1);
    expect(r['autoSaveEligible'], false);
  });
  test('spoken own transfer cannot be classified as consumption', () {
    final row = proposal(amount: 5000, quote: '50');
    row['fieldEvidence'] = {
      'amount': {'quote': '50', 'source': 'user'},
      'type': {'quote': '转给', 'source': 'user'},
    };
    expect(validate([row], input: '转给我自己50元')['transactions'], isEmpty);
  });
  test(
    'future intent is blocked despite erroneous record intent from model',
    () {
      expect(
        validate([proposal()], input: '明天准备午饭12元')['transactions'],
        isEmpty,
      );
    },
  );
  test('entire quoted clause cannot hide a balance as amount', () {
    final row = proposal(amount: 5000, quote: '午饭余额50');
    expect(validate([row], input: '午饭余额50')['transactions'], isEmpty);
  });
  test('ISO times accept fractions and zones but reject malformed offsets', () {
    expect(
      RecognitionProtocol.strictDate('2026-10-24T12:00:00.123+08:00'),
      isNotNull,
    );
    expect(RecognitionProtocol.strictDate('2026-10-24T12:00+08:00'), isNotNull);
    expect(RecognitionProtocol.strictDate('2026-10-24T12:00:00+08:99'), isNull);
  });
}

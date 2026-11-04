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
  DateTime? at,
}) => RecognitionProtocol.validate(
  {'intent': intent, 'proposals': rows},
  snapshot: snapshot,
  input: input,
  source: source,
  now: at ?? now,
  operationId: 'request',
);
void main() {
  Map<String, dynamic> aa({int amount = 2500, int count = 4}) => {
    ...proposal(amount: amount, quote: '100元'),
    'aaSplit': {'totalAmountFen': 10000, 'participantCount': count},
    'fieldEvidence': {
      'amount': {'quote': '100元', 'source': 'derived'},
      'type': {'quote': '午饭', 'source': 'user'},
    },
  };
  test('AA records only the locally verified personal share', () {
    final result = validate([aa()], input: '午饭我付了100元，4人AA');
    expect(result['questions'], isEmpty);
    final tx = LedgerTransaction.fromMap(
      stringMap((result['transactions'] as List).single),
    );
    expect(tx.amountFen, 2500);
    expect(tx.expenseSplit?.totalAmountFen, 10000);
    expect(tx.expenseSplit?.participantCount, 4);
    expect(result['autoSaveEligible'], true);
    final rounded = validate([
      aa(amount: 3333, count: 3),
    ], input: '午饭100元，三人AA');
    expect(rounded['transactions'], hasLength(1));
  });
  test('ordinary output may include a null AA field', () {
    expect(
      validate([
        {...proposal(), 'aaSplit': null},
      ])['transactions'],
      hasLength(1),
    );
  });
  test('collecting an AA advance does not divide the collected amount', () {
    final result = validate([
      {
        ...proposal(
          amount: 7500,
          type: 'INCOME',
          kind: 'receivable',
          quote: '75元',
        ),
        'fieldEvidence': {
          'amount': {'quote': '75元', 'source': 'user'},
          'type': {'quote': '收回AA垫付', 'source': 'user'},
        },
      },
    ], input: '收回AA垫付75元');
    expect(result['questions'], isEmpty);
    final tx = LedgerTransaction.fromMap(
      stringMap((result['transactions'] as List).single),
    );
    expect(tx.amountFen, 7500);
    expect(tx.expenseSplit, isNull);
    expect(tx.countsAsIncome, false);
  });
  test(
    'AA cannot fabricate a count, ignore sharing or supply wrong division',
    () {
      for (final result in [
        validate([aa()], input: '午饭100元AA'),
        validate([aa(amount: 10000)], input: '午饭100元4人AA'),
        validate([proposal(amount: 10000, quote: '100元')], input: '午饭100元AA'),
        validate([aa()], input: '午饭100元'),
      ]) {
        expect(result['transactions'], isEmpty);
        expect(result['questions'], isNotEmpty);
      }
    },
  );
  for (final kind in [null, 'unknown']) {
    test('inferred expense kind still requires AA allocation: $kind', () {
      final result = validate([
        {...proposal(amount: 10000, quote: '100元'), 'businessKind': kind},
      ], input: '午饭100元AA');
      expect(result['transactions'], isEmpty);
      expect(result['autoSaveEligible'], false);
      expect((result['questions'] as List).join(), contains('AA'));
    });
  }
  test(
    'latest count clarification rejects the old share and accepts the new one',
    () {
      const input = '午饭100元4人AA\n补充：改成3人';
      final stale = validate([aa()], input: input);
      expect(stale['transactions'], isEmpty);
      final corrected = validate([aa(amount: 3333, count: 3)], input: input);
      expect(corrected['questions'], isEmpty);
      expect(corrected['transactions'], hasLength(1));
    },
  );
  test('AA and ordinary records keep separate scopes in one input', () {
    final result = validate([
      aa(),
      {
        ...proposal(amount: 400, quote: '4元'),
        'fieldEvidence': {
          'amount': {'quote': '4元', 'source': 'user'},
          'type': {'quote': '地铁', 'source': 'user'},
        },
      },
    ], input: '午饭100元4人AA；地铁4元');
    expect(result['questions'], isEmpty);
    expect((result['transactions'] as List).map((row) => row['amount']), [
      2500,
      400,
    ]);
  });
  test('explicit personal amount can be recorded without AA count', () {
    final result = validate([
      {
        ...proposal(amount: 2500, quote: '25元'),
        'fieldEvidence': {
          'amount': {'quote': '25元', 'source': 'user'},
          'type': {'quote': '午饭', 'source': 'user'},
        },
      },
    ], input: '午饭AA，我自己承担25元');
    expect(result['questions'], isEmpty);
    expect(result['transactions'], hasLength(1));
  });
  for (final text in ['昨天午饭12元，不记得几点吃的', '昨天午饭12元，还没吃完', '昨天午饭12元，准备回宿舍']) {
    test(
      'nonfinancial uncertainty does not block an actual expense: $text',
      () {
        final result = validate([proposal()], input: text);
        expect(result['transactions'], hasLength(1));
        expect(result['autoSaveEligible'], true);
        expect(result['questions'], isEmpty);
      },
    );
  }
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
      expect(
        tx.recognitionMetadata['operationId'],
        'request:${tx.recognitionMetadata['recognitionKey']}',
      );
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
  for (final status in ['pending', 'failed']) {
    test('$status payment never becomes a draft', () {
      expect(validate([proposal(status: status)])['transactions'], isEmpty);
    });
  }
  test(
    'ordinary consumption can infer direction while unclear movement still asks',
    () {
      final inferred = validate([proposal(type: 'UNKNOWN')]);
      expect(inferred['transactions'], hasLength(1));
      expect(inferred['autoSaveEligible'], true);
      expect(
        validate([
          proposal(type: 'UNKNOWN', kind: 'unknown'),
        ], input: '记一下12元')['transactions'],
        isEmpty,
      );
    },
  );
  test('active declarations do not require proof of payment completion', () {
    final r = validate([proposal(status: 'unknown')]);
    expect(r['transactions'], hasLength(1));
    expect(
      validate([
        proposal(status: 'unknown'),
      ], source: 'AI_IMAGE')['transactions'],
      isEmpty,
    );
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
      ], input: '2026年2月30日午饭12元')['transactions'],
      isEmpty,
    );
  });
  test('future timestamp is not a completed expense', () {
    expect(
      validate([
        {...proposal(), 'occurred_at': '2026-10-25T10:00:00'},
      ], input: '明天午饭12元')['transactions'],
      isEmpty,
    );
  });
  test('noon meal accepts a calendar date without inventing a clock time', () {
    final r = validate([
      {
        ...proposal(amount: 2000, quote: '20块钱'),
        'fieldEvidence': {
          'amount': {'quote': '20块钱', 'source': 'user'},
          'type': {'quote': '花了', 'source': 'user'},
        },
        'occurred_at': '2026-10-24',
        // The local format determines precision, not a model confidence label.
        'timePrecision': 'exact',
      },
    ], input: '今天中午吃的饺子花了20块钱。');
    expect(r['questions'], isEmpty);
    expect(r['autoSaveEligible'], true);
    final tx = LedgerTransaction.fromMap(
      stringMap((r['transactions'] as List).single),
      draft: true,
    );
    expect(tx.amountFen, 2000);
    expect(tx.occurredAt, DateTime(2026, 10, 24));
    expect(tx.recognitionMetadata['timePrecision'], 'day');
  });
  for (final date in ['2026-02-30', '2026-13-01', '2026-10-25']) {
    test('day-only $date still rejects invalid or future dates', () {
      final r = validate([
        {
          ...proposal(status: 'completed'),
          'occurred_at': date,
          'timePrecision': 'day',
        },
      ], source: 'AI_IMAGE');
      expect(r['transactions'], isEmpty);
      expect(r['autoSaveEligible'], false);
    });
  }
  test('incorrect model day is corrected from the original relative date', () {
    final r = validate([
      {...proposal(), 'occurred_at': '2026-10-23'},
    ], input: '今天午饭12元');
    expect(
      (r['transactions'] as List).single['occurredAt'],
      DateTime(2026, 10, 24).millisecondsSinceEpoch,
    );
    expect(r['questions'], isEmpty);
  });
  test('format errors and future timestamps have different questions', () {
    final invalid = validate([
      {...proposal(status: 'completed'), 'occurred_at': '2026-02-30'},
    ], source: 'AI_IMAGE');
    final future = validate([
      {...proposal(status: 'completed'), 'occurred_at': '2026-10-25T10:00:00'},
    ], source: 'AI_IMAGE');
    expect(invalid['questions'], contains(contains('时间格式或日期无效')));
    expect(future['questions'], contains(contains('交易时间在未来')));
  });
  test('recognition prompt states local offset and day-level time rules', () {
    final prompt = RecognitionProtocol.prompt(snapshot, now);
    final localNow = now.toLocal();
    final minutes = localNow.timeZoneOffset.inMinutes.abs();
    final zone =
        '${localNow.timeZoneOffset.isNegative ? '-' : '+'}'
        '${(minutes ~/ 60).toString().padLeft(2, '0')}:'
        '${(minutes % 60).toString().padLeft(2, '0')}';
    expect(prompt, contains('${localNow.toIso8601String()}$zone'));
    expect(prompt, contains('YYYY-MM-DD'));
    expect(prompt, contains('不要编造具体时分'));
    expect(prompt, contains('不得把当地钟面时间直接标成Z'));
  });
  test(
    'image needs a completed status, quoted amount and original transaction date',
    () {
      expect(
        validate([proposal()], source: 'AI_IMAGE')['transactions'],
        isEmpty,
      );
      final r = validate([
        {
          ...proposal(status: 'completed'),
          'occurred_at': '2026-10-23T10:00:00',
        },
      ], source: 'AI_IMAGE');
      expect((r['transactions'] as List).length, 1);
      expect(r['autoSaveEligible'], true);
    },
  );
  test(
    'image date without a clock time retains day precision for automatic recording',
    () {
      final r = validate([
        {...proposal(status: 'completed'), 'occurred_at': '2026-10-23'},
      ], source: 'AI_IMAGE');
      final row = (r['transactions'] as List).single;
      expect(row['occurredAt'], DateTime(2026, 10, 23).millisecondsSinceEpoch);
      expect(jsonDecode(row['metadataJson'])['timePrecision'], 'day');
      expect(r['autoSaveEligible'], true);
    },
  );
  for (final phrase in [
    '前几天',
    '几天前',
    '上星期',
    '上个月',
    '去年',
    '那天',
    '之前',
    '前段时间',
    '最近',
  ]) {
    test('vague date $phrase is recordable with a visible assumption', () {
      for (final modelDate in [null, '2026-10-24T10:00:00']) {
        final r = validate([
          {...proposal(), 'occurred_at': modelDate},
        ], input: '$phrase午饭12元');
        final row = (r['transactions'] as List).single;
        expect(jsonDecode(row['metadataJson'])['assumptions'], isNotEmpty);
        expect(r['questions'], isEmpty);
        expect(r['autoSaveEligible'], true);
      }
    });
  }
  for (final item in {
    '昨天': '2026-10-23',
    '前天': '2026-10-22',
    '上周五': '2026-10-16',
    '本星期一': '2026-10-19',
    '周一': '2026-10-19',
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
      expect(
        (mismatch['transactions'] as List).single['occurredAt'],
        DateTime.parse(item.value).millisecondsSinceEpoch,
      );
    });
  }
  test('unsupported model history is replaced by the normal today default', () {
    final r = validate([
      {...proposal(), 'occurred_at': '2025-10-23T10:00:00'},
    ]);
    expect(
      (r['transactions'] as List).single['occurredAt'],
      now.millisecondsSinceEpoch,
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
  test('past date in a separate clause remains recordable', () {
    final r = validate([proposal()], input: '前几天，午饭12元');
    expect(r['transactions'], hasLength(1));
    expect(r['autoSaveEligible'], true);
    expect(
      jsonDecode(
        (r['transactions'] as List).single['metadataJson'],
      )['assumptions'],
      contains(contains('暂按今天')),
    );
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
    expect(r['autoSaveEligible'], true);
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
    expect(r['autoSaveEligible'], true);
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
    test(
      'stated estimate is recordable while negation and conflicting amounts are blocked: $text',
      () {
        final r = validate([proposal()], input: text, source: 'AI_VOICE');
        final approximate = RegExp(r'大概|左右|不确定').hasMatch(text);
        expect(r['autoSaveEligible'], approximate);
        if (approximate) {
          expect(r['transactions'], hasLength(1));
          expect(
            jsonDecode(
              (r['transactions'] as List).single['metadataJson'],
            )['assumptions'],
            contains(contains('估算')),
          );
        } else {
          expect(r['questions'], isNotEmpty);
          expect(r['transactions'], isEmpty);
        }
      },
    );
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
  test(
    'model semantic classification does not need a local direction keyword',
    () {
      final row = proposal(amount: 5000, quote: '50');
      row['fieldEvidence'] = {
        'amount': {'quote': '50', 'source': 'user'},
        'type': {'quote': '记一下', 'source': 'user'},
      };
      final r = validate([row], input: '记一下50元');
      expect((r['transactions'] as List).length, 1);
      expect(r['autoSaveEligible'], true);
    },
  );
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
    // CSV callers still require a full timestamp by default.
    expect(RecognitionProtocol.strictDate('2026-10-24'), isNull);
    expect(
      RecognitionProtocol.strictDate('2026-10-24', allowDateOnly: true),
      DateTime(2026, 10, 24),
    );
  });
  test(
    'screenshot speech resolves yesterday evening without payment or year questions',
    () {
      final r = validate(
        [
          {
            ...proposal(amount: 1700, quote: '17 块钱', status: 'unknown'),
            'occurred_at': '2026-11-01',
            'fieldEvidence': {
              'amount': {'quote': '17 块钱', 'source': 'user'},
              'type': {'quote': '吃的盖饭', 'source': 'inference'},
            },
            'missingFields': ['年份', 'paymentStatus'],
            'questions': ['请补充具体年月日', '交易是否已完成？'],
          },
        ],
        input: '昨天晚上 7 点过吃的盖饭，17 块钱。',
        at: DateTime(2026, 11, 1, 13, 25),
      );
      final row = (r['transactions'] as List).single;
      expect(row['amount'], 1700);
      expect(
        row['occurredAt'],
        DateTime(2026, 10, 31, 19).millisecondsSinceEpoch,
      );
      expect(r['questions'], isEmpty);
      expect(
        jsonDecode(row['metadataJson'])['assumptions'],
        contains(contains('19:00')),
      );
      expect(r['autoSaveEligible'], true);
    },
  );
  test(
    'month and day default to the most recent year including across new year',
    () {
      final r = validate(
        [proposal()],
        input: '12 月 31 号午饭12元',
        at: DateTime(2026, 1, 2, 12),
      );
      final row = (r['transactions'] as List).single;
      expect(row['occurredAt'], DateTime(2025, 12, 31).millisecondsSinceEpoch);
      expect(r['questions'], isEmpty);
    },
  );
  test('latest date supplement overrides original date and accepts spaces', () {
    final r = validate([
      {...proposal(), 'occurred_at': '2026-10-20'},
    ], input: '昨天午饭12元\n补充：不是昨天，是 10 月 20 号\n补充：支付宝付的');
    expect(
      (r['transactions'] as List).single['occurredAt'],
      DateTime(2026, 10, 20).millisecondsSinceEpoch,
    );
    expect(r['questions'], isEmpty);
  });
  test('latest clock supplement overrides the original clock', () {
    final r = validate([proposal()], input: '昨天晚上7点午饭12元\n补充：改为晚上8点');
    expect(
      (r['transactions'] as List).single['occurredAt'],
      DateTime(2026, 10, 23, 20).millisecondsSinceEpoch,
    );
  });
  test(
    'amount correction grounds the new amount without rejecting ordinary correction',
    () {
      final r = validate([
        proposal(amount: 1800, quote: '18元'),
      ], input: '昨天午饭17元\n补充：不是17元，其实18元');
      expect((r['transactions'] as List).single['amount'], 1800);
      expect(r['questions'], isEmpty);
      final old = validate([
        proposal(amount: 1700, quote: '17元'),
      ], input: '昨天午饭17元\n补充：不是17元，其实18元');
      expect(old['transactions'], isEmpty);
    },
  );
  test(
    'same amounts remain attached to distinct descriptions and inherited dates',
    () {
      Map<String, dynamic> meal(String description) => {
        ...proposal(amount: 1700, quote: '17元'),
        'fieldEvidence': {
          'amount': {'quote': '17元', 'source': 'user'},
          'type': {'quote': description, 'source': 'user'},
        },
      };
      final r = validate([
        meal('午餐'),
        meal('晚餐'),
        meal('买水'),
      ], input: '昨天午餐17元，今天晚餐17元，买水17元');
      final rows = r['transactions'] as List;
      expect(rows.map((r) => r['occurredAt']), [
        DateTime(2026, 10, 23).millisecondsSinceEpoch,
        DateTime(2026, 10, 24).millisecondsSinceEpoch,
        DateTime(2026, 10, 24).millisecondsSinceEpoch,
      ]);
      expect(
        rows.map((r) => jsonDecode(r['metadataJson'])['operationId']).toSet(),
        hasLength(3),
      );
    },
  );
  test(
    'review citation range and proposal order do not change record identity',
    () {
      const input = '昨天吃盖饭花了17元，今天午饭12元';
      Map<String, dynamic> rice(String amountQuote, String directionQuote) => {
        ...proposal(amount: 1700, quote: amountQuote),
        'fieldEvidence': {
          'amount': {'quote': amountQuote, 'source': 'user'},
          'type': {'quote': directionQuote, 'source': 'user'},
        },
      };
      String id(Map<String, dynamic> r, int amount) => jsonDecode(
        (r['transactions'] as List).singleWhere(
          (r) => r['amount'] == amount,
        )['metadataJson'],
      )['operationId'];
      final first = validate([rice('17元', '吃盖饭'), proposal()], input: input);
      final reviewed = validate([
        proposal(),
        rice('花了17元', '盖饭'),
      ], input: input);
      expect(id(first, 1700), id(reviewed, 1700));
      expect(id(first, 1200), id(reviewed, 1200));
      final onlyOther = validate([proposal()], input: input);
      expect(id(first, 1200), id(onlyOther, 1200));
    },
  );
  test(
    'a failed or future clause does not block an already completed record',
    () {
      final r = validate([proposal()], input: '午饭12元，晚饭20元没支付，明天准备买书30元');
      expect(r['transactions'], hasLength(1));
      final mixed = validate([
        proposal(),
        proposal(amount: 2000, quote: '20'),
      ], input: '午饭12元，晚饭20元没支付');
      expect(mixed['transactions'], hasLength(1));
      expect((mixed['transactions'] as List).single['amount'], 1200);
      expect(mixed['questions'], isNotEmpty);
    },
  );
  test(
    'important accounting questions remain while optional form fields do not block',
    () {
      final r = validate([
        {
          ...proposal(),
          'questions': ['请补充商户名称', '金额是12元还是20元？'],
        },
      ]);
      expect(r['transactions'], isEmpty);
      expect(r['questions'], contains('金额是12元还是20元？'));
      expect(r['questions'], isNot(contains('请补充商户名称')));
    },
  );
  test(
    'a model purpose question cannot block an already declared transfer',
    () {
      final r = validate([
        {
          ...proposal(kind: 'unknown'),
          'fieldEvidence': {
            'amount': {'quote': '12', 'source': 'user'},
            'type': {'quote': '转给小林', 'source': 'user'},
          },
          'missingFields': ['business_kind'],
          'questions': ['这笔转账是什么用途？账户互转还是消费？'],
        },
      ], input: '转给小林12元');
      final row = (r['transactions'] as List).single;
      expect(row['businessKind'], 'transfer');
      expect(r['questions'], isEmpty);
      expect(r['autoSaveEligible'], true);
    },
  );
  test('Chinese spoken amount also works across a comma', () {
    final r = validate([
      proposal(amount: 1700, quote: '十七块钱'),
    ], input: '昨天午饭，十七块钱');
    expect(
      (r['transactions'] as List).single['occurredAt'],
      DateTime(2026, 10, 23).millisecondsSinceEpoch,
    );
    expect(r['questions'], isEmpty);
  });
  test(
    'image record identities remain stable when review returns only another amount',
    () {
      final first = validate(
        [
          {...proposal(status: 'completed'), 'occurred_at': '2026-10-23'},
          {
            ...proposal(amount: 500, quote: '5', status: 'completed'),
            'occurred_at': '2026-10-23',
          },
        ],
        source: 'AI_IMAGE',
        input: '',
      );
      final reviewed = validate(
        [
          {
            ...proposal(amount: 500, quote: '5元', status: 'completed'),
            'occurred_at': '2026-10-23',
          },
        ],
        source: 'AI_IMAGE',
        input: '',
      );
      final original = (first['transactions'] as List).singleWhere(
        (r) => r['amount'] == 500,
      );
      expect(
        jsonDecode(original['metadataJson'])['operationId'],
        jsonDecode(
          (reviewed['transactions'] as List).single['metadataJson'],
        )['operationId'],
      );
    },
  );
  test('image questions about missing evidence are retained', () {
    final r = RecognitionProtocol.validate(
      {
        'intent': 'record',
        'questions': ['请补充具体日期'],
        'proposals': [
          {...proposal(status: 'completed'), 'occurred_at': '2026-10-23'},
        ],
      },
      snapshot: snapshot,
      input: '',
      source: 'AI_IMAGE',
      now: now,
      operationId: 'request',
    );
    expect(r['questions'], contains('请补充具体日期'));
  });
  test(
    'switching between voice and text supplement keeps active record identity',
    () {
      final voice = validate([proposal()], source: 'AI_VOICE');
      final text = validate([proposal()], source: 'AI_TEXT');
      expect(
        jsonDecode(
          (voice['transactions'] as List).single['metadataJson'],
        )['operationId'],
        jsonDecode(
          (text['transactions'] as List).single['metadataJson'],
        )['operationId'],
      );
    },
  );
  test(
    'unclassified transfer is automatically recorded without becoming consumption',
    () {
      final r = validate([
        {
          ...proposal(amount: 5000, kind: 'unknown', quote: '50元'),
          'questions': ['这笔转账是账户互转还是消费？'],
          'fieldEvidence': {
            'amount': {'quote': '50元', 'source': 'user'},
            'type': {'quote': '转账', 'source': 'inference'},
          },
        },
      ], input: '昨天转账50元');
      final row = (r['transactions'] as List).single;
      expect(row['businessKind'], 'transfer');
      expect(r['autoSaveEligible'], true);
      expect(r['questions'], isEmpty);
      expect(
        LedgerTransaction.fromMap(stringMap(row), draft: true).countsAsExpense,
        false,
      );
    },
  );
  test(
    'image without a numerically verifiable amount cannot automatically save',
    () {
      final r = validate([
        {
          ...proposal(amount: 5000, quote: '金额看不清', status: 'completed'),
          'occurred_at': '2026-10-23',
        },
      ], source: 'AI_IMAGE');
      expect(r['autoSaveEligible'], false);
    },
  );
  test(
    'completed refund and explicitly allocated advances can automatically record',
    () {
      final refund = validate([
        {
          ...proposal(
            amount: 5000,
            type: 'INCOME',
            kind: 'refund',
            quote: '50元',
          ),
          'fieldEvidence': {
            'amount': {'quote': '50元', 'source': 'user'},
            'type': {'quote': '退款到账', 'source': 'user'},
          },
        },
      ], input: '退款到账50元');
      expect(refund['autoSaveEligible'], true);
      expect((refund['transactions'] as List).single['businessKind'], 'refund');
      Map<String, dynamic> split(int amount, String kind) => {
        ...proposal(amount: amount, kind: kind, quote: '240元'),
        'fieldEvidence': {
          'amount': {'quote': '240元', 'source': 'derived'},
          'type': {'quote': '午饭', 'source': 'user'},
        },
        'allocation': {
          'groupId': 'meal',
          'totalAmount': 24000,
          'totalQuote': '240元',
          'parts': [8000, 16000],
        },
      };
      final aa = validate([
        split(8000, 'expense'),
        split(16000, 'receivable'),
      ], input: '午饭我垫付240元，三个人AA，我自己的80元，其余160元应收');
      expect(aa['transactions'], hasLength(2));
      expect(aa['autoSaveEligible'], true);
    },
  );
}

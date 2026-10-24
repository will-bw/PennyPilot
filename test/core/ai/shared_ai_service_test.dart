import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pennypilot/core/ai/shared_ai_service.dart';
import 'package:pennypilot/core/models.dart';

class MemoryCredentials implements SharedCredentials {
  final values = <String, String>{
    'deepSeekKey': 'test-key',
    'qwenKey': 'qwen-key',
  };
  @override
  Future<String?> get(String key) async => values[key];
  @override
  Future<void> set(String key, String value) async {
    values[key] = value;
  }

  @override
  Future<void> delete(String key) async {
    values.remove(key);
  }
}

http.Response completion(Object value, {String finishReason = 'stop'}) =>
    http.Response(
      jsonEncode({
        'choices': [
          {
            'finish_reason': finishReason,
            'message': {'content': value is String ? value : jsonEncode(value)},
          },
        ],
      }),
      200,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );

void main() {
  final now = DateTime(2026, 10, 23, 10);
  LedgerSnapshot snapshot({
    String provider = 'deepseek',
    String? base,
    List<LedgerTransaction> transactions = const [],
    List<LedgerCategory>? categories,
  }) => LedgerSnapshot(
    revision: 1,
    categories:
        categories ??
        const [
          LedgerCategory(id: 1, name: '餐饮', type: TxType.expense),
          LedgerCategory(id: 2, name: '其他', type: TxType.income),
        ],
    settings: {
      'activeProvider': provider,
      'deepSeekModel': 'deepseek-chat',
      'qwenModel': 'qwen-plus',
      'qwenBaseUrl': ?base,
    },
    transactions: transactions,
  );
  Map<String, dynamic> draft({
    Object amount = 1250,
    String type = 'EXPENSE',
    String category = '餐饮',
    String? date,
  }) => {
    'transactions': [
      {
        'amount': amount,
        'type': type,
        'category': category,
        'occurred_at': ?date,
      },
    ],
  };
  SharedAiService service(
    MockClient client, {
    MemoryCredentials? credentials,
  }) => SharedAiService(
    credentials: credentials ?? MemoryCredentials(),
    client: client,
    now: () => now,
  );

  test(
    'DeepSeek request and integer-fen parse are shared and use HTTPS',
    () async {
      final ai = service(
        MockClient((request) async {
          expect(
            request.url.toString(),
            'https://api.deepseek.com/chat/completions',
          );
          expect(request.followRedirects, isFalse);
          expect(request.headers['Authorization'], 'Bearer test-key');
          expect(jsonDecode(request.body)['response_format'], {
            'type': 'json_object',
          });
          return completion(draft());
        }),
      );
      final result = await ai.parseText('午饭12.5元', snapshot());
      expect(result['error'], isNull);
      expect(result['transactions'].single['amount'], 1250);
      expect(
        result['transactions'].single['occurredAt'],
        now.millisecondsSinceEpoch,
      );
    },
  );

  test('Qwen honors configured HTTPS endpoint and model', () async {
    final ai = service(
      MockClient((request) async {
        expect(
          request.url.toString(),
          'https://example.test/v1/chat/completions',
        );
        expect(request.headers['Authorization'], 'Bearer qwen-key');
        expect(jsonDecode(request.body)['model'], 'qwen-plus');
        return completion(draft());
      }),
    );
    expect(
      (await ai.parseText(
        '午饭',
        snapshot(provider: 'qwen', base: 'https://example.test/v1/'),
      ))['error'],
      isNull,
    );
  });

  for (final base in [
    'http://example.test',
    'https://key@example.test',
    'https://example.test?key=secret',
    'https://example.test/#secret',
  ]) {
    test('rejects unsafe endpoint $base before sending key', () async {
      var called = false;
      final ai = service(
        MockClient((_) async {
          called = true;
          return completion(draft());
        }),
      );
      expect(
        (await ai.parseText(
          '午饭',
          snapshot(provider: 'qwen', base: base),
        ))['error'],
        isNotNull,
      );
      expect(called, isFalse);
    });
  }

  for (final amount in <Object>[0, -1, 1000000000, 1.5, '1250']) {
    test('rejects malformed amount $amount', () async {
      final ai = service(
        MockClient((_) async => completion(draft(amount: amount))),
      );
      final result = await ai.parseText('午饭', snapshot());
      expect(result['transactions'], isEmpty);
      expect(result['error'], isNotNull);
    });
  }
  test(
    'invalid date and invalid type fail instead of fabricating a draft',
    () async {
      for (final value in [
        draft(date: '2026-02-30T10:00'),
        draft(date: '2026-10-23T24:01'),
        draft(date: '2026-10-23T10:00+99:99'),
        draft(type: 'REFUND'),
      ]) {
        final ai = service(MockClient((_) async => completion(value)));
        expect((await ai.parseText('午饭', snapshot()))['error'], isNotNull);
      }
    },
  );
  test('no category of matching type is not silently converted', () async {
    final ai = service(
      MockClient((_) async => completion(draft(type: 'INCOME'))),
    );
    expect(
      (await ai.parseText(
        '收入',
        snapshot(
          categories: const [
            LedgerCategory(id: 1, name: '餐饮', type: TxType.expense),
          ],
        ),
      ))['error'],
      isNotNull,
    );
  });
  test(
    'provider error response never exposes reflected secret or raw text',
    () async {
      final ai = service(
        MockClient(
          (_) async => http.Response('test-key private ledger text', 401),
        ),
      );
      final error = (await ai.parseText('午饭', snapshot()))['error'] as String;
      expect(error, contains('凭据'));
      expect(error, isNot(contains('test-key')));
      expect(error, isNot(contains('private')));
    },
  );
  test('length finish reason does not become a completed parse', () async {
    final ai = service(
      MockClient((_) async => completion(draft(), finishReason: 'length')),
    );
    expect((await ai.parseText('午饭', snapshot()))['error'], isNotNull);
  });
  test('month report preserves local totals when AI is offline', () async {
    final ai = service(
      MockClient(
        (_) async => throw http.ClientException('secret request info'),
      ),
    );
    final report = await ai.generateReport(
      '2026-09',
      snapshot(
        transactions: [
          LedgerTransaction(
            amountFen: 1234,
            type: TxType.expense,
            categoryId: 1,
            occurredAt: DateTime(2026, 9, 30, 23, 59),
          ),
          LedgerTransaction(
            amountFen: 9000,
            type: TxType.income,
            categoryId: 2,
            occurredAt: DateTime(2026, 9, 1),
          ),
          LedgerTransaction(
            amountFen: 9000,
            type: TxType.expense,
            categoryId: 1,
            occurredAt: DateTime(2026, 10, 1),
          ),
        ],
      ),
    );
    expect(report['totalExpense'], 1234);
    expect(report['totalIncome'], 9000);
    expect(report['llmSummary'], contains('12.34'));
    expect(report['llmSummary'], isNot(contains('secret')));
  });

  test(
    'report stores every category while AI summary gets only top three',
    () async {
      final ai = service(
        MockClient((request) async {
          final payload = jsonDecode(
            jsonDecode(request.body)['messages'].last['content'],
          );
          expect(payload['byCategory'], hasLength(3));
          return completion('月报摘要');
        }),
      );
      final categories = [
        for (var id = 1; id <= 4; id++)
          LedgerCategory(id: id, name: '分类$id', type: TxType.expense),
      ];
      final transactions = [
        for (var id = 1; id <= 4; id++)
          LedgerTransaction(
            amountFen: id * 100,
            type: TxType.expense,
            categoryId: id,
            occurredAt: DateTime(2026, 9, 2),
          ),
      ];
      final report = await ai.generateReport(
        '2026-09',
        snapshot(categories: categories, transactions: transactions),
      );
      expect(jsonDecode(report['byCategoryJson'] as String), hasLength(4));
      expect(report['totalExpense'], 1000);
    },
  );
}

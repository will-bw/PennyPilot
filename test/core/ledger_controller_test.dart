import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/core/ledger_repository.dart';
import 'package:pennypilot/core/models.dart';
import 'package:pennypilot/core/recognition/recognition_protocol.dart';

class FakeRepository implements LedgerRepository {
  LedgerSnapshot value = LedgerSnapshot(revision: 0, categories: categories);
  final updates = StreamController<LedgerEvent>.broadcast(sync: true);
  final calls = <({String method, Map<String, dynamic> args})>[];
  Future<Object?> Function(String, Map<String, dynamic>)? handler;
  Future<LedgerSnapshot> Function()? snapshotHandler;
  @override
  Stream<LedgerEvent> get events => updates.stream;
  @override
  Future<LedgerSnapshot> snapshot() =>
      snapshotHandler?.call() ?? Future.value(value);
  @override
  Future<Object?> invoke(String method, Map<String, dynamic> arguments) async {
    calls.add((method: method, args: arguments));
    return handler?.call(method, arguments);
  }
}

const categories = [
  LedgerCategory(id: 1, name: '餐饮', type: TxType.expense),
  LedgerCategory(id: 2, name: '工资', type: TxType.income),
  LedgerCategory(id: 3, name: '交通', type: TxType.expense),
];
LedgerTransaction transaction({
  int id = 1,
  int amount = 1200,
  TxType type = TxType.expense,
  TxStatus status = TxStatus.confirmed,
  int? categoryId,
  DateTime? date,
  String? note,
  String? merchant,
}) => LedgerTransaction(
  id: id,
  amountFen: amount,
  type: type,
  categoryId: categoryId ?? (type == TxType.expense ? 1 : 2),
  occurredAt: date ?? DateTime(2026, 10, 23),
  status: status,
  note: note,
  merchant: merchant,
);

Map<String, dynamic> autoRow(
  String operationId,
  int amount, {
  bool eligible = true,
}) => transaction(id: 0, amount: amount)
    .copyWith(
      source: TxSource.aiText,
      metadataJson: jsonEncode({
        'operationId': operationId,
        'autoSaveEligible': eligible,
      }),
    )
    .toMap();
Map<String, dynamic> persistRows(
  FakeRepository repository,
  Map<String, dynamic> args,
) {
  final rows = args['transactions'] as List;
  final first = repository.value.transactions.length + 1;
  final saved = [
    for (var i = 0; i < rows.length; i++)
      LedgerTransaction.fromMap({...stringMap(rows[i]), 'id': first + i}),
  ];
  repository.value = LedgerSnapshot(
    revision: repository.value.revision,
    categories: categories,
    settings: repository.value.settings,
    transactions: [...repository.value.transactions, ...saved],
  );
  return {'ids': saved.map((t) => t.id).toList()};
}

void main() {
  late FakeRepository repository;
  late LedgerController controller;
  setUp(() {
    repository = FakeRepository();
    controller = LedgerController(
      repository: repository,
      clock: () => DateTime(2026, 10, 23),
    );
  });
  tearDown(() async {
    controller.dispose();
    await repository.updates.close();
  });

  test(
    'summary and budget count only confirmed transactions in the selected month',
    () async {
      repository.value = LedgerSnapshot(
        revision: 0,
        categories: categories,
        settings: {'budgetCents': 10000},
        transactions: [
          transaction(amount: 1250),
          transaction(id: 2, amount: 30000, type: TxType.income),
          transaction(id: 3, amount: 9900, status: TxStatus.pending),
          transaction(id: 4, amount: 5000, status: TxStatus.ignored),
          transaction(id: 5, amount: 2000, date: DateTime(2026, 9, 30)),
        ],
      );
      await controller.init();
      expect(controller.monthSummary.expenseFen, 1250);
      expect(controller.monthSummary.incomeFen, 30000);
      expect(controller.monthSummary.balanceFen, 28750);
      expect(controller.monthSummary.count, 2);
      expect(controller.budgetRemainingFen, 8750);
      expect(controller.budgetProgress, .125);
      expect(controller.previousMonthSummary.expenseFen, 2000);
      expect(controller.categorySpending.single.amountFen, 1250);
      expect(controller.dailySpending, {23: 1250});
      expect(controller.pendingTransactions.single.id, 3);
    },
  );

  test(
    'chat automatic additions share a receipt and undo only their own rows',
    () async {
      repository.value = LedgerSnapshot(
        revision: 0,
        categories: categories,
        transactions: [transaction(id: 1)],
      );
      repository.handler = (method, args) async {
        if (method == 'ask') {
          repository.value = LedgerSnapshot(
            revision: 0,
            categories: categories,
            transactions: [
              transaction(id: 1),
              transaction(id: 42).copyWith(
                source: TxSource.aiText,
                businessKind: 'expense',
                metadataJson: jsonEncode({'operationId': 'agent:auto-42'}),
              ),
            ],
          );
          return {
            'answer': '已记下午饭。',
            'isError': false,
            'autoSavedIds': [42],
          };
        }
        if (method == 'undoRecognitionSave') {
          expect(args['transactions'], [
            {'id': 42, 'editVersion': 0, 'operationId': 'agent:auto-42'},
          ]);
          repository.value = LedgerSnapshot(
            revision: 0,
            categories: categories,
            transactions: [transaction(id: 1)],
          );
          return {'count': 1};
        }
        return null;
      };
      await controller.init();
      await controller.ask('午饭12元');
      expect(controller.transactions, hasLength(2));
      expect(controller.lastRecognitionSave!.ids, [42]);
      expect(controller.canUndoRecognitionSave, true);
      expect(await controller.undoLastRecognitionSave(), 1);
      expect(controller.transactions.single.id, 1);
    },
  );

  test('keyword, type, category, status and month filters compose', () async {
    repository.value = LedgerSnapshot(
      revision: 0,
      categories: categories,
      transactions: [
        transaction(id: 1, merchant: 'Cafe', note: '午餐'),
        transaction(id: 2, status: TxStatus.pending, merchant: '咖啡'),
        transaction(id: 3, status: TxStatus.ignored, note: '午餐'),
        transaction(id: 4, type: TxType.income),
        transaction(id: 5, date: DateTime(2026, 9, 30)),
      ],
    );
    await controller.init();
    expect(controller.filteredTransactions.map((item) => item.id), [4, 2, 1]);
    controller.setKeyword('CAFE');
    expect(controller.filteredTransactions.single.id, 1);
    controller.setKeyword('餐饮');
    controller.setStatusFilter(TxStatus.pending);
    expect(controller.filteredTransactions.single.id, 2);
    controller.clearFilters();
    controller.setStatusFilter(TxStatus.ignored);
    expect(controller.filteredTransactions.single.id, 3);
    controller.clearFilters();
    controller.setTypeFilter(TxType.income);
    expect(controller.filteredTransactions.single.id, 4);
    controller.clearFilters();
    controller.setCategoryFilter(1);
    expect(controller.filteredTransactions.length, 2);
    controller.setMonth(DateTime(2026, 9));
    expect(controller.filteredTransactions.single.id, 5);
  });

  test(
    'new transactions send positive integer fen and current revision',
    () async {
      repository.value = LedgerSnapshot(revision: 7, categories: categories);
      repository.handler = (_, _) async => {'id': 9};
      await controller.init();
      expect(
        await controller.saveTransaction(
          transaction(id: 0, type: TxType.income, amount: 105),
        ),
        9,
      );
      expect(repository.calls.single.args['amount'], 105);
      expect(repository.calls.single.args['revision'], 7);
      expect(repository.calls.single.args['type'], 'INCOME');
    },
  );

  test(
    'invalid amount and mismatched categories never reach persistence',
    () async {
      await controller.init();
      await expectLater(
        controller.saveTransaction(transaction(amount: 0)),
        throwsA(isA<LedgerException>()),
      );
      await expectLater(
        controller.saveTransaction(transaction(amount: maxTransactionFen + 1)),
        throwsA(isA<LedgerException>()),
      );
      await expectLater(
        controller.saveTransaction(transaction(categoryId: 2)),
        throwsA(isA<LedgerException>()),
      );
      expect(repository.calls, isEmpty);
    },
  );

  test(
    'old edit form revision is rejected after restore without writing',
    () async {
      repository.value = LedgerSnapshot(revision: 3, categories: categories);
      await controller.init();
      await expectLater(
        controller.saveTransaction(transaction(), expectedRevision: 2),
        throwsA(
          isA<LedgerException>().having(
            (error) => error.code,
            'code',
            'STALE_LEDGER',
          ),
        ),
      );
      expect(repository.calls, isEmpty);
    },
  );

  test(
    'reviewed drafts save atomically and clear only after success',
    () async {
      await controller.init();
      repository.handler = (method, args) async {
        if (method == 'parseText') {
          return {
            'transactions': [
              transaction(id: 0).toMap(),
              transaction(id: 0, amount: 250).toMap(),
            ],
          };
        }
        if (method == 'saveTransactions') throw const LedgerException('磁盘空间不足');
        return null;
      };
      await controller.parseText('午餐 12 元，饮料 2.5 元');
      expect(controller.drafts.length, 2);
      await expectLater(
        controller.saveDrafts(),
        throwsA(isA<LedgerException>()),
      );
      expect(controller.drafts.length, 2);
      repository.handler = (_, _) async => {
        'ids': [1, 2],
      };
      expect(await controller.saveDrafts(), 2);
      expect(controller.drafts, isEmpty);
      expect(
        repository.calls
            .where((call) => call.method == 'saveTransactions')
            .length,
        2,
      );
      expect(
        repository.calls.where((call) => call.method == 'saveTransaction'),
        isEmpty,
      );
    },
  );
  test(
    'follow-up recognition keeps saved and manually corrected session records',
    () async {
      Map<String, dynamic> row(int index, int amount) =>
          transaction(id: 0, amount: amount)
              .copyWith(
                source: TxSource.aiText,
                metadataJson: jsonEncode({'operationId': 'session:$index'}),
              )
              .toMap();
      repository.handler = (method, args) async {
        if (method == 'recognizeInput') {
          return {
            'transactions': [row(0, 1200), row(1, 250)],
            'questions': ['另一笔是多少？'],
            'autoSaveEligible': false,
          };
        }
        if (method == 'saveTransactions') {
          repository.value = LedgerSnapshot(
            revision: 0,
            categories: categories,
            transactions: [
              for (final saved in args['transactions'] as List)
                LedgerTransaction.fromMap({...saved, 'id': 99}),
            ],
          );
          return {
            'ids': [99],
          };
        }
        return null;
      };
      await controller.init();
      await controller.recognizeInput(text: '午饭12，饮料2.5', sessionId: 'session');
      controller.updateDraft(1, controller.drafts[1].copyWith(amountFen: 300));
      await controller.saveDrafts(items: [controller.drafts.first]);
      await controller.recognizeInput(
        text: '午饭12，饮料2.5。补充：另一笔3元',
        sessionId: 'session',
      );
      expect(controller.transactions, hasLength(1));
      expect(controller.drafts, hasLength(1));
      expect(controller.drafts.single.amountFen, 300);
      expect(controller.drafts.single.recognitionMetadata['reviewed'], true);
    },
  );

  test('unresolved questions do not block another eligible record', () async {
    repository.handler = (method, args) async {
      if (method == 'recognizeInput') {
        return {
          'transactions': [autoRow('meal', 1200)],
          'questions': ['另一笔金额是多少？'],
          'autoSaveEligible': true,
        };
      }
      if (method == 'saveTransactions') return persistRows(repository, args);
      return null;
    };
    await controller.init();
    await controller.recognizeInput(text: '午饭12元，咖啡忘了多少钱');
    expect(controller.drafts, isEmpty);
    expect(controller.transactions.single.amountFen, 1200);
    expect(controller.recognitionQuestions, ['另一笔金额是多少？']);
  });

  test(
    '101 imported records save in bounded batches without dropping the tail',
    () async {
      await controller.init();
      controller.addImportedDrafts(
        List.generate(
          101,
          (i) => transaction(id: 0).copyWith(
            source: TxSource.billImport,
            metadataJson: jsonEncode({'operationId': 'import:$i'}),
          ),
        ),
      );
      repository.handler = (method, args) async {
        expect((args['transactions'] as List).length, lessThanOrEqualTo(100));
        return {'ids': []};
      };
      expect(await controller.saveDrafts(), 101);
      expect(
        repository.calls
            .where((c) => c.method == 'saveTransactions')
            .map((c) => (c.args['transactions'] as List).length),
        [100, 1],
      );
      expect(controller.drafts, isEmpty);
    },
  );
  test(
    'failed second import batch preserves only the unsaved records for retry',
    () async {
      await controller.init();
      controller.addImportedDrafts(
        List.generate(
          101,
          (i) => transaction(id: 0).copyWith(
            source: TxSource.billImport,
            metadataJson: jsonEncode({'operationId': 'import:$i'}),
          ),
        ),
      );
      var writes = 0;
      repository.handler = (method, args) async {
        writes++;
        if (writes == 2) throw const LedgerException('磁盘空间不足');
        return {'ids': []};
      };
      await expectLater(
        controller.saveDrafts(),
        throwsA(
          isA<LedgerException>().having(
            (e) => e.message,
            'message',
            contains('已保存 100 笔，剩余 1 笔'),
          ),
        ),
      );
      expect(controller.drafts, hasLength(1));
      expect(
        controller.drafts.single.recognitionMetadata['operationId'],
        'import:100',
      );
      expect(await controller.saveDrafts(), 1);
      expect(controller.drafts, isEmpty);
      expect(
        repository.calls
            .where((c) => c.method == 'saveTransactions')
            .map((c) => (c.args['transactions'] as List).length),
        [100, 1, 1],
      );
    },
  );
  test(
    'unknown AI draft requires a purpose before writing while imported facts may remain pending',
    () async {
      await controller.init();
      final row = transaction(id: 0).copyWith(
        businessKind: 'unknown',
        source: TxSource.aiText,
        metadataJson: jsonEncode({'operationId': 'unknown'}),
      );
      controller.addImportedDrafts([row]);
      await expectLater(
        controller.saveDrafts(),
        throwsA(isA<LedgerException>()),
      );
      expect(repository.calls, isEmpty);
      controller.updateDraft(0, row.copyWith(businessKind: 'transfer'));
      expect(await controller.saveDrafts(), 1);
    },
  );

  test(
    'parse completion from an old ledger cannot resurrect drafts after restore',
    () async {
      await controller.init();
      final delayed = Completer<Object?>();
      repository.handler = (_, _) => delayed.future;
      final pending = controller.parseText('午餐 12 元');
      final expected = expectLater(
        pending,
        throwsA(
          isA<LedgerException>().having((e) => e.code, 'code', 'STALE_LEDGER'),
        ),
      );
      repository.value = LedgerSnapshot(revision: 1, categories: categories);
      repository.updates.add(const LedgerEvent('restored', 1));
      await controller.refresh();
      delayed.complete({
        'transactions': [transaction().toMap()],
      });
      await expected;
      expect(controller.drafts, isEmpty);
      expect(controller.revision, 1);
    },
  );

  test('restoration clears drafts, chat and navigation', () async {
    await controller.init();
    repository.handler = (method, _) async => method == 'parseText'
        ? {
            'transactions': [transaction().toMap()],
          }
        : {'answer': '本月支出 12 元。', 'isError': false};
    await controller.parseText('午餐 12 元');
    await controller.ask('这个月花了多少');
    repository.updates.add(
      const LedgerEvent('launch', 0, {'transactionId': 1}),
    );
    final previousGeneration = controller.sessionGeneration;
    repository.value = LedgerSnapshot(revision: 1, categories: categories);
    repository.updates.add(const LedgerEvent('restored', 1));
    await controller.refresh();
    expect(controller.sessionGeneration, greaterThan(previousGeneration));
    expect(controller.drafts, isEmpty);
    expect(controller.messages, isEmpty);
    expect(controller.launchTarget, isNull);
  });

  test(
    'retry preserves the failed attempt and continues without a duplicate user turn',
    () async {
      await controller.init();
      repository.handler = (_, _) async => {'answer': '暂时不可用', 'isError': true};
      await controller.ask('花了多少');
      expect(controller.retryQuestion, '花了多少');
      repository.handler = (_, _) async => {
        'answer': '支出 12 元',
        'isError': false,
      };
      await controller.retryAsk();
      expect(repository.calls.last.args.containsKey('history'), isFalse);
      expect(controller.messages.length, 3);
      expect(controller.messages.where((m) => m.role == 'user'), hasLength(1));
      expect(controller.messages[1].isError, true);
      expect(repository.calls.last.args['retry'], true);
      expect(controller.messages.first.text, '花了多少');
      expect(controller.messages.last.isError, false);
      expect(controller.retryQuestion, isNull);
    },
  );

  test('duplicate in-flight operation is rejected synchronously', () async {
    await controller.init();
    final delayed = Completer<Object?>();
    repository.handler = (_, _) => delayed.future;
    final pending = controller.parseText('午餐 12 元');
    expect(controller.isParsing, true);
    await expectLater(
      controller.parseText('午餐 12 元'),
      throwsA(isA<LedgerException>().having((e) => e.code, 'code', 'BUSY')),
    );
    delayed.complete({
      'transactions': [transaction().toMap()],
    });
    await pending;
    expect(controller.isParsing, false);
    expect(repository.calls.length, 1);
  });

  test(
    'batch confirmation targets pending rows only and refreshes after failure',
    () async {
      repository.value = LedgerSnapshot(
        revision: 0,
        categories: categories,
        transactions: [
          transaction(id: 1),
          transaction(id: 2, status: TxStatus.pending),
          transaction(id: 3, status: TxStatus.pending),
        ],
      );
      await controller.init();
      repository.handler = (_, args) async {
        if (args['id'] == 2) throw const LedgerException('更新失败');
        return null;
      };
      await expectLater(
        controller.confirmPending([1, 2, 3]),
        throwsA(isA<LedgerException>()),
      );
      expect(repository.calls.every((call) => call.args['id'] != 1), true);
      expect(controller.isBusy, false);
      expect(controller.isLoading, false);
    },
  );

  test(
    'refresh failure preserves existing rows and exposes retryable error',
    () async {
      repository.value = LedgerSnapshot(
        revision: 0,
        categories: categories,
        transactions: [transaction()],
      );
      await controller.init();
      repository.snapshotHandler = () =>
          Future.error(const LedgerException('读取账本失败'));
      await controller.refresh();
      expect(controller.transactions.length, 1);
      expect(controller.error, '读取账本失败');
      expect(controller.isLoading, false);
      repository.snapshotHandler = null;
      await controller.refresh();
      expect(controller.error, isNull);
    },
  );

  test(
    'coalesced refresh repeats after a change while query is in-flight',
    () async {
      final first = Completer<LedgerSnapshot>();
      var reads = 0;
      repository.snapshotHandler = () {
        reads++;
        return reads == 1 ? first.future : Future.value(repository.value);
      };
      final initial = controller.init();
      repository.updates.add(const LedgerEvent('changed', 0));
      first.complete(LedgerSnapshot(revision: 0));
      await initial;
      expect(reads, 2);
      expect(controller.categories.length, 3);
    },
  );

  test(
    'report generation accepts completed month and rejects current month',
    () async {
      await controller.init();
      await expectLater(
        controller.generateReport('2026-10'),
        throwsA(isA<LedgerException>()),
      );
      await expectLater(
        controller.generateReport('2026-13'),
        throwsA(isA<LedgerException>()),
      );
      await controller.generateReport('2026-09');
      expect(repository.calls.single.method, 'generateReport');
      expect(controller.reportStates['2026-09'], 'ENQUEUED');
    },
  );

  test('cancelled backup restore leaves drafts intact', () async {
    await controller.init();
    repository.handler = (method, _) async => method == 'parseText'
        ? {
            'transactions': [transaction().toMap()],
          }
        : {'cancelled': true};
    await controller.parseText('午餐 12 元');
    expect(await controller.restoreBackup(), false);
    expect(controller.drafts.length, 1);
  });

  test(
    'report completion before command return is not replaced by queued state',
    () async {
      await controller.init();
      repository.handler = (method, _) async {
        if (method == 'generateReport') {
          repository.updates.add(
            const LedgerEvent('report', 0, {
              'yearMonth': '2026-09',
              'state': 'SUCCEEDED',
            }),
          );
        }
        return {'queued': true};
      };
      await controller.generateReport('2026-09');
      expect(controller.reportStates['2026-09'], 'SUCCEEDED');
      expect(controller.isBusy, false);
    },
  );

  test(
    'clearing drafts cancels a pending parse without restoring discarded input',
    () async {
      await controller.init();
      final delayed = Completer<Object?>();
      repository.handler = (_, _) => delayed.future;
      final pending = controller.parseText('午餐 12 元');
      final expected = expectLater(
        pending,
        throwsA(
          isA<LedgerException>().having((e) => e.code, 'code', 'CANCELLED'),
        ),
      );
      controller.clearDrafts();
      delayed.complete({
        'transactions': [transaction().toMap()],
      });
      await expected;
      expect(controller.drafts, isEmpty);
    },
  );

  test(
    'late stale parse does not erase a conversation begun after restoration',
    () async {
      await controller.init();
      final delayed = Completer<Object?>();
      repository.handler = (method, _) => method == 'parseText'
          ? delayed.future
          : Future.value({'answer': '已查询新账本', 'isError': false});
      final pending = controller.parseText('午餐 12 元');
      final expected = expectLater(pending, throwsA(isA<LedgerException>()));
      repository.value = LedgerSnapshot(revision: 1, categories: categories);
      repository.updates.add(const LedgerEvent('restored', 1));
      await controller.refresh();
      await controller.ask('新账本有多少笔');
      delayed.complete({
        'transactions': [transaction().toMap()],
      });
      await expected;
      expect(controller.messages.length, 2);
      expect(controller.messages.last.text, '已查询新账本');
    },
  );

  test(
    'new revision launch survives snapshot reload and stale events are ignored',
    () async {
      await controller.init();
      repository.value = LedgerSnapshot(revision: 1, categories: categories);
      repository.updates.add(
        const LedgerEvent('launch', 1, {'transactionId': 42}),
      );
      await controller.refresh();
      expect(controller.launchTarget?['transactionId'], 42);
      repository.updates.add(
        const LedgerEvent('launch', 0, {'transactionId': 1}),
      );
      expect(controller.launchTarget?['transactionId'], 42);
    },
  );

  test(
    'draft batch keeps the opening ledger revision at save boundary',
    () async {
      repository.value = LedgerSnapshot(revision: 3, categories: categories);
      await controller.init();
      await expectLater(
        controller.saveDrafts(items: [transaction()], expectedRevision: 2),
        throwsA(
          isA<LedgerException>().having((e) => e.code, 'code', 'STALE_LEDGER'),
        ),
      );
      expect(repository.calls, isEmpty);
    },
  );
  test(
    'default active input saves the screenshot phrase with inferred date and direction',
    () async {
      repository.handler = (method, args) async {
        if (method == 'recognizeInput') {
          return RecognitionProtocol.validate(
            {
              'intent': 'record',
              'proposals': [
                {
                  'amount': 1700,
                  'type': 'UNKNOWN',
                  'businessKind': 'expense',
                  'currency': 'CNY',
                  'paymentStatus': 'unknown',
                  'category': '餐饮',
                  'fieldEvidence': {
                    'amount': {'quote': '17 块钱', 'source': 'user'},
                    'type': {'quote': '吃的盖饭', 'source': 'inference'},
                  },
                },
              ],
            },
            snapshot: repository.value,
            input: args['text'] as String,
            source: args['source'] as String,
            now: DateTime(2026, 10, 23, 13, 25),
            operationId: args['operationId'] as String,
          );
        }
        if (method == 'saveTransactions') return persistRows(repository, args);
        return null;
      };
      await controller.init();
      await controller.recognizeInput(
        text: '昨天晚上 7 点过吃的盖饭，17 块钱。',
        sessionId: 'meal',
      );
      expect(controller.transactions, hasLength(1));
      expect(
        controller.transactions.single.occurredAt,
        DateTime(2026, 10, 22, 19),
      );
      expect(controller.drafts, isEmpty);
      expect(controller.recognitionQuestions, isEmpty);
      expect(controller.lastRecognitionSave!.count, 1);
      expect(controller.canUndoRecognitionSave, true);
      expect(controller.recognitionNotice, contains('已记 1 笔'));
    },
  );
  test('explicitly disabled active auto-save retains drafts', () async {
    repository.value = LedgerSnapshot(
      revision: 0,
      categories: categories,
      settings: {'activeInputAutoSaveEnabled': false},
    );
    repository.handler = (method, args) async {
      if (method == 'recognizeInput') {
        return {
          'transactions': [autoRow('meal', 1200)],
          'autoSaveEligible': true,
        };
      }
      fail('auto-save must respect the explicit setting');
    };
    await controller.init();
    await controller.recognizeInput(text: '午饭12元');
    expect(controller.drafts, hasLength(1));
    expect(controller.lastRecognitionSave, isNull);
  });
  test(
    'per-record eligibility saves clear records while another purpose remains unresolved',
    () async {
      repository.handler = (method, args) async {
        if (method == 'recognizeInput') {
          return {
            'transactions': [
              autoRow('meal', 1200),
              {
                ...autoRow('transfer', 500, eligible: false),
                'businessKind': 'unknown',
              },
            ],
            'questions': ['另一笔是转账还是消费？'],
            'autoSaveEligible': true,
          };
        }
        if (method == 'saveTransactions') return persistRows(repository, args);
        return null;
      };
      await controller.init();
      await controller.recognizeInput(text: '午饭12，另一笔5');
      expect(controller.transactions.single.amountFen, 1200);
      expect(controller.drafts.single.amountFen, 500);
      expect(controller.recognitionQuestions, ['另一笔是转账还是消费？']);
    },
  );
  test(
    'a missing amount does not prevent another real record from being saved',
    () async {
      repository.handler = (method, args) async {
        if (method == 'recognizeInput') {
          Map<String, dynamic> proposal(
            int? amount,
            String quote,
            String item,
          ) => {
            'amount': amount,
            'currency': 'CNY',
            'type': 'EXPENSE',
            'businessKind': 'expense',
            'paymentStatus': 'declared',
            'category': '餐饮',
            'fieldEvidence': {
              'amount': {'quote': quote, 'source': 'user'},
              'type': {'quote': item, 'source': 'user'},
            },
          };
          return RecognitionProtocol.validate(
            {
              'intent': 'record',
              'proposals': [
                proposal(1200, '12元', '午饭'),
                proposal(null, '', '咖啡'),
              ],
            },
            snapshot: repository.value,
            input: args['text'] as String,
            source: 'AI_TEXT',
            now: DateTime(2026, 10, 23),
            operationId: 'batch',
          );
        }
        if (method == 'saveTransactions') return persistRows(repository, args);
        return null;
      };
      await controller.init();
      await controller.recognizeInput(text: '午饭12元，咖啡忘了多少钱');
      expect(controller.transactions.single.amountFen, 1200);
      expect(controller.drafts, isEmpty);
      expect(controller.recognitionQuestions, isNotEmpty);
      expect(controller.lastRecognitionSave!.count, 1);
    },
  );
  test(
    'undo sends one guarded batch and preserves unrelated later additions',
    () async {
      repository.handler = (method, args) async {
        if (method == 'recognizeInput') {
          return {
            'transactions': [autoRow('meal', 1200), autoRow('coffee', 500)],
            'autoSaveEligible': true,
          };
        }
        if (method == 'saveTransactions') return persistRows(repository, args);
        if (method == 'undoRecognitionSave') {
          expect(args['revision'], 0);
          expect(args['transactions'], [
            {'id': 1, 'editVersion': 0, 'operationId': 'meal'},
            {'id': 2, 'editVersion': 0, 'operationId': 'coffee'},
          ]);
          repository.value = LedgerSnapshot(
            revision: 0,
            categories: categories,
            transactions: repository.value.transactions
                .where((t) => t.id == 99)
                .toList(),
          );
          return {'count': 2};
        }
        fail('unexpected method $method');
      };
      await controller.init();
      await controller.recognizeInput(text: '午饭12元，咖啡5元');
      repository.value = LedgerSnapshot(
        revision: 0,
        categories: categories,
        transactions: [...repository.value.transactions, transaction(id: 99)],
      );
      await controller.refresh();
      expect(await controller.undoLastRecognitionSave(), 2);
      expect(controller.transactions.single.id, 99);
      expect(controller.lastRecognitionSave, isNull);
      expect(
        repository.calls.where((c) => c.method == 'deleteTransaction'),
        isEmpty,
      );
    },
  );
  test('undo refusal preserves receipt and records for correction', () async {
    repository.handler = (method, args) async {
      if (method == 'recognizeInput') {
        return {
          'transactions': [autoRow('meal', 1200)],
          'autoSaveEligible': true,
        };
      }
      if (method == 'saveTransactions') return persistRows(repository, args);
      if (method == 'undoRecognitionSave') {
        throw const LedgerException('账目已更新，不能撤销这次自动记账。', code: 'CONFLICT');
      }
      return null;
    };
    await controller.init();
    await controller.recognizeInput(text: '午饭12元');
    await expectLater(
      controller.undoLastRecognitionSave(),
      throwsA(isA<LedgerException>()),
    );
    expect(controller.transactions, hasLength(1));
    expect(controller.lastRecognitionSave!.count, 1);
  });
  test(
    'a restored ledger invalidates the previous auto-save receipt',
    () async {
      repository.handler = (method, args) async {
        if (method == 'recognizeInput') {
          return {
            'transactions': [autoRow('meal', 1200)],
            'autoSaveEligible': true,
          };
        }
        if (method == 'saveTransactions') return persistRows(repository, args);
        return null;
      };
      await controller.init();
      await controller.recognizeInput(text: '午饭12元');
      expect(controller.canUndoRecognitionSave, true);
      repository.value = LedgerSnapshot(
        revision: 1,
        categories: categories,
        transactions: repository.value.transactions,
      );
      repository.updates.add(const LedgerEvent('restored', 1));
      await controller.refresh();
      expect(controller.lastRecognitionSave, isNull);
      await expectLater(
        controller.undoLastRecognitionSave(),
        throwsA(isA<LedgerException>()),
      );
      expect(
        repository.calls.where((c) => c.method == 'undoRecognitionSave'),
        isEmpty,
      );
    },
  );
  test(
    'partial batch failure retains an undo receipt only for newly saved records',
    () async {
      var writes = 0;
      repository.handler = (method, args) async {
        if (method == 'saveTransactions') {
          if (++writes == 2) throw const LedgerException('磁盘空间不足');
          return persistRows(repository, args);
        }
        return null;
      };
      await controller.init();
      controller.addImportedDrafts(
        List.generate(
          101,
          (i) =>
              LedgerTransaction.fromMap(autoRow('batch:$i', 1200), draft: true),
        ),
      );
      await expectLater(
        controller.saveDrafts(rememberRecognitionSave: true),
        throwsA(isA<LedgerException>()),
      );
      expect(controller.transactions, hasLength(100));
      expect(controller.lastRecognitionSave!.count, 100);
      expect(controller.drafts, hasLength(1));
    },
  );
  test(
    'existing idempotent saves are excluded from a new undo receipt',
    () async {
      repository.value = LedgerSnapshot(
        revision: 0,
        categories: categories,
        transactions: [
          LedgerTransaction.fromMap({...autoRow('meal', 1200), 'id': 99}),
        ],
      );
      repository.handler = (method, args) async {
        if (method == 'recognizeInput') {
          return {
            'transactions': [autoRow('meal', 1200)],
            'autoSaveEligible': true,
          };
        }
        if (method == 'saveTransactions') {
          return {
            'ids': [99],
          };
        }
        return null;
      };
      await controller.init();
      await controller.recognizeInput(text: '午饭12元');
      expect(controller.transactions.single.id, 99);
      expect(controller.lastRecognitionSave, isNull);
    },
  );
  test(
    'a follow-up after undo records only the newly completed item',
    () async {
      var recognitions = 0;
      repository.handler = (method, args) async {
        if (method == 'recognizeInput') {
          recognitions++;
          return {
            'transactions': [
              autoRow('session:meal', 1700),
              if (recognitions > 1) autoRow('session:metro', 300),
            ],
            'questions': recognitions == 1 ? ['地铁多少钱？'] : <String>[],
            'autoSaveEligible': true,
          };
        }
        if (method == 'saveTransactions') {
          if (recognitions > 1) {
            expect((args['transactions'] as List).single['amount'], 300);
          }
          return persistRows(repository, args);
        }
        if (method == 'undoRecognitionSave') {
          repository.value = LedgerSnapshot(
            revision: 0,
            categories: categories,
          );
          return {'count': 1};
        }
        return null;
      };
      await controller.init();
      await controller.recognizeInput(text: '午饭17，地铁忘了', sessionId: 'session');
      expect(await controller.undoLastRecognitionSave(), 1);
      await controller.recognizeInput(
        text: '午饭17，地铁忘了\n补充：地铁3元',
        sessionId: 'session',
      );
      expect(controller.transactions.single.amountFen, 300);
      expect(controller.lastRecognitionSave!.count, 1);
    },
  );
  test(
    'undo does not adopt an edit made between insertion and snapshot refresh',
    () async {
      repository.handler = (method, args) async {
        if (method == 'recognizeInput') {
          return {
            'transactions': [autoRow('meal', 1200)],
            'autoSaveEligible': true,
          };
        }
        if (method == 'saveTransactions') {
          final result = persistRows(repository, args);
          repository.value = LedgerSnapshot(
            revision: 0,
            categories: categories,
            transactions: [
              LedgerTransaction.fromMap({
                ...repository.value.transactions.single.toMap(),
                'amount': 1800,
                'editVersion': 1,
              }),
            ],
          );
          return result;
        }
        if (method == 'undoRecognitionSave') {
          expect((args['transactions'] as List).single['editVersion'], 0);
          throw const LedgerException('账目已更新', code: 'CONFLICT');
        }
        return null;
      };
      await controller.init();
      await controller.recognizeInput(text: '午饭12元');
      await expectLater(
        controller.undoLastRecognitionSave(),
        throwsA(isA<LedgerException>()),
      );
      expect(controller.transactions.single.amountFen, 1800);
    },
  );
  test(
    'editing an auto-saved record learns corrected category, purpose and account',
    () async {
      final original = LedgerTransaction.fromMap({
        ...autoRow('meal', 1200),
        'id': 1,
        'merchant': '便利店',
        'accountTag': '微信',
      });
      repository.value = LedgerSnapshot(
        revision: 0,
        categories: categories,
        transactions: [original],
      );
      repository.handler = (method, args) async {
        if (method == 'saveTransaction') {
          return {'id': 1};
        }
        if (method == 'updateSettings') return null;
        fail('unexpected method $method');
      };
      await controller.init();
      expect(
        await controller.saveTransaction(
          original.copyWith(
            categoryId: 3,
            businessKind: 'transfer',
            accountTag: '支付宝',
          ),
        ),
        1,
      );
      final preferenceWrite = repository.calls.singleWhere(
        (c) => c.method == 'updateSettings',
      );
      final preference =
          (jsonDecode(
                    preferenceWrite.args['recognitionPreferencesJson']
                        as String,
                  )
                  as List)
              .single;
      expect(preference, {
        'merchant': '便利店',
        'type': 'EXPENSE',
        'category': '交通',
        'businessKind': 'transfer',
        'accountTag': '支付宝',
      });
    },
  );
  test(
    'a purpose or account correction in a draft updates recognition preferences',
    () async {
      final row = LedgerTransaction.fromMap({
        ...autoRow('meal', 1200),
        'merchant': '朋友小王',
      });
      repository.handler = (method, args) async {
        if (method == 'parseText') {
          return {
            'transactions': [row.toMap()],
          };
        }
        return null;
      };
      await controller.init();
      await controller.parseText('给朋友小王12元');
      controller.updateDraft(
        0,
        controller.drafts.single.copyWith(
          businessKind: 'receivable',
          accountTag: '支付宝',
        ),
      );
      final preferenceWrite = repository.calls.singleWhere(
        (c) => c.method == 'updateSettings',
      );
      final preference =
          (jsonDecode(
                    preferenceWrite.args['recognitionPreferencesJson']
                        as String,
                  )
                  as List)
              .single;
      expect(preference['businessKind'], 'receivable');
      expect(preference['accountTag'], '支付宝');
    },
  );
  test(
    'a preference-write failure does not turn a successful user correction into a failed save',
    () async {
      final original = LedgerTransaction.fromMap({
        ...autoRow('meal', 1200),
        'id': 1,
        'merchant': '便利店',
      });
      repository.value = LedgerSnapshot(
        revision: 0,
        categories: categories,
        transactions: [original],
      );
      repository.handler = (method, args) async {
        if (method == 'saveTransaction') {
          repository.value = LedgerSnapshot(
            revision: 0,
            categories: categories,
            transactions: [original.copyWith(categoryId: 3)],
          );
          return {'id': 1};
        }
        if (method == 'updateSettings') throw const LedgerException('偏好存储失败');
        return null;
      };
      await controller.init();
      expect(
        await controller.saveTransaction(original.copyWith(categoryId: 3)),
        1,
      );
      expect(controller.transactions.single.categoryId, 3);
    },
  );
}

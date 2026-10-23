import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/core/ledger_repository.dart';
import 'package:pennypilot/core/models.dart';

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
    'failed ask retry excludes error message and duplicate question from history',
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
      expect(repository.calls.last.args['history'], isEmpty);
      expect(controller.messages.length, 2);
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
}

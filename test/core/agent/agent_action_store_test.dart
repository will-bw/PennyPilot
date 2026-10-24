import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:pennypilot/core/ledger_repository.dart';
import 'package:pennypilot/core/models.dart';
import 'package:pennypilot/core/storage/portable_ledger_store.dart';

void main() {
  sqfliteFfiInit();
  late PortableLedgerStore store;
  setUp(() {
    store = PortableLedgerStore(
      databaseFactory: databaseFactoryFfi,
      databasePath: inMemoryDatabasePath,
      clock: () => DateTime(2026, 10, 24),
    );
  });
  tearDown(() => store.close());
  test(
    'atomic budget compare prevents overwriting an intervening user edit',
    () async {
      await store.invoke('updateSettings', {'budgetCents': 200000});
      await store.invoke('updateSettings', {'budgetCents': 250000});
      await expectLater(
        store.invoke('updateSettings', {
          'budgetCents': 300000,
          'expectedBudgetCents': 200000,
        }),
        throwsA(isA<LedgerException>()),
      );
      expect((await store.snapshot()).settings['budgetCents'], 250000);
      await store.invoke('updateSettings', {
        'budgetCents': 300000,
        'expectedBudgetCents': 250000,
      });
      expect((await store.snapshot()).settings['budgetCents'], 300000);
      expect(
        (await store.snapshot()).settings.containsKey('expectedBudgetCents'),
        false,
      );
    },
  );
  test(
    'delete proposal version is checked inside the ledger transaction',
    () async {
      final state = await store.snapshot();
      final category = state.categories.firstWhere(
        (c) => c.enabled && c.type == TxType.expense,
      );
      final fields = {
        'revision': 0,
        'id': 0,
        'amount': 1200,
        'type': 'EXPENSE',
        'categoryId': category.id,
        'businessKind': 'expense',
        'source': 'MANUAL',
        'occurredAt': DateTime(2026, 10, 24).millisecondsSinceEpoch,
      };
      final result = await store.invoke('saveTransaction', fields) as Map;
      final id = result['id'];
      await store.invoke('saveTransaction', {
        ...fields,
        'id': id,
        'amount': 1300,
        'editVersion': 0,
      });
      await expectLater(
        store.invoke('deleteTransaction', {
          'revision': 0,
          'id': id,
          'editVersion': 0,
        }),
        throwsA(isA<LedgerException>()),
      );
      expect((await store.snapshot()).transactions.single.amountFen, 1300);
      await store.invoke('deleteTransaction', {
        'revision': 0,
        'id': id,
        'editVersion': 1,
      });
      expect((await store.snapshot()).transactions, isEmpty);
    },
  );
}

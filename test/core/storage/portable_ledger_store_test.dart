import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as path;
import 'package:pennypilot/core/ledger_repository.dart';
import 'package:pennypilot/core/models.dart';
import 'package:pennypilot/core/storage/portable_ledger_store.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

final _now = DateTime(2026, 10, 23, 12);

Map<String, dynamic> _transaction({
  int amount = 1250,
  int categoryId = 1,
  String type = 'EXPENSE',
}) => {
  'amount': amount,
  'type': type,
  'categoryId': categoryId,
  'occurredAt': _now.millisecondsSinceEpoch,
  'merchant': '早餐店',
  'note': null,
  'source': 'MANUAL',
};

Matcher _error(String code) =>
    isA<LedgerException>().having((error) => error.code, 'code', code);

void main() {
  late Directory directory;
  late String databasePath;
  late PortableLedgerStore store;

  PortableLedgerStore open() => PortableLedgerStore(
    databaseFactory: databaseFactoryFfi,
    databasePath: databasePath,
    clock: () => _now,
  );

  Future<Map<String, dynamic>> export() async => Map<String, dynamic>.from(
    await store.invoke('exportBackupData', {}) as Map,
  );

  Future<void> restore(Map<String, dynamic> data, {int revision = 0}) async {
    await store.invoke('restoreBackupData', {
      'revision': revision,
      'data': data,
    });
  }

  setUpAll(sqfliteFfiInit);
  setUp(() async {
    directory = await Directory.systemTemp.createTemp('pennypilot-storage-');
    databasePath = path.join(directory.path, 'ledger.sqlite');
    store = open();
  });
  tearDown(() async {
    await store.close();
    await directory.delete(recursive: true);
  });

  test(
    'first launch has only real empty ledger and built-in categories',
    () async {
      final snapshot = await store.snapshot();
      expect(snapshot.revision, 0);
      expect(snapshot.transactions, isEmpty);
      expect(snapshot.reports, isEmpty);
      expect(snapshot.categories, hasLength(12));
      expect(
        snapshot.categories.every((category) => category.isPreset),
        isTrue,
      );
      expect(snapshot.rules, hasLength(5));
      expect(snapshot.settings['activeProvider'], 'deepseek');
      expect(snapshot.settings['hasDeepSeekKey'], false);
      expect(snapshot.auth['accounts'], isEmpty);
      expect(snapshot.capabilities['platform'], 'ios');
      expect(snapshot.capabilities['notificationServiceConnected'], false);
    },
  );

  test(
    'transactions, rules, reports and ordinary settings survive reopening',
    () async {
      await store.invoke('saveTransaction', {'revision': 0, ..._transaction()});
      await store.invoke('saveTransaction', {
        'revision': 0,
        ..._transaction(amount: 900000, categoryId: 10, type: 'INCOME'),
      });
      await store.invoke('saveRule', {
        'revision': 0,
        'appPackage': 'com.example.pay',
        'regexAmount': r'金额 (\d+)',
        'directionHint': 'EXPENSE',
      });
      await store.invoke('saveGeneratedReport', {
        'revision': 0,
        'yearMonth': '2026-09',
        'totalExpense': 1250,
        'totalIncome': 900000,
        'byCategoryJson': '[{"name":"餐饮","total":1250,"count":1}]',
        'llmSummary': '上月账单',
      });
      await store.invoke('updateSettings', {
        'onboardingDone': true,
        'budgetCents': 120000,
        'themeMode': 'dark',
        'activeProvider': 'qwen',
        'qwenModel': 'qwen-plus',
      });
      final backup = await export();
      await store.close();
      store = open();
      expect(await export(), backup);
      final snapshot = await store.snapshot();
      expect(
        snapshot.transactions.map((row) => row.amountFen),
        containsAll([1250, 900000]),
      );
      expect(snapshot.settings['budgetCents'], 120000);
      expect(snapshot.settings['onboardingDone'], true);
      expect(snapshot.settings['activeProvider'], 'qwen');
      expect(snapshot.revision, 0);
    },
  );

  test('batch rollback leaves no first-row write or change event', () async {
    final events = <LedgerEvent>[];
    final subscription = store.events.listen(events.add);
    await expectLater(
      store.invoke('saveTransactions', {
        'revision': 0,
        'transactions': [_transaction(), _transaction(categoryId: 999)],
      }),
      throwsA(_error('INVALID_ARGUMENT')),
    );
    expect((await store.snapshot()).transactions, isEmpty);
    await Future<void>.delayed(Duration.zero);
    expect(events, isEmpty);
    await subscription.cancel();
  });

  test(
    'amount, category type, source and date validation reject unsafe writes',
    () async {
      for (final invalid in [
        {'amount': 1.5},
        {'amount': -100},
        {'amount': 0},
        {'amount': maxTransactionFen + 1},
        {'type': 'INCOME'},
        {'categoryId': '1'},
        {'source': 'NOTIFICATION'},
        {'occurredAt': -1},
        {'occurredAt': 1.5},
        {'note': 100},
      ]) {
        await expectLater(
          store.invoke('saveTransaction', {
            'revision': 0,
            ..._transaction(),
            ...invalid,
          }),
          throwsA(_error('INVALID_ARGUMENT')),
        );
      }
      expect((await store.snapshot()).transactions, isEmpty);
    },
  );

  test(
    'shared Android/iOS backup fixture survives SQLite without field loss',
    () async {
      final fixture =
          jsonDecode(
                await File(
                  'test/core/storage/fixtures/portable_backup_v1.json',
                ).readAsString(),
              )
              as Map<String, dynamic>;
      await restore(fixture);
      final actual = await export();
      expect(actual, fixture);
      // The Kotlin contract test can read this actual SQLite export to verify the
      // complete Android -> Dart SQLite -> Android path in a two-command check.
      final destination =
          Platform.environment['PENNYPILOT_BACKUP_CONTRACT_EXPORT'];
      if (destination != null) {
        await File(destination).writeAsString(jsonEncode(actual));
      }
    },
  );

  test('invalid restore preserves data, revision and events', () async {
    await expectLater(
      restore({...await export(), 'categories': []}),
      throwsA(_error('INVALID_ARGUMENT')),
    );
    await store.invoke('saveTransaction', {'revision': 0, ..._transaction()});
    final original = await export();
    final events = <LedgerEvent>[];
    final subscription = store.events.listen(events.add);
    final invalidBackups = <Map<String, dynamic>>[
      {...original, 'version': 2},
      {...original, 'version': 1.0},
      {...original, 'format': 'unknown'},
      {...original, 'futureSchema': {}},
      {...original, 'categories': []},
      {
        ...original,
        'transactions': [
          {
            ...Map<String, dynamic>.from(
              (original['transactions'] as List).single as Map,
            ),
            'amount': 1250.5,
          },
        ],
      },
      {
        ...original,
        'transactions': [
          {
            ...Map<String, dynamic>.from(
              (original['transactions'] as List).single as Map,
            ),
            'amount': -1250,
          },
        ],
      },
      {
        ...original,
        'rules': [
          {
            'id': 1,
            'appPackage': 'com.example.pay',
            'regexAmount': 123,
            'directionHint': 'EXPENSE',
            'enabled': true,
            'priority': 0,
          },
        ],
      },
      {
        ...original,
        'reports': [
          {
            'id': 1,
            'yearMonth': '2026-13',
            'totalExpense': 0,
            'totalIncome': 0,
            'byCategoryJson': '[]',
            'generatedAt': 1,
          },
        ],
      },
      {
        ...original,
        'transactions': [
          ...original['transactions'] as List,
          ...original['transactions'] as List,
        ],
      },
    ];
    for (final invalid in invalidBackups) {
      await expectLater(restore(invalid), throwsA(_error('INVALID_ARGUMENT')));
      expect(await export(), original);
      expect((await store.snapshot()).revision, 0);
    }
    await Future<void>.delayed(Duration.zero);
    expect(events, isEmpty);
    await subscription.cancel();
  });

  test(
    'restore changes persisted revision once and rejects all stale writers',
    () async {
      final backup = await export();
      final event = store.events.first;
      await restore(backup);
      expect((await event).type, 'restored');
      expect((await store.snapshot()).revision, 1);
      await store.close();
      store = open();
      expect((await store.snapshot()).revision, 1);
      final before = await export();
      await expectLater(restore(backup), throwsA(_error('STALE_LEDGER')));
      for (final method in [
        'saveTransaction',
        'saveTransactions',
        'deleteTransaction',
        'setTransactionStatus',
        'saveCategory',
        'deleteCategory',
        'saveRule',
        'deleteRule',
        'resetRules',
        'saveGeneratedReport',
      ]) {
        await expectLater(
          store.invoke(method, {'revision': 0}),
          throwsA(_error('STALE_LEDGER')),
        );
      }
      expect(await export(), before);
      await store.invoke('saveTransaction', {'revision': 1, ..._transaction()});
      expect((await store.snapshot()).revision, 1);
    },
  );

  test(
    'Android backup roundtrip retains IDs and all original capture evidence',
    () async {
      final backup = await export();
      // Valid Java Regex syntax must remain portable as archival rule data,
      // even when the receiving platform does not run Android capture rules.
      (backup['rules'] as List).first['regexAmount'] = r'(?i)paid (\d++)';
      backup['transactions'] = [
        {
          'id': 41,
          'amount': 100,
          'type': 'EXPENSE',
          'categoryId': 1,
          'occurredAt': 1000,
          'createdAt': 1001,
          'source': 'MANUAL',
          'status': 'CONFIRMED',
          'merchant': null,
          'note': null,
          'accountTag': null,
          // Android retains hints after the referenced historical row is deleted.
          'duplicateOf': 777,
          'rawPayload': null,
          'captureEventKey': null,
        },
        {
          'id': 42,
          'amount': 1250,
          'type': 'EXPENSE',
          'categoryId': 1,
          'occurredAt': 2000,
          'createdAt': 2001,
          'source': 'NOTIFICATION',
          'status': 'PENDING',
          'merchant': '早餐店',
          'note': null,
          'accountTag': '微信',
          'duplicateOf': 41,
          'rawPayload': '微信支付 ¥12.50',
          'captureEventKey': 'event-42',
        },
      ];
      backup['captureEvents'] = [
        {
          'eventKey': 'event-42',
          'transactionId': 42,
          'packageName': 'com.tencent.mm',
          'source': 'NOTIFICATION',
          'observedAt': 2001,
          'firstObservedAt': 2000,
          'notificationKey': 'notification-42',
          'textFingerprint': 'hash',
          'orderId': 'order42',
          'orderDomain': 'wechat',
          'amount': 1250,
          'type': 'EXPENSE',
          'merchantFingerprint': 'merchant-hash',
        },
      ];
      await restore(backup);
      // Canonical JSON can move between Android and iOS without SQLite coupling.
      expect(jsonDecode(jsonEncode(await export())), backup);
      final snapshotMap = await store.invoke('snapshot', {}) as Map;
      expect(
        (snapshotMap['transactions'] as List).every(
          (row) => !(row as Map).containsKey('rawPayload'),
        ),
        isTrue,
      );
      expect(snapshotMap.containsKey('captureEvents'), isFalse);
      expect(
        (await store.snapshot()).capabilities['notificationServiceConnected'],
        false,
      );

      await store.invoke('saveTransaction', {
        'revision': 1,
        ..._transaction(amount: 2000),
        'id': 42,
        'source': 'AI_TEXT',
        'status': 'CONFIRMED',
        'createdAt': 9999,
      });
      final edited = await export();
      final row =
          (edited['transactions'] as List).singleWhere((row) => row['id'] == 42)
              as Map;
      expect(row['amount'], 2000);
      expect(row['source'], 'NOTIFICATION');
      expect(row['status'], 'PENDING');
      expect(row['createdAt'], 2001);
      expect(row['duplicateOf'], 41);
      expect(row['rawPayload'], '微信支付 ¥12.50');
      expect(row['captureEventKey'], 'event-42');
      expect(edited['captureEvents'], backup['captureEvents']);

      final created =
          await store.invoke('saveTransaction', {
                'revision': 1,
                ..._transaction(),
                'source': 'AI_TEXT',
              })
              as Map;
      expect(created['id'], greaterThan(42));
      await store.invoke('setTransactionStatus', {
        'revision': 1,
        'id': 42,
        'status': 'CONFIRMED',
      });
      await expectLater(
        store.invoke('setTransactionStatus', {
          'revision': 1,
          'id': 42,
          'status': 'IGNORED',
        }),
        throwsA(_error('INVALID_ARGUMENT')),
      );
      await store.invoke('deleteTransaction', {'revision': 1, 'id': 42});
      expect((await export())['captureEvents'], isEmpty);
    },
  );

  test(
    'category deletion atomically reassigns rows to enabled same-type Other',
    () async {
      final category =
          await store.invoke('saveCategory', {
                'revision': 0,
                'name': '测试',
                'icon': '📦',
                'type': 'INCOME',
              })
              as Map;
      await store.invoke('saveTransaction', {
        'revision': 0,
        ..._transaction(type: 'INCOME', categoryId: category['id'] as int),
      });
      await store.invoke('saveCategory', {
        'revision': 0,
        'id': 12,
        'name': '其他',
        'icon': '💰',
        'type': 'INCOME',
        'enabled': false,
      });
      await store.invoke('deleteCategory', {
        'revision': 0,
        'id': category['id'],
      });
      final snapshot = await store.snapshot();
      expect(snapshot.transactions.single.categoryId, 12);
      expect(
        snapshot.categories.singleWhere((row) => row.id == 12).enabled,
        true,
      );
      expect(snapshot.categories.any((row) => row.id == category['id']), false);
      await expectLater(
        store.invoke('deleteCategory', {'revision': 0, 'id': 12}),
        throwsA(_error('INVALID_ARGUMENT')),
      );
      await expectLater(
        store.invoke('saveCategory', {
          'revision': 0,
          'name': '工资',
          'icon': '📦',
          'type': 'INCOME',
        }),
        throwsA(_error('INVALID_ARGUMENT')),
      );
    },
  );

  test(
    'disabled category accepts only an existing transaction retaining it',
    () async {
      final created =
          await store.invoke('saveTransaction', {
                'revision': 0,
                ..._transaction(),
              })
              as Map;
      await store.invoke('saveCategory', {
        'revision': 0,
        'id': 1,
        'name': '餐饮',
        'icon': '🍜',
        'type': 'EXPENSE',
        'enabled': false,
      });
      await expectLater(
        store.invoke('saveTransaction', {'revision': 0, ..._transaction()}),
        throwsA(_error('INVALID_ARGUMENT')),
      );
      await store.invoke('saveTransaction', {
        'revision': 0,
        ..._transaction(amount: 1500),
        'id': created['id'],
      });
      expect((await store.snapshot()).transactions.single.amountFen, 1500);
    },
  );

  test(
    'settings reject secrets and invalid fields before any persistence',
    () async {
      final initial = (await store.snapshot()).settings;
      for (final invalid in [
        {'deepSeekKey': 'secret'},
        {'qwenKey': 'secret'},
        {'onboardingDone': 'true'},
        {'budgetCents': 12.5},
        {'themeMode': 'anything'},
        {'notificationCaptureEnabled': true},
        {'qwenBaseUrl': 'http://api.example.com'},
        {'qwenBaseUrl': 'https://api.example.com?key=secret'},
      ]) {
        await expectLater(
          store.invoke('updateSettings', {'budgetCents': 1000, ...invalid}),
          throwsA(_error('INVALID_ARGUMENT')),
        );
        expect((await store.snapshot()).settings, initial);
      }
      final backup = await export();
      expect(backup.containsKey('settings'), false);
      expect(backup.containsKey('auth'), false);
    },
  );

  test(
    'report save upserts a month without changing revision or IDs',
    () async {
      final report = {
        'revision': 0,
        'yearMonth': '2026-09',
        'totalExpense': 1250,
        'totalIncome': 0,
        'byCategoryJson': '[]',
        'llmSummary': '第一版',
      };
      final first = await store.invoke('saveGeneratedReport', report);
      final second = await store.invoke('saveGeneratedReport', {
        ...report,
        'llmSummary': '第二版',
      });
      expect(first, second);
      expect((await store.snapshot()).reports.single.llmSummary, '第二版');
      await expectLater(
        store.invoke('saveGeneratedReport', {
          ...report,
          'yearMonth': '2026-10',
        }),
        throwsA(_error('INVALID_ARGUMENT')),
      );
      expect((await store.snapshot()).revision, 0);
    },
  );
}

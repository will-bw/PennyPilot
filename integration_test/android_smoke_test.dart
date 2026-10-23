import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

// Run on a dedicated test emulator with --no-uninstall. Flutter's test command
// otherwise uninstalls the entire application after this test has cleaned up.
const _channel = MethodChannel('pennypilot/ledger');
const _prefix = '__flutter_smoke_';

Map<String, dynamic> _map(Object? value) {
  expect(value, isA<Map>(), reason: 'Bridge response must be a map.');
  return (value! as Map).map((key, value) => MapEntry(key.toString(), value));
}

List<Map<String, dynamic>> _rows(Map<String, dynamic> snapshot, String name) {
  expect(snapshot[name], isA<List>(), reason: 'Snapshot must expose $name.');
  return (snapshot[name] as List).map(_map).toList();
}

Future<Object?> _invoke(
  String method, [
  Map<String, dynamic> args = const {},
]) => _channel
    .invokeMethod<Object?>(method, args)
    .timeout(const Duration(seconds: 30));

Future<Map<String, dynamic>> _snapshot() async =>
    _map(await _invoke('snapshot'));

Object? _canonical(Object? value) {
  if (value is Map) {
    final keys = value.keys.map((key) => key.toString()).toList()..sort();
    return {for (final key in keys) key: _canonical(value[key])};
  }
  if (value is List) return value.map(_canonical).toList();
  return value;
}

String _canonicalRows(List<Map<String, dynamic>> rows) {
  final sorted = rows.toList()
    ..sort((a, b) => (a['id'] as int).compareTo(b['id'] as int));
  return jsonEncode(_canonical(sorted));
}

bool _hasEnabledFallback(List<Map<String, dynamic>> categories, String type) =>
    categories.any(
      (row) =>
          row['name'] == '其他' && row['type'] == type && row['enabled'] == true,
    );

Future<void> _expectRejected(
  Map<String, dynamic> args,
  String expectedCode,
) async {
  try {
    await _invoke('saveTransaction', args);
  } on PlatformException catch (error) {
    expect(error.code, expectedCode);
    expect(
      error.message,
      isNotEmpty,
      reason: 'Rejected writes need a user-facing explanation.',
    );
    return;
  }
  fail('The bridge accepted a write that should be rejected as $expectedCode.');
}

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets(
    'native ledger bridge preserves the existing ledger through isolated CRUD smoke checks',
    (tester) async {
      // No app widget, network, permission prompt, login or backup/restore call is
      // involved. The existing Android Activity supplies the real ledger channel.
      final before = await _snapshot();
      expect(before['revision'], isA<int>());
      final revision = before['revision'] as int;
      expect(revision, greaterThanOrEqualTo(0));
      final originalTransactions = _rows(before, 'transactions');
      final originalCategories = _rows(before, 'categories');
      final originalRules = _rows(before, 'rules');
      final originalReports = _rows(before, 'reports');
      final settings = _map(before['settings']);
      final auth = _map(before['auth']);
      expect(_map(before['capabilities'])['platform'], 'android');
      for (final forbidden in [
        'deepSeekKey',
        'qwenKey',
        'accessToken',
        'refreshToken',
      ]) {
        expect(
          settings.containsKey(forbidden),
          isFalse,
          reason: 'Snapshot must not expose secret fields.',
        );
        expect(
          auth.containsKey(forbidden),
          isFalse,
          reason: 'Snapshot must not expose token fields.',
        );
      }
      for (final row in originalTransactions) {
        expect(row['id'], isA<int>());
        expect(row['amount'], isA<int>());
        expect(row['categoryId'], isA<int>());
        expect(row['occurredAt'], isA<int>());
        expect(row['type'], isIn(['EXPENSE', 'INCOME']));
        expect(row['status'], isIn(['PENDING', 'CONFIRMED', 'IGNORED']));
        expect(
          row['source'],
          isIn([
            'MANUAL',
            'AI_TEXT',
            'AI_VOICE',
            'NOTIFICATION',
            'ACCESSIBILITY',
          ]),
        );
      }

      // deleteCategory may enable/create “其他”. Refuse to start if cleanup could
      // therefore change an existing user's category instead of only our rows.
      for (final type in ['EXPENSE', 'INCOME']) {
        expect(
          _hasEnabledFallback(originalCategories, type),
          isTrue,
          reason:
              'Both enabled “其他” categories must already exist before a non-destructive smoke run.',
        );
      }

      final timestamp = DateTime.now().microsecondsSinceEpoch;
      final marker = '$_prefix$timestamp';
      String? categoryName;
      // Category names have a 20-character limit. Retain the full 16-character
      // prefix and a four-character timestamp suffix, checking for collisions.
      for (var offset = 0; offset < 100; offset++) {
        final suffix = ((timestamp + offset) % 1679616)
            .toRadixString(36)
            .padLeft(4, '0');
        final candidate = '$_prefix$suffix';
        if (!originalCategories.any((row) => row['name'] == candidate)) {
          categoryName = candidate;
          break;
        }
      }
      expect(
        categoryName,
        isNotNull,
        reason: 'A unique smoke category name is required.',
      );
      final testCategoryName = categoryName!;
      final originalTransactionIds = originalTransactions
          .map((row) => row['id'] as int)
          .toSet();
      final originalCategoryIds = originalCategories
          .map((row) => row['id'] as int)
          .toSet();
      final ownedCategoryIds = <int>{};
      final ownedTransactionIds = <int>{};
      final occurredAt = DateTime.now().millisecondsSinceEpoch;

      try {
        final categoryByType = <String, int>{};
        for (final type in ['EXPENSE', 'INCOME']) {
          final result = _map(
            await _invoke('saveCategory', {
              'revision': revision,
              'name': testCategoryName,
              'icon': 'other',
              'type': type,
              'enabled': true,
            }),
          );
          expect(result['id'], isA<int>());
          final id = result['id'] as int;
          expect(
            originalCategoryIds.contains(id),
            isFalse,
            reason: 'New category must not replace an existing row.',
          );
          ownedCategoryIds.add(id);
          categoryByType[type] = id;
        }

        Map<String, dynamic> transactionArgs(
          String type,
          int amount,
          String source,
        ) => {
          'revision': revision,
          'amount': amount,
          'type': type,
          'categoryId': categoryByType[type],
          'occurredAt': occurredAt,
          'note': marker,
          'merchant': marker,
          'accountTag': 'smoke-test',
          'source': source,
        };

        final expenseArgs = transactionArgs('EXPENSE', 12345, 'AI_TEXT');
        final incomeArgs = transactionArgs('INCOME', 54321, 'AI_VOICE');
        final expenseId =
            _map(await _invoke('saveTransaction', expenseArgs))['id'] as int;
        ownedTransactionIds.add(expenseId);
        final incomeId =
            _map(await _invoke('saveTransaction', incomeArgs))['id'] as int;
        ownedTransactionIds.add(incomeId);
        expect(
          ownedTransactionIds.intersection(originalTransactionIds),
          isEmpty,
          reason: 'Test inserts must not replace existing transactions.',
        );

        final inserted = await _snapshot();
        expect(inserted['revision'], revision);
        final insertedRows = _rows(inserted, 'transactions');
        final expense = insertedRows.singleWhere(
          (row) => row['id'] == expenseId,
        );
        final income = insertedRows.singleWhere((row) => row['id'] == incomeId);
        expect(
          expense['amount'],
          12345,
          reason: 'Expense storage uses positive integer cents.',
        );
        expect(
          income['amount'],
          -54321,
          reason: 'Income storage uses negative integer cents.',
        );
        expect(expense['type'], 'EXPENSE');
        expect(income['type'], 'INCOME');
        expect(expense['status'], 'CONFIRMED');
        expect(income['status'], 'CONFIRMED');
        expect(expense['source'], 'AI_TEXT');
        expect(income['source'], 'AI_VOICE');

        // Every attempted row remains marked, including an erroneous acceptance,
        // so finally can discover and remove it without touching original rows.
        for (final amount in <Object>[0, -1, 1000000000, 12.5, '12']) {
          await _expectRejected({
            ...expenseArgs,
            'amount': amount,
          }, 'INVALID_ARGUMENT');
        }
        await _expectRejected({
          ...expenseArgs,
          'categoryId': categoryByType['INCOME'],
        }, 'INVALID_ARGUMENT');
        await _expectRejected({
          ...expenseArgs,
          'revision': revision - 1,
        }, 'STALE_LEDGER');
        expect(
          _rows(await _snapshot(), 'transactions').length,
          originalTransactions.length + 2,
          reason: 'Rejected writes must not leave extra rows.',
        );

        final editedId = _map(
          await _invoke('saveTransaction', {
            ...expenseArgs,
            'id': expenseId,
            'amount': 23456,
            'source': 'MANUAL',
            'merchant': '$marker/edited',
          }),
        )['id'];
        expect(editedId, expenseId);
        final edited = _rows(
          await _snapshot(),
          'transactions',
        ).singleWhere((row) => row['id'] == expenseId);
        expect(edited['amount'], 23456);
        expect(edited['merchant'], '$marker/edited');
        expect(
          edited['source'],
          'AI_TEXT',
          reason: 'Editing must retain the original source.',
        );
        expect(edited['createdAt'], expense['createdAt']);
        expect(edited['status'], expense['status']);
        expect(edited['duplicateOf'], expense['duplicateOf']);
      } finally {
        // Discover only this invocation's exact marker/name, and exclude all IDs
        // present before it. This also handles a write whose response was lost.
        var current = await _snapshot();
        if (current['revision'] != revision) {
          fail(
            'Ledger revision changed during smoke checks. Cleanup refused to touch the replacement ledger; marker: $marker.',
          );
        }
        final currentCategories = _rows(current, 'categories');
        for (final row in currentCategories) {
          if (!originalCategoryIds.contains(row['id']) &&
              row['name'] == testCategoryName) {
            ownedCategoryIds.add(row['id'] as int);
          }
        }
        final currentRows = _rows(current, 'transactions');
        for (final row in currentRows) {
          if (!originalTransactionIds.contains(row['id']) &&
              row['note'] == marker &&
              ownedCategoryIds.contains(row['categoryId'])) {
            ownedTransactionIds.add(row['id'] as int);
          }
        }
        for (final id in ownedTransactionIds) {
          final matches = currentRows.where((row) => row['id'] == id).toList();
          if (matches.isEmpty) continue;
          final row = matches.single;
          if (originalTransactionIds.contains(id) ||
              row['note'] != marker ||
              !ownedCategoryIds.contains(row['categoryId'])) {
            fail(
              'A smoke row no longer matches its ownership marker; cleanup stopped.',
            );
          }
          await _invoke('deleteTransaction', {'revision': revision, 'id': id});
        }
        current = await _snapshot();
        final remainingRows = _rows(current, 'transactions');
        final remainingCategories = _rows(current, 'categories');
        for (final id in ownedCategoryIds) {
          final matches = remainingCategories
              .where((row) => row['id'] == id)
              .toList();
          if (matches.isEmpty) continue;
          final category = matches.single;
          if (originalCategoryIds.contains(id) ||
              category['name'] != testCategoryName ||
              remainingRows.any((row) => row['categoryId'] == id) ||
              !_hasEnabledFallback(
                remainingCategories,
                category['type'] as String,
              )) {
            fail('Category cleanup could affect another row; cleanup stopped.');
          }
          await _invoke('deleteCategory', {'revision': revision, 'id': id});
        }

        final after = await _snapshot();
        // Compare full records, but only print a boolean failure instead of the
        // user's private transaction values if the preservation assertion fails.
        for (final table in [
          'transactions',
          'categories',
          'rules',
          'reports',
        ]) {
          final expected = switch (table) {
            'transactions' => originalTransactions,
            'categories' => originalCategories,
            'rules' => originalRules,
            _ => originalReports,
          };
          expect(
            _canonicalRows(_rows(after, table)) == _canonicalRows(expected),
            isTrue,
            reason:
                'All pre-existing $table records must be unchanged after cleanup.',
          );
        }
        binding.reportData = {
          'originalTransactionCount': originalTransactions.length,
          'remainingTransactionCount': _rows(after, 'transactions').length,
          'originalCategoryCount': originalCategories.length,
          'remainingCategoryCount': _rows(after, 'categories').length,
          'cleanupVerified': true,
        };
      }
    },
    timeout: const Timeout(Duration(minutes: 3)),
  );
}

import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:pennypilot/core/cross_platform_repository.dart';
import 'package:pennypilot/core/ledger_repository.dart';
import 'package:pennypilot/core/models.dart';

/// Run only on dedicated test devices, always with --no-uninstall.
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  testWidgets(
    'real platform storage, portable backup and restore invalidation',
    (tester) async {
      final repository = CrossPlatformLedgerRepository();
      final original = stringMap(
        await repository.invoke('exportBackupData', {}),
      );
      var restored = false;
      try {
        final state = await repository.snapshot();
        expect(['android', 'ios'], contains(state.capabilities['platform']));
        expect(state.settings.keys, isNot(contains('deepSeekKey')));
        if (state.capabilities['platform'] == 'ios') {
          expect(state.capabilities['automaticCaptureSupported'], isFalse);
          expect(state.settings['needsReconfiguration'], isFalse,
              reason: 'real iOS Keychain must be readable');
          expect(state.auth['error'], isNull,
              reason: 'new OAuth host identity must persist in Keychain');
        }
        final category = state.categories.firstWhere(
          (c) => c.enabled && c.type == TxType.expense,
        );
        final saved = stringMap(
          await repository.invoke('saveTransaction', {
            'revision': state.revision,
            'id': 0,
            'amount': 1234,
            'type': 'EXPENSE',
            'categoryId': category.id,
            'note':
                'cross-platform-smoke-${DateTime.now().microsecondsSinceEpoch}',
            'occurredAt': DateTime.now().millisecondsSinceEpoch,
          }),
        );
        final updated = await repository.snapshot();
        expect(
          updated.transactions.firstWhere((t) => t.id == saved['id']).amountFen,
          1234,
        );
        final encoded = jsonEncode(
          await repository.invoke('exportBackupData', {}),
        );
        final backup = stringMap(jsonDecode(encoded));
        expect(backup['format'], 'pennypilot');
        expect(backup.keys, isNot(contains('settings')));
        await repository.invoke('restoreBackupData', {
          'revision': updated.revision,
          'data': backup,
        });
        final after = await repository.snapshot();
        expect(after.revision, greaterThan(updated.revision));
        await expectLater(
          repository.invoke('deleteTransaction', {
            'revision': updated.revision,
            'id': saved['id'],
          }),
          throwsA(
            isA<LedgerException>().having(
              (e) => e.code,
              'code',
              'STALE_LEDGER',
            ),
          ),
        );
        await repository.invoke('restoreBackupData', {
          'revision': after.revision,
          'data': original,
        });
        restored = true;
        final finalBackup = stringMap(
          await repository.invoke('exportBackupData', {}),
        );
        for (final table in [
          'categories',
          'transactions',
          'rules',
          'reports',
          'captureEvents',
        ]) {
          final actual = _orderedRows(finalBackup[table]);
          final expected = _orderedRows(original[table]);
          expect(actual, expected, reason: '$table must be restored exactly');
        }
      } finally {
        if (!restored) {
          final state = await repository.snapshot();
          await repository.invoke('restoreBackupData', {
            'revision': state.revision,
            'data': original,
          });
        }
        await repository.close();
      }
    },
  );
}

List<Map<String, dynamic>> _orderedRows(Object? value) =>
    (value as List).map(stringMap).toList()..sort(
      (a, b) => (a['id'] ?? a['eventKey']).toString().compareTo(
        (b['id'] ?? b['eventKey']).toString(),
      ),
    );

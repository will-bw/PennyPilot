import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/ledger_repository.dart';
import 'package:pennypilot/core/models.dart';

void main() {
  test(
    'money parser accepts only exact positive fen within transaction limit',
    () {
      expect(parseMoney('0.01'), 1);
      expect(parseMoney('12.3'), 1230);
      expect(parseMoney(' 9,999,999.99 '), maxTransactionFen);
      for (final invalid in [
        '0',
        '-1',
        '+2',
        '1.001',
        '1e3',
        'NaN',
        'Infinity',
        '10,00.00',
        '.5',
        '1.',
        '10000000',
        '99999999999999999999999999',
      ]) {
        expect(parseMoney(invalid), isNull, reason: invalid);
      }
    },
  );
  test('money formatter uses integer arithmetic including large totals', () {
    expect(formatMoney(1), '0.01');
    expect(formatMoney(123456789), '1,234,567.89');
    expect(formatMoney(-123), '-1.23');
    expect(formatMoney(123456, group: false), '1234.56');
  });
  test(
    'signed native income becomes positive UI amount and positive save payload',
    () {
      final transaction = LedgerTransaction.fromMap({
        'id': 9,
        'amount': -1299,
        'type': 'INCOME',
        'categoryId': 2,
        'occurredAt': 1792713600000,
        'status': 'CONFIRMED',
        'source': 'MANUAL',
      });
      expect(transaction.amountFen, 1299);
      expect(transaction.signedAmountFen, -1299);
      expect(transaction.toMap()['amount'], 1299);
      expect(transaction.toMap()['occurredAt'], 1792713600000);
    },
  );
  test('bridge data refuses fractional fen and unknown enum values', () {
    final values = {
      'id': 9,
      'amount': 12.5,
      'type': 'EXPENSE',
      'categoryId': 1,
      'occurredAt': 1,
    };
    expect(() => LedgerTransaction.fromMap(values), throwsFormatException);
    expect(() => TxType.fromWire('OTHER'), throwsFormatException);
  });
  test(
    'non-Android repository explicitly refuses access without simulated data',
    () async {
      debugDefaultTargetPlatformOverride = TargetPlatform.macOS;
      addTearDown(() {
        debugDefaultTargetPlatformOverride = null;
      });
      final repository = NativeLedgerRepository();
      await expectLater(
        repository.snapshot(),
        throwsA(
          isA<LedgerException>().having(
            (error) => error.code,
            'code',
            'PLATFORM_UNAVAILABLE',
          ),
        ),
      );
      await expectLater(
        repository.invoke('saveTransaction', {}),
        throwsA(
          isA<LedgerException>().having(
            (error) => error.code,
            'code',
            'PLATFORM_UNAVAILABLE',
          ),
        ),
      );
    },
  );
}

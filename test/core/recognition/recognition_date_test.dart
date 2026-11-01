import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/recognition/recognition_date.dart';

void main() {
  final now = DateTime(2026, 11, 1, 10, 23);

  for (final amount in ['十二点五元', '十二点三五元', '12点5元', '十二点五块钱']) {
    test('spoken decimal amount $amount does not supply a clock', () {
      final result = RecognitionDate.resolve(
        context: '午饭$amount',
        inputBeforeAmount: '午饭',
        modelDate: null,
        now: now,
      );

      expect(result.date, now);
      expect(result.precision, 'user_entry_time');
      expect(result.problem, isNull);
    });
  }

  test('spoken decimal amount preserves an explicit calendar day', () {
    final result = RecognitionDate.resolve(
      context: '昨天午饭十二点五元',
      inputBeforeAmount: '昨天午饭',
      modelDate: null,
      now: now,
    );

    expect(result.date, DateTime(2026, 10, 31));
    expect(result.precision, 'day');
    expect(result.problem, isNull);
  });

  for (final item in [
    (clock: '中午十二点半', hour: 12, minute: 30, precision: 'exact'),
    (clock: '晚上7点过', hour: 19, minute: 0, precision: 'approximate'),
    (clock: '12:35', hour: 12, minute: 35, precision: 'exact'),
  ]) {
    test('actual clock ${item.clock} survives a later decimal amount', () {
      final result = RecognitionDate.resolve(
        context: '昨天${item.clock}午饭十二点五元',
        inputBeforeAmount: '昨天${item.clock}午饭',
        modelDate: null,
        now: now,
      );

      expect(result.date, DateTime(2026, 10, 31, item.hour, item.minute));
      expect(result.precision, item.precision);
      expect(result.problem, isNull);
    });
  }
}

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/features/reports/reports_screen.dart';
import 'package:pennypilot/ui/theme.dart';

import 'test_ledger_repository.dart';

void main() {
  Future<void> open(WidgetTester tester, {required bool currentMonth}) async {
    tester.view.physicalSize = const Size(390, 1000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final now = DateTime.now();
    final month = DateTime(now.year, now.month - (currentMonth ? 0 : 1));
    final previous = DateTime(month.year, month.month - 1);
    final repo = TestLedgerRepository();
    repo.data['transactions'] = [
      {
        ...fixtureTransaction(
          id: 1,
          amount: 5000,
          categoryId: 1,
          merchant: '午餐',
        ),
        'occurredAt': month.millisecondsSinceEpoch,
      },
      {
        ...fixtureTransaction(
          id: 2,
          amount: 10000,
          categoryId: 1,
          merchant: '午餐',
        ),
        'occurredAt': previous.millisecondsSinceEpoch,
      },
    ];
    final controller = LedgerController(repository: repo, clock: () => month);
    await controller.init();
    addTearDown(controller.dispose);
    addTearDown(repo.updates.close);
    await tester.pumpWidget(
      MaterialApp(
        theme: pennyTheme(Brightness.light),
        home: ReportsScreen(controller: controller),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets(
    'current month gives the previous full-month amount without misleading percentage',
    (tester) async {
      await open(tester, currentMonth: true);
      expect(find.textContaining('上月整月支出'), findsOneWidget);
      expect(find.textContaining('较上月'), findsNothing);
      expect(find.textContaining('50.0%'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('completed months retain the full-month comparison', (
    tester,
  ) async {
    await open(tester, currentMonth: false);
    expect(find.textContaining('较上月减少 50.0%'), findsOneWidget);
    expect(find.textContaining('上月整月支出'), findsNothing);
    expect(tester.takeException(), isNull);
  });
}

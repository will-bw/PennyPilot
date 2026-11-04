import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/core/models.dart';
import 'package:pennypilot/features/entry/entry_screen.dart';
import 'package:pennypilot/features/ledger/ledger_screen.dart';
import 'package:pennypilot/ui/theme.dart';

import 'app_widget_test.dart' as app_test;
import 'test_ledger_repository.dart';

final _amount = find.byKey(const ValueKey('entry-amount'));
final _participants = find.byKey(const ValueKey('entry-aa-participants'));
final _aaSwitch = find.byKey(const ValueKey('entry-aa-switch'));

Future<LedgerController> _openEntry(
  WidgetTester tester,
  TestLedgerRepository repository, {
  LedgerTransaction? transaction,
  Size size = const Size(390, 844),
  double textScale = 1,
  Brightness brightness = Brightness.light,
}) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = size;
  tester.platformDispatcher.textScaleFactorTestValue = textScale;
  addTearDown(() {
    tester.view.resetPhysicalSize();
    tester.view.resetDevicePixelRatio();
    tester.platformDispatcher.clearTextScaleFactorTestValue();
  });
  final controller = LedgerController(repository: repository);
  await controller.init();
  addTearDown(() async {
    controller.dispose();
    await repository.updates.close();
  });
  await tester.pumpWidget(
    RepaintBoundary(
      key: app_test.screenshotKey,
      child: MaterialApp(
        theme: pennyTheme(brightness),
        home: const Scaffold(body: Text('测试入口')),
      ),
    ),
  );
  await _pushEntry(tester, controller, transaction: transaction);
  return controller;
}

Future<void> _pushEntry(
  WidgetTester tester,
  LedgerController controller, {
  LedgerTransaction? transaction,
}) async {
  tester
      .state<ScaffoldMessengerState>(find.byType(ScaffoldMessenger))
      .clearSnackBars();
  await tester.pumpAndSettle();
  tester
      .state<NavigatorState>(find.byType(Navigator))
      .push(
        MaterialPageRoute<void>(
          builder: (_) =>
              EntryScreen(controller: controller, transaction: transaction),
        ),
      );
  await tester.pumpAndSettle();
}

Future<void> _setSplit(
  WidgetTester tester, {
  String total = '100',
  String count = '4',
}) async {
  await tester.enterText(_amount, total);
  await tester.tap(_aaSwitch);
  await tester.pumpAndSettle();
  await tester.enterText(_participants, count);
  await tester.pumpAndSettle();
}

Future<void> _save(WidgetTester tester, {bool editing = false}) async {
  final button = find.text(editing ? '保存修改' : '保存这笔账').hitTestable();
  expect(button, findsOneWidget);
  await tester.tap(button);
  await tester.pumpAndSettle();
}

LedgerTransaction _storedSplit(TestLedgerRepository repository) {
  final transaction = LedgerTransaction(
    id: 7,
    amountFen: 2500,
    type: TxType.expense,
    categoryId: 1,
    occurredAt: DateTime(2026, 10, 23),
    metadataJson: jsonEncode({
      'operationId': 'capture:test-aa',
      'aaSplit': {'totalAmountFen': 10000, 'participantCount': 4},
    }),
  );
  repository.data['transactions'] = [transaction.toMap()];
  return transaction;
}

String _inputText(WidgetTester tester, Finder field) => tester
    .widget<TextField>(
      find.descendant(of: field, matching: find.byType(TextField)),
    )
    .controller!
    .text;

void main() {
  setUpAll(app_test.loadScreenshotFonts);

  testWidgets('AA saves only the personal expense and retains the payment', (
    tester,
  ) async {
    final repository = TestLedgerRepository();
    final controller = await _openEntry(tester, repository);
    await _setSplit(tester);
    expect(find.text('自己承担 ¥25.00'), findsOneWidget);
    expect(find.text('仅此金额计入支出，按分四舍五入。'), findsOneWidget);
    await app_test.saveScreenshot(tester, 'aa-entry-light');
    await _save(tester);
    final saved = controller.transactions.single;
    expect(saved.amountFen, 2500);
    expect(saved.countsAsExpense, isTrue);
    expect(controller.summaryFor(saved.occurredAt).expenseFen, 2500);
    expect(saved.expenseSplit?.totalAmountFen, 10000);
    expect(saved.expenseSplit?.participantCount, 4);
    await _pushEntry(tester, controller, transaction: saved);
    await tester.drag(find.byType(ListView), const Offset(0, -1200));
    await tester.pumpAndSettle();
    expect(find.text('识别依据与待核对信息'), findsNothing);
    expect(find.textContaining('请核对凭证'), findsNothing);
    expect(find.textContaining('没有逐字段原文'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('AA rounds a third of 100 yuan to 33.33 yuan', (tester) async {
    final controller = await _openEntry(tester, TestLedgerRepository());
    await _setSplit(tester, count: '3');
    expect(find.text('自己承担 ¥33.33'), findsOneWidget);
    await _save(tester);
    expect(controller.transactions.single.amountFen, 3333);
    expect(tester.takeException(), isNull);
  });

  testWidgets('AA details retain real recognition evidence', (tester) async {
    final repository = TestLedgerRepository();
    final transaction = _storedSplit(repository).copyWith(
      source: TxSource.aiImage,
      metadataJson: jsonEncode({
        'aaSplit': {'totalAmountFen': 10000, 'participantCount': 4},
        'paymentStatus': 'completed',
        'fieldEvidence': {
          'amount': {'quote': '实际付款100元', 'source': 'image'},
        },
      }),
    );
    await _openEntry(tester, repository, transaction: transaction);
    await tester.drag(find.byType(ListView), const Offset(0, -1200));
    await tester.pumpAndSettle();
    expect(find.text('识别依据与待核对信息'), findsOneWidget);
    await tester.tap(find.text('识别依据与待核对信息'));
    await tester.pumpAndSettle();
    expect(find.text('实际付款100元'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'editing and reopening AA never divides the personal share again',
    (tester) async {
      final repository = TestLedgerRepository();
      final transaction = _storedSplit(repository);
      final controller = await _openEntry(
        tester,
        repository,
        transaction: transaction,
      );
      for (var i = 0; i < 2; i++) {
        expect(_inputText(tester, _amount), '100.00');
        expect(_inputText(tester, _participants), '4');
        expect(find.text('自己承担 ¥25.00'), findsOneWidget);
        await _save(tester, editing: true);
        final saved = controller.transactions.single;
        expect(saved.amountFen, 2500);
        expect(saved.recognitionMetadata['operationId'], 'capture:test-aa');
        if (i == 0) await _pushEntry(tester, controller, transaction: saved);
      }
      expect(
        repository.calls.where((call) => call.method == 'saveTransaction'),
        hasLength(2),
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('changing an AA total recomputes the stored personal share', (
    tester,
  ) async {
    final repository = TestLedgerRepository();
    final transaction = _storedSplit(repository);
    final controller = await _openEntry(
      tester,
      repository,
      transaction: transaction,
    );
    await tester.enterText(_amount, '120');
    await tester.pump();
    expect(find.text('自己承担 ¥30.00'), findsOneWidget);
    await _save(tester, editing: true);
    expect(controller.transactions.single.amountFen, 3000);
    expect(controller.transactions.single.expenseSplit?.totalAmountFen, 12000);
  });

  testWidgets('turning off AA records the total and removes only AA metadata', (
    tester,
  ) async {
    final repository = TestLedgerRepository();
    final transaction = _storedSplit(repository);
    final controller = await _openEntry(
      tester,
      repository,
      transaction: transaction,
    );
    await tester.tap(_aaSwitch);
    await tester.pumpAndSettle();
    expect(_participants, findsNothing);
    expect(_inputText(tester, _amount), '100.00');
    await _save(tester, editing: true);
    final saved = controller.transactions.single;
    expect(saved.amountFen, 10000);
    expect(saved.recognitionMetadata.containsKey('aaSplit'), isFalse);
    expect(saved.recognitionMetadata['operationId'], 'capture:test-aa');
  });

  testWidgets('switching to income clears AA and preserves the input total', (
    tester,
  ) async {
    final controller = await _openEntry(tester, TestLedgerRepository());
    await _setSplit(tester);
    await tester.tap(
      find.descendant(
        of: find.byType(SegmentedButton<TxType>),
        matching: find.text('收入'),
      ),
    );
    await tester.pumpAndSettle();
    expect(_aaSwitch, findsNothing);
    expect(_inputText(tester, _amount), '100');
    await _save(tester);
    final saved = controller.transactions.single;
    expect(saved.type, TxType.income);
    expect(saved.amountFen, 10000);
    expect(saved.recognitionMetadata.containsKey('aaSplit'), isFalse);
  });

  testWidgets('switching the purpose to a transfer removes AA', (tester) async {
    final controller = await _openEntry(tester, TestLedgerRepository());
    await _setSplit(tester);
    final purpose = find.byType(DropdownButtonFormField<String>);
    await tester.ensureVisible(purpose);
    await tester.tap(purpose);
    await tester.pumpAndSettle();
    await tester.tap(find.text('本人账户转移').last);
    await tester.pumpAndSettle();
    expect(_aaSwitch, findsNothing);
    await _save(tester);
    final saved = controller.transactions.single;
    expect(saved.effectiveKind, 'transfer');
    expect(saved.amountFen, 10000);
    expect(saved.recognitionMetadata.containsKey('aaSplit'), isFalse);
  });

  testWidgets(
    'AA requires a valid total count and at least one fen per person',
    (tester) async {
      final repository = TestLedgerRepository();
      await _openEntry(tester, repository);
      await _setSplit(tester, total: '0.01', count: '');
      for (final invalid in ['', '1']) {
        await tester.enterText(_participants, invalid);
        await _save(tester);
        expect(find.text('请输入包含自己在内的总人数，至少 2 人'), findsOneWidget);
      }
      await tester.enterText(_participants, '3');
      await _save(tester);
      expect(find.text('分摊后不足 0.01 元，请减少人数'), findsOneWidget);
      expect(
        repository.calls.where((call) => call.method == 'saveTransaction'),
        isEmpty,
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('AA form fits a narrow dark screen with enlarged text', (
    tester,
  ) async {
    await _openEntry(
      tester,
      TestLedgerRepository(theme: 'dark'),
      size: const Size(320, 740),
      textScale: 1.5,
      brightness: Brightness.dark,
    );
    await _setSplit(tester, total: '9999999.99', count: '3');
    await tester.ensureVisible(find.text('自己承担 ¥3,333,333.33'));
    await tester.pumpAndSettle();
    await app_test.saveScreenshot(tester, 'aa-entry-dark-large');
    expect(tester.takeException(), isNull);
    await _save(tester);
    expect(find.byType(EntryScreen), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('an offscreen invalid AA count cannot save the full payment', (
    tester,
  ) async {
    final repository = TestLedgerRepository();
    await _openEntry(tester, repository);
    await _setSplit(tester, count: '');
    await tester.drag(find.byType(ListView), const Offset(0, -1200));
    await tester.pumpAndSettle();
    await _save(tester);
    expect(
      repository.calls.where((call) => call.method == 'saveTransaction'),
      isEmpty,
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('ledger rows label AA and show only the personal expense', (
    tester,
  ) async {
    final repository = TestLedgerRepository();
    final transaction = _storedSplit(repository);
    await tester.pumpWidget(
      MaterialApp(
        theme: pennyTheme(Brightness.light),
        home: Scaffold(
          body: TransactionRow(transaction: transaction, onTap: () {}),
        ),
      ),
    );
    expect(find.textContaining('AA · 4 人'), findsOneWidget);
    expect(find.text('−¥25.00'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await repository.updates.close();
  });
}

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

  for (final compact in [false, true]) {
    testWidgets(
      'captured payment details remain readable and survive editing $compact',
      (tester) async {
        final repository = TestLedgerRepository();
        final details = {
          'merchantName': '测试门店（中心广场店）',
          'payeeName': '测试餐饮管理有限公司',
          'productDescription': '双人午餐套餐：主食两份、饮品两杯\n少冰，不加糖',
          'remark': '',
          'paymentChannel': '微信支付',
          'fundingAccount': '测试银行储蓄卡（1234）',
        };
        final transaction = _storedSplit(
          repository,
        ).copyWith(metadataJson: jsonEncode({'receiptDetails': details}));
        final controller = await _openEntry(
          tester,
          repository,
          transaction: transaction,
          size: compact ? const Size(320, 700) : const Size(390, 844),
          textScale: compact ? 1.6 : 1,
          brightness: compact ? Brightness.dark : Brightness.light,
        );
        await tester.drag(find.byType(ListView), const Offset(0, -2200));
        await tester.pumpAndSettle();
        expect(find.text('支付详情'), findsOneWidget);
        expect(find.text('收款对象'), findsOneWidget);
        expect(find.text(details['productDescription']!), findsOneWidget);
        expect(find.text('付款备注'), findsNothing);
        expect(find.text('识别依据与待核对信息'), findsNothing);
        expect(tester.takeException(), isNull);
        await app_test.saveScreenshot(
          tester,
          compact ? 'receipt-details-compact-dark' : 'receipt-details-light',
        );
        await _save(tester, editing: true);
        expect(
          controller.transactions.single.recognitionMetadata['receiptDetails'],
          details,
        );
        expect(tester.takeException(), isNull);
      },
    );

    testWidgets(
      'payment context keeps distinct roles and products and survives editing $compact',
      (tester) async {
        final repository = TestLedgerRepository();
        final details = {
          'merchantName': '青禾茶饮（虚构品牌）',
          'payeeName': '青禾餐饮有限公司（虚构）',
          'productDescription': '热茶一杯\n奶茶两杯',
        };
        final longSpecification = List.filled(
          12,
          '虚构套餐规格：大杯、少冰、无糖；茶饮和配料分别包装。',
        ).join('\n');
        final receiptContext = [
          {
            'label': '商家名称',
            'value': details['merchantName'],
            'source': '页面·商户节点',
          },
          {'label': '商品信息', 'value': '热茶一杯  \n奶茶两杯', 'source': '页面·商品说明'},
          {
            'label': '品牌',
            'value': details['merchantName'],
            'source': '页面·品牌名称',
          },
          {
            'label': '品牌',
            'value': details['merchantName'],
            'source': '本地OCR·品牌名称',
          },
          {'label': '门店', 'value': '青禾茶饮（虚构东街店）', 'source': '页面·门店名称'},
          {'label': '商品', 'value': '虚构红茶 × 1', 'source': '页面·商品行'},
          {'label': '商品', 'value': '虚构奶茶 × 2', 'source': '页面·商品行'},
          {'label': '规格', 'value': longSpecification, 'source': '页面·规格行'},
          {'label': '用途', 'value': '虚构样例：团队午餐饮品', 'source': '页面·用途'},
        ];
        final metadata = {
          'receiptDetails': details,
          'receiptContext': receiptContext,
          'captureVersion': 'synthetic-context-test',
        };
        final transaction = _storedSplit(
          repository,
        ).copyWith(metadataJson: jsonEncode(metadata));
        final controller = await _openEntry(
          tester,
          repository,
          transaction: transaction,
          size: compact ? const Size(320, 700) : const Size(390, 844),
          textScale: compact ? 1.6 : 1,
          brightness: compact ? Brightness.dark : Brightness.light,
        );
        await tester.scrollUntilVisible(
          app_test.field('备注'),
          400,
          scrollable: find.byType(Scrollable).first,
        );
        await tester.enterText(app_test.field('备注'), '已核对虚构样例');
        await tester.scrollUntilVisible(
          find.text('品牌'),
          400,
          scrollable: find.byType(Scrollable).first,
        );
        await tester.pumpAndSettle();
        expect(find.text('支付详情'), findsOneWidget);
        expect(find.text('商家名称'), findsNothing);
        expect(find.text('商品信息'), findsNothing);
        expect(find.text('品牌'), findsOneWidget);
        expect(find.text(details['merchantName']!), findsNWidgets(2));
        expect(find.text('青禾茶饮（虚构东街店）'), findsOneWidget);
        expect(find.text('商品'), findsNWidgets(2));
        expect(find.text('虚构红茶 × 1'), findsOneWidget);
        expect(find.text('虚构奶茶 × 2'), findsOneWidget);
        expect(
          tester
              .widgetList<SelectableText>(find.byType(SelectableText))
              .map((widget) => widget.data),
          contains(longSpecification),
        );
        expect(tester.takeException(), isNull);
        await app_test.saveScreenshot(
          tester,
          compact ? 'receipt-context-compact-dark' : 'receipt-context-light',
        );
        await tester.ensureVisible(find.text('虚构样例：团队午餐饮品'));
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        await _save(tester, editing: true);
        expect(controller.transactions.single.note, '已核对虚构样例');
        expect(controller.transactions.single.recognitionMetadata, metadata);
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets(
    'payment context can stand alone and skips malformed display rows',
    (tester) async {
      final repository = TestLedgerRepository();
      final receiptContext = [
        {'label': '门店', 'value': '虚构山茶店', 'source': '页面·明确标签'},
        {'label': '规格', 'value': '大杯\n少冰', 'source': '本地OCR·明确标签'},
        {'label': '', 'value': '不应显示', 'source': '页面'},
        {'label': '无效字段', 'value': 123, 'source': '页面'},
        '无效记录',
      ];
      final transaction = _storedSplit(
        repository,
      ).copyWith(metadataJson: jsonEncode({'receiptContext': receiptContext}));
      final controller = await _openEntry(
        tester,
        repository,
        transaction: transaction,
        size: const Size(320, 700),
        textScale: 1.6,
      );
      await tester.drag(find.byType(ListView), const Offset(0, -2200));
      await tester.pumpAndSettle();
      expect(find.text('支付详情'), findsOneWidget);
      expect(find.text('虚构山茶店'), findsOneWidget);
      expect(find.text('大杯\n少冰'), findsOneWidget);
      expect(find.text('不应显示'), findsNothing);
      expect(find.text('无效字段'), findsNothing);
      expect(find.text('无效记录'), findsNothing);
      expect(tester.takeException(), isNull);
      await _save(tester, editing: true);
      expect(
        controller.transactions.single.recognitionMetadata['receiptContext'],
        receiptContext,
      );
    },
  );

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
    await tester.tap(find.text('资金转移').last);
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

import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/app.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/features/entry/entry_screen.dart';
import 'package:pennypilot/features/ledger/ledger_screen.dart';
import 'package:pennypilot/core/models.dart';

import 'test_ledger_repository.dart';

const screenshots = bool.fromEnvironment('PENNYPILOT_SCREENSHOTS');
final screenshotKey = GlobalKey();

Future<void> pumpApp(
  WidgetTester tester,
  TestLedgerRepository repository, {
  Size size = const Size(390, 844),
  double textScale = 1,
}) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = size;
  tester.platformDispatcher.textScaleFactorTestValue = textScale;
  addTearDown(() {
    tester.view.resetPhysicalSize();
    tester.view.resetDevicePixelRatio();
    tester.platformDispatcher.clearTextScaleFactorTestValue();
  });
  final controller = LedgerController(
    repository: repository,
    clock: () => DateTime(2026, 10, 23),
  );
  addTearDown(() async {
    controller.dispose();
    await repository.updates.close();
  });
  await tester.pumpWidget(
    RepaintBoundary(
      key: screenshotKey,
      child: PennyPilotApp(controller: controller),
    ),
  );
  await tester.pumpAndSettle();
}

Future<void> chooseTab(WidgetTester tester, String label) async {
  final navigation = find.byType(NavigationBar).evaluate().isNotEmpty
      ? find.byType(NavigationBar)
      : find.byType(NavigationRail);
  await tester.tap(
    find.descendant(of: navigation, matching: find.text(label)).last,
  );
  await tester.pumpAndSettle();
}

Finder field(String label) => find.byWidgetPredicate(
  (widget) => widget is TextField && widget.decoration?.labelText == label,
);

Future<void> saveScreenshot(WidgetTester tester, String name) async {
  if (!screenshots) return;
  await tester.pumpAndSettle();
  await tester.runAsync(() async {
    final boundary =
        screenshotKey.currentContext!.findRenderObject()!
            as RenderRepaintBoundary;
    final image = await boundary.toImage(pixelRatio: 2);
    final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
    final directory = Directory('build/test-artifacts');
    await directory.create(recursive: true);
    await File(
      '${directory.path}/$name.png',
    ).writeAsBytes(bytes!.buffer.asUint8List());
    image.dispose();
  });
  await tester.pump();
}

void main() {
  setUpAll(() async {
    if (!screenshots) return;
    final icons = FontLoader('MaterialIcons');
    icons.addFont(rootBundle.load('fonts/MaterialIcons-Regular.otf'));
    await icons.load();
    // Optional local visual review; functional tests never require a host font.
    final font = File('/System/Library/Fonts/Supplemental/Arial Unicode.ttf');
    if (await font.exists()) {
      final loader = FontLoader('Roboto');
      loader.addFont(
        Future.value(ByteData.sublistView(await font.readAsBytes())),
      );
      await loader.load();
    }
  });

  testWidgets(
    'empty ledger uses genuine empty state and no sample transactions',
    (tester) async {
      final repository = TestLedgerRepository();
      await pumpApp(tester, repository);
      expect(find.text('这个月还没有账单'), findsOneWidget);
      expect(find.text('记下第一笔'), findsOneWidget);
      expect(find.byType(TransactionRow), findsNothing);
      expect(find.text('测试午餐店'), findsNothing);
      expect(repository.calls, isEmpty);
      expect(tester.takeException(), isNull);
      await saveScreenshot(tester, 'ledger-empty');
    },
  );

  testWidgets(
    'unavailable ledger surfaces error and never substitutes sample data',
    (tester) async {
      final repository = TestLedgerRepository()
        ..readError = const LedgerException('当前平台尚未接入账本服务。');
      await pumpApp(tester, repository);
      expect(find.text('账本暂时无法打开'), findsOneWidget);
      expect(find.text('当前平台尚未接入账本服务。'), findsOneWidget);
      expect(find.byType(TransactionRow), findsNothing);
      expect(find.text('测试午餐店'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('search and status filters show matching live snapshot records', (
    tester,
  ) async {
    final repository = TestLedgerRepository(populated: true);
    await pumpApp(tester, repository, size: const Size(390, 1000));
    expect(find.text('1 笔账单等待确认'), findsOneWidget);
    final search = find.byWidgetPredicate(
      (widget) =>
          widget is TextField && widget.decoration?.hintText == '搜索商户、备注或分类',
    );
    await tester.enterText(search, '午餐');
    await tester.pumpAndSettle();
    expect(find.text('测试午餐店'), findsOneWidget);
    expect(find.text('测试地铁'), findsNothing);
    await tester.enterText(search, '不存在的商户');
    await tester.pumpAndSettle();
    expect(find.text('没有找到这笔账'), findsOneWidget);
    await tester.enterText(search, '');
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(ChoiceChip, '收入'));
    await tester.pumpAndSettle();
    expect(find.text('测试工资'), findsOneWidget);
    expect(find.text('测试午餐店'), findsNothing);
    await tester.tap(find.widgetWithText(ChoiceChip, '全部收支'));
    await tester.tap(find.widgetWithText(FilterChip, '已忽略'));
    await tester.pumpAndSettle();
    expect(find.text('测试重复通知'), findsOneWidget);
    expect(find.text('测试待确认商户'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'bottom navigation opens reports chat and settings without losing month',
    (tester) async {
      await pumpApp(tester, TestLedgerRepository(populated: true));
      await saveScreenshot(tester, 'ledger-light');
      await chooseTab(tester, '统计');
      expect(find.text('收支统计'), findsOneWidget);
      expect(find.text('3 笔已确认账单'), findsOneWidget);
      expect(find.text('¥40.00'), findsOneWidget);
      await saveScreenshot(tester, 'reports-light');
      await chooseTab(tester, '问账');
      expect(find.text('问问账本'), findsOneWidget);
      await chooseTab(tester, '设置');
      expect(find.text('每月预算'), findsOneWidget);
      await chooseTab(tester, '账本');
      expect(find.text('2026 年 10 月'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'manual entry rejects invalid amount then saves exact integer fen',
    (tester) async {
      final repository = TestLedgerRepository();
      await pumpApp(tester, repository);
      await tester.tap(find.widgetWithText(FloatingActionButton, '记一笔'));
      await tester.pumpAndSettle();
      expect(find.byType(EntryScreen), findsOneWidget);
      await saveScreenshot(tester, 'entry-light');
      await tester.enterText(field('金额'), '0');
      await tester.scrollUntilVisible(
        find.text('保存这笔账'),
        250,
        scrollable: find.byType(Scrollable).last,
      );
      await tester.tap(find.text('保存这笔账'));
      await tester.pumpAndSettle();
      expect(find.text('请输入 0.01 至 9,999,999.99 元，最多两位小数'), findsOneWidget);
      expect(
        repository.calls.where((call) => call.method == 'saveTransaction'),
        isEmpty,
      );
      await tester.ensureVisible(field('金额'));
      await tester.enterText(field('金额'), '28.50');
      await tester.enterText(field('商户'), '测试精确录入');
      await tester.scrollUntilVisible(
        find.text('保存这笔账'),
        250,
        scrollable: find.byType(Scrollable).last,
      );
      await tester.tap(find.text('保存这笔账'));
      await tester.pumpAndSettle();
      final call = repository.calls.singleWhere(
        (call) => call.method == 'saveTransaction',
      );
      expect(call.arguments['amount'], 2850);
      expect(call.arguments['revision'], 0);
      expect(call.arguments['categoryId'], 1);
      expect(find.byType(EntryScreen), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'pending navigation excludes captured amount from totals until confirmation',
    (tester) async {
      final repository = TestLedgerRepository(populated: true);
      await pumpApp(tester, repository);
      expect(find.text('¥40.00'), findsOneWidget);
      await tester.tap(find.text('1 笔账单等待确认'));
      await tester.pumpAndSettle();
      expect(find.text('待确认账单'), findsOneWidget);
      expect(find.text('测试待确认商户'), findsOneWidget);
      await tester.tap(find.text('确认入账'));
      await tester.pumpAndSettle();
      expect(find.text('都核对好了'), findsOneWidget);
      expect(repository.calls.single.method, 'setTransactionStatus');
      expect(
        repository.calls.single.arguments['status'],
        TxStatus.confirmed.wireValue,
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'restoring a ledger closes an open editor before it can save stale values',
    (tester) async {
      final repository = TestLedgerRepository();
      await pumpApp(tester, repository);
      await tester.tap(find.widgetWithText(FloatingActionButton, '记一笔'));
      await tester.pumpAndSettle();
      await tester.enterText(field('金额'), '28.50');
      repository.data = {...repository.data, 'revision': 1};
      repository.updates.add(const LedgerEvent('restored', 1));
      await tester.pumpAndSettle();
      expect(find.byType(EntryScreen), findsNothing);
      expect(
        repository.calls.where((call) => call.method == 'saveTransaction'),
        isEmpty,
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'restore replaces dirty category editor without triggering its back confirmation',
    (tester) async {
      final repository = TestLedgerRepository();
      await pumpApp(tester, repository);
      await chooseTab(tester, '设置');
      await tester.tap(find.text('分类管理'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FloatingActionButton, '新增分类'));
      await tester.pumpAndSettle();
      await tester.enterText(field('分类名称'), '恢复前未保存分类');
      await tester.pump();
      expect(
        find.byWidgetPredicate(
          (widget) => widget is PopScope && !widget.canPop,
        ),
        findsOneWidget,
      );

      repository.data = {...repository.data, 'revision': 1};
      repository.updates.add(const LedgerEvent('restored', 1));
      await tester.pumpAndSettle();
      expect(find.text('放弃未保存的修改？'), findsNothing);
      expect(find.text('恢复前未保存分类'), findsNothing);
      expect(find.byType(NavigationBar), findsOneWidget);
      expect(find.text('这个月还没有账单'), findsOneWidget);
      expect(
        repository.calls.where((call) => call.method == 'saveCategory'),
        isEmpty,
      );

      await chooseTab(tester, '统计');
      expect(find.text('收支统计'), findsOneWidget);
      await chooseTab(tester, '设置');
      await tester.tap(find.text('分类管理'));
      await tester.pumpAndSettle();
      expect(find.text('分类管理'), findsOneWidget);
      await tester.tap(find.byType(BackButton).first);
      await tester.pumpAndSettle();
      expect(find.byType(NavigationBar), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'restore discards dirty AI configuration and replacement root navigates normally',
    (tester) async {
      final repository = TestLedgerRepository();
      await pumpApp(tester, repository, size: const Size(390, 1000));
      await chooseTab(tester, '设置');
      await tester.ensureVisible(find.text('AI 服务'));
      await tester.tap(find.text('AI 服务'));
      await tester.pumpAndSettle();
      await tester.enterText(field('API Key'), 'test-key-not-secret');
      await tester.pump();
      expect(
        find.byWidgetPredicate(
          (widget) => widget is PopScope && !widget.canPop,
        ),
        findsOneWidget,
      );

      repository.data = {...repository.data, 'revision': 1};
      repository.updates.add(const LedgerEvent('restored', 1));
      await tester.pumpAndSettle();
      expect(find.text('放弃未保存的配置？'), findsNothing);
      expect(field('API Key'), findsNothing);
      expect(find.text('这个月还没有账单'), findsOneWidget);
      expect(
        repository.calls.where((call) => call.method == 'updateSettings'),
        isEmpty,
      );

      await tester.tap(find.widgetWithText(FloatingActionButton, '记一笔'));
      await tester.pumpAndSettle();
      expect(find.byType(EntryScreen), findsOneWidget);
      await tester.tap(find.byType(BackButton).first);
      await tester.pumpAndSettle();
      await chooseTab(tester, '问账');
      expect(find.text('问问账本'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  for (final width in [320.0, 375.0]) {
    testWidgets(
      'dark $width width with 1.5 text scale stays usable on ledger reports and entry',
      (tester) async {
        await pumpApp(
          tester,
          TestLedgerRepository(populated: true, theme: 'dark'),
          size: Size(width, 960),
          textScale: 1.5,
        );
        expect(
          Theme.of(tester.element(find.byType(LedgerScreen))).brightness,
          Brightness.dark,
        );
        expect(tester.takeException(), isNull);
        await saveScreenshot(tester, 'ledger-dark-${width.toInt()}');
        await chooseTab(tester, '统计');
        expect(tester.takeException(), isNull);
        await saveScreenshot(tester, 'reports-dark-${width.toInt()}');
        await chooseTab(tester, '账本');
        await tester.tap(find.widgetWithText(FloatingActionButton, '记一笔'));
        await tester.pumpAndSettle();
        expect(find.byType(EntryScreen), findsOneWidget);
        expect(tester.takeException(), isNull);
        await saveScreenshot(tester, 'entry-dark-${width.toInt()}');
      },
    );
  }

  testWidgets(
    'wide layout uses navigation rail and all destinations are reachable',
    (tester) async {
      await pumpApp(
        tester,
        TestLedgerRepository(populated: true),
        size: const Size(1280, 900),
        textScale: 1.5,
      );
      expect(find.byType(NavigationRail), findsOneWidget);
      expect(find.byType(NavigationBar), findsNothing);
      await chooseTab(tester, '统计');
      expect(find.text('收支统计'), findsOneWidget);
      await chooseTab(tester, '问账');
      expect(find.text('问问账本'), findsOneWidget);
      await chooseTab(tester, '设置');
      expect(find.text('每月预算'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
}

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

Finder ledgerSearch() => find.byWidgetPredicate(
  (widget) =>
      widget is TextField && widget.decoration?.hintText == '搜索商户、备注或分类',
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
    final directory = Directory('build/test-artifacts');
    await directory.create(recursive: true);
    await File('${directory.path}/UI_SCREENSHOTS.md').writeAsString('''
# UI 截图说明

本目录中的本轮组件截图由 `test/ui/app_widget_test.dart` 生成。
账单、金额、分类和问答全部来自 `TestLedgerRepository` 合成测试数据，
并非用户真实账本、真实 AI 回答或真机运行结果。

批量验证：
`./tool/flutterw test test/ui/app_widget_test.dart --dart-define=PENNYPILOT_SCREENSHOTS=true`

矩阵文件名包含主题、逻辑宽度和文字倍率；图片以 2 倍像素输出。
覆盖账本、搜索展开、统计、问账、设置、手动录入和一句话录入。
''');
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

  testWidgets('custom category icon stays consistent in ledger and entry', (
    tester,
  ) async {
    final repository = TestLedgerRepository(populated: true);
    final categories = repository.data['categories'] as List;
    (categories.first as Map<String, dynamic>)['icon'] = 'gift';
    await pumpApp(tester, repository);
    expect(find.byIcon(Icons.redeem_outlined), findsWidgets);
    await tester.tap(find.byType(FloatingActionButton));
    await tester.pumpAndSettle();
    expect(find.byIcon(Icons.redeem_outlined), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('unset budget keeps a 48dp tap target', (tester) async {
    await pumpApp(tester, TestLedgerRepository());
    final budget = find
        .ancestor(of: find.text('设置月预算'), matching: find.byType(InkWell))
        .first;
    expect(tester.getSize(budget).height, greaterThanOrEqualTo(48));
    await tester.tap(find.text('设置月预算'));
    await tester.pumpAndSettle();
    expect(find.byType(AlertDialog), findsOneWidget);
    expect(tester.takeException(), isNull);
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
    expect(ledgerSearch(), findsNothing);
    await tester.tap(find.byTooltip('搜索账单'));
    await tester.pumpAndSettle();
    final search = ledgerSearch();
    await tester.enterText(search, '午餐');
    await tester.pumpAndSettle();
    expect(find.text('测试午餐店'), findsOneWidget);
    expect(find.text('测试地铁'), findsNothing);
    await tester.tap(find.byTooltip('收起搜索'));
    await tester.pumpAndSettle();
    expect(search, findsNothing);
    expect(find.text('测试午餐店'), findsOneWidget);
    expect(find.text('测试地铁'), findsNothing);
    await chooseTab(tester, '统计');
    await chooseTab(tester, '账本');
    await tester.tap(find.byTooltip('搜索账单'));
    await tester.pumpAndSettle();
    expect(tester.widget<TextField>(search).controller?.text, '午餐');
    await tester.enterText(search, '不存在的商户');
    await tester.pumpAndSettle();
    expect(find.text('没有找到这笔账'), findsOneWidget);
    await tester.tap(find.byTooltip('清空搜索'));
    await tester.pumpAndSettle();
    expect(tester.widget<TextField>(search).controller?.text, isEmpty);
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
      await saveScreenshot(tester, 'chat-light');
      await chooseTab(tester, '设置');
      expect(find.text('每月预算'), findsOneWidget);
      await saveScreenshot(tester, 'settings-light');
      await chooseTab(tester, '账本');
      expect(find.text('2026 年 10 月'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'chat sends typed questions and preserves messages until clearing is confirmed',
    (tester) async {
      final repository = TestLedgerRepository(populated: true);
      await pumpApp(tester, repository);
      await chooseTab(tester, '问账');
      final input = find.byWidgetPredicate(
        (widget) =>
            widget is TextField &&
            widget.decoration?.hintText == '查询、分析，或告诉我想完成的记账任务',
      );
      await tester.enterText(input, '本月已确认支出是多少？');
      await tester.pump();
      await tester.tap(find.byTooltip('发送问题'));
      await tester.pumpAndSettle();
      expect(find.text('本月已确认支出是多少？'), findsOneWidget);
      expect(find.text('测试账本已确认支出 40 元。'), findsOneWidget);
      final call = repository.calls.singleWhere((call) => call.method == 'ask');
      expect(call.arguments['question'], '本月已确认支出是多少？');
      expect(call.arguments['revision'], 0);
      await saveScreenshot(tester, 'chat-response-light');

      await chooseTab(tester, '账本');
      await chooseTab(tester, '问账');
      expect(find.text('测试账本已确认支出 40 元。'), findsOneWidget);
      await tester.tap(find.byTooltip('清空本次对话'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('取消'));
      await tester.pumpAndSettle();
      expect(find.text('测试账本已确认支出 40 元。'), findsOneWidget);
      await tester.tap(find.byTooltip('清空本次对话'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, '清空'));
      await tester.pumpAndSettle();
      expect(find.text('测试账本已确认支出 40 元。'), findsNothing);
      expect(repository.data['transactions'], hasLength(5));
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

  for (final scenario in [
    (width: 320.0, theme: 'light', scale: 1.5),
    (width: 320.0, theme: 'dark', scale: 1.5),
    (width: 390.0, theme: 'light', scale: 1.0),
    (width: 390.0, theme: 'dark', scale: 1.5),
  ]) {
    final suffix =
        '${scenario.theme}-${scenario.width.toInt()}-text-${scenario.scale}';
    testWidgets('$suffix keeps all destinations and both entry modes usable', (
      tester,
    ) async {
      await pumpApp(
        tester,
        TestLedgerRepository(populated: true, theme: scenario.theme),
        size: Size(scenario.width, 844),
        textScale: scenario.scale,
      );
      expect(
        Theme.of(tester.element(find.byType(LedgerScreen))).brightness,
        scenario.theme == 'dark' ? Brightness.dark : Brightness.light,
      );
      expect(tester.takeException(), isNull);
      await saveScreenshot(tester, 'ledger-$suffix');
      await tester.tap(find.byTooltip('搜索账单'));
      await tester.pumpAndSettle();
      expect(ledgerSearch(), findsOneWidget);
      expect(tester.takeException(), isNull);
      await saveScreenshot(tester, 'ledger-search-$suffix');
      await tester.tap(find.byTooltip('收起搜索'));
      await tester.pumpAndSettle();
      await chooseTab(tester, '统计');
      expect(find.text('收支统计'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await saveScreenshot(tester, 'reports-$suffix');
      await chooseTab(tester, '问账');
      expect(find.byTooltip('发送问题'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await saveScreenshot(tester, 'chat-$suffix');
      await chooseTab(tester, '设置');
      expect(find.text('每月预算'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await saveScreenshot(tester, 'settings-$suffix');
      await chooseTab(tester, '账本');
      await tester.tap(find.widgetWithText(FloatingActionButton, '记一笔'));
      await tester.pumpAndSettle();
      expect(find.byType(EntryScreen), findsOneWidget);
      expect(tester.takeException(), isNull);
      await saveScreenshot(tester, 'entry-$suffix');
      await tester.tap(find.text('一句话'));
      await tester.pumpAndSettle();
      expect(find.text('识别账单'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await saveScreenshot(tester, 'entry-ai-$suffix');
    });
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

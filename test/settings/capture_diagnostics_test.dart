import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/features/settings/capture_settings_screen.dart';
import 'package:pennypilot/features/settings/settings_widgets.dart';
import 'package:pennypilot/ui/theme.dart';

import '../ui/test_ledger_repository.dart';

Future<TestLedgerRepository> showCapture(
  WidgetTester tester, {
  bool enabled = false,
  bool granted = true,
  bool connected = true,
  String status = 'waiting',
  Object? readDiagnostics,
  Object? readHistory,
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
  final repository = TestLedgerRepository();
  (repository.data['settings'] as Map)['screenCaptureEnabled'] = enabled;
  (repository.data['capabilities'] as Map<String, dynamic>).addAll({
    'accessibilityGranted': granted,
    'accessibilityServiceConnected': connected,
    'screenCaptureStatus': status,
    'lastScreenEventTime': DateTime(2026, 10, 24, 10).millisecondsSinceEpoch,
    'lastScreenReadTime': DateTime(2026, 10, 24, 10).millisecondsSinceEpoch,
    'lastScreenPackage': 'com.tencent.mm',
    'screenReadDiagnostics': ?readDiagnostics,
    'screenReadHistory': ?readHistory,
  });
  final controller = LedgerController(repository: repository);
  await controller.init();
  addTearDown(() async {
    controller.dispose();
    await repository.updates.close();
  });
  await tester.pumpWidget(
    MaterialApp(
      theme: pennyTheme(brightness),
      home: CaptureSettingsScreen(controller: controller),
    ),
  );
  await tester.pumpAndSettle();
  return repository;
}

void main() {
  testWidgets(
    'system authorization alone does not claim screen capture ready',
    (tester) async {
      final repository = await showCapture(tester);
      expect(find.text('页面采集尚未开启'), findsOneWidget);
      expect(find.textContaining('系统已授权，但应用内开关仍关闭'), findsOneWidget);
      expect(find.text('页面采集已就绪'), findsNothing);
      await tester.tap(find.text('页面采集尚未开启'));
      await tester.pumpAndSettle();
      expect(find.text('开启支付页面读取？'), findsOneWidget);
      expect(repository.calls, isEmpty);
      await tester.tap(find.text('取消'));
      await tester.pumpAndSettle();
      expect(repository.calls, isEmpty);
    },
  );

  testWidgets(
    'already authorized enable saves app switch without requesting redundant permission',
    (tester) async {
      final repository = await showCapture(tester);
      await tester.tap(find.text('页面采集尚未开启'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('同意并开启'));
      await tester.pumpAndSettle();
      expect(repository.calls.single.method, 'updateSettings');
      expect(repository.calls.single.arguments, {'screenCaptureEnabled': true});
      expect(find.text('页面采集已就绪'), findsOneWidget);
    },
  );

  testWidgets(
    'granted permission with disconnected service requires recovery',
    (tester) async {
      await showCapture(tester, enabled: true, connected: false);
      expect(find.text('已授权，服务尚未连接'), findsOneWidget);
      expect(find.text('页面采集已就绪'), findsNothing);
    },
  );

  testWidgets('app switch without system permission is not ready', (
    tester,
  ) async {
    await showCapture(tester, enabled: true, granted: false, connected: false);
    expect(find.text('等待无障碍授权'), findsOneWidget);
    expect(find.text('页面采集已就绪'), findsNothing);
  });

  testWidgets(
    'saved receipt diagnostics explain pending records without reminders',
    (tester) async {
      await showCapture(tester, enabled: true, status: 'saved');
      final target = find.text('已保存识别结果');
      await tester.scrollUntilVisible(target, 400);
      expect(target, findsOneWidget);
      expect(find.textContaining('即使关闭记账提醒'), findsOneWidget);
      expect(find.text('微信'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  for (final scenario in [
    (
      'input_page',
      '已跳过含输入框的页面',
      '页面包含可见输入框，为避免误读聊天或待支付内容已跳过。请在本次支付完成页停留约 5 秒后再查看诊断；仍无法识别时可手动或语音补记。',
    ),
    (
      'password_page',
      '已跳过含密码控件的页面',
      '页面包含可见密码控件，已停止读取。请完成支付后在本次结果页停留约 5 秒，再回到流水核对。',
    ),
    (
      'incomplete_tree',
      '页面内容暂未读取完整',
      '本次读取缺少部分页面节点，尚不能判断支付结果。请在本次支付完成页停留约 5 秒，让服务重新读取；仍无记录时可手动或语音补记。',
    ),
    (
      'traversal_limit',
      '页面结构超出读取范围',
      '页面节点数量或层级已达到读取上限，本次未用于记账。请在本次支付完成页停留后查看诊断；仍无法识别时可手动或语音补记。',
    ),
    (
      'text_limit',
      '页面文字超出读取上限',
      '本次页面文字超过读取上限，未用于记账。请查看本次支付完成页的诊断；仍无法识别时可手动或语音补记。',
    ),
    (
      'empty_page',
      '未读到可见页面文字',
      '当前页面没有可读取的可见文字。请在本次支付完成页停留约 5 秒后再查看诊断；仍无记录时可手动或语音补记。',
    ),
    (
      'visual_required',
      '此页面需要本地截图补充',
      '微信仅提供了空节点。请在“识别与录入”中开启“本地支付截图补充”，再打开这张成功页；图片只在本机处理。',
    ),
    (
      'visual_not_receipt',
      '截图未匹配微信转账成功页',
      '未同时匹配支付成功、待收款人确认、有效金额和完成按钮。未生成账目，可继续停留或主动补录。',
    ),
  ]) {
    testWidgets('${scenario.$1} explains the cause and recovery', (
      tester,
    ) async {
      await showCapture(tester, enabled: true, status: scenario.$1);
      await tester.scrollUntilVisible(find.text(scenario.$2), 400);
      expect(find.text(scenario.$2), findsOneWidget);
      expect(find.text(scenario.$3), findsOneWidget);
      expect(find.text('已跳过输入或受保护页面'), findsNothing);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('node diagnostics show counts without page contents', (
    tester,
  ) async {
    await showCapture(
      tester,
      enabled: true,
      status: 'incomplete_tree',
      readDiagnostics: {
        'visitedNodes': 42,
        'textNodes': 8,
        'editableNodes': 1,
        'passwordNodes': 2,
        'missingChildren': 3,
        'hiddenNodes': 4,
        'foreignNodes': 5,
        'text': '不应展示的页面文字',
      },
    );
    await tester.scrollUntilVisible(find.text('页面节点摘要'), 400);
    expect(
      find.text(
        '检查 42 个节点 · 可读文字 8 处 · 输入框 1 处 · 密码控件 2 处 · 缺失子节点 3 个 · 跳过隐藏节点 4 个 · 跳过其他应用节点 5 个',
      ),
      findsOneWidget,
    );
    expect(find.textContaining('不应展示的页面文字'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'legacy snapshots retain protected status without invented counts',
    (tester) async {
      await showCapture(tester, enabled: true, status: 'protected');
      await tester.scrollUntilVisible(find.text('已跳过输入或受保护页面'), 400);
      expect(find.text('输入框、密码或不完整页面不会用于记账，避免误读聊天或待支付内容。'), findsOneWidget);
      expect(find.text('页面节点摘要'), findsNothing);
      expect(find.textContaining('检查 0 个节点'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('partial diagnostics show only available nonzero detail counts', (
    tester,
  ) async {
    await showCapture(
      tester,
      enabled: true,
      status: 'incomplete_tree',
      readDiagnostics: {
        'textNodes': 8,
        'editableNodes': 0,
        'passwordNodes': 0,
        'missingChildren': 2,
      },
    );
    await tester.scrollUntilVisible(find.text('页面节点摘要'), 400);
    expect(find.text('可读文字 8 处 · 缺失子节点 2 个'), findsOneWidget);
    expect(find.textContaining('检查 0 个节点'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  test('diagnostic summary ignores invalid and absent counts', () {
    for (final diagnostics in [
      null,
      'invalid',
      <String, dynamic>{},
      {'visitedNodes': '42', 'textNodes': -1, 'editableNodes': 1.5},
    ]) {
      expect(screenReadDiagnosticsSummary(diagnostics), isNull);
    }
    expect(
      screenReadDiagnosticsSummary({
        'visitedNodes': 1,
        'textNodes': 0,
        'editableNodes': 0,
        'missingChildren': 0,
      }),
      '检查 1 个节点 · 可读文字 0 处',
    );
  });

  for (final outcome in [
    ('saved', '已保存识别结果'),
    ('not_receipt', '未识别到完整支付凭证'),
  ]) {
    testWidgets('history retains ${outcome.$1} after returning to chat', (
      tester,
    ) async {
      await showCapture(
        tester,
        enabled: true,
        status: 'input_page',
        readHistory: [
          {
            'status': outcome.$1,
            'timestamp': DateTime(
              2026,
              10,
              24,
              10,
              0,
              12,
            ).millisecondsSinceEpoch,
            'packageName': 'com.tencent.mm',
            'diagnostics': {'visitedNodes': 42, 'textNodes': 8},
          },
          {
            'status': 'input_page',
            'timestamp': DateTime(
              2026,
              10,
              24,
              10,
              0,
              15,
            ).millisecondsSinceEpoch,
            'packageName': 'com.tencent.mm',
            'diagnostics': {'visitedNodes': 10, 'editableNodes': 1},
          },
        ],
      );
      await tester.scrollUntilVisible(find.text('最近识别过程'), 400);
      final newest = tester.widget<SettingsRow>(
        find.byKey(const ValueKey('screen-read-history-0')),
      );
      final previous = tester.widget<SettingsRow>(
        find.byKey(const ValueKey('screen-read-history-1')),
      );
      expect(newest.title, '已跳过含输入框的页面');
      expect(
        newest.subtitle,
        '2026年10月24日 10:00:15 · 微信 · 检查 10 个节点 · 输入框 1 处',
      );
      expect(previous.title, outcome.$2);
      expect(
        previous.subtitle,
        '2026年10月24日 10:00:12 · 微信 · 检查 42 个节点 · 可读文字 8 处',
      );
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('history shows the eight most recent valid records', (
    tester,
  ) async {
    await showCapture(
      tester,
      enabled: true,
      readHistory: [
        for (final second in [4, 1, 8, 3, 9, 0, 5, 7, 6, 2])
          {
            'status': 'not_receipt',
            'timestamp': DateTime(
              2026,
              10,
              24,
              10,
              0,
              second,
            ).millisecondsSinceEpoch,
          },
      ],
    );
    await tester.scrollUntilVisible(find.text('最近识别过程'), 400);
    for (var index = 0; index < 8; index++) {
      final row = tester.widget<SettingsRow>(
        find.byKey(ValueKey('screen-read-history-$index')),
      );
      expect(row.subtitle, '2026年10月24日 10:00:0${9 - index}');
    }
    expect(find.byKey(const ValueKey('screen-read-history-8')), findsNothing);
    expect(tester.takeException(), isNull);
  });

  for (final scenario in [
    ('legacy', null),
    ('empty', <Object?>[]),
    ('invalid shape', '不应展示的历史正文'),
  ]) {
    testWidgets('${scenario.$1} history does not render a history section', (
      tester,
    ) async {
      await showCapture(tester, readHistory: scenario.$2);
      await tester.scrollUntilVisible(find.text('页面识别诊断'), 400);
      expect(find.text('最近识别过程'), findsNothing);
      expect(find.textContaining('不应展示的历史正文'), findsNothing);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('history drops invalid entries and never displays extra text', (
    tester,
  ) async {
    final timestamp = DateTime(2026, 10, 24, 10, 0, 15).millisecondsSinceEpoch;
    await showCapture(
      tester,
      readHistory: [
        null,
        '不应展示的历史正文',
        {'status': '不应展示的未知状态', 'timestamp': timestamp},
        {'status': 'saved', 'timestamp': '不应展示的时间'},
        {'status': 'saved', 'timestamp': -1},
        {'status': 'saved', 'timestamp': 8640000000000001},
        {
          'status': 'saved',
          'timestamp': timestamp,
          'packageName': '不应展示的未知包名',
          'text': '不应展示的页面正文',
          'diagnostics': {'visitedNodes': 7, 'text': '不应展示的诊断正文'},
        },
      ],
    );
    await tester.scrollUntilVisible(find.text('最近识别过程'), 400);
    final row = tester.widget<SettingsRow>(
      find.byKey(const ValueKey('screen-read-history-0')),
    );
    expect(row.title, '已保存识别结果');
    expect(row.subtitle, '2026年10月24日 10:00:15 · 检查 7 个节点');
    expect(find.byKey(const ValueKey('screen-read-history-1')), findsNothing);
    expect(find.textContaining('不应展示'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  for (final scenario in [
    (const Size(320, 640), 2.0, Brightness.light),
    (const Size(844, 390), 1.0, Brightness.dark),
    (const Size(1024, 768), 1.3, Brightness.dark),
  ]) {
    testWidgets(
      'capture settings stays readable at ${scenario.$1} scale ${scenario.$2}',
      (tester) async {
        await showCapture(
          tester,
          enabled: true,
          status: 'incomplete_tree',
          readDiagnostics: {
            'visitedNodes': 12345,
            'textNodes': 12345,
            'editableNodes': 1,
            'passwordNodes': 1,
            'missingChildren': 2,
            'hiddenNodes': 3,
            'foreignNodes': 4,
          },
          readHistory: [
            {
              'status': 'saved',
              'timestamp': DateTime(
                2026,
                10,
                24,
                10,
                0,
                12,
              ).millisecondsSinceEpoch,
              'packageName': 'com.tencent.mm',
              'diagnostics': {
                'visitedNodes': 12345,
                'textNodes': 12345,
                'hiddenNodes': 12345,
              },
            },
          ],
          size: scenario.$1,
          textScale: scenario.$2,
          brightness: scenario.$3,
        );
        expect(tester.takeException(), isNull);
        final scrollStep = tester.getSize(find.byType(Scrollable)).height / 2;
        Future<void> reveal(Finder target) async {
          await tester.scrollUntilVisible(target, scrollStep);
          // ensureVisible may jump without laying out the next lazy section.
          await tester.pumpAndSettle();
          expect(target, findsOneWidget);
          expect(target.hitTestable(), findsOneWidget);
          expect(tester.takeException(), isNull);
        }

        await reveal(find.text('页面内容暂未读取完整'));
        await reveal(find.text('页面节点摘要'));
        await reveal(find.byKey(const ValueKey('screen-read-history-0')));
        await reveal(find.text('今日捕获 0 笔'));
      },
    );
  }
}

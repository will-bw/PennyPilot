import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/features/settings/capture_settings_screen.dart';
import 'package:pennypilot/ui/theme.dart';

import '../ui/test_ledger_repository.dart';

Future<TestLedgerRepository> showCapture(
  WidgetTester tester, {
  bool enabled = false,
  bool granted = true,
  bool connected = true,
  String status = 'waiting',
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
      final target = find.text('已生成待确认账单');
      await tester.scrollUntilVisible(target, 400);
      expect(target, findsOneWidget);
      expect(find.textContaining('即使关闭记账提醒'), findsOneWidget);
      expect(find.text('微信'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

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
          status: 'unreadable',
          size: scenario.$1,
          textScale: scenario.$2,
          brightness: scenario.$3,
        );
        expect(tester.takeException(), isNull);
        await tester.scrollUntilVisible(find.text('页面暂时无法读取'), 300);
        expect(tester.takeException(), isNull);
        await tester.scrollUntilVisible(find.text('今日捕获 0 笔'), 300);
        expect(tester.takeException(), isNull);
      },
    );
  }
}

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/features/settings/capture_apps_screen.dart';
import '../ui/test_ledger_repository.dart';

void main() {
  testWidgets(
    'application switches preserve selection and allow disabling every app',
    (tester) async {
      final repository = TestLedgerRepository();
      (repository.data['settings'] as Map)['captureAppPackages'] = [
        'com.tencent.mm',
      ];
      (repository.data['capabilities'] as Map)['captureApps'] = [
        {
          'packageName': 'com.tencent.mm',
          'name': '微信',
          'family': 'WALLET',
          'limitedByPlatform': false,
        },
        {
          'packageName': 'com.taobao.taobao',
          'name': '淘宝',
          'family': 'SHOPPING',
          'limitedByPlatform': true,
        },
      ];
      final controller = LedgerController(repository: repository);
      await controller.init();
      await tester.pumpWidget(
        MaterialApp(home: CaptureAppsScreen(controller: controller)),
      );
      await tester.pumpAndSettle();
      expect(find.textContaining('部分页面受应用限制'), findsOneWidget);
      await tester.tap(find.widgetWithText(SwitchListTile, '微信'));
      await tester.pumpAndSettle();
      expect(controller.settings['captureAppPackages'], isEmpty);
      expect(find.textContaining('当前未选择采集应用'), findsOneWidget);
      await tester.tap(find.widgetWithText(SwitchListTile, '淘宝'));
      await tester.pumpAndSettle();
      expect(controller.settings['captureAppPackages'], ['com.taobao.taobao']);
      expect(
        repository.calls
            .where((call) => call.method == 'updateSettings')
            .last
            .arguments,
        {
          'captureAppPackages': ['com.taobao.taobao'],
        },
      );
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      controller.dispose();
    },
  );
}

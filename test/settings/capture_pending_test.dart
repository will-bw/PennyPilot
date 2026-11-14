import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/features/settings/capture_pending_screen.dart';
import 'package:pennypilot/ui/theme.dart';

import '../ui/test_ledger_repository.dart';

class _PendingRepository extends TestLedgerRepository {
  final items = <Map<String, dynamic>>[
    {
      'id': 'receipt-event',
      'version': 3,
      'appName': '微信',
      'state': 'NEEDS_ATTENTION',
      'reason': 'NETWORK',
      'hasImage': false,
      'observedAt': DateTime(2026, 11, 14, 10).millisecondsSinceEpoch,
      'expiresAt': DateTime(2026, 11, 21, 10).millisecondsSinceEpoch,
    },
  ];
  bool failRetry = false;

  @override
  Future<Object?> invoke(String method, Map<String, dynamic> arguments) async {
    switch (method) {
      case 'capturePendingList':
        calls.add((method: method, arguments: arguments));
        return {
          'items': items.map((item) => {...item}).toList(),
          'retentionDays': 7,
        };
      case 'capturePendingDetail':
        calls.add((method: method, arguments: arguments));
        return {'text': '转账成功\n¥18.00\n待对方收款', 'images': <String>[]};
      case 'capturePendingRetry':
        calls.add((method: method, arguments: arguments));
        if (failRetry) throw const LedgerException('AI 配置已改变，请重新采集。');
        items.single['state'] = 'QUEUED';
        items.single['reason'] = null;
        items.single['version'] = 4;
        return null;
      case 'capturePendingDelete':
        calls.add((method: method, arguments: arguments));
        items.clear();
        return null;
      default:
        return super.invoke(method, arguments);
    }
  }
}

Future<_PendingRepository> _show(
  WidgetTester tester, {
  bool queued = false,
  bool failRetry = false,
  bool largeText = false,
}) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(375, 812);
  tester.platformDispatcher.textScaleFactorTestValue = largeText ? 1.8 : 1;
  addTearDown(() {
    tester.view.resetPhysicalSize();
    tester.view.resetDevicePixelRatio();
    tester.platformDispatcher.clearTextScaleFactorTestValue();
  });
  final repository = _PendingRepository()..failRetry = failRetry;
  if (queued) {
    repository.items.single['state'] = 'QUEUED';
    repository.items.single['reason'] = null;
  }
  final controller = LedgerController(repository: repository);
  await controller.init();
  addTearDown(() async {
    controller.dispose();
    await repository.updates.close();
  });
  await tester.pumpWidget(
    MaterialApp(
      theme: pennyTheme(largeText ? Brightness.dark : Brightness.light),
      home: CapturePendingScreen(controller: controller),
    ),
  );
  await tester.pumpAndSettle();
  return repository;
}

void main() {
  testWidgets(
    'list loads only metadata then explicit detail and retry preserve identity',
    (tester) async {
      final repository = await _show(tester);
      expect(find.text('微信 · 需要处理'), findsOneWidget);
      expect(find.textContaining('本机加密保存'), findsOneWidget);
      expect(
        repository.calls.where((call) => call.method == 'capturePendingDetail'),
        isEmpty,
      );
      await tester.tap(find.text('微信 · 需要处理'));
      await tester.pumpAndSettle();
      expect(find.textContaining('¥18.00'), findsOneWidget);
      await tester.tap(find.text('重新识别'));
      await tester.pumpAndSettle();
      final retry = repository.calls.singleWhere(
        (call) => call.method == 'capturePendingRetry',
      );
      expect(retry.arguments['taskId'], 'receipt-event');
      expect(retry.arguments['version'], 3);
      expect(retry.arguments.containsKey('revision'), isTrue);
      expect(find.text('微信 · 等待识别'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('failed retry keeps the receipt and reports actionable error', (
    tester,
  ) async {
    final repository = await _show(tester, failRetry: true);
    await tester.tap(find.text('微信 · 需要处理'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('重新识别'));
    await tester.pumpAndSettle();
    expect(find.text('核对凭证'), findsOneWidget);
    expect(find.text('AI 配置已改变，请重新采集。'), findsOneWidget);
    expect(repository.items, hasLength(1));
    expect(find.text('重新识别'), findsOneWidget);
  });

  testWidgets(
    'queued receipt cannot be retried or manually duplicated and can be removed',
    (tester) async {
      final repository = await _show(tester, queued: true);
      await tester.tap(find.text('微信 · 等待识别'));
      await tester.pumpAndSettle();
      expect(find.text('重新识别'), findsNothing);
      expect(find.text('按凭证手动补录'), findsNothing);
      await tester.tap(find.text('移除此凭证'));
      await tester.pumpAndSettle();
      expect(repository.items, isEmpty);
      expect(find.textContaining('没有待处理的凭证'), findsOneWidget);
    },
  );

  testWidgets(
    'small phone and large text keep recovery controls reachable in dark mode',
    (tester) async {
      await _show(tester, largeText: true);
      await tester.ensureVisible(find.text('微信 · 需要处理'));
      await tester.tap(find.text('微信 · 需要处理'));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.text('按凭证手动补录'),
        300,
        scrollable: find
            .descendant(
              of: find.byType(ListView),
              matching: find.byType(Scrollable),
            )
            .first,
      );
      expect(find.text('按凭证手动补录'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
}

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/app.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/features/settings/permission_setup_screen.dart';
import 'package:pennypilot/ui/theme.dart';

import '../ui/test_ledger_repository.dart';

class _Repository extends TestLedgerRepository {
  bool microphoneAllowed = false, reminderAllowed = false, failSettings = false;
  Completer<void>? permissionPending;

  void capabilities(Map<String, dynamic> values) {
    data['capabilities'] = {...data['capabilities'] as Map, ...values};
  }

  @override
  Future<Object?> invoke(String method, Map<String, dynamic> arguments) async {
    if (method == 'updateSettings' && failSettings) {
      throw const LedgerException('保存失败，请重试。');
    }
    if (method == 'openSystemSettings' ||
        method == 'requestMicrophonePermission' ||
        method == 'requestNotificationPermission' ||
        method == 'rebindNotificationService') {
      calls.add((method: method, arguments: arguments));
      if (method == 'requestMicrophonePermission') {
        await permissionPending?.future;
        capabilities({'microphoneGranted': microphoneAllowed});
        return {'granted': microphoneAllowed};
      }
      if (method == 'requestNotificationPermission') {
        capabilities({'remindersEnabled': reminderAllowed});
        return {'granted': reminderAllowed};
      }
      return null;
    }
    return super.invoke(method, arguments);
  }
}

Future<LedgerController> _controller(_Repository repository) async {
  final controller = LedgerController(repository: repository);
  await controller.init();
  addTearDown(() async {
    controller.dispose();
    await repository.updates.close();
  });
  return controller;
}

Future<void> _show(
  WidgetTester tester,
  LedgerController controller, {
  Size size = const Size(390, 844),
  double scale = 1,
  Brightness brightness = Brightness.light,
  bool firstRun = false,
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(
    MaterialApp(
      theme: pennyTheme(brightness),
      home: MediaQuery(
        data: MediaQueryData(size: size, textScaler: TextScaler.linear(scale)),
        child: PermissionSetupScreen(
          controller: controller,
          firstRun: firstRun,
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

Future<void> _tap(WidgetTester tester, String label) async {
  final target = find.text(label);
  await _reveal(tester, target);
  await tester.tap(target);
  await tester.pumpAndSettle();
}

Future<void> _reveal(WidgetTester tester, Finder target) async {
  if (target.evaluate().isEmpty) {
    await tester.scrollUntilVisible(
      target,
      250,
      scrollable: find.byType(Scrollable).first,
    );
  } else {
    await tester.ensureVisible(target);
  }
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
    'first use introduces permissions and skip persists without authorization',
    (tester) async {
      final repository = _Repository();
      (repository.data['settings'] as Map)['onboardingDone'] = false;
      final controller = await _controller(repository);
      await tester.pumpWidget(PennyPilotApp(controller: controller));
      await tester.pumpAndSettle();
      expect(find.text('设置记账权限'), findsOneWidget);
      expect(repository.calls, isEmpty);
      await _tap(tester, '设置记账权限');
      expect(find.byType(PermissionSetupScreen), findsOneWidget);
      expect(repository.calls, isEmpty);
      await _tap(tester, '开始记账');
      expect(controller.settings['onboardingDone'], isTrue);
      expect(repository.calls.single.method, 'updateSettings');
      expect(find.byType(NavigationBar), findsOneWidget);

      await tester.pumpWidget(const SizedBox());
      await tester.pumpWidget(PennyPilotApp(controller: controller));
      await tester.pumpAndSettle();
      expect(find.text('设置记账权限'), findsNothing);
      expect(find.byType(PermissionSetupScreen), findsNothing);
    },
  );

  testWidgets(
    'welcome can skip setup directly or return from permission overview',
    (tester) async {
      final repository = _Repository();
      (repository.data['settings'] as Map)['onboardingDone'] = false;
      final controller = await _controller(repository);
      await tester.pumpWidget(PennyPilotApp(controller: controller));
      await tester.pumpAndSettle();
      await _tap(tester, '设置记账权限');
      await tester.tap(find.byType(BackButton));
      await tester.pumpAndSettle();
      expect(find.text('设置记账权限'), findsOneWidget);
      expect(controller.settings['onboardingDone'], isFalse);
      await _tap(tester, '暂不设置，直接记账');
      expect(find.byType(NavigationBar), findsOneWidget);
      expect(repository.calls.single.arguments, {'onboardingDone': true});
    },
  );

  testWidgets(
    'settings reopens unified overview without requesting permissions',
    (tester) async {
      final repository = _Repository();
      final controller = await _controller(repository);
      await tester.pumpWidget(PennyPilotApp(controller: controller));
      await tester.pumpAndSettle();
      await _tap(tester, '设置');
      await _tap(tester, '权限设置');
      expect(find.byType(PermissionSetupScreen), findsOneWidget);
      expect(repository.calls, isEmpty);
      await _tap(tester, '完成');
      expect(find.byType(PermissionSetupScreen), findsNothing);
      expect(repository.calls, isEmpty);
    },
  );

  testWidgets(
    'screen permission requires consent and separate system authorization',
    (tester) async {
      final repository = _Repository();
      final controller = await _controller(repository);
      await _show(tester, controller);
      await _tap(tester, '开启页面采集');
      expect(find.text('开启支付页面读取？'), findsOneWidget);
      expect(repository.calls, isEmpty);
      await _tap(tester, '取消');
      expect(controller.settings['screenCaptureEnabled'], isFalse);
      expect(repository.calls, isEmpty);
      await _tap(tester, '开启页面采集');
      await _tap(tester, '同意并开启');
      expect(controller.settings['screenCaptureEnabled'], isTrue);
      expect(repository.calls.map((call) => call.method), [
        'updateSettings',
        'openSystemSettings',
      ]);
      expect(repository.calls.last.arguments, {'target': 'accessibility'});
      expect(find.text('已就绪'), findsNothing);
      expect(controller.settings['notificationCloudParsingEnabled'], isFalse);
    },
  );

  testWidgets(
    'grant alone is distinct from enabled capture and a connected service',
    (tester) async {
      final repository = _Repository();
      repository.capabilities({
        'notificationListenerGranted': true,
        'accessibilityGranted': true,
        'accessibilityServiceConnected': true,
      });
      final controller = await _controller(repository);
      await _show(tester, controller);
      expect(find.text('已授权 · 服务未连接'), findsOneWidget);
      expect(find.text('已授权 · 采集已暂停'), findsOneWidget);
      await _tap(tester, '重新连接通知服务');
      expect(repository.calls.single.method, 'rebindNotificationService');
      await _tap(tester, '开启页面采集');
      await _tap(tester, '同意并开启');
      expect(repository.calls.last.method, 'updateSettings');
      expect(find.text('已就绪'), findsOneWidget);
      expect(
        repository.calls.where((call) => call.method == 'openSystemSettings'),
        isEmpty,
      );
    },
  );

  testWidgets(
    'enabled screen capture keeps its grant while the service reconnects',
    (tester) async {
      final repository = _Repository();
      (repository.data['settings'] as Map)['screenCaptureEnabled'] = true;
      repository.capabilities({
        'accessibilityGranted': true,
        'accessibilityServiceConnected': false,
      });
      final controller = await _controller(repository);
      await _show(tester, controller);
      expect(find.text('已授权 · 服务未连接'), findsOneWidget);
      expect(find.text('开启页面采集'), findsNothing);
      await _tap(tester, '查看无障碍设置');
      expect(repository.calls.single.method, 'openSystemSettings');
      expect(repository.calls.single.arguments, {'target': 'accessibility'});
      expect(controller.settings['screenCaptureEnabled'], isTrue);
    },
  );

  testWidgets(
    'notification action enables app switch and opens notification access',
    (tester) async {
      final repository = _Repository();
      (repository.data['settings'] as Map)['notificationCaptureEnabled'] =
          false;
      final controller = await _controller(repository);
      await _show(tester, controller);
      await _tap(tester, '开启通知采集');
      expect(controller.settings['notificationCaptureEnabled'], isTrue);
      expect(repository.calls.last.arguments, {
        'target': 'notificationListener',
      });
      expect(find.text('已就绪'), findsNothing);
      repository.capabilities({
        'notificationListenerGranted': true,
        'notificationServiceConnected': true,
      });
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();
      expect(find.text('已就绪'), findsOneWidget);
      expect(find.text('查看通知使用权'), findsOneWidget);
    },
  );

  testWidgets(
    'iOS only shows microphone and denied authorization has settings recovery',
    (tester) async {
      final repository = _Repository()..capabilities({'platform': 'ios'});
      final controller = await _controller(repository);
      await _show(tester, controller);
      expect(find.text('支付通知'), findsNothing);
      expect(find.text('支付页面'), findsNothing);
      expect(find.text('后台运行'), findsNothing);
      expect(repository.calls, isEmpty);
      await _tap(tester, '允许使用麦克风');
      expect(repository.calls.single.method, 'requestMicrophonePermission');
      expect(find.text('打开麦克风设置'), findsOneWidget);
      await _tap(tester, '打开麦克风设置');
      expect(repository.calls.last.arguments, {'target': 'microphone'});
      expect(
        repository.calls.where((call) => call.method == 'startSpeech'),
        isEmpty,
      );
    },
  );

  testWidgets(
    'permission request prevents repeated taps and completion until resolved',
    (tester) async {
      final repository = _Repository()
        ..capabilities({'platform': 'ios'})
        ..microphoneAllowed = true
        ..permissionPending = Completer<void>();
      final controller = await _controller(repository);
      await _show(tester, controller, firstRun: true);
      await tester.tap(find.text('允许使用麦克风'));
      await tester.pump();
      expect(
        tester
            .widget<FilledButton>(find.widgetWithText(FilledButton, '开始记账'))
            .onPressed,
        isNull,
      );
      expect(
        tester
            .widget<OutlinedButton>(
              find.widgetWithText(OutlinedButton, '允许使用麦克风'),
            )
            .onPressed,
        isNull,
      );
      repository.permissionPending!.complete();
      await tester.pumpAndSettle();
      expect(find.text('已允许'), findsOneWidget);
      expect(repository.calls, hasLength(1));
    },
  );

  testWidgets('reminder denial opens recovery settings on next action', (
    tester,
  ) async {
    final repository = _Repository();
    final controller = await _controller(repository);
    await _show(tester, controller);
    await _tap(tester, '允许记账提醒');
    expect(repository.calls.single.method, 'requestNotificationPermission');
    await _tap(tester, '查看提醒设置');
    expect(repository.calls.last.arguments, {'target': 'notifications'});
  });

  testWidgets('failed completion stays in setup with retry available', (
    tester,
  ) async {
    final repository = _Repository()..failSettings = true;
    final controller = await _controller(repository);
    await _show(tester, controller, firstRun: true);
    await _tap(tester, '开始记账');
    expect(find.text('保存失败，请重试。'), findsOneWidget);
    expect(
      tester
          .widget<FilledButton>(find.widgetWithText(FilledButton, '开始记账'))
          .onPressed,
      isNotNull,
    );
    repository.failSettings = false;
    await _tap(tester, '开始记账');
    expect(controller.settings['onboardingDone'], isTrue);
  });

  for (final scenario in [
    (size: const Size(320, 700), scale: 1.5, brightness: Brightness.light),
    (size: const Size(390, 844), scale: 1.5, brightness: Brightness.dark),
    (size: const Size(844, 390), scale: 1.5, brightness: Brightness.dark),
    (size: const Size(1280, 900), scale: 1.5, brightness: Brightness.light),
  ]) {
    testWidgets('permission actions remain reachable at $scenario', (
      tester,
    ) async {
      final repository = _Repository();
      final controller = await _controller(repository);
      await _show(
        tester,
        controller,
        size: scenario.size,
        scale: scenario.scale,
        brightness: scenario.brightness,
          firstRun: true,
      );
        expect(find.text('按需要开启，记账更省心'), findsOneWidget);
      await _reveal(tester, find.text('允许使用麦克风'));
      expect(find.text('允许使用麦克风').hitTestable(), findsOneWidget);
        expect(find.text('开始记账').hitTestable(), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }
}

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/core/ledger_repository.dart';
import 'package:pennypilot/core/models.dart';
import 'package:pennypilot/features/settings/ai_settings_screen.dart';
import 'package:pennypilot/features/settings/capture_settings_screen.dart';
import 'package:pennypilot/features/settings/categories_screen.dart';
import 'package:pennypilot/features/settings/settings_screen.dart';
import 'package:pennypilot/features/settings/settings_widgets.dart';

class _Repository implements LedgerRepository {
  final changes = <(String, Map<String, dynamic>)>[];
  Map<String, dynamic> settings = {
    'activeProvider': 'deepseek',
    'hasDeepSeekKey': true,
    'deepSeekModel': 'deepseek-chat',
    'notificationCaptureEnabled': true,
    'screenCaptureEnabled': false,
    'notificationCloudParsingEnabled': false,
  };
  bool failSettings = false;
  Map<String, dynamic> auth = {};
  int revision = 4;
  String platform = 'android';
  @override
  Stream<LedgerEvent> get events => const Stream.empty();
  @override
  Future<LedgerSnapshot> snapshot() async => LedgerSnapshot(
    revision: revision,
    settings: settings,
    capabilities: {'platform': platform},
    auth: auth,
  );
  @override
  Future<Object?> invoke(String method, Map<String, dynamic> arguments) async {
    changes.add((method, Map.of(arguments)));
    if (method == 'updateSettings') {
      if (failSettings) throw const LedgerException('保存失败，请重试。');
      settings = {...settings, ...arguments};
    }
    if (method == 'restoreBackup' || method == 'exportBackup') {
      return {'cancelled': true};
    }
    if (method == 'saveCategory') return {'id': 10};
    if (method == 'selectAccount') {
      auth = {...auth, 'activeAccountId': arguments['id']};
      settings = {...settings, 'chatGptModel': ''};
    }
    if (method == 'listModels') {
      return [
        {'slug': '${auth['activeAccountId']}-model', 'displayName': '测试模型'},
      ];
    }
    return null;
  }
}

Finder _field(String label) => find.byWidgetPredicate(
  (widget) => widget is TextField && widget.decoration?.labelText == label,
);

Future<LedgerController> _controller(_Repository repository) async {
  final controller = LedgerController(repository: repository);
  await controller.init();
  return controller;
}

Future<void> _show(WidgetTester tester, Widget child) async {
  tester.view.physicalSize = const Size(800, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(
    MaterialApp(theme: ThemeData(useMaterial3: true), home: child),
  );
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
    'iOS exposes supported recording settings without Android capture controls',
    (tester) async {
      final repository = _Repository()..platform = 'ios';
      final controller = await _controller(repository);
      addTearDown(controller.dispose);
      await _show(tester, SettingsScreen(controller: controller));
      expect(find.text('自动采集与权限'), findsNothing);
      expect(find.text('通知解析规则'), findsNothing);
      expect(find.text('语音与系统权限'), findsOneWidget);
      await tester.tap(find.text('语音与系统权限'));
      await tester.pumpAndSettle();
      expect(find.byType(SwitchListTile), findsNothing);
      expect(find.text('打开应用设置'), findsOneWidget);
      expect(find.textContaining('iOS 不提供'), findsOneWidget);
    },
  );
  test(
    'budget parsing preserves cents and rejects rounded or negative input',
    () {
      expect(parseBudgetCents('123.45'), 12345);
      expect(parseBudgetCents('0'), 0);
      expect(parseBudgetCents('12.5'), 1250);
      expect(parseBudgetCents('1.234'), isNull);
      expect(parseBudgetCents('-1'), isNull);
    },
  );

  test('custom endpoints require HTTPS without credentials or query data', () {
    expect(isSecureSettingsEndpoint('https://api.example.com/v1'), isTrue);
    expect(isSecureSettingsEndpoint('http://api.example.com/v1'), isFalse);
    expect(isSecureSettingsEndpoint('https://key@api.example.com/v1'), isFalse);
    expect(
      isSecureSettingsEndpoint('https://api.example.com/v1?key=secret'),
      isFalse,
    );
  });

  testWidgets(
    'cloud parsing disclosure can be cancelled without changing settings',
    (tester) async {
      final repository = _Repository();
      final controller = await _controller(repository);
      addTearDown(controller.dispose);
      await _show(tester, CaptureSettingsScreen(controller: controller));
      await tester.ensureVisible(find.widgetWithText(SwitchListTile, '通知云端解析'));
      await tester.tap(find.widgetWithText(SwitchListTile, '通知云端解析'));
      await tester.pumpAndSettle();
      expect(find.text('开启通知云端解析？'), findsOneWidget);
      expect(repository.changes, isEmpty);
      await tester.tap(find.text('取消'));
      await tester.pumpAndSettle();
      expect(repository.changes, isEmpty);
      expect(controller.settings['notificationCloudParsingEnabled'], isFalse);
    },
  );

  testWidgets(
    'screen reading is enabled only after disclosure and opens its separate system permission',
    (tester) async {
      final repository = _Repository();
      final controller = await _controller(repository);
      addTearDown(controller.dispose);
      await _show(tester, CaptureSettingsScreen(controller: controller));
      await tester.ensureVisible(
        find.widgetWithText(SwitchListTile, '读取可见支付结果'),
      );
      await tester.tap(find.widgetWithText(SwitchListTile, '读取可见支付结果'));
      await tester.pumpAndSettle();
      expect(find.text('开启支付页面读取？'), findsOneWidget);
      expect(repository.changes, isEmpty);
      await tester.tap(find.text('同意并开启'));
      await tester.pumpAndSettle();
      expect(repository.changes.map((call) => call.$1), [
        'updateSettings',
        'openSystemSettings',
      ]);
      expect(repository.changes.first.$2, {'screenCaptureEnabled': true});
      expect(repository.changes.last.$2, {'target': 'accessibility'});
    },
  );

  testWidgets(
    'saving an existing API configuration with an empty secret preserves the key',
    (tester) async {
      final repository = _Repository();
      final controller = await _controller(repository);
      addTearDown(controller.dispose);
      await _show(tester, AiSettingsScreen(controller: controller));
      final keyField = tester.widget<TextField>(_field('API Key'));
      expect(keyField.obscureText, isTrue);
      expect(keyField.controller!.text, isEmpty);
      await tester.tap(find.text('保存配置'));
      await tester.pumpAndSettle();
      expect(repository.changes.single.$1, 'updateSettings');
      expect(repository.changes.single.$2, {
        'activeProvider': 'deepseek',
        'deepSeekModel': 'deepseek-chat',
      });
    },
  );

  testWidgets(
    'failed API save keeps edited key and model available for retry',
    (tester) async {
      final repository = _Repository()..failSettings = true;
      final controller = await _controller(repository);
      addTearDown(controller.dispose);
      await _show(tester, AiSettingsScreen(controller: controller));
      await tester.enterText(_field('API Key'), 'new-test-key');
      await tester.enterText(_field('模型名称'), 'model-for-test');
      await tester.tap(find.text('保存配置'));
      await tester.pumpAndSettle();
      expect(find.text('保存失败，请重试。'), findsOneWidget);
      expect(
        tester.widget<TextField>(_field('API Key')).controller!.text,
        'new-test-key',
      );
      expect(
        tester.widget<TextField>(_field('模型名称')).controller!.text,
        'model-for-test',
      );
    },
  );

  testWidgets(
    'removing a saved key is separate from an empty field and requires confirmation',
    (tester) async {
      final repository = _Repository();
      final controller = await _controller(repository);
      addTearDown(controller.dispose);
      await _show(tester, AiSettingsScreen(controller: controller));
      await tester.tap(find.text('删除已保存的密钥'));
      await tester.pumpAndSettle();
      expect(repository.changes, isEmpty);
      await tester.tap(find.text('取消'));
      await tester.pumpAndSettle();
      expect(repository.changes, isEmpty);
      await tester.tap(find.text('删除已保存的密钥'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('删除密钥'));
      await tester.pumpAndSettle();
      expect(repository.changes.single.$2, {'deepSeekKey': ''});
    },
  );

  testWidgets(
    'switching providers and saving preserves both edited configurations',
    (tester) async {
      final repository = _Repository();
      final controller = await _controller(repository);
      addTearDown(controller.dispose);
      await _show(tester, AiSettingsScreen(controller: controller));
      await tester.enterText(_field('API Key'), 'deepseek-test-key');
      await tester.tap(find.text('DeepSeek'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('通义千问 / 百炼').last);
      await tester.pumpAndSettle();
      await tester.enterText(_field('API Key'), 'qwen-test-key');
      await tester.enterText(
        _field('服务地址（Base URL）'),
        'https://api.example.test/v1',
      );
      await tester.enterText(_field('模型名称'), 'qwen-test-model');
      await tester.tap(find.text('保存配置'));
      await tester.pumpAndSettle();
      expect(repository.changes.single.$2, {
        'activeProvider': 'qwen',
        'qwenKey': 'qwen-test-key',
        'qwenModel': 'qwen-test-model',
        'qwenBaseUrl': 'https://api.example.test/v1',
        'deepSeekKey': 'deepseek-test-key',
      });
    },
  );

  testWidgets(
    'ChatGPT models must be refreshed for the active account before saving',
    (tester) async {
      final repository = _Repository()
        ..settings = {'activeProvider': 'chatgpt', 'chatGptModel': 'a-model'}
        ..auth = {
          'activeAccountId': 'a',
          'accounts': [
            {
              'id': 'a',
              'email': 'a@example.test',
              'isSignedIn': true,
              'canUsePlan': true,
            },
            {
              'id': 'b',
              'email': 'b@example.test',
              'isSignedIn': true,
              'canUsePlan': true,
            },
          ],
        };
      final controller = await _controller(repository);
      addTearDown(controller.dispose);
      await _show(tester, AiSettingsScreen(controller: controller));
      await tester.tap(find.text('保存配置'));
      await tester.pumpAndSettle();
      expect(find.text('请先刷新此账号的模型列表。'), findsOneWidget);
      expect(repository.changes, isEmpty);
      await tester.tap(find.text('刷新列表'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('保存配置'));
      await tester.pumpAndSettle();
      expect(repository.changes.last.$2, {
        'activeProvider': 'chatgpt',
        'chatGptModel': 'a-model',
      });
      await tester.tap(find.text('b@example.test'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('保存配置'));
      await tester.pumpAndSettle();
      expect(find.text('请先刷新此账号的模型列表。'), findsOneWidget);
      expect(repository.changes.last.$1, 'selectAccount');
    },
  );

  testWidgets('settings remain usable at phone width with large text', (
    tester,
  ) async {
    final repository = _Repository();
    final controller = await _controller(repository);
    addTearDown(controller.dispose);
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    for (final page in [
      SettingsScreen(controller: controller),
      CaptureSettingsScreen(controller: controller),
      AiSettingsScreen(controller: controller),
      CategoriesScreen(controller: controller),
    ]) {
      await tester.pumpWidget(
        MaterialApp(
          theme: ThemeData(useMaterial3: true, brightness: Brightness.dark),
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(
              context,
            ).copyWith(textScaler: const TextScaler.linear(1.3)),
            child: child!,
          ),
          home: page,
        ),
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    }
  });

  testWidgets(
    'category edits ask before leaving and keep input when dismissal is cancelled',
    (tester) async {
      final repository = _Repository();
      final controller = await _controller(repository);
      addTearDown(controller.dispose);
      await _show(tester, CategoriesScreen(controller: controller));
      await tester.tap(find.text('新增分类'));
      await tester.pumpAndSettle();
      await tester.enterText(_field('分类名称'), '午饭');
      await tester.pump();
      await tester.tap(find.byType(BackButton));
      await tester.pumpAndSettle();
      expect(find.text('放弃未保存的修改？'), findsOneWidget);
      await tester.tap(find.text('取消'));
      await tester.pumpAndSettle();
      expect(tester.widget<TextField>(_field('分类名称')).controller!.text, '午饭');
      expect(repository.changes, isEmpty);
    },
  );

  testWidgets(
    'a category draft cannot be written to a restored ledger revision',
    (tester) async {
      final repository = _Repository();
      final controller = await _controller(repository);
      addTearDown(controller.dispose);
      await _show(tester, CategoriesScreen(controller: controller));
      await tester.tap(find.text('新增分类'));
      await tester.pumpAndSettle();
      await tester.enterText(_field('分类名称'), '旧账本分类');
      repository.revision = 5;
      await controller.refresh();
      await tester.pump();
      await tester.tap(find.text('保存'));
      await tester.pumpAndSettle();
      expect(repository.changes, isEmpty);
      expect(find.text('账本已经恢复，请重新打开编辑页面。'), findsOneWidget);
    },
  );

  testWidgets(
    'restore requires both confirmations before opening system picker',
    (tester) async {
      final repository = _Repository();
      final controller = await _controller(repository);
      addTearDown(controller.dispose);
      await _show(tester, SettingsScreen(controller: controller));
      await tester.ensureVisible(find.text('从备份恢复'));
      await tester.tap(find.text('从备份恢复'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('继续恢复'));
      await tester.pumpAndSettle();
      expect(find.text('确认替换当前账本？'), findsOneWidget);
      expect(repository.changes, isEmpty);
      await tester.tap(find.text('取消'));
      await tester.pumpAndSettle();
      expect(repository.changes, isEmpty);
      await tester.tap(find.text('从备份恢复'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('继续恢复'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('选择备份并替换'));
      await tester.pumpAndSettle();
      expect(repository.changes.single.$1, 'restoreBackup');
      expect(repository.changes.single.$2['revision'], 4);
    },
  );
}

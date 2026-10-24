import 'dart:async';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/core/models.dart';
import 'package:pennypilot/core/speech/voice_configuration.dart';
import 'package:pennypilot/features/settings/voice_settings_screen.dart';
import 'package:pennypilot/ui/theme.dart';

import '../ui/test_ledger_repository.dart';

class VoiceRepository extends TestLedgerRepository {
  VoiceRepository({VoiceConfiguration config = const VoiceConfiguration()}) {
    data['settings'] = <String, dynamic>{
      ...data['settings'] as Map<String, dynamic>,
      ...config.publicSettings,
      'hasSpeechKey': true,
    };
  }
  Completer<String>? pendingSpeech;
  bool failSave = false;
  final voiceCalls = <String>[];

  void state(String state) => updates.add(
    LedgerEvent.fromMap({'type': 'speechState', 'revision': 0, 'state': state}),
  );

  @override
  Future<Object?> invoke(String method, Map<String, dynamic> arguments) async {
    voiceCalls.add(method);
    switch (method) {
      case 'saveSpeechSettings':
        if (failSave) throw const LedgerException('保存失败，请重试。');
        final config = VoiceConfiguration(
          provider: arguments['provider'],
          baseUrl: arguments['baseUrl'],
          model: arguments['model'],
          apiKey: arguments['apiKey'] == ''
              ? 'saved-test-key'
              : arguments['apiKey'],
        );
        config.validate();
        (data['settings'] as Map<String, dynamic>).addAll(
          config.publicSettings,
        );
        return null;
      case 'removeSpeechKey':
        (data['settings'] as Map)['hasSpeechKey'] = false;
        return null;
      case 'startSpeech':
        pendingSpeech = Completer<String>();
        state('recording');
        return {'text': await pendingSpeech!.future};
      case 'stopSpeech':
        state('transcribing');
        pendingSpeech?.complete('午饭 28 元');
        pendingSpeech = null;
        state('idle');
        return null;
      case 'cancelSpeech':
        pendingSpeech?.completeError(
          const LedgerException('已取消语音录入。', code: 'CANCELLED'),
        );
        pendingSpeech = null;
        state('idle');
        return null;
      default:
        return super.invoke(method, arguments);
    }
  }
}

const captureImages = bool.fromEnvironment('PENNYPILOT_SCREENSHOTS');
final boundaryKey = GlobalKey();
Finder field(String label) => find.byWidgetPredicate(
  (widget) => widget is TextField && widget.decoration?.labelText == label,
);

Future<VoiceRepository> showVoice(
  WidgetTester tester, {
  Size size = const Size(390, 844),
  double scale = 1,
  Brightness brightness = Brightness.light,
  VoiceConfiguration config = const VoiceConfiguration(),
}) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = size;
  tester.platformDispatcher.textScaleFactorTestValue = scale;
  addTearDown(() {
    tester.view.resetPhysicalSize();
    tester.view.resetDevicePixelRatio();
    tester.platformDispatcher.clearTextScaleFactorTestValue();
  });
  final repository = VoiceRepository(config: config);
  final controller = LedgerController(repository: repository);
  await controller.init();
  addTearDown(() async {
    controller.dispose();
    await repository.updates.close();
  });
  await tester.pumpWidget(
    RepaintBoundary(
      key: boundaryKey,
      child: MaterialApp(
        theme: pennyTheme(brightness),
        debugShowCheckedModeBanner: false,
        home: VoiceSettingsScreen(controller: controller),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return repository;
}

Future<void> screenshot(WidgetTester tester, String name) async {
  if (!captureImages) return;
  await tester.runAsync(() async {
    final boundary =
        boundaryKey.currentContext!.findRenderObject()!
            as RenderRepaintBoundary;
    final image = await boundary.toImage(pixelRatio: 2);
    final png = await image.toByteData(format: ui.ImageByteFormat.png);
    final directory = Directory('build/test-artifacts/voice-capture');
    await directory.create(recursive: true);
    await File(
      '${directory.path}/$name.png',
    ).writeAsBytes(png!.buffer.asUint8List());
    image.dispose();
  });
}

void main() {
  setUpAll(() async {
    if (!captureImages) return;
    final icons = FontLoader('MaterialIcons')
      ..addFont(rootBundle.load('fonts/MaterialIcons-Regular.otf'));
    await icons.load();
    final font = File('/System/Library/Fonts/Supplemental/Arial Unicode.ttf');
    if (await font.exists()) {
      final loader = FontLoader('Roboto')
        ..addFont(Future.value(ByteData.sublistView(await font.readAsBytes())));
      await loader.load();
    }
  });

  testWidgets('configuration is separate and saved key is never echoed', (
    tester,
  ) async {
    await showVoice(tester);
    expect(tester.widget<TextField>(field('API Key')).obscureText, isTrue);
    expect(
      tester.widget<TextField>(field('API Key')).controller!.text,
      isEmpty,
    );
    expect(find.text('qwen3-asr-flash'), findsOneWidget);
    expect(find.textContaining('无需系统语音识别服务'), findsOneWidget);
  });

  testWidgets('failed save preserves entered key and model for retry', (
    tester,
  ) async {
    final repository = await showVoice(tester);
    repository.failSave = true;
    await tester.enterText(field('API Key'), 'new-test-secret');
    await tester.enterText(field('语音模型名称'), 'my-asr');
    await tester.ensureVisible(find.text('保存配置'));
    await tester.tap(find.text('保存配置'));
    await tester.pumpAndSettle();
    expect(
      tester.widget<TextField>(field('API Key')).controller!.text,
      'new-test-secret',
    );
    expect(
      tester.widget<TextField>(field('语音模型名称')).controller!.text,
      'my-asr',
    );
    expect(find.text('保存失败，请重试。'), findsOneWidget);
  });

  testWidgets(
    'recording test returns transcript without parsing or saving any bill',
    (tester) async {
      final repository = await showVoice(tester);
      await tester.ensureVisible(find.text('开始测试录音'));
      await tester.tap(find.text('开始测试录音'));
      await tester.pumpAndSettle();
      expect(find.text('结束并转写'), findsOneWidget);
      await tester.tap(find.text('结束并转写'));
      await tester.pumpAndSettle();
      expect(find.text('午饭 28 元'), findsOneWidget);
      expect(repository.voiceCalls, ['startSpeech', 'stopSpeech']);
      expect(repository.data['transactions'], isEmpty);
    },
  );

  testWidgets('backgrounding cancels an active recording test', (tester) async {
    final repository = await showVoice(tester);
    await tester.ensureVisible(find.text('开始测试录音'));
    await tester.tap(find.text('开始测试录音'));
    await tester.pumpAndSettle();
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.pumpAndSettle();
    expect(repository.voiceCalls, ['startSpeech', 'cancelSpeech']);
    expect(find.text('转写成功'), findsNothing);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
  });

  testWidgets(
    'saved Bailian 3.0 configuration tests without rewriting or exposing credentials',
    (tester) async {
      const config = VoiceConfiguration(
        baseUrl: 'https://workspace.example.test/compatible-mode/v1',
        model: 'qwen-audio-3.0-asr-flash',
      );
      final repository = await showVoice(tester, config: config);
      expect(find.textContaining('当前模型使用百炼原生语音接口'), findsOneWidget);
      expect(
        tester.widget<TextField>(field('服务地址')).controller!.text,
        config.baseUrl,
      );
      expect(
        tester.widget<TextField>(field('语音模型名称')).controller!.text,
        config.model,
      );
      expect(
        tester.widget<TextField>(field('API Key')).controller!.text,
        isEmpty,
      );
      await tester.ensureVisible(find.byType(OutlinedButton));
      await tester.pumpAndSettle();
      await tester.tap(find.text('开始测试录音'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('结束并转写'));
      await tester.pumpAndSettle();
      expect(find.text('转写成功'), findsOneWidget);
      expect(repository.voiceCalls, ['startSpeech', 'stopSpeech']);
      expect(
        (repository.data['settings'] as Map)['speechBaseUrl'],
        config.baseUrl,
      );
      expect((repository.data['settings'] as Map)['speechModel'], config.model);
      expect(repository.data['transactions'], isEmpty);
    },
  );

  testWidgets('deleting a key requires explicit delete action', (tester) async {
    final repository = await showVoice(tester);
    await tester.ensureVisible(find.text('删除已保存的密钥'));
    await tester.tap(find.text('删除已保存的密钥'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('取消'));
    await tester.pumpAndSettle();
    expect(repository.voiceCalls, isEmpty);
    await tester.tap(find.text('删除已保存的密钥'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('删除密钥'));
    await tester.pumpAndSettle();
    expect(repository.voiceCalls, ['removeSpeechKey']);
  });

  for (final scenario in [
    (const Size(390, 844), 1.0, Brightness.light, 'phone-light'),
    (const Size(320, 640), 2.0, Brightness.light, 'small-large-text'),
    (const Size(844, 390), 1.0, Brightness.dark, 'landscape-dark'),
    (const Size(1024, 768), 1.3, Brightness.dark, 'tablet-dark'),
  ]) {
    testWidgets('voice settings fits ${scenario.$4}', (tester) async {
      await showVoice(
        tester,
        size: scenario.$1,
        scale: scenario.$2,
        brightness: scenario.$3,
      );
      expect(tester.takeException(), isNull);
      await screenshot(tester, 'voice-${scenario.$4}');
      await tester.scrollUntilVisible(
        find.text('开始测试录音'),
        300,
        scrollable: find.byType(Scrollable).first,
      );
      expect(tester.takeException(), isNull);
      await tester.pumpAndSettle();
      await screenshot(tester, 'voice-test-${scenario.$4}');
    });
  }
}

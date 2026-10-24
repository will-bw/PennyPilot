import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/ledger_repository.dart';
import 'package:pennypilot/core/platform/mobile_platform_services.dart';

void main() {
  late FakeSpeech speech;
  late FakeFiles files;
  late FakePermissions permissions;
  late MobilePlatformServices services;
  setUp(() {
    speech = FakeSpeech();
    files = FakeFiles();
    permissions = FakePermissions();
    services = MobilePlatformServices(
      speech: speech,
      files: files,
      devicePermissions: permissions,
      platform: TargetPlatform.iOS,
      finalResultTimeout: const Duration(milliseconds: 10),
    );
  });
  tearDown(() async => services.dispose());

  test(
    'partial transcripts are emitted, stop waits for final result',
    () async {
      final partials = <String>[];
      final subscription = services.partialTranscripts.listen(partials.add);
      final result = services.startSpeech();
      await Future<void>.delayed(Duration.zero);
      speech.onResult!('午饭花了', false);
      await services.stopSpeech();
      speech.onResult!('午饭花了二十元', true);
      expect(await result, '午饭花了二十元');
      await Future<void>.delayed(Duration.zero);
      expect(partials, ['午饭花了', '午饭花了二十元']);
      expect(speech.stopCount, 1);
      await subscription.cancel();
    },
  );

  test(
    'cancelling during permission initialization never starts listening',
    () async {
      speech.initializing = Completer<bool>();
      final result = services.startSpeech();
      final expectation = expectLater(result, throwsA(hasCode('CANCELLED')));
      await services.cancelSpeech();
      speech.initializing!.complete(true);
      await expectation;
      await Future<void>.delayed(Duration.zero);
      expect(speech.listenCount, 0);
    },
  );

  test('late final result after cancellation is ignored', () async {
    final result = services.startSpeech();
    final expectation = expectLater(result, throwsA(hasCode('CANCELLED')));
    await Future<void>.delayed(Duration.zero);
    final oldCallback = speech.onResult!;
    await services.cancelSpeech();
    await expectation;
    final next = services.startSpeech();
    await Future<void>.delayed(Duration.zero);
    oldCallback('旧的文字', true);
    speech.onResult!('新的文字', true);
    expect(await next, '新的文字');
  });

  test(
    'simultaneous start is rejected and errors release the active request',
    () async {
      final result = services.startSpeech();
      final failure = expectLater(
        result,
        throwsA(hasCode('PERMISSION_DENIED')),
      );
      await expectLater(
        services.startSpeech(),
        throwsA(hasCode('SPEECH_BUSY')),
      );
      speech.onError!('error_permission');
      await failure;
      final retry = services.startSpeech();
      await Future<void>.delayed(Duration.zero);
      speech.onResult!('晚饭十元', true);
      expect(await retry, '晚饭十元');
    },
  );

  test(
    'stopped recognition uses the last partial if no final callback arrives',
    () async {
      final result = services.startSpeech();
      await Future<void>.delayed(Duration.zero);
      speech.onResult!('咖啡十二元', false);
      await services.stopSpeech();
      expect(await result, '咖啡十二元');
    },
  );

  test('unavailable recognizer produces an actionable error', () async {
    speech.available = false;
    await expectLater(
      services.startSpeech(),
      throwsA(hasCode('SPEECH_UNAVAILABLE')),
    );
  });

  test('backup picker cancellation is distinct from successful save', () async {
    expect(await services.importBackup(), isNull);
    files.saved = false;
    expect(await services.exportBackup('{"version":1}'), isFalse);
    files.saved = true;
    expect(await services.exportBackup('{"version":1}'), isTrue);
    expect(utf8.decode(files.written!), '{"version":1}');
  });

  test(
    'import rejects invalid UTF-8, invalid JSON and oversized files',
    () async {
      for (final bytes in [
        Uint8List.fromList([0xff]),
        Uint8List.fromList(utf8.encode('[1,2]')),
        Uint8List(MobilePlatformServices.maximumBackupBytes + 1),
      ]) {
        files.selected = bytes;
        await expectLater(
          services.importBackup(),
          throwsA(hasCode('INVALID_BACKUP')),
        );
      }
      files.selected = Uint8List.fromList(utf8.encode('{"version":1}'));
      expect(await services.importBackup(), '{"version":1}');
    },
  );

  test(
    'iOS capability read never advertises Android capture or requests permission',
    () async {
      permissions.microphoneAllowed = true;
      final capabilities = await services.capabilities();
      expect(capabilities['platform'], 'ios');
      expect(capabilities['notificationCaptureSupported'], isFalse);
      expect(capabilities['automaticCaptureSupported'], isFalse);
      expect(capabilities['accessibilityCaptureSupported'], isFalse);
      expect(capabilities['microphoneGranted'], isTrue);
      expect(capabilities['speechRecognitionGranted'], isFalse);
      expect(speech.initializeCount, 0);
      expect(permissions.requestCount, 0);
    },
  );

  test(
    'explicit microphone authorization does not initialize recording',
    () async {
      expect(await services.requestMicrophonePermission(), isFalse);
      permissions.microphoneAllowed = true;
      expect(await services.requestMicrophonePermission(), isTrue);
      expect(permissions.requestCount, 2);
      expect(speech.initializeCount, 0);
      expect(speech.listenCount, 0);
    },
  );
}

Matcher hasCode(String code) =>
    isA<LedgerException>().having((e) => e.code, 'code', code);

class FakeSpeech implements SpeechInput {
  bool available = true;
  int initializeCount = 0, listenCount = 0, stopCount = 0;
  Completer<bool>? initializing;
  void Function(String code)? onError;
  void Function()? onDone;
  void Function(String, bool)? onResult;
  @override
  bool get isAvailable => available;
  @override
  Future<bool> initialize({
    required void Function(String code) onError,
    required void Function() onDone,
  }) async {
    initializeCount++;
    this.onError = onError;
    this.onDone = onDone;
    return initializing == null ? available : initializing!.future;
  }

  @override
  Future<void> listen({
    required void Function(String text, bool isFinal) onResult,
  }) async {
    listenCount++;
    this.onResult = onResult;
  }

  @override
  Future<void> stop() async => stopCount++;
  @override
  Future<void> cancel() async {}
}

class FakeFiles implements BackupFileAccess {
  Uint8List? selected, written;
  bool saved = true;
  @override
  Future<Uint8List?> pickJson(int maximumBytes) async => selected;
  @override
  Future<bool> saveJson(Uint8List bytes, String name) async {
    written = bytes;
    return saved;
  }
}

class FakePermissions implements DevicePermissions {
  bool microphoneAllowed = false;
  int requestCount = 0;
  @override
  Future<bool> microphoneGranted() async => microphoneAllowed;
  @override
  Future<bool> requestMicrophone() async {
    requestCount++;
    return microphoneAllowed;
  }

  @override
  Future<bool> speechGranted() async => false;
  @override
  Future<bool> openSettings() async => true;
}

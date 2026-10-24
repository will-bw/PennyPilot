import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/ai/shared_ai_service.dart';
import 'package:pennypilot/core/cross_platform_repository.dart';
import 'package:pennypilot/core/ledger_repository.dart';
import 'package:pennypilot/core/models.dart';
import 'package:pennypilot/core/platform/mobile_platform_services.dart';

import '../ui/test_ledger_repository.dart';

class _Credentials implements SharedCredentials {
  final values = <String, String>{};
  @override
  Future<String?> get(String key) async => values[key];
  @override
  Future<void> set(String key, String value) async {
    values[key] = value;
  }

  @override
  Future<void> delete(String key) async {
    values.remove(key);
  }
}

class _Platform extends MobilePlatformServices {
  bool saved = false;
  bool microphoneAllowed = false;
  int microphoneRequests = 0;
  String? selected;
  String? exported;
  @override
  Future<Map<String, dynamic>> capabilities() async => {
    'platform': 'ios',
    'automaticCaptureSupported': false,
  };
  @override
  Future<bool> exportBackup(String json, {String? suggestedName}) async {
    exported = json;
    return saved;
  }

  @override
  Future<String?> importBackup() async => selected;
  @override
  Future<bool> requestMicrophonePermission() async {
    microphoneRequests++;
    return microphoneAllowed;
  }

  @override
  Stream<String> get partialTranscripts => const Stream.empty();
  @override
  Future<void> dispose() async {}
}

class _UnavailableCredentials extends _Credentials {
  @override
  Future<String?> get(String key) async =>
      throw StateError('Keychain unavailable');
}

class _Store extends TestLedgerRepository {
  @override
  Future<Object?> invoke(String method, Map<String, dynamic> arguments) async {
    if (method == 'exportBackupData') {
      return {'format': 'pennypilot', 'version': 1};
    }
    return super.invoke(method, arguments);
  }
}

class _Ai extends SharedAiService {
  _Ai(SharedCredentials credentials) : super(credentials: credentials);
  final pending = Completer<Map<String, dynamic>>();
  int parses = 0;
  @override
  Future<Map<String, dynamic>> parseText(String text, LedgerSnapshot snapshot) {
    parses++;
    return pending.future;
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late _Store store;
  late _Platform platform;
  late _Credentials credentials;
  late CrossPlatformLedgerRepository repository;
  setUp(() {
    store = _Store();
    platform = _Platform();
    credentials = _Credentials();
    repository = CrossPlatformLedgerRepository(
      store: store,
      platformServices: platform,
      credentials: credentials,
      android: false,
    );
  });
  tearDown(() async {
    await repository.close();
    await store.updates.close();
  });

  test(
    'microphone authorization uses shared device service without recording',
    () async {
      expect(await repository.invoke('requestMicrophonePermission', {}), {
        'granted': false,
      });
      platform.microphoneAllowed = true;
      expect(await repository.invoke('requestMicrophonePermission', {}), {
        'granted': true,
      });
      expect(platform.microphoneRequests, 2);
      expect(store.calls, isEmpty);
    },
  );

  test('iOS API keys use secure storage and never ordinary settings', () async {
    await repository.invoke('updateSettings', {
      'deepSeekKey': 'test-key',
      'themeMode': 'dark',
    });
    expect(credentials.values['deepSeekKey'], 'test-key');
    expect(store.calls.single.arguments, {'themeMode': 'dark'});
    final state = await repository.snapshot();
    expect(state.settings['hasDeepSeekKey'], isTrue);
    expect(state.settings.containsKey('deepSeekKey'), isFalse);
    expect(state.capabilities['platform'], 'ios');
  });

  test('invalid key does not partially change ordinary settings', () async {
    await expectLater(
      repository.invoke('updateSettings', {
        'deepSeekKey': 'has spaces',
        'themeMode': 'dark',
      }),
      throwsA(isA<LedgerException>()),
    );
    expect(store.calls, isEmpty);
    expect(credentials.values, isEmpty);
  });

  test('voice credentials stay outside ledger settings and backup', () async {
    await repository.invoke('saveSpeechSettings', {
      'provider': 'qwen',
      'baseUrl': 'https://dashscope.aliyuncs.com/compatible-mode/v1',
      'model': 'qwen3-asr-flash',
      'apiKey': 'private-asr-test-key',
    });
    expect(store.calls, isEmpty);
    expect(
      credentials.values['voiceConfiguration'],
      contains('private-asr-test-key'),
    );
    final state = await repository.snapshot();
    expect(state.settings['hasSpeechKey'], isTrue);
    expect(state.settings['speechModel'], 'qwen3-asr-flash');
    expect(jsonEncode(state.settings), isNot(contains('private-asr-test-key')));
    expect(state.capabilities['speechUsesCloud'], isTrue);
    final backup = await repository.invoke('exportBackupData', {});
    expect(jsonEncode(backup), isNot(contains('private-asr-test-key')));
    await repository.invoke('removeSpeechKey', {});
    expect((await repository.snapshot()).settings['hasSpeechKey'], isFalse);
  });

  test(
    'Android voice routes through shared transcription instead of native recognizer',
    () async {
      await repository.close();
      repository = CrossPlatformLedgerRepository(
        store: store,
        credentials: credentials,
        platformServices: platform,
        android: true,
      );
      await expectLater(
        repository.invoke('startSpeech', {}),
        throwsA(
          isA<LedgerException>().having(
            (e) => e.code,
            'code',
            'SPEECH_NOT_CONFIGURED',
          ),
        ),
      );
      expect(
        store.calls.where((call) => call.method == 'startSpeech'),
        isEmpty,
      );
    },
  );

  test(
    'unavailable Keychain does not hide the local ledger or erase secrets',
    () async {
      await repository.close();
      final unavailable = _UnavailableCredentials()
        ..values['deepSeekKey'] = 'existing-key';
      repository = CrossPlatformLedgerRepository(
        store: store,
        platformServices: platform,
        credentials: unavailable,
        android: false,
      );
      final state = await repository.snapshot();
      expect(state.categories, isNotEmpty);
      expect(state.settings['needsReconfiguration'], isTrue);
      expect(unavailable.values['deepSeekKey'], 'existing-key');
    },
  );

  test(
    'cancelled backup does not mark success; confirmed save records time',
    () async {
      expect(await repository.invoke('exportBackup', {}), {'cancelled': true});
      expect(store.calls, isEmpty);
      platform.saved = true;
      expect(await repository.invoke('exportBackup', {}), {'cancelled': false});
      expect(store.calls.single.arguments['lastBackupAt'], isA<int>());
      expect(jsonDecode(platform.exported!)['format'], 'pennypilot');
    },
  );

  test(
    'cancelled or malformed restore never reaches storage replacement',
    () async {
      expect(await repository.invoke('restoreBackup', {'revision': 0}), {
        'cancelled': true,
      });
      platform.selected = '[1,2]';
      await expectLater(
        repository.invoke('restoreBackup', {'revision': 0}),
        throwsA(isA<LedgerException>()),
      );
      expect(store.calls, isEmpty);
    },
  );

  test(
    'Android uses shared API parser and rejects result from pre-restore snapshot',
    () async {
      await repository.close();
      final ai = _Ai(credentials);
      repository = CrossPlatformLedgerRepository(
        store: store,
        platformServices: platform,
        credentials: credentials,
        ai: ai,
        android: true,
      );
      final request = repository.invoke('parseText', {
        'revision': 0,
        'text': '午饭12元',
      });
      // Attach the expected error before completing the asynchronous operation.
      final checking = expectLater(
        request,
        throwsA(
          isA<LedgerException>().having((e) => e.code, 'code', 'STALE_LEDGER'),
        ),
      );
      await Future<void>.delayed(Duration.zero);
      expect(ai.parses, 1);
      store.data = {...store.data, 'revision': 1};
      ai.pending.complete({'transactions': [], 'error': null});
      await checking;
      expect(store.calls.where((c) => c.method == 'parseText'), isEmpty);
    },
  );
}

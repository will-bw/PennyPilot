import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pennypilot/core/ai/shared_credentials.dart';
import 'package:pennypilot/core/ledger_repository.dart';
import 'package:pennypilot/core/speech/voice_configuration.dart';
import 'package:pennypilot/core/speech/voice_transcription_service.dart';

class MemoryCredentials implements SharedCredentials {
  final data = <String, String>{};
  @override
  Future<String?> get(String key) async => data[key];
  @override
  Future<void> set(String key, String value) async {
    data[key] = value;
  }

  @override
  Future<void> delete(String key) async {
    data.remove(key);
  }
}

class FakeRecorder implements VoiceRecorder {
  bool permission = true;
  Completer<bool>? permissionResult;
  int starts = 0, cancels = 0;
  StreamController<Uint8List>? audio;
  @override
  Future<bool> requestPermission() =>
      permissionResult?.future ?? Future.value(permission);
  @override
  Future<Stream<Uint8List>> start() async {
    starts++;
    audio = StreamController<Uint8List>();
    return audio!.stream;
  }

  void say({int length = 32000, bool silent = false}) =>
      audio!.add(Uint8List.fromList(List.filled(length, silent ? 0 : 1)));
  @override
  Future<void> stop() async {
    await audio?.close();
  }

  @override
  Future<void> cancel() async {
    cancels++;
    await audio?.close();
  }

  @override
  Future<void> dispose() async {}
}

Future<void> tick() => Future<void>.delayed(Duration.zero);
Matcher code(String expected) =>
    isA<LedgerException>().having((e) => e.code, 'code', expected);

void main() {
  late MemoryCredentials credentials;
  late VoiceConfigurationStore configuration;
  late FakeRecorder recorder;
  late VoiceTranscriptionService service;
  late List<http.Request> requests;
  late Future<http.Response> Function(http.Request) responder;

  setUp(() async {
    credentials = MemoryCredentials();
    configuration = VoiceConfigurationStore(credentials: credentials);
    await configuration.save({
      'provider': 'qwen',
      'baseUrl': 'https://asr.example.test/v1',
      'model': 'qwen3-asr-flash',
      'apiKey': 'test-secret',
    });
    recorder = FakeRecorder();
    requests = [];
    responder = (_) async => http.Response(
      jsonEncode({
        'choices': [
          {
            'message': {'content': '午饭 28 元'},
          },
        ],
      }),
      200,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );
    service = VoiceTranscriptionService(
      configuration: configuration,
      recorder: recorder,
      clientFactory: () => MockClient((request) {
        requests.add(request);
        return responder(request);
      }),
      requestTimeout: const Duration(milliseconds: 100),
    );
  });
  tearDown(() => service.dispose());

  test(
    'Qwen uploads bounded WAV only after stop, no platform recognizer needed',
    () async {
      final states = <String>[];
      final subscription = service.states.listen(states.add);
      final result = service.start();
      await tick();
      expect(service.state, 'recording');
      recorder.say();
      await tick();
      expect(requests, isEmpty);
      await service.stop();
      expect(await result, '午饭 28 元');
      expect(requests.single.url.path, '/v1/chat/completions');
      expect(requests.single.followRedirects, false);
      final body = jsonDecode(requests.single.body);
      expect(body['model'], 'qwen3-asr-flash');
      final encoded =
          body['messages'][0]['content'][0]['input_audio']['data'] as String;
      final wav = base64Decode(encoded.split(',').last);
      expect(ascii.decode(wav.sublist(0, 4)), 'RIFF');
      expect(ByteData.sublistView(wav).getUint32(24, Endian.little), 16000);
      expect(wav.length, 32044);
      await tick();
      expect(states, ['preparing', 'recording', 'transcribing', 'idle']);
      await subscription.cancel();
    },
  );

  test('compatible endpoint uses multipart audio/transcriptions', () async {
    await configuration.save({
      'provider': 'compatible',
      'baseUrl': 'https://asr.example.test/v1/',
      'model': 'whisper-1',
    });
    responder = (_) async => http.Response(
      '{"text":"地铁四元"}',
      200,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );
    final result = service.start();
    await tick();
    recorder.say();
    await tick();
    await service.stop();
    expect(await result, '地铁四元');
    expect(requests.single.url.path, '/v1/audio/transcriptions');
    expect(
      requests.single.headers['content-type'],
      startsWith('multipart/form-data'),
    );
    final bytes = latin1.decode(requests.single.bodyBytes);
    expect(bytes, contains('filename="recording.wav"'));
    expect(bytes, contains('whisper-1'));
  });

  test('unconfigured service does not request microphone or network', () async {
    await configuration.removeKey();
    await expectLater(service.start(), throwsA(code('SPEECH_NOT_CONFIGURED')));
    expect(recorder.starts, 0);
    expect(requests, isEmpty);
  });

  test('denied permission is actionable without any upload', () async {
    recorder.permission = false;
    await expectLater(service.start(), throwsA(code('PERMISSION_DENIED')));
    expect(recorder.starts, 0);
    expect(requests, isEmpty);
  });

  test(
    'cancelling while permission dialog pending never starts recording',
    () async {
      recorder.permissionResult = Completer<bool>();
      final result = expectLater(service.start(), throwsA(code('CANCELLED')));
      await tick();
      final cancelled = service.cancel();
      recorder.permissionResult!.complete(true);
      await cancelled;
      await result;
      expect(recorder.starts, 0);
      expect(requests, isEmpty);
    },
  );

  test(
    'cancellation during recording discards audio without uploading',
    () async {
      final result = expectLater(service.start(), throwsA(code('CANCELLED')));
      await tick();
      recorder.say();
      await tick();
      await service.cancel();
      await result;
      await service.stop();
      expect(requests, isEmpty);
    },
  );

  test(
    'duplicate start rejected and stop before permission waits safely',
    () async {
      recorder.permissionResult = Completer<bool>();
      final result = expectLater(service.start(), throwsA(code('NO_SPEECH')));
      await expectLater(service.start(), throwsA(code('SPEECH_BUSY')));
      final stop = service.stop();
      recorder.permissionResult!.complete(true);
      await stop;
      await result;
      expect(requests, isEmpty);
    },
  );

  for (final silent in [false, true]) {
    test(
      silent
          ? 'silence does not get uploaded'
          : 'too short recording does not get uploaded',
      () async {
        final result = expectLater(service.start(), throwsA(code('NO_SPEECH')));
        await tick();
        recorder.say(length: silent ? 32000 : 100, silent: silent);
        await tick();
        await service.stop();
        await result;
        expect(requests, isEmpty);
      },
    );
  }

  test('system interruption terminates session without uploading', () async {
    final result = expectLater(
      service.start(),
      throwsA(code('SPEECH_INTERRUPTED')),
    );
    await tick();
    recorder.say();
    await recorder.audio!.close();
    await result;
    expect(requests, isEmpty);
  });

  for (final status in [301, 400, 401, 403, 404, 429, 500]) {
    test(
      'HTTP $status is handled without exposing reflected secrets',
      () async {
        responder = (_) async =>
            http.Response('test-secret PRIVATE-AUDIO', status);
        final result = expectLater(
          service.start(),
          throwsA(
            isA<LedgerException>()
                .having((e) => e.code, 'code', 'SPEECH_HTTP_$status')
                .having(
                  (e) => e.message,
                  'message',
                  isNot(contains('test-secret')),
                ),
          ),
        );
        await tick();
        recorder.say();
        await tick();
        await service.stop();
        await result;
        expect(service.state, 'idle');
      },
    );
  }

  test('timeout releases session and allows retry', () async {
    responder = (_) => Completer<http.Response>().future;
    final result = expectLater(
      service.start(),
      throwsA(code('SPEECH_TIMEOUT')),
    );
    await tick();
    recorder.say();
    await tick();
    await service.stop();
    await result;
    responder = (_) async => http.Response(
      '{"choices":[{"message":{"content":"成功"}}]}',
      200,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );
    final retry = service.start();
    await tick();
    recorder.say();
    await tick();
    await service.stop();
    expect(await retry, '成功');
  });

  test('cancelled transcription never delivers its late result', () async {
    final response = Completer<http.Response>();
    responder = (_) => response.future;
    final result = expectLater(service.start(), throwsA(code('CANCELLED')));
    await tick();
    recorder.say();
    await tick();
    final stopped = service.stop();
    await tick();
    await service.cancel();
    await result;
    response.complete(
      http.Response('{"choices":[{"message":{"content":"late"}}]}', 200),
    );
    await stopped;
    expect(service.state, 'idle');
  });

  for (final body in [
    'not-json',
    '{}',
    '{"choices":[]}',
    '{"choices":[{"message":{"content":""}}]}',
  ]) {
    test('invalid or empty transcription is not accepted: $body', () async {
      responder = (_) async => http.Response(body, 200);
      final result = expectLater(
        service.start(),
        throwsA(isA<LedgerException>()),
      );
      await tick();
      recorder.say();
      await tick();
      await service.stop();
      await result;
    });
  }

  test('configuration changes blocked during recording', () async {
    final result = expectLater(service.start(), throwsA(code('CANCELLED')));
    await tick();
    await expectLater(
      service.saveSettings({}),
      throwsA(isA<LedgerException>()),
    );
    await expectLater(service.removeKey(), throwsA(isA<LedgerException>()));
    await service.cancel();
    await result;
  });

  test(
    'public settings do not contain API key and empty key preserves secret',
    () async {
      expect(
        jsonEncode(await service.settings()),
        isNot(contains('test-secret')),
      );
      await configuration.save({
        'provider': 'qwen',
        'baseUrl': 'https://asr.example.test/v1',
        'model': 'another-asr',
        'apiKey': '',
      });
      expect((await configuration.read()).apiKey, 'test-secret');
    },
  );

  test(
    'changing host without replacement key does not leak existing secret',
    () async {
      await expectLater(
        configuration.save({
          'provider': 'qwen',
          'baseUrl': 'https://other.example.test/v1',
          'model': 'asr',
          'apiKey': '',
        }),
        throwsA(isA<LedgerException>()),
      );
      expect(
        (await configuration.read()).baseUrl,
        'https://asr.example.test/v1',
      );
    },
  );

  for (final url in [
    'http://example.test/v1',
    'https://secret@example.test/v1',
    'https://example.test?key=secret',
    'https://example.test#secret',
  ]) {
    test('unsafe endpoint rejected: $url', () async {
      await expectLater(
        configuration.save({
          'provider': 'qwen',
          'baseUrl': url,
          'model': 'asr',
          'apiKey': 'new-key',
        }),
        throwsA(isA<LedgerException>()),
      );
      expect((await configuration.read()).apiKey, 'test-secret');
    });
  }

  test(
    'removing key preserves selected model and makes voice unconfigured',
    () async {
      await service.removeKey();
      final settings = await service.settings();
      expect(settings['hasSpeechKey'], false);
      expect(settings['speechModel'], 'qwen3-asr-flash');
    },
  );
}

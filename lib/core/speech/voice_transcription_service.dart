import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart';
import 'package:record/record.dart';

import '../ledger_repository.dart';
import 'voice_configuration.dart';

abstract interface class VoiceRecorder {
  Future<bool> requestPermission();
  Future<Stream<Uint8List>> start();
  Future<void> stop();
  Future<void> cancel();
  Future<void> dispose();
}

class PluginVoiceRecorder implements VoiceRecorder {
  AudioRecorder? _instance;
  AudioRecorder get _recorder => _instance ??= AudioRecorder();
  @override
  Future<bool> requestPermission() => _recorder.hasPermission();
  @override
  Future<Stream<Uint8List>> start() => _recorder.startStream(
    const RecordConfig(
      encoder: AudioEncoder.pcm16bits,
      sampleRate: 16000,
      numChannels: 1,
      autoGain: true,
      echoCancel: true,
      noiseSuppress: true,
    ),
  );
  @override
  Future<void> stop() async {
    await _recorder.stop();
  }

  @override
  Future<void> cancel() async {
    await _instance?.cancel();
  }

  @override
  Future<void> dispose() async {
    await _instance?.dispose();
  }
}

/// Records directly from the microphone. No system speech-recognition service
/// is required. PCM and WAV exist only in memory and are dropped on every exit.
class VoiceTranscriptionService {
  VoiceTranscriptionService({
    VoiceConfigurationStore? configuration,
    VoiceRecorder? recorder,
    http.Client Function()? clientFactory,
    this.recordingLimit = const Duration(seconds: 60),
    this.requestTimeout = const Duration(seconds: 45),
  }) : configuration = configuration ?? VoiceConfigurationStore(),
       _recorder = recorder ?? PluginVoiceRecorder(),
       _clientFactory = clientFactory ?? http.Client.new;

  final VoiceConfigurationStore configuration;
  final VoiceRecorder _recorder;
  final http.Client Function() _clientFactory;
  final Duration recordingLimit, requestTimeout;
  final _states = StreamController<String>.broadcast();
  Stream<String> get states => _states.stream;
  String _state = 'idle';
  String get state => _state;
  Completer<String>? _pending;
  Future<void>? _starting;
  StreamSubscription<Uint8List>? _audio;
  http.Client? _client;
  BytesBuilder _pcm = BytesBuilder(copy: false);
  VoiceConfiguration? _sessionConfig;
  Timer? _timer;
  int _generation = 0;
  bool _disposed = false, _cleaning = false;
  bool get busy => _pending != null || _starting != null || _cleaning;

  void _setState(String value) {
    _state = value;
    if (!_states.isClosed) _states.add(value);
  }

  Future<Map<String, dynamic>> settings() async {
    try {
      return (await configuration.read()).publicSettings;
    } catch (_) {
      return {
        ...const VoiceConfiguration().publicSettings,
        'speechConfigurationError': true,
      };
    }
  }

  Future<void> saveSettings(Map<String, dynamic> values) async {
    if (busy) throw const LedgerException('请先结束语音录入，再修改模型。');
    await configuration.save(values);
  }

  Future<void> removeKey() async {
    if (busy) throw const LedgerException('请先结束语音录入，再删除密钥。');
    await configuration.removeKey();
  }

  Future<String> start() {
    if (_disposed) return Future.error(const LedgerException('语音服务已关闭。'));
    if (busy) {
      return Future.error(
        const LedgerException('已有语音录入正在进行。', code: 'SPEECH_BUSY'),
      );
    }
    final request = Completer<String>();
    _pending = request;
    final generation = ++_generation;
    _pcm = BytesBuilder(copy: false);
    _setState('preparing');
    _starting = _begin(generation).whenComplete(() => _starting = null);
    return request.future;
  }

  bool _current(int generation) =>
      !_disposed && generation == _generation && _pending != null;

  Future<void> _begin(int generation) async {
    try {
      final config = await configuration.read();
      if (!_current(generation)) return;
      config.validate();
      _sessionConfig = config;
      final permission = await _recorder.requestPermission();
      if (!_current(generation)) return;
      if (!permission) {
        throw const LedgerException(
          '请在系统设置中允许 PennyPilot 使用麦克风。',
          code: 'PERMISSION_DENIED',
        );
      }
      final stream = await _recorder.start();
      if (!_current(generation)) {
        await _recorder.cancel();
        return;
      }
      _audio = stream.listen(
        (bytes) {
          if (!_current(generation)) return;
          if (_pcm.length + bytes.length > 16000 * 2 * 61) {
            unawaited(stop());
            return;
          }
          _pcm.add(bytes);
        },
        onError: (Object _) {
          if (_current(generation)) {
            unawaited(
              _fail(
                const LedgerException(
                  '录音被中断，请重新录音。',
                  code: 'SPEECH_INTERRUPTED',
                ),
              ),
            );
          }
        },
        onDone: () {
          if (_current(generation) && _state == 'recording') {
            unawaited(
              _fail(
                const LedgerException(
                  '录音被系统中断，请重新录音。',
                  code: 'SPEECH_INTERRUPTED',
                ),
              ),
            );
          }
        },
      );
      _setState('recording');
      _timer = Timer(recordingLimit, () => unawaited(stop()));
    } catch (error) {
      if (_current(generation)) {
        await _fail(
          error is LedgerException
              ? error
              : const LedgerException(
                  '无法启动麦克风，请检查权限后重试。',
                  code: 'SPEECH_ERROR',
                ),
        );
      }
    }
  }

  Future<void> stop() async {
    final generation = _generation;
    await _starting;
    if (!_current(generation) || _state != 'recording') return;
    _timer?.cancel();
    _setState('transcribing');
    try {
      await _recorder.stop();
      await _audio?.cancel();
      _audio = null;
      if (!_current(generation)) return;
      final bytes = _pcm.takeBytes();
      if (bytes.length < 16000) {
        throw const LedgerException('录音太短，请至少说半秒再结束。', code: 'NO_SPEECH');
      }
      final samples = ByteData.sublistView(bytes);
      var audible = false;
      for (var i = 0; i + 1 < bytes.length; i += 2) {
        if (samples.getInt16(i, Endian.little).abs() > 200) {
          audible = true;
          break;
        }
      }
      if (!audible) {
        throw const LedgerException('没有听到声音，请靠近麦克风重试。', code: 'NO_SPEECH');
      }
      final text = await _transcribe(_sessionConfig!, pcmToWav(bytes));
      if (!_current(generation)) return;
      final request = _pending;
      _pending = null;
      _sessionConfig = null;
      _setState('idle');
      request?.complete(text);
    } catch (error) {
      if (_current(generation)) {
        await _fail(
          error is LedgerException
              ? error
              : const LedgerException(
                  '语音转写失败，请检查网络后重新录音。',
                  code: 'SPEECH_NETWORK',
                ),
        );
      }
    }
  }

  Future<String> _transcribe(VoiceConfiguration config, Uint8List wav) async {
    final client = _clientFactory();
    _client = client;
    try {
      final http.BaseRequest request;
      if (config.provider == 'qwen') {
        request =
            http.Request(
                'POST',
                Uri.parse('${config.baseUrl}/chat/completions'),
              )
              ..headers['Content-Type'] = 'application/json'
              ..body = jsonEncode({
                'model': config.model,
                'messages': [
                  {
                    'role': 'user',
                    'content': [
                      {
                        'type': 'input_audio',
                        'input_audio': {
                          'data': 'data:audio/wav;base64,${base64Encode(wav)}',
                        },
                      },
                    ],
                  },
                ],
                'stream': false,
                'asr_options': {'enable_itn': false},
              });
      } else {
        request =
            http.MultipartRequest(
                'POST',
                Uri.parse('${config.baseUrl}/audio/transcriptions'),
              )
              ..fields['model'] = config.model
              ..fields['response_format'] = 'json'
              ..files.add(
                http.MultipartFile.fromBytes(
                  'file',
                  wav,
                  filename: 'recording.wav',
                  contentType: MediaType('audio', 'wav'),
                ),
              );
      }
      request.headers['Authorization'] = 'Bearer ${config.apiKey}';
      // Never forward a credential to a redirect destination.
      request.followRedirects = false;
      final data = await (() async {
        final response = await client.send(request);
        if (response.statusCode != 200) {
          throw LedgerException(switch (response.statusCode) {
            401 || 403 => '语音服务拒绝了 API Key，请检查密钥、地区和模型权限。',
            404 => '没有找到语音接口或模型，请检查服务地址和模型名称。',
            429 => '语音服务额度不足或请求过多，请检查额度后重试。',
            >= 300 && < 400 => '语音服务地址发生跳转，请填写最终的 HTTPS API 地址。',
            >= 500 => '语音服务暂时不可用，请稍后重试。',
            _ => '语音服务不接受这次录音，请检查模型与接口类型。',
          }, code: 'SPEECH_HTTP_${response.statusCode}');
        }
        final body = BytesBuilder(copy: false);
        await for (final chunk in response.stream) {
          if (body.length + chunk.length > 256 * 1024) {
            throw const LedgerException(
              '语音服务返回内容过大，请检查接口。',
              code: 'SPEECH_RESPONSE',
            );
          }
          body.add(chunk);
        }
        return jsonDecode(utf8.decode(body.takeBytes()));
      })().timeout(requestTimeout);
      Object? text;
      if (config.provider == 'qwen') {
        final choices = data is Map ? data['choices'] : null;
        final first = choices is List && choices.isNotEmpty
            ? choices.first
            : null;
        final message = first is Map ? first['message'] : null;
        text = message is Map ? message['content'] : null;
      } else {
        text = data is Map ? data['text'] : null;
      }
      if (text is! String || text.trim().isEmpty) {
        throw const LedgerException('没有识别到语音内容，请重新录音。', code: 'NO_SPEECH');
      }
      if (text.length > 4000) {
        throw const LedgerException('识别内容过长，请分成几次录入。', code: 'SPEECH_RESPONSE');
      }
      return text.trim();
    } on TimeoutException {
      throw const LedgerException('语音转写超时，请检查网络后重试。', code: 'SPEECH_TIMEOUT');
    } on FormatException {
      throw const LedgerException(
        '语音服务返回格式不正确，请检查接口类型。',
        code: 'SPEECH_RESPONSE',
      );
    } finally {
      client.close();
      if (identical(_client, client)) _client = null;
    }
  }

  Future<void> _fail(LedgerException error) async {
    final request = _pending;
    _pending = null;
    ++_generation;
    _timer?.cancel();
    _client?.close();
    _client = null;
    _sessionConfig = null;
    _pcm.clear();
    _cleaning = true;
    try {
      await _audio?.cancel();
      _audio = null;
      await _recorder.cancel();
    } catch (_) {
      // Still release the session when the OS has already stopped its recorder.
    } finally {
      _cleaning = false;
      _setState('idle');
      request?.completeError(error);
    }
  }

  Future<void> cancel() async {
    await _fail(const LedgerException('已取消语音录入。', code: 'CANCELLED'));
    await _starting;
  }

  Future<void> dispose() async {
    _disposed = true;
    await cancel();
    await _recorder.dispose();
    await _states.close();
  }
}

Uint8List pcmToWav(Uint8List pcm) {
  final header = ByteData(44);
  void label(int offset, String value) {
    for (var i = 0; i < value.length; i++) {
      header.setUint8(offset + i, value.codeUnitAt(i));
    }
  }

  label(0, 'RIFF');
  header.setUint32(4, pcm.length + 36, Endian.little);
  label(8, 'WAVE');
  label(12, 'fmt ');
  header.setUint32(16, 16, Endian.little);
  header.setUint16(20, 1, Endian.little);
  header.setUint16(22, 1, Endian.little);
  header.setUint32(24, 16000, Endian.little);
  header.setUint32(28, 32000, Endian.little);
  header.setUint16(32, 2, Endian.little);
  header.setUint16(34, 16, Endian.little);
  label(36, 'data');
  header.setUint32(40, pcm.length, Endian.little);
  return (BytesBuilder(copy: false)
        ..add(header.buffer.asUint8List())
        ..add(pcm))
      .takeBytes();
}

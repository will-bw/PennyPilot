import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/foundation.dart';
import 'package:permission_handler/permission_handler.dart' as permissions;
import 'package:speech_to_text/speech_to_text.dart';

import '../ledger_repository.dart';

/// Small device boundary; persistence and backup schema belong to the repository.
/// Construct once per repository so a recognition session has a single owner.
class MobilePlatformServices {
  MobilePlatformServices({
    SpeechInput? speech,
    BackupFileAccess? files,
    DevicePermissions? devicePermissions,
    TargetPlatform? platform,
    this.finalResultTimeout = const Duration(seconds: 3),
    this.sessionTimeout = const Duration(seconds: 65),
  }) : _speech = speech ?? PluginSpeechInput(),
       _files = files ?? PluginBackupFileAccess(),
       _permissions = devicePermissions ?? PluginDevicePermissions(),
       _platform = platform ?? defaultTargetPlatform;

  static const maximumBackupBytes = 20 * 1024 * 1024;
  final SpeechInput _speech;
  final BackupFileAccess _files;
  final DevicePermissions _permissions;
  final TargetPlatform _platform;
  final Duration finalResultTimeout, sessionTimeout;
  final _transcripts = StreamController<String>.broadcast();
  Completer<String>? _pending;
  Timer? _finalTimer, _sessionTimer;
  String _lastText = '';
  bool _initialized = false, _disposed = false, _cancelling = false;

  Stream<String> get partialTranscripts => _transcripts.stream;

  Future<Map<String, dynamic>> capabilities() async {
    final mobile =
        !kIsWeb &&
        (_platform == TargetPlatform.iOS ||
            _platform == TargetPlatform.android);
    // Permission status checks never display a permission request at startup.
    final microphone = mobile && await _permissions.microphoneGranted();
    final speechGranted = mobile && await _permissions.speechGranted();
    return {
      'platform': kIsWeb ? 'web' : _platform.name.toLowerCase(),
      'automaticCaptureSupported': false,
      'notificationCaptureSupported': false,
      'accessibilityCaptureSupported': false,
      'notificationListenerGranted': false,
      'accessibilityGranted': false,
      'notificationServiceConnected': false,
      'accessibilityServiceConnected': false,
      'remindersEnabled': false,
      'microphoneGranted': microphone,
      'speechRecognitionGranted': speechGranted,
      'speechAvailable': mobile && (!_initialized || _speech.isAvailable),
      'batteryExempt': false,
      'todayCaptureCount': 0,
      'duplicateCount': 0,
      'lastCaptureAt': 0,
      'lastError': null,
    };
  }

  Future<Uint8List> _readSelected(PlatformFile file, int limit) async {
    if ((file.lengthSync() ?? 0) > limit) {
      throw const LedgerException('文件过大，请选择较小的文件。');
    }
    final bytes = BytesBuilder(copy: false);
    await for (final chunk in file.readAsByteStream()) {
      if (bytes.length + chunk.length > limit) {
        throw const LedgerException('文件过大，请选择较小的文件。');
      }
      bytes.add(chunk);
    }
    return bytes.takeBytes();
  }

  Future<Map<String, dynamic>?> pickRecognitionImage() async {
    final file = await FilePicker.pickFile(
      type: FileType.custom,
      allowedExtensions: ['png', 'jpg', 'jpeg', 'webp'],
    );
    if (file == null) return null;
    final bytes = await _readSelected(file, 2 * 1024 * 1024);
    final extension = file.extension?.toLowerCase();
    return {
      'base64': base64Encode(bytes),
      'mimeType': extension == 'png'
          ? 'image/png'
          : extension == 'webp'
          ? 'image/webp'
          : 'image/jpeg',
    };
  }

  Future<Map<String, dynamic>?> pickBillFile() async {
    final file = await FilePicker.pickFile(
      type: FileType.custom,
      allowedExtensions: ['csv'],
    );
    if (file == null) return null;
    final bytes = await _readSelected(file, maximumBackupBytes);
    try {
      return {'name': file.name, 'text': utf8.decode(bytes)};
    } on FormatException {
      throw const LedgerException('这份账单不是 UTF-8 编码，请转换为 UTF-8 CSV 后重新导入。');
    }
  }

  Future<String> startSpeech() {
    if (_disposed) {
      return Future.error(const LedgerException('语音服务已关闭。'));
    }
    if (_pending != null || _cancelling) {
      return Future.error(
        const LedgerException('已有语音录入正在进行。', code: 'SPEECH_BUSY'),
      );
    }
    final request = Completer<String>();
    _pending = request;
    _lastText = '';
    _sessionTimer = Timer(sessionTimeout, () {
      if (_pending != request) return;
      _finish();
      unawaited(_cancelInput());
    });
    unawaited(_beginSpeech(request));
    return request.future;
  }

  Future<void> _beginSpeech(Completer<String> request) async {
    try {
      if (!_initialized) {
        final available = await _speech.initialize(
          onError: _speechError,
          onDone: _scheduleFinal,
        );
        if (_pending != request) return;
        _initialized = available;
        if (!available) {
          _fail(
            const LedgerException(
              '语音识别不可用，请在系统设置中允许麦克风和语音识别，或改用文字录入。',
              code: 'SPEECH_UNAVAILABLE',
            ),
          );
          return;
        }
      }
      if (_pending != request) return;
      await _speech.listen(
        onResult: (text, isFinal) {
          if (_pending != request) return;
          _lastText = text.trim();
          _transcripts.add(_lastText);
          if (isFinal) _finish();
        },
      );
    } catch (_) {
      if (_pending == request) {
        _fail(
          const LedgerException('无法启动语音识别，请检查系统权限后重试。', code: 'SPEECH_ERROR'),
        );
      }
    }
  }

  void _speechError(String code) {
    if (_pending == null) return;
    final permission =
        code.contains('permission') ||
        code.contains('not_allowed') ||
        code.contains('not_authorized') ||
        code.contains('restricted') ||
        code.contains('denied');
    _fail(
      LedgerException(
        permission ? '请在系统设置中允许麦克风和语音识别。' : '未能完成语音识别，请重试或改用文字录入。',
        code: permission ? 'PERMISSION_DENIED' : 'SPEECH_ERROR',
      ),
    );
  }

  void _scheduleFinal() {
    if (_pending == null) return;
    _finalTimer?.cancel();
    _finalTimer = Timer(finalResultTimeout, _finish);
  }

  Future<void> stopSpeech() async {
    if (_pending == null) return;
    try {
      await _speech.stop();
      // Some recognizers send done before their final transcription. Keep the
      // request alive briefly; never discard a final result when Stop is tapped.
      _scheduleFinal();
    } catch (_) {
      _fail(const LedgerException('语音识别停止失败，请重试。', code: 'SPEECH_ERROR'));
    }
  }

  Future<void> cancelSpeech() async {
    _fail(const LedgerException('已取消语音录入。', code: 'CANCELLED'));
    await _cancelInput();
  }

  Future<void> _cancelInput() async {
    _cancelling = true;
    try {
      await _speech.cancel();
    } catch (_) {
      // Cancellation must still release the Dart request when a recognizer was
      // interrupted by the OS or is already gone.
    } finally {
      _cancelling = false;
    }
  }

  void _finish() {
    if (_lastText.isEmpty) {
      _fail(const LedgerException('没有听清记账内容，请重试。', code: 'NO_SPEECH'));
      return;
    }
    final request = _clearPending();
    request?.complete(_lastText);
  }

  void _fail(LedgerException error) {
    final request = _clearPending();
    request?.completeError(error);
  }

  Completer<String>? _clearPending() {
    final request = _pending;
    _pending = null;
    _finalTimer?.cancel();
    _sessionTimer?.cancel();
    return request;
  }

  /// True only after the system reports a completed save; false means cancelled.
  Future<bool> exportBackup(String json, {String? suggestedName}) async {
    final bytes = Uint8List.fromList(utf8.encode(json));
    _validateBackupBytes(bytes);
    final stamp = DateTime.now().toIso8601String().replaceAll(':', '-');
    return _files.saveJson(bytes, suggestedName ?? 'PennyPilot-$stamp.json');
  }

  /// Only reads the selected file. Schema validation and replacement are atomic
  /// repository operations, after the UI's destructive-restore confirmation.
  Future<String?> importBackup() async {
    final bytes = await _files.pickJson(maximumBackupBytes);
    if (bytes == null) return null;
    _validateBackupBytes(bytes);
    try {
      final content = utf8.decode(bytes);
      if (jsonDecode(content) is! Map) throw const FormatException();
      return content;
    } on FormatException {
      throw const LedgerException(
        '请选择有效的 PennyPilot JSON 备份。',
        code: 'INVALID_BACKUP',
      );
    }
  }

  void _validateBackupBytes(Uint8List bytes) {
    if (bytes.isEmpty || bytes.length > maximumBackupBytes) {
      throw const LedgerException(
        '备份文件为空或超过 20 MiB 限制。',
        code: 'INVALID_BACKUP',
      );
    }
  }

  Future<void> openAppSettings() async {
    if (!await _permissions.openSettings()) {
      throw const LedgerException('无法打开系统设置，请手动打开 PennyPilot 的应用设置。');
    }
  }

  Future<void> dispose() async {
    _disposed = true;
    await cancelSpeech();
    await _transcripts.close();
  }
}

abstract interface class SpeechInput {
  bool get isAvailable;
  Future<bool> initialize({
    required void Function(String code) onError,
    required void Function() onDone,
  });
  Future<void> listen({
    required void Function(String text, bool isFinal) onResult,
  });
  Future<void> stop();
  Future<void> cancel();
}

class PluginSpeechInput implements SpeechInput {
  PluginSpeechInput({SpeechToText? speech})
    : _speech = speech ?? SpeechToText();
  final SpeechToText _speech;
  @override
  bool get isAvailable => _speech.isAvailable;
  @override
  Future<bool> initialize({
    required void Function(String code) onError,
    required void Function() onDone,
  }) => _speech.initialize(
    onError: (error) => onError(error.errorMsg),
    onStatus: (status) {
      if (status == SpeechToText.doneStatus ||
          status == SpeechToText.notListeningStatus) {
        onDone();
      }
    },
  );

  @override
  Future<void> listen({
    required void Function(String text, bool isFinal) onResult,
  }) async {
    await _speech.listen(
      onResult: (result) =>
          onResult(result.recognizedWords, result.finalResult),
      listenOptions: SpeechListenOptions(
        partialResults: true,
        cancelOnError: true,
        listenMode: ListenMode.dictation,
        listenFor: const Duration(seconds: 60),
        pauseFor: const Duration(seconds: 4),
      ),
    );
  }

  @override
  Future<void> stop() => _speech.stop();
  @override
  Future<void> cancel() => _speech.cancel();
}

abstract interface class BackupFileAccess {
  Future<bool> saveJson(Uint8List bytes, String name);
  Future<Uint8List?> pickJson(int maximumBytes);
}

class PluginBackupFileAccess implements BackupFileAccess {
  @override
  Future<bool> saveJson(Uint8List bytes, String name) async {
    final output = await FilePicker.saveFile(
      dialogTitle: '导出 PennyPilot 备份',
      fileName: name,
      type: FileType.custom,
      allowedExtensions: ['json'],
      bytes: bytes,
    );
    return output != null;
  }

  @override
  Future<Uint8List?> pickJson(int maximumBytes) async {
    final file = await FilePicker.pickFile(
      dialogTitle: '选择 PennyPilot 备份',
      type: FileType.custom,
      allowedExtensions: ['json'],
    );
    if (file == null) return null;
    if ((file.lengthSync() ?? 0) > maximumBytes) {
      throw const LedgerException('备份文件超过 20 MiB 限制。', code: 'INVALID_BACKUP');
    }
    final bytes = BytesBuilder(copy: false);
    await for (final chunk in file.readAsByteStream()) {
      if (bytes.length + chunk.length > maximumBytes) {
        throw const LedgerException(
          '备份文件超过 20 MiB 限制。',
          code: 'INVALID_BACKUP',
        );
      }
      bytes.add(chunk);
    }
    return bytes.takeBytes();
  }
}

abstract interface class DevicePermissions {
  Future<bool> microphoneGranted();
  Future<bool> speechGranted();
  Future<bool> openSettings();
}

class PluginDevicePermissions implements DevicePermissions {
  @override
  Future<bool> microphoneGranted() =>
      permissions.Permission.microphone.isGranted;
  @override
  Future<bool> speechGranted() => permissions.Permission.speech.isGranted;
  @override
  Future<bool> openSettings() => permissions.openAppSettings();
}

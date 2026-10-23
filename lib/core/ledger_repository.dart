import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'models.dart';

class LedgerException implements Exception {
  const LedgerException(this.message, {this.code = 'LEDGER_ERROR'});
  final String message, code;
  @override
  String toString() => message;
}

/// Platform-neutral application contract. Production composes shared services
/// with Android Room or iOS SQLite; tests can inject an isolated repository.
abstract class LedgerRepository {
  Stream<LedgerEvent> get events;
  Future<LedgerSnapshot> snapshot();
  Future<Object?> invoke(String method, Map<String, dynamic> arguments);
}

class NativeLedgerRepository implements LedgerRepository {
  NativeLedgerRepository({MethodChannel? channel, EventChannel? eventChannel})
    : _channel = channel ?? const MethodChannel('pennypilot/ledger'),
      _eventChannel = eventChannel ?? const EventChannel('pennypilot/events');

  final MethodChannel _channel;
  final EventChannel _eventChannel;
  Stream<LedgerEvent>? _events;

  void _checkPlatform() {
    if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) {
      throw const LedgerException(
        '当前平台尚未接入账本服务。请在 Android 设备上打开 PennyPilot。',
        code: 'PLATFORM_UNAVAILABLE',
      );
    }
  }

  @override
  Stream<LedgerEvent> get events {
    try {
      _checkPlatform();
      return _events ??= _eventChannel.receiveBroadcastStream().map(
        (value) => LedgerEvent.fromMap(stringMap(value)),
      );
    } on LedgerException catch (error) {
      return Stream<LedgerEvent>.error(error);
    }
  }

  @override
  Future<LedgerSnapshot> snapshot() async {
    final result = await invoke('snapshot', const {});
    try {
      return LedgerSnapshot.fromMap(stringMap(result));
    } on FormatException {
      throw const LedgerException(
        '账本数据格式异常，请重新打开应用。',
        code: 'INVALID_SNAPSHOT',
      );
    } on TypeError {
      throw const LedgerException(
        '账本数据格式异常，请重新打开应用。',
        code: 'INVALID_SNAPSHOT',
      );
    }
  }

  @override
  Future<Object?> invoke(String method, Map<String, dynamic> arguments) async {
    _checkPlatform();
    try {
      return await _channel.invokeMethod<Object?>(method, arguments);
    } on PlatformException catch (error) {
      throw LedgerException(
        error.message?.trim().isNotEmpty == true
            ? error.message!
            : '操作未能完成，请稍后重试。',
        code: error.code,
      );
    } on MissingPluginException {
      throw const LedgerException(
        '设备账本服务尚未就绪，请重新安装完整的 Android 应用。',
        code: 'PLATFORM_UNAVAILABLE',
      );
    }
  }
}

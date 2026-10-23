import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';

import 'ai/shared_ai_service.dart';
import 'ledger_repository.dart';
import 'models.dart';
import 'platform/mobile_platform_services.dart';
import 'storage/portable_ledger_store.dart';

/// Shared application services with platform-specific persistence adapters.
/// Android retains its Room store so background captures and existing accounts
/// continue using the same database. iOS uses a real SQLite store.
class CrossPlatformLedgerRepository implements LedgerRepository {
  CrossPlatformLedgerRepository({
    LedgerRepository? store,
    MobilePlatformServices? platformServices,
    SharedCredentials? credentials,
    SharedAiService? ai,
    bool? android,
  }) : _android =
           android ??
           (!kIsWeb && defaultTargetPlatform == TargetPlatform.android),
       _store = store ?? _defaultStore(),
       _platform = platformServices ?? MobilePlatformServices() {
    _credentials =
        credentials ??
        (_android ? _AndroidCredentials(_store) : SecureSharedCredentials());
    _ai = ai ?? SharedAiService(credentials: _credentials);
    _storeEvents = _store.events.listen(
      (event) {
        _revision = event.revision;
        if (!_events.isClosed) _events.add(event);
      },
      onError: (Object error) {
        if (!_events.isClosed) _events.addError(error);
      },
    );
    if (!_android) {
      _authEvents = _ai.auth.events.listen((state) {
        final identity = jsonEncode({
          'activeAccountId': state['activeAccountId'],
          'accounts': state['accounts'],
        });
        if (_authIdentity != identity) {
          _authIdentity = identity;
          _settingsGeneration++;
        }
        _emit('changed');
      });
      _speechEvents = _platform.partialTranscripts.listen((text) {
        _emit('speech', {'text': text});
      });
    }
  }

  static LedgerRepository _defaultStore() {
    if (!kIsWeb && defaultTargetPlatform == TargetPlatform.android) {
      return NativeLedgerRepository();
    }
    if (!kIsWeb && defaultTargetPlatform == TargetPlatform.iOS) {
      return PortableLedgerStore();
    }
    return _UnsupportedRepository();
  }

  final bool _android;
  final LedgerRepository _store;
  final MobilePlatformServices _platform;
  late final SharedCredentials _credentials;
  late final SharedAiService _ai;
  final _events = StreamController<LedgerEvent>.broadcast();
  late final StreamSubscription<LedgerEvent> _storeEvents;
  StreamSubscription<String>? _speechEvents;
  StreamSubscription<Map<String, dynamic>>? _authEvents;
  int _revision = 0;
  int _settingsGeneration = 0;
  String? _authIdentity;
  bool _closed = false;
  final Set<String> _reports = {};

  @override
  Stream<LedgerEvent> get events => _events.stream;

  void _emit(String type, [Map<String, dynamic> data = const {}]) {
    if (!_closed) _events.add(LedgerEvent(type, _revision, data));
  }

  @override
  Future<LedgerSnapshot> snapshot() async {
    if (_closed) throw const LedgerException('账本已关闭。');
    final base = await _store.snapshot();
    _revision = base.revision;
    if (_android) return base;
    var keys = <String?>[null, null];
    var needsReconfiguration = false;
    try {
      keys = await Future.wait([
        _credentials.get('deepSeekKey'),
        _credentials.get('qwenKey'),
      ]);
    } catch (_) {
      // A locked or unavailable Keychain must not prevent local bookkeeping.
      // Do not delete or overwrite unreadable credentials during a snapshot.
      needsReconfiguration = true;
    }
    final auth = await _ai.auth.snapshot();
    final accountId = auth['activeAccountId'];
    var model = '';
    if (accountId != null) {
      try {
        model = await _credentials.get('chatGptModel:$accountId') ?? '';
      } catch (_) {
        needsReconfiguration = true;
      }
    }
    return LedgerSnapshot(
      revision: base.revision,
      transactions: base.transactions,
      categories: base.categories,
      rules: base.rules,
      reports: base.reports,
      settings: {
        ...base.settings,
        'hasDeepSeekKey': keys[0]?.isNotEmpty == true,
        'hasQwenKey': keys[1]?.isNotEmpty == true,
        'needsReconfiguration': needsReconfiguration,
        'chatGptModel': model,
      },
      capabilities: {
        ...await _platform.capabilities(),
        'chatGptSupported': true,
      },
      auth: auth,
    );
  }

  Future<LedgerSnapshot> _current(Map<String, dynamic> args) async {
    final state = await snapshot();
    if (args['revision'] != state.revision) {
      throw const LedgerException('账本已恢复，请重新打开此页面。', code: 'STALE_LEDGER');
    }
    return state;
  }

  @override
  Future<Object?> invoke(String method, Map<String, dynamic> arguments) async {
    if (_closed) throw const LedgerException('账本已关闭。');
    switch (method) {
      case 'snapshot':
        throw const LedgerException('请使用账本快照接口。');
      case 'parseText':
      case 'ask':
      case 'generateReport':
        final state = await _current(arguments);
        if (_android && state.settings['activeProvider'] == 'chatgpt') {
          return _store.invoke(method, arguments);
        }
        final generation = _settingsGeneration;
        if (method == 'generateReport') {
          final month = arguments['yearMonth'] as String;
          if (!_reports.add(month)) return {'queued': true};
          _emit('report', {'yearMonth': month, 'state': 'RUNNING'});
          unawaited(_generateReport(month, state, generation));
          return {'queued': true};
        }
        final result = await _ai.runGuarded(
          () => _guardAiOperation(state, generation),
          () => method == 'parseText'
              ? _ai.parseText(arguments['text'] as String, state)
              : _ai.ask(
                  arguments['question'] as String,
                  (arguments['history'] as List? ?? []).map(stringMap).toList(),
                  state,
                ),
        );
        await _current(arguments);
        if (_closed || generation != _settingsGeneration) {
          throw const LedgerException('AI 配置已改变，请重新操作。', code: 'CANCELLED');
        }
        return result;
      case 'updateSettings':
        if (arguments.keys.any(
          (key) => const {
            'activeProvider',
            'deepSeekKey',
            'qwenKey',
            'deepSeekModel',
            'qwenModel',
            'qwenBaseUrl',
            'chatGptModel',
          }.contains(key),
        )) {
          _settingsGeneration++;
        }
        if (_android) return _store.invoke(method, arguments);
        final settings = Map<String, dynamic>.from(arguments);
        final model = settings.remove('chatGptModel');
        String? accountId;
        if (model != null) {
          accountId = (await _ai.auth.snapshot())['activeAccountId'] as String?;
          if (accountId == null || model is! String || model.trim().isEmpty) {
            throw const LedgerException('请先登录 ChatGPT 并选择可用模型。');
          }
        }
        // Validate ordinary settings before changing Keychain entries.
        for (final key in ['deepSeekKey', 'qwenKey']) {
          if (!settings.containsKey(key)) continue;
          final value = settings.remove(key);
          if (value is! String ||
              value.length > 8192 ||
              RegExp(r'\s').hasMatch(value)) {
            throw const LedgerException(
              'API Key 格式无效。',
              code: 'INVALID_ARGUMENT',
            );
          }
        }
        await _store.invoke(method, settings);
        if (model != null) {
          await _credentials.set('chatGptModel:$accountId', model as String);
        }
        for (final key in ['deepSeekKey', 'qwenKey']) {
          if (!arguments.containsKey(key)) continue;
          final value = arguments[key] as String;
          if (value.isEmpty) {
            await _credentials.delete(key);
          } else {
            await _credentials.set(key, value);
          }
        }
        _emit('changed');
        return null;
      case 'exportBackup':
        final data = await _store.invoke('exportBackupData', const {});
        final saved = await _platform.exportBackup(jsonEncode(data));
        if (saved) {
          await _store.invoke('updateSettings', {
            'lastBackupAt': DateTime.now().millisecondsSinceEpoch,
          });
        }
        return {'cancelled': !saved};
      case 'restoreBackup':
        await _current(arguments);
        final raw = await _platform.importBackup();
        if (raw == null) return {'cancelled': true};
        Map<String, dynamic> data;
        try {
          data = stringMap(jsonDecode(raw));
        } on FormatException {
          throw const LedgerException(
            '备份不是有效的 PennyPilot JSON 文件。',
            code: 'INVALID_BACKUP',
          );
        }
        await _store.invoke('restoreBackupData', {...arguments, 'data': data});
        return {'cancelled': false};
      case 'restoreLegacyBackup':
        if (_android) return _store.invoke('restoreBackup', arguments);
        throw const LedgerException('请先在 Android 中把旧数据库备份导出为跨平台备份。');
      case 'signIn':
        _settingsGeneration++;
        if (_android) return _store.invoke(method, arguments);
        await _ai.auth.signIn(
          accountId: arguments['accountId'] as String?,
          requestPlanPermission: arguments['requestPlanPermission'] == true,
        );
        return null;
      case 'cancelSignIn':
        if (_android) return _store.invoke(method, arguments);
        await _ai.auth.cancel();
        return null;
      case 'selectAccount':
        _settingsGeneration++;
        if (_android) return _store.invoke(method, arguments);
        await _ai.auth.select(arguments['id'] as String);
        return null;
      case 'signOut':
        _settingsGeneration++;
        if (_android) return _store.invoke(method, arguments);
        await _ai.auth.signOut(arguments['id'] as String);
        return null;
      case 'dismissWelcome':
        if (_android) return _store.invoke(method, arguments);
        await _ai.auth.dismissWelcome();
        return null;
      case 'listModels':
        if (_android) return _store.invoke(method, arguments);
        return _ai.auth.listModels();
      case 'startSpeech':
        if (_android) return _store.invoke(method, arguments);
        return {'text': await _platform.startSpeech()};
      case 'stopSpeech':
        if (_android) return _store.invoke(method, arguments);
        await _platform.stopSpeech();
        return null;
      case 'cancelSpeech':
        if (_android) return _store.invoke(method, arguments);
        await _platform.cancelSpeech();
        return null;
      case 'openSystemSettings':
        if (_android) return _store.invoke(method, arguments);
        await _platform.openAppSettings();
        return null;
      case 'requestNotificationPermission':
      case 'rebindNotificationService':
        if (_android) return _store.invoke(method, arguments);
        throw const LedgerException(
          '此平台不支持读取其他应用的支付通知。',
          code: 'UNSUPPORTED_CAPABILITY',
        );
      default:
        return _store.invoke(method, arguments);
    }
  }

  Future<void> _generateReport(
    String month,
    LedgerSnapshot state,
    int generation,
  ) async {
    try {
      final report = await _ai.runGuarded(
        () => _guardAiOperation(state, generation),
        () => _ai.generateReport(month, state),
      );
      if (_closed || generation != _settingsGeneration) {
        throw const LedgerException('AI 配置已改变。', code: 'CANCELLED');
      }
      await _store.invoke('saveGeneratedReport', {
        ...report,
        'revision': state.revision,
      });
      if (!_closed && generation == _settingsGeneration) {
        // Preserve the original revision if a restore raced with the save.
        _events.add(
          LedgerEvent('report', state.revision, {
            'yearMonth': month,
            'state': 'SUCCEEDED',
          }),
        );
      }
    } catch (_) {
      // A restore owns the new generation; old report results must not reappear.
      if (!_closed && _revision == state.revision) {
        _events.add(
          LedgerEvent('report', state.revision, {
            'yearMonth': month,
            'state': 'FAILED',
          }),
        );
      }
    } finally {
      _reports.remove(month);
    }
  }

  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    await _storeEvents.cancel();
    await _speechEvents?.cancel();
    await _authEvents?.cancel();
    await _ai.dispose();
    await _platform.dispose();
    if (_store is PortableLedgerStore) await _store.close();
    await _events.close();
  }

  Future<void> _guardAiOperation(LedgerSnapshot state, int generation) async {
    if (_closed || generation != _settingsGeneration) {
      throw const LedgerException('AI 配置已改变，请重新操作。', code: 'CANCELLED');
    }
    final current = await _store.snapshot();
    if (current.revision != state.revision) {
      throw const LedgerException('账本已恢复，请重新打开此页面。', code: 'STALE_LEDGER');
    }
    if (_closed || generation != _settingsGeneration) {
      throw const LedgerException('AI 配置已改变，请重新操作。', code: 'CANCELLED');
    }
  }
}

class _AndroidCredentials implements SharedCredentials {
  _AndroidCredentials(this.store);
  final LedgerRepository store;
  @override
  Future<String?> get(String key) async =>
      await store.invoke('readAiCredential', {'key': key}) as String?;
  @override
  Future<void> set(String key, String value) async {
    await store.invoke('updateSettings', {key: value});
  }

  @override
  Future<void> delete(String key) => set(key, '');
}

class _UnsupportedRepository implements LedgerRepository {
  static const error = LedgerException(
    '请在 Android 或 iOS 设备上打开 PennyPilot。',
    code: 'PLATFORM_UNAVAILABLE',
  );
  @override
  Stream<LedgerEvent> get events => const Stream.empty();
  @override
  Future<LedgerSnapshot> snapshot() async => throw error;
  @override
  Future<Object?> invoke(String method, Map<String, dynamic> arguments) async =>
      throw error;
}

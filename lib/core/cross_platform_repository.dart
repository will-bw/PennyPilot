import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';

import 'ai/shared_ai_service.dart';
import 'agent/agent_models.dart';
import 'agent/agent_runtime.dart';
import 'agent/agent_store.dart';
import 'ledger_repository.dart';
import 'models.dart';
import 'platform/mobile_platform_services.dart';
import 'speech/voice_configuration.dart';
import 'speech/voice_transcription_service.dart';
import 'storage/portable_ledger_store.dart';

/// Shared application services with platform-specific persistence adapters.
/// Android retains its Room store so background captures and existing accounts
/// continue using the same database. iOS uses a real SQLite store.
class CrossPlatformLedgerRepository
    implements LedgerRepository, AgentSessionRepository {
  CrossPlatformLedgerRepository({
    LedgerRepository? store,
    MobilePlatformServices? platformServices,
    SharedCredentials? credentials,
    SharedAiService? ai,
    VoiceTranscriptionService? voice,
    AgentStore? agentStore,
    bool? android,
  }) : _android =
           android ??
           (!kIsWeb && defaultTargetPlatform == TargetPlatform.android),
       _store = store ?? _defaultStore(),
       _platform = platformServices ?? MobilePlatformServices(),
       _voice =
           voice ??
           VoiceTranscriptionService(
             configuration: VoiceConfigurationStore(credentials: credentials),
           ) {
    _credentials =
        credentials ??
        (_android ? _AndroidCredentials(_store) : SecureSharedCredentials());
    _ai = ai ?? SharedAiService(credentials: _credentials);
    _agentStore = agentStore ?? SqliteAgentStore();
    _agent = LedgerAgentRuntime(
      store: _agentStore,
      onProgress: (data) {
        if (!_closed) {
          _events.add(LedgerEvent('agent', data['revision'] as int, data));
        }
      },
    );
    _voiceEvents = _voice.states.listen(
      (state) => _emit('speechState', {'state': state}),
    );
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
  final VoiceTranscriptionService _voice;
  late final StreamSubscription<String> _voiceEvents;
  late final SharedCredentials _credentials;
  late final SharedAiService _ai;
  late final AgentStore _agentStore;
  late final LedgerAgentRuntime _agent;
  final _events = StreamController<LedgerEvent>.broadcast();
  late final StreamSubscription<LedgerEvent> _storeEvents;
  StreamSubscription<String>? _speechEvents;
  StreamSubscription<Map<String, dynamic>>? _authEvents;
  int _revision = 0;
  int _settingsGeneration = 0;
  int _agentInvocation = 0;
  String? _authIdentity;
  bool _closed = false;
  final Set<String> _reports = {};

  @override
  Stream<LedgerEvent> get events => _events.stream;

  @override
  Future<Map<String, dynamic>> agentState() async {
    final current = await _store.snapshot();
    return _agent.state(current.revision);
  }

  void _emit(String type, [Map<String, dynamic> data = const {}]) {
    if (!_closed) _events.add(LedgerEvent(type, _revision, data));
  }

  @override
  Future<LedgerSnapshot> snapshot() async {
    if (_closed) throw const LedgerException('账本已关闭。');
    final base = await _store.snapshot();
    _revision = base.revision;
    final voiceSettings = await _voice.settings();
    if (_android) {
      return LedgerSnapshot(
        revision: base.revision,
        transactions: base.transactions,
        categories: base.categories,
        rules: base.rules,
        reports: base.reports,
        settings: {...base.settings, ...voiceSettings},
        capabilities: {
          ...base.capabilities,
          'speechAvailable': voiceSettings['hasSpeechKey'] == true,
          'speechUsesCloud': true,
        },
        auth: base.auth,
        launch: base.launch,
      );
    }
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
        ...voiceSettings,
        'hasDeepSeekKey': keys[0]?.isNotEmpty == true,
        'hasQwenKey': keys[1]?.isNotEmpty == true,
        'needsReconfiguration': needsReconfiguration,
        'chatGptModel': model,
      },
      capabilities: {
        ...await _platform.capabilities(),
        'speechAvailable': voiceSettings['hasSpeechKey'] == true,
        'speechUsesCloud': true,
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
      case 'recognizeInput':
        final state = await _current(arguments);
        final generation = _settingsGeneration;
        final result = await _ai.runGuarded(
          () => _guardAiOperation(state, generation),
          () => _ai.recognizeInput(
            arguments,
            state,
            nativeChat: _android
                ? (messages, role) async =>
                      await _store.invoke('recognitionChat', {
                            'revision': state.revision,
                            'messages': messages.map((message) {
                              final content = message['content'];
                              if (content is! List) return message;
                              return {
                                'role': message['role'],
                                'content': content
                                    .whereType<Map>()
                                    .where((part) => part['type'] == 'text')
                                    .map((part) => part['text'])
                                    .join('\n'),
                                'imageDataUrls': content
                                    .whereType<Map>()
                                    .where(
                                      (part) => part['type'] == 'image_url',
                                    )
                                    .map(
                                      (part) =>
                                          (part['image_url'] as Map)['url'],
                                    )
                                    .toList(),
                              };
                            }).toList(),
                            'modelRole': role,
                          })
                          as String
                : null,
          ),
        );
        await _guardAiOperation(state, generation);
        return result;
      case 'cancelAgent':
        _agentInvocation++;
        _agent.cancel();
        return null;
      case 'agentState':
      case 'agentNewSession':
      case 'agentSelectSession':
      case 'agentDeleteSession':
      case 'agentForgetMemory':
      case 'agentConfigure':
        final state = await _current(arguments);
        return switch (method) {
          'agentNewSession' => _agent.newSession(state.revision),
          'agentSelectSession' => _agent.select(
            state.revision,
            arguments['id'] as String,
          ),
          'agentDeleteSession' => _agent.deleteSession(
            state.revision,
            arguments['id'] as String,
          ),
          'agentForgetMemory' => _agent.forget(
            state.revision,
            arguments['key'] as String,
          ),
          'agentConfigure' => () async {
            await _agent.configure(agentMap(arguments['config']));
            return _agent.state(state.revision);
          }(),
          _ => _agent.state(state.revision),
        };
      case 'ask':
      case 'agentResolveAction':
        final invocation = ++_agentInvocation;
        final initial = await _current(arguments);
        final generation = _settingsGeneration;
        Future<void> guard() async {
          if (invocation != _agentInvocation) {
            throw const LedgerException('已停止本次任务。', code: 'AGENT_CANCELLED');
          }
          await _guardAiOperation(initial, generation);
        }
        var question = arguments['question'] as String? ?? '';
        if (method == 'agentResolveAction') {
          question = await _agent.resolve(
            revision: initial.revision,
            actionId: arguments['actionId'] as String,
            approve: arguments['approve'] as bool,
            ledger: _store,
            guard: guard,
          );
        }
        final current = method == 'agentResolveAction'
            ? await snapshot()
            : initial;
        await guard();
        final model = await _ai.openAgentModel(
          current,
          guard: guard,
          nativeTurn:
              _android && current.settings['activeProvider'] == 'chatgpt'
              ? (messages, tools) async {
                  final raw = agentMap(
                    await _store.invoke('agentModelTurn', {
                      'revision': current.revision,
                      'messagesJson': jsonEncode(messages),
                      'toolsJson': jsonEncode(tools),
                      'expectedAccountId': current.auth['activeAccountId'],
                      'expectedModel': current.settings['chatGptModel'],
                    }),
                  );
                  return AgentReply(
                    message: agentMap(jsonDecode(raw['messageJson'] as String)),
                  );
                }
              : null,
        );
        final result = await _agent.run(
          question: question,
          ledger: current,
          model: model,
          guard: guard,
        );
        // A user stop is a normal persisted outcome, not a stale-ledger result.
        await _guardAiOperation(initial, generation);
        return result;
      case 'parseText':
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
          () => _ai.parseText(arguments['text'] as String, state),
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
            'recognitionTextModel',
            'recognitionVisionModel',
            'recognitionReviewModel',
            'recognitionTextProvider',
            'recognitionVisionProvider',
            'recognitionReviewProvider',
            'cloudImageParsingEnabled',
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
      case 'pickRecognitionImage':
        if (_android) return _store.invoke(method, arguments);
        return _platform.pickRecognitionImage();
      case 'pickBillFile':
        if (_android) return _store.invoke(method, arguments);
        return _platform.pickBillFile();
      case 'startSpeech':
        return {'text': await _voice.start()};
      case 'stopSpeech':
        await _voice.stop();
        return null;
      case 'cancelSpeech':
        await _voice.cancel();
        return null;
      case 'saveSpeechSettings':
        await _voice.saveSettings(arguments);
        _emit('changed');
        return null;
      case 'removeSpeechKey':
        await _voice.removeKey();
        _emit('changed');
        return null;
      case 'openSystemSettings':
        if (_android) return _store.invoke(method, arguments);
        await _platform.openAppSettings();
        return null;
      case 'requestMicrophonePermission':
        return {'granted': await _platform.requestMicrophonePermission()};
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
    await _agent.shutdown();
    await _storeEvents.cancel();
    await _speechEvents?.cancel();
    await _voiceEvents.cancel();
    await _voice.dispose();
    await _authEvents?.cancel();
    await _ai.dispose();
    await _agentStore.close();
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

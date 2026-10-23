import 'dart:convert';

import '../ai/shared_credentials.dart';
import '../ledger_repository.dart';

class VoiceConfiguration {
  const VoiceConfiguration({
    this.provider = 'qwen',
    this.baseUrl = 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    this.model = 'qwen3-asr-flash',
    this.apiKey = '',
  });

  final String provider, baseUrl, model, apiKey;
  bool get configured => apiKey.isNotEmpty;

  Map<String, dynamic> get publicSettings => {
    'speechProvider': provider,
    'speechBaseUrl': baseUrl,
    'speechModel': model,
    'hasSpeechKey': configured,
  };

  void validate({bool requireKey = true}) {
    final uri = Uri.tryParse(baseUrl);
    if (!const ['qwen', 'compatible'].contains(provider) ||
        uri == null ||
        uri.scheme != 'https' ||
        uri.host.isEmpty ||
        uri.userInfo.isNotEmpty ||
        uri.hasQuery ||
        uri.hasFragment ||
        RegExp(r'\s').hasMatch(baseUrl)) {
      throw const LedgerException('请输入有效的 HTTPS 服务地址，不要附带账号、密钥或查询参数。');
    }
    if (model.isEmpty ||
        model.length > 200 ||
        RegExp(r'[\s\x00-\x1f\x7f]').hasMatch(model)) {
      throw const LedgerException('请填写有效的语音转写模型名称。');
    }
    if ((requireKey && apiKey.isEmpty) ||
        apiKey.length > 8192 ||
        RegExp(r'[\s\x00-\x1f\x7f]').hasMatch(apiKey)) {
      throw const LedgerException(
        '请先在“语音模型”中填写 API Key。',
        code: 'SPEECH_NOT_CONFIGURED',
      );
    }
  }
}

/// Configuration and key are one encrypted value: a failed write cannot pair
/// a new endpoint with an old secret. Neither is part of a ledger backup.
class VoiceConfigurationStore {
  VoiceConfigurationStore({SharedCredentials? credentials})
    : _credentials = credentials ?? SecureSharedCredentials();
  final SharedCredentials _credentials;
  static const storageKey = 'voiceConfiguration';

  Future<VoiceConfiguration> read() async {
    try {
      final raw = await _credentials.get(storageKey);
      if (raw == null) return const VoiceConfiguration();
      final data = jsonDecode(raw) as Map<String, dynamic>;
      final config = VoiceConfiguration(
        provider: data['provider'] as String,
        baseUrl: data['baseUrl'] as String,
        model: data['model'] as String,
        apiKey: data['apiKey'] as String,
      );
      config.validate(requireKey: false);
      return config;
    } catch (_) {
      throw const LedgerException(
        '无法读取语音模型的加密配置，请解锁设备后重试。',
        code: 'SPEECH_STORAGE',
      );
    }
  }

  Future<void> save(Map<String, dynamic> values) async {
    final previous = await read();
    final baseUrl = (values['baseUrl'] as String).trim().replaceFirst(
      RegExp(r'/+$'),
      '',
    );
    final key = (values['apiKey'] as String? ?? '').trim();
    final config = VoiceConfiguration(
      provider: values['provider'] as String,
      baseUrl: baseUrl,
      model: (values['model'] as String).trim(),
      apiKey: key.isEmpty ? previous.apiKey : key,
    );
    config.validate();
    if (key.isEmpty &&
        previous.configured &&
        Uri.parse(baseUrl).origin != Uri.parse(previous.baseUrl).origin) {
      throw const LedgerException('服务地址已改变，请重新填写该服务的 API Key。');
    }
    try {
      await _credentials.set(
        storageKey,
        jsonEncode({
          'provider': config.provider,
          'baseUrl': config.baseUrl,
          'model': config.model,
          'apiKey': config.apiKey,
        }),
      );
    } catch (_) {
      throw const LedgerException('无法保存语音模型的加密配置，请重试。', code: 'SPEECH_STORAGE');
    }
  }

  Future<void> removeKey() async {
    final previous = await read();
    try {
      await _credentials.set(
        storageKey,
        jsonEncode({
          'provider': previous.provider,
          'baseUrl': previous.baseUrl,
          'model': previous.model,
          'apiKey': '',
        }),
      );
    } catch (_) {
      throw const LedgerException('无法删除语音模型密钥，请重试。', code: 'SPEECH_STORAGE');
    }
  }
}

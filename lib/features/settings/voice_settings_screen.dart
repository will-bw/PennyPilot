import 'package:flutter/material.dart';

import '../../core/ledger_controller.dart';
import '../../core/speech/voice_configuration.dart';
import 'settings_widgets.dart';

class VoiceSettingsScreen extends StatefulWidget {
  const VoiceSettingsScreen({super.key, required this.controller});
  final LedgerController controller;

  @override
  State<VoiceSettingsScreen> createState() => _VoiceSettingsScreenState();
}

class _VoiceSettingsScreenState extends State<VoiceSettingsScreen>
    with WidgetsBindingObserver {
  late final TextEditingController _url, _model;
  final _key = TextEditingController();
  late String _provider;
  bool _dirty = false, _busy = false, _testing = false, _canPop = false;
  bool _testError = false;
  String? _error, _result;

  bool get _hasKey => widget.controller.settings['hasSpeechKey'] == true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    const fallback = VoiceConfiguration();
    final settings = widget.controller.settings;
    _provider = settings['speechProvider'] as String? ?? fallback.provider;
    _url = TextEditingController(
      text: settings['speechBaseUrl'] as String? ?? fallback.baseUrl,
    );
    _model = TextEditingController(
      text: settings['speechModel'] as String? ?? fallback.model,
    );
    for (final field in [_url, _model, _key]) {
      field.addListener(() {
        if (mounted) setState(() => _dirty = true);
      });
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused && _testing) {
      widget.controller.cancelSpeech().catchError((Object _) {});
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    if (_testing) widget.controller.cancelSpeech().catchError((Object _) {});
    for (final field in [_url, _model, _key]) {
      field.dispose();
    }
    super.dispose();
  }

  Future<bool> _save() async {
    if (_busy || _testing) return false;
    setState(() {
      _busy = true;
      _error = null;
      _testError = false;
    });
    try {
      await widget.controller.saveSpeechSettings({
        'provider': _provider,
        'baseUrl': _url.text,
        'model': _model.text,
        'apiKey': _key.text,
      });
      if (!mounted) return false;
      _key.clear();
      setState(() => _dirty = false);
      settingsFeedback(context, '语音模型已保存');
      return true;
    } catch (error) {
      if (mounted) setState(() => _error = settingsError(error));
      return false;
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _test() async {
    if (_busy || _testing) return;
    if ((_dirty || !_hasKey) && !await _save()) return;
    if (!mounted) return;
    setState(() {
      _testing = true;
      _error = null;
      _result = null;
    });
    try {
      final result = await widget.controller.startSpeech();
      if (mounted) setState(() => _result = result);
    } catch (error) {
      if (mounted && !(error is LedgerException && error.code == 'CANCELLED')) {
        setState(() {
          _error = settingsError(error);
          _testError = true;
        });
      }
    } finally {
      if (mounted) setState(() => _testing = false);
    }
  }

  Future<void> _delete() async {
    if (!await confirmSettingsAction(
      context,
      title: '删除语音模型 API Key？',
      message: '将从本机加密存储移除此密钥。下次语音录入前需要重新填写。',
      confirmLabel: '删除密钥',
      destructive: true,
    )) {
      return;
    }
    if (!mounted) return;
    setState(() {
      _busy = true;
      _error = null;
      _testError = false;
    });
    try {
      await widget.controller.removeSpeechKey();
      if (mounted) settingsFeedback(context, '语音模型密钥已删除');
    } catch (error) {
      if (mounted) setState(() => _error = settingsError(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _leave() async {
    if (_dirty &&
        !await confirmSettingsAction(
          context,
          title: '放弃未保存的语音配置？',
          message: '已保存的配置仍然保留。',
          confirmLabel: '放弃修改',
        )) {
      return;
    }
    if (!mounted) return;
    if (_testing) await widget.controller.cancelSpeech();
    if (!mounted) return;
    setState(() => _canPop = true);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) Navigator.pop(context);
    });
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: widget.controller,
    builder: (context, _) {
      final c = widget.controller;
      final disabled = _busy || _testing;
      return PopScope(
        canPop: _canPop || (!_dirty && !_testing),
        onPopInvokedWithResult: (didPop, _) {
          if (!didPop) _leave();
        },
        child: Scaffold(
          appBar: AppBar(title: const Text('语音模型')),
          body: SafeArea(
            top: false,
            child: SettingsList(
              children: [
                SettingsSection(
                  title: '语音转写服务',
                  description: '把录音转为文字，再交给 AI 服务识别账单。只需麦克风权限，无需系统语音识别服务。',
                  children: [
                    Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          DropdownButtonFormField<String>(
                            initialValue: _provider,
                            isExpanded: true,
                            decoration: const InputDecoration(
                              labelText: '接口类型',
                            ),
                            items: const [
                              DropdownMenuItem(
                                value: 'qwen',
                                child: Text('通义千问 Qwen ASR'),
                              ),
                              DropdownMenuItem(
                                value: 'compatible',
                                child: Text('兼容转写 API'),
                              ),
                            ],
                            onChanged: disabled
                                ? null
                                : (value) {
                                    if (value == null || value == _provider) {
                                      return;
                                    }
                                    setState(() {
                                      _provider = value;
                                      _url.text = value == 'qwen'
                                          ? const VoiceConfiguration().baseUrl
                                          : 'https://api.openai.com/v1';
                                      _model.text = value == 'qwen'
                                          ? const VoiceConfiguration().model
                                          : 'whisper-1';
                                      _dirty = true;
                                    });
                                  },
                          ),
                          const SizedBox(height: 16),
                          TextField(
                            controller: _url,
                            enabled: !disabled,
                            keyboardType: TextInputType.url,
                            autocorrect: false,
                            decoration: const InputDecoration(
                              labelText: '服务地址',
                              helperText: '填写 API 基础地址，需与密钥所属地区一致。',
                              helperMaxLines: 3,
                            ),
                          ),
                          const SizedBox(height: 16),
                          TextField(
                            controller: _model,
                            enabled: !disabled,
                            autocorrect: false,
                            decoration: const InputDecoration(
                              labelText: '语音模型名称',
                              helperText: '填写账号已开通的语音转写模型，不能使用普通聊天模型。',
                              helperMaxLines: 3,
                            ),
                          ),
                          const SizedBox(height: 16),
                          TextField(
                            controller: _key,
                            enabled: !disabled,
                            obscureText: true,
                            autocorrect: false,
                            enableSuggestions: false,
                            decoration: InputDecoration(
                              labelText: 'API Key',
                              helperText: _hasKey
                                  ? '已加密保存；留空保留密钥。更换服务需重新填写。'
                                  : '单独配置语音服务密钥，ChatGPT 登录不会提供此密钥。',
                              helperMaxLines: 3,
                            ),
                          ),
                          const SizedBox(height: 20),
                          FilledButton(
                            onPressed: disabled ? null : _save,
                            child: Text(_busy ? '正在保存…' : '保存配置'),
                          ),
                          if (_error != null && !_testError) ...[
                            const SizedBox(height: 12),
                            Semantics(
                              liveRegion: true,
                              child: Text(
                                _error!,
                                style: TextStyle(
                                  color: Theme.of(context).colorScheme.error,
                                ),
                              ),
                            ),
                          ],
                          if (_hasKey)
                            TextButton(
                              onPressed: disabled ? null : _delete,
                              child: const Text('删除已保存的密钥'),
                            ),
                        ],
                      ),
                    ),
                  ],
                ),
                SettingsSection(
                  title: '测试语音转写',
                  description:
                      '说一句“午饭 28 元”，结束录音后检查结果。录音会发送给上方服务，可能消耗额度；测试不会新增账单。',
                  children: [
                    Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          if (_testing) ...[
                            Semantics(
                              liveRegion: true,
                              child: Text(switch (c.speechState) {
                                'transcribing' => '录音已结束，正在转写…',
                                'recording' => '正在录音，最长 60 秒。说完后点结束。',
                                _ => '正在准备麦克风…',
                              }),
                            ),
                            const SizedBox(height: 12),
                          ],
                          OutlinedButton.icon(
                            onPressed:
                                _busy ||
                                    (_testing && c.speechState != 'recording')
                                ? null
                                : _testing
                                ? () async {
                                    await c.stopSpeech();
                                  }
                                : _test,
                            icon: Icon(_testing ? Icons.stop : Icons.mic_none),
                            label: Text(
                              _testing
                                  ? (c.isTranscribing ? '正在转写…' : '结束并转写')
                                  : (_dirty || !_hasKey ? '保存并测试录音' : '开始测试录音'),
                            ),
                          ),
                          if (_testing)
                            TextButton(
                              onPressed: () async {
                                await c.cancelSpeech();
                              },
                              child: const Text('取消录音与转写'),
                            ),
                          if (_result != null) ...[
                            const SizedBox(height: 16),
                            const Text('转写成功'),
                            const SizedBox(height: 8),
                            SelectableText(_result!),
                          ],
                          if (_error != null && _testError) ...[
                            const SizedBox(height: 12),
                            Semantics(
                              liveRegion: true,
                              child: Text(
                                _error!,
                                style: TextStyle(
                                  color: Theme.of(context).colorScheme.error,
                                ),
                              ),
                            ),
                          ],
                          if (c.settings['speechConfigurationError'] ==
                              true) ...[
                            const SizedBox(height: 12),
                            const Text('加密配置暂时无法读取，请解锁设备后重试。'),
                          ],
                        ],
                      ),
                    ),
                  ],
                ),
                const Padding(
                  padding: EdgeInsets.fromLTRB(20, 20, 20, 0),
                  child: Text(
                    '密钥加密保存在本机，不进入账本备份。录音仅在内存中处理，结束或取消后释放。转写结果仍需核对金额。',
                  ),
                ),
              ],
            ),
          ),
        ),
      );
    },
  );
}

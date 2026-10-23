import 'package:flutter/material.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/core/models.dart';
import '../../ui/common.dart';

import 'settings_widgets.dart';

class AiSettingsScreen extends StatefulWidget {
  const AiSettingsScreen({super.key, required this.controller});
  final LedgerController controller;
  @override
  State<AiSettingsScreen> createState() => _AiSettingsScreenState();
}

class _AiSettingsScreenState extends State<AiSettingsScreen> {
  late final TextEditingController _deepSeekKey,
      _deepSeekModel,
      _qwenKey,
      _qwenModel,
      _qwenUrl;
  late String _provider;
  late String _selectedModel;
  late Map<String, String> _savedFields;
  String? _observedAccountId, _catalogAccountId;
  List<AiModel> _models = [];
  bool _busy = false, _loadingModels = false, _dirty = false, _canPop = false;
  String? _error, _modelsError;

  String? get _accountId =>
      widget.controller.auth['activeAccountId'] as String?;
  bool get _authorizing => widget.controller.auth['isAuthorizing'] == true;
  List<Map<String, dynamic>> get _accounts =>
      (widget.controller.auth['accounts'] as List? ?? [])
          .map(stringMap)
          .toList();
  Map<String, dynamic>? get _activeAccount {
    for (final account in _accounts) {
      if (account['id'] == _accountId) return account;
    }
    return null;
  }

  @override
  void initState() {
    super.initState();
    final settings = widget.controller.settings;
    _provider = settings['activeProvider'] as String? ?? '';
    if (!const ['chatgpt', 'deepseek', 'qwen'].contains(_provider)) {
      _provider = 'chatgpt';
    }
    _deepSeekKey = TextEditingController();
    _qwenKey = TextEditingController();
    _deepSeekModel = TextEditingController(
      text: settings['deepSeekModel'] as String? ?? 'deepseek-chat',
    );
    _qwenModel = TextEditingController(
      text: settings['qwenModel'] as String? ?? 'qwen-plus',
    );
    _qwenUrl = TextEditingController(
      text: settings['qwenBaseUrl'] as String? ?? '',
    );
    _savedFields = _configurationFields;
    _selectedModel = settings['chatGptModel'] as String? ?? '';
    _observedAccountId = _accountId;
    for (final controller in [
      _deepSeekKey,
      _qwenKey,
      _deepSeekModel,
      _qwenModel,
      _qwenUrl,
    ]) {
      controller.addListener(() {
        if (!_dirty && mounted) setState(() => _dirty = true);
      });
    }
    widget.controller.addListener(_onControllerChanged);
  }

  @override
  void dispose() {
    widget.controller.removeListener(_onControllerChanged);
    for (final controller in [
      _deepSeekKey,
      _qwenKey,
      _deepSeekModel,
      _qwenModel,
      _qwenUrl,
    ]) {
      controller.dispose();
    }
    super.dispose();
  }

  void _onControllerChanged() {
    if (!mounted) return;
    if (_observedAccountId != _accountId) {
      setState(() {
        _observedAccountId = _accountId;
        _catalogAccountId = null;
        _models = [];
        _selectedModel =
            widget.controller.settings['chatGptModel'] as String? ?? '';
        _modelsError = null;
      });
    }
  }

  Future<void> _run(Future<void> Function() action, [String? success]) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await action();
      if (mounted && success != null) settingsFeedback(context, success);
    } catch (error) {
      if (mounted) setState(() => _error = settingsError(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _refreshModels() async {
    if (_loadingModels) return;
    final accountId = _accountId;
    if (_activeAccount?['isSignedIn'] != true ||
        _activeAccount?['canUsePlan'] != true) {
      setState(() => _modelsError = '请先登录，并允许使用此账号的 ChatGPT 套餐。');
      return;
    }
    setState(() {
      _loadingModels = true;
      _modelsError = null;
    });
    try {
      final result = await widget.controller.listModels();
      if (!mounted || accountId != _accountId) return;
      setState(() {
        _models = result;
        _catalogAccountId = accountId;
        if (_selectedModel.isNotEmpty &&
            !result.any((model) => model.slug == _selectedModel)) {
          _selectedModel = '';
        }
        _modelsError = result.isEmpty ? '此账号暂未返回可选模型，请稍后刷新或检查套餐授权。' : null;
      });
      if (result.isNotEmpty) settingsFeedback(context, '模型列表已更新');
    } catch (error) {
      if (mounted && accountId == _accountId) {
        setState(() => _modelsError = settingsError(error));
      }
    } finally {
      if (mounted) setState(() => _loadingModels = false);
    }
  }

  Future<void> _signOut(Map<String, dynamic> account) async {
    if (!await confirmSettingsAction(
      context,
      title: '退出此 ChatGPT 账号？',
      message: '将移除此设备上的“${account['email'] ?? '此账号'}”登录状态。其他 AI 服务配置仍保留。',
      confirmLabel: '退出账号',
    )) {
      return;
    }
    if (mounted) {
      await _run(
        () => widget.controller.signOut(account['id'] as String),
        '已退出此账号',
      );
    }
  }

  Future<void> _removeKey(bool qwen) async {
    final name = qwen ? '通义千问' : 'DeepSeek';
    if (!await confirmSettingsAction(
      context,
      title: '删除 $name API Key？',
      message: '将从本机加密存储中移除这个密钥。继续使用此服务前，需要重新填写。',
      confirmLabel: '删除密钥',
      destructive: true,
    )) {
      return;
    }
    if (!mounted) return;
    await _run(() async {
      await widget.controller.updateSettings({
        qwen ? 'qwenKey' : 'deepSeekKey': '',
      });
      (qwen ? _qwenKey : _deepSeekKey).clear();
    }, 'API Key 已删除');
  }

  Future<void> _save() async {
    final changes = <String, dynamic>{'activeProvider': _provider};
    String? validation;
    if (_provider == 'chatgpt') {
      validation = _activeAccount?['isSignedIn'] != true
          ? '请先使用 ChatGPT 登录。'
          : _activeAccount?['canUsePlan'] != true
          ? '请先允许使用此账号的 ChatGPT 套餐。'
          : _loadingModels
          ? '正在读取模型，请稍候。'
          : _catalogAccountId != _accountId || _models.isEmpty
          ? '请先刷新此账号的模型列表。'
          : !_models.any((model) => model.slug == _selectedModel)
          ? '请选择此账号当前可用的模型。'
          : null;
      changes['chatGptModel'] = _selectedModel;
    } else {
      final qwen = _provider == 'qwen';
      final key = (qwen ? _qwenKey : _deepSeekKey).text.trim();
      final hasKey =
          widget.controller.settings[qwen ? 'hasQwenKey' : 'hasDeepSeekKey'] ==
          true;
      final model = (qwen ? _qwenModel : _deepSeekModel).text.trim();
      validation = key.isEmpty && !hasKey
          ? '请填写所选服务的 API Key。'
          : RegExp(r'\s').hasMatch(key)
          ? 'API Key 不能包含空格或换行。'
          : model.isEmpty
          ? '请填写模型名称。'
          : qwen && !isSecureSettingsEndpoint(_qwenUrl.text)
          ? '请输入有效的 HTTPS 服务地址，不要附带账号、密钥、查询参数或片段。'
          : null;
      if (key.isNotEmpty) changes[qwen ? 'qwenKey' : 'deepSeekKey'] = key;
      changes[qwen ? 'qwenModel' : 'deepSeekModel'] = model;
      if (qwen) {
        changes['qwenBaseUrl'] = _qwenUrl.text.trim().replaceFirst(
          RegExp(r'/+$'),
          '',
        );
      }
    }
    // Switching services keeps each form draft. Save every edited field so a
    // successful save never silently discards the other service's input.
    for (final entry in {
      'deepSeekKey': _deepSeekKey.text.trim(),
      'qwenKey': _qwenKey.text.trim(),
    }.entries) {
      if (entry.value.isEmpty) continue;
      if (RegExp(r'\s').hasMatch(entry.value)) {
        validation ??= 'API Key 不能包含空格或换行。';
      } else {
        changes[entry.key] = entry.value;
      }
    }
    for (final entry in _configurationFields.entries) {
      if (entry.value == _savedFields[entry.key]) continue;
      if (entry.key == 'qwenBaseUrl') {
        if (!isSecureSettingsEndpoint(entry.value)) {
          validation ??= '通义千问服务地址必须为不含账号、查询参数或片段的 HTTPS 地址。';
        }
      } else if (entry.value.isEmpty) {
        validation ??= '请填写已修改服务的模型名称。';
      }
      changes[entry.key] = entry.value;
    }
    if (validation != null) {
      setState(() => _error = validation);
      return;
    }
    await _run(() async {
      await widget.controller.updateSettings(changes);
      if (mounted) {
        setState(() {
          _deepSeekKey.clear();
          _qwenKey.clear();
          _savedFields = _configurationFields;
          _dirty = false;
        });
      }
    }, '配置已保存。连接将在下次使用 AI 时验证。');
  }

  Map<String, String> get _configurationFields => {
    'deepSeekModel': _deepSeekModel.text.trim(),
    'qwenModel': _qwenModel.text.trim(),
    'qwenBaseUrl': _qwenUrl.text.trim().replaceFirst(RegExp(r'/+$'), ''),
  };

  Future<void> _leave() async {
    if (_busy) return;
    if (_dirty &&
        !await confirmSettingsAction(
          context,
          title: '放弃未保存的配置？',
          message: '本次修改尚未保存，返回后需要重新填写。',
          confirmLabel: '放弃修改',
        )) {
      return;
    }
    if (mounted) {
      setState(() => _canPop = true);
      Navigator.pop(context);
    }
  }

  Widget _chatGpt(BuildContext context) {
    final account = _activeAccount;
    final controlsEnabled = !_busy && !_authorizing && !_loadingModels;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (final item in _accounts)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: Icon(
                    item['id'] == _accountId
                        ? Icons.check_circle_outline
                        : Icons.account_circle_outlined,
                    color: item['id'] == _accountId
                        ? Theme.of(context).colorScheme.primary
                        : null,
                  ),
                  title: Text(item['email'] as String? ?? 'ChatGPT 账号'),
                  subtitle: Text(
                    item['isSignedIn'] != true
                        ? '登录已失效'
                        : item['canUsePlan'] == true
                        ? '已登录 · 已允许套餐用量'
                        : '已登录 · 尚未允许套餐用量',
                  ),
                  onTap: !controlsEnabled || item['id'] == _accountId
                      ? null
                      : () => _run(
                          () => widget.controller.selectAccount(
                            item['id'] as String,
                          ),
                          '已切换账号',
                        ),
                  trailing: PopupMenuButton<String>(
                    tooltip: '账号操作',
                    enabled: controlsEnabled,
                    onSelected: (value) {
                      if (value == 'signOut') {
                        _signOut(item);
                      } else if (value == 'signIn') {
                        _run(
                          () => widget.controller.signIn(
                            accountId: item['id'] as String,
                          ),
                        );
                      } else {
                        _run(
                          () => widget.controller.selectAccount(
                            item['id'] as String,
                          ),
                          '已切换账号',
                        );
                      }
                    },
                    itemBuilder: (_) => [
                      if (item['id'] != _accountId)
                        const PopupMenuItem(
                          value: 'select',
                          child: Text('使用此账号'),
                        ),
                      const PopupMenuItem(value: 'signIn', child: Text('重新登录')),
                      const PopupMenuItem(
                        value: 'signOut',
                        child: Text('退出账号'),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        if (_accounts.isEmpty)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 12),
            child: Text('登录 ChatGPT 后，选择账号可用的模型。'),
          ),
        Align(
          alignment: Alignment.centerLeft,
          child: OutlinedButton.icon(
            onPressed: controlsEnabled
                ? () => _run(() => widget.controller.signIn())
                : null,
            icon: const Icon(Icons.login),
            label: Text(_accounts.isEmpty ? '使用 ChatGPT 登录' : '添加账号'),
          ),
        ),
        if (_authorizing) ...[
          const SizedBox(height: 12),
          const LinearProgressIndicator(),
          const SizedBox(height: 12),
          const Text('请在系统浏览器完成授权，然后返回 PennyPilot。'),
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton(
              onPressed: _busy
                  ? null
                  : () => _run(widget.controller.cancelSignIn, '已取消登录'),
              child: const Text('取消登录'),
            ),
          ),
        ],
        if (account != null &&
            account['isSignedIn'] == true &&
            account['canUsePlan'] != true) ...[
          const SizedBox(height: 16),
          const Text('需要单独授权使用 ChatGPT 套餐用量。授权后，记账解析与问账会使用此账号的可用额度。'),
          const SizedBox(height: 8),
          Align(
            alignment: Alignment.centerLeft,
            child: FilledButton.tonal(
              onPressed: controlsEnabled
                  ? () => _run(
                      () => widget.controller.signIn(
                        accountId: _accountId,
                        requestPlanPermission: true,
                      ),
                    )
                  : null,
              child: const Text('允许使用套餐'),
            ),
          ),
        ],
        if (widget.controller.auth['showWelcome'] == true) ...[
          const SizedBox(height: 16),
          const Text('ChatGPT 登录成功。请核对套餐授权，并刷新模型列表。'),
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton(
              onPressed: _busy
                  ? null
                  : () => _run(widget.controller.dismissWelcome),
              child: const Text('知道了'),
            ),
          ),
        ],
        if ((widget.controller.auth['error'] as String?)?.isNotEmpty == true)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: Text(
              widget.controller.auth['error'] as String,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ),
        const SizedBox(height: 24),
        Row(
          children: [
            Expanded(
              child: Text('模型', style: Theme.of(context).textTheme.titleSmall),
            ),
            TextButton.icon(
              onPressed:
                  controlsEnabled &&
                      account?['isSignedIn'] == true &&
                      account?['canUsePlan'] == true
                  ? _refreshModels
                  : null,
              icon: _loadingModels
                  ? const SettingsBusy()
                  : const Icon(Icons.refresh),
              label: const Text('刷新列表'),
            ),
          ],
        ),
        const SizedBox(height: 8),
        if (_models.isNotEmpty && _catalogAccountId == _accountId)
          DropdownButtonFormField<String>(
            initialValue: _models.any((model) => model.slug == _selectedModel)
                ? _selectedModel
                : null,
            key: ValueKey(
              '$_catalogAccountId:$_selectedModel:${_models.map((model) => model.slug).join(',')}',
            ),
            isExpanded: true,
            decoration: const InputDecoration(labelText: '选择模型'),
            items: _models
                .map(
                  (model) => DropdownMenuItem(
                    value: model.slug,
                    child: Text(
                      model.displayName,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                )
                .toList(),
            onChanged: controlsEnabled
                ? (value) => setState(() {
                    _selectedModel = value!;
                    _dirty = true;
                  })
                : null,
          )
        else
          InputDecorator(
            decoration: const InputDecoration(labelText: '当前模型'),
            child: Text(
              _selectedModel.isEmpty ? '刷新列表后选择' : '$_selectedModel（刷新后核对可用性）',
            ),
          ),
        if (_modelsError != null)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: Text(
              _modelsError!,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ),
      ],
    );
  }

  Widget _apiConfiguration(bool qwen) {
    final hasKey =
        widget.controller.settings[qwen ? 'hasQwenKey' : 'hasDeepSeekKey'] ==
        true;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        TextField(
          controller: qwen ? _qwenKey : _deepSeekKey,
          enabled: !_busy,
          obscureText: true,
          autocorrect: false,
          enableSuggestions: false,
          keyboardType: TextInputType.visiblePassword,
          decoration: InputDecoration(
            labelText: 'API Key',
            hintText: hasKey ? '已保存密钥，留空保持不变' : '填写服务提供的 API Key',
            helperText: hasKey ? '已有密钥不会显示。填写新密钥会替换原密钥。' : '密钥加密保存在本机。',
            helperMaxLines: 2,
          ),
        ),
        if (hasKey)
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton(
              onPressed: _busy ? null : () => _removeKey(qwen),
              style: TextButton.styleFrom(
                foregroundColor: Theme.of(context).colorScheme.error,
              ),
              child: const Text('删除已保存的密钥'),
            ),
          ),
        const SizedBox(height: 20),
        if (qwen) ...[
          TextField(
            controller: _qwenUrl,
            enabled: !_busy,
            keyboardType: TextInputType.url,
            autocorrect: false,
            decoration: const InputDecoration(
              labelText: '服务地址（Base URL）',
              helperText: '填写服务商提供的 HTTPS 兼容接口地址，通常以 /v1 结尾。',
              helperMaxLines: 3,
            ),
          ),
          const SizedBox(height: 20),
        ],
        TextField(
          controller: qwen ? _qwenModel : _deepSeekModel,
          enabled: !_busy,
          autocorrect: false,
          decoration: InputDecoration(
            labelText: '模型名称',
            helperText: qwen ? '例如 qwen-plus，需与服务地址对应。' : '例如 deepseek-chat。',
          ),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: widget.controller,
    builder: (context, _) => PopScope(
      canPop: _canPop || (!_dirty && !_busy),
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop) _leave();
      },
      child: Scaffold(
        appBar: AppBar(title: const Text('AI 服务')),
        body: SafeArea(
          top: false,
          child: SettingsList(
            padding: const EdgeInsets.fromLTRB(20, 16, 20, 32),
            children: [
              SectionSurface(
                padding: const EdgeInsets.all(20),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(
                      '用于文字解析、语音记账和账本问答。',
                      style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                    ),
                    const SizedBox(height: 20),
                    DropdownButtonFormField<String>(
                      initialValue: _provider,
                      isExpanded: true,
                      decoration: const InputDecoration(labelText: '使用的服务'),
                      items: const [
                        DropdownMenuItem(
                          value: 'chatgpt',
                          child: Text('ChatGPT'),
                        ),
                        DropdownMenuItem(
                          value: 'deepseek',
                          child: Text('DeepSeek'),
                        ),
                        DropdownMenuItem(
                          value: 'qwen',
                          child: Text('通义千问 / 百炼'),
                        ),
                      ],
                      onChanged: _busy || _authorizing
                          ? null
                          : (value) => setState(() {
                              _provider = value!;
                              _dirty = true;
                              _error = null;
                            }),
                    ),
                  ],
                ),
              ),
              if (widget.controller.settings['needsReconfiguration'] ==
                  true) ...[
                const SizedBox(height: 16),
                Text(
                  '本机凭据暂时无法读取，请稍后重试；如仍不可用，可重新配置 AI 服务。账本数据不受影响。',
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ],
              SectionTitle(_provider == 'chatgpt' ? '账号与授权' : '连接信息'),
              SectionSurface(
                padding: const EdgeInsets.all(20),
                child: _provider == 'chatgpt'
                    ? _chatGpt(context)
                    : _apiConfiguration(_provider == 'qwen'),
              ),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 24),
                  child: Semantics(
                    liveRegion: true,
                    child: Text(
                      _error!,
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ),
                  ),
                ),
              const SizedBox(height: 28),
              FilledButton(
                onPressed: _busy || _authorizing || _loadingModels
                    ? null
                    : _save,
                child: _busy ? const SettingsBusy() : const Text('保存配置'),
              ),
              const SizedBox(height: 20),
              Text(
                '登录凭据与 API Key 加密保存在本机，不包含在账本备份中。使用 AI 功能时，输入与相关账本查询数据会发送给所选服务。',
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
            ],
          ),
        ),
      ),
    ),
  );
}

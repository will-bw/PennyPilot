import 'dart:convert';

import 'package:flutter/material.dart';

import '../../core/ledger_controller.dart';
import '../../core/models.dart';
import '../../ui/common.dart';
import '../entry/entry_screen.dart';
import 'settings_widgets.dart';

String _reason(String? reason) => switch (reason) {
  'TIMEOUT' => '识别超时，自动重试已暂停',
  'NETWORK' => '识别服务暂不可用，自动重试已暂停',
  'INCOMPLETE' => '凭证字段不完整或相互冲突，需要核对',
  'EXPIRED' => '超过自动识别时限，等待你处理',
  'SAVE_FAILED' => '识别结果未能保存，需要重试',
  _ => '等待联网识别，完成后会移出此列表',
};

String _date(Object? value) {
  if (value is! int) return '';
  final date = DateTime.fromMillisecondsSinceEpoch(value);
  return '${date.year}/${date.month}/${date.day} ${timeLabel(date)}';
}

class CapturePendingScreen extends StatefulWidget {
  const CapturePendingScreen({super.key, required this.controller});
  final LedgerController controller;

  @override
  State<CapturePendingScreen> createState() => _CapturePendingScreenState();
}

class _CapturePendingScreenState extends State<CapturePendingScreen>
    with WidgetsBindingObserver {
  List<Map<String, dynamic>> _items = [];
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final response = stringMap(
        await widget.controller.command(
          'capturePendingList',
          ledger: true,
          refreshAfter: false,
        ),
      );
      if (mounted) {
        setState(
          () => _items = (response['items'] as List)
              .map(stringMap)
              .toList(growable: false),
        );
      }
    } catch (error) {
      if (mounted) setState(() => _error = settingsError(error));
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _open(Map<String, dynamic> item) async {
    await Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => _CapturePendingDetailScreen(
          controller: widget.controller,
          item: item,
        ),
      ),
    );
    if (mounted) await _load();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('未识别凭证'),
      actions: [
        IconButton(
          tooltip: '刷新凭证',
          onPressed: _loading ? null : _load,
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: SafeArea(
      top: false,
      child: SettingsList(
        children: [
          const SettingsNotice(
            '这里保留尚未入账的支付凭证。识别失败后可重试或手动补录；凭证在本机加密保存，采集 7 天后自动清除。',
          ),
          if (_loading) const LinearProgressIndicator(),
          if (_error != null) ...[
            SettingsNotice(_error!, error: true),
            Center(
              child: TextButton(onPressed: _load, child: const Text('重新加载')),
            ),
          ] else if (!_loading && _items.isEmpty)
            const SettingsNotice('没有待处理的凭证。已入账的记录可在流水中核对。')
          else
            for (final item in _items)
              Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 20,
                  vertical: 6,
                ),
                child: Card(
                  margin: EdgeInsets.zero,
                  child: ListTile(
                    contentPadding: const EdgeInsets.all(16),
                    leading: Icon(
                      item['hasImage'] == true
                          ? Icons.receipt_long_outlined
                          : Icons.description_outlined,
                    ),
                    title: Text(
                      '${item['appName'] ?? '支付'} · ${item['state'] == 'NEEDS_ATTENTION' ? '需要处理' : '等待识别'}',
                    ),
                    subtitle: Text(
                      '${_reason(item['reason'] as String?)}\n采集于 ${_date(item['observedAt'])}',
                    ),
                    trailing: const Icon(Icons.chevron_right),
                    onTap: () => _open(item),
                  ),
                ),
              ),
        ],
      ),
    ),
  );
}

class _CapturePendingDetailScreen extends StatefulWidget {
  const _CapturePendingDetailScreen({
    required this.controller,
    required this.item,
  });
  final LedgerController controller;
  final Map<String, dynamic> item;

  @override
  State<_CapturePendingDetailScreen> createState() =>
      _CapturePendingDetailScreenState();
}

class _CapturePendingDetailScreenState
    extends State<_CapturePendingDetailScreen> {
  Map<String, dynamic>? _detail;
  String? _error;
  bool _busy = false;
  LedgerTransaction? _manualDraft;
  late final int _revision = widget.controller.revision;
  bool get _canRetry => widget.item['state'] == 'NEEDS_ATTENTION';
  Map<String, dynamic> get _identity => {
    'taskId': widget.item['id'],
    'version': widget.item['version'],
    'revision': _revision,
  };

  @override
  void initState() {
    super.initState();
    _read();
  }

  Future<void> _read() async {
    setState(() => _error = null);
    try {
      final detail = stringMap(
        await widget.controller.command(
          'capturePendingDetail',
          arguments: _identity,
          ledger: true,
          refreshAfter: false,
        ),
      );
      if (mounted) setState(() => _detail = detail);
    } catch (error) {
      if (mounted) setState(() => _error = settingsError(error));
    }
  }

  Future<void> _perform(
    String method, {
    Map<String, dynamic> extra = const {},
  }) async {
    if (_busy) return;
    setState(() => _busy = true);
    final ok = await runUi(
      context,
      () => widget.controller.command(
        method,
        arguments: {..._identity, ...extra},
        ledger: true,
      ),
    );
    if (!mounted) return;
    setState(() => _busy = false);
    if (ok) Navigator.of(context).pop();
  }

  Future<void> _manual() async {
    LedgerTransaction? draft;
    await Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => EntryScreen(
          controller: widget.controller,
          transaction: _manualDraft,
          onDraftSaved: (value) => draft = value,
        ),
      ),
    );
    if (mounted && draft != null) {
      setState(() => _manualDraft = draft);
      await _perform(
        'capturePendingComplete',
        extra: {'transaction': draft!.toMap()},
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final images =
        (_detail?['images'] as List?)?.cast<String>() ?? const <String>[];
    final text = _detail?['text'] as String? ?? '';
    return Scaffold(
      appBar: AppBar(title: const Text('核对凭证')),
      body: SafeArea(
        top: false,
        child: SettingsList(
          padding: const EdgeInsets.all(20),
          children: [
            Text(
              _reason(widget.item['reason'] as String?),
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 8),
            Text(
              '采集于 ${_date(widget.item['observedAt'])}\n保留至 ${_date(widget.item['expiresAt'])}',
            ),
            const SizedBox(height: 20),
            if (_error != null) ...[
              SettingsNotice(_error!, error: true),
              TextButton(onPressed: _read, child: const Text('重新加载凭证')),
            ] else if (_detail == null)
              const Center(child: CircularProgressIndicator())
            else ...[
              for (final image in images)
                Padding(
                  padding: const EdgeInsets.only(bottom: 16),
                  child: Image.memory(
                    base64Decode(image.substring(image.indexOf(',') + 1)),
                    fit: BoxFit.contain,
                    semanticLabel: '待识别的支付凭证截图',
                    errorBuilder: (_, _, _) => const Text('截图无法显示，可重新采集凭证。'),
                  ),
                ),
              if (text.isNotEmpty && text != '支付凭证图片') SelectableText(text),
              const SizedBox(height: 20),
              if (_canRetry) ...[
                FilledButton.icon(
                  onPressed: _busy
                      ? null
                      : () => _perform('capturePendingRetry'),
                  icon: const Icon(Icons.refresh),
                  label: const Text('重新识别'),
                ),
                const SizedBox(height: 8),
                OutlinedButton.icon(
                  onPressed: _busy ? null : _manual,
                  icon: const Icon(Icons.edit_outlined),
                  label: Text(_manualDraft == null ? '按凭证手动补录' : '继续补录'),
                ),
                const SizedBox(height: 8),
                const Text('补录时请核对实际交易日期，采集时间不一定是交易时间。'),
              ],
              const SizedBox(height: 8),
              TextButton(
                onPressed: _busy
                    ? null
                    : () => _perform('capturePendingDelete'),
                child: const Text('移除此凭证'),
              ),
              if (_busy) const LinearProgressIndicator(),
            ],
          ],
        ),
      ),
    );
  }
}

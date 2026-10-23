import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/core/models.dart';

import 'settings_widgets.dart';

const _paymentApps = {
  'com.tencent.mm': '微信',
  'com.eg.android.AlipayGphone': '支付宝',
  'com.sankuai.meituan': '美团',
  'com.ss.android.ugc.aweme': '抖音',
};

class RulesScreen extends StatefulWidget {
  const RulesScreen({super.key, required this.controller});
  final LedgerController controller;
  @override
  State<RulesScreen> createState() => _RulesScreenState();
}

class _RulesScreenState extends State<RulesScreen> {
  bool _busy = false;
  String? _error;

  Future<void> _edit([ParseRule? rule]) => Navigator.push<void>(
    context,
    MaterialPageRoute(
      builder: (_) => _RuleEditor(controller: widget.controller, rule: rule),
    ),
  );

  Future<void> _run(Future<void> Function() action, String success) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await action();
      if (mounted) settingsFeedback(context, success);
    } catch (error) {
      if (mounted) setState(() => _error = settingsError(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _reset() async {
    if (!await confirmSettingsAction(
      context,
      title: '恢复预置规则？',
      message: '当前所有规则（包括自定义规则和修改）会被预置规则替换。已有账单不受影响。',
      confirmLabel: '恢复预置规则',
      destructive: true,
    )) {
      return;
    }
    if (mounted) await _run(widget.controller.resetRules, '已恢复预置规则');
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: widget.controller,
    builder: (context, _) {
      final rules = widget.controller.rules.toList()
        ..sort((a, b) => a.priority.compareTo(b.priority));
      return Scaffold(
        appBar: AppBar(
          title: const Text('通知解析规则'),
          actions: [
            PopupMenuButton<String>(
              tooltip: '规则操作',
              enabled: !_busy,
              onSelected: (_) => _reset(),
              itemBuilder: (_) => [
                const PopupMenuItem(value: 'reset', child: Text('恢复预置规则')),
              ],
            ),
          ],
        ),
        floatingActionButton: FloatingActionButton.extended(
          onPressed: _busy ? null : () => _edit(),
          icon: const Icon(Icons.add),
          label: const Text('新增规则'),
        ),
        body: SafeArea(
          top: false,
          child: ListView(
            padding: const EdgeInsets.only(bottom: 100),
            children: [
              if (_busy) const LinearProgressIndicator(),
              const SettingsNotice(
                '规则在本机匹配支付通知，优先级数值越小越先执行。支付页面读取使用内置识别，不受这里的规则影响。',
              ),
              if (_error != null) SettingsNotice(_error!, error: true),
              if (rules.isEmpty)
                const Padding(
                  padding: EdgeInsets.all(24),
                  child: Text('暂无通知规则，可新增规则或恢复预置规则。'),
                ),
              for (final rule in rules)
                ListTile(
                  contentPadding: const EdgeInsets.symmetric(
                    horizontal: 24,
                    vertical: 8,
                  ),
                  title: Text(_paymentApps[rule.appPackage] ?? rule.appPackage),
                  subtitle: Padding(
                    padding: const EdgeInsets.only(top: 4),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '${rule.directionHint.label} · 优先级 ${rule.priority}${rule.enabled ? '' : ' · 已停用'}',
                        ),
                        const SizedBox(height: 4),
                        Text(
                          rule.regexAmount,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ],
                    ),
                  ),
                  onTap: _busy ? null : () => _edit(rule),
                  trailing: Semantics(
                    label:
                        '${rule.enabled ? '停用' : '启用'}${_paymentApps[rule.appPackage] ?? rule.appPackage}规则',
                    child: Switch(
                      value: rule.enabled,
                      onChanged: _busy
                          ? null
                          : (value) => _run(() async {
                              await widget.controller.saveRule(
                                rule.copyWith(enabled: value),
                              );
                            }, value ? '规则已启用' : '规则已停用'),
                    ),
                  ),
                ),
            ],
          ),
        ),
      );
    },
  );
}

class _RuleEditor extends StatefulWidget {
  const _RuleEditor({required this.controller, this.rule});
  final LedgerController controller;
  final ParseRule? rule;
  @override
  State<_RuleEditor> createState() => _RuleEditorState();
}

class _RuleEditorState extends State<_RuleEditor> {
  late final TextEditingController _package, _amount, _merchant, _priority;
  late TxType _direction;
  late bool _enabled;
  late int _revision;
  bool _dirty = false, _busy = false, _canPop = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    final rule = widget.rule;
    _package = TextEditingController(text: rule?.appPackage ?? '');
    _amount = TextEditingController(text: rule?.regexAmount ?? '');
    _merchant = TextEditingController(text: rule?.regexMerchant ?? '');
    _priority = TextEditingController(text: '${rule?.priority ?? 0}');
    _direction = rule?.directionHint ?? TxType.expense;
    _enabled = rule?.enabled ?? true;
    _revision = widget.controller.snapshot?.revision ?? -1;
    for (final controller in [_package, _amount, _merchant, _priority]) {
      controller.addListener(() {
        if (!_dirty && mounted) setState(() => _dirty = true);
      });
    }
  }

  @override
  void dispose() {
    for (final controller in [_package, _amount, _merchant, _priority]) {
      controller.dispose();
    }
    super.dispose();
  }

  Future<void> _leave() async {
    if (_busy) return;
    if (_dirty &&
        !await confirmSettingsAction(
          context,
          title: '放弃未保存的规则？',
          message: '修改尚未保存，返回后需要重新填写。',
          confirmLabel: '放弃修改',
        )) {
      return;
    }
    if (mounted) {
      setState(() => _canPop = true);
      Navigator.pop(context);
    }
  }

  Future<void> _save() async {
    final priority = int.tryParse(_priority.text.trim());
    final problem = _package.text.trim().isEmpty
        ? '请填写应用包名。'
        : _amount.text.trim().isEmpty
        ? '请填写金额正则，至少包含一个捕获组。'
        : priority == null || priority < -2147483648 || priority > 2147483647
        ? '优先级须为有效整数。'
        : null;
    if (problem != null) {
      setState(() => _error = problem);
      return;
    }
    final rule = ParseRule(
      id: widget.rule?.id ?? 0,
      appPackage: _package.text.trim(),
      regexAmount: _amount.text.trim(),
      regexMerchant: _merchant.text.trim().isEmpty
          ? null
          : _merchant.text.trim(),
      directionHint: _direction,
      enabled: _enabled,
      priority: priority!,
    );
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await widget.controller.command(
        'saveRule',
        arguments: {...rule.toMap(), 'revision': _revision},
        ledger: true,
      );
      if (mounted) {
        settingsFeedback(context, '规则已保存');
        setState(() => _canPop = true);
        Navigator.pop(context);
      }
    } catch (error) {
      if (mounted) setState(() => _error = settingsError(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _delete() async {
    if (!await confirmSettingsAction(
      context,
      title: '删除此规则？',
      message: '删除后将不再使用此规则识别新通知。已有账单不受影响。',
      confirmLabel: '删除规则',
      destructive: true,
    )) {
      return;
    }
    if (!mounted) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await widget.controller.command(
        'deleteRule',
        arguments: {'id': widget.rule!.id, 'revision': _revision},
        ledger: true,
      );
      if (mounted) {
        settingsFeedback(context, '规则已删除');
        setState(() => _canPop = true);
        Navigator.pop(context);
      }
    } catch (error) {
      if (mounted) setState(() => _error = settingsError(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: _canPop || (!_dirty && !_busy),
    onPopInvokedWithResult: (didPop, result) {
      if (!didPop) _leave();
    },
    child: Scaffold(
      appBar: AppBar(
        title: Text(widget.rule == null ? '新增规则' : '编辑规则'),
        actions: [
          TextButton(
            onPressed: _busy ? null : _save,
            child: _busy ? const SettingsBusy() : const Text('保存'),
          ),
        ],
      ),
      body: SafeArea(
        top: false,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(24, 20, 24, 32),
          children: [
            TextField(
              controller: _package,
              enabled: !_busy,
              autocorrect: false,
              decoration: const InputDecoration(
                labelText: '应用包名',
                hintText: '例如 com.tencent.mm',
              ),
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final entry in _paymentApps.entries)
                  ActionChip(
                    label: Text(entry.value),
                    onPressed: _busy
                        ? null
                        : () {
                            _package.text = entry.key;
                          },
                  ),
              ],
            ),
            const SizedBox(height: 24),
            TextField(
              controller: _amount,
              enabled: !_busy,
              autocorrect: false,
              enableSuggestions: false,
              minLines: 2,
              maxLines: 5,
              decoration: const InputDecoration(
                labelText: '金额正则',
                helperText: r'第一个捕获组提取金额，例如 ([0-9]+\.[0-9]{2})',
                helperMaxLines: 3,
              ),
            ),
            const SizedBox(height: 20),
            TextField(
              controller: _merchant,
              enabled: !_busy,
              autocorrect: false,
              enableSuggestions: false,
              minLines: 2,
              maxLines: 5,
              decoration: const InputDecoration(
                labelText: '商户正则（选填）',
                helperText: '填写时需包含捕获组，用于提取商户名称。',
                helperMaxLines: 2,
              ),
            ),
            const SizedBox(height: 24),
            Text('交易方向', style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 12),
            SegmentedButton<TxType>(
              segments: const [
                ButtonSegment(value: TxType.expense, label: Text('支出')),
                ButtonSegment(value: TxType.income, label: Text('收入')),
              ],
              selected: {_direction},
              onSelectionChanged: _busy
                  ? null
                  : (value) => setState(() {
                      _direction = value.first;
                      _dirty = true;
                    }),
            ),
            const SizedBox(height: 24),
            TextField(
              controller: _priority,
              enabled: !_busy,
              keyboardType: const TextInputType.numberWithOptions(signed: true),
              inputFormatters: [
                FilteringTextInputFormatter.allow(RegExp(r'[-0-9]')),
              ],
              decoration: const InputDecoration(
                labelText: '优先级',
                helperText: '整数，数值越小越先执行。',
              ),
            ),
            const SizedBox(height: 20),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('启用规则'),
              value: _enabled,
              onChanged: _busy
                  ? null
                  : (value) => setState(() {
                      _enabled = value;
                      _dirty = true;
                    }),
            ),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(top: 16),
                child: Text(
                  _error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ),
            if (widget.rule != null) ...[
              const SizedBox(height: 24),
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  onPressed: _busy ? null : _delete,
                  style: TextButton.styleFrom(
                    foregroundColor: Theme.of(context).colorScheme.error,
                  ),
                  icon: const Icon(Icons.delete_outline),
                  label: const Text('删除规则'),
                ),
              ),
            ],
          ],
        ),
      ),
    ),
  );
}

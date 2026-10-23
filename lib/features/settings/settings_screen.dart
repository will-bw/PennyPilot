import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/core/models.dart';
import 'package:pennypilot/core/updates/app_update_service.dart';

import 'ai_settings_screen.dart';
import 'capture_settings_screen.dart';
import 'categories_screen.dart';
import 'rules_screen.dart';
import 'settings_widgets.dart';
import 'update_settings_screen.dart';
import 'voice_settings_screen.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key, required this.controller});
  final LedgerController controller;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  bool _backupBusy = false;
  bool _aboutBusy = false;

  Future<void> _open(Widget page) =>
      Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => page));

  Future<void> _about() async {
    if (_aboutBusy) return;
    setState(() => _aboutBusy = true);
    final updates = AppUpdateService();
    InstalledAppVersion? installed;
    try {
      installed = await updates.installedVersion();
    } catch (_) {
      // About and license information remain available if metadata is missing.
    } finally {
      updates.dispose();
      if (mounted) setState(() => _aboutBusy = false);
    }
    if (!mounted) return;
    showAboutDialog(
      context: context,
      applicationName: 'PennyPilot',
      applicationVersion: installed == null
          ? '版本信息暂不可用'
          : '${installed.version}（构建 ${installed.versionCode}）',
      applicationIcon: Icon(
        Icons.account_balance_wallet_outlined,
        size: 40,
        color: Theme.of(context).colorScheme.primary,
      ),
      children: const [
        Text('把日常收支记清楚。账本保存在本机，支持手动与 AI 记账、预算、报表和跨平台备份。Android 还支持支付通知与页面采集。'),
        SizedBox(height: 12),
        Text('使用 AI 功能时，输入与相关查询数据会发送给所选服务。登录凭据和 API Key 加密保存在本机，不包含在账本备份中。'),
      ],
    );
  }

  Future<void> _backup({required bool restore}) async {
    if (restore) {
      final proceed = await confirmSettingsAction(
        context,
        title: '从备份恢复账本',
        message: '备份中的账单、分类、解析规则和月报将替换当前账本。建议先导出当前账本。登录账号和 API Key 保持不变。',
        confirmLabel: '继续恢复',
        destructive: true,
      );
      if (!proceed || !mounted) return;
      final confirmed = await confirmSettingsAction(
        context,
        title: '确认替换当前账本？',
        message: '此操作会覆盖当前账本，无法在应用内撤销。请选择 PennyPilot 导出的 JSON 跨平台备份。',
        confirmLabel: '选择备份并替换',
        destructive: true,
      );
      if (!confirmed || !mounted) return;
    }
    setState(() => _backupBusy = true);
    try {
      final completed = restore
          ? await widget.controller.restoreBackup()
          : await widget.controller.exportBackup();
      if (mounted) {
        settingsFeedback(
          context,
          completed ? (restore ? '账本已恢复，立即生效' : '备份已保存到所选位置') : '已取消',
        );
      }
    } catch (error) {
      if (mounted) settingsFeedback(context, settingsError(error));
    } finally {
      if (mounted) setState(() => _backupBusy = false);
    }
  }

  Future<void> _theme() async {
    final current =
        widget.controller.settings['themeMode'] as String? ?? 'system';
    final selected = await showDialog<String>(
      context: context,
      builder: (context) => SimpleDialog(
        title: const Text('外观'),
        children: [
          for (final entry in const {
            'system': '跟随系统',
            'light': '明亮',
            'dark': '深色',
          }.entries)
            SimpleDialogOption(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
              onPressed: () => Navigator.pop(context, entry.key),
              child: Row(
                children: [
                  Expanded(child: Text(entry.value)),
                  if (entry.key == current) const Icon(Icons.check),
                ],
              ),
            ),
          Align(
            alignment: Alignment.centerRight,
            child: TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('取消'),
            ),
          ),
        ],
      ),
    );
    if (selected == null || !mounted) return;
    try {
      await widget.controller.updateSettings({'themeMode': selected});
    } catch (error) {
      if (mounted) settingsFeedback(context, settingsError(error));
    }
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: widget.controller,
    builder: (context, _) {
      final settings = widget.controller.settings;
      final android = widget.controller.capabilities['platform'] == 'android';
      final budget = (settings['budgetCents'] as num?)?.toInt() ?? 0;
      final theme =
          const {
            'system': '跟随系统',
            'light': '明亮',
            'dark': '深色',
          }[settings['themeMode']] ??
          '跟随系统';
      final provider =
          const {
            'chatgpt': 'ChatGPT',
            'deepseek': 'DeepSeek',
            'qwen': '通义千问',
          }[settings['activeProvider']] ??
          '尚未配置';
      return Scaffold(
        appBar: AppBar(title: const Text('设置')),
        body: SafeArea(
          top: false,
          child: SettingsList(
            padding: const EdgeInsets.only(bottom: 32),
            children: [
              SettingsSection(
                title: '账本',
                children: [
                  SettingsRow(
                    title: '每月预算',
                    subtitle: budget > 0
                        ? '¥${formatMoney(budget)} / 月'
                        : '设定本月支出上限',
                    icon: Icons.account_balance_wallet_outlined,
                    onTap: () => showBudgetSettings(context, widget.controller),
                  ),
                  SettingsRow(
                    title: '分类管理',
                    subtitle: '名称、图标与启用状态',
                    icon: Icons.category_outlined,
                    onTap: () =>
                        _open(CategoriesScreen(controller: widget.controller)),
                  ),
                  SettingsRow(
                    title: '外观',
                    subtitle: theme,
                    icon: Icons.contrast_outlined,
                    onTap: widget.controller.isBusy ? null : _theme,
                  ),
                ],
              ),
              SettingsSection(
                title: '记账方式',
                children: [
                  if (android)
                    SettingsRow(
                      title: '自动采集与权限',
                      subtitle: '支付通知、支付页面与运行状态',
                      icon: Icons.notifications_active_outlined,
                      onTap: () => _open(
                        CaptureSettingsScreen(controller: widget.controller),
                      ),
                    ),
                  if (android)
                    SettingsRow(
                      title: '通知解析规则',
                      subtitle: '优先使用本地规则识别支付通知',
                      icon: Icons.rule_outlined,
                      onTap: () =>
                          _open(RulesScreen(controller: widget.controller)),
                    ),
                  SettingsRow(
                    title: 'AI 服务',
                    subtitle: provider,
                    icon: Icons.tune_outlined,
                    onTap: () =>
                        _open(AiSettingsScreen(controller: widget.controller)),
                  ),
                  SettingsRow(
                    title: '语音模型',
                    subtitle: settings['hasSpeechKey'] == true
                        ? '${settings['speechModel']} · 可录音测试'
                        : '配置语音转写服务与模型',
                    icon: Icons.mic_none,
                    onTap: () => _open(
                      VoiceSettingsScreen(controller: widget.controller),
                    ),
                  ),
                  if (!android)
                    SettingsRow(
                      title: '语音与系统权限',
                      subtitle: '管理麦克风与语音识别授权',
                      icon: Icons.mic_none,
                      onTap: () => _open(
                        CaptureSettingsScreen(controller: widget.controller),
                      ),
                    ),
                ],
              ),
              SettingsSection(
                title: '数据与应用',
                children: [
                  SettingsRow(
                    title: '导出账本备份',
                    subtitle:
                        '上次备份：${settingsTimestamp((settings['lastBackupAt'] as num?)?.toInt() ?? 0)}',
                    icon: Icons.file_upload_outlined,
                    enabled: !_backupBusy,
                    onTap: () => _backup(restore: false),
                    trailing: _backupBusy ? const SettingsBusy() : null,
                  ),
                  SettingsRow(
                    title: '从备份恢复',
                    subtitle: '恢复 Android 或 iOS 导出的 JSON 账本',
                    icon: Icons.restore_outlined,
                    enabled: !_backupBusy,
                    onTap: () => _backup(restore: true),
                  ),
                  SettingsRow(
                    title: '检查更新',
                    subtitle: '查看新版本与更新说明',
                    icon: Icons.system_update_outlined,
                    onTap: () => _open(const UpdateSettingsScreen()),
                  ),
                  SettingsRow(
                    title: '关于 PennyPilot',
                    subtitle: '版本、隐私与应用信息',
                    icon: Icons.info_outline,
                    enabled: !_aboutBusy,
                    onTap: _about,
                    trailing: _aboutBusy ? const SettingsBusy() : null,
                  ),
                ],
              ),
              const SettingsNotice('备份包含账单、分类、通知解析规则和月报。建议定期保存到你信任的位置。'),
            ],
          ),
        ),
      );
    },
  );
}

Future<void> showBudgetSettings(
  BuildContext context,
  LedgerController controller,
) async {
  await showDialog<void>(
    context: context,
    builder: (_) => _BudgetDialog(controller: controller),
  );
}

class _BudgetDialog extends StatefulWidget {
  const _BudgetDialog({required this.controller});
  final LedgerController controller;
  @override
  State<_BudgetDialog> createState() => _BudgetDialogState();
}

class _BudgetDialogState extends State<_BudgetDialog> {
  late final TextEditingController _amount;
  String? _error;
  bool _busy = false;
  @override
  void initState() {
    super.initState();
    final cents =
        (widget.controller.settings['budgetCents'] as num?)?.toInt() ?? 0;
    _amount = TextEditingController(
      text: cents > 0 ? formatMoney(cents, group: false) : '',
    );
  }

  @override
  void dispose() {
    _amount.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final cents = parseBudgetCents(_amount.text);
    if (cents == null) {
      setState(() => _error = '请输入有效金额，最多两位小数；输入 0 可关闭预算。');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await widget.controller.setBudget(cents);
      if (mounted) {
        settingsFeedback(context, cents == 0 ? '已关闭月预算' : '月预算已保存');
        Navigator.pop(context);
      }
    } catch (error) {
      if (mounted) setState(() => _error = settingsError(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('每月预算'),
    content: SingleChildScrollView(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('根据本月已确认的支出计算使用进度。此金额每月沿用，输入 0 可关闭。'),
          const SizedBox(height: 20),
          TextField(
            controller: _amount,
            autofocus: true,
            enabled: !_busy,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            inputFormatters: [
              FilteringTextInputFormatter.allow(RegExp(r'[0-9.]')),
            ],
            decoration: InputDecoration(
              labelText: '每月预算',
              prefixText: '¥ ',
              errorText: _error,
              errorMaxLines: 3,
            ),
            onSubmitted: (_) {
              if (!_busy) _save();
            },
          ),
        ],
      ),
    ),
    actions: [
      TextButton(
        onPressed: _busy ? null : () => Navigator.pop(context),
        child: const Text('取消'),
      ),
      FilledButton(
        onPressed: _busy ? null : _save,
        child: _busy ? const SettingsBusy() : const Text('保存'),
      ),
    ],
  );
}

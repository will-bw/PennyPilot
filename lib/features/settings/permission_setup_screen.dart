import 'package:flutter/material.dart';

import '../../core/ledger_controller.dart';
import 'capture_consent.dart';
import 'capture_background_guidance.dart';
import 'capture_settings_screen.dart';
import 'settings_widgets.dart';

/// The same permission overview serves first use and later settings visits.
/// No permission is requested until the user chooses its corresponding action.
class PermissionSetupScreen extends StatefulWidget {
  const PermissionSetupScreen({
    super.key,
    required this.controller,
    this.firstRun = false,
    this.onBack,
  });

  final LedgerController controller;
  final bool firstRun;
  final VoidCallback? onBack;

  @override
  State<PermissionSetupScreen> createState() => _PermissionSetupScreenState();
}

class _PermissionSetupScreenState extends State<PermissionSetupScreen>
    with WidgetsBindingObserver {
  bool _busy = false;
  bool _microphoneDenied = false, _remindersDenied = false;
  String? _error;

  LedgerController get c => widget.controller;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) c.refresh();
  }

  Future<void> _run(Future<void> Function() action) async {
    if (_busy || c.isBusy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await action();
    } catch (error) {
      if (mounted) setState(() => _error = settingsError(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _notifications() => _run(() async {
    if (c.settings['notificationCaptureEnabled'] != true) {
      await c.updateSettings({'notificationCaptureEnabled': true});
    }
    if (!mounted) return;
    if (c.capabilities['notificationListenerGranted'] != true) {
      await c.openSystemSettings('notificationListener');
    } else if (c.capabilities['notificationServiceConnected'] != true) {
      await c.rebindNotificationService();
    } else {
      await c.openSystemSettings('notificationListener');
    }
  });

  Future<void> _screen() => _run(() async {
    if (c.settings['screenCaptureEnabled'] != true) {
      final confirmed = await confirmScreenCapture(context);
      if (!confirmed || !mounted) return;
      await c.updateSettings({'screenCaptureEnabled': true});
      if (c.capabilities['accessibilityGranted'] == true &&
          c.capabilities['accessibilityServiceConnected'] == true) {
        return;
      }
    }
    if (mounted) await c.openSystemSettings('accessibility');
  });

  Future<void> _microphone() => _run(() async {
    if (_microphoneDenied || c.capabilities['microphoneGranted'] == true) {
      await c.openSystemSettings('microphone');
      return;
    }
    final granted = await c.requestMicrophonePermission();
    if (!mounted) return;
    setState(() => _microphoneDenied = !granted);
    if (!granted) {
      settingsFeedback(context, '麦克风未允许，可打开系统设置，或在首次录音时再授权。');
    }
  });

  Future<void> _reminders() => _run(() async {
    if (_remindersDenied || c.capabilities['remindersEnabled'] == true) {
      await c.openSystemSettings('notifications');
      return;
    }
    await c.requestNotificationPermission();
    if (!mounted) return;
    final enabled = c.capabilities['remindersEnabled'] == true;
    setState(() => _remindersDenied = !enabled);
    if (!enabled) {
      settingsFeedback(context, '提醒尚未开启，可打开系统通知设置检查；账单仍会保存在待确认列表。');
    }
  });

  String _captureStatus(String setting, String permission, String connection) {
    if (c.capabilities[permission] != true) return '未授权';
    if (c.settings[setting] != true) return '已授权 · 采集已暂停';
    if (c.capabilities[connection] != true) return '已授权 · 服务未连接';
    return '已就绪';
  }

  Future<void> _finish() => _run(() async {
    if (widget.firstRun) {
      await c.updateSettings({'onboardingDone': true});
    } else if (mounted) {
      Navigator.of(context).pop();
    }
  });

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: c,
    builder: (context, _) {
      final android = c.capabilities['platform'] == 'android';
      final colors = Theme.of(context).colorScheme;
      final disabled = _busy || c.isBusy;
      final notificationReady =
          _captureStatus(
            'notificationCaptureEnabled',
            'notificationListenerGranted',
            'notificationServiceConnected',
          ) ==
          '已就绪';
      final screenReady =
          _captureStatus(
            'screenCaptureEnabled',
            'accessibilityGranted',
            'accessibilityServiceConnected',
          ) ==
          '已就绪';
      final microphone = c.capabilities['microphoneGranted'] == true;
      final reminders = c.capabilities['remindersEnabled'] == true;
      final battery = c.capabilities['batteryExempt'] == true;
      return PopScope(
        canPop: !widget.firstRun && !disabled,
        onPopInvokedWithResult: (didPop, _) {
          if (!didPop && widget.firstRun && !disabled) widget.onBack?.call();
        },
        child: Scaffold(
          appBar: AppBar(
            title: const Text('权限设置'),
            leading: widget.firstRun
                ? BackButton(onPressed: disabled ? null : widget.onBack)
                : null,
            actions: [
              IconButton(
                tooltip: '刷新权限状态',
                onPressed: disabled ? null : () => _run(c.refresh),
                icon: const Icon(Icons.refresh),
              ),
            ],
          ),
          body: SafeArea(
            bottom: false,
            child: SettingsList(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 16, 20, 0),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        widget.firstRun ? '按需要开启，记账更省心' : '集中管理记账权限',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      const SizedBox(height: 12),
                      Text(
                        widget.firstRun
                            ? '逐项了解用途，再决定是否开启。所有权限都可跳过，手动记账随时可用。以后可在「设置 → 权限设置」继续。'
                            : '从系统设置返回后会自动更新状态。你可以随时修改授权，手动记账不受影响。',
                        style: TextStyle(color: colors.onSurfaceVariant),
                      ),
                    ],
                  ),
                ),
                if (_error != null) SettingsNotice(_error!, error: true),
                if (android) ...[
                  SettingsSection(
                    title: '自动发现支付',
                    description: '需要应用内采集开关和系统授权同时开启。识别结果先进入待确认账单。',
                    children: [
                      _PermissionItem(
                        title: '支付通知',
                        detail:
                            '通过通知使用权读取微信、支付宝、美团和抖音的支付通知，在本机识别金额。没有通知或金额被隐藏时可能漏记。',
                        status: _captureStatus(
                          'notificationCaptureEnabled',
                          'notificationListenerGranted',
                          'notificationServiceConnected',
                        ),
                        ready: notificationReady,
                        action: notificationReady
                            ? '查看通知使用权'
                            : c.capabilities['notificationListenerGranted'] ==
                                      true &&
                                  c.settings['notificationCaptureEnabled'] ==
                                      true
                            ? '重新连接通知服务'
                            : '开启通知采集',
                        onPressed: disabled ? null : _notifications,
                      ),
                      const Divider(),
                      _PermissionItem(
                        title: '支付页面',
                        detail: '通过无障碍服务读取可见支付结果，补充没有通知的支付或转账。开启前会说明读取范围，由你确认。',
                        status: _captureStatus(
                          'screenCaptureEnabled',
                          'accessibilityGranted',
                          'accessibilityServiceConnected',
                        ),
                        ready: screenReady,
                        action: c.settings['screenCaptureEnabled'] == true
                            ? '查看无障碍设置'
                            : '开启页面采集',
                        onPressed: disabled ? null : _screen,
                      ),
                    ],
                  ),
                  SettingsSection(
                    title: '提醒与后台运行',
                    children: [
                      _PermissionItem(
                        title: '记账确认提醒',
                        detail:
                            '允许 PennyPilot 发送待确认账单提醒。这与上面的通知使用权独立；不开启也能在应用内核对。',
                        status: reminders ? '已允许' : '未开启',
                        ready: reminders,
                        action: reminders || _remindersDenied
                            ? '查看提醒设置'
                            : '允许记账提醒',
                        onPressed: disabled ? null : _reminders,
                      ),
                      const Divider(),
                      _PermissionItem(
                        title: '后台运行',
                        detail:
                            '系统省电可能中断自动采集。可将 PennyPilot 设为不优化；部分手机还需在应用系统设置中允许自启动。',
                        status: battery ? '电池优化已豁免' : '尚未豁免电池优化',
                        ready: battery,
                        action: '查看电池设置',
                        onPressed: disabled
                            ? null
                            : () => _run(() => c.openSystemSettings('battery')),
                        secondaryAction: '查看自启动与后台设置',
                        onSecondary: disabled
                            ? null
                            : () => _run(() => c.openSystemSettings('app')),
                      ),
                      const SettingsNotice(captureBackgroundGuidance),
                    ],
                  ),
                ],
                SettingsSection(
                  title: '语音记账',
                  description: android
                      ? '可现在授权，也可在第一次录音时再决定。'
                      : 'iOS 支持手动、文字和语音记账，不提供跨应用支付通知或页面采集。',
                  children: [
                    _PermissionItem(
                      title: '麦克风',
                      detail:
                          '只在你主动录音时使用。语音记账还需在「语音模型」配置转写服务；录音会发送给所选服务转为文字，由你核对账单。',
                      status: microphone ? '已允许' : '未授权',
                      ready: microphone,
                      action: microphone || _microphoneDenied
                          ? '打开麦克风设置'
                          : '允许使用麦克风',
                      onPressed: disabled ? null : _microphone,
                    ),
                  ],
                ),
                if (android && !widget.firstRun)
                  Padding(
                    padding: const EdgeInsets.fromLTRB(20, 24, 20, 0),
                    child: OutlinedButton.icon(
                      onPressed: disabled
                          ? null
                          : () => Navigator.of(context).push(
                              MaterialPageRoute<void>(
                                builder: (_) =>
                                    CaptureSettingsScreen(controller: c),
                              ),
                            ),
                      icon: const Icon(Icons.tune_outlined),
                      label: const Text('管理采集开关与运行诊断'),
                    ),
                  ),
              ],
            ),
          ),
          bottomNavigationBar: SafeArea(
            child: Center(
              heightFactor: 1,
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 1040),
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(20, 12, 20, 12),
                  child: SizedBox(
                    width: double.infinity,
                    child: FilledButton(
                      onPressed: disabled ? null : _finish,
                      child: Text(widget.firstRun ? '开始记账' : '完成'),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      );
    },
  );
}

class _PermissionItem extends StatelessWidget {
  const _PermissionItem({
    required this.title,
    required this.detail,
    required this.status,
    required this.ready,
    required this.action,
    required this.onPressed,
    this.secondaryAction,
    this.onSecondary,
  });

  final String title, detail, status, action;
  final bool ready;
  final VoidCallback? onPressed, onSecondary;
  final String? secondaryAction;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(title, style: theme.textTheme.titleMedium),
          const SizedBox(height: 8),
          Text(detail, style: theme.textTheme.bodyMedium),
          const SizedBox(height: 12),
          Semantics(
            liveRegion: true,
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                ExcludeSemantics(
                  child: Icon(
                    ready ? Icons.check_circle_outline : Icons.info_outline,
                    size: 20,
                    color: ready
                        ? theme.colorScheme.primary
                        : theme.colorScheme.onSurfaceVariant,
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(child: Text(status)),
              ],
            ),
          ),
          const SizedBox(height: 12),
          OutlinedButton(onPressed: onPressed, child: Text(action)),
          if (secondaryAction != null)
            TextButton(onPressed: onSecondary, child: Text(secondaryAction!)),
        ],
      ),
    );
  }
}

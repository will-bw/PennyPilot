import 'package:flutter/material.dart';
import 'package:pennypilot/core/ledger_controller.dart';

import 'settings_widgets.dart';

String screenCaptureStatusLabel(String? status) => switch (status) {
  'disabled' => '页面读取已暂停',
  'disconnected' => '页面读取服务已断开',
  'event_received' => '已收到页面变化',
  'unreadable' => '页面暂时无法读取',
  'protected' => '已跳过输入或受保护页面',
  'not_receipt' => '未识别到完整支付凭证',
  'parsed' => '已识别，正在保存',
  'saved' => '已生成待确认账单',
  'duplicate' => '重复页面已拦截',
  'error' => '页面处理未完成',
  _ => '等待支付页面事件',
};

String screenCaptureStatusDetail(String? status) => switch (status) {
  'disabled' => '开启“读取可见支付结果”后再进行页面识别。',
  'disconnected' => '检查系统无障碍设置，重新开启服务后返回刷新。',
  'event_received' => '正在等待页面内容稳定。',
  'unreadable' => '支付应用未提供可读取的页面文字，或结果页已离开；请核对流水，必要时补记。',
  'protected' => '输入框、密码或不完整页面不会用于记账，避免误读聊天或待支付内容。',
  'not_receipt' => '没有同时找到明确完成状态和有效金额。失败、待支付、聊天和历史页面也会被跳过。',
  'parsed' => '正在将识别出的交易字段保存到待确认列表。',
  'saved' => '请回到流水核对；即使关闭记账提醒，也可在待确认列表中查看。',
  'duplicate' => '同一页面不会反复生成账单，请查看已有待确认记录。',
  'error' => '请查看下方运行记录，检查权限并核对最近交易。',
  _ => '完成支付后在结果页稍作停留。若时间一直不更新，请核对应用内开关、系统授权和服务连接。',
};

String captureAppLabel(String packageName) => switch (packageName) {
  'com.tencent.mm' => '微信',
  'com.eg.android.AlipayGphone' => '支付宝',
  'com.sankuai.meituan' => '美团',
  'com.sankuai.meituan.takeoutnew' => '美团外卖',
  'com.ss.android.ugc.aweme' => '抖音',
  'com.ss.android.ugc.aweme.lite' => '抖音极速版',
  _ => '支付应用',
};

class CaptureSettingsScreen extends StatefulWidget {
  const CaptureSettingsScreen({super.key, required this.controller});
  final LedgerController controller;
  @override
  State<CaptureSettingsScreen> createState() => _CaptureSettingsScreenState();
}

class _CaptureSettingsScreenState extends State<CaptureSettingsScreen>
    with WidgetsBindingObserver {
  bool _busy = false;
  String? _error;
  bool _diagnostics = false;

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
    if (state == AppLifecycleState.resumed) widget.controller.refresh();
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

  Future<void> _setScreen(bool value) async {
    if (value) {
      final confirmed = await confirmSettingsAction(
        context,
        title: '开启支付页面读取？',
        message:
            'PennyPilot 会通过系统无障碍服务读取微信、支付宝、美团和抖音的屏幕可见文字，在本机判断是否为支付结果，生成待确认账单。\n\n只保存识别出的交易字段，不保存或上传整页，不读取密码或输入内容，不截图，也不会代你点击、付款或操作其他应用。\n\n部分页面可能无法读取，不能保证覆盖每笔支付。你可以随时在本页暂停，或在系统设置中撤销授权。',
        confirmLabel: '同意并开启',
      );
      if (!confirmed || !mounted) return;
    }
    await _run(() async {
      await widget.controller.updateSettings({'screenCaptureEnabled': value});
      if (value &&
          widget.controller.capabilities['accessibilityGranted'] != true) {
        await widget.controller.openSystemSettings('accessibility');
      }
    }, value ? '支付页面读取已开启，请核对系统授权' : '支付页面读取已暂停');
  }

  Future<void> _setCloud(bool value) async {
    if (value) {
      final confirmed = await confirmSettingsAction(
        context,
        title: '开启通知云端解析？',
        message:
            '本地规则无法完整识别时，会将筛选出的支付通知正文发送给你选择的 AI 服务，补充提取金额、商家等交易信息。通知正文可能包含个人信息。\n\n此设置默认关闭，不会上传支付页面全文。使用服务可能消耗套餐或 API 用量，你可以随时关闭。',
        confirmLabel: '同意并开启',
      );
      if (!confirmed || !mounted) return;
    }
    await _run(
      () => widget.controller.updateSettings({
        'notificationCloudParsingEnabled': value,
      }),
      value ? '通知云端解析已开启' : '通知云端解析已关闭',
    );
  }

  Widget _permission({
    required String title,
    required String detail,
    required bool granted,
    required String target,
    String? button,
  }) => Padding(
    padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(
              granted ? Icons.check_circle_outline : Icons.info_outline,
              size: 18,
              color: granted
                  ? Theme.of(context).colorScheme.primary
                  : Theme.of(context).colorScheme.onSurfaceVariant,
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                '$title · ${granted ? '已允许' : '未允许'}',
                style: Theme.of(context).textTheme.bodyMedium,
              ),
            ),
          ],
        ),
        const SizedBox(height: 6),
        Text(
          detail,
          style: Theme.of(context).textTheme.bodySmall?.copyWith(
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
        ),
        const SizedBox(height: 6),
        OutlinedButton(
          onPressed: _busy
              ? null
              : () => _run(() => widget.controller.openSystemSettings(target)),
          child: Text(button ?? (granted ? '查看系统设置' : '前往授权')),
        ),
      ],
    ),
  );

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: widget.controller,
    builder: (context, _) {
      final settings = widget.controller.settings;
      final capabilities = widget.controller.capabilities;
      if (capabilities['platform'] != 'android') {
        return Scaffold(
          appBar: AppBar(title: const Text('语音与系统权限')),
          body: SafeArea(
            child: SettingsList(
              padding: const EdgeInsets.symmetric(vertical: 24),
              children: [
                const SettingsNotice(
                  '手动记账、文字解析和语音记账均可使用。iOS 不提供读取其他应用支付通知或页面的权限，自动采集仅在 Android 上提供。',
                ),
                const SizedBox(height: 24),
                _permission(
                  title: '麦克风',
                  detail: '在“语音模型”中配置转写服务。首次录音时请求麦克风授权，转写文字经 AI 解析、由你核对后保存。',
                  granted: capabilities['microphoneGranted'] == true,
                  target: 'microphone',
                  button: '打开应用设置',
                ),
                if (_error != null) SettingsNotice(_error!),
              ],
            ),
          ),
        );
      }
      final notification = settings['notificationCaptureEnabled'] == true;
      final screen = settings['screenCaptureEnabled'] == true;
      final cloud = settings['notificationCloudParsingEnabled'] == true;
      final listenerGranted =
          capabilities['notificationListenerGranted'] == true;
      final accessibilityGranted = capabilities['accessibilityGranted'] == true;
      final reminders = capabilities['remindersEnabled'] == true;
      final listenerConnected =
          capabilities['notificationServiceConnected'] == true;
      final accessibilityConnected =
          capabilities['accessibilityServiceConnected'] == true;
      return Scaffold(
        appBar: AppBar(
          title: const Text('自动采集与权限'),
          actions: [
            IconButton(
              tooltip: '刷新状态',
              onPressed: _busy ? null : () => _run(widget.controller.refresh),
              icon: const Icon(Icons.refresh),
            ),
          ],
        ),
        body: SafeArea(
          top: false,
          child: SettingsList(
            padding: const EdgeInsets.only(bottom: 32),
            children: [
              if (_busy) const LinearProgressIndicator(),
              if (_error != null) SettingsNotice(_error!, error: true),
              SettingsSection(
                title: '无通知转账',
                description: '微信没有发出支付通知时，需要通过无障碍服务读取当前转账结果页。',
                children: [
                  SettingsRow(
                    title: !screen
                        ? '页面采集尚未开启'
                        : !accessibilityGranted
                        ? '等待无障碍授权'
                        : !accessibilityConnected
                        ? '已授权，服务尚未连接'
                        : '页面采集已就绪',
                    subtitle: !screen
                        ? (accessibilityGranted
                              ? '系统已授权，但应用内开关仍关闭。开启页面读取后才能识别没有通知的转账。'
                              : '开启页面读取并授予无障碍权限，才能识别没有通知的转账。')
                        : !accessibilityGranted
                        ? '应用内已开启，请在系统中允许 PennyPilot 无障碍服务。'
                        : !accessibilityConnected
                        ? '请在系统无障碍设置中关闭后重新开启 PennyPilot，再返回刷新。'
                        : '完成支付后，在结果页稍作停留，再回到流水查看待确认账单。',
                    icon:
                        screen && accessibilityGranted && accessibilityConnected
                        ? Icons.check_circle_outline
                        : Icons.info_outline,
                    onTap: _busy
                        ? null
                        : !screen
                        ? () => _setScreen(true)
                        : !accessibilityGranted || !accessibilityConnected
                        ? () => _run(
                            () => widget.controller.openSystemSettings(
                              'accessibility',
                            ),
                          )
                        : null,
                  ),
                  const SettingsNotice(
                    '仅识别可读取的支付结果，不会点击或执行转账。聊天中的转账卡片、历史账单和未完成付款不会自动入账；无法读取的页面请手动或语音补记。',
                  ),
                ],
              ),
              SettingsSection(
                title: '支付通知',
                description: '识别结果先进入待确认账单，核对后再计入收支。',
                children: [
                  SwitchListTile(
                    contentPadding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 10,
                    ),
                    title: const Text('读取支付通知'),
                    subtitle: const Text('支持微信、支付宝、美团和抖音。未发通知或通知隐藏金额时，可能无法识别。'),
                    value: notification,
                    onChanged: _busy
                        ? null
                        : (value) => _run(
                            () => widget.controller.updateSettings({
                              'notificationCaptureEnabled': value,
                            }),
                            value ? '通知读取已开启' : '通知读取已暂停',
                          ),
                  ),
                  _permission(
                    title: '通知使用权',
                    granted: listenerGranted,
                    target: 'notificationListener',
                    detail: !listenerGranted
                        ? '需要系统通知使用权，应用内开关不代表已授权。'
                        : !notification
                        ? '读取已暂停，系统授权仍保留。'
                        : listenerConnected
                        ? '通知监听服务已连接。'
                        : '通知监听服务未连接，可尝试重新连接。',
                  ),
                  if (listenerGranted && notification && !listenerConnected)
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 16),
                      child: Align(
                        alignment: Alignment.centerLeft,
                        child: TextButton(
                          onPressed: _busy
                              ? null
                              : () => _run(
                                  widget.controller.rebindNotificationService,
                                  '已请求重新连接；若仍未连接，可在系统中重新开启通知使用权',
                                ),
                          child: const Text('重新连接通知服务'),
                        ),
                      ),
                    ),
                  SwitchListTile(
                    contentPadding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 10,
                    ),
                    title: const Text('通知云端解析'),
                    subtitle: const Text('本地规则无法完整识别时，发送筛选出的支付通知正文给所选 AI 服务。'),
                    value: cloud,
                    onChanged: _busy ? null : _setCloud,
                  ),
                ],
              ),
              SettingsSection(
                title: '支付页面',
                children: [
                  SwitchListTile(
                    contentPadding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 10,
                    ),
                    title: const Text('读取可见支付结果'),
                    subtitle: const Text('识别没有通知的支付或转账结果。应用内开关与系统无障碍授权需要同时开启。'),
                    value: screen,
                    onChanged: _busy ? null : _setScreen,
                  ),
                  _permission(
                    title: '无障碍服务',
                    granted: accessibilityGranted,
                    target: 'accessibility',
                    detail: !screen
                        ? (accessibilityGranted
                              ? '页面读取已暂停，系统授权仍保留。'
                              : '先开启上方开关并阅读说明，再授予系统权限。')
                        : !accessibilityGranted
                        ? '应用内已开启，等待系统授权。'
                        : accessibilityConnected
                        ? '页面读取服务已连接。'
                        : '已授权，但服务尚未连接。',
                  ),
                ],
              ),
              SettingsSection(
                title: '提醒与后台',
                children: [
                  _permission(
                    title: '记账确认提醒',
                    granted: reminders,
                    target: 'notifications',
                    detail:
                        '这是 PennyPilot 发出的提醒，与读取支付通知的通知使用权独立。关闭提醒时，账单仍会保存在待确认列表。',
                    button: '查看提醒设置',
                  ),
                  if (!reminders)
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 16),
                      child: Align(
                        alignment: Alignment.centerLeft,
                        child: TextButton(
                          onPressed: _busy
                              ? null
                              : () => _run(() async {
                                  final granted = await widget.controller
                                      .requestNotificationPermission();
                                  if (context.mounted) {
                                    settingsFeedback(
                                      context,
                                      granted ? '已允许通知提醒' : '提醒未允许，可前往系统通知设置查看',
                                    );
                                  }
                                }),
                          child: const Text('请求允许提醒'),
                        ),
                      ),
                    ),
                  _permission(
                    title: '电池优化豁免',
                    granted: capabilities['batteryExempt'] == true,
                    target: 'battery',
                    detail: '系统省电可能限制后台运行。部分手机还需在应用管理中允许自启动，并将后台耗电设置为不限制。',
                    button: '查看电池设置',
                  ),
                  SettingsRow(
                    title: '应用系统设置',
                    subtitle: '查看自启动、后台运行与权限',
                    icon: Icons.settings_outlined,
                    onTap: _busy
                        ? null
                        : () => _run(
                            () => widget.controller.openSystemSettings('app'),
                          ),
                  ),
                  _permission(
                    title: '麦克风',
                    granted: capabilities['microphoneGranted'] == true,
                    target: 'microphone',
                    detail: '在“语音模型”中配置转写服务并测试录音。云端转写无需安装系统语音识别服务。',
                    button: '查看麦克风权限',
                  ),
                ],
              ),
              SettingsSection(
                title: '页面识别诊断',
                description: '转账后返回本页可查看处理停在哪一步。仅记录状态和时间，不保存页面全文。',
                children: [
                  SettingsRow(
                    title: screenCaptureStatusLabel(
                      capabilities['screenCaptureStatus'] as String?,
                    ),
                    subtitle: screenCaptureStatusDetail(
                      capabilities['screenCaptureStatus'] as String?,
                    ),
                  ),
                  SettingsRow(
                    title: '最近收到页面事件',
                    subtitle: settingsTimestamp(
                      (capabilities['lastScreenEventTime'] as num?)?.toInt() ??
                          0,
                    ),
                  ),
                  SettingsRow(
                    title: '最近读取页面',
                    subtitle: settingsTimestamp(
                      (capabilities['lastScreenReadTime'] as num?)?.toInt() ??
                          0,
                    ),
                  ),
                  if ((capabilities['lastScreenPackage'] as String?)
                          ?.isNotEmpty ==
                      true)
                    SettingsRow(
                      title: '最近事件应用',
                      subtitle: captureAppLabel(
                        capabilities['lastScreenPackage'] as String,
                      ),
                    ),
                ],
              ),
              SettingsSection(
                title: '运行记录',
                children: [
                  SettingsRow(
                    title: '今日捕获 ${capabilities['todayCaptureCount'] ?? 0} 笔',
                    subtitle: '包含待确认账单；疑似重复会留待核对。',
                    trailing: Icon(
                      _diagnostics ? Icons.expand_less : Icons.expand_more,
                    ),
                    onTap: () => setState(() => _diagnostics = !_diagnostics),
                  ),
                  if (_diagnostics) ...[
                    SettingsRow(
                      title: '最近成功捕获',
                      subtitle: settingsTimestamp(
                        (capabilities['lastCaptureAt'] as num?)?.toInt() ?? 0,
                      ),
                    ),
                    SettingsRow(
                      title: '本次运行拦截重复',
                      subtitle: '${capabilities['duplicateCount'] ?? 0} 次',
                    ),
                    const SettingsNotice(
                      '连接状态和运行记录随本次运行更新。最近没有捕获不一定代表故障，系统或支付应用也可能限制读取。',
                    ),
                  ],
                  if ((capabilities['lastError'] as String?)?.isNotEmpty ==
                      true)
                    SettingsNotice(
                      capabilities['lastError'] as String,
                      error: true,
                    ),
                ],
              ),
            ],
          ),
        ),
      );
    },
  );
}

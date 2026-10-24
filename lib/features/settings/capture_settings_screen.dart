import 'package:flutter/material.dart';
import 'package:pennypilot/core/ledger_controller.dart';

import 'settings_widgets.dart';
import 'capture_diagnostic_screen.dart';
import 'capture_consent.dart';
import 'capture_background_guidance.dart';

String screenCaptureStatusLabel(String? status) => switch (status) {
  'disabled' => '页面读取已暂停',
  'disconnected' => '页面读取服务已断开',
  'event_received' => '已收到页面变化',
  'unreadable' => '页面暂时无法读取',
  'protected' => '已跳过输入或受保护页面',
  'input_page' => '已跳过含输入框的页面',
  'password_page' => '已跳过含密码控件的页面',
  'incomplete_tree' => '页面内容暂未读取完整',
  'traversal_limit' => '页面结构超出读取范围',
  'text_limit' => '页面文字超出读取上限',
  'empty_page' => '未读到可见页面文字',
  'not_receipt' => '未识别到完整支付凭证',
  'parsed' => '已识别，正在保存',
  'saved' => '已保存识别结果',
  'model_queued' => '已加入模型识别队列',
  'model_pending' => '模型识别待确认',
  'model_processing' => '模型正在理解支付凭证',
  'model_rejected' => '模型未获得完整交易依据',
  'model_failed' => '模型暂时不可用，等待重试',
  'visual_unavailable' => '当前页面无法截图识别',
  'visual_read' => '正在本地识别支付截图',
  'visual_required' => '此页面需要本地截图补充',
  'visual_not_receipt' => '截图未取得完整交易依据',
  'visual_requires_flow' => '尚未取得本次红包完成凭证',
  'visual_waiting' => '等待支付画面稳定后重读',
  'duplicate' => '重复页面已拦截',
  'error' => '页面处理未完成',
  _ => '等待支付页面事件',
};

String screenCaptureStatusDetail(String? status) => switch (status) {
  'disabled' => '开启“读取可见支付结果”后再进行页面识别。',
  'disconnected' => '先刷新状态，并检查自启动与后台限制；持续未连接时，再到系统无障碍设置中关闭后重新开启服务。',
  'event_received' => '正在等待页面内容稳定。',
  'unreadable' => '支付应用未提供可读取的页面文字，或结果页已离开；请核对流水，必要时补记。',
  'protected' => '输入框、密码或不完整页面不会用于记账，避免误读聊天或待支付内容。',
  'input_page' =>
    '页面包含可见输入框，为避免误读聊天或待支付内容已跳过。请在本次支付完成页停留约 5 秒后再查看诊断；仍无法识别时可手动或语音补记。',
  'password_page' => '页面包含可见密码控件，已停止读取。请完成支付后在本次结果页停留约 5 秒，再回到流水核对。',
  'incomplete_tree' =>
    '本次读取缺少部分页面节点，尚不能判断支付结果。请在本次支付完成页停留约 5 秒，让服务重新读取；仍无记录时可手动或语音补记。',
  'traversal_limit' =>
    '页面节点数量或层级已达到读取上限，本次未用于记账。请在本次支付完成页停留后查看诊断；仍无法识别时可手动或语音补记。',
  'text_limit' => '本次页面文字超过读取上限，未用于记账。请查看本次支付完成页的诊断；仍无法识别时可手动或语音补记。',
  'empty_page' => '当前页面没有可读取的可见文字。请在本次支付完成页停留约 5 秒后再查看诊断；仍无记录时可手动或语音补记。',
  'not_receipt' => '没有同时找到明确完成状态和有效金额。失败、待支付、聊天和历史页面也会被跳过。',
  'parsed' => '正在将识别出的交易字段保存到待确认列表。',
  'model_queued' => '页面已进入持久识别队列，可离开此页。',
  'model_processing' => '正在使用所选模型理解支付字段，结果仍会经过本地校验。',
  'model_rejected' => '没有取得可核对的金额、方向和成功状态，未自动入账。可分享截图或导入账单补录。',
  'model_failed' => '请检查 AI 服务配置与网络，待处理任务会按策略重试。',
  'visual_unavailable' => '截图能力未启用、页面受保护或截图失败，可通过主动图片记账或账单导入补录。',
  'visual_required' => '微信仅提供了空节点。请在“识别与录入”中开启“本地支付截图补充”，再打开这张成功页；图片只在本机处理。',
  'visual_not_receipt' => '当前场景缺少明确完成状态、本人资金方向或有效金额，未生成账目。可查看对应支付结果、主动补录或导入账单。',
  'visual_requires_flow' => '仅打开没有交易时间的红包详情不能证明刚发生收发。发红包需核验付款完成，领红包需观察到打开红包并已存入零钱；较早的红包可通过账单导入补录。',
  'visual_read' => '仅在本机读取当前微信窗口，校验成功状态、金额和结果页布局；图片不会上传。',
  'visual_waiting' => '为避免连续截图，当前正在等待下一次有界读取；已保存的账目可在待确认列表核对。',
  'saved' => '请回到流水核对；即使关闭记账提醒，也可在待确认列表中查看。',
  'duplicate' => '同一页面不会反复生成账单，请查看已有待确认记录。',
  'error' => '请查看下方运行记录，检查权限并核对最近交易。',
  _ => '完成支付后在结果页稍作停留。若时间一直不更新，请核对应用内开关、系统授权和服务连接。',
};

String? screenReadDiagnosticsSummary(Object? diagnostics) {
  if (diagnostics is! Map) return null;
  int? count(String key) {
    final value = diagnostics[key];
    return value is int && value >= 0 ? value : null;
  }

  final visited = count('visitedNodes');
  final text = count('textNodes');
  final details = <String>[
    if (visited != null) '检查 $visited 个节点',
    if (text != null) '可读文字 $text 处',
  ];
  for (final field in [
    ('editableNodes', '输入框', '处'),
    ('passwordNodes', '密码控件', '处'),
    ('missingChildren', '缺失子节点', '个'),
    ('hiddenNodes', '跳过隐藏节点', '个'),
    ('foreignNodes', '跳过其他应用节点', '个'),
  ]) {
    final value = count(field.$1);
    if (value != null && value > 0) {
      details.add('${field.$2} $value ${field.$3}');
    }
  }
  return details.isEmpty ? null : details.join(' · ');
}

String captureAppLabel(String packageName) => switch (packageName) {
  'com.tencent.mm' => '微信',
  'com.eg.android.AlipayGphone' => '支付宝',
  'com.sankuai.meituan' => '美团',
  'com.sankuai.meituan.takeoutnew' => '美团外卖',
  'com.ss.android.ugc.aweme' => '抖音',
  'com.ss.android.ugc.aweme.lite' => '抖音极速版',
  _ => '支付应用',
};

typedef _ScreenReadHistoryEntry = ({
  String status,
  int timestamp,
  int sequence,
  String? app,
  String? summary,
});

List<_ScreenReadHistoryEntry> _screenReadHistory(Object? value) {
  if (value is! List) return const [];
  const knownStatuses = {
    'input_page',
    'password_page',
    'incomplete_tree',
    'traversal_limit',
    'text_limit',
    'empty_page',
    'unreadable',
    'not_receipt',
    'parsed',
    'saved',
    'duplicate',
    'error',
    'protected',
    'visual_read',
    'visual_required',
    'visual_not_receipt',
    'visual_unavailable',
    'model_queued',
    'model_processing',
    'model_rejected',
    'model_failed',
  };
  final history = <_ScreenReadHistoryEntry>[];
  for (final (sequence, entry) in value.indexed) {
    if (entry is! Map) continue;
    final status = entry['status'];
    final timestamp = entry['timestamp'];
    if (status is! String ||
        !knownStatuses.contains(status) ||
        timestamp is! int ||
        timestamp <= 0 ||
        timestamp > 8640000000000000) {
      continue;
    }
    final package = entry['packageName'];
    final label = package is String ? captureAppLabel(package) : null;
    history.add((
      status: status,
      timestamp: timestamp,
      sequence: sequence,
      app: label == '支付应用' ? null : label,
      summary: screenReadDiagnosticsSummary(entry['diagnostics']),
    ));
  }
  history.sort((a, b) {
    final byTime = b.timestamp.compareTo(a.timestamp);
    return byTime != 0 ? byTime : b.sequence.compareTo(a.sequence);
  });
  return history.take(8).toList(growable: false);
}

String _screenReadHistoryDetail(_ScreenReadHistoryEntry entry) {
  final time = DateTime.fromMillisecondsSinceEpoch(entry.timestamp);
  final second = time.second.toString().padLeft(2, '0');
  return [
    '${settingsTimestamp(entry.timestamp)}:$second',
    ?entry.app,
    ?entry.summary,
  ].join(' · ');
}

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
      final confirmed = await confirmScreenCapture(context);
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
      final screenReadSummary = screenReadDiagnosticsSummary(
        capabilities['screenReadDiagnostics'],
      );
      final screenReadHistory = _screenReadHistory(
        capabilities['screenReadHistory'],
      );
      return Scaffold(
        appBar: AppBar(
          title: const Text('自动采集'),
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
                        ? '系统授权仍保留。清理后台后可能尚未恢复，点此刷新；持续未连接时检查自启动与后台限制。'
                        : '完成支付后，在结果页稍作停留，再回到流水查看待确认账单。',
                    icon:
                        screen && accessibilityGranted && accessibilityConnected
                        ? Icons.check_circle_outline
                        : Icons.info_outline,
                    onTap: _busy
                        ? null
                        : !screen
                        ? () => _setScreen(true)
                        : !accessibilityGranted
                        ? () => _run(
                            () => widget.controller.openSystemSettings(
                              'accessibility',
                            ),
                          )
                        : !accessibilityConnected
                        ? () => _run(widget.controller.refresh)
                        : null,
                  ),
                  if (screen && !accessibilityConnected)
                    SettingsRow(
                      title: '检查自启动与后台限制',
                      subtitle: '允许自启动、放开省电限制，并锁定后台任务。详细方法见下方“提醒与后台”。',
                      icon: Icons.battery_saver_outlined,
                      onTap: _busy
                          ? null
                          : () => _run(
                              () => widget.controller.openSystemSettings('app'),
                            ),
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
                  const SettingsNotice(captureBackgroundGuidance),
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
                description: '转账后返回本页可查看处理停在哪一步。仅记录状态、时间和节点数量，不保存页面全文。',
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
                    title: '诊断这次支付',
                    subtitle: '主动采集本地页面样本，预览脱敏后可复制',
                    icon: Icons.bug_report_outlined,
                    onTap: () => Navigator.push(
                      context,
                      MaterialPageRoute<void>(
                        builder: (_) => CaptureDiagnosticScreen(
                          controller: widget.controller,
                        ),
                      ),
                    ),
                  ),
                  if (screenReadSummary != null)
                    SettingsRow(title: '页面节点摘要', subtitle: screenReadSummary),
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
                  if (screenReadHistory.isNotEmpty) ...[
                    const SettingsRow(
                      title: '最近识别过程',
                      subtitle: '按时间从近到远，仅保留最近 8 条。',
                    ),
                    for (final (index, entry) in screenReadHistory.indexed)
                      SettingsRow(
                        key: ValueKey('screen-read-history-$index'),
                        title: screenCaptureStatusLabel(entry.status),
                        subtitle: _screenReadHistoryDetail(entry),
                      ),
                  ],
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

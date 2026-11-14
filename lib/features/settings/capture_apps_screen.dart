import 'package:flutter/material.dart';

import '../../core/ledger_controller.dart';
import '../../core/models.dart';
import 'settings_widgets.dart';

class CaptureAppsScreen extends StatefulWidget {
  const CaptureAppsScreen({super.key, required this.controller});
  final LedgerController controller;

  @override
  State<CaptureAppsScreen> createState() => _CaptureAppsScreenState();
}

class _CaptureAppsScreenState extends State<CaptureAppsScreen> {
  bool _busy = false;
  String? _error;

  Future<void> _change(
    String package,
    bool enabled,
    Set<String> selected,
  ) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await widget.controller.updateSettings({
        'captureAppPackages':
            (enabled ? {...selected, package} : selected.difference({package}))
                .toList(),
      });
    } catch (error) {
      if (mounted) setState(() => _error = settingsError(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.controller,
    builder: (context, _) {
      final apps =
          (widget.controller.capabilities['captureApps'] as List? ?? [])
              .whereType<Map>()
              .map(stringMap)
              .toList();
      final selected =
          (widget.controller.settings['captureAppPackages'] as List? ??
                  apps.map((app) => app['packageName']).toList())
              .whereType<String>()
              .toSet();
      return Scaffold(
        appBar: AppBar(title: const Text('采集应用')),
        body: SafeArea(
          child: ListView(
            padding: const EdgeInsets.only(bottom: 24),
            children: [
              const SettingsNotice(
                '选择需要识别的应用。开启后会处理该应用的支付通知、付款结果和单笔账单详情；云端识图仍按你的独立设置运行。',
              ),
              if (_error != null) SettingsNotice(_error!),
              if (selected.isEmpty)
                const SettingsNotice('当前未选择采集应用，通知和页面采集均已暂停。'),
              for (final app in apps)
                SwitchListTile(
                  title: Text(app['name'] as String),
                  subtitle: Text(
                    app['limitedByPlatform'] == true
                        ? '部分页面受应用限制，可尝试分享凭证图片'
                        : switch (app['family']) {
                            'WALLET' => '付款、收款、转账、退款与单笔账单详情',
                            'FOOD' => '付款结果、已付订单与退款详情',
                            _ => '购物付款、单笔订单与退款详情',
                          },
                  ),
                  value: selected.contains(app['packageName']),
                  onChanged: _busy
                      ? null
                      : (value) => _change(
                          app['packageName'] as String,
                          value,
                          selected,
                        ),
                ),
            ],
          ),
        ),
      );
    },
  );
}

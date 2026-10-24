import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../core/ledger_controller.dart';
import '../../core/models.dart';
import '../../ui/common.dart';

class CaptureDiagnosticScreen extends StatefulWidget {
  const CaptureDiagnosticScreen({super.key, required this.controller});
  final LedgerController controller;
  @override
  State<CaptureDiagnosticScreen> createState() =>
      _CaptureDiagnosticScreenState();
}

class _CaptureDiagnosticScreenState extends State<CaptureDiagnosticScreen>
    with WidgetsBindingObserver {
  final preview = TextEditingController();
  String state = 'idle';
  bool busy = false;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    WidgetsBinding.instance.addPostFrameCallback((_) => read());
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    preview.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) read();
  }

  Future<void> read() async {
    await run('readCaptureDiagnostic');
  }

  Future<void> run(String method) async {
    if (busy) return;
    setState(() => busy = true);
    await runUi(context, () async {
      final response = stringMap(
        await widget.controller.command(method, refreshAfter: false),
      );
      if (mounted) {
        setState(() {
          state = response['state'] as String? ?? 'idle';
          preview.text = response['sample'] as String? ?? '';
        });
      }
    });
    if (mounted) setState(() => busy = false);
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('诊断这次支付'),
      actions: [
        IconButton(
          onPressed: busy ? null : read,
          tooltip: '刷新样本',
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: SafeArea(
      top: false,
      child: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          const Text(
            '主动采集下一次支付页面的结构样本，帮助定位微信版本差异。样本只保存在本机；先查看并删除个人信息，再自行复制分享。',
          ),
          const SizedBox(height: 20),
          FilledButton(
            onPressed: busy ? null : () => run('armCaptureDiagnostic'),
            child: const Text('采集下一次支付页面'),
          ),
          const SizedBox(height: 12),
          Text(switch (state) {
            'waiting' => '已准备。请切换到微信支付结果页，停留后返回这里。',
            'ready' => '样本已就绪。请检查下方内容，可直接编辑脱敏。',
            _ => '尚未采集样本。',
          }),
          if (preview.text.isNotEmpty) ...[
            const SizedBox(height: 20),
            TextField(
              controller: preview,
              minLines: 10,
              maxLines: 24,
              decoration: const InputDecoration(
                labelText: '本地样本（可编辑）',
                alignLabelWithHint: true,
              ),
            ),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: () => runUi(context, () async {
                await Clipboard.setData(ClipboardData(text: preview.text));
              }, success: '已复制当前预览内容'),
              icon: const Icon(Icons.copy),
              label: const Text('复制已检查的样本'),
            ),
          ],
          TextButton(
            onPressed: busy ? null : () => run('clearCaptureDiagnostic'),
            child: const Text('清除本地样本并取消采集'),
          ),
        ],
      ),
    ),
  );
}

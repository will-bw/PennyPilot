import 'dart:convert';
import 'package:flutter/material.dart';
import '../../core/ledger_controller.dart';
import '../../core/models.dart';
import '../../ui/common.dart';
import 'capture_consent.dart';
import 'settings_widgets.dart';
import 'ai_settings_screen.dart';
import 'voice_settings_screen.dart';

const _recognitionServiceNames = {
  '': '复用当前 AI 服务',
  'chatgpt': 'ChatGPT',
  'deepseek': 'DeepSeek',
  'qwen': '通义千问 / 百炼',
};

/// Operate surface: reuse the existing settings rhythm, with explicit data
/// choices followed by role configuration and an actual inference test.
class RecognitionSettingsScreen extends StatefulWidget {
  const RecognitionSettingsScreen({super.key, required this.controller});
  final LedgerController controller;
  @override
  State<RecognitionSettingsScreen> createState() =>
      _RecognitionSettingsScreenState();
}

class _RecognitionSettingsScreenState extends State<RecognitionSettingsScreen> {
  late final fields = {
    for (final key in [
      'recognitionTextModel',
      'recognitionVisionModel',
      'recognitionReviewModel',
    ])
      key: TextEditingController(
        text: widget.controller.settings[key] as String? ?? '',
      ),
  };
  late final providers = {
    for (final role in ['Text', 'Vision', 'Review'])
      'recognition${role}Provider':
          widget.controller.settings['recognition${role}Provider'] as String? ??
          '',
  };
  bool busy = false;
  String? result;
  @override
  void dispose() {
    for (final field in fields.values) {
      field.dispose();
    }
    super.dispose();
  }

  Future<void> run(Future<void> Function() work) async {
    setState(() {
      busy = true;
      result = null;
    });
    try {
      await work();
    } catch (error) {
      if (mounted) setState(() => result = settingsError(error));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: widget.controller,
    builder: (context, _) {
      final c = widget.controller;
      final visionProvider = c.settings['recognitionVisionProvider'];
      final visionService =
          _recognitionServiceNames[visionProvider == null ||
                  visionProvider == ''
              ? c.settings['activeProvider']
              : visionProvider];
      List<dynamic> preferences;
      try {
        preferences =
            jsonDecode(
                  c.settings['recognitionPreferencesJson'] as String? ?? '[]',
                )
                as List;
      } catch (_) {
        preferences = [];
      }
      return Scaffold(
        appBar: AppBar(title: const Text('识别与自动录入')),
        body: SafeArea(
          top: false,
          child: ListView(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
            children: [
              const Text(
                'AI 会结合时间、支付场景和已有习惯补全账单并自动保存。你可以随时修改；只有确实缺少关键信息时才需要补充。',
              ),
              const SectionTitle('数据与保存方式'),
              for (final option in const [
                (
                  'screenCloudVisionEnabled',
                  '云端支付截图识别',
                  '开启页面采集后，将符合条件的付款结果或账单详情截图交给图片模型，直接读取金额、姓名、商品和账户等字段。',
                ),
                (
                  'screenCloudParsingEnabled',
                  '云端支付页面识别',
                  '将必要的支付文字和相关记账习惯发给 AI，自动理解资金用途、分类和支付账户。',
                ),
                (
                  'cloudImageParsingEnabled',
                  '云端图片识别',
                  '你选择或分享的图片可在发送后由图片模型直接读取。请先检查图片内容。',
                ),
                (
                  'captureAutoRecordEnabled',
                  '支付自动记账',
                  '交易依据完整时自动记账。依据不足、金额冲突或疑似重复的交易留待确认。',
                ),
                (
                  'activeInputAutoSaveEnabled',
                  'AI 自动记账',
                  '文字、语音和已完成的图片凭证由 AI 补全后直接记账，可撤销。关闭后先展示草稿。',
                ),
              ])
                if (option.$1 != 'screenCloudVisionEnabled' ||
                    c.capabilities['screenCloudVisionSupported'] == true)
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text(option.$2),
                    subtitle: Text(option.$3),
                    value:
                        const {
                          'captureAutoRecordEnabled',
                          'activeInputAutoSaveEnabled',
                        }.contains(option.$1)
                        ? c.settings[option.$1] != false
                        : c.settings[option.$1] == true,
                    onChanged: busy
                        ? null
                        : (value) => run(() async {
                            if (value &&
                                option.$1 == 'screenCloudVisionEnabled' &&
                                !await confirmCloudReceiptVision(
                                  context,
                                  serviceName: visionService,
                                )) {
                              return;
                            }
                            if (mounted) {
                              await c.updateSettings({option.$1: value});
                            }
                          }),
                  ),
              ListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('语音转写授权与模型'),
                subtitle: const Text('录音使用独立的服务与设置'),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => Navigator.push(
                  context,
                  MaterialPageRoute<void>(
                    builder: (_) => VoiceSettingsScreen(controller: c),
                  ),
                ),
              ),
              const SectionTitle('模型角色'),
              const Text(
                '每个角色可选择不同服务，复用“AI 服务”中保存的密钥或登录账号。模型留空使用该服务默认值；图片角色需要支持视觉。',
              ),
              const SizedBox(height: 16),
              for (final entry in fields.entries)
                Padding(
                  padding: const EdgeInsets.only(bottom: 16),
                  child: Column(
                    children: [
                      DropdownButtonFormField<String>(
                        initialValue:
                            providers[entry.key.replaceFirst(
                              'Model',
                              'Provider',
                            )],
                        decoration: InputDecoration(
                          labelText:
                              '${const {'recognitionTextModel': '文字', 'recognitionVisionModel': '图片', 'recognitionReviewModel': '复核'}[entry.key]}服务',
                        ),
                        items: _recognitionServiceNames.entries
                            .map(
                              (e) => DropdownMenuItem(
                                value: e.key,
                                child: Text(e.value),
                              ),
                            )
                            .toList(),
                        onChanged: busy
                            ? null
                            : (value) => setState(
                                () =>
                                    providers[entry.key.replaceFirst(
                                          'Model',
                                          'Provider',
                                        )] =
                                        value!,
                              ),
                      ),
                      const SizedBox(height: 12),
                      TextField(
                        controller: entry.value,
                        enabled: !busy,
                        maxLength: 256,
                        decoration: InputDecoration(
                          labelText: const {
                            'recognitionTextModel': '文字与页面模型',
                            'recognitionVisionModel': '图片理解模型',
                            'recognitionReviewModel': '复杂情况复核模型',
                          }[entry.key],
                          hintText: '留空复用默认模型',
                          counterText: '',
                        ),
                      ),
                    ],
                  ),
                ),
              OutlinedButton(
                onPressed: busy
                    ? null
                    : () => Navigator.push(
                        context,
                        MaterialPageRoute<void>(
                          builder: (_) => AiSettingsScreen(controller: c),
                        ),
                      ),
                child: const Text('配置 AI 服务'),
              ),
              FilledButton(
                onPressed: busy
                    ? null
                    : () => run(() async {
                        await c.updateSettings({
                          ...providers,
                          for (final entry in fields.entries)
                            entry.key: entry.value.text.trim(),
                        });
                        if (mounted) setState(() => result = '识别配置已保存');
                      }),
                child: const Text('保存模型角色'),
              ),
              OutlinedButton.icon(
                onPressed: busy
                    ? null
                    : () => run(() async {
                        final raw = stringMap(
                          await c.command(
                            'recognizeInput',
                            arguments: {
                              'text': '帮我记今天午饭 12 元',
                              'source': 'AI_TEXT',
                            },
                            ledger: true,
                            refreshAfter: false,
                          ),
                        );
                        final transactions = raw['transactions'] as List? ?? [];
                        if (transactions.length != 1 ||
                            stringMap(transactions.first)['amount'] != 1200 ||
                            stringMap(transactions.first)['type'] !=
                                'EXPENSE') {
                          throw const LedgerException(
                            '模型未通过 12 元午饭测试，请检查模型或服务能力。',
                          );
                        }
                        if (mounted) {
                          setState(
                            () => result = '文字模型测试通过：正确识别 12.00 元支出。测试没有写入账本。',
                          );
                        }
                      }),
                icon: const Icon(Icons.science_outlined),
                label: const Text('测试文字识别（不入账）'),
              ),
              OutlinedButton.icon(
                onPressed:
                    busy || c.settings['cloudImageParsingEnabled'] != true
                    ? null
                    : () => run(() async {
                        final image = await c.command(
                          'pickRecognitionImage',
                          refreshAfter: false,
                        );
                        if (image == null) return;
                        final parsed = stringMap(
                          await c.command(
                            'recognizeInput',
                            arguments: {
                              'text': '',
                              'source': 'AI_IMAGE',
                              'imageBase64': stringMap(image)['base64'],
                              'mimeType': stringMap(image)['mimeType'],
                            },
                            ledger: true,
                            refreshAfter: false,
                          ),
                        );
                        if (mounted) {
                          setState(
                            () => result =
                                '图片模型已返回 ${(parsed['transactions'] as List? ?? []).length} 笔候选。${(parsed['questions'] as List? ?? []).join('；')} 测试没有写入账本。',
                          );
                        }
                      }),
                icon: const Icon(Icons.image_outlined),
                label: const Text('选择图片测试（发送图片，不入账）'),
              ),
              if (busy)
                const Padding(
                  padding: EdgeInsets.all(16),
                  child: Center(child: CircularProgressIndicator()),
                ),
              if (result != null)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 16),
                  child: Semantics(liveRegion: true, child: Text(result!)),
                ),
              const SectionTitle('分类偏好'),
              Text('你修改草稿分类后，会记住商户与分类的对应关系，仅用于推荐。已保存 ${preferences.length} 条。'),
              for (final pref in preferences.take(20))
                if (pref is Map)
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text('${pref['merchant']}'),
                    subtitle: Text('${pref['category']}'),
                  ),
              TextButton(
                onPressed: busy || preferences.isEmpty
                    ? null
                    : () => run(
                        () => c.updateSettings({
                          'recognitionPreferencesJson': '[]',
                        }),
                      ),
                child: const Text('清除全部分类偏好'),
              ),
            ],
          ),
        ),
      );
    },
  );
}

import 'package:flutter/material.dart';

import '../../core/ledger_controller.dart';
import '../../core/models.dart';
import '../../ui/common.dart';
import '../entry/entry_screen.dart';

Future<void> stageRecognitionImage(
  LedgerController controller,
  Map<String, dynamic> image,
) async {
  if (controller.settings['cloudImageParsingEnabled'] == true) {
    final bytes = image['base64'] ?? image['imageBase64'];
    if (bytes is! String || bytes.isEmpty) {
      throw const LedgerException('无法准备这张图片，请重新选择凭证。');
    }
    controller.setAssistantAttachment({
      'imageBase64': bytes,
      'mimeType': image['mimeType'],
      'name': image['name'] ?? '记账图片',
    });
    return;
  }
  throw const LedgerException('请在“识别与自动录入”开启云端图片识别，再选择或分享凭证。');
}

class AssistantRecognition extends StatefulWidget {
  const AssistantRecognition({super.key, required this.controller});
  final LedgerController controller;

  @override
  State<AssistantRecognition> createState() => _AssistantRecognitionState();
}

class _AssistantRecognitionState extends State<AssistantRecognition> {
  final excluded = <String>{};

  String _key(LedgerTransaction tx) =>
      tx.recognitionMetadata['operationId'] as String? ??
      '${tx.source.wireValue}:${tx.occurredAt.millisecondsSinceEpoch}:${tx.amountFen}:${tx.merchant}';

  void _edit(LedgerTransaction tx) {
    final c = widget.controller;
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => EntryScreen(
          controller: c,
          transaction: tx,
          onDraftSaved: (value) {
            final index = c.drafts.indexWhere((item) => _key(item) == _key(tx));
            if (index < 0) {
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('这笔草稿已经更新，请重新打开核对。')),
              );
              return;
            }
            c.updateDraft(index, value);
          },
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.controller;
    if (c.drafts.isEmpty && c.recognitionQuestions.isEmpty) {
      return const SizedBox.shrink();
    }
    final selected = c.drafts
        .where((tx) => !excluded.contains(_key(tx)))
        .toList();
    final colors = Theme.of(context).colorScheme;
    return Padding(
      key: const ValueKey('assistant-recognition'),
      padding: const EdgeInsets.only(top: 16, bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (c.recognitionQuestions.isNotEmpty) ...[
            Text('识别待补充', style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 8),
            for (final question in c.recognitionQuestions)
              Padding(
                padding: const EdgeInsets.only(bottom: 6),
                child: Text(question),
              ),
            const SizedBox(height: 8),
          ],
          if (c.drafts.isNotEmpty) ...[
            SectionTitle(
              '核对 ${c.drafts.length} 笔账单',
              trailing: TextButton(
                onPressed: c.isBusy
                    ? null
                    : () => setState(() {
                        if (selected.length == c.drafts.length) {
                          excluded.addAll(c.drafts.map(_key));
                        } else {
                          excluded.clear();
                        }
                      }),
                child: Text(selected.length == c.drafts.length ? '取消全选' : '全选'),
              ),
            ),
            for (final tx in c.drafts) ...[
              const Divider(),
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Checkbox(
                      value: !excluded.contains(_key(tx)),
                      semanticLabel: '选择${tx.merchant ?? '账单'}',
                      onChanged: c.isBusy
                          ? null
                          : (value) => setState(() {
                              if (value == true) {
                                excluded.remove(_key(tx));
                              } else {
                                excluded.add(_key(tx));
                              }
                            }),
                    ),
                    Expanded(
                      child: InkWell(
                        onTap: c.isBusy ? null : () => _edit(tx),
                        borderRadius: BorderRadius.circular(8),
                        child: Padding(
                          padding: const EdgeInsets.symmetric(vertical: 8),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                tx.merchant?.isNotEmpty == true
                                    ? tx.merchant!
                                    : c.categoryById(tx.categoryId)?.name ??
                                          '待分类',
                                style: Theme.of(context).textTheme.titleSmall,
                              ),
                              const SizedBox(height: 4),
                              AmountLabel(
                                tx.amountFen,
                                prefix: tx.type == TxType.income ? '+' : '−',
                              ),
                              const SizedBox(height: 6),
                              Text(
                                '${c.categoryById(tx.categoryId)?.name ?? '待分类'} · ${tx.businessLabel}',
                              ),
                              Text(
                                tx.recognitionMetadata['receiptDateUnconfirmed'] ==
                                        true
                                    ? '交易日期待选'
                                    : '${tx.occurredAt.year}年${dateLabel(tx.occurredAt)}${const {'exact', 'approximate'}.contains(tx.recognitionMetadata['timePrecision']) ? ' ${timeLabel(tx.occurredAt)}' : ''}${tx.recognitionMetadata['timePrecision'] == 'approximate' ? '左右' : ''}',
                              ),
                              if (tx.note?.isNotEmpty == true) Text(tx.note!),
                              const SizedBox(height: 4),
                              Text(
                                '点击修改',
                                style: TextStyle(
                                  fontSize: 12,
                                  color: colors.primary,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                    IconButton(
                      tooltip: '移除这笔草稿',
                      onPressed: c.isBusy
                          ? null
                          : () => c.removeDraft(c.drafts.indexOf(tx)),
                      icon: const Icon(Icons.close, size: 19),
                    ),
                  ],
                ),
              ),
              if ((tx.recognitionMetadata['assumptions'] as List? ?? [])
                  .isNotEmpty)
                ExpansionTile(
                  tilePadding: EdgeInsets.zero,
                  title: Text(
                    '查看 ${(tx.recognitionMetadata['assumptions'] as List).length} 项补全说明',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                  children: [
                    for (final assumption
                        in tx.recognitionMetadata['assumptions'] as List)
                      ListTile(
                        contentPadding: EdgeInsets.zero,
                        title: Text('$assumption'),
                      ),
                  ],
                ),
            ],
            const SizedBox(height: 12),
            Wrap(
              spacing: 12,
              runSpacing: 8,
              children: [
                FilledButton.icon(
                  onPressed: c.isBusy || selected.isEmpty
                      ? null
                      : () => runUi(
                          context,
                          () => c.saveDrafts(
                            items: selected,
                            rememberRecognitionSave: true,
                          ),
                          success: '已保存到账本',
                        ),
                  icon: const Icon(Icons.done_all, size: 20),
                  label: Text('保存这 ${selected.length} 笔账单'),
                ),
                TextButton(
                  onPressed: c.isBusy ? null : c.clearDrafts,
                  child: const Text('清空草稿'),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}

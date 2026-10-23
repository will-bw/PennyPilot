import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../core/ledger_controller.dart';
import '../../core/models.dart';
import '../../ui/common.dart';
import '../../ui/theme.dart';

class EntryScreen extends StatefulWidget {
  const EntryScreen({
    super.key,
    required this.controller,
    this.transaction,
    this.onDraftSaved,
  });
  final LedgerController controller;
  final LedgerTransaction? transaction;
  final ValueChanged<LedgerTransaction>? onDraftSaved;
  @override
  State<EntryScreen> createState() => _EntryScreenState();
}

class _EntryScreenState extends State<EntryScreen> {
  final form = GlobalKey<FormState>();
  final amount = TextEditingController(),
      merchant = TextEditingController(),
      note = TextEditingController(),
      account = TextEditingController();
  late TxType type = widget.transaction?.type ?? TxType.expense;
  late int? categoryId = widget.transaction?.categoryId;
  late DateTime occurredAt = widget.transaction?.occurredAt ?? DateTime.now();
  late final int openingRevision;
  bool saving = false, ai = false;
  @override
  void initState() {
    super.initState();
    openingRevision = widget.controller.revision;
    final tx = widget.transaction;
    if (tx != null) {
      amount.text = formatMoney(tx.amountFen, group: false);
      merchant.text = tx.merchant ?? '';
      note.text = tx.note ?? '';
      account.text = tx.accountTag ?? '';
    }
  }

  @override
  void dispose() {
    amount.dispose();
    merchant.dispose();
    note.dispose();
    account.dispose();
    super.dispose();
  }

  Future<void> save() async {
    if (!form.currentState!.validate()) return;
    if (categoryId == null) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('请先选择分类')));
      return;
    }
    final tx = LedgerTransaction(
      id: widget.transaction?.id ?? 0,
      amountFen: parseMoney(amount.text)!,
      type: type,
      categoryId: categoryId!,
      occurredAt: occurredAt,
      merchant: merchant.text.trim(),
      note: note.text.trim(),
      accountTag: account.text.trim(),
      source: widget.transaction?.source ?? TxSource.manual,
      status: widget.transaction?.status ?? TxStatus.confirmed,
    );
    if (widget.onDraftSaved != null) {
      widget.onDraftSaved!(tx);
      Navigator.pop(context);
      return;
    }
    setState(() => saving = true);
    final ok = await runUi(
      context,
      () => widget.controller.saveTransaction(
        tx,
        expectedRevision: openingRevision,
      ),
      success: widget.transaction == null ? '已记下一笔' : '账单已更新',
    );
    if (!mounted) return;
    setState(() => saving = false);
    if (ok) Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.controller,
    builder: (context, _) {
      final c = widget.controller;
      final colors = Theme.of(context).colorScheme;
      final cats = c.categories
          .where(
            (cat) =>
                cat.type == type &&
                (cat.enabled || cat.id == widget.transaction?.categoryId),
          )
          .toList();
      if (categoryId == null && cats.isNotEmpty) categoryId = cats.first.id;
      return Scaffold(
        appBar: AppBar(
          title: Text(
            widget.onDraftSaved != null
                ? '核对这笔账'
                : widget.transaction == null
                ? '记一笔'
                : '账单详情',
          ),
          actions: [
            if (widget.transaction?.id != null && widget.transaction!.id > 0)
              IconButton(
                tooltip: '删除账单',
                onPressed: saving
                    ? null
                    : () async {
                        final remove = await showDialog<bool>(
                          context: context,
                          builder: (context) => AlertDialog(
                            title: const Text('删除这笔账单？'),
                            content: const Text('删除后无法撤销，请确认这笔记录不再需要。'),
                            actions: [
                              TextButton(
                                onPressed: () => Navigator.pop(context, false),
                                child: const Text('保留'),
                              ),
                              TextButton(
                                onPressed: () => Navigator.pop(context, true),
                                child: const Text('删除'),
                              ),
                            ],
                          ),
                        );
                        if (remove == true && context.mounted) {
                          final ok = await runUi(
                            context,
                            () => c.command(
                              'deleteTransaction',
                              arguments: {
                                'id': widget.transaction!.id,
                                'revision': openingRevision,
                              },
                              ledger: true,
                            ),
                          );
                          if (ok && context.mounted) Navigator.pop(context);
                        }
                      },
                icon: const Icon(Icons.delete_outline),
              ),
          ],
        ),
        body: ContentWidth(
          child: Column(
            children: [
              if (widget.transaction == null)
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 4, 20, 16),
                  child: SegmentedButton<bool>(
                    segments: const [
                      ButtonSegment(
                        value: false,
                        label: Text('手动'),
                        icon: Icon(Icons.edit_outlined),
                      ),
                      ButtonSegment(
                        value: true,
                        label: Text('一句话'),
                        icon: Icon(Icons.short_text),
                      ),
                    ],
                    selected: {ai},
                    onSelectionChanged: saving
                        ? null
                        : (v) => setState(() => ai = v.first),
                  ),
                ),
              Expanded(
                child: ai
                    ? _AiEntry(controller: c)
                    : Form(
                        key: form,
                        child: ListView(
                          padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
                          children: [
                            SegmentedButton<TxType>(
                              segments: TxType.values
                                  .map(
                                    (t) => ButtonSegment(
                                      value: t,
                                      label: Text(t.label),
                                    ),
                                  )
                                  .toList(),
                              selected: {type},
                              onSelectionChanged: saving
                                  ? null
                                  : (v) => setState(() {
                                      type = v.first;
                                      categoryId = null;
                                    }),
                            ),
                            const SizedBox(height: 24),
                            TextFormField(
                              controller: amount,
                              keyboardType:
                                  const TextInputType.numberWithOptions(
                                    decimal: true,
                                  ),
                              inputFormatters: [
                                FilteringTextInputFormatter.allow(
                                  RegExp(r'[0-9.]'),
                                ),
                              ],
                              style: TextStyle(
                                fontSize: 36,
                                fontWeight: FontWeight.w600,
                                fontFeatures: moneyFigures,
                              ),
                              decoration: const InputDecoration(
                                labelText: '金额',
                                floatingLabelBehavior:
                                    FloatingLabelBehavior.always,
                                prefixText: '¥ ',
                                hintText: '0.00',
                              ),
                              validator: (v) => parseMoney(v ?? '') == null
                                  ? '请输入 0.01 至 9,999,999.99 元，最多两位小数'
                                  : null,
                            ),
                            const SectionTitle('分类'),
                            if (cats.isEmpty)
                              const Text('没有可用分类，请先到设置中添加。')
                            else
                              Wrap(
                                spacing: 8,
                                runSpacing: 8,
                                children: cats
                                    .map(
                                      (cat) => ChoiceChip(
                                        avatar: Icon(
                                          categoryIcon(cat.name),
                                          size: 18,
                                        ),
                                        label: Text(cat.name),
                                        selected: cat.id == categoryId,
                                        onSelected: (_) =>
                                            setState(() => categoryId = cat.id),
                                      ),
                                    )
                                    .toList(),
                              ),
                            const SizedBox(height: 24),
                            TextFormField(
                              controller: merchant,
                              maxLength: 100,
                              decoration: const InputDecoration(
                                labelText: '商户',
                                hintText: '例如：楼下便利店',
                                counterText: '',
                              ),
                            ),
                            const SizedBox(height: 16),
                            TextFormField(
                              controller: note,
                              maxLength: 500,
                              maxLines: 2,
                              decoration: const InputDecoration(
                                labelText: '备注',
                                hintText: '为这笔账留点线索',
                                counterText: '',
                              ),
                            ),
                            const SizedBox(height: 16),
                            TextFormField(
                              controller: account,
                              maxLength: 100,
                              decoration: const InputDecoration(
                                labelText: '账户标签（选填）',
                                hintText: '例如：微信、支付宝、现金',
                                counterText: '',
                              ),
                            ),
                            const SizedBox(height: 12),
                            ListTile(
                              contentPadding: EdgeInsets.zero,
                              leading: const Icon(
                                Icons.calendar_today_outlined,
                              ),
                              title: const Text('交易时间'),
                              subtitle: Text(
                                '${occurredAt.year}年${dateLabel(occurredAt)} ${timeLabel(occurredAt)}',
                              ),
                              trailing: const Icon(Icons.chevron_right),
                              onTap: () async {
                                final date = await showDatePicker(
                                  context: context,
                                  initialDate: occurredAt,
                                  firstDate: DateTime(2000),
                                  lastDate: DateTime(2100),
                                );
                                if (date == null || !context.mounted) return;
                                final time = await showTimePicker(
                                  context: context,
                                  initialTime: TimeOfDay.fromDateTime(
                                    occurredAt,
                                  ),
                                );
                                if (time != null && mounted) {
                                  setState(
                                    () => occurredAt = DateTime(
                                      date.year,
                                      date.month,
                                      date.day,
                                      time.hour,
                                      time.minute,
                                    ),
                                  );
                                }
                              },
                            ),
                            if (widget.transaction != null)
                              Padding(
                                padding: const EdgeInsets.symmetric(
                                  vertical: 12,
                                ),
                                child: Text(
                                  '${widget.transaction!.source.label} · ${widget.transaction!.status.label}',
                                  style: TextStyle(
                                    color: colors.onSurfaceVariant,
                                  ),
                                ),
                              ),
                            const SizedBox(height: 24),
                            FilledButton.icon(
                              onPressed: saving || cats.isEmpty ? null : save,
                              icon: saving
                                  ? const SizedBox.square(
                                      dimension: 18,
                                      child: CircularProgressIndicator(
                                        strokeWidth: 2,
                                      ),
                                    )
                                  : const Icon(Icons.check),
                              label: Text(
                                saving
                                    ? '正在保存…'
                                    : widget.onDraftSaved != null
                                    ? '完成核对'
                                    : widget.transaction == null
                                    ? '保存这笔账'
                                    : '保存修改',
                              ),
                            ),
                          ],
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

class _AiEntry extends StatefulWidget {
  const _AiEntry({required this.controller});
  final LedgerController controller;
  @override
  State<_AiEntry> createState() => _AiEntryState();
}

class _AiEntryState extends State<_AiEntry> {
  final input = TextEditingController();
  bool voice = false;
  @override
  void dispose() {
    input.dispose();
    if (widget.controller.isRecording) {
      widget.controller.cancelSpeech().catchError((Object _) {});
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.controller;
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
      children: [
        Text('描述你的收支。', style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 8),
        Text(
          '可以一次说多笔。识别后先核对，再保存到账本。',
          style: TextStyle(
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
        ),
        const SizedBox(height: 20),
        TextField(
          controller: input,
          onChanged: (_) => voice = false,
          minLines: 4,
          maxLines: 8,
          maxLength: 4000,
          decoration: const InputDecoration(
            labelText: '这笔钱怎么花的',
            hintText: '例如：今天午饭 28 元，地铁 4 元',
          ),
        ),
        const SizedBox(height: 8),
        Wrap(
          spacing: 12,
          runSpacing: 8,
          children: [
            OutlinedButton.icon(
              onPressed: c.isParsing
                  ? null
                  : () async {
                      if (c.isRecording) {
                        await runUi(context, c.stopSpeech);
                        return;
                      }
                      await runUi(context, () async {
                        final text = await c.startSpeech();
                        if (mounted) {
                          input.text = text;
                          voice = true;
                        }
                      });
                    },
              icon: Icon(c.isRecording ? Icons.stop : Icons.mic_none),
              label: Text(c.isRecording ? '结束录音' : '语音输入'),
            ),
            FilledButton.icon(
              onPressed: c.isParsing || c.isRecording
                  ? null
                  : () async {
                      if (input.text.trim().isEmpty) {
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(content: Text('先输入一段记账描述')),
                        );
                        return;
                      }
                      await runUi(context, () async {
                        final parsedInput = input.text;
                        final wasVoice = voice;
                        final parsed = await c.parseText(parsedInput);
                        if (wasVoice) {
                          for (
                            var i = c.drafts.length - parsed.length;
                            i < c.drafts.length;
                            i++
                          ) {
                            c.updateDraft(
                              i,
                              c.drafts[i].copyWith(source: TxSource.aiVoice),
                            );
                          }
                        }
                        if (mounted && input.text == parsedInput) {
                          input.clear();
                          voice = false;
                        }
                      });
                    },
              icon: c.isParsing
                  ? const SizedBox.square(
                      dimension: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.arrow_forward),
              label: Text(c.isParsing ? '正在识别…' : '识别账单'),
            ),
          ],
        ),
        if (c.isRecording) ...[
          const SizedBox(height: 12),
          Text(c.speechText.isEmpty ? '正在听，请说出金额和用途…' : c.speechText),
        ],
        if (c.drafts.isNotEmpty) ...[
          SectionTitle(
            '核对 ${c.drafts.length} 笔账单',
            trailing: TextButton(
              onPressed: c.clearDrafts,
              child: const Text('清空'),
            ),
          ),
          for (var i = 0; i < c.drafts.length; i++)
            _DraftRow(controller: c, index: i),
          const SizedBox(height: 20),
          FilledButton.icon(
            onPressed: c.isBusy
                ? null
                : () async {
                    final ok = await runUi(
                      context,
                      () => c.saveDrafts(),
                      success: '已保存到账本',
                    );
                    if (ok && context.mounted) Navigator.pop(context);
                  },
            icon: const Icon(Icons.done_all),
            label: const Text('确认并全部保存'),
          ),
        ],
        const SizedBox(height: 24),
        Text(
          '文字与语音识别内容会发送至你配置的 AI 服务。请勿输入与记账无关的敏感信息。',
          style: TextStyle(
            fontSize: 12,
            height: 1.6,
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
        ),
      ],
    );
  }
}

class _DraftRow extends StatelessWidget {
  const _DraftRow({required this.controller, required this.index});
  final LedgerController controller;
  final int index;
  @override
  Widget build(BuildContext context) {
    final tx = controller.drafts[index];
    final cats = controller.categories.where((cat) => cat.id == tx.categoryId);
    final name = cats.isEmpty ? '未分类' : cats.first.name;
    return Column(
      children: [
        ListTile(
          contentPadding: EdgeInsets.zero,
          title: Text(tx.merchant?.isNotEmpty == true ? tx.merchant! : name),
          subtitle: Text(
            '${tx.type.label} · $name · ${dateLabel(tx.occurredAt)}${tx.note?.isNotEmpty == true ? ' · ${tx.note}' : ''}',
          ),
          trailing: Text(
            money(tx.amountFen),
            style: const TextStyle(fontWeight: FontWeight.w600),
          ),
          onTap: () => Navigator.of(context).push(
            MaterialPageRoute<void>(
              builder: (_) => EntryScreen(
                controller: controller,
                transaction: tx,
                onDraftSaved: (value) => controller.updateDraft(index, value),
              ),
            ),
          ),
        ),
        Row(
          mainAxisAlignment: MainAxisAlignment.end,
          children: [
            TextButton.icon(
              onPressed: () => controller.removeDraft(index),
              icon: const Icon(Icons.close, size: 16),
              label: const Text('移除'),
            ),
            TextButton.icon(
              onPressed: () => Navigator.of(context).push(
                MaterialPageRoute<void>(
                  builder: (_) => EntryScreen(
                    controller: controller,
                    transaction: tx,
                    onDraftSaved: (value) =>
                        controller.updateDraft(index, value),
                  ),
                ),
              ),
              icon: const Icon(Icons.edit_outlined, size: 16),
              label: const Text('修改'),
            ),
          ],
        ),
        const Divider(),
      ],
    );
  }
}

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../core/ledger_controller.dart';
import '../../core/models.dart';
import '../../ui/common.dart';
import '../../ui/theme.dart';
import '../settings/voice_settings_screen.dart';

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
          child: SizedBox(
            width: 720,
            child: Column(
              children: [
                if (widget.transaction == null)
                  Padding(
                    padding: const EdgeInsets.fromLTRB(20, 4, 20, 20),
                    child: SegmentedButton<bool>(
                      showSelectedIcon: false,
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
                      : _manualForm(cats, colors),
                ),
              ],
            ),
          ),
        ),
      );
    },
  );

  Widget _manualForm(
    List<LedgerCategory> categories,
    ColorScheme colors,
  ) => Form(
    key: form,
    child: Column(
      children: [
        Expanded(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(20, 4, 20, 24),
            children: [
              Align(
                alignment: Alignment.centerLeft,
                child: SegmentedButton<TxType>(
                  showSelectedIcon: false,
                  segments: TxType.values
                      .map((t) => ButtonSegment(value: t, label: Text(t.label)))
                      .toList(),
                  selected: {type},
                  onSelectionChanged: saving
                      ? null
                      : (value) => setState(() {
                          type = value.first;
                          categoryId = null;
                        }),
                ),
              ),
              const SizedBox(height: 24),
              TextFormField(
                controller: amount,
                keyboardType: const TextInputType.numberWithOptions(
                  decimal: true,
                ),
                inputFormatters: [
                  FilteringTextInputFormatter.allow(RegExp(r'[0-9.]')),
                ],
                style: TextStyle(
                  fontSize: 44,
                  height: 1.2,
                  fontWeight: FontWeight.w500,
                  color: colors.onSurface,
                  fontFeatures: moneyFigures,
                ),
                decoration: InputDecoration(
                  labelText: '金额',
                  floatingLabelBehavior: FloatingLabelBehavior.always,
                  prefixText: '¥ ',
                  prefixStyle: TextStyle(
                    fontSize: 28,
                    fontWeight: FontWeight.w400,
                    color: colors.onSurfaceVariant,
                  ),
                  hintText: '0.00',
                  hintStyle: TextStyle(color: colors.onSurfaceVariant),
                  filled: false,
                  contentPadding: const EdgeInsets.only(top: 12, bottom: 16),
                  border: UnderlineInputBorder(
                    borderSide: BorderSide(color: colors.outlineVariant),
                  ),
                  enabledBorder: UnderlineInputBorder(
                    borderSide: BorderSide(color: colors.outlineVariant),
                  ),
                  focusedBorder: UnderlineInputBorder(
                    borderSide: BorderSide(color: colors.primary, width: 2),
                  ),
                ),
                validator: (value) => parseMoney(value ?? '') == null
                    ? '请输入 0.01 至 9,999,999.99 元，最多两位小数'
                    : null,
              ),
              const SectionTitle('分类'),
              if (categories.isEmpty)
                const Text('没有可用分类，请先到设置中添加。')
              else
                LayoutBuilder(
                  builder: (context, constraints) {
                    final columns = constraints.maxWidth >= 560 ? 6 : 4;
                    final width =
                        (constraints.maxWidth - (columns - 1) * 8) / columns;
                    return Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: [
                        for (final category in categories)
                          SizedBox(
                            width: width,
                            child: _CategoryOption(
                              category: category,
                              selected: category.id == categoryId,
                              onSelected: saving
                                  ? null
                                  : () => setState(
                                      () => categoryId = category.id,
                                    ),
                            ),
                          ),
                      ],
                    );
                  },
                ),
              const SectionTitle('补充信息'),
              SectionSurface(
                padding: const EdgeInsets.symmetric(
                  horizontal: 16,
                  vertical: 4,
                ),
                child: Column(
                  children: [
                    TextFormField(
                      controller: merchant,
                      maxLength: 100,
                      decoration: _detailDecoration('商户', '例如：楼下便利店'),
                    ),
                    TextFormField(
                      controller: account,
                      maxLength: 100,
                      decoration: _detailDecoration('账户标签（选填）', '例如：微信、支付宝、现金'),
                    ),
                    TextFormField(
                      controller: note,
                      maxLength: 500,
                      minLines: 1,
                      maxLines: 3,
                      decoration: _detailDecoration('备注', '为这笔账留点线索'),
                    ),
                    ListTile(
                      contentPadding: const EdgeInsets.symmetric(vertical: 4),
                      title: const Text('交易时间'),
                      subtitle: Text(
                        '${occurredAt.year}年${dateLabel(occurredAt)} ${timeLabel(occurredAt)}',
                      ),
                      trailing: Icon(
                        Icons.calendar_today_outlined,
                        size: 20,
                        color: colors.onSurfaceVariant,
                      ),
                      onTap: saving
                          ? null
                          : () async {
                              final date = await showDatePicker(
                                context: context,
                                initialDate: occurredAt,
                                firstDate: DateTime(2000),
                                lastDate: DateTime(2100),
                              );
                              if (date == null || !mounted) return;
                              final time = await showTimePicker(
                                context: context,
                                initialTime: TimeOfDay.fromDateTime(occurredAt),
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
                  ],
                ),
              ),
              if (widget.transaction != null)
                Padding(
                  padding: const EdgeInsets.only(top: 16),
                  child: Text(
                    '${widget.transaction!.source.label} · ${widget.transaction!.status.label}',
                    style: TextStyle(
                      fontSize: 12,
                      color: colors.onSurfaceVariant,
                    ),
                  ),
                ),
            ],
          ),
        ),
        SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 16),
            child: SizedBox(
              width: double.infinity,
              child: FilledButton.icon(
                onPressed: saving || categories.isEmpty ? null : save,
                icon: saving
                    ? const SizedBox.square(
                        dimension: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.check, size: 20),
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
            ),
          ),
        ),
      ],
    ),
  );

  InputDecoration _detailDecoration(String label, String hint) {
    final colors = Theme.of(context).colorScheme;
    return InputDecoration(
      labelText: label,
      hintText: hint,
      counterText: '',
      filled: false,
      contentPadding: const EdgeInsets.symmetric(vertical: 14),
      border: UnderlineInputBorder(
        borderSide: BorderSide(color: colors.outlineVariant),
      ),
      enabledBorder: UnderlineInputBorder(
        borderSide: BorderSide(color: colors.outlineVariant),
      ),
      focusedBorder: UnderlineInputBorder(
        borderSide: BorderSide(color: colors.primary),
      ),
    );
  }
}

class _CategoryOption extends StatelessWidget {
  const _CategoryOption({
    required this.category,
    required this.selected,
    required this.onSelected,
  });
  final LedgerCategory category;
  final bool selected;
  final VoidCallback? onSelected;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Semantics(
      selected: selected,
      button: true,
      child: Material(
        color: selected ? colors.primaryContainer : Colors.transparent,
        borderRadius: BorderRadius.circular(12),
        child: InkWell(
          onTap: onSelected,
          borderRadius: BorderRadius.circular(12),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 12),
            child: Column(
              children: [
                Icon(
                  ledgerCategoryIcon(category),
                  size: 24,
                  color: selected
                      ? colors.onPrimaryContainer
                      : colors.onSurfaceVariant,
                ),
                const SizedBox(height: 8),
                Text(
                  category.name,
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
                    color: selected
                        ? colors.onPrimaryContainer
                        : colors.onSurface,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _AiEntry extends StatefulWidget {
  const _AiEntry({required this.controller});
  final LedgerController controller;
  @override
  State<_AiEntry> createState() => _AiEntryState();
}

class _AiEntryState extends State<_AiEntry> with WidgetsBindingObserver {
  final input = TextEditingController();
  bool voice = false;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused && widget.controller.isRecording) {
      widget.controller.cancelSpeech().catchError((Object _) {});
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
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
        const PageHeading(title: '说说这笔收支', subtitle: '可以一次说多笔。识别后先核对，再保存到账本。'),
        const SizedBox(height: 24),
        TextField(
          controller: input,
          onChanged: (_) => voice = false,
          minLines: 4,
          maxLines: 8,
          maxLength: 4000,
          decoration: InputDecoration(
            labelText: '这笔钱怎么花的',
            hintText: '例如：今天午饭 28 元，地铁 4 元',
            fillColor: Theme.of(context).colorScheme.secondaryContainer,
            alignLabelWithHint: true,
          ),
        ),
        const SizedBox(height: 8),
        Wrap(
          spacing: 12,
          runSpacing: 8,
          children: [
            OutlinedButton.icon(
              onPressed:
                  c.isParsing ||
                      c.isTranscribing ||
                      (c.isRecording && c.speechState == 'preparing')
                  ? null
                  : () async {
                      if (c.isRecording) {
                        await runUi(context, c.stopSpeech);
                        return;
                      }
                      if (c.settings['hasSpeechKey'] != true) {
                        await Navigator.of(context).push(
                          MaterialPageRoute<void>(
                            builder: (_) => VoiceSettingsScreen(controller: c),
                          ),
                        );
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
              label: Text(
                c.isTranscribing
                    ? '正在转写…'
                    : c.isRecording
                    ? '结束录音'
                    : '语音输入',
              ),
            ),
            if (!c.isRecording)
              TextButton(
                onPressed: c.isParsing
                    ? null
                    : () => Navigator.of(context).push(
                        MaterialPageRoute<void>(
                          builder: (_) => VoiceSettingsScreen(controller: c),
                        ),
                      ),
                child: const Text('语音模型'),
              ),
            if (c.isRecording)
              TextButton(
                onPressed: () => runUi(context, c.cancelSpeech),
                child: const Text('取消'),
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
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(
                Icons.mic,
                size: 18,
                color: Theme.of(context).colorScheme.primary,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  c.isTranscribing
                      ? '录音已结束，正在转写成文字…'
                      : c.speechState == 'preparing'
                      ? '正在准备麦克风…'
                      : '正在录音，请说出金额和用途，最长 60 秒。',
                ),
              ),
            ],
          ),
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
          '录音发送至语音模型，转写文字在点击“识别账单”后发送至 AI 服务。请勿输入与记账无关的敏感信息。',
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
          contentPadding: const EdgeInsets.only(top: 8),
          leading: CategoryMark(category: cats.isEmpty ? null : cats.first),
          title: Text(tx.merchant?.isNotEmpty == true ? tx.merchant! : name),
          subtitle: Text(
            '${tx.type.label} · $name · ${dateLabel(tx.occurredAt)}${tx.note?.isNotEmpty == true ? ' · ${tx.note}' : ''}',
          ),
          trailing: AmountLabel(tx.amountFen),
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

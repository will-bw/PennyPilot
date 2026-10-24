import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../core/ledger_controller.dart';
import '../../core/models.dart';
import '../../ui/common.dart';
import '../../ui/theme.dart';
import '../settings/voice_settings_screen.dart';
import '../settings/recognition_settings_screen.dart';
import 'bill_import_screen.dart';

class EntryScreen extends StatefulWidget {
  const EntryScreen({
    super.key,
    required this.controller,
    this.transaction,
    this.onDraftSaved,
    this.initialAi = false,
    this.sharedImage = false,
  });
  final LedgerController controller;
  final LedgerTransaction? transaction;
  final ValueChanged<LedgerTransaction>? onDraftSaved;
  final bool initialAi, sharedImage;
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
  late String businessKind = widget.transaction?.effectiveKind ?? 'expense';
  bool saving = false;
  late bool ai = widget.initialAi;
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
      businessKind: businessKind,
      metadataJson: widget.transaction?.metadataJson,
      editVersion: widget.transaction?.editVersion ?? 0,
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
        confirmPending: widget.transaction?.status == TxStatus.pending,
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
                      ? _AiEntry(controller: c, sharedImage: widget.sharedImage)
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
                          if (businessKind == 'expense' ||
                              businessKind == 'income' ||
                              businessKind == 'refund' &&
                                  type == TxType.expense) {
                            businessKind = type == TxType.expense
                                ? 'expense'
                                : 'income';
                          }
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
              const SizedBox(height: 20),
              DropdownButtonFormField<String>(
                key: ValueKey('business-kind-$businessKind'),
                initialValue: businessKind,
                decoration: const InputDecoration(labelText: '账务用途'),
                items:
                    const {
                          'expense': '消费支出',
                          'income': '收入',
                          'transfer': '本人账户转移',
                          'receivable': '借出／垫付（应收）',
                          'payable': '借入／偿还（应付）',
                          'refund': '退款',
                          'unknown': '待分类资金变动',
                        }.entries
                        .map(
                          (e) => DropdownMenuItem(
                            value: e.key,
                            child: Text(e.value),
                          ),
                        )
                        .toList(),
                validator: (value) =>
                    (widget.transaction?.status == TxStatus.pending ||
                            widget.onDraftSaved != null &&
                                widget.transaction?.source !=
                                    TxSource.billImport) &&
                        value == 'unknown'
                    ? '请先选择这笔转账的用途，再确认入账'
                    : null,
                onChanged: saving
                    ? null
                    : (value) => setState(() {
                        businessKind = value!;
                        final nextType = value == 'expense'
                            ? TxType.expense
                            : const {'income', 'refund'}.contains(value)
                            ? TxType.income
                            : type;
                        if (nextType != type) {
                          type = nextType;
                          categoryId = null;
                        }
                      }),
              ),
              if (!const {'expense', 'income', 'refund'}.contains(businessKind))
                const Padding(
                  padding: EdgeInsets.only(top: 8),
                  child: Text('这笔资金变动不计入消费或收入统计。'),
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
              if (widget.transaction?.recognitionMetadata.isNotEmpty == true)
                _StoredEvidence(transaction: widget.transaction!),
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
                      : widget.transaction?.status == TxStatus.pending
                      ? '保存并确认'
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
  const _AiEntry({required this.controller, this.sharedImage = false});
  final LedgerController controller;
  final bool sharedImage;
  @override
  State<_AiEntry> createState() => _AiEntryState();
}

class _AiEntryState extends State<_AiEntry> with WidgetsBindingObserver {
  final input = TextEditingController();
  Map<String, dynamic>? shared;
  String? recognitionSession;
  Future<void> recognize(
    String text, {
    TxSource source = TxSource.aiText,
  }) async {
    recognitionSession ??= DateTime.now().microsecondsSinceEpoch.toString();
    await widget.controller.recognizeInput(
      text: text,
      source: source,
      sessionId: recognitionSession,
    );
    if (mounted && widget.controller.recognitionQuestions.isEmpty) {
      input.clear();
      recognitionSession = null;
    }
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    if (widget.sharedImage) {
      WidgetsBinding.instance.addPostFrameCallback((_) async {
        await runUi(context, () async {
          final value = await widget.controller.command(
            'consumeSharedImage',
            refreshAfter: false,
          );
          if (mounted && value != null) {
            setState(() => shared = stringMap(value));
          }
        });
      });
    }
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

  Future<void> image() async {
    final c = widget.controller;
    if (c.settings['cloudImageParsingEnabled'] != true) {
      await Navigator.push(
        context,
        MaterialPageRoute<void>(
          builder: (_) => RecognitionSettingsScreen(controller: c),
        ),
      );
      return;
    }
    if (!mounted) return;
    await runUi(context, () async {
      final raw =
          shared ??
          await c.command('pickRecognitionImage', refreshAfter: false);
      if (raw == null) return;
      final data = stringMap(raw);
      // Native picker contract uses base64; the model transport names it explicitly.
      await c.recognizeInput(
        text: input.text,
        source: TxSource.aiImage,
        image: {
          'imageBase64': data['base64'] ?? data['imageBase64'],
          'mimeType': data['mimeType'],
        },
      );
      if (mounted) setState(() => shared = null);
    });
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.controller;
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
      children: [
        const PageHeading(
          title: '说说这笔收支',
          subtitle: '文字、语音、图片或账单文件，都可以一次录入多笔。',
        ),
        const SizedBox(height: 24),
        TextField(
          controller: input,
          minLines: 4,
          maxLines: 8,
          maxLength: 4000,
          decoration: InputDecoration(
            labelText: '记账描述或补充信息',
            hintText: '例如：昨天午饭 28 元，地铁 4 元；今天借给小王 200 元',
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
                        await Navigator.push(
                          context,
                          MaterialPageRoute<void>(
                            builder: (_) => VoiceSettingsScreen(controller: c),
                          ),
                        );
                        return;
                      }
                      if (!context.mounted) return;
                      await runUi(context, () async {
                        final text = await c.startSpeech();
                        if (mounted && text.trim().isNotEmpty) {
                          input.text = text;
                          await recognize(text, source: TxSource.aiVoice);
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
            if (c.isRecording)
              TextButton(
                onPressed: () => runUi(context, c.cancelSpeech),
                child: const Text('取消'),
              ),
            FilledButton.icon(
              onPressed: c.isParsing || c.isRecording
                  ? null
                  : () => runUi(context, () async {
                      await recognize(input.text);
                    }),
              icon: c.isParsing
                  ? const SizedBox.square(
                      dimension: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.arrow_forward),
              label: Text(c.isParsing ? '正在识别…' : '识别账单'),
            ),
            OutlinedButton.icon(
              onPressed: c.isBusy ? null : image,
              icon: const Icon(Icons.image_outlined),
              label: Text(shared == null ? '图片记账' : '识别分享的图片'),
            ),
            OutlinedButton.icon(
              onPressed: c.isBusy
                  ? null
                  : () => Navigator.push(
                      context,
                      MaterialPageRoute<void>(
                        builder: (_) => BillImportScreen(controller: c),
                      ),
                    ),
              icon: const Icon(Icons.file_open_outlined),
              label: const Text('导入账单'),
            ),
            TextButton(
              onPressed: () => Navigator.push(
                context,
                MaterialPageRoute<void>(
                  builder: (_) => VoiceSettingsScreen(controller: c),
                ),
              ),
              child: const Text('语音模型'),
            ),
            TextButton(
              onPressed: () => Navigator.push(
                context,
                MaterialPageRoute<void>(
                  builder: (_) => RecognitionSettingsScreen(controller: c),
                ),
              ),
              child: const Text('识别设置'),
            ),
          ],
        ),
        if (c.isRecording)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: Text(
              c.isTranscribing
                  ? '录音已结束，正在转写成文字…'
                  : c.speechState == 'preparing'
                  ? '正在准备麦克风…'
                  : '正在录音，结束后自动生成账单草稿，最长 60 秒。',
            ),
          ),
        if (c.recognitionNotice != null)
          Padding(
            padding: const EdgeInsets.only(top: 20),
            child: Semantics(
              liveRegion: true,
              child: Text(c.recognitionNotice!),
            ),
          ),
        if (c.recognitionQuestions.isNotEmpty) ...[
          const SectionTitle('需要补充'),
          for (final question in c.recognitionQuestions)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Text(question),
            ),
          const Text('在上面的描述中补充完整，再重新识别。已有草稿可保留或移除。'),
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
          if (c.drafts.any(
            (tx) =>
                tx.source == TxSource.billImport &&
                tx.effectiveKind == 'unknown',
          ))
            const Text('账单文件中用途未知的转账会保存到“待确认”，选择用途后才会确认入账。'),
          const SizedBox(height: 20),
          FilledButton.icon(
            onPressed: c.isBusy
                ? null
                : () async {
                    await runUi(
                      context,
                      () => c.saveDrafts(),
                      success: '已保存到账本',
                    );
                  },
            icon: const Icon(Icons.done_all),
            label: const Text('确认并全部保存'),
          ),
        ],
        const SizedBox(height: 24),
        Text(
          '录音发送至语音模型，转写后自动进行记账理解；主动输入发送至所选 AI 服务。图片需单独开启云端识别，CSV 在本机解析。',
          style: Theme.of(context).textTheme.bodySmall?.copyWith(
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
    final cat = controller.categoryById(tx.categoryId);
    final metadata = tx.recognitionMetadata;
    final evidence = metadata['fieldEvidence'] is Map
        ? stringMap(metadata['fieldEvidence'])
        : <String, dynamic>{};
    void edit() => Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => EntryScreen(
          controller: controller,
          transaction: tx,
          onDraftSaved: (value) => controller.updateDraft(index, value),
        ),
      ),
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        ListTile(
          contentPadding: const EdgeInsets.only(top: 8),
          leading: CategoryMark(category: cat),
          title: Text(
            tx.merchant?.isNotEmpty == true ? tx.merchant! : cat?.name ?? '待分类',
          ),
          subtitle: Text(
            '${tx.businessLabel} · ${tx.type.label} · ${dateLabel(tx.occurredAt)}${tx.note?.isNotEmpty == true ? ' · ${tx.note}' : ''}',
          ),
          trailing: AmountLabel(tx.amountFen),
          onTap: edit,
        ),
        if (evidence.isNotEmpty)
          ExpansionTile(
            tilePadding: EdgeInsets.zero,
            title: Text('查看依据 · ${tx.source.label}'),
            children: [
              for (final entry in evidence.entries)
                if (entry.value is Map)
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    dense: true,
                    title: Text(
                      '${const {'amount': '金额', 'type': '方向', 'occurredAt': '时间'}[entry.key] ?? entry.key}：${entry.value['quote'] ?? '未提供原文'}',
                    ),
                    subtitle: Text(
                      const {
                            'user': '你说的',
                            'image': '图片中的文字',
                            'file': '账单文件',
                            'derived': '根据描述计算，需核对',
                            'inference': '模型推断，需核对',
                          }[entry.value['source']] ??
                          '待核对',
                    ),
                  ),
              if (metadata['timePrecision'] == 'user_entry_time')
                const ListTile(
                  contentPadding: EdgeInsets.zero,
                  title: Text('未指定日期，按本次录入时间保存，可点“修改”调整。'),
                ),
            ],
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
              onPressed: edit,
              icon: const Icon(Icons.edit_outlined, size: 16),
              label: Text(tx.effectiveKind == 'unknown' ? '选择用途' : '修改'),
            ),
          ],
        ),
        const Divider(),
      ],
    );
  }
}

class _StoredEvidence extends StatelessWidget {
  const _StoredEvidence({required this.transaction});
  final LedgerTransaction transaction;
  @override
  Widget build(BuildContext context) {
    final metadata = transaction.recognitionMetadata;
    final facts = metadata['fieldEvidence'] is Map
        ? stringMap(metadata['fieldEvidence'])
        : <String, dynamic>{};
    final questions = (metadata['questions'] as List? ?? [])
        .whereType<String>();
    return ExpansionTile(
      tilePadding: EdgeInsets.zero,
      title: const Text('识别依据与待核对信息'),
      children: [
        ListTile(
          contentPadding: EdgeInsets.zero,
          title: Text(transaction.businessLabel),
          subtitle: Text(
            '${transaction.source.label} · ${metadata['paymentStatus'] ?? metadata['payment_status'] ?? '请核对凭证'}',
          ),
        ),
        for (final question in questions)
          ListTile(contentPadding: EdgeInsets.zero, title: Text(question)),
        for (final fact in facts.entries)
          ListTile(
            contentPadding: EdgeInsets.zero,
            title: Text(
              const {
                    'amount': '金额',
                    'type': '资金方向',
                    'direction': '资金方向',
                    'paymentStatus': '支付状态',
                    'occurredAt': '交易时间',
                    'transferTime': '转账时间',
                    'merchant': '交易对方',
                    'refundId': '退款单号',
                    'orderId': '交易单号',
                    'originalOrderId': '原支付单号',
                  }[fact.key] ??
                  fact.key,
            ),
            subtitle: Text(
              fact.value is Map
                  ? '${fact.value['quote'] ?? fact.value['value'] ?? '未提供原文'}'
                  : fact.value.toString(),
            ),
          ),
        if (facts.isEmpty)
          const ListTile(
            contentPadding: EdgeInsets.zero,
            title: Text('该条记录没有逐字段原文，请对照支付凭证核对。'),
          ),
      ],
    );
  }
}

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
  late String businessKind = widget.transaction?.effectiveKind ?? 'expense';
  bool saving = false;
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
              children: [Expanded(child: _manualForm(cats, colors))],
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

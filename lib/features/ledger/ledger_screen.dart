import 'package:flutter/material.dart';
import '../../core/ledger_controller.dart';
import '../../core/models.dart';
import '../../ui/common.dart';
import '../../ui/theme.dart';
import '../entry/entry_screen.dart';

class LedgerScreen extends StatefulWidget {
  const LedgerScreen({
    super.key,
    required this.controller,
    required this.onBudget,
  });
  final LedgerController controller;
  final VoidCallback onBudget;
  @override
  State<LedgerScreen> createState() => _LedgerScreenState();
}

class _LedgerScreenState extends State<LedgerScreen> {
  bool searching = false;
  late final search = TextEditingController(text: widget.controller.keyword);
  @override
  void dispose() {
    search.dispose();
    super.dispose();
  }

  void _entry([LedgerTransaction? tx]) => Navigator.of(context).push(
    MaterialPageRoute<void>(
      builder: (_) =>
          EntryScreen(controller: widget.controller, transaction: tx),
    ),
  );

  @override
  Widget build(BuildContext context) {
    final c = widget.controller;
    final colors = Theme.of(context).colorScheme;
    final rows = c.filteredTransactions;
    final summary = c.monthSummary;
    final days = <DateTime, List<LedgerTransaction>>{};
    for (final tx in rows) {
      days.putIfAbsent(DateUtils.dateOnly(tx.occurredAt), () => []).add(tx);
    }
    return Scaffold(
      appBar: AppBar(
        title: const Text('账本'),
        actions: [
          IconButton(
            tooltip: searching ? '收起搜索' : '搜索账单',
            onPressed: () => setState(() => searching = !searching),
            icon: Icon(
              searching ? Icons.close : Icons.search,
              color: c.keyword.isNotEmpty ? colors.primary : null,
            ),
          ),
          IconButton(
            tooltip: '刷新账本',
            onPressed: c.isLoading ? null : c.refresh,
            icon: const Icon(Icons.refresh, size: 21),
          ),
          const SizedBox(width: 8),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _entry,
        icon: const Icon(Icons.add, size: 22),
        label: const Text('记一笔'),
      ),
      body: ContentWidth(
        child: RefreshIndicator(
          onRefresh: c.refresh,
          child: CustomScrollView(
            physics: const AlwaysScrollableScrollPhysics(),
            slivers: [
              SliverPadding(
                padding: const EdgeInsets.symmetric(horizontal: 20),
                sliver: SliverToBoxAdapter(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      SectionSurface(
                        padding: const EdgeInsets.fromLTRB(18, 2, 18, 16),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            MonthSelector(
                              month: c.selectedMonth,
                              onChanged: c.setMonth,
                            ),
                            const SizedBox(height: 6),
                            LayoutBuilder(
                              builder: (context, constraints) {
                                final label = Text(
                                  '本月支出',
                                  style: TextStyle(
                                    fontSize: 14,
                                    color: colors.onSurfaceVariant,
                                  ),
                                );
                                final value = Text(
                                  money(summary.expenseFen),
                                  style: TextStyle(
                                    fontSize: 30,
                                    fontWeight: FontWeight.w600,
                                    letterSpacing: -.6,
                                    fontFeatures: moneyFigures,
                                    color: colors.onSurface,
                                  ),
                                );
                                if (constraints.maxWidth < 300 ||
                                    MediaQuery.textScalerOf(context).scale(14) >
                                        18) {
                                  return Column(
                                    crossAxisAlignment:
                                        CrossAxisAlignment.start,
                                    children: [
                                      label,
                                      const SizedBox(height: 4),
                                      value,
                                    ],
                                  );
                                }
                                return Row(
                                  children: [
                                    Expanded(child: label),
                                    Flexible(child: value),
                                  ],
                                );
                              },
                            ),
                            const SizedBox(height: 12),
                            Wrap(
                              spacing: 28,
                              runSpacing: 12,
                              children: [
                                _Total(
                                  '收入',
                                  summary.incomeFen,
                                  colors.tertiary,
                                ),
                                _Total(
                                  '结余',
                                  summary.balanceFen,
                                  colors.onSurface,
                                ),
                              ],
                            ),
                            const SizedBox(height: 16),
                            const Divider(),
                            InkWell(
                              onTap: widget.onBudget,
                              borderRadius: BorderRadius.circular(8),
                              child: Container(
                                constraints: const BoxConstraints(
                                  minHeight: 48,
                                ),
                                alignment: Alignment.centerLeft,
                                padding: const EdgeInsets.only(
                                  top: 12,
                                  bottom: 8,
                                ),
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Row(
                                      children: [
                                        Icon(
                                          Icons.pie_chart_outline,
                                          size: 17,
                                          color: colors.onSurfaceVariant,
                                        ),
                                        const SizedBox(width: 8),
                                        Expanded(
                                          child: Text(
                                            c.budgetFen > 0
                                                ? (c.budgetRemainingFen >= 0
                                                      ? '预算还剩 ${money(c.budgetRemainingFen)}'
                                                      : '预算已超出 ${money(-c.budgetRemainingFen)}')
                                                : '设置月预算',
                                            style: TextStyle(
                                              fontSize: 13,
                                              color:
                                                  c.budgetRemainingFen < 0 &&
                                                      c.budgetFen > 0
                                                  ? colors.error
                                                  : colors.onSurfaceVariant,
                                            ),
                                          ),
                                        ),
                                        Icon(
                                          Icons.chevron_right,
                                          size: 18,
                                          color: colors.onSurfaceVariant,
                                        ),
                                      ],
                                    ),
                                    if (c.budgetFen > 0) ...[
                                      const SizedBox(height: 9),
                                      Semantics(
                                        label:
                                            '本月预算已用 ${(c.budgetProgress * 100).round()}%',
                                        child: LinearProgressIndicator(
                                          value: c.budgetProgress.clamp(0, 1),
                                          minHeight: 3,
                                          borderRadius: BorderRadius.circular(
                                            2,
                                          ),
                                          color: c.budgetRemainingFen < 0
                                              ? colors.error
                                              : colors.primary,
                                        ),
                                      ),
                                    ],
                                  ],
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                      if (c.pendingTransactions.isNotEmpty)
                        Padding(
                          padding: const EdgeInsets.only(top: 12),
                          child: Material(
                            color: colors.secondaryContainer,
                            borderRadius: BorderRadius.circular(10),
                            child: ListTile(
                              minTileHeight: 48,
                              contentPadding: const EdgeInsets.symmetric(
                                horizontal: 14,
                              ),
                              leading: Icon(
                                Icons.inbox_outlined,
                                size: 20,
                                color: colors.onSecondaryContainer,
                              ),
                              minLeadingWidth: 20,
                              title: Text(
                                '${c.pendingTransactions.length} 笔账单等待确认',
                                style: TextStyle(
                                  fontSize: 13,
                                  color: colors.onSecondaryContainer,
                                ),
                              ),
                              trailing: Icon(
                                Icons.chevron_right,
                                size: 18,
                                color: colors.onSecondaryContainer,
                              ),
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(10),
                              ),
                              onTap: () => Navigator.of(context).push(
                                MaterialPageRoute<void>(
                                  builder: (_) => PendingScreen(controller: c),
                                ),
                              ),
                            ),
                          ),
                        ),
                      const SizedBox(height: 18),
                      if (searching) ...[
                        TextField(
                          controller: search,
                          onChanged: c.setKeyword,
                          decoration: InputDecoration(
                            hintText: '搜索商户、备注或分类',
                            prefixIcon: const Icon(Icons.search, size: 20),
                            suffixIcon: c.keyword.isEmpty
                                ? null
                                : IconButton(
                                    tooltip: '清空搜索',
                                    icon: const Icon(Icons.close, size: 18),
                                    onPressed: () {
                                      search.clear();
                                      c.setKeyword('');
                                    },
                                  ),
                            isDense: true,
                          ),
                        ),
                        const SizedBox(height: 8),
                      ],
                      SingleChildScrollView(
                        scrollDirection: Axis.horizontal,
                        child: Row(
                          children: [
                            for (final type in <TxType?>[
                              null,
                              ...TxType.values,
                            ])
                              Padding(
                                padding: const EdgeInsets.only(right: 4),
                                child: ChoiceChip(
                                  label: Text(type?.label ?? '全部收支'),
                                  selected: c.typeFilter == type,
                                  onSelected: (_) => c.setTypeFilter(type),
                                ),
                              ),
                            const SizedBox(width: 4),
                            FilterChip(
                              label: const Text('已忽略'),
                              selected: c.statusFilter == TxStatus.ignored,
                              onSelected: (v) => c.setStatusFilter(
                                v ? TxStatus.ignored : null,
                              ),
                            ),
                          ],
                        ),
                      ),
                      if (c.keyword.isNotEmpty && !searching)
                        Padding(
                          padding: const EdgeInsets.only(top: 6, bottom: 4),
                          child: Text(
                            '搜索“${c.keyword}” · ${rows.length} 笔',
                            style: TextStyle(
                              fontSize: 12,
                              color: colors.primary,
                            ),
                          ),
                        ),
                      if (c.error != null)
                        Padding(
                          padding: const EdgeInsets.symmetric(vertical: 8),
                          child: Text(
                            c.error.toString(),
                            style: TextStyle(color: colors.error),
                          ),
                        ),
                    ],
                  ),
                ),
              ),
              if (rows.isEmpty)
                SliverToBoxAdapter(
                  child: EmptyState(
                    icon: Icons.receipt_long_outlined,
                    title: c.keyword.isNotEmpty ? '没有找到这笔账' : '这个月还没有账单',
                    description: c.keyword.isNotEmpty
                        ? '换个关键词，或调整收支筛选。'
                        : c.capabilities['automaticCaptureSupported'] == true
                        ? '添加第一笔账单，或在设置中开启自动采集。'
                        : '添加第一笔账单，或在设置中导入已有账本备份。',
                    action: c.keyword.isEmpty
                        ? OutlinedButton.icon(
                            onPressed: _entry,
                            icon: const Icon(Icons.add),
                            label: const Text('记下第一笔'),
                          )
                        : null,
                  ),
                ),
              for (final day in days.entries) ...[
                SliverToBoxAdapter(
                  child: _DayHeading(day: day.key, transactions: day.value),
                ),
                SliverList(
                  delegate: SliverChildBuilderDelegate((context, index) {
                    final tx = day.value[index];
                    return TransactionRow(
                      transaction: tx,
                      category: _category(c, tx.categoryId),
                      onTap: () => _entry(tx),
                    );
                  }, childCount: day.value.length),
                ),
              ],
              const SliverToBoxAdapter(child: SizedBox(height: 100)),
            ],
          ),
        ),
      ),
    );
  }
}

class _DayHeading extends StatelessWidget {
  const _DayHeading({required this.day, required this.transactions});
  final DateTime day;
  final List<LedgerTransaction> transactions;
  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final expense = transactions
        .where(
          (t) => t.type == TxType.expense && t.status == TxStatus.confirmed,
        )
        .fold(0, (sum, t) => sum + t.amountFen);
    final income = transactions
        .where((t) => t.type == TxType.income && t.status == TxStatus.confirmed)
        .fold(0, (sum, t) => sum + t.amountFen);
    final weekday = const [
      '周一',
      '周二',
      '周三',
      '周四',
      '周五',
      '周六',
      '周日',
    ][day.weekday - 1];
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 20, 20, 5),
      child: Column(
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '${day.day}'.padLeft(2, '0'),
                style: TextStyle(
                  fontSize: 22,
                  fontWeight: FontWeight.w500,
                  color: colors.onSurface,
                  fontFeatures: moneyFigures,
                ),
              ),
              const SizedBox(width: 8),
              Padding(
                padding: const EdgeInsets.only(top: 6),
                child: Text(
                  weekday,
                  style: TextStyle(
                    fontSize: 12,
                    color: colors.onSurfaceVariant,
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: Text(
                    [
                      if (expense > 0) '支 ${money(expense)}',
                      if (income > 0) '收 ${money(income)}',
                    ].join('  '),
                    textAlign: TextAlign.end,
                    style: TextStyle(
                      fontSize: 12,
                      color: colors.onSurfaceVariant,
                      fontFeatures: moneyFigures,
                    ),
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          const Divider(),
        ],
      ),
    );
  }
}

LedgerCategory? _category(LedgerController c, int id) {
  for (final category in c.categories) {
    if (category.id == id) return category;
  }
  return null;
}

class _Total extends StatelessWidget {
  const _Total(this.label, this.amount, this.color);
  final String label;
  final int amount;
  final Color color;
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(
        label,
        style: TextStyle(
          color: Theme.of(context).colorScheme.onSurfaceVariant,
          fontSize: 12,
        ),
      ),
      const SizedBox(height: 4),
      Text(
        money(amount),
        style: TextStyle(
          color: color,
          fontWeight: FontWeight.w500,
          fontSize: 17,
          fontFeatures: moneyFigures,
        ),
      ),
    ],
  );
}

class TransactionRow extends StatelessWidget {
  const TransactionRow({
    super.key,
    required this.transaction,
    this.category,
    required this.onTap,
  });
  final LedgerTransaction transaction;
  final LedgerCategory? category;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) {
    final tx = transaction;
    final colors = Theme.of(context).colorScheme;
    final title = tx.merchant?.isNotEmpty == true
        ? tx.merchant!
        : category?.name ?? '未分类';
    final detail = [
      if (!tx.countsAsExpense && !tx.countsAsIncome) tx.businessLabel,
      category?.name ?? '未分类',
      if (tx.note?.isNotEmpty == true) tx.note!,
      if (tx.status != TxStatus.confirmed) tx.status.label,
    ].join(' · ');
    final amount = Text(
      '${tx.type == TxType.income ? '+' : '−'}${money(tx.amountFen)}',
      style: TextStyle(
        fontSize: 16,
        fontWeight: FontWeight.w600,
        color: tx.type == TxType.income ? colors.tertiary : colors.onSurface,
        fontFeatures: moneyFigures,
      ),
    );
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 13),
        child: LayoutBuilder(
          builder: (context, constraints) {
            final stacked =
                constraints.maxWidth < 330 &&
                MediaQuery.textScalerOf(context).scale(16) > 20;
            return Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                CategoryMark(category: category),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        detail,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 12,
                          color: colors.onSurfaceVariant,
                        ),
                      ),
                      if (stacked) ...[const SizedBox(height: 6), amount],
                    ],
                  ),
                ),
                if (!stacked) ...[
                  const SizedBox(width: 12),
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.only(top: 1),
                      child: Align(
                        alignment: Alignment.centerRight,
                        child: amount,
                      ),
                    ),
                  ),
                ],
              ],
            );
          },
        ),
      ),
    );
  }
}

class PendingScreen extends StatelessWidget {
  const PendingScreen({super.key, required this.controller});
  final LedgerController controller;
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: controller,
    builder: (context, _) {
      final rows = controller.pendingTransactions;
      final confirmable = rows
          .where((tx) => tx.effectiveKind != 'unknown')
          .toList();
      final displayedRevision = controller.revision;
      return Scaffold(
        appBar: AppBar(title: const Text('待确认账单')),
        body: ContentWidth(
          child: rows.isEmpty
              ? const EmptyState(
                  icon: Icons.done_all,
                  title: '都核对好了',
                  description: '新采集的支付记录会出现在这里。',
                )
              : ListView(
                  padding: const EdgeInsets.only(bottom: 24),
                  children: [
                    Padding(
                      padding: const EdgeInsets.all(20),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Text(
                            '核对金额和用途后确认。消费和收入计入统计；转移、借还款单独记录。待分类转账需先选择用途，重复或无关记录可以忽略。',
                          ),
                          const SizedBox(height: 16),
                          OutlinedButton.icon(
                            onPressed: controller.isBusy || confirmable.isEmpty
                                ? null
                                : () async {
                                    final ok = await showDialog<bool>(
                                      context: context,
                                      builder: (context) => AlertDialog(
                                        title: Text(
                                          '确认这 ${confirmable.length} 笔已分类账单？',
                                        ),
                                        content: const Text(
                                          '请确保已核对金额、用途和重复记录。待分类转账不会批量确认。',
                                        ),
                                        actions: [
                                          TextButton(
                                            onPressed: () =>
                                                Navigator.pop(context, false),
                                            child: const Text('返回核对'),
                                          ),
                                          FilledButton(
                                            onPressed: () =>
                                                Navigator.pop(context, true),
                                            child: const Text('全部确认'),
                                          ),
                                        ],
                                      ),
                                    );
                                    if (ok == true && context.mounted) {
                                      await runUi(context, () {
                                        if (controller.revision !=
                                            displayedRevision) {
                                          throw const LedgerException(
                                            '账本已恢复，请重新核对账单。',
                                          );
                                        }
                                        return controller.confirmPending(
                                          confirmable.map((t) => t.id),
                                        );
                                      }, success: '账单已入账');
                                    }
                                  },
                            icon: const Icon(Icons.done_all),
                            label: Text(
                              confirmable.length == rows.length
                                  ? '全部确认'
                                  : '确认已分类的 ${confirmable.length} 笔',
                            ),
                          ),
                        ],
                      ),
                    ),
                    for (final tx in rows)
                      Column(
                        children: [
                          TransactionRow(
                            transaction: tx,
                            category: _category(controller, tx.categoryId),
                            onTap: () => Navigator.of(context).push(
                              MaterialPageRoute<void>(
                                builder: (_) => EntryScreen(
                                  controller: controller,
                                  transaction: tx,
                                ),
                              ),
                            ),
                          ),
                          Padding(
                            padding: const EdgeInsets.fromLTRB(20, 0, 20, 12),
                            child: Row(
                              mainAxisAlignment: MainAxisAlignment.end,
                              children: [
                                if (tx.duplicateOf != null)
                                  const Expanded(
                                    child: Text(
                                      '可能与已有账单重复',
                                      style: TextStyle(fontSize: 12),
                                    ),
                                  ),
                                TextButton(
                                  onPressed: controller.isBusy
                                      ? null
                                      : () => runUi(
                                          context,
                                          () => controller.command(
                                            'setTransactionStatus',
                                            arguments: {
                                              'id': tx.id,
                                              'status': 'IGNORED',
                                              'revision': displayedRevision,
                                            },
                                            ledger: true,
                                          ),
                                        ),
                                  child: const Text('忽略'),
                                ),
                                const SizedBox(width: 8),
                                FilledButton.tonal(
                                  onPressed: controller.isBusy
                                      ? null
                                      : tx.effectiveKind == 'unknown'
                                      ? () => Navigator.of(context).push(
                                          MaterialPageRoute<void>(
                                            builder: (_) => EntryScreen(
                                              controller: controller,
                                              transaction: tx,
                                            ),
                                          ),
                                        )
                                      : () => runUi(
                                          context,
                                          () => controller.command(
                                            'setTransactionStatus',
                                            arguments: {
                                              'id': tx.id,
                                              'status': 'CONFIRMED',
                                              'revision': displayedRevision,
                                            },
                                            ledger: true,
                                          ),
                                        ),
                                  child: Text(
                                    tx.effectiveKind == 'unknown'
                                        ? '选择用途并确认'
                                        : '确认入账',
                                  ),
                                ),
                              ],
                            ),
                          ),
                          const Divider(),
                        ],
                      ),
                  ],
                ),
        ),
      );
    },
  );
}

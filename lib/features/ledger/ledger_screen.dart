import 'package:flutter/material.dart';
import '../../core/ledger_controller.dart';
import '../../core/models.dart';
import '../../ui/common.dart';
import '../../ui/theme.dart';
import '../entry/entry_screen.dart';

class LedgerScreen extends StatelessWidget {
  const LedgerScreen({
    super.key,
    required this.controller,
    required this.onBudget,
  });
  final LedgerController controller;
  final VoidCallback onBudget;
  void _entry(BuildContext context, [LedgerTransaction? tx]) =>
      Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (_) => EntryScreen(controller: controller, transaction: tx),
        ),
      );
  @override
  Widget build(BuildContext context) {
    final c = controller, colors = Theme.of(context).colorScheme;
    final rows = c.filteredTransactions;
    final summary = c.monthSummary;
    return Scaffold(
      appBar: AppBar(
        title: const Text('账本'),
        actions: [
          IconButton(
            tooltip: '刷新账本',
            onPressed: c.isLoading ? null : () => c.refresh(),
            icon: const Icon(Icons.refresh),
          ),
          const SizedBox(width: 8),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _entry(context),
        icon: const Icon(Icons.add),
        label: const Text('记一笔'),
      ),
      body: ContentWidth(
        child: RefreshIndicator(
          onRefresh: c.refresh,
          child: CustomScrollView(
            slivers: [
              SliverPadding(
                padding: const EdgeInsets.fromLTRB(20, 0, 20, 0),
                sliver: SliverToBoxAdapter(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      MonthSelector(
                        month: c.selectedMonth,
                        onChanged: c.setMonth,
                      ),
                      const SizedBox(height: 12),
                      LayoutBuilder(
                        builder: (context, constraints) => Wrap(
                          spacing: 20,
                          runSpacing: 20,
                          children: [
                            _Total('支出', summary.expenseFen, colors.onSurface),
                            _Total('收入', summary.incomeFen, colors.primary),
                            _Total(
                              '结余',
                              summary.balanceFen,
                              colors.onSurfaceVariant,
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 24),
                      if (c.budgetFen > 0)
                        InkWell(
                          onTap: onBudget,
                          borderRadius: BorderRadius.circular(12),
                          child: Padding(
                            padding: const EdgeInsets.symmetric(vertical: 4),
                            child: Column(
                              children: [
                                SizedBox(
                                  width: double.infinity,
                                  child: Wrap(
                                    alignment: WrapAlignment.spaceBetween,
                                    spacing: 12,
                                    runSpacing: 8,
                                    children: [
                                      const Text('本月预算'),
                                      Text(
                                        c.budgetRemainingFen >= 0
                                            ? '还可用 ${money(c.budgetRemainingFen)}'
                                            : '已超出 ${money(-c.budgetRemainingFen)}',
                                        style: TextStyle(
                                          color: c.budgetRemainingFen >= 0
                                              ? colors.primary
                                              : colors.error,
                                          fontWeight: FontWeight.w600,
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                                const SizedBox(height: 10),
                                ClipRRect(
                                  borderRadius: BorderRadius.circular(3),
                                  child: LinearProgressIndicator(
                                    value: c.budgetProgress.clamp(0, 1),
                                    minHeight: 5,
                                    color: c.budgetRemainingFen < 0
                                        ? colors.error
                                        : colors.primary,
                                    backgroundColor:
                                        colors.surfaceContainerHighest,
                                  ),
                                ),
                                const SizedBox(height: 8),
                                Align(
                                  alignment: Alignment.centerLeft,
                                  child: Text(
                                    '预算 ${money(c.budgetFen)} · 已用 ${(c.budgetProgress * 100).round()}%',
                                    style: TextStyle(
                                      fontSize: 12,
                                      color: colors.onSurfaceVariant,
                                    ),
                                  ),
                                ),
                              ],
                            ),
                          ),
                        )
                      else
                        TextButton.icon(
                          onPressed: onBudget,
                          icon: const Icon(Icons.track_changes, size: 18),
                          label: const Text('设置月预算，让支出有个参考'),
                        ),
                      const SizedBox(height: 20),
                      if (c.pendingTransactions.isNotEmpty)
                        Padding(
                          padding: const EdgeInsets.only(bottom: 20),
                          child: Material(
                            color: colors.secondaryContainer.withValues(
                              alpha: .7,
                            ),
                            borderRadius: BorderRadius.circular(12),
                            child: ListTile(
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(12),
                              ),
                              leading: const Icon(Icons.inbox_outlined),
                              title: Text(
                                '${c.pendingTransactions.length} 笔账单等待确认',
                              ),
                              subtitle: const Text('自动采集的账单，核对后才计入收支'),
                              trailing: const Icon(Icons.chevron_right),
                              onTap: () => Navigator.of(context).push(
                                MaterialPageRoute<void>(
                                  builder: (_) => PendingScreen(controller: c),
                                ),
                              ),
                            ),
                          ),
                        ),
                      TextField(
                        onChanged: c.setKeyword,
                        decoration: const InputDecoration(
                          hintText: '搜索商户、备注或分类',
                          prefixIcon: Icon(Icons.search),
                          isDense: true,
                        ),
                      ),
                      const SizedBox(height: 12),
                      SingleChildScrollView(
                        scrollDirection: Axis.horizontal,
                        child: Row(
                          children: [
                            for (final type in <TxType?>[
                              null,
                              ...TxType.values,
                            ])
                              Padding(
                                padding: const EdgeInsets.only(right: 8),
                                child: ChoiceChip(
                                  label: Text(type?.label ?? '全部收支'),
                                  selected: c.typeFilter == type,
                                  onSelected: (_) => c.setTypeFilter(type),
                                ),
                              ),
                            const SizedBox(width: 8),
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
                      const SizedBox(height: 16),
                      if (c.error != null)
                        Text(
                          c.error.toString(),
                          style: TextStyle(color: colors.error),
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
                            onPressed: () => _entry(context),
                            icon: const Icon(Icons.add),
                            label: const Text('记下第一笔'),
                          )
                        : null,
                  ),
                ),
              SliverList(
                delegate: SliverChildBuilderDelegate((context, index) {
                  final tx = rows[index];
                  final newDay =
                      index == 0 ||
                      !DateUtils.isSameDay(
                        rows[index - 1].occurredAt,
                        tx.occurredAt,
                      );
                  return Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      if (newDay)
                        Padding(
                          padding: const EdgeInsets.fromLTRB(20, 20, 20, 8),
                          child: Text(
                            dateLabel(tx.occurredAt),
                            style: TextStyle(
                              color: colors.onSurfaceVariant,
                              fontSize: 13,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                      TransactionRow(
                        transaction: tx,
                        category: _category(c, tx.categoryId),
                        onTap: () => _entry(context, tx),
                      ),
                    ],
                  );
                }, childCount: rows.length),
              ),
              const SliverToBoxAdapter(child: SizedBox(height: 110)),
            ],
          ),
        ),
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
          fontSize: 13,
        ),
      ),
      const SizedBox(height: 8),
      Text(
        money(amount),
        style: TextStyle(
          color: color,
          fontWeight: FontWeight.w700,
          fontSize: 20,
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
    return ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 5),
      leading: CategoryMark(category: category),
      title: Text(
        tx.merchant?.isNotEmpty == true
            ? tx.merchant!
            : category?.name ?? '未分类',
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
      ),
      subtitle: Text(
        [
          category?.name ?? '未分类',
          if (tx.note?.isNotEmpty == true) tx.note!,
          if (tx.status != TxStatus.confirmed) tx.status.label,
        ].join(' · '),
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(fontSize: 12, color: colors.onSurfaceVariant),
      ),
      trailing: Text(
        '${tx.type == TxType.income ? '+' : '−'}${money(tx.amountFen)}',
        style: TextStyle(
          fontSize: 16,
          fontWeight: FontWeight.w600,
          color: tx.type == TxType.income ? colors.primary : colors.onSurface,
          fontFeatures: moneyFigures,
        ),
      ),
      onTap: onTap,
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
                          const Text('核对金额和分类，确认后计入收支。重复或无关的记录可以忽略。'),
                          const SizedBox(height: 16),
                          OutlinedButton.icon(
                            onPressed: controller.isBusy
                                ? null
                                : () async {
                                    final ok = await showDialog<bool>(
                                      context: context,
                                      builder: (context) => AlertDialog(
                                        title: Text('确认这 ${rows.length} 笔账单？'),
                                        content: const Text(
                                          '请确保已经核对金额、分类及重复记录。',
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
                                          rows.map((t) => t.id),
                                        );
                                      }, success: '账单已入账');
                                    }
                                  },
                            icon: const Icon(Icons.done_all),
                            label: const Text('全部确认'),
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
                                  child: const Text('确认入账'),
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

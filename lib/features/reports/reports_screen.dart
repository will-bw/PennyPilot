import 'dart:math' as math;
import 'package:flutter/material.dart';
import '../../core/ledger_controller.dart';
import '../../core/models.dart';
import '../../ui/common.dart';
import '../../ui/theme.dart';
import '../entry/entry_screen.dart';
import '../ledger/ledger_screen.dart';

class ReportsScreen extends StatelessWidget {
  const ReportsScreen({super.key, required this.controller});
  final LedgerController controller;

  @override
  Widget build(BuildContext context) {
    final c = controller, s = c.monthSummary, previous = c.previousMonthSummary;
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final month = c.selectedMonth;
    final monthKey = '${month.year}-${month.month.toString().padLeft(2, '0')}';
    final report = c.reports.where((r) => r.yearMonth == monthKey).firstOrNull;
    final now = DateTime.now();
    final completed = month.isBefore(DateTime(now.year, now.month));
    final categories = c.categorySpending;
    final generating = [
      'ENQUEUED',
      'RUNNING',
      'BLOCKED',
    ].contains(c.reportStates[monthKey]);
    return Scaffold(
      appBar: AppBar(title: const Text('收支统计')),
      body: ContentWidth(
        child: RefreshIndicator(
          onRefresh: c.refresh,
          child: ListView(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 32),
            children: [
              Align(
                alignment: Alignment.centerLeft,
                child: MonthSelector(month: month, onChanged: c.setMonth),
              ),
              const SizedBox(height: 16),
              SectionSurface(
                padding: const EdgeInsets.all(20),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _SummaryLine(
                      label: '已入账支出',
                      amount: s.expenseFen,
                      prominent: true,
                    ),
                    const SizedBox(height: 12),
                    Text(
                      '${s.count} 笔已确认账单${previous.expenseFen > 0 ? ' · 较上月${s.expenseFen >= previous.expenseFen ? '增加' : '减少'} ${((s.expenseFen - previous.expenseFen).abs() / previous.expenseFen * 100).toStringAsFixed(1)}%' : ''}',
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: colors.onSurfaceVariant,
                      ),
                    ),
                    const Padding(
                      padding: EdgeInsets.symmetric(vertical: 18),
                      child: Divider(),
                    ),
                    _SummaryLine(label: '已入账收入', amount: s.incomeFen),
                    const SizedBox(height: 12),
                    _SummaryLine(label: '收支结余', amount: s.balanceFen),
                  ],
                ),
              ),
              const SectionTitle('每日支出'),
              if (s.expenseFen == 0)
                const EmptyState(
                  icon: Icons.bar_chart_outlined,
                  title: '还没有可统计的支出',
                  description: '确认账单后，就能看到消费分布和变化。',
                )
              else
                _DailyChart(values: c.dailySpending, month: month),
              SectionTitle(
                '分类支出',
                trailing: Text(
                  '${categories.length} 个分类',
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: colors.onSurfaceVariant,
                  ),
                ),
              ),
              if (categories.isEmpty)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 12),
                  child: Text(
                    '本月还没有分类支出。',
                    style: TextStyle(color: colors.onSurfaceVariant),
                  ),
                ),
              for (var index = 0; index < categories.length; index++) ...[
                _CategorySpendingRow(
                  category: categories[index].category,
                  amount: categories[index].amountFen,
                  count: categories[index].count,
                  total: s.expenseFen,
                  onTap: () => Navigator.of(context).push(
                    MaterialPageRoute<void>(
                      builder: (_) => _CategoryDetail(
                        controller: c,
                        category: categories[index].category,
                        month: month,
                      ),
                    ),
                  ),
                ),
                if (index < categories.length - 1) const Divider(indent: 56),
              ],
              const SectionTitle('月度回顾'),
              SectionSurface(
                padding: const EdgeInsets.all(20),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    if (report != null) ...[
                      SelectableText(
                        report.llmSummary?.isNotEmpty == true
                            ? report.llmSummary!
                            : '本月支出 ${money(report.totalExpense)}，收入 ${money(report.totalIncome)}。',
                        style: const TextStyle(height: 1.7),
                      ),
                      const SizedBox(height: 12),
                      Text(
                        '生成于 ${dateLabel(report.generatedAt)} ${timeLabel(report.generatedAt)}',
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: colors.onSurfaceVariant,
                        ),
                      ),
                    ] else
                      Text(
                        completed
                            ? '根据本月账单，回顾一下这段时间的收支。'
                            : '本月仍在进行中。月末结束后，可以生成完整回顾。',
                        style: TextStyle(
                          color: colors.onSurfaceVariant,
                          height: 1.6,
                        ),
                      ),
                    if (completed) ...[
                      const SizedBox(height: 16),
                      Align(
                        alignment: Alignment.centerLeft,
                        child: OutlinedButton.icon(
                          onPressed: c.isBusy || generating
                              ? null
                              : () => runUi(
                                  context,
                                  () => c.generateReport(monthKey),
                                  success: '月报已加入生成队列',
                                ),
                          icon: const Icon(Icons.summarize_outlined, size: 18),
                          label: Text(
                            generating
                                ? '月报生成中…'
                                : report == null
                                ? '生成月度回顾'
                                : '重新生成回顾',
                          ),
                        ),
                      ),
                    ],
                    if (c.reportStates[monthKey] == 'FAILED')
                      Padding(
                        padding: const EdgeInsets.only(top: 12),
                        child: Text(
                          '生成失败，可检查 AI 配置后重试。',
                          style: TextStyle(color: colors.error),
                        ),
                      ),
                  ],
                ),
              ),
              const SizedBox(height: 20),
              Text(
                '统计仅包含已确认账单，待确认和已忽略记录不计入。预算为每月重复使用的总额度。',
                style: theme.textTheme.bodySmall?.copyWith(
                  height: 1.6,
                  color: colors.onSurfaceVariant,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _SummaryLine extends StatelessWidget {
  const _SummaryLine({
    required this.label,
    required this.amount,
    this.prominent = false,
  });
  final String label;
  final int amount;
  final bool prominent;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Row(
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        Expanded(
          child: Text(
            label,
            style: theme.textTheme.bodyMedium?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
        ),
        const SizedBox(width: 12),
        Flexible(
          child: Text(
            money(amount),
            textAlign: TextAlign.end,
            style:
                (prominent
                        ? theme.textTheme.headlineSmall
                        : theme.textTheme.titleMedium)
                    ?.copyWith(
                      fontFeatures: moneyFigures,
                      fontWeight: prominent ? FontWeight.w700 : FontWeight.w500,
                    ),
          ),
        ),
      ],
    );
  }
}

class _CategorySpendingRow extends StatelessWidget {
  const _CategorySpendingRow({
    required this.category,
    required this.amount,
    required this.count,
    required this.total,
    required this.onTap,
  });
  final LedgerCategory category;
  final int amount, count, total;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final fraction = total > 0 ? amount / total : 0.0;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(8),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 16),
        child: Row(
          children: [
            CategoryMark(category: category),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          category.name,
                          style: theme.textTheme.titleSmall,
                        ),
                      ),
                      Text(
                        money(amount),
                        style: theme.textTheme.titleSmall?.copyWith(
                          fontFeatures: moneyFigures,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 7),
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          '$count 笔',
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                      ),
                      Text(
                        '${(fraction * 100).toStringAsFixed(1)}%',
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                          fontFeatures: moneyFigures,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 9),
                  ClipRRect(
                    borderRadius: BorderRadius.circular(2),
                    child: LinearProgressIndicator(
                      value: fraction.clamp(0.0, 1.0),
                      minHeight: 3,
                      backgroundColor:
                          theme.colorScheme.surfaceContainerHighest,
                      color: theme.colorScheme.primary.withValues(alpha: .65),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 10),
            Icon(
              Icons.chevron_right,
              size: 18,
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ],
        ),
      ),
    );
  }
}

class _DailyChart extends StatefulWidget {
  const _DailyChart({required this.values, required this.month});
  final Map<int, int> values;
  final DateTime month;
  @override
  State<_DailyChart> createState() => _DailyChartState();
}

class _DailyChartState extends State<_DailyChart> {
  int? selected;
  @override
  void didUpdateWidget(covariant _DailyChart oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.month != widget.month) selected = null;
  }

  @override
  Widget build(BuildContext context) {
    final days = DateUtils.getDaysInMonth(
      widget.month.year,
      widget.month.month,
    );
    final maxValue = widget.values.values.fold<int>(0, math.max);
    final colors = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Semantics(
          liveRegion: true,
          child: Text(
            selected == null
                ? '点击图表查看每天的支出'
                : '${widget.month.month} 月 $selected 日 · ${money(widget.values[selected] ?? 0)}',
            style: TextStyle(
              fontSize: 13,
              color: selected == null
                  ? colors.onSurfaceVariant
                  : colors.onSurface,
            ),
          ),
        ),
        const SizedBox(height: 16),
        Text(
          '最高 ${money(maxValue)}',
          style: TextStyle(
            fontSize: 11,
            color: colors.onSurfaceVariant,
            fontFeatures: moneyFigures,
          ),
        ),
        const SizedBox(height: 8),
        Semantics(
          label: '每日支出柱状图，最高单日支出 ${money(maxValue)}',
          child: LayoutBuilder(
            builder: (context, constraints) => GestureDetector(
              onTapDown: (details) => setState(
                () => selected =
                    (details.localPosition.dx / constraints.maxWidth * days)
                        .floor()
                        .clamp(0, days - 1) +
                    1,
              ),
              child: CustomPaint(
                size: Size(constraints.maxWidth, 150),
                painter: _BarPainter(
                  values: widget.values,
                  days: days,
                  maxValue: maxValue,
                  color: colors.primary,
                  muted: colors.surfaceContainerHighest,
                  selected: selected,
                ),
              ),
            ),
          ),
        ),
        const SizedBox(height: 8),
        Row(
          children: [
            Text(
              '1日',
              style: TextStyle(fontSize: 12, color: colors.onSurfaceVariant),
            ),
            const Spacer(),
            Text(
              '${days ~/ 2}日',
              style: TextStyle(fontSize: 12, color: colors.onSurfaceVariant),
            ),
            const Spacer(),
            Text(
              '$days日',
              style: TextStyle(fontSize: 12, color: colors.onSurfaceVariant),
            ),
          ],
        ),
        ExpansionTile(
          tilePadding: EdgeInsets.zero,
          childrenPadding: const EdgeInsets.only(bottom: 8),
          shape: const Border(),
          collapsedShape: const Border(),
          title: const Text('查看每日明细', style: TextStyle(fontSize: 13)),
          children: [
            for (final day in widget.values.keys.toList()..sort())
              ListTile(
                dense: true,
                title: Text('${widget.month.month}月$day日'),
                trailing: Text(
                  money(widget.values[day]!),
                  style: const TextStyle(fontFeatures: moneyFigures),
                ),
              ),
          ],
        ),
      ],
    );
  }
}

class _BarPainter extends CustomPainter {
  _BarPainter({
    required this.values,
    required this.days,
    required this.maxValue,
    required this.color,
    required this.muted,
    this.selected,
  });
  final Map<int, int> values;
  final int days, maxValue;
  final Color color, muted;
  final int? selected;
  @override
  void paint(Canvas canvas, Size size) {
    final width = size.width / days;
    final paint = Paint();
    final guide = Paint()
      ..color = muted.withValues(alpha: .7)
      ..strokeWidth = 1;
    for (final fraction in [0.0, .5, 1.0]) {
      final y = 8 + (size.height - 8) * fraction;
      canvas.drawLine(Offset(0, y), Offset(size.width, y), guide);
    }
    for (var d = 1; d <= days; d++) {
      final height = maxValue == 0
          ? 0.0
          : (values[d] ?? 0) / maxValue * (size.height - 8);
      paint.color = selected == null || selected == d
          ? color
          : color.withValues(alpha: .3);
      if (height == 0) paint.color = muted;
      canvas.drawRRect(
        RRect.fromRectAndRadius(
          Rect.fromLTWH(
            (d - 1) * width + 2,
            size.height - math.max(2, height),
            math.max(1, width - 4),
            math.max(2, height),
          ),
          const Radius.circular(2),
        ),
        paint,
      );
    }
  }

  @override
  bool shouldRepaint(covariant _BarPainter old) =>
      old.values != values ||
      old.selected != selected ||
      old.color != color ||
      old.muted != muted ||
      old.maxValue != maxValue ||
      old.days != days;
}

class _CategoryDetail extends StatelessWidget {
  const _CategoryDetail({
    required this.controller,
    required this.category,
    required this.month,
  });
  final LedgerController controller;
  final LedgerCategory category;
  final DateTime month;
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: controller,
    builder: (context, _) {
      final rows =
          controller.transactions
              .where(
                (t) =>
                    t.categoryId == category.id &&
                    t.status == TxStatus.confirmed &&
                    t.occurredAt.year == month.year &&
                    t.occurredAt.month == month.month,
              )
              .toList()
            ..sort((a, b) => b.occurredAt.compareTo(a.occurredAt));
      return Scaffold(
        appBar: AppBar(title: Text('${category.name} · ${month.month}月')),
        body: ContentWidth(
          child: ListView(
            children: [
              for (final tx in rows)
                TransactionRow(
                  transaction: tx,
                  category: category,
                  onTap: () => Navigator.of(context).push(
                    MaterialPageRoute<void>(
                      builder: (_) =>
                          EntryScreen(controller: controller, transaction: tx),
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

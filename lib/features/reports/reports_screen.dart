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
    final colors = Theme.of(context).colorScheme;
    final month = c.selectedMonth;
    final monthKey = '${month.year}-${month.month.toString().padLeft(2, '0')}';
    final report = c.reports.where((r) => r.yearMonth == monthKey).firstOrNull;
    final now = DateTime.now();
    final completed = month.isBefore(DateTime(now.year, now.month));
    final categories = c.categorySpending;
    return Scaffold(
      appBar: AppBar(title: const Text('收支统计')),
      body: ContentWidth(
        child: RefreshIndicator(
          onRefresh: c.refresh,
          child: ListView(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 32),
            children: [
              MonthSelector(month: month, onChanged: c.setMonth),
              const SizedBox(height: 20),
              Text(
                '这个月的钱，花在哪了？',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 20),
              Wrap(
                spacing: 32,
                runSpacing: 20,
                children: [
                  _Metric('已入账支出', s.expenseFen),
                  _Metric('已入账收入', s.incomeFen),
                  _Metric('收支结余', s.balanceFen),
                ],
              ),
              const SizedBox(height: 16),
              Text(
                '${s.count} 笔已确认账单${previous.expenseFen > 0 ? ' · 较上月支出${s.expenseFen >= previous.expenseFen ? '增加' : '减少'} ${((s.expenseFen - previous.expenseFen).abs() / previous.expenseFen * 100).toStringAsFixed(1)}%' : ''}',
                style: TextStyle(color: colors.onSurfaceVariant, fontSize: 13),
              ),
              const SizedBox(height: 24),
              const Divider(),
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
                  style: TextStyle(
                    color: colors.onSurfaceVariant,
                    fontSize: 13,
                  ),
                ),
              ),
              for (final item in categories)
                InkWell(
                  onTap: () => Navigator.of(context).push(
                    MaterialPageRoute<void>(
                      builder: (_) => _CategoryDetail(
                        controller: c,
                        category: item.category,
                        month: month,
                      ),
                    ),
                  ),
                  borderRadius: BorderRadius.circular(8),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 12),
                    child: Row(
                      children: [
                        CategoryMark(category: item.category),
                        const SizedBox(width: 14),
                        Expanded(
                          child: Column(
                            children: [
                              Row(
                                children: [
                                  Expanded(
                                    child: Text(
                                      item.category.name,
                                      style: const TextStyle(
                                        fontWeight: FontWeight.w600,
                                      ),
                                    ),
                                  ),
                                  Text(
                                    money(item.amountFen),
                                    style: const TextStyle(
                                      fontWeight: FontWeight.w600,
                                      fontFeatures: moneyFigures,
                                    ),
                                  ),
                                ],
                              ),
                              const SizedBox(height: 8),
                              ClipRRect(
                                borderRadius: BorderRadius.circular(2),
                                child: LinearProgressIndicator(
                                  value: s.expenseFen > 0
                                      ? item.amountFen / s.expenseFen
                                      : 0,
                                  minHeight: 4,
                                  backgroundColor:
                                      colors.surfaceContainerHighest,
                                  color: colors.primary,
                                ),
                              ),
                              const SizedBox(height: 6),
                              Row(
                                children: [
                                  Text(
                                    '${item.count} 笔',
                                    style: TextStyle(
                                      fontSize: 12,
                                      color: colors.onSurfaceVariant,
                                    ),
                                  ),
                                  const Spacer(),
                                  Text(
                                    '${(item.amountFen / s.expenseFen * 100).toStringAsFixed(1)}%',
                                    style: TextStyle(
                                      fontSize: 12,
                                      color: colors.onSurfaceVariant,
                                    ),
                                  ),
                                ],
                              ),
                            ],
                          ),
                        ),
                        const SizedBox(width: 8),
                        const Icon(Icons.chevron_right, size: 18),
                      ],
                    ),
                  ),
                ),
              const SectionTitle('月度回顾'),
              if (report != null) ...[
                SelectableText(
                  report.llmSummary?.isNotEmpty == true
                      ? report.llmSummary!
                      : '本月支出 ${money(report.totalExpense)}，收入 ${money(report.totalIncome)}。',
                  style: const TextStyle(height: 1.7),
                ),
                const SizedBox(height: 8),
                Text(
                  '生成于 ${dateLabel(report.generatedAt)} ${timeLabel(report.generatedAt)}',
                  style: TextStyle(
                    fontSize: 12,
                    color: colors.onSurfaceVariant,
                  ),
                ),
              ] else
                Text(
                  completed
                      ? '生成一份月度回顾，结合真实账单看看消费习惯。'
                      : '本月仍在进行中。月末结束后，可以生成完整回顾。',
                  style: TextStyle(color: colors.onSurfaceVariant),
                ),
              if (completed) ...[
                const SizedBox(height: 16),
                OutlinedButton.icon(
                  onPressed:
                      c.isBusy ||
                          [
                            'ENQUEUED',
                            'RUNNING',
                            'BLOCKED',
                          ].contains(c.reportStates[monthKey])
                      ? null
                      : () => runUi(
                          context,
                          () => c.generateReport(monthKey),
                          success: '月报已加入生成队列',
                        ),
                  icon: const Icon(Icons.summarize_outlined),
                  label: Text(
                    [
                          'ENQUEUED',
                          'RUNNING',
                          'BLOCKED',
                        ].contains(c.reportStates[monthKey])
                        ? '月报生成中…'
                        : report == null
                        ? '生成月度回顾'
                        : '重新生成回顾',
                  ),
                ),
              ],
              if (c.reportStates[monthKey] == 'FAILED')
                Text(
                  '生成失败，可检查 AI 配置后重试。',
                  style: TextStyle(color: colors.error),
                ),
              const SizedBox(height: 24),
              Text(
                '统计仅包含已确认账单，待确认和已忽略记录不计入。预算为每月重复使用的总额度。',
                style: TextStyle(
                  fontSize: 12,
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

class _Metric extends StatelessWidget {
  const _Metric(this.label, this.amount);
  final String label;
  final int amount;
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(
        label,
        style: TextStyle(
          fontSize: 13,
          color: Theme.of(context).colorScheme.onSurfaceVariant,
        ),
      ),
      const SizedBox(height: 8),
      Text(
        money(amount),
        style: Theme.of(context).textTheme.headlineSmall?.copyWith(
          fontFeatures: moneyFigures,
          fontWeight: FontWeight.w600,
        ),
      ),
    ],
  );
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
        Text(
          selected == null
              ? '点击图表查看每天的支出'
              : '${widget.month.month} 月 $selected 日 · ${money(widget.values[selected] ?? 0)}',
          style: TextStyle(fontSize: 13, color: colors.onSurfaceVariant),
        ),
        const SizedBox(height: 16),
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
          title: const Text('查看每日明细', style: TextStyle(fontSize: 13)),
          children: [
            for (final day in widget.values.keys.toList()..sort())
              ListTile(
                dense: true,
                title: Text('${widget.month.month}月$day日'),
                trailing: Text(money(widget.values[day]!)),
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

import 'package:flutter/material.dart';
import '../core/models.dart';
import 'theme.dart';

String money(int cents, {bool symbol = true}) {
  final absolute = cents.abs();
  final whole = (absolute ~/ 100).toString().replaceAllMapped(
    RegExp(r'(\d)(?=(\d{3})+(?!\d))'),
    (m) => '${m[1]},',
  );
  return '${cents < 0 ? '−' : ''}${symbol ? '¥' : ''}$whole.${(absolute % 100).toString().padLeft(2, '0')}';
}

String monthLabel(DateTime date) => '${date.year} 年 ${date.month} 月';
String dateLabel(DateTime date) => '${date.month}月${date.day}日';
String timeLabel(DateTime date) =>
    '${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}';

Future<bool> runUi(
  BuildContext context,
  Future<dynamic> Function() action, {
  String? success,
}) async {
  try {
    await action();
    if (context.mounted && success != null) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(success)));
    }
    return true;
  } catch (error) {
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(error.toString().replaceFirst('Exception: ', '')),
        ),
      );
    }
    return false;
  }
}

class ContentWidth extends StatelessWidget {
  const ContentWidth({super.key, required this.child});
  final Widget child;
  @override
  Widget build(BuildContext context) => Align(
    alignment: Alignment.topCenter,
    child: ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 1040),
      child: child,
    ),
  );
}

class EmptyState extends StatelessWidget {
  const EmptyState({
    super.key,
    required this.icon,
    required this.title,
    required this.description,
    this.action,
  });
  final IconData icon;
  final String title, description;
  final Widget? action;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 48),
    child: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 40, color: Theme.of(context).colorScheme.primary),
        const SizedBox(height: 20),
        Text(
          title,
          style: Theme.of(context).textTheme.titleLarge,
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: 8),
        Text(
          description,
          textAlign: TextAlign.center,
          style: TextStyle(
            color: Theme.of(context).colorScheme.onSurfaceVariant,
            height: 1.6,
          ),
        ),
        if (action != null) ...[const SizedBox(height: 24), action!],
      ],
    ),
  );
}

class SectionTitle extends StatelessWidget {
  const SectionTitle(this.title, {super.key, this.trailing});
  final String title;
  final Widget? trailing;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(top: 24, bottom: 12),
    child: Row(
      children: [
        Expanded(
          child: Text(title, style: Theme.of(context).textTheme.titleMedium),
        ),
        ?trailing,
      ],
    ),
  );
}

IconData categoryIcon(String name) {
  final value = name.toLowerCase();
  if (RegExp(r'餐|食|food|restaurant').hasMatch(value)) {
    return Icons.restaurant_outlined;
  }
  if (RegExp(r'交|车|transport|bus').hasMatch(value)) {
    return Icons.directions_bus_outlined;
  }
  if (RegExp(r'购|shop|bag').hasMatch(value)) return Icons.shopping_bag_outlined;
  if (RegExp(r'住|房|home|rent').hasMatch(value)) return Icons.home_outlined;
  if (RegExp(r'娱|game|movie').hasMatch(value)) {
    return Icons.sports_esports_outlined;
  }
  if (RegExp(r'医|health|medical').hasMatch(value)) {
    return Icons.health_and_safety_outlined;
  }
  if (RegExp(r'学|书|book|school').hasMatch(value)) {
    return Icons.menu_book_outlined;
  }
  if (RegExp(r'工|薪|salary|work').hasMatch(value)) return Icons.work_outline;
  if (RegExp(r'礼|gift').hasMatch(value)) return Icons.card_giftcard_outlined;
  if (RegExp(r'咖|coffee').hasMatch(value)) return Icons.local_cafe_outlined;
  return Icons.receipt_long_outlined;
}

class CategoryMark extends StatelessWidget {
  const CategoryMark({super.key, this.category});
  final LedgerCategory? category;
  @override
  Widget build(BuildContext context) {
    final c = Theme.of(context).colorScheme;
    return Container(
      width: 42,
      height: 42,
      decoration: BoxDecoration(
        color: c.surfaceContainerHighest.withValues(alpha: .6),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Icon(
        categoryIcon(category?.name ?? ''),
        size: 21,
        color: c.onSurfaceVariant,
      ),
    );
  }
}

class AmountLabel extends StatelessWidget {
  const AmountLabel(this.cents, {super.key, this.style, this.prefix = ''});
  final int cents;
  final TextStyle? style;
  final String prefix;
  @override
  Widget build(BuildContext context) => Text(
    '$prefix${money(cents)}',
    style: (style ?? Theme.of(context).textTheme.titleMedium)?.copyWith(
      fontFeatures: moneyFigures,
    ),
  );
}

class MonthSelector extends StatelessWidget {
  const MonthSelector({
    super.key,
    required this.month,
    required this.onChanged,
  });
  final DateTime month;
  final ValueChanged<DateTime> onChanged;
  @override
  Widget build(BuildContext context) => Row(
    mainAxisSize: MainAxisSize.min,
    children: [
      IconButton(
        tooltip: '上个月',
        onPressed: () => onChanged(DateTime(month.year, month.month - 1)),
        icon: const Icon(Icons.chevron_left),
      ),
      Flexible(
        child: TextButton(
          onPressed: () async {
            final chosen = await showDatePicker(
              context: context,
              initialDate: month,
              firstDate: DateTime(2000),
              lastDate: DateTime(2100),
              helpText: '选择要查看的月份',
            );
            if (chosen != null) onChanged(DateTime(chosen.year, chosen.month));
          },
          child: Text(monthLabel(month)),
        ),
      ),
      IconButton(
        tooltip: '下个月',
        onPressed: () => onChanged(DateTime(month.year, month.month + 1)),
        icon: const Icon(Icons.chevron_right),
      ),
    ],
  );
}

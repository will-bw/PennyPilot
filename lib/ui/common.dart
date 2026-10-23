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

class PageHeading extends StatelessWidget {
  const PageHeading({
    super.key,
    required this.title,
    this.subtitle,
    this.trailing,
  });
  final String title;
  final String? subtitle;
  final Widget? trailing;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(top: 8, bottom: 24),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: Theme.of(context).textTheme.headlineMedium),
              if (subtitle != null) ...[
                const SizedBox(height: 8),
                Text(
                  subtitle!,
                  style: TextStyle(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ],
          ),
        ),
        ?trailing,
      ],
    ),
  );
}

class SectionSurface extends StatelessWidget {
  const SectionSurface({
    super.key,
    required this.child,
    this.padding = const EdgeInsets.all(16),
  });
  final Widget child;
  final EdgeInsetsGeometry padding;
  @override
  Widget build(BuildContext context) => Material(
    color: Theme.of(context).colorScheme.surfaceContainerLow,
    borderRadius: BorderRadius.circular(14),
    clipBehavior: Clip.antiAlias,
    child: SizedBox(
      width: double.infinity,
      child: Padding(padding: padding, child: child),
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
        Icon(
          icon,
          size: 32,
          color: Theme.of(context).colorScheme.onSurfaceVariant,
        ),
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
    final color = categoryTone(context, category?.name ?? '');
    return Container(
      width: 40,
      height: 40,
      decoration: BoxDecoration(
        color: Color.alphaBlend(color.withValues(alpha: .12), c.surface),
        shape: BoxShape.circle,
      ),
      child: Icon(ledgerCategoryIcon(category), size: 20, color: color),
    );
  }
}

const categorySymbols = <String, (String, IconData)>{
  'dining': ('餐饮', Icons.restaurant_outlined),
  'shopping': ('购物', Icons.shopping_bag_outlined),
  'transport': ('交通', Icons.directions_bus_outlined),
  'home': ('居家', Icons.home_outlined),
  'health': ('健康', Icons.health_and_safety_outlined),
  'entertainment': ('娱乐', Icons.movie_outlined),
  'education': ('学习', Icons.school_outlined),
  'salary': ('收入', Icons.payments_outlined),
  'gift': ('礼物', Icons.redeem_outlined),
  'other': ('其他', Icons.category_outlined),
};

IconData ledgerCategoryIcon(LedgerCategory? category) =>
    categorySymbols[category?.icon]?.$2 ?? categoryIcon(category?.name ?? '');

Color categoryTone(BuildContext context, String name) {
  final dark = Theme.of(context).brightness == Brightness.dark;
  final index = switch (categoryIcon(name)) {
    Icons.restaurant_outlined || Icons.local_cafe_outlined => 0,
    Icons.directions_bus_outlined => 1,
    Icons.shopping_bag_outlined => 2,
    Icons.home_outlined => 3,
    Icons.work_outline => 4,
    _ => 5,
  };
  return (dark
      ? const [
          Color(0xFFDEB293),
          Color(0xFFA8C4DA),
          Color(0xFFD4AFC0),
          Color(0xFFB6C7A5),
          Color(0xFFA4CDB9),
          Color(0xFFBDB8D6),
        ]
      : const [
          Color(0xFF946644),
          Color(0xFF527B9A),
          Color(0xFF9A647B),
          Color(0xFF6E8058),
          Color(0xFF517D65),
          Color(0xFF7C7299),
        ])[index];
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
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final compact =
          constraints.maxWidth < 300 &&
          MediaQuery.textScalerOf(context).scale(14) > 18;
      return Row(
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
                if (chosen != null) {
                  onChanged(DateTime(chosen.year, chosen.month));
                }
              },
              child: Text(
                compact
                    ? '${month.year}.${month.month.toString().padLeft(2, '0')}'
                    : monthLabel(month),
                semanticsLabel: monthLabel(month),
              ),
            ),
          ),
          IconButton(
            tooltip: '下个月',
            onPressed: () => onChanged(DateTime(month.year, month.month + 1)),
            icon: const Icon(Icons.chevron_right),
          ),
        ],
      );
    },
  );
}

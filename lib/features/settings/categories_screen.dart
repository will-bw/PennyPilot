import 'package:flutter/material.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/core/models.dart';

import 'settings_widgets.dart';

const _categoryIcons = <String, (String, IconData)>{
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

IconData _iconForCategory(LedgerCategory category) {
  if (_categoryIcons.containsKey(category.icon)) {
    return _categoryIcons[category.icon]!.$2;
  }
  final match = _categoryIcons.values.where(
    (entry) => category.name.contains(entry.$1),
  );
  return match.isEmpty ? Icons.category_outlined : match.first.$2;
}

class CategoriesScreen extends StatefulWidget {
  const CategoriesScreen({super.key, required this.controller});
  final LedgerController controller;
  @override
  State<CategoriesScreen> createState() => _CategoriesScreenState();
}

class _CategoriesScreenState extends State<CategoriesScreen> {
  TxType _type = TxType.expense;
  int? _busyId;
  String? _error;

  Future<void> _edit([LedgerCategory? category]) => Navigator.push<void>(
    context,
    MaterialPageRoute(
      builder: (_) => _CategoryEditor(
        controller: widget.controller,
        category: category,
        type: _type,
      ),
    ),
  );

  Future<void> _toggle(LedgerCategory category, bool enabled) async {
    setState(() {
      _busyId = category.id;
      _error = null;
    });
    try {
      await widget.controller.saveCategory(category.copyWith(enabled: enabled));
      if (mounted) {
        settingsFeedback(context, enabled ? '分类已启用' : '分类已停用，已有账单保留');
      }
    } catch (error) {
      if (mounted) setState(() => _error = settingsError(error));
    } finally {
      if (mounted) setState(() => _busyId = null);
    }
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: widget.controller,
    builder: (context, _) {
      final categories =
          widget.controller.categories
              .where((category) => category.type == _type)
              .toList()
            ..sort((a, b) => a.sortOrder.compareTo(b.sortOrder));
      return Scaffold(
        appBar: AppBar(title: const Text('分类管理')),
        floatingActionButton: FloatingActionButton.extended(
          onPressed: () => _edit(),
          icon: const Icon(Icons.add),
          label: const Text('新增分类'),
        ),
        body: SafeArea(
          top: false,
          child: ListView(
            padding: const EdgeInsets.only(bottom: 100),
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 16, 24, 12),
                child: SegmentedButton<TxType>(
                  segments: const [
                    ButtonSegment(value: TxType.expense, label: Text('支出')),
                    ButtonSegment(value: TxType.income, label: Text('收入')),
                  ],
                  selected: {_type},
                  onSelectionChanged: (value) =>
                      setState(() => _type = value.first),
                ),
              ),
              const SettingsNotice('停用分类后，已有账单仍保留。预设分类可改名和停用，不能删除。'),
              if (_error != null) SettingsNotice(_error!, error: true),
              if (categories.isEmpty)
                const Padding(
                  padding: EdgeInsets.all(32),
                  child: Text('暂无此类型分类，点击“新增分类”创建。'),
                ),
              for (final category in categories)
                ListTile(
                  contentPadding: const EdgeInsets.symmetric(
                    horizontal: 24,
                    vertical: 6,
                  ),
                  leading: Icon(
                    _iconForCategory(category),
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                  title: Text(category.name),
                  subtitle: Text(
                    '${category.isPreset ? '预设分类' : '自定义分类'}${category.enabled ? '' : ' · 已停用'}',
                  ),
                  onTap: _busyId == null ? () => _edit(category) : null,
                  trailing: _busyId == category.id
                      ? const SettingsBusy()
                      : Semantics(
                          label:
                              '${category.enabled ? '停用' : '启用'}${category.name}',
                          child: Switch(
                            value: category.enabled,
                            onChanged: _busyId == null
                                ? (value) => _toggle(category, value)
                                : null,
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

class _CategoryEditor extends StatefulWidget {
  const _CategoryEditor({
    required this.controller,
    this.category,
    required this.type,
  });
  final LedgerController controller;
  final LedgerCategory? category;
  final TxType type;
  @override
  State<_CategoryEditor> createState() => _CategoryEditorState();
}

class _CategoryEditorState extends State<_CategoryEditor> {
  late final TextEditingController _name;
  late String _icon;
  late TxType _type;
  late bool _enabled;
  late int _revision;
  bool _busy = false;
  bool _dirty = false;
  bool _canPop = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _name = TextEditingController(text: widget.category?.name ?? '');
    _icon = widget.category?.icon ?? 'other';
    _type = widget.category?.type ?? widget.type;
    _enabled = widget.category?.enabled ?? true;
    _revision = widget.controller.snapshot?.revision ?? -1;
    _name.addListener(() {
      if (!_dirty && mounted) setState(() => _dirty = true);
    });
  }

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  Future<void> _leave() async {
    if (_busy) return;
    if (_dirty &&
        !await confirmSettingsAction(
          context,
          title: '放弃未保存的修改？',
          message: '分类修改尚未保存，返回后需要重新填写。',
          confirmLabel: '放弃修改',
        )) {
      return;
    }
    if (mounted) {
      setState(() => _canPop = true);
      Navigator.pop(context);
    }
  }

  Future<void> _save() async {
    final name = _name.text.trim();
    if (name.isEmpty || name.characters.length > 20) {
      setState(() => _error = '请填写 1 至 20 个字的分类名称。');
      return;
    }
    if (widget.controller.categories.any(
      (item) =>
          item.id != widget.category?.id &&
          item.name == name &&
          item.type == _type,
    )) {
      setState(() => _error = '此类型已有同名分类，请换一个名称。');
      return;
    }
    final category =
        widget.category?.copyWith(name: name, icon: _icon, enabled: _enabled) ??
        LedgerCategory(
          name: name,
          icon: _icon,
          type: _type,
          enabled: _enabled,
          sortOrder:
              widget.controller.categories
                  .where((item) => item.type == _type)
                  .fold<int>(
                    -1,
                    (value, item) =>
                        value > item.sortOrder ? value : item.sortOrder,
                  ) +
              1,
        );
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await widget.controller.command(
        'saveCategory',
        arguments: {...category.toMap(), 'revision': _revision},
        ledger: true,
      );
      if (mounted) {
        settingsFeedback(context, '分类已保存');
        setState(() => _canPop = true);
        Navigator.pop(context);
      }
    } catch (error) {
      if (mounted) setState(() => _error = settingsError(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _delete() async {
    final category = widget.category!;
    if (!await confirmSettingsAction(
      context,
      title: '删除“${category.name}”？',
      message: '此分类下的账单会移至同类型的“其他”分类。删除后无法撤销。',
      confirmLabel: '删除分类',
      destructive: true,
    )) {
      return;
    }
    if (!mounted) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await widget.controller.command(
        'deleteCategory',
        arguments: {'id': category.id, 'revision': _revision},
        ledger: true,
      );
      if (mounted) {
        settingsFeedback(context, '分类已删除，账单已移至“其他”');
        setState(() => _canPop = true);
        Navigator.pop(context);
      }
    } catch (error) {
      if (mounted) setState(() => _error = settingsError(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: _canPop || (!_dirty && !_busy),
    onPopInvokedWithResult: (didPop, result) {
      if (!didPop) _leave();
    },
    child: Scaffold(
      appBar: AppBar(
        title: Text(widget.category == null ? '新增分类' : '编辑分类'),
        actions: [
          TextButton(
            onPressed: _busy ? null : _save,
            child: _busy ? const SettingsBusy() : const Text('保存'),
          ),
        ],
      ),
      body: SafeArea(
        top: false,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(24, 20, 24, 32),
          children: [
            TextField(
              controller: _name,
              enabled: !_busy,
              maxLength: 20,
              textInputAction: TextInputAction.done,
              decoration: const InputDecoration(
                labelText: '分类名称',
                hintText: '例如：日常餐饮',
              ),
            ),
            const SizedBox(height: 16),
            if (widget.category == null)
              SegmentedButton<TxType>(
                segments: const [
                  ButtonSegment(value: TxType.expense, label: Text('支出')),
                  ButtonSegment(value: TxType.income, label: Text('收入')),
                ],
                selected: {_type},
                onSelectionChanged: _busy
                    ? null
                    : (value) => setState(() {
                        _type = value.first;
                        _dirty = true;
                      }),
              )
            else
              Text(
                '类型：${_type.label}（已有分类不能更改类型）',
                style: Theme.of(context).textTheme.bodyMedium,
              ),
            const SizedBox(height: 28),
            Text('图标', style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final entry in _categoryIcons.entries)
                  ChoiceChip(
                    avatar: Icon(entry.value.$2, size: 18),
                    label: Text(entry.value.$1),
                    selected: _icon == entry.key,
                    onSelected: _busy
                        ? null
                        : (_) => setState(() {
                            _icon = entry.key;
                            _dirty = true;
                          }),
                  ),
              ],
            ),
            const SizedBox(height: 24),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('启用分类'),
              subtitle: const Text('停用后不再出现在新账单的分类选择中'),
              value: _enabled,
              onChanged: _busy
                  ? null
                  : (value) => setState(() {
                      _enabled = value;
                      _dirty = true;
                    }),
            ),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(top: 16),
                child: Text(
                  _error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ),
            if (widget.category != null && !widget.category!.isPreset) ...[
              const SizedBox(height: 32),
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  onPressed: _busy ? null : _delete,
                  style: TextButton.styleFrom(
                    foregroundColor: Theme.of(context).colorScheme.error,
                  ),
                  icon: const Icon(Icons.delete_outline),
                  label: const Text('删除分类'),
                ),
              ),
            ],
          ],
        ),
      ),
    ),
  );
}

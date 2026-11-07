import 'dart:convert';

import 'package:flutter/material.dart';

import '../../core/agent/agent_runtime.dart';
import '../../core/ledger_controller.dart';
import '../../core/models.dart';
import '../../ui/common.dart';
import '../entry/entry_screen.dart';
import 'assistant_reasoning.dart';

/// A run's operations stay beside the answer they produced.
class AgentActivity extends StatelessWidget {
  const AgentActivity({
    super.key,
    required this.controller,
    required this.parts,
    this.active = false,
    this.status = 'complete',
    this.onDetailsExpanded,
  });

  final LedgerController controller;
  final List<Map<String, dynamic>> parts;
  final bool active;
  final String status;
  final VoidCallback? onDetailsExpanded;

  @override
  Widget build(BuildContext context) {
    final tools = parts.where((part) => part['type'] == 'tool').toList();
    final actions = parts.where((part) => part['type'] == 'action');
    final receipts = parts.where((part) => part['type'] == 'receipt');
    final reasoning = parts
        .where((part) => part['type'] == 'reasoning')
        .toList();
    final plan = active
        ? controller.agentProgress['plan'] as List? ?? []
        : const [];
    final colors = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (active || reasoning.isNotEmpty || tools.isNotEmpty)
          _process(context, reasoning: reasoning, tools: tools, plan: plan),
        for (final action in actions)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: _ActionCard(controller: controller, action: action),
          ),
        for (final receipt in receipts)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(
                  receipt['status'] == 'undone'
                      ? Icons.undo
                      : Icons.check_circle_outline,
                  size: 20,
                  color: colors.primary,
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(receipt['title'] as String? ?? '账本已更新'),
                      if (receipt['text'] case final String text)
                        Text(
                          text,
                          style: TextStyle(color: colors.onSurfaceVariant),
                        ),
                      if ((receipt['transactions'] as List? ?? []).isNotEmpty)
                        _ReceiptTransactions(
                          controller: controller,
                          receipt: receipt,
                        ),
                    ],
                  ),
                ),
              ],
            ),
          ),
      ],
    );
  }

  Widget _process(
    BuildContext context, {
    required List<Map<String, dynamic>> reasoning,
    required List<Map<String, dynamic>> tools,
    required List<dynamic> plan,
  }) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final reduceMotion = MediaQuery.disableAnimationsOf(context);
    final (outcome, outcomeIcon) = switch (status) {
      'cancelled' => ('已停止', Icons.stop_circle_outlined),
      'interrupted' => ('已中断', Icons.pause_circle_outline),
      'failed' || 'error' => ('执行未完成', Icons.error_outline),
      _ when reasoning.any((part) => part['status'] == 'cancelled') => (
        '已停止',
        Icons.stop_circle_outlined,
      ),
      _ => (null, Icons.check_circle_outline),
    };
    final duration = active ? null : assistantReasoningDuration(reasoning);
    final label = active
        ? _liveLabel(reasoning)
        : outcome ??
              [
                if (reasoning.isNotEmpty)
                  if (duration == null)
                    '思考过程'
                  else
                    '${tools.isEmpty ? '已思考' : '思考'} $duration',
                if (tools.isNotEmpty)
                  '${tools.length} 次${reasoning.isEmpty ? '工具' : ''}调用',
              ].join(' · ');
    final title = Semantics(
      liveRegion: active,
      child: Text(
        label,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: theme.textTheme.bodyMedium?.copyWith(
          color: colors.onSurfaceVariant,
        ),
      ),
    );
    final leading = SizedBox.square(
      dimension: 18,
      child: active && !reduceMotion
          ? Padding(
              padding: const EdgeInsets.all(2),
              child: CircularProgressIndicator(
                strokeWidth: 2,
                color: colors.primary,
              ),
            )
          : Icon(
              active ? Icons.pending_outlined : outcomeIcon,
              size: 18,
              color: colors.onSurfaceVariant,
            ),
    );
    final details = <Widget>[
      if (reasoning.isNotEmpty)
        AssistantReasoning(parts: reasoning, active: active),
      if (plan.isNotEmpty) ...[
        const SizedBox(height: 8),
        Text('执行计划', style: theme.textTheme.labelMedium),
        for (final item in plan)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(
                  switch (item['status']) {
                    'completed' => Icons.check_circle_outline,
                    'in_progress' => Icons.play_circle_outline,
                    _ => Icons.radio_button_unchecked,
                  },
                  size: 18,
                  color: colors.onSurfaceVariant,
                ),
                const SizedBox(width: 8),
                Expanded(child: Text(item['title'] as String)),
              ],
            ),
          ),
      ],
      if (tools.isNotEmpty) ...[
        if (reasoning.isNotEmpty || plan.isNotEmpty) const SizedBox(height: 8),
        Text('执行记录', style: theme.textTheme.labelMedium),
        for (final tool in tools) _toolRow(context, tool),
      ],
    ];
    return ListTileTheme.merge(
      minLeadingWidth: 18,
      horizontalTitleGap: 8,
      child: details.isEmpty
          ? ListTile(
              contentPadding: EdgeInsets.zero,
              minTileHeight: 48,
              leading: leading,
              title: title,
            )
          : ExpansionTile(
              tilePadding: EdgeInsets.zero,
              minTileHeight: 48,
              leading: leading,
              title: title,
              shape: const Border(),
              collapsedShape: const Border(),
              expandedCrossAxisAlignment: CrossAxisAlignment.stretch,
              childrenPadding: const EdgeInsets.only(bottom: 12),
              expansionAnimationStyle: reduceMotion
                  ? AnimationStyle.noAnimation
                  : const AnimationStyle(
                      duration: Duration(milliseconds: 180),
                      curve: Curves.easeOutCubic,
                    ),
              onExpansionChanged: (expanded) {
                if (expanded) onDetailsExpanded?.call();
              },
              children: details,
            ),
    );
  }

  String _liveLabel(List<Map<String, dynamic>> reasoning) {
    final progress = controller.agentProgress;
    return switch (progress['phase']) {
      'tool' => LedgerAgentRuntime.toolLabel(progress['tool'] as String? ?? ''),
      'tool_result' => '正在整理执行结果…',
      'text_delta' => '正在回复…',
      'text_reset' => '正在核对账本依据…',
      'text_complete' => '正在整理回复…',
      _ when reasoning.any((part) => part['status'] == 'streaming') => '正在思考…',
      'model' || 'text_start' => progress['text'] as String? ?? '正在理解你的问题…',
      _ => '正在理解你的问题…',
    };
  }

  Widget _toolRow(BuildContext context, Map<String, dynamic> tool) {
    final colors = Theme.of(context).colorScheme;
    final status = tool['status'];
    final description = [
      if (tool['step'] != null) '第 ${tool['step']} 步',
      if (tool['evidenceId'] != null) '依据 ${tool['evidenceId']}',
      if (status == 'error') '执行失败',
      if (status == 'cancelled' || status == 'stopped') '已停止',
    ].join(' · ');
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: Icon(
        switch (status) {
          'completed' || 'applied' => Icons.check_circle_outline,
          'running' || 'in_progress' => Icons.pending_outlined,
          'cancelled' || 'stopped' => Icons.stop_circle_outlined,
          _ => Icons.error_outline,
        },
        size: 18,
        color: status == 'error' ? colors.error : colors.onSurfaceVariant,
      ),
      title: Text(
        LedgerAgentRuntime.toolLabel(
          tool['tool'] as String? ?? '',
        ).replaceFirst('正在', ''),
      ),
      subtitle: description.isEmpty ? null : Text(description),
      trailing: const Icon(Icons.chevron_right, size: 20),
      onTap: () => showAgentEvidence(context, controller, tool),
    );
  }
}

class _ActionCard extends StatelessWidget {
  const _ActionCard({required this.controller, required this.action});
  final LedgerController controller;
  final Map<String, dynamic> action;

  @override
  Widget build(BuildContext context) {
    final status = action['status'];
    final payload = stringMap(action['payload'] ?? const {});
    final title = switch (status) {
      'pending' => '待你核对',
      'applied' => '已执行',
      'rejected' => '已拒绝',
      'uncertain' => '执行结果需要核对',
      'failed' || 'error' => '执行未完成',
      _ => '执行记录',
    };
    return SectionSurface(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 8),
          Text(action['title'] as String? ?? '更新账本'),
          for (final detail in _actionDetails(action))
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text(detail),
            ),
          if (status == 'pending') ...[
            const SizedBox(height: 12),
            Wrap(
              spacing: 12,
              runSpacing: 8,
              children: [
                FilledButton(
                  onPressed: controller.isBusy
                      ? null
                      : () => runUi(
                          context,
                          () => controller.resolveAgentAction(
                            action['id'] as String,
                            true,
                          ),
                        ),
                  child: const Text('确认执行'),
                ),
                TextButton(
                  onPressed: controller.isBusy
                      ? null
                      : () => runUi(
                          context,
                          () => controller.resolveAgentAction(
                            action['id'] as String,
                            false,
                          ),
                        ),
                  child: const Text('拒绝'),
                ),
              ],
            ),
          ],
          if (status == 'uncertain') ...[
            const SizedBox(height: 8),
            const Text('上次执行被中断。先核对账本中的实际结果，再决定下一步。'),
            TextButton(
              onPressed: () => controller.openLedgerQuery(const {}),
              child: const Text('核对账本'),
            ),
          ],
          if (status == 'applied' && payload['transaction'] is Map)
            Builder(
              builder: (context) {
                final id =
                    action['transactionId'] ??
                    (payload['transaction'] as Map)['id'];
                final tx = controller.transactions
                    .where((item) => item.id == id)
                    .firstOrNull;
                if (tx == null) return const SizedBox.shrink();
                return TextButton(
                  onPressed: () => _openTransaction(context, controller, tx),
                  child: const Text('查看账单'),
                );
              },
            ),
        ],
      ),
    );
  }
}

class _ReceiptTransactions extends StatelessWidget {
  const _ReceiptTransactions({required this.controller, required this.receipt});
  final LedgerController controller;
  final Map<String, dynamic> receipt;

  @override
  Widget build(BuildContext context) => ExpansionTile(
    tilePadding: EdgeInsets.zero,
    title: Text(
      '查看 ${(receipt['transactions'] as List).length} 笔账单',
      style: Theme.of(context).textTheme.bodyMedium,
    ),
    children: [
      for (final raw in receipt['transactions'] as List)
        Builder(
          builder: (context) {
            final row = stringMap(raw);
            final transaction = controller.transactions
                .where((item) => item.id == row['id'])
                .firstOrNull;
            final date = DateTime.fromMillisecondsSinceEpoch(
              row['occurredAt'] as int,
            );
            return ListTile(
              contentPadding: EdgeInsets.zero,
              title: Text(
                row['merchant'] as String? ?? row['note'] as String? ?? '账单',
              ),
              subtitle: Text(
                '${row['type'] == 'INCOME' ? '收入' : '支出'} ${formatMoney((row['amount'] as num).toInt())} 元\n${date.year}年${dateLabel(date)}',
              ),
              isThreeLine: true,
              trailing: transaction == null
                  ? null
                  : const Icon(Icons.chevron_right, size: 18),
              onTap: transaction == null
                  ? null
                  : () => _openTransaction(context, controller, transaction),
            );
          },
        ),
    ],
  );
}

List<String> _actionDetails(Map<String, dynamic> action) {
  final payload = stringMap(action['payload'] ?? const {});
  if (action['kind'] == 'transaction') {
    final tx = stringMap(payload['transaction']);
    final metadata = tx['metadataJson'] == null
        ? const <String, dynamic>{}
        : stringMap(jsonDecode(tx['metadataJson'] as String));
    return [
      if (payload['before'] is Map)
        '原金额：${formatMoney((payload['before'] as Map)['amount'] as int)} 元',
      '${tx['type'] == 'INCOME' ? '收入' : '支出'} ${formatMoney(tx['amount'] as int)} 元',
      if (tx['merchant'] != null) '商户：${tx['merchant']}',
      if (tx['note'] != null) '备注：${tx['note']}',
      for (final assumption in metadata['assumptions'] as List? ?? [])
        '推断：$assumption',
    ];
  }
  if (action['kind'] == 'budget') {
    return ['原预算：${formatMoney(payload['before'] as int? ?? 0)} 元'];
  }
  final before = payload['before'] as Map?;
  return [if (before?['note'] != null) '备注：${before!['note']}'];
}

Map<String, dynamic> _toolObservation(
  LedgerController controller,
  Map<String, dynamic> tool,
) {
  final direct = tool['result'] ?? tool['observation'];
  if (direct is Map) return stringMap(direct);
  String? content = direct is String ? direct : null;
  if (content == null) {
    final messages = controller.agentSession['messages'] as List? ?? [];
    final raw = messages
        .where(
          (message) =>
              message['role'] == 'tool' &&
              message['tool_call_id'] == tool['callId'],
        )
        .firstOrNull;
    content = raw?['content'] as String?;
  }
  if (content == null || content.isEmpty) return const {};
  try {
    return stringMap(jsonDecode(content));
  } on FormatException {
    return {'message': content};
  }
}

Future<void> showAgentEvidence(
  BuildContext context,
  LedgerController controller,
  Map<String, dynamic> tool,
) async {
  final result = _toolObservation(controller, tool);
  final data = stringMap(result['data'] ?? result);
  final rows = (data['transactions'] as List? ?? [])
      .whereType<Map>()
      .map(stringMap)
      .toList();
  final lines = <String>[
    if (result['message'] != null) result['message'] as String,
    if (result['as_of'] != null) '查询时间：${result['as_of']}',
    if (data['start'] != null) '范围：${data['start']} 至 ${data['end']}',
    if (data['status'] == 'CONFIRMED') '口径：已确认账单',
    if (data['expense'] is int) '净支出：${formatMoney(data['expense'] as int)} 元',
    if (data['income'] is int) '收入：${formatMoney(data['income'] as int)} 元',
    if (data['budget'] is int) '月预算：${formatMoney(data['budget'] as int)} 元',
    if (data['remaining'] is int)
      '预算余额：${formatMoney(data['remaining'] as int)} 元',
    if (data['total_count'] != null)
      '匹配 ${data['total_count']} 笔，当前页 ${rows.length} 笔',
    if (data['truncated'] == true) '这里只展示了部分明细，完整总额以统计结果为准。',
    if (data['a'] is Map)
      'A：${data['a']['start']} 至 ${data['a']['end']}，支出 ${formatMoney(data['a']['expense'] as int)} 元',
    if (data['b'] is Map)
      'B：${data['b']['start']} 至 ${data['b']['end']}，支出 ${formatMoney(data['b']['expense'] as int)} 元',
    for (final group in data['groups'] as List? ?? [])
      '${group['label']}：支出 ${formatMoney(group['expense'] as int)} 元，收入 ${formatMoney(group['income'] as int)} 元',
    if (data['title'] is String) data['title'] as String,
    if (data['instruction'] is String) data['instruction'] as String,
    if (data['status'] == 'remembered') '已保存长期记忆：${data['memory']['content']}',
    if (data['status'] == 'forgotten') '已删除指定记忆。',
    if (data['transaction_count'] != null)
      '账本共 ${data['transaction_count']} 笔，覆盖 ${data['first_date']} 至 ${data['last_date']}',
  ];
  await showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    builder: (context) => SafeArea(
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxHeight: MediaQuery.sizeOf(context).height * .8,
        ),
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                '执行依据 ${tool['evidenceId'] ?? ''}',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 16),
              SelectableText(
                lines.isEmpty ? '执行结果已保存在本地会话中。' : lines.join('\n\n'),
              ),
              for (final row in rows)
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  title: Text(
                    '${row['merchant'] ?? row['category'] ?? '账单'} · ${formatMoney((row['amount'] as num).toInt())} 元',
                  ),
                  subtitle: Text(
                    (row['date'] as String? ?? '').split('T').first,
                  ),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () {
                    final tx = controller.transactions
                        .where((item) => item.id == row['id'])
                        .firstOrNull;
                    if (tx != null) {
                      _openTransaction(context, controller, tx);
                    } else {
                      controller.openLedgerQuery({'keyword': row['merchant']});
                    }
                  },
                ),
              if (data['start'] != null || rows.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 16),
                  child: OutlinedButton.icon(
                    onPressed: () {
                      Navigator.pop(context);
                      controller.openLedgerQuery({
                        if (data['start'] != null) 'start': data['start'],
                        if (data['end'] != null) 'end': data['end'],
                        if (data['category_id'] != null)
                          'category_id': data['category_id'],
                        if (data['keyword'] != null) 'keyword': data['keyword'],
                        if (data['type'] != null) 'type': data['type'],
                        if (data['status'] != null) 'status': data['status'],
                      });
                    },
                    icon: const Icon(Icons.receipt_long_outlined),
                    label: const Text('到账本查看明细'),
                  ),
                ),
            ],
          ),
        ),
      ),
    ),
  );
}

void _openTransaction(
  BuildContext context,
  LedgerController controller,
  LedgerTransaction transaction,
) => Navigator.of(context).push(
  MaterialPageRoute<void>(
    builder: (_) =>
        EntryScreen(controller: controller, transaction: transaction),
  ),
);

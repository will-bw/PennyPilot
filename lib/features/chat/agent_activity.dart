import 'dart:convert';

import 'package:flutter/material.dart';

import '../../core/agent/agent_runtime.dart';
import '../../core/ledger_controller.dart';
import '../../core/models.dart';
import '../../ui/common.dart';

class AgentActivity extends StatelessWidget {
  const AgentActivity({super.key, required this.controller});
  final LedgerController controller;
  @override
  Widget build(BuildContext context) {
    final c = controller;
    final plan = c.isAsking
        ? c.agentProgress['plan'] as List? ?? []
        : c.agentSession['plan'] as List? ?? [];
    final trace = c.agentSession['trace'] as List? ?? [];
    final actions = (c.agentSession['actions'] as List? ?? [])
        .where((a) => a['status'] == 'pending' || a['status'] == 'uncertain')
        .toList();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (c.isAsking)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 16),
            child: Row(
              children: [
                const SizedBox.square(
                  dimension: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(c.agentProgress['text'] as String? ?? '正在准备任务…'),
                ),
                if (c.agentProgress['step'] != null)
                  Text(
                    '${c.agentProgress['step']}/${c.agentProgress['maxSteps']}',
                  ),
              ],
            ),
          ),
        if (plan.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(bottom: 16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                for (final item in plan)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 5),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Icon(
                          item['status'] == 'completed'
                              ? Icons.check_circle_outline
                              : item['status'] == 'in_progress'
                              ? Icons.play_circle_outline
                              : Icons.radio_button_unchecked,
                          size: 18,
                        ),
                        const SizedBox(width: 10),
                        Expanded(child: Text(item['title'] as String)),
                      ],
                    ),
                  ),
              ],
            ),
          ),
        for (final action in actions)
          Padding(
            padding: const EdgeInsets.only(bottom: 16),
            child: SectionSurface(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    action['status'] == 'uncertain' ? '执行结果需要核对' : '待你核对',
                    style: Theme.of(context).textTheme.titleSmall,
                  ),
                  const SizedBox(height: 8),
                  Text(action['title'] as String),
                  const SizedBox(height: 8),
                  ..._details(action).map(
                    (s) => Padding(
                      padding: const EdgeInsets.only(bottom: 4),
                      child: Text(s),
                    ),
                  ),
                  const SizedBox(height: 12),
                  if (action['status'] == 'pending')
                    Wrap(
                      spacing: 12,
                      runSpacing: 8,
                      children: [
                        FilledButton(
                          onPressed: c.isAsking
                              ? null
                              : () => runUi(
                                  context,
                                  () => c.resolveAgentAction(
                                    action['id'] as String,
                                    true,
                                  ),
                                ),
                          child: const Text('确认执行'),
                        ),
                        TextButton(
                          onPressed: c.isAsking
                              ? null
                              : () => runUi(
                                  context,
                                  () => c.resolveAgentAction(
                                    action['id'] as String,
                                    false,
                                  ),
                                ),
                          child: const Text('拒绝'),
                        ),
                      ],
                    )
                  else
                    const Text('上次执行被中断，系统不会自动重试。请先查询最新账本，确认实际结果。'),
                ],
              ),
            ),
          ),
        if (trace.isNotEmpty && !c.isAsking)
          ExpansionTile(
            tilePadding: EdgeInsets.zero,
            title: Text('执行记录 · ${trace.length} 次工具调用'),
            subtitle: Text(
              '上下文估算 ${c.agentSession['estimatedTokens'] ?? 0} tokens',
            ),
            children: [
              for (final item in trace.reversed.take(40))
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  dense: true,
                  leading: Icon(
                    item['status'] == 'completed'
                        ? Icons.check
                        : Icons.error_outline,
                    size: 18,
                  ),
                  title: Text(
                    LedgerAgentRuntime.toolLabel(
                      item['tool'] as String,
                    ).replaceFirst('正在', ''),
                  ),
                  subtitle: Text(
                    '第 ${item['step']} 步${item['evidenceId'] == null ? '' : ' · 依据 ${item['evidenceId']}'}',
                  ),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => showModalBottomSheet<void>(
                    context: context,
                    isScrollControlled: true,
                    showDragHandle: true,
                    builder: (context) => SafeArea(
                      child: Padding(
                        padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
                        child: SingleChildScrollView(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Text(
                                '执行依据 ${item['evidenceId'] ?? ''}',
                                style: Theme.of(context).textTheme.titleLarge,
                              ),
                              const SizedBox(height: 16),
                              SelectableText(
                                _observation(c, item['callId'] as String),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
            ],
          ),
      ],
    );
  }

  static List<String> _details(dynamic action) {
    final payload = action['payload'] as Map;
    if (action['kind'] == 'transaction') {
      final tx = payload['transaction'] as Map;
      return [
        if (payload['before'] is Map)
          '原金额：${formatMoney((payload['before'] as Map)['amount'] as int)} 元',
        '${tx['type'] == 'INCOME' ? '收入' : '支出'} ${formatMoney(tx['amount'] as int)} 元',
        if (tx['merchant'] != null) '商户：${tx['merchant']}',
        if (tx['note'] != null) '备注：${tx['note']}',
      ];
    }
    if (action['kind'] == 'budget') {
      return ['原预算：${formatMoney(payload['before'] as int? ?? 0)} 元'];
    }
    final before = payload['before'] as Map?;
    return [if (before?['note'] != null) '备注：${before!['note']}'];
  }

  static String _observation(LedgerController c, String callId) {
    final messages = c.agentSession['messages'] as List? ?? [];
    final raw = messages
        .where((m) => m['role'] == 'tool' && m['tool_call_id'] == callId)
        .firstOrNull;
    if (raw == null) return '没有已保存的结果。';
    try {
      final result = jsonDecode(raw['content'] as String) as Map;
      if (result['error'] != null) {
        return result['message'] as String? ?? '本次工具没有完成。';
      }
      final data = result['data'] as Map? ?? result;
      final lines = <String>[
        if (result['as_of'] != null) '查询时间：${result['as_of']}',
        if (data['start'] != null) '范围：${data['start']} 至 ${data['end']}',
        if (data['status'] == 'CONFIRMED') '口径：已确认账单',
        if (data['expense'] is int)
          '净支出：${formatMoney(data['expense'] as int)} 元',
        if (data['income'] is int) '收入：${formatMoney(data['income'] as int)} 元',
        if (data['budget'] is int)
          '月预算：${formatMoney(data['budget'] as int)} 元',
        if (data['remaining'] is int)
          '预算余额：${formatMoney(data['remaining'] as int)} 元',
        if (data['total_count'] != null)
          '匹配 ${data['total_count']} 笔，当前页 ${(data['transactions'] as List).length} 笔',
        if (data['truncated'] == true) '明细尚有下一页，完整总额请查看统计工具。',
        if (data['a'] is Map)
          'A：${data['a']['start']} 至 ${data['a']['end']}，支出 ${formatMoney(data['a']['expense'] as int)} 元',
        if (data['b'] is Map)
          'B：${data['b']['start']} 至 ${data['b']['end']}，支出 ${formatMoney(data['b']['expense'] as int)} 元',
        for (final row in data['transactions'] as List? ?? [])
          '${(row['date'] as String).substring(0, 10)} ${row['merchant'] ?? row['category'] ?? ''} ${formatMoney(row['amount'] as int)} 元',
        for (final group in data['groups'] as List? ?? [])
          '${group['label']}：支出 ${formatMoney(group['expense'] as int)} 元，收入 ${formatMoney(group['income'] as int)} 元',
        if (data['title'] != null) data['title'] as String,
        if (data['instruction'] != null) data['instruction'] as String,
        if (data['status'] == 'remembered')
          '已保存长期记忆：${data['memory']['content']}',
        if (data['status'] == 'forgotten') '已删除指定记忆。',
        if (data['transaction_count'] != null)
          '账本共 ${data['transaction_count']} 笔，覆盖 ${data['first_date']} 至 ${data['last_date']}',
      ];
      return lines.isEmpty ? '执行结果已保存在本地会话中。' : lines.join('\n\n');
    } catch (_) {
      return '结果已保存，可让助手重新查询并解释。';
    }
  }
}

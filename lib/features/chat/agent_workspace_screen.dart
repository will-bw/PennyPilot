import 'package:flutter/material.dart';

import '../../core/agent/agent_runtime.dart';
import '../../core/agent/ledger_agent_tools.dart';
import '../../core/ledger_controller.dart';
import '../../ui/common.dart';

class AgentWorkspaceScreen extends StatelessWidget {
  const AgentWorkspaceScreen({super.key, required this.controller});
  final LedgerController controller;
  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: controller,
    builder: (context, _) => DefaultTabController(
      length: 3,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('对话与记忆'),
          bottom: const TabBar(
            tabs: [
              Tab(text: '对话'),
              Tab(text: '记忆'),
              Tab(text: '运行设置'),
            ],
          ),
        ),
        body: ContentWidth(
          child: TabBarView(
            children: [
              _sessions(context),
              _memories(context),
              _AgentSettings(controller: controller),
            ],
          ),
        ),
      ),
    ),
  );
  Widget _sessions(BuildContext context) => ListView(
    padding: const EdgeInsets.all(20),
    children: [
      FilledButton.icon(
        onPressed: controller.isAsking
            ? null
            : () async {
                await runUi(
                  context,
                  () => controller.agentCommand('agentNewSession'),
                );
                if (context.mounted) Navigator.pop(context);
              },
        icon: const Icon(Icons.add),
        label: const Text('开始新对话'),
      ),
      const SizedBox(height: 20),
      for (final session in controller.agentSessions)
        ListTile(
          contentPadding: EdgeInsets.zero,
          leading: Icon(
            session['id'] == controller.agentSession['id']
                ? Icons.chat_bubble
                : Icons.chat_bubble_outline,
          ),
          title: Text(
            session['title'] as String,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
          ),
          subtitle: Text(_date(session['updatedAt'] as int)),
          onTap: controller.isAsking
              ? null
              : () async {
                  await runUi(
                    context,
                    () => controller.agentCommand('agentSelectSession', {
                      'id': session['id'],
                    }),
                  );
                  if (context.mounted) Navigator.pop(context);
                },
          trailing: IconButton(
            tooltip: '删除这段对话',
            icon: const Icon(Icons.delete_outline),
            onPressed: controller.isAsking
                ? null
                : () async {
                    final confirmed = await showDialog<bool>(
                      context: context,
                      builder: (context) => AlertDialog(
                        title: const Text('删除这段对话？'),
                        content: const Text('会删除对话和执行记录，保留账单与长期记忆。'),
                        actions: [
                          TextButton(
                            onPressed: () => Navigator.pop(context, false),
                            child: const Text('取消'),
                          ),
                          FilledButton(
                            onPressed: () => Navigator.pop(context, true),
                            child: const Text('删除'),
                          ),
                        ],
                      ),
                    );
                    if (confirmed == true && context.mounted) {
                      await runUi(
                        context,
                        () => controller.agentCommand('agentDeleteSession', {
                          'id': session['id'],
                        }),
                      );
                    }
                  },
          ),
        ),
      const SizedBox(height: 16),
      const Text('对话与工具记录保存在这台设备上，重启后可继续。恢复账本后会使用新的对话空间。'),
    ],
  );
  Widget _memories(BuildContext context) => ListView(
    padding: const EdgeInsets.all(20),
    children: [
      const Text('助手记住的偏好、目标和个人事实。你可以在对话中纠正，也可以随时删除。'),
      const SizedBox(height: 20),
      if (controller.agentMemories.isEmpty)
        const Padding(
          padding: EdgeInsets.symmetric(vertical: 32),
          child: Text('还没有长期记忆。可以告诉助手：“记住，我每月的储蓄目标是……”'),
        ),
      for (final memory in controller.agentMemories)
        ExpansionTile(
          tilePadding: EdgeInsets.zero,
          title: Text(memory['content'] as String),
          subtitle: Text(_date(memory['updatedAt'] as int)),
          children: [
            Padding(
              padding: const EdgeInsets.only(bottom: 16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('原话依据：${memory['evidence']}'),
                  TextButton.icon(
                    onPressed: controller.isAsking
                        ? null
                        : () => runUi(
                            context,
                            () => controller.agentCommand('agentForgetMemory', {
                              'key': memory['key'],
                            }),
                          ),
                    icon: const Icon(Icons.delete_outline),
                    label: const Text('忘记这条记忆'),
                  ),
                ],
              ),
            ),
          ],
        ),
    ],
  );
  static String _date(int value) {
    final d = DateTime.fromMillisecondsSinceEpoch(value);
    return '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')} '
        '${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';
  }
}

class _AgentSettings extends StatefulWidget {
  const _AgentSettings({required this.controller});
  final LedgerController controller;
  @override
  State<_AgentSettings> createState() => _AgentSettingsState();
}

class _AgentSettingsState extends State<_AgentSettings> {
  late int tokens =
      widget.controller.agentConfig['contextTokens'] as int? ?? 24000;
  late int steps = widget.controller.agentConfig['maxSteps'] as int? ?? 20;
  @override
  Widget build(BuildContext context) => ListView(
    padding: const EdgeInsets.all(20),
    children: [
      const Text('为连续查账设置资源上限。助手自行选择工具和执行顺序，达到上限时会保留进度。'),
      const SizedBox(height: 24),
      DropdownButtonFormField<int>(
        initialValue: tokens,
        isExpanded: true,
        decoration: const InputDecoration(
          labelText: '上下文预算',
          helperText: '按保守估算控制；请保持在所选模型支持范围内。',
          helperMaxLines: 3,
        ),
        items: [8000, 16000, 24000, 32000, 64000, 128000]
            .map((n) => DropdownMenuItem(value: n, child: Text('$n tokens')))
            .toList(),
        onChanged: widget.controller.isAsking
            ? null
            : (v) => setState(() => tokens = v!),
      ),
      const SizedBox(height: 24),
      DropdownButtonFormField<int>(
        initialValue: steps,
        isExpanded: true,
        decoration: const InputDecoration(labelText: '每次任务最多执行步数'),
        items: [
          4,
          10,
          20,
          30,
          40,
        ].map((n) => DropdownMenuItem(value: n, child: Text('$n 步'))).toList(),
        onChanged: widget.controller.isAsking
            ? null
            : (v) => setState(() => steps = v!),
      ),
      const SizedBox(height: 24),
      FilledButton(
        onPressed: widget.controller.isAsking
            ? null
            : () => runUi(
                context,
                () => widget.controller.agentCommand('agentConfigure', {
                  'config': {'contextTokens': tokens, 'maxSteps': steps},
                }),
                success: 'Agent 设置已保存',
              ),
        child: const Text('保存设置'),
      ),
      const SizedBox(height: 24),
      ExpansionTile(
        tilePadding: EdgeInsets.zero,
        title: const Text('助手可以使用的工具'),
        children: [
          for (final tool in LedgerAgentTools.definitions)
            ListTile(
              contentPadding: EdgeInsets.zero,
              title: Text(
                LedgerAgentRuntime.toolLabel(
                  (tool['function'] as Map)['name'] as String,
                ).replaceFirst('正在', ''),
              ),
              subtitle: Text(
                (tool['function'] as Map)['description'] as String,
              ),
            ),
        ],
      ),
    ],
  );
}

import 'package:flutter/material.dart';
import '../../core/ledger_controller.dart';
import '../../ui/common.dart';
import 'agent_activity.dart';
import 'agent_workspace_screen.dart';

class ChatScreen extends StatefulWidget {
  const ChatScreen({
    super.key,
    required this.controller,
    required this.onSettings,
  });
  final LedgerController controller;
  final VoidCallback onSettings;
  @override
  State<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends State<ChatScreen> {
  final input = TextEditingController(), scroll = ScrollController();
  @override
  void dispose() {
    input.dispose();
    scroll.dispose();
    super.dispose();
  }

  Future<void> ask(String text) async {
    if (text.trim().isEmpty) return;
    input.clear();
    await runUi(context, () => widget.controller.ask(text));
    if (mounted && scroll.hasClients) {
      scroll.animateTo(
        scroll.position.maxScrollExtent,
        duration: MediaQuery.disableAnimationsOf(context)
            ? Duration.zero
            : const Duration(milliseconds: 200),
        curve: Curves.easeOut,
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.controller;
    final colors = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(
        title: const Text('问问账本'),
        actions: [
          IconButton(
            tooltip: '对话、记忆与运行设置',
            icon: const Icon(Icons.forum_outlined),
            onPressed: c.isAsking
                ? null
                : () => Navigator.push(
                    context,
                    MaterialPageRoute<void>(
                      builder: (_) => AgentWorkspaceScreen(controller: c),
                    ),
                  ),
          ),
          IconButton(
            tooltip: 'AI 设置',
            onPressed: widget.onSettings,
            icon: const Icon(Icons.tune),
          ),
          IconButton(
            tooltip: '清空本次对话',
            onPressed: c.isAsking || c.messages.isEmpty
                ? null
                : () async {
                    final ok = await showDialog<bool>(
                      context: context,
                      builder: (context) => AlertDialog(
                        title: const Text('清空本次对话？'),
                        content: const Text('这只会清除当前问答，不会删除账单。'),
                        actions: [
                          TextButton(
                            onPressed: () => Navigator.pop(context, false),
                            child: const Text('取消'),
                          ),
                          FilledButton(
                            onPressed: () => Navigator.pop(context, true),
                            child: const Text('清空'),
                          ),
                        ],
                      ),
                    );
                    if (ok == true && context.mounted) {
                      await runUi(context, c.clearConversation);
                    }
                  },
            icon: const Icon(Icons.delete_sweep_outlined),
          ),
        ],
      ),
      body: ContentWidth(
        child: SizedBox(
          width: 720,
          child: Column(
            children: [
              Expanded(
                child: c.messages.isEmpty
                    ? _suggestions(context)
                    : ListView.builder(
                        controller: scroll,
                        padding: const EdgeInsets.fromLTRB(24, 12, 24, 24),
                        itemCount: c.messages.length + 1,
                        itemBuilder: (context, index) {
                          if (index == c.messages.length) {
                            return AgentActivity(controller: c);
                          }
                          final msg = c.messages[index];
                          final user = msg.role == 'user';
                          return Padding(
                            padding: const EdgeInsets.only(bottom: 28),
                            child: Column(
                              crossAxisAlignment: user
                                  ? CrossAxisAlignment.end
                                  : CrossAxisAlignment.start,
                              children: [
                                Padding(
                                  padding: const EdgeInsets.only(bottom: 8),
                                  child: Text(
                                    user ? '你' : '账本答复',
                                    style: TextStyle(
                                      fontSize: 12,
                                      color: colors.onSurfaceVariant,
                                    ),
                                  ),
                                ),
                                Container(
                                  constraints: const BoxConstraints(
                                    maxWidth: 640,
                                  ),
                                  padding: user
                                      ? const EdgeInsets.symmetric(
                                          horizontal: 16,
                                          vertical: 12,
                                        )
                                      : EdgeInsets.zero,
                                  decoration: user
                                      ? BoxDecoration(
                                          color: colors.surfaceContainerLow,
                                          borderRadius: BorderRadius.circular(
                                            12,
                                          ),
                                        )
                                      : null,
                                  child: SelectionArea(
                                    child: Text(
                                      msg.text,
                                      style: TextStyle(
                                        fontSize: 15,
                                        height: 1.7,
                                        color: msg.isError
                                            ? colors.error
                                            : colors.onSurface,
                                      ),
                                    ),
                                  ),
                                ),
                                if (msg.chartData != null) ...[
                                  const SizedBox(height: 16),
                                  SectionSurface(
                                    padding: const EdgeInsets.symmetric(
                                      horizontal: 16,
                                      vertical: 8,
                                    ),
                                    child: Column(
                                      children: [
                                        for (
                                          var i = 0;
                                          i < msg.chartData!.labels.length &&
                                              i < msg.chartData!.values.length;
                                          i++
                                        ) ...[
                                          if (i > 0) const Divider(),
                                          Padding(
                                            padding: const EdgeInsets.symmetric(
                                              vertical: 12,
                                            ),
                                            child: Row(
                                              children: [
                                                Expanded(
                                                  child: Text(
                                                    msg.chartData!.labels[i],
                                                  ),
                                                ),
                                                const SizedBox(width: 12),
                                                AmountLabel(
                                                  msg.chartData!.values[i],
                                                ),
                                              ],
                                            ),
                                          ),
                                        ],
                                      ],
                                    ),
                                  ),
                                ],
                                if (msg.isError &&
                                    index == c.messages.length - 1 &&
                                    c.retryQuestion != null)
                                  Padding(
                                    padding: const EdgeInsets.only(top: 8),
                                    child: TextButton.icon(
                                      onPressed: c.isAsking
                                          ? null
                                          : () => runUi(context, c.retryAsk),
                                      icon: const Icon(Icons.refresh, size: 18),
                                      label: const Text('重试这个问题'),
                                    ),
                                  ),
                              ],
                            ),
                          );
                        },
                      ),
              ),
              SafeArea(
                top: false,
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(20, 12, 20, 12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      DecoratedBox(
                        decoration: BoxDecoration(
                          color: colors.surfaceContainerLow,
                          borderRadius: BorderRadius.circular(14),
                          border: Border.all(color: colors.outlineVariant),
                        ),
                        child: Padding(
                          padding: const EdgeInsets.fromLTRB(4, 4, 8, 4),
                          child: Row(
                            crossAxisAlignment: CrossAxisAlignment.end,
                            children: [
                              Expanded(
                                child: TextField(
                                  controller: input,
                                  minLines: 1,
                                  maxLines: 5,
                                  maxLength: 2000,
                                  decoration: const InputDecoration(
                                    hintText: '查询、分析，或告诉我想完成的记账任务',
                                    counterText: '',
                                    filled: false,
                                    border: InputBorder.none,
                                    enabledBorder: InputBorder.none,
                                    focusedBorder: InputBorder.none,
                                    contentPadding: EdgeInsets.symmetric(
                                      horizontal: 12,
                                      vertical: 12,
                                    ),
                                  ),
                                  textInputAction: TextInputAction.newline,
                                ),
                              ),
                              Padding(
                                padding: const EdgeInsets.only(bottom: 2),
                                child: ValueListenableBuilder<TextEditingValue>(
                                  valueListenable: input,
                                  builder: (context, value, _) =>
                                      IconButton.filled(
                                        tooltip: c.isAsking ? '停止任务' : '发送问题',
                                        onPressed: c.isAsking
                                            ? () => runUi(context, c.stopAgent)
                                            : value.text.trim().isEmpty
                                            ? null
                                            : () => ask(input.text),
                                        icon: c.isAsking
                                            ? const Icon(Icons.stop, size: 21)
                                            : const Icon(
                                                Icons.arrow_upward,
                                                size: 21,
                                              ),
                                      ),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                      const SizedBox(height: 10),
                      Text(
                        'AI 可能出错，请以账单明细为准。对话、必要账本数据和相关记忆会发送到所选 AI 服务。',
                        style: TextStyle(
                          fontSize: 11,
                          height: 1.5,
                          color: colors.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _suggestions(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return ListView(
      padding: const EdgeInsets.fromLTRB(24, 28, 24, 24),
      children: [
        const PageHeading(title: '看看钱花在哪里', subtitle: '用日常语言提问，从真实账单中找答案。'),
        const SizedBox(height: 32),
        for (final question in [
          '这个月一共花了多少钱？',
          '本月哪类支出最多？',
          '对比本月和上月，找出增长最多的分类，再查明细分析。',
        ]) ...[
          InkWell(
            onTap: widget.controller.isAsking ? null : () => ask(question),
            borderRadius: BorderRadius.circular(8),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 19, horizontal: 2),
              child: Row(
                children: [
                  Expanded(
                    child: Text(question, style: const TextStyle(fontSize: 15)),
                  ),
                  const SizedBox(width: 12),
                  Icon(Icons.arrow_forward, size: 18, color: colors.primary),
                ],
              ),
            ),
          ),
          const Divider(),
        ],
        const SizedBox(height: 20),
        Align(
          alignment: Alignment.centerLeft,
          child: TextButton.icon(
            onPressed: widget.onSettings,
            icon: const Icon(Icons.tune, size: 16),
            label: const Text('配置或切换 AI 服务'),
          ),
        ),
      ],
    );
  }
}

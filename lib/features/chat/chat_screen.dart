import 'package:flutter/material.dart';
import '../../core/ledger_controller.dart';
import '../../ui/common.dart';

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
                    if (ok == true) c.clearConversation();
                  },
            icon: const Icon(Icons.delete_sweep_outlined),
          ),
        ],
      ),
      body: ContentWidth(
        child: Column(
          children: [
            Expanded(
              child: c.messages.isEmpty
                  ? ListView(
                      padding: const EdgeInsets.fromLTRB(24, 40, 24, 24),
                      children: [
                        Icon(
                          Icons.forum_outlined,
                          size: 40,
                          color: colors.primary,
                        ),
                        const SizedBox(height: 24),
                        Text(
                          '从账单中找到答案。',
                          textAlign: TextAlign.center,
                          style: Theme.of(context).textTheme.titleLarge,
                        ),
                        const SizedBox(height: 12),
                        Text(
                          '用日常语言提问，从真实账单中找答案。',
                          textAlign: TextAlign.center,
                          style: TextStyle(color: colors.onSurfaceVariant),
                        ),
                        const SizedBox(height: 32),
                        for (final q in [
                          '这个月一共花了多少钱？',
                          '本月哪类支出最多？',
                          '最近三个月支出有什么变化？',
                        ])
                          Padding(
                            padding: const EdgeInsets.only(bottom: 10),
                            child: OutlinedButton(
                              onPressed: c.isAsking ? null : () => ask(q),
                              child: Align(
                                alignment: Alignment.centerLeft,
                                child: Padding(
                                  padding: const EdgeInsets.symmetric(
                                    vertical: 14,
                                  ),
                                  child: Text(q),
                                ),
                              ),
                            ),
                          ),
                        const SizedBox(height: 16),
                        TextButton(
                          onPressed: widget.onSettings,
                          child: const Text('配置或切换 AI 服务'),
                        ),
                      ],
                    )
                  : ListView.builder(
                      controller: scroll,
                      padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
                      itemCount: c.messages.length + (c.isAsking ? 1 : 0),
                      itemBuilder: (context, index) {
                        if (index == c.messages.length) {
                          return const Padding(
                            padding: EdgeInsets.symmetric(vertical: 20),
                            child: Row(
                              children: [
                                SizedBox.square(
                                  dimension: 18,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                                ),
                                SizedBox(width: 12),
                                Text('正在查询账本…'),
                              ],
                            ),
                          );
                        }
                        final msg = c.messages[index],
                            user = c.messages[index].role == 'user';
                        return Padding(
                          padding: const EdgeInsets.symmetric(vertical: 10),
                          child: Column(
                            crossAxisAlignment: user
                                ? CrossAxisAlignment.end
                                : CrossAxisAlignment.start,
                            children: [
                              Text(
                                user ? '你' : 'PennyPilot',
                                style: TextStyle(
                                  fontSize: 12,
                                  color: colors.onSurfaceVariant,
                                ),
                              ),
                              const SizedBox(height: 8),
                              Container(
                                padding: const EdgeInsets.all(16),
                                decoration: BoxDecoration(
                                  color: user
                                      ? colors.primaryContainer
                                      : colors.surfaceContainerLow,
                                  borderRadius: BorderRadius.circular(14),
                                ),
                                child: SelectionArea(
                                  child: Text(
                                    msg.text,
                                    style: TextStyle(
                                      height: 1.65,
                                      color: msg.isError
                                          ? colors.error
                                          : colors.onSurface,
                                    ),
                                  ),
                                ),
                              ),
                              if (msg.chartData != null) ...[
                                const SizedBox(height: 12),
                                for (
                                  var i = 0;
                                  i < msg.chartData!.labels.length &&
                                      i < msg.chartData!.values.length;
                                  i++
                                )
                                  Padding(
                                    padding: const EdgeInsets.symmetric(
                                      vertical: 6,
                                    ),
                                    child: Row(
                                      children: [
                                        Expanded(
                                          child: Text(msg.chartData!.labels[i]),
                                        ),
                                        Text(money(msg.chartData!.values[i])),
                                      ],
                                    ),
                                  ),
                              ],
                              if (msg.isError &&
                                  index == c.messages.length - 1 &&
                                  c.retryQuestion != null)
                                TextButton.icon(
                                  onPressed: c.isAsking
                                      ? null
                                      : () => runUi(context, c.retryAsk),
                                  icon: const Icon(Icons.refresh),
                                  label: const Text('重试这个问题'),
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
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
                child: Column(
                  children: [
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.end,
                      children: [
                        Expanded(
                          child: TextField(
                            controller: input,
                            minLines: 1,
                            maxLines: 5,
                            maxLength: 2000,
                            decoration: const InputDecoration(
                              hintText: '想了解哪笔开销？',
                              counterText: '',
                            ),
                            textInputAction: TextInputAction.newline,
                          ),
                        ),
                        const SizedBox(width: 8),
                        IconButton.filled(
                          tooltip: '发送问题',
                          onPressed: c.isAsking ? null : () => ask(input.text),
                          icon: c.isAsking
                              ? const SizedBox.square(
                                  dimension: 20,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                                )
                              : const Icon(Icons.arrow_upward),
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'AI 可能出错，请以账单明细为准。相关账本信息会发送到已配置的服务。',
                      style: TextStyle(
                        fontSize: 11,
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
    );
  }
}

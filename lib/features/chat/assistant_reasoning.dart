import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/ai/model_reasoning.dart';
import '../../ui/assistant_content.dart';
import '../../ui/common.dart';

class AssistantReasoning extends StatefulWidget {
  const AssistantReasoning({
    super.key,
    required this.parts,
    this.active = false,
  });
  final List<Map<String, dynamic>> parts;
  final bool active;

  @override
  State<AssistantReasoning> createState() => _AssistantReasoningState();
}

class _AssistantReasoningState extends State<AssistantReasoning> {
  Timer? timer;
  bool get streaming =>
      widget.active && widget.parts.any((p) => p['status'] == 'streaming');

  @override
  void initState() {
    super.initState();
    _timer();
  }

  @override
  void didUpdateWidget(AssistantReasoning oldWidget) {
    super.didUpdateWidget(oldWidget);
    _timer();
  }

  void _timer() {
    if (streaming) {
      timer ??= Timer.periodic(const Duration(seconds: 1), (_) {
        if (mounted) setState(() {});
      });
    } else {
      timer?.cancel();
      timer = null;
    }
  }

  @override
  void dispose() {
    timer?.cancel();
    super.dispose();
  }

  int? _elapsed(Map<String, dynamic> part) {
    final start = part['startedAt'] as int?;
    final end = part['endedAt'] as int?;
    final receiving = widget.active && part['status'] == 'streaming';
    if (start == null || end == null && !receiving) return null;
    return ((end ?? DateTime.now().millisecondsSinceEpoch) - start).clamp(
      0,
      1 << 52,
    );
  }

  String _duration(int ms) {
    if (ms < 1000) return '不足 1 秒';
    final seconds = ms ~/ 1000;
    return seconds < 60 ? '$seconds 秒' : '${seconds ~/ 60} 分 ${seconds % 60} 秒';
  }

  @override
  Widget build(BuildContext context) {
    if (widget.parts.isEmpty) return const SizedBox.shrink();
    final summaries = widget.parts.every((part) => part['kind'] == 'summary');
    final title = summaries ? '思考摘要' : '思考过程';
    final interrupted = widget.parts.any(
      (part) =>
          const {'cancelled', 'error', 'streaming'}.contains(part['status']),
    );
    final empty = widget.parts.every(
      (part) => (part['text'] as String? ?? '').isEmpty,
    );
    final cancelled = widget.parts.any((part) => part['status'] == 'cancelled');
    final status = streaming
        ? '正在接收'
        : cancelled
        ? '已停止'
        : interrupted
        ? '未完成'
        : empty
        ? '未返回内容'
        : '已完成';
    final durations = widget.parts.map(_elapsed).whereType<int>().toList();
    final duration = durations.length == widget.parts.length
        ? _duration(durations.fold<int>(0, (sum, value) => sum + value))
        : '耗时未记录';
    final configurations = <String>{};
    for (final part in widget.parts) {
      final model = part['model'] as String?;
      final effort = part['effort'] as String?;
      final description = [
        if (model?.isNotEmpty == true) model!,
        if (effort?.isNotEmpty == true) '深度：${reasoningEffortLabel(effort!)}',
      ].join(' · ');
      if (description.isNotEmpty) configurations.add(description);
    }
    final colors = Theme.of(context).colorScheme;
    return ExpansionTile(
      key: const ValueKey('assistant-reasoning'),
      expandedCrossAxisAlignment: CrossAxisAlignment.stretch,
      tilePadding: EdgeInsets.zero,
      childrenPadding: const EdgeInsets.only(bottom: 16),
      leading: Icon(
        streaming
            ? Icons.psychology_outlined
            : interrupted
            ? Icons.pause_circle_outline
            : Icons.check_circle_outline,
        size: 20,
        color: colors.onSurfaceVariant,
      ),
      title: Text(
        '$title · $status',
        style: Theme.of(context).textTheme.bodyMedium,
      ),
      subtitle: Text(
        [
          '${widget.parts.length > 1 ? '${widget.parts.length} 次模型响应 · ' : ''}$duration',
          ...configurations,
        ].join('\n'),
      ),
      children: [
        for (var i = 0; i < widget.parts.length; i++)
          _part(context, widget.parts[i], i),
      ],
    );
  }

  Widget _part(BuildContext context, Map<String, dynamic> part, int index) {
    final text = part['text'] as String? ?? '';
    final summary = part['kind'] == 'summary';
    final receiving = widget.active && part['status'] == 'streaming';
    final elapsed = _elapsed(part);
    return Padding(
      key: ValueKey(part['id'] ?? part['messageId'] ?? index),
      padding: EdgeInsets.only(top: index == 0 ? 0 : 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (widget.parts.length > 1) ...[
            Text(
              '第 ${index + 1} 次模型响应${elapsed == null ? '' : ' · ${_duration(elapsed)}'}',
              style: Theme.of(context).textTheme.labelMedium,
            ),
            const SizedBox(height: 8),
          ],
          if (text.isNotEmpty)
            AssistantMarkdown(text: text, isStreaming: receiving)
          else
            Text(
              receiving
                  ? summary
                        ? '等待服务商返回公开摘要…'
                        : '等待模型返回思考内容…'
                  : summary
                  ? '这次没有返回可展示的公开思考摘要。'
                  : '这次没有返回可展示的思考内容。',
            ),
          if (summary && text.isNotEmpty) ...[
            const SizedBox(height: 8),
            Text('服务商返回的公开摘要', style: Theme.of(context).textTheme.bodySmall),
          ],
          if (text.isNotEmpty && !receiving)
            TextButton.icon(
              onPressed: () => runUi(
                context,
                () => Clipboard.setData(ClipboardData(text: text)),
                success: '已复制思考内容',
              ),
              icon: const Icon(Icons.copy_outlined, size: 16),
              label: const Text('复制思考内容'),
            ),
        ],
      ),
    );
  }
}

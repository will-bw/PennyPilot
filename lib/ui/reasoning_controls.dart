import 'package:flutter/material.dart';

import '../core/ai/model_reasoning.dart';

class ReasoningControls extends StatelessWidget {
  const ReasoningControls({
    super.key,
    required this.options,
    required this.enabled,
    required this.onEnabledChanged,
    required this.onEffortChanged,
    this.compact = false,
    this.keyPrefix = 'reasoning',
  });

  final ReasoningOptions options;
  final bool enabled;
  final ValueChanged<bool> onEnabledChanged;
  final ValueChanged<String> onEffortChanged;
  final bool compact;
  final String keyPrefix;

  @override
  Widget build(BuildContext context) {
    final capability = options.capability;
    final colors = Theme.of(context).colorScheme;
    if (!capability.known || !capability.supported) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Text(
          !capability.known ? '当前模型的思考选项尚未确认。' : '当前模型不支持可调节的深度思考。',
          style: TextStyle(
            color: colors.onSurfaceVariant,
            fontSize: compact ? 12 : null,
          ),
        ),
      );
    }
    final toggle = capability.canToggle
        ? compact
              ? FilterChip(
                  key: ValueKey('$keyPrefix-thinking-toggle'),
                  label: const Text('深度思考'),
                  selected: options.enabled,
                  onSelected: enabled ? onEnabledChanged : null,
                )
              : SwitchListTile.adaptive(
                  key: ValueKey('$keyPrefix-thinking-toggle'),
                  contentPadding: EdgeInsets.zero,
                  title: const Text('深度思考'),
                  subtitle: const Text('关闭时使用此模型支持的快速回答模式。'),
                  value: options.enabled,
                  onChanged: enabled ? onEnabledChanged : null,
                )
        : compact
        ? const Chip(
            avatar: Icon(Icons.lock_outline, size: 16),
            label: Text('始终思考'),
          )
        : const ListTile(
            contentPadding: EdgeInsets.zero,
            leading: Icon(Icons.lock_outline),
            title: Text('思考已启用'),
            subtitle: Text('此模型始终启用思考，不能关闭。'),
          );
    final efforts = capability.efforts;
    final showEffort = options.enabled && efforts.isNotEmpty;
    if (compact) {
      return Wrap(
        spacing: 8,
        runSpacing: 4,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          toggle,
          if (showEffort)
            PopupMenuButton<String>(
              key: ValueKey('$keyPrefix-thinking-effort'),
              tooltip: '思考深度',
              enabled: enabled && efforts.length > 1,
              initialValue: options.effort,
              onSelected: onEffortChanged,
              itemBuilder: (_) => [
                for (final effort in efforts)
                  CheckedPopupMenuItem(
                    value: effort,
                    checked: options.effort == effort,
                    child: Text(reasoningEffortLabel(effort)),
                  ),
              ],
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 8,
                  vertical: 12,
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      reasoningEffortLabel(
                        options.effort ??
                            capability.defaultEffort ??
                            efforts.first,
                      ),
                      style: TextStyle(
                        color: enabled
                            ? colors.primary
                            : colors.onSurfaceVariant,
                      ),
                    ),
                    const SizedBox(width: 4),
                    const Icon(Icons.expand_more, size: 18),
                  ],
                ),
              ),
            ),
          if (!enabled)
            Text(
              '任务进行中',
              style: TextStyle(fontSize: 12, color: colors.onSurfaceVariant),
            ),
        ],
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        toggle,
        if (showEffort) ...[
          const SizedBox(height: 12),
          DropdownButtonFormField<String>(
            key: ValueKey('$keyPrefix-thinking-effort-${options.effort}'),
            initialValue:
                options.effort ?? capability.defaultEffort ?? efforts.first,
            isExpanded: true,
            decoration: const InputDecoration(labelText: '思考深度'),
            items: [
              for (final effort in efforts)
                DropdownMenuItem(
                  value: effort,
                  child: Text(reasoningEffortLabel(effort)),
                ),
            ],
            onChanged: !enabled || efforts.length < 2
                ? null
                : (value) => onEffortChanged(value!),
          ),
        ],
        const SizedBox(height: 12),
        Text(
          capability.visibility == ReasoningVisibility.summary
              ? capability.supportsSummary == false
                    ? '当前模型不提供公开思考摘要。'
                    : '对话中仅展示服务商返回的公开思考摘要。'
              : capability.visibility == ReasoningVisibility.reasoningContent
              ? '对话中可展开服务商返回的思考内容。'
              : '此模型未提供可展示的思考内容。',
          style: TextStyle(color: colors.onSurfaceVariant, fontSize: 12),
        ),
      ],
    );
  }
}

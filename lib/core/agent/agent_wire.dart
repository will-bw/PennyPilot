import '../ai/ai_transport.dart';
import 'agent_models.dart';

const ledgerToolNamespace = 'pennypilot';

/// Lossless Responses replay, including completed encrypted reasoning. Ordinary
/// messages/tool calls remain provider-neutral; account-bound items are tagged.
List<Map<String, dynamic>> responsesAgentInput(
  List<Map<String, dynamic>> messages,
  String accountId,
) {
  final result = <Map<String, dynamic>>[];
  final calls = <String>{}, answered = <String>{};
  for (final message in messages) {
    final role = message['role'];
    if (role == 'tool') {
      final id = message['tool_call_id'];
      if (id is! String || !calls.contains(id) || !answered.add(id)) {
        aiFailure('工具结果与调用不匹配。');
      }
      result.add({
        'type': 'function_call_output',
        'call_id': id,
        'output': message['content'],
      });
      continue;
    }
    if (!const {'user', 'assistant', 'system', 'developer'}.contains(role)) {
      aiFailure('对话角色无效。');
    }
    final toolCalls = (message['tool_calls'] as List? ?? [])
        .map(agentMap)
        .toList();
    for (final call in toolCalls) {
      if (!calls.add(call['id'] as String)) aiFailure('工具调用编号重复。');
    }
    final preserved = message['responsesItems'];
    if (preserved != null) {
      if (message['responsesAccountId'] != accountId || role != 'assistant') {
        aiFailure('工具历史所属账号不一致。');
      }
      final items = (preserved as List).map(agentMap).toList();
      final decoded = agentReplyFromResponses(items, accountId);
      if (agentJson(decoded.calls) != agentJson(toolCalls)) {
        aiFailure('保留的工具历史不一致。');
      }
      result.addAll(items);
      continue;
    }
    if ((message['content'] as String? ?? '').isNotEmpty || toolCalls.isEmpty) {
      result.add({
        'type': 'message',
        'role': role == 'system' ? 'developer' : role,
        'content': message['content'] ?? '',
      });
    }
    for (final call in toolCalls) {
      final fn = agentMap(call['function']);
      result.add({
        'type': 'function_call',
        'namespace': ledgerToolNamespace,
        'call_id': call['id'],
        'name': fn['name'],
        'arguments': fn['arguments'],
      });
    }
  }
  if (calls.length != answered.length) aiFailure('工具结果尚未齐全。');
  return result;
}

AgentReply agentReplyFromResponses(
  List<Map<String, dynamic>> items,
  String accountId, {
  Map<String, dynamic>? usage,
  Set<String>? allowedTools,
}) {
  final text = StringBuffer();
  final calls = <Map<String, dynamic>>[];
  for (final item in items) {
    switch (item['type']) {
      case 'reasoning':
        break;
      case 'function_call':
        if (item['status'] != null && item['status'] != 'completed' ||
            item['call_id'] is! String ||
            item['name'] is! String ||
            item['arguments'] is! String ||
            item['namespace'] != null &&
                item['namespace'] != ledgerToolNamespace ||
            allowedTools != null && !allowedTools.contains(item['name'])) {
          aiFailure('ChatGPT工具调用无效或不在注册范围。');
        }
        calls.add({
          'id': item['call_id'],
          'type': 'function',
          'function': {'name': item['name'], 'arguments': item['arguments']},
        });
      case 'message':
        if (item['role'] != 'assistant' ||
            item['status'] != 'completed' ||
            item['content'] is! List) {
          aiFailure('ChatGPT消息未完整完成。');
        }
        for (final raw in item['content'] as List) {
          final part = agentMap(raw);
          if (part['type'] != 'output_text' || part['text'] is! String) {
            aiFailure('ChatGPT没有返回有效文本。');
          }
          text.write(part['text']);
        }
      default:
        aiFailure('ChatGPT返回了未授权的输出类型。');
    }
  }
  if (calls.isEmpty && text.toString().trim().isEmpty) {
    aiFailure('ChatGPT响应没有有效内容。');
  }
  return AgentReply(
    message: {
      'role': 'assistant',
      'content': text.toString(),
      if (calls.isNotEmpty) 'tool_calls': calls,
      'responsesItems': items,
      'responsesAccountId': accountId,
    },
    inputTokens: usage?['input_tokens'] as int?,
    outputTokens: usage?['output_tokens'] as int?,
  );
}

List<Map<String, dynamic>> responsesAgentTools(
  List<Map<String, dynamic>> definitions,
) => definitions.isEmpty
    ? []
    : [
        {
          'type': 'namespace',
          'name': ledgerToolNamespace,
          'description': 'PennyPilot账本、记忆与任务工具',
          'tools': definitions.map((t) {
            final function = agentMap(t['function']);
            return {'type': 'function', ...function, 'strict': false};
          }).toList(),
        },
      ];

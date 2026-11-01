import 'dart:convert';

import '../models.dart';

/// The model transcript contains protocol messages; the reader sees one task
/// with its results. Stable run ids keep receipts attached to their own turn.
List<ChatMessage> presentAgentSession(Map<String, dynamic> session) {
  final raw = (session['messages'] as List? ?? [])
      .whereType<Map>()
      .map((m) => Map<String, dynamic>.from(m))
      .toList();
  final turns = <String, _Turn>{};
  var current = '';
  final sessionId = session['id'] ?? 'local';
  for (var i = 0; i < raw.length; i++) {
    final m = raw[i];
    if (m['internal'] == true) continue;
    final role = m['role'];
    if (role == 'user') {
      current = m['runId'] as String? ?? '$sessionId:turn:$i';
      turns[current] = _Turn(current, {
        ...m,
        'id': m['id'] ?? '$sessionId:message:$i',
      });
    }
    final key = m['runId'] as String? ?? current;
    if (key.isEmpty) continue;
    final turn = turns.putIfAbsent(key, () => _Turn(key, null));
    if (role == 'assistant') {
      if (m['provisional'] != true &&
          (m['tool_calls'] as List? ?? []).isEmpty) {
        turn.answer = m;
      }
      final parts = (m['parts'] as List? ?? [])
          .whereType<Map>()
          .map((p) => Map<String, dynamic>.from(p))
          .where((p) => m['provisional'] != true || p['type'] == 'reasoning')
          .toList();
      turn.parts.addAll(parts);
      // Older conversations kept public provider reasoning only in the wire
      // message. Restore it without ever reading opaque Responses items.
      if (!parts.any((p) => p['type'] == 'reasoning')) {
        final summary = m['reasoning_summary'] as String?;
        final reasoning = summary ?? m['reasoning_content'] as String?;
        if (reasoning != null && reasoning.isNotEmpty) {
          turn.parts.add({
            'type': 'reasoning',
            'id': '${m['id'] ?? '$sessionId:message:$i'}:reasoning',
            'messageId': m['id'],
            'runId': key,
            'text': reasoning,
            'kind': summary != null ? 'summary' : 'reasoning_content',
            'status': 'completed',
          });
        }
      }
    } else if (role == 'tool') {
      final content = m['content'];
      if (content is! String) continue;
      try {
        final result = jsonDecode(content);
        if (result is Map) {
          turn.results[m['tool_call_id'] as String] = Map<String, dynamic>.from(
            result,
          );
        }
      } on FormatException {
        // Legacy interrupted tool records can contain a plain-text reason.
      }
    }
  }
  final last = turns.values.lastOrNull;
  final legacy = !raw.any((m) => m['runId'] != null);
  for (final trace in (session['trace'] as List? ?? []).whereType<Map>()) {
    final turn =
        turns[trace['runId']] ??
        turns.values
            .where((t) => t.results.containsKey(trace['callId']))
            .firstOrNull ??
        (trace['runId'] == null || legacy ? last : null);
    if (turn == null) continue;
    final result = turn.results[trace['callId']];
    turn.parts.add({
      'type': 'tool',
      ...Map<String, dynamic>.from(trace),
      'result': ?result,
    });
  }
  for (final action in (session['actions'] as List? ?? []).whereType<Map>()) {
    final turn = action['runId'] == null ? last : turns[action['runId']];
    turn?.parts.add({'type': 'action', ...Map<String, dynamic>.from(action)});
  }
  final messages = <ChatMessage>[];
  for (final turn in turns.values) {
    final user = turn.user;
    if (user != null) {
      messages.add(
        ChatMessage(
          id: user['id'] as String,
          runId: turn.id,
          role: 'user',
          text: user['content'] as String? ?? '',
          createdAt: user['createdAt'] as int?,
          parts: (user['parts'] as List? ?? [])
              .whereType<Map>()
              .map((p) => Map<String, dynamic>.from(p))
              .followedBy(
                (user['attachments'] as List? ?? []).whereType<Map>().map(
                  (p) => {
                    'type': 'attachment',
                    ...Map<String, dynamic>.from(p),
                  },
                ),
              )
              .toList(),
        ),
      );
    }
    for (final result in turn.results.values) {
      final p = result['presentation'];
      if (p is Map) {
        turn.parts.add({
          'type': p['type'],
          'spec': Map<String, dynamic>.from(p),
        });
      }
    }
    final answer = turn.answer;
    if (answer == null && turn.parts.isEmpty) continue;
    final visualIds = <String>{};
    final parts = <Map<String, dynamic>>[];
    for (final part in turn.parts) {
      if (part['type'] == 'chart' || part['type'] == 'artifact') {
        final spec = part['spec'] is Map ? stringMap(part['spec']) : part;
        final id = spec['id'] as String? ?? jsonEncode(spec);
        if (visualIds.add(id)) parts.add({'type': spec['type'], 'spec': spec});
      } else {
        parts.add(part);
      }
    }
    messages.add(
      ChatMessage(
        id: '${turn.id}:answer',
        runId: turn.id,
        role: 'assistant',
        text: answer?['content'] as String? ?? '',
        isError: answer?['isError'] == true,
        status: identical(turn, last)
            ? session['status'] as String? ?? 'complete'
            : answer?['isError'] == true
            ? 'error'
            : 'complete',
        createdAt: answer?['createdAt'] as int?,
        parts: parts,
        chartData:
            answer?['chartData'] is Map &&
                !turn.parts.any((p) => p['type'] == 'chart')
            ? InsightChart.fromMap(stringMap(answer!['chartData']))
            : null,
      ),
    );
  }
  return messages;
}

class _Turn {
  _Turn(this.id, this.user);
  final String id;
  final Map<String, dynamic>? user;
  Map<String, dynamic>? answer;
  final parts = <Map<String, dynamic>>[];
  final results = <String, Map<String, dynamic>>{};
}

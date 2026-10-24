import 'dart:convert';
import 'dart:math';

import '../ledger_repository.dart';

String agentId() {
  final random = Random.secure();
  return '${DateTime.now().microsecondsSinceEpoch.toRadixString(36)}-${List.generate(12, (_) => random.nextInt(256).toRadixString(16).padLeft(2, '0')).join()}';
}

typedef AgentComplete =
    Future<AgentReply> Function(
      List<Map<String, dynamic>> messages,
      List<Map<String, dynamic>> tools,
    );

class AgentModel {
  const AgentModel({required this.identity, required this.complete});
  // Public identity only. Never put credentials into persisted state.
  final String identity;
  final AgentComplete complete;
}

class AgentReply {
  const AgentReply({
    required this.message,
    this.inputTokens,
    this.outputTokens,
  });
  final Map<String, dynamic> message;
  final int? inputTokens, outputTokens;
  List<Map<String, dynamic>> get calls =>
      (message['tool_calls'] as List? ?? []).map(agentMap).toList();
  String get text => message['content'] as String? ?? '';
}

Map<String, dynamic> agentMap(Object? value) {
  if (value is! Map) {
    throw const LedgerException('Agent 数据格式无效。', code: 'AGENT_PROTOCOL');
  }
  return value.map((key, value) => MapEntry(key.toString(), value));
}

class AgentConfig {
  const AgentConfig({this.contextTokens = 24000, this.maxSteps = 20});
  final int contextTokens, maxSteps;
  int get reserveTokens => (contextTokens ~/ 4).clamp(2000, 6000);
  int get maxToolCalls => maxSteps * 4;
  Map<String, dynamic> toMap() => {
    'contextTokens': contextTokens,
    'maxSteps': maxSteps,
  };
  factory AgentConfig.fromMap(Map<String, dynamic> map) {
    final tokens = map['contextTokens'] ?? 24000;
    final steps = map['maxSteps'] ?? 20;
    if (tokens is! int ||
        tokens < 8000 ||
        tokens > 128000 ||
        steps is! int ||
        steps < 4 ||
        steps > 40) {
      throw const LedgerException('上下文预算须为 8000～128000，执行步数须为 4～40。');
    }
    return AgentConfig(contextTokens: tokens, maxSteps: steps);
  }
}

class AgentSession {
  AgentSession({
    required this.id,
    required this.revision,
    required this.createdAt,
    required this.updatedAt,
    this.title = '新对话',
    this.summary = '',
    this.contextStart = 0,
    this.status = 'idle',
    this.modelIdentity = '',
    this.estimatedTokens = 0,
    this.inputTokens = 0,
    this.outputTokens = 0,
    List<Map<String, dynamic>>? messages,
    List<Map<String, dynamic>>? actions,
    List<Map<String, dynamic>>? trace,
    List<Map<String, dynamic>>? plan,
  }) : messages = messages ?? [],
       actions = actions ?? [],
       trace = trace ?? [],
       plan = plan ?? [];

  factory AgentSession.create(int revision, DateTime now) => AgentSession(
    id: agentId(),
    revision: revision,
    createdAt: now.millisecondsSinceEpoch,
    updatedAt: now.millisecondsSinceEpoch,
  );

  final String id;
  final int revision, createdAt;
  int updatedAt, contextStart, estimatedTokens, inputTokens, outputTokens;
  String title, summary, status, modelIdentity;
  final List<Map<String, dynamic>> messages, actions, trace, plan;

  Map<String, dynamic> toMap() => {
    'id': id,
    'revision': revision,
    'createdAt': createdAt,
    'updatedAt': updatedAt,
    'title': title,
    'summary': summary,
    'contextStart': contextStart,
    'status': status,
    'modelIdentity': modelIdentity,
    'estimatedTokens': estimatedTokens,
    'inputTokens': inputTokens,
    'outputTokens': outputTokens,
    'messages': messages,
    'actions': actions,
    'trace': trace,
    'plan': plan,
  };

  factory AgentSession.fromMap(Map<String, dynamic> map) => AgentSession(
    id: map['id'] as String,
    revision: map['revision'] as int,
    createdAt: map['createdAt'] as int,
    updatedAt: map['updatedAt'] as int,
    title: map['title'] as String? ?? '新对话',
    summary: map['summary'] as String? ?? '',
    contextStart: map['contextStart'] as int? ?? 0,
    status: map['status'] as String? ?? 'idle',
    modelIdentity: map['modelIdentity'] as String? ?? '',
    estimatedTokens: map['estimatedTokens'] as int? ?? 0,
    inputTokens: map['inputTokens'] as int? ?? 0,
    outputTokens: map['outputTokens'] as int? ?? 0,
    messages: (map['messages'] as List? ?? []).map(agentMap).toList(),
    actions: (map['actions'] as List? ?? []).map(agentMap).toList(),
    trace: (map['trace'] as List? ?? []).map(agentMap).toList(),
    plan: (map['plan'] as List? ?? []).map(agentMap).toList(),
  );

  Map<String, dynamic> get listing => {
    'id': id,
    'title': title,
    'updatedAt': updatedAt,
    'status': status,
  };
}

class AgentCancellation {
  bool _cancelled = false;
  LedgerException _reason = const LedgerException(
    '已停止本次任务。',
    code: 'AGENT_CANCELLED',
  );
  final _listeners = <void Function()>[];
  void cancel([LedgerException? reason]) {
    if (_cancelled) return;
    if (reason != null) _reason = reason;
    _cancelled = true;
    for (final listener in List.of(_listeners)) {
      listener();
    }
    _listeners.clear();
  }

  void listen(void Function() listener) {
    if (_cancelled) {
      listener();
    } else {
      _listeners.add(listener);
    }
  }

  void unlisten(void Function() listener) => _listeners.remove(listener);
  void check() {
    if (_cancelled) {
      throw _reason;
    }
  }
}

String agentJson(Object? value) => jsonEncode(value);

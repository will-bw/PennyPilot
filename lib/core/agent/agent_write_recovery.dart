part of 'agent_runtime.dart';

/// Reconciles a committed insert when its separate Agent checkpoint was lost.
/// The ledger is read-only here. An unverified write stays uncertain.
bool _reconcileAgentWriteReceipts(AgentSession session, LedgerSnapshot ledger) {
  if (session.revision != ledger.revision) return false;

  final toolResults = {
    for (final message in session.messages)
      if (message['role'] == 'tool') message['tool_call_id']: message,
  };
  Map<String, LedgerTransaction?>? transactionsByOperation;
  final recovered = <Map<String, dynamic>>[];
  var changed = false;
  for (final action in session.actions) {
    final status = action['status'];
    final payload = action['payload'];
    if (action['kind'] != 'transaction' ||
        action['revision'] != session.revision ||
        !const {'applying', 'uncertain', 'applied'}.contains(status) ||
        payload is! Map ||
        payload['before'] != null) {
      continue;
    }
    final toolCallId = action['toolCallId'];
    final result = toolResults[toolCallId];
    final canRepairResult =
        toolCallId is String &&
        (result == null ||
            _recoveryJsonObject(result['content'])?['error'] == 'interrupted');
    if (status == 'applied' && !canRepairResult) continue;

    final draft = payload['transaction'];
    if (draft is! Map || draft['id'] != 0) continue;
    final metadata = _recoveryJsonObject(draft['metadataJson']);
    final operationId = metadata?['operationId'];
    if (operationId is! String || operationId.isEmpty) continue;

    final needsResult = metadata?['autoRecorded'] == true && canRepairResult;
    if (status == 'applied' && !needsResult) continue;

    transactionsByOperation ??= _indexRecoveryTransactions(ledger.transactions);
    final match = transactionsByOperation[operationId];
    final savedId = action['transactionId'];
    if (match == null ||
        match.source.wireValue != draft['source'] ||
        (savedId != null && savedId != match.id)) {
      continue;
    }

    if (status != 'applied' || savedId == null) {
      action['status'] = 'applied';
      action['transactionId'] = match.id;
      if (status != 'applied') recovered.add(action);
      changed = true;
    }
    if (needsResult &&
        _recoverAutomaticToolResult(session, action, match, result)) {
      changed = true;
    }
  }

  if (session.status == 'awaiting_confirmation' && recovered.isNotEmpty) {
    final latest = session.messages
        .where((m) => m['internal'] != true && m['actionReceipt'] == null)
        .lastOrNull?['runId'];
    if (latest != null) {
      final linked = session.messages
          .where((m) => m['runId'] == latest)
          .map((m) => m['actionId'])
          .whereType<String>()
          .toSet();
      bool belongsToLatest(Map<String, dynamic> action) =>
          action['runId'] == latest || linked.contains(action['id']);
      if (recovered.any(belongsToLatest) &&
          !session.actions.any(
            (action) =>
                belongsToLatest(action) &&
                const {
                  'pending',
                  'applying',
                  'uncertain',
                }.contains(action['status']),
          )) {
        session.status = 'completed';
      }
    }
  }
  return changed;
}

Map<String, LedgerTransaction?> _indexRecoveryTransactions(
  List<LedgerTransaction> transactions,
) {
  final rows = <String, LedgerTransaction?>{};
  for (final row in transactions) {
    // Some older snapshots don't expose the ledger's operation key. Edited
    // metadata cannot prove which operation originally inserted that row.
    final key =
        row.operationKey ??
        (row.editVersion == 0 ? row.recognitionMetadata['operationId'] : null);
    if (row.id > 0 && key is String && key.isNotEmpty) {
      rows[key] = rows.containsKey(key) ? null : row;
    }
  }
  return rows;
}

bool _recoverAutomaticToolResult(
  AgentSession session,
  Map<String, dynamic> action,
  LedgerTransaction transaction,
  Map<String, dynamic>? existing,
) {
  final callId = action['toolCallId'];
  final runId = action['runId'];
  if (callId is! String || runId is! String) return false;

  final callIndex = session.messages.indexWhere(
    (message) =>
        message['role'] == 'assistant' &&
        message['runId'] == runId &&
        (message['tool_calls'] as List? ?? const []).whereType<Map>().any(
          (call) =>
              call['id'] == callId &&
              call['function'] is Map &&
              (call['function'] as Map)['name'] == 'propose_transaction',
        ),
  );
  if (callIndex == -1) return false;

  final content = jsonEncode({
    'status': 'applied',
    'action_id': action['id'],
    'transaction_id': transaction.id,
    'ledger_revision': session.revision,
    'instruction': '已从账本核对到这笔新增，无需重复保存。如需当前账单详情，请重新查询。',
  });
  if (existing == null) {
    // Keep all results from the same assistant turn together on the wire.
    var insertAt = callIndex + 1;
    while (insertAt < session.messages.length &&
        session.messages[insertAt]['role'] == 'tool') {
      insertAt++;
    }
    session.messages.insert(insertAt, {
      'id': agentId(),
      'runId': runId,
      'role': 'tool',
      'tool_call_id': callId,
      'content': content,
    });
  } else {
    existing['runId'] = runId;
    existing['content'] = content;
  }
  for (final trace in session.trace.where(
    (entry) => entry['callId'] == callId && entry['runId'] == runId,
  )) {
    trace['status'] = 'completed';
  }
  return true;
}

Map<String, dynamic>? _recoveryJsonObject(Object? content) {
  if (content is! String) return null;
  try {
    final decoded = jsonDecode(content);
    return decoded is Map<String, dynamic> ? decoded : null;
  } on FormatException {
    return null;
  }
}

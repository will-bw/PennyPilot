import 'dart:convert';

import '../models.dart';
import 'agent_models.dart';
import 'agent_store.dart';

class LedgerAgentTools {
  LedgerAgentTools({
    required this.store,
    required this.session,
    required this.ledger,
    required this.question,
    required this.now,
  }) {
    for (final trace in session.trace) {
      final id = trace['evidenceId'] as String?;
      final number = id == null ? 0 : int.tryParse(id.substring(1)) ?? 0;
      if (number > _evidence) _evidence = number;
    }
  }
  final AgentStore store;
  final AgentSession session;
  final LedgerSnapshot ledger;
  final String question;
  final DateTime now;
  int _evidence = 0;
  bool hasLedgerEvidence = false;
  final _readTransactions = <int>{};

  static Map<String, dynamic> _s(String description, {List<String>? values}) =>
      {'type': 'string', 'description': description, 'enum': ?values};
  static Map<String, dynamic> _i(String description) => {
    'type': 'integer',
    'description': description,
  };
  static Map<String, dynamic> _tool(
    String name,
    String description,
    Map<String, dynamic> properties, [
    List<String> required = const [],
  ]) => {
    'type': 'function',
    'function': {
      'name': name,
      'description': description,
      'parameters': {
        'type': 'object',
        'properties': properties,
        'required': required,
        'additionalProperties': false,
      },
    },
  };
  static Map<String, dynamic> get _range => {
    'start': _s('开始日期 YYYY-MM-DD'),
    'end': _s('结束日期 YYYY-MM-DD，包含结束日'),
  };
  static final definitions = <Map<String, dynamic>>[
    _tool(
      'ask_user',
      '缺少金额、日期、方向或必须由用户选择时暂停，提出一个清楚的问题；用户回复后继续同一任务。',
      {'question': _s('需要用户补充的问题，最多1000字')},
      ['question'],
    ),
    _tool('get_ledger_overview', '读取分类标识、账单覆盖范围与数量，不包含完整流水。', {}),
    _tool(
      'query_transactions',
      '分页查交易，含id、方向、用途、状态；金额单位分。不能以一页明细推算总额。',
      {
        ..._range,
        'category_id': _i('分类id，可选'),
        'keyword': _s('商户或备注关键词'),
        'type': _s('资金方向', values: ['EXPENSE', 'INCOME']),
        'status': _s(
          '默认CONFIRMED',
          values: ['CONFIRMED', 'PENDING', 'IGNORED', 'ALL'],
        ),
        'offset': _i('分页起点，默认0'),
        'limit': _i('每页1～50，默认20'),
      },
      ['start', 'end'],
    ),
    _tool(
      'get_transaction',
      '读取一笔交易的完整可编辑字段与版本。',
      {'transaction_id': _i('真实交易id')},
      ['transaction_id'],
    ),
    _tool(
      'aggregate_by',
      '完整范围统计收支，退款冲减支出，互转借还款不计消费；分组支持分类/天/月/商户。',
      {
        ..._range,
        'group_by': _s('分组', values: ['category', 'day', 'month', 'merchant']),
        'category_id': _i('分类id，可选'),
        'keyword': _s('商户或备注关键词'),
        'offset': _i('分组分页起点，默认0'),
        'limit': _i('返回分组1～100，默认30'),
      },
      ['start', 'end', 'group_by'],
    ),
    _tool(
      'compare_periods',
      '比较两个完整范围的收入与净支出，给出差额、变化率和分类贡献。',
      {
        'period_a_start': _s('A开始日期'),
        'period_a_end': _s('A结束日期，含当天'),
        'period_b_start': _s('B开始日期'),
        'period_b_end': _s('B结束日期，含当天'),
        'category_id': _i('分类id，可选'),
        'keyword': _s('商户或备注关键词'),
      },
      ['period_a_start', 'period_a_end', 'period_b_start', 'period_b_end'],
    ),
    _tool('get_budget', '读取当前月度预算设置与指定月份的已确认净支出、余额和剩余天数。', {
      'month': _s('YYYY-MM，默认本月'),
    }),
    _tool(
      'search_memory',
      '搜索长期偏好、目标和用户事实；不是账本金额来源。空关键词返回最近记忆。',
      {'query': _s('关键词，空字符串表示最近记忆')},
      ['query'],
    ),
    _tool(
      'remember',
      '保存用户明确表达的稳定事实/偏好/目标，同key覆盖；evidence须逐字出自当前用户消息。禁止保存凭据或临时账本统计。',
      {
        'key': _s('稳定语义key，如 budget_goal'),
        'content': _s('简洁记忆，最多500字'),
        'kind': _s('记忆类型', values: ['preference', 'goal', 'fact']),
        'evidence': _s('当前用户消息中支持记忆的原话'),
      },
      ['key', 'content', 'kind', 'evidence'],
    ),
    _tool(
      'forget_memory',
      '按search_memory返回的key删除长期记忆。',
      {'key': _s('记忆key')},
      ['key'],
    ),
    _tool(
      'search_conversation',
      '在本地所有同代次对话原文中搜索，恢复被压缩的目标、约束与查询记录。',
      {'query': _s('检索词')},
      ['query'],
    ),
    _tool(
      'read_conversation',
      '按搜索结果的session_id和message_index读取原记录附近内容。',
      {'session_id': _s('会话id'), 'message_index': _i('消息序号')},
      ['session_id', 'message_index'],
    ),
    _tool(
      'update_plan',
      '建立或更新公开任务计划，不含内部思维链；最多8步。',
      {
        'steps': {
          'type': 'array',
          'minItems': 1,
          'maxItems': 8,
          'items': {
            'type': 'object',
            'additionalProperties': false,
            'properties': {
              'title': _s('可执行步骤'),
              'status': _s(
                '状态',
                values: ['pending', 'in_progress', 'completed'],
              ),
            },
            'required': ['title', 'status'],
          },
        },
      },
      ['steps'],
    ),
    _tool(
      'propose_transaction',
      '准备新增或修改账单提案，等待用户界面确认。修改前先get_transaction；没有金额/日期/方向依据则先询问。',
      {
        'transaction_id': _i('修改目标id；新增时不传'),
        'amount': _i('正整数分'),
        'type': _s('方向', values: ['EXPENSE', 'INCOME']),
        'category_id': _i('真实分类id'),
        'date': _s('实际发生日期 YYYY-MM-DD，不能是未来日期'),
        'business_kind': _s(
          '用途',
          values: [
            'expense',
            'income',
            'transfer',
            'receivable',
            'payable',
            'refund',
          ],
        ),
        'merchant': _s('商户，可选'),
        'note': _s('备注，可选'),
        'evidence': _s('当前用户请求中支持这次修改的原话'),
      },
      ['amount', 'type', 'category_id', 'date', 'business_kind', 'evidence'],
    ),
    _tool(
      'propose_delete_transaction',
      '提出删除单笔账单的提案，等待用户核对；需先get_transaction读取原记录。',
      {'transaction_id': _i('真实交易id'), 'evidence': _s('当前用户删除请求原话')},
      ['transaction_id', 'evidence'],
    ),
    _tool(
      'propose_budget',
      '提出修改全局月度预算的提案，金额为整数分；等待用户界面确认。',
      {'amount': _i('月预算整数分，0表示取消预算'), 'evidence': _s('当前用户预算设置请求原话')},
      ['amount', 'evidence'],
    ),
  ];

  static final ledgerReads = {
    'get_ledger_overview',
    'query_transactions',
    'get_transaction',
    'aggregate_by',
    'compare_periods',
    'get_budget',
  };

  static void validate(String name, Map<String, dynamic> args) {
    final tool = definitions
        .where((t) => (t['function'] as Map)['name'] == name)
        .firstOrNull;
    if (tool == null) throw const FormatException('unknown_tool：请使用注册列表中的工具名称');
    final schema = (tool['function'] as Map)['parameters'] as Map;
    void check(Object? value, Map rule, String field) {
      final type = rule['type'];
      if (type == 'string' && value is! String ||
          type == 'integer' && value is! int ||
          type == 'array' && value is! List ||
          type == 'object' && value is! Map) {
        throw FormatException('$field 的类型须为 $type');
      }
      if (rule['enum'] is List && !(rule['enum'] as List).contains(value)) {
        throw FormatException('$field 必须为 ${rule['enum']} 中的值');
      }
      if (value is String && value.length > 2000) {
        throw FormatException('$field 过长');
      }
      if (type == 'object') {
        final map = value as Map;
        final props = rule['properties'] as Map;
        for (final key in rule['required'] as List? ?? []) {
          if (!map.containsKey(key)) {
            throw FormatException('缺少必填参数 $field.$key');
          }
        }
        for (final key in map.keys) {
          if (!props.containsKey(key)) {
            throw FormatException('未知参数 $field.$key');
          }
          check(map[key], props[key] as Map, '$field.$key');
        }
      }
      if (type == 'array') {
        final list = value as List;
        if (list.isEmpty || list.length > 8) {
          throw FormatException('$field 须为1～8项');
        }
        for (final item in list) {
          check(item, rule['items'] as Map, field);
        }
      }
    }

    check(args, schema, name);
  }

  Future<Map<String, dynamic>> execute(
    String name,
    Map<String, dynamic> args,
  ) async {
    validate(name, args);
    final data = await _execute(name, args);
    if (ledgerReads.contains(name)) {
      hasLedgerEvidence = true;
      return {
        'evidence_id': 'Q${++_evidence}',
        'as_of': now.toIso8601String(),
        'ledger_revision': ledger.revision,
        'data': data,
      };
    }
    return data;
  }

  Future<Map<String, dynamic>> _execute(
    String name,
    Map<String, dynamic> a,
  ) async {
    switch (name) {
      case 'ask_user':
        final question = (a['question'] as String).trim();
        if (question.isEmpty || question.length > 1000) {
          throw const FormatException('问题须为1～1000字');
        }
        return {'status': 'awaiting_input', 'question': question};
      case 'get_ledger_overview':
        final dates = ledger.transactions.map((t) => t.occurredAt).toList()
          ..sort();
        return {
          'categories': ledger.categories
              .map(
                (c) => {
                  'id': c.id,
                  'name': c.name,
                  'type': c.type.wireValue,
                  'enabled': c.enabled,
                },
              )
              .toList(),
          'transaction_count': dates.length,
          'first_date': dates.isEmpty ? null : day(dates.first),
          'last_date': dates.isEmpty ? null : day(dates.last),
          'default_status': 'CONFIRMED',
        };
      case 'get_transaction':
        final tx = _transaction(a);
        _readTransactions.add(tx.id);
        return _row(tx, full: true);
      case 'query_transactions':
        final rows = _filter(a)
          ..sort((x, y) => y.occurredAt.compareTo(x.occurredAt));
        final offset = _int(a, 'offset', 0, 0, 100000000);
        final limit = _int(a, 'limit', 20, 1, 50);
        final page = rows.skip(offset).take(limit).map((t) => _row(t)).toList();
        while (page.length > 1 && jsonEncode(page).length > 14000) {
          page.removeLast();
        }
        return {
          'start': a['start'],
          'end': a['end'],
          'status': a['status'] ?? 'CONFIRMED',
          'total_count': rows.length,
          'offset': offset,
          'transactions': page,
          'truncated': offset + page.length < rows.length,
          'next_offset': offset + page.length < rows.length
              ? offset + page.length
              : null,
        };
      case 'aggregate_by':
        final rows = _filter(a);
        final groups = <String, Map<String, dynamic>>{};
        for (final tx in rows) {
          final label = switch (a['group_by']) {
            'day' => day(tx.occurredAt),
            'month' => day(tx.occurredAt).substring(0, 7),
            'merchant' => tx.merchant ?? '未填写商户',
            _ =>
              ledger.categories
                      .where((c) => c.id == tx.categoryId)
                      .firstOrNull
                      ?.name ??
                  '其他',
          };
          final key = a['group_by'] == 'category' ? '${tx.categoryId}' : label;
          final group = groups.putIfAbsent(
            key,
            () => {
              'label': label,
              if (a['group_by'] == 'category') 'category_id': tx.categoryId,
              'expense': 0,
              'income': 0,
              'count': 0,
            },
          );
          group['expense'] = (group['expense'] as int) + _expense(tx);
          group['income'] =
              (group['income'] as int) + (tx.countsAsIncome ? tx.amountFen : 0);
          group['count'] = (group['count'] as int) + 1;
        }
        final sorted = groups.values.toList()
          ..sort(
            (x, y) => a['group_by'] == 'day' || a['group_by'] == 'month'
                ? (x['label'] as String).compareTo(y['label'] as String)
                : (y['expense'] as int).compareTo(x['expense'] as int),
          );
        final offset = _int(a, 'offset', 0, 0, 100000000);
        final page = sorted
            .skip(offset)
            .take(_int(a, 'limit', 30, 1, 100))
            .toList();
        return {
          ..._totals(rows),
          'start': a['start'],
          'end': a['end'],
          'status': 'CONFIRMED',
          'group_by': a['group_by'],
          'group_count': sorted.length,
          'groups': page,
          'next_offset': offset + page.length < sorted.length
              ? offset + page.length
              : null,
        };
      case 'compare_periods':
        final first = _filter({
          ...a,
          'start': a['period_a_start'],
          'end': a['period_a_end'],
        });
        final second = _filter({
          ...a,
          'start': a['period_b_start'],
          'end': a['period_b_end'],
        });
        final totalA = _totals(first), totalB = _totals(second);
        final expenseA = totalA['expense'] as int,
            expenseB = totalB['expense'] as int;
        final changes =
            ledger.categories
                .map((c) {
                  final av = first
                      .where((t) => t.categoryId == c.id)
                      .fold(0, (s, t) => s + _expense(t));
                  final bv = second
                      .where((t) => t.categoryId == c.id)
                      .fold(0, (s, t) => s + _expense(t));
                  return {
                    'category_id': c.id,
                    'category': c.name,
                    'a_expense': av,
                    'b_expense': bv,
                    'change': bv - av,
                  };
                })
                .where((c) => c['a_expense'] != 0 || c['b_expense'] != 0)
                .toList()
              ..sort(
                (x, y) => (y['change'] as int).compareTo(x['change'] as int),
              );
        return {
          'a': {
            'start': a['period_a_start'],
            'end': a['period_a_end'],
            ...totalA,
          },
          'b': {
            'start': a['period_b_start'],
            'end': a['period_b_end'],
            ...totalB,
          },
          'expense_change': expenseB - expenseA,
          'expense_change_percent': expenseA == 0
              ? null
              : (expenseB - expenseA) * 100 / expenseA,
          'category_changes': changes.take(50).toList(),
          'status': 'CONFIRMED',
        };
      case 'get_budget':
        final month = a['month'] as String? ?? day(now).substring(0, 7);
        if (!RegExp(r'^\d{4}-(0[1-9]|1[0-2])$').hasMatch(month)) {
          throw const FormatException('month须为YYYY-MM');
        }
        final start = parseDay('$month-01');
        final end = DateTime(start.year, start.month + 1, 0);
        final expense =
            _totals(_filter({'start': day(start), 'end': day(end)}))['expense']
                as int;
        final budget = ledger.settings['budgetCents'] as int? ?? 0;
        return {
          'month': month,
          'budget': budget,
          'expense': expense,
          'remaining': budget == 0 ? null : budget - expense,
          'remaining_days': month == day(now).substring(0, 7)
              ? end.day - now.day + 1
              : null,
          'budget_scope': '当前全局月预算设置，不代表历史月份当时的预算',
          'status': 'CONFIRMED',
        };
      case 'search_memory':
        final query = (a['query'] as String).toLowerCase();
        final memories = await store.scan('memory:${ledger.revision}:');
        memories.sort(
          (x, y) => (y['updatedAt'] as int).compareTo(x['updatedAt'] as int),
        );
        return {
          'memories': memories
              .where(
                (m) =>
                    '${m['key']} ${m['content']}'.toLowerCase().contains(query),
              )
              .take(20)
              .toList(),
        };
      case 'remember':
        _evidenceQuote(a);
        final key = (a['key'] as String).trim(),
            content = (a['content'] as String).trim();
        if (key.isEmpty ||
            key.length > 100 ||
            content.isEmpty ||
            content.length > 500 ||
            RegExp(
              r'(?i:api.?key|bearer\s|password|密码|密钥|access.?token|refresh.?token|sk-[a-zA-Z0-9]{8})',
            ).hasMatch('$key $content ${a['evidence']}')) {
          throw const FormatException(
            '记忆须为稳定偏好/目标/事实，key最多100字、正文最多500字，不能包含凭据',
          );
        }
        final memory = {
          'key': key,
          'content': content,
          'kind': a['kind'],
          'evidence': a['evidence'],
          'sessionId': session.id,
          'updatedAt': now.millisecondsSinceEpoch,
        };
        await store.write('memory:${ledger.revision}:$key', memory);
        return {'status': 'remembered', 'memory': memory};
      case 'forget_memory':
        await store.remove('memory:${ledger.revision}:${a['key']}');
        return {'status': 'forgotten', 'key': a['key']};
      case 'search_conversation':
        final query = (a['query'] as String).trim().toLowerCase();
        if (query.isEmpty) throw const FormatException('请提供检索词');
        final sessions = await store.scan('session:${ledger.revision}:');
        final matches = <Map<String, dynamic>>[];
        for (final saved in sessions) {
          final messages = saved['id'] == session.id
              ? session.messages
              : saved['messages'] as List;
          for (var index = 0; index < messages.length; index++) {
            final message = agentMap(messages[index]);
            final content = message['content'] as String? ?? '';
            final position = content.toLowerCase().indexOf(query);
            if (position >= 0) {
              final start = (position - 80).clamp(0, content.length);
              matches.add({
                'session_id': saved['id'],
                'title': saved['title'],
                'message_index': index,
                'role': message['role'],
                'excerpt': content.substring(
                  start,
                  (position + 600).clamp(start, content.length),
                ),
              });
            }
          }
        }
        return {'matches': matches.reversed.take(20).toList()};
      case 'read_conversation':
        final saved = a['session_id'] == session.id
            ? session.toMap()
            : await store.read('session:${ledger.revision}:${a['session_id']}');
        if (saved == null) throw const FormatException('会话不存在或不属于当前账本');
        final messages = saved['messages'] as List;
        final index = _int(a, 'message_index', 0, 0, messages.length - 1);
        return {
          'messages': [
            for (
              var i = (index - 1).clamp(0, index);
              i <= (index + 1).clamp(0, messages.length - 1);
              i++
            )
              {
                'index': i,
                'role': (messages[i] as Map)['role'],
                'content': _clip(
                  (messages[i] as Map)['content'] as String? ?? '',
                  4000,
                ),
              },
          ],
        };
      case 'update_plan':
        final steps = (a['steps'] as List).map(agentMap).toList();
        if (steps.where((s) => s['status'] == 'in_progress').length > 1 ||
            steps.any((s) => (s['title'] as String).length > 120)) {
          throw const FormatException('最多一个进行中的步骤，标题不超过120字');
        }
        session.plan
          ..clear()
          ..addAll(steps);
        return {'status': 'updated', 'steps': steps};
      case 'propose_budget':
        _evidenceQuote(a);
        final amount = _int(a, 'amount', -1, 0, maxTransactionFen);
        return _propose('budget', '月预算改为 ${formatMoney(amount)} 元', {
          'amount': amount,
          'before': ledger.settings['budgetCents'],
        });
      case 'propose_delete_transaction':
        _evidenceQuote(a);
        final tx = _transaction(a);
        _requireRead(tx.id);
        return _propose(
          'delete',
          '删除 ${day(tx.occurredAt)} ${tx.merchant ?? tx.businessLabel} ${formatMoney(tx.amountFen)} 元',
          {'transactionId': tx.id, 'before': tx.toMap()},
        );
      case 'propose_transaction':
        _evidenceQuote(a);
        final amount = _int(a, 'amount', -1, 1, maxTransactionFen);
        final type = TxType.fromWire(a['type']);
        final category = ledger.categories
            .where(
              (c) => c.id == a['category_id'] && c.enabled && c.type == type,
            )
            .firstOrNull;
        if (category == null) {
          throw const FormatException('分类不存在、未启用或与资金方向不一致，请先查询分类');
        }
        final date = parseDay(a['date'] as String);
        if (date.isAfter(DateTime(now.year, now.month, now.day))) {
          throw const FormatException('未来计划不能作为已发生账目');
        }
        final kind = a['business_kind'] as String;
        if (kind == 'expense' && type != TxType.expense ||
            (kind == 'income' || kind == 'refund') && type != TxType.income) {
          throw const FormatException('账务用途与方向不一致');
        }
        final old = a['transaction_id'] == null ? null : _transaction(a);
        if (old != null) _requireRead(old.id);
        final id = agentId();
        final payload = {
          'id': old?.id ?? 0,
          'amount': amount,
          'type': type.wireValue,
          'categoryId': category.id,
          'businessKind': kind,
          'occurredAt': old != null && day(old.occurredAt) == day(date)
              ? old.occurredAt.millisecondsSinceEpoch
              : date.millisecondsSinceEpoch,
          'merchant': a.containsKey('merchant')
              ? _text(a, 'merchant', 200)
              : old?.merchant,
          'note': a.containsKey('note') ? _text(a, 'note', 2000) : old?.note,
          'accountTag': old?.accountTag,
          'source': 'AI_TEXT',
          if (old != null) 'editVersion': old.editVersion,
          'metadataJson': jsonEncode({
            ...?old?.recognitionMetadata,
            'operationId': 'agent:$id',
            'agentSessionId': session.id,
            'evidence': a['evidence'],
          }),
        };
        return _propose(
          'transaction',
          '${old == null ? '新增' : '修改'} ${day(date)} ${category.name} ${formatMoney(amount)} 元',
          {'transaction': payload, if (old != null) 'before': old.toMap()},
          id: id,
        );
    }
    throw const FormatException('unknown_tool');
  }

  void _requireRead(int id) {
    if (!_readTransactions.contains(id)) {
      throw const FormatException('修改或删除前须先get_transaction读取目标与版本');
    }
  }

  void _evidenceQuote(Map<String, dynamic> a) {
    final evidence = (a['evidence'] as String).trim();
    if (evidence.length < 2 || !question.contains(evidence)) {
      throw const FormatException('evidence必须是当前用户请求中的逐字原文，不能来自商户备注或历史回答');
    }
  }

  Map<String, dynamic> _propose(
    String kind,
    String title,
    Map<String, dynamic> payload, {
    String? id,
  }) {
    final duplicate = session.actions
        .where(
          (p) =>
              p['status'] == 'pending' &&
              p['kind'] == kind &&
              jsonEncode(p['payload']) == jsonEncode(payload),
        )
        .firstOrNull;
    if (duplicate != null) {
      return {
        'status': 'awaiting_confirmation',
        'action_id': duplicate['id'],
        'title': duplicate['title'],
      };
    }
    final action = {
      'id': id ?? agentId(),
      'kind': kind,
      'title': title,
      'payload': payload,
      'status': 'pending',
      'revision': ledger.revision,
      'createdAt': now.millisecondsSinceEpoch,
    };
    session.actions.add(action);
    return {
      'status': 'awaiting_confirmation',
      'action_id': action['id'],
      'title': title,
      'instruction': '仅创建提案，尚未改变账本。等待用户在界面核对确认。',
    };
  }

  LedgerTransaction _transaction(Map<String, dynamic> a) =>
      ledger.transactions
          .where((t) => t.id == a['transaction_id'])
          .firstOrNull ??
      (throw const FormatException('交易不存在，请先查询真实id'));
  List<LedgerTransaction> _filter(Map<String, dynamic> a) {
    if (a['category_id'] != null &&
        !ledger.categories.any((c) => c.id == a['category_id'])) {
      throw const FormatException('分类id不存在，请先读取账本概况');
    }
    final start = parseDay(a['start'] as String),
        end = parseDay(a['end'] as String);
    if (end.isBefore(start)) throw const FormatException('结束日期不能早于开始日期');
    final exclusive = DateTime(end.year, end.month, end.day + 1);
    final keyword = (a['keyword'] as String?)?.toLowerCase();
    return ledger.transactions
        .where(
          (t) =>
              !t.occurredAt.isBefore(start) &&
              t.occurredAt.isBefore(exclusive) &&
              (a['category_id'] == null || t.categoryId == a['category_id']) &&
              (a['type'] == null || t.type.wireValue == a['type']) &&
              (a['status'] == 'ALL' ||
                  t.status.wireValue == (a['status'] ?? 'CONFIRMED')) &&
              (keyword == null ||
                  '${t.merchant ?? ''} ${t.note ?? ''}'.toLowerCase().contains(
                    keyword,
                  )),
        )
        .toList();
  }

  static int _expense(LedgerTransaction t) => t.isRefund
      ? -t.amountFen
      : t.countsAsExpense
      ? t.amountFen
      : 0;
  static Map<String, dynamic> _totals(List<LedgerTransaction> rows) => {
    'expense': rows.fold(0, (s, t) => s + _expense(t)),
    'income': rows.fold(0, (s, t) => s + (t.countsAsIncome ? t.amountFen : 0)),
    'count': rows.length,
    'unit': 'fen',
  };
  Map<String, dynamic> _row(LedgerTransaction t, {bool full = false}) => {
    'id': t.id,
    'amount': t.amountFen,
    'type': t.type.wireValue,
    'business_kind': t.effectiveKind,
    'category_id': t.categoryId,
    'category': ledger.categories
        .where((c) => c.id == t.categoryId)
        .firstOrNull
        ?.name,
    'date': t.occurredAt.toIso8601String(),
    'status': t.status.wireValue,
    'merchant': _clip(t.merchant, full ? 200 : 100),
    'note': _clip(t.note, full ? 2000 : 250),
    if (full) ...{'editVersion': t.editVersion, 'source': t.source.wireValue},
  };
  static String? _clip(String? value, int max) => value == null
      ? null
      : value.length <= max
      ? value
      : '${value.substring(0, max)}…';
  static int _int(Map a, String key, int fallback, int min, int max) {
    final value = a[key] ?? fallback;
    if (value is! int || value < min || value > max) {
      throw FormatException('$key须为$min～$max的整数');
    }
    return value;
  }

  static String? _text(Map a, String key, int max) {
    final value = a[key] as String?;
    if (value != null && value.length > max) {
      throw FormatException('$key过长，最多$max字');
    }
    return value;
  }

  static DateTime parseDay(String value) {
    if (!RegExp(r'^\d{4}-\d{2}-\d{2}$').hasMatch(value)) {
      throw const FormatException('日期须为YYYY-MM-DD');
    }
    final date = DateTime.tryParse(value);
    if (date == null ||
        date.year < 2000 ||
        date.year > 2100 ||
        day(date) != value) {
      throw const FormatException('日期不存在或超出2000～2100范围');
    }
    return date;
  }

  static String day(DateTime value) =>
      '${value.year.toString().padLeft(4, '0')}-${value.month.toString().padLeft(2, '0')}-${value.day.toString().padLeft(2, '0')}';
}

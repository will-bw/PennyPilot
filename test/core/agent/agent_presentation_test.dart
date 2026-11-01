import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/agent/agent_presentation.dart';

void main() {
  test(
    'tool results, actions and visualizations stay with their original turn',
    () {
      final messages = presentAgentSession({
        'id': 'session',
        'status': 'completed',
        'messages': [
          {'id': 'u1', 'runId': 'r1', 'role': 'user', 'content': '看看预算'},
          {
            'id': 'a1',
            'runId': 'r1',
            'role': 'assistant',
            'content': '预算如下',
            'parts': [
              {'id': 'v1', 'type': 'chart', 'title': '预算'},
            ],
          },
          {
            'role': 'tool',
            'runId': 'r1',
            'tool_call_id': 't1',
            'content': jsonEncode({
              'evidence_id': 'Q1',
              'data': {'budget': 300000},
              'presentation': {'id': 'v1', 'type': 'chart', 'title': '预算'},
            }),
          },
          {'id': 'u2', 'runId': 'r2', 'role': 'user', 'content': '你好'},
          {'id': 'a2', 'runId': 'r2', 'role': 'assistant', 'content': '你好'},
          {
            'role': 'assistant',
            'runId': 'r2',
            'content': '未采纳文本',
            'provisional': true,
          },
          {'role': 'user', 'runId': 'r2', 'content': '内部校验', 'internal': true},
        ],
        'trace': [
          {
            'runId': 'r1',
            'callId': 't1',
            'tool': 'get_budget',
            'status': 'completed',
          },
        ],
        'actions': [
          {'runId': 'r1', 'id': 'p1', 'status': 'applied', 'kind': 'budget'},
        ],
      });
      expect(messages.map((m) => m.text), ['看看预算', '预算如下', '你好', '你好']);
      expect(
        messages[1].parts.where((p) => p['type'] == 'chart'),
        hasLength(1),
      );
      expect(
        messages[1].parts.firstWhere((p) => p['type'] == 'chart')['spec']['id'],
        'v1',
      );
      expect(
        messages[1].parts.firstWhere(
          (p) => p['type'] == 'tool',
        )['result']['data']['budget'],
        300000,
      );
      expect(messages.last.parts, isEmpty);
    },
  );

  test('pre-upgrade trace run ids map to the matching legacy tool round', () {
    final messages = presentAgentSession({
      'id': 'old',
      'messages': [
        {'role': 'user', 'content': '查预算'},
        {
          'role': 'tool',
          'tool_call_id': 'old-call',
          'content': jsonEncode({
            'evidence_id': 'Q1',
            'data': {'budget': 20000},
          }),
        },
        {'role': 'assistant', 'content': '预算200元'},
        {'role': 'user', 'content': '你好'},
        {'role': 'assistant', 'content': '你好'},
      ],
      'trace': [
        {
          'runId': 'old-runtime-run',
          'callId': 'old-call',
          'tool': 'get_budget',
          'status': 'completed',
        },
      ],
    });
    expect(messages[1].parts.single['result']['data']['budget'], 20000);
    expect(messages.last.parts, isEmpty);
  });

  test(
    'legacy conversations preserve results and cancelled partial content',
    () {
      final messages = presentAgentSession({
        'id': 'legacy',
        'status': 'cancelled',
        'messages': [
          {
            'role': 'user',
            'content': '检查支出',
            'attachments': [
              {'path': '/local/image.png', 'mimeType': 'image/png'},
            ],
          },
          {
            'role': 'assistant',
            'content': '已停止',
            'isError': true,
            'parts': [
              {'type': 'interrupted_text', 'text': '已找到部分结果'},
            ],
          },
        ],
        'trace': [
          {
            'callId': 'old',
            'tool': 'query_transactions',
            'status': 'completed',
          },
        ],
        'actions': [
          {'id': 'old-action', 'status': 'rejected'},
        ],
      });
      expect(messages.first.parts.single['type'], 'attachment');
      expect(messages.last.status, 'cancelled');
      expect(messages.last.parts.map((p) => p['type']), [
        'interrupted_text',
        'tool',
        'action',
      ]);
      expect(messages.map((m) => m.id).toSet(), hasLength(2));
    },
  );

  test(
    'provisional answers retain only public reasoning while opaque Responses items never reach the reader',
    () {
      final messages = presentAgentSession({
        'id': 's',
        'status': 'completed',
        'messages': [
          {'role': 'user', 'id': 'u', 'runId': 'r', 'content': '核对账单'},
          {
            'role': 'assistant',
            'id': 'provisional',
            'runId': 'r',
            'content': '不应展示的未核实正文',
            'provisional': true,
            'responsesItems': [
              {
                'type': 'reasoning',
                'encrypted_content': 'opaque-secret',
                'content': 'not-public',
              },
            ],
            'parts': [
              {
                'type': 'reasoning',
                'id': 'public',
                'messageId': 'provisional',
                'runId': 'r',
                'kind': 'summary',
                'text': '公开摘要：先核对流水。',
                'status': 'completed',
                'startedAt': 1000,
                'endedAt': 2000,
              },
              {
                'type': 'artifact',
                'spec': {
                  'id': 'rejected',
                  'type': 'artifact',
                  'content': '不应预览的HTML',
                },
              },
            ],
          },
          {
            'role': 'assistant',
            'id': 'accepted',
            'runId': 'r',
            'content': '已核对，支出 40 元。',
            'reasoning_summary': '最终公开摘要',
            'responsesItems': [
              {'type': 'reasoning', 'encrypted_content': 'second-opaque'},
            ],
          },
          {
            'role': 'assistant',
            'internal': true,
            'runId': 'r',
            'content': '内部处理',
            'reasoning_content': '内部内容不展示',
          },
        ],
      });
      expect(messages.map((m) => m.text), ['核对账单', '已核对，支出 40 元。']);
      expect(messages.last.parts.map((p) => p['type']), [
        'reasoning',
        'reasoning',
      ]);
      expect(messages.last.parts.map((p) => p['text']), [
        '公开摘要：先核对流水。',
        '最终公开摘要',
      ]);
      final presented = jsonEncode(messages.map((m) => m.parts).toList());
      expect(presented, isNot(contains('encrypted_content')));
      expect(presented, isNot(contains('opaque-secret')));
      expect(presented, isNot(contains('not-public')));
    },
  );

  test(
    'restart restores unfinished public reasoning without making it an active stream',
    () {
      final messages = presentAgentSession({
        'id': 'restarted',
        'status': 'interrupted',
        'messages': [
          {'role': 'user', 'id': 'u', 'runId': 'r', 'content': '检查支出'},
          {
            'role': 'assistant',
            'id': 'a',
            'runId': 'r',
            'provisional': true,
            'content': '未完成正文',
            'parts': [
              {
                'type': 'reasoning',
                'id': 'p',
                'messageId': 'a',
                'runId': 'r',
                'text': '已经核对了一部分流水',
                'kind': 'reasoning_content',
                'status': 'streaming',
                'startedAt': 1000,
              },
            ],
            'responsesItems': [
              {'type': 'reasoning', 'encrypted_content': 'never-render'},
            ],
          },
        ],
      });
      expect(messages.last.status, 'interrupted');
      expect(messages.last.isStreaming, false);
      expect(messages.last.text, isEmpty);
      expect(messages.last.parts.single['text'], '已经核对了一部分流水');
      expect(messages.last.parts.single.containsKey('endedAt'), false);
      expect(jsonEncode(messages.last.parts), isNot(contains('never-render')));
    },
  );

  test(
    'legacy public reasoning restores once per model message with the original turn',
    () {
      final messages = presentAgentSession({
        'id': 'legacy-reasoning',
        'status': 'completed',
        'messages': [
          {'role': 'user', 'id': 'u1', 'runId': 'r1', 'content': '查账'},
          {
            'role': 'assistant',
            'id': 'a1',
            'runId': 'r1',
            'content': '',
            'tool_calls': [
              {'id': 't'},
            ],
            'reasoning_content': '先查询统计',
          },
          {
            'role': 'assistant',
            'id': 'a2',
            'runId': 'r1',
            'content': '第一轮已完成',
            'reasoning_content': '再核对范围',
            'parts': [
              {
                'type': 'reasoning',
                'id': 'a2:p',
                'messageId': 'a2',
                'runId': 'r1',
                'text': '再核对范围',
                'kind': 'reasoning_content',
                'status': 'completed',
              },
            ],
          },
          {'role': 'user', 'id': 'u2', 'runId': 'r2', 'content': '你好'},
          {
            'role': 'assistant',
            'id': 'a3',
            'runId': 'r2',
            'content': '你好',
            'reasoning_summary': '第二轮公开摘要',
          },
        ],
      });
      expect(messages[1].parts.map((p) => p['text']), ['先查询统计', '再核对范围']);
      expect(messages.last.parts.single['text'], '第二轮公开摘要');
      expect(messages[1].parts.map((p) => p['messageId']), ['a1', 'a2']);
    },
  );
}

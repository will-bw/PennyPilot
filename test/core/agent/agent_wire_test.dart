import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pennypilot/core/agent/agent_wire.dart';
import 'package:pennypilot/core/ai/ai_transport.dart';
import 'package:pennypilot/core/ai/shared_ai_service.dart';
import 'package:pennypilot/core/ledger_repository.dart';
import 'package:pennypilot/core/models.dart';

import '../ai/shared_ai_service_test.dart' show MemoryCredentials;
import '../ai/responses_stream_test.dart' show event, completed, message;

void main() {
  final items = <Map<String, dynamic>>[
    {
      'id': 'reason',
      'type': 'reasoning',
      'encrypted_content': 'opaque-reasoning',
      'summary': [],
    },
    {
      'id': 'fc',
      'type': 'function_call',
      'status': 'completed',
      'namespace': 'pennypilot',
      'call_id': 'call-one',
      'name': 'get_budget',
      'arguments': '{}',
    },
  ];
  test(
    'Responses replay preserves completed encrypted reasoning and matched function results',
    () {
      final reply = agentReplyFromResponses(
        items,
        'account',
        allowedTools: {'get_budget'},
      );
      final input = responsesAgentInput([
        {'role': 'system', 'content': '规则'},
        {'role': 'user', 'content': '预算'},
        reply.message,
        {
          'role': 'tool',
          'tool_call_id': 'call-one',
          'content': '{"budget":300000}',
        },
      ], 'account');
      expect(input[2]['encrypted_content'], 'opaque-reasoning');
      expect(input.last['type'], 'function_call_output');
      expect(input.last['call_id'], 'call-one');
      expect(input.first['role'], 'developer');
    },
  );
  test(
    'reasoning from another account and incomplete tool pairs are blocked',
    () {
      final reply = agentReplyFromResponses(items, 'account');
      expect(
        () => responsesAgentInput([reply.message], 'other-account'),
        throwsA(isA<LedgerException>()),
      );
      expect(
        () => responsesAgentInput([reply.message], 'account'),
        throwsA(isA<LedgerException>()),
      );
      expect(
        () => agentReplyFromResponses(
          items,
          'account',
          allowedTools: {'query_transactions'},
        ),
        throwsA(isA<LedgerException>()),
      );
    },
  );
  test('streamed done reasoning is used instead of provisional added item', () {
    final provisional = {...items.first, 'encrypted_content': 'incomplete'};
    final body =
        event({
          'type': 'response.output_item.added',
          'output_index': 0,
          'item': provisional,
        }) +
        event({
          'type': 'response.output_item.done',
          'output_index': 0,
          'item': items.first,
        }) +
        event({
          'type': 'response.output_item.done',
          'output_index': 1,
          'item': items.last,
        }) +
        completed([]);
    final output = decodeResponsesOutput(body);
    final reply = agentReplyFromResponses(
      (output['items'] as List).cast<Map<String, dynamic>>(),
      'account',
    );
    expect(
      (reply.message['responsesItems'] as List).first['encrypted_content'],
      'opaque-reasoning',
    );
  });

  for (final provider in ['deepseek', 'qwen']) {
    test(
      '$provider receives tools on every request and roundtrips reasoning_content',
      () async {
        final credentials = MemoryCredentials();
        var requests = 0;
        final service = SharedAiService(
          credentials: credentials,
          client: MockClient((request) async {
            final payload = jsonDecode(request.body) as Map;
            expect(payload['tools'], hasLength(1));
            expect(payload['tool_choice'], 'auto');
            expect(jsonEncode(payload), isNot(contains('responsesItems')));
            if (++requests == 2) {
              expect(
                (payload['messages'] as List)[1]['reasoning_content'],
                'model-reasoning',
              );
            }
            return http.Response(
              jsonEncode({
                'choices': [
                  {
                    'finish_reason': requests == 1 ? 'tool_calls' : 'stop',
                    'message': requests == 1
                        ? {
                            'role': 'assistant',
                            'content': null,
                            'reasoning_content': 'model-reasoning',
                            'tool_calls': [
                              {
                                'id': 'one',
                                'type': 'function',
                                'function': {
                                  'name': 'get_budget',
                                  'arguments': '{}',
                                },
                              },
                            ],
                          }
                        : {'role': 'assistant', 'content': '结果'},
                  },
                ],
                'usage': {'prompt_tokens': 100, 'completion_tokens': 20},
              }),
              200,
              headers: {'content-type': 'application/json; charset=utf-8'},
            );
          }),
        );
        addTearDown(service.dispose);
        final model = await service.openAgentModel(
          LedgerSnapshot(
            revision: 0,
            settings: {
              'activeProvider': provider,
              'deepSeekModel': 'deepseek-chat',
              'qwenModel': 'qwen-plus',
              'qwenBaseUrl': 'https://example.test/v1',
            },
          ),
          guard: () async {},
        );
        final tools = [
          {
            'type': 'function',
            'function': {
              'name': 'get_budget',
              'parameters': {'type': 'object', 'properties': {}},
            },
          },
        ];
        final first = await model.complete([
          {'role': 'user', 'content': '预算'},
        ], tools);
        expect(first.inputTokens, 100);
        await model.complete([
          {'role': 'user', 'content': '预算'},
          first.message,
          {'role': 'tool', 'tool_call_id': 'one', 'content': '{}'},
        ], tools);
        expect(requests, 2);
        await credentials.set(
          provider == 'deepseek' ? 'deepSeekKey' : 'qwenKey',
          'changed',
        );
        await expectLater(
          model.complete([
            {'role': 'user', 'content': '预算'},
          ], tools),
          throwsA(isA<LedgerException>()),
        );
        expect(requests, 2);
      },
    );
  }

  test(
    'iOS ChatGPT supports multiple tool turns with preserved reasoning and account binding',
    () async {
      final now = DateTime.fromMillisecondsSinceEpoch(1800000000000);
      final credentials = MemoryCredentials()
        ..values['chatGptAuth'] = jsonEncode({
          'hostId': 'urn:uuid:test-host',
          'activeId': 'account',
          'accounts': [
            {
              'clientId': 'account',
              'subject': 'subject',
              'email': 'test@example.test',
              'welcomeShown': false,
              'tokens': {
                'accessToken': 'test-access',
                'refreshToken': 'test-refresh',
                'idToken': null,
                'expiresAt': 1800003600,
                'earliestRefreshAt': 0,
                'scopes': ['chatgpt.tokens.use.direct'],
              },
            },
          ],
        });
      var requests = 0;
      final service = SharedAiService(
        credentials: credentials,
        now: () => now,
        client: MockClient((request) async {
          final body = jsonDecode(request.body) as Map;
          expect(body['store'], false);
          expect(body['tools'], isNotEmpty);
          if (++requests == 1) {
            return http.Response(
              completed(items),
              200,
              headers: {'content-type': 'text/event-stream; charset=utf-8'},
            );
          }
          expect(
            (body['input'] as List).any(
              (m) => m['encrypted_content'] == 'opaque-reasoning',
            ),
            true,
          );
          expect((body['input'] as List).last['call_id'], 'call-one');
          return http.Response(
            completed([message('预算查询完成')]),
            200,
            headers: {'content-type': 'text/event-stream; charset=utf-8'},
          );
        }),
      );
      addTearDown(service.dispose);
      final model = await service.openAgentModel(
        LedgerSnapshot(
          revision: 0,
          settings: {'activeProvider': 'chatgpt', 'chatGptModel': 'test-model'},
        ),
        guard: () async {},
      );
      final tools = [
        {
          'type': 'function',
          'function': {
            'name': 'get_budget',
            'description': '预算',
            'parameters': {'type': 'object', 'properties': {}},
          },
        },
      ];
      final first = await model.complete([
        {'role': 'user', 'content': '预算'},
      ], tools);
      final second = await model.complete([
        {'role': 'user', 'content': '预算'},
        first.message,
        {'role': 'tool', 'tool_call_id': 'call-one', 'content': '{}'},
      ], tools);
      expect(second.text, '预算查询完成');
      expect(requests, 2);
    },
  );
}

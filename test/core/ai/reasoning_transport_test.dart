import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pennypilot/core/agent/agent_wire.dart';
import 'package:pennypilot/core/ai/ai_transport.dart';
import 'package:pennypilot/core/ai/shared_ai_service.dart';
import 'package:pennypilot/core/ledger_repository.dart';
import 'package:pennypilot/core/models.dart';

import 'responses_stream_test.dart' show message;
import 'shared_ai_service_test.dart'
    as fixtures
    show MemoryCredentials, completion;

AiSseEvent _chunk(Map<String, dynamic> delta, {String? finish}) => AiSseEvent(
  jsonEncode({
    'choices': [
      {'index': 0, 'delta': delta, 'finish_reason': finish},
    ],
  }),
  null,
);

http.Response _answer({String? reasoning}) => http.Response(
  '${reasoning == null ? '' : 'data: ${_chunk({'reasoning_content': reasoning}).data}\n\n'}'
  'data: ${_chunk({'content': '结果'}, finish: 'stop').data}\n\ndata: [DONE]\n\n',
  200,
  headers: {'content-type': 'text/event-stream; charset=utf-8'},
);

void main() {
  for (final example in [
    (
      provider: 'deepseek',
      model: 'deepseek-flash',
      enabled: true,
      effort: 'max',
      expected: <String, dynamic>{
        'thinking': {'type': 'enabled'},
        'reasoning_effort': 'max',
      },
    ),
    (
      provider: 'deepseek',
      model: 'deepseek-flash',
      enabled: false,
      effort: 'max',
      expected: <String, dynamic>{
        'thinking': {'type': 'disabled'},
      },
    ),
    (
      provider: 'qwen',
      model: 'qwen3.8-flash',
      enabled: true,
      effort: 'medium',
      expected: <String, dynamic>{
        'enable_thinking': true,
        'reasoning_effort': 'medium',
      },
    ),
    (
      provider: 'qwen',
      model: 'qwen3.8-flash',
      enabled: false,
      effort: 'xhigh',
      expected: <String, dynamic>{'enable_thinking': false},
    ),
    (
      provider: 'qwen',
      model: 'qwen3.7-flash',
      enabled: true,
      effort: 'xhigh',
      expected: <String, dynamic>{'enable_thinking': true},
    ),
    (
      provider: 'qwen',
      model: 'custom-private-model',
      enabled: true,
      effort: 'xhigh',
      expected: <String, dynamic>{},
    ),
  ]) {
    test(
      '${example.provider}/${example.model} sends only supported thinking settings: ${example.enabled}',
      () async {
        final prefix = example.provider == 'deepseek' ? 'deepSeek' : 'qwen';
        final service = SharedAiService(
          credentials: fixtures.MemoryCredentials(),
          client: MockClient((request) async {
            final body = jsonDecode(request.body) as Map;
            final actual = {
              for (final key in [
                'thinking',
                'enable_thinking',
                'reasoning_effort',
              ])
                if (body.containsKey(key)) key: body[key],
            };
            expect(actual, example.expected);
            expect(body.containsKey('extra_body'), false);
            expect(body.containsKey('thinking_budget'), false);
            expect(body.containsKey('max_tokens'), false);
            expect(body.containsKey('max_completion_tokens'), false);
            if (example.model == 'qwen3.8-flash') {
              expect(body['preserve_thinking'], true);
            }
            return _answer(reasoning: example.enabled ? '服务返回的推理' : null);
          }),
        );
        addTearDown(service.dispose);
        final model = await service.openAgentModel(
          LedgerSnapshot(
            revision: 0,
            settings: {
              'activeProvider': example.provider,
              '${prefix}Model': example.model,
              '${prefix}ThinkingEnabled': example.enabled,
              '${prefix}ReasoningEffort': example.effort,
              'qwenBaseUrl': 'https://example.test/v1',
            },
          ),
          guard: () async {},
        );
        final reasoning = <String>[], text = <String>[];
        final reply = await model.turn(
          [
            {'role': 'user', 'content': '你好'},
          ],
          [],
          onTextDelta: text.add,
          onReasoningDelta: reasoning.add,
        );
        expect(text, ['结果']);
        expect(reasoning, example.enabled ? ['服务返回的推理'] : isEmpty);
        expect(
          reply.message['reasoning_content'],
          example.enabled ? '服务返回的推理' : null,
        );
        expect(model.reasoningConfig['provider'], example.provider);
      },
    );
  }

  for (final provider in ['deepseek', 'qwen']) {
    test(
      '$provider preserves reasoning through tool rounds and a later final answer',
      () async {
        var requestCount = 0;
        final prefix = provider == 'deepseek' ? 'deepSeek' : 'qwen';
        final service = SharedAiService(
          credentials: fixtures.MemoryCredentials(),
          client: MockClient((request) async {
            final body = jsonDecode(request.body) as Map;
            final messages = body['messages'] as List;
            switch (++requestCount) {
              case 1:
                return http.Response(
                  'data: ${_chunk({'reasoning_content': '先核对预算'}).data}\n\n'
                  'data: ${_chunk({
                    'tool_calls': [
                      {
                        'index': 0,
                        'id': 'budget-call',
                        'type': 'function',
                        'function': {'name': 'get_budget', 'arguments': '{}'},
                      },
                    ],
                  }, finish: 'tool_calls').data}\n\ndata: [DONE]\n\n',
                  200,
                  headers: {'content-type': 'text/event-stream; charset=utf-8'},
                );
              case 2:
                expect(messages[1]['reasoning_content'], '先核对预算');
                expect(messages[1]['tool_calls'].single['id'], 'budget-call');
                expect(messages[2]['tool_call_id'], 'budget-call');
                return _answer(reasoning: '根据查询结果总结');
              default:
                expect(messages[1]['reasoning_content'], '先核对预算');
                expect(messages[3]['reasoning_content'], '根据查询结果总结');
                return _answer(reasoning: '继续分析');
            }
          }),
        );
        addTearDown(service.dispose);
        final model = await service.openAgentModel(
          LedgerSnapshot(
            revision: 0,
            settings: {
              'activeProvider': provider,
              '${prefix}Model': provider == 'deepseek'
                  ? 'deepseek-flash'
                  : 'qwen3.8-flash',
              '${prefix}ThinkingEnabled': true,
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
        final messages = <Map<String, dynamic>>[
          {'role': 'user', 'content': '预算'},
        ];
        final first = await model.turn(messages, tools, onTextDelta: (_) {});
        messages.addAll([
          first.message,
          {'role': 'tool', 'tool_call_id': 'budget-call', 'content': '{}'},
        ]);
        final finalReply = await model.turn(
          messages,
          tools,
          onTextDelta: (_) {},
        );
        messages.addAll([
          finalReply.message,
          {'role': 'user', 'content': '继续'},
        ]);
        await model.turn(messages, tools, onTextDelta: (_) {});
        expect(requestCount, 3);
      },
    );
  }

  test(
    'Qwen repeated tool ids remain stable while reasoning and arguments stream separately',
    () {
      final thoughts = <String>[];
      final decoder = ChatCompletionStreamDecoder(
        onReasoningDelta: thoughts.add,
      );
      decoder.accept(
        _chunk({
          'reasoning_content': '先查',
          'tool_calls': [
            {
              'index': 0,
              'id': 'call-one',
              'function': {'name': 'get_budget', 'arguments': '{"month":'},
            },
          ],
        }),
      );
      decoder.accept(
        _chunk({
          'reasoning_content': '预算',
          'tool_calls': [
            {
              'index': 0,
              'id': 'call-one',
              'function': {'arguments': '"2026-11"}'},
            },
          ],
        }, finish: 'tool_calls'),
      );
      final message = decoder.finish({'get_budget'})['message'] as Map;
      expect(thoughts, ['先查', '预算']);
      expect(message['reasoning_content'], '先查预算');
      expect(message['tool_calls'].single['id'], 'call-one');
    },
  );

  test(
    'length exhaustion retains emitted reasoning but rejects incomplete executable tools',
    () {
      final thoughts = <String>[];
      final decoder = ChatCompletionStreamDecoder(
        onReasoningDelta: thoughts.add,
      );
      expect(
        () => decoder.accept(
          _chunk({
            'reasoning_content': '未完成的推理',
            'tool_calls': [
              {
                'index': 0,
                'id': 'call-one',
                'function': {'name': 'get_budget', 'arguments': '{'},
              },
            ],
          }, finish: 'length'),
        ),
        throwsA(
          isA<LedgerException>().having(
            (e) => e.message,
            'message',
            contains('长度上限'),
          ),
        ),
      );
      expect(thoughts, ['未完成的推理']);
      expect(
        () => decoder.finish({'get_budget'}),
        throwsA(isA<LedgerException>()),
      );
    },
  );

  test(
    'Responses exposes public summaries and never encrypted or private reasoning text',
    () {
      final summaries = <String>[];
      final decoder = ResponsesStreamDecoder(onReasoningDelta: summaries.add);
      decoder.accept(
        AiSseEvent(
          jsonEncode({
            'type': 'response.reasoning_text.delta',
            'delta': 'PRIVATE',
          }),
          null,
        ),
      );
      decoder.accept(
        AiSseEvent(
          jsonEncode({
            'type': 'response.reasoning_summary_text.delta',
            'item_id': 'rs_1',
            'summary_index': 0,
            'delta': '公开摘要',
          }),
          null,
        ),
      );
      expect(summaries, ['公开摘要']);
      final reply = agentReplyFromResponses([
        {
          'id': 'reason-1',
          'type': 'reasoning',
          'encrypted_content': 'ENCRYPTED_PRIVATE',
          'summary': [
            {'type': 'summary_text', 'text': '公开摘要'},
          ],
        },
        message('正文'),
      ], 'account');
      expect(reply.message['reasoning_summary'], '公开摘要');
      expect(reply.message['reasoning_summary'], isNot(contains('PRIVATE')));
      expect(
        (reply.message['responsesItems'] as List).first['encrypted_content'],
        'ENCRYPTED_PRIVATE',
      );
    },
  );

  test(
    'Qwen thinking complete consumes SSE for non-preview compaction requests',
    () async {
      final service = SharedAiService(
        credentials: fixtures.MemoryCredentials(),
        client: MockClient((request) async {
          final body = jsonDecode(request.body) as Map;
          expect(body['model'], 'qwen3.5-27b');
          expect(body['enable_thinking'], true);
          expect(body['stream'], true);
          return _answer(reasoning: '公开的压缩推理');
        }),
      );
      addTearDown(service.dispose);
      final model = await service.openAgentModel(
        LedgerSnapshot(
          revision: 0,
          settings: {
            'activeProvider': 'qwen',
            'qwenModel': 'qwen3.5-27b',
            'qwenThinkingEnabled': true,
            'qwenBaseUrl': 'https://example.test/v1',
          },
        ),
        guard: () async {},
      );
      final reply = await model.complete([
        {'role': 'user', 'content': '压缩历史'},
      ], []);
      expect(reply.text, '结果');
      expect(reply.message['reasoning_content'], '公开的压缩推理');
    },
  );

  test(
    'thinking-only Qwen recognition consumes SSE final JSON without exposing reasoning',
    () async {
      final service = SharedAiService(
        credentials: fixtures.MemoryCredentials(),
        client: MockClient((request) async {
          final body = jsonDecode(request.body) as Map;
          expect(body['model'], 'qwen3.8-2.4t-a95b');
          expect(body['stream'], true);
          expect(body['response_format'], {'type': 'json_object'});
          expect(body.containsKey('enable_thinking'), false);
          expect(body.containsKey('reasoning_effort'), false);
          return http.Response(
            'data: ${_chunk({'reasoning_content': '后台公开推理不进入识别结果'}).data}\n\n'
            'data: ${_chunk({
              'content': jsonEncode({'intent': 'record', 'proposals': []}),
            }, finish: 'stop').data}\n\ndata: [DONE]\n\n',
            200,
            headers: {'content-type': 'text/event-stream; charset=utf-8'},
          );
        }),
      );
      addTearDown(service.dispose);
      final result = await service.recognizeInput(
        {'text': '本次不记账'},
        LedgerSnapshot(
          revision: 0,
          settings: {
            'activeProvider': 'qwen',
            'qwenModel': 'qwen3.8-2.4t-a95b',
            'qwenThinkingEnabled': false,
            'qwenReasoningEffort': 'xhigh',
            'qwenBaseUrl': 'https://example.test/v1',
          },
        ),
      );
      expect(result['transactions'], isEmpty);
      expect(jsonEncode(result), isNot(contains('后台公开推理')));
    },
  );

  test(
    'older Qwen thinking recognition streams prompt-constrained JSON without response_format',
    () async {
      final service = SharedAiService(
        credentials: fixtures.MemoryCredentials(),
        client: MockClient((request) async {
          final body = jsonDecode(request.body) as Map;
          expect(body['model'], 'qwen3-235b-a22b-thinking-2507');
          expect(body['stream'], true);
          expect(body.containsKey('response_format'), false);
          expect(body.containsKey('enable_thinking'), false);
          expect(jsonEncode(body['messages']), contains('JSON'));
          return http.Response(
            'data: ${_chunk({
              'reasoning_content': '后台思考',
              'content': jsonEncode({'intent': 'record', 'proposals': []}),
            }, finish: 'stop').data}\n\ndata: [DONE]\n\n',
            200,
            headers: {'content-type': 'text/event-stream; charset=utf-8'},
          );
        }),
      );
      addTearDown(service.dispose);
      final result = await service.recognizeInput(
        {'text': '本次不记账'},
        LedgerSnapshot(
          revision: 0,
          settings: {
            'activeProvider': 'qwen',
            'qwenModel': 'qwen3.8-flash',
            'recognitionTextModel': 'qwen3-235b-a22b-thinking-2507',
            'qwenBaseUrl': 'https://example.test/v1',
          },
        ),
      );
      expect(result['transactions'], isEmpty);
      expect(jsonEncode(result), isNot(contains('后台思考')));
    },
  );

  test(
    'thinking-only Qwen recognition rejects length-exhausted JSON before validation',
    () async {
      final service = SharedAiService(
        credentials: fixtures.MemoryCredentials(),
        client: MockClient(
          (_) async => http.Response(
            'data: ${_chunk({'reasoning_content': '尚未完成', 'content': '{"intent":'}, finish: 'length').data}\n\ndata: [DONE]\n\n',
            200,
            headers: {'content-type': 'text/event-stream; charset=utf-8'},
          ),
        ),
      );
      addTearDown(service.dispose);
      await expectLater(
        service.recognizeInput(
          {'text': '本次不记账'},
          LedgerSnapshot(
            revision: 0,
            settings: {
              'activeProvider': 'qwen',
              'qwenModel': 'qwen3.8-2.4t-a95b',
              'qwenBaseUrl': 'https://example.test/v1',
            },
          ),
        ),
        throwsA(
          isA<LedgerException>().having(
            (e) => e.message,
            'message',
            contains('长度上限'),
          ),
        ),
      );
    },
  );

  test(
    'synchronous recognition overrides do not inherit chat thinking preferences',
    () async {
      final service = SharedAiService(
        credentials: fixtures.MemoryCredentials(),
        client: MockClient((request) async {
          final body = jsonDecode(request.body) as Map;
          expect(body['model'], 'qwen3.5-27b');
          expect(body['enable_thinking'], false);
          expect(body.containsKey('reasoning_effort'), false);
          expect(body.containsKey('thinking_budget'), false);
          return fixtures.completion({'intent': 'record', 'proposals': []});
        }),
      );
      addTearDown(service.dispose);
      await service.recognizeInput(
        {'text': '这次不记账'},
        LedgerSnapshot(
          revision: 0,
          settings: {
            'activeProvider': 'qwen',
            'qwenModel': 'qwen3.8-flash',
            'qwenThinkingEnabled': true,
            'qwenReasoningEffort': 'xhigh',
            'recognitionTextModel': 'qwen3.5-27b',
            'qwenBaseUrl': 'https://example.test/v1',
          },
        ),
      );
    },
  );
}

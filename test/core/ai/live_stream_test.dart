import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pennypilot/core/ai/ai_transport.dart';
import 'package:pennypilot/core/ai/shared_ai_service.dart';
import 'package:pennypilot/core/ledger_repository.dart';
import 'package:pennypilot/core/models.dart';

import 'shared_ai_service_test.dart' show MemoryCredentials;

String _event(Map<String, dynamic> data) => 'data: ${jsonEncode(data)}\n\n';
AiSseEvent _chunk(Map<String, dynamic> delta, {String? finish}) => AiSseEvent(
  jsonEncode({
    'choices': [
      {'index': 0, 'delta': delta, 'finish_reason': finish},
    ],
  }),
  null,
);

void main() {
  for (final provider in ['deepseek', 'qwen']) {
    test(
      '$provider adapter requests a real SSE stream and preserves completed usage',
      () async {
        final credentials = MemoryCredentials();
        await credentials.set(
          provider == 'deepseek' ? 'deepSeekKey' : 'qwenKey',
          'test-key',
        );
        final previews = <String>[];
        final client = MockClient.streaming((request, body) async {
          final payload = jsonDecode(utf8.decode(await body.toBytes())) as Map;
          expect(payload['stream'], true);
          expect(payload['stream_options'], {'include_usage': true});
          expect(request.followRedirects, false);
          return http.StreamedResponse(
            Stream.fromIterable([
              utf8.encode('data: ${_chunk({'content': '你好'}).data}\n\n'),
              utf8.encode('data: ${_chunk({}, finish: 'stop').data}\n\n'),
              utf8.encode(
                'data: {"choices":[],"usage":{"prompt_tokens":11,"completion_tokens":2}}\n\ndata: [DONE]\n\n',
              ),
            ]),
            200,
            headers: {'content-type': 'text/event-stream'},
          );
        });
        final service = SharedAiService(
          credentials: credentials,
          client: client,
        );
        addTearDown(service.dispose);
        addTearDown(client.close);
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
        final reply = await model.turn(
          [
            {'role': 'user', 'content': '你好'},
          ],
          [],
          onTextDelta: previews.add,
        );
        expect(previews, ['你好']);
        expect(reply.text, '你好');
        expect(reply.inputTokens, 11);
        expect(reply.outputTokens, 2);
      },
    );
  }

  test(
    'UTF8 and SSE fragments reach the preview before response completion',
    () async {
      final body = StreamController<List<int>>();
      final previews = <String>[];
      final preview = Completer<void>();
      final decoder = ResponsesStreamDecoder(
        onTextDelta: (delta) {
          previews.add(delta);
          preview.complete();
        },
      );
      final client = MockClient.streaming(
        (_, _) async => http.StreamedResponse(
          body.stream,
          200,
          headers: {'content-type': 'text/event-stream; charset=utf-8'},
        ),
      );
      addTearDown(client.close);
      final request = aiSseRequest(
        client,
        http.Request('POST', Uri.parse('https://example.test/responses')),
        onEvent: decoder.accept,
      );
      final bytes = utf8.encode(
        '\uFEFF: keepalive\r\n\r\nevent: response.output_text.delta\r\n'
        'data: {"type":"response.output_text.delta",\r\n'
        'data: "delta":"你好☕"}\r\n\r\n',
      );
      for (final byte in bytes) {
        body.add([byte]);
      }
      await preview.future;
      expect(previews, ['你好☕']);
      expect(() => decoder.output, throwsA(isA<LedgerException>()));
      body.add(
        utf8.encode(
          _event({
            'type': 'response.completed',
            'response': {
              'status': 'completed',
              'output': [
                {
                  'id': 'm1',
                  'type': 'message',
                  'role': 'assistant',
                  'status': 'completed',
                  'content': [
                    {'type': 'output_text', 'text': '你好☕'},
                  ],
                },
              ],
            },
          }),
        ),
      );
      await body.close();
      await request;
      expect(
        (decoder.output['items'] as List).single['content'].single['text'],
        '你好☕',
      );
    },
  );

  test(
    'a cut stream keeps preview provisional and rejects incomplete UTF8',
    () async {
      for (final bytes in [
        utf8.encode(
          _event({'type': 'response.output_text.delta', 'delta': '部分'}),
        ),
        [...utf8.encode('data: {"delta":"'), 0xe4, 0xbd],
      ]) {
        final decoder = ResponsesStreamDecoder();
        final client = MockClient.streaming(
          (_, _) async => http.StreamedResponse(
            Stream.value(bytes),
            200,
            headers: {'content-type': 'text/event-stream'},
          ),
        );
        try {
          await aiSseRequest(
            client,
            http.Request('POST', Uri.parse('https://example.test/stream')),
            onEvent: decoder.accept,
          );
          expect(() => decoder.output, throwsA(isA<LedgerException>()));
        } on LedgerException {
          expect(() => decoder.output, throwsA(isA<LedgerException>()));
        } finally {
          client.close();
        }
      }
    },
  );

  test(
    'Chat Completions assembles split calls without exposing partial arguments',
    () {
      final previews = <String>[];
      final decoder = ChatCompletionStreamDecoder(onTextDelta: previews.add);
      decoder.accept(
        _chunk({'content': '正在查询', 'reasoning_content': 'private'}),
      );
      decoder.accept(
        _chunk({
          'tool_calls': [
            {
              'index': 0,
              'id': 'call-1',
              'type': 'function',
              'function': {'name': 'get_', 'arguments': '{"month":'},
            },
          ],
        }),
      );
      expect(
        () => decoder.finish({'get_budget'}),
        throwsA(isA<LedgerException>()),
      );
      decoder.accept(
        _chunk({
          'tool_calls': [
            {
              'index': 0,
              'function': {'name': 'budget', 'arguments': '"2026-11"}'},
            },
          ],
        }, finish: 'tool_calls'),
      );
      final result = decoder.finish({'get_budget'});
      final message = result['message'] as Map;
      expect(previews, ['正在查询']);
      expect(message['reasoning_content'], 'private');
      expect((message['tool_calls'] as List).single['function'], {
        'name': 'get_budget',
        'arguments': '{"month":"2026-11"}',
      });
    },
  );

  test(
    'unknown tools, truncated JSON and failed completion never produce executable calls',
    () {
      for (final arguments in ['{', '{}']) {
        final decoder = ChatCompletionStreamDecoder();
        decoder.accept(
          _chunk({
            'tool_calls': [
              {
                'index': 0,
                'id': 'call-1',
                'type': 'function',
                'function': {'name': 'unknown', 'arguments': arguments},
              },
            ],
          }, finish: 'tool_calls'),
        );
        expect(
          () => decoder.finish({'get_budget'}),
          throwsA(isA<LedgerException>()),
        );
      }
      final truncated = ChatCompletionStreamDecoder();
      truncated.accept(
        _chunk({
          'tool_calls': [
            {
              'index': 0,
              'id': 'call-1',
              'function': {'name': 'get_budget', 'arguments': '{'},
            },
          ],
        }, finish: 'tool_calls'),
      );
      expect(
        () => truncated.finish({'get_budget'}),
        throwsA(isA<LedgerException>()),
      );
      expect(
        () => ChatCompletionStreamDecoder().accept(
          _chunk({'content': 'half'}, finish: 'length'),
        ),
        throwsA(isA<LedgerException>()),
      );
    },
  );
}

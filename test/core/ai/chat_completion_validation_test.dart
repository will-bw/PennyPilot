import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pennypilot/core/ai/shared_ai_service.dart';
import 'package:pennypilot/core/ledger_repository.dart';
import 'package:pennypilot/core/models.dart';

import 'shared_ai_service_test.dart' show MemoryCredentials;

Map<String, dynamic> _call({
  String id = 'budget-call',
  String name = 'get_budget',
  String arguments = '{}',
}) => {
  'id': id,
  'type': 'function',
  'function': {'name': name, 'arguments': arguments},
};

void main() {
  for (final provider in ['deepseek', 'qwen']) {
    Future<void> check(
      Map<String, dynamic> message,
      String finish, {
      bool rejected = true,
    }) async {
      final service = SharedAiService(
        credentials: MemoryCredentials(),
        client: MockClient((request) async {
          expect((jsonDecode(request.body) as Map)['stream'], isNull);
          return http.Response(
            jsonEncode({
              'choices': [
                {'finish_reason': finish, 'message': message},
              ],
            }),
            200,
            headers: {'content-type': 'application/json'},
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
            'qwenThinkingEnabled': false,
            'qwenBaseUrl': 'https://example.test/v1',
          },
        ),
        guard: () async {},
      );
      final result = model.complete(
        [
          {'role': 'user', 'content': '查看预算'},
        ],
        [
          {
            'type': 'function',
            'function': {
              'name': 'get_budget',
              'parameters': {'type': 'object', 'properties': {}},
            },
          },
        ],
      );
      if (rejected) {
        await expectLater(result, throwsA(isA<LedgerException>()));
      } else {
        final reply = await result;
        expect(reply.text, '');
        expect(reply.calls.single['id'], 'budget-call');
      }
    }

    test('$provider complete rejects tools paired with stop', () async {
      await check({
        'role': 'assistant',
        'tool_calls': [_call()],
      }, 'stop');
    });
    test('$provider complete rejects tool_calls without a tool', () async {
      await check({'role': 'assistant', 'content': '未查询'}, 'tool_calls');
    });
    test('$provider complete validates calls before exposing them', () async {
      for (final calls in [
        [_call(), _call()],
        [_call(name: 'unknown')],
        [_call(arguments: '{')],
        [_call(id: ' ')],
        [_call(arguments: '[]')],
        [_call(), null],
      ]) {
        await check({'role': 'assistant', 'tool_calls': calls}, 'tool_calls');
      }
    });
    test('$provider complete rejects malformed message fields', () async {
      await check({'role': 'user', 'content': '回答'}, 'stop');
      await check({
        'role': 'assistant',
        'content': '回答',
        'reasoning_content': 1,
      }, 'stop');
    });
    test(
      '$provider complete accepts content omitted or null for tools',
      () async {
        await check(
          {
            'role': 'assistant',
            'tool_calls': [_call()],
          },
          'tool_calls',
          rejected: false,
        );
        await check(
          {
            'role': 'assistant',
            'content': null,
            'tool_calls': [_call()],
          },
          'tool_calls',
          rejected: false,
        );
      },
    );
  }
}

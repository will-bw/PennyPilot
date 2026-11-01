import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pennypilot/core/agent/agent_runtime.dart';
import 'package:pennypilot/core/agent/agent_store.dart';
import 'package:pennypilot/core/ai/shared_ai_service.dart';
import 'package:pennypilot/core/models.dart';

import 'shared_ai_service_test.dart' show MemoryCredentials;

void main() {
  test(
    'stop aborts the specific request and releases its subscription without closing the shared client',
    () async {
      final credentials = MemoryCredentials();
      await credentials.set('deepSeekKey', 'test-key');
      final started = Completer<void>(),
          aborted = Completer<void>(),
          cancelled = Completer<void>();
      var requests = 0;
      final client = MockClient.streaming((request, body) async {
        await body.drain<void>();
        expect(request, isA<http.Abortable>());
        if (++requests > 1) {
          return http.StreamedResponse(
            Stream.value(
              utf8.encode(
                'data: {"choices":[{"index":0,"delta":{"content":"新的回答"},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n',
              ),
            ),
            200,
            headers: {'content-type': 'text/event-stream'},
          );
        }
        late StreamController<List<int>> stream;
        stream = StreamController<List<int>>(
          onListen: () {
            stream.add(
              utf8.encode(
                'data: {"choices":[{"index":0,"delta":{"content":"部分答案"},"finish_reason":null}]}\n\n',
              ),
            );
          },
          onCancel: () {
            cancelled.complete();
          },
        );
        (request as http.Abortable).abortTrigger!.then((_) {
          aborted.complete();
          stream.addError(http.RequestAbortedException(request.url));
          unawaited(stream.close());
        });
        return http.StreamedResponse(
          stream.stream,
          200,
          headers: {'content-type': 'text/event-stream'},
        );
      });
      final service = SharedAiService(credentials: credentials, client: client);
      addTearDown(service.dispose);
      addTearDown(client.close);
      final snapshot = LedgerSnapshot(
        revision: 0,
        settings: {
          'activeProvider': 'deepseek',
          'deepSeekModel': 'deepseek-chat',
        },
      );
      final runtime = LedgerAgentRuntime(
        store: MemoryAgentStore(),
        onProgress: (event) {
          if (event['phase'] == 'text_delta' && !started.isCompleted) {
            started.complete();
          }
        },
      );
      final firstModel = await service.openAgentModel(
        snapshot,
        guard: () async {},
      );
      final task = runtime.run(
        question: '你好',
        ledger: snapshot,
        model: firstModel,
        guard: () async {},
      );
      await started.future;
      runtime.cancel();
      final result = await task;
      await aborted.future;
      await cancelled.future;
      expect((result['session'] as Map)['status'], 'cancelled');
      final nextModel = await service.openAgentModel(
        snapshot,
        guard: () async {},
      );
      final next = await runtime.run(
        question: '再说一句',
        ledger: snapshot,
        model: nextModel,
        guard: () async {},
      );
      expect(next['answer'], '新的回答');
      expect(requests, 2);
    },
  );

  test('a request waiting for headers receives the abort signal too', () async {
    final credentials = MemoryCredentials();
    await credentials.set('qwenKey', 'test-key');
    final sent = Completer<void>(), aborted = Completer<void>();
    final client = MockClient.streaming((request, body) async {
      await body.drain<void>();
      final abort = (request as http.Abortable).abortTrigger!;
      sent.complete();
      await abort;
      aborted.complete();
      throw http.RequestAbortedException(request.url);
    });
    final service = SharedAiService(credentials: credentials, client: client);
    addTearDown(service.dispose);
    addTearDown(client.close);
    final snapshot = LedgerSnapshot(
      revision: 0,
      settings: {
        'activeProvider': 'qwen',
        'qwenModel': 'qwen-plus',
        'qwenBaseUrl': 'https://example.test/v1',
      },
    );
    final runtime = LedgerAgentRuntime(store: MemoryAgentStore());
    final model = await service.openAgentModel(snapshot, guard: () async {});
    final task = runtime.run(
      question: '你好',
      ledger: snapshot,
      model: model,
      guard: () async {},
    );
    await sent.future;
    runtime.cancel();
    final result = await task;
    await aborted.future;
    expect((result['session'] as Map)['status'], 'cancelled');
  });
}

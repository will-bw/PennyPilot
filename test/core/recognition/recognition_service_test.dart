import 'dart:convert';
import 'package:flutter_test/flutter_test.dart' hide completion;
import 'package:pennypilot/core/ledger_repository.dart';
import 'package:http/testing.dart';
import 'package:pennypilot/core/ai/shared_ai_service.dart';
import 'package:pennypilot/core/models.dart';
import '../ai/shared_ai_service_test.dart' show MemoryCredentials, completion;
import 'recognition_protocol_test.dart' show proposal;

void main() {
  final now = DateTime(2026, 10, 24, 12);
  LedgerSnapshot snapshot({
    bool image = false,
    String review = '',
    String provider = 'qwen',
    String visionProvider = '',
    String reviewProvider = '',
  }) => LedgerSnapshot(
    revision: 0,
    categories: const [LedgerCategory(id: 1, name: '餐饮', type: TxType.expense)],
    settings: {
      'activeProvider': provider,
      'recognitionVisionProvider': visionProvider,
      'recognitionReviewProvider': reviewProvider,
      'qwenModel': 'default',
      'qwenBaseUrl': 'https://example.test/v1',
      'recognitionTextModel': 'text-model',
      'recognitionVisionModel': 'vision-model',
      'recognitionReviewModel': review,
      'cloudImageParsingEnabled': image,
    },
  );
  test(
    'text role uses selected model with structured proposal output',
    () async {
      final service = SharedAiService(
        credentials: MemoryCredentials(),
        now: () => now,
        client: MockClient((request) async {
          final body = jsonDecode(request.body);
          expect(body['model'], 'text-model');
          expect(body['response_format'], {'type': 'json_object'});
          return completion({
            'intent': 'record',
            'proposals': [proposal()],
          });
        }),
      );
      final result = await service.recognizeInput({
        'text': '午饭12元',
        'operationId': 'one',
      }, snapshot());
      expect((result['transactions'] as List).length, 1);
      await service.dispose();
    },
  );
  test('image upload requires independent permission before network', () async {
    var sent = false;
    final service = SharedAiService(
      credentials: MemoryCredentials(),
      client: MockClient((_) async {
        sent = true;
        return completion({});
      }),
    );
    await expectLater(
      service.recognizeInput({
        'imageBase64': 'aW1hZ2U=',
        'mimeType': 'image/png',
      }, snapshot()),
      throwsException,
    );
    expect(sent, false);
    await service.dispose();
  });
  test('vision role sends image content and retains historical date', () async {
    final service = SharedAiService(
      credentials: MemoryCredentials(),
      now: () => now,
      client: MockClient((request) async {
        final body = jsonDecode(request.body);
        expect(body['model'], 'vision-model');
        expect(
          body['messages'][1]['content'][1]['image_url']['url'],
          'data:image/png;base64,aW1hZ2U=',
        );
        return completion({
          'intent': 'record',
          'proposals': [
            {
              ...proposal(status: 'completed'),
              'occurred_at': '2026-09-20T12:00:00',
            },
          ],
        });
      }),
    );
    final result = await service.recognizeInput({
      'imageBase64': 'aW1hZ2U=',
      'mimeType': 'image/png',
    }, snapshot(image: true));
    expect((result['transactions'] as List).single['source'], 'AI_IMAGE');
    expect(result['autoSaveEligible'], false);
    await service.dispose();
  });
  test(
    'review model is called only for unresolved evidence and cannot auto save',
    () async {
      final models = <String>[];
      final service = SharedAiService(
        credentials: MemoryCredentials(),
        now: () => now,
        client: MockClient((request) async {
          models.add(jsonDecode(request.body)['model'] as String);
          return completion({
            'intent': 'record',
            'proposals': [
              proposal(type: models.length == 1 ? 'UNKNOWN' : 'EXPENSE'),
            ],
          });
        }),
      );
      final result = await service.recognizeInput({
        'text': '午饭12元',
      }, snapshot(review: 'review-model'));
      expect(models, ['text-model', 'review-model']);
      expect((result['transactions'] as List).length, 1);
      expect(result['autoSaveEligible'], false);
      await service.dispose();
    },
  );
  test('native ChatGPT transport still uses the same fact validator', () async {
    final service = SharedAiService(
      credentials: MemoryCredentials(),
      now: () => now,
    );
    final result = await service.recognizeInput(
      {'text': '午饭12元'},
      snapshot(provider: 'chatgpt'),
      nativeChat: (messages, role) async {
        expect(role, 'text');
        return jsonEncode({
          'intent': 'record',
          'proposals': [proposal(amount: 99900)],
        });
      },
    );
    expect(result['transactions'], isEmpty);
    await service.dispose();
  });
  test(
    'DeepSeek text and Qwen vision route to different configured endpoints',
    () async {
      final hosts = <String>[];
      final service = SharedAiService(
        credentials: MemoryCredentials(),
        now: () => now,
        client: MockClient((request) async {
          hosts.add(request.url.host);
          return completion({
            'intent': 'record',
            'proposals': [
              {
                ...proposal(status: 'completed'),
                'occurred_at': '2026-10-23T10:00:00',
              },
            ],
          });
        }),
      );
      final state = snapshot(
        provider: 'deepseek',
        visionProvider: 'qwen',
        image: true,
      );
      await service.recognizeInput({'text': '午饭12元'}, state);
      await service.recognizeInput({
        'imageBase64': 'aW1hZ2U=',
        'mimeType': 'image/png',
      }, state);
      expect(hosts, ['api.deepseek.com', 'example.test']);
      await service.dispose();
    },
  );
  test(
    'ChatGPT selected only for review reaches native transport once',
    () async {
      var nativeCalls = 0;
      final service = SharedAiService(
        credentials: MemoryCredentials(),
        now: () => now,
        client: MockClient(
          (_) async => completion({
            'intent': 'record',
            'proposals': [proposal(type: 'UNKNOWN')],
          }),
        ),
      );
      final result = await service.recognizeInput(
        {'text': '午饭12元'},
        snapshot(reviewProvider: 'chatgpt'),
        nativeChat: (messages, role) async {
          nativeCalls++;
          expect(role, 'review');
          return jsonEncode({
            'intent': 'record',
            'proposals': [proposal()],
          });
        },
      );
      expect(nativeCalls, 1);
      expect(result['autoSaveEligible'], false);
      await service.dispose();
    },
  );
  test(
    'settings change between interpretation and review cancels before another recipient',
    () async {
      var changed = false, requests = 0;
      final service = SharedAiService(
        credentials: MemoryCredentials(),
        now: () => now,
        client: MockClient((_) async {
          requests++;
          changed = true;
          return completion({
            'intent': 'record',
            'proposals': [proposal(type: 'UNKNOWN')],
          });
        }),
      );
      await expectLater(
        service.runGuarded(
          () async {
            if (changed) {
              throw const LedgerException('配置已改变', code: 'CANCELLED');
            }
          },
          () => service.recognizeInput({
            'text': '午饭12元',
          }, snapshot(review: 'other', reviewProvider: 'deepseek')),
        ),
        throwsA(
          isA<LedgerException>().having((e) => e.code, 'code', 'CANCELLED'),
        ),
      );
      expect(requests, 1);
      await service.dispose();
    },
  );
}

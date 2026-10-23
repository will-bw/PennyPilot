import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pennypilot/core/ai/shared_chatgpt_auth.dart';
import 'package:pennypilot/core/ledger_repository.dart';

import 'shared_ai_service_test.dart' show MemoryCredentials;

void main() {
  final fixture =
      jsonDecode(File('test/core/ai/id_token_fixture.json').readAsStringSync())
          as Map<String, dynamic>;
  const nowSeconds = 1800000000;
  final now = DateTime.fromMillisecondsSinceEpoch(nowSeconds * 1000);
  Map<String, dynamic> account(String id, {bool expired = false}) => {
    'clientId': id,
    'subject': '$id-subject',
    'email': '$id@example.test',
    'welcomeShown': false,
    'tokens': {
      'accessToken': '$id-access',
      'refreshToken': '$id-refresh',
      'idToken': null,
      'expiresAt': nowSeconds + (expired ? -1 : 3600),
      'earliestRefreshAt': 0,
      'scopes': ['chatgpt.tokens.use.direct'],
    },
  };
  MemoryCredentials stored({bool expired = false}) =>
      MemoryCredentials()
        ..values['chatGptAuth'] = jsonEncode({
          'hostId': 'urn:uuid:test-host',
          'activeId': 'one',
          'accounts': [account('one', expired: expired), account('two')],
        });

  test(
    'RS256 verifies signature, identity and nonce with generated test fixture',
    () {
      final identity = verifyChatGptIdToken(
        fixture['valid'] as String,
        fixture['jwks'] as Map<String, dynamic>,
        clientId: 'test-client',
        nonce: 'test-nonce',
        nowSeconds: nowSeconds,
      );
      expect(identity, {'sub': 'test-subject', 'email': 'test@example.test'});
    },
  );
  for (final invalid in ['wrongAudience', 'wrongNonce', 'expired']) {
    test('rejects signed but invalid ID token: $invalid', () {
      expect(
        () => verifyChatGptIdToken(
          fixture[invalid] as String,
          fixture['jwks'] as Map<String, dynamic>,
          clientId: 'test-client',
          nonce: 'test-nonce',
          nowSeconds: nowSeconds,
        ),
        throwsA(isA<LedgerException>()),
      );
    });
  }
  test('tampered token and duplicate JWK cannot be authenticated', () {
    final token = fixture['valid'] as String;
    final parts = token.split('.');
    parts[1] = base64Url
        .encode(utf8.encode('{"sub":"attacker"}'))
        .replaceAll('=', '');
    expect(
      () => verifyChatGptIdToken(
        parts.join('.'),
        fixture['jwks'] as Map<String, dynamic>,
        clientId: 'test-client',
        nonce: 'test-nonce',
        nowSeconds: nowSeconds,
      ),
      throwsA(isA<LedgerException>()),
    );
    final keys = (fixture['jwks'] as Map)['keys'] as List;
    expect(
      () => verifyChatGptIdToken(
        token,
        {
          'keys': [...keys, ...keys],
        },
        clientId: 'test-client',
        nonce: 'test-nonce',
        nowSeconds: nowSeconds,
      ),
      throwsA(isA<LedgerException>()),
    );
  });
  test(
    'auth presentation never contains credentials and survives account selection',
    () async {
      final auth = SharedChatGptAuth(credentials: stored(), now: () => now);
      addTearDown(auth.dispose);
      expect((await auth.snapshot())['activeAccountId'], 'one');
      await auth.select('two');
      final state = await auth.snapshot();
      expect(state['activeAccountId'], 'two');
      expect(jsonEncode(state), isNot(contains('accessToken')));
      expect(jsonEncode(state), isNot(contains('two-access')));
      expect(jsonEncode(state), isNot(contains('refresh')));
    },
  );
  test('model directory filters visibility and deduplicates slugs', () {
    expect(
      parseChatGptModels(
        jsonEncode({
          'models': [
            {
              'slug': 'visible',
              'display_name': 'Visible',
              'visibility': 'list',
            },
            {
              'slug': 'hidden',
              'display_name': 'Hidden',
              'visibility': 'hidden',
            },
            {
              'slug': 'visible',
              'display_name': 'Duplicate',
              'visibility': 'list',
            },
            {'slug': 'bad\nslug', 'display_name': 'Bad', 'visibility': 'list'},
          ],
        }),
      ),
      [
        {'slug': 'visible', 'displayName': 'Visible'},
      ],
    );
  });
  test(
    'refresh rotates credentials once before using model endpoint',
    () async {
      var refreshCalls = 0;
      final credentials = stored(expired: true);
      final auth = SharedChatGptAuth(
        credentials: credentials,
        now: () => now,
        client: MockClient((request) async {
          expect(request.followRedirects, isFalse);
          if (request.url.path.endsWith('/token')) {
            refreshCalls++;
            expect(request.bodyFields['refresh_token'], 'one-refresh');
            expect(request.bodyFields['client_id'], 'one');
            expect(request.bodyFields['resource'], 'https://api.openai.com/v1');
            return http.Response(
              jsonEncode({
                'access_token': 'rotated-access',
                'refresh_token': 'rotated-refresh',
                'token_type': 'Bearer',
                'expires_in': 3600,
              }),
              200,
            );
          }
          expect(request.headers['Authorization'], 'Bearer rotated-access');
          return http.Response('{"models":[]}', 200);
        }),
      );
      addTearDown(auth.dispose);
      await Future.wait([auth.listModels(), auth.listModels()]);
      expect(refreshCalls, 1);
      expect(credentials.values['chatGptAuth'], contains('rotated-refresh'));
      expect(jsonEncode(await auth.snapshot()), isNot(contains('rotated')));
    },
  );
  test(
    'terminal refresh error clears unusable tokens but retains registration',
    () async {
      final credentials = stored(expired: true);
      final auth = SharedChatGptAuth(
        credentials: credentials,
        now: () => now,
        client: MockClient(
          (_) async => http.Response('{"error":"invalid_grant"}', 400),
        ),
      );
      addTearDown(auth.dispose);
      await expectLater(auth.listModels(), throwsA(isA<LedgerException>()));
      final state = await auth.snapshot();
      expect(state['activeAccountId'], isNull);
      expect((state['accounts'] as List).first['id'], 'one');
      expect((state['accounts'] as List).first['isSignedIn'], isFalse);
    },
  );
  test(
    'account change while catalog request is running rejects stale results',
    () async {
      late SharedChatGptAuth auth;
      auth = SharedChatGptAuth(
        credentials: stored(),
        now: () => now,
        client: MockClient((_) async {
          await auth.select('two');
          return http.Response('{"models":[]}', 200);
        }),
      );
      addTearDown(auth.dispose);
      await expectLater(auth.listModels(), throwsA(isA<LedgerException>()));
    },
  );
  test(
    'pinned account mismatch aborts before Responses network request',
    () async {
      var calls = 0;
      final auth = SharedChatGptAuth(
        credentials: stored(),
        now: () => now,
        client: MockClient((_) async {
          calls++;
          return http.Response('', 200);
        }),
      );
      addTearDown(auth.dispose);
      await expectLater(
        auth.chat('model', [
          {'role': 'user', 'content': 'hello'},
        ], expectedAccountId: 'two'),
        throwsA(isA<LedgerException>()),
      );
      expect(calls, 0);
    },
  );
  test(
    'signout clears local tokens even when remote revocation fails',
    () async {
      final credentials = stored();
      final auth = SharedChatGptAuth(
        credentials: credentials,
        now: () => now,
        client: MockClient(
          (_) async => http.Response('private reflected credential', 503),
        ),
      );
      addTearDown(auth.dispose);
      expect(await auth.signOut('one'), contains('本机退出'));
      final state = await auth.snapshot();
      expect(state['activeAccountId'], isNull);
      expect((state['accounts'] as List).first['isSignedIn'], isFalse);
      expect(credentials.values['chatGptAuth'], isNot(contains('one-access')));
    },
  );
  test(
    'loopback state mismatch is rejected without consuming sign-in',
    () async {
      Uri? authorization;
      final auth = SharedChatGptAuth(
        credentials: MemoryCredentials(),
        now: () => now,
        launchBrowser: (url) async {
          authorization = url;
          return true;
        },
      );
      addTearDown(auth.dispose);
      await auth.signIn();
      expect(
        authorization!.queryParameters['client_id'],
        'dynamic_agent_client',
      );
      expect(authorization!.queryParameters['code_challenge_method'], 'S256');
      final redirect = Uri.parse(
        authorization!.queryParameters['redirect_uri']!,
      );
      expect(redirect.host, '127.0.0.1');
      final client = HttpClient();
      addTearDown(() => client.close(force: true));
      final rejected = await (await client.getUrl(
        redirect.replace(
          queryParameters: {'state': 'bad-state', 'error': 'access_denied'},
        ),
      )).close();
      expect(rejected.statusCode, 400);
      await rejected.drain<void>();
      expect((await auth.snapshot())['isAuthorizing'], isTrue);
      final stopped = auth.events.firstWhere((state) => state['error'] != null);
      final accepted = await (await client.getUrl(
        redirect.replace(
          queryParameters: {
            'state': authorization!.queryParameters['state']!,
            'error': 'access_denied',
          },
        ),
      )).close();
      expect(accepted.statusCode, 200);
      await accepted.drain<void>();
      expect(
        (await stopped.timeout(const Duration(seconds: 5)))['isAuthorizing'],
        isFalse,
      );
      expect((await auth.snapshot())['accounts'], isEmpty);
    },
  );

  test(
    'cancel completes while Safari first-page load is still pending',
    () async {
      final loading = Completer<bool>();
      final presented = Completer<void>();
      var closed = 0;
      final auth = SharedChatGptAuth(
        credentials: MemoryCredentials(),
        launchBrowser: (_) {
          presented.complete();
          return loading.future;
        },
        closeBrowser: () async {
          closed++;
        },
      );
      addTearDown(auth.dispose);
      final signIn = auth.signIn();
      final cancelled = expectLater(
        signIn,
        throwsA(
          isA<LedgerException>().having((e) => e.code, 'code', 'CANCELLED'),
        ),
      );
      await presented.future;
      await auth.cancel().timeout(const Duration(seconds: 2));
      await cancelled.timeout(const Duration(seconds: 2));
      expect(closed, 1);
      expect((await auth.snapshot())['isAuthorizing'], isFalse);
      loading.complete(true);
      await Future<void>.delayed(Duration.zero);
      final state = await auth.snapshot();
      expect(state['isAuthorizing'], isFalse);
      expect(state['loginUrl'], isNull);
      expect(state['accounts'], isEmpty);
    },
  );

  test('dispose can close pending Safari load and its event stream', () async {
    final loading = Completer<bool>();
    final presented = Completer<void>();
    final eventsClosed = Completer<void>();
    var closed = 0;
    final auth = SharedChatGptAuth(
      credentials: MemoryCredentials(),
      launchBrowser: (_) {
        presented.complete();
        return loading.future;
      },
      closeBrowser: () async {
        closed++;
      },
    );
    auth.events.listen((_) {}, onDone: eventsClosed.complete);
    final cancelled = expectLater(
      auth.signIn(),
      throwsA(
        isA<LedgerException>().having((e) => e.code, 'code', 'CANCELLED'),
      ),
    );
    await presented.future;
    await auth.dispose().timeout(const Duration(seconds: 2));
    await eventsClosed.future.timeout(const Duration(seconds: 2));
    await cancelled.timeout(const Duration(seconds: 2));
    expect(closed, 1);
    loading.complete(true);
    await auth.dispose();
    expect(closed, 1);
  });

  test(
    'failed Safari initial load closes presented browser and clears attempt',
    () async {
      var closed = 0;
      final auth = SharedChatGptAuth(
        credentials: MemoryCredentials(),
        launchBrowser: (_) async =>
            throw StateError('private authorization URL'),
        closeBrowser: () async {
          closed++;
        },
      );
      addTearDown(auth.dispose);
      await expectLater(auth.signIn(), throwsA(isA<LedgerException>()));
      final state = await auth.snapshot();
      expect(closed, 1);
      expect(state['isAuthorizing'], isFalse);
      expect(state['loginUrl'], isNull);
      expect(state['error'], isNot(contains('private')));
    },
  );

  test(
    'late launch result from replaced attempt cannot alter the new attempt',
    () async {
      final oldLoading = Completer<bool>();
      final firstPresented = Completer<void>();
      var launches = 0, closed = 0;
      final auth = SharedChatGptAuth(
        credentials: MemoryCredentials(),
        launchBrowser: (_) {
          if (++launches == 1) {
            firstPresented.complete();
            return oldLoading.future;
          }
          return Future.value(true);
        },
        closeBrowser: () async {
          closed++;
        },
      );
      addTearDown(auth.dispose);
      final oldCancelled = expectLater(
        auth.signIn(),
        throwsA(
          isA<LedgerException>().having((e) => e.code, 'code', 'CANCELLED'),
        ),
      );
      await firstPresented.future;
      final newUrl = await auth.signIn().timeout(const Duration(seconds: 2));
      await oldCancelled;
      oldLoading.complete(true);
      await Future<void>.delayed(Duration.zero);
      final state = await auth.snapshot();
      expect(state['isAuthorizing'], isTrue);
      expect(state['loginUrl'], newUrl);
      expect(closed, 1);
    },
  );
}

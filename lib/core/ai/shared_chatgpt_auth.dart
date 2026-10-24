import 'dart:async';
import 'dart:convert';
import '../agent/agent_models.dart';
import '../agent/agent_wire.dart';
import 'dart:io';
import 'dart:math';

import 'package:crypto/crypto.dart';
import 'package:http/http.dart' as http;
import 'package:url_launcher/url_launcher.dart';

import '../ledger_repository.dart';
import 'ai_transport.dart';
import 'shared_credentials.dart';

const _issuer = 'https://auth.openai.com';
const _resource = 'https://api.openai.com/v1';
const _planScope = 'chatgpt.tokens.use.direct';
const _dynamicClient = 'dynamic_agent_client';

/// Public-client OAuth shared by iOS and other Dart IO platforms.
/// The protocol mirrors Android's implementation and the official OSS flow:
/// https://developers.openai.com/siwc/token-sharing-open-source/sign-in
class SharedChatGptAuth {
  SharedChatGptAuth({
    SharedCredentials? credentials,
    http.Client? client,
    DateTime Function()? now,
    Future<bool> Function(Uri)? launchBrowser,
    Future<void> Function()? closeBrowser,
  }) : _credentials = credentials ?? SecureSharedCredentials(),
       _client = client ?? http.Client(),
       _ownsClient = client == null,
       _now = now ?? DateTime.now,
       _closeBrowser =
           closeBrowser ??
           (launchBrowser == null && Platform.isIOS
               ? closeInAppWebView
               : () async {}),
       _launchBrowser =
           launchBrowser ??
           ((url) => launchUrl(
             url,
             mode: Platform.isIOS
                 ? LaunchMode.inAppBrowserView
                 : LaunchMode.externalApplication,
           ));

  final SharedCredentials _credentials;
  final http.Client _client;
  final bool _ownsClient;
  bool _disposed = false;
  final DateTime Function() _now;
  final Future<bool> Function(Uri) _launchBrowser;
  final Future<void> Function() _closeBrowser;
  bool _browserOpen = false;
  Completer<bool>? _browserCancellation;
  final _events = StreamController<Map<String, dynamic>>.broadcast();
  Future<void>? _ready;
  Future<void> _tail = Future<void>.value();
  Map<String, dynamic>? _saved;
  Map<String, dynamic> _state = {
    'accounts': <dynamic>[],
    'activeAccountId': null,
    'isAuthorizing': false,
    'loginUrl': null,
    'error': null,
    'showWelcome': false,
  };
  HttpServer? _server;
  Timer? _timeout;
  int _attempt = 0;
  String? _retryClientId;
  int get _seconds => _now().millisecondsSinceEpoch ~/ 1000;
  Stream<Map<String, dynamic>> get events => _events.stream;

  Future<T> _serial<T>(Future<T> Function() work) {
    final result = _tail.then((_) => work());
    _tail = result.then<void>((_) {}, onError: (Object _, StackTrace _) {});
    return result;
  }

  Future<void> _load() async {
    try {
      final raw = await _credentials.get('chatGptAuth');
      final saved = raw == null
          ? <String, dynamic>{
              'hostId': _hostId(),
              'activeId': null,
              'accounts': <dynamic>[],
            }
          : decodeAiObject(raw);
      if (saved['hostId'] is! String ||
          !(saved['hostId'] as String).startsWith('urn:uuid:') ||
          saved['accounts'] is! List) {
        throw const FormatException();
      }
      final accounts = (saved['accounts'] as List).map(aiObject).toList();
      if (accounts.any(
            (a) =>
                a['clientId'] is! String ||
                a['email'] is! String ||
                a['subject'] is! String,
          ) ||
          accounts.map((a) => a['clientId']).toSet().length !=
              accounts.length) {
        throw const FormatException();
      }
      if (raw == null) await _credentials.set('chatGptAuth', jsonEncode(saved));
      _saved = saved;
      _publish();
    } catch (_) {
      _saved = null;
      _state = {..._state, 'error': 'ChatGPT 安全存储暂不可用；原有凭据未被覆盖。'};
      _emit();
    }
  }

  Future<Map<String, dynamic>> snapshot() async {
    if (_disposed) aiFailure('ChatGPT 登录服务已关闭。');
    await (_ready ??= _load());
    return _publicState();
  }

  Map<String, dynamic> _publicState() =>
      jsonDecode(jsonEncode(_state)) as Map<String, dynamic>;
  void _emit() {
    if (!_events.isClosed) _events.add(_publicState());
  }

  Map<String, dynamic> _current() {
    final value = _saved;
    if (value == null) aiFailure('ChatGPT 安全存储不可用，无法读取或保存登录凭据。');
    return value;
  }

  List<Map<String, dynamic>> _accounts() =>
      (_current()['accounts'] as List).map(aiObject).toList();
  Map<String, dynamic>? _account(String? id) =>
      _accounts().where((a) => a['clientId'] == id).firstOrNull;

  void _publish({bool? welcome}) {
    final saved = _current();
    final accounts = _accounts().map((a) {
      final tokens = a['tokens'] as Map<String, dynamic>?;
      return {
        'id': a['clientId'],
        'email': a['email'],
        'isSignedIn': tokens != null,
        'canUsePlan':
            (tokens?['scopes'] as List?)?.contains(_planScope) == true,
      };
    }).toList();
    _state = {
      ..._state,
      'accounts': accounts,
      'activeAccountId': saved['activeId'],
      'showWelcome':
          welcome ??
          (_state['activeAccountId'] == saved['activeId'] &&
              saved['activeId'] != null &&
              _state['showWelcome'] == true),
    };
    _emit();
  }

  Future<void> _persist(Map<String, dynamic> next) async {
    try {
      await _credentials.set('chatGptAuth', jsonEncode(next));
    } catch (_) {
      aiFailure('ChatGPT 安全凭据保存失败，请重试。');
    }
    _saved = next;
    _publish();
  }

  Future<String> signIn({
    String? accountId,
    bool requestPlanPermission = false,
  }) async {
    await snapshot();
    final prepared = await _serial(() async {
      if (_disposed) aiFailure('ChatGPT 登录服务已关闭。');
      final current = _current();
      final account = accountId == null ? null : _account(accountId);
      if (accountId != null && account == null) aiFailure('未找到保存的 ChatGPT 账号。');
      await _cancel();
      final serial = _attempt;
      try {
        final server = await HttpServer.bind(
          InternetAddress.loopbackIPv4,
          0,
          backlog: 4,
          shared: false,
        );
        server.idleTimeout = const Duration(seconds: 5);
        _server = server;
        final state = _secret(), nonce = _secret(), verifier = _secret();
        final redirect = 'http://127.0.0.1:${server.port}/auth/callback';
        final clientId =
            account?['clientId'] as String? ?? _retryClientId ?? _dynamicClient;
        final url = Uri.parse('$_issuer/api/accounts/authorize').replace(
          queryParameters: {
            'client_id': clientId,
            if (clientId == _dynamicClient) 'agent_name_hint': 'PennyPilot',
            'ext_agent_host_id': current['hostId'] as String,
            if (account != null && account['email'] != '未提供邮箱')
              'login_hint': account['email'] as String,
            'response_type': 'code',
            'redirect_uri': redirect,
            'scope':
                'openid profile email offline_access resource.invoke $_planScope',
            'resource': _resource,
            'state': state,
            'nonce': nonce,
            'code_challenge_method': 'S256',
            'code_challenge': _b64(
              sha256.convert(ascii.encode(verifier)).bytes,
            ),
            if (requestPlanPermission) 'prompt': 'consent',
          },
        );
        _state = {
          ..._state,
          'isAuthorizing': true,
          'loginUrl': url.toString(),
          'error': null,
        };
        _emit();
        var consumed = false;
        server.listen(
          (request) async {
            var accepted = false;
            try {
              final uri = request.uri;
              accepted =
                  !consumed &&
                  serial == _attempt &&
                  request.method == 'GET' &&
                  uri.path == '/auth/callback' &&
                  !uri.hasFragment &&
                  uri.toString().length <= 16384 &&
                  request.headers.value(HttpHeaders.hostHeader) ==
                      '127.0.0.1:${server.port}' &&
                  ['state', 'code', 'client_id', 'error'].every(
                    (key) => (uri.queryParametersAll[key]?.length ?? 0) <= 1,
                  ) &&
                  sameOAuthSecret(uri.queryParameters['state'], state) &&
                  ((uri.queryParameters['code']?.isNotEmpty == true) !=
                      (uri.queryParameters['error']?.isNotEmpty == true));
              if (accepted) consumed = true;
              request.response.statusCode = accepted ? 200 : 400;
              request.response.headers
                ..contentType = ContentType.text
                ..set('Cache-Control', 'no-store')
                ..set('Content-Security-Policy', "default-src 'none'")
                ..set('X-Content-Type-Options', 'nosniff');
              request.response.write(
                accepted
                    ? 'PennyPilot 已收到授权结果。请返回应用查看登录状态。'
                    : '请求无效，请返回 PennyPilot 继续登录。',
              );
              await request.response.close();
              if (accepted) {
                await server.close(force: true);
                unawaited(
                  _finish(
                    serial,
                    uri,
                    clientId,
                    redirect,
                    verifier,
                    nonce,
                    account?['subject'] as String?,
                  ),
                );
              }
            } catch (_) {
              try {
                await request.response.close();
              } catch (_) {}
            }
          },
          onError: (Object _) {
            if (serial == _attempt) {
              unawaited(_failed(serial, 'ChatGPT 登录连接中断，请重试。'));
            }
          },
        );
        _timeout = Timer(const Duration(minutes: 10), () {
          unawaited(_failed(serial, 'ChatGPT 登录已超时，请重试。'));
        });
        // Safari's launch Future resolves after its first page load, which may
        // wait indefinitely on a network. Keep it outside the state queue so
        // cancel, timeout and dispose can still close the browser/listener.
        _browserOpen = true;
        final cancellation = Completer<bool>();
        _browserCancellation = cancellation;
        return (url: url, attempt: serial, cancelled: cancellation.future);
      } on LedgerException {
        rethrow;
      } catch (_) {
        await _cancel();
        aiFailure('无法启动 ChatGPT 登录，请稍后重试。');
      }
    });
    if (prepared.attempt != _attempt || _disposed) {
      throw const LedgerException('ChatGPT 登录已取消。', code: 'CANCELLED');
    }
    try {
      final launched = await Future.any([
        _launchBrowser(prepared.url),
        prepared.cancelled,
      ]);
      return await _serial(() async {
        if (prepared.attempt != _attempt || _disposed) {
          throw const LedgerException('ChatGPT 登录已取消。', code: 'CANCELLED');
        }
        if (!launched) {
          await _cancel();
          aiFailure('无法打开浏览器，请检查系统浏览器设置后重试。');
        }
        return prepared.url.toString();
      });
    } on LedgerException {
      rethrow;
    } catch (_) {
      await _failed(prepared.attempt, '无法启动 ChatGPT 登录，请稍后重试。');
      aiFailure('无法启动 ChatGPT 登录，请稍后重试。');
    }
  }

  Future<void> _finish(
    int serial,
    Uri callback,
    String pendingClient,
    String redirect,
    String verifier,
    String nonce,
    String? subject,
  ) async {
    String? issued;
    try {
      if (callback.queryParameters['error'] != null) {
        aiFailure('ChatGPT 授权未完成，请重新登录。');
      }
      final returned = callback.queryParameters['client_id'];
      issued = pendingClient == _dynamicClient ? returned : pendingClient;
      if (issued == null ||
          issued.isEmpty ||
          issued == _dynamicClient ||
          issued.length > 512 ||
          (pendingClient != _dynamicClient &&
              returned != null &&
              returned != pendingClient)) {
        aiFailure('ChatGPT 返回的应用注册信息无效，请重新登录。');
      }
      final response = await _tokenRequest({
        'grant_type': 'authorization_code',
        'client_id': issued,
        'code': callback.queryParameters['code']!,
        'code_verifier': verifier,
        'redirect_uri': redirect,
        'resource': _resource,
      });
      final tokens = _parseTokens(response);
      final identity = await _verifyIdentity(tokens['idToken'], issued, nonce);
      if (subject != null && subject != identity['sub']) {
        aiFailure('登录账号与所选 ChatGPT 账号不一致。');
      }
      await _serial(() async {
        if (serial != _attempt) return;
        final previous = _account(issued);
        if (previous != null && previous['subject'] != identity['sub']) {
          aiFailure('账号身份与原有注册信息不一致。');
        }
        final welcome =
            (tokens['scopes'] as List).contains(_planScope) &&
            previous?['welcomeShown'] != true;
        final next = {
          ..._current(),
          'activeId': issued,
          'accounts': [
            ..._accounts().where((a) => a['clientId'] != issued),
            {
              'clientId': issued,
              'email': identity['email'] ?? '未提供邮箱',
              'subject': identity['sub'],
              'tokens': tokens,
              'welcomeShown': welcome || previous?['welcomeShown'] == true,
            },
          ],
        };
        await _persist(next);
        _retryClientId = null;
        await _cancel();
        _state = {..._state, 'error': null};
        _publish(welcome: welcome);
      });
    } catch (error) {
      if (serial == _attempt &&
          error is _OAuthFailure &&
          error.code == 'invalid_grant' &&
          subject == null &&
          issued != null) {
        _retryClientId = issued;
      }
      await _failed(
        serial,
        error is LedgerException ? error.message : 'ChatGPT 登录验证失败，请重新登录。',
      );
    }
  }

  Future<void> _failed(int serial, String message) => _serial(() async {
    if (serial != _attempt) return;
    await _cancel();
    _state = {..._state, 'error': message};
    _emit();
  });

  Future<void> _cancel() async {
    _attempt++;
    final cancellation = _browserCancellation;
    _browserCancellation = null;
    if (cancellation != null && !cancellation.isCompleted) {
      cancellation.complete(false);
    }
    _timeout?.cancel();
    _timeout = null;
    final server = _server;
    _server = null;
    if (server != null) {
      try {
        await server.close(force: true);
      } catch (_) {}
    }
    if (_browserOpen) {
      try {
        await _closeBrowser();
      } catch (_) {}
    }
    _browserOpen = false;
    _state = {..._state, 'isAuthorizing': false, 'loginUrl': null};
    _emit();
  }

  Future<void> cancel() => _serial(_cancel);

  Future<void> select(String accountId) async {
    await snapshot();
    await _serial(() async {
      final account = _account(accountId);
      if (account == null || account['tokens'] == null) {
        aiFailure('此 ChatGPT 账号已退出，请重新登录。');
      }
      await _cancel();
      await _persist({..._current(), 'activeId': accountId});
      _publish(welcome: false);
    });
  }

  Future<void> dismissWelcome() async {
    await snapshot();
    _state = {..._state, 'showWelcome': false};
    _emit();
  }

  Future<String?> signOut(String accountId) async {
    await snapshot();
    return _serial(() async {
      final account = _account(accountId);
      if (account == null) return null;
      await _cancel();
      final tokens = account['tokens'] as Map<String, dynamic>?;
      var revoked = tokens == null;
      if (tokens?['refreshToken'] is String) {
        try {
          final metadata = await _metadata();
          final endpoint = _trustedAuthUri(metadata['revocation_endpoint']);
          final request = http.Request('POST', endpoint)
            ..bodyFields = {
              'token': tokens!['refreshToken'] as String,
              'token_type_hint': 'refresh_token',
              'client_id': accountId,
            };
          await aiRequest(_client, request, maxBytes: 1024 * 1024);
          revoked = true;
        } catch (_) {}
      }
      await _clearTokens(accountId);
      final warning = revoked
          ? null
          : '已在本机退出；未能确认远程会话已撤销，可前往 ChatGPT 设置断开 PennyPilot。';
      _state = {..._state, 'error': warning};
      _emit();
      return warning;
    });
  }

  Future<void> _clearTokens(String accountId) => _persist({
    ..._current(),
    'activeId': _current()['activeId'] == accountId
        ? null
        : _current()['activeId'],
    'accounts': _accounts()
        .map((a) => a['clientId'] == accountId ? {...a, 'tokens': null} : a)
        .toList(),
  });

  Future<({String accountId, String token})> _access() async {
    await snapshot();
    return _serial(() async {
      final account = _account(_current()['activeId'] as String?);
      if (account == null || account['tokens'] == null) {
        aiFailure('请先在 AI 设置中登录 ChatGPT。');
      }
      final id = account['clientId'] as String;
      var tokens = aiObject(account['tokens']);
      if (!(tokens['scopes'] as List).contains(_planScope)) {
        aiFailure('请允许 PennyPilot 使用 ChatGPT 套餐额度。');
      }
      final expiry = tokens['expiresAt'] as int;
      if (!(expiry > _seconds + 60 ||
          (expiry > _seconds &&
              (tokens['earliestRefreshAt'] as int? ?? 0) > _seconds))) {
        final refresh = tokens['refreshToken'];
        if (refresh is! String || refresh.isEmpty) {
          await _clearTokens(id);
          aiFailure('ChatGPT 登录已到期，请重新登录。');
        }
        try {
          final response = await _tokenRequest({
            'grant_type': 'refresh_token',
            'client_id': id,
            'refresh_token': refresh,
            'resource': _resource,
          });
          tokens = _parseTokens(response, previous: tokens);
          if (response['id_token'] != null) {
            final identity = await _verifyIdentity(tokens['idToken'], id, null);
            if (identity['sub'] != account['subject']) {
              aiFailure('ChatGPT 续期返回的账号不一致，请重新登录。');
            }
          }
          await _persist({
            ..._current(),
            'accounts': _accounts()
                .map((a) => a['clientId'] == id ? {...a, 'tokens': tokens} : a)
                .toList(),
          });
        } on _OAuthFailure catch (failure) {
          if (const {
            'invalid_grant',
            'invalid_refresh_token',
            'token_expired',
            'refresh_token_expired',
            'refresh_token_invalidated',
            'refresh_token_reused',
          }.contains(failure.code)) {
            await _clearTokens(id);
            aiFailure('ChatGPT 登录已失效，请重新登录。');
          }
          aiFailure('ChatGPT 认证服务暂不可用，请稍后重试。');
        }
      }
      if (!(tokens['scopes'] as List).contains(_planScope)) {
        aiFailure('ChatGPT 套餐授权未启用，请重新授权。');
      }
      return (
        accountId: id,
        token: checkedBearer(tokens['accessToken'] as String?),
      );
    });
  }

  void _sameAccount(String id) {
    if (_current()['activeId'] != id || _account(id)?['tokens'] == null) {
      aiFailure('ChatGPT 账号已变化，请重新发起请求。');
    }
  }

  Future<List<Map<String, dynamic>>> listModels() async {
    final access = await _access();
    final request = http.Request('GET', Uri.parse('$_resource/models'))
      ..headers.addAll({
        'Authorization': 'Bearer ${access.token}',
        'Accept': 'application/json',
      });
    _sameAccount(access.accountId);
    final response = await aiRequest(
      _client,
      request,
      maxBytes: 2 * 1024 * 1024,
    );
    _sameAccount(access.accountId);
    return parseChatGptModels(response.body);
  }

  Future<String> chat(
    String model,
    List<Map<String, dynamic>> messages, {
    bool jsonMode = false,
    String? expectedAccountId,
    Future<void> Function()? beforeRequest,
  }) async {
    final access = await _access();
    if (expectedAccountId != null && access.accountId != expectedAccountId) {
      aiFailure('ChatGPT 账号已变化，请重新发起查询。');
    }
    final request = http.Request('POST', Uri.parse('$_resource/responses'))
      ..headers.addAll({
        'Authorization': 'Bearer ${access.token}',
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream',
      })
      ..body = jsonEncode({
        'model': checkedModel(model),
        'stream': true,
        'store': false,
        'include': ['reasoning.encrypted_content'],
        'input': messages
            .map(
              (m) => {
                'type': 'message',
                'role': m['role'] == 'system' ? 'developer' : m['role'],
                'content': m['content'],
              },
            )
            .toList(),
        if (jsonMode)
          'instructions': '只输出一个有效的 JSON 对象，不要输出 Markdown 代码块或额外说明。',
        if (jsonMode)
          'text': {
            'format': {'type': 'json_object'},
          },
      });
    if (request.bodyBytes.length > 4 * 1024 * 1024) aiFailure('请求内容过大，请缩小范围。');
    _sameAccount(access.accountId);
    await beforeRequest?.call();
    _sameAccount(access.accountId);
    final response = await aiRequest(_client, request);
    _sameAccount(access.accountId);
    final text = decodeResponsesStream(
      response.body,
      contentType: response.contentType,
    );
    if (jsonMode) decodeAiObject(text);
    return text;
  }

  Future<AgentReply> agentTurn(
    String model,
    List<Map<String, dynamic>> messages,
    List<Map<String, dynamic>> tools, {
    required String expectedAccountId,
    Future<void> Function()? beforeRequest,
  }) async {
    final access = await _access();
    if (access.accountId != expectedAccountId) {
      aiFailure('ChatGPT账号已切换，本次任务已停止。');
    }
    final request = http.Request('POST', Uri.parse('$_resource/responses'))
      ..headers.addAll({
        'Authorization': 'Bearer ${access.token}',
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream',
      })
      ..body = jsonEncode({
        'model': checkedModel(model),
        'stream': true,
        'store': false,
        'include': ['reasoning.encrypted_content'],
        'input': responsesAgentInput(messages, access.accountId),
        if (tools.isNotEmpty) 'tools': responsesAgentTools(tools),
      });
    if (request.bodyBytes.length > 4 * 1024 * 1024) {
      aiFailure('Agent请求过大，请缩小范围。');
    }
    await beforeRequest?.call();
    _sameAccount(access.accountId);
    final response = await aiRequest(_client, request);
    _sameAccount(access.accountId);
    await beforeRequest?.call();
    final output = decodeResponsesOutput(
      response.body,
      contentType: response.contentType,
    );
    return agentReplyFromResponses(
      (output['items'] as List).map(agentMap).toList(),
      access.accountId,
      usage: output['usage'] == null ? null : agentMap(output['usage']),
      allowedTools: tools
          .map((t) => agentMap(t['function'])['name'] as String)
          .toSet(),
    );
  }

  Future<Map<String, dynamic>> _metadata() async {
    final result = await aiRequest(
      _client,
      http.Request(
        'GET',
        Uri.parse('$_issuer/.well-known/openid-configuration'),
      ),
      maxBytes: 1024 * 1024,
    );
    final metadata = decodeAiObject(result.body);
    if (metadata['issuer'] != _issuer) aiFailure('ChatGPT 认证服务配置无效。');
    return metadata;
  }

  Future<Map<String, dynamic>> _verifyIdentity(
    Object? token,
    String clientId,
    String? nonce,
  ) async {
    final metadata = await _metadata();
    final response = await aiRequest(
      _client,
      http.Request('GET', _trustedAuthUri(metadata['jwks_uri'])),
      maxBytes: 1024 * 1024,
    );
    if (token is! String) aiFailure('ChatGPT 未返回身份凭据，请重新登录。');
    return verifyChatGptIdToken(
      token,
      decodeAiObject(response.body),
      clientId: clientId,
      nonce: nonce,
      nowSeconds: _seconds,
    );
  }

  Future<Map<String, dynamic>> _tokenRequest(Map<String, String> fields) async {
    final request =
        http.Request('POST', Uri.parse('$_issuer/api/accounts/oauth/token'))
          ..followRedirects = false
          ..bodyFields = fields;
    try {
      final response = await _client
          .send(request)
          .timeout(const Duration(seconds: 30));
      final bytes = <int>[];
      await for (final chunk in response.stream.timeout(
        const Duration(seconds: 20),
      )) {
        if (bytes.length + chunk.length > 1024 * 1024) {
          aiFailure('ChatGPT 认证服务返回异常。');
        }
        bytes.addAll(chunk);
      }
      Map<String, dynamic>? body;
      try {
        body = jsonDecode(utf8.decode(bytes)) as Map<String, dynamic>;
      } catch (_) {}
      if (response.statusCode < 200 || response.statusCode >= 300) {
        final error = body?['error'];
        final code = error is String
            ? error
            : error is Map
            ? error['code']
            : null;
        throw _OAuthFailure(
          code is String && RegExp(r'^[a-zA-Z0-9_]{1,100}$').hasMatch(code)
              ? code
              : null,
        );
      }
      if (body == null) aiFailure('ChatGPT 认证服务返回异常。');
      return body;
    } on _OAuthFailure {
      rethrow;
    } on LedgerException {
      rethrow;
    } catch (_) {
      aiFailure('无法连接 ChatGPT 认证服务，请检查网络后重试。');
    }
  }

  Map<String, dynamic> _parseTokens(
    Map<String, dynamic> response, {
    Map<String, dynamic>? previous,
  }) {
    final expiry = response['expires_in'];
    final access = response['access_token'];
    if (access is! String ||
        access.isEmpty ||
        response['token_type'] is! String ||
        (response['token_type'] as String).toLowerCase() != 'bearer' ||
        expiry is! int ||
        expiry < 1 ||
        expiry > 2678400) {
      aiFailure('ChatGPT 登录凭据不完整，请重新登录。');
    }
    checkedBearer(access);
    final scope = response['scope'];
    if (scope != null && scope is! String) aiFailure('ChatGPT 授权范围无效。');
    for (final field in ['refresh_token', 'id_token']) {
      if (response[field] != null &&
          (response[field] is! String || (response[field] as String).isEmpty)) {
        aiFailure('ChatGPT 登录凭据不完整。');
      }
    }
    return {
      'accessToken': access,
      'refreshToken': response['refresh_token'] ?? previous?['refreshToken'],
      'idToken': response['id_token'] ?? previous?['idToken'],
      'expiresAt': _seconds + expiry,
      'earliestRefreshAt': response['earliest_refresh_at'] is int
          ? response['earliest_refresh_at']
          : 0,
      'scopes': scope is String
          ? scope.split(' ').where((v) => v.isNotEmpty).toList()
          : previous?['scopes'] ?? <String>[],
    };
  }

  Future<void> dispose() async {
    if (_disposed) return;
    _disposed = true;
    if (_ownsClient) _client.close();
    await cancel();
    await _events.close();
  }
}

class _OAuthFailure implements Exception {
  const _OAuthFailure(this.code);
  final String? code;
  @override
  String toString() => 'ChatGPT 认证服务请求失败';
}

String _secret() =>
    _b64(List<int>.generate(32, (_) => Random.secure().nextInt(256)));
String _b64(List<int> bytes) => base64Url.encode(bytes).replaceAll('=', '');
String _hostId() {
  final bytes = List<int>.generate(16, (_) => Random.secure().nextInt(256));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  final hex = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  return 'urn:uuid:${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
}

bool sameOAuthSecret(String? actual, String expected) {
  if (actual == null) return false;
  final a = utf8.encode(actual), b = utf8.encode(expected);
  var difference = a.length ^ b.length;
  for (var i = 0; i < b.length; i++) {
    difference |= (i < a.length ? a[i] : 0) ^ b[i];
  }
  return difference == 0;
}

Uri _trustedAuthUri(Object? value) {
  if (value is! String) aiFailure('ChatGPT 认证服务配置无效。');
  final uri = Uri.tryParse(value);
  if (uri == null ||
      uri.scheme != 'https' ||
      uri.host != 'auth.openai.com' ||
      uri.port != 443 ||
      uri.userInfo.isNotEmpty ||
      uri.hasFragment) {
    aiFailure('ChatGPT 认证服务配置无效。');
  }
  return uri;
}

List<Map<String, dynamic>> parseChatGptModels(String body) {
  final entries = decodeAiObject(body)['models'];
  if (entries is! List) aiFailure('ChatGPT 模型目录格式无效。');
  final seen = <String>{};
  final models = <Map<String, dynamic>>[];
  for (final raw in entries) {
    if (raw is! Map || raw['visibility'] != 'list') continue;
    final slug = raw['slug'], label = raw['display_name'];
    if (slug is! String ||
        label is! String ||
        slug.trim().isEmpty ||
        label.trim().isEmpty ||
        slug.length > 256 ||
        label.length > 256 ||
        RegExp(r'[\x00-\x1f\x7f]').hasMatch('$slug$label') ||
        !seen.add(slug)) {
      continue;
    }
    models.add({'slug': slug, 'displayName': label});
  }
  return models;
}

/// Strict RSASSA-PKCS1-v1_5 SHA-256 verification with public-key modular exponentiation.
/// No private key operation or JWT-controlled network endpoint is used.
Map<String, dynamic> verifyChatGptIdToken(
  String token,
  Map<String, dynamic> jwks, {
  required String clientId,
  String? nonce,
  required int nowSeconds,
}) {
  try {
    final parts = token.split('.');
    if (parts.length != 3 || parts.any((p) => p.isEmpty || p.length > 32768)) {
      throw const FormatException();
    }
    List<int> decode(String value) =>
        base64Url.decode(base64Url.normalize(value));
    final header =
        jsonDecode(utf8.decode(decode(parts[0]))) as Map<String, dynamic>;
    if (header['alg'] != 'RS256' ||
        header.containsKey('crit') ||
        header['kid'] is! String) {
      throw const FormatException();
    }
    final keys = (jwks['keys'] as List)
        .whereType<Map>()
        .where(
          (key) =>
              key['kid'] == header['kid'] &&
              key['kty'] == 'RSA' &&
              (key['use'] == null || key['use'] == 'sig') &&
              (key['alg'] == null || key['alg'] == 'RS256'),
        )
        .toList();
    if (keys.length != 1) throw const FormatException();
    BigInt integer(List<int> bytes) => bytes.fold(
      BigInt.zero,
      (value, byte) => (value << 8) | BigInt.from(byte),
    );
    final key = keys.single;
    final modulusBytes = decode(key['n'] as String);
    final modulus = integer(modulusBytes),
        exponent = integer(decode(key['e'] as String));
    if (modulus.bitLength < 2048 ||
        modulus.bitLength > 8192 ||
        exponent < BigInt.from(3) ||
        exponent.isEven ||
        exponent.bitLength > 32) {
      throw const FormatException();
    }
    final signatureBytes = decode(parts[2]);
    final size = (modulus.bitLength + 7) ~/ 8;
    final signature = integer(signatureBytes);
    if (signatureBytes.length != size || signature >= modulus) {
      throw const FormatException();
    }
    final encoded = signature
        .modPow(exponent, modulus)
        .toRadixString(16)
        .padLeft(size * 2, '0');
    final digestInfo =
        '3031300d060960864801650304020105000420${sha256.convert(ascii.encode('${parts[0]}.${parts[1]}'))}';
    final paddingLength = size - 3 - digestInfo.length ~/ 2;
    if (paddingLength < 8 ||
        !sameOAuthSecret(
          encoded,
          '0001${List.filled(paddingLength, 'ff').join()}00$digestInfo',
        )) {
      throw const FormatException();
    }
    final claims =
        jsonDecode(utf8.decode(decode(parts[1]))) as Map<String, dynamic>;
    final aud = claims['aud'];
    final audiences = aud is String
        ? [aud]
        : aud is List
        ? aud
        : <dynamic>[];
    if (claims['iss'] != _issuer ||
        !audiences.contains(clientId) ||
        ((audiences.length > 1 || claims.containsKey('azp')) &&
            claims['azp'] != clientId) ||
        claims['exp'] is! int ||
        (claims['exp'] as int) <= nowSeconds ||
        (claims['nbf'] != null &&
            (claims['nbf'] is! int ||
                (claims['nbf'] as int) > nowSeconds + 60)) ||
        (claims['iat'] != null &&
            (claims['iat'] is! int ||
                (claims['iat'] as int) > nowSeconds + 60)) ||
        (nonce != null &&
            (claims['nonce'] is! String ||
                !sameOAuthSecret(claims['nonce'] as String, nonce))) ||
        claims['sub'] is! String ||
        (claims['sub'] as String).isEmpty ||
        (claims['email'] != null && claims['email'] is! String)) {
      throw const FormatException();
    }
    return {'sub': claims['sub'], 'email': claims['email']};
  } catch (_) {
    aiFailure('ChatGPT 身份验证未通过，请重新登录。');
  }
}

import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../ledger_repository.dart';

Never aiFailure(String message) =>
    throw LedgerException(message, code: 'AI_ERROR');

Map<String, dynamic> aiObject(Object? value) {
  if (value is! Map<String, dynamic>) aiFailure('AI 返回的数据格式无效，请重试。');
  return value;
}

Map<String, dynamic> decodeAiObject(String text) {
  try {
    var cleaned = text.trim();
    if (cleaned.startsWith('```') && cleaned.endsWith('```')) {
      cleaned = cleaned.replaceFirst(RegExp(r'^```(?:json)?\s*'), '');
      cleaned = cleaned.substring(0, cleaned.length - 3).trim();
    }
    return aiObject(jsonDecode(cleaned));
  } on LedgerException {
    rethrow;
  } catch (_) {
    aiFailure('AI 返回的数据格式无效，请重试。');
  }
}

Uri secureAiEndpoint(String value) {
  final uri = Uri.tryParse(value);
  if (uri == null ||
      uri.scheme != 'https' ||
      uri.host.isEmpty ||
      uri.userInfo.isNotEmpty ||
      uri.hasQuery ||
      uri.hasFragment) {
    aiFailure('请输入有效的 HTTPS 服务地址，不要附带密钥、查询参数或片段。');
  }
  return uri;
}

String checkedModel(Object? value) {
  if (value is! String ||
      value.trim().isEmpty ||
      value.length > 256 ||
      RegExp(r'[\x00-\x1f\x7f]').hasMatch(value)) {
    aiFailure('请先在 AI 设置中选择并保存模型。');
  }
  return value.trim();
}

String checkedBearer(String? value) {
  if (value == null ||
      value.trim().isEmpty ||
      value.length > 32768 ||
      RegExp(r'[\s\x00-\x1f\x7f]').hasMatch(value)) {
    aiFailure('AI 服务凭据未配置或已失效，请到 AI 设置中重新配置。');
  }
  return value;
}

/// No redirect following or retries: a billed POST/rotating token is single-use.
Future<({String body, String? contentType})> aiRequest(
  http.Client client,
  http.Request request, {
  int maxBytes = 8 * 1024 * 1024,
}) async {
  request.followRedirects = false;
  try {
    final response = await client
        .send(request)
        .timeout(const Duration(seconds: 60));
    final chunks = <int>[];
    await for (final part in response.stream.timeout(
      const Duration(seconds: 60),
    )) {
      if (chunks.length + part.length > maxBytes) {
        aiFailure('AI 服务返回内容过大，请缩小查询范围。');
      }
      chunks.addAll(part);
    }
    if (response.statusCode < 200 || response.statusCode >= 300) {
      switch (response.statusCode) {
        case 401:
          aiFailure('AI 服务凭据已失效，请重新登录或检查 API Key。');
        case 403:
          aiFailure('当前账号没有访问此模型的权限，请检查授权或更换模型。');
        case 429:
          aiFailure('AI 服务额度不足或请求过于频繁，请稍后重试。');
        default:
          aiFailure('AI 服务请求失败（HTTP ${response.statusCode}），请稍后重试。');
      }
    }
    return (
      body: utf8.decode(chunks),
      contentType: response.headers['content-type'],
    );
  } on LedgerException {
    rethrow;
  } catch (_) {
    // Never surface provider bodies, request URIs, keys or transport exceptions.
    aiFailure('无法连接 AI 服务，请检查网络后重试。');
  }
}

/// Only completed Responses output is accepted; deltas and [DONE] are provisional.
String decodeResponsesStream(String body, {String? contentType}) {
  final prefix = body.replaceFirst('\uFEFF', '').trimLeft();
  if (contentType != null
      ? contentType.split(';').first.trim().toLowerCase() != 'text/event-stream'
      : !(prefix.startsWith('data:') ||
            prefix.startsWith('event:') ||
            prefix.startsWith(':'))) {
    aiFailure('ChatGPT 返回了非流式响应，请稍后重试。');
  }
  final added = <int, Map<String, dynamic>>{};
  final done = <int, Map<String, dynamic>>{};
  var data = <String>[];
  String? eventName;
  var first = true;
  for (var line in body.split('\n').take(body.split('\n').length - 1)) {
    if (line.endsWith('\r')) line = line.substring(0, line.length - 1);
    if (first) line = line.replaceFirst('\uFEFF', '');
    first = false;
    if (utf8.encode(line).length > 512 * 1024) aiFailure('ChatGPT 数据流单行过长。');
    if (line.isEmpty) {
      if (data.isNotEmpty) {
        final payload = data.join('\n');
        if (payload != '[DONE]' && payload != '[END]') {
          final event = decodeAiObject(payload);
          final type = event['type'];
          if (type is! String ||
              (eventName != null &&
                  eventName != '' &&
                  eventName != 'message' &&
                  eventName != type)) {
            aiFailure('ChatGPT 数据流事件类型无效。');
          }
          if (type == 'response.output_item.added' ||
              type == 'response.output_item.done') {
            final index = event['output_index'];
            final item = aiObject(event['item']);
            if (index is! int ||
                index < 0 ||
                index > 4095 ||
                item['id'] is! String ||
                (item['id'] as String).isEmpty ||
                item['type'] is! String) {
              aiFailure('ChatGPT 输出项格式无效。');
            }
            if (type.endsWith('.added')) {
              if (added.containsKey(index) || done.containsKey(index)) {
                aiFailure('ChatGPT 输出项重复。');
              }
              added[index] = item;
            } else {
              final initial = added[index];
              if (done.containsKey(index) ||
                  (initial != null &&
                      (initial['id'] != item['id'] ||
                          initial['type'] != item['type']))) {
                aiFailure('ChatGPT 输出项不一致。');
              }
              done[index] = item;
            }
          } else if (type == 'response.completed') {
            final response = aiObject(event['response']);
            final output = response['output'];
            if (response['status'] != 'completed' || output is! List) {
              aiFailure('ChatGPT 响应未完整完成。');
            }
            if (output.isNotEmpty) return _outputText(output);
            final indices = done.keys.toList()..sort();
            if (!added.keys.every(done.containsKey) ||
                indices.asMap().entries.any(
                  (entry) => entry.key != entry.value,
                ) ||
                done.values.map((item) => item['id']).toSet().length !=
                    done.length) {
              aiFailure('ChatGPT 流式输出不完整。');
            }
            return _outputText(indices.map((index) => done[index]).toList());
          } else if (type == 'error' ||
              type == 'response.failed' ||
              type == 'response.incomplete') {
            aiFailure('ChatGPT 未能完成此次回答，请检查模型权限与额度后重试。');
          }
        }
      }
      data = [];
      eventName = null;
    } else if (!line.startsWith(':')) {
      final colon = line.indexOf(':');
      final field = colon < 0 ? line : line.substring(0, colon);
      var value = colon < 0 ? '' : line.substring(colon + 1);
      if (value.startsWith(' ')) value = value.substring(1);
      if (field == 'data') {
        data.add(value);
        if (data.fold<int>(0, (sum, part) => sum + part.length + 1) >
            2 * 1024 * 1024) {
          aiFailure('ChatGPT 事件过大。');
        }
      } else if (field == 'event') {
        eventName = value;
      }
    }
  }
  aiFailure('ChatGPT 数据流未收到完成事件，请重试。');
}

String _outputText(List<dynamic> output) {
  final text = StringBuffer();
  for (final raw in output) {
    final item = aiObject(raw);
    if (item['type'] == 'reasoning') continue;
    if (item['type'] != 'message' ||
        item['role'] != 'assistant' ||
        item['status'] != 'completed' ||
        item['content'] is! List) {
      aiFailure('ChatGPT 返回了不完整或不支持的输出。');
    }
    for (final rawPart in item['content'] as List) {
      final part = aiObject(rawPart);
      if (part['type'] != 'output_text' || part['text'] is! String) {
        aiFailure('ChatGPT 未返回有效文本。');
      }
      text.write(part['text']);
    }
  }
  if (text.toString().trim().isEmpty) aiFailure('ChatGPT 响应没有有效内容。');
  return text.toString();
}

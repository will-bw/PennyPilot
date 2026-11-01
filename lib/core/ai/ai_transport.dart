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

class AiSseEvent {
  const AiSseEvent(this.data, this.name);
  final String data;
  final String? name;
}

/// Dispatch only blank-line-terminated events. UTF-8 is decoded by the stream
/// transformer, so a multi-byte character may span any number of HTTP chunks.
class AiSseDecoder {
  final _data = <String>[];
  String? _name;
  var _first = true;
  var _eventSize = 0;

  AiSseEvent? line(String line) {
    if (_first) line = line.replaceFirst('\uFEFF', '');
    _first = false;
    if (utf8.encode(line).length > 512 * 1024) aiFailure('AI 数据流单行过长。');
    if (line.isEmpty) {
      final event = _data.isEmpty ? null : AiSseEvent(_data.join('\n'), _name);
      _data.clear();
      _name = null;
      _eventSize = 0;
      return event;
    }
    if (line.startsWith(':')) return null;
    final colon = line.indexOf(':');
    final field = colon < 0 ? line : line.substring(0, colon);
    var value = colon < 0 ? '' : line.substring(colon + 1);
    if (value.startsWith(' ')) value = value.substring(1);
    if (field == 'data') {
      _eventSize += value.length + 1;
      if (_eventSize > 2 * 1024 * 1024) aiFailure('AI 数据流事件过大。');
      _data.add(value);
    } else if (field == 'event') {
      _name = value;
    }
    return null;
  }
}

Future<void> aiSseRequest(
  http.Client client,
  http.Request request, {
  required void Function(AiSseEvent event) onEvent,
  bool Function()? isComplete,
  Future<void> Function()? guard,
  int maxBytes = 8 * 1024 * 1024,
}) async {
  request.followRedirects = false;
  try {
    final response = await client
        .send(request)
        .timeout(const Duration(seconds: 60));
    if (response.statusCode < 200 || response.statusCode >= 300) {
      await response.stream.listen(null).cancel();
      _streamHttpFailure(response.statusCode);
    }
    final contentType = response.headers['content-type'];
    if (contentType != null &&
        contentType.split(';').first.trim().toLowerCase() !=
            'text/event-stream') {
      await response.stream.listen(null).cancel();
      aiFailure('AI 服务未返回流式响应，请检查所选模型与接口。');
    }
    var size = 0;
    final guardWatch = Stopwatch()..start();
    final decoder = AiSseDecoder();
    final bytes = response.stream.timeout(const Duration(seconds: 60)).map((
      chunk,
    ) {
      size += chunk.length;
      if (size > maxBytes) aiFailure('AI 服务返回内容过大，请缩小查询范围。');
      return chunk;
    });
    await for (final line
        in bytes.transform(utf8.decoder).transform(const LineSplitter())) {
      final event = decoder.line(line);
      if (event != null) {
        if (guardWatch.elapsedMilliseconds >= 250) {
          await guard?.call();
          guardWatch.reset();
        }
        onEvent(event);
        if (event.data == '[DONE]' ||
            event.data == '[END]' ||
            isComplete?.call() == true) {
          break;
        }
      }
    }
    await guard?.call();
  } on LedgerException {
    rethrow;
  } catch (_) {
    aiFailure('AI 数据流中断，请检查网络后重试。');
  }
}

Never _streamHttpFailure(int status) {
  switch (status) {
    case 401:
      aiFailure('AI 服务凭据已失效，请重新登录或检查 API Key。');
    case 403:
      aiFailure('当前账号没有访问此模型的权限，请检查授权或更换模型。');
    case 429:
      aiFailure('AI 服务额度不足或请求过于频繁，请稍后重试。');
    default:
      aiFailure('AI 服务请求失败（HTTP $status），请稍后重试。');
  }
}

class ResponsesStreamDecoder {
  ResponsesStreamDecoder({this.onTextDelta, this.onReasoningDelta});
  final void Function(String delta)? onTextDelta;
  final void Function(String delta)? onReasoningDelta;
  final _added = <int, Map<String, dynamic>>{};
  final _done = <int, Map<String, dynamic>>{};
  final _summaryParts = <(String, int), String>{};
  final _summaryDone = <(String, int)>{};
  var _summaryCharacters = 0;
  Map<String, dynamic>? _output;
  bool get isComplete => _output != null;

  void accept(AiSseEvent sse) {
    if (_output != null || sse.data == '[DONE]' || sse.data == '[END]') return;
    final event = decodeAiObject(sse.data);
    final type = event['type'];
    if (type is! String ||
        sse.name != null &&
            sse.name != '' &&
            sse.name != 'message' &&
            sse.name != type) {
      aiFailure('ChatGPT 数据流事件类型无效。');
    }
    if (const {
      'response.reasoning_summary_text.delta',
      'response.reasoning_summary_text.done',
      'response.reasoning_summary_part.added',
      'response.reasoning_summary_part.done',
    }.contains(type)) {
      _acceptSummary(type, event);
    } else if (type == 'response.output_text.delta') {
      final delta = event['delta'];
      if (delta is! String) aiFailure('ChatGPT 文本增量无效。');
      onTextDelta?.call(delta);
    } else if (type == 'response.output_item.added' ||
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
        if (_added.containsKey(index) || _done.containsKey(index)) {
          aiFailure('ChatGPT 输出项重复。');
        }
        _added[index] = item;
      } else {
        final initial = _added[index];
        if (_done.containsKey(index) ||
            initial != null &&
                (initial['id'] != item['id'] ||
                    initial['type'] != item['type'])) {
          aiFailure('ChatGPT 输出项不一致。');
        }
        _done[index] = item;
      }
    } else if (type == 'response.completed') {
      final response = aiObject(event['response']);
      final output = response['output'];
      if (response['status'] != 'completed' || output is! List) {
        aiFailure('ChatGPT 响应未完整完成。');
      }
      List<dynamic> items = output;
      if (output.isEmpty) {
        final indices = _done.keys.toList()..sort();
        if (!_added.keys.every(_done.containsKey) ||
            indices.asMap().entries.any((entry) => entry.key != entry.value) ||
            _done.values.map((item) => item['id']).toSet().length !=
                _done.length) {
          aiFailure('ChatGPT 流式输出不完整。');
        }
        items = indices.map((index) => _done[index]!).toList();
      }
      final finalSummary = _finalSummary(items);
      final summary = finalSummary.isNotEmpty
          ? finalSummary
          : _summaryParts.entries
                .where(
                  (entry) =>
                      _summaryDone.contains(entry.key) &&
                      entry.value.trim().isNotEmpty,
                )
                .map((entry) => entry.value)
                .join('\n\n');
      _output = {
        'items': items,
        'usage': response['usage'],
        if (summary.isNotEmpty) 'reasoning_summary': summary,
      };
    } else if (type == 'error' ||
        type == 'response.failed' ||
        type == 'response.incomplete') {
      aiFailure('ChatGPT 未能完成此次回答，请检查模型权限与额度后重试。');
    }
  }

  void _acceptSummary(String type, Map<String, dynamic> event) {
    final item = event['item_id'], index = event['summary_index'];
    if (item is! String ||
        item.trim().isEmpty ||
        item.length > 256 ||
        index is! int ||
        index < 0 ||
        index > 4095) {
      aiFailure('ChatGPT 思考摘要标识无效。');
    }
    final key = (item, index);
    final before = _summaryParts[key] ?? '';
    String next;
    if (type == 'response.reasoning_summary_text.delta') {
      final delta = event['delta'];
      if (delta is! String || _summaryDone.contains(key)) {
        aiFailure('ChatGPT 思考摘要增量无效。');
      }
      next = '$before$delta';
    } else if (type == 'response.reasoning_summary_text.done') {
      final text = event['text'];
      if (text is! String) aiFailure('ChatGPT 思考摘要完成内容无效。');
      next = text;
    } else {
      final part = aiObject(event['part']);
      final text = part['text'];
      if (part['type'] != 'summary_text' || text is! String) {
        aiFailure('ChatGPT 思考摘要分段无效。');
      }
      next = text;
    }
    if (!next.startsWith(before)) aiFailure('ChatGPT 思考摘要内容不一致。');
    final appended = next.substring(before.length);
    if (_summaryCharacters + appended.length > 64000) {
      aiFailure('ChatGPT 思考摘要过长。');
    }
    if (appended.isNotEmpty) {
      if (before.isEmpty &&
          _summaryParts.entries.any(
            (entry) => entry.key != key && entry.value.isNotEmpty,
          )) {
        onReasoningDelta?.call('\n\n');
      }
      _summaryCharacters += appended.length;
      onReasoningDelta?.call(appended);
    }
    _summaryParts[key] = next;
    if (type.endsWith('.done')) _summaryDone.add(key);
  }

  String _finalSummary(List<dynamic> items) {
    final texts = <String>[];
    var characters = 0;
    for (final raw in items) {
      final item = aiObject(raw);
      if (item['type'] != 'reasoning') continue;
      final parts = item['summary'];
      if (parts == null) continue;
      if (parts is! List) aiFailure('ChatGPT 思考摘要格式无效。');
      for (final raw in parts) {
        final part = aiObject(raw);
        final text = part['text'];
        if (part['type'] != 'summary_text' || text is! String) {
          aiFailure('ChatGPT 思考摘要格式无效。');
        }
        characters += text.length;
        if (characters > 64000) aiFailure('ChatGPT 思考摘要过长。');
        if (text.trim().isNotEmpty) texts.add(text);
      }
    }
    return texts.join('\n\n');
  }

  Map<String, dynamic> get output {
    if (_output == null) aiFailure('ChatGPT 数据流未收到完成事件，请重试。');
    return _output!;
  }
}

/// Chat Completions tool arguments are assembled by index, never executed from
/// deltas. Only a terminal choice with complete JSON calls can leave the decoder.
class ChatCompletionStreamDecoder {
  ChatCompletionStreamDecoder({this.onTextDelta, this.onReasoningDelta});
  final void Function(String delta)? onTextDelta;
  final void Function(String delta)? onReasoningDelta;
  final _text = StringBuffer(), _reasoning = StringBuffer();
  final _calls = <int, Map<String, dynamic>>{};
  String? _finish;
  Map<String, dynamic>? _usage;

  void accept(AiSseEvent sse) {
    if (sse.data == '[DONE]') return;
    final chunk = decodeAiObject(sse.data);
    if (chunk['error'] != null) aiFailure('模型未能完成此次回答，请检查模型权限与额度。');
    if (chunk['usage'] is Map<String, dynamic>) {
      _usage = aiObject(chunk['usage']);
    }
    final choices = chunk['choices'];
    if (choices is! List) aiFailure('模型流式响应格式无效。');
    if (choices.isEmpty) return;
    final choice = aiObject(choices.first);
    if (choice['index'] != null && choice['index'] != 0) {
      aiFailure('模型返回了不支持的多选响应。');
    }
    final delta = aiObject(choice['delta']);
    final content = delta['content'];
    final reasoning = delta['reasoning_content'];
    if (content != null && content is! String ||
        reasoning != null && reasoning is! String) {
      aiFailure('模型文本增量格式无效。');
    }
    if (_finish != null) {
      if (content != null || reasoning != null || delta['tool_calls'] != null) {
        aiFailure('模型在完成后继续返回内容。');
      }
      return;
    }
    if (content is String) {
      _text.write(content);
      onTextDelta?.call(content);
    }
    if (reasoning is String) {
      _reasoning.write(reasoning);
      onReasoningDelta?.call(reasoning);
    }
    final calls = delta['tool_calls'];
    if (calls != null && calls is! List) aiFailure('模型工具增量格式无效。');
    for (final raw in calls as List? ?? const []) {
      final call = aiObject(raw);
      final index = call['index'];
      if (index is! int || index < 0 || index >= 16) aiFailure('模型工具序号无效。');
      final target = _calls.putIfAbsent(
        index,
        () => {
          'id': '',
          'type': 'function',
          'function': {'name': '', 'arguments': ''},
        },
      );
      if (call['type'] != null && call['type'] != 'function') {
        aiFailure('模型工具类型无效。');
      }
      if (call['id'] != null) {
        if (call['id'] is! String) aiFailure('模型工具标识无效。');
        if (target['id'] != '' && target['id'] != call['id']) {
          aiFailure('模型工具标识发生变化。');
        }
        target['id'] = call['id'];
      }
      if (call['function'] != null) {
        final function = aiObject(call['function']);
        final assembled = target['function'] as Map<String, dynamic>;
        for (final key in ['name', 'arguments']) {
          if (function[key] != null) {
            if (function[key] is! String) aiFailure('模型工具参数无效。');
            assembled[key] = '${assembled[key]}${function[key]}';
          }
        }
        if ((assembled['arguments'] as String).length > 96 * 1024) {
          aiFailure('模型工具参数过长。');
        }
      }
    }
    if (choice['finish_reason'] != null) {
      final finish = choice['finish_reason'];
      if (finish == 'length') {
        aiFailure('模型输出达到长度上限，已保留返回的思考与回复，未执行不完整工具。');
      }
      if (finish != 'stop' && finish != 'tool_calls') {
        aiFailure('模型输出未完整完成，未执行工具。');
      }
      _finish = finish as String;
    }
  }

  Map<String, dynamic> finish(Set<String> allowedTools) {
    if (_finish == null) aiFailure('模型数据流未收到完成状态，未执行工具。');
    final indices = _calls.keys.toList()..sort();
    if (indices.asMap().entries.any((entry) => entry.key != entry.value)) {
      aiFailure('模型工具调用不完整。');
    }
    final message = <String, dynamic>{
      'role': 'assistant',
      'content': _text.toString(),
      if (_calls.isNotEmpty)
        'tool_calls': indices.map((index) => _calls[index]).toList(),
      if (_reasoning.isNotEmpty) 'reasoning_content': _reasoning.toString(),
    };
    validateChatCompletionMessage(message, _finish!, allowedTools);
    return {'message': message, 'usage': _usage};
  }
}

/// Both complete JSON responses and assembled streams must pass the same tool
/// checks before the runtime receives executable calls.
void validateChatCompletionMessage(
  Map<String, dynamic> message,
  String finishReason,
  Set<String> allowedTools,
) {
  if (message['role'] != 'assistant' ||
      message['content'] != null && message['content'] is! String ||
      message['tool_calls'] != null && message['tool_calls'] is! List ||
      message['reasoning_content'] != null &&
          message['reasoning_content'] is! String) {
    aiFailure('模型工具响应格式无效。');
  }
  final calls = message['tool_calls'] as List? ?? const [];
  if (calls.length > 16) aiFailure('模型返回了过多工具调用。');
  if (calls.isNotEmpty && finishReason != 'tool_calls' ||
      calls.isEmpty && finishReason != 'stop') {
    aiFailure('模型工具完成状态不一致。');
  }
  final ids = <String>{};
  for (final raw in calls) {
    final call = aiObject(raw);
    final function = aiObject(call['function']);
    final id = call['id'], arguments = function['arguments'];
    if (id is! String ||
        id.trim().isEmpty ||
        id.length > 256 ||
        !ids.add(id) ||
        call['type'] != null && call['type'] != 'function' ||
        !allowedTools.contains(function['name']) ||
        arguments is! String) {
      aiFailure('模型工具调用无效。');
    }
    if (arguments.length >
        (function['name'] == 'render_artifact' ? 96 * 1024 : 16000)) {
      aiFailure('模型工具参数过长。');
    }
    decodeAiObject(arguments);
  }
  if (calls.isEmpty && (message['content'] as String? ?? '').trim().isEmpty) {
    aiFailure('模型没有返回有效内容。');
  }
}

/// Only completed Responses output is accepted; deltas and [DONE] are provisional.
String decodeResponsesStream(String body, {String? contentType}) => _outputText(
  decodeResponsesOutput(body, contentType: contentType)['items'] as List,
);

Map<String, dynamic> decodeResponsesOutput(String body, {String? contentType}) {
  final prefix = body.replaceFirst('\uFEFF', '').trimLeft();
  if (contentType != null
      ? contentType.split(';').first.trim().toLowerCase() != 'text/event-stream'
      : !(prefix.startsWith('data:') ||
            prefix.startsWith('event:') ||
            prefix.startsWith(':'))) {
    aiFailure('ChatGPT 返回了非流式响应，请稍后重试。');
  }
  final sse = AiSseDecoder();
  final decoder = ResponsesStreamDecoder();
  final lines = body.split('\n');
  for (var line in lines.take(lines.length - 1)) {
    if (line.endsWith('\r')) line = line.substring(0, line.length - 1);
    final event = sse.line(line);
    if (event != null) decoder.accept(event);
  }
  return decoder.output;
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

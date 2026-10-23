import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/ai/ai_transport.dart';
import 'package:pennypilot/core/ledger_repository.dart';

String event(
  Map<String, dynamic> value, {
  String? name,
  String newline = '\n',
}) =>
    '${name == null ? '' : 'event: $name$newline'}data: ${jsonEncode(value)}$newline$newline';
Map<String, dynamic> message(String text, {String id = 'message-1'}) => {
  'id': id,
  'type': 'message',
  'role': 'assistant',
  'status': 'completed',
  'content': [
    {'type': 'output_text', 'text': text},
  ],
};
String completed(List<dynamic> output) => event({
  'type': 'response.completed',
  'response': {'status': 'completed', 'output': output},
});

void main() {
  test('complete output works even if response omitted Content-Type', () {
    expect(decodeResponsesStream(completed([message('完成')])), '完成');
  });
  test('BOM, comments and CRLF SSE supported', () {
    final body =
        '\uFEFF: keepalive\r\n\r\n${completed([message('中文')]).replaceAll('\n', '\r\n')}';
    expect(
      decodeResponsesStream(
        body,
        contentType: 'text/event-stream; charset=utf-8',
      ),
      '中文',
    );
  });
  test('terminal empty output uses only completed indexed items', () {
    final item = message('流式内容');
    final body =
        event({
          'type': 'response.output_item.added',
          'output_index': 0,
          'item': {...item, 'status': 'in_progress'},
        }) +
        event({
          'type': 'response.output_item.done',
          'output_index': 0,
          'item': item,
        }) +
        completed([]);
    expect(decodeResponsesStream(body), '流式内容');
  });
  test('done item without added event matches direct-route compatibility', () {
    final body =
        event({
          'type': 'response.output_item.done',
          'output_index': 0,
          'item': message('内容'),
        }) +
        completed([]);
    expect(decodeResponsesStream(body), '内容');
  });
  test('sentinel and provisional text do not establish completion', () {
    final body =
        '${event({'type': 'response.output_text.delta', 'delta': 'half'})}data: [DONE]\n\n';
    expect(() => decodeResponsesStream(body), throwsA(isA<LedgerException>()));
  });
  test('incomplete, malformed and mismatched events are rejected', () {
    final bodies = [
      event({'type': 'response.incomplete'}),
      event({'type': 'error', 'message': 'secret'}),
      'data: not-json\n\n',
      event({
        'type': 'response.completed',
        'response': {
          'status': 'completed',
          'output': [message('text')],
        },
      }, name: 'response.failed'),
      completed([message('truncated')]).trimRight(),
    ];
    for (final body in bodies) {
      expect(
        () => decodeResponsesStream(body),
        throwsA(isA<LedgerException>()),
      );
    }
  });
  test('incomplete and duplicated item sequences cannot be returned', () {
    for (final body in [
      event({
            'type': 'response.output_item.done',
            'output_index': 1,
            'item': message('text'),
          }) +
          completed([]),
      event({
            'type': 'response.output_item.added',
            'output_index': 0,
            'item': message('text'),
          }) +
          completed([]),
      event({
            'type': 'response.output_item.done',
            'output_index': 0,
            'item': message('text'),
          }) +
          event({
            'type': 'response.output_item.done',
            'output_index': 0,
            'item': message('text'),
          }) +
          completed([]),
    ]) {
      expect(
        () => decodeResponsesStream(body),
        throwsA(isA<LedgerException>()),
      );
    }
  });
  test('declared non-SSE and unauthorized tools rejected', () {
    expect(
      () => decodeResponsesStream(
        completed([message('text')]),
        contentType: 'application/json',
      ),
      throwsA(isA<LedgerException>()),
    );
    expect(
      () => decodeResponsesStream(
        completed([
          {'type': 'function_call', 'name': 'execute_sql'},
        ]),
      ),
      throwsA(isA<LedgerException>()),
    );
  });
}

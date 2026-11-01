import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/ai/ai_transport.dart';
import 'package:pennypilot/core/ledger_repository.dart';

AiSseEvent _event(Map<String, dynamic> event) =>
    AiSseEvent(jsonEncode(event), null);
AiSseEvent _summary(
  String type,
  int index, {
  String? delta,
  String? text,
  String? part,
  String item = 'rs_1',
}) => _event({
  'type': 'response.reasoning_summary_$type',
  'item_id': item,
  'output_index': 0,
  'summary_index': index,
  'delta': ?delta,
  'text': ?text,
  if (part != null) 'part': {'type': 'summary_text', 'text': part},
});
Map<String, dynamic> _message() => {
  'id': 'msg_1',
  'type': 'message',
  'role': 'assistant',
  'status': 'completed',
  'content': [
    {'type': 'output_text', 'text': '最终结论'},
  ],
};
AiSseEvent _completed({List<Map<String, dynamic>>? items}) => _event({
  'type': 'response.completed',
  'response': {
    'status': 'completed',
    'output': items ?? [_message()],
  },
});

void main() {
  test('public summary parts stream once by item and part index', () {
    final summary = <String>[], answer = <String>[];
    final decoder = ResponsesStreamDecoder(
      onReasoningDelta: summary.add,
      onTextDelta: answer.add,
    );
    for (final event in [
      _summary('part.added', 0, part: ''),
      _summary('text.delta', 0, delta: '先核对'),
      _summary('text.done', 0, text: '先核对账本'),
      _summary('part.done', 0, part: '先核对账本'),
      _summary('part.added', 1, part: ''),
      _summary('text.done', 1, text: '再比较预算'),
      _summary('part.done', 1, part: '再比较预算'),
      _summary('text.done', 0, text: '另一工具轮', item: 'rs_2'),
      _event({'type': 'response.reasoning_text.delta', 'delta': 'PRIVATE'}),
      _event({'type': 'response.output_text.delta', 'delta': '答案'}),
      _completed(),
    ]) {
      decoder.accept(event);
    }
    expect(summary.join(), '先核对账本\n\n再比较预算\n\n另一工具轮');
    expect(answer, ['答案']);
    expect(decoder.output['reasoning_summary'], '先核对账本\n\n再比较预算\n\n另一工具轮');
    expect(jsonEncode(decoder.output), isNot(contains('PRIVATE')));
  });

  test(
    'terminal public summaries override previews while opaque data stays opaque',
    () {
      final summary = <String>[];
      final decoder = ResponsesStreamDecoder(onReasoningDelta: summary.add);
      decoder.accept(_summary('text.delta', 0, delta: '预览'));
      decoder.accept(
        _completed(
          items: [
            {
              'id': 'rs_1',
              'type': 'reasoning',
              'encrypted_content': 'OPAQUE_PRIVATE',
              'summary': [
                {'type': 'summary_text', 'text': '已核对的摘要'},
              ],
            },
            _message(),
          ],
        ),
      );
      expect(summary, ['预览']);
      expect(decoder.output['reasoning_summary'], '已核对的摘要');
      expect(jsonEncode(decoder.output['items']), contains('OPAQUE_PRIVATE'));
      expect(decoder.output['reasoning_summary'], isNot(contains('PRIVATE')));
    },
  );

  test(
    'only completed public summary parts persist after response completion',
    () {
      final decoder = ResponsesStreamDecoder();
      decoder.accept(_summary('text.delta', 0, delta: '未完成预览'));
      decoder.accept(_summary('part.done', 1, part: '完成摘要'));
      expect(() => decoder.output, throwsA(isA<LedgerException>()));
      decoder.accept(_completed());
      expect(decoder.output['reasoning_summary'], '完成摘要');
      final unfinished = ResponsesStreamDecoder();
      unfinished.accept(_summary('text.delta', 0, delta: '未完成预览'));
      unfinished.accept(_completed());
      expect(unfinished.output.containsKey('reasoning_summary'), isFalse);
    },
  );

  test(
    'bad identifiers inconsistent done text and oversized public summaries fail',
    () {
      for (final bad in [
        _event({
          'type': 'response.reasoning_summary_text.delta',
          'delta': 'missing identifiers',
        }),
        _summary('text.delta', -1, delta: 'bad index'),
        _summary('text.delta', 4096, delta: 'bad index'),
        _summary('text.done', 0, text: 'x' * 64001),
      ]) {
        expect(
          () => ResponsesStreamDecoder().accept(bad),
          throwsA(isA<LedgerException>()),
        );
      }
      final mismatch = ResponsesStreamDecoder();
      mismatch.accept(_summary('text.delta', 0, delta: '初稿'));
      expect(
        () => mismatch.accept(_summary('text.done', 0, text: '不同内容')),
        throwsA(isA<LedgerException>()),
      );
      final done = ResponsesStreamDecoder();
      done.accept(_summary('text.done', 0, text: '已完成'));
      expect(
        () => done.accept(_summary('text.delta', 0, delta: '额外内容')),
        throwsA(isA<LedgerException>()),
      );
    },
  );
}

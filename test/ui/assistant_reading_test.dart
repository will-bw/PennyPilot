import 'dart:async';

import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/core/ledger_repository.dart';
import 'package:pennypilot/core/models.dart';
import 'package:pennypilot/features/chat/chat_screen.dart';
import 'package:pennypilot/ui/theme.dart';

import 'test_ledger_repository.dart';

class _ReadingRepository extends TestLedgerRepository
    implements AgentSessionRepository {
  _ReadingRepository({bool withHistory = false}) {
    (data['settings'] as Map)['cloudImageParsingEnabled'] = true;
    if (withHistory) {
      (state['session'] as Map)['messages'] = <Map<String, dynamic>>[
        for (var i = 0; i < 18; i++) ...[
          {'id': 'u$i', 'runId': 'r$i', 'role': 'user', 'content': '历史问题 $i'},
          {
            'id': 'a$i',
            'runId': 'r$i',
            'role': 'assistant',
            'content': '历史回答 $i\n\n已经完成的账本分析，保留给用户查阅。',
          },
        ],
      ];
    }
  }

  final pending = Completer<Map<String, dynamic>>();
  final state = <String, dynamic>{
    'session': {
      'id': 'reading-session',
      'status': 'completed',
      'messages': <Map<String, dynamic>>[],
      'actions': [],
      'trace': [],
      'plan': [],
    },
    'sessions': [],
    'memories': [],
    'config': {},
  };

  @override
  Future<Map<String, dynamic>> agentState() async => state;

  @override
  Future<Object?> invoke(String method, Map<String, dynamic> arguments) async {
    if (method == 'ask') {
      calls.add((method: method, arguments: arguments));
      return pending.future;
    }
    if (method == 'pickRecognitionImage') {
      calls.add((method: method, arguments: arguments));
      return {
        'base64':
            'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=',
        'mimeType': 'image/png',
        'name': '测试凭证',
      };
    }
    return super.invoke(method, arguments);
  }

  void delta(String text, {bool start = false}) {
    if (start) {
      for (final event in [
        {'phase': 'run_started', 'runId': 'live', 'userMessageId': 'live-user'},
        {'phase': 'text_start', 'runId': 'live', 'messageId': 'live-answer'},
      ]) {
        updates.add(LedgerEvent('agent', 0, event));
      }
    }
    updates.add(
      LedgerEvent('agent', 0, {
        'phase': 'text_delta',
        'runId': 'live',
        'messageId': 'live-answer',
        'delta': text,
      }),
    );
  }

  void chart(String id) {
    updates.add(
      LedgerEvent('agent', 0, {
        'phase': 'tool_result',
        'runId': 'live',
        'callId': id,
        'tool': 'present_insight',
        'trace': {'status': 'completed'},
        'result': {'presentation': _chart(id)},
      }),
    );
  }

  void complete() {
    if (pending.isCompleted) return;
    (state['session'] as Map)['status'] = 'completed';
    ((state['session'] as Map)['messages'] as List).addAll(
      <Map<String, dynamic>>[
        {'id': 'live-user', 'runId': 'live', 'role': 'user', 'content': '继续分析'},
        {
          'id': 'live-answer',
          'runId': 'live',
          'role': 'assistant',
          'content': '分析已完成。',
        },
      ],
    );
    pending.complete({'answer': '分析已完成。', 'isError': false, ...state});
  }
}

Finder get _input => find.byKey(const ValueKey('assistant-input'));
Finder get _conversation =>
    find.byKey(const ValueKey('assistant-conversation'));

Map<String, dynamic> _chart(String id, {String type = 'bar'}) => {
  'id': id,
  'type': 'chart',
  'title': '分类支出 $id',
  'chartType': type,
  'metric': 'expense',
  'data': {
    'expense': 4000,
    'group_by': 'category',
    'groups': [
      {'label': '餐饮', 'category_id': 1, 'expense': 2800},
      {'label': '交通', 'category_id': 2, 'expense': 1200},
    ],
  },
};

Finder _nativePlot(String type) => switch (type) {
  'pie' => find.byType(PieChart),
  'line' => find.byType(LineChart),
  _ => find.byType(BarChart),
};

Future<({LedgerController controller, ValueNotifier<bool> active})> _open(
  WidgetTester tester,
  _ReadingRepository repository,
) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(390, 844);
  addTearDown(tester.view.resetDevicePixelRatio);
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetViewInsets);
  final controller = LedgerController(repository: repository);
  await controller.init();
  final active = ValueNotifier(true);
  addTearDown(() async {
    controller.dispose();
    active.dispose();
    await repository.updates.close();
  });
  await tester.pumpWidget(
    MaterialApp(
      theme: pennyTheme(Brightness.light),
      home: ValueListenableBuilder<bool>(
        valueListenable: active,
        builder: (context, value, _) => ChatScreen(
          controller: controller,
          active: value,
          onSettings: () {},
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return (controller: controller, active: active);
}

Future<void> _layout(WidgetTester tester) async {
  await tester.pump(const Duration(milliseconds: 100));
  await tester.pump();
  await tester.pump();
}

void main() {
  for (final type in ['bar', 'line', 'pie']) {
    testWidgets(
      'a slow vertical drag starting on a saved $type plot reads earlier messages without streaming',
      (tester) async {
        final repository = _ReadingRepository(withHistory: true);
        ((repository.state['session'] as Map)['messages'] as List).addAll(
          <Map<String, dynamic>>[
            {
              'id': 'chart-user',
              'runId': 'saved-chart',
              'role': 'user',
              'content': '查看分类支出图',
            },
            {
              'id': 'chart-answer',
              'runId': 'saved-chart',
              'role': 'assistant',
              'content': '下面是分类图，前面的分析也可以向上查看。',
              'parts': [
                {'type': 'chart', 'spec': _chart('saved-chart', type: type)},
              ],
            },
          ],
        );
        await _open(tester, repository);
        final scroll = tester.widget<ListView>(_conversation).controller!;
        final initialOffset = scroll.offset;
        expect(scroll.position.extentAfter, lessThan(2));
        final plot = _nativePlot(type);
        expect(plot.hitTestable(), findsOneWidget);
        final gesture = await tester.startGesture(tester.getCenter(plot));
        try {
          for (var i = 0; i < 12; i++) {
            await gesture.moveBy(const Offset(0, 6));
            await tester.pump(const Duration(milliseconds: 30));
          }
          expect(
            scroll.offset,
            lessThan(initialOffset - 30),
            reason: '绘图区上的竖向拖动应滚动会话，不能被图表的 pan 手势吞掉。',
          );
        } finally {
          await gesture.cancel();
        }
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets(
    'small history drags pause following before the old 80 pixel threshold',
    (tester) async {
      final repository = _ReadingRepository(withHistory: true);
      await _open(tester, repository);
      await tester.enterText(_input, '继续分析');
      await tester.pump();
      await tester.tap(find.byTooltip('发送消息'));
      await tester.pump();
      repository.delta('正在继续分析。', start: true);
      await _layout(tester);
      final scroll = tester.widget<ListView>(_conversation).controller!;
      final initialOffset = scroll.offset;
      final viewport = tester.getRect(_conversation);
      final gesture = await tester.startGesture(
        Offset(viewport.left + 8, viewport.center.dy),
      );
      try {
        for (var i = 0; i < 16; i++) {
          await gesture.moveBy(const Offset(0, 6));
          await tester.pump(const Duration(milliseconds: 30));
          final afterDrag = scroll.offset;
          repository.delta('继续');
          await _layout(tester);
          if (i >= 3) {
            expect(
              scroll.offset,
              lessThanOrEqualTo(afterDrag + 1),
              reason: '用户已经开始慢慢向上阅读，流式更新不能把小于80px的滚动拉回底部。',
            );
          }
        }
        expect(scroll.offset, lessThan(initialOffset - 30));
        expect(find.text('回到最新'), findsOneWidget);
      } finally {
        await gesture.cancel();
        repository.complete();
        await tester.pumpAndSettle();
      }
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'slow drags from a live chart keep their reading position across deltas and another chart insertion',
    (tester) async {
      final repository = _ReadingRepository(withHistory: true);
      await _open(tester, repository);
      await tester.enterText(_input, '继续分析');
      await tester.pump();
      await tester.tap(find.byTooltip('发送消息'));
      await tester.pump();
      repository.delta('已经生成第一张分类图。', start: true);
      repository.chart('first-chart');
      await _layout(tester);
      final scroll = tester.widget<ListView>(_conversation).controller!;
      final initialOffset = scroll.offset;
      expect(scroll.position.extentAfter, lessThan(2));
      final plot = _nativePlot('bar');
      expect(plot.hitTestable(), findsOneWidget);
      final gesture = await tester.startGesture(tester.getCenter(plot));
      var dragging = true;
      try {
        for (var i = 0; i < 16; i++) {
          await gesture.moveBy(const Offset(0, 6));
          await tester.pump(const Duration(milliseconds: 30));
          final afterDrag = scroll.offset;
          repository.delta('继续');
          if (i == 7) repository.chart('second-chart');
          await _layout(tester);
          if (i >= 3) {
            expect(
              scroll.offset,
              lessThanOrEqualTo(afterDrag + 1),
              reason: '持续拖动中，文字和新图表导致的高度变化不能抢走用户的阅读位置。',
            );
          }
        }
        expect(scroll.offset, lessThan(initialOffset - 30));
        expect(find.text('回到最新'), findsOneWidget);
        await gesture.cancel();
        dragging = false;
        await tester.tap(find.text('回到最新'));
        await _layout(tester);
        expect(scroll.position.extentAfter, lessThan(2));
      } finally {
        if (dragging) await gesture.cancel();
        repository.complete();
        await tester.pumpAndSettle();
      }
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'reading history survives keyboard resizing and streamed updates until return to latest',
    (tester) async {
      final repository = _ReadingRepository(withHistory: true);
      await _open(tester, repository);
      await tester.enterText(_input, '继续分析');
      await tester.pump();
      await tester.tap(find.byTooltip('发送消息'));
      await tester.pump();
      repository.delta('正在继续分析。', start: true);
      await _layout(tester);

      await tester.drag(_conversation, const Offset(0, 600));
      await tester.pump(const Duration(milliseconds: 500));
      final scroll = tester.widget<ListView>(_conversation).controller!;
      final readingOffset = scroll.offset;
      expect(scroll.position.extentAfter, greaterThan(200));

      tester.view.viewInsets = const FakeViewPadding(bottom: 280);
      await _layout(tester);
      expect(scroll.offset, closeTo(readingOffset, 1));
      repository.delta('\n\n${List.filled(15, '新增的账本分析内容。').join('\n\n')}');
      await _layout(tester);
      expect(scroll.offset, closeTo(readingOffset, 1));
      tester.view.viewInsets = const FakeViewPadding();
      await _layout(tester);
      expect(scroll.offset, closeTo(readingOffset, 1));

      await tester.tap(find.text('回到最新'));
      await _layout(tester);
      expect(scroll.position.extentAfter, lessThan(2));
      repository.delta('\n\n继续收到新的结论。');
      await _layout(tester);
      expect(scroll.position.extentAfter, lessThan(2));
      tester.view.viewInsets = const FakeViewPadding(bottom: 280);
      await _layout(tester);
      expect(scroll.position.extentAfter, lessThan(2));

      repository.complete();
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('sending gives the conversation keyboard focus back', (
    tester,
  ) async {
    final repository = _ReadingRepository();
    final result = await _open(tester, repository);
    await tester.enterText(_input, '继续分析');
    final inputFocus = tester.widget<TextField>(_input).focusNode!;
    expect(inputFocus.hasFocus, true);
    await tester.pump();
    await tester.tap(find.byTooltip('发送消息'));
    await tester.pump();
    expect(inputFocus.hasFocus, false);
    expect(result.controller.assistantDraft, isEmpty);
    expect(
      repository.calls.where((call) => call.method == 'ask'),
      hasLength(1),
    );
    repository.complete();
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });

  testWidgets('choosing an image keeps the keyboard closed and draft intact', (
    tester,
  ) async {
    final repository = _ReadingRepository();
    final result = await _open(tester, repository);
    await tester.enterText(_input, '这张凭证的用途说明');
    final inputFocus = tester.widget<TextField>(_input).focusNode!;
    await tester.pump();
    await tester.tap(find.byTooltip('添加附件和更多'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('添加记账图片'));
    await tester.pumpAndSettle();
    expect(inputFocus.hasFocus, false);
    expect(result.controller.assistantDraft, '这张凭证的用途说明');
    expect(result.controller.assistantAttachment?['name'], '测试凭证');
    expect(
      repository.calls.where(
        (call) => call.method == 'ask' || call.method == 'recognizeInput',
      ),
      isEmpty,
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'leaving the assistant dismisses keyboard without discarding draft',
    (tester) async {
      final repository = _ReadingRepository();
      final result = await _open(tester, repository);
      await tester.enterText(_input, '稍后再发的草稿');
      final inputFocus = tester.widget<TextField>(_input).focusNode!;
      expect(inputFocus.hasFocus, true);
      result.active.value = false;
      await tester.pump();
      expect(inputFocus.hasFocus, false);
      expect(result.controller.assistantDraft, '稍后再发的草稿');
      result.active.value = true;
      await tester.pumpAndSettle();
      expect(tester.widget<TextField>(_input).controller!.text, '稍后再发的草稿');
      expect(inputFocus.hasFocus, false);
      expect(tester.takeException(), isNull);
    },
  );
}

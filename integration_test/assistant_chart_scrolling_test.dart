import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:pennypilot/app.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/core/ledger_repository.dart';
import 'package:pennypilot/features/chat/chat_screen.dart';
import 'package:pennypilot/ui/assistant_content.dart';

import '../test/ui/test_ledger_repository.dart';

/// Completed conversations and amounts are synthetic. No production storage,
/// credentials, network provider or model is opened by this acceptance test.
class _ChartReadingRepository extends TestLedgerRepository
    implements AgentSessionRepository {
  _ChartReadingRepository(String chartType, {int groups = 2})
    : super(populated: true) {
    final rows = [
      for (var i = 0; i < groups; i++)
        {
          'label': groups == 2 ? (i == 0 ? '餐饮' : '交通') : '分类 ${i + 1}',
          'category_id': i + 1,
          'expense': groups == 2 ? (i == 0 ? 2800 : 1200) : (i + 1) * 100,
        },
    ];
    final total = rows.fold<int>(
      0,
      (sum, row) => sum + (row['expense'] as int),
    );
    state = {
      'session': {
        'id': 'chart-reading-$chartType-$groups',
        'status': 'completed',
        'messages': [
          for (var i = 0; i < 18; i++) ...[
            {
              'id': 'question-$i',
              'runId': 'history-$i',
              'role': 'user',
              'content': '历史问题 $i：这一周的消费情况怎么样？',
            },
            {
              'id': 'answer-$i',
              'runId': 'history-$i',
              'role': 'assistant',
              'content': '历史分析 $i\n\n这是已经完成的合成账本分析，向上滚动时应始终可以重新阅读。',
            },
          ],
          {
            'id': 'chart-question',
            'runId': 'chart-run',
            'role': 'user',
            'content': '查看分类支出图，也保留上面的分析供我回看。',
          },
          {
            'id': 'chart-answer',
            'runId': 'chart-run',
            'role': 'assistant',
            'content': '分类支出图已生成，可以在图上向上翻阅之前的对话。',
            'parts': [
              {
                'type': 'chart',
                'spec': {
                  'id': 'acceptance-$chartType-$groups',
                  'type': 'chart',
                  'title': groups == 2 ? '分类支出' : '可横向浏览的分类支出',
                  'chartType': chartType,
                  'metric': 'expense',
                  'data': {
                    'expense': total,
                    'group_by': 'category',
                    'groups': rows,
                  },
                },
              },
            ],
          },
        ],
        'actions': [],
        'trace': [],
        'plan': [],
      },
      'sessions': [],
      'memories': [],
      'config': {},
    };
  }

  late final Map<String, dynamic> state;

  @override
  Future<Map<String, dynamic>> agentState() async => state;
}

Finder get _conversation =>
    find.byKey(const ValueKey('assistant-conversation'));

Finder _plot(String type) => switch (type) {
  'line' => find.byType(LineChart),
  'pie' => find.byType(PieChart),
  _ => find.byType(BarChart),
};

ScrollController _conversationScroll(WidgetTester tester) =>
    tester.widget<ListView>(_conversation).controller!;

Future<_ChartReadingRepository> _open(
  WidgetTester tester,
  String type, {
  int groups = 2,
}) async {
  final repository = _ChartReadingRepository(type, groups: groups);
  final controller = LedgerController(repository: repository);
  addTearDown(() async {
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
    await repository.updates.close();
  });
  await tester.pumpWidget(PennyPilotApp(controller: controller));
  await tester.pumpAndSettle();
  final navigation = find.byType(NavigationRail).evaluate().isNotEmpty
      ? find.byType(NavigationRail)
      : find.byType(NavigationBar);
  await tester.tap(
    find.descendant(of: navigation, matching: find.text('AI 助手')).last,
  );
  await tester.pumpAndSettle();
  expect(find.byType(ChatScreen), findsOneWidget);
  expect(find.byType(AssistantVisualization), findsOneWidget);
  final scroll = _conversationScroll(tester);
  expect(scroll.position.maxScrollExtent, greaterThan(500));
  expect(scroll.position.extentAfter, lessThan(2));
  return repository;
}

Offset _visiblePlotPoint(WidgetTester tester, String type) {
  // A wide cartesian chart's widget center can be far outside its viewport.
  // Start in the actual plot portion visible within both scroll viewports.
  var visible = tester
      .getRect(_plot(type))
      .intersect(tester.getRect(_conversation));
  if (type != 'pie') {
    final horizontal = find.descendant(
      of: find.byType(AssistantVisualization),
      matching: find.byWidgetPredicate(
        (widget) =>
            widget is SingleChildScrollView &&
            widget.scrollDirection == Axis.horizontal,
      ),
    );
    visible = visible.intersect(tester.getRect(horizontal));
  }
  expect(visible.width, greaterThan(40));
  expect(visible.height, greaterThan(40));
  return visible.center;
}

Future<void> _latest(WidgetTester tester) async {
  expect(find.text('回到最新').hitTestable(), findsOneWidget);
  await tester.tap(find.text('回到最新'));
  await tester.pumpAndSettle();
  expect(_conversationScroll(tester).position.extentAfter, lessThan(2));
  expect(find.text('回到最新'), findsNothing);
}

Future<void> _capture(WidgetTester tester, String marker) async {
  debugPrint(marker);
  const seconds = int.fromEnvironment('SCREENSHOT_HOLD');
  if (seconds > 0) {
    await tester.runAsync(
      () => Future<void>.delayed(Duration(seconds: seconds.clamp(0, 60))),
    );
  }
}

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  for (final type in ['bar', 'line', 'pie']) {
    testWidgets(
      'completed $type plot yields both fast and slow vertical gestures to conversation history',
      (tester) async {
        final repository = await _open(tester, type);
        final scroll = _conversationScroll(tester);
        final beforeFastSwipe = scroll.offset;
        final fastGesture = await tester.startGesture(
          _visiblePlotPoint(tester, type),
        );
        for (var i = 0; i < 3; i++) {
          await fastGesture.moveBy(const Offset(0, 80));
          await tester.pump(const Duration(milliseconds: 40));
        }
        await fastGesture.up();
        await tester.pumpAndSettle();
        expect(
          scroll.offset,
          lessThan(beforeFastSwipe - 80),
          reason: '从真实绘图区开始的快划必须能翻回历史对话。',
        );
        await _capture(tester, 'CHART_SCROLL_${type.toUpperCase()}_FAST');
        await _latest(tester);

        final beforeSlowDrag = scroll.offset;
        final gesture = await tester.startGesture(
          _visiblePlotPoint(tester, type),
        );
        try {
          for (var i = 0; i < 12; i++) {
            await gesture.moveBy(const Offset(0, 6));
            await tester.pump(const Duration(milliseconds: 30));
          }
          expect(
            scroll.offset,
            lessThan(beforeSlowDrag - 30),
            reason: '每次6px的慢拖也必须滚动会话，不能被图表吞掉或拉回底部。',
          );
        } finally {
          await gesture.cancel();
        }
        await tester.pumpAndSettle();
        await _capture(tester, 'CHART_SCROLL_${type.toUpperCase()}_SLOW');
        await _latest(tester);
        expect(repository.calls, isEmpty);
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets(
    'a completed wide bar chart scrolls horizontally without moving conversation or showing return to latest',
    (tester) async {
      final repository = await _open(tester, 'bar', groups: 24);
      final horizontal = find.descendant(
        of: find.byType(AssistantVisualization),
        matching: find.byWidgetPredicate(
          (widget) =>
              widget is Scrollable &&
              (widget.axisDirection == AxisDirection.right ||
                  widget.axisDirection == AxisDirection.left),
        ),
      );
      final chartPosition = tester.state<ScrollableState>(horizontal).position;
      expect(chartPosition.maxScrollExtent, greaterThan(250));
      final outer = _conversationScroll(tester);
      final before = outer.offset;
      final chartBefore = chartPosition.pixels;
      await tester.timedDragFrom(
        _visiblePlotPoint(tester, 'bar'),
        const Offset(-180, 0),
        const Duration(milliseconds: 250),
      );
      await tester.pumpAndSettle();
      expect(chartPosition.pixels, greaterThan(chartBefore + 50));
      expect(outer.offset, closeTo(before, 1));
      expect(find.text('回到最新'), findsNothing);
      await _capture(tester, 'CHART_SCROLL_WIDE_HORIZONTAL');
      expect(repository.calls, isEmpty);
      expect(tester.takeException(), isNull);
    },
  );
}

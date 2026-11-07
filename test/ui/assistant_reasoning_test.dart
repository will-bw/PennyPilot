import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/ai/model_reasoning.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/features/chat/agent_activity.dart';
import 'package:pennypilot/ui/reasoning_controls.dart';
import 'package:pennypilot/ui/theme.dart';

import 'test_ledger_repository.dart';

void main() {
  LedgerController createController() {
    final repository = TestLedgerRepository();
    final controller = LedgerController(repository: repository);
    addTearDown(repository.updates.close);
    addTearDown(controller.dispose);
    return controller;
  }

  Future<void> show(
    WidgetTester tester,
    Widget child, {
    double width = 390,
    double scale = 1,
  }) async {
    tester.view.physicalSize = Size(width, 1000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      MaterialApp(
        theme: pennyTheme(Brightness.light),
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(
            context,
          ).copyWith(textScaler: TextScaler.linear(scale)),
          child: child!,
        ),
        home: Scaffold(
          body: ListView(padding: const EdgeInsets.all(20), children: [child]),
        ),
      ),
    );
    await tester.pump();
  }

  Map<String, dynamic> part({
    String kind = 'reasoning_content',
    String text = '**核对**已确认的流水。',
    String status = 'completed',
    int? end = 3500,
    String id = 'reasoning-1',
  }) => {
    'type': 'reasoning',
    'id': id,
    'messageId': id,
    'runId': 'run-1',
    'kind': kind,
    'text': text,
    'status': status,
    'startedAt': end == null
        ? DateTime.now().millisecondsSinceEpoch - 1000
        : 1000,
    'endedAt': ?end,
  };

  testWidgets(
    'saved public reasoning is collapsed, readable and separate from the final answer',
    (tester) async {
      await show(
        tester,
        Column(
          children: [
            AgentActivity(
              controller: createController(),
              parts: [
                {...part(), 'model': 'deepseek-flash', 'effort': 'high'},
              ],
            ),
            const Text('最终答复：本月支出 40 元。'),
          ],
        ),
      );
      final header = find.text('已思考 2 秒');
      expect(header, findsOneWidget);
      expect(find.text('思考过程 · 已完成'), findsNothing);
      expect(find.textContaining('deepseek-flash · 深度：高'), findsNothing);
      expect(find.text('最终答复：本月支出 40 元。'), findsOneWidget);
      expect(find.text('核对已确认的流水。', findRichText: true), findsNothing);
      await tester.tap(header);
      await tester.pumpAndSettle();
      expect(find.text('思考过程 · 已完成'), findsOneWidget);
      expect(find.textContaining('deepseek-flash · 深度：高'), findsOneWidget);
      expect(find.text('核对已确认的流水。', findRichText: true), findsOneWidget);
      expect(find.text('复制思考内容'), findsOneWidget);
      await tester.tap(header);
      await tester.pumpAndSettle();
      expect(find.text('复制思考内容'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'the activity updates from thinking to tools to reply without closing expanded details',
    (tester) async {
      final controller = createController();
      var current = [
        part(kind: 'summary', text: '先核对支出', status: 'streaming', end: null),
      ];
      var active = true;
      final changes = ValueNotifier(0);
      addTearDown(changes.dispose);
      await show(
        tester,
        ListenableBuilder(
          listenable: changes,
          builder: (_, _) => AgentActivity(
            controller: controller,
            parts: current,
            active: active,
          ),
        ),
      );
      final thinking = find.text('正在思考…');
      const summary = '先核对支出，再比较上月。';
      expect(thinking, findsOneWidget);
      expect(find.text('先核对支出', findRichText: true), findsNothing);
      await tester.tap(thinking);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 250));
      expect(find.text('先核对支出', findRichText: true), findsOneWidget);
      current = [
        part(kind: 'summary', text: summary, status: 'streaming', end: null),
      ];
      changes.value++;
      await tester.pump();
      expect(find.text(summary, findRichText: true), findsOneWidget);

      final completed = part(kind: 'summary', text: summary);
      final tool = {
        'type': 'tool',
        'callId': 'query-1',
        'tool': 'query_transactions',
        'status': 'running',
      };
      controller.agentProgress = {
        'phase': 'tool',
        'tool': 'query_transactions',
        'text': '正在查询流水',
      };
      current = [completed, tool];
      changes.value++;
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 250));
      expect(find.textContaining('正在查询流水'), findsOneWidget);
      expect(thinking, findsNothing);
      expect(find.text(summary, findRichText: true), findsOneWidget);

      controller.agentProgress['phase'] = 'text_delta';
      current = [
        completed,
        {...tool, 'status': 'completed'},
      ];
      changes.value++;
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 250));
      expect(find.textContaining('正在查询流水'), findsNothing);
      final replying = find.text('正在回复…');
      expect(replying, findsOneWidget);
      expect(find.text(summary, findRichText: true), findsOneWidget);

      active = false;
      changes.value++;
      await tester.pumpAndSettle();
      expect(replying, findsNothing);
      expect(find.text('思考 2 秒 · 1 次调用'), findsOneWidget);
      expect(find.text('思考摘要 · 已完成'), findsOneWidget);
      expect(find.text(summary, findRichText: true), findsOneWidget);
      expect(find.text('服务商返回的公开摘要'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('stopped thinking keeps the partial public summary', (
    tester,
  ) async {
    const summary = '先核对支出，再比较上月。';
    await show(
      tester,
      AgentActivity(
        controller: createController(),
        parts: [part(kind: 'summary', text: summary, status: 'cancelled')],
      ),
    );
    await tester.tap(find.text('已停止'));
    await tester.pumpAndSettle();
    expect(find.text('思考摘要 · 已停止'), findsOneWidget);
    expect(find.text(summary, findRichText: true), findsOneWidget);
  });

  testWidgets(
    'empty provider summary reports no returned content without inventing text',
    (tester) async {
      await show(
        tester,
        AgentActivity(
          controller: createController(),
          parts: [part(kind: 'summary', text: '')],
        ),
      );
      await tester.tap(find.text('已思考 2 秒'));
      await tester.pumpAndSettle();
      expect(find.text('思考摘要 · 未返回内容'), findsOneWidget);
      expect(find.text('这次没有返回可展示的公开思考摘要。'), findsOneWidget);
      expect(find.text('复制思考内容'), findsNothing);
    },
  );

  testWidgets(
    'multiple model steps restore with their own content and total timing at large text',
    (tester) async {
      await show(
        tester,
        AgentActivity(
          controller: createController(),
          parts: [
            part(text: '先查本月账单。'),
            part(id: 'reasoning-2', text: '再看分类明细。', end: 2500),
          ],
        ),
        width: 320,
        scale: 1.5,
      );
      expect(find.text('2 次模型响应 · 4 秒'), findsNothing);
      await tester.tap(find.text('已思考 4 秒'));
      await tester.pumpAndSettle();
      expect(find.text('2 次模型响应 · 4 秒'), findsOneWidget);
      expect(find.text('先查本月账单。', findRichText: true), findsOneWidget);
      expect(find.text('再看分类明细。', findRichText: true), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('unequal reasoning paragraphs keep the same left edge', (
    tester,
  ) async {
    const short = '先查账单。';
    const long = '再核对餐饮、交通、住房与其他分类的预算明细。';
    await show(
      tester,
      AgentActivity(
        controller: createController(),
        parts: [
          part(id: 'short', text: short),
          part(id: 'long', text: long),
        ],
      ),
    );
    await tester.tap(find.text('已思考 5 秒'));
    await tester.pumpAndSettle();
    expect(
      tester.getTopLeft(find.text(short, findRichText: true)).dx,
      tester.getTopLeft(find.text(long, findRichText: true)).dx,
    );
    expect(tester.takeException(), isNull);
  });

  for (final model in [('chatgpt', 'gpt-5'), ('qwen', 'qwen3.8-2.4t-a95b')]) {
    testWidgets('${model.$2} required reasoning has no off toggle', (
      tester,
    ) async {
      final options = resolveReasoningOptions(
        {'${reasoningSettingsPrefix(model.$1)}ThinkingEnabled': false},
        provider: model.$1,
        model: model.$2,
      );
      await show(
        tester,
        ReasoningControls(
          options: options,
          enabled: true,
          compact: true,
          onEnabledChanged: (_) => fail('required model cannot be disabled'),
          onEffortChanged: (_) {},
        ),
        width: 320,
        scale: 1.5,
      );
      expect(find.text('始终思考'), findsOneWidget);
      expect(find.byType(FilterChip), findsNothing);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('running task disables its visible optional reasoning choices', (
    tester,
  ) async {
    final options = resolveReasoningOptions(
      {},
      provider: 'deepseek',
      model: 'deepseek-flash',
    );
    await show(
      tester,
      ReasoningControls(
        options: options,
        enabled: false,
        compact: true,
        onEnabledChanged: (_) => fail('running configuration is frozen'),
        onEffortChanged: (_) => fail('running configuration is frozen'),
      ),
    );
    expect(
      tester.widget<FilterChip>(find.byType(FilterChip)).onSelected,
      isNull,
    );
    expect(
      tester
          .widget<PopupMenuButton<String>>(find.byType(PopupMenuButton<String>))
          .enabled,
      false,
    );
    expect(find.text('任务进行中'), findsOneWidget);
  });
}

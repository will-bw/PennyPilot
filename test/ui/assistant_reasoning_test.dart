import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/ai/model_reasoning.dart';
import 'package:pennypilot/features/chat/assistant_reasoning.dart';
import 'package:pennypilot/ui/reasoning_controls.dart';
import 'package:pennypilot/ui/theme.dart';

void main() {
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
            AssistantReasoning(
              parts: [
                {...part(), 'model': 'deepseek-flash', 'effort': 'high'},
              ],
            ),
            const Text('最终答复：本月支出 40 元。'),
          ],
        ),
      );
      expect(find.text('思考过程 · 已完成'), findsOneWidget);
      expect(find.textContaining('2 秒'), findsOneWidget);
      expect(find.textContaining('deepseek-flash · 深度：高'), findsOneWidget);
      expect(find.text('最终答复：本月支出 40 元。'), findsOneWidget);
      expect(find.text('核对已确认的流水。', findRichText: true), findsNothing);
      await tester.tap(find.text('思考过程 · 已完成'));
      await tester.pumpAndSettle();
      expect(find.text('核对已确认的流水。', findRichText: true), findsOneWidget);
      expect(find.text('复制思考内容'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'streamed public summaries update while expanded and stop keeps the partial content',
    (tester) async {
      var current = [
        part(kind: 'summary', text: '先核对支出', status: 'streaming', end: null),
      ];
      final active = ValueNotifier(true);
      final changes = ValueNotifier(0);
      addTearDown(active.dispose);
      addTearDown(changes.dispose);
      await show(
        tester,
        ListenableBuilder(
          listenable: Listenable.merge([active, changes]),
          builder: (_, _) =>
              AssistantReasoning(parts: current, active: active.value),
        ),
      );
      await tester.tap(find.text('思考摘要 · 正在接收'));
      await tester.pump(const Duration(milliseconds: 250));
      expect(find.text('先核对支出', findRichText: true), findsOneWidget);
      current = [
        part(
          kind: 'summary',
          text: '先核对支出，再比较上月。',
          status: 'streaming',
          end: null,
        ),
      ];
      changes.value++;
      await tester.pump();
      expect(find.text('先核对支出，再比较上月。', findRichText: true), findsOneWidget);
      current = [
        part(kind: 'summary', text: '先核对支出，再比较上月。', status: 'cancelled'),
      ];
      active.value = false;
      await tester.pumpAndSettle();
      expect(find.text('思考摘要 · 已停止'), findsOneWidget);
      expect(find.text('先核对支出，再比较上月。', findRichText: true), findsOneWidget);
      expect(find.text('服务商返回的公开摘要'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'empty provider summary reports no returned content without inventing text',
    (tester) async {
      await show(
        tester,
        AssistantReasoning(
          parts: [part(kind: 'summary', text: '')],
        ),
      );
      expect(find.text('思考摘要 · 未返回内容'), findsOneWidget);
      await tester.tap(find.text('思考摘要 · 未返回内容'));
      await tester.pumpAndSettle();
      expect(find.text('这次没有返回可展示的公开思考摘要。'), findsOneWidget);
      expect(find.text('复制思考内容'), findsNothing);
    },
  );

  testWidgets(
    'multiple model steps restore with their own content and total timing at large text',
    (tester) async {
      await show(
        tester,
        AssistantReasoning(
          parts: [
            part(text: '先查本月账单。'),
            part(id: 'reasoning-2', text: '再看分类明细。', end: 2500),
          ],
        ),
        width: 320,
        scale: 1.5,
      );
      expect(find.text('2 次模型响应 · 4 秒'), findsOneWidget);
      await tester.tap(find.text('思考过程 · 已完成'));
      await tester.pumpAndSettle();
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
      AssistantReasoning(
        parts: [
          part(id: 'short', text: short),
          part(id: 'long', text: long),
        ],
      ),
    );
    await tester.tap(find.text('思考过程 · 已完成'));
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

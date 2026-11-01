import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/ui/assistant_content.dart';
import 'package:pennypilot/ui/theme.dart';

void main() {
  Widget host(Widget child) => MaterialApp(
    theme: pennyTheme(Brightness.light),
    home: Scaffold(
      body: SingleChildScrollView(
        child: Padding(padding: const EdgeInsets.all(20), child: child),
      ),
    ),
  );

  testWidgets(
    'Markdown renders headings, emphasis and tables without source markers',
    (tester) async {
      await tester.pumpWidget(
        host(
          const AssistantMarkdown(
            text:
                '## 本月回顾\n\n**餐饮** 是主要支出。\n\n|分类|金额|\n|---|---|\n|早餐|17元|\n\n[依据 Q1]',
          ),
        ),
      );
      await tester.pumpAndSettle();
      final rendered = tester
          .widgetList<RichText>(
            find.byWidgetPredicate((widget) => widget is RichText),
          )
          .map((w) => w.text.toPlainText())
          .join('\n');
      expect(rendered, contains('本月回顾'));
      expect(rendered, contains('餐饮'));
      expect(rendered, contains('早餐'));
      expect(rendered, isNot(contains('**餐饮**')));
      expect(find.byType(SelectionArea), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'streaming HTML waits for completion, completed code previews in chat',
    (tester) async {
      await tester.pumpWidget(
        host(
          const AssistantMarkdown(text: '```html\n<p>交互报告', isStreaming: true),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('正在生成可视化内容…'), findsOneWidget);
      expect(find.byType(AssistantArtifact), findsNothing);
      await tester.pumpWidget(
        host(const AssistantMarkdown(text: '```html\n<p>交互报告</p>\n```')),
      );
      await tester.pumpAndSettle();
      expect(find.byType(AssistantArtifact), findsOneWidget);
      expect(find.textContaining('暂不支持 HTML'), findsOneWidget);
      expect(find.text('<p>交互报告</p>'), findsNothing);
    },
  );

  testWidgets(
    'chart shows trusted net amounts and opens matching ledger filters',
    (tester) async {
      Map<String, dynamic>? opened;
      await tester.pumpWidget(
        host(
          AssistantVisualization(
            spec: {
              'title': '十月分类支出',
              'chartType': 'bar',
              'metric': 'expense',
              'evidenceId': 'Q1',
              'asOf': '2026-11-01T12:00:00+08:00',
              'query': {
                'start': '2026-10-01',
                'end': '2026-10-31',
                'group_by': 'category',
              },
              'data': {
                'start': '2026-10-01',
                'end': '2026-10-31',
                'expense': 8000,
                'group_by': 'category',
                'groups': [
                  {
                    'label': '餐饮',
                    'category_id': 1,
                    'expense': 8000,
                    'income': 0,
                  },
                ],
              },
            },
            onOpenTransactions: (query) => opened = query,
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('净支出 80.00 元'), findsOneWidget);
      expect(find.textContaining('依据 Q1'), findsOneWidget);
      await tester.tap(find.text('80.00 元'));
      expect(opened, containsPair('category_id', 1));
      expect(opened, containsPair('start', '2026-10-01'));
      expect(opened, containsPair('status', 'CONFIRMED'));
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'unsafe SVG produces a readable error instead of executable source',
    (tester) async {
      await tester.pumpWidget(
        host(
          const AssistantArtifact(
            spec: {
              'format': 'svg',
              'title': '图示',
              'template': 'custom',
              'content': '<svg onload="alert(1)"/>',
            },
          ),
        ),
      );
      expect(find.textContaining('不支持的资源或格式'), findsOneWidget);
      expect(find.textContaining('onload'), findsNothing);
    },
  );
}

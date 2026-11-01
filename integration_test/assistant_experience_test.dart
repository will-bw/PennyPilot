import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:integration_test/integration_test.dart';
import 'package:pennypilot/app.dart';
import 'package:pennypilot/core/agent/agent_models.dart';
import 'package:pennypilot/core/agent/agent_store.dart';
import 'package:pennypilot/core/agent/assistant_artifacts.dart';
import 'package:pennypilot/core/ai/shared_ai_service.dart';
import 'package:pennypilot/core/ai/model_reasoning.dart';
import 'package:pennypilot/core/cross_platform_repository.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/core/models.dart';
import 'package:pennypilot/core/platform/mobile_platform_services.dart';
import 'package:pennypilot/features/chat/chat_screen.dart';
import 'package:pennypilot/ui/assistant_content.dart';
import 'package:pennypilot/ui/theme.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../test/ui/test_ledger_repository.dart';

class _Credentials implements SharedCredentials {
  @override
  Future<String?> get(String key) async => null;
  @override
  Future<void> set(String key, String value) async {}
  @override
  Future<void> delete(String key) async {}
}

class _Platform extends MobilePlatformServices {
  @override
  Future<Map<String, dynamic>> capabilities() async => {'platform': 'ios'};
  @override
  Stream<String> get partialTranscripts => const Stream.empty();
  @override
  Future<void> dispose() async {}
}

class _ScriptedModel extends SharedAiService {
  _ScriptedModel() : super(credentials: _Credentials());
  final firstDelta = Completer<void>();
  final continueReply = Completer<void>();
  final observedCalls = <String>[];

  @override
  Future<AgentModel> openAgentModel(
    LedgerSnapshot snapshot, {
    AgentStreamComplete? nativeTurn,
    AgentReasoningStreamComplete? nativeReasoningTurn,
    AiModel? catalogModel,
    required Future<void> Function() guard,
  }) async {
    var step = 0;
    AgentReply call(String name, Map<String, dynamic> arguments) {
      observedCalls.add(name);
      return AgentReply(
        message: {
          'role': 'assistant',
          'content': '',
          'tool_calls': [
            {
              'id': 'assistant-acceptance-$step',
              'type': 'function',
              'function': {'name': name, 'arguments': jsonEncode(arguments)},
            },
          ],
        },
      );
    }

    Future<AgentReply> turn(
      List<Map<String, dynamic>> messages,
      List<Map<String, dynamic>> tools, {
      void Function(String)? onDelta,
      void Function(String)? onReasoningDelta,
    }) async {
      await guard();
      final currentStep = ++step;
      const thoughts = [
        '**先核对**已确认流水的统计范围。',
        '再读取十月预算，保持金额口径一致。',
        '把分类统计转换为可核对的柱状图。',
        '将预算与净支出整理成进度图。',
        '使用真实统计结果生成交互报告。',
        '预算模拟仅预览，不写入账本。',
        '补充一张记录、核对与回顾的流程图。',
        '**最后汇总**已有查询结果，并附上来源依据。',
      ];
      final thought = thoughts[currentStep - 1];
      final split = thought.length ~/ 2;
      onReasoningDelta?.call(thought.substring(0, split));
      await Future<void>.delayed(const Duration(milliseconds: 35));
      await guard();
      onReasoningDelta?.call(thought.substring(split));
      switch (currentStep) {
        case 1:
          return call('aggregate_by', {
            'start': '2026-10-01',
            'end': '2026-10-31',
            'group_by': 'category',
          });
        case 2:
          return call('get_budget', {'month': '2026-10'});
        case 3:
          return call('present_insight', {
            'evidence_id': 'Q1',
            'title': '十月分类支出',
            'chart_type': 'bar',
          });
        case 4:
          return call('present_insight', {
            'evidence_id': 'Q2',
            'title': '十月预算进度',
            'chart_type': 'budget',
          });
        case 5:
          return call('render_artifact', {
            'format': 'html',
            'title': '十月交互报告',
            'template': 'report',
            'evidence_id': 'Q1',
          });
        case 6:
          return call('render_artifact', {
            'format': 'html',
            'title': '预算模拟器',
            'template': 'budget_simulator',
            'evidence_id': 'Q2',
          });
        case 7:
          return call('render_artifact', {
            'format': 'svg',
            'title': '从记录到回顾',
            'template': 'custom',
            'content':
                '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 320 180">'
                '<rect x="8" y="50" width="90" height="65" rx="12" fill="#e7eff5"/>'
                '<rect x="115" y="50" width="90" height="65" rx="12" fill="#f5e9dd"/>'
                '<rect x="222" y="50" width="90" height="65" rx="12" fill="#e5eee9"/>'
                '<path d="M100 82h12m95 0h12" stroke="#4d6b85" stroke-width="2"/>'
                '<g font-size="16" fill="#303940" text-anchor="middle">'
                '<text x="53" y="88">记下收支</text><text x="160" y="88">账本核对</text>'
                '<text x="267" y="88">查看趋势</text></g></svg>',
          });
        default:
          const chunks = [
            '## 十月收支回顾\n\n',
            '**已确认净支出 40.00 元**，餐饮 28.00 元、交通 12.00 元。[依据 Q1]\n\n',
            '| 项目 | 金额 |\n| --- | ---: |\n| 净支出 | 40.00 元 |\n| 收入 | 8,000.00 元 |\n\n',
            '当前月预算为 **3,000.00 元**，图表和交互报告已放在本条消息中。预算模拟只预览，不修改账本。[依据 Q2]',
          ];
          onDelta?.call(chunks.first);
          if (!firstDelta.isCompleted) {
            firstDelta.complete();
          }
          await continueReply.future;
          for (final chunk in chunks.skip(1)) {
            await Future<void>.delayed(const Duration(milliseconds: 35));
            await guard();
            onDelta?.call(chunk);
          }
          return AgentReply(
            message: {'role': 'assistant', 'content': chunks.join()},
          );
      }
    }

    return AgentModel(
      identity: 'isolated-assistant-acceptance',
      reasoningConfig: resolveReasoningOptions(snapshot.settings).toMap(),
      complete: (messages, tools) => turn(messages, tools),
      stream: (messages, tools, delta) => turn(messages, tools, onDelta: delta),
      reasoningStream: (messages, tools, text, reasoning) =>
          turn(messages, tools, onDelta: text, onReasoningDelta: reasoning),
    );
  }
}

Future<void> _pumpUntil(
  WidgetTester tester,
  bool Function() ready,
  String description,
) async {
  for (var i = 0; i < 150; i++) {
    if (ready()) {
      return;
    }
    await tester.pump(const Duration(milliseconds: 50));
  }
  fail('等待超时：$description');
}

Future<void> _captureHold(WidgetTester tester, String marker) async {
  debugPrint(marker);
  const seconds = int.fromEnvironment('SCREENSHOT_HOLD');
  if (seconds > 0) {
    await tester.runAsync(
      () => Future<void>.delayed(Duration(seconds: seconds.clamp(0, 60))),
    );
  }
}

Finder _presentation(String title) => find.byWidgetPredicate(
  (widget) =>
      widget is AssistantVisualization && widget.spec['title'] == title ||
      widget is AssistantArtifact && widget.spec['title'] == title,
);

Future<void> _scrollChatTo(WidgetTester tester, Finder target) async {
  final conversation = find.byKey(const ValueKey('assistant-conversation'));
  final scrollable = find
      .descendant(
        of: conversation,
        matching: find.byWidgetPredicate(
          (widget) =>
              widget is Scrollable &&
              (widget.axisDirection == AxisDirection.down ||
                  widget.axisDirection == AxisDirection.up),
        ),
      )
      .first;
  final viewport = tester.getRect(conversation);
  final delta =
      target.evaluate().isNotEmpty &&
          tester.getCenter(target).dy < viewport.center.dy
      ? -220.0
      : 220.0;
  // A drag updates the conversation's user-scroll intent. Jumping directly to a
  // RenderObject can race the pending follow-latest frame after a streamed run.
  await tester.scrollUntilVisible(
    target.hitTestable(),
    delta,
    scrollable: scrollable,
    maxScrolls: 60,
    duration: const Duration(milliseconds: 100),
  );
  await tester.pumpAndSettle();
  expect(
    target.hitTestable(),
    findsOneWidget,
    reason: '目标应在真实滚动后保持可点击：$target',
  );
}

/// Real native views, isolated in-memory stores and a deterministic provider.
/// Synthetic records are imported exclusively from the test fixture.
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets(
    'assistant streams, renders rich parts and opens ledger evidence on device',
    (tester) async {
      final fixture = TestLedgerRepository(populated: true, theme: 'dark');
      fixture.data['settings'] = {
        ...fixture.data['settings'] as Map<String, dynamic>,
        'deepSeekModel': defaultDeepSeekModel,
        'deepSeekThinkingEnabled': true,
        'deepSeekReasoningEffort': 'high',
      };
      final ai = _ScriptedModel();
      final repository = CrossPlatformLedgerRepository(
        store: fixture,
        platformServices: _Platform(),
        credentials: _Credentials(),
        ai: ai,
        agentStore: MemoryAgentStore(),
        android: false,
      );
      final controller = LedgerController(repository: repository);
      addTearDown(() async {
        if (!ai.continueReply.isCompleted) {
          ai.continueReply.complete();
        }
        controller.dispose();
        await repository.close();
        await fixture.updates.close();
      });
      await tester.pumpWidget(PennyPilotApp(controller: controller));
      await tester.pumpAndSettle();
      await tester.tap(find.text('AI 助手').last);
      await tester.pumpAndSettle();
      expect(find.byType(ChatScreen), findsOneWidget);
      expect(find.byTooltip('语音输入'), findsOneWidget);
      expect(find.byTooltip('添加记账图片'), findsOneWidget);
      expect(
        tester
            .widget<FilterChip>(
              find.byKey(const ValueKey('assistant-thinking-toggle')),
            )
            .selected,
        isTrue,
      );

      final operation = controller.ask(
        '分析2026年10月支出，展示分类图、预算进度、交互报告和预算模拟，再画一张流程图。',
      );
      await _pumpUntil(
        tester,
        () =>
            ai.firstDelta.isCompleted &&
            controller.messages.any(
              (m) => m.isStreaming && m.text.contains('十月收支回顾'),
            ),
        '首段增量应先于完成显示',
      );
      expect(controller.isAsking, isTrue);
      expect(find.byTooltip('停止任务'), findsOneWidget);
      expect(find.byType(AssistantMarkdown), findsWidgets);
      final liveReasoning = controller.messages.last.parts
          .where((part) => part['type'] == 'reasoning')
          .toList();
      expect(liveReasoning, hasLength(8));
      expect(liveReasoning.first['text'], '**先核对**已确认流水的统计范围。');
      expect(controller.messages.last.text, isNot(contains('先核对')));
      expect(find.text('先核对已确认流水的统计范围。', findRichText: true), findsNothing);
      ai.continueReply.complete();
      await operation;
      await tester.pumpAndSettle(const Duration(milliseconds: 100));
      expect(controller.messages.last.isError, isFalse);
      expect(controller.messages.last.isStreaming, isFalse);
      expect(ai.observedCalls, [
        'aggregate_by',
        'get_budget',
        'present_insight',
        'present_insight',
        'render_artifact',
        'render_artifact',
        'render_artifact',
      ]);
      expect(find.byType(AssistantVisualization), findsNWidgets(2));
      expect(find.byType(AssistantArtifact), findsNWidgets(3));
      expect(find.byType(SvgPicture), findsOneWidget);
      expect(find.byType(WebViewWidget), findsNWidgets(2));
      expect(find.byTooltip('保存内容'), findsNWidgets(5));
      expect(controller.transactions, hasLength(5));
      expect(
        fixture.calls.where((call) => call.method == 'saveTransaction'),
        isEmpty,
      );

      final thinking = find.text('思考过程 · 已完成');
      await _scrollChatTo(tester, thinking);
      expect(find.textContaining('8 次模型响应 ·'), findsOneWidget);
      await tester.tap(thinking);
      await tester.pumpAndSettle();
      expect(find.text('先核对已确认流水的统计范围。', findRichText: true), findsOneWidget);
      expect(find.text('**先核对**已确认流水的统计范围。'), findsNothing);
      expect(find.textContaining('第 8 次模型响应'), findsOneWidget);
      final savedReasoning = controller.messages.last.parts
          .where((part) => part['type'] == 'reasoning')
          .toList();
      expect(savedReasoning, hasLength(8));
      expect(
        savedReasoning.every(
          (part) =>
              part['status'] == 'completed' &&
              part['startedAt'] is int &&
              part['endedAt'] is int,
        ),
        isTrue,
      );
      await _scrollChatTo(tester, thinking);
      await _captureHold(tester, 'ASSISTANT_THINKING_CAPTURE');
      await tester.tap(thinking);
      await tester.pumpAndSettle();
      expect(find.text('先核对已确认流水的统计范围。', findRichText: true), findsNothing);

      final execution = find.text('执行记录 · 7 次工具调用');
      await _scrollChatTo(tester, execution);
      await tester.tap(execution);
      await tester.pumpAndSettle();
      final evidence = find.text('第 1 步 · 依据 Q1');
      await _scrollChatTo(tester, evidence);
      await tester.tap(evidence);
      await tester.pumpAndSettle();
      final evidenceText = find.textContaining('净支出：40.00 元');
      expect(evidenceText, findsOneWidget);
      Navigator.of(tester.element(evidenceText)).pop();
      await tester.pumpAndSettle();
      await _scrollChatTo(tester, execution);
      await tester.tap(execution);
      await tester.pumpAndSettle();

      final chart = _presentation('十月分类支出');
      final amount = find.descendant(of: chart, matching: find.text('28.00 元'));
      await _scrollChatTo(tester, chart);
      expect(amount.hitTestable(), findsOneWidget);
      await _captureHold(tester, 'ASSISTANT_CAPTURE_CHART');
      await tester.tap(amount);
      await tester.pumpAndSettle();
      expect(controller.categoryFilter, 1);
      expect(controller.rangeStart, DateTime(2026, 10, 1));
      expect(controller.filteredTransactions.single.merchant, '测试午餐店');
      await tester.tap(find.text('AI 助手').last);
      await tester.pumpAndSettle();

      final report = _presentation('十月交互报告');
      final reportExpand = find.descendant(
        of: report,
        matching: find.byTooltip('全屏查看'),
      );
      await _scrollChatTo(tester, reportExpand);
      await tester.tap(reportExpand);
      await tester.pumpAndSettle();
      expect(find.text('十月交互报告'), findsOneWidget);
      expect(find.byType(WebViewWidget), findsOneWidget);
      await _captureHold(tester, 'ASSISTANT_CAPTURE_HTML');
      await tester.tap(find.byType(BackButton));
      await tester.pumpAndSettle();
      expect(find.byType(ChatScreen), findsOneWidget);
      expect(_presentation('十月交互报告'), findsOneWidget);

      final toolPart = controller.messages.last.parts.firstWhere(
        (part) => part['type'] == 'tool' && part['evidenceId'] == 'Q1',
      );
      expect(toolPart['status'], 'completed');
      final chartPart = controller.messages.last.parts.firstWhere(
        (part) => part['type'] == 'chart',
      );
      expect((chartPart['spec'] as Map)['evidenceId'], 'Q1');
      expect(((chartPart['spec'] as Map)['data'] as Map)['expense'], 4000);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pumpAndSettle();
    },
  );

  testWidgets(
    'native HTML sandbox supports interaction and keeps ledger data isolated',
    (tester) async {
      const source = <String, dynamic>{
        'budget': 20000,
        'expense': 8000,
        'remaining_days': 10,
      };
      final content =
          '${assistantBudgetHtml(source, '预算交互验收')}'
          '''<script>setTimeout(async function(){
const r={origin:window.origin,parentBlocked:false,cookieBlocked:false,fetchBlocked:false};
try{parent.document.body.textContent='unexpected'}catch(e){r.parentBlocked=true;}
try{document.cookie='unexpected=1';r.cookieBlocked=!document.cookie.includes('unexpected=1');}catch(e){r.cookieBlocked=true;}
try{await fetch('https://example.invalid/pennypilot-sandbox-test')}catch(e){r.fetchBlocked=true;}
const i=document.getElementById('budget');i.value='300';i.dispatchEvent(new Event('input'));
try{pennyData.expense=0}catch(e){}
r.balance=document.getElementById('balance').textContent;r.expense=pennyData.expense;r.budget=pennyData.budget;r.frozen=Object.isFrozen(pennyData);r.scheme=getComputedStyle(document.documentElement).colorScheme;
parent.postMessage(JSON.stringify(r),'*');},350);</script>''';
      // Only this test fixture listens to postMessage. The product installs no
      // listener and no native JavaScript channel in the generated content.
      final document =
          assistantSandboxDocument(
            content,
            data: source,
            dark: true,
          ).replaceFirst(
            '<body>',
            '''<body><script>window.addEventListener('message',function(e){if(e.source===document.querySelector('iframe').contentWindow&&typeof e.data==='string'){window.pennySandboxTest=JSON.parse(e.data);}});</script>''',
          );
      final web = WebViewController(
        onPermissionRequest: (request) => request.deny(),
      );
      await web.setJavaScriptMode(JavaScriptMode.unrestricted);
      await web.setNavigationDelegate(
        NavigationDelegate(
          onNavigationRequest: (request) =>
              assistantArtifactNavigationAllowed(request.url)
              ? NavigationDecision.navigate
              : NavigationDecision.prevent,
        ),
      );
      await tester.pumpWidget(
        MaterialApp(
          theme: pennyTheme(Brightness.dark),
          home: Scaffold(
            appBar: AppBar(title: const Text('预算模拟器 · 原生交互验收')),
            body: SafeArea(child: WebViewWidget(controller: web)),
          ),
        ),
      );
      await web.loadHtmlString(document);
      Map<String, dynamic>? result;
      for (var i = 0; i < 100 && result == null; i++) {
        await tester.pump(const Duration(milliseconds: 100));
        try {
          final raw = await web.runJavaScriptReturningResult(
            'JSON.stringify(window.pennySandboxTest || null)',
          );
          Object? decoded = raw is String ? jsonDecode(raw) : raw;
          if (decoded is String) {
            decoded = jsonDecode(decoded);
          }
          if (decoded is Map) {
            result = Map<String, dynamic>.from(decoded);
          }
        } on PlatformException {
          // WKWebView's context may not yet exist during initial navigation.
        }
      }
      expect(result, isNotNull, reason: '真实WebView应完成子帧脚本与交互');
      expect(result!['origin'], 'null');
      expect(result['parentBlocked'], isTrue);
      expect(result['cookieBlocked'], isTrue);
      expect(result['fetchBlocked'], isTrue);
      expect(result['balance'], '预计剩余 220.00 元');
      expect(result['expense'], 8000);
      expect(result['budget'], 20000);
      expect(result['frozen'], isTrue);
      expect(result['scheme'], 'dark');
      debugPrint('ASSISTANT_NATIVE_SANDBOX ${jsonEncode(result)}');
      await _captureHold(tester, 'ASSISTANT_CAPTURE_SIMULATOR');
      await web.loadHtmlString('<!doctype html><title>完成</title>');
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pumpAndSettle();
    },
  );
}

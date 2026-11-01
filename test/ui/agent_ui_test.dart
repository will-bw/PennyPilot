import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/core/ledger_repository.dart';
import 'package:pennypilot/core/models.dart';
import 'package:pennypilot/features/chat/chat_screen.dart';
import 'package:pennypilot/ui/assistant_content.dart';
import 'package:pennypilot/ui/theme.dart';

import 'test_ledger_repository.dart';

class _AgentRepository extends TestLedgerRepository
    implements AgentSessionRepository {
  _AgentRepository() : super(populated: true);
  Completer<Map<String, dynamic>>? delayedAnswer;
  VoidCallback? onCancel;
  final state = <String, dynamic>{
    'session': {
      'id': 'test-session',
      'status': 'awaiting_confirmation',
      'messages': [
        {'role': 'user', 'content': '检查预算'},
        {'role': 'assistant', 'content': '已准备预算提案，等待核对。[依据 Q1]'},
        {
          'role': 'tool',
          'tool_call_id': 'tool-1',
          'content': jsonEncode({
            'evidence_id': 'Q1',
            'data': {
              'month': '2026-10',
              'expense': 4000,
              'budget': 200000,
              'remaining': 196000,
            },
          }),
        },
      ],
      'actions': [
        {
          'id': 'budget-1',
          'kind': 'budget',
          'title': '月预算改为 3,000.00 元',
          'payload': {'amount': 300000, 'before': 200000},
          'status': 'pending',
        },
      ],
      'trace': [
        {
          'callId': 'tool-1',
          'step': 1,
          'tool': 'get_budget',
          'status': 'completed',
          'evidenceId': 'Q1',
        },
      ],
      'plan': [],
      'estimatedTokens': 5000,
    },
    'sessions': [
      {'id': 'test-session', 'title': '检查预算', 'updatedAt': 1},
    ],
    'memories': [
      {
        'key': 'goal',
        'content': '每月储蓄1000元',
        'evidence': '每月储蓄1000元',
        'updatedAt': 1,
      },
    ],
    'config': {'contextTokens': 24000, 'maxSteps': 20},
  };
  @override
  Future<Map<String, dynamic>> agentState() async => state;
  @override
  Future<Object?> invoke(String method, Map<String, dynamic> args) async {
    if (method == 'ask' && delayedAnswer != null) {
      calls.add((method: method, arguments: args));
      return delayedAnswer!.future;
    }
    if (method == 'cancelAgent') {
      calls.add((method: method, arguments: args));
      onCancel?.call();
      return null;
    }
    if (method == 'agentResolveAction') {
      calls.add((method: method, arguments: args));
      final session = state['session'] as Map;
      (session['actions'] as List).first['status'] = args['approve'] == true
          ? 'applied'
          : 'rejected';
      session['status'] = 'completed';
      return {'answer': '处理完成', 'isError': false, ...state};
    }
    if (method == 'agentForgetMemory') {
      (state['memories'] as List).clear();
      return state;
    }
    return super.invoke(method, args);
  }
}

void main() {
  Future<({LedgerController controller, _AgentRepository repo})> open(
    WidgetTester tester, {
    double width = 390,
    double scale = 1,
    Brightness brightness = Brightness.light,
    Map<String, dynamic>? action,
    _AgentRepository? repository,
  }) async {
    tester.view.physicalSize = Size(width, 1000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final repo = repository ?? _AgentRepository();
    if (action != null) (repo.state['session'] as Map)['actions'] = [action];
    final controller = LedgerController(repository: repo);
    await controller.init();
    addTearDown(controller.dispose);
    addTearDown(repo.updates.close);
    await tester.pumpWidget(
      MaterialApp(
        theme: pennyTheme(brightness),
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(
            context,
          ).copyWith(textScaler: TextScaler.linear(scale)),
          child: child!,
        ),
        home: AnimatedBuilder(
          animation: controller,
          builder: (context, _) =>
              ChatScreen(controller: controller, onSettings: () {}),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return (controller: controller, repo: repo);
  }

  for (final layout in [
    (320.0, 1.3, Brightness.light),
    (768.0, 1.3, Brightness.dark),
  ]) {
    testWidgets(
      'Agent proposals, history and settings fit ${layout.$1} at large text',
      (tester) async {
        await open(
          tester,
          width: layout.$1,
          scale: layout.$2,
          brightness: layout.$3,
        );
        expect(find.text('待你核对'), findsOneWidget);
        expect(tester.takeException(), isNull);
        await tester.tap(find.byTooltip('对话、记忆与运行设置'));
        await tester.pumpAndSettle();
        expect(find.text('开始新对话'), findsOneWidget);
        await tester.tap(find.text('记忆').last);
        await tester.pumpAndSettle();
        expect(find.text('每月储蓄1000元'), findsOneWidget);
        await tester.tap(find.text('运行设置'));
        await tester.pumpAndSettle();
        expect(find.text('保存设置'), findsOneWidget);
        expect(tester.takeException(), isNull);
      },
    );
  }
  testWidgets(
    'proposal confirmation sends only stored id and decision, then removes the pending action',
    (tester) async {
      final result = await open(tester);
      await tester.ensureVisible(find.text('确认执行'));
      await tester.tap(find.text('确认执行'));
      await tester.pumpAndSettle();
      final call = result.repo.calls.single;
      expect(call.method, 'agentResolveAction');
      expect(call.arguments, {
        'revision': 0,
        'actionId': 'budget-1',
        'approve': true,
      });
      expect(find.text('待你核对'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets('execution evidence is accessible from the saved tool record', (
    tester,
  ) async {
    await open(tester);
    await tester.ensureVisible(find.text('执行记录 · 1 次工具调用'));
    await tester.tap(find.text('执行记录 · 1 次工具调用'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('第 1 步 · 依据 Q1'));
    await tester.tap(find.text('第 1 步 · 依据 Q1'));
    await tester.pumpAndSettle();
    expect(find.textContaining('净支出：40.00 元'), findsOneWidget);
    expect(find.textContaining('月预算：2,000.00 元'), findsOneWidget);
  });
  for (final layout in [
    (320.0, 1.5, Brightness.light),
    (768.0, 1.3, Brightness.dark),
  ]) {
    testWidgets(
      'transaction assumptions are visible before approval at ${layout.$1}',
      (tester) async {
        final result = await open(
          tester,
          width: layout.$1,
          scale: layout.$2,
          brightness: layout.$3,
          action: {
            'id': 'transaction-1',
            'kind': 'transaction',
            'title': '新增 2026-10-23 餐饮 17.00 元',
            'status': 'pending',
            'payload': {
              'transaction': {
                'amount': 1700,
                'type': 'EXPENSE',
                'metadataJson': jsonEncode({
                  'assumptions': ['10月23日按今年理解', '盖饭按餐饮分类'],
                }),
              },
            },
          },
        );
        await tester.ensureVisible(find.text('推断：盖饭按餐饮分类'));
        expect(find.text('推断：10月23日按今年理解'), findsOneWidget);
        expect(find.text('推断：盖饭按餐饮分类'), findsOneWidget);
        expect(result.repo.calls, isEmpty);
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets(
    'real text deltas render as Markdown before completion and stop remains available',
    (tester) async {
      final repo = _AgentRepository()
        ..delayedAnswer = Completer<Map<String, dynamic>>();
      (repo.state['session'] as Map).addAll(<String, Object>{
        'messages': <Map<String, dynamic>>[],
        'actions': [],
        'trace': [],
      });
      final result = await open(tester, repository: repo);
      await tester.enterText(
        find.byKey(const ValueKey('assistant-input')),
        '这个月支出多少？',
      );
      await tester.pump();
      await tester.tap(find.byTooltip('发送消息'));
      await tester.pump();
      for (final event in [
        {'phase': 'run_started', 'runId': 'live', 'userMessageId': 'live-user'},
        {'phase': 'text_start', 'runId': 'live', 'messageId': 'live-answer'},
        {
          'phase': 'text_delta',
          'runId': 'live',
          'messageId': 'live-answer',
          'delta': '### 月支出\n\n**40.00 元**',
        },
      ]) {
        repo.updates.add(LedgerEvent('agent', 0, event));
      }
      await tester.pump(const Duration(milliseconds: 80));
      await tester.pump();
      expect(result.controller.isAsking, true);
      expect(find.text('40.00 元', findRichText: true), findsOneWidget);
      expect(find.text('**40.00 元**'), findsNothing);
      expect(find.byTooltip('停止任务'), findsOneWidget);
      expect(find.byTooltip('复制回复'), findsNothing);
      (repo.state['session'] as Map).addAll(<String, Object>{
        'status': 'completed',
        'messages': [
          {
            'id': 'live-user',
            'runId': 'live',
            'role': 'user',
            'content': '这个月支出多少？',
          },
          {
            'id': 'live-answer',
            'runId': 'live',
            'role': 'assistant',
            'content': '### 月支出\n\n**40.00 元**',
          },
        ],
      });
      repo.delayedAnswer!.complete({
        'answer': '### 月支出\n\n**40.00 元**',
        'isError': false,
        ...repo.state,
      });
      await tester.pumpAndSettle();
      expect(find.byType(AssistantMarkdown), findsOneWidget);
      expect(find.byTooltip('复制回复'), findsOneWidget);
      expect(result.controller.isAsking, false);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'reading earlier messages pauses stream following until return to latest',
    (tester) async {
      final repo = _AgentRepository()
        ..delayedAnswer = Completer<Map<String, dynamic>>();
      final history = <Map<String, dynamic>>[
        for (var i = 0; i < 15; i++) ...[
          {'id': 'u$i', 'runId': 'r$i', 'role': 'user', 'content': '历史问题 $i'},
          {
            'id': 'a$i',
            'runId': 'r$i',
            'role': 'assistant',
            'content': '历史回答 $i\n\n这是一段已经完成的账本分析。',
          },
        ],
      ];
      (repo.state['session'] as Map).addAll(<String, Object>{
        'messages': history,
        'actions': [],
        'trace': [],
      });
      await open(tester, repository: repo);
      await tester.enterText(
        find.byKey(const ValueKey('assistant-input')),
        '继续分析',
      );
      await tester.pump();
      await tester.tap(find.byTooltip('发送消息'));
      await tester.pump();
      for (final event in [
        {'phase': 'run_started', 'runId': 'live', 'userMessageId': 'live-user'},
        {'phase': 'text_start', 'runId': 'live', 'messageId': 'live-answer'},
        {
          'phase': 'text_delta',
          'runId': 'live',
          'messageId': 'live-answer',
          'delta': '正在生成的回答。',
        },
      ]) {
        repo.updates.add(LedgerEvent('agent', 0, event));
      }
      await tester.pump(const Duration(milliseconds: 80));
      await tester.pump();
      final list = find.byKey(const ValueKey('assistant-conversation'));
      await tester.drag(list, const Offset(0, 500));
      await tester.pump(const Duration(milliseconds: 500));
      final scroll = tester.widget<ListView>(list).controller!;
      final before = scroll.offset;
      repo.updates.add(
        LedgerEvent('agent', 0, {
          'phase': 'text_delta',
          'runId': 'live',
          'messageId': 'live-answer',
          'delta': '\n\n${List.filled(20, '新的账本分析段落。').join('\n\n')}',
        }),
      );
      await tester.pump(const Duration(milliseconds: 80));
      await tester.pump();
      expect(scroll.offset, closeTo(before, 1));
      expect(find.text('回到最新'), findsOneWidget);
      await tester.tap(find.text('回到最新'));
      await tester.pump();
      expect(scroll.position.extentAfter, lessThan(2));
      (repo.state['session'] as Map)['messages'] = [
        ...history,
        {'id': 'live-user', 'runId': 'live', 'role': 'user', 'content': '继续分析'},
        {
          'id': 'live-answer',
          'runId': 'live',
          'role': 'assistant',
          'content': '分析完成。',
        },
      ];
      repo.delayedAnswer!.complete({
        'answer': '分析完成。',
        'isError': false,
        ...repo.state,
      });
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'tools and completed actions remain attached to their own saved turn',
    (tester) async {
      final repo = _AgentRepository();
      (repo.state['session'] as Map).addAll(<String, Object>{
        'messages': [
          {'id': 'u1', 'runId': 'r1', 'role': 'user', 'content': '调整预算'},
          {'id': 'a1', 'runId': 'r1', 'role': 'assistant', 'content': '预算已更新。'},
          {'id': 'u2', 'runId': 'r2', 'role': 'user', 'content': '看看支出'},
          {
            'id': 'a2',
            'runId': 'r2',
            'role': 'assistant',
            'content': '本月支出 40 元。',
          },
        ],
        'actions': [
          {
            'id': 'b1',
            'runId': 'r1',
            'kind': 'budget',
            'title': '月预算改为 3,000.00 元',
            'payload': {'amount': 300000, 'before': 200000},
            'status': 'applied',
          },
        ],
        'trace': [
          {
            'callId': 'c1',
            'runId': 'r1',
            'step': 1,
            'tool': 'get_budget',
            'status': 'completed',
            'evidenceId': 'Q1',
          },
          {
            'callId': 'c2',
            'runId': 'r2',
            'step': 1,
            'tool': 'get_summary',
            'status': 'completed',
            'evidenceId': 'Q2',
          },
        ],
      });
      await open(tester, repository: repo, width: 768);
      expect(find.text('已执行'), findsOneWidget);
      expect(find.text('确认执行'), findsNothing);
      expect(find.text('执行记录 · 1 次工具调用'), findsNWidgets(2));
      expect(find.text('执行记录 · 2 次工具调用'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'chat reasoning shortcuts persist the selected provider without sending a message',
    (tester) async {
      final repo = _AgentRepository();
      repo.data['settings'] = {
        ...repo.data['settings'] as Map<String, dynamic>,
        'deepSeekModel': 'deepseek-flash',
        'deepSeekThinkingEnabled': true,
        'deepSeekReasoningEffort': 'high',
      };
      await open(tester, repository: repo);
      final toggle = find.byKey(const ValueKey('assistant-thinking-toggle'));
      await tester.tap(toggle);
      await tester.pumpAndSettle();
      expect(repo.data['settings']['deepSeekThinkingEnabled'], false);
      expect(
        find.byKey(const ValueKey('assistant-thinking-effort')),
        findsNothing,
      );
      await tester.tap(toggle);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('assistant-thinking-effort')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('最高').last);
      await tester.pumpAndSettle();
      expect(repo.data['settings']['deepSeekReasoningEffort'], 'max');
      expect(repo.calls.where((call) => call.method == 'ask'), isEmpty);
      expect(
        repo.calls
            .where((call) => call.method == 'updateSettings')
            .every(
              (call) => !call.arguments.keys.any(
                (key) => key.startsWith('qwen') || key.startsWith('chatGpt'),
              ),
            ),
        true,
      );
      expect(tester.takeException(), isNull);
    },
  );
}

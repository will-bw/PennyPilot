import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/core/ledger_repository.dart';
import 'package:pennypilot/features/chat/chat_screen.dart';
import 'package:pennypilot/ui/theme.dart';

import 'test_ledger_repository.dart';

class _AgentRepository extends TestLedgerRepository
    implements AgentSessionRepository {
  _AgentRepository() : super(populated: true);
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
  }) async {
    tester.view.physicalSize = Size(width, 1000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final repo = _AgentRepository();
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
}

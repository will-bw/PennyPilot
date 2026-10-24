import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:pennypilot/app.dart';
import 'package:pennypilot/core/agent/agent_models.dart';
import 'package:pennypilot/core/agent/agent_store.dart';
import 'package:pennypilot/core/ai/shared_ai_service.dart';
import 'package:pennypilot/core/cross_platform_repository.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/core/models.dart';
import 'package:pennypilot/core/platform/mobile_platform_services.dart';

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

class _Model extends SharedAiService {
  _Model() : super(credentials: _Credentials());
  @override
  Future<AgentModel> openAgentModel(
    LedgerSnapshot snapshot, {
    Future<AgentReply> Function(
      List<Map<String, dynamic>>,
      List<Map<String, dynamic>>,
    )?
    nativeTurn,
    required Future<void> Function() guard,
  }) async {
    var step = 0;
    AgentReply call(String name, Map<String, dynamic> args) => AgentReply(
      message: {
        'role': 'assistant',
        'content': '',
        'tool_calls': [
          {
            'id': 'preview-$step',
            'type': 'function',
            'function': {'name': name, 'arguments': jsonEncode(args)},
          },
        ],
      },
    );
    return AgentModel(
      identity: 'synthetic-preview',
      complete: (messages, tools) async {
        await guard();
        switch (++step) {
          case 1:
            return call('update_plan', {
              'steps': [
                {'title': '比较两个月的支出和分类变化', 'status': 'in_progress'},
                {'title': '查看主要分类的交易明细', 'status': 'pending'},
                {'title': '记录储蓄目标，准备预算提案', 'status': 'pending'},
              ],
            });
          case 2:
            return call('compare_periods', {
              'period_a_start': '2026-09-01',
              'period_a_end': '2026-09-30',
              'period_b_start': '2026-10-01',
              'period_b_end': '2026-10-31',
            });
          case 3:
            return call('query_transactions', {
              'start': '2026-10-01',
              'end': '2026-10-31',
              'category_id': 1,
            });
          case 4:
            return call('remember', {
              'key': 'savings_goal',
              'content': '每月储蓄1000元',
              'kind': 'goal',
              'evidence': '每月储蓄1000元',
            });
          case 5:
            return call('propose_budget', {
              'amount': 300000,
              'evidence': '月预算改为3000元',
            });
          case 6:
            return call('update_plan', {
              'steps': [
                {'title': '比较两个月的支出和分类变化', 'status': 'completed'},
                {'title': '查看主要分类的交易明细', 'status': 'completed'},
                {'title': '记录储蓄目标，准备预算提案', 'status': 'completed'},
              ],
            });
          default:
            return const AgentReply(
              message: {
                'role': 'assistant',
                'content':
                    '10月已确认支出40元，其中餐饮28元、交通12元。9月没有已确认记录，因此不能把差额直接解释为消费增长。[依据 Q1]\n\n'
                    '餐饮明细来自午餐记录。[依据 Q2] 已记住每月储蓄1000元的目标，预算改为3000元的提案等待你核对，尚未修改。',
              },
            );
        }
      },
    );
  }
}

/// Dedicated simulator only. Uses synthetic data + scripted HTTP-free model;
/// production stores, credentials and the user's real phone are never touched.
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  testWidgets('Agent UI on isolated simulator', (tester) async {
    const darkLarge = bool.fromEnvironment('AGENT_DARK_LARGE');
    if (darkLarge) tester.platformDispatcher.textScaleFactorTestValue = 1.3;
    final fake = TestLedgerRepository(
      populated: true,
      theme: darkLarge ? 'dark' : 'light',
    );
    final repository = CrossPlatformLedgerRepository(
      store: fake,
      platformServices: _Platform(),
      credentials: _Credentials(),
      ai: _Model(),
      agentStore: MemoryAgentStore(),
      android: false,
    );
    final controller = LedgerController(repository: repository);
    await tester.pumpWidget(PennyPilotApp(controller: controller));
    await tester.pumpAndSettle();
    await tester.tap(find.text('问账'));
    await tester.pumpAndSettle();
    await controller.ask('对比本月和上月，再查餐饮明细。记住每月储蓄1000元，并把月预算改为3000元。');
    await tester.pumpAndSettle();
    expect(controller.messages.last.isError, false);
    expect(controller.agentMemories, hasLength(1));
    expect(
      (controller.agentSession['actions'] as List).single['status'],
      'pending',
    );
    // Host screenshot collector watches these markers. Give it a stable render.
    debugPrint('AGENT_CAPTURE_CHAT');
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(seconds: 8)),
    );
    await tester.tap(find.byTooltip('对话、记忆与运行设置'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('记忆').last);
    await tester.pumpAndSettle();
    debugPrint('AGENT_CAPTURE_MEMORY');
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(seconds: 8)),
    );
    await tester.tap(find.text('运行设置'));
    await tester.pumpAndSettle();
    debugPrint('AGENT_CAPTURE_SETTINGS');
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(seconds: 8)),
    );
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
    controller.dispose();
    await repository.close();
    await fake.updates.close();
  });
}

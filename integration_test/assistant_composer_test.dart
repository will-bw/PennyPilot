import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:pennypilot/app.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/core/ledger_repository.dart';
import 'package:pennypilot/features/chat/chat_screen.dart';

import '../test/ui/test_ledger_repository.dart';

/// Isolated presentation data. This repository never opens platform storage,
/// reads credentials, or calls a model; the amounts below belong only to this test.
class _ComposerRepository extends TestLedgerRepository
    implements AgentSessionRepository {
  _ComposerRepository() {
    data['transactions'] = [
      _transaction(1, 126000, 1, 9, '日常餐饮'),
      _transaction(2, 18000, 2, 9, '通勤交通'),
      _transaction(3, -800000, 3, 9, '工资'),
      _transaction(4, 153000, 1, 10, '日常餐饮'),
      _transaction(5, 17000, 2, 10, '通勤交通'),
      _transaction(6, -800000, 3, 10, '工资'),
    ];
    data['settings'] = <String, dynamic>{
      ...data['settings'] as Map<String, dynamic>,
      'themeMode': 'light',
      'deepSeekThinkingEnabled': true,
      'deepSeekReasoningEffort': 'high',
    };
  }

  static Map<String, dynamic> _transaction(
    int id,
    int amount,
    int category,
    int month,
    String merchant,
  ) => {
    ...fixtureTransaction(
      id: id,
      amount: amount,
      categoryId: category,
      merchant: merchant,
      type: amount < 0 ? 'INCOME' : 'EXPENSE',
    ),
    'occurredAt': DateTime(2026, month, 23, 12).millisecondsSinceEpoch,
    'createdAt': DateTime(2026, month, 23, 12).millisecondsSinceEpoch,
  };

  final state = <String, dynamic>{
    'session': {
      'id': 'composer-review',
      'title': '看看这两个月的收支',
      'status': 'completed',
      'messages': [
        {
          'id': 'question-1',
          'runId': 'review-1',
          'role': 'user',
          'content': '对比九月和十月，哪些支出增加了？',
        },
        {
          'id': 'answer-1',
          'runId': 'review-1',
          'role': 'assistant',
          'content':
              '十月支出 **1,700 元**，比九月多 260 元。\n\n'
              '- 餐饮：1,530 元，增加 270 元。\n'
              '- 交通：170 元，减少 10 元。\n\n'
              '两个月收入都是 8,000 元，支出增长主要在餐饮。'
              '可以接着把工作日午饭与周末聚餐分开看。',
        },
        {
          'id': 'question-2',
          'runId': 'review-2',
          'role': 'user',
          'content': '想把餐饮控制一下，有什么做法？',
        },
        {
          'id': 'answer-2',
          'runId': 'review-2',
          'role': 'assistant',
          'content':
              '可以先从这次增加的 **270 元**入手，给自己定个容易坚持的小目标。\n\n'
              '把午饭与聚餐分开记录，每周回顾一次。先看哪部分增加，'
              '再决定减少外卖次数，还是调整聚餐预算。\n\n'
              '如果你愿意，我可以继续帮你拆分餐饮记录；'
              '调整预算前会先让你核对。',
        },
      ],
      'actions': [],
      'trace': [],
      'plan': [],
    },
    'sessions': [
      {'id': 'composer-review', 'title': '看看这两个月的收支', 'updatedAt': 1},
    ],
    'memories': [],
    'config': {'contextTokens': 24000, 'maxSteps': 20},
  };

  @override
  Future<Map<String, dynamic>> agentState() async => state;
}

Finder get _input => find.byKey(const ValueKey('assistant-input'));
Finder get _composer => find.byKey(const ValueKey('assistant-composer'));
Finder get _conversation =>
    find.byKey(const ValueKey('assistant-conversation'));

double _keyboardInset(WidgetTester tester) =>
    MediaQuery.viewInsetsOf(tester.element(find.byType(ChatScreen))).bottom;

Future<void> _waitFor(
  WidgetTester tester,
  bool Function() ready,
  String description,
) async {
  for (var i = 0; i < 160; i++) {
    if (ready()) return;
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

/// Run only on a dedicated simulator/device with --no-uninstall. Native software
/// keyboard must be enabled: no fake keyboard, viewport, or window inset is used.
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('native assistant composer preserves space for reading and writing', (
    tester,
  ) async {
    final repository = _ComposerRepository();
    final controller = LedgerController(repository: repository);
    addTearDown(() async {
      controller.dispose();
      await repository.updates.close();
    });

    await tester.pumpWidget(PennyPilotApp(controller: controller));
    await tester.pumpAndSettle();
    await tester.tap(find.text('AI 助手').last);
    await tester.pumpAndSettle();

    final wide = MediaQuery.sizeOf(tester.element(_input)).width >= 840;
    expect(find.byType(NavigationRail), wide ? findsOneWidget : findsNothing);
    expect(find.byType(NavigationBar), wide ? findsNothing : findsOneWidget);
    expect(tester.getSize(_composer).height, lessThanOrEqualTo(100));
    expect(tester.getSize(_conversation).height, greaterThan(300));
    expect(controller.messages, hasLength(4));
    expect(controller.isAsking, isFalse);
    await _captureHold(tester, 'COMPOSER_CAPTURE_PHONE');

    // Integration binding leaves the native text input connected. Opening it
    // through the actual field also verifies real IME insets reach the app shell.
    await tester.tap(_input);
    await _waitFor(
      tester,
      () => _keyboardInset(tester) > 0,
      '原生软件键盘出现；请关闭模拟器的硬件键盘连接',
    );
    const draft =
        '这周外卖花得有点多，\n'
        '帮我看看午饭和聚餐分别多少。\n'
        '退款单独列出，\n'
        '账户间转账不要计入支出。\n'
        '先给我一个简短结论，\n'
        '再列出需要核对的账单。';
    // Update the real controlled field without registering TestTextInput, which
    // would replace the native keyboard under an integration-test binding.
    controller.setAssistantDraft(draft);
    await tester.pumpAndSettle();
    expect(tester.widget<TextField>(_input).focusNode!.hasFocus, isTrue);
    expect(tester.widget<TextField>(_input).controller!.text, draft);
    expect(find.byType(NavigationBar), findsNothing);
    expect(tester.getSize(_composer).height, lessThan(180));
    expect(tester.getSize(_conversation).height, greaterThan(120));
    expect(controller.isAsking, isFalse);
    debugPrint(
      'COMPOSER_NATIVE_METRICS inset=${_keyboardInset(tester).round()} '
      'composer=${tester.getSize(_composer).height.round()} '
      'conversation=${tester.getSize(_conversation).height.round()}',
    );
    await _captureHold(tester, 'COMPOSER_CAPTURE_KEYBOARD');

    FocusManager.instance.primaryFocus?.unfocus();
    await _waitFor(tester, () => _keyboardInset(tester) == 0, '原生键盘收起');
    await controller.updateSettings({'themeMode': 'dark'});
    await tester.pumpAndSettle();
    expect(Theme.of(tester.element(_input)).brightness, Brightness.dark);
    expect(controller.assistantDraft, draft);
    await _captureHold(tester, 'COMPOSER_CAPTURE_DARK');

    await tester.tap(find.byTooltip('添加附件和更多'));
    await tester.pumpAndSettle();
    expect(find.text('输入与附件'), findsOneWidget);
    expect(find.widgetWithText(ListTile, '添加记账图片'), findsOneWidget);
    expect(find.text('思考设置'), findsOneWidget);
    expect(_keyboardInset(tester), 0);
    expect(controller.assistantDraft, draft);
    await _captureHold(tester, 'COMPOSER_CAPTURE_MORE');
    await tester.tap(find.byTooltip('关闭输入选项'));
    await tester.pumpAndSettle();

    expect(controller.messages, hasLength(4));
    expect(
      repository.calls.where((call) => call.method != 'updateSettings'),
      isEmpty,
      reason:
          'Presentation review must not send input or mutate ledger records.',
    );
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpAndSettle();
  });
}

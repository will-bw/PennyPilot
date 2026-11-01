import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/app.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/features/chat/chat_screen.dart';

import 'test_ledger_repository.dart';

class _KeyboardRepository extends TestLedgerRepository {
  _KeyboardRepository() : super(populated: true);
  final reply = Completer<Map<String, dynamic>>();

  @override
  Future<Object?> invoke(String method, Map<String, dynamic> arguments) async {
    if (method == 'ask') {
      calls.add((method: method, arguments: arguments));
      return reply.future;
    }
    if (method == 'cancelAgent') {
      calls.add((method: method, arguments: arguments));
      if (!reply.isCompleted) {
        reply.complete({'answer': '已停止本次任务。', 'isError': true});
      }
      return null;
    }
    return super.invoke(method, arguments);
  }
}

void main() {
  const keyboardHeight = 300.0;
  const screenHeight = 844.0;
  Finder input() => find.byKey(const ValueKey('assistant-input'));

  Future<({LedgerController controller, _KeyboardRepository repository})> open(
    WidgetTester tester, {
    double width = 320,
  }) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = Size(width, screenHeight);
    tester.platformDispatcher.textScaleFactorTestValue = 1.5;
    final repository = _KeyboardRepository();
    final controller = LedgerController(repository: repository);
    addTearDown(() async {
      tester.view.resetViewInsets();
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
      tester.platformDispatcher.clearTextScaleFactorTestValue();
      if (!repository.reply.isCompleted) {
        repository.reply.complete({'answer': '测试结束', 'isError': false});
      }
      controller.dispose();
      await repository.updates.close();
    });
    await tester.pumpWidget(PennyPilotApp(controller: controller));
    await tester.pumpAndSettle();
    return (controller: controller, repository: repository);
  }

  Future<void> select(WidgetTester tester, String title) async {
    final navigation = find.byType(NavigationRail).evaluate().isNotEmpty
        ? find.byType(NavigationRail)
        : find.byType(NavigationBar);
    await tester.tap(
      find.descendant(of: navigation, matching: find.text(title)).last,
    );
    await tester.pumpAndSettle();
  }

  Future<void> showKeyboard(WidgetTester tester) async {
    await tester.showKeyboard(input());
    tester.view.viewInsets = const FakeViewPadding(bottom: keyboardHeight);
    await tester.pumpAndSettle();
  }

  void expectAboveKeyboard(WidgetTester tester, Finder control) {
    expect(control.hitTestable(), findsOneWidget);
    final rect = tester.getRect(control);
    expect(rect.top, greaterThanOrEqualTo(0));
    expect(rect.bottom, lessThanOrEqualTo(screenHeight - keyboardHeight));
  }

  testWidgets(
    'phone chat hides navigation only while the keyboard is visible and consumes its inset once',
    (tester) async {
      await open(tester);
      await select(tester, 'AI 助手');
      expect(find.byType(NavigationBar), findsOneWidget);
      await tester.enterText(input(), '对比本月支出');
      await showKeyboard(tester);
      expect(find.byType(NavigationBar), findsNothing);
      expect(
        MediaQuery.viewInsetsOf(tester.element(find.byType(ChatScreen))).bottom,
        keyboardHeight,
      );
      expectAboveKeyboard(tester, find.byTooltip('发送消息'));
      expect(
        tester.getBottomLeft(find.byTooltip('发送消息')).dy,
        greaterThan(screenHeight - keyboardHeight - 160),
      );
      expect(tester.takeException(), isNull);
      tester.view.resetViewInsets();
      await tester.pumpAndSettle();
      expect(find.byType(NavigationBar), findsOneWidget);
      expect(tester.widget<TextField>(input()).controller!.text, '对比本月支出');
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('other phone destinations retain navigation with a keyboard', (
    tester,
  ) async {
    await open(tester);
    tester.view.viewInsets = const FakeViewPadding(bottom: keyboardHeight);
    await tester.pumpAndSettle();
    expect(find.byType(NavigationBar), findsOneWidget);
    expect(
      tester.widget<NavigationBar>(find.byType(NavigationBar)).selectedIndex,
      0,
    );
    expect(tester.takeException(), isNull);
    tester.view.resetViewInsets();
    await tester.pumpAndSettle();
    await select(tester, 'AI 助手');
    await tester.enterText(input(), '保留我的草稿');
    await showKeyboard(tester);
    final focus = tester.widget<TextField>(input()).focusNode!;
    expect(focus.hasFocus, isTrue);
    tester.widget<ChatScreen>(find.byType(ChatScreen)).onSettings();
    await tester.pumpAndSettle();
    expect(focus.hasFocus, isFalse);
    expect(find.byType(NavigationBar), findsOneWidget);
    expect(
      tester.widget<NavigationBar>(find.byType(NavigationBar)).selectedIndex,
      3,
    );
    expect(tester.takeException(), isNull);
  });

  for (final keyboard in [false, true]) {
    testWidgets(
      'whole-app chat feedback stays above the composer with keyboard $keyboard',
      (tester) async {
        await open(tester);
        await select(tester, 'AI 助手');
        await tester.enterText(input(), '保存后继续输入');
        if (keyboard) await showKeyboard(tester);
        final chatContext = tester.element(find.byType(ChatScreen));
        final messenger = ScaffoldMessenger.of(chatContext);
        messenger.showSnackBar(
          const SnackBar(
            content: Text('这条反馈不能遮挡输入区'),
            behavior: SnackBarBehavior.floating,
          ),
        );
        await tester.pumpAndSettle();
        expect(find.byType(SnackBar), findsOneWidget);
        final composer = find.byKey(const ValueKey('assistant-composer'));
        expect(
          tester.getRect(find.byType(SnackBar)).bottom,
          lessThanOrEqualTo(tester.getRect(composer).top),
        );
        expect(find.byTooltip('发送消息').hitTestable(), findsOneWidget);
        expect(tester.takeException(), isNull);
        messenger.removeCurrentSnackBar();
        await tester.pumpAndSettle();
      },
    );
  }

  testWidgets(
    'wide chat keeps its rail and switching destinations dismisses input focus',
    (tester) async {
      final result = await open(tester, width: 1280);
      await select(tester, 'AI 助手');
      await tester.enterText(input(), '切换页面后继续写');
      await showKeyboard(tester);
      final focus = tester.widget<TextField>(input()).focusNode!;
      expect(focus.hasFocus, isTrue);
      expect(find.byType(NavigationRail), findsOneWidget);
      expect(find.byType(NavigationBar), findsNothing);
      expectAboveKeyboard(tester, find.byTooltip('发送消息'));
      await select(tester, '设置');
      expect(focus.hasFocus, isFalse);
      expect(result.controller.assistantDraft, '切换页面后继续写');
      expect(
        tester
            .widget<NavigationRail>(find.byType(NavigationRail))
            .selectedIndex,
        3,
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'send and stop remain reachable above a phone keyboard at large text',
    (tester) async {
      final result = await open(tester);
      await select(tester, 'AI 助手');
      await tester.enterText(input(), '看看本月预算');
      await showKeyboard(tester);
      expectAboveKeyboard(tester, find.byTooltip('发送消息'));
      await tester.tap(find.byTooltip('发送消息'));
      await tester.pump(const Duration(milliseconds: 100));
      expect(result.controller.isAsking, isTrue);
      expect(find.byType(NavigationBar), findsNothing);
      expectAboveKeyboard(tester, find.byTooltip('停止任务'));
      await tester.enterText(input(), '下一条草稿');
      await tester.pump();
      await tester.tap(find.byTooltip('停止任务'));
      await tester.pumpAndSettle();
      expect(result.controller.isAsking, isFalse);
      expect(result.controller.assistantDraft, '下一条草稿');
      expect(
        result.repository.calls.where((call) => call.method == 'ask'),
        hasLength(1),
      );
      expect(
        result.repository.calls.where((call) => call.method == 'cancelAgent'),
        hasLength(1),
      );
      expect(tester.takeException(), isNull);
    },
  );
}

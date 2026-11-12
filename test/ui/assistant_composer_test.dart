import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/core/ledger_repository.dart';
import 'package:pennypilot/features/chat/chat_screen.dart';
import 'package:pennypilot/ui/theme.dart';

import 'test_ledger_repository.dart';

class _PendingRecognitionRepository extends TestLedgerRepository
    implements AgentSessionRepository {
  @override
  Future<Map<String, dynamic>> agentState() async => {
    'session': {
      'id': 'recognition-session',
      'status': 'awaiting_recognition',
      'messages': [
        {'role': 'user', 'runId': 'receipt', 'content': '识别这张凭证'},
        {
          'role': 'assistant',
          'runId': 'receipt',
          'content': '这笔是消费还是转账？',
          'parts': [
            {
              'type': 'recognition_state',
              'runId': 'receipt',
              'text': '识别这张凭证',
              'questions': ['这笔是消费还是转账？'],
            },
          ],
        },
      ],
    },
  };
}

void main() {
  final input = find.byKey(const ValueKey('assistant-input'));
  final composer = find.byKey(const ValueKey('assistant-composer'));
  final conversation = find.byKey(const ValueKey('assistant-conversation'));

  Future<LedgerController> open(
    WidgetTester tester, {
    double width = 390,
    double height = 844,
    double scale = 1,
    Brightness brightness = Brightness.light,
    TestLedgerRepository? repository,
  }) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = Size(width, height);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetViewInsets);
    final store = repository ?? TestLedgerRepository(populated: true);
    final controller = LedgerController(repository: store);
    await controller.init();
    addTearDown(controller.dispose);
    addTearDown(store.updates.close);
    await tester.pumpWidget(
      MaterialApp(
        theme: pennyTheme(brightness),
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(
            context,
          ).copyWith(textScaler: TextScaler.linear(scale)),
          child: child!,
        ),
        home: ChatScreen(controller: controller, onSettings: () {}),
      ),
    );
    await tester.pumpAndSettle();
    return controller;
  }

  testWidgets('pending receipt does not consume a new question', (
    tester,
  ) async {
    final store = _PendingRecognitionRepository();
    final controller = await open(tester, repository: store);
    expect(controller.canSupplementRecognition, isTrue);
    expect(find.text('补充这次识别').hitTestable(), findsOneWidget);
    expect(find.text('取消识别').hitTestable(), findsOneWidget);
    await tester.enterText(input, '上个月花了多少钱？');
    await tester.pump();
    expect(find.byTooltip('发送消息').hitTestable(), findsOneWidget);
    await tester.tap(find.byTooltip('发送消息'));
    await tester.pumpAndSettle();
    expect(
      store.calls.where((call) => call.method == 'recognizeInput'),
      isEmpty,
    );
    expect(
      store.calls
          .singleWhere((call) => call.method == 'ask')
          .arguments['question'],
      '上个月花了多少钱？',
    );
    expect(controller.canSupplementRecognition, isTrue);
    await tester.tap(find.text('补充这次识别'));
    await tester.pumpAndSettle();
    await tester.enterText(input, '这笔是消费');
    await tester.pump();
    expect(find.byTooltip('发送补充').hitTestable(), findsOneWidget);
    await tester.tap(find.text('改为新问题'));
    await tester.pumpAndSettle();
    expect(find.byTooltip('发送消息').hitTestable(), findsOneWidget);
    expect(controller.assistantDraft, '这笔是消费');
  });

  for (final layout in [
    (width: 390.0, scale: 1.0, brightness: Brightness.light),
    (width: 320.0, scale: 1.5, brightness: Brightness.light),
    (width: 768.0, scale: 1.5, brightness: Brightness.dark),
  ]) {
    testWidgets(
      'idle composer leaves reading space at ${layout.width}/${layout.scale}',
      (tester) async {
        await open(
          tester,
          width: layout.width,
          scale: layout.scale,
          brightness: layout.brightness,
        );
        expect(tester.getSize(composer).height, lessThanOrEqualTo(100));
        expect(tester.getSize(conversation).height, greaterThan(600));
        expect(find.byTooltip('语音输入').hitTestable(), findsOneWidget);
        expect(find.byTooltip('添加附件和更多').hitTestable(), findsOneWidget);
        expect(
          find.byKey(const ValueKey('assistant-thinking-toggle')),
          findsNothing,
        );
        await tester.enterText(input, '午饭28元');
        await tester.pump();
        expect(find.byTooltip('发送消息').hitTestable(), findsOneWidget);
        expect(tester.getSize(composer).height, lessThanOrEqualTo(100));
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets(
    'a long draft stays bounded and editable above a short phone keyboard',
    (tester) async {
      final controller = await open(
        tester,
        width: 320,
        height: 600,
        scale: 1.5,
      );
      final draft = List.filled(12, '这是需要保留的多行账务说明。').join('\n');
      await tester.enterText(input, draft);
      tester.view.viewInsets = const FakeViewPadding(bottom: 260);
      await tester.pumpAndSettle();
      expect(tester.getSize(composer).height, lessThanOrEqualTo(160));
      expect(tester.getSize(conversation).height, greaterThan(90));
      final send = find.byTooltip('发送消息');
      expect(send.hitTestable(), findsOneWidget);
      expect(tester.getBottomRight(send).dy, lessThanOrEqualTo(340));
      expect(controller.assistantDraft, draft);
      await tester.tap(find.byTooltip('添加附件和更多'));
      await tester.pumpAndSettle();
      expect(find.text('添加记账图片').hitTestable(), findsOneWidget);
      await tester.tap(find.byTooltip('关闭输入选项'));
      await tester.pumpAndSettle();
      expect(tester.widget<TextField>(input).controller!.text, draft);
      expect(tester.takeException(), isNull);
    },
  );
}

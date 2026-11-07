import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/core/ledger_repository.dart';
import 'package:pennypilot/core/storage/assistant_attachment_store.dart';
import 'package:pennypilot/features/chat/chat_screen.dart';
import 'package:pennypilot/features/entry/entry_screen.dart';
import 'package:pennypilot/features/ledger/ledger_screen.dart';
import 'package:pennypilot/ui/theme.dart';

import 'app_widget_test.dart' as app_test;
import 'test_ledger_repository.dart';

const imageBase64 =
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=';

class _Attachments extends AssistantAttachmentStore {
  @override
  Future<Map<String, dynamic>> save(Map<String, dynamic> attachment) async => {
    'type': 'attachment',
    'name': '测试凭证',
    'mimeType': 'image/png',
  };
  @override
  Future<Map<String, dynamic>> read(Map<String, dynamic> attachment) async => {
    'imageBase64': imageBase64,
    'mimeType': 'image/png',
  };
}

class EntryRepository extends TestLedgerRepository
    implements AgentSessionRepository {
  EntryRepository({super.theme, bool autoSave = false}) {
    (data['settings'] as Map).addAll(<String, Object>{
      'hasDeepSeekKey': true,
      'activeInputAutoSaveEnabled': autoSave,
      'cloudImageParsingEnabled': true,
      'hasSpeechKey': true,
    });
  }

  final responses = <Map<String, dynamic>>[];
  Completer<Map<String, dynamic>>? speech;
  final state = <String, dynamic>{
    'session': {
      'id': 'recognition-session',
      'status': 'completed',
      'messages': <Map<String, dynamic>>[],
      'actions': <Map<String, dynamic>>[],
      'trace': [],
      'plan': [],
    },
    'sessions': [
      {'id': 'recognition-session', 'title': '新对话', 'updatedAt': 1},
    ],
    'memories': [],
    'config': {},
  };

  @override
  Future<Map<String, dynamic>> agentState() async => state;

  @override
  Future<Object?> invoke(String method, Map<String, dynamic> arguments) async {
    switch (method) {
      case 'recognizeInput':
        calls.add((method: method, arguments: arguments));
        return responses.removeAt(0);
      case 'agentAppendInput':
        calls.add((method: method, arguments: arguments));
        final session = state['session'] as Map;
        final messages = session['messages'] as List;
        final runId = arguments['runId'];
        final assistant = messages
            .where((m) => m['role'] == 'assistant' && m['runId'] == runId)
            .firstOrNull;
        if (assistant == null) {
          messages.addAll(<Map<String, dynamic>>[
            {
              'id': '$runId:user',
              'runId': runId,
              'role': 'user',
              'content': arguments['question'],
              'source': arguments['source'],
              if (arguments['attachment'] != null)
                'attachments': [arguments['attachment']],
            },
            {
              'id': '$runId:answer',
              'runId': runId,
              'role': 'assistant',
              'content': arguments['answer'],
              'parts': arguments['parts'],
            },
          ]);
        } else {
          assistant['content'] = arguments['answer'];
          assistant['parts'] = arguments['parts'];
        }
        session['status'] = arguments['status'];
        return state;
      case 'saveTransactions':
        calls.add((method: method, arguments: arguments));
        final rows = data['transactions'] as List;
        final ids = <int>[];
        for (final row in arguments['transactions'] as List) {
          final id = rows.length + 1;
          rows.add(<String, dynamic>{
            ...Map<String, dynamic>.from(row as Map),
            'id': id,
          });
          ids.add(id);
        }
        return {'ids': ids};
      case 'undoRecognitionSave':
        calls.add((method: method, arguments: arguments));
        final ids = (arguments['transactions'] as List)
            .map((r) => r['id'])
            .toSet();
        (data['transactions'] as List).removeWhere(
          (row) => ids.contains(row['id']),
        );
        return {'count': ids.length};
      case 'agentUndoRun':
        calls.add((method: method, arguments: arguments));
        final messages = (state['session'] as Map)['messages'] as List;
        final answer = messages.lastWhere(
          (m) => m['role'] == 'assistant' && m['runId'] == arguments['runId'],
        );
        final receipt = (answer['parts'] as List).firstWhere(
          (p) => p['type'] == 'receipt',
        );
        final ids = (receipt['transactions'] as List)
            .map((r) => r['id'])
            .toSet();
        (data['transactions'] as List).removeWhere(
          (row) => ids.contains(row['id']),
        );
        receipt['status'] = 'undone';
        receipt['title'] = '已撤销本次记账';
        return {'count': ids.length, ...state};
      case 'pickRecognitionImage':
        return {'base64': imageBase64, 'mimeType': 'image/png'};
      case 'startSpeech':
        calls.add((method: method, arguments: arguments));
        return speech == null ? {'text': '午饭二十八元'} : speech!.future;
      case 'stopSpeech':
        speech?.complete({'text': '午饭二十八元'});
        return null;
      case 'cancelSpeech':
        calls.add((method: method, arguments: arguments));
        if (speech != null && !speech!.isCompleted) {
          speech!.complete({'text': ''});
        }
        return null;
      default:
        return super.invoke(method, arguments);
    }
  }
}

Map<String, dynamic> draft(int index, {int amount = 1700}) => {
  ...fixtureTransaction(
    id: 0,
    amount: amount,
    categoryId: 1,
    merchant: index == 0 ? '盖饭' : '地铁',
    source: 'AI_IMAGE',
  ),
  'businessKind': 'expense',
  'metadataJson': jsonEncode({
    'operationId': 'entry:$index',
    'timePrecision': 'approximate',
    'assumptions': ['没有说年份，按最近已发生的日期记录'],
  }),
};

Future<LedgerController> openAssistant(
  WidgetTester tester,
  EntryRepository repository, {
  Size size = const Size(390, 844),
  double textScale = 1,
}) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = size;
  tester.platformDispatcher.textScaleFactorTestValue = textScale;
  addTearDown(() {
    tester.view.resetPhysicalSize();
    tester.view.resetDevicePixelRatio();
    tester.platformDispatcher.clearTextScaleFactorTestValue();
  });
  final controller = LedgerController(
    repository: repository,
    attachmentStore: _Attachments(),
    clock: () => DateTime(2026, 10, 23),
  );
  await controller.init();
  addTearDown(() async {
    controller.dispose();
    await repository.updates.close();
  });
  await tester.pumpWidget(
    MaterialApp(
      theme: pennyTheme(
        repository.data['settings']['themeMode'] == 'dark'
            ? Brightness.dark
            : Brightness.light,
      ),
      home: ChatScreen(controller: controller, onSettings: () {}),
    ),
  );
  await tester.pumpAndSettle();
  return controller;
}

Finder messageInput() => find.byKey(const ValueKey('assistant-input'));
Finder conversationScroll() => find
    .descendant(
      of: find.byKey(const ValueKey('assistant-conversation')),
      matching: find.byType(Scrollable),
    )
    .first;

Future<void> sendImage(WidgetTester tester, {String text = ''}) async {
  if (text.isNotEmpty) await tester.enterText(messageInput(), text);
  await chooseAssistantInputOption(tester, '添加记账图片');
  await tester.tap(find.byTooltip('发送消息'));
  await tester.pumpAndSettle();
  final controller = tester
      .widget<ChatScreen>(find.byType(ChatScreen))
      .controller;
  expect(controller.error, isNull, reason: '凭证识别应完整成功：${controller.error}');
}

Future<void> chooseAssistantInputOption(
  WidgetTester tester,
  String label,
) async {
  await tester.tap(find.byTooltip('添加附件和更多'));
  await tester.pumpAndSettle();
  final option = find.widgetWithText(ListTile, label);
  await tester.ensureVisible(option);
  await tester.tap(option);
  await tester.pumpAndSettle();
}

Future<void> supplement(WidgetTester tester, String text) async {
  await tester.enterText(messageInput(), text);
  await tester.pump();
  final send = find.byTooltip('发送补充');
  expect(send.hitTestable(), findsOneWidget);
  await tester.tap(send.hitTestable());
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('a receipt draft without a date requires using the date picker', (
    tester,
  ) async {
    final repository = EntryRepository(autoSave: true);
    final controller = await openAssistant(tester, repository);
    await controller.addLocalReceiptDraft({
      'amount': 1800,
      'type': 'EXPENSE',
      'merchant': '测试截图商家',
      'businessKind': 'expense',
      'metadata': {
        'operationId': 'local-receipt:widget-test',
        'receiptInput': 'local_image',
      },
    });
    await tester.pumpAndSettle();
    final draft = find.text('交易日期待选');
    await tester.ensureVisible(draft);
    await tester.tap(draft);
    await tester.pumpAndSettle();
    expect(find.byType(EntryScreen), findsOneWidget);
    await tester.tap(find.text('完成核对'));
    await tester.pumpAndSettle();
    expect(find.text('请先选择这张凭证的实际交易日期。'), findsOneWidget);
    await tester.pump(const Duration(seconds: 5));
    await tester.pumpAndSettle();
    final date = find.text('交易日期');
    await Scrollable.ensureVisible(tester.element(date), alignment: .4);
    await tester.pumpAndSettle();
    await tester.tap(date);
    await tester.pumpAndSettle();
    final picker = find.byType(DatePickerDialog);
    await tester.tap(find.descendant(of: picker, matching: find.text('22')));
    await tester.tap(find.descendant(of: picker, matching: find.text('OK')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('完成核对'));
    await tester.pumpAndSettle();
    expect(find.byType(EntryScreen), findsNothing);
    expect(controller.drafts.single.occurredAt, DateTime(2026, 10, 22));
    expect(
      controller.drafts.single.recognitionMetadata['receiptDateUnconfirmed'],
      isFalse,
    );
    expect(
      repository.calls.where((call) => call.method == 'saveTransactions'),
      isEmpty,
    );
  });

  setUpAll(app_test.loadScreenshotFonts);

  testWidgets(
    'empty assistant keeps its heading visible when editing a draft at 320 and large text',
    (tester) async {
      await openAssistant(
        tester,
        EntryRepository(),
        size: const Size(320, 844),
        textScale: 1.5,
      );
      final scroll = tester
          .widget<ListView>(
            find.byKey(const ValueKey('assistant-conversation')),
          )
          .controller!;
      expect(scroll.offset, 0);
      expect(find.text('记一笔，或问问账本…'), findsOneWidget);
      expect(
        tester.widget<TextField>(messageInput()).decoration!.labelText,
        isNull,
      );
      await tester.enterText(messageInput(), '明天再看看这笔消费');
      await tester.pumpAndSettle();
      expect(scroll.offset, 0);
      final heading = find.text('把收支交给助手');
      expect(
        tester.getTopLeft(heading).dy,
        greaterThanOrEqualTo(tester.getBottomLeft(find.byType(AppBar)).dy),
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'attachment thumbnail opens a full-screen image that supports pinch zoom without sending',
    (tester) async {
      final repository = EntryRepository();
      final c = await openAssistant(tester, repository);
      await chooseAssistantInputOption(tester, '添加记账图片');
      await tester.tap(
        find.byKey(const ValueKey('assistant-attachment-preview')),
      );
      await tester.pumpAndSettle();
      expect(
        find.byKey(const ValueKey('assistant-attachment-viewer')),
        findsOneWidget,
      );
      expect(find.byType(InteractiveViewer), findsOneWidget);
      final center = tester.getCenter(find.byType(InteractiveViewer));
      final first = await tester.createGesture(pointer: 1);
      final second = await tester.createGesture(pointer: 2);
      await first.down(center - const Offset(20, 0));
      await second.down(center + const Offset(20, 0));
      await first.moveTo(center - const Offset(60, 0));
      await second.moveTo(center + const Offset(60, 0));
      await tester.pump();
      final transform = tester.widget<Transform>(
        find
            .descendant(
              of: find.byType(InteractiveViewer),
              matching: find.byType(Transform),
            )
            .first,
      );
      expect(transform.transform.getMaxScaleOnAxis(), greaterThan(1));
      await first.up();
      await second.up();
      await tester.tap(find.byType(CloseButton));
      await tester.pumpAndSettle();
      expect(
        find.byKey(const ValueKey('assistant-attachment-viewer')),
        findsNothing,
      );
      expect(c.assistantAttachment, isNotNull);
      expect(
        repository.calls.where(
          (call) => call.method == 'recognizeInput' || call.method == 'ask',
        ),
        isEmpty,
      );
      expect(tester.takeException(), isNull);
    },
  );

  for (final theme in ['light', 'dark']) {
    testWidgets(
      '$theme image recognition saves automatically and can be undone from its run',
      (tester) async {
        final repository = EntryRepository(theme: theme, autoSave: true)
          ..responses.add({
            'transactions': [draft(0)],
            'questions': <String>[],
            'autoSaveEligible': true,
          });
        final c = await openAssistant(tester, repository);
        await sendImage(tester, text: '昨天晚上7点过吃的盖饭17块钱');
        expect(c.transactions, hasLength(1));
        expect(c.drafts, isEmpty);
        expect(find.text('保存这 1 笔账单'), findsNothing);
        expect(
          tester.widget<TextField>(messageInput()).controller!.text,
          isEmpty,
        );
        await tester.ensureVisible(find.text('撤销本次记账'));
        await tester.tap(find.text('撤销本次记账'));
        await tester.pumpAndSettle();
        expect(c.transactions, isEmpty);
        expect(find.text('撤销本次记账'), findsNothing);
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets('ledger auto-save notice expires and a new save can be undone', (
    tester,
  ) async {
    final repository = EntryRepository(autoSave: true)
      ..responses.addAll([
        for (final index in [0, 1])
          {
            'transactions': [draft(index)],
            'autoSaveEligible': true,
          },
      ]);
    await app_test.pumpApp(tester, repository);
    final ledger = find.byType(LedgerScreen);
    final controller = tester.widget<LedgerScreen>(ledger).controller;
    final notice = find.text('已自动记下 1 笔');
    final undo = find.descendant(of: ledger, matching: find.text('撤销'));

    await controller.recognizeInput(text: '盖饭 17 元');
    await tester.pump();
    expect(notice, findsOneWidget);
    expect(undo, findsOneWidget);
    await tester.pump(const Duration(seconds: 7));
    await controller.refresh();
    await tester.pump();
    expect(notice, findsOneWidget);
    await tester.pump(const Duration(seconds: 1));
    expect(notice, findsNothing);
    expect(undo, findsNothing);
    expect(controller.transactions.single.merchant, '盖饭');

    await app_test.chooseTab(tester, '统计');
    await app_test.chooseTab(tester, '账本');
    expect(notice, findsNothing);

    await controller.recognizeInput(text: '地铁 17 元');
    await tester.pump();
    expect(notice, findsOneWidget);
    await tester.tap(undo);
    await tester.pumpAndSettle();
    expect(notice, findsNothing);
    expect(find.text('已撤销这次自动记账'), findsOneWidget);
    expect(controller.transactions.single.merchant, '盖饭');
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets(
    'manual entry opens directly while the assistant draft stays intact',
    (tester) async {
      final c = await openAssistant(tester, EntryRepository());
      await tester.enterText(messageInput(), '昨天盖饭 17 元');
      final context = tester.element(find.byType(ChatScreen));
      unawaited(
        Navigator.push(
          context,
          MaterialPageRoute<void>(builder: (_) => EntryScreen(controller: c)),
        ),
      );
      await tester.pumpAndSettle();
      expect(app_test.field('金额'), findsOneWidget);
      expect(find.text('一句话'), findsNothing);
      await tester.enterText(app_test.field('金额'), '28');
      await tester.tap(find.byType(BackButton).last);
      await tester.pumpAndSettle();
      expect(
        tester.widget<TextField>(messageInput()).controller!.text,
        '昨天盖饭 17 元',
      );
      expect(c.assistantDraft, '昨天盖饭 17 元');
      expect(tester.takeException(), isNull);
    },
  );

  for (final theme in ['light', 'dark']) {
    testWidgets(
      '$theme supplements reuse original image and text without duplicate drafts at 320 and 1.5 text',
      (tester) async {
        final repository = EntryRepository(theme: theme)
          ..responses.addAll([
            {
              'transactions': [draft(0)],
              'questions': ['地铁花了多少？'],
              'autoSaveEligible': false,
            },
            {
              'transactions': [draft(0), draft(1, amount: 400)],
              'questions': <String>[],
              'autoSaveEligible': false,
            },
          ]);
        final c = await openAssistant(
          tester,
          repository,
          size: const Size(320, 844),
          textScale: 1.5,
        );
        const original = '昨天晚上 7 点过吃的盖饭，17 块钱，还有地铁。';
        await sendImage(tester, text: original);
        expect(c.pendingRecognition, true);
        await supplement(tester, '地铁 4 块钱');
        final requests = repository.calls
            .where((call) => call.method == 'recognizeInput')
            .toList();
        expect(requests, hasLength(2));
        expect(requests.last.arguments['text'], '$original\n补充：地铁 4 块钱');
        expect(
          requests.last.arguments['operationId'],
          requests.first.arguments['operationId'],
        );
        expect(requests.last.arguments['imageBase64'], imageBase64);
        expect(c.drafts, hasLength(2));
        expect(c.recognitionQuestions, isEmpty);
        await tester.ensureVisible(find.text('保存这 2 笔账单'));
        expect(find.text('查看 1 项补全说明'), findsWidgets);
        expect(tester.takeException(), isNull);
        await tester.tap(find.text('保存这 2 笔账单'));
        await tester.pumpAndSettle();
        expect(c.drafts, isEmpty);
        expect(c.transactions, hasLength(2));
        expect(
          repository.calls.where((call) => call.method == 'saveTransactions'),
          hasLength(1),
        );
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets(
    'selected saving and corrected drafts survive a follow-up without duplicates',
    (tester) async {
      final repository = EntryRepository()
        ..responses.addAll([
          {
            'transactions': [draft(0), draft(1, amount: 400)],
            'questions': ['还有其他消费吗？'],
          },
          {
            'transactions': [draft(0), draft(1, amount: 400)],
            'questions': <String>[],
          },
        ]);
      final c = await openAssistant(tester, repository);
      await sendImage(tester, text: '盖饭 17 元，地铁 4 元。');
      await tester.ensureVisible(find.text('地铁'));
      await tester.tap(find.text('地铁'));
      await tester.pumpAndSettle();
      await tester.enterText(app_test.field('金额'), '5');
      await tester.ensureVisible(find.text('完成核对'));
      await tester.tap(find.text('完成核对'));
      await tester.pumpAndSettle();
      expect(c.drafts.last.amountFen, 500);
      await tester.ensureVisible(find.bySemanticsLabel('选择地铁'));
      await tester.tap(find.bySemanticsLabel('选择地铁'));
      await tester.pump();
      await tester.ensureVisible(find.text('保存这 1 笔账单'));
      await tester.tap(find.text('保存这 1 笔账单'));
      await tester.pumpAndSettle();
      expect(find.text('已保存到账本'), findsOneWidget);
      expect(c.transactions, hasLength(1));
      expect(c.drafts, hasLength(1));
      await supplement(tester, '没有其他消费了');
      final requests = repository.calls
          .where((call) => call.method == 'recognizeInput')
          .toList();
      expect(requests, hasLength(2));
      expect(requests.last.arguments['text'], '盖饭 17 元，地铁 4 元。\n补充：没有其他消费了');
      expect(c.transactions, hasLength(1));
      expect(c.drafts, hasLength(1));
      expect(c.drafts.single.amountFen, 500);
      expect(c.drafts.single.recognitionMetadata['reviewed'], true);
      expect(
        repository.calls.where((call) => call.method == 'saveTransactions'),
        hasLength(1),
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('image-only follow-up keeps the original image and operation', (
    tester,
  ) async {
    final repository = EntryRepository()
      ..responses.addAll([
        {
          'transactions': <Map<String, dynamic>>[],
          'questions': ['这笔转账是用来做什么的？'],
        },
        {
          'transactions': [draft(0)],
          'questions': <String>[],
        },
      ]);
    await openAssistant(tester, repository);
    await sendImage(tester);
    await supplement(tester, '昨天的饭钱');
    final requests = repository.calls
        .where((call) => call.method == 'recognizeInput')
        .toList();
    expect(requests, hasLength(2));
    expect(requests.first.arguments['source'], 'AI_IMAGE');
    expect(requests.last.arguments['source'], 'AI_IMAGE');
    expect(requests.last.arguments['imageBase64'], imageBase64);
    expect(requests.last.arguments['mimeType'], 'image/png');
    expect(requests.last.arguments['text'], contains('昨天的饭钱'));
    expect(
      requests.last.arguments['operationId'],
      requests.first.arguments['operationId'],
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('voice transcription stays editable until explicitly sent', (
    tester,
  ) async {
    final repository = EntryRepository();
    await openAssistant(tester, repository);
    await tester.tap(find.byTooltip('语音输入'));
    await tester.pumpAndSettle();
    expect(tester.widget<TextField>(messageInput()).controller!.text, '午饭二十八元');
    expect(
      repository.calls.where(
        (call) => call.method == 'ask' || call.method == 'recognizeInput',
      ),
      isEmpty,
    );
    await tester.enterText(messageInput(), '午饭 28 元，微信支付');
    await tester.pump();
    await tester.tap(find.byTooltip('发送消息'));
    await tester.pumpAndSettle();
    final call = repository.calls.lastWhere((call) => call.method == 'ask');
    expect(call.arguments['question'], '午饭 28 元，微信支付');
    expect(call.arguments['source'], 'AI_VOICE');
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'backgrounding cancels an unfinished recording and preserves existing input',
    (tester) async {
      final repository = EntryRepository()
        ..speech = Completer<Map<String, dynamic>>();
      await openAssistant(tester, repository);
      await tester.enterText(messageInput(), '原有草稿');
      await chooseAssistantInputOption(tester, '语音输入');
      await tester.pump();
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      await tester.pumpAndSettle();
      expect(
        repository.calls.where((call) => call.method == 'cancelSpeech'),
        hasLength(1),
      );
      expect(tester.widget<TextField>(messageInput()).controller!.text, '原有草稿');
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      expect(tester.takeException(), isNull);
    },
  );
}

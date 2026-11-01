import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/features/entry/entry_screen.dart';

import 'app_widget_test.dart' as app_test;
import 'test_ledger_repository.dart';

class EntryRepository extends TestLedgerRepository {
  EntryRepository({super.theme, bool autoSave = false}) {
    (data['settings'] as Map)['hasDeepSeekKey'] = true;
    (data['settings'] as Map)['activeInputAutoSaveEnabled'] = autoSave;
  }

  final responses = <Map<String, dynamic>>[];

  @override
  Future<Object?> invoke(String method, Map<String, dynamic> arguments) async {
    if (method == 'recognizeInput') {
      calls.add((method: method, arguments: arguments));
      return responses.removeAt(0);
    }
    if (method == 'saveTransactions') {
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
    }
    if (method == 'undoRecognitionSave') {
      calls.add((method: method, arguments: arguments));
      final ids = (arguments['transactions'] as List)
          .map((r) => r['id'])
          .toSet();
      (data['transactions'] as List).removeWhere(
        (row) => ids.contains(row['id']),
      );
      return {'count': ids.length};
    }
    if (method == 'pickRecognitionImage') {
      return {'base64': 'AQID', 'mimeType': 'image/png'};
    }
    return super.invoke(method, arguments);
  }
}

Map<String, dynamic> draft(int index, {int amount = 1700}) => {
  ...fixtureTransaction(
    id: 0,
    amount: amount,
    categoryId: 1,
    merchant: '盖饭',
    source: 'AI_TEXT',
  ),
  'businessKind': 'expense',
  'metadataJson': jsonEncode({
    'operationId': 'entry:$index',
    'timePrecision': 'approximate',
    'assumptions': ['没有说年份，按最近已发生的日期记录'],
  }),
};

Future<void> openEntry(WidgetTester tester) async {
  await tester.tap(find.widgetWithText(FloatingActionButton, '记一笔'));
  await tester.pumpAndSettle();
}

Finder entryScroll() => find
    .descendant(
      of: find.byKey(const ValueKey('ai-entry-list')),
      matching: find.byType(Scrollable),
    )
    .first;

void main() {
  setUpAll(app_test.loadScreenshotFonts);
  for (final theme in ['light', 'dark']) {
    testWidgets(
      '$theme inferred record saves automatically and can be undone',
      (tester) async {
        final repository = EntryRepository(theme: theme, autoSave: true);
        repository.responses.add({
          'transactions': [draft(0)],
          'questions': <String>[],
          'autoSaveEligible': true,
        });
        await app_test.pumpApp(tester, repository);
        await openEntry(tester);
        await tester.enterText(app_test.field('记账描述'), '昨天晚上7点过吃的盖饭17块钱');
        await tester.pump();
        await tester.ensureVisible(find.text('自动记账'));
        await tester.tap(find.text('自动记账'));
        await tester.pumpAndSettle();
        final controller = tester
            .widget<EntryScreen>(find.byType(EntryScreen))
            .controller;
        expect(controller.transactions, hasLength(1));
        expect(controller.drafts, isEmpty);
        expect(find.text('保存这 1 笔账单'), findsNothing);
        expect(find.text('已自动记下 1 笔'), findsOneWidget);
        expect(
          tester.widget<TextField>(app_test.field('记账描述')).controller!.text,
          isEmpty,
        );
        await app_test.saveScreenshot(tester, 'entry-automatic-$theme');
        await tester.ensureVisible(find.text('撤销'));
        await tester.tap(find.text('撤销'));
        await tester.pumpAndSettle();
        expect(controller.transactions, isEmpty);
        expect(controller.canUndoRecognitionSave, false);
        expect(tester.takeException(), isNull);
      },
    );
  }
  testWidgets('new entry starts with AI and preserves both input modes', (
    tester,
  ) async {
    await app_test.pumpApp(tester, EntryRepository());
    await openEntry(tester);
    expect(app_test.field('记账描述'), findsOneWidget);
    await tester.enterText(app_test.field('记账描述'), '昨天盖饭 17 元');
    await tester.tap(find.text('手动'));
    await tester.pumpAndSettle();
    await tester.enterText(app_test.field('金额'), '28');
    await tester.tap(find.text('一句话'));
    await tester.pumpAndSettle();
    expect(
      tester.widget<TextField>(app_test.field('记账描述')).controller!.text,
      '昨天盖饭 17 元',
    );
    await tester.tap(find.text('手动'));
    await tester.pumpAndSettle();
    expect(
      tester.widget<TextField>(app_test.field('金额')).controller!.text,
      '28',
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('missing connection has a direct setup action and manual mode', (
    tester,
  ) async {
    await app_test.pumpApp(tester, TestLedgerRepository());
    await openEntry(tester);
    expect(find.text('连接 AI 服务'), findsOneWidget);
    await tester.tap(find.text('连接 AI 服务'));
    await tester.pumpAndSettle();
    expect(find.text('AI 服务'), findsOneWidget);
    await tester.tap(find.byType(BackButton).last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('手动'));
    await tester.pumpAndSettle();
    expect(app_test.field('金额'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  for (final theme in ['light', 'dark']) {
    testWidgets('$theme supplements reuse original text and replace drafts', (
      tester,
    ) async {
      final repository = EntryRepository(theme: theme);
      repository.responses.addAll([
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
      await app_test.pumpApp(
        tester,
        repository,
        size: const Size(320, 844),
        textScale: 1.5,
      );
      await openEntry(tester);
      const original = '昨天晚上 7 点过吃的盖饭，17 块钱，还有地铁。';
      await tester.enterText(app_test.field('记账描述'), original);
      await tester.pump();
      await tester.ensureVisible(find.text('识别账单'));
      await tester.tap(find.text('识别账单'));
      await tester.pumpAndSettle();
      expect(
        tester.widget<TextField>(app_test.field('记账描述')).controller!.text,
        original,
      );
      await tester.scrollUntilVisible(
        app_test.field('补充一句'),
        220,
        scrollable: entryScroll(),
      );
      await tester.enterText(app_test.field('补充一句'), '地铁 4 块钱');
      await tester.pump();
      await tester.ensureVisible(find.text('更新账单'));
      await tester.tap(find.text('更新账单'));
      await tester.pumpAndSettle();
      final requests = repository.calls
          .where((call) => call.method == 'recognizeInput')
          .toList();
      expect(requests, hasLength(2));
      expect(requests.last.arguments['text'], '$original\n补充：地铁 4 块钱');
      expect(
        requests.last.arguments['operationId'],
        requests.first.arguments['operationId'],
      );
      final controller = tester
          .widget<EntryScreen>(find.byType(EntryScreen))
          .controller;
      expect(controller.drafts, hasLength(2));
      expect(controller.recognitionQuestions, isEmpty);
      await tester.scrollUntilVisible(
        find.text('保存这 2 笔账单'),
        240,
        scrollable: entryScroll(),
      );
      expect(find.text('查看 1 项补全说明'), findsWidgets);
      expect(tester.takeException(), isNull);
      await app_test.saveScreenshot(
        tester,
        'entry-supplement-$theme-320-text-1.5',
      );
      await tester.tap(find.text('保存这 2 笔账单'));
      await tester.pumpAndSettle();
      expect(find.byType(EntryScreen), findsNothing);
      expect(controller.drafts, isEmpty);
      expect(
        repository.calls.where((call) => call.method == 'saveTransactions'),
        hasLength(1),
      );
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets(
    'partial saving keeps the original and resumes without duplicates',
    (tester) async {
      final repository = EntryRepository();
      repository.responses.addAll([
        {
          'transactions': [draft(0)],
          'questions': ['地铁花了多少？'],
        },
        {
          'transactions': [draft(0), draft(1, amount: 400)],
          'questions': <String>[],
        },
      ]);
      await app_test.pumpApp(tester, repository);
      await openEntry(tester);
      const original = '盖饭 17 元，还有地铁。';
      await tester.enterText(app_test.field('记账描述'), original);
      await tester.pump();
      await tester.tap(find.text('识别账单'));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.text('保存这 1 笔账单'),
        240,
        scrollable: entryScroll(),
      );
      await tester.tap(find.text('保存这 1 笔账单'));
      await tester.pumpAndSettle();
      expect(find.byType(EntryScreen), findsOneWidget);
      final controller = tester
          .widget<EntryScreen>(find.byType(EntryScreen))
          .controller;
      expect(controller.transactions, hasLength(1));
      expect(controller.drafts, isEmpty);
      expect(
        tester.widget<TextField>(app_test.field('记账描述')).controller!.text,
        original,
      );
      await tester.scrollUntilVisible(
        find.text('已保存 1 笔，继续补充剩余账单。'),
        -240,
        scrollable: entryScroll(),
      );
      await tester.ensureVisible(app_test.field('补充一句'));
      await tester.enterText(app_test.field('补充一句'), '地铁 4 块钱');
      await tester.pump();
      await tester.ensureVisible(find.text('更新账单'));
      await tester.tap(find.text('更新账单'));
      await tester.pumpAndSettle();
      expect(controller.transactions, hasLength(1));
      expect(controller.drafts, hasLength(1));
      expect(controller.drafts.single.amountFen, 400);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('image follow-up keeps the original image and session', (
    tester,
  ) async {
    final repository = EntryRepository();
    (repository.data['settings'] as Map)['cloudImageParsingEnabled'] = true;
    repository.responses.addAll([
      {
        'transactions': <Map<String, dynamic>>[],
        'questions': ['这笔转账是用来做什么的？'],
      },
      {
        'transactions': [
          {...draft(0), 'source': 'AI_IMAGE'},
        ],
        'questions': <String>[],
      },
    ]);
    await app_test.pumpApp(tester, repository);
    await openEntry(tester);
    await tester.tap(find.text('图片记账'));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(
      app_test.field('补充一句'),
      240,
      scrollable: entryScroll(),
    );
    await tester.enterText(app_test.field('补充一句'), '昨天的饭钱');
    await tester.pump();
    await tester.ensureVisible(find.text('更新账单'));
    await tester.tap(find.text('更新账单'));
    await tester.pumpAndSettle();
    final requests = repository.calls
        .where((call) => call.method == 'recognizeInput')
        .toList();
    expect(requests, hasLength(2));
    expect(requests.first.arguments['source'], 'AI_IMAGE');
    expect(requests.last.arguments['source'], 'AI_IMAGE');
    expect(requests.last.arguments['imageBase64'], 'AQID');
    expect(requests.last.arguments['mimeType'], 'image/png');
    expect(requests.last.arguments['text'], contains('昨天的饭钱'));
    expect(
      requests.last.arguments['operationId'],
      requests.first.arguments['operationId'],
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('leaving an unsaved description asks once and keeps input', (
    tester,
  ) async {
    await app_test.pumpApp(tester, EntryRepository());
    await openEntry(tester);
    await tester.enterText(app_test.field('记账描述'), '盖饭 17 元');
    await tester.pump();
    await tester.tap(find.byType(BackButton).last);
    await tester.pumpAndSettle();
    expect(find.text('退出这次录入？'), findsOneWidget);
    await tester.tap(find.text('继续录入'));
    await tester.pumpAndSettle();
    expect(
      tester.widget<TextField>(app_test.field('记账描述')).controller!.text,
      '盖饭 17 元',
    );
    await tester.tap(find.byType(BackButton).last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('退出录入'));
    await tester.pumpAndSettle();
    expect(find.byType(EntryScreen), findsNothing);
    expect(tester.takeException(), isNull);
  });
}

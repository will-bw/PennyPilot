import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/core/models.dart';
import 'package:pennypilot/features/settings/recognition_settings_screen.dart';
import 'package:pennypilot/features/entry/entry_screen.dart';
import 'package:pennypilot/features/chat/chat_screen.dart';
import 'package:pennypilot/features/ledger/ledger_screen.dart';
import '../ui/test_ledger_repository.dart';

void main() {
  testWidgets(
    'unknown WeChat transfer must choose purpose before atomic confirmation',
    (tester) async {
      final repo = TestLedgerRepository();
      repo.data['transactions'] = [
        {
          ...fixtureTransaction(
            id: 1,
            amount: 1200,
            categoryId: 1,
            merchant: '微信转账',
            status: 'PENDING',
            source: 'ACCESSIBILITY',
          ),
          'businessKind': 'unknown',
        },
      ];
      final controller = LedgerController(repository: repo);
      await controller.init();
      await tester.pumpWidget(
        MaterialApp(home: PendingScreen(controller: controller)),
      );
      await tester.pumpAndSettle();
      final bulk = find.byWidgetPredicate((widget) => widget is OutlinedButton);
      expect(tester.widget<OutlinedButton>(bulk).onPressed, isNull);
      await tester.tap(find.text('选择用途并确认'));
      await tester.pumpAndSettle();
      expect(find.byType(EntryScreen), findsOneWidget);
      expect(
        repo.calls.where((c) => c.method == 'setTransactionStatus'),
        isEmpty,
      );
      await tester.tap(find.text('保存并确认'));
      await tester.pumpAndSettle();
      expect(find.text('请先选择这笔交易的用途，再确认入账'), findsOneWidget);
      expect(repo.calls.where((c) => c.method == 'saveTransaction'), isEmpty);
      await tester.tap(find.byKey(const ValueKey('business-kind-unknown')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('资金转移').last);
      await tester.pumpAndSettle();
      await tester.tap(find.text('保存并确认'));
      await tester.pumpAndSettle();
      final saved = repo.calls
          .singleWhere((c) => c.method == 'saveTransaction')
          .arguments;
      expect(saved['confirmPending'], true);
      expect(saved['businessKind'], 'transfer');
      expect(saved['source'], 'ACCESSIBILITY');
      expect(controller.transactions.single.status, TxStatus.confirmed);
      expect(controller.transactions.single.countsAsExpense, false);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      controller.dispose();
    },
  );
  testWidgets(
    'choosing income purpose synchronizes direction and matching category',
    (tester) async {
      final repo = TestLedgerRepository();
      final controller = LedgerController(repository: repo);
      await controller.init();
      await tester.pumpWidget(
        MaterialApp(
          home: Builder(
            builder: (context) => Scaffold(
              body: TextButton(
                onPressed: () => Navigator.of(context).push(
                  MaterialPageRoute<void>(
                    builder: (_) => EntryScreen(controller: controller),
                  ),
                ),
                child: const Text('打开'),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('打开'));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byWidgetPredicate(
          (w) => w is TextField && w.decoration?.labelText == '金额',
        ),
        '12',
      );
      await tester.tap(find.byKey(const ValueKey('business-kind-expense')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('收入').last);
      await tester.pumpAndSettle();
      expect(
        tester
            .widget<SegmentedButton<TxType>>(
              find.byType(SegmentedButton<TxType>),
            )
            .selected,
        {TxType.income},
      );
      expect(find.text('工资'), findsOneWidget);
      await tester.tap(find.text('保存这笔账'));
      await tester.pumpAndSettle();
      final args = repo.calls
          .singleWhere((c) => c.method == 'saveTransaction')
          .arguments;
      expect(args['businessKind'], 'income');
      expect(args['type'], 'INCOME');
      expect(args['categoryId'], 3);
      expect(args.containsKey('confirmPending'), false);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      controller.dispose();
    },
  );
  test(
    'bulk confirmation skips unknown purpose while keeping known payments',
    () async {
      final repo = TestLedgerRepository();
      repo.data['transactions'] = [
        {
          ...fixtureTransaction(
            id: 1,
            amount: 1200,
            categoryId: 1,
            merchant: '微信转账',
            status: 'PENDING',
          ),
          'businessKind': 'unknown',
        },
        {
          ...fixtureTransaction(
            id: 2,
            amount: 1200,
            categoryId: 1,
            merchant: '店铺消费',
            status: 'PENDING',
          ),
          'businessKind': 'expense',
        },
      ];
      final controller = LedgerController(repository: repo);
      await controller.init();
      await controller.confirmPending();
      final statusCalls = repo.calls.where(
        (c) => c.method == 'setTransactionStatus',
      );
      expect(statusCalls, hasLength(1));
      expect(statusCalls.single.arguments['id'], 2);
      expect(controller.pendingTransactions.single.id, 1);
      controller.dispose();
    },
  );
  testWidgets(
    'recognition privacy controls are separate and native wire keys match',
    (tester) async {
      final repo = TestLedgerRepository();
      final controller = LedgerController(repository: repo);
      await controller.init();
      await tester.pumpWidget(
        MaterialApp(home: RecognitionSettingsScreen(controller: controller)),
      );
      await tester.pumpAndSettle();
      expect(find.text('云端支付截图识别'), findsNothing);
      await tester.tap(find.widgetWithText(SwitchListTile, '云端图片识别'));
      await tester.pumpAndSettle();
      expect(
        repo.calls
            .where((call) => call.method == 'updateSettings')
            .last
            .arguments,
        {'cloudImageParsingEnabled': true},
      );
      expect(controller.settings['activeInputAutoSaveEnabled'], isNot(true));
      await tester.pumpWidget(const SizedBox());
      controller.dispose();
    },
  );
  testWidgets(
    'automatic screenshot upload requires a new consent even when manual image upload is enabled',
    (tester) async {
      final repo = TestLedgerRepository();
      repo.data['settings'] = {
        ...repo.data['settings'] as Map<String, dynamic>,
        'screenVisualCaptureEnabled': true,
        'cloudImageParsingEnabled': true,
        'recognitionVisionProvider': 'qwen',
      };
      (repo.data['capabilities']
              as Map<String, dynamic>)['screenCloudVisionSupported'] =
          true;
      final controller = LedgerController(repository: repo);
      await controller.init();
      await tester.pumpWidget(
        MaterialApp(home: RecognitionSettingsScreen(controller: controller)),
      );
      await tester.pumpAndSettle();

      final automaticVision = find.widgetWithText(SwitchListTile, '云端支付截图识别');
      expect(tester.widget<SwitchListTile>(automaticVision).value, false);
      await tester.tap(automaticVision);
      await tester.pumpAndSettle();
      expect(find.textContaining('收付款人姓名、商户、金额和订单号'), findsOneWidget);
      expect(find.textContaining('你配置的图片模型服务'), findsOneWidget);
      expect(find.textContaining('当前所选服务是 通义千问 / 百炼'), findsOneWidget);
      await tester.tap(find.text('取消'));
      await tester.pumpAndSettle();
      expect(
        repo.calls.where((call) => call.method == 'updateSettings'),
        isEmpty,
      );

      await tester.tap(automaticVision);
      await tester.pumpAndSettle();
      await tester.tap(find.text('同意并开启'));
      await tester.pumpAndSettle();
      expect(
        repo.calls
            .singleWhere((call) => call.method == 'updateSettings')
            .arguments,
        {'screenCloudVisionEnabled': true},
      );
      expect(tester.widget<SwitchListTile>(automaticVision).value, true);

      await tester.tap(automaticVision);
      await tester.pumpAndSettle();
      expect(tester.widget<SwitchListTile>(automaticVision).value, false);
      expect(controller.settings['cloudImageParsingEnabled'], true);
      expect(controller.settings['screenVisualCaptureEnabled'], true);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      controller.dispose();
    },
  );
  testWidgets(
    'assistant offers images and local CSV without overflow at narrow width',
    (tester) async {
      tester.view.physicalSize = const Size(320, 640);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final repo = TestLedgerRepository();
      final controller = LedgerController(repository: repo);
      await controller.init();
      await tester.pumpWidget(
        MaterialApp(
          home: ChatScreen(controller: controller, onSettings: () {}),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('添加附件和更多'));
      await tester.pumpAndSettle();
      expect(find.widgetWithText(ListTile, '添加记账图片'), findsOneWidget);
      expect(repo.calls.where((call) => call.method == 'ask'), isEmpty);
      await tester.tap(find.byTooltip('关闭输入选项'));
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('更多操作'));
      await tester.pumpAndSettle();
      expect(find.text('导入账单'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      controller.dispose();
    },
  );
}

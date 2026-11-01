import 'dart:ui' show PointerDeviceKind;

import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/ui/assistant_content.dart';
import 'package:pennypilot/ui/theme.dart';

Map<String, dynamic> _spec(String type, {int groups = 1}) => {
  'title': '分类支出',
  'chartType': type,
  'metric': 'expense',
  'query': {'start': '2026-10-01', 'end': '2026-10-31', 'group_by': 'category'},
  'data': {
    'expense': groups * 8000,
    'group_by': 'category',
    'groups': [
      for (var i = 0; i < groups; i++)
        {
          'label': '餐饮${i == 0 ? '' : i}',
          'category_id': i + 1,
          'expense': 8000,
        },
    ],
  },
};

Finder _chart(String type) => find.byType(switch (type) {
  'pie' => PieChart,
  'line' => LineChart,
  _ => BarChart,
});

Offset _valuePosition(WidgetTester tester, String type) {
  final rect = tester.getRect(_chart(type));
  if (type == 'line') return Offset(rect.left + 2, rect.center.dy);
  return rect.center + (type == 'pie' ? const Offset(0, -65) : Offset.zero);
}

Future<ScrollController> _open(
  WidgetTester tester,
  String type, {
  int groups = 1,
  ValueChanged<Map<String, dynamic>>? onOpenTransactions,
}) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(390, 700);
  addTearDown(tester.view.resetDevicePixelRatio);
  addTearDown(tester.view.resetPhysicalSize);
  final scroll = ScrollController(initialScrollOffset: 300);
  addTearDown(scroll.dispose);
  await tester.pumpWidget(
    MaterialApp(
      theme: pennyTheme(Brightness.light),
      home: Scaffold(
        body: ListView(
          controller: scroll,
          children: [
            const SizedBox(height: 360, child: Text('上文：之前的收支分析')),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              child: AssistantVisualization(
                spec: _spec(type, groups: groups),
                onOpenTransactions: onOpenTransactions,
              ),
            ),
            const SizedBox(height: 700, child: Text('下文：继续分析')),
          ],
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return scroll;
}

void main() {
  test('touch option copies preserve the embedded-chart pan opt-out', () {
    const bar = BarTouchData(panEnabled: false);
    const line = LineTouchData(panEnabled: false);
    final pie = PieTouchData(panEnabled: false);
    expect(bar.copyWith().panEnabled, isFalse);
    expect(line.copyWith().panEnabled, isFalse);
    expect(pie.panEnabled, isFalse);
    expect(bar, isNot(const BarTouchData()));
    expect(line, isNot(const LineTouchData()));
    expect(pie, isNot(PieTouchData()));
    expect(const BarTouchData().panEnabled, isTrue);
    expect(const LineTouchData().panEnabled, isTrue);
    expect(PieTouchData().panEnabled, isTrue);
  });

  for (final type in ['bar', 'line', 'pie']) {
    testWidgets(
      'a downward drag starting on $type reveals earlier conversation',
      (tester) async {
        final scroll = await _open(tester, type, groups: 3);
        final before = scroll.offset;
        final chart = _chart(type);
        expect(chart.hitTestable(), findsOneWidget);
        await tester.dragFrom(tester.getCenter(chart), const Offset(0, 160));
        await tester.pumpAndSettle();
        expect(scroll.offset, lessThan(before - 80));
        expect(tester.takeException(), isNull);
      },
    );
    testWidgets(
      'a fast downward drag on $type also reveals earlier conversation',
      (tester) async {
        final scroll = await _open(tester, type, groups: 3);
        final before = scroll.offset;
        final gesture = await tester.startGesture(
          tester.getCenter(_chart(type)),
        );
        // A fast first sample can cross both the pan and vertical-drag slop. It
        // must still scroll the conversation rather than only selecting chart data.
        await gesture.moveBy(const Offset(0, 80));
        await gesture.moveBy(const Offset(0, 80));
        await gesture.up();
        await tester.pumpAndSettle();
        expect(scroll.offset, lessThan(before - 60));
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets(
    'horizontal dragging a wide bar chart still reveals more groups',
    (tester) async {
      final vertical = await _open(tester, 'bar', groups: 16);
      final horizontal = tester
          .stateList<ScrollableState>(
            find.descendant(
              of: find.byType(AssistantVisualization),
              matching: find.byType(Scrollable),
            ),
          )
          .singleWhere((state) => state.axisDirection == AxisDirection.right)
          .position;
      final before = vertical.offset;
      final plot = tester.getRect(find.byType(BarChart));
      await tester.dragFrom(
        plot.topLeft + const Offset(140, 80),
        const Offset(-140, 0),
      );
      await tester.pumpAndSettle();
      expect(horizontal.pixels, greaterThan(80));
      expect(vertical.offset, closeTo(before, 1));
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('a fast horizontal drag on the plot reveals more groups', (
    tester,
  ) async {
    final vertical = await _open(tester, 'bar', groups: 16);
    final horizontal = tester
        .stateList<ScrollableState>(
          find.descendant(
            of: find.byType(AssistantVisualization),
            matching: find.byType(Scrollable),
          ),
        )
        .singleWhere((state) => state.axisDirection == AxisDirection.right)
        .position;
    final before = vertical.offset;
    final plot = tester.getRect(find.byType(BarChart));
    final gesture = await tester.startGesture(
      plot.topLeft + const Offset(140, 80),
    );
    await gesture.moveBy(const Offset(-80, 0));
    await gesture.moveBy(const Offset(-80, 0));
    await gesture.up();
    await tester.pumpAndSettle();
    expect(horizontal.pixels, greaterThan(40));
    expect(vertical.offset, closeTo(before, 1));
    expect(tester.takeException(), isNull);
  });

  for (final type in ['bar', 'line', 'pie']) {
    testWidgets(
      '$type remains selectable and its detail still opens the ledger',
      (tester) async {
        Map<String, dynamic>? opened;
        await _open(
          tester,
          type,
          onOpenTransactions: (query) => opened = query,
        );
        await tester.tapAt(_valuePosition(tester, type));
        await tester.pumpAndSettle();
        expect(find.text('餐饮 · 80.00 元'), findsOneWidget);
        expect(opened, isNull);
        final amount = find.text('80.00 元');
        await tester.ensureVisible(amount);
        await tester.tap(amount);
        expect(opened, containsPair('category_id', 1));
        expect(opened, containsPair('start', '2026-10-01'));
        expect(opened, containsPair('status', 'CONFIRMED'));
        expect(tester.takeException(), isNull);
      },
    );

    testWidgets('$type still shows values on long press', (tester) async {
      await _open(tester, type);
      await tester.longPressAt(_valuePosition(tester, type));
      await tester.pumpAndSettle();
      expect(find.text('餐饮 · 80.00 元'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });

    testWidgets('$type still shows values on mouse hover', (tester) async {
      await _open(tester, type);
      final mouse = await tester.createGesture(kind: PointerDeviceKind.mouse);
      await mouse.addPointer(location: Offset.zero);
      await mouse.moveTo(_valuePosition(tester, type));
      await tester.pumpAndSettle();
      expect(find.text('餐饮 · 80.00 元'), findsOneWidget);
      await mouse.removePointer();
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });
  }
}

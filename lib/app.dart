import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'core/ledger_controller.dart';
import 'features/ledger/ledger_screen.dart';
import 'features/reports/reports_screen.dart';
import 'features/chat/chat_screen.dart';
import 'features/settings/settings_screen.dart';
import 'features/settings/capture_settings_screen.dart';
import 'features/entry/entry_screen.dart';
import 'ui/common.dart';
import 'ui/theme.dart';

class PennyPilotApp extends StatefulWidget {
  const PennyPilotApp({super.key, this.controller});
  final LedgerController? controller;
  @override
  State<PennyPilotApp> createState() => _PennyPilotAppState();
}

class _PennyPilotAppState extends State<PennyPilotApp>
    with WidgetsBindingObserver {
  late final LedgerController controller =
      widget.controller ?? LedgerController();
  final navigatorKey = GlobalKey<NavigatorState>();
  int? lastRevision;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    controller.addListener(_onChanged);
    controller.init();
  }

  void _onChanged() {
    if (lastRevision != null && lastRevision != controller.revision) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        navigatorKey.currentState?.pushAndRemoveUntil(
          MaterialPageRoute<void>(
            builder: (_) => _AppHome(controller: controller),
          ),
          (_) => false,
        );
      });
    }
    lastRevision = controller.revision;
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) controller.refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    controller.removeListener(_onChanged);
    if (widget.controller == null) controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: controller,
    builder: (context, _) => MaterialApp(
      navigatorKey: navigatorKey,
      title: 'PennyPilot',
      debugShowCheckedModeBanner: false,
      theme: pennyTheme(Brightness.light),
      darkTheme: pennyTheme(Brightness.dark),
      themeMode: switch (controller.settings['themeMode']) {
        'light' => ThemeMode.light,
        'dark' => ThemeMode.dark,
        _ => ThemeMode.system,
      },
      locale: const Locale('zh', 'CN'),
      supportedLocales: const [Locale('zh', 'CN'), Locale('en')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      home: _AppHome(controller: controller),
    ),
  );
}

class _AppHome extends StatefulWidget {
  const _AppHome({required this.controller});
  final LedgerController controller;
  @override
  State<_AppHome> createState() => _AppHomeState();
}

class _AppHomeState extends State<_AppHome> {
  int selected = 0;
  static const destinations = [
    NavigationDestination(
      icon: Icon(Icons.receipt_long_outlined),
      selectedIcon: Icon(Icons.receipt_long),
      label: '账本',
    ),
    NavigationDestination(
      icon: Icon(Icons.bar_chart_outlined),
      selectedIcon: Icon(Icons.bar_chart),
      label: '统计',
    ),
    NavigationDestination(
      icon: Icon(Icons.chat_bubble_outline),
      selectedIcon: Icon(Icons.chat_bubble),
      label: '问账',
    ),
    NavigationDestination(
      icon: Icon(Icons.tune_outlined),
      selectedIcon: Icon(Icons.tune),
      label: '设置',
    ),
  ];
  @override
  Widget build(BuildContext context) {
    final c = widget.controller;
    final launch = c.launchTarget;
    if (launch != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        c.consumeLaunch();
        final id = launch['transactionId'];
        if (id is int) {
          final matches = c.transactions.where((t) => t.id == id);
          if (matches.isNotEmpty) {
            Navigator.of(context).push(
              MaterialPageRoute<void>(
                builder: (_) =>
                    EntryScreen(controller: c, transaction: matches.first),
              ),
            );
          }
        } else if (launch['route'] == 'coloros_guide') {
          setState(() => selected = 3);
          Navigator.of(context).push(
            MaterialPageRoute<void>(
              builder: (_) => CaptureSettingsScreen(controller: c),
            ),
          );
        } else if (launch['route'] == 'report') {
          setState(() => selected = 1);
        }
      });
    }
    if (c.isLoading && c.transactions.isEmpty && c.categories.isEmpty) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    if (c.error != null && c.categories.isEmpty) {
      return Scaffold(
        body: SafeArea(
          child: Center(
            child: EmptyState(
              icon: Icons.cloud_off_outlined,
              title: '账本暂时无法打开',
              description: c.error.toString(),
              action: FilledButton.icon(
                onPressed: () => c.refresh(),
                icon: const Icon(Icons.refresh),
                label: const Text('重新加载'),
              ),
            ),
          ),
        ),
      );
    }
    if (c.settings['onboardingDone'] == false) return _Welcome(controller: c);
    final screens = [
      LedgerScreen(
        controller: c,
        onBudget: () => showBudgetSettings(context, c),
      ),
      ReportsScreen(controller: c),
      ChatScreen(controller: c, onSettings: () => setState(() => selected = 3)),
      SettingsScreen(controller: c),
    ];
    final wide = MediaQuery.sizeOf(context).width >= 840;
    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: Row(
          children: [
            if (wide)
              NavigationRail(
                selectedIndex: selected,
                onDestinationSelected: (v) => setState(() => selected = v),
                labelType: NavigationRailLabelType.all,
                destinations: destinations
                    .map(
                      (d) => NavigationRailDestination(
                        icon: d.icon,
                        selectedIcon: d.selectedIcon,
                        label: Text(d.label),
                      ),
                    )
                    .toList(),
              ),
            if (wide) const VerticalDivider(width: 1),
            Expanded(
              child: IndexedStack(index: selected, children: screens),
            ),
          ],
        ),
      ),
      bottomNavigationBar: wide
          ? null
          : DecoratedBox(
              decoration: BoxDecoration(
                border: Border(
                  top: BorderSide(
                    color: Theme.of(context).colorScheme.outlineVariant,
                    width: .6,
                  ),
                ),
              ),
              child: NavigationBar(
                selectedIndex: selected,
                onDestinationSelected: (v) => setState(() => selected = v),
                destinations: destinations,
              ),
            ),
    );
  }
}

class _Welcome extends StatelessWidget {
  const _Welcome({required this.controller});
  final LedgerController controller;
  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: ContentWidth(
        child: ListView(
          padding: const EdgeInsets.all(28),
          children: [
            const SizedBox(height: 40),
            Text(
              'PennyPilot',
              style: TextStyle(
                fontSize: 20,
                fontWeight: FontWeight.w600,
                color: Theme.of(context).colorScheme.primary,
              ),
            ),
            const SizedBox(height: 40),
            Text(
              '把日常收支记清楚。',
              style: Theme.of(context).textTheme.headlineMedium,
            ),
            const SizedBox(height: 16),
            const Text('欢迎使用 PennyPilot。先记下一笔，再慢慢看清生活的收支。'),
            const SizedBox(height: 32),
            const ListTile(
              contentPadding: EdgeInsets.zero,
              leading: Icon(Icons.edit_outlined),
              title: Text('随手记，或者说一句'),
              subtitle: Text('手动记账随时可用；配置 AI 后可用文字和语音。'),
            ),
            if (controller.capabilities['platform'] == 'android')
              const ListTile(
                contentPadding: EdgeInsets.zero,
                leading: Icon(Icons.fact_check_outlined),
                title: Text('自动发现，亲自核对'),
                subtitle: Text('支付通知与页面采集可在设置中按需开启。'),
              ),
            const ListTile(
              contentPadding: EdgeInsets.zero,
              leading: Icon(Icons.lock_outline),
              title: Text('账本保存在这台设备'),
              subtitle: Text('定期导出备份，换机时可在 Android 与 iOS 之间转移。'),
            ),
            const SizedBox(height: 40),
            FilledButton(
              onPressed: controller.isBusy
                  ? null
                  : () => runUi(
                      context,
                      () => controller.updateSettings({'onboardingDone': true}),
                    ),
              child: const Text('开始记账'),
            ),
          ],
        ),
      ),
    ),
  );
}

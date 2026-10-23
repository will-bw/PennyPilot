import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/updates/app_update_service.dart';
import 'package:pennypilot/features/settings/update_settings_screen.dart';
import 'package:pennypilot/ui/theme.dart';

final _release = AppUpdateInfo(
  version: '0.5.0',
  versionCode: 5,
  releaseUrl: Uri.parse(
    'https://github.com/will-bw/PennyPilot/releases/tag/v0.5.0',
  ),
  apkUrl: Uri.parse(
    'https://github.com/will-bw/PennyPilot/releases/download/v0.5.0/PennyPilot.apk',
  ),
  sha256: 'a' * 64,
  size: 20 * 1024 * 1024,
  notes: '改善账本记录体验。\n修复更新下载过程中的问题。',
);

class _Updates extends AppUpdateService {
  _Updates({this.android = true});

  final bool android;
  int checks = 0, downloads = 0, installs = 0, cancellations = 0;
  AppUpdateInfo? nextRelease = _release;
  Completer<AppUpdateInfo?>? checkPending;
  Completer<String>? downloadPending;
  AppUpdateException? downloadError, installError;
  UpdateInstallResult installResult = UpdateInstallResult.installerOpened;
  void Function(UpdateDownloadProgress)? progress;

  @override
  bool get canInstallApk => android;

  @override
  Future<InstalledAppVersion> installedVersion() async =>
      const InstalledAppVersion(version: '0.4.0', versionCode: 4);

  @override
  Future<AppUpdateInfo?> checkForUpdate() async {
    checks++;
    return checkPending == null ? nextRelease : await checkPending!.future;
  }

  @override
  Future<String> downloadUpdate(
    AppUpdateInfo info, {
    required void Function(UpdateDownloadProgress) onProgress,
  }) async {
    downloads++;
    progress = onProgress;
    if (downloadError != null) throw downloadError!;
    return downloadPending == null
        ? '/cache/PennyPilot-5.apk'
        : await downloadPending!.future;
  }

  @override
  Future<UpdateInstallResult> installUpdate(
    AppUpdateInfo info,
    String path,
  ) async {
    installs++;
    if (installError != null) throw installError!;
    return installResult;
  }

  @override
  void cancelDownload() {
    cancellations++;
    final pending = downloadPending;
    if (pending != null && !pending.isCompleted) {
      pending.completeError(
        const AppUpdateException('下载已取消。', code: 'CANCELLED'),
      );
    }
  }
}

Future<void> _show(
  WidgetTester tester,
  _Updates service, {
  Size size = const Size(600, 1200),
  double textScale = 1,
  Brightness brightness = Brightness.light,
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  addTearDown(service.dispose);
  await tester.pumpWidget(
    MaterialApp(
      theme: pennyTheme(brightness),
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(
          context,
        ).copyWith(textScaler: TextScaler.linear(textScale)),
        child: child!,
      ),
      home: UpdateSettingsScreen(service: service),
    ),
  );
  await tester.pumpAndSettle();
}

Future<void> _tap(WidgetTester tester, String label) async {
  final target = find.text(label);
  // A newly added, tall release section can extend the lazy list's scroll
  // extent only after it has been laid out at the new position.
  for (var attempt = 0; attempt < 3; attempt++) {
    await tester.ensureVisible(target);
    await tester.pumpAndSettle();
    if (target.hitTestable().evaluate().isNotEmpty) break;
  }
  expect(target.hitTestable(), findsOneWidget);
  await tester.tap(target);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('check, download, and install each require an explicit action', (
    tester,
  ) async {
    final service = _Updates()
      ..installResult = UpdateInstallResult.permissionRequired;
    await _show(tester, service);

    expect(find.text('0.4.0（构建 4）'), findsOneWidget);
    expect(service.checks, 0);
    expect(service.downloads, 0);
    await _tap(tester, '检查更新');
    expect(find.text('发现新版本 0.5.0'), findsOneWidget);
    expect(find.text(_release.notes), findsOneWidget);
    expect(service.downloads, 0);

    await _tap(tester, '下载更新');
    expect(find.text('安装更新'), findsOneWidget);
    expect(service.installs, 0);
    await _tap(tester, '安装更新');
    expect(service.installs, 1);
    expect(find.text('授权后继续安装'), findsOneWidget);

    // Merely returning from settings must not reopen the installer or settings.
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();
    expect(service.installs, 1);
    service.installResult = UpdateInstallResult.installerOpened;
    await _tap(tester, '授权后继续安装');
    expect(service.installs, 2);
    expect(find.textContaining('已打开系统安装页面'), findsOneWidget);
    expect(find.text('再次打开安装'), findsOneWidget);
    expect(find.text('更新成功'), findsNothing);
  });

  testWidgets('a failed check can be retried and cannot run twice at once', (
    tester,
  ) async {
    final pending = Completer<AppUpdateInfo?>();
    final service = _Updates()..checkPending = pending;
    await _show(tester, service);
    await _tap(tester, '检查更新');
    final checkingButton = tester.widget<OutlinedButton>(
      find.widgetWithText(OutlinedButton, '正在检查…'),
    );
    expect(checkingButton.onPressed, isNull);
    expect(service.checks, 1);

    pending.completeError(const AppUpdateException('网络不可用，请重试。'));
    await tester.pumpAndSettle();
    expect(find.text('网络不可用，请重试。'), findsOneWidget);
    service
      ..checkPending = null
      ..nextRelease = null;
    await _tap(tester, '重新检查');
    expect(service.checks, 2);
    expect(find.text('当前已是最新版本。'), findsOneWidget);
    expect(find.text('网络不可用，请重试。'), findsNothing);
    expect(find.text('下载更新'), findsNothing);
  });

  testWidgets('download progress can be cancelled and restarted safely', (
    tester,
  ) async {
    final service = _Updates()..downloadPending = Completer<String>();
    await _show(tester, service);
    await _tap(tester, '检查更新');
    await tester.ensureVisible(find.text('下载更新'));
    await tester.tap(find.text('下载更新'));
    await tester.pump();
    service.progress!(
      const UpdateDownloadProgress(10 * 1024 * 1024, 20 * 1024 * 1024),
    );
    await tester.pump();
    expect(find.text('10.0 MB / 20.0 MB（50%）'), findsOneWidget);
    expect(
      tester
          .widget<LinearProgressIndicator>(find.byType(LinearProgressIndicator))
          .value,
      .5,
    );
    await _tap(tester, '取消下载');
    expect(service.cancellations, 1);
    expect(find.text('下载已取消，可以稍后重新下载。'), findsOneWidget);
    expect(find.text('下载已取消。'), findsNothing);
    expect(service.installs, 0);

    service.downloadPending = null;
    await _tap(tester, '下载更新');
    expect(service.downloads, 2);
    expect(find.text('安装更新'), findsOneWidget);
  });

  testWidgets('download and install failures preserve the appropriate retry', (
    tester,
  ) async {
    final service = _Updates()
      ..downloadError = const AppUpdateException('更新包校验失败，请重新下载。');
    await _show(tester, service);
    await _tap(tester, '检查更新');
    await _tap(tester, '下载更新');
    expect(find.text('更新包校验失败，请重新下载。'), findsOneWidget);
    expect(find.text(_release.notes), findsOneWidget);
    service.downloadError = null;
    await _tap(tester, '重新下载');
    service.installError = const AppUpdateException('无法打开安装页面，请重试。');
    await _tap(tester, '安装更新');
    expect(find.text('无法打开安装页面，请重试。'), findsOneWidget);
    expect(find.text('安装更新'), findsOneWidget);
    service.installError = null;
    await _tap(tester, '安装更新');
    expect(service.downloads, 2);
    expect(service.installs, 2);
    expect(find.text('再次打开安装'), findsOneWidget);
  });

  testWidgets('iOS shows release notes without APK installation actions', (
    tester,
  ) async {
    final service = _Updates(android: false);
    await _show(tester, service);
    expect(find.textContaining('当前仅提供 Android 安装包'), findsOneWidget);
    expect(find.text('查看发布说明'), findsOneWidget);
    await _tap(tester, '检查更新');
    expect(find.text(_release.notes), findsOneWidget);
    expect(find.text('下载更新'), findsNothing);
    expect(find.text('安装更新'), findsNothing);
    expect(service.downloads, 0);
  });

  for (final brightness in Brightness.values) {
    testWidgets(
      'update states fit a small phone with large text in $brightness',
      (tester) async {
        final service = _Updates()
          ..installResult = UpdateInstallResult.permissionRequired;
        await _show(
          tester,
          service,
          size: const Size(320, 640),
          textScale: 2,
          brightness: brightness,
        );
        expect(tester.takeException(), isNull);
        await _tap(tester, '检查更新');
        expect(tester.takeException(), isNull);
        await _tap(tester, '下载更新');
        expect(tester.takeException(), isNull);
        await _tap(tester, '安装更新');
        expect(tester.takeException(), isNull);
        await tester.ensureVisible(find.text('授权后继续安装'));
        await tester.pumpAndSettle();
        expect(find.text('授权后继续安装').hitTestable(), findsOneWidget);
      },
    );
  }

  testWidgets('leaving the update page cancels a pending download', (
    tester,
  ) async {
    final service = _Updates()..downloadPending = Completer<String>();
    await _show(tester, service);
    await _tap(tester, '检查更新');
    await tester.ensureVisible(find.text('下载更新'));
    await tester.tap(find.text('下载更新'));
    await tester.pump();
    await tester.pumpWidget(const MaterialApp(home: SizedBox()));
    await tester.pumpAndSettle();
    expect(service.cancellations, 1);
    expect(tester.takeException(), isNull);
  });
}

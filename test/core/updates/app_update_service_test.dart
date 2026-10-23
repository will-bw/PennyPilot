import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:pennypilot/core/updates/app_update_service.dart';

const base = 'https://github.com/will-bw/PennyPilot/releases';
const apkUrl = '$base/download/v0.4.0/PennyPilot-v0.4.0-android.apk';
const manifestUrl = '$base/download/v0.4.0/update.json';
final bytes = utf8.encode('APK test fixture');
final hash = sha256.convert(bytes).toString();

Map<String, dynamic> manifest() => {
  'schemaVersion': 1,
  'version': '0.4.0',
  'versionCode': 4,
  'apkUrl': apkUrl,
  'sha256': hash,
  'size': bytes.length,
};

Map<String, dynamic> release() => {
  'tag_name': 'v0.4.0',
  'draft': false,
  'prerelease': false,
  'html_url': '$base/tag/v0.4.0',
  'body': '本次更新说明',
  'assets': [
    {'name': 'update.json', 'size': 300, 'browser_download_url': manifestUrl},
    {
      'name': 'PennyPilot-v0.4.0-android.apk',
      'size': bytes.length,
      'browser_download_url': apkUrl,
    },
  ],
};

AppUpdateInfo update({String? digest, int? size, Uri? url}) => AppUpdateInfo(
  version: '0.4.0',
  versionCode: 4,
  releaseUrl: Uri.parse('$base/tag/v0.4.0'),
  apkUrl: url ?? Uri.parse(apkUrl),
  sha256: digest ?? hash,
  size: size ?? bytes.length,
  notes: '本次更新说明',
);

Matcher code(String value) =>
    isA<AppUpdateException>().having((error) => error.code, 'code', value);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late Directory cache;
  final services = <AppUpdateService>[];
  setUp(() async {
    cache = await Directory.systemTemp.createTemp('pennypilot-update-test-');
  });
  tearDown(() async {
    for (final service in services) {
      service.dispose();
    }
    services.clear();
    await cache.delete(recursive: true);
  });

  AppUpdateService service({
    http.Client? client,
    Map<String, dynamic>? releaseData,
    Map<String, dynamic>? manifestData,
    String installed = '0.3.0',
    int installedCode = 3,
    TargetPlatform platform = TargetPlatform.android,
  }) {
    final result = AppUpdateService(
      platform: platform,
      clientFactory: () =>
          client ??
          MockClient((request) async {
            expect(request.followRedirects, isFalse);
            expect(request.headers.containsKey('authorization'), isFalse);
            if (request.url.host == 'api.github.com') {
              return http.Response(
                jsonEncode(releaseData ?? release()),
                200,
                headers: {'content-type': 'application/json; charset=utf-8'},
              );
            }
            expect(request.url.toString(), manifestUrl);
            return http.Response(jsonEncode(manifestData ?? manifest()), 200);
          }),
      versionReader: () async =>
          InstalledAppVersion(version: installed, versionCode: installedCode),
      cacheDirectory: () async => cache,
    );
    services.add(result);
    return result;
  }

  test(
    'checks a stable release without downloading or installing it',
    () async {
      final result = await service().checkForUpdate();
      expect(result!.version, '0.4.0');
      expect(result.versionCode, 4);
      expect(result.notes, '本次更新说明');
      expect(result.sha256, hash);
      expect(await cache.list().isEmpty, isTrue);
    },
  );

  test(
    'compares versions numerically and rejects unstable or malformed versions',
    () {
      expect(
        AppUpdateService.compareVersions('0.10.0', '0.9.9'),
        greaterThan(0),
      );
      expect(
        AppUpdateService.compareVersions('1.0.0', '0.99.0'),
        greaterThan(0),
      );
      expect(AppUpdateService.compareVersions('0.4.0', '0.4.0'), 0);
      for (final value in [
        '0.4.0-beta',
        '04.0.0',
        'v0.4.0',
        '1.0',
        '1.0.0+4',
      ]) {
        expect(
          () => AppUpdateService.compareVersions(value, '0.3.0'),
          throwsA(code('INVALID_RELEASE')),
        );
      }
    },
  );

  test(
    'equal code, newer installed code, or version downgrade do not offer an update',
    () async {
      for (final installedCode in [4, 5]) {
        expect(
          await service(installedCode: installedCode).checkForUpdate(),
          isNull,
        );
      }
      expect(await service(installed: '0.5.0').checkForUpdate(), isNull);
      expect(await service(installed: '0.4.0').checkForUpdate(), isNotNull);
    },
  );

  test(
    'missing release and GitHub rate limits have actionable codes',
    () async {
      for (final item in [
        (404, 'NO_RELEASE'),
        (403, 'RATE_LIMITED'),
        (429, 'RATE_LIMITED'),
      ]) {
        await expectLater(
          service(
            client: MockClient((_) async => http.Response('', item.$1)),
          ).checkForUpdate(),
          throwsA(code(item.$2)),
        );
      }
    },
  );

  test('drafts and prereleases never become update candidates', () async {
    for (final field in ['draft', 'prerelease']) {
      final value = release()..[field] = true;
      await expectLater(
        service(releaseData: value).checkForUpdate(),
        throwsA(code('NO_RELEASE')),
      );
    }
  });

  test(
    'manifest must match the published release and exact APK asset size',
    () async {
      for (final change in <String, Object>{
        'version': '0.5.0',
        'versionCode': 0,
        'schemaVersion': 2,
        'size': bytes.length + 1,
        'sha256': 'invalid',
      }.entries) {
        await expectLater(
          service(
            manifestData: manifest()..[change.key] = change.value,
          ).checkForUpdate(),
          throwsA(code('INVALID_RELEASE')),
        );
      }
      final data = release();
      (data['assets'] as List).removeLast();
      await expectLater(
        service(releaseData: data).checkForUpdate(),
        throwsA(code('INVALID_RELEASE')),
      );
    },
  );

  test(
    'rejects different repository, HTTP, encoded paths, and untrusted redirects',
    () async {
      for (final url in [
        apkUrl.replaceFirst('will-bw', 'other'),
        apkUrl.replaceFirst('https:', 'http:'),
        '$apkUrl?source=other',
        '$base/download/v0.4.0/..%2Fevil.apk',
        apkUrl.replaceFirst('github.com', 'github.com.evil.example'),
      ]) {
        await expectLater(
          service(manifestData: manifest()..['apkUrl'] = url).checkForUpdate(),
          throwsA(code('INVALID_RELEASE')),
        );
      }
      final requests = <Uri>[];
      final client = MockClient((request) async {
        requests.add(request.url);
        return http.Response(
          '',
          302,
          headers: {'location': 'https://evil.example/update.apk'},
        );
      });
      await expectLater(
        service(client: client).downloadUpdate(update(), onProgress: (_) {}),
        throwsA(code('INVALID_RELEASE')),
      );
      expect(requests.length, 1);
    },
  );

  test(
    'follows official CDN redirect, verifies SHA, and stores only final APK',
    () async {
      final requests = <Uri>[];
      final progress = <UpdateDownloadProgress>[];
      final client = MockClient((request) async {
        requests.add(request.url);
        if (request.url.host == 'github.com') {
          return http.Response(
            '',
            302,
            headers: {
              'location':
                  'https://release-assets.githubusercontent.com/assets/test?signature=value',
            },
          );
        }
        return http.Response.bytes(bytes, 200);
      });
      final path = await service(
        client: client,
      ).downloadUpdate(update(), onProgress: progress.add);
      expect(requests.length, 2);
      expect(await File(path).readAsBytes(), bytes);
      expect(path, endsWith('/updates/PennyPilot-4.apk'));
      expect(progress.last.fraction, 1);
      expect(
        (await Directory('${cache.path}/updates').list().toList()).length,
        1,
      );
    },
  );

  test(
    'hash mismatch and declared size mismatch remove partial downloads',
    () async {
      for (final fixture in [
        update(digest: 'a' * 64),
        update(size: bytes.length + 1),
      ]) {
        final sut = service(
          client: MockClient((_) async => http.Response.bytes(bytes, 200)),
        );
        await expectLater(
          sut.downloadUpdate(fixture, onProgress: (_) {}),
          throwsA(isA<AppUpdateException>()),
        );
        expect(await Directory('${cache.path}/updates').list().isEmpty, isTrue);
      }
    },
  );

  test(
    'streaming beyond the declared size is rejected without Content-Length',
    () async {
      final client = MockClient.streaming(
        (_, _) async => http.StreamedResponse(Stream.value([...bytes, 0]), 200),
      );
      await expectLater(
        service(client: client).downloadUpdate(update(), onProgress: (_) {}),
        throwsA(code('INVALID_DOWNLOAD')),
      );
      expect(await Directory('${cache.path}/updates').list().isEmpty, isTrue);
    },
  );

  test(
    'cancelling a download removes its partial APK and never installs it',
    () async {
      late AppUpdateService sut;
      sut = service(
        client: MockClient((_) async => http.Response.bytes(bytes, 200)),
      );
      await expectLater(
        sut.downloadUpdate(
          update(),
          onProgress: (progress) {
            if (progress.receivedBytes > 0) sut.cancelDownload();
          },
        ),
        throwsA(code('CANCELLED')),
      );
      expect(await Directory('${cache.path}/updates').list().isEmpty, isTrue);
    },
  );

  test(
    'oversized APK metadata is rejected before starting any request',
    () async {
      var requests = 0;
      final sut = service(
        client: MockClient((_) async {
          requests++;
          return http.Response('', 200);
        }),
      );
      await expectLater(
        sut.downloadUpdate(
          update(size: AppUpdateService.maximumApkBytes + 1),
          onProgress: (_) {},
        ),
        throwsA(code('INVALID_RELEASE')),
      );
      expect(requests, 0);
    },
  );

  test(
    'separate service instances cannot race shared download cleanup',
    () async {
      final response = Completer<http.Response>();
      final requested = Completer<void>();
      final first = service(
        client: MockClient((_) {
          requested.complete();
          return response.future;
        }),
      );
      final second = service(
        client: MockClient((_) async => http.Response.bytes(bytes, 200)),
      );
      final pending = first.downloadUpdate(update(), onProgress: (_) {});
      final cancellation = expectLater(pending, throwsA(code('CANCELLED')));
      await requested.future;
      first.dispose();
      await expectLater(
        second.downloadUpdate(update(), onProgress: (_) {}),
        throwsA(code('BUSY')),
      );
      response.complete(http.Response.bytes(bytes, 200));
      await cancellation;
      final path = await second.downloadUpdate(update(), onProgress: (_) {});
      expect(await File(path).readAsBytes(), bytes);
    },
  );

  test(
    'iOS checks semantic versions but never downloads or invokes an installer',
    () async {
      final sut = service(platform: TargetPlatform.iOS, installedCode: 999);
      expect(sut.canInstallApk, isFalse);
      expect(
        (await sut.checkForUpdate())!.releaseUrl.toString(),
        '$base/tag/v0.4.0',
      );
      await expectLater(
        sut.downloadUpdate(update(), onProgress: (_) {}),
        throwsA(code('UNSUPPORTED')),
      );
      await expectLater(
        sut.installUpdate(update(), '/tmp/test.apk'),
        throwsA(code('UNSUPPORTED')),
      );
    },
  );

  test(
    'permission flow and signature errors are relayed; no automatic retry',
    () async {
      const channel = MethodChannel('pennypilot/updates');
      final calls = <MethodCall>[];
      var outcome = 'permissionRequired';
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            calls.add(call);
            if (outcome == 'SIGNATURE_MISMATCH') {
              throw PlatformException(code: outcome, message: '请先备份再迁移。');
            }
            return outcome;
          });
      addTearDown(
        () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(channel, null),
      );
      final sut = service();
      expect(
        await sut.installUpdate(
          update(),
          '${cache.path}/updates/PennyPilot-4.apk',
        ),
        UpdateInstallResult.permissionRequired,
      );
      expect(calls.length, 1);
      expect(calls.single.arguments['sha256'], hash);
      outcome = 'installerOpened';
      expect(
        await sut.installUpdate(
          update(),
          '${cache.path}/updates/PennyPilot-4.apk',
        ),
        UpdateInstallResult.installerOpened,
      );
      outcome = 'SIGNATURE_MISMATCH';
      await expectLater(
        sut.installUpdate(update(), '/tmp/test.apk'),
        throwsA(code('SIGNATURE_MISMATCH')),
      );
    },
  );
}

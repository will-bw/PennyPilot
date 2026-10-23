import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:package_info_plus/package_info_plus.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

class AppUpdateException implements Exception {
  const AppUpdateException(this.message, {this.code = 'UPDATE_ERROR'});
  final String message;
  final String code;
  @override
  String toString() => message;
}

class InstalledAppVersion {
  const InstalledAppVersion({required this.version, required this.versionCode});
  final String version;
  final int versionCode;
}

class AppUpdateInfo {
  const AppUpdateInfo({
    required this.version,
    required this.versionCode,
    required this.releaseUrl,
    required this.apkUrl,
    required this.sha256,
    required this.size,
    required this.notes,
  });
  final String version, sha256, notes;
  final int versionCode, size;
  final Uri releaseUrl, apkUrl;
}

class UpdateDownloadProgress {
  const UpdateDownloadProgress(this.receivedBytes, this.totalBytes);
  final int receivedBytes, totalBytes;
  double get fraction => totalBytes == 0 ? 0 : receivedBytes / totalBytes;
}

enum UpdateInstallResult { installerOpened, permissionRequired }

/// A user-driven release client. Creating this service does not check, download,
/// request permissions, or install anything. Each operation needs a UI action.
class AppUpdateService {
  AppUpdateService({
    TargetPlatform? platform,
    http.Client Function()? clientFactory,
    Future<InstalledAppVersion> Function()? versionReader,
    Future<Directory> Function()? cacheDirectory,
    MethodChannel? installChannel,
  }) : _platform = platform ?? defaultTargetPlatform,
       _clientFactory = clientFactory ?? http.Client.new,
       _versionReader = versionReader ?? _readVersion,
       _cacheDirectory = cacheDirectory ?? getTemporaryDirectory,
       _installChannel =
           installChannel ?? const MethodChannel('pennypilot/updates');

  static const maximumApkBytes = 200 * 1024 * 1024;
  // Separate settings-page instances share one private download directory.
  // Keep ownership through cancellation cleanup before another instance starts.
  static bool _downloadInProgress = false;
  static const _maximumJsonBytes = 512 * 1024;
  static final releasesUrl = Uri.parse(
    'https://github.com/will-bw/PennyPilot/releases',
  );
  static final _latestUrl = Uri.parse(
    'https://api.github.com/repos/will-bw/PennyPilot/releases/latest',
  );
  final TargetPlatform _platform;
  final http.Client Function() _clientFactory;
  final Future<InstalledAppVersion> Function() _versionReader;
  final Future<Directory> Function() _cacheDirectory;
  final MethodChannel _installChannel;
  http.Client? _downloadClient;
  bool _cancelled = false, _disposed = false, _checking = false;
  final Set<http.Client> _clients = {};

  bool get canInstallApk => !kIsWeb && _platform == TargetPlatform.android;

  static Future<InstalledAppVersion> _readVersion() async {
    final info = await PackageInfo.fromPlatform();
    return InstalledAppVersion(
      version: info.version,
      versionCode: int.tryParse(info.buildNumber) ?? 0,
    );
  }

  Future<InstalledAppVersion> installedVersion() => _versionReader();

  Future<AppUpdateInfo?> checkForUpdate() async {
    _ensureActive();
    if (_checking) {
      throw const AppUpdateException('正在检查更新，请稍候。', code: 'BUSY');
    }
    _checking = true;
    final client = _clientFactory();
    _clients.add(client);
    try {
      return await _check(client).timeout(const Duration(seconds: 45));
    } on AppUpdateException {
      rethrow;
    } catch (_) {
      throw const AppUpdateException('无法检查更新，请检查网络连接后重试。');
    } finally {
      _checking = false;
      _clients.remove(client);
      client.close();
    }
  }

  Future<AppUpdateInfo?> _check(http.Client client) async {
    final installed = await installedVersion();
    final release = await _json(client, _latestUrl, api: true);
    if (release['draft'] != false || release['prerelease'] != false) {
      throw const AppUpdateException('暂时没有可用的正式版本。', code: 'NO_RELEASE');
    }
    final tag = release['tag_name'];
    if (tag is! String || !RegExp(r'^v\d+\.\d+\.\d+$').hasMatch(tag)) {
      _invalidRelease();
    }
    final version = tag.substring(1);
    _versionParts(version);
    final releaseUrl = Uri.parse('$releasesUrl/tag/$tag');
    if (release['html_url'] != releaseUrl.toString()) _invalidRelease();
    final assets = release['assets'];
    if (assets is! List) _invalidRelease();
    final manifests = assets.whereType<Map<String, dynamic>>().where(
      (asset) => asset['name'] == 'update.json',
    );
    if (manifests.length != 1) _invalidRelease();
    final manifestAsset = manifests.single;
    final manifestUrl = _assetUri(manifestAsset['browser_download_url'], tag);
    if (manifestUrl.pathSegments.last != 'update.json') _invalidRelease();
    final manifestSize = manifestAsset['size'];
    if (manifestSize is! int ||
        manifestSize <= 0 ||
        manifestSize > _maximumJsonBytes) {
      _invalidRelease();
    }
    final manifest = await _json(client, manifestUrl);
    if (manifest['schemaVersion'] != 1 || manifest['version'] != version) {
      _invalidRelease();
    }
    final code = manifest['versionCode'];
    final hash = manifest['sha256'];
    final size = manifest['size'];
    if (code is! int ||
        code <= 0 ||
        code > 2100000000 ||
        hash is! String ||
        !RegExp(r'^[0-9a-f]{64}$').hasMatch(hash) ||
        size is! int ||
        size <= 0 ||
        size > maximumApkBytes) {
      _invalidRelease();
    }
    final apkUrl = _assetUri(manifest['apkUrl'], tag);
    if (!apkUrl.pathSegments.last.endsWith('.apk')) _invalidRelease();
    final apks = assets.whereType<Map<String, dynamic>>().where(
      (asset) => asset['browser_download_url'] == apkUrl.toString(),
    );
    if (apks.length != 1 || apks.single['size'] != size) _invalidRelease();
    final comparison = compareVersions(version, installed.version);
    if (comparison < 0 ||
        (canInstallApk ? code <= installed.versionCode : comparison <= 0)) {
      return null;
    }
    final notes = release['body'];
    _ensureActive();
    return AppUpdateInfo(
      version: version,
      versionCode: code,
      releaseUrl: releaseUrl,
      apkUrl: apkUrl,
      sha256: hash,
      size: size,
      notes: notes is String ? notes : '',
    );
  }

  Future<String> downloadUpdate(
    AppUpdateInfo info, {
    required void Function(UpdateDownloadProgress) onProgress,
  }) async {
    _ensureActive();
    if (!canInstallApk) {
      throw const AppUpdateException(
        '此平台请在 GitHub 版本页面查看发布说明。',
        code: 'UNSUPPORTED',
      );
    }
    if (_downloadClient != null || _downloadInProgress) {
      throw const AppUpdateException('已有更新正在下载。', code: 'BUSY');
    }
    _validateInfo(info);
    _cancelled = false;
    final client = _clientFactory();
    _downloadInProgress = true;
    _downloadClient = client;
    _clients.add(client);
    File? partial;
    IOSink? sink;
    var timedOut = false;
    final deadline = Timer(const Duration(minutes: 10), () {
      timedOut = true;
      client.close();
    });
    try {
      final directory = Directory(
        p.join((await _cacheDirectory()).path, 'updates'),
      );
      await directory.create(recursive: true);
      _checkCancelled();
      // Only this service's own previous downloads are removed. No user files or
      // ledger data are stored here, and an in-flight download owns this folder.
      await for (final file in directory.list()) {
        if (file is File &&
            RegExp(
              r'^PennyPilot-\d+\.apk(?:\.part)?$',
            ).hasMatch(p.basename(file.path))) {
          await file.delete();
        }
      }
      partial = File(
        p.join(directory.path, 'PennyPilot-${info.versionCode}.apk.part'),
      );
      final response = await _get(client, info.apkUrl);
      _checkCancelled();
      _checkStatus(response.statusCode);
      final expected = response.contentLength;
      if (expected != null && expected != info.size) _invalidDownload();
      sink = partial.openWrite();
      // Observe asynchronous file-write errors before the final flush.
      unawaited(sink.done.catchError((Object _) {}));
      var received = 0;
      onProgress(UpdateDownloadProgress(0, info.size));
      await for (final chunk in response.stream.timeout(
        const Duration(seconds: 45),
      )) {
        _checkCancelled();
        if (timedOut) throw TimeoutException('Update download timed out');
        received += chunk.length;
        if (received > info.size || received > maximumApkBytes) {
          _invalidDownload();
        }
        sink.add(chunk);
        onProgress(UpdateDownloadProgress(received, info.size));
      }
      await sink.flush();
      await sink.close();
      sink = null;
      _checkCancelled();
      if (received != info.size) _invalidDownload();
      final digest = await sha256.bind(partial.openRead()).first;
      _checkCancelled();
      if (timedOut) throw TimeoutException('Update download timed out');
      if (digest.toString() != info.sha256) {
        throw const AppUpdateException(
          '更新包完整性校验失败，请重新下载。',
          code: 'HASH_MISMATCH',
        );
      }
      final complete = await partial.rename(
        p.join(directory.path, 'PennyPilot-${info.versionCode}.apk'),
      );
      partial = complete;
      _checkCancelled();
      partial = null;
      return complete.path;
    } on AppUpdateException {
      rethrow;
    } catch (_) {
      _checkCancelled();
      throw const AppUpdateException('下载更新失败，请检查网络或存储空间后重试。');
    } finally {
      deadline.cancel();
      client.close();
      _clients.remove(client);
      _downloadClient = null;
      try {
        await sink?.close();
      } catch (_) {
        /* Preserve the original error. */
      }
      if (partial != null) {
        try {
          if (await partial.exists()) await partial.delete();
        } catch (_) {
          /* Cache can be reclaimed by the OS. */
        }
      }
      _downloadInProgress = false;
    }
  }

  /// Opens either the Android installer or its per-app installation permission.
  /// After granting permission, the UI must ask for another explicit click.
  /// Returning installerOpened does not mean the user completed installation.
  Future<UpdateInstallResult> installUpdate(
    AppUpdateInfo info,
    String path,
  ) async {
    _ensureActive();
    if (!canInstallApk) {
      throw const AppUpdateException(
        '此平台不支持安装 Android 更新包。',
        code: 'UNSUPPORTED',
      );
    }
    _validateInfo(info);
    try {
      final result = await _installChannel.invokeMethod<String>('install', {
        'path': path,
        'sha256': info.sha256,
        'version': info.version,
        'versionCode': info.versionCode,
        'size': info.size,
      });
      return switch (result) {
        'installerOpened' => UpdateInstallResult.installerOpened,
        'permissionRequired' => UpdateInstallResult.permissionRequired,
        _ => throw const AppUpdateException('无法打开系统安装程序，请重试。'),
      };
    } on PlatformException catch (error) {
      throw AppUpdateException(
        error.message ?? '无法安装更新，请重试。',
        code: error.code,
      );
    } on MissingPluginException {
      throw const AppUpdateException('当前应用构建不支持安装更新，请从版本页面下载安装包。');
    }
  }

  void cancelDownload() {
    if (_downloadClient == null) return;
    _cancelled = true;
    _downloadClient?.close();
  }

  void dispose() {
    _disposed = true;
    cancelDownload();
    for (final client in _clients) {
      client.close();
    }
    _clients.clear();
  }

  void _checkCancelled() {
    if (_cancelled || _disposed) {
      throw const AppUpdateException('已取消下载。', code: 'CANCELLED');
    }
  }

  void _ensureActive() {
    if (_disposed) {
      throw const AppUpdateException('更新服务已关闭。', code: 'CANCELLED');
    }
  }

  static void _validateInfo(AppUpdateInfo info) {
    _versionParts(info.version);
    _assetUri(info.apkUrl.toString(), 'v${info.version}');
    if (!info.apkUrl.path.endsWith('.apk') ||
        info.size <= 0 ||
        info.size > maximumApkBytes ||
        info.versionCode <= 0 ||
        info.versionCode > 2100000000 ||
        !RegExp(r'^[0-9a-f]{64}$').hasMatch(info.sha256)) {
      _invalidRelease();
    }
  }

  static int compareVersions(String left, String right) {
    final a = _versionParts(left), b = _versionParts(right);
    for (var i = 0; i < 3; i++) {
      final comparison = a[i].compareTo(b[i]);
      if (comparison != 0) return comparison;
    }
    return 0;
  }

  static List<int> _versionParts(String value) {
    if (!RegExp(
          r'^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$',
        ).hasMatch(value) ||
        value.length > 32) {
      _invalidRelease();
    }
    return value.split('.').map(int.parse).toList();
  }

  static Uri _assetUri(Object? value, String tag) {
    if (value is! String) _invalidRelease();
    final uri = Uri.tryParse(value);
    if (uri == null ||
        uri.scheme != 'https' ||
        uri.host != 'github.com' ||
        uri.port != 443 ||
        uri.userInfo.isNotEmpty ||
        uri.hasQuery ||
        uri.hasFragment ||
        uri.pathSegments.length != 6 ||
        uri.pathSegments.take(5).join('/') !=
            'will-bw/PennyPilot/releases/download/$tag' ||
        !RegExp(
          r'^[A-Za-z0-9][A-Za-z0-9._-]*$',
        ).hasMatch(uri.pathSegments.last)) {
      _invalidRelease();
    }
    return uri;
  }

  Future<Map<String, dynamic>> _json(
    http.Client client,
    Uri url, {
    bool api = false,
  }) async {
    final response = await _get(client, url, api: api);
    _checkStatus(response.statusCode);
    if ((response.contentLength ?? 0) > _maximumJsonBytes) _invalidRelease();
    final bytes = BytesBuilder(copy: false);
    await for (final chunk in response.stream.timeout(
      const Duration(seconds: 20),
    )) {
      if (bytes.length + chunk.length > _maximumJsonBytes) _invalidRelease();
      bytes.add(chunk);
    }
    final value = jsonDecode(utf8.decode(bytes.takeBytes()));
    if (value is! Map<String, dynamic>) _invalidRelease();
    return value;
  }

  Future<http.StreamedResponse> _get(
    http.Client client,
    Uri initial, {
    bool api = false,
  }) async {
    var url = initial;
    for (var redirects = 0; redirects <= 4; redirects++) {
      final request = http.Request('GET', url)..followRedirects = false;
      request.headers['User-Agent'] = 'PennyPilot-Updater';
      if (api) request.headers['Accept'] = 'application/vnd.github+json';
      final response = await client
          .send(request)
          .timeout(const Duration(seconds: 20));
      if (![301, 302, 303, 307, 308].contains(response.statusCode)) {
        return response;
      }
      final location = response.headers['location'];
      await response.stream.listen((_) {}).cancel();
      if (api || location == null || redirects == 4) _invalidRelease();
      final next = url.resolve(location);
      // GitHub release URLs redirect to GitHub's own signed asset CDN. Never
      // follow arbitrary hosts or HTTP; no account token is sent to any host.
      if (next.scheme != 'https' ||
          next.port != 443 ||
          next.userInfo.isNotEmpty ||
          next.hasFragment ||
          (next != initial &&
              !{
                'release-assets.githubusercontent.com',
                'objects.githubusercontent.com',
              }.contains(next.host))) {
        _invalidRelease();
      }
      url = next;
    }
    _invalidRelease();
  }

  static void _checkStatus(int status) {
    if (status == 404) {
      throw const AppUpdateException('暂时没有可用的正式版本或安装包。', code: 'NO_RELEASE');
    }
    if (status == 403 || status == 429) {
      throw const AppUpdateException(
        'GitHub 请求受限，请稍后再试。',
        code: 'RATE_LIMITED',
      );
    }
    if (status != 200) throw AppUpdateException('更新服务器返回 HTTP $status，请稍后重试。');
  }

  static Never _invalidRelease() => throw const AppUpdateException(
    '版本信息校验失败，请稍后重试或查看 GitHub 版本页面。',
    code: 'INVALID_RELEASE',
  );
  static Never _invalidDownload() => throw const AppUpdateException(
    '更新包大小不符合发布信息，请重新下载。',
    code: 'INVALID_DOWNLOAD',
  );
}

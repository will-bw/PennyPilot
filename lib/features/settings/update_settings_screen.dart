import 'dart:async';

import 'package:flutter/material.dart';
import 'package:pennypilot/core/updates/app_update_service.dart';
import 'package:url_launcher/url_launcher.dart';

import 'settings_widgets.dart';

enum _UpdatePhase {
  idle,
  checking,
  current,
  available,
  downloading,
  downloaded,
}

/// Updates are checked and downloaded only in response to an explicit action.
class UpdateSettingsScreen extends StatefulWidget {
  const UpdateSettingsScreen({super.key, this.service});

  final AppUpdateService? service;

  @override
  State<UpdateSettingsScreen> createState() => _UpdateSettingsScreenState();
}

class _UpdateSettingsScreenState extends State<UpdateSettingsScreen> {
  late final AppUpdateService _service;
  InstalledAppVersion? _installed;
  AppUpdateInfo? _update;
  UpdateDownloadProgress? _progress;
  _UpdatePhase _phase = _UpdatePhase.idle;
  String? _downloadedPath, _error, _downloadNotice;
  bool _loadingVersion = true, _installing = false;
  bool _cancelling = false;
  bool _permissionRequired = false, _installerOpened = false;
  int _downloadAttempt = 0;

  bool get _busy =>
      _loadingVersion ||
      _phase == _UpdatePhase.checking ||
      _phase == _UpdatePhase.downloading ||
      _installing;

  @override
  void initState() {
    super.initState();
    _service = widget.service ?? AppUpdateService();
    unawaited(_loadVersion());
  }

  @override
  void dispose() {
    _downloadAttempt++;
    if (_phase == _UpdatePhase.downloading) _service.cancelDownload();
    if (widget.service == null) _service.dispose();
    super.dispose();
  }

  String _message(Object error) =>
      error is AppUpdateException ? error.message : '操作未完成，请检查网络后重试。';

  Future<void> _loadVersion() async {
    try {
      final installed = await _service.installedVersion();
      if (mounted) setState(() => _installed = installed);
    } catch (error) {
      if (mounted) setState(() => _error = _message(error));
    } finally {
      if (mounted) setState(() => _loadingVersion = false);
    }
  }

  Future<void> _check() async {
    if (_busy) return;
    setState(() {
      _phase = _UpdatePhase.checking;
      _error = null;
      _update = null;
      _downloadedPath = null;
      _downloadNotice = null;
      _permissionRequired = false;
      _installerOpened = false;
    });
    try {
      final installed = await _service.installedVersion();
      final update = await _service.checkForUpdate();
      if (!mounted) return;
      setState(() {
        _installed = installed;
        _update = update;
        _phase = update == null ? _UpdatePhase.current : _UpdatePhase.available;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = _message(error);
        _phase = _UpdatePhase.idle;
      });
    }
  }

  Future<void> _download() async {
    final update = _update;
    if (_busy || update == null || !_service.canInstallApk) return;
    final attempt = ++_downloadAttempt;
    setState(() {
      _phase = _UpdatePhase.downloading;
      _cancelling = false;
      _progress = null;
      _error = null;
      _downloadNotice = null;
      _permissionRequired = false;
      _installerOpened = false;
    });
    try {
      final path = await _service.downloadUpdate(
        update,
        onProgress: (progress) {
          if (mounted && attempt == _downloadAttempt) {
            setState(() => _progress = progress);
          }
        },
      );
      if (!mounted || attempt != _downloadAttempt) return;
      setState(() {
        _downloadedPath = path;
        _phase = _UpdatePhase.downloaded;
      });
    } catch (error) {
      if (!mounted || attempt != _downloadAttempt) return;
      setState(() {
        if (error is AppUpdateException && error.code == 'CANCELLED') {
          _downloadNotice = '下载已取消，可以稍后重新下载。';
        } else {
          _error = _message(error);
        }
        _cancelling = false;
        _phase = _UpdatePhase.available;
      });
    }
  }

  void _cancelDownload() {
    if (_cancelling) return;
    setState(() => _cancelling = true);
    _service.cancelDownload();
  }

  Future<void> _install() async {
    final update = _update;
    final path = _downloadedPath;
    if (_busy || update == null || path == null) return;
    setState(() {
      _installing = true;
      _error = null;
    });
    try {
      final result = await _service.installUpdate(update, path);
      if (!mounted) return;
      setState(() {
        _permissionRequired = result == UpdateInstallResult.permissionRequired;
        _installerOpened = result == UpdateInstallResult.installerOpened;
      });
    } catch (error) {
      if (mounted) setState(() => _error = _message(error));
    } finally {
      if (mounted) setState(() => _installing = false);
    }
  }

  Future<void> _openReleaseNotes() async {
    final url = _update?.releaseUrl ?? AppUpdateService.releasesUrl;
    try {
      if (await launchUrl(url, mode: LaunchMode.externalApplication)) return;
    } catch (_) {
      // Keep the checked version and downloaded package available for retry.
    }
    if (mounted) settingsFeedback(context, '无法打开发布说明，请稍后重试。');
  }

  String get _status {
    if (_loadingVersion) return '正在读取版本信息…';
    return switch (_phase) {
      _UpdatePhase.checking => '正在检查 GitHub 上的最新正式版本…',
      _UpdatePhase.current => '当前已是最新版本。',
      _UpdatePhase.idle => '点击检查，查看是否有新版本。',
      _UpdatePhase.available => '已发现新版本，可先阅读更新说明。',
      _UpdatePhase.downloading => '正在下载更新。',
      _UpdatePhase.downloaded => '安装包已准备好。',
    };
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final update = _update;
    final installed = _installed;
    return Scaffold(
      appBar: AppBar(title: const Text('应用更新')),
      body: SafeArea(
        top: false,
        child: SettingsList(
          children: [
            SettingsSection(
              title: '当前版本',
              children: [
                SettingsRow(
                  title: 'PennyPilot',
                  subtitle: installed == null
                      ? (_loadingVersion ? '正在读取…' : '暂时无法读取版本')
                      : '${installed.version}（构建 ${installed.versionCode}）',
                  icon: Icons.account_balance_wallet_outlined,
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Semantics(
                        liveRegion: true,
                        child: Text(
                          _status,
                          style: theme.textTheme.bodyMedium?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                      ),
                      const SizedBox(height: 16),
                      OutlinedButton(
                        onPressed: _busy ? null : _check,
                        child: Text(
                          _phase == _UpdatePhase.checking
                              ? '正在检查…'
                              : _error != null && update == null
                              ? '重新检查'
                              : '检查更新',
                        ),
                      ),
                    ],
                  ),
                ),
                const Divider(),
                SettingsRow(
                  title: '查看发布说明',
                  subtitle: '在浏览器中打开 GitHub',
                  icon: Icons.open_in_new,
                  onTap: _openReleaseNotes,
                ),
              ],
            ),
            if (_error != null) SettingsNotice(_error!, error: true),
            if (!_service.canInstallApk)
              const SettingsNotice(
                '当前仅提供 Android 安装包。你可以查看发布说明；iOS 暂不支持从此页面安装更新。',
              ),
            if (update != null)
              SettingsSection(
                title: '发现新版本 ${update.version}',
                description: _service.canInstallApk
                    ? 'Android 安装包 · ${_bytes(update.size)}'
                    : null,
                children: [
                  Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Text('更新说明', style: theme.textTheme.titleMedium),
                        const SizedBox(height: 8),
                        SelectableText(
                          update.notes.trim().isEmpty
                              ? '此版本未提供更新说明。'
                              : update.notes.trim(),
                          style: theme.textTheme.bodyMedium,
                        ),
                        if (_service.canInstallApk) ...[
                          const SizedBox(height: 24),
                          ..._updateActions(),
                        ],
                      ],
                    ),
                  ),
                ],
              ),
            if (_service.canInstallApk)
              const SettingsNotice('更新安装由 Android 系统确认。建议更新前在“数据与应用”中导出账本备份。'),
          ],
        ),
      ),
    );
  }

  List<Widget> _updateActions() {
    if (_phase == _UpdatePhase.downloading) {
      final progress = _progress;
      final percent = progress == null
          ? null
          : (progress.fraction * 100).clamp(0, 100).round();
      return [
        Semantics(
          label: '更新下载进度',
          value: percent == null ? '准备下载' : '$percent%',
          child: LinearProgressIndicator(value: progress?.fraction),
        ),
        const SizedBox(height: 12),
        Text(
          progress == null
              ? '准备下载…'
              : '${_bytes(progress.receivedBytes)} / ${_bytes(progress.totalBytes)}（$percent%）',
        ),
        const SizedBox(height: 8),
        TextButton(
          onPressed: _cancelling ? null : _cancelDownload,
          child: Text(_cancelling ? '正在取消…' : '取消下载'),
        ),
      ];
    }
    if (_phase == _UpdatePhase.downloaded) {
      return [
        Semantics(
          liveRegion: true,
          child: Text(
            _permissionRequired
                ? '请在系统中允许 PennyPilot 安装应用，返回后点击“授权后继续安装”。'
                : _installerOpened
                ? '已打开系统安装页面，请按系统提示完成安装；取消后可以再次安装。'
                : '安装包已下载并校验，点击安装后由系统确认。',
          ),
        ),
        const SizedBox(height: 16),
        FilledButton(
          onPressed: _busy ? null : _install,
          child: Text(
            _installing
                ? '正在打开…'
                : _permissionRequired
                ? '授权后继续安装'
                : _installerOpened
                ? '再次打开安装'
                : '安装更新',
          ),
        ),
      ];
    }
    return [
      if (_downloadNotice != null) ...[
        Text(_downloadNotice!),
        const SizedBox(height: 16),
      ],
      FilledButton(
        onPressed: _busy ? null : _download,
        child: Text(_error == null ? '下载更新' : '重新下载'),
      ),
    ];
  }
}

String _bytes(int value) {
  if (value < 1024) return '$value B';
  if (value < 1024 * 1024) return '${(value / 1024).toStringAsFixed(1)} KB';
  return '${(value / (1024 * 1024)).toStringAsFixed(1)} MB';
}

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Settings use ordinary rows and dividers, so related controls remain easy to scan.
class SettingsSection extends StatelessWidget {
  const SettingsSection({
    super.key,
    required this.title,
    required this.children,
    this.description,
  });

  final String title;
  final String? description;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 28, 24, 8),
          child: Semantics(
            header: true,
            child: Text(
              title,
              style: theme.textTheme.titleSmall?.copyWith(
                color: theme.colorScheme.primary,
              ),
            ),
          ),
        ),
        if (description != null)
          Padding(
            padding: const EdgeInsets.fromLTRB(24, 0, 24, 12),
            child: Text(
              description!,
              style: theme.textTheme.bodyMedium?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ),
        ...children,
      ],
    );
  }
}

class SettingsRow extends StatelessWidget {
  const SettingsRow({
    super.key,
    required this.title,
    this.subtitle,
    this.icon,
    this.onTap,
    this.trailing,
    this.enabled = true,
  });

  final String title;
  final String? subtitle;
  final IconData? icon;
  final VoidCallback? onTap;
  final Widget? trailing;
  final bool enabled;

  @override
  Widget build(BuildContext context) => ListTile(
    contentPadding: const EdgeInsets.symmetric(horizontal: 24, vertical: 4),
    minVerticalPadding: 12,
    enabled: enabled,
    leading: icon == null
        ? null
        : Icon(icon, color: Theme.of(context).colorScheme.onSurfaceVariant),
    title: Text(title),
    subtitle: subtitle == null
        ? null
        : Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text(subtitle!),
          ),
    trailing:
        trailing ?? (onTap == null ? null : const Icon(Icons.chevron_right)),
    onTap: enabled ? onTap : null,
  );
}

class SettingsNotice extends StatelessWidget {
  const SettingsNotice(this.message, {super.key, this.error = false});
  final String message;
  final bool error;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Semantics(
      liveRegion: true,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(
              error ? Icons.error_outline : Icons.info_outline,
              size: 20,
              color: error ? colors.error : colors.onSurfaceVariant,
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                message,
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  color: error ? colors.error : colors.onSurfaceVariant,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

String settingsError(Object error) {
  if (error is PlatformException) return error.message ?? '操作未完成，请重试。';
  if (error is FormatException) return error.message;
  return error.toString().replaceFirst(
    RegExp(r'^(Exception|StateError|Bad state):\s*'),
    '',
  );
}

void settingsFeedback(BuildContext context, String message) {
  ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(content: Text(message)));
}

Future<bool> confirmSettingsAction(
  BuildContext context, {
  required String title,
  required String message,
  required String confirmLabel,
  bool destructive = false,
}) async =>
    await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title),
        content: SingleChildScrollView(child: Text(message)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            style: destructive
                ? TextButton.styleFrom(
                    foregroundColor: Theme.of(context).colorScheme.error,
                  )
                : null,
            child: Text(confirmLabel),
          ),
        ],
      ),
    ) ??
    false;

class SettingsBusy extends StatelessWidget {
  const SettingsBusy({super.key});
  @override
  Widget build(BuildContext context) => const SizedBox(
    width: 20,
    height: 20,
    child: CircularProgressIndicator(strokeWidth: 2),
  );
}

String settingsTimestamp(int milliseconds) {
  if (milliseconds <= 0) return '暂无记录';
  final time = DateTime.fromMillisecondsSinceEpoch(milliseconds);
  final minute = time.minute.toString().padLeft(2, '0');
  return '${time.year}年${time.month}月${time.day}日 ${time.hour}:$minute';
}

/// Decimal parsing is integer based: no rounding or floating point currency drift.
int? parseBudgetCents(String value) {
  final input = value.trim();
  if (!RegExp(r'^\d{1,9}(\.\d{1,2})?$').hasMatch(input)) return null;
  final parts = input.split('.');
  final cents =
      int.parse(parts.first) * 100 +
      (parts.length == 1 ? 0 : int.parse(parts.last.padRight(2, '0')));
  return cents;
}

bool isSecureSettingsEndpoint(String value) {
  final uri = Uri.tryParse(value.trim());
  return uri != null &&
      uri.scheme == 'https' &&
      uri.host.isNotEmpty &&
      uri.userInfo.isEmpty &&
      !uri.hasQuery &&
      !uri.hasFragment;
}

import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:path/path.dart' as path;
import 'package:path_provider/path_provider.dart';

import '../ledger_repository.dart';

class AssistantAttachmentStore {
  AssistantAttachmentStore({this.directory});
  final Directory? directory;

  Future<Map<String, dynamic>> read(Map<String, dynamic> attachment) async {
    final file = File(attachment['path'] as String);
    if (!await file.exists()) throw const LedgerException('凭证图片已不存在，请重新选择图片。');
    final bytes = await file.readAsBytes();
    if (bytes.length > 2 * 1024 * 1024) {
      throw const LedgerException('图片不能超过 2 MB。');
    }
    return {
      'imageBase64': base64Encode(bytes),
      'mimeType': attachment['mimeType'],
    };
  }

  Future<Map<String, dynamic>> save(Map<String, dynamic> attachment) async {
    final mime = attachment['mimeType'];
    final extension = const {
      'image/png': 'png',
      'image/jpeg': 'jpg',
      'image/webp': 'webp',
    }[mime];
    final encoded = attachment['imageBase64'] ?? attachment['base64'];
    if (extension == null || encoded is! String) {
      throw const LedgerException('请选择 PNG、JPEG 或 WebP 图片。');
    }
    final bytes = base64Decode(encoded);
    if (bytes.length > 2 * 1024 * 1024) {
      throw const LedgerException('图片不能超过 2 MB。');
    }
    final root =
        directory ??
        Directory(
          path.join(
            (await getApplicationSupportDirectory()).path,
            'assistant-attachments',
          ),
        );
    await root.create(recursive: true);
    final file = File(
      path.join(root.path, '${sha256.convert(bytes)}.$extension'),
    );
    if (!await file.exists()) await file.writeAsBytes(bytes, flush: true);
    return {
      'type': 'attachment',
      'mimeType': mime,
      'name': attachment['name'] as String? ?? '凭证图片',
      'path': file.path,
    };
  }
}

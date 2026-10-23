import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Secret values are never part of the ledger snapshot or its backup.
abstract interface class SharedCredentials {
  Future<String?> get(String key);
  Future<void> set(String key, String value);
  Future<void> delete(String key);
}

class SecureSharedCredentials implements SharedCredentials {
  SecureSharedCredentials({FlutterSecureStorage? storage})
    : _storage = storage ?? const FlutterSecureStorage();
  final FlutterSecureStorage _storage;
  String _key(String key) => 'pennypilot.ai.$key';
  @override
  Future<String?> get(String key) => _storage.read(key: _key(key));
  @override
  Future<void> set(String key, String value) =>
      _storage.write(key: _key(key), value: value);
  @override
  Future<void> delete(String key) => _storage.delete(key: _key(key));
}

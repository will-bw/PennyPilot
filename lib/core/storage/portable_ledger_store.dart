import 'dart:async';
import 'dart:convert';

import 'package:path/path.dart' as path;
import 'package:path_provider/path_provider.dart';
import 'package:sqflite/sqflite.dart' as sqlite;

import '../ledger_repository.dart';
import '../models.dart';

part 'portable_validation.dart';
part 'portable_presets.dart';

/// Local SQLite storage for the shared Dart application. This is a separate
/// database from Android Room; it never opens or migrates `pennypilot.db`.
///
/// Rows retain the portable backup wire format, including Android capture
/// evidence. The latter is archival data and never enables capture on iOS.
class PortableLedgerStore implements LedgerRepository {
  PortableLedgerStore({
    sqlite.DatabaseFactory? databaseFactory,
    String? databasePath,
    this.platform = 'ios',
    DateTime Function()? clock,
  }) : _factory = databaseFactory ?? sqlite.databaseFactory,
       _databasePath = databasePath,
       _clock = clock ?? DateTime.now;

  final sqlite.DatabaseFactory _factory;
  final String? _databasePath;
  final DateTime Function() _clock;
  final String platform;
  final _events = StreamController<LedgerEvent>.broadcast();
  Future<sqlite.Database>? _opening;
  bool _closed = false;

  @override
  Stream<LedgerEvent> get events => _events.stream;

  Future<sqlite.Database> _database() {
    if (_closed) {
      throw const LedgerException('账本已关闭。', code: 'STORE_CLOSED');
    }
    return _opening ??= _open();
  }

  Future<sqlite.Database> _open() async {
    var location = _databasePath;
    if (location == null) {
      final directory = await getApplicationSupportDirectory();
      await directory.create(recursive: true);
      location = path.join(directory.path, 'pennypilot-portable.sqlite');
    }
    return _factory.openDatabase(
      location,
      options: sqlite.OpenDatabaseOptions(
        version: 1,
        onCreate: (database, _) async {
          // The canonical JSON row preserves all wire fields. Typed validation,
          // relationship checks and writes run inside the same SQLite transaction.
          for (final table in _tables) {
            await database.execute(
              'CREATE TABLE $table (id INTEGER PRIMARY KEY AUTOINCREMENT, '
              'payload TEXT NOT NULL)',
            );
          }
          await database.execute(
            'CREATE TABLE capture_events (eventKey TEXT PRIMARY KEY NOT NULL, '
            'payload TEXT NOT NULL)',
          );
          await database.execute(
            'CREATE TABLE metadata (key TEXT PRIMARY KEY NOT NULL, '
            'value TEXT NOT NULL)',
          );
          await _putMetadata(database, 'revision', 0);
          await _putMetadata(database, 'settings', _defaultSettings);
          for (final category in _presetCategories) {
            await _writeRow(database, 'categories', category);
          }
          for (final rule in _presetRules) {
            await _writeRow(database, 'rules', rule);
          }
        },
      ),
    );
  }

  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    final opening = _opening;
    if (opening != null) await (await opening).close();
    await _events.close();
  }

  @override
  Future<LedgerSnapshot> snapshot() async =>
      LedgerSnapshot.fromMap(_map(await invoke('snapshot', const {}), '账本'));

  @override
  Future<Object?> invoke(String method, Map<String, dynamic> arguments) async {
    final database = await _database();
    switch (method) {
      case 'snapshot':
        return database.transaction(_snapshot);
      case 'exportBackupData':
        return database.transaction(_export);
      case 'updateSettings':
        final revision = await database.transaction((transaction) async {
          final changes = _validateSettings(arguments);
          final current = await _settings(transaction);
          await _putMetadata(transaction, 'settings', {...current, ...changes});
          return _revision(transaction);
        });
        _emit('changed', revision);
        return null;
      case 'saveTransaction':
      case 'saveTransactions':
      case 'deleteTransaction':
      case 'setTransactionStatus':
      case 'saveCategory':
      case 'deleteCategory':
      case 'saveRule':
      case 'deleteRule':
      case 'resetRules':
      case 'saveGeneratedReport':
      case 'restoreBackupData':
        final outcome = await database.transaction((transaction) async {
          final revision = await _revision(transaction);
          if (_int(arguments, 'revision', min: 0) != revision) {
            throw const LedgerException(
              '账本已恢复，请重新打开此页面。',
              code: 'STALE_LEDGER',
            );
          }
          final result = await _mutate(transaction, method, arguments);
          return (result: result, revision: await _revision(transaction));
        });
        _emit(
          method == 'restoreBackupData' ? 'restored' : 'changed',
          outcome.revision,
        );
        return outcome.result;
      default:
        throw const LedgerException(
          '当前平台尚未提供此功能。',
          code: 'PLATFORM_UNAVAILABLE',
        );
    }
  }

  void _emit(String type, int revision) {
    if (!_events.isClosed) _events.add(LedgerEvent(type, revision));
  }

  Future<Map<String, dynamic>> _snapshot(
    sqlite.DatabaseExecutor database,
  ) async {
    final transactions = await _rows(database, 'transactions');
    transactions.sort(
      (a, b) => (b['occurredAt'] as int).compareTo(a['occurredAt'] as int),
    );
    for (final row in transactions) {
      row.remove('rawPayload');
      row.remove('captureEventKey');
    }
    final categories = await _rows(database, 'categories');
    categories.sort(
      (a, b) => (a['sortOrder'] as int).compareTo(b['sortOrder'] as int),
    );
    final rules = await _rows(database, 'rules');
    rules.sort(
      (a, b) => (a['priority'] as int).compareTo(b['priority'] as int),
    );
    final reports = await _rows(database, 'reports');
    reports.sort(
      (a, b) => (b['yearMonth'] as String).compareTo(a['yearMonth'] as String),
    );
    return {
      'revision': await _revision(database),
      'transactions': transactions,
      'categories': categories,
      'rules': rules,
      'reports': reports,
      'settings': {
        ...await _settings(database),
        'hasDeepSeekKey': false,
        'hasQwenKey': false,
        'needsReconfiguration': false,
      },
      'capabilities': {
        'platform': platform,
        'notificationListenerGranted': false,
        'accessibilityGranted': false,
        'remindersEnabled': false,
        'microphoneGranted': false,
        'batteryExempt': false,
        'speechAvailable': false,
        'notificationServiceConnected': false,
        'accessibilityServiceConnected': false,
        'todayCaptureCount': 0,
        'duplicateCount': 0,
        'lastCaptureAt': null,
        'lastError': null,
      },
      'auth': {
        'accounts': <Object?>[],
        'activeAccountId': null,
        'isAuthorizing': false,
        'loginUrl': null,
        'error': null,
        'showWelcome': false,
      },
      'launch': null,
    };
  }

  Future<Object?> _mutate(
    sqlite.Transaction database,
    String method,
    Map<String, dynamic> arguments,
  ) async {
    switch (method) {
      case 'saveTransaction':
        return {'id': await _saveTransaction(database, arguments)};
      case 'saveTransactions':
        final rows = _maps(arguments['transactions'], '账目', max: 100);
        _require(rows.isNotEmpty, '请选择 1～100 笔待保存账目。');
        final ids = <int>[];
        for (final row in rows) {
          ids.add(await _saveTransaction(database, row));
        }
        return {'ids': ids};
      case 'deleteTransaction':
        final id = _int(arguments, 'id', min: 1);
        await _existing(database, 'transactions', id, '账目');
        for (final evidence in await _captureRows(database)) {
          if (evidence['transactionId'] == id) {
            await database.delete(
              'capture_events',
              where: 'eventKey = ?',
              whereArgs: [evidence['eventKey']],
            );
          }
        }
        await database.delete('transactions', where: 'id = ?', whereArgs: [id]);
        return null;
      case 'setTransactionStatus':
        final id = _int(arguments, 'id', min: 1);
        final row = await _existing(database, 'transactions', id, '账目');
        final status = _choice(arguments, 'status', const {
          'CONFIRMED',
          'IGNORED',
        });
        _require(row['status'] == 'PENDING', '账目已处理，请刷新列表。');
        await _writeRow(database, 'transactions', {...row, 'status': status});
        return null;
      case 'saveCategory':
        return {'id': await _saveCategory(database, arguments)};
      case 'deleteCategory':
        await _deleteCategory(database, _int(arguments, 'id', min: 1));
        return null;
      case 'saveRule':
        final id = _int(arguments, 'id', min: 0, fallback: 0);
        if (id > 0) await _existing(database, 'rules', id, '规则');
        final row = _validateRule({...arguments, 'id': id}, restoring: false);
        return {'id': await _writeRow(database, 'rules', row)};
      case 'deleteRule':
        final id = _int(arguments, 'id', min: 1);
        await _existing(database, 'rules', id, '规则');
        await database.delete('rules', where: 'id = ?', whereArgs: [id]);
        return null;
      case 'resetRules':
        await database.delete('rules');
        for (final rule in _presetRules) {
          await _writeRow(database, 'rules', {...rule, 'id': 0});
        }
        return null;
      case 'saveGeneratedReport':
        final month = _month(arguments['yearMonth']);
        final now = _clock();
        final currentMonth =
            '${now.year.toString().padLeft(4, '0')}-${now.month.toString().padLeft(2, '0')}';
        _require(month.compareTo(currentMonth) < 0, '只能生成已结束月份的报告。');
        final matching = (await _rows(
          database,
          'reports',
        )).where((row) => row['yearMonth'] == month).firstOrNull;
        final row = _validateReport({
          ...arguments,
          'id': matching?['id'] ?? 0,
          'generatedAt':
              arguments['generatedAt'] ?? _clock().millisecondsSinceEpoch,
        }, restoring: false);
        return {'id': await _writeRow(database, 'reports', row)};
      case 'restoreBackupData':
        await _restore(database, _map(arguments['data'], '备份'));
        return null;
      default:
        throw StateError('Unsupported mutation: $method');
    }
  }

  Future<int> _saveTransaction(
    sqlite.DatabaseExecutor database,
    Map<String, dynamic> arguments,
  ) async {
    final id = _int(arguments, 'id', min: 0, fallback: 0);
    final old = id == 0
        ? null
        : await _existing(database, 'transactions', id, '账目');
    final amount = _int(arguments, 'amount', min: 1, max: maxTransactionFen);
    final type = _choice(arguments, 'type', _types);
    final categoryId = _int(arguments, 'categoryId', min: 1);
    final category = await _existing(database, 'categories', categoryId, '分类');
    _require(
      category['type'] == type &&
          (category['enabled'] == true || old?['categoryId'] == categoryId),
      '请选择相同收支类型的可用分类。',
    );
    final row = {
      ...?old,
      'id': id,
      'amount': type == 'INCOME' ? -amount : amount,
      'type': type,
      'categoryId': categoryId,
      'occurredAt': _int(arguments, 'occurredAt', min: 0, max: _maxTimestamp),
      'note': _optionalString(arguments, 'note', max: 2000, blankToNull: true),
      'merchant': _optionalString(
        arguments,
        'merchant',
        max: 200,
        blankToNull: true,
      ),
      'accountTag': _optionalString(
        arguments,
        'accountTag',
        max: 100,
        blankToNull: true,
      ),
      'createdAt': old?['createdAt'] ?? _clock().millisecondsSinceEpoch,
      'source':
          old?['source'] ??
          _choice(arguments, 'source', const {
            'MANUAL',
            'AI_TEXT',
            'AI_VOICE',
          }, fallback: 'MANUAL'),
      'status': old?['status'] ?? 'CONFIRMED',
      'duplicateOf': old?['duplicateOf'],
      'rawPayload': old?['rawPayload'],
      'captureEventKey': old?['captureEventKey'],
    };
    return _writeRow(database, 'transactions', row);
  }

  Future<int> _saveCategory(
    sqlite.DatabaseExecutor database,
    Map<String, dynamic> arguments,
  ) async {
    final id = _int(arguments, 'id', min: 0, fallback: 0);
    final old = id == 0
        ? null
        : await _existing(database, 'categories', id, '分类');
    final categories = await _rows(database, 'categories');
    final type = _choice(arguments, 'type', _types);
    _require(old == null || old['type'] == type, '已有分类的收支类型不可更改。');
    final name = _text(arguments, 'name', max: 20).trim();
    _require(
      !categories.any(
        (row) => row['id'] != id && row['type'] == type && row['name'] == name,
      ),
      '该类型下已有同名分类。',
    );
    final maxOrder = categories.fold<int>(
      -1,
      (value, row) =>
          (row['sortOrder'] as int) > value ? row['sortOrder'] as int : value,
    );
    final maxColor = categories.fold<int>(
      -1,
      (value, row) =>
          (row['colorIndex'] as int) > value ? row['colorIndex'] as int : value,
    );
    return _writeRow(database, 'categories', {
      'id': id,
      'name': name,
      'icon': _text(arguments, 'icon', max: 32).trim(),
      'type': type,
      'enabled': _bool(
        arguments,
        'enabled',
        fallback: old?['enabled'] as bool? ?? true,
      ),
      'sortOrder': _int(
        arguments,
        'sortOrder',
        min: 0,
        max: _maxInt32,
        fallback: old?['sortOrder'] as int? ?? maxOrder + 1,
      ),
      'colorIndex': old?['colorIndex'] ?? (maxColor + 1) % 7,
      'isPreset': old?['isPreset'] ?? false,
    });
  }

  Future<void> _deleteCategory(sqlite.DatabaseExecutor database, int id) async {
    final old = await _existing(database, 'categories', id, '分类');
    _require(old['isPreset'] != true, '预设分类不能删除，可在列表中停用。');
    final categories = await _rows(database, 'categories');
    final fallback = categories
        .where(
          (row) =>
              row['id'] != id &&
              row['name'] == '其他' &&
              row['type'] == old['type'],
        )
        .firstOrNull;
    int fallbackId;
    if (fallback == null) {
      fallbackId = await _saveCategory(database, {
        'name': '其他',
        'icon': old['type'] == 'EXPENSE' ? '📦' : '💰',
        'type': old['type'],
      });
      final created = await _existing(database, 'categories', fallbackId, '分类');
      await _writeRow(database, 'categories', {...created, 'isPreset': true});
    } else {
      fallbackId = fallback['id'] as int;
      await _writeRow(database, 'categories', {...fallback, 'enabled': true});
    }
    for (final row in await _rows(database, 'transactions')) {
      if (row['categoryId'] == id) {
        await _writeRow(database, 'transactions', {
          ...row,
          'categoryId': fallbackId,
        });
      }
    }
    await database.delete('categories', where: 'id = ?', whereArgs: [id]);
  }

  Future<Map<String, dynamic>> _export(
    sqlite.DatabaseExecutor database,
  ) async => {
    'format': 'pennypilot',
    'version': 1,
    for (final table in _tables) table: await _rows(database, table),
    'captureEvents': await _captureRows(database),
  };

  Future<void> _restore(
    sqlite.DatabaseExecutor database,
    Map<String, dynamic> data,
  ) async {
    final backup = _validateBackup(data);
    // Validation is complete before the first delete. SQLite also rolls back
    // every replacement and the revision together if any write fails.
    for (final table in _tables) {
      await database.delete(table);
    }
    await database.delete('capture_events');
    for (final table in _tables) {
      for (final row in backup[table]!) {
        await _writeRow(database, table, row);
      }
    }
    for (final row in backup['captureEvents']!) {
      await database.insert('capture_events', {
        'eventKey': row['eventKey'],
        'payload': jsonEncode(row),
      });
    }
    await _putMetadata(database, 'revision', await _revision(database) + 1);
  }
}

const _tables = ['transactions', 'categories', 'rules', 'reports'];

Future<List<Map<String, dynamic>>> _rows(
  sqlite.DatabaseExecutor database,
  String table,
) async => (await database.query(
  table,
  orderBy: 'id',
)).map((row) => _map(jsonDecode(row['payload'] as String), table)).toList();

Future<List<Map<String, dynamic>>> _captureRows(
  sqlite.DatabaseExecutor database,
) async => (await database.query(
  'capture_events',
  orderBy: 'eventKey',
)).map((row) => _map(jsonDecode(row['payload'] as String), '采集证据')).toList();

Future<Map<String, dynamic>> _existing(
  sqlite.DatabaseExecutor database,
  String table,
  int id,
  String label,
) async {
  final rows = await database.query(table, where: 'id = ?', whereArgs: [id]);
  _require(rows.isNotEmpty, '$label已不存在，请刷新。');
  return _map(jsonDecode(rows.single['payload'] as String), label);
}

Future<int> _writeRow(
  sqlite.DatabaseExecutor database,
  String table,
  Map<String, dynamic> row,
) async {
  final id = row['id'] as int;
  if (id > 0) {
    await database.insert(table, {
      'id': id,
      'payload': jsonEncode(row),
    }, conflictAlgorithm: sqlite.ConflictAlgorithm.replace);
    return id;
  }
  final created = await database.insert(table, {'payload': '{}'});
  _require(created <= _maxSafeInteger, '记录编号超出可移植备份支持的范围。');
  await database.update(
    table,
    {
      'payload': jsonEncode({...row, 'id': created}),
    },
    where: 'id = ?',
    whereArgs: [created],
  );
  return created;
}

Future<void> _putMetadata(
  sqlite.DatabaseExecutor database,
  String key,
  Object value,
) => database.insert('metadata', {
  'key': key,
  'value': jsonEncode(value),
}, conflictAlgorithm: sqlite.ConflictAlgorithm.replace);

Future<Object?> _metadata(sqlite.DatabaseExecutor database, String key) async =>
    jsonDecode(
      (await database.query(
            'metadata',
            where: 'key = ?',
            whereArgs: [key],
          )).single['value']
          as String,
    );

Future<int> _revision(sqlite.DatabaseExecutor database) async =>
    await _metadata(database, 'revision') as int;

Future<Map<String, dynamic>> _settings(
  sqlite.DatabaseExecutor database,
) async => {
  ..._defaultSettings,
  ..._map(await _metadata(database, 'settings'), '设置'),
};

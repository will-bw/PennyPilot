import 'dart:convert';

import 'package:path/path.dart' as path;
import 'package:path_provider/path_provider.dart';
import 'package:sqflite/sqflite.dart' as sqlite;

import 'agent_models.dart';

abstract class AgentStore {
  Future<Map<String, dynamic>?> read(String key);
  Future<void> write(String key, Map<String, dynamic> value);
  Future<void> remove(String key);
  Future<List<Map<String, dynamic>>> scan(String prefix);
  Future<void> close();
}

/// Separate from the ledger/Room schema. One committed row is a checkpoint;
/// transcripts are retained even when their model projection is compacted.
class SqliteAgentStore implements AgentStore {
  SqliteAgentStore({sqlite.DatabaseFactory? factory, String? databasePath})
    : _factory = factory,
      _path = databasePath;
  final sqlite.DatabaseFactory? _factory;
  final String? _path;
  Future<sqlite.Database>? _opening;

  Future<sqlite.Database> _database() => _opening ??= () async {
    final location =
        _path ??
        path.join(
          (await getApplicationSupportDirectory()).path,
          'pennypilot-agent.sqlite',
        );
    return (_factory ?? sqlite.databaseFactory).openDatabase(
      location,
      options: sqlite.OpenDatabaseOptions(
        version: 1,
        onCreate: (db, _) => db.execute(
          'CREATE TABLE agent_state (key TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)',
        ),
      ),
    );
  }();

  @override
  Future<Map<String, dynamic>?> read(String key) async {
    final rows = await (await _database()).query(
      'agent_state',
      where: 'key = ?',
      whereArgs: [key],
      limit: 1,
    );
    return rows.isEmpty
        ? null
        : agentMap(jsonDecode(rows.single['payload'] as String));
  }

  @override
  Future<void> write(String key, Map<String, dynamic> value) async =>
      (await _database())
          .insert('agent_state', {
            'key': key,
            'payload': jsonEncode(value),
          }, conflictAlgorithm: sqlite.ConflictAlgorithm.replace)
          .then((_) {});
  @override
  Future<void> remove(String key) async => (await _database())
      .delete('agent_state', where: 'key = ?', whereArgs: [key])
      .then((_) {});
  @override
  Future<List<Map<String, dynamic>>> scan(String prefix) async {
    // Prefix is application-owned; substr avoids SQL LIKE wildcard semantics.
    final rows = await (await _database()).query(
      'agent_state',
      where: 'substr(key, 1, ?) = ?',
      whereArgs: [prefix.length, prefix],
    );
    return rows
        .map((r) => agentMap(jsonDecode(r['payload'] as String)))
        .toList();
  }

  @override
  Future<void> close() async {
    if (_opening != null) await (await _opening!).close();
  }
}

/// Explicit injection for tests; production always uses durable SQLite.
class MemoryAgentStore implements AgentStore {
  final _rows = <String, Map<String, dynamic>>{};
  Map<String, dynamic> _copy(Map<String, dynamic> value) =>
      agentMap(jsonDecode(jsonEncode(value)));
  @override
  Future<Map<String, dynamic>?> read(String key) async =>
      _rows[key] == null ? null : _copy(_rows[key]!);
  @override
  Future<void> write(String key, Map<String, dynamic> value) async {
    _rows[key] = _copy(value);
  }

  @override
  Future<void> remove(String key) async {
    _rows.remove(key);
  }

  @override
  Future<List<Map<String, dynamic>>> scan(String prefix) async => _rows.entries
      .where((e) => e.key.startsWith(prefix))
      .map((e) => _copy(e.value))
      .toList();
  @override
  Future<void> close() async {}
}

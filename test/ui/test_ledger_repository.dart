import 'dart:async';

import 'package:pennypilot/core/ledger_repository.dart';
import 'package:pennypilot/core/models.dart';

/// Synthetic records exist only in tests. Production never imports this fixture.
class TestLedgerRepository implements LedgerRepository {
  TestLedgerRepository({bool populated = false, String theme = 'light'})
    : data = fixtureSnapshot(populated: populated, theme: theme);

  Map<String, dynamic> data;
  final updates = StreamController<LedgerEvent>.broadcast(sync: true);
  final calls = <({String method, Map<String, dynamic> arguments})>[];
  LedgerException? readError;

  @override
  Stream<LedgerEvent> get events => updates.stream;

  @override
  Future<LedgerSnapshot> snapshot() async {
    if (readError != null) throw readError!;
    return LedgerSnapshot.fromMap(data);
  }

  @override
  Future<Object?> invoke(String method, Map<String, dynamic> arguments) async {
    calls.add((method: method, arguments: arguments));
    switch (method) {
      case 'saveTransaction':
        final rows = List<Map<String, dynamic>>.from(
          data['transactions'] as List,
        );
        final id = arguments['id'] == 0 ? 99 : arguments['id'];
        final row = <String, dynamic>{
          ...arguments,
          'id': id,
          'createdAt': DateTime(2026, 10, 23).millisecondsSinceEpoch,
          'amount': arguments['type'] == 'INCOME'
              ? -(arguments['amount'] as int)
              : arguments['amount'],
          'status': 'CONFIRMED',
          'duplicateOf': null,
        };
        rows.removeWhere((item) => item['id'] == id);
        rows.add(row);
        data = {...data, 'transactions': rows};
        return {'id': id};
      case 'updateSettings':
        data = {
          ...data,
          'settings': {
            ...data['settings'] as Map<String, dynamic>,
            ...arguments,
          },
        };
        return null;
      case 'setTransactionStatus':
        data = {
          ...data,
          'transactions': (data['transactions'] as List)
              .map(
                (item) => item['id'] == arguments['id']
                    ? {
                        ...item as Map<String, dynamic>,
                        'status': arguments['status'],
                      }
                    : item,
              )
              .toList(),
        };
        return null;
      case 'ask':
        return {'answer': '测试账本已确认支出 40 元。', 'isError': false};
      default:
        throw LedgerException('测试未实现 $method', code: 'TEST_UNSUPPORTED');
    }
  }
}

Map<String, dynamic> fixtureSnapshot({
  bool populated = false,
  String theme = 'light',
}) => {
  'revision': 0,
  'transactions': populated
      ? [
          fixtureTransaction(
            id: 1,
            amount: 2800,
            categoryId: 1,
            merchant: '测试午餐店',
            note: '午饭',
          ),
          fixtureTransaction(
            id: 2,
            amount: 1200,
            categoryId: 2,
            merchant: '测试地铁',
            note: '通勤',
          ),
          fixtureTransaction(
            id: 3,
            amount: -800000,
            categoryId: 3,
            type: 'INCOME',
            merchant: '测试工资',
          ),
          fixtureTransaction(
            id: 4,
            amount: 5500,
            categoryId: 1,
            merchant: '测试待确认商户',
            status: 'PENDING',
            source: 'NOTIFICATION',
          ),
          fixtureTransaction(
            id: 5,
            amount: 5500,
            categoryId: 1,
            merchant: '测试重复通知',
            status: 'IGNORED',
            source: 'NOTIFICATION',
          ),
        ]
      : <Map<String, dynamic>>[],
  'categories': [
    {
      'id': 1,
      'name': '餐饮',
      'icon': '🍜',
      'colorIndex': 0,
      'type': 'EXPENSE',
      'sortOrder': 0,
      'enabled': true,
      'isPreset': true,
    },
    {
      'id': 2,
      'name': '交通',
      'icon': '🚌',
      'colorIndex': 1,
      'type': 'EXPENSE',
      'sortOrder': 1,
      'enabled': true,
      'isPreset': true,
    },
    {
      'id': 3,
      'name': '工资',
      'icon': '💼',
      'colorIndex': 2,
      'type': 'INCOME',
      'sortOrder': 0,
      'enabled': true,
      'isPreset': true,
    },
  ],
  'rules': <Map<String, dynamic>>[],
  'reports': <Map<String, dynamic>>[],
  'settings': {
    'onboardingDone': true,
    'notificationCaptureEnabled': true,
    'screenCaptureEnabled': false,
    'notificationCloudParsingEnabled': false,
    'lastBackupAt': 0,
    'budgetCents': populated ? 300000 : 0,
    'themeMode': theme,
    'activeProvider': 'deepseek',
    'deepSeekModel': 'deepseek-chat',
    'qwenModel': 'qwen-plus',
    'qwenBaseUrl': 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    'hasDeepSeekKey': false,
    'hasQwenKey': false,
    'needsReconfiguration': false,
    'chatGptModel': '',
  },
  'capabilities': {
    'platform': 'android',
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
    'accounts': <Map<String, dynamic>>[],
    'activeAccountId': null,
    'isAuthorizing': false,
    'loginUrl': null,
    'error': null,
    'showWelcome': false,
  },
  'launch': null,
};

Map<String, dynamic> fixtureTransaction({
  required int id,
  required int amount,
  required int categoryId,
  required String merchant,
  String? note,
  String type = 'EXPENSE',
  String status = 'CONFIRMED',
  String source = 'MANUAL',
}) => {
  'id': id,
  'amount': amount,
  'type': type,
  'categoryId': categoryId,
  'note': note,
  'merchant': merchant,
  'accountTag': null,
  'occurredAt': DateTime(2026, 10, 23, 12, id).millisecondsSinceEpoch,
  'createdAt': DateTime(2026, 10, 23, 12, id).millisecondsSinceEpoch,
  'source': source,
  'status': status,
  'duplicateOf': null,
};

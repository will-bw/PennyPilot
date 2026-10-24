part of 'portable_ledger_store.dart';

const _maxInt32 = 2147483647;
const _maxSafeInteger = 9007199254740991;
const _maxTimestamp = 253402214400000;
const _types = {'EXPENSE', 'INCOME'};
const _businessKinds = {
  'standard',
  'expense',
  'income',
  'transfer',
  'receivable',
  'payable',
  'refund',
  'unknown',
};
const _sources = {
  'MANUAL',
  'AI_TEXT',
  'AI_VOICE',
  'AI_IMAGE',
  'BILL_IMPORT',
  'NOTIFICATION',
  'ACCESSIBILITY',
};

Never _invalid(String message) =>
    throw LedgerException(message, code: 'INVALID_ARGUMENT');

void _require(bool condition, String message) {
  if (!condition) _invalid(message);
}

Map<String, dynamic> _map(Object? value, String field) {
  if (value is! Map || value.keys.any((key) => key is! String)) {
    _invalid('$field格式无效。');
  }
  return Map<String, dynamic>.from(value);
}

List<Map<String, dynamic>> _maps(
  Object? value,
  String field, {
  int max = 100000,
}) {
  if (value is! List || value.length > max) _invalid('$field列表无效或过大。');
  return value.map((row) => _map(row, field)).toList();
}

int _int(
  Map<String, dynamic> row,
  String field, {
  int min = -_maxSafeInteger,
  int max = _maxSafeInteger,
  int? fallback,
}) {
  final value = row.containsKey(field) ? row[field] : fallback;
  if (value is! int || value < min || value > max) {
    _invalid('$field 必须是有效范围内的整数。');
  }
  return value;
}

bool _bool(Map<String, dynamic> row, String field, {bool? fallback}) {
  if (!row.containsKey(field) && fallback != null) return fallback;
  final value = row[field];
  if (value is! bool) _invalid('$field 必须是布尔值。');
  return value;
}

String _text(
  Map<String, dynamic> row,
  String field, {
  int max = 4000,
  bool allowEmpty = false,
}) {
  final value = row[field];
  if (value is! String ||
      value.length > max ||
      (!allowEmpty && value.trim().isEmpty)) {
    _invalid('$field 内容为空、过长或格式无效。');
  }
  return value;
}

String? _optionalString(
  Map<String, dynamic> row,
  String field, {
  int max = 4000,
  bool blankToNull = false,
}) {
  if (row[field] == null) return null;
  final value = _text(row, field, max: max, allowEmpty: true);
  return blankToNull && value.trim().isEmpty ? null : value;
}

String _choice(
  Map<String, dynamic> row,
  String field,
  Set<String> choices, {
  String? fallback,
}) {
  if (!row.containsKey(field) && fallback != null) return fallback;
  final value = row[field];
  if (value is! String || !choices.contains(value)) _invalid('$field 取值无效。');
  return value;
}

String _month(Object? value) {
  if (value is! String ||
      !RegExp(r'^[0-9]{4}-(?:0[1-9]|1[0-2])$').hasMatch(value) ||
      value.startsWith('0000')) {
    _invalid('报告月份无效。');
  }
  return value;
}

Map<String, dynamic> _validateCategory(Map<String, dynamic> row) => {
  'id': _int(row, 'id', min: 1),
  'name': _text(row, 'name', max: 20).trim(),
  'icon': _text(row, 'icon', max: 32).trim(),
  'colorIndex': _int(row, 'colorIndex', min: 0, max: _maxInt32),
  'type': _choice(row, 'type', _types),
  'sortOrder': _int(row, 'sortOrder', min: 0, max: _maxInt32),
  'enabled': _bool(row, 'enabled'),
  'isPreset': _bool(row, 'isPreset'),
};

Map<String, dynamic> _validateTransaction(
  Map<String, dynamic> row,
  Map<int, Map<String, dynamic>> categories,
) {
  final id = _int(row, 'id', min: 1);
  final type = _choice(row, 'type', _types);
  final amount = _int(
    row,
    'amount',
    min: -maxTransactionFen,
    max: maxTransactionFen,
  );
  _require(type == 'EXPENSE' ? amount > 0 : amount < 0, '备份金额符号与收支类型不一致。');
  final kind = _choice(
    row,
    'businessKind',
    _businessKinds,
    fallback: 'standard',
  );
  _require(kind != 'expense' || type == 'EXPENSE', '备份消费支出与资金方向不一致。');
  _require(
    !const {'income', 'refund'}.contains(kind) || type == 'INCOME',
    '备份收入或退款与资金方向不一致。',
  );
  final categoryId = _int(row, 'categoryId', min: 1);
  _require(categories[categoryId]?['type'] == type, '备份账目的分类缺失或收支类型不一致。');
  final duplicateOf = row['duplicateOf'] == null
      ? null
      : _int(row, 'duplicateOf', min: 1);
  // Android permits a duplicate hint to outlive the referenced transaction.
  // Retain that hint instead of discarding a valid Android backup.
  _require(duplicateOf != id, '账目不能关联自身为重复项。');
  return {
    'id': id,
    'amount': amount,
    'type': type,
    'categoryId': categoryId,
    'occurredAt': _int(row, 'occurredAt', min: 0, max: _maxTimestamp),
    'createdAt': _int(row, 'createdAt', min: 0, max: _maxTimestamp),
    'source': _choice(row, 'source', _sources),
    'status': _choice(row, 'status', const {'CONFIRMED', 'PENDING', 'IGNORED'}),
    'note': _optionalString(row, 'note', max: 2000),
    'merchant': _optionalString(row, 'merchant', max: 200),
    'accountTag': _optionalString(row, 'accountTag', max: 100),
    'duplicateOf': duplicateOf,
    'rawPayload': _optionalString(row, 'rawPayload', max: 100000),
    'captureEventKey': _optionalString(row, 'captureEventKey', max: 2000),
    'businessKind': kind,
    'metadataJson': _optionalString(row, 'metadataJson', max: 100000),
    'operationKey': _optionalString(row, 'operationKey', max: 2000),
    'editVersion': row['editVersion'] == null
        ? 0
        : _int(row, 'editVersion', min: 0),
  };
}

void _validateRegex(String value, String field) {
  try {
    // The empty alternative always matches and exposes the capture group count,
    // without running an untrusted pattern against arbitrary captured text.
    _require(
      RegExp('(?:$value)|').firstMatch('')!.groupCount > 0,
      '$field需要一个捕获组。',
    );
  } on FormatException {
    _invalid('$field不是有效的正则表达式。');
  }
}

Map<String, dynamic> _validateRule(
  Map<String, dynamic> row, {
  required bool restoring,
}) {
  final appPackage = _text(
    row,
    'appPackage',
    max: restoring ? 300 : 256,
  ).trim();
  _require(
    RegExp(r'^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$').hasMatch(appPackage),
    '请填写有效的应用包名。',
  );
  final amount = _text(row, 'regexAmount');
  final merchant = _optionalString(row, 'regexMerchant', blankToNull: true);
  // Imported Android rules are archival Java regular expressions. Java allows
  // syntax (for example (?i) and possessive quantifiers) absent from Dart's
  // RegExp; preserving those strings is required for a lossless roundtrip.
  // Only newly authored portable rules are compiled by this backend.
  if (!restoring) {
    _validateRegex(amount, '金额正则');
    if (merchant != null) _validateRegex(merchant, '商户正则');
  }
  return {
    'id': _int(row, 'id', min: restoring ? 1 : 0),
    'appPackage': appPackage,
    'regexAmount': amount,
    'regexMerchant': merchant,
    'directionHint': _choice(
      row,
      'directionHint',
      _types,
      fallback: restoring ? null : 'EXPENSE',
    ),
    'enabled': _bool(row, 'enabled', fallback: restoring ? null : true),
    'priority': _int(
      row,
      'priority',
      min: -2147483648,
      max: _maxInt32,
      fallback: restoring ? null : 0,
    ),
  };
}

Map<String, dynamic> _validateReport(
  Map<String, dynamic> row, {
  required bool restoring,
}) {
  final breakdown = _text(row, 'byCategoryJson', max: 100000);
  Object? decoded;
  try {
    decoded = jsonDecode(breakdown);
  } on FormatException {
    _invalid('报告分类汇总不是有效的 JSON。');
  }
  for (final category in _maps(decoded, '报告分类汇总', max: 10000)) {
    _text(category, 'name', max: 200);
    _int(category, 'total', min: 0);
    _int(category, 'count', min: 0);
  }
  return {
    'id': _int(row, 'id', min: restoring ? 1 : 0),
    'yearMonth': _month(row['yearMonth']),
    'totalExpense': _int(row, 'totalExpense'),
    'totalIncome': _int(row, 'totalIncome', min: 0),
    'byCategoryJson': breakdown,
    'llmSummary': _text(row, 'llmSummary', max: 100000, allowEmpty: true),
    'generatedAt': _int(row, 'generatedAt', min: 0, max: _maxTimestamp),
  };
}

Map<String, dynamic> _validateCapture(
  Map<String, dynamic> row,
  Set<int> transactionIds,
) {
  final transactionId = _int(row, 'transactionId', min: 1);
  _require(transactionIds.contains(transactionId), '采集证据所关联的账目不存在。');
  final type = _choice(row, 'type', _types);
  final amount = _int(
    row,
    'amount',
    min: -maxTransactionFen,
    max: maxTransactionFen,
  );
  _require(type == 'EXPENSE' ? amount > 0 : amount < 0, '采集证据金额与收支类型不一致。');
  final observedAt = _int(row, 'observedAt', min: 0, max: _maxTimestamp);
  final firstObservedAt = _int(
    row,
    'firstObservedAt',
    min: 0,
    max: _maxTimestamp,
  );
  return {
    'eventKey': _text(row, 'eventKey', max: 2000),
    'transactionId': transactionId,
    'packageName': _text(row, 'packageName', max: 300),
    'source': _choice(row, 'source', const {'NOTIFICATION', 'ACCESSIBILITY'}),
    'observedAt': observedAt,
    'firstObservedAt': firstObservedAt,
    'notificationKey': _optionalString(row, 'notificationKey', max: 2000),
    'textFingerprint': _text(row, 'textFingerprint', max: 2000),
    'orderId': _optionalString(row, 'orderId', max: 2000),
    'orderDomain': _text(row, 'orderDomain', max: 300),
    'amount': amount,
    'type': type,
    'metadataJson': _optionalString(row, 'metadataJson', max: 100000),
    'merchantFingerprint': _optionalString(
      row,
      'merchantFingerprint',
      max: 2000,
    ),
  };
}

Map<String, List<Map<String, dynamic>>> _validateBackup(
  Map<String, dynamic> data,
) {
  _require(
    data['format'] == 'pennypilot' &&
        data['version'] is int &&
        data['version'] == 1,
    '不支持此备份格式或版本。',
  );
  _require(
    data.keys.every(
      const {
        'format',
        'version',
        'transactions',
        'categories',
        'rules',
        'reports',
        'captureEvents',
        'captureTombstones',
      }.contains,
    ),
    '备份包含未知字段，请升级应用后重试。',
  );
  try {
    _require(
      utf8.encode(jsonEncode(data)).length <= 20 * 1024 * 1024,
      '备份文件不能超过 20 MB。',
    );
  } on JsonUnsupportedObjectError {
    _invalid('备份包含无法识别的数据。');
  }
  final categories = _maps(
    data['categories'],
    '分类',
    max: 10000,
  ).map(_validateCategory).toList();
  _require(categories.isNotEmpty, '备份必须包含账本分类。');
  final categoryIndex = <int, Map<String, dynamic>>{};
  final categoryNames = <String>{};
  for (final row in categories) {
    _require(categoryIndex[row['id']] == null, '备份包含重复分类编号。');
    _require(
      categoryNames.add('${row['type']}\u0000${row['name']}'),
      '备份包含同类型的重复分类名。',
    );
    categoryIndex[row['id'] as int] = row;
  }
  final transactions = _maps(
    data['transactions'],
    '账目',
  ).map((row) => _validateTransaction(row, categoryIndex)).toList();
  final rules = _maps(
    data['rules'],
    '规则',
    max: 10000,
  ).map((row) => _validateRule(row, restoring: true)).toList();
  final reports = _maps(
    data['reports'],
    '报告',
    max: 10000,
  ).map((row) => _validateReport(row, restoring: true)).toList();
  for (final rows in [transactions, rules, reports]) {
    final ids = <int>{};
    for (final row in rows) {
      _require(ids.add(row['id'] as int), '备份包含重复记录编号。');
    }
  }
  final eventKeys = <String>{};
  for (final row in transactions) {
    if (row['captureEventKey'] != null) {
      _require(eventKeys.add(row['captureEventKey'] as String), '备份包含重复采集标识。');
    }
  }
  final months = <String>{};
  for (final row in reports) {
    _require(months.add(row['yearMonth'] as String), '备份包含同月份的重复报告。');
  }
  final transactionIds = transactions.map((row) => row['id'] as int).toSet();
  final captures = _maps(
    data.containsKey('captureEvents') ? data['captureEvents'] : const [],
    '采集证据',
    max: 100000,
  ).map((row) => _validateCapture(row, transactionIds)).toList();
  final captureKeys = <String>{};
  for (final row in captures) {
    _require(captureKeys.add(row['eventKey'] as String), '备份包含重复采集证据。');
  }
  return {
    'categories': categories,
    'transactions': transactions,
    'rules': rules,
    'reports': reports,
    'captureEvents': captures,
    'captureTombstones': _maps(data['captureTombstones'] ?? [], '删除记录')
        .map(
          (row) => {
            'identityKey': _text(row, 'identityKey', max: 2000),
            'transactionId': _int(row, 'transactionId', min: 1),
            'deletedAt': _int(row, 'deletedAt', min: 0, max: _maxTimestamp),
          },
        )
        .toList(),
  };
}

const _defaultSettings = <String, dynamic>{
  'onboardingDone': false,
  'notificationCaptureEnabled': false,
  'screenCaptureEnabled': false,
  'notificationCloudParsingEnabled': false,
  'lastBackupAt': 0,
  'budgetCents': 0,
  'themeMode': 'system',
  'activeProvider': 'deepseek',
  'deepSeekModel': 'deepseek-chat',
  'qwenModel': 'qwen-plus',
  'qwenBaseUrl':
      'https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1',
  'chatGptModel': '',
  'recognitionTextModel': '',
  'recognitionTextProvider': '',
  'recognitionVisionProvider': '',
  'recognitionReviewProvider': '',
  'recognitionVisionModel': '',
  'recognitionReviewModel': '',
  'recognitionPreferencesJson': '[]',
  'screenCloudParsingEnabled': false,
  'screenVisualCaptureEnabled': false,
  'captureAutoRecordEnabled': false,
  'activeInputAutoSaveEnabled': false,
  'cloudImageParsingEnabled': false,
};

Map<String, dynamic> _validateSettings(Map<String, dynamic> arguments) {
  _require(
    arguments.keys.every(_defaultSettings.containsKey),
    '本地账本只能保存普通设置，密钥必须写入系统安全存储。',
  );
  final validated = <String, dynamic>{};
  for (final key in arguments.keys) {
    switch (key) {
      case 'onboardingDone':
        validated[key] = _bool(arguments, key);
      case 'notificationCaptureEnabled':
      case 'screenCaptureEnabled':
      case 'notificationCloudParsingEnabled':
        _require(!_bool(arguments, key), '当前平台不支持 Android 支付采集。');
        validated[key] = false;
      case 'screenVisualCaptureEnabled':
      case 'screenCloudParsingEnabled':
      case 'captureAutoRecordEnabled':
      case 'activeInputAutoSaveEnabled':
      case 'cloudImageParsingEnabled':
        validated[key] = _bool(arguments, key);
      case 'recognitionTextModel':
      case 'recognitionVisionModel':
      case 'recognitionReviewModel':
        validated[key] = _text(
          arguments,
          key,
          max: 256,
          allowEmpty: true,
        ).trim();
      case 'recognitionPreferencesJson':
        final value = _text(arguments, key, max: 30000);
        _require(jsonDecode(value) is List, '分类偏好格式无效。');
        validated[key] = value;
      case 'lastBackupAt':
        validated[key] = _int(arguments, key, min: 0, max: _maxTimestamp);
      case 'budgetCents':
        validated[key] = _int(arguments, key, min: 0, max: maxTransactionFen);
      case 'themeMode':
        validated[key] = _choice(arguments, key, const {
          'system',
          'light',
          'dark',
        });
      case 'recognitionTextProvider':
      case 'recognitionVisionProvider':
      case 'recognitionReviewProvider':
      case 'activeProvider':
        validated[key] = _choice(arguments, key, const {
          '',
          'deepseek',
          'qwen',
          'chatgpt',
        });
      case 'deepSeekModel':
      case 'qwenModel':
      case 'chatGptModel':
        validated[key] = _text(arguments, key, max: 256).trim();
      case 'qwenBaseUrl':
        final value = _text(arguments, key, max: 2048).trim();
        final uri = Uri.tryParse(value);
        _require(
          uri != null &&
              uri.scheme == 'https' &&
              uri.host.isNotEmpty &&
              uri.userInfo.isEmpty &&
              !uri.hasQuery &&
              !uri.hasFragment,
          'AI 服务地址须为有效的 HTTPS 地址，不能附带密钥、查询参数或片段。',
        );
        validated[key] = value.replaceFirst(RegExp(r'/+$'), '');
    }
  }
  return validated;
}

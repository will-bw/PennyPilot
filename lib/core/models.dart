import 'package:flutter/foundation.dart';

const maxTransactionFen = 999999999;

/// Converts decimal yuan to integer fen without floating point rounding.
int? parseMoney(String input) {
  final value = input.trim();
  if (!RegExp(
    r'^(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\.[0-9]{1,2})?$',
  ).hasMatch(value)) {
    return null;
  }
  final parts = value.replaceAll(',', '').split('.');
  final whole = int.tryParse(parts.first);
  if (whole == null || whole > maxTransactionFen ~/ 100) return null;
  final fraction = parts.length == 2 ? int.parse(parts[1].padRight(2, '0')) : 0;
  final result = whole * 100 + fraction;
  return result > 0 && result <= maxTransactionFen ? result : null;
}

String formatMoney(int fen, {bool group = true}) {
  final absolute = fen.abs();
  var whole = (absolute ~/ 100).toString();
  if (group) {
    whole = whole.replaceAllMapped(RegExp(r'\B(?=(\d{3})+(?!\d))'), (_) => ',');
  }
  return '${fen < 0 ? '-' : ''}$whole.${(absolute % 100).toString().padLeft(2, '0')}';
}

enum TxType {
  expense('EXPENSE', '支出'),
  income('INCOME', '收入');

  const TxType(this.wireValue, this.label);
  final String wireValue;
  final String label;
  static TxType fromWire(Object? value) => values.firstWhere(
    (item) => item.wireValue == value,
    orElse: () => throw const FormatException('账单收支类型无效'),
  );
}

enum TxStatus {
  pending('PENDING', '待确认'),
  confirmed('CONFIRMED', '已入账'),
  ignored('IGNORED', '已忽略');

  const TxStatus(this.wireValue, this.label);
  final String wireValue;
  final String label;
  static TxStatus fromWire(Object? value) => values.firstWhere(
    (item) => item.wireValue == value,
    orElse: () => throw const FormatException('账单状态无效'),
  );
}

enum TxSource {
  manual('MANUAL', '手动记账'),
  notification('NOTIFICATION', '通知捕获'),
  accessibility('ACCESSIBILITY', '支付页面'),
  aiText('AI_TEXT', '文字记账'),
  aiVoice('AI_VOICE', '语音记账');

  const TxSource(this.wireValue, this.label);
  final String wireValue;
  final String label;
  static TxSource fromWire(Object? value) => values.firstWhere(
    (item) => item.wireValue == value,
    orElse: () => throw const FormatException('账单来源无效'),
  );
}

Map<String, dynamic> stringMap(Object? value) {
  if (value is! Map) throw const FormatException('账本数据格式无效');
  return value.map((key, value) => MapEntry(key.toString(), value));
}

int integer(Object? value, String field, {int? fallback}) {
  if (value == null && fallback != null) return fallback;
  if (value is! int) throw FormatException('$field 必须是整数');
  return value;
}

List<T> _list<T>(Object? value, T Function(Map<String, dynamic>) parse) =>
    value == null
    ? <T>[]
    : (value as List)
          .map((item) => parse(stringMap(item)))
          .toList(growable: false);

@immutable
class LedgerTransaction {
  const LedgerTransaction({
    this.id = 0,
    required this.amountFen,
    required this.type,
    required this.categoryId,
    required this.occurredAt,
    this.createdAt,
    this.merchant,
    this.note,
    this.accountTag,
    this.source = TxSource.manual,
    this.status = TxStatus.confirmed,
    this.duplicateOf,
  });

  final int id;

  /// Always positive. The Android database's signed amount is normalized here.
  final int amountFen;
  final TxType type;
  final int categoryId;
  final DateTime occurredAt;
  final DateTime? createdAt;
  final String? merchant, note, accountTag;
  final TxSource source;
  final TxStatus status;
  final int? duplicateOf;
  int get signedAmountFen => type == TxType.income ? -amountFen : amountFen;

  factory LedgerTransaction.fromMap(
    Map<String, dynamic> map, {
    bool draft = false,
  }) => LedgerTransaction(
    id: integer(map['id'], 'id', fallback: 0),
    amountFen: integer(map['amount'], 'amount').abs(),
    type: TxType.fromWire(map['type']),
    categoryId: integer(map['categoryId'], 'categoryId'),
    occurredAt: DateTime.fromMillisecondsSinceEpoch(
      integer(map['occurredAt'], 'occurredAt'),
    ),
    createdAt: map['createdAt'] == null
        ? null
        : DateTime.fromMillisecondsSinceEpoch(
            integer(map['createdAt'], 'createdAt'),
          ),
    merchant: map['merchant'] as String?,
    note: map['note'] as String?,
    accountTag: map['accountTag'] as String?,
    source: draft
        ? TxSource.aiText
        : TxSource.fromWire(map['source'] ?? 'MANUAL'),
    status: TxStatus.fromWire(map['status'] ?? 'CONFIRMED'),
    duplicateOf: map['duplicateOf'] == null
        ? null
        : integer(map['duplicateOf'], 'duplicateOf'),
  );

  Map<String, dynamic> toMap() => {
    'id': id,
    'amount': amountFen,
    'type': type.wireValue,
    'categoryId': categoryId,
    'occurredAt': occurredAt.millisecondsSinceEpoch,
    'merchant': merchant,
    'note': note,
    'accountTag': accountTag,
    'source': source.wireValue,
  };

  LedgerTransaction copyWith({
    int? id,
    int? amountFen,
    TxType? type,
    int? categoryId,
    DateTime? occurredAt,
    String? merchant,
    String? note,
    String? accountTag,
    TxSource? source,
    TxStatus? status,
  }) => LedgerTransaction(
    id: id ?? this.id,
    amountFen: amountFen ?? this.amountFen,
    type: type ?? this.type,
    categoryId: categoryId ?? this.categoryId,
    occurredAt: occurredAt ?? this.occurredAt,
    createdAt: createdAt,
    merchant: merchant ?? this.merchant,
    note: note ?? this.note,
    accountTag: accountTag ?? this.accountTag,
    source: source ?? this.source,
    status: status ?? this.status,
    duplicateOf: duplicateOf,
  );
}

@immutable
class LedgerCategory {
  const LedgerCategory({
    this.id = 0,
    required this.name,
    this.icon = '📦',
    this.colorIndex = 0,
    required this.type,
    this.sortOrder = 0,
    this.enabled = true,
    this.isPreset = false,
  });
  final int id, colorIndex, sortOrder;
  final String name, icon;
  final TxType type;
  final bool enabled, isPreset;
  factory LedgerCategory.fromMap(Map<String, dynamic> map) => LedgerCategory(
    id: integer(map['id'], 'id'),
    name: map['name'] as String,
    icon: map['icon'] as String? ?? '📦',
    colorIndex: integer(map['colorIndex'], 'colorIndex', fallback: 0),
    type: TxType.fromWire(map['type']),
    sortOrder: integer(map['sortOrder'], 'sortOrder', fallback: 0),
    enabled: map['enabled'] as bool? ?? true,
    isPreset: map['isPreset'] as bool? ?? false,
  );
  Map<String, dynamic> toMap() => {
    'id': id,
    'name': name,
    'icon': icon,
    'colorIndex': colorIndex,
    'type': type.wireValue,
    'sortOrder': sortOrder,
    'enabled': enabled,
  };
  LedgerCategory copyWith({
    String? name,
    String? icon,
    bool? enabled,
    int? sortOrder,
  }) => LedgerCategory(
    id: id,
    name: name ?? this.name,
    icon: icon ?? this.icon,
    colorIndex: colorIndex,
    type: type,
    sortOrder: sortOrder ?? this.sortOrder,
    enabled: enabled ?? this.enabled,
    isPreset: isPreset,
  );
}

@immutable
class ParseRule {
  const ParseRule({
    this.id = 0,
    required this.appPackage,
    required this.regexAmount,
    this.regexMerchant,
    this.directionHint = TxType.expense,
    this.enabled = true,
    this.priority = 0,
  });
  final int id, priority;
  final String appPackage, regexAmount;
  final String? regexMerchant;
  final TxType directionHint;
  final bool enabled;
  factory ParseRule.fromMap(Map<String, dynamic> map) => ParseRule(
    id: integer(map['id'], 'id'),
    appPackage: map['appPackage'] as String,
    regexAmount: map['regexAmount'] as String,
    regexMerchant: map['regexMerchant'] as String?,
    directionHint: TxType.fromWire(map['directionHint'] ?? 'EXPENSE'),
    enabled: map['enabled'] as bool? ?? true,
    priority: integer(map['priority'], 'priority', fallback: 0),
  );
  Map<String, dynamic> toMap() => {
    'id': id,
    'appPackage': appPackage,
    'regexAmount': regexAmount,
    'regexMerchant': regexMerchant,
    'directionHint': directionHint.wireValue,
    'enabled': enabled,
    'priority': priority,
  };
  ParseRule copyWith({bool? enabled}) => ParseRule(
    id: id,
    appPackage: appPackage,
    regexAmount: regexAmount,
    regexMerchant: regexMerchant,
    directionHint: directionHint,
    enabled: enabled ?? this.enabled,
    priority: priority,
  );
}

@immutable
class MonthlyReport {
  const MonthlyReport({
    required this.id,
    required this.yearMonth,
    required this.totalExpense,
    required this.totalIncome,
    required this.generatedAt,
    this.byCategoryJson = '[]',
    this.llmSummary,
  });
  final int id, totalExpense, totalIncome;
  final String yearMonth, byCategoryJson;
  final String? llmSummary;
  final DateTime generatedAt;
  factory MonthlyReport.fromMap(Map<String, dynamic> map) => MonthlyReport(
    id: integer(map['id'], 'id'),
    yearMonth: map['yearMonth'] as String,
    totalExpense: integer(map['totalExpense'], 'totalExpense'),
    totalIncome: integer(map['totalIncome'], 'totalIncome'),
    byCategoryJson: map['byCategoryJson'] as String? ?? '[]',
    llmSummary: map['llmSummary'] as String?,
    generatedAt: DateTime.fromMillisecondsSinceEpoch(
      integer(map['generatedAt'], 'generatedAt'),
    ),
  );
}

@immutable
class LedgerSnapshot {
  LedgerSnapshot({
    required this.revision,
    List<LedgerTransaction> transactions = const [],
    List<LedgerCategory> categories = const [],
    List<ParseRule> rules = const [],
    List<MonthlyReport> reports = const [],
    Map<String, dynamic> settings = const {},
    Map<String, dynamic> capabilities = const {},
    Map<String, dynamic> auth = const {},
    this.launch,
  }) : transactions = List.unmodifiable(transactions),
       categories = List.unmodifiable(categories),
       rules = List.unmodifiable(rules),
       reports = List.unmodifiable(reports),
       settings = Map.unmodifiable(settings),
       capabilities = Map.unmodifiable(capabilities),
       auth = Map.unmodifiable(auth);
  final int revision;
  final List<LedgerTransaction> transactions;
  final List<LedgerCategory> categories;
  final List<ParseRule> rules;
  final List<MonthlyReport> reports;
  final Map<String, dynamic> settings, capabilities, auth;
  final Map<String, dynamic>? launch;
  factory LedgerSnapshot.fromMap(Map<String, dynamic> map) => LedgerSnapshot(
    revision: integer(map['revision'], 'revision'),
    transactions: _list(map['transactions'], LedgerTransaction.fromMap),
    categories: _list(map['categories'], LedgerCategory.fromMap),
    rules: _list(map['rules'], ParseRule.fromMap),
    reports: _list(map['reports'], MonthlyReport.fromMap),
    settings: stringMap(map['settings'] ?? {}),
    capabilities: stringMap(map['capabilities'] ?? {}),
    auth: stringMap(map['auth'] ?? {}),
    launch: map['launch'] == null ? null : stringMap(map['launch']),
  );
}

@immutable
class LedgerEvent {
  const LedgerEvent(this.type, this.revision, [this.data = const {}]);
  final String type;
  final int revision;
  final Map<String, dynamic> data;
  factory LedgerEvent.fromMap(Map<String, dynamic> map) => LedgerEvent(
    map['type'] as String,
    integer(map['revision'], 'revision'),
    Map.unmodifiable(map),
  );
}

@immutable
class LedgerSummary {
  const LedgerSummary({
    this.expenseFen = 0,
    this.incomeFen = 0,
    this.count = 0,
  });
  final int expenseFen, incomeFen, count;
  int get balanceFen => incomeFen - expenseFen;
}

@immutable
class CategorySpending {
  const CategorySpending({
    required this.category,
    required this.amountFen,
    required this.count,
  });
  final LedgerCategory category;
  final int amountFen, count;
}

@immutable
class InsightChart {
  const InsightChart({
    required this.labels,
    required this.values,
    this.highlightIndex = 0,
    this.unit = '元',
  });
  final List<String> labels;

  /// Amounts are integer fen, even though the presentation unit is yuan.
  final List<int> values;
  final int highlightIndex;
  final String unit;
  factory InsightChart.fromMap(Map<String, dynamic> map) => InsightChart(
    labels: List<String>.unmodifiable(map['labels'] as List),
    values: List<int>.unmodifiable(
      (map['values'] as List).map((value) => integer(value, 'values')),
    ),
    highlightIndex: integer(
      map['highlightIndex'],
      'highlightIndex',
      fallback: 0,
    ),
    unit: map['unit'] as String? ?? '元',
  );
}

@immutable
class ChatMessage {
  const ChatMessage({
    required this.role,
    required this.text,
    this.isError = false,
    this.chartData,
  });
  final String role, text;
  final bool isError;
  final InsightChart? chartData;
  Map<String, dynamic> toHistory() => {
    'role': role == 'user' ? 'user' : 'assistant',
    'content': text,
  };
}

@immutable
class AiModel {
  const AiModel({required this.slug, required this.displayName});
  final String slug, displayName;
  factory AiModel.fromMap(Map<String, dynamic> map) => AiModel(
    slug: map['slug'] as String,
    displayName: map['displayName'] as String? ?? map['slug'] as String,
  );
}

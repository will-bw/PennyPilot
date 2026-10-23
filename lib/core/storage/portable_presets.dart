part of 'portable_ledger_store.dart';

final _presetCategories = <Map<String, dynamic>>[
  for (final (index, entry) in const [
    ('餐饮', '🍜', 0, 'EXPENSE'),
    ('交通', '🚕', 1, 'EXPENSE'),
    ('购物', '🛍️', 2, 'EXPENSE'),
    ('居住', '🏠', 3, 'EXPENSE'),
    ('娱乐', '🎬', 4, 'EXPENSE'),
    ('医疗', '💊', 5, 'EXPENSE'),
    ('教育', '📚', 6, 'EXPENSE'),
    ('人情', '🧧', 0, 'EXPENSE'),
    ('其他', '📦', 1, 'EXPENSE'),
    ('工资', '💼', 6, 'INCOME'),
    ('红包', '🧧', 6, 'INCOME'),
    ('其他', '💰', 6, 'INCOME'),
  ].indexed)
    {
      'id': index + 1,
      'name': entry.$1,
      'icon': entry.$2,
      'colorIndex': entry.$3,
      'type': entry.$4,
      'sortOrder': index,
      'enabled': true,
      'isPreset': true,
    },
];

const _amountPattern = r'((?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d{1,2})?)(?![\d.,])';
const _separatorPattern = r'\s*[:：]?\s*[¥￥]?\s*';
const _expensePattern = r'(?:付款|支付|消费)(?:成功)?(?:金额)?';
const _incomePattern = r'(?:收款(?:到账)?|收入|到账|收到(?:转账|红包))(?:成功)?(?:金额)?';

final _presetRules = <Map<String, dynamic>>[
  for (final (index, entry) in const [
    (
      'com.tencent.mm',
      '$_expensePattern$_separatorPattern$_amountPattern',
      r'(?:向|给)(.+?)(?:付款|支付)',
      'EXPENSE',
      10,
    ),
    (
      'com.tencent.mm',
      r'微信支付\s*[:：]?\s*[¥￥]\s*' + _amountPattern,
      null,
      'EXPENSE',
      20,
    ),
    (
      'com.tencent.mm',
      '$_incomePattern$_separatorPattern$_amountPattern',
      null,
      'INCOME',
      0,
    ),
    (
      'com.eg.android.AlipayGphone',
      '$_expensePattern$_separatorPattern$_amountPattern',
      r'(?:向|在)(.+?)(?:付款|支付|消费)',
      'EXPENSE',
      10,
    ),
    (
      'com.eg.android.AlipayGphone',
      '$_incomePattern$_separatorPattern$_amountPattern',
      null,
      'INCOME',
      0,
    ),
  ].indexed)
    {
      'id': index + 1,
      'appPackage': entry.$1,
      'regexAmount': entry.$2,
      'regexMerchant': entry.$3,
      'directionHint': entry.$4,
      'enabled': true,
      'priority': entry.$5,
    },
];

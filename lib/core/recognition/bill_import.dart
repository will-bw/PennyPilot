import 'dart:convert';

import '../ledger_repository.dart';
import '../models.dart';
import 'recognition_protocol.dart';

class BillImportRow {
  const BillImportRow({
    required this.line,
    required this.description,
    required this.state,
    this.transaction,
  });
  final int line;
  final String description, state;
  final LedgerTransaction? transaction;
}

/// Parses exported WeChat/Alipay CSV without sending financial files to a model.
class BillImport {
  static List<BillImportRow> parse(
    String text,
    LedgerSnapshot snapshot, {
    required String account,
  }) {
    if (account.trim().isEmpty) {
      throw const LedgerException('请填写这份账单所属的账户标签，用于跨次导入去重。');
    }
    if (text.length > 20 * 1024 * 1024) {
      throw const LedgerException('账单文件不能超过 20 MB。');
    }
    final rows = csv(text.replaceFirst('\uFEFF', ''));
    final headerIndex = rows.indexWhere(
      (row) =>
          row.any((v) => v.trim() == '交易时间') &&
          row.any((v) => v.contains('金额')),
    );
    if (headerIndex < 0) {
      throw const LedgerException('没有找到微信或支付宝 CSV 表头，请导入原始账单文件。');
    }
    final header = rows[headerIndex].map((v) => v.trim()).toList();
    final isWechat =
        text.contains('微信支付') ||
        header.contains('当前状态') && header.contains('交易单号');
    final app = isWechat ? 'wechat' : 'alipay';
    String field(List<String> row, List<String> names) {
      for (final name in names) {
        final index = header.indexOf(name);
        if (index >= 0 && index < row.length) {
          return row[index].replaceAll('\t', '').trim();
        }
      }
      return '';
    }

    final result = <BillImportRow>[];
    final seen = <String>{};
    for (var i = headerIndex + 1; i < rows.length; i++) {
      final row = rows[i];
      if (row.every((v) => v.trim().isEmpty) || row.first.startsWith('----')) {
        continue;
      }
      final rawDate = field(row, ['交易时间']);
      if (!RegExp(r'^\d{4}[-/]').hasMatch(rawDate)) continue;
      final date = RecognitionProtocol.strictDate(rawDate.replaceAll('/', '-'));
      final direction = field(row, ['收/支', '收支', '收支类型']);
      final status = field(row, ['当前状态', '交易状态']);
      final kindText = field(row, ['交易类型', '交易分类']);
      final merchant = field(row, ['交易对方']);
      final note = field(row, ['商品', '商品说明', '商品名称']);
      final rawAmount = field(row, ['金额(元)', '金额（元）', '金额']);
      final amount = parseMoney(
        rawAmount
            .replaceAll(RegExp(r'[¥￥\s]'), '')
            .replaceFirst(RegExp(r'^[-+]'), ''),
      );
      final id = field(row, ['交易单号', '交易订单号', '支付宝交易号']);
      final refund =
          direction == '收入' &&
          (kindText.contains('退款') ||
              note.startsWith('退款') ||
              status.contains('退款'));
      final isTransfer = RegExp(r'充值|提现|还款|信用卡').hasMatch('$kindText $note');
      final type = direction == '支出'
          ? TxType.expense
          : direction == '收入'
          ? TxType.income
          : null;
      final completed =
          RegExp(r'成功|已收钱|已收款|已转账|已到账|已存入|已全额退款|已退款|已退还').hasMatch(status) &&
          !RegExp(r'未|失败|关闭|退款中|处理中|待').hasMatch(status);
      final summary = '$rawDate · $merchant · $rawAmount · $status';
      if (date == null || amount == null || type == null || !completed) {
        result.add(
          BillImportRow(
            line: i + 1,
            description:
                '$summary\n${!completed ? '未完成交易，不导入' : '日期、金额或资金方向不完整，请手动核对'}',
            state: 'unsupported',
          ),
        );
        continue;
      }
      final kind = refund
          ? 'refund'
          : isTransfer
          ? 'transfer'
          : kindText.contains('转账')
          ? 'unknown'
          : type == TxType.expense
          ? 'expense'
          : 'income';
      final identity = id.isEmpty
          ? null
          : RecognitionProtocol.identity(
              '$app\u0000${account.trim()}\u0000${refund ? 'refund' : 'payment'}\u0000$id',
            );
      final categories = snapshot.categories
          .where((c) => c.enabled && c.type == type)
          .toList();
      if (categories.isEmpty) throw const LedgerException('请先添加收入和支出分类。');
      final category =
          categories.where((c) => c.name == '其他').firstOrNull ??
          categories.first;
      final matches = snapshot.transactions
          .where(
            (tx) =>
                identity != null &&
                tx.recognitionMetadata['importIdentity'] == identity,
          )
          .toList();
      var state = !refund && status.contains('退款') ? 'review' : 'new';
      if (matches.isNotEmpty) {
        state = matches.any((tx) => tx.amountFen == amount && tx.type == type)
            ? 'duplicate'
            : 'conflict';
      }
      if (identity != null && !seen.add(identity)) state = 'duplicate';
      final possible = snapshot.transactions.any(
        (tx) =>
            tx.amountFen == amount &&
            tx.type == type &&
            tx.merchant == merchant &&
            tx.occurredAt.difference(date).inSeconds.abs() <= 120,
      );
      if (state == 'new' && possible) state = 'review';
      final metadata = {
        'protocolVersion': 1,
        'scene': 'bill_import',
        'paymentStatus': 'completed',
        'currency': 'CNY',
        'sourceApp': app,
        'importIdentity': identity,
        'transactionId': id,
        'timePrecision': 'exact',
        'operationId':
            'import:${identity ?? RecognitionProtocol.identity('$app:${account.trim()}:$rawDate:$amount:$merchant:$i')}',
        'fieldEvidence': {
          'amount': {'quote': rawAmount, 'source': 'file'},
          'type': {'quote': direction, 'source': 'file'},
          'occurredAt': {'quote': rawDate, 'source': 'file'},
        },
        'questions': state == 'review' ? ['已有相近账目，请确认不是同一笔支付。'] : <String>[],
        'reviewed': false,
      };
      result.add(
        BillImportRow(
          line: i + 1,
          description: summary,
          state: state,
          transaction: LedgerTransaction(
            amountFen: amount,
            type: type,
            categoryId: category.id,
            occurredAt: date,
            merchant: merchant,
            note: note,
            accountTag: account.trim(),
            source: TxSource.billImport,
            businessKind: kind,
            metadataJson: jsonEncode(metadata),
          ),
        ),
      );
    }
    if (result.isEmpty) throw const LedgerException('账单中没有可识别的交易行。');
    if (result.length > 10000) {
      throw const LedgerException('一次最多核对 10,000 行，请按月份导出。');
    }
    return result;
  }

  static List<List<String>> csv(String input) {
    final rows = <List<String>>[];
    var row = <String>[], field = StringBuffer(), quoted = false;
    for (var i = 0; i < input.length; i++) {
      final ch = input[i];
      if (ch == '"') {
        if (quoted && i + 1 < input.length && input[i + 1] == '"') {
          field.write('"');
          i++;
        } else {
          quoted = !quoted;
        }
      } else if (ch == ',' && !quoted) {
        row.add(field.toString());
        field = StringBuffer();
      } else if ((ch == '\n' || ch == '\r') && !quoted) {
        if (ch == '\r' && i + 1 < input.length && input[i + 1] == '\n') i++;
        row.add(field.toString());
        rows.add(row);
        row = [];
        field = StringBuffer();
      } else {
        field.write(ch);
      }
    }
    if (quoted) throw const LedgerException('CSV 引号未闭合，请重新导出原始文件。');
    if (field.isNotEmpty || row.isNotEmpty) {
      row.add(field.toString());
      rows.add(row);
    }
    return rows;
  }
}

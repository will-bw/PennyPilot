import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/models.dart';
import 'package:pennypilot/core/recognition/bill_import.dart';

final snapshot = LedgerSnapshot(
  revision: 0,
  categories: const [
    LedgerCategory(id: 1, name: '其他', type: TxType.expense),
    LedgerCategory(id: 2, name: '其他', type: TxType.income),
  ],
);
const header = '微信支付账单明细\n交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注\n';
const row = '2026-09-20 12:30:00,商户消费,午饭店,午餐,支出,￥12.00,零钱,支付成功,wx1,m1,/\n';
void main() {
  test('preserves historical date, integer amount, source and account', () {
    final rows = BillImport.parse('$header$row', snapshot, account: '我的微信');
    final tx = rows.single.transaction!;
    expect(tx.amountFen, 1200);
    expect(tx.occurredAt, DateTime(2026, 9, 20, 12, 30));
    expect(tx.source, TxSource.billImport);
    expect(tx.accountTag, '我的微信');
    expect(tx.recognitionMetadata['transactionId'], 'wx1');
  });
  test('CSV quotes commas newlines and escaped quote remain one record', () {
    expect(BillImport.csv('a,"b,c","d""e"\r\nx,"y\nz",q'), [
      ['a', 'b,c', 'd"e'],
      ['x', 'y\nz', 'q'],
    ]);
  });
  test('same typed transaction identity skips duplicate import', () {
    final first = BillImport.parse(
      '$header$row',
      snapshot,
      account: '我的微信',
    ).single.transaction!;
    final next = LedgerSnapshot(
      revision: 0,
      categories: snapshot.categories,
      transactions: [first],
    );
    expect(
      BillImport.parse('$header$row', next, account: '我的微信').single.state,
      'duplicate',
    );
    expect(
      BillImport.parse('$header$row', next, account: '另一个微信').single.state,
      'review',
    );
  });
  test('matching id with another amount is conflict, never overwritten', () {
    final first = BillImport.parse(
      '$header$row',
      snapshot,
      account: '我的微信',
    ).single.transaction!;
    final next = LedgerSnapshot(
      revision: 0,
      categories: snapshot.categories,
      transactions: [first],
    );
    expect(
      BillImport.parse(
        '$header${row.replaceFirst('12.00', '13.00')}',
        next,
        account: '我的微信',
      ).single.state,
      'conflict',
    );
  });
  test('equal amount another id is review not swallowed', () {
    final first = BillImport.parse(
      '$header$row',
      snapshot,
      account: '我的微信',
    ).single.transaction!;
    final next = LedgerSnapshot(
      revision: 0,
      categories: snapshot.categories,
      transactions: [first],
    );
    expect(
      BillImport.parse(
        '$header${row.replaceFirst('wx1', 'wx2')}',
        next,
        account: '我的微信',
      ).single.state,
      'review',
    );
  });
  test('failed and pending payments are not imported', () {
    for (final status in ['支付失败', '待收款', '退款中', '交易关闭']) {
      expect(
        BillImport.parse(
          '$header${row.replaceFirst('支付成功', status)}',
          snapshot,
          account: '微信',
        ).single.transaction,
        isNull,
      );
    }
  });
  test('partial refunds use independent id and semantic kind', () {
    final refund = row
        .replaceFirst('商户消费', '退款')
        .replaceFirst('支出', '收入')
        .replaceFirst('支付成功', '退款成功')
        .replaceFirst('wx1', 'refund1');
    final rows = BillImport.parse(
      '$header$row$refund',
      snapshot,
      account: '微信',
    );
    expect(rows.last.transaction!.effectiveKind, 'refund');
    expect(rows.last.transaction!.countsAsIncome, false);
    expect(
      jsonDecode(rows.first.transaction!.metadataJson!)['importIdentity'],
      isNot(jsonDecode(rows.last.transaction!.metadataJson!)['importIdentity']),
    );
  });
  test('malformed date and floating precision do not fabricate records', () {
    expect(
      BillImport.parse(
        '$header${row.replaceFirst('2026-09-20', '2026-02-30')}',
        snapshot,
        account: '微信',
      ).single.transaction,
      isNull,
    );
    expect(
      BillImport.parse(
        '$header${row.replaceFirst('12.00', '12.001')}',
        snapshot,
        account: '微信',
      ).single.transaction,
      isNull,
    );
  });
  test('requires account to avoid cross account identity collisions', () {
    expect(
      () => BillImport.parse('$header$row', snapshot, account: ''),
      throwsException,
    );
  });
  test('unclosed CSV quote fails explicitly', () {
    expect(() => BillImport.csv('"unfinished'), throwsException);
  });
  test('Alipay format supported', () {
    const csv =
        '交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注\n2026-10-20 10:00:00,餐饮,商户,account,早餐,支出,8.50,余额,交易成功,ali123,merchant123,\n';
    final tx = BillImport.parse(
      csv,
      snapshot,
      account: '支付宝',
    ).single.transaction!;
    expect(tx.amountFen, 850);
    expect(tx.recognitionMetadata['sourceApp'], 'alipay');
  });
}

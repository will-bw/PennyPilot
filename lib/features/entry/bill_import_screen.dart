import 'dart:convert';
import 'package:flutter/material.dart';
import '../../core/ledger_controller.dart';
import '../../core/models.dart';
import '../../core/recognition/bill_import.dart';

class BillImportScreen extends StatefulWidget {
  const BillImportScreen({super.key, required this.controller});
  final LedgerController controller;
  @override
  State<BillImportScreen> createState() => _BillImportScreenState();
}

class _BillImportScreenState extends State<BillImportScreen> {
  final account = TextEditingController();
  List<BillImportRow> rows = [];
  final selected = <int>{};
  bool busy = false;
  String? error, name;
  @override
  void dispose() {
    account.dispose();
    super.dispose();
  }

  Future<void> choose() async {
    if (account.text.trim().isEmpty) {
      setState(() => error = '先填写账户标签，例如“我的微信”。同一账户以后请使用相同标签。');
      return;
    }
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final raw = await widget.controller.command(
        'pickBillFile',
        refreshAfter: false,
      );
      if (raw == null) return;
      final file = stringMap(raw);
      final parsed = BillImport.parse(
        file['text'] as String,
        widget.controller.snapshot!,
        account: account.text,
      );
      if (mounted) {
        setState(() {
          rows = parsed;
          name = file['name'] as String?;
          selected.clear();
          for (var i = 0; i < rows.length; i++) {
            if (rows[i].state == 'new') selected.add(i);
          }
        });
      }
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('账单导入与对账')),
    body: SafeArea(
      top: false,
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('在本机读取微信／支付宝 CSV，保留实际交易时间。相同交易号会跳过，相近账目由你核对。'),
                const SizedBox(height: 16),
                TextField(
                  controller: account,
                  enabled: rows.isEmpty && !busy,
                  maxLength: 100,
                  decoration: const InputDecoration(
                    labelText: '这份账单的账户',
                    hintText: '例如：我的微信',
                    counterText: '',
                  ),
                ),
                const SizedBox(height: 12),
                OutlinedButton.icon(
                  onPressed: busy ? null : choose,
                  icon: const Icon(Icons.file_open_outlined),
                  label: Text(busy ? '正在读取…' : '选择 CSV 账单'),
                ),
                if (error != null)
                  Text(
                    error!,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.error,
                    ),
                  ),
                if (rows.isNotEmpty)
                  Text(
                    '${name ?? '账单'}：${rows.length} 行，新增 ${rows.where((r) => r.state == 'new').length}，已记录 ${rows.where((r) => r.state == 'duplicate').length}，待核对 ${rows.where((r) => r.state == 'review' || r.state == 'conflict').length}。',
                  ),
                if (rows.any(
                  (row) => row.transaction?.effectiveKind == 'unknown',
                ))
                  const Text('转账的消费或借贷用途无法从文件确定。这些记录保存后进入“待确认”，请再选择用途。'),
              ],
            ),
          ),
          Expanded(
            child: ListView.builder(
              itemCount: rows.length,
              itemBuilder: (context, i) {
                final row = rows[i];
                final available =
                    row.transaction != null &&
                    row.state != 'duplicate' &&
                    row.state != 'conflict';
                return CheckboxListTile(
                  value: selected.contains(i),
                  onChanged: available
                      ? (v) => setState(() {
                          if (v == true) {
                            selected.add(i);
                          } else {
                            selected.remove(i);
                          }
                        })
                      : null,
                  title: Text(row.description),
                  subtitle: Text(
                    const {
                          'new': '可新增',
                          'duplicate': '已记录，跳过',
                          'review': '已有金额、对手和时间相近的账目；确认是另一笔才勾选',
                          'conflict': '相同交易号金额不同，请先检查原账目',
                          'unsupported': '不导入',
                        }[row.state] ??
                        row.state,
                  ),
                );
              },
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 20),
            child: SizedBox(
              width: double.infinity,
              child: FilledButton(
                onPressed: selected.isEmpty
                    ? null
                    : () {
                        widget.controller.addImportedDrafts(
                          selected.map((i) {
                            final tx = rows[i].transaction!;
                            return tx.copyWith(
                              metadataJson: jsonEncode({
                                ...tx.recognitionMetadata,
                                'reviewed': true,
                                'questions': <String>[],
                              }),
                            );
                          }).toList(),
                        );
                        Navigator.pop(context);
                      },
                child: Text('加入 ${selected.length} 笔待核对账单'),
              ),
            ),
          ),
        ],
      ),
    ),
  );
}

import 'package:flutter/material.dart';

import '../../core/ledger_controller.dart';
import '../../ui/common.dart';

class RecognitionSaveNotice extends StatelessWidget {
  const RecognitionSaveNotice({super.key, required this.controller});
  final LedgerController controller;

  @override
  Widget build(BuildContext context) {
    if (!controller.canUndoRecognitionSave) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: ListTile(
        contentPadding: EdgeInsets.zero,
        leading: const Icon(Icons.check_circle_outline),
        title: Text('已自动记下 ${controller.lastRecognitionSave!.count} 笔'),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            for (final tx in controller.lastRecognitionSave!.transactions.take(
              3,
            ))
              Text(
                '${tx.merchant?.isNotEmpty == true ? tx.merchant : controller.categoryById(tx.categoryId)?.name ?? tx.businessLabel}'
                ' · ${tx.type.label} ${money(tx.amountFen)}'
                ' · ${dateLabel(tx.occurredAt)}'
                '${const {'exact', 'approximate'}.contains(tx.recognitionMetadata['timePrecision']) ? ' ${timeLabel(tx.occurredAt)}' : ''}'
                '${tx.recognitionMetadata['timePrecision'] == 'approximate' ? '左右' : ''}',
              ),
            const Text('可在流水中修改。'),
          ],
        ),
        trailing: TextButton(
          onPressed: controller.isBusy
              ? null
              : () => runUi(
                  context,
                  () async => controller.undoLastRecognitionSave(),
                  success: '已撤销这次自动记账',
                ),
          child: const Text('撤销'),
        ),
      ),
    );
  }
}

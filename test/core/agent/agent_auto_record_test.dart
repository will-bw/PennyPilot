import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/agent/agent_models.dart';
import 'package:pennypilot/core/agent/agent_runtime.dart';
import 'package:pennypilot/core/agent/agent_store.dart';
import 'package:pennypilot/core/models.dart';
import 'package:pennypilot/core/storage/portable_ledger_store.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

AgentReply _call(String id, String name, Map<String, dynamic> args) =>
    AgentReply(
      message: {
        'role': 'assistant',
        'content': '',
        'tool_calls': [
          {
            'id': id,
            'type': 'function',
            'function': {'name': name, 'arguments': jsonEncode(args)},
          },
        ],
      },
    );
AgentReply _answer(String text) =>
    AgentReply(message: {'role': 'assistant', 'content': text});
Map<String, dynamic> _observation(List<Map<String, dynamic>> messages) =>
    stringMap(
      jsonDecode(
        messages.lastWhere((m) => m['role'] == 'tool')['content'] as String,
      ),
    );

void main() {
  final now = DateTime(2026, 10, 24, 12);
  late PortableLedgerStore ledger;
  late LedgerAgentRuntime runtime;
  var writes = 0;
  setUpAll(sqfliteFfiInit);
  setUp(() {
    ledger = PortableLedgerStore(
      databaseFactory: databaseFactoryFfi,
      databasePath: inMemoryDatabasePath,
      clock: () => now,
    );
    runtime = LedgerAgentRuntime(store: MemoryAgentStore(), clock: () => now);
    writes = 0;
  });
  tearDown(() => ledger.close());
  Future<({int id, LedgerSnapshot ledger})?> record(
    Map<String, dynamic> tx,
  ) async {
    writes++;
    final result = stringMap(
      await ledger.invoke('saveTransaction', {...tx, 'revision': 0}),
    );
    return (id: result['id'] as int, ledger: await ledger.snapshot());
  }

  Map<String, dynamic> meal(String evidence) => {
    'amount': 1700,
    'type': 'EXPENSE',
    'category_id': 1,
    'business_kind': 'expense',
    'evidence': evidence,
  };
  Future<Map<String, dynamic>> run(
    AgentComplete complete, {
    String question = '午饭17元',
    bool auto = true,
    String source = 'AI_TEXT',
  }) async => runtime.run(
    question: question,
    ledger: await ledger.snapshot(),
    model: AgentModel(identity: 'test:auto-record', complete: complete),
    guard: () async {},
    autoRecord: auto ? record : null,
    source: source,
  );

  Map<String, dynamic> aa(
    String evidence, {
    int count = 4,
    int amount = 2500,
  }) => {
    ...meal(evidence),
    'amount': amount,
    'aa_total_amount': 10000,
    'aa_participant_count': count,
  };

  test('AA advance collections preserve the collected amount', () async {
    const text = '收回AA垫付75元';
    final incomeCategory = (await ledger.snapshot()).categories.firstWhere(
      (category) => category.type == TxType.income,
    );
    var step = 0;
    final result = await run((messages, _) async {
      if (++step == 1) {
        return _call('collection', 'propose_transaction', {
          ...meal(text),
          'amount': 7500,
          'type': 'INCOME',
          'business_kind': 'receivable',
          'category_id': incomeCategory.id,
        });
      }
      expect(_observation(messages)['status'], 'applied');
      return _answer('已记录收回应收款75元。');
    }, question: text);
    expect(result['isError'], false);
    expect(writes, 1);
    final saved = (await ledger.snapshot()).transactions.single;
    expect(saved.amountFen, 7500);
    expect(saved.expenseSplit, isNull);
    expect(saved.countsAsIncome, false);
  });

  test(
    'AA saves one personal expense and statistics use only that amount',
    () async {
      var step = 0;
      final result = await run((messages, _) async {
        switch (++step) {
          case 1:
            return _call('aa', 'propose_transaction', aa('午饭100元，4人AA'));
          case 2:
            expect(_observation(messages)['status'], 'applied');
            return _call('total', 'aggregate_by', {
              'start': '2026-10-24',
              'end': '2026-10-24',
              'group_by': 'category',
            });
          default:
            expect((_observation(messages)['data'] as Map)['expense'], 2500);
            return _answer('已记个人支出25元。');
        }
      }, question: '午饭100元，4人AA');
      expect(result['isError'], false);
      expect(writes, 1);
      final saved = (await ledger.snapshot()).transactions.single;
      expect(saved.amountFen, 2500);
      expect(saved.expenseSplit?.totalAmountFen, 10000);
      expect(saved.expenseSplit?.participantCount, 4);
    },
  );

  for (final reply in ['4人', '4', '四个']) {
    test('AA count clarification preserves original total: $reply', () async {
      await run(
        (_, _) async =>
            _call('clarify', 'ask_user', {'question': '包含本人共几人 AA？'}),
        question: '午饭100元AA',
      );
      var step = 0;
      final result = await run((messages, _) async {
        if (++step == 1) return _call('aa', 'propose_transaction', aa(reply));
        expect(_observation(messages)['status'], 'applied');
        return _answer('已记个人支出25元。');
      }, question: reply);
      expect(result['isError'], false);
      expect(writes, 1);
      expect(
        (await ledger.snapshot())
            .transactions
            .single
            .expenseSplit
            ?.participantCount,
        4,
      );
    });
  }

  test('an unlinked one-digit reply cannot authorize an AA record', () async {
    var step = 0;
    final result = await run((messages, _) async {
      if (++step == 1) return _call('aa', 'propose_transaction', aa('4'));
      expect(_observation(messages)['error'], 'validation_error');
      return _answer('请补充记账内容。');
    }, question: '4');
    expect(result['isError'], false);
    expect(writes, 0);
  });

  for (final scenario in [
    (text: '午饭100元AA', args: <String, dynamic>{'amount': 10000}),
    (
      text: '午饭100元AA',
      args: <String, dynamic>{
        'amount': 2500,
        'aa_total_amount': 10000,
        'aa_participant_count': 4,
      },
    ),
    (
      text: '午饭100元4人AA',
      args: <String, dynamic>{
        'amount': 10000,
        'aa_total_amount': 10000,
        'aa_participant_count': 4,
      },
    ),
  ]) {
    test(
      'AA invalid evidence or division never auto-saves: ${scenario.text} ${scenario.args}',
      () async {
        var step = 0;
        await run((messages, _) async {
          if (++step == 1) {
            return _call('aa', 'propose_transaction', {
              ...meal(scenario.text),
              ...scenario.args,
            });
          }
          expect(_observation(messages)['error'], 'validation_error');
          return _answer('需要补充或修正分摊信息。');
        }, question: scenario.text);
        expect(writes, 0);
        expect((await ledger.snapshot()).transactions, isEmpty);
      },
    );
  }

  test(
    'mixed entries do not apply AA to an unrelated ordinary transaction',
    () async {
      var step = 0;
      await run((messages, _) async {
        switch (++step) {
          case 1:
            return _call('aa', 'propose_transaction', aa('午饭100元4人AA'));
          case 2:
            expect(_observation(messages)['status'], 'applied');
            return _call('subway', 'propose_transaction', {
              ...meal('地铁4元'),
              'amount': 400,
            });
          default:
            expect(_observation(messages)['status'], 'applied');
            return _answer('已记两笔。');
        }
      }, question: '午饭100元4人AA；地铁4元');
      expect(writes, 2);
      final saved = (await ledger.snapshot()).transactions;
      expect(saved.map((tx) => tx.amountFen), unorderedEquals([2500, 400]));
      expect(saved.where((tx) => tx.expenseSplit != null), hasLength(1));
    },
  );

  test(
    'voice recording source survives the shared agent and ledger save',
    () async {
      var turn = 0;
      await run((messages, _) async {
        if (++turn == 1) {
          return _call('voice-record', 'propose_transaction', meal('午饭17元'));
        }
        final result = _observation(messages);
        return _answer('已记下。[依据 ${result['evidence_id']}]');
      }, source: 'AI_VOICE');
      expect(
        (await ledger.snapshot()).transactions.single.source,
        TxSource.aiVoice,
      );
    },
  );

  test(
    'automatic chat entry writes the real store and later tools read the new balance',
    () async {
      var step = 0;
      final result = await run((messages, _) async {
        switch (++step) {
          case 1:
            return _call('record', 'propose_transaction', meal('午饭17元'));
          case 2:
            final saved = _observation(messages);
            expect(saved['status'], 'applied');
            expect((saved['data'] as Map)['amount'], 1700);
            return _call('total', 'aggregate_by', {
              'start': '2026-10-24',
              'end': '2026-10-24',
              'group_by': 'category',
            });
          default:
            final total = _observation(messages);
            expect((total['data'] as Map)['expense'], 1700);
            return _answer('已记17元，今天支出17元。[依据 ${total['evidence_id']}]');
        }
      });
      expect(result['isError'], false);
      expect(writes, 1);
      final saved = (await ledger.snapshot()).transactions.single;
      expect(saved.amountFen, 1700);
      expect(saved.recognitionMetadata['autoRecorded'], true);
      expect(result['autoSavedIds'], [saved.id]);
      expect(
        ((result['session'] as Map)['actions'] as List).single['status'],
        'applied',
      );
    },
  );

  test(
    'repeating the same new-entry tool call in one run is idempotent',
    () async {
      var step = 0;
      int? id;
      final result = await run((messages, _) async {
        if (++step <= 2) {
          if (step == 2) id = _observation(messages)['transaction_id'] as int;
          return _call('record-$step', 'propose_transaction', meal('午饭17元'));
        }
        final saved = _observation(messages);
        expect(saved['transaction_id'], id);
        return _answer('已记17元。[依据 ${saved['evidence_id']}]');
      });
      expect(result['isError'], false);
      expect(writes, 1);
      expect((await ledger.snapshot()).transactions, hasLength(1));
      expect((result['session'] as Map)['actions'], hasLength(1));
    },
  );

  test(
    'spoken relative date updates both stored entry and applied action title',
    () async {
      const text = '昨天晚上7点过吃盖饭17元';
      var step = 0;
      final result = await run((messages, _) async {
        if (++step == 1) {
          return _call('record', 'propose_transaction', meal(text));
        }
        final saved = _observation(messages);
        expect(saved['title'], contains('2026-10-23'));
        expect((saved['data'] as Map)['date'], startsWith('2026-10-23T19:00'));
        return _answer('已记昨晚盖饭17元。[依据 ${saved['evidence_id']}]');
      }, question: text);
      expect(result['isError'], false);
      expect(
        (await ledger.snapshot()).transactions.single.occurredAt,
        DateTime(2026, 10, 23, 19),
      );
    },
  );

  test('disabling automatic saving keeps the pending proposal flow', () async {
    var step = 0;
    final result = await run(
      (_, _) async => ++step == 1
          ? _call('record', 'propose_transaction', meal('午饭17元'))
          : _answer('已准备待核对的账单。'),
      auto: false,
    );
    expect(result['isError'], false);
    expect(writes, 0);
    expect((await ledger.snapshot()).transactions, isEmpty);
    expect((result['session'] as Map)['status'], 'awaiting_confirmation');
  });

  for (final scenario in [
    (original: '昨天吃了午饭', reply: '17元', clarification: '午饭花了多少元？'),
    (original: '午饭17元', reply: '昨天', clarification: '这笔午饭是哪一天？'),
  ]) {
    test(
      'clarification preserves date and amount across user turns: ${scenario.reply}',
      () async {
        final first = await run(
          (_, _) async => _call('clarify', 'ask_user', {
            'question': scenario.clarification,
          }),
          question: scenario.original,
        );
        expect((first['session'] as Map)['status'], 'awaiting_input');
        var step = 0;
        final result = await run((messages, _) async {
          if (++step == 1) {
            return _call('record', 'propose_transaction', {
              ...meal(scenario.reply),
              'date': '2026-10-23',
            });
          }
          final saved = _observation(messages);
          expect(saved['status'], 'applied');
          return _answer('已记昨天午饭17元。[依据 ${saved['evidence_id']}]');
        }, question: scenario.reply);
        expect(result['isError'], false);
        expect(writes, 1);
        final saved = (await ledger.snapshot()).transactions.single;
        expect(saved.amountFen, 1700);
        expect(saved.occurredAt, DateTime(2026, 10, 23));
      },
    );
  }

  test('multiple clarification replies preserve the original task', () async {
    await run(
      (_, _) async =>
          _call('clarify-amount', 'ask_user', {'question': '午饭花了多少元？'}),
      question: '昨天吃了午饭',
    );
    await run(
      (_, _) async =>
          _call('clarify-account', 'ask_user', {'question': '使用哪个账户支付？'}),
      question: '17元',
    );
    var step = 0;
    final result = await run((messages, _) async {
      if (++step == 1) {
        return _call('record', 'propose_transaction', {
          ...meal('支付宝'),
          'date': '2026-10-23',
          'account_tag': '支付宝',
        });
      }
      final saved = _observation(messages);
      expect(saved['status'], 'applied');
      return _answer('已记昨天午饭17元。[依据 ${saved['evidence_id']}]');
    }, question: '支付宝');
    expect(result['isError'], false);
    expect(writes, 1);
    expect(
      (await ledger.snapshot()).transactions.single.occurredAt,
      DateTime(2026, 10, 23),
    );
  });

  test(
    'clarifying a second entry does not save the completed first entry again',
    () async {
      var step = 0;
      int? mealId;
      final first = await run((messages, _) async {
        if (++step == 1) {
          return _call('meal', 'propose_transaction', meal('午饭17元'));
        }
        mealId = _observation(messages)['transaction_id'] as int;
        return _call('clarify', 'ask_user', {'question': '地铁花了多少元？'});
      }, question: '午饭17元，地铁多少钱忘了');
      expect((first['session'] as Map)['status'], 'awaiting_input');
      expect(writes, 1);
      step = 0;
      final result = await run((messages, _) async {
        switch (++step) {
          case 1:
            return _call('repeated-meal', 'propose_transaction', meal('4元'));
          case 2:
            final previous = _observation(messages);
            expect(previous['transaction_id'], mealId);
            expect(previous['instruction'], contains('未重复新增'));
            expect(writes, 1);
            return _call('subway', 'propose_transaction', {
              ...meal('4元'),
              'amount': 400,
            });
          default:
            final saved = _observation(messages);
            expect((saved['data'] as Map)['amount'], 400);
            return _answer('已补记地铁4元。[依据 ${saved['evidence_id']}]');
        }
      }, question: '4元');
      expect(result['isError'], false);
      expect(writes, 2);
      expect(
        (await ledger.snapshot()).transactions.map((tx) => tx.amountFen),
        unorderedEquals([1700, 400]),
      );
    },
  );

  test(
    'equal amounts in different user turns remain separate transactions',
    () async {
      var step = 0;
      await run((messages, _) async {
        if (++step == 1) {
          return _call('meal', 'propose_transaction', {
            ...meal('午饭17元'),
            'merchant': '午饭',
          });
        }
        return _call('clarify', 'ask_user', {'question': '地铁花了多少元？'});
      }, question: '午饭17元，地铁多少钱忘了');
      step = 0;
      final result = await run((messages, _) async {
        if (++step == 1) {
          return _call('subway', 'propose_transaction', {
            ...meal('17元'),
            'merchant': '地铁',
          });
        }
        final saved = _observation(messages);
        return _answer('已补记地铁17元。[依据 ${saved['evidence_id']}]');
      }, question: '17元');
      expect(result['isError'], false);
      expect(writes, 2);
      final transactions = (await ledger.snapshot()).transactions;
      expect(transactions.map((tx) => tx.amountFen), [1700, 1700]);
      expect(
        transactions.map((tx) => tx.merchant),
        unorderedEquals(['午饭', '地铁']),
      );
      expect(
        transactions.map((tx) => tx.recognitionMetadata['operationId']).toSet(),
        hasLength(2),
      );
    },
  );

  test(
    'completed tasks cannot supply evidence for a later new entry',
    () async {
      var step = 0;
      await run((messages, _) async {
        if (++step == 1) {
          return _call('original', 'propose_transaction', meal('午饭17元'));
        }
        final saved = _observation(messages);
        return _answer('已记17元。[依据 ${saved['evidence_id']}]');
      });
      step = 0;
      final result = await run((messages, _) async {
        if (++step == 1) {
          return _call('unrelated', 'propose_transaction', meal('昨天'));
        }
        expect(_observation(messages)['error'], 'validation_error');
        return _answer('本次没有新增账目。');
      }, question: '昨天');
      expect(result['isError'], false);
      expect(writes, 1);
      expect((await ledger.snapshot()).transactions, hasLength(1));
    },
  );

  test('clarifying a query does not authorize a new transaction', () async {
    await run(
      (_, _) async => _call('clarify', 'ask_user', {'question': '要查哪一天的午饭？'}),
      question: '查一下午饭17元',
    );
    var step = 0;
    final result = await run((messages, _) async {
      if (++step == 1) {
        return _call('record', 'propose_transaction', meal('昨天'));
      }
      expect(_observation(messages)['error'], 'validation_error');
      return _answer('本次没有新增账目。');
    }, question: '昨天');
    expect(result['isError'], false);
    expect(writes, 0);
    expect((await ledger.snapshot()).transactions, isEmpty);
  });

  for (final text in [
    '明天准备午饭17元',
    '午饭17元还没支付',
    '看看今天午饭17元吗',
    '午饭18元',
    '10月17号午饭100元',
  ]) {
    test(
      'unpaid, future, query or fabricated amount cannot auto-record: $text',
      () async {
        var step = 0;
        final result = await run((messages, _) async {
          if (++step == 1) {
            return _call('record', 'propose_transaction', meal(text));
          }
          expect(_observation(messages)['error'], 'validation_error');
          return _answer('本次没有新增账目。');
        }, question: text);
        expect(result['isError'], false);
        expect(writes, 0);
        expect((await ledger.snapshot()).transactions, isEmpty);
        expect(
          ((result['session'] as Map)['actions'] as List).single['status'],
          'invalid',
        );
      },
    );
  }

  test(
    'successful writes remain reported if the next model request fails',
    () async {
      var step = 0;
      final result = await run((_, _) async {
        if (++step == 1) {
          return _call('record', 'propose_transaction', meal('午饭17元'));
        }
        throw StateError('offline');
      });
      expect(result['isError'], true);
      expect(writes, 1);
      expect(result['autoSavedIds'], [
        (await ledger.snapshot()).transactions.single.id,
      ]);
    },
  );

  test(
    'edit delete and budget proposals never use the automatic new-entry callback',
    () async {
      await ledger.invoke('saveTransaction', {
        'revision': 0,
        'amount': 1700,
        'type': 'EXPENSE',
        'categoryId': 1,
        'source': 'MANUAL',
        'businessKind': 'expense',
        'occurredAt': now.millisecondsSinceEpoch,
      });
      var step = 0;
      final result = await run((messages, _) async {
        switch (++step) {
          case 1:
            return _call('read', 'get_transaction', {'transaction_id': 1});
          case 2:
            return _call('edit', 'propose_transaction', {
              ...meal('改成20元'),
              'transaction_id': 1,
              'amount': 2000,
            });
          case 3:
            return _call('delete', 'propose_delete_transaction', {
              'transaction_id': 1,
              'evidence': '删除第一笔',
            });
          case 4:
            return _call('budget', 'propose_budget', {
              'amount': 300000,
              'evidence': '预算3000元',
            });
          default:
            return _answer('三项修改等待核对。[依据 Q1]');
        }
      }, question: '第一笔改成20元，再删除第一笔，预算3000元');
      expect(result['isError'], false);
      expect(writes, 0);
      expect((await ledger.snapshot()).transactions.single.amountFen, 1700);
      expect(
        ((result['session'] as Map)['actions'] as List).map((a) => a['status']),
        everyElement('pending'),
      );
    },
  );
}

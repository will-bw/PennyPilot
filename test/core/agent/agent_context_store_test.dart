import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:pennypilot/core/agent/agent_context.dart';
import 'package:pennypilot/core/agent/agent_models.dart';
import 'package:pennypilot/core/agent/agent_runtime.dart';
import 'package:pennypilot/core/agent/agent_store.dart';
import 'package:pennypilot/core/agent/ledger_agent_tools.dart';
import 'package:pennypilot/core/ledger_repository.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'SQLite checkpoints survive close/reopen with complete transcript and active session',
    () async {
      sqfliteFfiInit();
      final directory = await Directory.systemTemp.createTemp(
        'pennypilot-agent-',
      );
      addTearDown(() => directory.delete(recursive: true));
      final path = '${directory.path}/agent.sqlite';
      var store = SqliteAgentStore(
        factory: databaseFactoryFfi,
        databasePath: path,
      );
      var runtime = LedgerAgentRuntime(store: store);
      final first = await runtime.active(3);
      first.messages.add({'role': 'user', 'content': '跨进程保留'});
      await store.write('session:3:${first.id}', first.toMap());
      await store.write('memory:3:goal', {
        'key': 'goal',
        'content': '储蓄',
        'updatedAt': 1,
      });
      await store.close();
      store = SqliteAgentStore(factory: databaseFactoryFfi, databasePath: path);
      addTearDown(store.close);
      runtime = LedgerAgentRuntime(store: store);
      final state = await runtime.state(3);
      expect((state['session'] as Map)['id'], first.id);
      expect(
        ((state['session'] as Map)['messages'] as List).single['content'],
        '跨进程保留',
      );
      expect(state['memories'], hasLength(1));
      final second = await runtime.newSession(3);
      final secondId = (second['session'] as Map)['id'];
      expect(secondId, isNot(first.id));
      expect((await runtime.select(3, first.id))['sessions'], hasLength(2));
      final deleted = await runtime.deleteSession(3, first.id);
      expect((deleted['session'] as Map)['id'], isNot(first.id));
      expect(deleted['memories'], hasLength(1));
    },
  );

  test(
    'compaction preserves current user/tool span, source transcript and iterative summary',
    () async {
      final session = AgentSession.create(0, DateTime(2026, 10, 24));
      for (var i = 0; i < 8; i++) {
        session.messages.add({
          'role': 'user',
          'content': '目标$i ${'旧'.padRight(1600, '旧')}',
        });
        session.messages.add({
          'role': 'assistant',
          'content': '回答$i',
          'responsesItems': [
            {'type': 'reasoning', 'encrypted_content': 'DO_NOT_SUMMARIZE'},
          ],
        });
      }
      session.messages.add({'role': 'user', 'content': '继续检查上次目标'});
      session.messages.add({
        'role': 'assistant',
        'content': '',
        'tool_calls': [
          {
            'id': 'active-call',
            'type': 'function',
            'function': {'name': 'get_budget', 'arguments': '{}'},
          },
        ],
        'responsesItems': [
          {'type': 'reasoning', 'encrypted_content': 'ACTIVE_REASONING'},
        ],
      });
      session.messages.add({
        'role': 'tool',
        'tool_call_id': 'active-call',
        'content': '{"budget":100000}',
      });
      final original = jsonEncode(session.messages);
      var summaries = 0;
      await AgentContext.compact(
        session: session,
        config: const AgentConfig(contextTokens: 8000),
        system: '系统',
        memories: [],
        tools: LedgerAgentTools.definitions,
        guard: () async {},
        progress: (_) {},
        model: AgentModel(
          identity: 'test',
          complete: (messages, tools) async {
            summaries++;
            expect(jsonEncode(messages), isNot(contains('DO_NOT_SUMMARIZE')));
            expect(tools, isEmpty);
            return const AgentReply(
              message: {'content': '目标：比较支出。原文可检索；历史数字须重新查询。'},
            );
          },
        ),
      );
      expect(summaries, greaterThan(0));
      expect(session.contextStart, greaterThan(0));
      expect(jsonEncode(session.messages), original);
      final projection = AgentContext.project(session, '系统', []);
      expect(projection.last['tool_call_id'], 'active-call');
      expect(projection[projection.length - 2]['responsesItems'], isNotNull);
      expect(
        projection.any((m) => (m['content'] as String? ?? '').contains('更早会话')),
        true,
      );
      expect(session.estimatedTokens, lessThan(6001));
    },
  );

  test(
    'an oversized active turn fails without splitting tool calls or truncating original data',
    () async {
      final session = AgentSession.create(0, DateTime.now());
      session.messages.add({'role': 'user', 'content': '新任务'});
      session.messages.add({
        'role': 'tool',
        'tool_call_id': 'one',
        'content': '中'.padRight(10000, '中'),
      });
      final original = jsonEncode(session.messages);
      await expectLater(
        AgentContext.compact(
          session: session,
          model: AgentModel(
            identity: 'test',
            complete: (_, _) async => throw StateError('must not call'),
          ),
          config: const AgentConfig(contextTokens: 8000),
          system: '系统',
          memories: [],
          tools: [],
          guard: () async {},
          progress: (_) {},
        ),
        throwsA(isA<LedgerException>()),
      );
      expect(jsonEncode(session.messages), original);
      expect(session.contextStart, 0);
    },
  );

  test(
    'failed compaction keeps summary boundary intact and reports failure',
    () async {
      final session = AgentSession.create(0, DateTime.now());
      session.messages.add({
        'role': 'user',
        'content': '中'.padRight(10000, '中'),
      });
      session.messages.add({'role': 'assistant', 'content': '旧回答'});
      session.messages.add({'role': 'user', 'content': '新任务'});
      await expectLater(
        AgentContext.compact(
          session: session,
          model: AgentModel(
            identity: 'test',
            complete: (_, _) async =>
                const AgentReply(message: {'content': ''}),
          ),
          config: const AgentConfig(contextTokens: 8000),
          system: '系统',
          memories: [],
          tools: [],
          guard: () async {},
          progress: (_) {},
        ),
        throwsA(isA<LedgerException>()),
      );
      expect(session.contextStart, 0);
      expect(session.summary, isEmpty);
    },
  );
}

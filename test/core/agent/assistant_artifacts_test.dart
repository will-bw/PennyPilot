import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/agent/agent_models.dart';
import 'package:pennypilot/core/agent/agent_store.dart';
import 'package:pennypilot/core/agent/assistant_artifacts.dart';
import 'package:pennypilot/core/agent/ledger_agent_tools.dart';
import 'package:pennypilot/core/models.dart';
import 'package:xml/xml.dart';

void main() {
  test('SVG retains editable vector geometry and internal gradient references', () {
    const svg =
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 300 120">'
        '<defs><linearGradient id="paint"><stop offset="0" stop-color="#4d6b85"/>'
        '</linearGradient></defs><rect width="100" height="40" fill="url(#paint)"/>'
        '<text x="8" y="70">餐饮</text></svg>';
    expect(checkedAssistantSvg(svg), contains('url(#paint)'));
    expect(checkedAssistantSvg(svg), contains('餐饮'));
  });

  test('SVG rejects executable content, namespaces and external resources', () {
    for (final content in [
      '<svg><script>alert(1)</script></svg>',
      '<svg><foreignObject><p>test</p></foreignObject></svg>',
      '<svg onload="alert(1)"></svg>',
      '<svg><style>path{fill:red}</style></svg>',
      '<svg><use href="https://example.com/tracker"/></svg>',
      '<svg><path fill="url(https://example.com/image)"/></svg>',
      '<svg xmlns="https://example.com/custom"/>',
      '<!DOCTYPE svg [<!ENTITY x SYSTEM "file:///private/data">]><svg>&x;</svg>',
    ]) {
      expect(
        () => checkedAssistantSvg(content),
        throwsFormatException,
        reason: content,
      );
    }
  });

  test(
    'HTML sandbox uses opaque origin, restrictive policies and escaped srcdoc',
    () {
      final document = assistantSandboxDocument(
        '<button onclick="document.body.append(\'ok\')">测试</button>'
        '<script>fetch("https://example.com");</script>',
        data: {'merchant': '</script><script>stolen()</script>'},
      );
      expect(document, contains('sandbox="allow-scripts"'));
      expect(document, isNot(contains('allow-same-origin')));
      expect(document, contains("connect-src 'none'"));
      expect(document, contains("form-action 'none'"));
      expect(document, contains("worker-src 'none'"));
      expect(document, contains('clipboard-read'));
      expect(document, isNot(contains('<button')));
      final encodedSrcdoc = RegExp(
        r'srcdoc="([^"]*)"',
      ).firstMatch(document)!.group(1)!;
      final srcdoc = XmlDocument.parse(
        '<frame srcdoc="$encodedSrcdoc"/>',
      ).rootElement.getAttribute('srcdoc')!;
      expect(srcdoc, contains(r'\u003c/script>'));
      expect(srcdoc, isNot(contains('"merchant":"</script>')));
      expect(document, isNot(contains('addJavaScriptChannel')));
      // The outer policy must permit inline scripts too: srcdoc inherits CSP.
      expect(document, contains("script-src 'unsafe-inline'"));
    },
  );

  test('artifact navigation accepts only internal blank documents', () {
    expect(assistantArtifactNavigationAllowed('about:blank'), isTrue);
    expect(assistantArtifactNavigationAllowed('about:srcdoc'), isTrue);
    for (final url in [
      'https://example.com',
      'http://localhost:1234',
      'file:///etc/passwd',
      'javascript:alert(1)',
      'intent://example',
      'data:text/html,test',
      'about:blank?data=private',
    ]) {
      expect(assistantArtifactNavigationAllowed(url), isFalse, reason: url);
    }
  });

  test(
    'explicit app appearance fixes both HTML layers while exports follow system',
    () {
      for (final dark in [true, false]) {
        final document = assistantSandboxDocument('<p>内容</p>', dark: dark);
        final encoded = RegExp(
          r'srcdoc="([^"]*)"',
        ).firstMatch(document)!.group(1)!;
        final inner = XmlDocument.parse(
          '<frame srcdoc="$encoded"/>',
        ).rootElement.getAttribute('srcdoc')!;
        final expected = dark
            ? 'color-scheme:dark;background:#191d21;color:#ebedeb'
            : 'color-scheme:light;background:#fcfcfa;color:#303940';
        expect(document, contains(expected));
        expect(inner, contains(expected));
        expect(document, isNot(contains('prefers-color-scheme')));
      }
      final exported = assistantSandboxDocument('<p>内容</p>');
      expect(exported, contains('color-scheme:light dark'));
      expect(exported, contains('@media(prefers-color-scheme:dark)'));
    },
  );

  final now = DateTime(2026, 11, 1);
  final ledger = LedgerSnapshot(
    revision: 3,
    categories: const [LedgerCategory(id: 1, name: '餐饮', type: TxType.expense)],
    transactions: [
      LedgerTransaction(
        id: 1,
        amountFen: 10000,
        type: TxType.expense,
        categoryId: 1,
        occurredAt: DateTime(2026, 10, 3),
        businessKind: 'expense',
      ),
      LedgerTransaction(
        id: 2,
        amountFen: 2000,
        type: TxType.income,
        categoryId: 1,
        occurredAt: DateTime(2026, 10, 4),
        businessKind: 'refund',
      ),
      LedgerTransaction(
        id: 3,
        amountFen: 50000,
        type: TxType.expense,
        categoryId: 1,
        occurredAt: DateTime(2026, 10, 5),
        businessKind: 'transfer',
      ),
    ],
    settings: {'budgetCents': 20000},
  );
  LedgerAgentTools tools() => LedgerAgentTools(
    store: MemoryAgentStore(),
    session: AgentSession.create(3, now),
    ledger: ledger,
    question: '显示我的支出图表和交互报告',
    now: now,
  );

  test(
    'chart takes amounts directly from current-run accounting evidence',
    () async {
      final t = tools();
      final evidence = await t.execute('aggregate_by', {
        'start': '2026-10-01',
        'end': '2026-10-31',
        'group_by': 'category',
      });
      final result = await t.execute('present_insight', {
        'evidence_id': evidence['evidence_id'],
        'title': '十月消费',
        'chart_type': 'bar',
      });
      final spec = result['presentation'] as Map;
      expect(spec['type'], 'chart');
      expect(spec['ledgerRevision'], 3);
      expect(spec['evidenceId'], evidence['evidence_id']);
      expect(spec['data'], evidence['data']);
      expect((spec['data'] as Map)['expense'], 8000);
      expect(spec['query'], containsPair('start', '2026-10-01'));
      await expectLater(
        t.execute('present_insight', {
          'evidence_id': 'Q999',
          'title': '伪造图表',
          'chart_type': 'bar',
        }),
        throwsFormatException,
      );
      await expectLater(
        t.execute('present_insight', {
          'evidence_id': evidence['evidence_id'],
          'title': '伪造金额',
          'chart_type': 'bar',
          'values': [999999],
        }),
        throwsFormatException,
      );
      final freshRun = tools();
      await expectLater(
        freshRun.execute('present_insight', {
          'evidence_id': evidence['evidence_id'],
          'title': '历史编号',
          'chart_type': 'bar',
        }),
        throwsFormatException,
      );
    },
  );

  test(
    'pie charts reject incomplete groups and negative net expenses',
    () async {
      final t = tools();
      final firstPage = await t.execute('aggregate_by', {
        'start': '2026-10-01',
        'end': '2026-10-31',
        'group_by': 'day',
        'limit': 1,
      });
      await expectLater(
        t.execute('present_insight', {
          'evidence_id': firstPage['evidence_id'],
          'title': '不完整占比',
          'chart_type': 'pie',
        }),
        throwsFormatException,
      );
      final refundDay = await t.execute('aggregate_by', {
        'start': '2026-10-04',
        'end': '2026-10-04',
        'group_by': 'day',
      });
      await expectLater(
        t.execute('present_insight', {
          'evidence_id': refundDay['evidence_id'],
          'title': '退款占比',
          'chart_type': 'pie',
        }),
        throwsFormatException,
      );
    },
  );

  test(
    'interactive templates persist source snapshot and never change budget',
    () async {
      final t = tools();
      final evidence = await t.execute('get_budget', {'month': '2026-10'});
      final result = await t.execute('render_artifact', {
        'format': 'html',
        'title': '预算模拟',
        'template': 'budget_simulator',
        'evidence_id': evidence['evidence_id'],
      });
      final spec = result['presentation'] as Map;
      expect(spec['type'], 'artifact');
      expect(spec['data'], evidence['data']);
      expect(spec['content'], contains('window.pennyData'));
      expect(spec['content'], contains('addEventListener'));
      expect(spec['content'], contains('仅为模拟'));
      expect(ledger.settings['budgetCents'], 20000);
      expect(
        jsonDecode(jsonEncode(spec)),
        containsPair('evidenceId', evidence['evidence_id']),
      );
      await expectLater(
        t.execute('render_artifact', {
          'format': 'html',
          'title': '伪造报告',
          'template': 'report',
          'evidence_id': evidence['evidence_id'],
        }),
        throwsFormatException,
      );
    },
  );

  test(
    'custom artifacts accept complete HTML and safe SVG, reject oversized payloads',
    () async {
      final t = tools();
      final result = await t.execute('render_artifact', {
        'format': 'html',
        'title': '说明',
        'template': 'custom',
        'content': '<button onclick="this.textContent=\'已点\'">点一下</button>',
      });
      expect((result['presentation'] as Map)['content'], contains('onclick'));
      await expectLater(
        t.execute('render_artifact', {
          'format': 'svg',
          'title': '不安全SVG',
          'template': 'custom',
          'content': '<svg onload="alert(1)"/>',
        }),
        throwsFormatException,
      );
      await expectLater(
        t.execute('render_artifact', {
          'format': 'html',
          'title': '内容过大',
          'template': 'custom',
          'content': 'a' * (maxArtifactCharacters + 1),
        }),
        throwsFormatException,
      );
    },
  );
}

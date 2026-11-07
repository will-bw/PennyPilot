import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:pennypilot/core/agent/agent_store.dart';
import 'package:pennypilot/core/ai/shared_ai_service.dart';
import 'package:pennypilot/core/cross_platform_repository.dart';
import 'package:pennypilot/core/ledger_controller.dart';
import 'package:pennypilot/core/ledger_repository.dart';
import 'package:pennypilot/core/models.dart';
import 'package:pennypilot/core/platform/mobile_platform_services.dart';
import 'package:pennypilot/core/storage/assistant_attachment_store.dart';
import 'package:pennypilot/core/storage/portable_ledger_store.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

class _Credentials implements SharedCredentials {
  final values = <String, String>{};
  @override
  Future<String?> get(String key) async => values[key];
  @override
  Future<void> set(String key, String value) async => values[key] = value;
  @override
  Future<void> delete(String key) async => values.remove(key);
}

class _OfflineClient extends http.BaseClient {
  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) async =>
      throw StateError(
        'The recognition recovery test must not access the network.',
      );
}

class _Platform extends MobilePlatformServices {
  @override
  Future<Map<String, dynamic>> capabilities() async => {'platform': 'ios'};
  @override
  Stream<String> get partialTranscripts => const Stream.empty();
  @override
  Future<void> dispose() async {}
}

class _Attachments extends AssistantAttachmentStore {
  static const saved = {
    'type': 'attachment',
    'path': '/test-memory/receipt.png',
    'name': '凭证图片',
    'mimeType': 'image/png',
  };
  Map<String, dynamic>? image;

  @override
  Future<Map<String, dynamic>> save(Map<String, dynamic> attachment) async {
    image = Map.of(attachment);
    return Map.of(saved);
  }

  @override
  Future<Map<String, dynamic>> read(Map<String, dynamic> attachment) async {
    expect(attachment, saved);
    return Map.of(image!);
  }
}

// The fixture owns the real in-memory database across repository restarts.
// Every read, save and undo still executes PortableLedgerStore's transactions.
class _BorrowedLedger implements LedgerRepository {
  _BorrowedLedger(this.store);
  final PortableLedgerStore store;
  @override
  Stream<LedgerEvent> get events => store.events;
  @override
  Future<LedgerSnapshot> snapshot() => store.snapshot();
  @override
  Future<Object?> invoke(String method, Map<String, dynamic> arguments) =>
      store.invoke(method, arguments);
}

class _RecognitionAi extends SharedAiService {
  _RecognitionAi(SharedCredentials credentials, this.now)
    : super(credentials: credentials, client: _OfflineClient(), now: () => now);

  final DateTime now;
  final requests = <Map<String, dynamic>>[];

  @override
  Future<Map<String, dynamic>> recognizeInput(
    Map<String, dynamic> arguments,
    LedgerSnapshot snapshot, {
    Future<String> Function(List<Map<String, dynamic>>, String)? nativeChat,
  }) async {
    requests.add(Map.of(arguments));
    final first = requests.length == 1;
    final runId = arguments['operationId'] as String;
    expect(arguments['source'], 'AI_IMAGE');
    expect(arguments['imageBase64'], 'AQID');
    Map<String, dynamic> row(
      String suffix,
      String merchant,
      int amount, {
      bool eligible = true,
    }) => LedgerTransaction(
      amountFen: amount,
      type: TxType.expense,
      categoryId: snapshot.categories
          .firstWhere(
            (category) =>
                category.name == (suffix == 'meal' ? '餐饮' : '交通') &&
                category.type == TxType.expense,
          )
          .id,
      merchant: merchant,
      occurredAt: now,
      source: TxSource.aiImage,
      businessKind: eligible ? 'expense' : 'unknown',
      metadataJson: jsonEncode({
        'operationId': '$runId:$suffix',
        'autoSaveEligible': eligible,
      }),
    ).toMap();
    return {
      'transactions': [
        row('meal', '午餐', 1700),
        row('metro', '地铁', 300, eligible: !first),
      ],
      'questions': first ? ['这笔3元属于消费还是转账？'] : <String>[],
      'autoSaveEligible': !first,
    };
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(sqfliteFfiInit);

  test(
    'local image draft survives restart, never calls AI, and needs a selected date',
    () async {
      final now = DateTime(2026, 11, 7, 12);
      final store = PortableLedgerStore(
        databaseFactory: databaseFactoryFfi,
        databasePath: inMemoryDatabasePath,
        clock: () => now,
      );
      await store.invoke('updateSettings', {
        'activeInputAutoSaveEnabled': true,
        'cloudImageParsingEnabled': false,
      });
      final agentStore = MemoryAgentStore();
      final credentials = _Credentials();
      final attachments = _Attachments();
      final aiInstances = <_RecognitionAi>[];
      late CrossPlatformLedgerRepository repository;
      late LedgerController controller;
      Future<void> open() async {
        final ai = _RecognitionAi(credentials, now);
        aiInstances.add(ai);
        repository = CrossPlatformLedgerRepository(
          store: _BorrowedLedger(store),
          agentStore: agentStore,
          credentials: credentials,
          ai: ai,
          platformServices: _Platform(),
          android: false,
        );
        controller = LedgerController(
          repository: repository,
          attachmentStore: attachments,
          clock: () => now,
        );
        await controller.init();
        await controller.loadAgentState();
      }

      await open();
      addTearDown(() async {
        controller.dispose();
        await repository.close();
        await store.close();
      });
      final receipt = <String, dynamic>{
        'amount': 1800,
        'type': 'EXPENSE',
        'merchant': '测试餐馆',
        'businessKind': 'expense',
        'metadata': {
          'operationId': 'local-receipt:test',
          'receiptInput': 'local_image',
        },
      };
      await controller.addLocalReceiptDraft(receipt);
      expect((await store.snapshot()).transactions, isEmpty);
      expect(controller.drafts.single.status, TxStatus.pending);
      expect(controller.pendingRecognition, isTrue);
      expect(controller.canSupplementRecognition, isFalse);
      expect(attachments.image, isNull);
      controller.dispose();
      await repository.close();
      await open();
      expect(controller.error, isNull);
      expect(controller.drafts, hasLength(1));
      final restored = controller.drafts.single;
      expect(restored.recognitionMetadata['receiptDateUnconfirmed'], isTrue);
      expect(restored.status, TxStatus.pending);
      expect(controller.canSupplementRecognition, isFalse);
      final state = controller.messages
          .expand((message) => message.parts)
          .singleWhere((part) => part['type'] == 'recognition_state');
      expect(state['localOnly'], isTrue);
      expect(state['attachments'], isEmpty);
      await expectLater(
        controller.sendAssistantInput('这笔是午餐', isSupplement: true),
        throwsA(isA<LedgerException>()),
      );
      await expectLater(
        controller.addLocalReceiptDraft(receipt),
        throwsA(isA<LedgerException>()),
      );
      controller.updateDraft(0, restored.copyWith(merchant: '编辑后的测试餐馆'));
      await expectLater(
        controller.saveDrafts(),
        throwsA(isA<LedgerException>()),
      );
      expect((await store.snapshot()).transactions, isEmpty);
      final reviewed = controller.drafts.single;
      final actualDate = DateTime(2026, 10, 30);
      controller.updateDraft(
        0,
        reviewed.copyWith(
          occurredAt: actualDate,
          metadataJson: jsonEncode({
            ...reviewed.recognitionMetadata,
            'receiptDateUnconfirmed': false,
          }),
        ),
      );
      expect(await controller.saveDrafts(), 1);
      expect(
        (await store.snapshot()).transactions.single.occurredAt,
        actualDate,
      );
      expect(aiInstances.expand((ai) => ai.requests), isEmpty);
    },
  );

  test(
    'an undone image record stays deleted after supplementing the same live run and reopening the controller',
    () async {
      final now = DateTime(2026, 11, 1, 12);
      final ledger = PortableLedgerStore(
        databaseFactory: databaseFactoryFfi,
        databasePath: inMemoryDatabasePath,
        clock: () => now,
      );
      await ledger.invoke('updateSettings', {
        'activeInputAutoSaveEnabled': true,
        'cloudImageParsingEnabled': true,
      });
      final borrowedLedger = _BorrowedLedger(ledger);
      final agentStore = MemoryAgentStore();
      final credentials = _Credentials();
      final attachments = _Attachments();
      LedgerController? currentController;
      CrossPlatformLedgerRepository? currentRepository;
      addTearDown(() async {
        await currentRepository?.close();
        currentController?.dispose();
        await ledger.close();
      });

      final ai = _RecognitionAi(credentials, now);
      final repository = CrossPlatformLedgerRepository(
        store: borrowedLedger,
        agentStore: agentStore,
        credentials: credentials,
        ai: ai,
        platformServices: _Platform(),
        android: false,
      );
      currentRepository = repository;
      final controller = LedgerController(
        repository: repository,
        attachmentStore: attachments,
        clock: () => now,
      );
      currentController = controller;
      await controller.init();
      expect(controller.error, isNull);
      await controller.sendAssistantInput(
        '识别午餐和地铁这两笔',
        attachment: {'imageBase64': 'AQID', 'mimeType': 'image/png'},
      );

      final runId = controller.messages
          .firstWhere((message) => message.role == 'user')
          .runId;
      final mealOperation = '$runId:meal', metroOperation = '$runId:metro';
      final first = (await ledger.snapshot()).transactions.single;
      expect(first.amountFen, 1700);
      expect(first.source, TxSource.aiImage);
      expect(first.recognitionMetadata['operationId'], mealOperation);
      expect(first.recognitionMetadata['requestSessionId'], runId);
      expect(controller.pendingRecognition, true);
      expect(
        controller.drafts.single.recognitionMetadata['operationId'],
        metroOperation,
      );
      expect(
        controller.drafts.single.recognitionMetadata['requestSessionId'],
        runId,
      );
      expect(controller.drafts.single.source, TxSource.aiImage);
      expect(controller.canUndoAssistantRun(runId), true);

      expect(await controller.undoAssistantRun(runId), 1);
      expect((await ledger.snapshot()).transactions, isEmpty);
      expect(controller.pendingRecognition, true);
      final afterUndo = await repository.agentState();
      final undoneParts = ((afterUndo['session'] as Map)['messages'] as List)
          .expand((message) => message['parts'] as List? ?? const [])
          .whereType<Map>();
      expect(
        undoneParts.singleWhere(
          (part) => part['type'] == 'recognition_state',
        )['undoneOperations'],
        contains(mealOperation),
      );

      // Keep this controller alive: a restart here would mask the in-memory
      // tombstone bug by restoring the correctly persisted recognition state.
      await controller.sendAssistantInput(
        '地铁这笔是交通支出，用支付宝付的',
        isSupplement: true,
      );
      expect(ai.requests, hasLength(2));
      expect(ai.requests.last['operationId'], ai.requests.first['operationId']);
      expect(ai.requests.last['text'], contains('识别午餐和地铁这两笔'));
      expect(ai.requests.last['text'], contains('地铁这笔是交通支出'));
      final remaining = (await ledger.snapshot()).transactions.single;
      expect(remaining.amountFen, 300);
      expect(remaining.merchant, '地铁');
      expect(remaining.source, TxSource.aiImage);
      expect(remaining.recognitionMetadata['operationId'], metroOperation);
      expect(remaining.recognitionMetadata['requestSessionId'], runId);
      expect(controller.drafts, isEmpty);
      expect(controller.pendingRecognition, false);
      final sessionId = controller.agentSession['id'];

      await repository.close();
      controller.dispose();
      currentController = null;
      currentRepository = null;
      final reopenedAi = _RecognitionAi(credentials, now);
      final reopenedRepository = CrossPlatformLedgerRepository(
        store: borrowedLedger,
        agentStore: agentStore,
        credentials: credentials,
        ai: reopenedAi,
        platformServices: _Platform(),
        android: false,
      );
      currentRepository = reopenedRepository;
      final reopened = LedgerController(
        repository: reopenedRepository,
        attachmentStore: attachments,
        clock: () => now,
      );
      currentController = reopened;
      await reopened.init();
      expect(reopened.error, isNull);
      expect(reopened.agentSession['id'], sessionId);
      expect(reopened.transactions.single.id, remaining.id);
      expect(
        reopened.transactions.single.recognitionMetadata['operationId'],
        metroOperation,
      );
      expect(reopened.drafts, isEmpty);
      expect(reopened.pendingRecognition, false);
      expect(reopenedAi.requests, isEmpty);
      final persistedParts = reopened.messages
          .expand((message) => message.parts)
          .toList();
      expect(
        persistedParts.singleWhere(
          (part) => part['type'] == 'attachment',
        )['path'],
        _Attachments.saved['path'],
      );
      final state = persistedParts.singleWhere(
        (part) => part['type'] == 'recognition_state',
      );
      expect(state['runId'], runId);
      expect(state['undoneOperations'], contains(mealOperation));
      expect(state['drafts'], isEmpty);
      final saved =
          persistedParts.singleWhere(
                (part) => part['type'] == 'receipt',
              )['transactions']
              as List;
      expect(saved, hasLength(1));
      expect(saved.single['id'], remaining.id);
      expect(saved.single['source'], 'AI_IMAGE');
      final metadata =
          jsonDecode(saved.single['metadataJson'] as String) as Map;
      expect(metadata['requestSessionId'], runId);
      expect(metadata['operationId'], metroOperation);
    },
  );
}

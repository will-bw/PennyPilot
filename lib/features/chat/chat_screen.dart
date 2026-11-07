import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';

import '../../core/ledger_controller.dart';
import '../../core/models.dart';
import '../../ui/assistant_content.dart';
import '../../ui/common.dart';
import '../../ui/reasoning_controls.dart';
import '../entry/bill_import_screen.dart';
import '../entry/recognition_save_notice.dart';
import '../settings/recognition_settings_screen.dart';
import '../settings/voice_settings_screen.dart';
import 'agent_activity.dart';
import 'agent_workspace_screen.dart';
import 'assistant_recognition.dart';

class ChatScreen extends StatefulWidget {
  const ChatScreen({
    super.key,
    required this.controller,
    required this.onSettings,
    this.active = true,
  });
  final LedgerController controller;
  final VoidCallback onSettings;
  final bool active;

  @override
  State<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends State<ChatScreen> with WidgetsBindingObserver {
  final input = TextEditingController();
  final scroll = ScrollController();
  final focus = FocusNode();
  final scrollPointers = <int>{};
  late Listenable updates;
  bool syncingDraft = false, followingLatest = true, scrollScheduled = false;
  bool showLatest = false, programmaticScroll = false;
  bool continueRecognition = true, dictated = false, ownsRecording = false;
  int speechGeneration = 0;
  String? sessionId;
  bool hadRecognition = false;
  String? outgoingRecognitionText;
  Map<String, dynamic>? outgoingRecognitionAttachment;

  @override
  void initState() {
    super.initState();
    input.text = widget.controller.assistantDraft;
    input.addListener(_inputChanged);
    focus.addListener(_focusChanged);
    _listen();
    WidgetsBinding.instance.addObserver(this);
    _scheduleScroll();
  }

  void _listen() {
    updates = Listenable.merge([
      widget.controller,
      widget.controller.chatUpdates,
    ]);
    updates.addListener(_controllerChanged);
    sessionId = widget.controller.agentSession['id'] as String?;
    hadRecognition = widget.controller.canSupplementRecognition;
  }

  @override
  void didUpdateWidget(ChatScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      updates.removeListener(_controllerChanged);
      _listen();
      _controllerChanged();
    }
    if (oldWidget.active && !widget.active) {
      focus.unfocus();
      _cancelRecording();
    }
    if (!oldWidget.active && widget.active && followingLatest) {
      _scheduleScroll();
    }
  }

  void _inputChanged() {
    if (!syncingDraft) widget.controller.setAssistantDraft(input.text);
  }

  void _focusChanged() => setState(() {});

  void _setDraft(String text) {
    input.value = TextEditingValue(
      text: text,
      selection: TextSelection.collapsed(offset: text.length),
    );
  }

  void _controllerChanged() {
    final c = widget.controller;
    if (input.text != c.assistantDraft) {
      syncingDraft = true;
      input.value = TextEditingValue(
        text: c.assistantDraft,
        selection: TextSelection.collapsed(offset: c.assistantDraft.length),
      );
      syncingDraft = false;
    }
    final nextSession = c.agentSession['id'] as String?;
    if (sessionId != nextSession) {
      sessionId = nextSession;
      followingLatest = true;
      showLatest = false;
      if (c.messages.isEmpty) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted && scroll.hasClients && c.messages.isEmpty) {
            scroll.jumpTo(0);
          }
        });
      }
    }
    if (!hadRecognition && c.canSupplementRecognition) {
      continueRecognition = true;
    }
    hadRecognition = c.canSupplementRecognition;
    if (followingLatest) {
      _scheduleScroll();
    }
  }

  bool _conversationScrolled(ScrollNotification notification) {
    // Child charts can scroll horizontally. Only the conversation's own
    // scrolling changes whether new output should follow the reader.
    if (notification.depth != 0 || programmaticScroll) return false;
    if (notification is ScrollStartNotification &&
            notification.dragDetails != null ||
        notification is UserScrollNotification &&
            notification.direction != ScrollDirection.idle) {
      // Stop at the start of the gesture, before the first scroll delta. Waiting
      // until 80px away lets a queued jumpTo cancel small drags at the bottom.
      if (followingLatest) {
        _followLatest(false);
      }
    } else if (notification is ScrollEndNotification &&
        notification.metrics.extentAfter <= 1 &&
        (!followingLatest || showLatest)) {
      _followLatest(true);
    }
    return false;
  }

  void _followLatest(bool follow) {
    followingLatest = follow;
    showLatest = !follow;
    // Scroll notifications can arrive during layout. Update the intent now so
    // queued output cannot move the reader, and rebuild after this frame.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) setState(() {});
    });
  }

  void _releaseScrollPointer(int pointer) {
    scrollPointers.remove(pointer);
    if (scrollPointers.isEmpty && followingLatest) _scheduleScroll();
  }

  void _scheduleScroll() {
    if (scrollScheduled || !mounted) return;
    scrollScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      scrollScheduled = false;
      if (!mounted ||
          !followingLatest ||
          !scroll.hasClients ||
          scrollPointers.isNotEmpty ||
          !widget.active ||
          widget.controller.messages.isEmpty &&
              !widget.controller.busyFor('assistantInput')) {
        return;
      }
      programmaticScroll = true;
      scroll.jumpTo(scroll.position.maxScrollExtent);
      programmaticScroll = false;
    });
  }

  void _latest() {
    setState(() {
      followingLatest = true;
      showLatest = false;
    });
    _scheduleScroll();
  }

  Future<void> _send([String? suggestion]) async {
    final c = widget.controller;
    final text = (suggestion ?? input.text).trim();
    final attachment = c.assistantAttachment;
    if ((text.isEmpty && attachment == null) || c.isBusy) return;
    final supplement =
        c.canSupplementRecognition && continueRecognition && attachment == null;
    final source = dictated ? 'AI_VOICE' : 'AI_TEXT';
    if (attachment != null || supplement) {
      outgoingRecognitionText = text;
      outgoingRecognitionAttachment = attachment;
    }
    _setDraft('');
    focus.unfocus();
    c.setAssistantAttachment(null);
    dictated = false;
    _latest();
    final ok = await runUi(
      context,
      () => c.sendAssistantInput(
        text,
        attachment: attachment,
        isSupplement: supplement,
        source: source,
      ),
    );
    if (mounted) {
      setState(() {
        outgoingRecognitionText = null;
        outgoingRecognitionAttachment = null;
      });
    }
    if (!ok && mounted) {
      if (input.text.isEmpty) _setDraft(text);
      if (c.assistantAttachment == null && attachment != null) {
        c.setAssistantAttachment(attachment);
      }
    }
  }

  Future<void> _image() async {
    final c = widget.controller;
    if (c.settings['cloudImageParsingEnabled'] != true &&
        c.capabilities['localReceiptImageSupported'] != true) {
      await Navigator.push(
        context,
        MaterialPageRoute<void>(
          builder: (_) => RecognitionSettingsScreen(controller: c),
        ),
      );
      return;
    }
    await runUi(context, () async {
      final raw = await c.command(
        'pickRecognitionImage',
        ledger: true,
        refreshAfter: false,
      );
      if (raw == null || !mounted) return;
      await stageRecognitionImage(c, stringMap(raw));
      if (!mounted) return;
      setState(() => continueRecognition = false);
      focus.unfocus();
    });
  }

  Future<void> _voice() async {
    final c = widget.controller;
    if (c.isRecording) {
      await runUi(context, c.stopSpeech);
      return;
    }
    if (c.settings['hasSpeechKey'] != true) {
      await Navigator.push(
        context,
        MaterialPageRoute<void>(
          builder: (_) => VoiceSettingsScreen(controller: c),
        ),
      );
      return;
    }
    final current = ++speechGeneration;
    ownsRecording = true;
    final prefix = input.text.trimRight();
    await runUi(context, () async {
      final text = await c.startSpeech();
      if (!mounted ||
          current != speechGeneration ||
          !widget.active ||
          text.trim().isEmpty) {
        return;
      }
      _setDraft([if (prefix.isNotEmpty) prefix, text.trim()].join('\n'));
      setState(() => dictated = true);
      focus.requestFocus();
    });
    if (current == speechGeneration) ownsRecording = false;
  }

  void _cancelRecording() {
    if (!ownsRecording) return;
    speechGeneration++;
    ownsRecording = false;
    unawaited(
      widget.controller.cancelSpeech().catchError((Object error) {
        debugPrint('取消助手录音失败：$error');
      }),
    );
  }

  @override
  void didChangeMetrics() {
    if (followingLatest) _scheduleScroll();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.detached) {
      _cancelRecording();
    }
  }

  @override
  void dispose() {
    _cancelRecording();
    WidgetsBinding.instance.removeObserver(this);
    updates.removeListener(_controllerChanged);
    input.dispose();
    scroll.dispose();
    focus.dispose();
    super.dispose();
  }

  Future<void> _menu(String action) async {
    final c = widget.controller;
    switch (action) {
      case 'settings':
        widget.onSettings();
      case 'recognition':
        await Navigator.push(
          context,
          MaterialPageRoute<void>(
            builder: (_) => RecognitionSettingsScreen(controller: c),
          ),
        );
      case 'voice':
        await Navigator.push(
          context,
          MaterialPageRoute<void>(
            builder: (_) => VoiceSettingsScreen(controller: c),
          ),
        );
      case 'import':
        await Navigator.push(
          context,
          MaterialPageRoute<void>(
            builder: (_) => BillImportScreen(controller: c),
          ),
        );
      case 'clear':
        final ok = await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
            title: const Text('清空本次对话？'),
            content: const Text('会清除当前对话和执行记录，保留账单与长期记忆。'),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context, false),
                child: const Text('取消'),
              ),
              FilledButton(
                onPressed: () => Navigator.pop(context, true),
                child: const Text('清空'),
              ),
            ],
          ),
        );
        if (ok == true && mounted) await runUi(context, c.clearConversation);
    }
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: updates,
    builder: (context, _) {
      final c = widget.controller;
      return Scaffold(
        appBar: AppBar(
          title: const Text('AI 助手'),
          actions: [
            IconButton(
              tooltip: '开始新对话',
              onPressed: c.isBusy
                  ? null
                  : () =>
                        runUi(context, () => c.agentCommand('agentNewSession')),
              icon: const Icon(Icons.add_comment_outlined),
            ),
            IconButton(
              tooltip: '对话、记忆与运行设置',
              icon: const Icon(Icons.history),
              onPressed: c.isBusy
                  ? null
                  : () => Navigator.push(
                      context,
                      MaterialPageRoute<void>(
                        builder: (_) => AgentWorkspaceScreen(controller: c),
                      ),
                    ),
            ),
            PopupMenuButton<String>(
              tooltip: '更多操作',
              onSelected: _menu,
              itemBuilder: (_) => [
                const PopupMenuItem(value: 'settings', child: Text('AI 设置')),
                const PopupMenuItem(value: 'recognition', child: Text('识别设置')),
                const PopupMenuItem(value: 'voice', child: Text('语音设置')),
                PopupMenuItem(
                  value: 'import',
                  enabled: !c.isBusy,
                  child: const Text('导入账单'),
                ),
                PopupMenuItem(
                  value: 'clear',
                  enabled: !c.isBusy && c.messages.isNotEmpty,
                  child: const Text('清空本次对话'),
                ),
              ],
            ),
          ],
        ),
        body: ContentWidth(
          child: SizedBox(width: 760, child: _conversation(c)),
        ),
        // Scaffold reserves this space for the composer and positions saved /
        // error SnackBars above it, so feedback cannot cover the send button.
        bottomNavigationBar: Padding(
          padding: EdgeInsets.only(
            bottom: MediaQuery.viewInsetsOf(context).bottom,
          ),
          child: Align(
            heightFactor: 1,
            child: SizedBox(
              width: 760,
              child: _composer(
                c,
                maxLines: MediaQuery.viewInsetsOf(context).bottom > 0 ? 2 : 4,
              ),
            ),
          ),
        ),
      );
    },
  );

  Widget _conversation(LedgerController c) => Stack(
    children: [
      NotificationListener<ScrollMetricsNotification>(
        onNotification: (notification) {
          if (notification.depth != 0) return false;
          if (followingLatest) {
            _scheduleScroll();
          } else {
            final hasContentBelow = notification.metrics.extentAfter > 1;
            if (showLatest != hasContentBelow) {
              setState(() => showLatest = hasContentBelow);
            }
          }
          return false;
        },
        child: NotificationListener<ScrollNotification>(
          onNotification: _conversationScrolled,
          child: Listener(
            onPointerDown: (event) => scrollPointers.add(event.pointer),
            onPointerUp: (event) => _releaseScrollPointer(event.pointer),
            onPointerCancel: (event) => _releaseScrollPointer(event.pointer),
            child: ListView.builder(
              key: const ValueKey('assistant-conversation'),
              controller: scroll,
              keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
              padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
              itemCount: c.messages.isEmpty ? 2 : c.messages.length + 1,
              itemBuilder: (context, index) {
                if (c.messages.isEmpty && index == 0) {
                  return c.busyFor('assistantInput')
                      ? const SizedBox.shrink()
                      : _suggestions();
                }
                if (c.messages.isEmpty || index == c.messages.length) {
                  return Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      if (c.busyFor('assistantInput')) _recognitionProgress(c),
                      if (!c.messages.any(
                        (message) => c.canUndoAssistantRun(message.runId),
                      ))
                        RecognitionSaveNotice(
                          controller: c,
                          titlePrefix: '已记下',
                        ),
                      AssistantRecognition(controller: c),
                    ],
                  );
                }
                return _message(c, c.messages[index], index);
              },
            ),
          ),
        ),
      ),
      if (c.messages.isNotEmpty && showLatest)
        Positioned(
          bottom: 12,
          right: 16,
          child: FilledButton.tonalIcon(
            onPressed: _latest,
            icon: const Icon(Icons.arrow_downward, size: 18),
            label: const Text('回到最新'),
          ),
        ),
    ],
  );

  Widget _recognitionProgress(LedgerController c) {
    final colors = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (outgoingRecognitionAttachment != null)
            Align(
              alignment: Alignment.centerRight,
              child: _AttachmentPreview(
                attachment: outgoingRecognitionAttachment!,
              ),
            ),
          if (outgoingRecognitionText?.isNotEmpty == true)
            Align(
              alignment: Alignment.centerRight,
              child: Container(
                margin: const EdgeInsets.only(top: 8, bottom: 16),
                padding: const EdgeInsets.symmetric(
                  horizontal: 16,
                  vertical: 12,
                ),
                decoration: BoxDecoration(
                  color: colors.surfaceContainerLow,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Text(outgoingRecognitionText!),
              ),
            ),
          Semantics(
            liveRegion: true,
            child: Row(
              children: [
                const SizedBox.square(
                  dimension: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
                const SizedBox(width: 10),
                Expanded(child: Text(c.isParsing ? '正在识别凭证与收支…' : '正在整理这次识别…')),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _message(LedgerController c, ChatMessage message, int index) {
    final colors = Theme.of(context).colorScheme;
    final user = message.role == 'user';
    final active = message.isStreaming && c.isAsking;
    return Padding(
      key: ValueKey('message-${message.id}-$index'),
      padding: const EdgeInsets.only(bottom: 24),
      child: Column(
        crossAxisAlignment: user
            ? CrossAxisAlignment.end
            : CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Text(
              user ? '你' : 'AI 助手',
              style: TextStyle(fontSize: 12, color: colors.onSurfaceVariant),
            ),
          ),
          for (final part in message.parts.where(
            (part) => part['type'] == 'attachment',
          ))
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: _AttachmentPreview(attachment: part),
            ),
          if (!user)
            AgentActivity(
              controller: c,
              parts: message.parts,
              active: active,
              status: message.status,
              onDetailsExpanded: () {
                // Keep the opened details in view as the conversation grows.
                followingLatest = false;
              },
            ),
          if (message.isError)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Row(
                children: [
                  Icon(Icons.error_outline, size: 18, color: colors.error),
                  const SizedBox(width: 8),
                  Text('本次未完成', style: TextStyle(color: colors.error)),
                ],
              ),
            ),
          for (final part in message.parts.where(
            (part) => part['type'] == 'interrupted_text',
          ))
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '未完成的回复',
                    style: TextStyle(
                      fontSize: 12,
                      color: colors.onSurfaceVariant,
                    ),
                  ),
                  const SizedBox(height: 6),
                  AssistantMarkdown(text: part['text'] as String? ?? ''),
                ],
              ),
            ),
          if (message.text.isNotEmpty)
            Container(
              width: user ? null : double.infinity,
              constraints: const BoxConstraints(maxWidth: 680),
              padding: user
                  ? const EdgeInsets.symmetric(horizontal: 16, vertical: 12)
                  : EdgeInsets.zero,
              decoration: user
                  ? BoxDecoration(
                      color: colors.surfaceContainerLow,
                      borderRadius: BorderRadius.circular(12),
                    )
                  : null,
              child: user
                  ? SelectableText(
                      message.text,
                      style: const TextStyle(fontSize: 15, height: 1.6),
                    )
                  : DefaultTextStyle.merge(
                      style: TextStyle(
                        color: message.isError
                            ? colors.error
                            : colors.onSurface,
                      ),
                      child: AssistantMarkdown(
                        text: message.text,
                        isStreaming: message.isStreaming,
                        onEvidenceTap: (id) {
                          final tool = message.parts
                              .where(
                                (part) =>
                                    part['type'] == 'tool' &&
                                    part['evidenceId'] == id,
                              )
                              .firstOrNull;
                          if (tool != null) showAgentEvidence(context, c, tool);
                        },
                      ),
                    ),
            ),
          for (final part in message.parts)
            if (part['type'] == 'chart')
              Padding(
                padding: const EdgeInsets.only(top: 16),
                child: AssistantVisualization(
                  spec: stringMap(part['spec']),
                  onOpenTransactions: c.openLedgerQuery,
                ),
              )
            else if (part['type'] == 'artifact')
              Padding(
                padding: const EdgeInsets.only(top: 16),
                child: AssistantArtifact(spec: stringMap(part['spec'])),
              ),
          if (message.chartData != null &&
              !message.parts.any((part) => part['type'] == 'chart'))
            Padding(
              padding: const EdgeInsets.only(top: 16),
              child: SectionSurface(
                child: Column(
                  children: [
                    for (
                      var i = 0;
                      i < message.chartData!.labels.length &&
                          i < message.chartData!.values.length;
                      i++
                    )
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 8),
                        child: Row(
                          children: [
                            Expanded(child: Text(message.chartData!.labels[i])),
                            const SizedBox(width: 12),
                            AmountLabel(message.chartData!.values[i]),
                          ],
                        ),
                      ),
                  ],
                ),
              ),
            ),
          if (!user && !message.isStreaming && !active)
            Wrap(
              spacing: 4,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                if (message.text.isNotEmpty)
                  IconButton(
                    tooltip: '复制回复',
                    onPressed: () => runUi(
                      context,
                      () =>
                          Clipboard.setData(ClipboardData(text: message.text)),
                      success: '已复制回复',
                    ),
                    icon: const Icon(Icons.copy_outlined, size: 18),
                  ),
                if (message.status == 'cancelled' ||
                    message.status == 'stopped')
                  Text(
                    '已停止，已完成的操作保留',
                    style: TextStyle(
                      fontSize: 12,
                      color: colors.onSurfaceVariant,
                    ),
                  ),
                if (c.canUndoAssistantRun(message.runId))
                  TextButton.icon(
                    onPressed: c.isBusy
                        ? null
                        : () => runUi(
                            context,
                            () => c.undoAssistantRun(message.runId),
                            success: '已撤销本次记账',
                          ),
                    icon: const Icon(Icons.undo, size: 18),
                    label: const Text('撤销本次记账'),
                  ),
                if (index == c.messages.length - 1 && c.retryQuestion != null)
                  TextButton.icon(
                    onPressed: c.isBusy
                        ? null
                        : () {
                            _latest();
                            runUi(context, c.retryAsk);
                          },
                    icon: const Icon(Icons.refresh, size: 18),
                    label: Text(message.isError ? '重试这个问题' : '继续处理'),
                  ),
              ],
            ),
        ],
      ),
    );
  }

  Future<void> _more() async {
    focus.unfocus();
    final action = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      constraints: BoxConstraints(
        maxHeight: MediaQuery.sizeOf(context).height * .8,
        maxWidth: 760,
      ),
      builder: (sheetContext) => SafeArea(
        top: false,
        child: ListenableBuilder(
          listenable: updates,
          builder: (context, _) {
            final c = widget.controller;
            return SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          '输入与附件',
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                      ),
                      IconButton(
                        tooltip: '关闭输入选项',
                        onPressed: () => Navigator.pop(sheetContext),
                        icon: const Icon(Icons.close),
                      ),
                    ],
                  ),
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.add_photo_alternate_outlined),
                    title: const Text('添加记账图片'),
                    subtitle: const Text('选择账单或支付截图'),
                    enabled: !c.isBusy,
                    onTap: () => Navigator.pop(sheetContext, 'image'),
                  ),
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.mic_none),
                    title: const Text('语音输入'),
                    subtitle: const Text('转写成文字，修改后再发送'),
                    enabled: !c.isBusy,
                    onTap: () => Navigator.pop(sheetContext, 'voice'),
                  ),
                  const Divider(),
                  const SizedBox(height: 16),
                  Text('思考设置', style: Theme.of(context).textTheme.titleMedium),
                  Text(
                    c.assistantReasoningOptions.model,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                  ),
                  const SizedBox(height: 8),
                  ReasoningControls(
                    keyPrefix: 'assistant',
                    compact: true,
                    options: c.assistantReasoningOptions,
                    enabled: !c.isBusy,
                    onEnabledChanged: (enabled) => runUi(
                      context,
                      () => c.setAssistantReasoning(enabled: enabled),
                    ),
                    onEffortChanged: (effort) => runUi(
                      context,
                      () => c.setAssistantReasoning(effort: effort),
                    ),
                  ),
                  const SizedBox(height: 16),
                  Text(
                    '发送消息会将对话与必要账本数据交给所选 AI 服务。图片需开启识别；语音会发送至转写服务，转写后可编辑。',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            );
          },
        ),
      ),
    );
    if (!mounted) return;
    if (action == 'image') await _image();
    if (action == 'voice') await _voice();
  }

  Widget _composer(LedgerController c, {required int maxLines}) {
    final colors = Theme.of(context).colorScheme;
    final supplement =
        c.canSupplementRecognition &&
        continueRecognition &&
        c.assistantAttachment == null;
    final recording = c.isRecording || c.isTranscribing;
    return SafeArea(
      top: false,
      child: Padding(
        key: const ValueKey('assistant-composer'),
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 10),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (c.canSupplementRecognition && c.assistantAttachment == null)
              Row(
                children: [
                  Expanded(
                    child: Text(
                      supplement ? '补充这次识别' : '正在输入新问题',
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: colors.onSurfaceVariant,
                      ),
                    ),
                  ),
                  TextButton(
                    onPressed: c.isBusy
                        ? null
                        : () => setState(
                            () => continueRecognition = !continueRecognition,
                          ),
                    child: Text(supplement ? '改为新问题' : '继续补充'),
                  ),
                ],
              ),
            if (c.assistantAttachment != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: _AttachmentPreview(
                  attachment: c.assistantAttachment!,
                  onRemove: c.isBusy
                      ? null
                      : () => c.setAssistantAttachment(null),
                ),
              ),
            if (recording)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Row(
                  children: [
                    const Icon(Icons.mic_none, size: 18),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        c.isTranscribing
                            ? '正在转写成文字…'
                            : c.speechState == 'preparing'
                            ? '正在准备麦克风…'
                            : '正在录音，结束后可修改再发送。',
                      ),
                    ),
                    TextButton(
                      onPressed: _cancelRecording,
                      child: const Text('取消'),
                    ),
                  ],
                ),
              ),
            DecoratedBox(
              decoration: BoxDecoration(
                color: colors.surfaceContainerLow,
                borderRadius: BorderRadius.circular(16),
                border: Border.all(
                  color: focus.hasFocus
                      ? colors.primary
                      : colors.outlineVariant,
                  width: 1.5,
                ),
              ),
              child: Padding(
                padding: const EdgeInsets.all(4),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    IconButton(
                      tooltip: '添加附件和更多',
                      constraints: const BoxConstraints(
                        minWidth: 48,
                        minHeight: 48,
                      ),
                      onPressed: c.isBusy || recording ? null : _more,
                      icon: const Icon(Icons.add),
                    ),
                    Expanded(
                      child: Semantics(
                        label: '消息',
                        child: TextField(
                          key: const ValueKey('assistant-input'),
                          controller: input,
                          focusNode: focus,
                          enabled: !recording,
                          minLines: 1,
                          maxLines: maxLines,
                          maxLength: 4000,
                          textInputAction: TextInputAction.newline,
                          onTapOutside: (_) => focus.unfocus(),
                          decoration: InputDecoration(
                            hintText: supplement
                                ? '补充识别信息…'
                                : c.isAsking
                                ? '可先写下一条消息…'
                                : '记一笔，或问问账本…',
                            hintMaxLines: 1,
                            counterText: '',
                            filled: false,
                            border: InputBorder.none,
                            enabledBorder: InputBorder.none,
                            focusedBorder: InputBorder.none,
                            contentPadding: const EdgeInsets.symmetric(
                              horizontal: 4,
                              vertical: 12,
                            ),
                          ),
                        ),
                      ),
                    ),
                    ValueListenableBuilder<TextEditingValue>(
                      valueListenable: input,
                      builder: (context, value, _) {
                        final hasDraft =
                            value.text.trim().isNotEmpty ||
                            c.assistantAttachment != null;
                        if (c.isAsking || hasDraft && !recording) {
                          return IconButton.filled(
                            tooltip: c.isAsking
                                ? '停止任务'
                                : supplement
                                ? '发送补充'
                                : '发送消息',
                            constraints: const BoxConstraints(
                              minWidth: 48,
                              minHeight: 48,
                            ),
                            onPressed: c.isAsking
                                ? () => runUi(context, c.stopAgent)
                                : c.isBusy
                                ? null
                                : _send,
                            icon: Icon(
                              c.isAsking ? Icons.stop : Icons.arrow_upward,
                              size: 22,
                            ),
                          );
                        }
                        return IconButton(
                          tooltip: c.isRecording ? '结束录音' : '语音输入',
                          constraints: const BoxConstraints(
                            minWidth: 48,
                            minHeight: 48,
                          ),
                          onPressed:
                              c.isTranscribing ||
                                  c.isRecording &&
                                      c.speechState == 'preparing' ||
                                  c.isBusy && !c.isRecording
                              ? null
                              : _voice,
                          icon: Icon(
                            c.isRecording
                                ? Icons.stop_circle_outlined
                                : Icons.mic_none,
                            size: 22,
                          ),
                        );
                      },
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _suggestions() => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      const SizedBox(height: 12),
      const PageHeading(title: '把收支交给助手', subtitle: '一句话记账、核对凭证，也能从账本中看清花销。'),
      for (final example in [
        '午饭 28 元，地铁 4 元',
        '这个月一共花了多少钱？',
        '对比本月和上月，看看哪些支出增长了。',
      ]) ...[
        ListTile(
          contentPadding: EdgeInsets.zero,
          title: Text(example, style: const TextStyle(fontSize: 15)),
          trailing: const Icon(Icons.north_west, size: 18),
          onTap: () {
            _setDraft(example);
            focus.requestFocus();
          },
        ),
        const Divider(),
      ],
      const SizedBox(height: 12),
      TextButton.icon(
        onPressed: widget.onSettings,
        icon: const Icon(Icons.tune, size: 18),
        label: const Text('连接或切换 AI 服务'),
      ),
      const SizedBox(height: 8),
      Text(
        '发送后，消息与必要账本数据会交给所选 AI 服务。',
        style: Theme.of(context).textTheme.bodySmall?.copyWith(
          color: Theme.of(context).colorScheme.onSurfaceVariant,
        ),
      ),
    ],
  );
}

class _AttachmentPreview extends StatefulWidget {
  const _AttachmentPreview({required this.attachment, this.onRemove});
  final Map<String, dynamic> attachment;
  final VoidCallback? onRemove;
  @override
  State<_AttachmentPreview> createState() => _AttachmentPreviewState();
}

class _AttachmentPreviewState extends State<_AttachmentPreview> {
  Uint8List? bytes;
  String? encoded;

  @override
  void initState() {
    super.initState();
    _decode();
  }

  @override
  void didUpdateWidget(_AttachmentPreview oldWidget) {
    super.didUpdateWidget(oldWidget);
    _decode();
  }

  void _decode() {
    final next =
        widget.attachment['imageBase64'] as String? ??
        widget.attachment['base64'] as String?;
    if (next == encoded) return;
    encoded = next;
    try {
      bytes = next == null ? null : base64Decode(next);
    } on FormatException {
      bytes = null;
    }
  }

  @override
  Widget build(BuildContext context) {
    final path = widget.attachment['path'] as String?;
    Widget failed(BuildContext context, Object error, StackTrace? stack) =>
        const Icon(Icons.image_not_supported_outlined);
    final image = bytes != null
        ? Image.memory(
            bytes!,
            width: 60,
            height: 60,
            fit: BoxFit.cover,
            errorBuilder: failed,
            excludeFromSemantics: true,
          )
        : path != null
        ? Image.file(
            File(path),
            width: 60,
            height: 60,
            fit: BoxFit.cover,
            errorBuilder: failed,
            excludeFromSemantics: true,
          )
        : const SizedBox.square(
            dimension: 60,
            child: Icon(Icons.image_outlined),
          );
    return Container(
      constraints: const BoxConstraints(maxWidth: 360),
      padding: const EdgeInsets.all(8),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Semantics(
            label: '放大查看图片',
            button: true,
            child: InkWell(
              key: const ValueKey('assistant-attachment-preview'),
              borderRadius: BorderRadius.circular(8),
              onTap: bytes == null && path == null
                  ? null
                  : () => Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        fullscreenDialog: true,
                        builder: (_) => _AttachmentViewer(
                          bytes: bytes,
                          path: path,
                          title: widget.attachment['name'] as String? ?? '记账图片',
                        ),
                      ),
                    ),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(8),
                child: image,
              ),
            ),
          ),
          const SizedBox(width: 10),
          Flexible(
            child: Text(
              widget.attachment['name'] as String? ?? '记账图片',
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
            ),
          ),
          if (widget.onRemove != null)
            IconButton(
              tooltip: '移除图片',
              onPressed: widget.onRemove,
              icon: const Icon(Icons.close, size: 18),
            ),
        ],
      ),
    );
  }
}

class _AttachmentViewer extends StatelessWidget {
  const _AttachmentViewer({
    required this.bytes,
    required this.path,
    required this.title,
  });
  final Uint8List? bytes;
  final String? path;
  final String title;

  @override
  Widget build(BuildContext context) {
    Widget failed(BuildContext context, Object error, StackTrace? stack) =>
        const Center(child: Text('图片暂时无法显示，请重新选择凭证。'));
    final image = bytes != null
        ? Image.memory(bytes!, fit: BoxFit.contain, errorBuilder: failed)
        : Image.file(File(path!), fit: BoxFit.contain, errorBuilder: failed);
    return Scaffold(
      key: const ValueKey('assistant-attachment-viewer'),
      appBar: AppBar(leading: const CloseButton(), title: Text(title)),
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) => InteractiveViewer(
            minScale: .8,
            maxScale: 6,
            child: SizedBox(
              width: constraints.maxWidth,
              height: constraints.maxHeight,
              child: image,
            ),
          ),
        ),
      ),
    );
  }
}

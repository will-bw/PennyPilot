import 'package:flutter_test/flutter_test.dart';
import 'package:pennypilot/core/ai/model_reasoning.dart';
import 'package:pennypilot/core/models.dart';

void main() {
  test(
    'provider defaults use exact current IDs and distinct wire parameters',
    () {
      final deepseek = resolveReasoningOptions({'activeProvider': 'deepseek'});
      expect(deepseek.model, 'deepseek-flash');
      expect(deepseek.chatParameters, {
        'thinking': {'type': 'enabled'},
        'reasoning_effort': 'high',
      });
      final qwen = resolveReasoningOptions({'activeProvider': 'qwen'});
      expect(qwen.model, 'qwen3.8-flash');
      expect(qwen.chatParameters, {
        'enable_thinking': true,
        'reasoning_effort': 'xhigh',
      });
      expect(
        defaultQwenBaseUrl,
        'https://dashscope.aliyuncs.com/compatible-mode/v1',
      );
      for (final provider in ['deepseek', 'qwen']) {
        final prefix = reasoningSettingsPrefix(provider);
        final off = resolveReasoningOptions({
          'activeProvider': provider,
          '${prefix}ThinkingEnabled': false,
        });
        expect(off.chatParameters, isNot(contains('reasoning_effort')));
        expect(off.enabled, false);
      }
    },
  );

  test(
    'required GPT models preserve saved off preference without sending none',
    () {
      final settings = {
        'activeProvider': 'chatgpt',
        'chatGptModel': 'gpt-6.1-sol',
        'chatGptThinkingEnabled': false,
        'chatGptReasoningEffort': 'max',
      };
      final required = resolveReasoningOptions(settings);
      expect(required.capability.mode, ReasoningMode.required);
      expect(required.enabled, true);
      expect(required.responseEffort, 'max');
      expect(
        resolveReasoningOptions(settings, model: 'gpt-5.4').responseEffort,
        'none',
      );
      expect(settings['chatGptThinkingEnabled'], false);
    },
  );

  test(
    'account capabilities override family inference and exclude orchestration efforts',
    () {
      final options = resolveReasoningOptions(
        {
          'activeProvider': 'chatgpt',
          'chatGptModel': 'gpt-5.4',
          'chatGptReasoningEffort': 'ultra',
        },
        catalogModel: const AiModel(
          slug: 'gpt-5.4',
          displayName: 'Account model',
          reasoningEfforts: ['low', 'max', 'ultra'],
          defaultReasoningEffort: 'low',
          supportsReasoningSummaries: false,
        ),
      );
      expect(options.capability.canToggle, false);
      expect(options.capability.efforts, ['low', 'max']);
      expect(options.responseEffort, 'low');
      expect(options.requestSummary, false);
      final noThinking = resolveReasoningOptions(
        {'activeProvider': 'chatgpt', 'chatGptModel': 'gpt-5.4'},
        catalogModel: const AiModel(
          slug: 'gpt-5.4',
          displayName: '',
          reasoningEfforts: [],
        ),
      );
      expect(noThinking.capability.mode, ReasoningMode.unsupported);
      expect(noThinking.responseEffort, null);
    },
  );

  test(
    'unknown and legacy models do not receive invented effort parameters',
    () {
      for (final provider in ['chatgpt', 'deepseek', 'qwen']) {
        final unknown = resolveReasoningOptions(
          {},
          provider: provider,
          model: 'custom-model',
        );
        expect(unknown.capability.mode, ReasoningMode.unknown);
        expect(unknown.chatParameters, isEmpty);
        expect(unknown.responseEffort, null);
      }
      final legacy = resolveReasoningOptions(
        {},
        provider: 'qwen',
        model: 'qwen-plus',
      );
      expect(legacy.capability.canToggle, true);
      expect(legacy.chatParameters, {'enable_thinking': true});
      final onlyThinking = resolveReasoningOptions(
        {'qwenThinkingEnabled': false},
        provider: 'qwen',
        model: 'qwen3.8-2.4t-a95b',
      );
      expect(onlyThinking.enabled, true);
      expect(onlyThinking.chatParameters, isEmpty);
    },
  );

  test('partial account metadata still disables unsupported summaries', () {
    final options = resolveReasoningOptions(
      {'activeProvider': 'chatgpt', 'chatGptModel': 'gpt-5.4'},
      catalogModel: const AiModel(
        slug: 'gpt-5.4',
        displayName: '',
        defaultReasoningEffort: 'low',
        supportsReasoningSummaries: false,
      ),
    );
    expect(options.responseEffort, 'low');
    expect(options.requestSummary, false);
    expect(options.capability.supportsSummary, false);
  });
}

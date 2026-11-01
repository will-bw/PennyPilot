import '../models.dart';

const defaultDeepSeekModel = 'deepseek-flash';
const defaultQwenModel = 'qwen3.8-flash';
const defaultQwenBaseUrl = 'https://dashscope.aliyuncs.com/compatible-mode/v1';

enum ReasoningMode { optional, required, unsupported, unknown }

enum ReasoningVisibility { summary, reasoningContent, none }

class ReasoningCapability {
  const ReasoningCapability({
    required this.mode,
    this.efforts = const [],
    this.defaultEffort,
    this.visibility = ReasoningVisibility.none,
    this.supportsSummary,
  });

  final ReasoningMode mode;
  final List<String> efforts;
  final String? defaultEffort;
  final ReasoningVisibility visibility;
  final bool? supportsSummary;
  bool get canToggle => mode == ReasoningMode.optional;
  bool get known => mode != ReasoningMode.unknown;
  bool get supported => canToggle || mode == ReasoningMode.required;
}

String reasoningSettingsPrefix(String provider) => switch (provider) {
  'chatgpt' => 'chatGpt',
  'deepseek' => 'deepSeek',
  _ => 'qwen',
};

String reasoningEffortLabel(String effort) => switch (effort) {
  'minimal' => '最少',
  'low' => '低',
  'medium' => '中',
  'high' => '高',
  'xhigh' => '超高',
  'max' => '最高',
  _ => effort,
};

// These are Responses API efforts. Codex's multi-agent "ultra" is not an
// effort accepted by the direct Responses endpoint used by this app.
const responsesReasoningEfforts = {
  'none',
  'minimal',
  'low',
  'medium',
  'high',
  'xhigh',
  'max',
};

ReasoningCapability reasoningCapabilityFor({
  required String provider,
  required String model,
  AiModel? catalogModel,
}) {
  const unknown = ReasoningCapability(mode: ReasoningMode.unknown);
  const unsupported = ReasoningCapability(mode: ReasoningMode.unsupported);
  final name = model.trim().toLowerCase();
  if (provider == 'chatgpt') {
    final declared = catalogModel?.reasoningEfforts;
    if (declared != null) {
      final levels = declared
          .where(responsesReasoningEfforts.contains)
          .toList();
      final active = levels.where((e) => e != 'none').toList();
      if (active.isEmpty) {
        return declared.isEmpty || levels.contains('none')
            ? unsupported
            : unknown;
      }
      return ReasoningCapability(
        mode: levels.contains('none')
            ? ReasoningMode.optional
            : ReasoningMode.required,
        efforts: active,
        defaultEffort: catalogModel?.defaultReasoningEffort,
        visibility: ReasoningVisibility.summary,
        supportsSummary: catalogModel?.supportsReasoningSummaries,
      );
    }
    // Only documented families are inferred without an account catalog.
    // Unrecognised model ids retain their server defaults until refreshed.
    final List<String> levels;
    final bool optional;
    if (RegExp(
      r'^gpt-5(?:-(?:mini|nano))?(?:-\d{4}-\d{2}-\d{2})?$',
    ).hasMatch(name)) {
      levels = ['minimal', 'low', 'medium', 'high'];
      optional = false;
    } else if (RegExp(r'^gpt-5\.1(?:-\d{4}-\d{2}-\d{2})?$').hasMatch(name)) {
      levels = ['low', 'medium', 'high'];
      optional = true;
    } else if (RegExp(
      r'^gpt-5\.(?:2|4|5)(?:-\d{4}-\d{2}-\d{2})?$',
    ).hasMatch(name)) {
      levels = ['low', 'medium', 'high', 'xhigh'];
      optional = true;
    } else if (RegExp(
      r'^gpt-5\.6(?:-sol)?(?:-\d{4}-\d{2}-\d{2})?$',
    ).hasMatch(name)) {
      levels = ['low', 'medium', 'high', 'xhigh', 'max'];
      optional = true;
    } else if (RegExp(
      r'^gpt-(?:6-astra|6\.1-sol)(?:-\d{4}-\d{2}-\d{2})?$',
    ).hasMatch(name)) {
      levels = ['low', 'medium', 'high', 'xhigh', 'max'];
      optional = false;
    } else if (RegExp(
      r'^gpt-(?:5\.6-(?:terra|luna)|6-(?:sol|luna)|6\.1-terra)(?:-\d{4}-\d{2}-\d{2})?$',
    ).hasMatch(name)) {
      levels = ['low', 'medium', 'high', 'xhigh', 'max'];
      optional = true;
    } else if (RegExp(
      r'^o[134](?:-mini)?(?:-\d{4}-\d{2}-\d{2})?$',
    ).hasMatch(name)) {
      levels = ['low', 'medium', 'high'];
      optional = false;
    } else if (RegExp(r'^gpt-(?:4|3)').hasMatch(name)) {
      return unsupported;
    } else {
      return unknown;
    }
    return ReasoningCapability(
      mode: optional ? ReasoningMode.optional : ReasoningMode.required,
      efforts: levels,
      defaultEffort: catalogModel?.defaultReasoningEffort ?? 'medium',
      visibility: ReasoningVisibility.summary,
      supportsSummary: catalogModel?.supportsReasoningSummaries,
    );
  }
  if (provider == 'deepseek') {
    if (RegExp(
      r'^deepseek-(?:(?:v4(?:\.1)?)-)?(?:flash|pro)(?:-\d+)?$',
    ).hasMatch(name)) {
      return const ReasoningCapability(
        mode: ReasoningMode.optional,
        efforts: ['low', 'high', 'max'],
        defaultEffort: 'high',
        visibility: ReasoningVisibility.reasoningContent,
      );
    }
    if (name == 'deepseek-reasoner') {
      return const ReasoningCapability(
        mode: ReasoningMode.required,
        visibility: ReasoningVisibility.reasoningContent,
      );
    }
    if (name == 'deepseek-chat') return unsupported;
    return unknown;
  }
  if (provider == 'qwen') {
    if (RegExp(r'^qwen3\.8-(?:flash|max|plus)(?:-\d+)?$').hasMatch(name)) {
      return const ReasoningCapability(
        mode: ReasoningMode.optional,
        efforts: ['low', 'medium', 'xhigh'],
        defaultEffort: 'xhigh',
        visibility: ReasoningVisibility.reasoningContent,
      );
    }
    if (name == 'qwen3.8-2.4t-a95b' || name.contains('-thinking')) {
      return const ReasoningCapability(
        mode: ReasoningMode.required,
        visibility: ReasoningVisibility.reasoningContent,
      );
    }
    if (name.contains('-instruct')) return unsupported;
    if (RegExp(r'^qwen3\.[567]-').hasMatch(name) ||
        RegExp(r'^qwen-(?:plus|flash)(?:-\d+)?$').hasMatch(name)) {
      return const ReasoningCapability(
        mode: ReasoningMode.optional,
        visibility: ReasoningVisibility.reasoningContent,
      );
    }
    return unknown;
  }
  return unknown;
}

class ReasoningOptions {
  const ReasoningOptions({
    required this.provider,
    required this.model,
    required this.enabled,
    required this.effort,
    required this.capability,
  });
  final String provider, model;
  final bool enabled;
  final String? effort;
  final ReasoningCapability capability;

  String? get responseEffort => !capability.supported
      ? null
      : !enabled && capability.canToggle
      ? 'none'
      : effort;
  bool get requestSummary =>
      enabled && capability.supported && capability.supportsSummary != false;
  Map<String, dynamic> get chatParameters {
    if (!capability.supported) return {};
    // Legacy thinking-only ids do not accept the newer hybrid-mode parameters.
    if (provider == 'deepseek' && model == 'deepseek-reasoner') return {};
    return {
      if (provider == 'deepseek')
        'thinking': {'type': enabled ? 'enabled' : 'disabled'},
      if (provider == 'qwen' && capability.canToggle)
        'enable_thinking': enabled,
      if (enabled && effort != null) 'reasoning_effort': effort,
    };
  }

  Map<String, dynamic> toMap() => {
    'provider': provider,
    'model': model,
    'enabled': enabled,
    'effort': effort,
    'mode': capability.mode.name,
    'efforts': capability.efforts,
    'defaultEffort': capability.defaultEffort,
    'visibility': capability.visibility.name,
    'supportsSummary': capability.supportsSummary,
  };

  factory ReasoningOptions.fromMap(Map<String, dynamic> map) =>
      ReasoningOptions(
        provider: map['provider'] as String,
        model: map['model'] as String,
        enabled: map['enabled'] == true,
        effort: map['effort'] as String?,
        capability: ReasoningCapability(
          mode: ReasoningMode.values.byName(map['mode'] as String),
          efforts: (map['efforts'] as List? ?? []).cast<String>(),
          defaultEffort: map['defaultEffort'] as String?,
          visibility: ReasoningVisibility.values.byName(
            map['visibility'] as String,
          ),
          supportsSummary: map['supportsSummary'] as bool?,
        ),
      );
}

ReasoningOptions resolveReasoningOptions(
  Map<String, dynamic> settings, {
  String? provider,
  String? model,
  AiModel? catalogModel,
}) {
  provider ??= settings['activeProvider'] as String? ?? '';
  final prefix = reasoningSettingsPrefix(provider);
  model ??=
      settings['${prefix}Model'] as String? ??
      switch (provider) {
        'deepseek' => defaultDeepSeekModel,
        'qwen' => defaultQwenModel,
        _ => '',
      };
  final capability = reasoningCapabilityFor(
    provider: provider,
    model: model,
    catalogModel: catalogModel,
  );
  final savedEffort = settings['${prefix}ReasoningEffort'] as String?;
  final effort = capability.efforts.contains(savedEffort)
      ? savedEffort
      : capability.efforts.contains(capability.defaultEffort)
      ? capability.defaultEffort
      : capability.efforts.contains('medium')
      ? 'medium'
      : capability.efforts.firstOrNull;
  return ReasoningOptions(
    provider: provider,
    model: model,
    enabled:
        capability.mode == ReasoningMode.required ||
        (capability.canToggle && settings['${prefix}ThinkingEnabled'] != false),
    effort: effort,
    capability: capability,
  );
}

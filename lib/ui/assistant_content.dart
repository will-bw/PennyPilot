import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:file_picker/file_picker.dart';
import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:gpt_markdown/gpt_markdown.dart';
import 'package:http/http.dart' as http;
import 'package:url_launcher/url_launcher.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../core/agent/assistant_artifacts.dart';
import '../core/models.dart';
import 'theme.dart';

class AssistantMarkdown extends StatelessWidget {
  const AssistantMarkdown({
    required this.text,
    this.isStreaming = false,
    this.onEvidenceTap,
    super.key,
  });

  final String text;
  final bool isStreaming;
  final ValueChanged<String>? onEvidenceTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final withEvidenceLinks = text.replaceAllMapped(
      RegExp(r'\[依据 (Q\d+)\](?!\()'),
      (m) => '[依据 ${m[1]}](pennypilot-evidence:${m[1]})',
    );
    return SelectionArea(
      child: GptMarkdown(
        withEvidenceLinks,
        isStreaming: isStreaming,
        // Real deltas drive the text. Avoid a second, slower typewriter queue.
        animation: GptMarkdownAnimation.none,
        html: false,
        autolinkSchemes: const {'pennypilot-evidence'},
        style: TextStyle(fontSize: 15, height: 1.65, color: colors.onSurface),
        styleSheet: GptMarkdownStyleSheet(
          blockSpacing: 12,
          heading: const HeadingStyle(showDivider: false),
          blockQuote: BlockQuoteStyle(
            barWidth: 1,
            barColor: colors.outline,
            textStyle: TextStyle(color: colors.onSurfaceVariant),
          ),
          table: TableStyle(
            borderColor: colors.outlineVariant,
            borderWidth: .6,
            cellPadding: const EdgeInsets.symmetric(
              horizontal: 12,
              vertical: 8,
            ),
            headerBackground: colors.surfaceContainerLow,
          ),
        ),
        onLinkTap: (url, _) async {
          if (url.startsWith('pennypilot-evidence:')) {
            onEvidenceTap?.call(url.substring('pennypilot-evidence:'.length));
            return;
          }
          final uri = Uri.tryParse(url);
          if (uri == null ||
              !const {'https', 'http'}.contains(uri.scheme) ||
              uri.host.isEmpty ||
              uri.userInfo.isNotEmpty) {
            _contentNotice(context, '此链接格式不支持打开。');
            return;
          }
          try {
            if (!await launchUrl(uri, mode: LaunchMode.externalApplication) &&
                context.mounted) {
              _contentNotice(context, '未能打开链接。');
            }
          } on PlatformException {
            if (context.mounted) {
              _contentNotice(context, '未能打开链接。');
            }
          }
        },
        imageBuilder: (context, url, width, height) =>
            _AssistantImage(url: url),
        codeBuilder: (context, language, code, closed) {
          final visualCode = const {
            'html',
            'svg',
          }.contains(language.toLowerCase());
          if (visualCode) {
            if (!closed || isStreaming) {
              return const _ContentMessage('正在生成可视化内容…');
            }
            return AssistantArtifact(
              spec: {
                'type': 'artifact',
                'title': language.toLowerCase() == 'svg' ? '生成的图示' : '交互内容',
                'format': language.toLowerCase(),
                'template': 'custom',
                'content': code,
              },
            );
          }
          return Container(
            width: double.infinity,
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: colors.surfaceContainerLow,
              borderRadius: BorderRadius.circular(10),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (closed)
                  Align(
                    alignment: Alignment.centerRight,
                    child: IconButton(
                      tooltip: '复制代码',
                      onPressed: () =>
                          Clipboard.setData(ClipboardData(text: code)),
                      icon: const Icon(Icons.copy_outlined, size: 18),
                    ),
                  ),
                SingleChildScrollView(
                  scrollDirection: Axis.horizontal,
                  child: Text(
                    code,
                    style: const TextStyle(
                      fontFamily: 'monospace',
                      fontSize: 13,
                    ),
                  ),
                ),
              ],
            ),
          );
        },
      ),
    );
  }
}

class AssistantVisualization extends StatefulWidget {
  const AssistantVisualization({
    required this.spec,
    this.onOpenTransactions,
    super.key,
  });

  final Map<String, dynamic> spec;
  final ValueChanged<Map<String, dynamic>>? onOpenTransactions;

  @override
  State<AssistantVisualization> createState() => _AssistantVisualizationState();
}

class _AssistantVisualizationState extends State<AssistantVisualization> {
  bool expanded = false;
  int? selected;
  final previewKey = GlobalKey();

  @override
  Widget build(BuildContext context) {
    final spec = widget.spec;
    if (spec['data'] is! Map) {
      return const _ContentMessage('图表数据不可用，请重新查询。');
    }
    final data = Map<String, dynamic>.from(spec['data'] as Map);
    final colors = Theme.of(context).colorScheme;
    final type = spec['chartType'];
    final metric = spec['metric'] == 'income' ? 'income' : 'expense';
    final metricLabel = metric == 'income' ? '收入' : '净支出';
    final points = _chartPoints(spec);
    final shown = expanded ? points : points.take(6).toList();
    final total = data[metric] as int?;
    return RepaintBoundary(
      key: previewKey,
      child: _ContentSurface(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _ContentTitle(
              title: spec['title'] as String? ?? '账本图表',
              onSave: () => _saveChart(context),
              onExpand: () => _openFullContent(
                context,
                spec['title'] as String? ?? '账本图表',
                AssistantVisualization(
                  spec: spec,
                  onOpenTransactions: widget.onOpenTransactions,
                ),
              ),
            ),
            _EvidenceCaption(spec: spec),
            const SizedBox(height: 16),
            if (type == 'budget')
              _BudgetContent(data: data)
            else if (points.isEmpty)
              const _ContentMessage('这个范围没有可绘制的数据。')
            else ...[
              if (total != null)
                Padding(
                  padding: const EdgeInsets.only(bottom: 16),
                  child: Text(
                    '$metricLabel ${formatMoney(total)} 元',
                    style: const TextStyle(
                      fontWeight: FontWeight.w600,
                      fontFeatures: moneyFigures,
                    ),
                  ),
                ),
              if (type == 'pie')
                _pie(context, points)
              else
                _cartesian(context, points, line: type == 'line'),
              const SizedBox(height: 12),
              if (selected != null && selected! < points.length)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Text(
                    '${points[selected!].label} · ${formatMoney(points[selected!].amount)} 元',
                    style: TextStyle(
                      color: colors.primary,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              for (final point in shown)
                _ChartValueRow(
                  point: point,
                  onTap: widget.onOpenTransactions == null
                      ? null
                      : () => widget.onOpenTransactions!(point.query),
                ),
              if (points.length > 6)
                TextButton(
                  onPressed: () => setState(() => expanded = !expanded),
                  child: Text(expanded ? '收起明细' : '展开全部 ${points.length} 项'),
                ),
              if (data['next_offset'] != null ||
                  (spec['query'] as Map?)?['offset'] != null &&
                      (spec['query'] as Map)['offset'] != 0)
                Text(
                  '当前显示部分分组；总额来自完整查询范围。',
                  style: TextStyle(
                    fontSize: 12,
                    color: colors.onSurfaceVariant,
                  ),
                ),
            ],
            if (widget.onOpenTransactions != null && spec['query'] is Map)
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  onPressed: () => widget.onOpenTransactions!(
                    Map<String, dynamic>.from(spec['query'] as Map),
                  ),
                  icon: const Icon(Icons.receipt_long_outlined, size: 18),
                  label: const Text('查看相关账单'),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Future<void> _saveChart(BuildContext context) async {
    try {
      await WidgetsBinding.instance.endOfFrame;
      if (!mounted) {
        return;
      }
      final boundary =
          previewKey.currentContext!.findRenderObject()
              as RenderRepaintBoundary;
      final ratio = math.min(
        2.0,
        math.sqrt(6000000 / (boundary.size.width * boundary.size.height)),
      );
      final image = await boundary.toImage(pixelRatio: ratio);
      final data = await image.toByteData(format: ui.ImageByteFormat.png);
      image.dispose();
      if (data != null && context.mounted) {
        await _saveContentFile(
          context,
          data.buffer.asUint8List(),
          widget.spec['title'] as String? ?? '账本图表',
          'png',
        );
      }
    } on PlatformException {
      if (context.mounted) {
        _contentNotice(context, '图表保存失败，请重试。');
      }
    }
  }

  Widget _pie(BuildContext context, List<_ChartPoint> points) {
    final positive = points.where((p) => p.amount > 0).toList();
    if (points.any((p) => p.amount < 0)) {
      return const _ContentMessage('包含负净额，无法绘制占比图。请改用柱状图。');
    }
    if (positive.isEmpty) {
      return const _ContentMessage('金额均为零，暂无占比。');
    }
    final total = positive.fold<int>(0, (sum, p) => sum + p.amount);
    final sectors = positive.take(6).toList();
    if (positive.length > 6) {
      sectors.add(
        _ChartPoint(
          '其他分组',
          positive.skip(6).fold<int>(0, (s, p) => s + p.amount),
          const {},
        ),
      );
    }
    final palette = _chartColors(context);
    return Semantics(
      label: '各分组金额占比，具体数字见下方明细',
      child: SizedBox(
        height: 200,
        child: PieChart(
          PieChartData(
            centerSpaceRadius: 42,
            sectionsSpace: 2,
            startDegreeOffset: -90,
            sections: [
              for (var i = 0; i < sectors.length; i++)
                PieChartSectionData(
                  value: sectors[i].amount.toDouble(),
                  color: palette[i % palette.length],
                  radius: 46,
                  title: sectors[i].amount / total >= .06
                      ? '${(sectors[i].amount * 100 / total).round()}%'
                      : '',
                  titleStyle: TextStyle(
                    color: palette[i % palette.length].computeLuminance() > .45
                        ? Colors.black87
                        : Colors.white,
                    fontWeight: FontWeight.w700,
                    fontSize: 12,
                  ),
                ),
            ],
            pieTouchData: PieTouchData(
              touchCallback: (event, response) {
                final index = response?.touchedSection?.touchedSectionIndex;
                if (index != null &&
                    index >= 0 &&
                    index < math.min(6, positive.length)) {
                  setState(() => selected = points.indexOf(positive[index]));
                }
              },
            ),
          ),
          duration: Duration.zero,
        ),
      ),
    );
  }

  Widget _cartesian(
    BuildContext context,
    List<_ChartPoint> points, {
    required bool line,
  }) {
    final colors = Theme.of(context).colorScheme;
    final values = points.map((p) => p.amount / 100).toList();
    final minimum = math.min(0.0, values.reduce(math.min));
    final maximum = math.max(0.0, values.reduce(math.max));
    final padding = math.max(1.0, (maximum - minimum) * .15);
    final minY = minimum < 0 ? minimum - padding : 0.0;
    final maxY = maximum + padding;
    final titles = FlTitlesData(
      topTitles: const AxisTitles(sideTitles: SideTitles(showTitles: false)),
      rightTitles: const AxisTitles(sideTitles: SideTitles(showTitles: false)),
      leftTitles: const AxisTitles(sideTitles: SideTitles(showTitles: false)),
      bottomTitles: AxisTitles(
        sideTitles: SideTitles(
          showTitles: true,
          reservedSize: 34,
          interval: 1,
          getTitlesWidget: (value, meta) {
            final index = value.round();
            if (value != index || index < 0 || index >= points.length) {
              return const SizedBox.shrink();
            }
            final label = points[index].label;
            return Padding(
              padding: const EdgeInsets.only(top: 8),
              child: SizedBox(
                width: 52,
                child: Text(
                  RegExp(r'^\d{4}-\d{2}').hasMatch(label)
                      ? label.substring(5)
                      : label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontSize: 11,
                    color: colors.onSurfaceVariant,
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
    final grid = FlGridData(
      show: true,
      drawVerticalLine: false,
      getDrawingHorizontalLine: (_) =>
          FlLine(color: colors.outlineVariant, strokeWidth: .6),
    );
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = math.max(constraints.maxWidth, points.length * 55.0);
        return Semantics(
          label: '${line ? '趋势折线' : '分组柱状'}图，横向滑动查看更多，金额单位元',
          child: SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: SizedBox(
              height: 196,
              width: width,
              child: line
                  ? LineChart(
                      LineChartData(
                        minX: 0,
                        maxX: math.max(1, points.length - 1).toDouble(),
                        minY: minY,
                        maxY: maxY,
                        titlesData: titles,
                        gridData: grid,
                        borderData: FlBorderData(show: false),
                        lineBarsData: [
                          LineChartBarData(
                            spots: [
                              for (var i = 0; i < points.length; i++)
                                FlSpot(i.toDouble(), values[i]),
                            ],
                            color: colors.primary,
                            barWidth: 2.5,
                            isCurved: false,
                            dotData: FlDotData(show: points.length < 20),
                            belowBarData: BarAreaData(
                              show: true,
                              color: colors.primary.withValues(alpha: .08),
                            ),
                          ),
                        ],
                        lineTouchData: LineTouchData(
                          touchCallback: (event, response) {
                            final spots = response?.lineBarSpots;
                            if (spots != null && spots.isNotEmpty) {
                              setState(() => selected = spots.first.spotIndex);
                            }
                          },
                        ),
                      ),
                      duration: Duration.zero,
                    )
                  : BarChart(
                      BarChartData(
                        minY: minY,
                        maxY: maxY,
                        titlesData: titles,
                        gridData: grid,
                        borderData: FlBorderData(show: false),
                        barGroups: [
                          for (var i = 0; i < points.length; i++)
                            BarChartGroupData(
                              x: i,
                              barRods: [
                                BarChartRodData(
                                  toY: values[i],
                                  width: 24,
                                  color: colors.primary,
                                  borderRadius: const BorderRadius.vertical(
                                    top: Radius.circular(4),
                                  ),
                                ),
                              ],
                            ),
                        ],
                        barTouchData: BarTouchData(
                          touchCallback: (event, response) {
                            final index = response?.spot?.touchedBarGroupIndex;
                            if (index != null) {
                              setState(() => selected = index);
                            }
                          },
                        ),
                      ),
                      duration: Duration.zero,
                    ),
            ),
          ),
        );
      },
    );
  }
}

class AssistantArtifact extends StatelessWidget {
  const AssistantArtifact({required this.spec, super.key});
  final Map<String, dynamic> spec;

  @override
  Widget build(BuildContext context) {
    final format = spec['format'];
    final content = spec['content'];
    if (content is! String || content.isEmpty) {
      return const _ContentMessage('可视化内容不完整，请重新生成。');
    }
    Widget preview;
    try {
      if (format == 'svg') {
        final svg = checkedAssistantSvg(content);
        preview = SvgPicture.string(
          svg,
          fit: BoxFit.contain,
          semanticsLabel: spec['title'] as String? ?? 'AI 图示',
        );
      } else if (format == 'html') {
        checkedAssistantHtml(content);
        preview = _SandboxPreview(
          content: content,
          brightness: Theme.of(context).brightness,
          data: spec['data'] is Map
              ? Map<String, dynamic>.from(spec['data'] as Map)
              : null,
        );
      } else {
        return const _ContentMessage('此内容格式暂不支持，请让 AI 改用图表、HTML 或 SVG。');
      }
    } on FormatException {
      return const _ContentMessage('内容包含不支持的资源或格式，请让 AI 重新生成。');
    }
    final title = spec['title'] as String? ?? '可视化内容';
    return _ContentSurface(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _ContentTitle(
            title: title,
            onSave: () async {
              final exported = format == 'svg'
                  ? checkedAssistantSvg(content)
                  : assistantSandboxDocument(
                      content,
                      data: spec['data'] is Map
                          ? Map<String, dynamic>.from(spec['data'] as Map)
                          : null,
                    );
              await _saveContentFile(
                context,
                Uint8List.fromList(utf8.encode(exported)),
                title,
                format as String,
              );
            },
            onExpand: () => _openFullContent(
              context,
              title,
              format == 'svg'
                  ? SizedBox(
                      height: MediaQuery.sizeOf(context).height * .7,
                      child: InteractiveViewer(maxScale: 5, child: preview),
                    )
                  : Builder(
                      builder: (fullContext) => SizedBox(
                        height: MediaQuery.sizeOf(fullContext).height * .75,
                        child: _SandboxPreview(
                          content: content,
                          brightness: Theme.of(fullContext).brightness,
                          data: spec['data'] is Map
                              ? Map<String, dynamic>.from(spec['data'] as Map)
                              : null,
                        ),
                      ),
                    ),
            ),
          ),
          _EvidenceCaption(spec: spec),
          if (spec['template'] == 'custom' || spec['evidenceId'] == null)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text(
                'AI 生成展示',
                style: TextStyle(
                  fontSize: 12,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
            ),
          const SizedBox(height: 12),
          SizedBox(
            height: 300,
            width: double.infinity,
            child: ClipRRect(
              borderRadius: BorderRadius.circular(8),
              child: preview,
            ),
          ),
        ],
      ),
    );
  }
}

class _SandboxPreview extends StatefulWidget {
  const _SandboxPreview({
    required this.content,
    required this.brightness,
    this.data,
  });
  final String content;
  final Brightness brightness;
  final Map<String, dynamic>? data;

  @override
  State<_SandboxPreview> createState() => _SandboxPreviewState();
}

class _SandboxPreviewState extends State<_SandboxPreview> {
  WebViewController? controller;
  String? error;
  bool loading = true;
  Timer? timeout;

  bool get supported =>
      !kIsWeb &&
      const {
        TargetPlatform.android,
        TargetPlatform.iOS,
        TargetPlatform.macOS,
      }.contains(defaultTargetPlatform) &&
      WebViewPlatform.instance != null;

  @override
  void initState() {
    super.initState();
    if (supported) {
      unawaited(_load());
    }
  }

  @override
  void didUpdateWidget(covariant _SandboxPreview oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.content != widget.content ||
        oldWidget.brightness != widget.brightness ||
        jsonEncode(oldWidget.data) != jsonEncode(widget.data)) {
      timeout?.cancel();
      if (controller != null) {
        unawaited(
          controller!.loadHtmlString('<!doctype html><title>已关闭</title>'),
        );
      }
      controller = null;
      loading = true;
      error = null;
      if (supported) {
        unawaited(_load());
      }
    }
  }

  Future<void> _load() async {
    try {
      final view = WebViewController(
        onPermissionRequest: (request) => request.deny(),
      );
      controller = view;
      await view.setJavaScriptMode(JavaScriptMode.unrestricted);
      await view.setBackgroundColor(Colors.transparent);
      // No JavaScript channel is installed. Even the frame's generated code
      // cannot call app APIs or gain a parent origin.
      await view.setNavigationDelegate(
        NavigationDelegate(
          onNavigationRequest: (request) =>
              assistantArtifactNavigationAllowed(request.url)
              ? NavigationDecision.navigate
              : NavigationDecision.prevent,
          onPageFinished: (_) {
            if (mounted && identical(controller, view)) {
              timeout?.cancel();
              setState(() => loading = false);
            }
          },
          onWebResourceError: (failure) {
            if (failure.isForMainFrame == true &&
                mounted &&
                identical(controller, view)) {
              timeout?.cancel();
              setState(() {
                error = '交互内容加载失败，请重试。';
                loading = false;
              });
            }
          },
        ),
      );
      if (!mounted || !identical(controller, view)) {
        return;
      }
      timeout = Timer(const Duration(seconds: 15), () {
        if (mounted && loading) {
          setState(() {
            error = '交互内容加载超时，请重试。';
            loading = false;
          });
        }
      });
      await view.loadHtmlString(
        assistantSandboxDocument(
          widget.content,
          data: widget.data,
          dark: widget.brightness == Brightness.dark,
        ),
      );
      if (mounted) {
        setState(() {});
      }
    } on PlatformException {
      if (mounted) {
        setState(() {
          error = '设备未能打开交互内容，请重试。';
          loading = false;
        });
      }
    } on UnsupportedError {
      if (mounted) {
        setState(() {
          error = '当前设备不支持此交互内容。';
          loading = false;
        });
      }
    }
  }

  @override
  void dispose() {
    timeout?.cancel();
    if (controller != null) {
      unawaited(
        controller!.loadHtmlString('<!doctype html><title>已关闭</title>'),
      );
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (!supported) {
      return const _ContentMessage('此设备暂不支持 HTML 交互预览。图表与正文仍可正常查看。');
    }
    if (error != null) {
      return Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          _ContentMessage(error!),
          TextButton(
            onPressed: () {
              setState(() {
                error = null;
                loading = true;
              });
              unawaited(_load());
            },
            child: const Text('重新加载'),
          ),
        ],
      );
    }
    return Stack(
      children: [
        if (controller != null) WebViewWidget(controller: controller!),
        if (loading) const Center(child: CircularProgressIndicator()),
      ],
    );
  }
}

class _AssistantImage extends StatefulWidget {
  const _AssistantImage({required this.url});
  final String url;
  @override
  State<_AssistantImage> createState() => _AssistantImageState();
}

class _AssistantImageState extends State<_AssistantImage> {
  Uint8List? bytes;
  bool loading = false;
  String? error;
  http.Client? client;

  @override
  void initState() {
    super.initState();
    if (widget.url.startsWith('data:image/')) {
      unawaited(_load());
    }
  }

  @override
  void didUpdateWidget(covariant _AssistantImage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.url != widget.url) {
      client?.close();
      bytes = null;
      error = null;
      loading = false;
      if (widget.url.startsWith('data:image/')) {
        unawaited(_load());
      }
    }
  }

  Future<void> _load() async {
    final requestedUrl = widget.url;
    http.Client? requestClient;
    setState(() {
      loading = true;
      error = null;
    });
    try {
      Uint8List result;
      const allowedTypes = {
        'image/png',
        'image/jpeg',
        'image/webp',
        'image/gif',
      };
      if (widget.url.startsWith('data:')) {
        if (widget.url.length > 4 * 1024 * 1024) {
          throw const FormatException();
        }
        final data = UriData.parse(widget.url);
        if (!allowedTypes.contains(data.mimeType)) {
          throw const FormatException();
        }
        result = data.contentAsBytes();
      } else {
        final uri = Uri.tryParse(widget.url);
        if (uri == null ||
            uri.scheme != 'https' ||
            uri.host.isEmpty ||
            uri.userInfo.isNotEmpty) {
          throw const FormatException();
        }
        client?.close();
        final activeClient = http.Client();
        client = activeClient;
        requestClient = activeClient;
        final response = await activeClient
            .send(http.Request('GET', uri)..followRedirects = false)
            .timeout(const Duration(seconds: 20));
        if (response.statusCode != 200 ||
            !allowedTypes.contains(
              response.headers['content-type']?.split(';').first.trim(),
            )) {
          throw const FormatException();
        }
        final buffer = BytesBuilder(copy: false);
        await for (final chunk in response.stream.timeout(
          const Duration(seconds: 20),
        )) {
          if (buffer.length + chunk.length > 3 * 1024 * 1024) {
            throw const FormatException();
          }
          buffer.add(chunk);
        }
        result = buffer.takeBytes();
      }
      if (mounted && widget.url == requestedUrl) {
        setState(() => bytes = result);
      }
    } catch (_) {
      if (mounted && widget.url == requestedUrl) {
        setState(() => error = '图片未能加载，支持 3 MB 内的 PNG、JPEG、WebP 和 GIF。');
      }
    } finally {
      requestClient?.close();
      if (identical(client, requestClient)) {
        client = null;
      }
      if (mounted && widget.url == requestedUrl) {
        setState(() => loading = false);
      }
    }
  }

  @override
  void dispose() {
    client?.close();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (bytes != null) {
      return InkWell(
        onTap: () => _openFullContent(
          context,
          '图片',
          SizedBox(
            height: MediaQuery.sizeOf(context).height * .75,
            child: InteractiveViewer(
              maxScale: 5,
              child: Image.memory(
                bytes!,
                fit: BoxFit.contain,
                cacheWidth: 1800,
              ),
            ),
          ),
        ),
        child: Image.memory(
          bytes!,
          fit: BoxFit.contain,
          cacheWidth: 1200,
          height: 240,
          errorBuilder: (_, _, _) => const _ContentMessage('图片格式无法解码。'),
        ),
      );
    }
    if (loading) {
      return const SizedBox(
        height: 100,
        child: Center(child: CircularProgressIndicator()),
      );
    }
    final uri = Uri.tryParse(widget.url);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (error != null) _ContentMessage(error!),
        TextButton.icon(
          onPressed: _load,
          icon: const Icon(Icons.image_outlined),
          label: Text(
            error != null
                ? '重新加载图片'
                : '加载图片${uri?.host.isNotEmpty == true ? ' · ${uri!.host}' : ''}',
          ),
        ),
      ],
    );
  }
}

class _ChartPoint {
  const _ChartPoint(this.label, this.amount, this.query);
  final String label;
  final int amount;
  final Map<String, dynamic> query;
}

List<_ChartPoint> _chartPoints(Map<String, dynamic> spec) {
  final data = spec['data'] is Map ? spec['data'] as Map : const {};
  final metric = spec['metric'] == 'income' ? 'income' : 'expense';
  final query = spec['query'] is Map
      ? Map<String, dynamic>.from(spec['query'] as Map)
      : <String, dynamic>{};
  if (spec['chartType'] == 'comparison') {
    return [
      for (final key in ['a', 'b'])
        if (data[key] is Map)
          _ChartPoint(
            '${data[key]['start']}～${data[key]['end']}',
            data[key][metric] as int? ?? 0,
            {
              'start': data[key]['start'],
              'end': data[key]['end'],
              'status': 'CONFIRMED',
              ...query,
            }..removeWhere((k, _) => k.startsWith('period_')),
          ),
    ];
  }
  return [
    for (final row in data['groups'] as List? ?? [])
      if (row is Map && row['label'] is String && row[metric] is int)
        _ChartPoint(
          row['label'] as String,
          row[metric] as int,
          {
              ...query,
              'status': 'CONFIRMED',
              if (data['group_by'] == 'category')
                'category_id': row['category_id'],
              if (data['group_by'] == 'merchant') 'keyword': row['label'],
              if (data['group_by'] == 'day') ...{
                'start': row['label'],
                'end': row['label'],
              },
              if (data['group_by'] == 'month')
                ..._monthRange(row['label'] as String),
            }
            ..remove('offset')
            ..remove('limit')
            ..remove('group_by'),
        ),
  ];
}

Map<String, dynamic> _monthRange(String month) {
  final start = DateTime.tryParse('$month-01');
  if (start == null) {
    return {};
  }
  final last = DateTime(start.year, start.month + 1, 0).day;
  return {
    'start': '$month-01',
    'end': '$month-${last.toString().padLeft(2, '0')}',
  };
}

class _ChartValueRow extends StatelessWidget {
  const _ChartValueRow({required this.point, this.onTap});
  final _ChartPoint point;
  final VoidCallback? onTap;
  @override
  Widget build(BuildContext context) => InkWell(
    onTap: onTap,
    child: Padding(
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Text(point.label, style: const TextStyle(fontSize: 13)),
          ),
          const SizedBox(width: 12),
          Flexible(
            child: Text(
              '${formatMoney(point.amount)} 元',
              textAlign: TextAlign.right,
              style: const TextStyle(fontSize: 13, fontFeatures: moneyFigures),
            ),
          ),
          if (onTap != null)
            const Padding(
              padding: EdgeInsets.only(left: 4),
              child: Icon(Icons.chevron_right, size: 18),
            ),
        ],
      ),
    ),
  );
}

class _BudgetContent extends StatelessWidget {
  const _BudgetContent({required this.data});
  final Map<String, dynamic> data;
  @override
  Widget build(BuildContext context) {
    final budget = data['budget'] as int? ?? 0;
    final expense = data['expense'] as int? ?? 0;
    final remaining = budget - expense;
    final colors = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          budget > 0
              ? '${remaining < 0 ? '已超支' : '还可用'} ${formatMoney(remaining.abs())} 元'
              : '尚未设置月预算',
          style: TextStyle(
            fontSize: 20,
            fontWeight: FontWeight.w600,
            color: remaining < 0 ? colors.error : colors.onSurface,
            fontFeatures: moneyFigures,
          ),
        ),
        const SizedBox(height: 12),
        if (budget > 0)
          LinearProgressIndicator(
            value: (expense / budget).clamp(0, 1),
            minHeight: 8,
            borderRadius: BorderRadius.circular(4),
          ),
        const SizedBox(height: 12),
        Text(
          '净支出 ${formatMoney(expense)} 元${budget > 0 ? ' / 预算 ${formatMoney(budget)} 元' : ''}',
        ),
        if (data['remaining_days'] is int)
          Text('本月剩余 ${data['remaining_days']} 天'),
        const SizedBox(height: 8),
        Text(
          data['budget_scope'] as String? ?? '当前月预算设置',
          style: TextStyle(fontSize: 12, color: colors.onSurfaceVariant),
        ),
      ],
    );
  }
}

class _EvidenceCaption extends StatelessWidget {
  const _EvidenceCaption({required this.spec});
  final Map<String, dynamic> spec;
  @override
  Widget build(BuildContext context) {
    if (spec['evidenceId'] == null) {
      return const SizedBox.shrink();
    }
    final data = spec['data'] as Map? ?? const {};
    final time = DateTime.tryParse(spec['asOf'] as String? ?? '')?.toLocal();
    final date = time == null
        ? ''
        : '${time.year}-${time.month.toString().padLeft(2, '0')}-${time.day.toString().padLeft(2, '0')} ${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}';
    final range = data['start'] != null
        ? '${data['start']}～${data['end']}'
        : data['month'];
    return Text(
      [
        ?range,
        '已确认账目',
        '依据 ${spec['evidenceId']}',
        if (date.isNotEmpty) '查询于 $date',
      ].join(' · '),
      style: TextStyle(
        fontSize: 12,
        height: 1.5,
        color: Theme.of(context).colorScheme.onSurfaceVariant,
      ),
    );
  }
}

class _ContentSurface extends StatelessWidget {
  const _ContentSurface({required this.child});
  final Widget child;
  @override
  Widget build(BuildContext context) => Container(
    margin: const EdgeInsets.symmetric(vertical: 8),
    padding: const EdgeInsets.all(16),
    decoration: BoxDecoration(
      border: Border.all(color: Theme.of(context).colorScheme.outlineVariant),
      borderRadius: BorderRadius.circular(12),
    ),
    child: child,
  );
}

class _ContentTitle extends StatelessWidget {
  const _ContentTitle({
    required this.title,
    required this.onExpand,
    this.onSave,
  });
  final String title;
  final VoidCallback onExpand;
  final VoidCallback? onSave;
  @override
  Widget build(BuildContext context) => Row(
    children: [
      Expanded(
        child: Text(title, style: Theme.of(context).textTheme.titleMedium),
      ),
      if (onSave != null)
        IconButton(
          tooltip: '保存内容',
          onPressed: onSave,
          icon: const Icon(Icons.download_outlined, size: 18),
        ),
      IconButton(
        tooltip: '全屏查看',
        onPressed: onExpand,
        icon: const Icon(Icons.open_in_full, size: 18),
      ),
    ],
  );
}

class _ContentMessage extends StatelessWidget {
  const _ContentMessage(this.message);
  final String message;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 12),
    child: Text(
      message,
      style: TextStyle(
        color: Theme.of(context).colorScheme.onSurfaceVariant,
        height: 1.5,
      ),
    ),
  );
}

Future<void> _saveContentFile(
  BuildContext context,
  Uint8List bytes,
  String title,
  String extension,
) async {
  final name = title.replaceAll(RegExp(r'[\\/:*?"<>|\x00-\x1f]'), '_');
  final clipped = name.substring(0, math.min(60, name.length));
  try {
    final output = await FilePicker.saveFile(
      dialogTitle: '保存 $title',
      fileName: 'PennyPilot-${clipped.isEmpty ? '内容' : clipped}.$extension',
      type: FileType.custom,
      allowedExtensions: [extension],
      bytes: bytes,
    );
    if (output != null && context.mounted) {
      _contentNotice(context, '内容已保存。');
    }
  } on PlatformException {
    if (context.mounted) {
      _contentNotice(context, '保存失败，请重试或选择其他位置。');
    }
  }
}

List<Color> _chartColors(BuildContext context) => [
  Theme.of(context).colorScheme.primary,
  const Color(0xFF7A7150),
  const Color(0xFF9B6370),
  const Color(0xFF517967),
  const Color(0xFF786A95),
  const Color(0xFFA06447),
  const Color(0xFF6B7F87),
];

void _contentNotice(BuildContext context, String message) =>
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));

void _openFullContent(BuildContext context, String title, Widget content) {
  Navigator.of(context).push(
    MaterialPageRoute<void>(
      builder: (context) => Scaffold(
        appBar: AppBar(title: Text(title)),
        body: SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(20),
            child: content,
          ),
        ),
      ),
    ),
  );
}

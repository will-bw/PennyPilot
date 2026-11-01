import 'dart:convert';

import 'package:xml/xml.dart';

const maxArtifactCharacters = 16000;

/// SVG is rendered by Flutter, so only a finite, non-executable vector vocabulary
/// is accepted. External resources, CSS sheets and XML entities stay outside it.
String checkedAssistantSvg(String source) {
  if (source.trim().isEmpty || source.length > maxArtifactCharacters) {
    throw const FormatException('SVG 内容须为 1～16000 字符');
  }
  if (RegExp(
    r'<!DOCTYPE|<!ENTITY|<\?',
    caseSensitive: false,
  ).hasMatch(source)) {
    throw const FormatException('SVG 不能包含 XML 声明、实体或外部文档');
  }
  final document = XmlDocument.parse(source);
  final root = document.rootElement;
  if (root.name.local != 'svg') {
    throw const FormatException('SVG 须以 svg 元素为根节点');
  }
  const elements = {
    'svg',
    'g',
    'defs',
    'symbol',
    'use',
    'path',
    'rect',
    'circle',
    'ellipse',
    'line',
    'polyline',
    'polygon',
    'text',
    'tspan',
    'title',
    'desc',
    'linearGradient',
    'radialGradient',
    'stop',
    'clipPath',
    'mask',
  };
  const attributes = {
    'xmlns',
    'id',
    'viewBox',
    'width',
    'height',
    'x',
    'y',
    'x1',
    'x2',
    'y1',
    'y2',
    'cx',
    'cy',
    'r',
    'rx',
    'ry',
    'fx',
    'fy',
    'd',
    'points',
    'transform',
    'fill',
    'fill-rule',
    'fill-opacity',
    'stroke',
    'stroke-width',
    'stroke-linecap',
    'stroke-linejoin',
    'stroke-dasharray',
    'stroke-dashoffset',
    'stroke-opacity',
    'opacity',
    'font-family',
    'font-size',
    'font-weight',
    'font-style',
    'text-anchor',
    'dominant-baseline',
    'dx',
    'dy',
    'rotate',
    'textLength',
    'lengthAdjust',
    'preserveAspectRatio',
    'clip-path',
    'clip-rule',
    'mask',
    'maskUnits',
    'maskContentUnits',
    'gradientUnits',
    'gradientTransform',
    'offset',
    'stop-color',
    'stop-opacity',
    'spreadMethod',
    'href',
    'role',
    'aria-label',
    'space',
  };
  var count = 0;
  for (final element in root.descendants.whereType<XmlElement>().followedBy([
    root,
  ])) {
    if (++count > 1500 || !elements.contains(element.name.local)) {
      throw const FormatException('SVG 包含不支持的元素，或元素超过 1500 个');
    }
    if (element.name.prefix != null ||
        element.namespaceUri != null &&
            element.namespaceUri != 'http://www.w3.org/2000/svg') {
      throw const FormatException('SVG 只能使用标准 SVG 命名空间');
    }
    for (final attribute in element.attributes) {
      final name = attribute.name.local;
      final value = attribute.value;
      final isNamespace = attribute.name.prefix == 'xmlns';
      if (isNamespace) {
        if (name != 'xlink' || value != 'http://www.w3.org/1999/xlink') {
          throw const FormatException('SVG 包含不支持的命名空间');
        }
        continue;
      }
      if (!attributes.contains(name) ||
          value.length > 16000 ||
          attribute.name.prefix != null &&
              !const {'xml', 'xlink'}.contains(attribute.name.prefix)) {
        throw const FormatException('SVG 包含不支持的属性');
      }
      if (name == 'xmlns') {
        if (value != 'http://www.w3.org/2000/svg') {
          throw const FormatException('SVG 只能使用标准 SVG 命名空间');
        }
        continue;
      }
      if (name == 'href' && !RegExp(r'^#[A-Za-z_][\w:.-]*$').hasMatch(value) ||
          RegExp(
            r'javascript:|data:|https?:|file:|@|expression\s*\(',
            caseSensitive: false,
          ).hasMatch(value)) {
        throw const FormatException('SVG 不能加载外部资源或执行脚本');
      }
      for (final match in RegExp(
        r'url\s*\(([^)]*)\)',
        caseSensitive: false,
      ).allMatches(value)) {
        final reference = match
            .group(1)!
            .trim()
            .replaceAll(RegExp('[\'"]'), '');
        if (!RegExp(r'^#[A-Za-z_][\w:.-]*$').hasMatch(reference)) {
          throw const FormatException('SVG 资源只能引用图内标识');
        }
      }
    }
  }
  return root.toXmlString();
}

String checkedAssistantHtml(String source) {
  if (source.trim().isEmpty || source.length > maxArtifactCharacters) {
    throw const FormatException('HTML 内容须为 1～16000 字符');
  }
  return source;
}

/// The generated document lives in an opaque-origin sandbox without a native
/// bridge. The policy precedes generated markup and cannot be relaxed by it.
String assistantSandboxDocument(
  String content, {
  Map<String, dynamic>? data,
  bool? dark,
}) {
  checkedAssistantHtml(content);
  final scheme = dark == null
      ? 'light dark'
      : dark
      ? 'dark'
      : 'light';
  final background = dark == true ? '#191d21' : '#fcfcfa';
  final foreground = dark == true ? '#ebedeb' : '#303940';
  final accent = dark == true ? '#adc9df' : '#4d6b85';
  final appearance =
      'color-scheme:$scheme;background:$background;color:$foreground;--penny-accent:$accent;';
  final automaticDark = dark == null
      ? '@media(prefers-color-scheme:dark){html,body{background:#191d21;color:#ebedeb;--penny-accent:#adc9df}}'
      : '';
  const policy =
      "default-src 'none'; script-src 'unsafe-inline'; "
      "style-src 'unsafe-inline'; img-src data:; font-src 'none'; "
      "connect-src 'none'; frame-src 'none'; child-src 'none'; "
      "worker-src 'none'; object-src 'none'; media-src 'none'; "
      "base-uri 'none'; form-action 'none'";
  final encodedData = jsonEncode(data ?? const {}).replaceAll('<', r'\u003c');
  final inner =
      '<!doctype html><html lang="zh-CN"><head>'
      '<meta charset="utf-8"><meta name="viewport" '
      'content="width=device-width,initial-scale=1">'
      '<meta http-equiv="Content-Security-Policy" content="$policy">'
      '<meta name="referrer" content="no-referrer">'
      '<style>html{$appearance}body{margin:16px;background:inherit;color:inherit;'
      'font:15px/1.6 -apple-system,BlinkMacSystemFont,sans-serif;'
      'overflow-wrap:anywhere}*{box-sizing:border-box}img,svg,canvas{max-width:100%}$automaticDark'
      '</style><script>Object.defineProperty(window,"pennyData",'
      '{value:(function freeze(v){if(v&&typeof v==="object"){'
      'Object.values(v).forEach(freeze);Object.freeze(v);}return v;})($encodedData),'
      'writable:false,configurable:false});'
      'window.addEventListener("error",function(){var e=document.getElementById("pennypilot-render-error");'
      'if(e){e.hidden=false;e.textContent="交互内容未能完成渲染，请重新生成。";}});</script>'
      '</head><body><p id="pennypilot-render-error" role="alert" hidden></p>$content</body></html>';
  final escaped = const HtmlEscape(HtmlEscapeMode.attribute).convert(inner);
  return '<!doctype html><html><head><meta charset="utf-8">'
      '<meta name="viewport" content="width=device-width,initial-scale=1">'
      '<meta http-equiv="Content-Security-Policy" '
      'content="default-src \'none\'; frame-src about:; script-src \'unsafe-inline\'; '
      'img-src data:; style-src \'unsafe-inline\'; '
      'base-uri \'none\'; form-action \'none\'">'
      '<style>html,body{margin:0;height:100%;$appearance}'
      'iframe{border:0;width:100%;height:100%;background:transparent}$automaticDark</style>'
      '</head><body><iframe title="AI 交互内容" sandbox="allow-scripts" '
      'referrerpolicy="no-referrer" allow="camera \'none\'; microphone \'none\'; '
      'geolocation \'none\'; clipboard-read \'none\'; clipboard-write \'none\'; '
      'payment \'none\'" srcdoc="$escaped"></iframe></body></html>';
}

bool assistantArtifactNavigationAllowed(String url) {
  final uri = Uri.tryParse(url);
  return uri != null &&
      uri.scheme == 'about' &&
      const {'blank', 'srcdoc'}.contains(uri.path) &&
      !uri.hasQuery;
}

String assistantReportHtml(Map<String, dynamic> data, String title) {
  final safeTitle = const HtmlEscape().convert(title);
  return '''<h2>$safeTitle</h2><p>已确认账目 · 金额单位：元</p>
<label>查看 <select id="metric"><option value="expense">净支出</option><option value="income">收入</option></select></label>
<p id="total"></p><div id="rows"></div>
<style>h2{font-size:20px;margin:0 0 8px}select{font:inherit;padding:8px;border-radius:8px}section{padding:12px 0;border-bottom:1px solid #8796a433}progress{width:100%;accent-color:var(--penny-accent)}small{opacity:.75}strong{float:right}</style>
<script>
const d=window.pennyData;const m=document.getElementById('metric');
function money(v){return (v/100).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2});}
function draw(){const k=m.value;document.getElementById('total').textContent='合计 '+money(d[k]||0)+' 元';
const host=document.getElementById('rows');host.replaceChildren();
const rows=d.groups||[];const max=Math.max(1,...rows.map(r=>Math.abs(r[k]||0)));
for(const r of rows){const row=document.createElement('section');const label=document.createElement('span');label.textContent=r.label;const n=document.createElement('strong');n.textContent=money(r[k]||0)+' 元';const bar=document.createElement('progress');bar.max=max;bar.value=Math.abs(r[k]||0);row.append(label,n,bar);host.append(row);}}
m.addEventListener('change',draw);draw();
</script>''';
}

String assistantBudgetHtml(Map<String, dynamic> data, String title) {
  final safeTitle = const HtmlEscape().convert(title);
  return '''<h2>$safeTitle</h2><p>调整下方预算，预览本月余额。仅为模拟，不修改账本设置。</p>
<label for="budget">模拟月预算（元）</label><input id="budget" type="number" min="0" step="100" inputmode="decimal"><p id="balance" role="status"></p><progress id="progress" max="100"></progress><p id="daily"></p>
<style>h2{font-size:20px;margin-top:0}label{display:block}input{width:100%;font:inherit;padding:12px;border:1px solid #8796a4;border-radius:8px}progress{width:100%;accent-color:var(--penny-accent)}#balance{font-size:22px;font-weight:600}</style>
<script>
const d=window.pennyData;const input=document.getElementById('budget');input.value=((d.budget||0)/100).toFixed(2);
function money(v){return (v/100).toFixed(2);}
function draw(){const budget=Math.round(Number(input.value)*100);if(!Number.isFinite(budget)||budget<0){document.getElementById('balance').textContent='请输入有效预算';return;}const remaining=budget-(d.expense||0);document.getElementById('balance').textContent=(remaining>=0?'预计剩余 ':'预计超支 ')+money(Math.abs(remaining))+' 元';document.getElementById('progress').value=budget>0?Math.min(100,Math.max(0,(d.expense||0)*100/budget)):0;document.getElementById('daily').textContent=d.remaining_days>0?'剩余 '+d.remaining_days+' 天，日均可用 '+money(remaining/d.remaining_days)+' 元':'本月净支出 '+money(d.expense||0)+' 元';}
input.addEventListener('input',draw);draw();
</script>''';
}

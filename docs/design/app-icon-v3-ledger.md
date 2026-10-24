# PennyPilot 图标 v3 · 账本与页签

![PennyPilot 账本与页签图标](assets/pennypilot-icon-v3-ledger.png)

## 设计方向

按用户“直接重构、不要绿色、贴合整体 App”的要求重新设计。

主体采用完整的圆角账本轮廓、两条账目横线及嵌入式页签。构图重新建立，不沿用此前 P 字母与圆形硬币的组合。配色回到当前 Flutter 应用的纸白、雾蓝、暖杏；圆角与留白参照账本页的分组表面与按钮。

## 依据

- `DESIGN.md`：轻柔的日常账本、纸白底、雾蓝操作色、暖杏提示色。
- `lib/ui/theme.dart`：surface `#FCFCFA`、primary `#4D6B85`、secondaryContainer `#F5E9DD`。
- 本地组件截图 `build/test-artifacts/ledger-light-390-text-1.0.png`：用于核对界面风格，内容为合成测试数据。

## 交付

- `assets/pennypilot-icon-v3-ledger.png`：1254 × 1254 PNG。
- 使用内置 imagegen 从头生成，不以此前图标为编辑底图。
- 当前为图标设计稿，尚未替换 Android/iOS 启动资源，也未做真机桌面裁切验证。
- 提示词色值为设计目标；生成图片的像素颜色以实际文件为准，PNG 不是可编辑矢量源文件。

## 生成提示词

```text
Use case: logo-brand.
Asset type: FINAL mobile app icon master for PennyPilot, a quiet, friendly personal expense ledger.
Primary request: Create a completely NEW icon design, exquisitely simple and visually resolved, closely matching an app with paper-white surfaces, muted slate-blue buttons, soft corners, airy spacing and tiny warm-apricot accents.

Design ONE custom compact ledger-book symbol with an integrated bookmark, not a letter:
- Full-bleed perfectly flat paper-white background #FCFCFA.
- One clean muted slate-blue #4D6B85 ledger cover, roughly 50% of canvas width and 57% of its height, centered optically. Rounded but restrained corners, warm and approachable, straight-on view, pleasing balanced proportions.
- A narrow paper-white inset near the left edge subtly suggests a book spine; do not outline the whole object and do not draw stacked pages.
- Only TWO simple generous paper-white horizontal ledger entries, positioned comfortably in the upper/middle part of the cover with disciplined spacing, upper line longer than lower. Broad smooth rounded terminals, legible at tiny sizes.
- One warm-apricot #F5E9DD ribbon bookmark enters from the cover's top edge near the right side; its short V-notched end is inside the cover, integrated into the symbol, proportionately small. It must not overlap the two ledger lines.
Carefully tune the exact shape and spacing so this feels like a polished consumer finance app brand, not a generic clip-art document. The bookmark supplies the only warm accent. Quiet everyday-stationery friendliness, mature simplicity, generous negative space.

Style: beautifully constructed FLAT VECTOR logo artwork, 3 uniform solid colors, crisp clean contours and precise smooth curves. Absolute simplicity; a single coherent silhouette with a memorable tiny bookmark detail. No material rendering at all.
Framing: square 1:1, 1024x1024 or greater, full-bleed off-white background; meaningful parts inside the central safe area and safe for circular/squircle launcher crops. Do not pre-round the outer square.
Output ONLY the finished icon artwork. No mockup, grid, framing board, accompanying text or annotations.
Forbidden: ANY green, mint, teal or sage; letters including P; coin or disk or rings; currency signs; plants; AI stars; robots; charts; arrows; checkmarks; extra objects; heavy outlines; 3D; bevel; lighting; shadows; gradients; noise; paper texture; grain; glossy rendering; watermark; tiny details.
```

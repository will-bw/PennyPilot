# PennyPilot 图标 v4 · 小熊账本

![PennyPilot 小熊账本图标](assets/pennypilot-icon-v4-bear.png)

## 设计

按用户新增“小动物角色”的方向，以小熊的圆耳、简洁表情和两只前爪，组合一册雾蓝账本。纸白背景保持留白，暖杏熊与雾蓝账本承接应用现有的冷暖配色。没有使用绿色。

角色采用头部和前爪构图，避免额外场景与装饰；封面保留两条简洁账目线，让记账用途仍可识别。用户本轮明确允许小动物角色，此方向据此调整了此前纯账本图标的设定。

## 文件

- 图标：`assets/pennypilot-icon-v4-bear.png`
- 尺寸：1254 × 1254。
- 生成方式：内置 imagegen，从头生成。
- 已接入 Android 和 iOS 启动图标；正式源文件及导出方法见 [品牌资源](../../assets/branding/README.md)。
- Android 调试包与 iOS 模拟器包构建通过。Android 已在测试模拟器覆盖安装并检查启动器裁切；未做真机桌面验证。
- 提示词色值是设计目标，像素颜色以实际生成文件为准。

## 生成提示词

```text
Use case: logo-brand.
Asset type: a polished mobile app icon for PennyPilot, a calm and friendly personal daily expense ledger.
Primary request: Introduce a charming small animal character into the app identity: a simple round-eared little bear quietly holding its daily ledger. Sophisticated, warm, memorable, beautifully drawn, appropriate for an adult personal finance app.

Composition: ONE tight unified icon made from a bear's head, two small front paws, and a notebook. No full body. Front-facing, balanced and optically centered, generous clear margins. The bear head occupies the upper part, a prominent slate-blue ledger occupies the lower part, the bear's two small paws rest at its left and right edges. The entire character-plus-ledger occupies about 60% of the square width and 60% of its height, fully safe inside a circular launcher crop. Keep the book clearly legible instead of hidden by paws.

Character: soft round head, small circular ears, understated round cheeks as shape only, tiny dark slate-blue dot eyes and a tiny simple nose/mouth. A calm gently attentive expression. Minimal face, no big anime eyes, no blush, no eyelashes, no eyebrows, no clothing or accessories. The bear is warm almond/apricot #DEC2A6, with a very small lighter cream muzzle if needed; visible enough against the background. The silhouette should have refined organic geometry, not a generic stock clip-art bear.
Ledger: a single clean rounded slate mist-blue #4D6B85 notebook cover, around 43% of canvas width; at most TWO short wide paper-white horizontal entries. A subtle page edge may be hinted at, but no extra bookmark or decorative props. The ledger must read immediately as the animal's useful everyday notebook.
Background: perfectly uniform full-bleed paper-white #FCFCFA. No container border, no pre-rounded outer corners.
Color relationship: main app colors are paper-white #FCFCFA, muted slate-blue #4D6B85, warm-apricot #F5E9DD. The bear uses a slightly deeper warm-apricot #DEC2A6 for contrast. Slate-blue for facial features. NO green, mint, sage or teal anywhere.

Style: impeccably clean flat vector-like character brand design; solid color areas, carefully tuned rounded contours, few graphic shapes, gentle personality. Light everyday stationery feeling, confident and simple, excellent at 48px. Subtle charm with restraint, not an illustrated scene.
Output only one square finished app icon master at 1024x1024 or greater.
Avoid: letters, P monograms, words, dollar signs, coins, money bags, plants, hearts, stars, sparkle, hats, props, landscape, sticker outlines, thick black strokes, dramatic expression, infantilized cartoon, children's book style, 3D plush, furry texture, grain, gradients, shadows, gloss, lighting, mockups, presentation sheets, watermark.
```

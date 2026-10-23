# PennyPilot 启动图标

`app-icon.png` 是已选定的小熊抱账本原画：纸白底、暖杏小熊、雾蓝账本。

在仓库根目录运行以下命令重新导出平台资源，需要 Python 和 ImageMagick：

```sh
python tool/generate_app_icons.py
```

- iOS：按 `AppIcon.appiconset/Contents.json` 导出全部 15 个尺寸，保留 RGB、不含透明通道；系统负责圆角。
- Android：导出 `app/src/main/res/drawable-nodpi/ic_launcher_bear.png`。Flutter Android 与原生构建直接共用该资源目录。前景 XML 将原画放在 108dp 图层中央的 78dp 正方形内，保留系统裁切空间；背景为纸白。
- Android 单色主题图标：`ic_launcher_monochrome.xml` 使用熊头、前爪和账本的简化矢量轮廓，保留表情与账目镂空，由系统着色。

尺寸导出不改变原画的构图和颜色，不修改版本号、签名或启动画面。

平台规范：[Android 自适应图标](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive?hl=en)、[Apple AppIcon 资源配置](https://developer.apple.com/documentation/xcode/configuring-your-app-icon)。

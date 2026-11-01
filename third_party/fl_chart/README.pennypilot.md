# PennyPilot local fl_chart patch

This directory contains the Dart `lib/`, `LICENSE` and `pubspec.yaml` from the
resolved **fl_chart 1.3.0** package. Examples, upstream tests, screenshots and
build output are not vendored. The package is marked non-publishable and its
omitted publication screenshots are removed from the manifest. The upstream MIT
license is retained in `LICENSE`.

- Upstream repository: https://github.com/imaNNeo/fl_chart
- Package: https://pub.dev/packages/fl_chart/versions/1.3.0
- Original hosted archive SHA-256, recorded in PennyPilot's `pubspec.lock`
  before switching to the local path dependency:
  `5ed69b8be550c6276c8f0bc25c09704fb061018ed1b1681867ad18fcf477e7b7`

## Local changes

`lib/src/utils/canvas_wrapper.dart` uses LF line endings instead of the hosted
archive's CRLF; its source text is otherwise unchanged.

`RenderBaseChart` registers an unrestricted pan recognizer alongside tap and
long-press recognizers. A fast first pointer movement can let that pan recognizer
win before a surrounding vertical or horizontal scroll view, even for completed
chart content.

The patch adds `FlTouchData.panEnabled`, defaulting to `true` to preserve upstream
behavior. `BarTouchData`, `LineTouchData` and `PieTouchData` expose it and include
it in equality checks; the existing bar and line `copyWith` methods preserve it.
`RenderBaseChart` skips registration of
the pan recognizer when this flag is `false`; tap, long-press, hover, tooltips,
chart geometry and drawing are unchanged.

PennyPilot disables only this pan recognizer on its embedded charts. The outer
conversation keeps vertical drags, and wide chart scroll views keep horizontal
drags. Regressions are covered by
`test/ui/assistant_chart_gestures_test.dart` in the app repository.

When upgrading, compare these five patched files with the new upstream package:

- `lib/src/chart/base/base_chart/base_chart_data.dart`
- `lib/src/chart/base/base_chart/render_base_chart.dart`
- `lib/src/chart/bar_chart/bar_chart_data.dart`
- `lib/src/chart/line_chart/line_chart_data.dart`
- `lib/src/chart/pie_chart/pie_chart_data.dart`

Remove the local path dependency when upstream offers an equivalent option and
the app's scrolling, chart selection and detail-navigation regressions pass.

import 'package:flutter/material.dart';

import 'settings_widgets.dart';

Future<bool> confirmScreenCapture(
  BuildContext context,
) => confirmSettingsAction(
  context,
  title: '开启支付页面读取？',
  message:
      'PennyPilot 会通过系统无障碍服务读取微信、支付宝、美团和抖音的屏幕可见文字，在本机判断是否为支付结果，生成待确认账单。\n\n只保存识别出的交易字段，不读取密码或输入内容，也不会代你点击、付款或操作其他应用。截图补充需在“识别与录入”中单独开启；未开启时不截图，开启后按需在本机识别，不自动上传图片。\n\n部分页面可能无法读取，不能保证覆盖每笔支付。你可以随时在本页暂停，或在系统设置中撤销授权。',
  confirmLabel: '同意并开启',
);

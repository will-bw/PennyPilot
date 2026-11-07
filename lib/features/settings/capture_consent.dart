import 'package:flutter/material.dart';

import 'settings_widgets.dart';

Future<bool> confirmScreenCapture(
  BuildContext context,
) => confirmSettingsAction(
  context,
  title: '开启支付页面读取？',
  message:
      'PennyPilot 会通过系统无障碍服务读取微信、支付宝、云闪付、美团和抖音的屏幕可见文字，在本机判断是否为支付结果，按你的设置自动记账或生成待确认账单。\n\n只保存识别出的交易字段，不读取密码或输入内容，也不会代你点击、付款或操作其他应用。本地或云端的支付截图识别均需在“识别与自动录入”中单独开启。\n\n部分页面可能无法读取，不能保证覆盖每笔支付。你可以随时在本页暂停，或在系统设置中撤销授权。',
  confirmLabel: '同意并开启',
);

Future<bool> confirmCloudReceiptVision(
  BuildContext context, {
  String? serviceName,
}) => confirmSettingsAction(
  context,
  title: '允许发送支付截图给云端模型？',
  message:
      '开启页面采集后，PennyPilot 会按需将符合采集条件的支付结果窗口截图直接发送给你配置的图片模型服务。'
      '${serviceName == null ? '' : '当前所选服务是 $serviceName。'}'
      '截图可能包含收付款人姓名、商户、金额和订单号。\n\n'
      '姓名等截图字段由图片模型直接读取。只有截图能提供交易信息时，结果会留待你确认。你可以随时关闭这项授权。',
  confirmLabel: '同意并开启',
);

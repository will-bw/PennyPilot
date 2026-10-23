# PennyPilot
<!-- impeccable:product-schema: 1 -->

## Purpose
面向个人的本地优先 AI 记账工具。手动、自然语言、语音和支付事件采集共同完成记账；用户确认账单后查看支出、收入与月度报告。

## Audience and use
中文 Android 与 iOS 用户在支付后、通勤或晚间回顾支出。常见任务是快速记下一笔、检查预算和查询账本；Android 用户还可核对自动采集的支付记录。

## Platform
android, ios

## Stack
Flutter / Dart 共用界面、账务业务、AI 解析、问账与录音转写；Android 保留 Room、支付采集和原有 ChatGPT 账号适配器以兼容现有数据。iOS 使用 SQLite、Keychain 和系统内嵌浏览器登录。两端语音转写使用独立配置的云端语音模型。保留 com.pennypilot.app 应用 ID；两端使用同一 JSON 备份格式。

## Scope
尽量完整支持 Android 与 iOS：账单录入与编辑、文字/语音解析、AI 问账、分类、搜索筛选、月份切换、预算、待确认、月报、备份恢复与权限引导。Android 的跨应用通知/页面采集属于平台扩展，iOS 不显示其授权入口。当前不包含桌面/Web 发布和云端自动同步；以实际构建与设备测试记录描述验收状态。

## Constraints
真实账本不注入示例数据。收入负分、支出正分的存储约定保持兼容。凭据只保存在设备安全存储，账本备份不含密钥。AI 输出必须经用户核对入账。

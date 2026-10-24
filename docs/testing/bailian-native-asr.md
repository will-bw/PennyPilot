# 百炼原生语音接入验证（v0.5.2）

日期：2026-10-24，北京时间。用户要求继续使用 `qwen-audio-3.0-asr-flash`，保留手机当前 Base URL、API Key 与模型配置，并完成真机转写验证。

## 根因

手机原配置为 `provider=qwen`、北京业务空间域名及 `/compatible-mode/v1` 基础路径、模型 `qwen-audio-3.0-asr-flash`，密钥已加密保存。

旧实现将所有 `qwen` 模型发往 `/chat/completions`，使用顶层 `messages` 和 `asr_options`，只解析 `choices[0].message.content`。这是 Qwen3-ASR 的兼容协议；Qwen-Audio-3.0-ASR-Flash 使用百炼原生协议，不能只更换模型名或 URL 后缀。

依据：[百炼 Qwen-Audio-ASR 原生 HTTP API](https://help.aliyun.com/zh/model-studio/fun-asr-flash-recorded-speech-recognition-http-api)。

## 修复

- `VoiceConfiguration` 根据模型选择原生同步语音协议。已保存配置、默认配置和安全存储格式不迁移、不重写。
- 运行时将同一 origin 下的 `/compatible-mode/v1` 映射为 `/api/v1/services/aigc/multimodal-generation/generation`。保留域名、地区、端口和可识别的网关前缀；同时支持根地址、`/api/v1` 与完整原生端点。未知路径在打开麦克风前报错，不猜测新主机、不转发密钥到重定向地址。
- 原生请求使用 `model`、`input.messages[].content[].input_audio.data` 以及 `parameters={format: wav, sample_rate: "16000"}`，指定 `X-DashScope-SSE: disable`。
- 音频依旧通过手机麦克风采集为单声道 16 kHz / PCM16，并只在内存中封装 WAV/Base64。结果优先解析 `output.text`；只有 `sentence_end=true` 时才可使用缺少汇总文本的 `output.sentence.text`。未完成、空或类型错误的结果不作为成功转写。
- 保留请求超时、取消代际、体积上限和不反射服务端密钥/音频正文的错误处理。原有 Qwen3-ASR 兼容模式与 multipart 转写接口不改变。
- 界面按当前模型解释原生接口与基础地址兼容行为；开始测试不触发保存设置，不写入账目。

## 自动化结果

- 语音服务与设置页面定向回归：59 项通过。
- Flutter 完整测试：336 项通过；静态分析无问题。
- `git diff --check` 通过。
- 正式签名 Release 构建与 `python tool/prepare_release.py` 校验通过，版本 `0.5.2 (10)`，包名与固定签名证书不变。

新增覆盖：原生请求体、WAV 编码、Bearer 凭据、禁用 SSE/重定向、同 origin 地址派生、配置密文保持、最终句兜底、空/畸形/未完成返回、取消后迟到结果、错误协议在录音前拒绝，以及测试页面沿用原配置而不另存。

## 真机验收

- 在 RMX3888 / Android 16 上覆盖安装成功；未卸载、未清数据，账本及原有待确认记录保留。
- 对安装前后的完整地址与模型公开字段做逐项比对，`baseUrlUnchanged=true`、`modelUnchanged=true`；模型仍是 `qwen-audio-3.0-asr-flash`。未点击保存配置，未从手机导出、回显或重新输入密钥，继续使用原先加密保存的值鉴权。
- 约 13:46，在手机设置页实际启动麦克风、结束录音，并使用原配置获得“转写成功”。这证明原生请求、既有密钥鉴权和返回解析可用。
- 首轮返回内容不是预定的电脑播放短句，因此仅作为真实连通证据，不作为受控短句准确性验收。
- 约 13:48，用户在手机测试页重新录音，朗读“今天午饭二十八元，地铁四元”，随后结束并转写。实际界面显示“转写成功”，返回 **“今天午饭28元，地铁4元。”**，与朗读内容及两笔金额一致；用户随后确认已完成操作。
- 两次验证均使用“语音模型 → 测试语音转写”，未进入账单生成或保存流程。手机保留在第二次成功结果页，供用户直接核对。

本文件不保存用户密钥、业务空间标识、录音或无关转写内容。普通话短句成功也不能代表所有方言、环境噪声和网络条件均已验证。

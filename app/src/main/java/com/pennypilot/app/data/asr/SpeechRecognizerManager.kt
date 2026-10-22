package com.pennypilot.app.data.asr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wraps Android's SpeechRecognizer for press-and-hold voice input.
 * Uses the installed system recognition service and reports failures to the caller.
 */
class SpeechRecognizerManager(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private var session = 0L

    fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit) {
        val currentSession = ++session
        _partialText.value = ""

        // Destroy previous instance
        recognizer?.destroy()
        recognizer = null
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                _isListening.value = false
                onError("此设备没有可用的语音识别服务，请使用文字输入")
                return
            }
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(listener(currentSession, onResult, onError))
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

            _isListening.value = true
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            _isListening.value = false
            onError("无法启动语音识别，请检查麦克风权限和系统语音服务")
        }
    }

    fun stopListening() {
        recognizer?.stopListening()
    }

    fun cancel() {
        session++
        recognizer?.cancel()
        _isListening.value = false
    }

    fun destroy() {
        session++
        recognizer?.destroy()
        recognizer = null
        _isListening.value = false
    }

    private fun listener(currentSession: Long, onResult: (String) -> Unit, onFailure: (String) -> Unit) = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            if (currentSession != session) return
            session++
            _isListening.value = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            if (text.isNotBlank()) {
                onResult(text)
            } else {
                onFailure("未识别到语音，请按住麦克风重新说一次")
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (currentSession != session) return
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            matches?.firstOrNull()?.let { _partialText.value = it }
        }

        override fun onError(error: Int) {
            if (currentSession != session) return
            session++
            _isListening.value = false
            onFailure(when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "未获得录音权限，请在系统设置中开启麦克风权限"
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未识别到语音，请按住麦克风重新说一次"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络不可用，请重试或使用文字输入"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音服务忙，请稍后再试"
                else -> "语音识别失败，请重试或使用文字输入（错误 $error）"
            })
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { if (currentSession == session) _isListening.value = false }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}

package com.pennypilot.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable
fun InputDock(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onMicPress: () -> Unit,
    onMicRelease: () -> Unit,
    modifier: Modifier = Modifier,
    isRecording: Boolean = false,
    isSending: Boolean = false,
    placeholder: String = "输入或按住说话…",
    showMicrophone: Boolean = true,
    enabled: Boolean = true,
    isRecognizing: Boolean = false
) {
    val currentMicPress by rememberUpdatedState(onMicPress)
    val currentMicRelease by rememberUpdatedState(onMicRelease)
    val canSend = enabled && !isSending && !isRecording && !isRecognizing && text.isNotBlank()
    val microphoneEnabled = enabled && !isRecognizing
    val microphoneInteraction = remember { MutableInteractionSource() }
    var inputFocused by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (isRecording || inputFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier.padding(6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            if (showMicrophone) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (isRecording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer)
                        .indication(microphoneInteraction, ripple(bounded = true))
                        .semantics(mergeDescendants = true) {
                            contentDescription = if (isRecording) "结束录音" else "语音输入"
                            role = Role.Button
                            stateDescription = if (isRecording) "正在录音" else if (isRecognizing) "正在识别" else "按住说话"
                            if (!microphoneEnabled) disabled()
                            onClick(label = if (isRecording) "结束录音" else "开始录音") {
                                if (microphoneEnabled) {
                                    if (isRecording) currentMicRelease() else currentMicPress()
                                }
                                microphoneEnabled
                            }
                        }
                        .pointerInput(microphoneEnabled) {
                            if (microphoneEnabled) {
                                detectTapGestures(onPress = {
                                    val press = PressInteraction.Press(it)
                                    microphoneInteraction.emit(press)
                                    var released = false
                                    try {
                                        currentMicPress()
                                        released = tryAwaitRelease()
                                    } finally {
                                        microphoneInteraction.tryEmit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                                        currentMicRelease()
                                    }
                                })
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isRecording) Icons.Default.Stop else Icons.Outlined.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = if (isRecording) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.width(6.dp))
            }

            Box(
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (isRecording || isRecognizing) {
                    Text(
                        if (isRecording) "正在录音，松开结束" else "正在识别语音…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        enabled = enabled,
                        minLines = 1,
                        maxLines = 4,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (canSend) onSend(text) }),
                        modifier = Modifier.fillMaxWidth().onFocusChanged { inputFocused = it.isFocused }
                            .semantics { contentDescription = "输入内容" },
                        decorationBox = { innerTextField ->
                            if (text.isEmpty()) {
                                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            innerTextField()
                        }
                    )
                }
            }

            FilledIconButton(
                onClick = { onSend(text) },
                enabled = canSend,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    disabledContainerColor = if (isSending) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                )
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp).semantics { contentDescription = "正在处理" },
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "发送", modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

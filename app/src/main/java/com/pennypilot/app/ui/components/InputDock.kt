package com.pennypilot.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pennypilot.app.ui.theme.LightIncome

@Composable
fun InputDock(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onMicPress: () -> Unit,
    onMicRelease: () -> Unit,
    isRecording: Boolean = false,
    placeholder: String = "输入或按住说话…",
    modifier: Modifier = Modifier
) {
    val borderColor = if (isRecording) LightIncome else MaterialTheme.colorScheme.outline
    val shape = RoundedCornerShape(99.dp)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.5.dp, borderColor, shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mic button — press and hold
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isRecording) LightIncome
                        else MaterialTheme.colorScheme.primaryContainer
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                onMicPress()
                                tryAwaitRelease()
                                onMicRelease()
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "🎤",
                    fontSize = 17.sp,
                    modifier = Modifier
                        .scale(if (isRecording) 1.1f else 1f)
                )
            }

            Spacer(Modifier.width(8.dp))

            // Wave animation when recording
            AnimatedVisibility(
                visible = isRecording,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.weight(1f)
            ) {
                WaveAnimation()
            }

            // Text input (hidden when recording)
            AnimatedVisibility(visible = !isRecording, enter = fadeIn(), exit = fadeOut()) {
                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    textStyle = MaterialTheme.typography.bodyLarge
                )
            }

            Spacer(Modifier.width(4.dp))

            // Send button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { if (text.isNotBlank()) onSend(text) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "↑",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun WaveAnimation() {
    val transition = rememberInfiniteTransition(label = "wave")
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(24.dp)
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        repeat(5) { i ->
            val scale by transition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1.2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = i * 120),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar$i"
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(20.dp)
                    .scale(scaleY = scale, scaleX = 1f)
                    .background(LightIncome, RoundedCornerShape(2.dp))
            )
        }
    }
}

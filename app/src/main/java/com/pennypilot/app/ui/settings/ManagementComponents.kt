package com.pennypilot.app.ui.settings

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pennypilot.app.ui.theme.PennyMotion

@Composable
internal fun ManagementHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 0.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("返回")
        }
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ManagementMessage(text: String, error: Boolean = false) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().animateContentSize(PennyMotion.spatial())
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
}

/** Keep the message alive until its exit completes, without announcing stale text. */
@Composable
internal fun ManagementNotice(text: String?, error: Boolean = false) {
    var lastText by remember { mutableStateOf(text.orEmpty()) }
    SideEffect { if (text != null) lastText = text }
    AnimatedVisibility(
        visible = text != null,
        enter = expandVertically(PennyMotion.spatial()) + fadeIn(tween(PennyMotion.Enter)),
        exit = shrinkVertically(PennyMotion.spatial()) + fadeOut(tween(PennyMotion.Exit)),
        modifier = if (text == null) Modifier.clearAndSetSemantics { } else Modifier
    ) {
        ManagementMessage(text ?: lastText, error)
    }
}

/** Fade through discrete states; never crossfade two editable forms on top of one another. */
@Composable
internal fun <T> ManagementTransition(
    targetState: T,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit
) {
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(PennyMotion.Enter, delayMillis = PennyMotion.Exit)) togetherWith
                fadeOut(tween(PennyMotion.Exit))).using(
                SizeTransform(clip = true, sizeAnimationSpec = { _, _ -> PennyMotion.spatial() })
            )
        },
        label = "managementState"
    ) { state ->
        Box(if (state == targetState) Modifier else Modifier.clearAndSetSemantics { }) {
            content(state)
        }
    }
}

@Composable
internal fun ManagementProgress(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(PennyMotion.spatial()) + fadeIn(tween(PennyMotion.Enter)),
        exit = shrinkVertically(PennyMotion.spatial()) + fadeOut(tween(PennyMotion.Exit))
    ) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

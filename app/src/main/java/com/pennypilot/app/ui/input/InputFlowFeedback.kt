package com.pennypilot.app.ui.input

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pennypilot.app.ui.theme.PennyMotion
import com.pennypilot.app.util.MoneyUtil

private enum class InputStage { COMPOSE, PARSING, REVIEW, SAVING, SAVED }

private val AiInputUiState.stage: InputStage
    get() = when {
        savingItems.isNotEmpty() -> InputStage.SAVING
        isParsing -> InputStage.PARSING
        previewItems.isNotEmpty() -> InputStage.REVIEW
        lastSave != null -> InputStage.SAVED
        else -> InputStage.COMPOSE
    }

/** Keep recognition and completion in the same place instead of resetting the screen. */
@Composable
internal fun InputFlowFeedback(state: AiInputUiState, onNavigateToManual: () -> Unit, onViewLedger: () -> Unit) {
    val emphasized = state.stage == InputStage.COMPOSE || state.stage == InputStage.SAVED
    val containerColor by animateColorAsState(
        if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        PennyMotion.effects(), label = "inputFlowContainer"
    )
    val contentColor by animateColorAsState(
        if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        PennyMotion.effects(), label = "inputFlowContent"
    )
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth()
    ) {
        AnimatedContent(
            targetState = state,
            contentKey = { it.stage },
            transitionSpec = {
                (fadeIn(tween(PennyMotion.Feedback, delayMillis = PennyMotion.Exit)) togetherWith fadeOut(tween(PennyMotion.Exit)))
                    .using(SizeTransform { _, _ -> PennyMotion.spatial() })
            },
            label = "inputFlow"
        ) { current ->
            val stage = current.stage
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (stage == InputStage.SAVED) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(24.dp))
                    }
                    Text(
                        when (stage) {
                            InputStage.COMPOSE -> "说一句，记下多笔账"
                            InputStage.PARSING -> "正在识别账目…"
                            InputStage.REVIEW -> "核对这 ${current.previewItems.size} 笔账目"
                            InputStage.SAVING -> "正在保存 ${current.savingItems.size} 笔账目…"
                            InputStage.SAVED -> "已保存 ${current.lastSave?.count ?: 0} 笔账目"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).semantics { heading(); liveRegion = LiveRegionMode.Polite }
                    )
                }
                when (stage) {
                    InputStage.COMPOSE -> {
                        Text("“昨天午饭 25 元，打车去公司 32 元。”", style = MaterialTheme.typography.bodyLarge)
                        Text("输入或按住说话，识别后核对，再保存到账本。", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onNavigateToManual) { Text("打开完整记账表单") }
                    }
                    InputStage.PARSING -> {
                        Text(current.submittedText, style = MaterialTheme.typography.bodyLarge,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(if (current.previewItems.isEmpty()) "正在整理金额、分类和时间，识别后请核对。"
                            else "已有账目保留在下方，新识别的账目会追加到列表。",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    InputStage.REVIEW -> Text("点按账目即可修改，确认无误后保存。", style = MaterialTheme.typography.bodyMedium)
                    InputStage.SAVING -> Text("核对结果会保留到保存完成。", style = MaterialTheme.typography.bodyMedium)
                    InputStage.SAVED -> {
                        current.lastSave?.let { receipt ->
                            Text(listOfNotNull(
                                receipt.expense.takeIf { it > 0 }?.let { "支出 ¥${MoneyUtil.formatHeroAmount(it)}" },
                                receipt.income.takeIf { it > 0 }?.let { "收入 ¥${MoneyUtil.formatHeroAmount(it)}" }
                            ).joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
                        }
                        Text("已计入账本，可以继续记下一笔。", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onViewLedger) { Text("查看流水") }
                    }
                }
            }
        }
    }
}

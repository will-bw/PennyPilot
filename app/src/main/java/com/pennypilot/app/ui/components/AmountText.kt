package com.pennypilot.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import com.pennypilot.app.domain.model.TxType
import com.pennypilot.app.ui.theme.incomeColor
import com.pennypilot.app.util.MoneyUtil

@Composable
fun AmountText(amount: Long, type: TxType, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.titleLarge) {
    val isExpense = type == TxType.EXPENSE
    Text(
        text = MoneyUtil.formatForDisplay(amount, isExpense),
        style = style,
        color = if (isExpense) MaterialTheme.colorScheme.onSurface else MaterialTheme.incomeColor,
        maxLines = 1,
        modifier = modifier.horizontalScroll(rememberScrollState()).clearAndSetSemantics {
            contentDescription = "${if (isExpense) "支出" else "收入"}${MoneyUtil.formatHeroAmount(kotlin.math.abs(amount))}元"
        }
    )
}

package com.pennypilot.app.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import com.pennypilot.app.domain.model.TxType
import com.pennypilot.app.ui.theme.DarkIncome
import com.pennypilot.app.ui.theme.LightIncome
import com.pennypilot.app.util.MoneyUtil

@Composable
fun AmountText(amount: Long, type: TxType, style: TextStyle = MaterialTheme.typography.titleLarge) {
    val isExpense = type == TxType.EXPENSE
    val incomeColor = if (isSystemInDarkTheme()) DarkIncome else LightIncome
    val color = if (isExpense) MaterialTheme.colorScheme.onSurface else incomeColor
    Text(
        text = MoneyUtil.formatForDisplay(amount, isExpense),
        style = style,
        color = color,
    )
}

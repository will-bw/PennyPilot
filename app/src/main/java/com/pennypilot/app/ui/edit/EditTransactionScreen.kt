package com.pennypilot.app.ui.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennypilot.app.ui.components.CatIcon

@Composable
fun EditTransactionScreen(
    onBack: () -> Unit,
    vm: EditTransactionViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            if (state.isEditing) "编辑账目" else "记一笔",
            style = MaterialTheme.typography.headlineLarge
        )

        // Amount input
        OutlinedTextField(
            value = state.amountText,
            onValueChange = vm::updateAmount,
            label = { Text("金额（元）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Category grid
        Text("分类", style = MaterialTheme.typography.titleMedium)
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.height(220.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.categories) { cat ->
                val selected = cat.id == state.selectedCategoryId
                Column(
                    modifier = Modifier
                        .clickable { vm.selectCategory(cat.id) }
                        .alpha(if (selected) 1f else 0.6f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CatIcon(colorIndex = cat.colorIndex, emoji = cat.icon, size = 42)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        cat.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Note
        OutlinedTextField(
            value = state.note, onValueChange = vm::updateNote,
            label = { Text("备注") }, modifier = Modifier.fillMaxWidth(), singleLine = true
        )

        // Merchant
        OutlinedTextField(
            value = state.merchant, onValueChange = vm::updateMerchant,
            label = { Text("商户") }, modifier = Modifier.fillMaxWidth(), singleLine = true
        )

        Spacer(Modifier.height(16.dp))

        // Save button
        Button(
            onClick = { vm.save(onBack) },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = MaterialTheme.shapes.medium
        ) { Text("保存") }

        // Delete button (edit mode only)
        if (state.isEditing) {
            OutlinedButton(
                onClick = { vm.delete(onBack) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("删除") }
        }
    }
}

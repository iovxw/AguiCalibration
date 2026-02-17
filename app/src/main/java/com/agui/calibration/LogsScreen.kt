package com.agui.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun LogsScreen(logState: AppLogState) {
    Text("日志", style = MaterialTheme.typography.headlineMedium)
    Button(onClick = logState::clear) {
        Text("清空日志")
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(CommonPadding), verticalArrangement = Arrangement.spacedBy(CommonItemSpacing)) {
            if (logState.logs.value.isEmpty()) {
                Text("暂无日志")
            } else {
                logState.logs.value.forEach {
                    Text(it, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
        }
    }
}
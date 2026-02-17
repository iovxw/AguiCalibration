package com.agui.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

internal val CommonPadding: Dp = 16.dp
internal val CommonSpacing: Dp = 12.dp
internal val CommonItemSpacing: Dp = 6.dp

@Composable
internal fun CalibrationApp(
    selectedTab: MutableState<CalibrationTab>,
    authorizationPageState: AuthorizationPageState,
    gSensorPageState: GSensorPageState,
    gyroscopePageState: GyroscopePageState,
    alspsPageState: AlspsPageState,
    logState: AppLogState,
    onTabSelected: (CalibrationTab) -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                CalibrationTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab.value == tab,
                        onClick = { onTabSelected(tab) },
                        icon = { Text(tab.iconText) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ScreenColumn {
                when (selectedTab.value) {
                    CalibrationTab.Authorization -> AuthorizationScreen(authorizationPageState)
                    CalibrationTab.GSensor -> GSensorScreen(gSensorPageState)
                    CalibrationTab.Gyroscope -> GyroscopeScreen(gyroscopePageState)
                    CalibrationTab.Alsps -> AlspsScreen(alspsPageState)
                    CalibrationTab.Logs -> LogsScreen(logState)
                }
            }
        }
    }
}

@Composable
internal fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(CommonPadding)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(CommonSpacing),
        content = content
    )
}

@Composable
internal fun StatusCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(CommonPadding), verticalArrangement = Arrangement.spacedBy(CommonItemSpacing)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body)
        }
    }
}

@Composable
internal fun SensorCard(title: String, values: FloatArray) {
    StatusCard(
        title,
        String.format(
            Locale.US,
            "X = %.3f\nY = %.3f\nZ = %.3f",
            values.getOrElse(0) { 0f },
            values.getOrElse(1) { 0f },
            values.getOrElse(2) { 0f }
        )
    )
}

@Composable
internal fun ActionRow(
    primaryLabel: String,
    primaryEnabled: Boolean,
    primaryBusy: Boolean,
    onPrimary: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(CommonSpacing)) {
        Button(onClick = onPrimary, enabled = primaryEnabled) {
            if (primaryBusy) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
            } else {
                Text(primaryLabel)
            }
        }
    }
}

@Composable
internal fun FullWidthBusyButton(
    label: String,
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
        } else {
            Text(label)
        }
    }
}

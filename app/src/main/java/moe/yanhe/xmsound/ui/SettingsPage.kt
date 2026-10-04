package moe.yanhe.xmsound.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.yanhe.xmsound.R
import moe.yanhe.xmsound.hook.HookModuleInfo

@Composable
fun SettingsPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var autoConnect by remember { mutableStateOf(AppPrefs.autoConnect(context)) }
    var island by remember { mutableStateOf(AppPrefs.islandEnabled(context)) }
    var card by remember { mutableStateOf(AppPrefs.notificationCardEnabled(context)) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(horizontal = 16.dp, vertical = 12.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                SwitchRow(
                    title = stringResource(R.string.auto_connect),
                    summary = stringResource(R.string.auto_connect_summary),
                    checked = autoConnect,
                    onCheckedChange = {
                        autoConnect = it
                        AppPrefs.setAutoConnect(context, it)
                    },
                )
                HorizontalDivider()
                SwitchRow(
                    title = stringResource(R.string.notif_island),
                    summary = stringResource(R.string.notif_island_summary),
                    checked = island,
                    onCheckedChange = {
                        island = it
                        AppPrefs.setIslandEnabled(context, it)
                    },
                )
                HorizontalDivider()
                SwitchRow(
                    title = stringResource(R.string.notif_card),
                    summary = stringResource(R.string.notif_card_summary),
                    checked = card,
                    onCheckedChange = {
                        card = it
                        AppPrefs.setNotificationCardEnabled(context, it)
                    },
                )
            }
        }

        ModuleStatusCard()
    }
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ModuleStatusCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.module),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.module_scope_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HookModuleInfo.scopePackages.forEach { pkg ->
                Text(
                    text = "•  $pkg",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

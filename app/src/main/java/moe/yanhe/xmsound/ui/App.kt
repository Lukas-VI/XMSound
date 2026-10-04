package moe.yanhe.xmsound.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import moe.yanhe.xmsound.R

@Composable
fun XMSoundApp(
    ui: HeadsetUiState,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!permissionsGranted) {
        PermissionGate(onRequestPermissions, modifier)
        return
    }

    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf(
        R.string.tab_earphones,
        R.string.tab_settings,
        R.string.tab_debug,
        R.string.tab_about,
    )

    Column(modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            titles.forEachIndexed { index, title ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(stringResource(title)) },
                )
            }
        }

        when (tab) {
            0 -> EarphonesPage(ui)
            1 -> SettingsPage()
            2 -> DebugPage(ui)
            else -> AboutPage()
        }
    }
}

/**
 * HyperOS does not let `adb shell pm grant` hand out Bluetooth runtime permissions, so the user has
 * to confirm once from the UI. A plain button is far more reliable than firing the system dialog
 * from a side effect.
 */
@Composable
private fun PermissionGate(onRequest: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.bt_permission_required),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRequest) {
            Text(stringResource(R.string.grant_permission))
        }
    }
}

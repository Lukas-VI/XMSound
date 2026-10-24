package moe.yanhe.xmsound.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import moe.yanhe.xmsound.R

private class NavItem(val labelRes: Int, val iconRes: Int)

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

    var tab by rememberSaveable { mutableIntStateOf(0) }
    val items = listOf(
        NavItem(R.string.tab_earphones, R.drawable.ic_nav_headset),
        NavItem(R.string.tab_settings, R.drawable.ic_nav_settings),
        NavItem(R.string.tab_debug, R.drawable.ic_nav_log),
        NavItem(R.string.tab_about, R.drawable.ic_nav_about),
    )

    Column(modifier.fillMaxSize()) {
        Header(ui, Modifier.fillMaxWidth())

        Box(Modifier.fillMaxSize().weight(1f)) {
            when (tab) {
                0 -> EarphonesPage(ui)
                1 -> SettingsPage(ui)
                2 -> DebugPage(ui)
                else -> AboutPage()
            }
        }

        NavigationBar {
            items.forEachIndexed { index, item ->
                NavigationBarItem(
                    selected = tab == index,
                    onClick = { tab = index },
                    icon = { Icon(painterResource(item.iconRes), contentDescription = null) },
                    label = { Text(stringResource(item.labelRes)) },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ header

@Composable
private fun Header(ui: HeadsetUiState, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.app_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ConnectionPill(ui.connected)
    }
}

@Composable
private fun ConnectionPill(connected: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (connected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (connected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                    ),
            )
            Text(
                text = stringResource(
                    if (connected) R.string.conn_connected else R.string.conn_disconnected,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = if (connected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------------ permission gate

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

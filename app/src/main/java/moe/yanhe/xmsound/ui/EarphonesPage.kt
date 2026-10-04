package moe.yanhe.xmsound.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.yanhe.xmsound.R
import moe.yanhe.xmsound.pods.sony.SonyBatteryState
import moe.yanhe.xmsound.pods.sony.SonyBudBattery
import moe.yanhe.xmsound.pods.sony.SonyHeadsetState
import moe.yanhe.xmsound.pods.sony.SonyNoiseMode
import moe.yanhe.xmsound.pods.sony.SonyProtocol

@Composable
fun EarphonesPage(ui: HeadsetUiState, modifier: Modifier = Modifier) {
    val state = ui.headset

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        item { StatusCard(ui) }
        if (ui.connected) {
            item { BatteryCard(state.battery) }
            item { NoiseControlCard(state, ui) }
        } else {
            item { DevicePickerCard(ui) }
        }
    }
}

// ------------------------------------------------------------------ status

@Composable
private fun StatusCard(ui: HeadsetUiState) {
    val state = ui.headset
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(10.dp)
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            if (ui.connected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (ui.connected) R.string.conn_connected else R.string.conn_disconnected,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            val device = ui.controllerRef.selectedDevice
            InfoRow(
                stringResource(R.string.label_device),
                device?.let { "${it.name ?: stringResource(R.string.unknown_device)}  ${it.address}" }
                    ?: stringResource(R.string.unknown_device),
            )
            InfoRow(
                stringResource(R.string.label_protocol),
                when (state.protocolVersion) {
                    2 -> stringResource(R.string.protocol_v2)
                    1 -> stringResource(R.string.protocol_v1)
                    else -> stringResource(R.string.protocol_unknown)
                },
            )
            state.firmware?.let { InfoRow(stringResource(R.string.label_firmware), it) }

            ui.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.connected) {
                    OutlinedButton(onClick = { ui.controllerRef.refresh() }) {
                        Text(stringResource(R.string.retry))
                    }
                    OutlinedButton(onClick = { ui.controllerRef.disconnect() }) {
                        Text(stringResource(R.string.disconnect))
                    }
                } else {
                    Button(onClick = { ui.controllerRef.connect() }) {
                        Text(stringResource(R.string.connect))
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

// ------------------------------------------------------------------ device picker

@Composable
private fun DevicePickerCard(ui: HeadsetUiState) {
    val controller = ui.controllerRef
    val devices = remember(ui.connected) { controller.candidateDevices() }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.select_device),
                style = MaterialTheme.typography.titleMedium,
            )
            if (devices.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_paired_devices),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            devices.forEach { device ->
                val name = runCatching { device.name }.getOrNull()
                    ?: stringResource(R.string.unknown_device)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { controller.selectDevice(device) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            device.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = {
                        controller.selectDevice(device)
                        controller.connect()
                    }) {
                        Text(stringResource(R.string.connect))
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ battery

@Composable
private fun BatteryCard(battery: SonyBatteryState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.battery_title),
                style = MaterialTheme.typography.titleMedium,
            )
            BatteryRow(stringResource(R.string.batt_left_pod), battery.left)
            BatteryRow(stringResource(R.string.batt_right_pod), battery.right)
            BatteryRow(stringResource(R.string.pod_case), battery.case)
        }
    }
}

@Composable
private fun BatteryRow(label: String, battery: SonyBudBattery?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(64.dp))
            Text(
                text = battery?.let { stringResource(R.string.percent_format, it.level) }
                    ?: stringResource(R.string.battery_unknown),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (battery?.charging == true) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.battery_charging),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        BatteryBar(battery)
    }
}

@Composable
private fun BatteryBar(battery: SonyBudBattery?) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = if (battery?.charging == true) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.primary
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(track),
    ) {
        val fraction = (battery?.level ?: 0).coerceIn(0, 100) / 100f
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(4.dp))
                    .background(fill),
            )
        }
    }
}

// ------------------------------------------------------------------ noise control

@Composable
private fun NoiseControlCard(state: SonyHeadsetState, ui: HeadsetUiState) {
    val controller = ui.controllerRef
    val noise = state.noise

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.noise_control_title),
                style = MaterialTheme.typography.titleMedium,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(
                    label = stringResource(R.string.noise_control_off),
                    selected = noise.mode == SonyNoiseMode.OFF,
                ) { controller.setNoiseMode(SonyNoiseMode.OFF) }

                ModeChip(
                    label = stringResource(R.string.noise_control_nc),
                    selected = noise.mode == SonyNoiseMode.NOISE_CANCELLING,
                ) { controller.setNoiseMode(SonyNoiseMode.NOISE_CANCELLING) }

                ModeChip(
                    label = stringResource(R.string.noise_control_ambient),
                    selected = noise.mode == SonyNoiseMode.AMBIENT_SOUND,
                ) { controller.setNoiseMode(SonyNoiseMode.AMBIENT_SOUND) }
            }

            if (noise.mode == SonyNoiseMode.AMBIENT_SOUND) {
                AmbientLevelSlider(noise.ambientLevel, onCommit = { controller.setAmbientLevel(it) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.voice_passthrough),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = noise.focusOnVoice,
                        onCheckedChange = { controller.setFocusOnVoice(it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

/**
 * The wire value is 0-20. The slider keeps a local value while dragging and only commits on
 * release, because every committed write makes the headset play a confirmation tone.
 */
@Composable
private fun AmbientLevelSlider(level: Int, onCommit: (Int) -> Unit) {
    var local by remember(level) { mutableFloatStateOf(level.toFloat()) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(
                text = stringResource(R.string.ambient_level),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = local.toInt().toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local.toInt()) },
            valueRange = SonyProtocol.AMBIENT_LEVEL_MIN.toFloat()..SonyProtocol.AMBIENT_LEVEL_MAX.toFloat(),
            steps = SonyProtocol.AMBIENT_LEVEL_MAX - SonyProtocol.AMBIENT_LEVEL_MIN - 1,
        )
    }
}

package moe.yanhe.xmsound.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        item { HeroCard(ui) }
        if (ui.connected) {
            item { BatteryCard(state.battery) }
            item { NoiseControlCard(state, ui) }
        } else {
            item { DevicePickerCard(ui) }
        }
    }
}

// ------------------------------------------------------------------ hero

/**
 * One card for "what am I talking to": device name, connection state, protocol/firmware facts and
 * the primary action - instead of a status list plus a separate battery card plus a picker.
 */
@Composable
private fun HeroCard(ui: HeadsetUiState) {
    val state = ui.headset
    val device = ui.controllerRef.selectedDevice
    val deviceName = device?.let { runCatching { it.name }.getOrNull() }
        ?: stringResource(R.string.unknown_device)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_nav_headset),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = deviceName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (ui.connected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                ),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(
                                if (ui.connected) R.string.conn_connected else R.string.conn_disconnected,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetaChip(
                    stringResource(R.string.label_protocol) + " " + when (state.protocolVersion) {
                        2 -> stringResource(R.string.protocol_v2)
                        1 -> stringResource(R.string.protocol_v1)
                        else -> stringResource(R.string.protocol_unknown)
                    },
                )
                state.firmware?.let {
                    MetaChip(stringResource(R.string.label_firmware) + " " + it)
                }
            }

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
private fun MetaChip(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

// ------------------------------------------------------------------ device picker

@Composable
private fun DevicePickerCard(ui: HeadsetUiState) {
    val controller = ui.controllerRef
    val devices = remember(ui.connected) { controller.candidateDevices() }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.select_device),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
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
                    FilledTonalButton(onClick = {
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

/** The three parts side by side - the shape every first-party earbud UI uses. */
@Composable
private fun BatteryCard(battery: SonyBatteryState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = stringResource(R.string.battery_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BatteryCell(stringResource(R.string.batt_left_pod), battery.left, Modifier.weight(1f))
                BatteryCell(stringResource(R.string.batt_right_pod), battery.right, Modifier.weight(1f))
                BatteryCell(stringResource(R.string.pod_case), battery.case, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BatteryCell(label: String, battery: SonyBudBattery?, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = battery?.let { stringResource(R.string.percent_format, it.level) }
                ?: stringResource(R.string.battery_unknown),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        BatteryBar(battery)
        if (battery?.charging == true) {
            Text(
                text = stringResource(R.string.battery_charging),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        } else {
            Spacer(Modifier.height(14.dp))
        }
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
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(track),
    ) {
        val fraction = (battery?.level ?: 0).coerceIn(0, 100) / 100f
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(3.dp))
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
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = stringResource(R.string.noise_control_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            NoiseSegmented(
                selected = noise.mode,
                onSelect = { controller.setNoiseMode(it) },
            )

            Text(
                text = stringResource(
                    when (noise.mode) {
                        SonyNoiseMode.NOISE_CANCELLING -> R.string.nc_nc_summary
                        SonyNoiseMode.AMBIENT_SOUND -> R.string.nc_ambient_summary
                        else -> R.string.nc_off_summary
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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

private class NoiseOption(
    val mode: SonyNoiseMode,
    val labelRes: Int,
    val iconRes: Int,
)

/** One control that looks like the system's own: a filled segment per mode, icons above labels. */
@Composable
private fun NoiseSegmented(selected: SonyNoiseMode, onSelect: (SonyNoiseMode) -> Unit) {
    val options = listOf(
        NoiseOption(SonyNoiseMode.OFF, R.string.noise_control_off, R.drawable.ic_anc_off),
        NoiseOption(SonyNoiseMode.NOISE_CANCELLING, R.string.noise_control_nc, R.drawable.ic_anc_nc),
        NoiseOption(SonyNoiseMode.AMBIENT_SOUND, R.string.noise_control_ambient, R.drawable.ic_anc_ambient),
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEach { option ->
                val isSelected = selected == option.mode
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(option.mode) },
                ) {
                    Column(
                        Modifier.padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            painter = painterResource(option.iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(option.labelRes),
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
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

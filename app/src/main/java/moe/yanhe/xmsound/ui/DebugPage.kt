package moe.yanhe.xmsound.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import moe.yanhe.xmsound.R
import moe.yanhe.xmsound.pods.sony.TrafficLine

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

@Composable
fun DebugPage(ui: HeadsetUiState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lines = ui.traffic
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.debug_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(top = 12.dp),
            )
            TextButton(onClick = { ui.controllerRef.clearTraffic() }) {
                Text(stringResource(R.string.debug_clear))
            }
            TextButton(onClick = {
                val text = lines.joinToString("\n") {
                    "${TIME_FORMAT.format(Date(it.timestamp))} ${it.direction} ${it.text}"
                }
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("XMSound log", text))
            }) {
                Text(stringResource(R.string.debug_copy))
            }
        }

        if (lines.isEmpty()) {
            Text(
                text = stringResource(R.string.debug_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            items(lines) { line -> TrafficRow(line) }
        }
    }
}

@Composable
private fun TrafficRow(line: TrafficLine) {
    val color = when (line.direction) {
        '>' -> MaterialTheme.colorScheme.primary
        '<' -> MaterialTheme.colorScheme.tertiary
        '!' -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = TIME_FORMAT.format(Date(line.timestamp)),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "  ${line.direction}  ${line.text}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = color,
        )
    }
}

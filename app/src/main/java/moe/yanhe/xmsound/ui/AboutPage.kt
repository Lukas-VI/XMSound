package moe.yanhe.xmsound.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.yanhe.xmsound.BuildConfig
import moe.yanhe.xmsound.R

@Composable
fun AboutPage(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(horizontal = 16.dp, vertical = 12.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.about_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.about_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${stringResource(R.string.about_version)}  ${BuildConfig.VERSION_NAME} " +
                        "(${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.about_based_on),
                    style = MaterialTheme.typography.titleMedium,
                )
                listOf(
                    "OppoPods" to "https://github.com/1812z/OppoPods",
                    "HyperPods" to "https://github.com/Art-Chen/HyperPods",
                    "Gadgetbridge" to "https://codeberg.org/Freeyourgadget/Gadgetbridge",
                    "SonyHeadphonesClient" to "https://github.com/Plutoberth/SonyHeadphonesClient",
                    "HyperEars" to "https://github.com/silverpoetry/HyperEars",
                    "Miuix" to "https://github.com/YuKongA/miuix",
                ).forEach { (name, url) ->
                    Column {
                        Text(name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

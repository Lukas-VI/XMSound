package moe.yanhe.xmsound

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import moe.yanhe.xmsound.pods.sony.SonyHeadsetController
import moe.yanhe.xmsound.ui.AppPrefs
import moe.yanhe.xmsound.ui.HeadsetUiState
import moe.yanhe.xmsound.ui.XMSoundApp
import moe.yanhe.xmsound.ui.XMSoundTheme

class MainActivity : ComponentActivity() {

    companion object {
        /** Runtime permissions required to discover and talk to the headset on API 31+. */
        val REQUIRED_PERMISSIONS = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }.toTypedArray()

        fun hasBluetoothPermissions(context: Context): Boolean =
            REQUIRED_PERMISSIONS.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XMSoundTheme {
                val context = LocalContext.current
                val controller = remember { SonyHeadsetController.get(context) }
                val ui = remember { HeadsetUiState(controller) }

                var granted by remember { mutableStateOf(hasBluetoothPermissions(context)) }
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { result ->
                    granted = result.values.all { it } || hasBluetoothPermissions(context)
                }

                LaunchedEffect(granted) {
                    if (!granted && REQUIRED_PERMISSIONS.isNotEmpty()) {
                        launcher.launch(REQUIRED_PERMISSIONS)
                    }
                }

                DisposableEffect(ui) {
                    ui.attach()
                    onDispose { ui.detach() }
                }

                LaunchedEffect(granted) {
                    if (granted && AppPrefs.autoConnect(context)) controller.connect()
                }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    XMSoundApp(
                        ui = ui,
                        permissionsGranted = granted,
                        onRequestPermissions = {
                            if (REQUIRED_PERMISSIONS.isNotEmpty()) {
                                launcher.launch(REQUIRED_PERMISSIONS)
                            }
                        },
                    )
                }
            }
        }
    }
}

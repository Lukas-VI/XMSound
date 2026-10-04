package moe.yanhe.xmsound.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import moe.yanhe.xmsound.pods.sony.SonyHeadsetController
import moe.yanhe.xmsound.pods.sony.SonyHeadsetState
import moe.yanhe.xmsound.pods.sony.TrafficLine

/**
 * Bridges the thread-safe [SonyHeadsetController] into Compose state. Every callback arrives on the
 * main thread (the controller marshals for us), so writing the snapshot state here is safe.
 */
class HeadsetUiState(private val controller: SonyHeadsetController) {

    var headset by mutableStateOf(controller.state)
        private set

    var connected by mutableStateOf(controller.isConnected)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var traffic by mutableStateOf(controller.traffic())
        private set

    val controllerRef: SonyHeadsetController get() = controller

    private val observer = object : SonyHeadsetController.Observer {
        override fun onStateChanged(state: SonyHeadsetState) {
            headset = state
        }

        override fun onConnectionChanged(connected: Boolean, message: String?) {
            this@HeadsetUiState.connected = connected
            error = if (connected) null else message ?: error
        }

        override fun onTraffic(line: TrafficLine) {
            traffic = controller.traffic()
        }
    }

    fun attach() {
        controller.addObserver(observer)
        headset = controller.state
        connected = controller.isConnected
        traffic = controller.traffic()
    }

    fun detach() {
        controller.removeObserver(observer)
    }
}

package moe.yanhe.xmsound.hook.runtime

import android.bluetooth.BluetoothDevice
import moe.yanhe.xmsound.hook.HookLog

/**
 * Drives A2DP / HFP profile connections from inside the Bluetooth process - the executor behind
 * 耳机流转 (audio handoff).
 *
 * HyperOS' device center offers 流转 for first-party earbuds: bring the headset's audio to this
 * phone, or release it to the headset's other multipoint host. A Sony WF-1000XM5 speaks none of
 * Xiaomi's orchestration protocol, but it *is* a dual-host multipoint headset: connecting or
 * disconnecting the audio profiles on this phone produces exactly the same user-visible switch.
 *
 * We run in `com.android.bluetooth`, where the profile services live in the same process, so a
 * direct reflective call is both the simplest and the cheapest path - no binder hop, no proxy
 * binding, and no permission check applies to an in-process call. Class names follow AOSP
 * (`A2dpService` / `HeadsetService`); the static getters are probed with fallbacks because HyperOS
 * trims some of them between releases.
 */
object BtProfiles {

    private const val TAG = "BtProfiles"

    /** (class, static getter candidates) for the services we drive. */
    private val SERVICES = listOf(
        "com.android.bluetooth.a2dp.A2dpService" to listOf("getA2dpService", "getService"),
        "com.android.bluetooth.hfp.HeadsetService" to listOf("getHeadsetService", "getService"),
    )

    @Volatile
    var hostClassLoader: ClassLoader? = null

    private fun service(className: String, getters: List<String>): Any? {
        val loader = hostClassLoader ?: ClassLoader.getSystemClassLoader()
        val cls = runCatching { Class.forName(className, false, loader) }.getOrNull() ?: run {
            HookLog.w(TAG, "MISSING $className")
            return null
        }
        for (name in getters) {
            val instance = runCatching {
                cls.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
                    ?.invoke(null)
            }.getOrNull()
            if (instance != null) return instance
        }
        // Some builds keep the singleton in a static field instead of a getter.
        return runCatching {
            cls.declaredFields.firstOrNull {
                java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.type == cls
            }?.apply { isAccessible = true }?.get(null)
        }.getOrNull()
    }

    /** Connect (`connect = true`) or disconnect the audio profiles for [device] on this phone. */
    fun setConnected(device: BluetoothDevice, connect: Boolean): Boolean {
        var any = false
        val methodName = if (connect) "connect" else "disconnect"
        for ((className, getters) in SERVICES) {
            val svc = service(className, getters) ?: continue
            runCatching {
                val method = svc.javaClass.methods.firstOrNull {
                    it.name == methodName &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == BluetoothDevice::class.java
                }
                if (method == null) {
                    HookLog.w(TAG, "no $methodName(BluetoothDevice) on $className")
                    return@runCatching
                }
                method.isAccessible = true
                val accepted = method.invoke(svc, device)
                HookLog.i(TAG, "${className.substringAfterLast('.')}.$methodName(${device.address}) -> $accepted")
                any = true
            }.onFailure { HookLog.w(TAG, "$className failed: ${it.message}") }
        }
        return any
    }

    /**
     * Whether A2DP reports the device connected here. Best-effort: the owner also tracks the
     * stack's own connection-state broadcasts, this covers the state at process start.
     */
    fun a2dpConnected(device: BluetoothDevice): Boolean {
        val svc = service(SERVICES[0].first, SERVICES[0].second) ?: return false
        return runCatching {
            val method = svc.javaClass.methods.firstOrNull {
                it.name == "getConnectionState" &&
                    it.parameterTypes.contentEquals(arrayOf(BluetoothDevice::class.java))
            } ?: return false
            method.isAccessible = true
            // android.bluetooth.BluetoothProfile.STATE_CONNECTED == 2
            method.invoke(svc, device) == 2
        }.getOrDefault(false)
    }
}

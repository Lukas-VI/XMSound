package moe.yanhe.xmsound.hook.bluetooth

import java.util.concurrent.atomic.AtomicInteger
import moe.yanhe.xmsound.hook.HookLog
import moe.yanhe.xmsound.hook.SafeHookContext

/**
 * Reconnaissance on HyperOS' device-identification path inside `com.android.bluetooth`.
 *
 * This ROM identifies third-party earbuds from a **vendor product info** blob that the Bluetooth
 * stack hands to `HyperAdapterService`:
 *
 * ```
 * devicePropertyChangedCallback(byte[] propBytes, int[] propTypes, byte[][] values,
 *                               RemoteDevices remoteDevices, AdapterService adapterService)
 * handleAirpodsInfo(AdapterService adapterService, byte[] payload, String address)
 * ```
 *
 * The module has to make HyperOS accept a WF-1000XM5, and this is where the acceptance decision is
 * made. This hook is read-only: it reports the property stream and the meaning of the property IDs
 * so the right injection point can be chosen from evidence rather than guesswork.
 */
class HyperAdapterProbe : SafeHookContext() {

    override val tag: String get() = "HyperAdapter"

    private val budget = AtomicInteger(MAX_LINES)

    override fun onHook() {
        dumpPropertyIds()

        install("HyperAdapterService#devicePropertyChangedCallback") {
            val method = findMethodByParamCount(HYPER_ADAPTER_SERVICE, "devicePropertyChangedCallback", 5)
            hookBefore(method) {
                val propBytes = args.getOrNull(0) as? ByteArray
                val propTypes = args.getOrNull(1) as? IntArray
                val values = args.getOrNull(2) as? Array<*>
                report(
                    "devicePropertyChanged",
                    "propBytes=${hex(propBytes)} propTypes=${propTypes?.toList()} " +
                        "values=${values?.joinToString { hex(it as? ByteArray) }}",
                )
            }
        }

        install("HyperAdapterService#handleAirpodsInfo") {
            val method = findMethodByParamCount(HYPER_ADAPTER_SERVICE, "handleAirpodsInfo", 3)
            hookBefore(method) {
                val payload = args.getOrNull(1) as? ByteArray
                val address = args.getOrNull(2) as? String
                report("handleAirpodsInfo", "address=$address payload=${hex(payload)} ascii=${ascii(payload)}")
            }
        }

        install("HyperAdapterService#updateName") {
            val method = findMethodByParamCount(HYPER_ADAPTER_SERVICE, "updateName", 3)
            hookBefore(method) {
                report("updateName", "args=${args.map { it?.toString() }}")
            }
        }

        install("HyperAdapterService#batteryServiceConnectDevice") {
            val method = findMethodByParamCount(HYPER_ADAPTER_SERVICE, "batteryServiceConnectDevice", 3)
            hookBefore(method) {
                report("batteryServiceConnectDevice", "args=${args.map { it?.toString() }}")
            }
        }

        HookLog.i(tag, "HyperAdapter probe installed in $packageName")
    }

    /**
     * `MiAbstractionLayer` holds the property IDs, and constants are inlined by javac - reading them
     * back through reflection is the only way to learn which ID means what on this ROM.
     */
    private fun dumpPropertyIds() {
        val layer = findClassOrNull(MI_ABSTRACTION_LAYER) ?: run {
            HookLog.w(tag, "MISSING $MI_ABSTRACTION_LAYER")
            return
        }
        layer.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType }
            .sortedBy { it.name }
            .forEach { f ->
                runCatching {
                    f.isAccessible = true
                    HookLog.i(tag, "PROPERTY ${f.name} = 0x%02X".format(f.getInt(null)))
                }
            }
    }

    private fun report(label: String, detail: String) {
        // The property stream can be chatty; keep a full record of the start and then only changes.
        if (budget.decrementAndGet() > 0) {
            HookLog.i(tag, "$label $detail")
        } else {
            logOnChange(label, detail)
        }
    }

    private fun hex(bytes: ByteArray?): String =
        bytes?.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) } ?: "null"

    private fun ascii(bytes: ByteArray?): String =
        bytes?.takeIf { it.isNotEmpty() }
            ?.joinToString("") { b -> (b.toInt() and 0xFF).toChar().takeIf { it.code in 32..126 }?.toString() ?: "." }
            ?: "null"

    private companion object {
        const val HYPER_ADAPTER_SERVICE = "com.android.bluetooth.btservice.HyperAdapterService"
        const val MI_ABSTRACTION_LAYER = "com.android.bluetooth.btservice.HyperAdapterService\$MiAbstractionLayer"
        const val MAX_LINES = 120
    }
}

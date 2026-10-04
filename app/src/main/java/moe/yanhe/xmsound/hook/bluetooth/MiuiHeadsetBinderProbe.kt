package moe.yanhe.xmsound.hook.bluetooth

import android.os.IBinder
import android.os.Parcel
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import moe.yanhe.xmsound.hook.HookLog
import moe.yanhe.xmsound.hook.SafeHookContext

/**
 * Discovers the Xiaomi headset Binder protocol at the transaction level.
 *
 * `com.android.bluetooth.ble.app.headset.BluetoothHeadsetService` is where HyperOS gets headset
 * battery / ANC state and where the settings UI sends ANC changes, but its 208 methods are
 * **R8-obfuscated** (`A`, `A0`, `B1`, `C2`, ...), so hooking them by name is not viable and the
 * names change on every release.
 *
 * `Service.onBind` and `Binder.onTransact` are framework names and therefore stable. This probe:
 *  1. captures the `IBinder` the service returns from `onBind`,
 *  2. records its interface descriptor, and
 *  3. logs every transaction code with a snapshot of the request parcel.
 *
 * That yields the AIDL opcode map actually in use on this ROM - the same thing the reference
 * implementations had to reverse by hand.
 *
 * The parcel is snapshotted with `marshall()` after restoring `dataPosition`, so the real call is
 * never disturbed. This probe hooks nothing that has a side effect.
 */
class MiuiHeadsetBinderProbe : SafeHookContext() {

    override val tag: String get() = "HeadsetBinder"

    private val budget = AtomicInteger(MAX_LINES)
    private val seenCodes = ConcurrentHashMap<Int, String>()
    private val repliesLogged = ConcurrentHashMap<Int, String>()
    private var hooksInstalled = false

    override fun onHook() {
        install("BluetoothHeadsetService#onBind") {
            val method = findMethodAnywhere(SERVICE, "onBind", android.content.Intent::class.java)
            hookAfter(method) {
                val binder = result as? IBinder
                report("onBind", "returned=${binder?.javaClass?.name} descriptor=${descriptorOf(binder)}")
                if (binder != null) hookTransact(binder)
            }
        }

        install("BluetoothHeadsetService#onCreate") {
            val method = findMethodAnywhere(SERVICE, "onCreate")
            hookAfter(method) { report("onCreate", "service created") }
        }

        install("BluetoothHeadsetService#onDestroy") {
            val method = findMethodAnywhere(SERVICE, "onDestroy")
            hookBefore(method) { report("onDestroy", "service destroyed") }
        }

        HookLog.i(tag, "headset Binder probe installed in $packageName")
    }

    /**
     * Hook the concrete `onTransact` implementation of whichever Binder the service hands out.
     * Called from inside the `onBind` hook, so it happens on first bind rather than at module load.
     */
    private fun hookTransact(binder: IBinder) {
        if (hooksInstalled) return
        hooksInstalled = true

        val descriptor = descriptorOf(binder)
        val onTransact = runCatching {
            var cls: Class<*>? = binder.javaClass
            while (cls != null) {
                cls.declaredMethods.firstOrNull {
                    it.name == "onTransact" && it.parameterTypes.size == 4
                }?.let { return@runCatching it.apply { isAccessible = true } }
                cls = cls.superclass
            }
            null
        }.getOrNull()

        if (onTransact == null) {
            HookLog.w(tag, "no onTransact found on ${binder.javaClass.name}")
            return
        }

        HookLog.i(tag, "hooking ${onTransact.declaringClass.name}#onTransact descriptor=$descriptor")
        runCatching {
            hookBefore(onTransact as Method) {
                val code = args.getOrNull(0) as? Int ?: return@hookBefore
                val data = args.getOrNull(1) as? Parcel
                val detail = "code=$code ${snapshot(data)}"
                // Log the first sighting of a code in full, then only when its arguments change.
                val isNewCode = seenCodes.putIfAbsent(code, detail) == null
                if (isNewCode || budget.decrementAndGet() > 0) {
                    HookLog.i(tag, "req $detail")
                } else {
                    logOnChange("req code=$code", detail)
                }
            }
        }.onFailure { HookLog.e(tag, "could not hook onTransact (before)", it) }

        // The reply parcel is what we would have to forge, so record its wire format too. It is
        // only populated after the real implementation has run.
        runCatching {
            hookAfter(onTransact as Method) {
                val code = args.getOrNull(0) as? Int ?: return@hookAfter
                val reply = args.getOrNull(2) as? Parcel
                val detail = "code=$code ${snapshot(reply)}"
                if (repliesLogged.putIfAbsent(code, detail) == null) {
                    HookLog.i(tag, "rsp $detail")
                } else {
                    logOnChange("rsp code=$code", detail)
                }
            }
        }.onFailure { HookLog.e(tag, "could not hook onTransact (after)", it) }
    }

    private fun descriptorOf(binder: IBinder?): String =
        runCatching { binder?.interfaceDescriptor }.getOrNull() ?: "?"

    /**
     * Copy the parcel without consuming it.
     *
     * The bytes are copied with `appendFrom` into a scratch parcel, so the original's data position
     * can be restored and the real transaction is never disturbed. The raw hex is included for the
     * first sighting of every code (so the wire format can be decoded offline), and the ASCII
     * arguments are extracted from the UTF-16 text the Parcel carries.
     */
    private fun snapshot(parcel: Parcel?): String {
        if (parcel == null) return "null"
        return runCatching {
            val position = parcel.dataPosition()
            val size = parcel.dataSize()

            val copy = Parcel.obtain()
            try {
                copy.appendFrom(parcel, 0, size)
                copy.setDataPosition(0)
                val bytes = copy.marshall()
                val strings = extractUtf16(bytes)
                val hex = bytes.take(MAX_SNAPSHOT_BYTES).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
                val suffix = if (bytes.size > MAX_SNAPSHOT_BYTES) "…" else ""
                "size=$size strings=$strings hex=$hex$suffix"
            } finally {
                copy.recycle()
                parcel.setDataPosition(position)
            }
        }.getOrElse { "unavailable(${it.javaClass.simpleName}: ${it.message})" }
    }

    /** Pull the ASCII arguments out of a Parcel's UTF-16 text (high byte zero, low byte printable). */
    private fun extractUtf16(bytes: ByteArray): List<String> {
        val found = ArrayList<String>(4)
        val current = StringBuilder()
        var i = 0
        while (i + 1 < bytes.size) {
            val low = bytes[i].toInt() and 0xFF
            val high = bytes[i + 1].toInt() and 0xFF
            if (high == 0 && low in 32..126) {
                current.append(low.toChar())
                i += 2
            } else {
                if (current.length >= 3) found.add(current.toString())
                current.setLength(0)
                i += 1
            }
        }
        if (current.length >= 3) found.add(current.toString())
        return found.take(6)
    }

    private fun report(label: String, detail: String) {
        if (budget.decrementAndGet() > 0) HookLog.i(tag, "$label $detail") else logOnChange(label, detail)
    }

    private companion object {
        const val SERVICE = "com.android.bluetooth.ble.app.headset.BluetoothHeadsetService"
        const val MAX_LINES = 400
        const val MAX_SNAPSHOT_BYTES = 48
    }
}

# HyperOS Third-Party Headset Integration — Architecture Notes

Research notes taken from an existing, working open-source LSPosed module
(`OppoPods`, which gives Xiaomi **HyperOS** system-level control of OPPO Bluetooth earbuds),
written so that an analogous module can be built for a **different headset brand (Sony)**.

**Sources studied (read-only, nothing was modified):**

| Reference | What it is |
|---|---|
| `D:\Java\XMSound\.reference\OppoPods` | The primary subject of this document |
| `D:\Java\XMSound\.reference\HyperEars` | Third-party BT headset integration for HyperOS/MiLink (multi-brand, incl. Sony) |
| `D:\Java\XMSound\.reference\HyperVolumeANC` | HyperOS volume-panel ANC button for multiple brands |

All file paths below are relative to `D:\Java\XMSound\.reference\OppoPods` unless stated
otherwise. Line numbers come from the files as they exist in the checkout.

> **Reading note.** Some files in this checkout mix LF and CRLF endings, so line counts can
> differ between tools (e.g. `Get-Content | Measure-Object -Line` reports 631 lines for
> `hook/milink/MiLinkServiceHook.kt` while a record-based reader reports 690). **Every line
> number in this document was taken from record-based reads** and is therefore consistent
> within the document; if a line number looks off by a few, treat it as a line-ending artefact
> and search for the quoted symbol instead.

---

## 0. Executive summary

OppoPods is a **three-layer** system:

1. **Hook layer** (`hook/`) — runs inside three HyperOS system processes
   (`com.android.bluetooth`, `com.xiaomi.bluetooth`, `com.milink.service`). It intercepts the
   MIUI/MiLink *headset service* API, **pretends the OPPO earbuds are a supported Xiaomi
   model**, swallows the vendor-private calls that would fail, and re-publishes real state
   gathered from its own transport.
2. **Protocol layer** (`pods/`) — an RFCOMM (SPP) client that speaks the OPPO private protocol,
   caches state, and broadcasts it as plain Android broadcasts.
3. **UI layer** (`ui/`, `utils/miuiStrongToast/`) — an app-side Compose UI (Miuix) plus
   notification / Super-Island / strong-toast surfacing.

The important structural insight: **the hook layer and the protocol layer never call each
other directly across processes.** They communicate through (a) broadcasts with
`chen.action.oppopods.*` actions, (b) `RfcommController.currentStatusSnapshot()` inside the
Bluetooth process, and (c) a shared LSPosed `RemotePreferences` group. That decoupling is the
single most reusable part of the design.

---

## 1. Build setup

### 1.1 Version catalog

`gradle/libs.versions.toml`:

| Item | Value | Evidence |
|---|---|---|
| AGP | `9.1.0` | `gradle/libs.versions.toml:2` |
| Kotlin | `2.4.0` | `gradle/libs.versions.toml:4` |
| KSP | `2.3.6` | `gradle/libs.versions.toml:5` |
| Compose BOM | `2026.05.01` | `gradle/libs.versions.toml:8` |
| core-ktx | `1.19.0` | `gradle/libs.versions.toml:6` |
| activity-compose | `1.13.0` | `gradle/libs.versions.toml:7` |
| kotlinx-serialization-json | `1.11.0` | `gradle/libs.versions.toml:9` |
| **libxposed `api`** | **`101.0.1`** | `gradle/libs.versions.toml:10` |
| **libxposed `service`** | **`101.0.0`** | `gradle/libs.versions.toml:11` |
| Miuix (KMP) | `0.9.2` | `gradle/libs.versions.toml:12` |
| Navigation3 | `1.1.2` | `gradle/libs.versions.toml:13` |
| JUnit | `4.13.2` | `gradle/libs.versions.toml:14` |
| `focus-api` (`com.xzakota.hyper.notification`) | `1.4` | `gradle/libs.versions.toml:3,43` |
| LSPosed `lsplugin.apksign` | `1.4` | `gradle/libs.versions.toml:20` |
| LSPosed `lsplugin.resopt` | `1.6` | `gradle/libs.versions.toml:21` |

Plugins applied by the app module (`app/build.gradle.kts:1-8`):
`com.android.application`, `org.lsposed.lsplugin.apksign`, `org.lsposed.lsplugin.resopt`,
`kotlin.plugin.serialization`, `kotlin.plugin.parcelize`, `kotlin.plugin.compose`.

### 1.2 SDK levels and Java/JDK

```
app/build.gradle.kts:19   compileSdk = 37
app/build.gradle.kts:23   minSdk     = 35   (Android 15)
app/build.gradle.kts:24   targetSdk  = 36   (Android 16)
app/build.gradle.kts:25   versionCode = 16
app/build.gradle.kts:26   versionName = "2.1.0"
```

JDK requirement is **Java 22** for both Java and Kotlin compilation:

```
app/build.gradle.kts:78-82   java { toolchain { languageVersion = JavaLanguageVersion.of(JavaVersion.VERSION_22.majorVersion) } }
app/build.gradle.kts:84-86   kotlin { jvmToolchain(JavaVersion.VERSION_22.majorVersion.toInt()) }
```

The root build script deliberately upgrades KGP above AGP 9's default:

```
build.gradle.kts:2-7   buildscript { dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}") } }
```

The toolchain is resolved automatically via the foojay convention plugin
(`settings.gradle.kts:22-24`, `org.gradle.toolchains.foojay-resolver-convention` `1.0.0`).

Repository setup (`settings.gradle.kts:11-20`): `google()`, `mavenCentral()`,
`https://api.xposed.info/`, `https://s01.oss.sonatype.org/content/repositories/releases/`,
`jitpack.io`, with `RepositoriesMode.FAIL_ON_PROJECT_REPOS`.

### 1.3 Xposed API dependency

```
app/build.gradle.kts:94   compileOnly(libs.libxposedApi)     // io.github.libxposed:api:101.0.1
app/build.gradle.kts:95   implementation(libs.libxposedService) // io.github.libxposed:service:101.0.0
```

`api` is `compileOnly` (the framework provides it at runtime); `service` is a real
dependency because the app process uses `XposedServiceHelper` to reach the framework
(`OppoPodsApp.kt:5-12`). The `focus-api` library (`app/build.gradle.kts:117`) provides the
HyperOS Super-Island (`FocusNotification.buildV3 { … }`) builder.

### 1.4 Signing configuration

Two independent mechanisms:

1. **LSPosed `apksign` plugin**, driven by Gradle properties — `app/build.gradle.kts:10-15`:
   ```kotlin
   apksign {
       storeFileProperty = "KEYSTORE_FILE"
       storePasswordProperty = "KEYSTORE_PASSWORD"
       keyAliasProperty = "KEY_ALIAS"
       keyPasswordProperty = "KEY_PASSWORD"
   }
   ```
2. **CI re-signing with the Android SDK `apksigner`**, using the `SIGNING_KEY` base64 secret —
   `.github/workflows/build.yml:35-68`. If `SIGNING_KEY` is empty, the Gradle-produced APK is
   uploaded as-is and `signed=false` (`.github/workflows/build.yml:63-67`).

### 1.5 Build variants and Gradle properties

Three build types: `debug`, `release` (minify + shrink resources), and `releaseFast`
(`initWith(release)`, no minify/shrink) — `app/build.gradle.kts:30-54`. A `BUILD_TIMESTAMP`
build config field is injected (`app/build.gradle.kts:27`).

`gradle.properties`:
```
android.experimental.enableNewResourceShrinker.preciseShrinking=true
android.enableAppCompileTimeRClass=true
android.useAndroidX=true
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1024m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8
ksp.useKSP2=true
```

`app/proguard-rules.pro` matters for a hooking module — obfuscation is aggressive
(`-repackageclasses`, `-allowaccessmodification`, `-overloadaggressively`, lines 7-10), so the
entry point and hook classes are explicitly kept:
```
proguard-rules.pro:7-19
-repackageclasses
-allowaccessmodification
-keep class moe.chenxy.oppopods.hook.HookEntry { *; }
-keep class moe.chenxy.oppopods.hook.** { *; }
-keep class moe.chenxy.oppopods.utils.miuiStrongToast.data.** { *; }
```

### 1.6 CI workflow

`.github/workflows/build.yml`:

| Step | Detail | Line |
|---|---|---|
| Triggers | tags `v*`, branches `master`/`main`, PRs, `workflow_dispatch` | 3-9 |
| Runner | `ubuntu-latest` | 13 |
| Java | Temurin **21** | 20-24 |
| Gradle | `gradle/actions/setup-gradle@v4` | 26-27 |
| Build | `./gradlew :app:assembleRelease` | 32-33 |
| Sign | base64-decode `secrets.SIGNING_KEY` → `zipalign -f 4` → `apksigner sign` | 35-68 |
| Upload | `actions/upload-artifact@v7` with `archive: false` | 71-77 |
| Release | `ncipollo/release-action@v1` on tags when signed | 79-86 |

Note the **JDK mismatch worth planning for**: CI installs JDK 21 while the build toolchain
demands Java 22; it works only because the foojay resolver plugin downloads a JDK 22 toolchain
(`settings.gradle.kts:22-24`). Pin the toolchain explicitly in a new project.

---

## 2. Module manifest

### 2.1 `assets/xposed_init`

`app/src/main/assets/xposed_init` contains exactly one line:

```
moe.chenxy.oppopods.hook.HookEntry_YukiHookXposedInit
```

> **⚠ Divergence / possible bug.** The only `XposedModule` subclass in the source tree is
> `HookEntry` (`app/src/main/java/moe/chenxy/oppopods/hook/HookEntry.kt:11`), and
> `proguard-rules.pro:13` keeps `moe.chenxy.oppopods.hook.HookEntry`. A repo-wide grep finds
> no `YukiHookXposedInit` anywhere except this file, and git history shows the string was
> introduced in the initial commit of that file (`git log -p --follow` → commit `2c5b168`,
> "feat: Game mode switch"). For a new module, write the **real** class name, e.g.
> `dev.example.sonypods.hook.HookEntry` — this is the pattern HyperEars uses
> (`.reference\HyperEars\system-module\src\main\assets\xposed_init:1` = `dev.hyperears.hook.HookEntry`).

### 2.2 `AndroidManifest.xml`

`app/src/main/AndroidManifest.xml`:

| Element | Value | Line |
|---|---|---|
| Permissions | `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` | 4-5 |
| `<queries>` | `com.heytap.headset`, `com.android.settings` | 7-10 |
| `android:name` | `.OppoPodsApp` | 13 |
| `activity-alias` | `.LauncherActivity` → `.MainActivity`, `MAIN`/`LAUNCHER` | 22-34 |
| `MainActivity` | `launchMode="singleTask"`, exported, extra intent-filter with `de.robv.android.xposed.category.MODULE_SETTINGS` | 36-44 |
| `PopupActivity` | custom theme, `taskAffinity=""`, `excludeFromRecents`, action `chen.action.oppopods.show_pods_ui` | 45-54 |
| `provider` | `.config.PodImageProvider`, authority `moe.chenxy.oppopods.podimages` | 55-59 |

Xposed metadata keys (the four that matter):

```xml
AndroidManifest.xml:61-76
<meta-data android:name="xposedmodule"      android:value="true" />
<meta-data android:name="xposeddescription" android:value="OPPO Earphones Support for Xiaomi HyperOS." />
<meta-data android:name="xposedminversion"  android:value="101" />
<meta-data android:name="xposedscope"       android:resource="@array/xposedscope" />
```

### 2.3 Recommended scope list

The scope array is the **"recommended" list shown by LSPosed**. There is no separate
`recommended` key; LSPosed derives the recommended scope from `xposedscope`.

```xml
app/src/main/res/values/arrays.xml:3-7
<string-array name="xposedscope">
    <item>com.android.bluetooth</item>
    <item>com.milink.service</item>
    <item>com.xiaomi.bluetooth</item>
</string-array>
```

This exactly matches the packages handled in `HookEntry.onPackageLoaded`
(`HookEntry.kt:19-30`). `SettingsHeadsetHook` (`com.android.settings`) is **compiled but not
registered** — its dispatch line is commented out at `HookEntry.kt:24`.

The README documents the user flow: install → enable in LSPosed + tick recommended scope →
one-tap scope restart → connect earbuds (`README.md:47-52`).

---

## 3. Hook layer

### 3.1 Registration and dispatch

**`HookEntry.kt`** is the `XposedModule` entry point.

```
HookEntry.kt:11-31
class HookEntry : XposedModule() {
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return
        when (param.packageName) {
            "com.android.bluetooth" -> {
                loadHook(HeadsetStateDispatcher, param.defaultClassLoader, param.packageName)
                loadHook(BluetoothUpstreamHeadsetHook(), param.defaultClassLoader, param.packageName)
            }
            //"com.android.settings" -> loadHook(SettingsHeadsetHook, ...)   // disabled at :24
            "com.milink.service" -> loadHook(MiLinkServiceHook, param.defaultClassLoader, param.packageName)
            "com.xiaomi.bluetooth" -> {
                loadHook(MiBluetoothToastHook, param.defaultClassLoader, param.packageName)
                loadHook(BluetoothUpstreamHeadsetHook(), param.defaultClassLoader, param.packageName)
            }
        }
    }
```

Note that `BluetoothUpstreamHeadsetHook` is instantiated **twice** — a fresh instance per
process — while `HeadsetStateDispatcher`, `MiBluetoothToastHook` and `MiLinkServiceHook` are
Kotlin `object` singletons. Plain classes are used where per-process mutable state must not be
shared (`HookEntry.kt:22,28`).

`loadHook` (`HookEntry.kt:33-49`) does the per-process wiring:

```kotlin
private fun loadHook(hook: HookContext, classLoader: ClassLoader, packageName: String) {
    Log.module = this
    hook.module = this
    hook.appClassLoader = classLoader
    hook.packageName = packageName
    hook.prefs = getRemotePreferences("oppopods_settings")   // LSPosed remote prefs
    ConfigManager.init(hook.prefs)
    // register a listener so PREF_KEY_CONFIG_JSON changes re-read config in-process
    hook.prefs.registerOnSharedPreferenceChangeListener(configListener)
    hook.onHook()
}
```

**`HookContext.kt`** is the shared base class + reflection/hooking façade.

```
HookContext.kt:10-20    abstract class HookContext { module, appClassLoader, prefs, packageName; abstract fun onHook() }
HookContext.kt:18-20    fun fakeDeviceId() = ConfigManager.fakeDeviceId(); fun fakeSupport() = ConfigManager.fakeSupport()
HookContext.kt:26       findClass(name) = Class.forName(name, false, appClassLoader)
HookContext.kt:28-29    findMethod(className, methodName, vararg parameterTypes)
HookContext.kt:31-32    findConstructor(...)
HookContext.kt:34-35    findMethodByParamCount(className, methodName, paramCount)   // ← version tolerance
HookContext.kt:38-39    findConstructorByParamCount(className, paramCount)          // ← version tolerance
HookContext.kt:42-47    hookAfter(method) { chain.proceed() then mutate .result }
HookContext.kt:49-54    hookBefore(method) { set .result to skip the original call }
HookContext.kt:56-60    hookConstructorAfter(constructor)
HookContext.kt:63-106   object Log  (level-filtered via ConfigManager.logLevel(), routes to module.log)
HookContext.kt:108-118  class HookParam { args, instance, hasResult, result }
HookContext.kt:120-155  getObjectField / setObjectField / callMethod  (walk superclass chain, setAccessible)
```

Two idioms are used everywhere and are the core of version resilience:

* `findMethodByParamCount` / `findConstructorByParamCount` — locate a method when the
  obfuscated *name* is unknown or when the exact parameter types drift.
* `runCatching { … }.onFailure { Log.w(…) }` around every individual hook, so that **a hook
  that does not exist on this ROM build is skipped rather than crashing the host process**
  (see e.g. `HeadsetStateDispatcher.kt:22-28`, `MiBluetoothToastHook.kt:335-337`).

**`HeadsetStateDispatcher.kt`** is the connection-lifecycle router.

```
HeadsetStateDispatcher.kt:18   object HeadsetStateDispatcher : HookContext()
HeadsetStateDispatcher.kt:23   hooks com.android.bluetooth.btservice.AdapterService#onCreate
                               → registerAppRequestReceiver(instance as Context)
HeadsetStateDispatcher.kt:30-53 hooks com.android.bluetooth.a2dp.A2dpService
                               #handleConnectionStateChanged(3 params): device, fromState, currState
                               .args[0]=BluetoothDevice, [1]=fromState, [2]=currState
                               posts onto the service's own mHandler (getObjectField(instance,"mHandler"))
                               STATE_CONNECTED   → statusBar.setIconVisibility("wireless_headset", true)
                                                   RfcommController.connectPod(context, device, prefs)
                               STATE_DISCONNECT*  → setIconVisibility(false)
                                                   RfcommController.disconnectedPod(context, device)
HeadsetStateDispatcher.kt:56-88  registerAppRequestReceiver: receiver for
                               ACTION_PODS_UI_INIT / ACTION_REFRESH_STATUS
                                 → answer ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE to the module app
                               ACTION_CONNECT_POD_REQUEST / ACTION_DISCONNECT_POD_REQUEST
                                 → RfcommController.connectPod(..., appRequested = true) / disconnectedPod
                               registered with Context.RECEIVER_EXPORTED (:86)
HeadsetStateDispatcher.kt:94-97  isOppoPod(device) = device.name contains "oppo" (case-insensitive)
```

Why it exists: `com.android.bluetooth` is the only process guaranteed to see the A2DP
connection, and A2DP state changes are the trigger for opening the vendor RFCOMM channel. The
hook runs the work **on the Bluetooth service's own handler** (`:34,38`) to avoid
cross-thread state corruption.

**`HookContext`-based dispatch summary**

| File | Kind | Registered for | Hooked targets (class#method) |
|---|---|---|---|
| `hook/HookEntry.kt` | `XposedModule` | — (entry point) | none; dispatch only |
| `hook/HookContext.kt` | abstract base | — | none; provides `findMethod`/`hookBefore`/`hookAfter` |
| `hook/HeadsetStateDispatcher.kt` | `object` | `com.android.bluetooth` | `…btservice.AdapterService#onCreate`; `…a2dp.A2dpService#handleConnectionStateChanged` |
| `hook/BluetoothUpstreamHeadsetHook.kt` | `class` (×2) | `com.android.bluetooth`, `com.xiaomi.bluetooth` | see §3.2 |
| `hook/MiBluetoothToastHook.kt` | `object` | `com.xiaomi.bluetooth` | `…ble.app.MiuiBluetoothNotification` constructors |
| `hook/SettingsHeadsetHook.kt` | `object` | `com.android.settings` — **disabled** (`HookEntry.kt:24`) | `…settings.bluetooth.MiuiHeadsetActivity`, `MiuiHeadsetActivityPlugin`, `HeadsetIDConstants`, `tws.MiuiHeadsetBattery`, `MiuiHeadsetFragment`, `IMiuiHeadsetService$Stub$Proxy` |
| `hook/milink/MiLinkServiceHook.kt` | `object` | `com.milink.service` | see §3.3 |
| `hook/milink/MiLinkSpatialAudioHook.kt` | `internal class` | (owned by `MiLinkServiceHook`) | spatial-audio subset of MiLink |

### 3.2 `BluetoothUpstreamHeadsetHook` — the device-spoofing core

Declared constants at `BluetoothUpstreamHeadsetHook.kt:26`:
`DESCRIPTOR = "com.android.bluetooth.ble.app.IMiuiHeadsetService"`.

`onHook()` (`:41-44`) installs two groups:

**(a) `hookHeadsetServiceBinder()` (`:126-154`)** — hooks the MIUI headset Binder
implementation:

```
:131  BluetoothHeadsetService#onBind(Intent)      → grab the binder, installHeadsetBinderHooks(binder.javaClass)
:139  BluetoothHeadsetService#onCreate()          → registerStatusReceiver
:148-153  parse the binder class from an obfuscated-name candidate list:
          "com.android.bluetooth.ble.app.headset.BinderC6776v"
          "com.android.bluetooth.ble.app.headset.v"
```

`installHeadsetBinderHooks` (`:215-296`) installs, on whichever binder class exists:

| Method | Hook kind | Injected result | Line |
|---|---|---|---|
| `checkSupport(BluetoothDevice)` | before | `fakeSupport()` (skip real call) | 221-229 |
| `getDeviceInfo(String)` | before | `fakeSupport()` | 231 |
| `isSupportAudioSwitch(String)` | before | `"1"` | 232 |
| `isMiTWS(String)` | before | `true` | 233 |
| `checkIsMiTWS(String)` | before | `true` | 234 |
| `getRingFindState(String)` | before | `false` | 235 |
| `setCommonCommand(Int, String, BluetoothDevice)` | before | `102→"1"`, `123→"4"`, else `"1"` | 238-253 |
| `connect(BluetoothDevice)` | before | `null` + re-push real status | 255 |
| `getDeviceConfig(BluetoothDevice)` | before | `null` + re-push real status | 256 |
| `getCommonConfig(BluetoothDevice, String)` | before | `null` + re-push real status | 257 |
| `changeAncMode(Int, BluetoothDevice)` | before | swallow; forward mapped ANC; re-push status | 258, 365-378 |
| `changeAncLevel(String, BluetoothDevice)` | before | swallow; forward mapped ANC level | 259, 380-393 |
| `register(IMiuiHeadsetCallback)` | before | **swallow**, remember the callback for later `refreshStatus` pushes | 263-273 |
| `registerCallbackDevice(cb, device)` | before | same | 274-285 |
| `unregister(cb, device)` | before | **swallow**, forget callback | 286-293 |

The `register*` hooks are the single most important trick: instead of letting MIUI's headset
framework register its own callback (which would then keep asking a non-existent MIUI headset
service for state), the module **captures the callback object** (`rememberCallback`, `:395-397`)
and later drives it itself with a synthesized payload.

**(b) `hookNotificationBatteryUpstream()` (`:46-124`)** — patches MIUI's own notification and
Super-Island payloads:

```
:47-83   …ble.app.MiuiBluetoothNotificationApi#showNewConnectedToast(int,int,int,int,BluetoothDevice,String)
         before-hook: if it is our device, capture the args, set result = null to swallow, then call
         MiuiBluetoothNotification#showConnectedToast(...) with OUR battery/wear values.
:89-105  …ble.app.MiuiBluetoothNotification#invokeStatusBar(Context, String, Bundle)
         before-hook: if bundle notifyId == "headset_wear_notification", either swallow it entirely
         (islandMode NONE/MODULE) or rewrite the island JSON (islandMode OFFICIAL).
:108-123 …ble.app.MiuiBluetoothNotification#updateParameters(C4705R2)
         after-hook: overwrite obfuscated request fields f18107b / f18108c (battery) and f18109d (wear).
```

Note the **obfuscated field names** `f18110e`, `f18109d`, `f18107b`, `f18108c`
(`:110,115-118`) and the obfuscated class `com.android.bluetooth.ble.app.C4705R2` (`:86`) —
these are decompiled/renamed symbols that will change between ROM builds. Grep-verified
hard-coded names in the repo: `BinderC6776v`, `v`, `C4705R2`, `MiuiBluetoothNotification`,
`MiuiBluetoothNotificationApi`, `BluetoothHeadsetService`, `IMiuiHeadsetCallback`,
`IMiuiHeadsetService`.

**(c) A dormant Parcel-level fallback.** `hookMiuiHeadsetBinder()` (`:413-429`) and
`handleTransaction(...)` (`:431-458`) can hook
`IMiuiHeadsetService$Stub#onTransact(int, Parcel, Parcel, int)` and directly answer
transaction codes 1,2,3,4,9,10,11,12,14,15,16,18,19,20,24, i.e.
`checkSupport`, `register`, `unregister`, `connect`, `changeAncMode`, `changeAncLevel`,
`getDeviceInfo`, `getDeviceConfig`, `setCommonCommand`, `getCommonConfig`,
`registerCallbackDevice`, `isMiTWS`, `checkIsMiTWS`, `isSupportAudioSwitch`,
`getRingFindState` (`:435-451`). **This method is never called** — `onHook()` only calls
`hookHeadsetServiceBinder()` and `hookNotificationBatteryUpstream()` (`:42-43`). It is a useful
blueprint: it shows how to bypass the Java-level API entirely if the binder class is renamed.

**Real-state push-back** (`:618-678`): `sendRealStatus(address, reason)` iterates
`callbacks.values` and invokes `callback#refreshStatus(address, payload)`. The payload comes
from `realRefreshPayload()` (`:657-678`), which prefers
`RfcommController.currentStatusSnapshot()` and falls back to locally cached broadcast values,
encoded by `RfcommController.miuiRefreshPayload(...)`.

**State ingestion** (`:160-213`): `registerStatusReceiver` listens for
`ACTION_PODS_CONNECTED/DISCONNECTED/BATTERY_CHANGED/ANC_CHANGED/TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED/CONFIG_CHANGED`
and maintains `knownOppoAddresses` (an address allow-list) so that later hooks can decide
"is this our device" from an address string alone (`isOppoAddress`, `:884-886`).

**Mapping tables** (`:767-800`): MIUI modes `1→2, 2→3, else→1` (`oppoAncFromMiuiMode`); MIUI
level strings `0103=Smart, 0101=Light, 0100=Medium, 0102=Deep, 02xx=Transparency` — identical
tables live in `SettingsHeadsetHook.kt:597-616`, `RfcommController.kt:383-394`,
`RfcommController.kt:112-121`.

### 3.3 `MiLinkServiceHook` + `MiLinkSpatialAudioHook` — 融合设备中心 / MiLink

`MiLinkServiceHook.onHook()` (`MiLinkServiceHook.kt:46-52`):

```
:47  hookContextEntry()          — steal a Context from the MiLink SDK
:48  hookMxBluetoothRuntime()    — the MxBluetooth SDK seam
:49  hookHeadsetRuntimeDisplay() — com.miui.headset.runtime / .api
:50  spatialAudioHook.hookCirculateHeadsetServiceInfo()
:51  hookGameModeCard()          — card rendering + game mode
```

**Context theft** (`:54-65`): hooks `getInstanceForIsMiTWS(Context)` on both
`com.xiaomi.mxbluetoothsdk.service.MxBluetoothService` and `…manager.MxBluetoothManager` and
registers its broadcast receiver with that `Context` — a clean way to get a usable `Context`
inside an arbitrary system process.

**MxBluetooth SDK runtime** (`:67-90`) — hooked on both
`com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager` and
`com.xiaomi.mxbluetoothsdk.service.MxBluetoothService`:

| Method | Injected | Line |
|---|---|---|
| `checkIsMiTWS(BluetoothDevice)` | `1` | 73 |
| `getDeviceId(BluetoothDevice)` | `fakeDeviceId()` | 74 |
| `getBatteryLevel(BluetoothDevice)` | `1` | 75 |
| `getAncState(BluetoothDevice)` | `miLinkAncState()` | 76 |
| `getDeviceRunInfo(BluetoothDevice)` | `0` | 77 |
| `getWearStatus(BluetoothDevice)` | `"0,0"` | 78 |
| `isLeAudio(BluetoothDevice)` | `false` | 79 |
| `openAnc(BluetoothDevice)` | before: swallow, forward OPPO ANC 2, result `1` | 80 |
| `closeAnc(BluetoothDevice)` | before: forward OPPO ANC 1, result `0` | 81 |
| `openTransparent(BluetoothDevice)` | before: forward OPPO ANC 3, result `2` | 82 |
| `isMiTWS(String)` / `isSupportAudioSwitch(String)` / `getRingFindState(String)` | `true` / `miLinkSwitchState()` / `false` | 85-87 |
| `getSpatialMode(BluetoothDevice)` / `setSpatialMode(BluetoothDevice, Int)` | spatial audio | `MiLinkSpatialAudioHook.kt:14-15` |

**`com.miui.headset.runtime` display layer** (`:92-113`):

* `ProfileContext#getDeviceId(BluetoothDevice)` → `fakeDeviceId()` (`:93`)
* `ProfileContext#getBatteryLevel(BluetoothDevice)` → `miLinkBatteryLevels()` (`:94`)
* `AncBatteryController#getDeviceId` → `fakeDeviceId()`; `#getAncState` → `miLinkAncState()`;
  `#getBatteryLevelCache` → `miLinkBatteryLevels()`;
  `#getHeadsetPropertyBlock` → `batteryPercentForMiLink()`;
  `#getFindRingState` → `miLinkGameModeState()`; `#getSwitchState` → `miLinkSwitchState()`
  (`:95-100`)
* `AncBatteryController#setAncStateBlock(BluetoothDevice, Int)` — before-hook: map MiLink mode →
  OPPO ANC, broadcast it, then set `result = miLinkAncState()` (`:155-176`)
* `com.miui.headset.api.HeadsetInfo` no-arg getters are hooked by **param count** with a
  component-name fallback, because R8 renames them (`:103-112`):
  `getDeviceId`/`component3` → device id, `getPowers`/`component4` → battery,
  `getMode`/`component5` → ANC, `getSwitchState`/`component8` → switch,
  `getFindRingState`/`component11` → game mode

**Game-mode card injection** (`:457-490`) hooks
`AncBatteryController#setFindRing(BluetoothDevice, Int)` (game mode on/off, `:459-470`) and
`com.miui.circulate.world.sticker.ui.SynergyView#setTitle(Int)` (with a package-name-shifted
second candidate `…sticker.p067ui.SynergyView`, `:472-475`). It retitles the "find earphone"
row to 游戏模式 / 已开启 / 已关闭 by walking child views named `item_title`, `item_subtitle`,
`item_icon` (`:497-530`). **This is the clearest example of the obfuscation-tolerant pattern**:
`getDeclaredMethod("setTitle", Int::class.javaPrimitiveType!!)` where the method *name* is
stable but the *argument* is a resource id that is resolved back to a name at runtime via
`view.resources.getResourceEntryName(id)` (`:497-500`).

**Callback driving** (`:602-608`): `notifyHeadsetPropertyChanged(controller, device, updateType)`
reflectively invokes the controller's `headsetPropertyChangeListener` field with a
`(device, updateType)` tuple. Update types observed: `4` (battery/property block), `8` (ANC),
`9` (spatial), `10` (game mode).

**Spatial audio** (`MiLinkSpatialAudioHook.kt`): `getMiAudioEffect`, `setMiAudioEffect`,
`setHeadTracking`, `AncBatteryModel#getDeviceSpatialType`/`setDeviceSpatialType`,
`AncBatteryController$mmaCallback$1#onDeviceSpatialType`/`onReportSpatialState`,
`ProfileContext#getAudioSpatialEffectState`/`setAudioEffectState`, and
`com.miui.circulate.api.service.CirculateServiceInfo#setHeadsetId(String, Int)` (to clear
`headset_switch_state` in the service-properties bundle, `:32-44`).

### 3.4 `SettingsHeadsetHook` (compiled, currently out of scope)

Registered nowhere (`HookEntry.kt:24` is commented out), but it is the reference for
`com.android.settings` integration if Settings-tab support is wanted later. Targets:

| Target | Purpose | Line |
|---|---|---|
| `…settings.bluetooth.MiuiHeadsetActivity#onCreate(Bundle)` | inject `MIUI_HEADSET_SUPPORT`, `DEVICE_ID`, `COME_FROM` extras | 61-72 |
| `MiuiHeadsetActivityPlugin#onCreate(Bundle)` | same | 78-89 |
| `MiuiHeadsetActivity#getDeviceID()` / `#getSupport()` | force fake id/support | 73-74, 92-102 |
| `HeadsetIDConstants#checkSupport(String)` / `isTWS01Headset` / `isK77sHeadset` | identity checks | 105-109 |
| `HeadsetIDConstants#isBleMmaConnect(Context, BluetoothDevice, String)` | force `true` | 127-139 |
| `HeadsetIDConstants#isBleMmaConnect(IMiuiHeadsetService, BluetoothDevice, String)` | force `true` | 141-154 |
| `IMiuiHeadsetService$Stub$Proxy` — `checkSupport`, `getDeviceInfo`, `isSupportAudioSwitch`, `setCommonCommand`, `connect`, `getDeviceConfig`, `getCommonConfig`, `isMiTWS`, `checkIsMiTWS`, `getRingFindState`, `changeAncMode`, `changeAncLevel` | mirror of the Bluetooth-process spoofing **on the client side of the Binder** | 156-179 |
| `…settings.bluetooth.tws.MiuiHeadsetBattery` ctor (4 params) + `#onBatteryChanged(String)` | push battery into the settings UI | 267-291 |
| `…settings.bluetooth.MiuiHeadsetFragment#onCreateView(3)`, `#onServiceConnected()`, `#refreshStatus(String,String)`, `#handleConnectMmaFailed(String)`, `#updateAncMode(Int,Boolean)`, `#updateAncLevel(String,Boolean)` | suppress MMA failures and inject synthesized status | 293-348 |
| `MiuiHeadsetFragment#updateAtUiInfo(String)`, `#updateAncUi(...)`, `#refreshStatus(address,payload)` | payload format `"<mode>|0100;0101;0102;0103;0200;0201|<batt>|00"` and a 16-slot CSV | 464-479, 580-595 |

It also persists state to its own prefs file `oppopods_milink_state` (`:21`) because the
settings process can restart before the first broadcast arrives.

### 3.5 How the module decides "is this HyperOS / which version"

Grep results across `app/src/main/java` for `SDK_INT`, `VERSION_CODES`, `hyperos`, `getprop`:

| Mechanism | Location | Detail |
|---|---|---|
| Runtime HyperOS detection | `utils/SystemApisUtils.kt:81-84` | `val isHyperOS get() = getPropByShell("ro.mi.os.version.code").isNotEmpty()`; `getPropByShell` shells out to `getprop` (`:72-79`) |
| HyperOS-gated features | `utils/miuiStrongToast/MiuiStrongToastUtil.kt:33, 68, 122, 171` | official strong toast / island only if `isHyperOS`, otherwise degrade to a plain `Toast` (`:33-38`) |
| Android-version branch | `PopupActivity.kt:143` | `SDK_INT >= TIRAMISU` for `getParcelableExtra(key, Class)` |
| Android-version annotation | `HookEntry.kt:15` | `@RequiresApi(Build.VERSION_CODES.Q)` only |

**There is no explicit HyperOS-version number check anywhere.** All version tolerance is
achieved structurally instead:

1. **Candidate class-name lists**, first hit wins — `BluetoothUpstreamHeadsetHook.kt:148-153`
   (`BinderC6776v`, `v`), `:403-407` (`firstExistingClass`), `MiLinkServiceHook.kt:472-475`
   (`…sticker.ui.SynergyView`, `…sticker.p067ui.SynergyView`).
2. **Candidate method-name lists** — `hookAddressStringResult` / `hookAddressBooleanResult`
   take `listOf("isSupportAudioSwitch", "mo19775z1", "z1")` and pick the first one that
   resolves (`BluetoothUpstreamHeadsetHook.kt:231-235, 313-347`).
3. **Param-count lookup** — `findMethodByParamCount` / `findConstructorByParamCount`
   (`HookContext.kt:34-39`) used for R8-renamed methods such as the `HeadsetInfo` components
   and the `MiuiHeadsetBattery` constructor.
4. **Per-hook failure isolation** — every `hookX` body is wrapped in `runCatching { }.onFailure {
   Log.w(...) }`, so absent symbols are skipped (e.g. `BluetoothUpstreamHeadsetHook.kt:220-229`).
5. **Introspection-based dispatch** — `view.resources.getResourceEntryName(id)` instead of
   hard-coded resource ids (`MiLinkServiceHook.kt:497-500`), and
   `binderClass.declaredMethods.first { name matches && paramCount matches }`.

The README states the target explicitly: 小米设备 running **HyperOS (Android 15+)**, with
**Super Island only on OS3** (`README.md:44`), and the ROM badge is 澎湃OS3 (`README.md:14`).

---

## 4. Device spoofing / model registry

### 4.1 The two halves of "identity"

Spoofing has two independent halves that must both be present:

**(a) Model registry — "what can this earbud do".**
`pods/DeviceModelRegistry.kt` parses a bundled OPPO model table:

```
DeviceModelRegistry.kt:10-12   ASSET_NAME = "device_models.json"
                               EQ_MODE_NAMES_ASSET_NAME = "eq_mode_names.json"
                               EQ_MODE_NAMES_EN_ASSET_NAME = "eq_mode_names.en.json"
DeviceModelRegistry.kt:24-53   ensureLoaded(context): reads the asset through a
                               createPackageContext(BuildConfig.APPLICATION_ID, CONTEXT_IGNORE_SECURITY)
                               wrapper (so system processes can read our assets), takes
                               JSONObject(text).optJSONArray("whiteList"), builds byId map
DeviceModelRegistry.kt:45-52   byId: id.uppercase() -> entry; an entry WITH a "function" object
                               wins over one without (duplicate-id disambiguation)
DeviceModelRegistry.kt:55-59   byProductId(context, productId)
DeviceModelRegistry.kt:61-72   byDeviceName(context, deviceName)   // exact match on a
                               letter/digit-normalized name (normalize(), :179-180) — explicitly
                               NOT substring matching (doc comment, :8)
DeviceModelRegistry.kt:74-103  parse(entry) -> ModelCapabilities
DeviceModelRegistry.kt:183-197  data class ModelCapabilities
```

`assets/device_models.json` top-level shape (single line, ~135 KB):

```json
{"whiteList":[
  {"rssi":{"rightOffset":0,"leftOffset":0,"secondRssi":57,"firstRssi":54,"flattenOffset":2},
   "minVersion":16007000,
   "function":{ "dolbyAtmos":1, "spatialDescriptionType":2, "autoFirmwareUpdate":1,
                "noiseReductionMode":[{"modeType":5,"protocolIndex":0},
                                      {"modeType":1,"protocolIndex":1},
                                      {"modeType":2,"protocolIndex":2}],
                "control":[{"ear":2,"action":1,"support":516,"showEar":0}, ...],
                "callControl":[...], "batteryInfo":1, "equalizer":2,
                "spatialTypes":[0,1], "multiDevicesConnect":1,
                "equalizerMode":[{"modeType":1,"protocolIndex":0}, ...],
                "findDevice":1, "diagnostic":1, ... },
   "name":"OPPO Enco Air4s",
   "id":"06F010",
   "type":"T1",                      // form factor
   "supportSpp":true,
   "brand":"oppo",
   "uuid":"0000079A-D102-11E1-9B23-00025B00A5A5"},
  { ... "children":[{"name":"OPPO Enco xiapu OF"}], "name":"OPPO Enco R5", "id":"06B450", ... },
  ...
]}
```

| JSON field | Meaning | Consumed at |
|---|---|---|
| `id` | 6-hex-digit product id (matches the RFCOMM `0x8103` response) | `DeviceModelRegistry.kt:47, 85` |
| `name` | marketing model name | `:67, 86` |
| `brand` | `"oppo"` | (not read by the registry) |
| `type` | form factor / carrier id (`T1`, `T2`, …) | (not read by the registry) |
| `uuid` | the product's private SPP UUID — **same value as the controller's connect UUID** | (not read by the registry) |
| `supportSpp` | whether a private SPP channel exists | (not read by the registry) |
| `minVersion` | minimum firmware/protocol version for this entry | (not read by the registry) |
| `children[].name` | alias names that resolve to the same entry | (not read by the registry) |
| `rssi.*` | pairing RSSI calibration for the OPPO app | (not read by the registry) |
| `function.*` | capability flags / tables | `:74-102` |

`function` sub-fields actually read: `noiseReductionMode[].modeType`,
`spatialTypes[]`, `multiDevicesConnect`, `multiConnectFunctions[]`, `customEqualizer`,
`equalizerMode` / `equalizerModeCompat` / `equalizerModeByVersion`,
`customEqFrequency[]`, `customEqMax`, `gameSoundList[].type`, `gameModeList[].gameMode`,
`gameMode`.

Derived capability logic worth reusing:

```
DeviceModelRegistry.kt:87   adaptiveSupported        = noiseReductionMode contains modeType 6 or 10
DeviceModelRegistry.kt:88   spatialAudioSupported    = spatialTypes contains HEAD_TRACKING(2)
DeviceModelRegistry.kt:89-90 spatialSoundSwitchSupported = spatialTypes non-empty && !contains(2)
DeviceModelRegistry.kt:91   legacyAnc               = isLegacyAnc(...) (:168-177) — no children
                                                      and modeType==5 && protocolIndex==0
DeviceModelRegistry.kt:92-93 dualDeviceSupported     = multiDevicesConnect || multiConnectFunctions non-empty
DeviceModelRegistry.kt:100-101 gameModeFeatureId / gameModeSupported
DeviceModelRegistry.kt:105-119 parseEqPresets(): id = protocolIndex, name resolved from
                                 eq_mode_names.json / eq_mode_names.en.json by locale (:121-138)
```

`pods/DeviceCapabilities.kt` converts registry output into the transport- and UI-facing shape:

```
DeviceCapabilities.kt:5-17    data class DeviceCapabilities
DeviceCapabilities.kt:41-67   detectDeviceCapabilities(context, deviceName, productId)
                              = DeviceModelRegistry.byProductId(...) ?: byDeviceName(...)
DeviceCapabilities.kt:54-58   ancImplementation = COMPATIBLE if legacyAnc else STANDARD
```

**(b) Identity injection — "make HyperOS believe it is a supported Xiaomi headset".**
The spoofed identity is a **device-id string plus a support-blob**:

```
config/ConfigManager.kt:33     DEFAULT_FAKE_DEVICE_ID = "01010607"
config/ConfigManager.kt:81     fun fakeDeviceId() = current().fakeDeviceId.normalizedFakeDeviceId()
config/ConfigManager.kt:97     fun fakeSupport() = "${fakeDeviceId()},000000000000000010000000"
config/ConfigManager.kt:228    normalizedFakeDeviceId() = trim() or DEFAULT_FAKE_DEVICE_ID
```

`ConfigManager` is a `@Serializable AppConfig` persisted as JSON **in the LSPosed remote
prefs** so all hooked processes see the same value (`ConfigManager.kt:9-19, 162-174`), and it
migrates/downgrades gracefully via direct per-key prefs (`:176-216`).

The injected value propagates through a small set of seams:

| Injection point | File:line |
|---|---|
| `HookContext.fakeDeviceId()` / `fakeSupport()` helpers | `HookContext.kt:18-20` |
| Bluetooth-process binder: `checkSupport` → `fakeSupport()` | `BluetoothUpstreamHeadsetHook.kt:225` |
| Bluetooth-process binder: `getDeviceInfo` → `fakeSupport()` | `:231` |
| Bluetooth-process Parcel fallback (dormant) | `:442, 467` |
| `com.android.settings`: intent extras `MIUI_HEADSET_SUPPORT`, `DEVICE_ID` | `SettingsHeadsetHook.kt:68-70, 85-86` |
| `com.android.settings`: `getDeviceID()` / `getSupport()` getters | `SettingsHeadsetHook.kt:73-74` |
| `com.android.settings`: `HeadsetIDConstants.checkSupport/isTWS01Headset` | `SettingsHeadsetHook.kt:105-108` |
| `com.android.settings`: `IMiuiHeadsetService$Stub$Proxy` | `SettingsHeadsetHook.kt:158-159` |
| `com.milink.service`: `MxBluetoothManager/Service#getDeviceId` | `MiLinkServiceHook.kt:74` |
| `com.milink.service`: `ProfileContext` / `AncBatteryController` `getDeviceId` | `MiLinkServiceHook.kt:93, 95` |
| `com.milink.service`: `HeadsetInfo#getDeviceId` / `component3` | `MiLinkServiceHook.kt:103-104` |
| Module UI, when launching the Settings activity itself | `PopupActivity.kt:134-136`; `ui/MainUI.kt:603-605` |
| User-facing setting | `ui/pages/SettingsPage.kt:51, 236`; `ui/MainUI.kt:156, 764-766` |

The **`fakeSupport()` format** `"<8 hex digits>,000000000000000010000000"` is a MIUI "headset
support" string: `HeadsetIDConstants.checkSupport()` is hooked to accept anything
`startsWith`/`contains` the fake id (`SettingsHeadsetHook.kt:105-107, 119-121`), and
`isTWS01Headset` is forced to compare equal to it (`:108`). This is a **Xiaomi-model
impersonation**, not an OPPO impersonation — the module makes the OPPO earbuds look like a
Xiaomi model whose id happens to be `01010607`.

---

## 5. Protocol layer (RFCOMM)

### 5.1 Files

| File | Lines | Role |
|---|---|---|
| `pods/RfcommController.kt` | 1330 | singleton transport, state cache, command API, broadcast fan-out |
| `pods/Packets.kt` | 971 | packet builders + one parser object per response type |
| `pods/DeviceCapabilities.kt` | 67 | capability DTO + `detectDeviceCapabilities()` |
| `pods/DeviceModelRegistry.kt` | 197 | JSON model table → `ModelCapabilities` |
| `pods/RfcommLog.kt` | 74 | ring buffer of last 200 TX/RX frames, broadcast to the app |

### 5.2 Packet framing (`Packets.kt`)

```
Packets.kt:3-8    Header(0xAA) + TotalLen(1B) + Res(0000) + Cmd(2B LE) + Seq(1B) + PayLen(2B LE) + Payload
Packets.kt:13-29  OppoPackets.buildPacket(cmd, seq = 0xF0, payload)
                  totalLen = 7 + payLen; packet size = 2 + totalLen
                  packet[0]=0xAA, [1]=totalLen, [2..3]=0, [4..5]=cmd LE, [6]=seq, [7..8]=payLen LE, [9..]=payload
Packets.kt:196-247  object Cmd  — every command/response/notify opcode
Packets.kt:305-446  object Enums — pre-built ByteArray packets
Packets.kt:249-302  EqDetailsParser, ProductIdParser
Packets.kt:456,568,612,633,683,745,780,852,910,939
                    BatteryParser, SpatialAudioParser, EqPresetParser, WearStatusParser,
                    AncModeParser, TransparencyVocalEnhancementParser, GameModeParser,
                    SwitchFeatureSetParser, NotificationSupportParser, SmartAncLevelParser
```

Every parser is an `object` with a `parse(data: ByteArray): X?` that returns `null` when the
frame is not its type — **no checksum validation and no length-prefixed re-assembly**; the
controller relies on one RFCOMM read mapping to one frame. Each parser re-derives
`cmd = data[4] | (data[5] shl 8)` independently (e.g. `BatteryParser.parse`, `:478-482`).

**Subscription handshake** (a notable design detail):
```
Packets.kt:242-246   QUERY_NOTIFICATION_SUPPORT = 0x0200, NOTIFICATION_SUPPORT_RESPONSE = 0x8200,
                     REGISTER_MULTI_NOTIFICATION = 0x0205
Packets.kt:371-376   Enums.registerMultiNotification(ids) -> payload [count, id1, id2, ...]
RfcommController.kt:765-772  on the 0x8200 reply: filter out ids >= 0xF0 ("debug channels") and
                     register the rest
RfcommController.kt:761-764  comment: id 0x03 in that list carries the smart-mode notify
```

### 5.3 Connection lifecycle and threading (`RfcommController.kt`)

Singleton object: `RfcommController.kt:42`. Transport constants and jobs:

```
RfcommController.kt:44-45   AUTO_RECONNECT_DELAY_MS = 120_000L; APP_UI_ACTIVE_TIMEOUT_MS = 75_000L
RfcommController.kt:48-55   socket: BluetoothSocket?, mContext, mDevice, mPrefs, MediaRouter2
RfcommController.kt:99-101  connectionJob, reconnectJob, readerJob  (kotlinx.coroutines.Job?)
RfcommController.kt:102-103 reconnectAttempts: AtomicInteger, reconnectPending flag
RfcommController.kt:104     OPPO_RFCOMM_UUID = UUID.fromString("0000079A-D102-11E1-9B23-00025B00A5A5")
```

All three jobs run on `Dispatchers.IO` scope-per-job (`CoroutineScope(Dispatchers.IO).launch`),
never a shared dispatcher — see `:630, 687, 711, 768, 1013, 1053, 1070, 1084, 1110, 1129, 1142, 1190, 1198, 1207`.

**`connectPod(context, device, prefs, appRequested = false)` — `:501-554`**
1. cancel `connectionJob`, `reconnectJob`, `readerJob`; `closeSocketOnly()` (`:502-505`)
2. store `mContext`/`mDevice`/`mPrefs`, cache the device name, clear `currentProductId`,
   reset `gameModeFeatureId` from capabilities (`:506-511`)
3. `markAppUiActive()` if the app asked for it (`:512-514`)
4. `ConfigManager.refreshFromPrefs(mPrefs)` (`:515`)
5. register the single UI-event receiver once, with the full action list (`:521-543`)
6. set up `MediaRouter2` and start the route scan (`:545-547`) — used later for audio routing
7. `isConnected = true`; broadcast `"connecting"`; `connectRfcomm(initialDelayMs = 500L)` (`:549-552`)

**`connectRfcomm` — `:628-662`**
```kotlin
connectionJob = CoroutineScope(Dispatchers.IO).launch {
    delay(initialDelayMs)
    closeSocketOnly()
    val newSocket = createRfcommSocket(mDevice)   // device.createRfcommSocketToServiceRecord(OPPO_RFCOMM_UUID)
    newSocket.connect()                            // blocking connect on IO
    socket = newSocket; reconnectAttempts.set(0); reconnectPending = false
    startPacketReader(newSocket.inputStream)
    delay(300)
    sendPacketSafe(OppoPackets.buildQueryProductId(), "product id query")
    delay(50)
    sendPacketSafe(Enums.QUERY_NOTIFICATION_SUPPORT)
    delay(50)
    sendStatusQueryPackets(immediateReconnect = false)
    scheduleAutoGameModeOnConnect(waitForProductId = true)
}
```
Failure path (`:656-660`): broadcast `"error"` and `scheduleReconnect("connect failed")`.

**Reader — `:709-735`**: a `while (isConnected)` loop over `inputStream.read(ByteArray(1024))`;
each positive read is logged (`RfcommLog.d`, hex upper-case) and handed to
`handleOppoPacket(packet)`; `-1` or `IOException` triggers `scheduleReconnect`.

**Reconnect — `:664-698`**: normal path waits `AUTO_RECONNECT_DELAY_MS` (120 s) via
`reconnectJob`; `immediate = true` (used by interactive requests) cancels the pending job and
reconnects at once, skipping if a connect is already active. `reconnectNowForRequest` (`:695-698`)
is only a no-op-fast-path wrapper. Writes call `scheduleReconnect(..., immediate = requestReason != null)`
(`:979-995`), i.e. **a user-initiated command wakes a dead channel; a background poll does not**.

**Teardown — `disconnectedPod(context, device)` (`:936-977`)**: cancel all jobs, close socket,
stop route scan, cancel the MIUI notification, broadcast `ACTION_PODS_DISCONNECTED`, unregister
the receiver, and reset every cached field (`mShowedConnectedToast`, wear, anc, smart-level,
game mode, vocal enhancement, spatial mode, EQ, dual-device, productId, case battery, name,
`mContext`).

### 5.4 State cache and broadcast fan-out

**`StatusSnapshot`** (`:86-96`) is the cross-layer read model:
```kotlin
data class StatusSnapshot(
    val battery: BatteryParams?, val anc: Int, val transparencyVocalEnhancement: Boolean,
    val address: String?, val deviceName: String?, val productId: String?,
    val connected: Boolean, val connecting: Boolean, val reconnectPending: Boolean,
)
```
produced by `currentStatusSnapshot()` (`:307-319`). This is the **only** API the hook layer
uses to read live state (e.g. `BluetoothUpstreamHeadsetHook.kt:658, 681, 544`;
`MiBluetoothToastHook.kt:284`; `MiLinkServiceHook.kt:544, 549`).

**Three broadcast channels:**

| Method | Audience | Guard | Line |
|---|---|---|---|
| `sendAppStatusBroadcast(action)` | our own app (`BuildConfig.APPLICATION_ID`), only while the app UI is "active" | `isAppUiActive()` — an `appUiActive` flag with a 75 s lease refreshed by `ACTION_PODS_UI_INIT` | `:338-361` |
| `sendExternalPodsStatusBroadcast(action)` | `com.milink.service`, `com.xiaomi.bluetooth`, `com.android.settings` | none | `:556-570` |
| `sendRfcommLog` | our own app | `RfcommLog.isEnabled()` | `RfcommLog.kt:63-73` |

The 75-second app-UI lease is a deliberate battery optimisation: the Bluetooth process stops
broadcasting to the module app once the UI has closed (`:222-226, 349-361`).

**Change publishers** (`:112-194`): `changeUIAncStatus`, `changeUIBatteryStatus`,
`changeUIWearStatus`, `changeUIGameModeStatus`, `changeUITransparencyVocalEnhancementStatus`,
`changeUISpatialAudioStatus`, `changeUIEqPreset`, `changeUISmartAncLevel`,
`changeUIDualDeviceConnectionStatus`, `changeUIConnectionState` — one per state field, each
sending both an app broadcast and (where relevant) an external one.

**State rehydration** (`handleUIEvent`, `:196-305`): on `ACTION_PODS_UI_INIT` the controller
replays **all** cached state to the app (`:198-221`), then sets the UI-active lease. The same
handler is the write path: `ACTION_ANC_SELECT`, `ACTION_REFRESH_STATUS`, `ACTION_GAME_MODE_SET`,
`ACTION_AUTO_GAME_MODE_CHANGED`, `ACTION_TRANSPARENCY_VOCAL_ENHANCEMENT_SET`,
`ACTION_SPATIAL_AUDIO_SET`, `ACTION_EQ_PRESET_SET/SAVE/DELETE`,
`ACTION_DUAL_DEVICE_CONNECTION_SET`, `ACTION_CYCLE_ANC`, `ACTION_CONFIG_CHANGED`,
`ACTION_RFCOMM_LOG_CONNECT/DISCONNECT/CLEAR`, `ACTION_RFCOMM_DEBUG_SEND` (`:227-304`).

**MIUI payload encoder** — `miuiRefreshPayload(battery, anc, transparencyVocalEnhancement)`
(`:364-375`) builds a `MutableList(16) { "" }` where slot 0/1/2 = left/right/case battery,
slot 7 = ANC level code, slot 8 = `"true"`, slots 11/13/14 = `"00"`. Battery encoding is
`value or 128` when charging, `"255"` when disconnected (`:377-381`); ANC level codes are
`0103=Smart, 0101=Light, 0100=Medium, 0102=Deep, 0200/0201=Transparency(±vocal enhance)`
(`:383-394`). The hook layer feeds this straight into `refreshStatus(address, payload)`.
`SettingsHeadsetHook.kt:580-595` has a near-duplicate of the same encoder.

### 5.5 Command API

| Method | Packet | Line |
|---|---|---|
| `setANCMode(mode)` | maps 1..8 → `Enums.ANC_*`, swaps OFF/NC when `AncImplementation.COMPATIBLE`, sends, waits 350 ms, re-queries | `:1168-1195` |
| `cycleAnc()` | `[2,4,3,1]` if adaptive else `[2,3,1]` | `:1147-1156` |
| `setGameMode(enabled)` | `Enums.gameModePacket(enabled, gameModeFeatureId)` | `:1009-1016, 1040-1042` |
| `setTransparencyVocalEnhancement(enabled)` | dedicated packets | `:1044-1058` |
| `setSpatialAudioMode(mode)` | switch packet vs. mode packet depending on capability | `:1060-1073` |
| `setEqPreset(presetId)` | `Enums.eqPresetPacket` | `:1075-1087` |
| `saveEqPreset` / `deleteEqPreset` | `OppoPackets.buildSaveEqualizer` / `buildDeleteEqualizer`, then re-query all | `:1100-1134` |
| `setDualDeviceConnection(enabled)` | gated on `dualDeviceSupported` | `:1136-1145` |
| `queryBattery()` / `queryStatus()` / `sendStatusQueryPackets()` | combo: `QUERY_STATUS`, +50 ms, `QUERY_BATTERY`, +50 ms, `QUERY_ANC`, +50 ms, `QUERY_EQ`, (+ `queryAllEqualizers` if custom EQ) | `:1197-1225` |
| `sendDebugHex(hex)` | validates hex, sends raw | `:997-1007` |

**Auto game mode** (`:1018-1038, 1089-1098`): up to 3 attempts, each sends the packet, waits
300 ms, queries status, waits 700 ms (first attempt) / 1500 ms, and gives up early if
`lastGameModeStatusUpdateMs` advanced — i.e. **write-then-verify-read**, not fire-and-forget.

**Standard battery propagation** (`:1292-1304`): `setRegularBatteryLevel(level)` reflectively
calls `BluetoothDevice#setBatteryLevel(level)`, falling back to
`AdapterService/… #setBatteryLevel(device, level, false)`. This is how the *system* (outside
MIUI's headset UI) shows a battery for the device.

**Audio routing** (`:1227-1290`): `disconnectAudio` / `connectAudio` use a `BluetoothProfile`
`HEADSET` proxy via reflection plus `MediaRouter2.transferTo(route)`; `startRoutesScan`/
`stopRoutesScan` (`:478-495`) maintain `routes` via a `RouteCallback`.

### 5.6 Structural pattern to reuse for Sony

Stripping the OPPO bytes, the reusable skeleton is:

```
Transport singleton (object)
  ├─ identity: transport kind + endpoint UUID(s)          ← LOCKED to brand
  ├─ lifecycle: connectPod / connectRfcomm / startPacketReader /
  │             scheduleReconnect / closeSocketOnly / disconnectedPod   ← REUSABLE
  ├─ threading: one CoroutineScope(Dispatchers.IO) per job;
  │             no shared dispatcher; Job fields for cancel        ← REUSABLE
  ├─ framing:  buildPacket(...) + one object Parser per response,
  │             each returning null on mismatch                     ← REPLACE bytes, keep shape
  ├─ cache:    StatusSnapshot + one changeUIXxx publisher per field  ← REUSABLE
  ├─ fan-out:  app broadcast (UI-active gated) +
  │             external broadcast to the 3 system packages          ← REUSABLE
  ├─ write path: handleUIEvent(action → setter)                       ← REUSABLE
  ├─ command API: setXxx() { update cache → broadcast → IO send → delay → re-query } ← REUSABLE
  └─ debug:    RfcommLog ring buffer + sendDebugHex                    ← REUSABLE
```

Sony-specific replacements: the RFCOMM service UUID(s)
(Sony uses `96cc203e-5068-46ad-b32d-e316f5e069ba` v1 and
`956c7b26-d49a-4ba8-b03f-b17d393cb6e2` v2 —
`.reference\HyperEars\docs\sony-headphones-protocol.md:7-8`), the frame delimiters
(`0x3e … 0x3c`, with `0x3d` escaping — same doc, `:23-24`), the checksum, the init handshake
(`00 00`, v1 = 4-byte response, v2 = 8-byte response — `:25`), and the ACK/sequence discipline
(`:31-33`). Note that Sony's protocol is **request/ACK-queued**, so `sendPacketSafe` must
become "enqueue, send only after previous ACK", which is a genuine structural difference from
OppoPods' fire-and-read-loop model.

---

## 6. UI layer

### 6.1 Roles

| File | Lines | Role |
|---|---|---|
| `MainActivity.kt` | 82 | single activity; locale from prefs; edge-to-edge; hosts `App()` |
| `ui/App.kt` | 47 | root composable: theme mode + accent mode + locale provider + `mainMutableStateListOf<Screen>` back stack |
| `ui/Theme.kt` | 46 | `AppTheme(colorSchemeMode, accentMode)`: wraps `MiuixTheme(controller = ThemeController(mode))`, forces `LocalConfiguration.uiMode` for Light/Dark |
| `ui/MainUI.kt` | 942 | the whole app shell: state holders, receivers, navigation, restart-scope plumbing |
| `ui/MainTabs.kt` / `MainTabsPagerState.kt` / `MainBottomNavigation.kt` / `MainTab.kt` | 626 / 56 / 65 / 19 | tabs + navigation bar |
| `ui/pages/*` | — | HomePage, DevicePickerPage, EarphonesTabPage, PodDetailPage, EqualizerPage, RfcommDebugPage, SettingsPage, ThemeSettingsPage, AboutPage |
| `ui/components/*` | — | `AncSwitch` (353), `PodStatus` (227), `AppIcons` (104) |
| `ui/dialogs/*` | — | PodImageConfigDialog, MelodyImageImportDialog, RestartScopeDialog |
| `PopupActivity.kt` | 491 | the notification-tap floating window |

### 6.2 `MainActivity.kt`

```
MainActivity.kt:19-23  attachBaseContext: AppLocale.rememberDeviceLocale + AppLocale.apply(prefs)
MainActivity.kt:28-51  setContent { read theme/accent/floating/blur/language from prefs
                       enableEdgeToEdge(SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { darkMode })
                       window.isNavigationBarContrastEnforced = false }
MainActivity.kt:53-79  App(themeMode, accentMode, floatingBottomBar, blurBottomBar, appLanguage,
                            each with a prefs-writing onXChange callback)
```
All UI state is plain `mutableStateOf` seeded from `SharedPreferences` — no ViewModel layer
(`configurations.configureEach { exclude(group = "androidx.lifecycle", module = "lifecycle-viewmodel-ktx") }`
at `app/build.gradle.kts:88-90` actively removes lifecycle-viewmodel-ktx).

### 6.3 `PopupActivity.kt`

Declared in the manifest with `taskAffinity=""` + `excludeFromRecents="true"` and the custom
action `chen.action.oppopods.show_pods_ui` (`AndroidManifest.xml:45-54`). It is the target of
the notification's content `PendingIntent` (`MiBluetoothToastHook.kt:118-128`).

```
PopupActivity.kt:67-74   read prefs; if notificationClickAction != NOTIFICATION_CLICK_MODULE_POPUP,
                         redirect and finish immediately
PopupActivity.kt:76-93   setContent { AppTheme(...) { PopupContent(onMore, onDone) } }
PopupActivity.kt:111-122 openModule() / openHeyTapOrModule() (falls back to the module)
PopupActivity.kt:124-140 openSystemSettings(device): explicit Intent to
                         com.android.settings/.bluetooth.MiuiHeadsetActivity with
                         MIUI_HEADSET_SUPPORT = ConfigManager.fakeSupport(), DEVICE_ID = fakeDeviceId()
```
This is the "tap the notification → get a HyperOS-styled mini panel" path.

### 6.4 Miuix dependency and HyperOS-styled Compose

Miuix is `top.yukonga.miuix.kmp` (`libs.versions.toml:37-41`, docs credit
`https://github.com/YuKongA/miuix` in `README.md:57`). Five artifacts are used
(`app/build.gradle.kts:106-111`): `miuix-ui-android`, `miuix-preference-android`,
`miuix-icons-android`, `miuix-blur-android`, `miuix-navigation3-ui-android`.

133 import sites across `ui/` (grep for `top.yukonga.miuix`). Representative usage:

| Miuix API | Where |
|---|---|
| `MiuixTheme.colorScheme.*`, `MiuixTheme(controller = ThemeController(mode))` | `ui/Theme.kt:8-10, 39-43` |
| `ColorSchemeMode.Light/Dark/System/MonetLight/MonetDark/MonetSystem` | `ui/Theme.kt:8, 18-26` |
| `Scaffold`, `TopAppBar`, `MiuixScrollBehavior`, `rememberTopAppBarState`, `overScrollVertical` | `ui/MainUI.kt:69-81` |
| `layerBackdrop` / `rememberLayerBackdrop` / `textureBlur` / `LayerBackdrop` | `ui/MainUI.kt:75-76`; `ui/MainBottomNavigation.kt:13-14, 27-30` |
| `NavigationBar` / `FloatingNavigationBar` + Items | `ui/MainBottomNavigation.kt:9-12, 36-63` |
| `Card`, `CardDefaults`, `BasicComponent`, `SmallTitle`, `VerticalSlider` | `ui/pages/*` |
| `SwitchPreference`, `OverlayDropdownPreference`, `DropdownEntry/Item`, `TextField` | `ui/pages/SettingsPage.kt:18-27`, `PodDetailPage.kt:43-45` |
| `OverlayDialog`, `WindowDialog`, `LocalDismissState` | `ui/dialogs/*`, `DevicePickerPage.kt:58, 64` |
| `TabRowWithContour`, `pressable`, `SinkFeedback`, `PressFeedbackType` | `ui/components/AncSwitch.kt:42-45`, `HomePage.kt:42` |
| `MiuixIcons` + `icon.extended.*` / `icon.basic.*` | `ui/MainTabs.kt:53-59`, `SettingsPage.kt:26-27` |

HyperOS styling therefore comes entirely from Miuix primitives + `ThemeController`; there is
no custom design system in this repo. `ui/components/AppIcons.kt` (104 lines) and
`ui/components/AncSwitch.kt` (353 lines) are the two locally-built look-alike widgets.

`navigation3` (`androidx.navigation3:navigation3-runtime`, `libs.versions.toml:13, 42`) plus
`miuix-navigation3-ui-android` are depended on, but `ui/App.kt:28` keeps a hand-rolled
`mutableStateListOf<Screen>` back stack for the main flow — the Navigation3 dependency exists
for the Miuix Navigation3 UI pieces.

### 6.5 Notification / control-center / Super-Island surfacing

This is split between **the app side** (`utils/miuiStrongToast/`, `utils/FocusIslandUtil.kt`)
and **the system side** (`hook/MiBluetoothToastHook.kt`), because the UI elements must be
posted from a process with the right privileges.

**(a) `utils/miuiStrongToast/MiuiStrongToastUtil.kt` (266 lines)** — the "strong toast"
(HyperOS系统级通知/超级岛) API. Mechanism: get `Context.STATUS_BAR_SERVICE` and reflectively call
`setStatus(1, "strong_toast_action", Bundle)`:

```
MiuiStrongToastUtil.kt:32-59    showStringToast(text, colorType): if !isHyperOS → plain Toast;
                                else build StringToastBean → StringToastBundle → setStatus(...)
                                category TEXT_BITMAP_INTENT, package = BuildConfig.APPLICATION_ID
MiuiStrongToastUtil.kt:61-103   showPodsBatteryToast(leftVideoUri, rightVideoUri, threshold, battery):
                                category VIDEO_TEXT_TEXT_VIDEO, duration 5000,
                                package_name "com.xiaomi.bluetooth"
MiuiStrongToastUtil.kt:105-115  showPodsBatteryToastByMiuiBt(...): broadcasts
                                "chen.action.oppopods.sendstrongtoast" to com.xiaomi.bluetooth
MiuiStrongToastUtil.kt:117-168  showOfficialConnectToast(...): uses HyperOS's OWN animation clips
                                (earphone_left_inear / earphone_left_no_inear / right variants),
                                category VIDEO_TEXT_TEXT_VIDEO, notifyId "headset_wear_notification",
                                plus a separate setIslandParam(...) payload
MiuiStrongToastUtil.kt:170-185  hideOfficialToast(): setStatus(1, "strong_toast_action",
                                {"package_name":"com.xiaomi.bluetooth","status_bar_strong_toast":"hide_strong_toast"})
MiuiStrongToastUtil.kt:197-222  officialClipUri(...): copies a bundled raw mp4 into filesDir, calls
                                file.setReadable(true,false), grants read to com.android.systemui, and
                                returns "content://com.xiaomi.bluetooth.fileprovider/internal_files/<name>.mp4"
MiuiStrongToastUtil.kt:224-244  showPodsNotificationByMiuiBt / cancelPodsNotificationByMiuiBt:
                                broadcasts to com.xiaomi.bluetooth
MiuiStrongToastUtil.kt:246-265  Category / FileType / StrongToastCategory constants
```

`utils/miuiStrongToast/StringToastBundle.kt` (46 lines) builds the `Bundle`
(`package_name`, `strong_toast_category`, `duration`, `param`, `island_param`, `notify_id`), and
`utils/miuiStrongToast/data/` holds the serializable payload DTOs:
`Left.kt` (7), `Right.kt` (7), `TextParams.kt` (9), `IconParams.kt` (9), `StringToastBean.kt` (7).

**(b) `hook/MiBluetoothToastHook.kt` (356 lines)** — runs inside `com.xiaomi.bluetooth` and
posts a real MIUI headset notification while presenting OPPO battery data:

```
MiBluetoothToastHook.kt:322-346  hooks constructors of com.android.bluetooth.ble.app.MiuiBluetoothNotification:
                                 (Context, Looper) and (Looper, BluetoothHeadsetService) — whichever exists
                                 → after-hook pulls a Context from args or mContext and calls
                                   registerNotificationReceiver(context)
MiBluetoothToastHook.kt:248-320  receiver for chen.action.oppopods.sendstrongtoast /
                                 updatepodsnotification / cancelpodsnotification /
                                 ACTION_CYCLE_ANC / ACTION_PODS_CONNECTED / _DISCONNECTED / _ANC_CHANGED
MiBluetoothToastHook.kt:49-233   createPodsNotification(device, context, batteryParams):
                                 - reads MIUI strings from com.xiaomi.bluetooth resources
                                   (miheadset_notification_Box / LeftEar / RightEar / Disconnect)
                                 - creates channel "BTHeadset<address>"
                                 - builds a FocusNotification.buildV3 { iconTextInfo{...}; island{
                                   islandProperty = 1; bigIslandArea{ imageTextInfoLeft/Right } };
                                   textButton{ "cycle ANC" action, "disconnect" action } }
                                 - injects AOD text into the focus JSON:
                                   param_v2.aodTitle = "L 88% | R 87%", param_v2.aodPic = "key_headset"
                                   (MiBluetoothToastHook.kt:194-210)
                                 - notificationManager.notifyAsUser(channel, 10003, notification, UserHandle.ALL)
MiBluetoothToastHook.kt:103-107  ANC cycle notification action → broadcast ACTION_CYCLE_ANC routed to
                                 com.android.bluetooth with setIdentifier("BTHeadset$address")
MiBluetoothToastHook.kt:283-303  ACTION_CYCLE_ANC handler: builds the cycle from
                                 detectDeviceCapabilities(...adaptiveSupported),
                                 calls RfcommController.currentStatusSnapshot(), broadcasts ACTION_ANC_SELECT
MiBluetoothToastHook.kt:235-245  cancelNotification via cancelAsUser(...)
```

**(c) `utils/FocusIslandUtil.kt` (131 lines)** — the *module-built* Super Island
(`ISLAND_MODE_MODULE`), independent of MIUI's own:
```
FocusIslandUtil.kt:67-108  FocusNotification.buildV3 { isShowNotification = false; island {
                           islandProperty = 1; bigIslandArea { imageTextInfoLeft/Right with bitmaps;
                           textInfo{ title = "<pct>", content = "%" } }; shareData{...} } }
FocusIslandUtil.kt:110-122 Notification.Builder(channel).addExtras(extras) → nm.notify(10086)
                           auto-cancel after DISMISS_DELAY_MS = 4000L
```

**(d) System API reflection helpers — `utils/SystemApisUtils.kt` (85 lines)**:
`getUserAllUserHandle()` (reflect `UserHandle.ALL`, `:36-38`), `notifyAsUser` / `cancelAsUser`
(`:48-54`), `StatusBarManager#setIconVisibility` (`:56-58`), `BluetoothDevice#getMetadata` /
`setMetadata` with the full MIUI untethered-headset metadata key table (`:15-32, 40-46`).

**Island mode configuration** is a user choice with three values —
`ISLAND_MODE_NONE / OFFICIAL / MODULE` (`ConfigManager.kt:37-39`) plus show-timings
`CONNECTED / WEARING / REMOVED / IN_CASE` (`ConfigManager.kt:40-43`). The official path
publishes its own strong toast and then patches or swallows MIUI's
(`BluetoothUpstreamHeadsetHook.kt:89-105, 710-749`).

---

## 7. Reusable skeleton for a Sony-based module

### 7.1 Copy nearly verbatim (only rename packages / actions)

| OppoPods file | Why it is generic | Adaptation |
|---|---|---|
| `hook/HookEntry.kt` | libxposed `XposedModule` dispatch by `param.packageName`; only the three package names and the pref group name are content | rename class, keep `getRemotePreferences("<brand>_settings")` |
| `hook/HookContext.kt` | pure reflection/hooking façade (`findMethod`, `findMethodByParamCount`, `hookBefore/After/ConstructorAfter`, `HookParam`, `Log`, `get/setObjectField`, `callMethod`) | verbatim |
| `hook/HeadsetStateDispatcher.kt` | A2DP-state-driven channel lifecycle + app request receiver | verbatim except `isOppoPod()` → `isSonyHeadset()` (`:94-97`) |
| `hook/BluetoothUpstreamHeadsetHook.kt` | **the whole MIUI-headset Binder spoofing contract** — `checkSupport`/`getDeviceInfo`/`isMiTWS`/`checkIsMiTWS`/`isSupportAudioSwitch`/`getRingFindState`/`setCommonCommand`/`changeAncMode`/`changeAncLevel`/`register*`/`unregister`, the notification patches, `sendRealStatus` | keep the structure; change only (i) the device predicate, (ii) the MIUI-mode→brand-mode mapping tables (`:767-800`), (iii) the broadcast action names |
| `hook/MiBluetoothToastHook.kt` | MIUI notification + FocusNotification island + ANC cycle action; brand-neutral | swap the notification icon source (`PodImageLoader`) and the `addActionInfo` labels |
| `hook/milink/MiLinkServiceHook.kt` | entire 融合设备中心 contract: `MxBluetoothManager/Service`, `com.miui.headset.runtime.{ProfileContext,AncBatteryController,AncBatteryModel}`, `com.miui.headset.api.HeadsetInfo`, `circular` card strings | keep everything; replace `miLinkAncState()`/`oppoAncFromMiLink()` mappings and the feature-id tables |
| `hook/milink/MiLinkSpatialAudioHook.kt` | spatial-audio sub-contract | replace the mode mapping only |
| `pods/RfcommController.kt` (transport half) | job structure, reconnect policy, `sendPacketSafe`, `currentStatusSnapshot`, `sendAppStatusBroadcast`/`sendExternalPodsStatusBroadcast`, app-UI lease, `setRegularBatteryLevel`, `MediaRouter2` routing, `RfcommLog` plumbing | keep; replace the UUID, the packet builders called in `connectRfcomm`/`sendStatusQueryPackets`, and the parser chain in `handleOppoPacket` |
| `pods/RfcommLog.kt` | brand-neutral | verbatim |
| `utils/SystemApisUtils.kt` | brand-neutral system-API reflection + `isHyperOS` | verbatim |
| `utils/miuiStrongToast/**` (10 files) | brand-neutral HyperOS toast/island payload builder | replace only the raw animation assets (`officialClipUri`, `:197-222`) and the raw mp4 resources |
| `utils/FocusIslandUtil.kt` | brand-neutral module island | replace icon loader |
| `utils/PodImageLoader.kt`, `config/PodImagePrefs.kt`, `config/PodImageProvider.kt` | generic "pick an image per earbud address" subsystem | rename authority |
| `config/ConfigManager.kt` | `AppConfig` + remote-prefs sync + migration | keep; change `DEFAULT_FAKE_DEVICE_ID` (see below) |
| `OppoPodsApp.kt` | `XposedServiceHelper` binding + listener registry | verbatim |
| `MainActivity.kt`, `ui/Theme.kt`, `ui/App.kt`, `ui/AppLocale.kt`, `ui/MainTabs*.kt`, `ui/MainBottomNavigation.kt`, `ui/components/AppIcons.kt`, `ui/dialogs/RestartScopeDialog.kt`, `ui/pages/RfcommDebugPage.kt`, `ui/pages/SettingsPage.kt`, `ui/pages/ThemeSettingsPage.kt`, `ui/pages/AboutPage.kt` | generic Miuix app shell | re-label, drop EQ/spatial pages if unsupported |
| `utils/RootManager.kt`, `utils/MediaControl.kt` | root scope restart + media pause | verbatim |

### 7.2 Rewrite (OPPO-specific)

| OppoPods file / class | What must change for Sony |
|---|---|
| `pods/Packets.kt` → `OppoPackets` | replace with `SonyPackets`: new framing (`0x3e … 0x3c`, `0x3d` escape, big-endian length, additive checksum — `docs\sony-headphones-protocol.md:23-24`), new opcodes, new init handshake (`00 00`) |
| `pods/Packets.kt` → `Cmd`, `AncMode`, `GameModeFeature`, `SpatialAudioMode`, `BatteryComponent`, `WearComponent`, `WearState` | replace constants with Sony's; drop what Sony does not expose |
| `pods/Packets.kt` → `Enums` | replace every pre-built packet with a Sony builder function |
| `pods/Packets.kt` → `BatteryParser`, `AncModeParser`, `WearStatusParser`, `SpatialAudioParser`, `EqPresetParser`, `EqDetailsParser`, `GameModeParser`, `SwitchFeatureSetParser`, `NotificationSupportParser`, `SmartAncLevelParser`, `TransparencyVocalEnhancementParser`, `ProductIdParser` | rewrite as Sony parsers. Keep the **shape**: `object X { fun parse(data: ByteArray): Y? }` returning `null` on mismatch. Add a proper stateful stream decoder because Sony frames are delimited + escaped, not one-read-one-frame |
| `pods/RfcommController.kt` → `OPPO_RFCOMM_UUID` (`:104`) | Sony service UUID(s); note there are **two** (v1/v2) and HyperEars decides v1-vs-v2 by init-response length (4 vs 8) — `docs\sony-headphones-protocol.md:25`. So `createRfcommSocket` may need a try-each-UUID loop |
| `pods/RfcommController.kt` → `sendPacketSafe` (`:979-995`) | must become an **ACK-gated sequential writer** (Sony allows one outstanding request; ACK the device immediately, then send the next queued request) — `docs\sony-headphones-protocol.md:31-33`. This is the single biggest structural change from OppoPods to Sony |
| `pods/RfcommController.kt` → `handleOppoPacket` (`:738-934`) chain of `Parser.parse(packet)?.let { … return }` | keep the chain shape, but feed it from the Sony stream decoder; add Sony's init/ACK handling before the state parsers |
| `pods/RfcommController.kt` → `setANCMode`/`cycleAnc`/`oppoAnc*` mappings (`:112-121, 365-394, 767-800, 1147-1195`) | Sony noise modes are OFF / NOISE_CANCELLING / AMBIENT_SOUND (+ wind-noise on some models) and the v2 ambient dialect differs by model generation (`docs\sony-headphones-protocol.md:12-16`) — so the mapping table must be **per-family/model**, not global |
| `pods/RfcommController.kt` → `miuiRefreshPayload` / `miuiAncLevel` (`:364-394`) | keep the 16-slot CSV shape, but the level codes must map Sony modes into MIUI's `0100/0101/0102/0103/0200/0201` vocabulary; add/replace slots if Game-mode is not exposed |
| `pods/DeviceModelRegistry.kt` + `assets/device_models.json` | **entirely OPPO's own product database.** Replace with a Sony model catalog (model id, form factor, battery topology, whether `WF`/`WH`/`WI`/`LinkBuds`, ambient-sound dialect, service priority). HyperEars' `SonyAdapterConfig` + `SonyEarbudAdapter` / `SonyProtocolFamilyAdapter` three-level split is the cleaner template — `docs\sony-headphones-protocol.md:35-46` |
| `pods/DeviceCapabilities.kt` (`detectDeviceCapabilities`, `AncImplementation`) | keep the DTO idea; the `legacyAnc` OFF/NC swap and `GameModeFeature.LOW_LATENCY/MAIN` IDs are OPPO concepts — drop or replace |
| `assets/eq_mode_names.json` / `.en.json` | OPPO EQ preset ids; drop unless Sony EQ is implemented (HyperEars explicitly excludes Sony EQ: `docs\sony-headphones-protocol.md:18-19`) |
| `hook/milink/MiLinkServiceHook.kt` → `currentGameMode` / `setFindRing` / `isMiTWS` / game-mode card retitling (`:99, 452-490`) | these map game mode onto MIUI's **"find my earbuds" (ring) card**. Reuse only if the Sony module implements an equivalent toggle; otherwise remove the `setFindRing` hook and the `SynergyView` text injection |
| `hook/SettingsHeadsetHook.kt` | keep as an optional fourth scope; it is already brand-neutral apart from `oppoAncFromSettings` / `oppoAncFromLevel` mappings (`:597-616`) and the `isTws01Headset` force (`:108`) |
| `assets/device_models.json`-driven `ui/pages/EqualizerPage.kt` | OPPO EQ UI; drop for Sony v1 |
| `README.md`, `res/values/strings.xml`, `res/values-zh-rCN/strings.xml`, drawables | brand + product art |
| `app/src/main/assets/xposed_init` | write the **real** entry class (see §2.1) |

### 7.3 Concrete class-by-class starting list for a Sony module

```
dev.example.sonypods
├─ SonyPodsApp                     ← copy OppoPodsApp.kt
├─ MainActivity, PopupActivity     ← copy, re-label
├─ config/ConfigManager            ← copy; DEFAULT_FAKE_DEVICE_ID = <a Xiaomi-supported headset id>
├─ config/PodImagePrefs, PodImageProvider, utils/PodImageLoader   ← copy, rename authority
├─ hook/HookEntry                  ← copy; scope = com.android.bluetooth, com.milink.service, com.xiaomi.bluetooth
├─ hook/HookContext, hook/HeadsetStateDispatcher                   ← copy verbatim (rename predicates only)
├─ hook/BluetoothUpstreamHeadsetHook  ← copy the Binder contract; rewrite the ANC mapping tables
├─ hook/MiBluetoothToastHook       ← copy; swap icons/labels
├─ hook/milink/MiLinkServiceHook, MiLinkSpatialAudioHook ← copy; rewrite mode mappings
├─ hook/SettingsHeadsetHook        ← copy (optional)
├─ pods/SonyPackets                ← NEW (frame codec: 0x3e/0x3d/0x3c, checksum, init, ACK)
├─ pods/SonyParsers                ← NEW (battery, noise mode, ambient level, wear, wind noise)
├─ pods/SonyProtocolSession        ← NEW: sequence numbers + ACK queue (Sony-specific concurrency)
├─ pods/RfcommController           ← copy; replace UUID selection, handshake, writer, parser chain
├─ pods/DeviceModelRegistry + assets/sony_models.json  ← NEW catalog
├─ pods/DeviceCapabilities         ← copy shape; new capability fields
├─ pods/RfcommLog                  ← copy verbatim
├─ utils/SystemApisUtils, FocusIslandUtil, miuiStrongToast/** ← copy verbatim (brand-neutral)
└─ ui/**                           ← copy the shell; new pages, drop EqualizerPage
```

**Order of work that the reference suggests:** (1) `HookContext` + `HookEntry` + a
`HeadsetStateDispatcher` that just logs, to prove the scope wiring; (2)
`BluetoothUpstreamHeadsetHook`'s `checkSupport`/`getDeviceInfo`/`isMiTWS` spoofing, to make
HyperOS show a headset UI at all; (3) the RFCOMM transport with a debug-hex page
(`ui/pages/RfcommDebugPage.kt` + `sendDebugHex`) to reverse the Sony frames on the real ROM;
(4) battery/ANC parsers and the `refreshStatus` push-back; (5) MiLink; (6) island/notification
polish.

---

## 8. HyperOS 3 / Android 16 caveats

### 8.1 Version signals inside OppoPods

| Signal | Location | Note |
|---|---|---|
| Target platform stated as HyperOS (Android 15+) | `README.md:44` | "(超级岛仅支持OS3)" — **Super Island is HyperOS 3 (OS3) only** |
| ROM badge 澎湃OS3 | `README.md:14` | the module as shipped targets HyperOS 3 |
| `compileSdk = 37`, `targetSdk = 36` | `app/build.gradle.kts:19, 24` | API 36 = Android 16 |
| `minSdk = 35` | `app/build.gradle.kts:23` | Android 15 |
| `xposedminversion = 101` | `AndroidManifest.xml:73` | requires LSPosed API ≥ 101 |
| `isHyperOS` = `getprop ro.mi.os.version.code` non-empty | `SystemApisUtils.kt:81-84` | boolean only — **no OS version number is ever parsed** |
| `SDK_INT >= TIRAMISU` | `PopupActivity.kt:143` | parcelling only |
| `@RequiresApi(Q)` | `HookEntry.kt:15` | annotation only |

**There is no HyperOS-2 vs HyperOS-3 branch, and no `Android 16` / `Baklava` string anywhere in
the tree.** All cross-version tolerance is the *structural* pattern described in §3.5
(candidate-name lists, param-count lookup, per-hook `runCatching`). Practical consequences for a
new module:

* Do **not** assume class names are stable. Concretely, the names that must be probed
  defensively are `com.android.bluetooth.ble.app.headset.BinderC6776v` / `…headset.v`
  (`BluetoothUpstreamHeadsetHook.kt:148-153`), `com.android.bluetooth.ble.app.C4705R2` (`:86`),
  the obfuscated fields `f18107b`/`f18108c`/`f18109d`/`f18110e` (`:110, 115-118`), and
  `…sticker.p067ui.SynergyView` (`MiLinkServiceHook.kt:472-475`).
* Expect the MIUI/MiLink runtime package names (`com.miui.headset.runtime.*`,
  `com.miui.circulate.*`, `com.miui.circulateplus.*`) to be the most stable anchors, and the
  *implementation* classes behind them (`MxBluetoothManager`/`MxBluetoothService`) the least.
* Treat "the hook class exists" as a runtime decision, never a compile-time one.

### 8.2 What `HyperEars` does differently

HyperEars is the better reference for **multi-version** support, because it was designed to
survive MiLink releases.

| Aspect | OppoPods | HyperEars | Evidence |
|---|---|---|---|
| Build stack | AGP 9.1.0, Kotlin 2.4.0, Compose BOM 2026.05.01, Miuix 0.9.2, libxposed 101.0.1/101.0.0 | AGP 9.4.0, Kotlin 2.4.10, Compose BOM 2026.06.00, Miuix 0.9.3, **libxposed 102.0.0** | `libs.versions.toml` of each |
| SDK | compile 37 / min 35 / target 36 | compile 37 / min 35 / target 36 (identical) | `HyperEars\system-module\build.gradle.kts:19,23-24` |
| Java level | Java **22** toolchain (app) | Java **17** (`sourceCompatibility/targetCompatibility = VERSION_17`) | `OppoPods\app\build.gradle.kts:78-86` vs `HyperEars\system-module\build.gradle.kts:68-71` |
| Explicit Android-16 note | none | `lint { /* Android 16 is the deliberate deployment target for the current HyperOS device. */ disable += setOf("OldTargetApi","ObsoleteSdkInt") }` | `HyperEars\system-module\build.gradle.kts:76-80` |
| Android-version branches in hook code | `SDK_INT >= TIRAMISU` (parcelling only) | **none** — grep for `SDK_INT|VERSION_CODES` finds hits only in `protocol-test` UI code and a diagnostics string | grep over `HyperEars/**/*.kt` |
| Version-table / obfuscation strategy | ad-hoc candidate name lists + `runCatching` | **three tiers**: stable symbols → a verified per-release table → **DexKit DEX fingerprinting as a last resort** | `MiLinkServiceHook.kt:662-668` (comment names `b0.L` on MiLink 17.2.0 vs `HeadsetServiceController.getSupportAncMode` on 17.2.4) and `MiLinkHeadsetSettingsDexResolver.kt:8-15, 36-63` |
| DexKit dependency | none | `org.luckypray:dexkit:2.2.0` | `HyperEars\gradle\libs.versions.toml:14,37`; `system-module\build.gradle.kts:101` |
| Scope declaration | `@array/xposedscope` | `@array/xposed_scope`, **16 packages**, incl. every vendor control app and `com.sony.songpal.mdr` | `HyperEars\system-module\src\main\res\values\strings.xml:4-21` |
| Entry point | `onPackageLoaded` | `onPackageLoaded`, additionally filtered by **process name** for MiLink | `HyperEars\system-module\...\HookEntry.kt:29-45, 69-75` |
| Native-ownership arbitration | none (always spoofs) | refuses to override an address the ROM already owns natively | `HyperEars\...\MiLinkServiceHook.kt:93-99`; `docs\compatibility.md:301-304` |
| Platform-scope statement | HyperOS Android 15+ | "Android 15+ Xiaomi HyperOS, LSPosed API 101… **MiLink, the Bluetooth service and the native cards are ROM internals; binary stability across HyperOS major versions is not promised**" ; verified on Xiaomi 14 Pro / Pad 6S Pro / REDMI K Pad, and **HyperOS 4's MiLink native three-state card + wind-noise branch switch were device-verified** | `HyperEars\docs\compatibility.md:294-299` |

Key takeaways for a HyperOS-3/Android-16 build:

1. **Add a version table, not just name probing.** HyperEars' comment at
   `MiLinkServiceHook.kt:665-667` documents a *real* rename between two MiLink point releases
   of the same HyperOS major version. A candidate list handles that; a DEX fingerprint
   (`MiLinkHeadsetSettingsDexResolver`) handles the case where the candidate list is empty.
2. **Filter by process name for `com.milink.service`.** HyperEars only installs into
   `:audio`, `:core`, `:ui` (`HookEntry.kt:70-75`); OppoPods does not filter
   (`OppoPods\hook\HookEntry.kt:25`). On newer builds the wrong sub-process can install hooks
   that never fire, or fire twice.
3. **Do not publish capabilities the device has not proven.** HyperEars' "capability
   authenticity" rule (`docs\system-module-architecture.md:265-287`) — start with
   Android's stock battery, and only promote to a private source after a valid protocol
   response — is the correct answer to ROM changes that silently alter card semantics.
4. **Expect the *island/notification* surface to be the most ROM-fragile part**, since it is
   driven by MIUI-internal `setStatus(1, "strong_toast_action", bundle)` calls whose
   `strong_toast_category` / `island_param` / `notify_id` semantics are not public
   (`MiuiStrongToastUtil.kt:52-55, 89-98, 150-163`). Both the OppoPods README (`:44`, island
   only on OS3) and HyperEars' compatibility note (`:296-299`) point the same way.

### 8.3 What `HyperVolumeANC` does differently

`HyperVolumeANC` targets **HyperOS 4 Beta** (`README.md:11`) and takes a very different tactic
because it hooks **SystemUI**, whose plugin classes are loaded lazily:

| Aspect | HyperVolumeANC | Evidence |
|---|---|---|
| Build | AGP plugins by id (no catalog), Kotlin Android + Compose, **Java 17**, `compileSdk 37`, **`minSdk 34`**, `targetSdk 36` | `app\build.gradle.kts:7-18, 32-41` |
| Xposed API | `compileOnly("io.github.libxposed:api:102.0.0")` | `app\build.gradle.kts:50` |
| Signing | signs release with the **debug key** so the APK installs directly | `app\build.gradle.kts:20-29` |
| Entry / lifecycle | subclasses `XposedModule` but implements **`onModuleLoaded(ModuleLoadedParam)` and `onPackageReady(PackageReadyParam)`**, not `onPackageLoaded` | `hook\HookEntry.java:15, 38-59` |
| Scope | **no `xposedscope` array and no `assets/xposed_init`** in the tree; scope packages are runtime constants `com.android.systemui` and `com.xiaomi.bluetooth` | `hook\HookEntry.java:17-18`; directory listing of `app\src\main` shows only `java/ res/ resources/ AndroidManifest.xml` |
| Lazy-class strategy | hooks **`ClassLoader.loadClass(String, boolean)`** and waits for the HyperOS volume-plugin class, then installs layout/animation hooks | `hook\HookEntry.java:100-115, 119-121, 58` |
| HyperOS-specific class names it depends on | `com.android.systemui.miui.volume.MiuiRingerModeLayout`, `…MiuiVolumeDialogRes`, `…VolumeShowHideAnimator`, `…VolumeExpandCollapsedAnimator`, `…volume.Util#sIsNotificationSingle` | `hook\HookEntry.java:19-26`; `hook\VolumeButtonInjector.java:558` |
| Bluetooth-extension hook | `com.android.bluetooth.ble.app.headset.miuibluetoothprovider.MiuiBluetoothContentProvider` + `com.android.bluetooth.ble.app.permission.PermissionChecker` — hooked in `com.xiaomi.bluetooth` | `hook\HookEntry.java:27-30, 66-77` |
| ANC control contract | talks the **same MIUI headset Binder**: `SERVICE_ACTION = "miui.bluetooth.mible.BluetoothHeadsetService"`, `SERVICE_PACKAGE = "com.xiaomi.bluetooth"`, `DESCRIPTOR = "com.android.bluetooth.ble.app.IMiuiHeadsetService"` | `hook\AncController.java:41-43` |
| Version gating | grep for `SDK_INT|VERSION_CODES|ro.mi.os.version|hyperos` finds **nothing** in the hook code; only the README badge | grep over the tree |

Two genuinely reusable ideas from HyperVolumeANC:

* **A `ClassLoader.loadClass` hook + plugin-package constant** (`PLUGIN_PACKAGE =
  "miui.systemui.plugin"`, `hook\VolumeButtonInjector.java:25`) is the robust way to reach
  HyperOS classes that a major-version upgrade moved into a *plugin APK* rather than the
  SystemUI APK. If a HyperOS 3→4 change relocates a class a new module needs, this is the
  pattern to fall back on.
* **`HookHeartbeat`** (`hook\HookHeartbeat.java`, 138+ lines) hooks `ActivityThread` and
  `Instrumentation` to re-assert hooks after process restarts — worth copying if the target
  ROM aggressively kills/restarts the Bluetooth or SystemUI process.

The shared contract across all three modules — and therefore the safest anchor for a new
one — is:

```
package com.xiaomi.bluetooth (or com.android.bluetooth)
  descriptor  com.android.bluetooth.ble.app.IMiuiHeadsetService
  service     miui.bluetooth.mible.BluetoothHeadsetService
  extras      android.bluetooth.device.extra.DEVICE,
              MIUI_HEADSET_SUPPORT, DEVICE_ID, COME_FROM, bluetoothaddress
```

(Evidence: `OppoPods\hook\BluetoothUpstreamHeadsetHook.kt:26`;
`OppoPods\config\ConfigManager.kt:97`; `OppoPods\hook\SettingsHeadsetHook.kt:61-88`;
`HyperVolumeANC\hook\AncController.java:41-43, 59`.)

---

## 9. Quick reference — every hooked class, by package

### `com.android.bluetooth`
| Class | Method | File:line |
|---|---|---|
| `com.android.bluetooth.btservice.AdapterService` | `onCreate` | `hook/HeadsetStateDispatcher.kt:23` |
| `com.android.bluetooth.a2dp.A2dpService` | `handleConnectionStateChanged` (3 params) | `hook/HeadsetStateDispatcher.kt:30` |
| `com.android.bluetooth.ble.app.MiuiBluetoothNotification` | ctor `(Context, Looper)` / `(Looper, BluetoothHeadsetService)` | `hook/MiBluetoothToastHook.kt:322-346` |
| `com.android.bluetooth.ble.app.MiuiBluetoothNotificationApi` | `showNewConnectedToast(int,int,int,int,BluetoothDevice,String)` | `hook/BluetoothUpstreamHeadsetHook.kt:51-59` |
| `com.android.bluetooth.ble.app.MiuiBluetoothNotification` | `invokeStatusBar(Context,String,Bundle)` | `hook/BluetoothUpstreamHeadsetHook.kt:89` |
| `com.android.bluetooth.ble.app.MiuiBluetoothNotification` | `updateParameters(C4705R2)` | `hook/BluetoothUpstreamHeadsetHook.kt:108` |
| `com.android.bluetooth.ble.app.headset.BluetoothHeadsetService` | `onBind(Intent)`, `onCreate()` | `hook/BluetoothUpstreamHeadsetHook.kt:131, 139` |
| `…headset.BinderC6776v` **or** `…headset.v` | `checkSupport`, `getDeviceInfo`, `isSupportAudioSwitch`, `isMiTWS`, `checkIsMiTWS`, `getRingFindState`, `setCommonCommand`, `connect`, `getDeviceConfig`, `getCommonConfig`, `changeAncMode`, `changeAncLevel`, `register`, `registerCallbackDevice`, `unregister` | `hook/BluetoothUpstreamHeadsetHook.kt:148-296` |
| `…ble.app.IMiuiHeadsetService$Stub` | `onTransact` (dormant fallback, 15 opcodes) | `hook/BluetoothUpstreamHeadsetHook.kt:414-458` |

### `com.xiaomi.bluetooth`
| Class | Method | File:line |
|---|---|---|
| `com.android.bluetooth.btservice.AdapterService` | `onCreate` | `hook/HeadsetStateDispatcher.kt:23` (same object, second process) |
| `…a2dp.A2dpService` | `handleConnectionStateChanged` | `hook/HeadsetStateDispatcher.kt:30` |
| `…ble.app.headset.BluetoothHeadsetService` | `onBind`, `onCreate` | `hook/BluetoothUpstreamHeadsetHook.kt:131, 139` |
| `…ble.app.MiuiBluetoothNotification` | ctor variants | `hook/MiBluetoothToastHook.kt:322-346` |
| (+ all `BluetoothUpstreamHeadsetHook` targets) | | `hook/BluetoothUpstreamHeadsetHook.kt:126-296` |

### `com.milink.service`
| Class | Method | File:line |
|---|---|---|
| `com.xiaomi.mxbluetoothsdk.service.MxBluetoothService` | `getInstanceForIsMiTWS(Context)`; `checkIsMiTWS`, `getDeviceId`, `getBatteryLevel`, `getAncState`, `getDeviceRunInfo`, `getWearStatus`, `isLeAudio`, `openAnc`, `closeAnc`, `openTransparent`, `isMiTWS`, `isSupportAudioSwitch`, `getRingFindState`, `getSpatialMode`, `setSpatialMode` | `hook/milink/MiLinkServiceHook.kt:56, 73-87`; `MiLinkSpatialAudioHook.kt:14-15` |
| `com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager` | same set | `hook/milink/MiLinkServiceHook.kt:57, 68-90` |
| `com.miui.headset.runtime.ProfileContext` | `getDeviceId`, `getBatteryLevel`, `getAudioSpatialEffectState`, `setAudioEffectState` | `MiLinkServiceHook.kt:93-94`; `MiLinkSpatialAudioHook.kt:134, 146` |
| `com.miui.headset.runtime.AncBatteryController` | `getDeviceId`, `getAncState`, `getBatteryLevelCache`, `getHeadsetPropertyBlock`, `getFindRingState`, `getSwitchState`, `setAncStateBlock`, `setFindRing`, `getMiAudioEffect`, `setMiAudioEffect`, `setHeadTracking` | `MiLinkServiceHook.kt:95-101, 157, 459`; `MiLinkSpatialAudioHook.kt:20, 66, 81` |
| `com.miui.headset.runtime.AncBatteryModel` | `getDeviceSpatialType`, `setDeviceSpatialType` | `MiLinkSpatialAudioHook.kt:97, 104` |
| `com.miui.headset.runtime.AncBatteryController$mmaCallback$1` | `onDeviceSpatialType`, `onReportSpatialState` | `MiLinkSpatialAudioHook.kt:113, 122` |
| `com.miui.headset.api.HeadsetInfo` | `getDeviceId`/`component3`, `getPowers`/`component4`, `getMode`/`component5`, `getSwitchState`/`component8`, `getFindRingState`/`component11`, `getAudioEffectState`/`component10` | `MiLinkServiceHook.kt:103-112`; `MiLinkSpatialAudioHook.kt:28-29` |
| `com.miui.circulate.api.service.CirculateServiceInfo` | `setHeadsetId(String, Int)` | `MiLinkSpatialAudioHook.kt:34` |
| `com.miui.circulate.world.sticker.ui.SynergyView` (or `…sticker.p067ui.SynergyView`) | `setTitle(Int)` | `MiLinkServiceHook.kt:472-489` |

### `com.android.settings` (compiled, scope commented out — `HookEntry.kt:24`)
`bluetooth.MiuiHeadsetActivity` (`onCreate`, `getDeviceID`, `getSupport`),
`bluetooth.MiuiHeadsetActivityPlugin` (`onCreate`),
`bluetooth.HeadsetIDConstants` (`checkSupport`, `isTWS01Headset`, `isK77sHeadset`,
`isBleMmaConnect` ×2), `bluetooth.tws.MiuiHeadsetBattery` (ctor, `onBatteryChanged`),
`bluetooth.MiuiHeadsetFragment` (`onCreateView`, `onServiceConnected`, `refreshStatus`,
`handleConnectMmaFailed`, `updateAncMode`, `updateAncLevel`),
`com.android.bluetooth.ble.app.IMiuiHeadsetService$Stub$Proxy` (12 methods) —
`hook/SettingsHeadsetHook.kt:61-348`.

---

## 10. Appendix — broadcast contract (copy this table into a new module)

`utils/miuiStrongToast/data/OppoPodsAction.kt` (43 lines) is the process-to-process API
surface. Renaming `chen.action.oppopods.*` is the only change needed.

| Action | Direction | Payload extras | Line |
|---|---|---|---|
| `…ui_init` | app → Bluetooth | — | 4 |
| `…ui_closed` | app → Bluetooth | — | 5 |
| `…module_bluetooth_service_alive` | Bluetooth → app | — | 6 |
| `…pods_connected` / `…pods_disconnected` | Bluetooth → app + externals | `address`, `device_name`, `product_id` | 7-8 |
| `…connect_pod_request` / `…disconnect_pod_request` | app → Bluetooth | `device` (BluetoothDevice) | 9-10 |
| `…pods_connection_state_changed` | Bluetooth → app | `state` ∈ {connecting, connected, disconnected, error} | 11 |
| `…pods_battery_changed` | Bluetooth → app + externals | `status` (Parcelable) **or** `left/right/case_battery`, `_charging`, `_connected` | 12 |
| `…pods_wear_status_changed` | Bluetooth → app | `left/right/case_wear_status` | 13 |
| `…anc_select` | any → Bluetooth | `status` 1..8 | 14 |
| `…pods_anc_select` | Bluetooth/Settings/MiLink → app | `status` | 15 |
| `…refresh_status` | any → Bluetooth | — | 18 |
| `…game_mode_set` | MiLink → Bluetooth | `enabled` | 19 |
| `…pods_game_mode_changed` | Bluetooth → app | `enabled` | 20 |
| `…transparency_vocal_enhancement_set` | MiLink/Settings → Bluetooth | `enabled` | 21 |
| `…pods_transparency_vocal_enhancement_changed` | Bluetooth → app | `enabled` | 22 |
| `…spatial_audio_set` | MiLink → Bluetooth | `mode` 0..2 | 23 |
| `…pods_spatial_audio_changed` | Bluetooth → app | `mode` | 24 |
| `…eq_preset_set` | app → Bluetooth | `preset` | 25 |
| `…pods_eq_preset_changed` | Bluetooth → app | `preset`, `preset_ids[]`, `preset_names[]`, `eq_entries_json` | 26 |
| `…eq_preset_save` / `…eq_preset_delete` | app → Bluetooth | `id`, `name`, `frequencies[]`, `gains[]`, `min_value`, `max_value` | 27-28 |
| `…pods_smart_anc_level_changed` | Bluetooth → app | `ordinal` | 29 |
| `…dual_device_connection_set` | app → Bluetooth | `enabled` | 30 |
| `…pods_dual_device_connection_changed` | Bluetooth → app | `enabled` | 31 |
| `…cycle_anc` | notification action → Bluetooth | `device_name` | 32 |
| `…auto_game_mode_changed` | app → Bluetooth | `enabled` | 33 |
| `…rfcomm_log_connect` / `_disconnect` / `_clear` / `_debug_send` | app → Bluetooth | `hex` | 34-38 |
| `…rfcomm_log` | Bluetooth → app | `level`, `tag`, `message`, `time` | 37 |
| `…config_changed` | app → all scopes | — | 41 |
| `chen.action.oppopods.sendstrongtoast` | Bluetooth → `com.xiaomi.bluetooth` | `batteryParams`, `address` | `MiuiStrongToastUtil.kt:110-114` |
| `chen.action.oppopods.updatepodsnotification` | Bluetooth → `com.xiaomi.bluetooth` | `batteryParams`, `device` | `MiuiStrongToastUtil.kt:229-233` |
| `chen.action.oppopods.cancelpodsnotification` | Bluetooth → `com.xiaomi.bluetooth` | `device` | `MiuiStrongToastUtil.kt:240-243` |
| `chen.action.oppopods.show_pods_ui` | notification PendingIntent → `PopupActivity` | `android.bluetooth.device.extra.DEVICE`, `bluetoothaddress`, `device_name` | `AndroidManifest.xml:51`; `MiBluetoothToastHook.kt:121-126` |

Config is shared out-of-band through the LSPosed remote preference group
`oppopods_settings` (`HookEntry.kt:38`), keyed by `config_json` plus flat per-field keys
(`ConfigManager.kt:23-32, 162-174`).

---

*End of notes. No files outside `docs/hyperos-integration-notes.md` were created or modified.*

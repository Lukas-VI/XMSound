# 小米耳机 Binder API 完整参考（HyperOS 3 实测）

本文件是在**目标真机**上通过运行时反射得到的**权威** API 表，不是从其他项目抄来的推测。
原始日志见 `class-probe-imiuheadsetservice-aidl.txt`。

## 1. 传输层

| 项目 | 值 |
| --- | --- |
| 进程 | `com.xiaomi.bluetooth`（独立进程，APK 为 `BluetoothExtension.apk`） |
| 服务实现 | `com.android.bluetooth.ble.app.headset.BluetoothHeadsetService`（208 方法，**已 R8 混淆**） |
| Binder 实现类 | `com.android.bluetooth.ble.app.headset.v`（混淆名，运行时从 `onBind` 取） |
| Stub 声明类 | `com.android.bluetooth.ble.app.r1`（混淆名，`onTransact` 所在类） |
| 接口描述符 | `com.android.bluetooth.ble.app.IMiuiHeadsetService` |
| 服务名 | `miui.bluetooth.mible.BluetoothHeadsetService` |
| 客户端 | `com.android.bluetooth.ble.app.IMiuiHeadsetService$Stub$Proxy`（**方法名未混淆**） |

> `BluetoothHeadsetService` 的方法名形如 `A`/`A0`/`B1`/`C2`，**按方法名 hook 不可行**。
> 必须走 `Service.onBind` + `Binder.onTransact` 这两个**框架名**。

## 2. 事务码表（从 `IMiuiHeadsetService$Stub` 的 `TRANSACTION_*` 常量反射读出）

| opcode | 方法 | 签名 | 说明 |
| --- | --- | --- | --- |
| 1 | `checkSupport` | `String(BluetoothDevice)` | **支持性判定（关键卡点）** |
| 2 | `register` | `void(IMiuiHeadsetCallback)` | 注册回调 |
| 3 | `unregister` | `void(IMiuiHeadsetCallback, BluetoothDevice)` | 注销回调 |
| 4 | `connect` | `void(BluetoothDevice)` | |
| 5 | `disconnect` | `void(BluetoothDevice)` | |
| 6 | `setFunKey` | `void(int, int, BluetoothDevice)` | 自定义按键 |
| 7 | `startOta` | `void(BluetoothDevice, String, String, String)` | |
| 8 | `changePlayStatus` | `void(int, BluetoothDevice)` | |
| 9 | `changeAncMode` | `void(int, BluetoothDevice)` | **降噪模式设置** |
| 10 | `changeAncLevel` | `void(String, BluetoothDevice)` | **降噪等级设置** |
| 11 | `getDeviceInfo` | `String(String)` | **设备信息（电量等）** |
| 12 | `getDeviceConfig` | `void(BluetoothDevice)` | |
| 13 | `localOta` | `void(BluetoothDevice)` | |
| 14 | `setCommonCommand` | `String(int, String, BluetoothDevice)` | 通用命令 |
| 15 | `getCommonConfig` | `void(BluetoothDevice, String)` | |
| 16 | `registerCallbackDevice` | `void(IMiuiHeadsetCallback, BluetoothDevice)` | 按设备注册回调 |
| 17 | `ignorePairDialog` | `boolean(String, String)` | |
| 18 | `isMiTWS` | `boolean(String)` | |
| 19 | `checkIsMiTWS` | `boolean(String)` | 是否小米 TWS |
| 20 | `isSupportAudioSwitch` | `boolean(String)` | |
| 21 | `checkIsAirPods` | `boolean(String)` | 是否 AirPods |
| 22 | `getAirPodsState` | `String[](String)` | |
| 23 | `ringFindForAirPods` | `boolean(String, boolean)` | |
| 24 | `getRingFindState` | `boolean(String)` | 查找耳机 |

## 3. 回调接口

`com.android.bluetooth.ble.app.IMiuiHeadsetCallback`：

| 方法 | 签名 |
| --- | --- |
| `refreshStatus` | `void(String, String)` |

## 4. 实测流量（WF-1000XM5 = `AC:80:0A:10:00:DB`）

| opcode | 方法 | 请求参数 | 应答 |
| --- | --- | --- | --- |
| 1 | `checkSupport` | XM5 地址 | **12 字节全 0（= 不支持）** |
| 1 | `checkSupport` | PCM-A10 地址 | 12 字节全 0 |
| 3 | `unregister` | 无地址 | — |
| 14 | `setCommonCommand` | `1.4_1.83` + 地址 | `"false"` |
| 14 | `setCommonCommand` | `SettingsOriginal` + 地址 | `02 00 00 00 2c 00 20 00` |
| 16 | `registerCallbackDevice` | 地址 | — |
| 19 | `checkIsMiTWS` | 地址 | — |
| 21 | `checkIsAirPods` | 地址 | — |

**设置页确实在为 XM5 逐项询问，全部得到否定回答** —— 这就是要改写的地方。

## 5. 相关类（`com.milink.service` 侧，方法名可读）

| 类 | 成员数 | 说明 |
| --- | --- | --- |
| `com.miui.headset.runtime.AncBatteryController` | 52 方法 / 20 字段 | 降噪与电量总控；持有 `Access$getMxBluetoothManager`、`AncBatteryModel`、`interceptAJustAncEarphones` 等 |
| `com.miui.headset.runtime.AncBatteryModel` | — | 状态模型 |
| `com.miui.headset.api.HeadsetInfo` | 27 / 11 | UI 数据模型（`getPowers`/`getMode`/…） |
| `com.miui.circulate.api.protocol.headset.HeadsetServiceClient` | 72 / 30 | 协议客户端 |

## 6. 实现要点

1. **定位**：hook `BluetoothHeadsetService#onBind` → 取返回的 `IBinder` →
   反射其 `onTransact(int, Parcel, Parcel, int)` 并装钩子。实现类名（`v`/`r1`）是混淆产物，
   必须运行时获取，不可硬编码。
2. **改写应答**：在 `hookAfter` 中按 opcode 判断，重写 `reply` Parcel。
   注意 AIDL 应答以 `writeNoException()`（int 0）开头，其后才是返回值。
3. **必须抓的下一步**：**受支持机型**的 `checkSupport` 应答字符串格式。
   参考实现（OppoPods）使用 `<deviceId>,<24 位能力位图>`，例如
   `"01010607,000000000000000010000000"` —— 但需在本 ROM 上验证。
4. **转发降噪**：拦截 opcode 9 / 10，转成协议层
   `68 17 01 <on> <0=NC|1=AS> <focus> <level>`。
   `changeAncMode` 的 `int` 取值需与 `com.miui.headset.api.AncMode` /
   `com.miui.headset.runtime.AncBatteryController` 对齐。

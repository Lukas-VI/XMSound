# 澎湃OS 集成方案（基于真机实测）

本文是 `hyperos-integration-notes.md`（基于参考实现整理）的**真机校正与落地计划**。
所有类名、方法签名均来自在本机（Xiaomi 13 Ultra / HyperOS OS3.0.305 / Android 16）
运行**运行时类探针**得到的实测结果，原始输出见 `captures/`。

---

## 1. 模块加载：已解决

**症状**：模块在 LSPosed 中显示为已启用、作用域也已勾选，但任何目标进程都不注入。

**根因**：libxposed API 100+ **不使用**旧版 Xposed 的声明方式。LSPosed 读到了
`assets/xposed_init`，走遗留路径加载入口类，发现它不是 `IXposedMod` 就跳过了：

```
E LSPosedFramework: (com.android.bluetooth)[framework,XposedBridge]
    This class doesn't implement any sub-interface of IXposedMod, skipping it
```

**正确声明**（三个参考项目 OpsoPods / HyperEars / HyperVolumeANC 全部如此）：

| 用途 | 旧版 | 新版（libxposed 100+） |
| --- | --- | --- |
| 入口类 | `assets/xposed_init` | `resources/META-INF/xposed/java_init.list` |
| 作用域 | manifest `xposedscope` | `resources/META-INF/xposed/scope.list` |
| API 版本 | manifest `xposedminversion` | `resources/META-INF/xposed/module.prop` |
| 模块描述 | manifest `xposeddescription` | `<application android:description>` |

`module.prop` **必须存在**，否则 LSPosed 不把它识别成模块：

```
minApiVersion=101
targetApiVersion=101
staticScope=true
```

修复后模块正常注入，且**重装 APK 后自动重新加载**，无需重启或手动开关作用域。

---

## 2. 实测环境

| 项目 | 值 |
| --- | --- |
| LSPosed | 2.0.0 (7607)，API 101，已激活 |
| 管理器包名 | `com.android.shell`（寄生式管理器） |
| 注入方式 | Zygisk（日志可见 `zygisk_lsposed` 映射） |
| 目标进程 | `com.android.bluetooth`、`com.milink.service[:audio\|:ui\|:persistent\|:core\|:provider\|:crossdeviceservice]`、`com.android.settings`、`com.xiaomi.bluetooth` |

抓取方式（无需 root）：

```bash
adb logcat -s XMSound-Hook     # 钩子层
adb logcat -s XMSound-Sony     # 协议层
```

---

## 3. 关键结论：澎湃**完全不认识** WF-1000XM5

实测打开「融合设备中心」，其中只有手机本体与小米手环 7 Pro，**没有耳机**。
同时 `MiLinkHeadsetProbe` 在 `com.milink.service` 里对
`HeadsetInfo#getPowers/getMode/getName` 与 `HeadsetState#getBattery/getName`
装了 12 个钩子（全部安装成功），**这些 getter 一次都没有被调用**。

原因与之前的 APK 字符串扫描一致：`com.xiaomi.bluetooth`(BluetoothExtension) 的 DEX 中
**不存在任何 Sony 相关字符串**，澎湃的耳机 UI 只对受支持的自有/合作型号生效。

> **这是架构级结论**：本项目的集成方式**不是「把数据注入已有的耳机卡片」**，
> 而是必须**先让澎湃把 XM5 认作受支持的小米耳机**（即 OppoPods 的机型伪装路线），
> 之后电量与降噪才有地方显示。

---

## 4. 实测 API 地图

### 4.1 `com.android.bluetooth`（APEX `Bluetooth@BP2A.250605.031.A3`）

澎湃在 AOSP 之上叠了三层自有类：

| 类 | 成员数 | 关键方法/字段 |
| --- | --- | --- |
| `btservice.HyperAdapterService` | 7 方法 / 12 字段 | `devicePropertyChangedCallback(byte[] propBytes, int[] propTypes, byte[][] values, RemoteDevices, AdapterService)`、`handleAirpodsInfo(AdapterService, byte[], String)`、`batteryServiceConnectDevice(BatteryService, BluetoothDevice, AdapterService)`、`updateName(String, BluetoothDevice, int)`、`mDeviceInfoMap` |
| `btservice.HyperAdapterService$DeviceInfo` | 2 / 2 | `getGattName()/setGattName(String)`、`mDevice`、`mGattName` |
| `btservice.HyperAdapterService$MiAbstractionLayer` | 0 / 6 | `BT_PROPERTY_VENDOR_PRODUCT_INFO_HYPER`、`BT_PROPERTY_ACL_BDNAME` |
| `hfp.HyperHeadsetService` | 2 方法 | `isHfpConnectionUnderRestrict(HeadsetService)`、`isReconnectAllowWhenHfpForbidden(BluetoothDevice, A2dpService)` |
| `bas.BatteryService` | 24 方法 | `handleBatteryChanged(BluetoothDevice, int)`、`handleConnectionStateChanged(BluetoothDevice, int, int)`、`getOrCreateStateMachine(BluetoothDevice)` |

> **`handleAirpodsInfo(AdapterService, byte[], String)` 是最有价值的线索**：
> 澎湃自身就是用一段「厂商产品信息」字节串来识别第三方耳机（AirPods）的。
> `BT_PROPERTY_VENDOR_PRODUCT_INFO_HYPER` + `VENDOR_PRODUCT_INFO_LENGTH` 表明这条信息
> 经 `devicePropertyChangedCallback` 送达。**这是让澎湃「认识」XM5 的最自然入口。**
>
> 注：`HyperHeadsetService` 只有两个 HFP 限制判断方法，**不是**耳机功能 API——
> 与 `hyperos-integration-notes.md` 的推测不同，实测澄清了这一点。

### 4.2 `com.milink.service`（`MiLinkOS3Cn.apk`）

融合设备中心的耳机栈，1136 个匹配类。核心数据模型（`-` 表示探针未列出全部）：

| 类 | 成员数 | 关键成员 |
| --- | --- | --- |
| `com.miui.headset.api.HeadsetInfo` | 27 方法 / 11 字段 | `getAddress`、`getDeviceId`、`getName`、`getPowers(): List`、`getMode`、`getSwitchState`、`getType`、`getAudioEffectState`、`getWiredState` |
| `com.miui.headset.api.AncState` | 字段 | `AncClose`、`AncOn`、`AncNotSupport` |
| `com.miui.headset.api.AncMode` | 字段 | `NoiseCancelling` … |
| `com.miui.headset.api.AudioEffectState` | 字段 | … |
| `com.miui.headset.api.HeadsetType` | 8 字段 | … |
| `com.miui.headset.api.HeadsetClient` | 36 / 19 | 客户端入口 |
| `com.miui.headset.api.HeadsetHost` | 15 / 5 | 宿主入口 |
| `com.miui.headset.api.HeadsetResult` | 0 / 59 | 53 个结果码常量 |
| `com.miui.headset.api.IHeadsetLocalService$Stub` | 3 / 10 | Binder 本地服务 |
| `com.miui.circulate.world.headset.HeadsetContentManager` | 62 / 15 | 卡片内容管理 |
| `com.miui.circulate.world.headset.data.HeadsetState` | 22 / 6 | `getName/setName`、`getBattery/setBattery(List)`、`getBluetoothMode/setBluetoothMode(int)`、`getLowDelayMode/setLowDelayMode(boolean)`；事件常量 `HeadsetBatteryChanged`、`HeadsetModeChanged`、`HeadsetPropertyUpdate`、`HeadsetLeftWorn` … |
| `com.miui.circulate.api.protocol.headset.HeadsetDeviceInfo` | 3 / 14 | Parcelable：`deviceId`、`name`、`power: List`、`mode`、`type`、`audioEffectState`、`headsetVolume` |
| `com.miui.circulate.api.protocol.headset.HeadsetServiceClient` | 72 / 30 | 协议客户端 |
| `com.miui.circulate.api.service.CirculateServiceInfo` | 19 / 14 | 服务信息 |
| `com.miui.circulate.world.headset.ui.HeadsetControlAncItemView` | — | 降噪控制 UI 项 |
| `com.miui.circulate.world.headset.ui.HeadsetInfoView` | — | 耳机信息 UI |

### 4.3 `com.xiaomi.bluetooth`（`BluetoothExtension.apk`）

125 个匹配类，绝大多数属于 `com.xiaomi.aivsbluetoothsdk.*`（小米自有 RCSP 私有协议，
面向自家音箱/耳机）。**无任何 Sony 支持**。

`hyperos-integration-notes.md` 从 OppoPods 抄来的
`com.android.bluetooth.ble.app.MiuiBluetoothNotification` 等类名，**在本 ROM 的探针结果中
全部报告 MISSING** —— 说明这些类是 **HyperOS 3 中已迁移或重构**的，不能照抄。

---

## 5. 落地计划

按依赖顺序：

### 阶段 A：让澎湃认识 XM5（阻塞项，必须先做）
1. 在 `com.android.bluetooth` 中 hook `HyperAdapterService.devicePropertyChangedCallback`，
   观察 XM5 连接时到达的 `propTypes` / `values`（尤其 `BT_PROPERTY_VENDOR_PRODUCT_INFO_HYPER`）。
2. 据此复用 `handleAirpodsInfo` 的识别路径，为 XM5 构造并注入一段能让澎湃接受的厂商产品信息。
3. 参照 OppoPods 的 `DeviceModelRegistry` / `ConfigManager.DEFAULT_FAKE_DEVICE_ID`，
   准备「伪装成某款受支持小米耳机」的方案作为兜底。

### 阶段 B：接入真实数据
4. 把协议层会话移入 `com.android.bluetooth` 进程（复用已连接的 ACL，避免第二个 SPP 客户端）。
5. 用 `bas.BatteryService.handleBatteryChanged(BluetoothDevice, int)` 把 SPP 电量发布进系统栈。
6. 在 `com.milink.service` 中校正 `HeadsetInfo#getPowers/getMode` 与
   `HeadsetState#setBattery/setName`，使设备中心显示真值。

### 阶段 C：控制回传
7. 拦截 `HeadsetControlAncItemView` / `HeadsetState#HeadsetModeChanged` 触发的降噪切换，
   转发到协议层的 `68 17 01 <on> <0=NC|1=AS> <focus> <level>`。

### 阶段 D：其余澎湃特性
8. 设置页集成（`com.android.settings`）、超级岛、通知卡片。

---

## 6. 已知约束（实测）

- **单一 SPP 客户端**：模块持有时官方 Sound Connect 无法连接；反之会话会被踢掉 →
  自动重连是必需能力（已实现）。
- **`staticScope=true`**：作用域由 APK 内的 `scope.list` 决定，改作用域需重新打包安装。
- **每次改代码都要重装 APK**：已验证重装后模块会自动重新加载，无需重启手机。
- **澎湃OS 禁止 `adb shell pm grant` / `input tap` / `input keyevent`**：
  真机自动化只能靠显式广播（`SonyControlReceiver`）与 `am start`。
- **`input keyevent` 不可用**意味着无法脚本化点亮屏幕，UI 相关验证需要人工配合。

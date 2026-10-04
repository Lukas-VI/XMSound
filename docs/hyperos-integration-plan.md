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

> **⚠️ 本节已被第 3.5 节修正。** 初次结论（澎湃对 XM5 一无所知）只对了一半：融合设备中心里确实没有它，
> 但设置页**已经会显示它的电量**。继续阅读前请先看 3.5。

实测打开「融合设备中心」，其中只有手机本体与小米手环 7 Pro，**没有耳机**。
同时 `MiLinkHeadsetProbe` 在 `com.milink.service` 里对
`HeadsetInfo#getPowers/getMode/getName` 与 `HeadsetState#getBattery/getName`
装了 12 个钩子（全部安装成功），**这些 getter 一次都没有被调用**。

原因与之前的 APK 字符串扫描一致：`com.xiaomi.bluetooth`(BluetoothExtension) 的 DEX 中
**不存在任何 Sony 相关字符串**，澎湃的耳机 UI 只对受支持的自有/合作型号生效。

---

## 3.5 修正：电量其实已经有了，缺的是「细分 + 降噪」

打开**蓝牙设置页**截图可见：

```
WF-1000XM5 [LDAC]
已连接 | 电量为 88% | 使用中
```

88% 正好等于协议层读到的**右耳**电量（同时 SPP 读到 L=93 R=88 case=30）。
`dumpsys bluetooth_manager` 显示该设备 `isActiveHfpDevice=true`，且特性开关
`enable_battery_level_update_only_through_hf_indicator` 为关闭状态 ——
**澎湃是通过 HFP 电量指示（AT+IPHONEACCEV 一类）拿到这个单一数值的**，与我们的模块无关。

因此本项目的实际价值定位应为：

| 需求 | 现状 | 本项目要做的 |
| --- | --- | --- |
| 蓝牙设置页显示一个电量数字 | ✅ 系统已支持（HFP） | 无需处理 |
| 左 / 右 / 耳机盒**分别**显示 | ❌ | 通过 Binder 注入 SPP 数据 |
| 系统内**降噪控制**（关闭/降噪/环境声） | ❌ | 通过 Binder 注入 + 拦截设置 |
| 融合设备中心耳机卡片 | ❌ | 需要机型识别（阶段 A） |
| 超级岛 / 通知卡片 | ❌ | 阶段 D |

### 3.6 已定位到可用的 Binder 注入点

与 `HeadsetInfo`（从未被调用）不同，**设置页确实在为 XM5 查询小米耳机 Binder**：

```
onBind returned = com.android.bluetooth.ble.app.headset.v
       descriptor = com.android.bluetooth.ble.app.IMiuiHeadsetService
onTransact 声明于 com.android.bluetooth.ble.app.r1
```

进程为 `com.xiaomi.bluetooth`（独立进程），实现类
`com.android.bluetooth.ble.app.headset.BluetoothHeadsetService`（208 方法，**已被 R8 混淆**，
方法名形如 `A`/`A0`/`B1`/`C2`，**按方法名 hook 不可行**）。

抓到的 AIDL 事务码（请求 + 应答）：

| 事务码 | 请求参数 | 应答 | 推断 |
| --- | --- | --- | --- |
| 16 | — | `00 00 00 00`（int 0） | 全局查询（能力/版本） |
| 1 | 设备地址 | 12 字节全 0 | 按地址查询设备信息 |
| 14 | `1.4_1.83` + 地址 | 字符串 `"false"` | 能力/开关查询 |
| 14 | `SettingsOriginal` + 地址 | `02 00 00 00 2c 00 20 00` | 设置来源相关查询 |
| 19 | 设备地址 | — | 按地址查询 |

**对 XM5 的查询返回的是空/`false`**，这就是要伪造的地方。
原始抓包见 `captures/binder-protocol-imiuheadsetservice.txt`。

> 结论：集成应走 **Binder 层（`onTransact`）**，而不是方法名 hook。
> `Service.onBind` 与 `Binder.onTransact` 是框架名，跨版本稳定；
> 实现类名 `headset.v` / Stub `r1` 是混淆产物，必须运行时动态获取（探针已实现）。

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

## 5. 落地计划（据第 3.5/3.6 节修正）

### 阶段 B′：Binder 注入（当前最高价值，不再被机型识别阻塞）
1. 在 `com.xiaomi.bluetooth` 中，用 `onBind` 拿到 `IMiuiHeadsetService` 的 Binder，
   对 `onTransact` 装钩子（探针已实现该定位逻辑）。
2. 补齐事务码语义：继续抓包覆盖「获取设备详情 / 获取电量 / 获取与设置降噪 /
   注册与注销回调」等剩余 opcode（需要人工在设置页与设备中心多点几下）。
3. 对 XM5 的地址，把事务码 1 / 14 / 19 的应答改写为真实数据
   （左/右/盒电量、当前降噪模式），使设置页显示细分电量并出现降噪入口。
4. 拦截「设置降噪」的事务码，转发到协议层的
   `68 17 01 <on> <0=NC|1=AS> <focus> <level>`，并把回执拟造成成功。

### 阶段 A：融合设备中心卡片（仍需要机型识别）
5. hook `HyperAdapterService.devicePropertyChangedCallback`，观察 XM5 连接时的
   `propTypes` / `values`（已知 `0xF9` 仅索尼设备出现：XM5=`0x03`、PCM-A10=`0x01`；
   `0xFD` = `BT_PROPERTY_VENDOR_PRODUCT_INFO_HYPER` 尚未出现）。
6. 复用 `handleAirpodsInfo` 的识别路径，或参照 OppoPods 的
   `ConfigManager.DEFAULT_FAKE_DEVICE_ID` 做机型伪装，让耳机出现在设备中心。

### 阶段 C/D
7. 超级岛（`MiuiHeadsetIslandParam`）、通知卡片、设置页深度集成。

> **注意**：阶段 B′ 不再依赖阶段 A。设置页已经在为 XM5 调用 Binder，
> 只要应答被改写，细分电量与降噪入口就能先落地。

---

## 6. 已知约束（实测）

- **单一 SPP 客户端**：模块持有时官方 Sound Connect 无法连接；反之会话会被踢掉 →
  自动重连是必需能力（已实现）。
- **`staticScope=true`**：作用域由 APK 内的 `scope.list` 决定，改作用域需重新打包安装。
- **每次改代码都要重装 APK**：已验证重装后模块会自动重新加载，无需重启手机。
- **澎湃OS 禁止 `adb shell pm grant` / `input tap` / `input keyevent`**：
  真机自动化只能靠显式广播（`SonyControlReceiver`）与 `am start`。
- **`input keyevent` 不可用**意味着无法脚本化点亮屏幕，UI 相关验证需要人工配合。

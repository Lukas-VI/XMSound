# XMSound

<div align="center">

**为小米 HyperOS 设备提供系统级索尼 WF-1000XM5 耳机控制**

![Platform](https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android)
![LSPosed](https://img.shields.io/badge/Framework-LSPosed-blueviolet?style=flat-square)
![HyperOS](https://img.shields.io/badge/ROM-澎湃OS3-orange?style=flat-square)

</div>

---

## 这是什么

XMSound 是一个 Xposed / LSPosed 模块，参照 [OppoPods](https://github.com/1812z/OppoPods) 的思路，
让澎湃OS（HyperOS）把**索尼 WF-1000XM5** 当作受支持的第一方耳机来对待：在系统蓝牙设置、
融合设备中心与音量面板里直接显示电量并切换降噪模式，而不必打开官方 Sound Connect。

与 OppoPods 的主要区别在于协议层：OPPO 耳机与索尼耳机使用完全不同的私有 SPP 协议，
因此本项目在 `pods/sony/` 下独立实现了索尼协议栈。

## 目标设备

| 项目 | 值 |
| --- | --- |
| 耳机 | Sony WF-1000XM5 |
| 手机 | Xiaomi 13 Ultra (`ishtar`, 2304FPN6DC) |
| 系统 | HyperOS OS3.0.305.0.WMACNXM / Android 16 (API 36) |
| 框架 | LSPosed (libxposed API 101) |
| 官方 App | Sony Sound Connect (`com.sony.songpal.mdr`) |

## 当前进度

- [x] 工作区与 Git 仓库初始化
- [x] 真机环境勘察（型号 / 系统 / 框架 / 耳机配对状态）
- [x] 参考实现调研（OppoPods、HyperEars、HyperVolumeANC、Gadgetbridge、SonyHeadphonesClient）
- [x] **索尼 SPP 协议层：连接、握手、电量、降噪读写、固件 —— 已真机验证**
- [x] 模块 UI（电量 / 降噪 / 协议日志）
- [x] 自动重连（SPP 通道会被官方 App 抢占）
- [ ] HyperOS Hook 集成层（蓝牙设置 / 融合设备中心 / 音量面板）
- [ ] 超级岛 / 通知卡片

### 真机验证状态

在 Xiaomi 13 Ultra / HyperOS OS3.0.305 / WF-1000XM5（固件 6.1.0）上实测通过：

```
state v2 fw=6.1.0 L=100 R=100 case=38 noise=AMBIENT_SOUND lvl=16 focus=false
```

| 能力 | 状态 |
| --- | --- |
| RFCOMM(V2 UUID) 连接 + 握手 | ✅ |
| 左 / 右 / 耳机盒电量 | ✅ |
| 降噪模式读取（关闭 / 降噪 / 环境声） | ✅ |
| 降噪模式写入 | ✅ 已回读确认 |
| 环境声等级 0–20 读写 | ✅ |
| 固件版本 | ✅ 6.1.0 |
| 连接状态 | ✅ |
| 关注语音（Focus on Voice） | ⚠️ 协议字段已实现，待人工听感确认 |
| 风噪抑制 | ➖ 本机型不提供 |
| 自适应降噪 | ➖ 协议层不存在（官方 App 为应用层自动化） |

> 实测中发现若干与公开参考实现**不一致**的地方（环境声读取子类型、应答布局、ACK 序号语义等），
> 详见 [docs/hardware-findings.md](docs/hardware-findings.md)。

## 无界面调试

官方 App 与模块会争抢唯一的 SPP 通道，且澎湃OS 禁止 `adb shell pm grant` / `input tap`，
因此控制路径通过显式广播暴露，便于自动化与后续 Hook 层调用：

```bash
RC=moe.yanhe.xmsound/.pods.sony.SonyControlReceiver
adb shell am broadcast -n $RC -a moe.yanhe.xmsound.action.SET_NOISE --es mode nc
adb shell am broadcast -n $RC -a moe.yanhe.xmsound.action.SET_AMBIENT_LEVEL --ei level 16
adb logcat -s XMSound-Sony     # 每一帧收发与解析后的状态
```

> 注意：必须用 `-n` 指定组件。Android 8+ 不把隐式广播送达 manifest receiver。

## 仓库结构

```
app/                          Android 模块（LSPosed 模块 + 独立控制 App）
  src/main/assets/xposed_init  Xposed 入口类声明
  src/main/java/moe/yanhe/xmsound/
    hook/                       Xposed 钩子层
    pods/sony/                  索尼协议栈（RFCOMM + 帧 + 消息）
    ui/                         Compose 界面
docs/                         调研与协议文档
.reference/                   上游参考项目（已 gitignore，不随仓库分发）
```

## 构建

需要 JDK 22、Android SDK Platform 36（或 37）与 Build-Tools。

```bash
./gradlew assembleDebug
```

产物位于 `app/build/outputs/apk/debug/`。

> 国内网络下 Gradle 需要代理才能拉取发行包与依赖，
> 可在 `~/.gradle/gradle.properties` 中配置 `systemProp.https.proxyHost/Port`。

## 使用

1. 安装 APK
2. 在 LSPosed 中启用模块并勾选作用域（`com.android.bluetooth`、`com.xiaomi.bluetooth`、
   `com.milink.service`、`com.android.settings`）
3. 重启作用域
4. 通过蓝牙连接 WF-1000XM5

## 致谢

- [OppoPods](https://github.com/1812z/OppoPods) by 1812z — 本项目的直接参照
- [HyperPods](https://github.com/Art-Chen/HyperPods) by Art_Chen — OppoPods 的上游
- [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) — 索尼协议参考实现
- [SonyHeadphonesClient](https://github.com/Plutoberth/SonyHeadphonesClient) — 索尼协议参考实现
- [Miuix](https://github.com/YuKongA/miuix) — 澎湃风格 Compose 组件

## 许可证

GPL-3.0

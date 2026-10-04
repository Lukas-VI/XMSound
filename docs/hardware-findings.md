# 真机验证结果 — Sony WF-1000XM5 × 澎湃OS

本文记录在**目标真机**上实测得到的协议事实，以及它们与公开参考实现的差异。
凡是与 `sony-wf1000xm5-protocol.md`（基于参考实现整理）冲突的地方，**以本文为准**。

## 测试环境

| 项目 | 值 |
| --- | --- |
| 手机 | Xiaomi 13 Ultra (`ishtar`, 2304FPN6DC) |
| 系统 | HyperOS OS3.0.305.0.WMACNXM / Android 16 (API 36) |
| 耳机 | Sony WF-1000XM5, 固件 **6.1.0**, MAC `AC:80:0A:10:00:DB` |
| 官方 App | Sony Sound Connect 13.2.1 (`com.sony.songpal.mdr`) |
| 模块 | XMSound 0.1.0（本仓库） |
| 抓取方式 | `adb logcat -s XMSound-Sony`（模块自身打印每一帧收发） |

---

## 1. 已确认可用的完整会话

以下是一次真实会话的原始日志（已删减无关行）：

```
TX connect AC:80:0A:10:00:DB (956c7b26-d49a-4ba8-b03f-b17d393cb6e2)
TX 3e 0c 00 00 00 00 02 00 00 0e 3c            ← INIT_REQUEST (seq 0)
RX type=0x01 seq=1                              ← ACK
RX type=0x0C seq=1 len=8 payload=0100030030180000   ← INIT_REPLY, 8 字节 ⇒ V2
TX 3e 01 00 00 00 00 00 01 3c                  ← ACK (seq = 1-1 = 0)
TX 3e 0c 01 00 00 00 02 22 09 3a 3c            ← 电量 DUAL
RX type=0x0C seq=0 len=8 payload=2309640064006464
TX 3e 0c 00 00 00 00 02 22 0a 3a 3c            ← 电量 CASE
RX type=0x0C seq=1 len=5 payload=230a26001e
TX 3e 0c 01 00 00 00 02 66 17 8c 3c            ← 环境声读取（注意是 0x17）
RX type=0x0C seq=1 len=7 payload=6717010101000d
```

解析后的状态：

```
state v2 fw=6.1.0 L=100 R=100 case=38 noise=AMBIENT_SOUND lvl=13 focus=false
```

**结论：连接、握手、电量、固件、降噪读取全部打通。**

---

## 2. 与参考实现的关键差异

### 2.1 差异一：环境声读取必须用 `0x17`，不是 `0x15`

参考实现中 Gadgetbridge 与 HyperEars 对 WF-1000XM5 发送 `66 15`，SonyBridge 发送 `66 17`。

> **实测：本机固件 6.1.0 对 `66 15` 只回 ACK、不回数据帧，对 `66 17` 立即返回 `67 17 ...`。**

因此 `SonyProtocol.AMBIENT_SUBTYPE_LADDER` 依次尝试 `0x15 → 0x17 → 0x02`，
并在收到应答后把可用子类型记入 `ambientSubtype`，后续优先使用。

### 2.2 差异二：`0x17` 的应答是**短布局**（7 字节），不是文献所述的风噪 8 字节布局

实测应答：`67 17 01 01 01 00 0d`（7 字节）

| 偏移 | 值 | 含义 |
| --- | --- | --- |
| 0 | `0x67` | RET |
| 1 | `0x17` | 子类型 |
| 2 | `0x01` | 常量 |
| 3 | `0x01` | 开关（`0x00` ⇒ 关闭） |
| 4 | `0x01` | `0x00` = 降噪 / `0x01` = 环境声 |
| 5 | `0x00` | 关注语音 |
| 6 | `0x0d` | 环境声等级 0–20 |

参考实现把 `0x17` 一律当作带风噪选择字节的 8 字节布局，因此**会直接丢弃本机型的应答**。
`SonyParser.ambient` 现在同时支持两种布局，判定依据是**长度 + 偏移 5 是否为风噪选择值（`0x03`/`0x05`）**，
而不是子类型。

### 2.3 差异三：写入帧同样使用 7 字节短布局

实测写入帧（切到降噪）：`68 17 01 01 00 00 0b`

```
68 <子类型> 01 <开关> <0=降噪|1=环境声> <关注语音> <等级>
```

`windCapable` 由耳机**自己上报的应答长度**决定，而不是由子类型推断。若某机型确实上报 8 字节布局，
`ambientSetRequest` 会自动插入风噪选择字节。

### 2.4 差异四：电量应答长度与参考文献不同

| 查询 | 实测应答 | 长度 | 解析 |
| --- | --- | --- | --- |
| `22 09` DUAL | `23 09 64 00 64 00 64 64` | 8 | 左 100%/不充电，右 100%/不充电 |
| `22 01` DUAL2 | `23 01 64 00 64 00` | 6 | 左 100%，右 100% |
| `22 0a` CASE | `23 0a 26 00 1e` | 5 | 盒 38% |
| `22 00` SINGLE | **无应答** | — | 本机型不支持 |

- DUAL 的**尾部 2 字节（`64 64`）含义未知**。它不能按「等级 + 充电标志」解释（`0x64` 不是合法充电值）。
  解析器为此增加了**严格校验**：充电字节必须是 `0x00`/`0x01`，否则该字段判为 null，
  以免把未知数据当成可信电量显示给用户。
- CASE 应答第 4 字节（`0x1e`）同样未知。
- DUAL2 的 6 字节形式与文献完全一致，可作为布局正确性的交叉验证。

### 2.5 差异五：**ACK 必须反转序号**（已用对照实验证伪另一种写法）

我们在真机上做过一次对照实验，把主机 ACK 的序号从「反转」改为「回显收到的序号」：

> 结果：耳机**卡在重复重传最后一帧电量应答**，对其余所有请求（包括环境声、固件）都不再应答，
> 每次请求都超时。改回反转后一切恢复正常。

因此 `SonyHeadsetSession.ACK_ECHOES_SEQUENCE = false`（反转）是**经验证的必需行为**，
与 Gadgetbridge / SonyBridge / HyperEars 的记载一致，代码中保留该常量并注明原因，便于日后复核。

### 2.6 握手应答与文献样本不同（但不影响判定）

| 来源 | INIT_REPLY |
| --- | --- |
| 文献样本（固件 2.0.1） | `01 00 03 00 10 04 00 00` |
| **本机（固件 6.1.0）** | `01 00 03 00 30 18 00 00` |

`01 00 03 00` 前缀一致，尾部随固件变化。协议版本仍然只按**应答长度**判定（4 = V1，8 = V2），
不要按内容匹配，否则固件升级即失效。

---

## 3. 系统层面的实测事实

### 3.1 SPP 是单客户端，且会被抢走

- 模块持有通道时，打开 Sound Connect 会停在「正在连接…」，**无法连接**。
- 反向亦然：会话运行期间若其他组件（Sound Connect、澎湃「融合设备中心」）取得通道，**我们的 socket 会断开**。

因此**自动重连是必需能力而非容错**：`SonyHeadsetController` 在掉线后按
2s → 4s → 8s → 15s → 30s 退避重连，用户主动断开则不再重连。

### 3.2 中断唤醒会伪装成掉线（已修复的实现缺陷）

早期版本用 `worker.interrupt()` 唤醒工作线程以提交新指令。若线程恰好停在 SET 后的
`Thread.sleep`，`InterruptedException` 会冒泡到工作循环并被当作连接错误，导致**每次下发指令都掉线**。
现改为 500ms 短轮询，彻底不使用中断唤醒。

### 3.3 澎湃OS 的调试限制（影响后续自动化）

| 操作 | 结果 |
| --- | --- |
| `adb install -g` | 被拒（需 `INSTALL_GRANT_RUNTIME_PERMISSIONS`） |
| `adb shell pm grant` | 被拒（MIUI 加固） |
| `adb shell input tap` | 被拒（需 `INJECT_EVENTS`） |
| 隐式广播送达 manifest receiver | **不送达**（Android 8+ 隐式广播限制） |
| 显式广播 `-n <组件>` | 正常送达 |

结论：真机自动化必须使用**显式广播**，运行时权限只能由用户在界面上确认。
这两点已体现在 `SonyControlReceiver` 与 UI 的「授予权限」入口上。

### 3.4 时序

| 阶段 | 实测耗时 |
| --- | --- |
| `createRfcommSocketToServiceRecord` + connect | ≈ 1.6–2.2 s |
| INIT 请求 → INIT_REPLY | ≈ 0.4–1.4 s |
| 请求 → 应答（电量/固件/环境声） | ≈ 0.1–1.1 s |
| 请求 → ACK | ≈ 0.2–1.1 s |

`REQUEST_TIMEOUT_MS = 2500` 对以上分布有充足余量。

---

## 4. 验证方式的复盘

界面点击无法用 adb 自动化，因此模块内置了 `SonyControlReceiver`，通过显式广播即可驱动全部控制路径：

```bash
RC=moe.yanhe.xmsound/.pods.sony.SonyControlReceiver
adb shell am broadcast -n $RC -a moe.yanhe.xmsound.action.SET_NOISE --es mode nc
adb shell am broadcast -n $RC -a moe.yanhe.xmsound.action.SET_NOISE --es mode ambient
adb shell am broadcast -n $RC -a moe.yanhe.xmsound.action.SET_NOISE --es mode off
adb shell am broadcast -n $RC -a moe.yanhe.xmsound.action.SET_AMBIENT_LEVEL --ei level 16
adb logcat -s XMSound-Sony
```

这条通道同时也是后续 HyperOS Hook 层（运行在其他进程）驱动会话所必需的。

---

## 5. 仍未解的问题

1. **DUAL 应答尾部 2 字节、CASE 应答末字节含义未知**。需要更多状态样本（例如把耳机取出/放回、
   处于充电中）才能确认；目前按「未知」处理，不作展示。
2. **未取得官方 App 的界面数值作为对照**：Sound Connect 在模块持有通道时无法连接，
   而在本机 HyperOS 上无法用 adb 点击授权/连接按钮，因此缺少独立真值。
   建议由人工在手机上对照一次（关闭本模块 → 打开 Sound Connect → 读电量），以确认 38% 等数值。
3. **「自适应降噪」不存在于协议层**（与文献一致）：协议只有 关闭 / 降噪 / 环境声 三态。
   官方 App 的自适应属于应用层自动化，不应伪造协议枚举值。
4. **双设备连接（多点）无法从协议区分当前音源设备**，需从 Android `BluetoothA2dp` 侧获取。

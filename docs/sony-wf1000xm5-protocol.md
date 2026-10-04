# Sony WF-1000XM5 — SPP/RFCOMM control protocol specification

> **⚠️ 真机实测修正见 [`hardware-findings.md`](hardware-findings.md)。**
> 本文基于公开参考实现整理，其中若干结论与**目标机型（WF-1000XM5 / 固件 6.1.0）的实测结果冲突**：
> 环境声读取需用 `66 17` 而非 `66 15`；`0x17` 的应答与写入均为 **7 字节短布局**而非本文所述的 8 字节风噪布局；
> 电量应答长度也与本文不同。**发生冲突时以 `hardware-findings.md` 为准。**

**Audience:** engineer re-implementing the Sony Headphones Connect private RFCOMM protocol in an
Android LSPosed module (Kotlin).

**Scope:** transport discovery, frame format, the message catalog needed for *battery (L/R/case)*,
*ANC / Ambient Sound Control read+write*, *device info*, and the minimal init sequence.

All claims below are cited to a local reference file + line numbers. Where the references disagree
or where I extrapolate, the statement is marked **INFERRED** or **DISAGREEMENT**.

## 0. Reference inventory (exact revisions used)

| Ref | Path | Revision |
|---|---|---|
| Gadgetbridge | `D:\Java\XMSound\.reference\Gadgetbridge` | `016ecd57a6c36766d60652e64fd3abbc490df006` (2026-10-03, codeberg.org/Freeyourgadget/Gadgetbridge) |
| SonyBridge (C++/Qt, has v2) | `D:\Java\XMSound\.reference\SonyBridge` | `8c81a27` (github.com/AmitRajput-Dev/SonyBridge) |
| SonyHeadphonesClient (C++, v1 only) | `D:\Java\XMSound\.reference\SonyHeadphonesClient` | `5620e8e` (github.com/Plutoberth/SonyHeadphonesClient) |
| HyperEars (Kotlin/LSPosed module — closest analogue to what you are writing) | `D:\Java\XMSound\.reference\HyperEars` | `8bbd653` (github.com/silverpoetry/HyperEars) |

Short forms used below: `GB/...` = Gadgetbridge `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/...`,
`SB/Client/...` = SonyBridge, `HC/...` = HyperEars.

---

## 1. Transport

### 1.1 Service UUIDs (two protocol generations)

| Generation | RFCOMM/SPP service UUID | Evidence |
|---|---|---|
| **V1** | `96CC203E-5068-46AD-B32D-E316F5E069BA` | GB `service/devices/sony/headphones/SonyHeadphonesSupport.java:44`; SB `Client/Constants.h:11`; SonyHeadphonesClient `Client/IBluetoothConnector.h:6`; HC `protocol/src/main/java/dev/hyperears/protocol/sony/SonyHeadphonesWireCodec.kt:10` |
| **V2** | `956C7B26-D49A-4BA8-B03F-B17D393CB6E2` | GB `SonyHeadphonesSupport.java:45`; SB `Client/Constants.h:18`; HC `SonyHeadphonesWireCodec.kt:11` |

The **WF-1000XM5 speaks V2**. Evidence:
* Gadgetbridge's init-reply comment literally lists `WF-1000XM5 2.0.1: 01:00:03:00:10:04:00:00` as the
  8-byte (⇒ v2) init reply — `GB/.../SonyHeadphonesProtocol.java:142`.
* SonyBridge README lists WF-1000XM5 under "v2 earbuds" (`SB/README.md:124`).
* HyperEars registers `wf-1000xm5` with the v2 dialect (`HC/integration/.../SonyEarbudAdapter.kt:137`).

### 1.2 Channel discovery

All implementations resolve the RFCOMM **channel number via SDP** from the service UUID — the channel
number is *not* fixed and must not be hard-coded.

* Android: `BluetoothDevice.createRfcommSocketToServiceRecord(uuid)` — GB
  `service/btbr/BtBRQueue.java:327-342` (that branch is taken when `mRfcommChannel < 0`, i.e. "no explicit
  RFCOMM channel", `service/btbr/AbstractBTBRDeviceSupport.java:46-51`), HC
  `system-module/.../EarbudChannel.kt:148-161`.
* Gadgetbridge picks the UUID from the **cached SDP UUID list** of the bonded device: if the V2 UUID is
  present it uses V2, else V1, else it falls back to a per-coordinator preference —
  `SonyHeadphonesSupport.java:175-202` (`getSupportedService()`), preference hook
  `devices/sony/headphones/SonyHeadphonesCoordinator.kt:158-160`.

**Gotcha (INFERRED):** `SonyWF1000XM5Coordinator` does **not** override `preferServiceV2()`, so it inherits
`false` (`SonyHeadphonesCoordinator.kt:158-160`). If SDP lookup yields neither UUID, Gadgetbridge would
fall back to the **V1** UUID on an XM5. Only LinkBuds S / LinkBuds / ULT WEAR override it to `true`
(grep `preferServiceV2` under `GB/devices/sony/headphones/coordinators/`). **For the XM5 module, prefer V2
first (or use the V2 UUID unconditionally) and treat V1 as a fallback.**

* SonyBridge tries V1 first and falls back to V2 (`SB/README.md:174-177`); HyperEars orders transports per
  model (`HC/.../SonyEarbudAdapter.kt:669-681`).
* Recommended order for XM5: **V2 UUID → V1 UUID**.

### 1.3 Connection timing / link behaviour

* Android needs a delay before connecting or `connect()` throws `IOException`: GB returns **500 ms**
  (`SonyHeadphonesSupport.java:86-90`).
* The headset **drops the control (SPP) link when idle** to save power; SonyBridge's user advice is to keep
  audio playing (`SB/README.md:102`). **INFERRED:** expect the RFCOMM socket to close on its own after a
  period of inactivity; re-establish rather than assuming a permanent session. No exact timeout value
  exists in any reference.
* Max frame buffer seen: 2048 bytes (GB `SonyHeadphonesSupport.java:51`; SB `Client/Constants.h:5`).
  HyperEars caps an unescaped frame body at 16 KiB (`HC/.../SonyHeadphonesWireCodec.kt:140`).

---

## 2. Framing

### 2.1 Envelope

Canonical statement of the format (Gadgetbridge `protocol/Message.java:33-46`):

```
0x3E | ESCAPE( <TYPE> <SEQ> <LEN32_BE> <PAYLOAD...> <CHECKSUM> ) | 0x3C
```

| Offset (in *unescaped* body) | Size | Field |
|---|---|---|
| 0 | 1 | **Message type** — `0x01` ACK, `0x0C` COMMAND_1, `0x0E` COMMAND_2 (`protocol/MessageType.java:20-24`) |
| 1 | 1 | **Sequence number** — 1-bit toggle (values 0/1 in practice) |
| 2..5 | 4 | **Payload length**, unsigned 32-bit **big-endian**, number of bytes of payload only |
| 6..6+len-1 | len | **Payload**; byte 0 is the *payload type / command* (`Request.java:47-53`) |
| 6+len | 1 | **Checksum** — 8-bit additive sum, see §2.3 |

Frame boundaries: `0x3E` start, `0x3C` end (`Message.java:48-49`). Body bytes equal to `0x3E`, `0x3D` or
`0x3C` are escaped; therefore the **first raw `0x3C` after a `0x3E` always terminates the frame** and may be
used for stream resynchronisation (GB `SonyHeadphonesSupport.java:139-151` scans exactly that way; HyperEars
does the same in `SonyHeadphonesWireCodec.kt:75-78`).

Minimum frame: 9 bytes (`3E <type> <seq> 00 00 00 00 <checksum> 3C`) = a bare ACK.
SonyBridge enforces the same minimum ("1 (type) + 1 (seq) + 4 (size) + 1 (checksum) = 7 bytes minimum",
`SB/Client/CommandSerializer.cpp:141-145`).

### 2.2 Escaping

| Constant | Value | Evidence |
|---|---|---|
| `MESSAGE_ESCAPE` | `0x3D` | `Message.java:50` |
| `MESSAGE_ESCAPE_MASK` | `0xEF` (`0b11101111`) | `Message.java:51` |

Encode: for each body byte `b` in `{0x3E, 0x3C, 0x3D}` emit `0x3D, (b & 0xEF)`; else emit `b`
(`Message.java:156-174`; SB `Client/CommandSerializer.cpp:11-42`; HC `SonyHeadphonesWireCodec.kt:43-55`).

Decode: `0x3D x` → `x | 0x10` (`Message.java:176-192` uses `bytes[i] | ~MESSAGE_ESCAPE_MASK`;
HC uses the explicit constant `ESCAPE_RESTORE_BIT = 0x10`, `SonyHeadphonesWireCodec.kt:137,70`).

| raw | escaped |
|---|---|
| `0x3E` | `3D 2E` |
| `0x3D` | `3D 2D` |
| `0x3C` | `3D 2C` |

Worked example (verified against HyperEars' unit test
`HC/protocol/src/test/.../SonyHeadphonesWireCodecTest.kt:22-28` and the official v1 frame in
`HC/.../SonyHeadphonesWireCodecTest.kt:10-19`):

```
payload 3e 3d 3c  ->  body ... 3d 2e 3d 2d 3d 2c ... checksum c6
full frame: 3e 0c 00 00 00 00 03 3d 2e 3d 2d 3d 2c c6 3c
```

Note the checksum is computed **before** escaping and covers the **unescaped** bytes
(`Message.java:142-146`, `SB/Client/CommandSerializer.cpp:117-119`).

### 2.3 Checksum — **it is not a CRC**

There is **no CRC and no polynomial** anywhere in this protocol. The last body byte is a plain mod-256
additive sum ("8-bit sum / LRC") over every body byte *excluding* itself:

```kotlin
fun checksum(body: ByteArray, endExclusive: Int): Byte {
    var sum = 0
    for (i in 0 until endExclusive) sum = (sum + (body[i].toInt() and 0xFF)) and 0xFF
    return sum.toByte()
}
```

Evidence: `Message.java:194-200` (`calcChecksum`, `chk += message[i] & 255`), `Message.java:108-112`
(verification: `messageBytes[len-2]` vs sum of `[1, len-2)`), `SB/Client/CommandSerializer.cpp:87-100`
and `:159`, HC `SonyHeadphonesWireCodec.kt:112-118`.

**Worked example (byte by byte)** — the init request every reference agrees on:

```
body     = 0c 00 00 00 00 02 00 00
sum      = 0x0C + 0x00 + 0x00 + 0x00 + 0x00 + 0x02 + 0x00 + 0x00 = 0x0E
frame    = 3e 0c 00 00 00 00 02 00 00 0e 3c
```
Byte-for-byte identical to `HC/.../SonyHeadphonesWireCodecTest.kt:12`.

Second worked example with escaping and payload:

```
payload  = 66 15                      (AMBIENT_SOUND_CONTROL_GET, subtype 0x15)
body     = 0c 00 00 00 00 02 66 15
sum      = 0x0C+0x02+0x66+0x15 = 0x89
frame    = 3e 0c 00 00 00 00 02 66 15 89 3c
```

Third example — real Gadgetbridge test vector with seq=1
(`GB/app/src/test/.../v2/SonyProtocolImplV2Test.java:118`), independently reproduced:

```
3e 0c 01 00 00 00 02 12 02 23 3c      ; sum = 0c+01+02+12+02 = 0x23
```

### 2.4 Sequence numbers

* The sequence number is effectively a **1-bit toggle** (0/1). Gadgetbridge starts at 0 and takes the next
  value from the device's ACK (`SonyHeadphonesProtocol.java:75`, `:100-107`).
* **ACK carries the *inverted* sequence number of the frame it acknowledges** — i.e. the number the peer is
  expected to use next:
  * host ACK for a device frame with seq `D`: `seq = 1 - D` (GB `SonyHeadphonesProtocol.java:426-428`;
    SB `Client/BluetoothWrapper.cpp:153-159`; HC `HC/integration/.../SonyEarbudAdapter.kt:309-312`).
  * device ACK for a host frame with seq `S`: carries `1 - S`; the host then uses that value for the next
    request (`SB/Client/BluetoothWrapper.cpp:78-82,96` — `this->_seqNumber = msg.seqNumber`).
* Gadgetbridge *adopts* the ACK's sequence value and ignores an ACK whose sequence equals the current one
  (`SonyHeadphonesProtocol.java:100-107`); HyperEars does the same
  (`HC/.../SonyEarbudAdapter.kt:322-327`: `if (ackSequence == sequence) return`).

### 2.5 ACK / retry rules

* **Every** received `COMMAND_1`/`COMMAND_2` frame must be ACKed immediately, before or interleaved with
  payload handling: GB emits `encodeAck(message.getSequenceNumber())` then handles the payload
  (`SonyHeadphonesProtocol.java:179-189`); HyperEars queues the ACK as an "immediate command"
  (`HC/.../SonyEarbudAdapter.kt:302-317`).
* The ACK frame has **type `0x01`, length 0, no payload type byte** (`SonyHeadphonesProtocol.java:426-428`).
  Example ACK for a device frame with seq 0 → host ACK seq 1: `3e 01 01 00 00 00 00 02 3c`.
* The **device retransmits its own data frames until the host ACKs**, so an un-ACKed frame will be repeated:
  "The device retransmits any DATA_MDR frame it sends until the host ACKs it."
  (`SB/Client/BluetoothWrapper.cpp:153-155`). **INFERRED from that comment:** duplicate frames must be
  idempotent in your state machine.
* The **host must not have two requests in flight**: Gadgetbridge keeps `pendingAcks` and only pops the next
  request from the queue when an ACK arrives (`SonyHeadphonesProtocol.java:79-80,366-368,456-483`);
  HyperEars has a single `awaitingAck` flag and a FIFO (`HC/.../SonyEarbudAdapter.kt:271-277,417-431`).
  HyperEars' doc states it explicitly: "Sony 通道一次只允许一个等待 ACK 的请求"
  (`HC/docs/sony-headphones-protocol.md:31-33`).
* **Retries:** none of the references retransmit arbitrary commands. Gadgetbridge only retries the *init*
  (up to 2 times, 1250 ms apart) and its comment admits the general case is unimplemented
  (`SonyHeadphonesSupport.java:53-79,204-208`). HyperEars enforces a **2500 ms handshake deadline**
  (`HC/system-module/.../EarbudDeviceSession.kt:538,945`). **Recommendation (INFERRED):** implement a
  per-request ACK timeout (~1–1.5 s) and one retransmit with the same sequence number, then fail the request.

### 2.6 Stream reassembly

RFCOMM is a byte stream: frames can be split across reads and several frames can arrive in one read.
* Gadgetbridge accumulates into a 2048-byte `ByteBuffer` and extracts `0x3E ... 0x3C` (note: it allocates
  the buffer with `LITTLE_ENDIAN` but only does byte-wise reads — `SonyHeadphonesSupport.java:51,129-173`).
* SonyBridge keeps a `_leftoverBytes` buffer so bytes after an `END_MARKER` are not dropped
  (`SB/Client/BluetoothWrapper.cpp:109-116,141-143`).
* HyperEars' `Decoder.offer()` is a proper incremental state machine and resynchronises on a new header
  (`HC/.../SonyHeadphonesWireCodec.kt:58-110`) — **the best model to copy for Kotlin.**

---

## 3. Message catalog

Payload byte 0 is the *payload type*; its meaning depends on the message type. V2 reuses most V1 payload
types verbatim (see the inheritance `SonyProtocolImplV2 extends SonyProtocolImplV1`,
`GB/.../v2/SonyProtocolImplV2.java:67`), with a V2-only set for battery/codec/auto-power-off/system control
(`GB/.../v2/PayloadTypeV2.java:22-54`).

### 3.1 Payload-type tables

**V1 payload types** (`GB/.../v1/PayloadTypeV1.java:22-99`, all `COMMAND_1` unless noted):

| GET | RET | SET | NOTIFY | Name |
|---|---|---|---|---|
| `0x00` | `0x01` | – | – | INIT_REQUEST / INIT_REPLY |
| `0x04` | `0x05` | – | – | FW_VERSION |
| `0x06` | `0x07` | – | – | INIT_2 |
| `0x10` | `0x11` | – | `0x13` | BATTERY_LEVEL |
| `0x18` | `0x19` | – | `0x1b` | AUDIO_CODEC |
| `0x22` | – | – | – | POWER_OFF |
| `0x46` | `0x47` | `0x48` | `0x49` | SOUND_POSITION_OR_MODE |
| `0x56` | `0x57` | `0x58` | `0x59` | EQUALIZER |
| `0x66` | `0x67` | `0x68` | `0x69` | **AMBIENT_SOUND_CONTROL** |
| `0x84` | `0x85` | – | – | NC_OPTIMIZER_START / STATUS |
| `0x86` | `0x87` | – | `0x89` | NC_OPTIMIZER_STATE |
| `0xa6` | `0xa7` | `0xa8` | `0xa9` | VOLUME |
| `0xd6` | `0xd7` | `0xd8` | `0xd9` | TOUCH_SENSOR (sub-selector, see §3.4) |
| `0xe6` | `0xe7` | `0xe8` | `0xe9` | AUDIO_UPSAMPLING / connection quality |
| `0xf6` | `0xf7` | `0xf8` | `0xf9` | AUTOMATIC_POWER_OFF_BUTTON_MODE (multiplexed) |
| `0xfa` | `0xfb` | `0xfc` | `0xfd` | SPEAK_TO_CHAT_CONFIG |
| `0xc4` | `0xc9` | – | – | JSON |
| `0x46` | `0x47` | `0x48` | `0x49` | VOICE_NOTIFICATIONS *(message type `COMMAND_2` = `0x0E`)* |

**V2-only payload types** (`GB/.../v2/PayloadTypeV2.java:22-54`, all `COMMAND_1`):

| GET | RET | SET | NOTIFY | Name |
|---|---|---|---|---|
| `0x12` | `0x13` | – | `0x15` | AUDIO_CODEC |
| `0x22` | `0x23` | `0x24` | `0x25` | **BATTERY_LEVEL** / POWER_SET |
| `0x26` | `0x27` | `0x28` | `0x29` | AUTOMATIC_POWER_OFF |
| `0x96` | `0x97` | `0x98` | `0x99` | SYSTEM_CONTROL (reboot `0x16`, connect-two-devices `0x06`, …) |
| `0xb6` | `0xb7` | `0xb8` | `0xb9` | SERVICE_LINK |
| – | – | `0xf8` | – | FACTORY_RESET_SET |
| `0xfa` | `0xfb` | `0xfc` | `0xfd` | AMBIENT_SOUND_CONTROL_BUTTON_MODE |

Cross-check by an independent implementation: SonyBridge's `V2Command` namespace lists
`INIT_REQUEST 0x00 / INIT_REPLY 0x01`, `BATTERY_GET 0x22 / BATTERY_RET 0x23 / BATTERY_NTFY 0x25`,
`EQ 0x56/0x57/0x58`, `DSEE 0xe6/0xe7/0xe8`, `FW 0x04/0x05`, `CODEC 0x12/0x13`, `APO 0x26/0x27/0x28`,
`BTNMODE 0xf6/0xf7/0xf8` (`SB/Client/Constants.h:32-58`).

Note: Gadgetbridge calls payload type `0x15` "AUDIO_CODEC_NOTIFY" for V2 while using `0x15` as the **ambient
sound subtype**, not a payload type — they are different fields (`PayloadTypeV2.java:24` vs
`SonyProtocolImplV2.java:80`). Do not confuse them.

### 3.2 Handshake / protocol negotiation / initialisation

There is **no separate "protocol version query"**. The version is negotiated implicitly by the init
request/reply pair:

| Step | Dir | Type | Payload | Meaning |
|---|---|---|---|---|
| 1 | host→dev | `0x0C` | `00 00` | INIT_REQUEST. Sent **once, immediately after the socket connects** |
| 2 | dev→host | `0x01` | *(empty)* | ACK of step 1 (may be interleaved with step 3) |
| 3 | dev→host | `0x0C` | `01 …` | INIT_REPLY; `payload[0] == 0x01` |
| 4 | host→dev | `0x01` | *(empty)* | ACK of step 3, seq = 1 − step-3 seq |

**Version detection is purely by init-reply payload length** (`SonyHeadphonesProtocol.java:120-172`):

* length **4** ⇒ **V1** (`WH-1000XM3: 01 00 40 10`, `WF-SP800N 1.0.1: 01 00 70 00`)
* length **8** ⇒ **V2**; documented samples at `SonyHeadphonesProtocol.java:138-144`:
  * `WF-1000XM4 1.1.5: 01 00 01 00 00 00 00 00`
  * `LinkBuds S 2.0.2: 01 00 03 00 00 07 00 00`
  * `WH-1000XM5 1.1.3: 01 00 03 00 00 00 00 00`
  * **`WF-1000XM5 2.0.1: 01 00 03 00 10 04 00 00`**
  * `LinkBuds   1.0.3: 01 00 02 00 10 00 00 00`
* any other length ⇒ reject (`SonyHeadphonesProtocol.java:145-148`; HyperEars emits
  `ProtocolEvent.HandshakeRejected`, `HC/.../SonyEarbudAdapter.kt:331-336`).

Concrete frames (checksums computed with the §2.3 algorithm):

```
host INIT_REQUEST, seq=0 :  3e 0c 00 00 00 00 02 00 00 0e 3c
device INIT_REPLY (XM5)  :  3e 0c 00 00 00 00 08 01 00 03 00 10 04 00 00 2c 3c
device INIT_REPLY (v1)   :  3e 0c 00 00 00 00 04 01 00 40 10 61 3c
host ACK (dev seq was 0) :  3e 01 01 00 00 00 00 02 3c
```

Init must be retried because some units ignore the first frame — Gadgetbridge's rule: if no init reply
after **1250 ms**, resend `00 00`, **max 2 retries**, then give up
(`SonyHeadphonesSupport.java:53-79,111-119,204-208`). HyperEars only retries on specific models
(WH-1000XM4) and only when the device talks before the handshake (`HC/docs/sony-headphones-protocol.md:27-29`,
`HC/.../SonyEarbudAdapter.kt:343-353`); its generic handshake deadline is **2500 ms**.

After INIT_REPLY, Gadgetbridge enqueues the whole capability probe list
(`SonyProtocolImplV1.java:767-818`) — see §4.

### 3.3 Battery / power

#### 3.3.1 Read request

`COMMAND_1`, payload `[0x22, <battery-type>]`. V2 type codes
(`GB/.../v2/SonyProtocolImplV2.java:1332-1362`; identical in `SB/Client/Headphones.cpp:88-120` and
`HC/.../SonyEarbudAdapter.kt:582-616`):

| Type code | Meaning | WF-1000XM5? |
|---|---|---|
| `0x00` | SINGLE (whole device) | no |
| `0x09` | **DUAL** — left+right in one reply | **yes** |
| `0x01` | DUAL2 — alternative dual layout (WF-C500/C510/C700N/C710N) | no |
| `0x0A` | **CASE** | **yes** |

`SonyWF1000XM5Coordinator` declares exactly `BatteryDual` + `BatteryCase`
(`GB/devices/sony/headphones/coordinators/SonyWF1000XM5Coordinator.java:51-64`), and its battery-index
layout is case=0, left=1, right=2 (`:39-46`).

Request frames:

```
BATTERY_LEVEL_REQUEST DUAL :  3e 0c 00 00 00 00 02 22 09 39 3c
BATTERY_LEVEL_REQUEST CASE :  3e 0c 01 00 00 00 02 22 0a 3b 3c
BATTERY_LEVEL_REQUEST SINGLE: 3e 0c 00 00 00 00 02 22 00 30 3c   (V2 single, e.g. WH-1000XM5)
```

#### 3.3.2 Reply / notify layout

Reply type `0x23`, notify type `0x25` (V2). Layout, all levels unsigned 0–100, charging `0x00`/`0x01`
(1 = charging):

```
SINGLE  23 00 <level> <chg>
DUAL    23 09 <leftLevel> <leftChg> <rightLevel> <rightChg>
DUAL2   23 01 <leftLevel> <leftChg> <rightLevel> <rightChg>     (INFERRED: same shape; only the type byte differs)
CASE    23 0a <level> <chg>
```

Evidence: `GB/.../v1/SonyProtocolImplV1.java:1079-1127` (single/case read `payload[2]`+`payload[3]`;
dual reads `payload[2]/[3]` = L and `payload[4]/[5]` = R; `payload[2] != 0` / `payload[4] != 0` gates the
bud being present), `SB/Client/Headphones.cpp:90-120`, `HC/.../SonyEarbudAdapter.kt:441-464` (rejects
out-of-range and treats 0 as "unavailable" for a bud, `:618-628`).

Concrete examples:

```
reply DUAL, L=90 not charging, R=91 charging : 3e 0c 00 00 00 00 06 23 09 5a 00 5b 01 f4 3c
reply CASE, case=100 charging                 : 3e 0c 01 00 00 00 04 23 0a 64 01 a3 3c
notify DUAL, L=90 charging, R=91 not          : 3e 0c 00 00 00 00 06 25 09 5a 01 5b 00 f6 3c
notify CASE, empty case (0, not charging)     : 3e 0c 00 00 00 00 04 25 0a 00 00 3f 3c
```

**Both buds are addressed in one message.** There is no per-bud request or address on the XM5 — do not
invent a left/right addressee.

`POWER_SET` (V2) = `COMMAND_1 0x24 03 01` (`SonyProtocolImplV2.java:686-695`); V1 power-off is
`0x22 00 01` (`SonyProtocolImplV1.java:655-665`).

#### 3.3.3 Multipoint — how are the two *connected phones* distinguished?

**No.** The protocol exposed by these references contains **no connected-device list and no per-phone
identity**. Battery and ANC state are device-global. The only multipoint-related message is the
*"Connect to 2 devices simultaneously"* toggle:

* Read: V2 maps `ConnectTwoDevices` onto the **TOUCH_SENSOR** read with sub-selector `0xd1`
  (`SonyProtocolImplV2.java:407-415` delegating to `getWideAreaTap()`, `:321-330`) — the actual read is
  `COMMAND_1` payload `d6 d1`, and the reply `d7 d1 …` yields the CTD/WAT boolean
  (`SonyProtocolImplV2.java:1227-1242`; comment at `:410-414`: "SYSTEM_CONTROL_RET always returns
  value=01 regardless of actual CTD state").
* Write/apply: `COMMAND_1 0x98 00 06 01` — a fixed commit frame sent after the related state write
  (`SonyProtocolImplV2.java:392-405`; frame `3e 0c 00 00 00 00 04 98 00 06 01 af 3c`).
* On V1 the same feature is `WEAR_AREA_TAP`/CTD with `0x00`/`0x01` at the tail
  (`SonyProtocolImplV1.java:397-407`).

**INFERRED:** if the module needs to know *which* phone is the active A2DP source, it must get that from
Android (`AudioManager`/`BluetoothA2dp`), not from the Sony protocol.

### 3.4 Noise cancelling / Ambient Sound Control

#### 3.4.1 Mode enum (the only modes that exist)

`AmbientSoundControl.Mode` (`GB/devices/sony/headphones/prefs/AmbientSoundControl.java:33-50`):
`OFF`, `NOISE_CANCELLING`, `WIND_NOISE_REDUCTION`, `AMBIENT_SOUND`.

HyperEars' abstract view is the same three-state model plus wind
(`HC/.../SonyEarbudAdapter.kt:28-41,661-665`). On the wire the mode is **two independent booleans**:
"enabled?" and "ambient-sound vs noise-cancelling".

State machine on the wire (V2, subtype `0x15`/`0x22` dialect):

| UI mode | byte `on` | byte `AS` |
|---|---|---|
| Off (ANC off / ambient off) | `0x00` | `0x00` (don't care) |
| Noise Cancelling | `0x01` | `0x00` |
| Ambient Sound | `0x01` | `0x01` |
| Wind Noise Reduction (models with it only) | `0x01` | `0x00` + wind byte `0x03`/`0x05` |

**"Adaptive" / "Auto Ambient" (Sony Sound Connect's Adaptive Sound Control / automatic action-based
switching) does NOT exist in any reference implementation.** The mode enum has no such value, no message
type carries it, and Gadgetbridge's settings UI exposes only Off / NC / Ambient / Wind
(`SonyHeadphonesDeviceSettings.kt:58-89`). **INFERRED:** it is an app-side automation in Sound Connect
(possibly implemented via repeated mode writes), not a protocol mode; if you must support it, model it in
your own app. Do not send an invented enum value.

#### 3.4.2 Read

`COMMAND_1` payload `[0x66, <subtype>]`. Subtype selection in Gadgetbridge V2
(`SonyProtocolImplV2.java:74-83`):

* `0x15` — plain dialect (NC + Ambient only). **This is what the XM5 uses**: the XM5 coordinator declares
  neither `WindNoiseReduction` nor `AmbientSoundControl2`
  (`SonyWF1000XM5Coordinator.java:51-64`; gate at `SonyProtocolImplV2.java:80`).
* `0x17` — extended dialect with wind (`WindNoiseReduction` or `AmbientSoundControl2` capability).
* `0x02` — V1 dialect (`SonyProtocolImplV1.java:84-93`).
* `0x19` — 2026-generation dialect (WF-1000XM6 only; HyperEars `MODERN`
  dialect, `HC/.../SonyEarbudAdapter.kt:34,543-557,659`).

```
GET ambient, plain V2 :  3e 0c 00 00 00 00 02 66 15 89 3c
GET ambient, extended :  3e 0c 00 00 00 00 02 66 17 8b 3c
GET ambient, v1       :  3e 0c 00 00 00 00 02 66 02 76 3c
```

**Reply** `0x67` / **notify** `0x69`, layout for the `0x15` dialect (7-byte payload)
(`SonyProtocolImplV2.java:779-848`; HyperEars `parseAmbientV2` `:492-520`):

| idx | field |
|---|---|
| 0 | `0x67`/`0x69` |
| 1 | subtype `0x15` |
| 2 | `0x01` constant |
| 3 | **on/off** (`0x00` ⇒ mode = OFF regardless of idx 4) |
| 4 | **`0x00` = Noise Cancelling, `0x01` = Ambient Sound** |
| 5 | **focusOnVoice** (0/1) — Gadgetbridge reads `payload[len-2]`, i.e. index 5 here |
| 6 | **ambient level 0…20** — Gadgetbridge reads `payload[len-1]`; range-checked 0..20 |

For the `0x17` (wind) dialect the payload is 8 bytes and idx 5 is the **wind/NC selector**
(`0x03`/`0x05` ⇒ wind; `0x02` ⇒ then idx 4 selects NC/AS), idx 6 = focus, idx 7 = level
(`SonyProtocolImplV2.java:785-848`). Subtype `0x22` = "no NC at all" ⇒ always Ambient Sound
(`SonyProtocolImplV2.java:791,810-811`).

Concrete replies:

```
AS, level 20, focus off : 3e 0c 00 00 00 00 07 67 15 01 01 01 00 14 a6 3c
NC, focus off           : 3e 0c 01 00 00 00 07 67 15 01 01 00 00 14 a6 3c
OFF                     : 3e 0c 01 00 00 00 07 67 15 01 00 00 00 14 a5 3c
notify AS level 20      : 3e 0c 00 00 00 00 07 69 15 01 01 01 00 14 a8 3c
```

V1 reply layout differs (8 bytes: `67 02 <on> <windCap> <mode> 01 <focus> <level>`, mode `0x00`=AS,
`0x01`=wind, `0x02`=NC when wind-capable; `0x00`=AS / `0x01`=NC otherwise) —
`SonyProtocolImplV1.java:820-877`, HyperEars `parseAmbientV1` `:472-490`. Include only for completeness;
the XM5 is V2.

#### 3.4.3 Write

`COMMAND_1` payload `[0x68, subtype, …]`.

**Gadgetbridge V2 layout** (`SonyProtocolImplV2.java:86-117`) — for the XM5 (no wind, subtype `0x15`),
7 bytes:

| idx | field | value |
|---|---|---|
| 0 | `0x68` | SET |
| 1 | subtype | `0x15` on XM5 (`0x17` when wind capable) |
| 2 | dragging flag | `0x01` = commit with confirmation tone, `0x00` = silent slider drag (`:91` comment "0x00 while dragging the slider?") |
| 3 | on/off | `0x00` if mode==OFF else `0x01` |
| 4 | NC/AS | `0x01` if mode==AMBIENT_SOUND else `0x00` |
| 5* | wind selector | **only when wind capable** (`0x03` wind, else `0x02`) |
| 5/6 | focusOnVoice | `0x01`/`0x00` |
| 6/7 | ambient level | `0x00`…`0x14` (0–20) |

```
SET Ambient Sound, level 20, focus off : 3e 0c 00 00 00 00 07 68 15 01 01 01 00 14 a7 3c
SET Noise Cancelling                   : 3e 0c 01 00 00 00 07 68 15 01 01 00 00 00 93 3c
SET Off                                : 3e 0c 00 00 00 00 07 68 15 01 00 00 00 00 91 3c
SET AS level 10, focus on              : 3e 0c 00 00 00 00 07 68 15 01 01 01 01 0a 9e 3c
```

The ambient level is **0–20** (0x00–0x14); `AmbientSoundControl`'s constructor hard-rejects anything
outside that range (`GB/devices/sony/headphones/prefs/AmbientSoundControl.java:56-64`), and the reply
parser rejects it too (`SonyProtocolImplV2.java:834-838`). Gadgetbridge's UI seekbar is 0–19 with a +1
offset ("Level is offset by 1 because we can't configure the SeekBarPreference min level",
`AmbientSoundControl.java:91-92,100-101`) — **the wire value is 1-based-ish; do not replicate the UI
offset.**

**HyperEars V2 layout** for the same `0x15` dialect is byte-identical
(`HC/.../SonyEarbudAdapter.kt:559-570`: `68 15 01 <on> <AS> 00 <level>`) except it hard-codes the
focus/passthrough byte to `0x00` (`:568`) — i.e. HyperEars never enables focus-on-voice. **DISAGREEMENT
(minor):** trust Gadgetbridge's `focusOnVoice` byte (`SonyProtocolImplV2.java:113`) — HyperEars simply
does not implement that feature.

**SonyBridge** uses subtype **`0x17`** even for its non-wind models, and documents the frame as
`68 17 01 [on] [NC:0/AS:1] [voice passthrough] [level]`
(`SB/Client/CommandSerializer.cpp:203-216`, with the comment "Reverse-engineered from
https://github.com/mos9527/SonyHeadphonesClient (WF-1000XM5); WH family unverified"), read as
`66 17 → 67 17 01 <effect> <0=NC/1=Ambient> <voice> <level>` (`SB/Client/Headphones.cpp:252-265`).
**DISAGREEMENT:** Gadgetbridge/HyperEars use `0x15` on models without wind (`SonyProtocolImplV2.java:80`,
`HC/.../SonyEarbudAdapter.kt:573-580`), SonyBridge uses `0x17` everywhere in V2. Recommendation:
**send `66 15` and parse `67 15`; if you get no answer, fall back to `66 17`.** Both are accepted by the
reply parsers in all three implementations.

#### 3.4.4 Focus on voice / voice passthrough

Present as a single boolean byte in both read (idx 5) and write (idx 5/6) frames; the mode must be
`AMBIENT_SOUND` for it to be meaningful, and Gadgetbridge only persists it in that mode
(`AmbientSoundControl.java:87-93`). V1 writes it at `SonyProtocolImplV1.java:135`, tests at
`SonyProtocolImplV1Test.java:83-89`. In SonyBridge it is `ASM_ID::VOICE = 1`
(`SB/Client/Constants.h:140-144`, used at `SB/Client/Headphones.cpp:392-397`).

#### 3.4.5 Adjacent controls found in the same family (FYI)

* **Ambient Sound Control button mode** (what the physical button cycles through): V2 payload type
  `0xfa`/`0xfc` with sub-selector `0x03`, values `NC_AS_OFF=0x01, NC_AS=0x02, NC_OFF=0x03, AS_OFF=0x04`
  (`SonyProtocolImplV2.java:468-492`, test vectors `SonyProtocolImplV2Test.java:236-254`).
* **Speak-to-Chat** (V2, multiplexed `0xf6`/`0xf8`, sub `0x0c`, **inverted** enable bit):
  enable `f8 0c 00 01`, disable `f8 0c 01 01` (`SonyProtocolImplV2.java:143-164`, tests
  `SonyProtocolImplV2Test.java:142-147`, `SB/Client/Headphones.cpp:358-364`).
* **Adaptive Volume Control** (V2, sub `0x0a`, also inverted): `f8 0a 00` = on (`SonyProtocolImplV2.java:120-129`).
* **ANC optimizer** — V1 only; not implemented for V2 (`SonyProtocolImplV2.java:190-194`).

### 3.5 Device state / firmware / device info

| Query | Request frame | Reply |
|---|---|---|
| Firmware | `COMMAND_1 04 02` → `3e 0c 00 00 00 00 02 04 02 14 3c` | `COMMAND_1 05 02 <len> <ascii…>`; `<len>` = number of ASCII chars, version = `payload[3..]`, must match `^[0-9.\-a-zA-Z_]+$` (`SonyProtocolImplV1.java:247-255,1186-1217`) |
| Audio codec | V2: `COMMAND_1 12 02` (frame `3e 0c 00 00 00 00 02 12 02 22 3c`); V1: `COMMAND_1 18 00` | `13 02 <codec>` (V2) / `19 00 <codec>` (V1). Codec codes: `0x00` UNKNOWN, `0x01` SBC, `0x02` AAC, `0x10` LDAC, `0x20` aptX, `0x21` aptX HD (`impl/v1/params/AudioCodec.java:19-25`; SB `Client/Headphones.cpp:297-308`) |
| Connection quality (LDAC vs stable) | V2 reuses `COMMAND_1 e6 02`, SET `e8 02 <00|01>`, where `0x00` = sound quality / LDAC, `0x01` = stable (`SonyProtocolImplV2.java:234-258,876-891`) | `e7 02 <val>` |

Firmware example (`2.0.1`): `3e 0c 00 00 00 00 08 05 02 05 32 2e 30 2e 31 0f 3c`.

**Device "state"/connection status** as such is not a protocol message in any reference. The RFCOMM link
being up *is* the connection state; Gadgetbridge derives `INITIALIZING` → `INITIALIZED` purely from the
init queue draining (`SonyHeadphonesProtocol.java:475-480`). **INFERRED:** for a module, treat
"RFCOMM connected + INIT_REPLY parsed + at least one valid battery reply" as "usable session".

### 3.6 Notify-driven live updates

The device pushes `0x25` (battery), `0x69` (ambient), `0x29`, `0x59`, `0x99`, `0xfd`, etc. Gadgetbridge
handles NOTIFY and RET identically (`SonyProtocolImplV1.java:720-757`, `SonyProtocolImplV2.java:738-756`).
Always ACK notifies too. Physical-button changes (e.g. ANC toggled on the bud) arrive as `0x69`.

---

## 4. Minimal ordered init sequence to get battery + ANC read/write working

Strictly one outstanding request at a time; wait for the device ACK before sending the next
(`SonyHeadphonesProtocol.java:456-483`, `HC/.../SonyEarbudAdapter.kt:417-431`).

```
 1. RFCOMM connect to 956C7B26-D49A-4BA8-B03F-B17D393CB6E2  (SDP → channel)
    • wait ≥ 500 ms after Bluetooth "connected" before connect(), else IOException
      (SonyHeadphonesSupport.java:86-90)
 2. TX  COMMAND_1 seq=0  "00 00"                -> 3e 0c 00 00 00 00 02 00 00 0e 3c
    • start a ~1250 ms timer; if no INIT_REPLY, resend (max 2 retries)
 3. RX  (ACK 0x01)                      -> adopt seq from it
 4. RX  COMMAND_1 payload "01 …"
        len 4 => V1  (not the XM5: abort if you only support v2)
        len 8 => V2  (expected on XM5: 01 00 03 00 10 04 00 00)
    • TX  ACK seq = 1 - deviceSeq     -> e.g. 3e 01 01 00 00 00 00 02 3c
 5. TX  COMMAND_1 seq=<adopted> "22 09"   (battery DUAL: left+right)
 6. RX  ACK, then 0x23 09 <L> <Lchg> <R> <Rchg>  → TX ACK
 7. TX  "22 0a"                            (battery CASE)
 8. RX  ACK, then 0x23 0a <lvl> <chg>            → TX ACK
 9. TX  "66 15"                            (ambient sound control GET)
10. RX  ACK, then 0x67 15 01 <on> <AS> <focus> <level>  → TX ACK
11. (optional device info) "04 02" then "12 02", ACK each reply
```

Gadgetbridge's equivalent ordered queue is `getFirmwareVersion(), getAudioCodec(),` then, filtered by
capability, `getBattery(SINGLE)…(DUAL2)…(CASE), getAmbientSoundControl(), …`
(`SonyProtocolImplV1.java:767-818`) — for the XM5 with `BatteryDual + BatteryCase + AmbientSoundControl`
the effective queue is firmware, codec, battery(0x09), battery(0x0a), ambient(0x66).
Note Gadgetbridge **skips** `BatterySingle`/`BatteryDual2` because they are not in the XM5 capability set.

Writing ANC afterwards is a single unacknowledged-in-the-queue sense SET frame (§3.4.3) that still gets
its own device ACK; do not pipeline it behind an unanswered GET.

---

## 5. Gotchas specific to the WF-1000XM5 / LSPosed implementations

1. **TWS is one logical device.** Both buds come back in a single DUAL reply; the case is a separate
   query. There is no left/right addressing anywhere (`SonyProtocolImplV1.java:1099-1123`). Do not try to
   open two sessions.
2. **Battery type codes are V2-specific**: DUAL is `0x09`, not `0x01`. `0x01` is DUAL2 (other models) and
   `0x0A` is CASE (`SonyProtocolImplV2.java:1332-1362`). Probing `0x00` (SINGLE) first, like SonyBridge
   does, is a valid robustness strategy (`SB/Client/Headphones.cpp:88-99`).
3. **A bud showing 0 % usually means "not present"**, not "empty": Gadgetbridge skips a bud whose level is
   0 (`SonyProtocolImplV1.java:1105,1115`) and HyperEars maps 0 to "unavailable"
   (`HC/.../SonyEarbudAdapter.kt:455-459,622-627`). Treat 0 as unknown in UI.
4. **ANC dialect ambiguity (see §3.4.3):** Gadgetbridge/HyperEars send subtype `0x15` on the XM5,
   SonyBridge sends `0x17`. Implement `0x15` with a `0x17` fallback. The `0x19` dialect belongs to the
   2026 generation (WF-1000XM6) — **not** the XM5 (`HC/docs/sony-headphones-protocol.md:12-16`).
5. **"Adaptive/Auto Ambient" is not a protocol mode** (§3.4.1) — do not send an invented enum value.
6. **Ambient level range is 0–20 (`0x00`–`0x14`)**; anything else is rejected by the parsers
   (`SonyProtocolImplV2.java:834-838`, `AmbientSoundControl.java:56-64`). Gadgetbridge's UI adds a +1
   offset that must **not** leak into the wire value (`AmbientSoundControl.java:91-92`).
7. **The `0xE0`/`0xE1` "ack channel" does not exist.** I searched all four references for `0xE0`/`0xE1`
   in the Sony context and found nothing: the ACK is **message type `0x01`** with an empty payload and the
   inverted sequence number (§2.5). The only `0xE0/0xE1` hits in `D:\Java\XMSound\.reference` are HID key
   codes in Gadgetbridge (`app/.../util/HidKey.kt:9-10`) and unrelated vendor commands. `DATA_TYPE` in the
   C++ clients does enumerate other type bytes (`DATA_ICD = 9`, `DATA_EV = 10`, `SHOT*`, …,
   `SB/Client/Constants.h:80-99`) but **only `DATA_MDR = 0x0C`, `DATA_MDR_NO2 = 0x0E` and `ACK = 0x01`
   are ever sent/received in the Sony implementations** (`SB/Client/BluetoothWrapper.cpp:30,71,78,155`).
   **Do not implement an `0xE0`/`0xE1` channel.** If your source for it is a capture, re-verify: it is most
   likely the *payload* byte of a different command, or another brand's protocol.
8. **Only one SPP client per channel (INFERRED for Sony).** Android RFCOMM/SPP is a single-client channel:
   HyperEars documents that when a third-party client occupies the channel it cannot connect and must wait,
   for the Huawei family (`HC/docs/compatibility.md:224-228`). For Sony, HyperEars' troubleshooting says
   that keeping two control ends connected "may cause protocol contention" and recommends runtime backoff
   (`HC/docs/troubleshooting.md:66-68`), and it implements exactly that: when a declared vendor control app
   (`com.sony.songpal.mdr`) has any hooked process alive, the module closes its private RFCOMM channel and
   falls back to standard-only integration (`HC/docs/control-apps.md:34,71-87`;
   `HC/docs/system-module-architecture.md:352-369`). **No reference proves the headset accepts two SPP
   clients simultaneously; assume it does not.** Practical consequences:
   * expect `connect()`/SDP to fail, or the socket to connect but never answer, while Sound Connect holds
     the link; retry with backoff and degrade gracefully;
   * the device will keep pushing notifications to whichever client it accepted, so a second client that
     "connects" may see no traffic;
   * there is **no way to steal or share** the channel from another app.
9. **LSPosed / process context.** HyperEars runs its Sony session inside the `com.android.bluetooth`
   process (its docs describe Bluetooth-process sessions and MiLink/Bluetooth cross-process state,
   `HC/docs/system-module-architecture.md:345-350,365-394`), which is where the A2DP/headset device object
   and the Bluetooth stack live. Creating the `BluetoothSocket` there reuses the already-connected ACL, and
   `createRfcommSocketToServiceRecord` performs SDP for you (`HC/system-module/.../EarbudChannel.kt:148-161`).
   **INFERRED:** if you instead open the socket in your own app process, you still need
   `BLUETOOTH_CONNECT` (API 31+), the device must be bonded, and you will be a *second* SPP client (§8).
10. **Parallel apps / work profile:** no reference addresses Sony specifically. **INFERRED generalisation
    of §8:** every extra process/user that opens the private channel is another competing SPP client; keep
    exactly one session per headset address, keyed globally (HyperEars: "每台设备最多一个活动通道"
    — at most one active channel, one reader, one serialised write path per device,
    `HC/docs/system-module-architecture.md:345-347`).
11. **Idle disconnect / standby:** the control link is dropped when idle (`SB/README.md:102`). Keep audio
    playing, or accept reconnection; do not treat socket closure as an error.
12. **Handshake must be re-done after every reconnect**, including the sequence reset to 0
    (`HC/.../SonyEarbudAdapter.kt:634-643` `resetState()`), and the first init frame may be ignored —
    retry it (`SonyHeadphonesSupport.java:53-79`).
13. **Some settings need a reboot to apply** on this family (e.g. voice assistant / NC-ambient button
    function: V2 `COMMAND_1 98 00 16 01`, `SonyProtocolImplV2.java:697-708`; tests
    `SonyProtocolImplV2Test.java:187-190`). Not needed for battery/ANC, but relevant if you extend scope.

---

## 6. Copy-paste checklist for the Kotlin implementation

1. Constants: `0x3E` header, `0x3C` trailer, `0x3D` escape, mask `0xEF`, restore `0x10`
   (`Message.java:48-51`, `SonyHeadphonesWireCodec.kt:133-137`).
2. `MessageType { ACK=0x01, COMMAND_1=0x0C, COMMAND_2=0x0E }` (`MessageType.java:20-24`).
3. Incremental decoder state machine over the RFCOMM `InputStream` — model on
   `SonyHeadphonesWireCodec.Decoder` (`SonyHeadphonesWireCodec.kt:58-110`), which already handles
   fragmentation, coalescing, escaping, resync-on-header, and checksum rejection.
4. Encoder: type, seq, 4-byte BE length, payload, additive checksum, escape, wrap in `3E … 3C`.
5. Session: single outstanding request, FIFO queue, `awaitingAck`, seq adopted from device ACK
   (`SonyHeadphonesProtocol.java:75-81,456-483`; `SonyEarbudAdapter.kt:271-285,417-431`).
6. ACK every `COMMAND_1`/`COMMAND_2` immediately with `type=0x01, seq=1-receivedSeq, payload empty`.
7. Init: `00 00`, detect v2 by 8-byte reply, retry the init at ~1250 ms (max 2).
8. Reads: `22 09`, `22 0a`, `66 15`; writes: `68 15 01 <on> <AS> 00 <level>` with level 0–20.
9. Parse battery `23 09`/`25 09` (6-byte payload) and `23 0a`/`25 0a`; parse ambient `67 15`/`69 15`
   (7-byte payload).
10. Subscribe to notifies (`0x25`, `0x69`) so headset-side button presses are reflected.

# QPlayer HiFi · 阶段 1.5 实施方案
## 输出模式切换 + 独占输出（AAudio / Oboe）

> **目标读者**：负责编码的 agent。
> **执行时机**：**阶段 1 完成并验证通过后**，在同一分支上做的一次增量修改。不要与阶段 1 混在一起做。
> **范围**：仅 Android。桌面宿主（desktop-host）、插件系统**都不动**。
> **前置**：阶段 1 已交付（HiFi 标签页 / 设置项 / 输出链路模型 / 设备与能力探测 / 链路显示卡片）。

---

## 0. 为什么有阶段 1.5

阶段 1 落地后确认了一项事实：**目标 ROM 上 Android 14 的 `AudioManager.getSupportedMixerAttributes()` 不返回 `MIXER_BEHAVIOR_BIT_PERFECT` 属性**。也就是说，"Android 14 位完美直通"这条路在本 ROM 上**不可用**，不能作为主输出路径。

因此阶段 1.5 把目标改为 **「输出模式切换」**，并新增两条**真正能改变输出路径**的通道：

- `LOW_LATENCY`：纯 Java，无需 native，立即可用；
- `EXCLUSIVE`：AAudio / Oboe 独占流（**需 native 模块**，本次已批准引入）。

`BIT_PERFECT`（Android 14）保留为**可选模式之一**，仅在设备支持时可用。

### 0.1 备选思路盘点（结论：选 AAudio）

| 方案 | 无 native | 严格独占 | 结论 |
|---|---|---|---|
| **A. AAudio / Oboe `SharingMode::Exclusive`** | 否 | 准 | **本期主推** |
| B. `AudioTrack` 低延迟 fast path + 指定 USB 设备 | **是** | 否（准直通） | 本期兜底 |
| C. Android 14 位完美 | 是 | 是 | **本 ROM 不可用**，保留为选项 |
| D. 原生 UAC2 等时驱动 | 否 | 是（真 bit-perfect） | 成本极高，**本期不做** |
| E. `AudioPlaybackCapture` 捕获其他 App | 是 | 否 | 与独占无关，**不采用** |

> ⚠️ **必须在用户文案里说清楚（不要过度承诺）**：A / B **都不保证 bit-perfect**。它们买到的是"更低延迟 + 独占该输出设备 + 按源格式开流"。真 bit-perfect 只有 C（本 ROM 不可用）或 D（成本极高）。链路卡片要如实显示"独占程度"。

---

## 1. 阶段 1 已提供的接口（本阶段基于它们）

阶段 1 的产物是本次改动的锚点。下表是**期望命名**；**若阶段 1 实际落地命名不同，按语义映射即可，不要推翻重写**。

| 阶段 1 产物 | 期望命名 | 本阶段用途 |
|---|---|---|
| HiFi 分类 | `SettingsCatalog.HIFI`（`"hifi"`） | 新增行挂在这里 |
| 总开关 key | `hifiEnabled` | 模式行的 `dependsOn` |
| 位完美开关 key | `hifiBitPerfect` | 仅 `BIT_PERFECT` 模式语义生效 |
| 跟随采样率 key | `hifiFollowSampleRate` | `LOW_LATENCY` / `EXCLUSIVE` 生效 |
| 设备选择 key | `hifiOutputDevice`（hidden，String） | 非 `SYSTEM` 模式的目标设备 |
| 链路 DTO | `AudioOutputStatus` | 扩展字段 |
| 链路显示卡片 | HiFi 页 QML | 扩展字段 |
| 控制器只读属性 | `PlayerController` 上的 Property | 新增模式/状态属性 |

> **执行纪律**：动手前先通读阶段 1 的实际代码，把上表映射到真实名字，再开始改。

---

## 2. 模式定义（4 档）

新增枚举 `HifiOutputMode`：

| id | 名称 | 原理 | 依赖 | 需 native | 严格独占 | 本 ROM 预期 |
|---|---|---|---|---|---|---|
| 0 `SYSTEM` | 系统模式 | `MediaPlayer`（现状，默认） | — | 否 | 否 | 可用（默认） |
| 1 `LOW_LATENCY` | 低延迟直通 | `AudioTrack` + `PERFORMANCE_MODE_LOW_LATENCY` + `setPreferredDevice(USB)` + 跟随源采样率 | API 26+ | **否** | 否 | 可用 |
| 2 `EXCLUSIVE` | 独占输出 | Oboe/AAudio `SharingMode::Exclusive` + `setDeviceId(USB)` + 源格式 | Oboe AAR + CMake | **是** | 准 | 需实测 |
| 3 `BIT_PERFECT` | 位完美（Android 14） | `setPreferredMixerAttributes` + AudioTrack | API 34 + ROM 支持 | 否 | 是 | **不可用** |

---

## 3. 实施步骤（按顺序，每步可独立验证）

**一次只做一层，每步自测通过再进下一步。** 不要一次性铺开。

### Step 1 · 模式枚举 + 设置行（不接后端）
- 加 `HifiOutputMode`（枚举）+ key `hifiOutputMode`（int，默认 0）。
- `SettingsCatalog` 加一行 `dropdown`，`.onlyOn(ANDROID)` + `.dependsOn(HIFI_ENABLED_KEY)`。
- **先不接任何后端**：选任何模式都不改变输出（仍走 `SYSTEM`）。
- **验证**：HiFi 页出现「输出模式」，默认"系统模式"；选别的值能存住、重启后保留。

### Step 2 · 能力探测扩展
- 扩展阶段 1 的探测（见 §5.2），产出 `HifiCapabilities`。
- **验证**：链路卡片能看到"低延迟支持 / 位完美支持 / USB 设备"能力位。

### Step 3 · 调度层骨架
- 新增 `HifiAudioDispatcher`：按模式选择实际后端；**除 `SYSTEM` 外一律先回退 `SYSTEM`**。
- 记录 `lastFallbackReason`。
- **验证**：选任意模式，播放与阶段 1 行为一致（因为都回退到 system），无回归。

### Step 4 · `LOW_LATENCY` 后端（纯 Java）
- 见 §5.4。接入后非回退，真正走 `AudioTrack`。
- **验证**（插 USB）：切"低延迟直通"，链路显示生效；播放 / seek / 暂停 / 后台保活 / 切歌均正常。

### Step 5 · `EXCLUSIVE` 后端（Oboe native）
- 见 §5.5。需要 Gradle + CMake + JNI 接入。
- **验证**（插 USB）：切"独占输出"；成功 → 链路显示"独占"；失败 → 显示回退原因且**播放不中断**。

### Step 6 · `BIT_PERFECT` 条件接入
- 复用阶段 1 的位完美探测；**本 ROM 上应判定不可用**。
- **验证**：选"位完美"→ 链路显示"不可用 → 已回退（ROM 未声明位完美输出）"。

### Step 7 · UI / i18n / 回归
- 链路卡片扩展字段 + 全部文案；跑一遍 §7 验收。

---

## 4. 设计建议（降低工作量，避免重复）

`LOW_LATENCY` 和 `EXCLUSIVE` 都需要"**解码出 PCM → 写入输出**"。建议抽一层，避免两套逻辑：

```
MediaCodec → PcmSource(解码 + 缓冲 + seek/flush)
                 ├── AudioTrackSink   (LOW_LATENCY)
                 └── OboeSink         (EXCLUSIVE, 走 JNI)
```

- **现有后端已有逻辑必须复用**：音频焦点、`WifiLock`、stall 看门狗、缓存/切流、暂停/seek。不要在新后端里漏掉，否则会引入"后台被杀 / 跳歌"之类回归。
- **不要替换 `AndroidAudioBackend` 单实例**：它是进程级共享的（`sharedController` + `PlaybackService` 都持有）。由 `HifiAudioDispatcher` **内部**切策略。

---

## 5. 细节规范

### 5.1 设置项与框架限制（**两个坑，务必避开**）

1. **`dropdown` 的选项是静态标签**（`SettingSpec.dropdown(key, cat, title, def, String... labels)`）。
   → **不支持逐项禁用 / 动态选项**。**不要**试图把不可用的模式置灰。
   → 正确做法：允许选中，由控制器判定 → 不可用则**自动回退**，并在链路卡片显示原因。
2. **`dependsOn` 只支持布尔键**（`shared-qml/settings/SettingGroup.qml`：`settings.value(spec.dependsOn) === true`）。
   → **不能**写 `.dependsOn("hifiOutputMode == 2")`。
   → 「位完美独占」开关**保持可见**（受 `hifiEnabled` 控制），仅当模式 = `BIT_PERFECT` 时**语义生效**。

选项标签（i18n）：
```
settings.hifi.mode.system      = 系统模式
settings.hifi.mode.lowlatency  = 低延迟直通
settings.hifi.mode.exclusive   = 独占输出
settings.hifi.mode.bitperfect  = 位完美（Android 14）
```

### 5.2 能力探测（扩展阶段 1）

探测时机：App 启动、USB 插拔、切换模式时、播放开始前。

| 探测项 | 来源 |
|---|---|
| USB 输出设备 | `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` → `getType()` ∈ `{TYPE_USB_DEVICE, TYPE_USB_HEADSET, TYPE_USB_ACCESSORY}` 且 `isSink()`；`getId()` 供 Oboe `setDeviceId` |
| 低延迟支持 | `PackageManager.hasSystemFeature(FEATURE_AUDIO_LOW_LATENCY)`；API 30+ 可加 `FEATURE_AUDIO_PRO`；`AudioManager.getProperty(PROPERTY_OUTPUT_FRAMES_PER_BUFFER)` 非空 |
| 位完美支持 | API 34+ 且 `getSupportedMixerAttributes(usbDev)` 中存在 `MIXER_BEHAVIOR_BIT_PERFECT`（**本 ROM → 空**） |
| 独占（AAudio）支持 | **Java 侧无法预判**：切到 `EXCLUSIVE` 时"尝试打开"，失败即回退并记录原因 |

### 5.3 调度与回退

```java
HifiAudioMode effective = switch (requestedMode) {
    case LOW_LATENCY -> caps.lowLatencySupported ? LOW_LATENCY : SYSTEM;
    case EXCLUSIVE   -> tryOpenExclusive() ? EXCLUSIVE : SYSTEM;   // 打开失败即回退
    case BIT_PERFECT -> caps.bitPerfectSupported ? BIT_PERFECT : SYSTEM;
    default          -> SYSTEM;
};
```
- 回退时写入 `lastFallbackReason`（i18n key + 参数）供链路卡片显示。
- **模式切换不打断当前曲目，下一首生效**；空闲时可立即生效（与阶段 1 一致）。

### 5.4 `LOW_LATENCY` 规格

`AudioTrack.Builder`：
- `setAudioAttributes(USAGE_MEDIA)`；
- `setAudioFormat(...)`：源采样率 / 声道 / `ENCODING_PCM_FLOAT`（或 16/24bit packed）；
- `setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)`（API 26+）；
- `setPreferredDevice(usbDevice)`（API 23+）。

写入用独立线程；必须处理欠载、`flush()`（seek）、暂停恢复。

### 5.5 `EXCLUSIVE` 规格（Oboe / AAudio native）

**Gradle（`android-shell/app/build.gradle.kts`）**
```kotlin
android {
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    defaultConfig { ndk { abiFilters += "arm64-v8a" } }   // 先只做 arm64，后续再评估 v7a
    ndkVersion = "<与项目现有 NDK 一致>"
}
dependencies { implementation("com.google.oboe:oboe:<最新稳定版>") }  // 含 prefab
```

**CMakeLists.txt（`src/main/cpp/`）**
```cmake
cmake_minimum_required(VERSION 3.22)
project(oboeoutput)
find_package(oboe REQUIRED CONFIG)
add_library(oboeoutput SHARED oboe_output.cpp)
target_link_libraries(oboeoutput oboe::oboe log)
```

**JNI 接口（建议签名）**
```java
// OboeOutput.java
static { System.loadLibrary("oboeoutput"); }
boolean open(int deviceId, int sampleRate, int channels, boolean floatPcm);
void    start(); void pause(); void flush();
int     write(float[] pcm, int frames);   // 或 short[]
int     actualSampleRate(); int actualChannels();
boolean wasExclusive();                   // 实际是否拿到独占
String  lastError();                      // 打开失败原因
void    close();
```

**Oboe builder 配置**
`setDirection(Output)`、`setSharingMode(SharingMode::Exclusive)`、`setPerformanceMode(LowLatency)`、`setDeviceId(usbId)`、`setFormat(...)`、`setSampleRate(...)`、`setChannelCount(...)`、`setErrorCallback(...)`。

**行为与失败处理**
- 打开失败（不被支持 / 设备被占用）→ `open()` 返回 `false` → 调度器回退 `SYSTEM`，链路卡片显示原因。
- **独占流不用时立即 `close()`**（官方建议）：`onPause` / `onStop` / 播放结束 / 切歌失败都要关。
- 拔掉设备后必须重新探测并关闭流。

### 5.6 `BIT_PERFECT` 规格
沿用阶段 1 的探测结果。设备不支持 → 该模式状态显示"不可用"，**不参与实际输出**。实现方式即 `setPreferredMixerAttributes` + `AudioTrack`（仅在支持设备上）。

### 5.7 链路显示新增字段

| 字段 | 说明 |
|---|---|
| 输出模式 | 用户当前选择的模式 |
| 实际使用 | 实际生效的后端；若回退则显示「已回退」 |
| 独占状态 | 是 / 否 / 准 |
| 说明 | 不可用或回退原因（i18n） |

保留阶段 1 字段：音源、输出设备、输出格式、状态、位完美。

### 5.8 i18n
中英各新增：4 个模式标签 + 链路新增 4 个字段名 + 各回退原因文案（约 12–16 条）。桌面语言文件只需补键（桌面不显示该标签）。

---

## 6. 文件清单

**新增**
- `player-core`：`HifiOutputMode.java`（枚举）、`HifiCapabilities.java`（DTO）、`HifiAudioDispatcher.java`
- `android-shell`：`AndroidHifiProbe.java`（若阶段 1 未拆出）、`AudioTrackSink.java`
- `android-shell`（`EXCLUSIVE`）：`src/main/cpp/CMakeLists.txt`、`src/main/cpp/oboe_output.cpp`、`OboeOutput.java`

**修改**
- `SettingsCatalog.java`（+1 行 `dropdown`）
- `SettingsCore.java`（`apply()` / `pushToController()` 接线 `hifiOutputMode`）
- `PlayerController.java`（+模式/状态只读属性 + `setHifiOutputMode` 等）
- Android 音频后端 → 接入 `HifiAudioDispatcher`
- `android-shell/app/build.gradle.kts`（Oboe 依赖 + `externalNativeBuild` + abiFilters）
- 链路卡片 QML + i18n

---

## 7. 验收标准

1. **回归红线**：默认（`SYSTEM`）行为与阶段 1 / 现状 **100% 一致**；桌面版仍看不到 HiFi 标签。
2. `LOW_LATENCY`（插 USB）：链路显示"低延迟直通"；播放 / seek / 暂停 / 后台保活正常。
3. `EXCLUSIVE`（插 USB）：成功 → 显示"独占"；失败 → 显示回退原因且播放继续。
4. `BIT_PERFECT`：本 ROM 上选它 → 显示"不可用 → 已回退"。
5. 拔出 USB：任意模式回落 `SYSTEM`。
6. 无 USB 设备：非 `SYSTEM` 模式均回退并提示。

---

## 8. 不做的事（红线）

- ❌ 不替换 `AndroidAudioBackend` 单实例（用调度器内部切策略）。
- ❌ 不改桌面宿主、不改插件系统、不给插件开放音频接口。
- ❌ 不做原生 UAC2 等时驱动（方案 D，成本极高）。
- ❌ 不引入除 Oboe 之外的 native 依赖。
- ❌ 不为了"置灰选项"去改设置框架的行类型。

---

## 9. 风险与兜底

| 风险 | 兜底 |
|---|---|
| Oboe 独占在部分机型打开失败/很慢 | 调度器回退 `SYSTEM` + 显示原因；不阻塞播放 |
| native 构建链 / ABI 体积 | 只做 `arm64-v8a`；`abiFilters` 收窄 |
| MediaCodec 解码位深限制（多为 16bit/float） | 链路卡片如实显示实际位深；不做过度承诺 |
| 新后端漏掉焦点/保活/看门狗 | Step 4 的验收里专门跑后台与切歌用例 |

---

## 10. 给 agent 的执行纪律

1. **先读阶段 1 的实际代码**，把 §1 的"期望命名"映射到真实名字。
2. **按 Step 1→7 顺序**，每步自测通过再继续。
3. **默认值是 `SYSTEM`**，任何模式下都必须能安全回退。
4. **保留阶段 1 的链路显示**，只做扩展，不重写。
5. 用户文案里**不要写"bit-perfect 已实现"**，只说"独占 / 低延迟"，除非模式 = `BIT_PERFECT` 且实际生效。

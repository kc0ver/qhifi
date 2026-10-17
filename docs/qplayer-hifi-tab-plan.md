# QPlayer HiFi 标签页实施方案（Android）

> 目标读者：负责编码的 agent。
> 范围：**仅 Android**。桌面宿主（desktop-host）不做任何改动。
> 说明：未收到设置页截图，标签位置依据代码现状确认——`SettingsCatalog.CATEGORIES` 当前顺序为
> `外观 → 页面 → 播放 → 歌词 → 悬浮歌词 → 桌面歌词 → 存储 → 插件 → 关于`，
> 因此「HiFi」插在 **`插件` 与 `关于` 之间**。

---

## 0. 结论与交付分期

| 期 | 内容 | 风险 | 是否改变实际输出 |
|---|---|---|---|
| **期 1** | HiFi 标签 + 设置项 + 输出链路模型 + Android 设备/能力探测 + 链路显示（只读） | 低 | 否 |
| **期 2** | Android 14 bit-perfect 直通输出后端（AudioTrack + MediaCodec + `setPreferredMixerAttributes`），不支持则回退 MediaPlayer | 高 | 是 |

**建议先合并期 1**：它正好补上用户最初要的"链路显示"，且默认开关关闭时行为与现状 100% 一致，可独立验证。
期 2 单独评审后再做，默认关闭、可回退。

---

## 1. 现状架构速览（改动的锚点）

- **设置页是数据驱动生成，不是手写**：`SettingsCatalog.CATEGORIES` 决定标签顺序；`SettingsCatalog.specs()` 决定每一行；`SettingsGroup` 决定卡片；`SettingsPage.qml` 通过 `Repeater` 通用渲染。文件名见 [SettingsPage.qml](file:///workspace/qplayer/shared-qml/settings/SettingsPage.qml#L24-L34)。
  - **普通设置行不需要改任何 QML**，只在 catalog 里加一条。
- **空分类会被自动隐藏**：`SettingsCore.categories()` 只保留"有可见行"的分类（唯一例外是 `plugins`，被硬编码保留）。见 [SettingsCore.java](file:///workspace/qplayer/player-core/src/main/java/dev/t1m3/qplayer/settings/SettingsCore.java#L200-L208)。
  - → 只要 HiFi 的行都加 `.onlyOn(ANDROID)`，**该标签就只在 Android 出现**，桌面自动没有。
- **QML 全局对象**：`player` = `PlayerController`、`settings` = `SettingsCore`、`i18n` = `I18n`，在 `QmlGLSurfaceView` 里注册（[QmlGLSurfaceView.java](file:///workspace/qplayer/android-shell/app/src/main/java/io/github/timer_err/qml4j/android/QmlGLSurfaceView.java#L583-L590)）。
  - → **在 `PlayerController` 上新增 `Property` 即自动成为 `player.xxx`，无需注册**。
- **音频后端是单实例、进程级共享**：在 [QPlayerActivity.java](file:///workspace/qplayer/android-shell/app/src/main/java/dev/t1m3/qplayer/android/ui/QPlayerActivity.java#L144-L163) 创建 `AndroidAudioBackend` 并交给进程级共享的 `sharedController`，`PlaybackService` 也持有该 controller。
  - → **期 2 不要替换后端实例**，应让一个调度后端内部切换策略（见 §6.2）。
- **编译/运行 API**：`compileSdk = 34`、`minSdk = 26`、`targetSdk = 34`（[build.gradle.kts](file:///workspace/qplayer/android-shell/app/build.gradle.kts#L7-L12)）。
  - → API 34 的 `AudioMixerAttributes` **可编译**，但**运行时必须 `SDK_INT >= 34` 分支**。
- **现有"设置→控制器"接线模式**：`SettingsCore.apply()` / `pushToController()` 按 key `switch` 调用 `controller.setXxx()`，见 [SettingsCore.java](file:///workspace/qplayer/player-core/src/main/java/dev/t1m3/qplayer/settings/SettingsCore.java#L496-L533)。新增设置照此办理。

---

## 2. 期 1：标签 + 设置项 + 链路显示

### 2.1 新增分类（player-core）

文件：[SettingsCatalog.java](file:///workspace/qplayer/player-core/src/main/java/dev/t1m3/qplayer/settings/SettingsCatalog.java#L44-L49)

1. 新增分类常量：
   ```java
   public static final String HIFI = "hifi";
   ```
2. 把 `HIFI` 插入 `CATEGORIES`，位置在 `PLUGINS` 之后、`ABOUT` 之前：
   ```java
   Arrays.asList(APPEARANCE, PAGES, PLAYBACK, LYRIC, OVERLAY_LYRIC, DESKTOP_LYRIC,
           LOCAL, PLUGINS, HIFI, ABOUT)
   ```
3. 新增 key 常量：
   ```java
   public static final String HIFI_ENABLED_KEY     = "hifiEnabled";
   public static final String HIFI_BIT_PERFECT_KEY = "hifiBitPerfect";
   public static final String HIFI_FOLLOW_RATE_KEY = "hifiFollowSampleRate";
   public static final String HIFI_DEVICE_KEY      = "hifiOutputDevice"; // String，空 = 自动
   ```

### 2.2 期 1 的 catalog 行（追加在 `specs()` 中 ABOUT 段之前）

全部 `.onlyOn(ANDROID)`。分组 id 用于卡片标题（`settings.group.<id>`）。

| 行 | 类型 | key / action | 默认 | group | dependsOn |
|---|---|---|---|---|---|
| 启用 HiFi 输出 | `toggle` | `hifiEnabled` | false | `hifiOutput` | — |
| 位完美独占 | `toggle` | `hifiBitPerfect` | true | `hifiOutput` | `hifiEnabled` |
| 采样率跟随源 | `toggle` | `hifiFollowSampleRate` | true | `hifiOutput` | `hifiEnabled` |
| 输出设备 | `action` | action `pickHifiDevice`，`provider("hifiDeviceName")`，`inlineProvider()` | — | `hifiDevice` | `hifiEnabled` |
| （隐藏存储） | `hidden(TEXT)` | `hifiOutputDevice` | "" | — | — |

示例代码：

```java
// ---- HiFi（Android only）--------------------------------------------
out.add(SettingSpec.toggle(HIFI_ENABLED_KEY, HIFI, "settings.hifiEnabled.title", false)
        .desc("settings.hifiEnabled.desc")
        .onlyOn(ANDROID)
        .group("hifiOutput")
        .build());
out.add(SettingSpec.toggle(HIFI_BIT_PERFECT_KEY, HIFI, "settings.hifiBitPerfect.title", true)
        .desc("settings.hifiBitPerfect.desc")
        .onlyOn(ANDROID)
        .dependsOn(HIFI_ENABLED_KEY)
        .group("hifiOutput")
        .build());
out.add(SettingSpec.toggle(HIFI_FOLLOW_RATE_KEY, HIFI, "settings.hifiFollowSampleRate.title", true)
        .desc("settings.hifiFollowSampleRate.desc")
        .onlyOn(ANDROID)
        .dependsOn(HIFI_ENABLED_KEY)
        .group("hifiOutput")
        .build());
out.add(SettingSpec.action("pickHifiDevice", HIFI, "settings.hifiOutputDevice.title", "")
        .provider("hifiDeviceName").inlineProvider()
        .desc("settings.hifiOutputDevice.desc")
        .onlyOn(ANDROID)
        .dependsOn(HIFI_ENABLED_KEY)
        .group("hifiDevice")
        .build());
// 存储设备 id，自身不渲染（与 lyricFullWidth 同款用法）
out.add(SettingSpec.hidden(HIFI_DEVICE_KEY, SettingSpec.TEXT, "")
        .onlyOn(ANDROID)
        .build());
```

> 注意：`SettingSpec.hidden(...)` 的 `category` 为空，不会出现在任何分类里，也**不影响** `categories()`。
> 设备列表是动态的，而 `SettingSpec.options` 是静态的，所以设备选择必须走 `action + provider + 宿主弹窗`（与插件目录的 dialog 模式一致），不能做成 `dropdown`。

### 2.3 设置核心接线（player-core）

文件：[SettingsCore.java](file:///workspace/qplayer/player-core/src/main/java/dev/t1m3/qplayer/settings/SettingsCore.java#L496-L533)

- `apply(SettingSpec, Object)` 的 `controller != null` switch 里增加：
  ```java
  case SettingsCatalog.HIFI_ENABLED_KEY:     controller.setHifiEnabled(bool(SettingsCatalog.HIFI_ENABLED_KEY)); break;
  case SettingsCatalog.HIFI_BIT_PERFECT_KEY: controller.setHifiBitPerfect(bool(SettingsCatalog.HIFI_BIT_PERFECT_KEY)); break;
  case SettingsCatalog.HIFI_FOLLOW_RATE_KEY: controller.setHifiFollowSampleRate(bool(SettingsCatalog.HIFI_FOLLOW_RATE_KEY)); break;
  case SettingsCatalog.HIFI_DEVICE_KEY:      controller.setHifiOutputDevice(str(SettingsCatalog.HIFI_DEVICE_KEY)); break;
  ```
- `pushToController()` 增加同样四行（保证启动时用持久化值初始化）。

### 2.4 宿主注册（android-shell）

文件：[QPlayerActivity.java](file:///workspace/qplayer/android-shell/app/src/main/java/dev/t1m3/qplayer/android/ui/QPlayerActivity.java#L242-L255)（`settings = new SettingsCore(); settings.load(...)` 附近）

```java
settings.registerInfo("hifiDeviceName", () -> controller.hifiDeviceName.peek());
settings.registerAction("pickHifiDevice", () -> runOnUiThread(this::showHifiDevicePicker));
```

`showHifiDevicePicker()`：读取 `player.hifiDevices`，弹选择对话框（可复用现有 `Dialog` 组件或系统 `AlertDialog`），
选中后：

```java
settings.put(SettingsCatalog.HIFI_DEVICE_KEY, deviceId);   // 触发 setHifiOutputDevice 接线
```

> 若无任何 USB 设备，列表至少要有"自动（系统默认）"一项，对应 id `""`。

### 2.5 链路显示卡片（shared-qml）

文件：[SettingsPage.qml](file:///workspace/qplayer/shared-qml/settings/SettingsPage.qml#L150-L268)

沿用"关于/插件"的自定义卡片写法，在 `groupsColumn` 内新增一块，`visible: page.currentCategory === "hifi"`：

```
SettingCard {
  visible: page.currentCategory === "hifi"
  SettingTitle { text: i18n.t("settings.hifiChain.title") }
  // 每行：左标签 + 右值，全部读 player.hifi* 属性（自动响应式）
  源       player.hifiSourceFormat
  输出设备  player.hifiDeviceName
  输出格式  player.hifiOutputFormat
  状态     player.hifiResampled ? 已重采样 : 直通
  能力     player.hifiSupported ? (player.hifiBitPerfectActive ? 位完美生效 : 位完美可用) : 不支持
  原因     player.hifiReason   （hifiReason 为空则隐藏该行）
}
```

**qml4j 约束（重要）**：`Layout.fillWidth` 只可靠作用于 `Layout` 的直接子级（见 [SettingCard.qml](file:///workspace/qplayer/shared-qml/settings/SettingCard.qml#L8-L15) 注释）；卡片内不要做深嵌套，
每行用扁平的 `RowLayout`（`Item { Layout.fillWidth: true }` 撑开后放右侧值），不要依赖 `anchors` 逐层跨越。

> 若嫌卡片成本高，可退化为只用 §2.2 的 action 行 + `inlineProvider` 显示一行摘要；但信息量不足以表达"源/输出/是否重采样"，不推荐。

### 2.6 i18n（两份语言文件都要加）

文件：[zh_CN.properties](file:///workspace/qplayer/player-core/src/main/resources/lang/zh_CN.properties#L47-L56)、[en_US.properties](file:///workspace/qplayer/player-core/src/main/resources/lang/en_US.properties#L47-L56)

```properties
# zh_CN
settings.category.hifi=HiFi 音频
settings.group.hifiOutput=输出
settings.group.hifiDevice=输出设备
settings.group.hifiChain=音频链路
settings.hifiEnabled.title=HiFi 直通输出
settings.hifiEnabled.desc=关闭时使用系统默认播放路径（与当前版本一致）
settings.hifiBitPerfect.title=位完美独占
settings.hifiBitPerfect.desc=需要 Android 14 及以上且 USB DAC 支持；不支持时自动回退
settings.hifiFollowSampleRate.title=采样率跟随源
settings.hifiFollowSampleRate.desc=按音源采样率输出，避免系统重采样
settings.hifiOutputDevice.title=输出设备
settings.hifiOutputDevice.desc=选择用于播放的 USB 音频设备
settings.hifiOutputDevice.button=选择设备
settings.hifiChain.title=音频链路
settings.hifiChain.source.title=音源
settings.hifiChain.output.title=输出设备
settings.hifiChain.format.title=输出格式
settings.hifiChain.status.title=状态
settings.hifiChain.status.direct=直通
settings.hifiChain.status.resampled=已重采样
settings.hifiChain.capability.title=位完美
settings.hifiChain.capability.active=已生效
settings.hifiChain.capability.available=可用
settings.hifiChain.capability.unsupported=不支持
settings.hifiChain.reason.title=说明
```

```properties
# en_US
settings.category.hifi=HiFi audio
settings.group.hifiOutput=Output
settings.group.hifiDevice=Output device
settings.group.hifiChain=Audio chain
settings.hifiEnabled.title=HiFi direct output
settings.hifiEnabled.desc=Off uses the system default path (same as today)
settings.hifiBitPerfect.title=Bit-perfect exclusive
settings.hifiBitPerfect.desc=Requires Android 14+ and a USB DAC that supports it; falls back automatically
settings.hifiFollowSampleRate.title=Follow source sample rate
settings.hifiFollowSampleRate.desc=Output at the source rate to avoid system resampling
settings.hifiOutputDevice.title=Output device
settings.hifiOutputDevice.desc=Pick the USB audio device used for playback
settings.hifiOutputDevice.button=Choose device
settings.hifiChain.title=Audio chain
settings.hifiChain.source.title=Source
settings.hifiChain.output.title=Output device
settings.hifiChain.format.title=Output format
settings.hifiChain.status.title=Status
settings.hifiChain.status.direct=Direct
settings.hifiChain.status.resampled=Resampled
settings.hifiChain.capability.title=Bit-perfect
settings.hifiChain.capability.active=Active
settings.hifiChain.capability.available=Available
settings.hifiChain.capability.unsupported=Unsupported
settings.hifiChain.reason.title=Note
```

---

## 3. 期 1 的 Java 模型（player-core，新增文件）

### 3.1 `audio/AudioOutputDevice.java`

设备列表项。public final 字段（QML 桥按字段反射读取）：

```java
public final class AudioOutputDevice {
    public final String id;            // DeviceInfo.getId() 字符串；"" = 自动
    public final String name;          // 产品名，如 "iBasso DC03"
    public final String routeType;     // "usb" / "builtin" / "bluetooth"
    public final boolean supportsBitPerfect;
}
```

### 3.2 `audio/AudioOutputStatus.java`

链路快照，不可变，public final 字段：

| 字段 | 含义 |
|---|---|
| `deviceId` / `deviceName` / `routeType` | 当前实际输出设备 |
| `sourceEncoding` / `sourceSampleRate` / `sourceBitDepth` | 音源格式 |
| `outputEncoding` / `outputSampleRate` / `outputBitDepth` | 协商后的输出格式 |
| `resampled` | 是否发生重采样 |
| `bitPerfectSupported` / `bitPerfectActive` | 设备是否支持 / 当前是否生效 |
| `reason` | 不可用的原因说明（可空） |

### 3.3 `audio/HifiOutputControl.java`（可选能力接口）

由 Android 后端实现；桌面后端不实现 → 上层用 `instanceof` 判定，未实现即视为"不支持 HiFi"。

```java
public interface HifiOutputControl {
    java.util.List<AudioOutputDevice> outputDevices();
    AudioOutputStatus outputStatus();
    boolean isBitPerfectSupported();
    void setHifiEnabled(boolean on);
    void setBitPerfectEnabled(boolean on);
    void setFollowSampleRate(boolean on);
    void selectOutputDevice(String deviceId);
}
```

> 不要往现有 [AudioBackend.java](file:///workspace/qplayer/player-core/src/main/java/dev/t1m3/qplayer/audio/AudioBackend.java) 里加方法，避免污染桌面契约。用独立接口 + `instanceof` 更干净。

---

## 4. PlayerController 改动（player-core）

文件：[PlayerController.java](file:///workspace/qplayer/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L469-L508)（属性区）、构造区 [L923-L930](file:///workspace/qplayer/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L923-L930)、renderWake 区 [L2745-L2750](file:///workspace/qplayer/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L2745-L2750)

### 4.1 新增 Property（全部 `public final Property<...>`）

```java
public final Property<Boolean> hifiSupported       = new Property<>(false);
public final Property<Boolean> hifiEnabled         = new Property<>(false);
public final Property<Boolean> hifiBitPerfectActive= new Property<>(false);
public final Property<String>  hifiDeviceName      = new Property<>("");
public final Property<String>  hifiRouteType       = new Property<>("builtin");
public final Property<String>  hifiSourceFormat    = new Property<>("");
public final Property<String>  hifiOutputFormat    = new Property<>("");
public final Property<Boolean> hifiResampled       = new Property<>(false);
public final Property<String>  hifiReason          = new Property<>("");
public final Property<java.util.List<AudioOutputDevice>> hifiDevices
        = new Property<>(java.util.Collections.emptyList());
```

### 4.2 新增方法（由 `SettingsCore` 调用）

```java
public void setHifiEnabled(boolean on) { /* if (backend instanceof HifiOutputControl h) h.setHifiEnabled(on); */ refreshHifiStatus(); }
public void setHifiBitPerfect(boolean on) { ... }
public void setHifiFollowSampleRate(boolean on) { ... }
public void setHifiOutputDevice(String id) { ... }
public void refreshHifiStatus() { ... }   // 从 h.outputStatus() 灌进上面的 Property
```

`refreshHifiStatus()` 内部注意：
- 后端回调可能来自音频线程 → 用现有 `mainExec` / `renderWake` 模式回落到渲染线程再 `set()`，与仓库既有做法保持一致。
- 未实现 `HifiOutputControl` 时，把 `hifiSupported=false`、`hifiDeviceName` 留空、`hifiReason` 设为"当前设备不支持"。

### 4.3 刷新时机

- 进入 / 离开播放、切歌后；
- 设置项变更后（§4.2 已覆盖）;
- USB 设备插拔后（期 2 用 `AudioDeviceCallback`，见 §6.3）。

> **期 1 不改变播放路径**：链路显示反映的是 `MediaPlayer` 现状，例如
> 源 `FLAC 24bit/96kHz` → 输出 `48kHz / 16bit` → 状态 `已重采样`。

---

## 5. 验收标准（期 1）

1. 设置页出现「HiFi 音频」标签，位于 **插件** 与 **关于** 之间；桌面构建不出现该标签。
2. 默认（`hifiEnabled=false`）时，播放行为与改动前完全一致，链路卡片显示现状并标注"已重采样"。
3. 插入 USB DAC 后，输出设备行显示真实设备名，链路卡片随之更新；拔掉后回落为系统默认输出。
4. 单测：
   - `SettingsCatalog`：`CATEGORIES` 顺序断言（`PLUGINS` 之后紧跟 `HIFI`，再是 `ABOUT`）。
   - `SettingsCore`：新增 key 的读取/持久化/`apply` 接线（参照 [overlayLyricSettingsTest / 现有关联测试](file:///workspace/qplayer/player-core/src/test/java/dev/t1m3/qplayer/settings)）。
   - `PlayerController`：`backend` 未实现 `HifiOutputControl` 时，`hifiSupported=false` 且不抛异常。

---

## 6. 期 2：Android 14 bit-perfect 直通后端

> 这是重活，**建议独立 PR、默认关闭、可回退**。

### 6.1 目标

`hifiEnabled && bitPerfectSupported` 时，走 `AudioTrack` + `MediaCodec`，并对 USB 设备应用
`AudioManager.setPreferredMixerAttributes(..., MIXER_BEHAVIOR_BIT_PERFECT)`；否则回退现有 `MediaPlayer` 路径。

### 6.2 后端结构（保持单实例）

- 新增 `AndroidHiFiAudioBackend implements AudioBackend, HifiOutputControl`（AudioTrack + MediaCodec 直通实现）。
- 新增调度器 `AndroidOutputBackend implements AudioBackend, HifiOutputControl`：持有"普通后端 + HiFi 后端"，
  `play()` 时按当前设置选路；`QPlayerActivity` 仍只创建这一个实例（[L144](file:///workspace/qplayer/android-shell/app/src/main/java/dev/t1m3/qplayer/android/ui/QPlayerActivity.java#L144)）。
  - 设置变更**不打断当前曲目**，下一曲生效（与 `highQuality` 的"仅影响之后解析的曲目"语义一致，见 [PlayerController.java](file:///workspace/qplayer/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L5571-L5576)）。

### 6.3 HiFi 后端实现要点（Android）

- **设备枚举**：`AudioManager.getDevices(GET_DEVICES_OUTPUTS)`，筛 `TYPE_USB_DEVICE` / `TYPE_USB_HEADSET` / `TYPE_USB_ACCESSORY`；
  `getEncodings()` / `getSampleRates()` / `getChannelCounts()` / `getChannelMasks()` 用于能力与协商。
- **位完美能力**（运行时判 `Build.VERSION.SDK_INT >= 34`）：
  - `audioManager.getSupportedMixerAttributes(usbDevice)`；
  - 过滤 `getMixerBehavior() == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT`；
  - `AudioAttributes(USAGE_MEDIA)` + `audioManager.setPreferredMixerAttributes(attr, usbDevice, mixerAttributes)`；
  - `AudioTrack.Builder().setAudioAttributes(attr).setAudioFormat(mixerAttributes.getFormat())`，**轨格式必须与设置一致**；
  - 结束：`clearPreferredMixerAttributes(attr, usbDevice)`；
  - `addOnPreferredMixerAttributesChangedListener` 处理属性/设备变化。
- **打开源**：`http(s)+headers`（HttpURLConnection）、`content://`（ContentResolver）、本地路径 → 交给 `MediaExtractor`/`MediaCodec`。
- **解码与格式**：`MediaCodec` 解码到 PCM；目标格式取 `getSupportedMixerAttributes` 返回的 `format`。源采样率与设备支持一致才是真 bit-perfect，否则要么重采样（降级）要么选最接近的支持率。
- **音量旁路**：bit-perfect 下框架不提供软件音量 → `setVolume` 应旁路，UI 提示"音量由设备/系统控制"；同时说明它与现有 fade 逻辑的冲突（bit-perfect 时 fade 不可用）。
- **位置/时长/seek**：`AudioTrack.getPlaybackHeadPosition()` + 帧率换算；`duration` 取 `MediaFormat.KEY_DURATION`；seek 需重建解码器 + 轨并定位到关键帧。
- **必须复刻的健壮性**（否则会明显退化，参照 [AndroidAudioBackend.java](file:///workspace/qplayer/android-shell/app/src/main/java/dev/t1m3/qplayer/android/playback/AndroidAudioBackend.java)）：
  音频焦点（含 OEM 的 LOSS 处理）、WifiLock、partial wake lock、stall 看门狗、缓存文件切换（`growingCache`/`notifyCacheComplete`）。
- **热插拔**：`AudioManager.registerAudioDeviceCallback`；设备消失 → 暂停或回退到默认后端，并刷新链路状态。

### 6.4 与设置项的联动

- `hifiBitPerfect` 仅在 `hifiSupported == true` 时可切换；否则行显示禁用 + `hifiReason` 说明原因。
- `hifiOutputDevice` 列表来自 §6.3 的枚举结果。

---

## 7. 风险与注意事项

1. **期 2 等于自建解码 + 输出栈**：必须复刻现后端的焦点/锁/看门狗/缓存逻辑，否则会引入跳过歌曲、后台被杀等回归。
2. **API 34 bit-perfect 是可选能力**：厂商未声明 `AUDIO_OUTPUT_FLAG_BIT_PERFECT` 的机型上 `getSupportedMixerAttributes` 里就没有该项 → 必须优雅降级，绝不能假设可用。
3. **bit-perfect 与软件音量/淡入淡出互斥**：需明确旁路策略，避免出现"音量滑块无效"的困惑。
4. **换路径影响媒体会话同步**：`PlaybackService` 通过 `sharedController` 镜像状态，切换后端时位置/播放态要重新基线（现有 `setOnStarted` 就是为此）。
5. **不要引入 native**：本期路线刻意避开 JNI / UAC2 驱动。
6. **qml4j 布局约束**：`Layout.fillWidth` 仅对直接子级可靠，卡片内保持扁平，别用深层 anchors（见 §2.5）。

---

## 8. 明确不做（Out of scope）

- 不改桌面宿主：desktop 不显示 HiFi 标签、不实现 `HifiOutputControl`。
- 不写 native USB UAC2 驱动。
- 不改插件系统与插件权限（本期不向插件暴露音频 host API）。
- 不改变 `hifiEnabled=false` 时的任何现有行为。

---

## 附录 A：改动文件一览

| 文件 | 改动 |
|---|---|
| [SettingsCatalog.java](file:///workspace/qplayer/player-core/src/main/java/dev/t1m3/qplayer/settings/SettingsCatalog.java) | 新增 `HIFI` 分类 + 插入 `CATEGORIES`（插件↔关于）+ 4 个 key 常量 + 5 条 spec |
| [SettingsCore.java](file:///workspace/qplayer/player-core/src/main/java/dev/t1m3/qplayer/settings/SettingsCore.java) | `apply()` 与 `pushToController()` 增加 4 个 case |
| [PlayerController.java](file:///workspace/qplayer/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java) | 新增 10 个 Property + 5 个方法 |
| `audio/AudioOutputDevice.java`（新） | 设备项值对象 |
| `audio/AudioOutputStatus.java`（新） | 链路快照值对象 |
| `audio/HifiOutputControl.java`（新） | 可选能力接口 |
| [QPlayerActivity.java](file:///workspace/qplayer/android-shell/app/src/main/java/dev/t1m3/qplayer/android/ui/QPlayerActivity.java) | `registerInfo("hifiDeviceName")` / `registerAction("pickHifiDevice")` + 设备选择弹窗 |
| `playback/AndroidOutputBackend.java`（新） | 期 2：调度后端 |
| `playback/AndroidHiFiAudioBackend.java`（新） | 期 2：AudioTrack + MediaCodec 直通后端 |
| [AndroidAudioBackend.java](file:///workspace/qplayer/android-shell/app/src/main/java/dev/t1m3/qplayer/android/playback/AndroidAudioBackend.java) | 期 1：实现 `HifiOutputControl` 的**只读部分**（枚举/状态）；期 2：归入调度器 |
| [SettingsPage.qml](file:///workspace/qplayer/shared-qml/settings/SettingsPage.qml) | 新增 HiFi 链路显示卡片 |
| [zh_CN.properties](file:///workspace/qplayer/player-core/src/main/resources/lang/zh_CN.properties) / [en_US.properties](file:///workspace/qplayer/player-core/src/main/resources/lang/en_US.properties) | 新增 HiFi 文案 |

---

## 附录 B：链路对比（修改前 / 修改后）

```
修改前： 音源+控制器 → MediaPlayer → AudioFlinger(混音·重采样48kHz) → USB DAC(非独占·系统音量)
修改后： 音源+控制器 → AndroidOutputBackend → [HiFi] AudioTrack+MediaCodec
                                       → PreferredMixerAttributes(BIT_PERFECT) → USB DAC(源采样率·硬件音量)
```

变化集中在"输出后端 + 音频框架策略"两列，**音源与控制器不变**。

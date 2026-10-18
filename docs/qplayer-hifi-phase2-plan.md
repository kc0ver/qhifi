# QHIFI 第二轮修复方案（v1.8.5-hifi 候选）

第一版 v1.8.4-hifi 发布后的 5 项问题：4 个 bug + 预解码功能。
分析结论 + 实施设计。已按用户二次修订更新：**Bug 2 与 Bug 3 合并为"一律忽略音频焦点"**（别人播放任何内容都不影响我们，不做暂停/不恢复/无开关）；**Bug 4 回归核心实现**（不做插件）；预解码两阶段全做。

---

## Bug 1 软件音量刻度不合理（"0~200 塞进 0~100"）

### 根因（已核实）
整条软件音量链路是**纯线性增益**，无任何感知曲线：

- [uac_output.cpp L936-963](file:///c:/Users/23314/Desktop/qplayer-hifi/android-shell/app/src/main/cpp/uac_output.cpp#L936-L963)：`write()` 中 `value * gain` 线性乘法
- [uac_output.cpp L730-755](file:///c:/Users/23314/Desktop/qplayer-hifi/android-shell/app/src/main/cpp/uac_output.cpp#L730-L755)：`setSoftwareVolume()` 仅存 `softwareGain_ = volume`，不做任何映射
- [PlayerController.java L2539-2545](file:///c:/Users/23314/Desktop/qplayer-hifi/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L2539-L2545)：`backend.setVolume(userVolume * clampGain(gain))`，线性
- [Main.qml L890-948](file:///c:/Users/23314/Desktop/qplayer-hifi/shared-qml/Main.qml#L890-L948)：Slider 0..1 直通
- [QPlayerActivity.java L1270-1295](file:///c:/Users/23314/Desktop/qplayer-hifi/android-shell/app/src/main/java/dev/t1m3/qplayer/android/ui/QPlayerActivity.java#L1270-L1295)：音量键每按步进 1/16
- 全工程搜索 sqrt/pow/log/curve 在音量上下文：零命中

线性刻度下，人耳感知（dB 响度）与滑条位置严重不匹配：
滑条 10% = -20dB（已经很响）、50% = -6dB（接近满音量）。
舒适区全部挤在滑条底部 0~20%，稍往上就是"吵"——正是用户体感"0~200 塞进 0~100"。

### 方案
在**软件音量路径**加感知幂曲线：`effectiveGain = v^γ`，**已定 γ = 3**（dB = 60·log10(v)）：

| 滑条 | 旧增益 | 新增益 (γ=3) | 感知 |
|---|---|---|---|
| 10% | -20dB | -60dB | 极轻 |
| 30% | -10.5dB | -31dB | 轻柔 |
| 50% | -6dB | -18dB | 中等 |
| 70% | -3.1dB | -9.5dB | 响 |
| 100% | 0dB | 0dB | 不变，保住上限 |

- 实现位置：**Java 层**（UsbIsoSink 软件分支调用 `UacOutput.setSoftwareVolume(curve(volume))`），native 保持"增益=线性标量"语义，曲线集中一处便于调参和诊断换算
- **硬件音量模式不动**：其 0..1→dB 映射（floorDb..maxDb）本身已是感知刻度
- 非独占路径 AndroidAudioBackend.setVolume 应用同一条曲线（动工时核实其现状，保持两模式听感一致）
- PlayerController 的 fade gain 仍是线性乘子，叠乘不受影响
- HUD 百分比显示 userVolume 原值（用户刻度），不显示内部增益
- γ 做成常量便于听感调整（已定 3，如试听偏陡可回调 2）

### 验证
- 软件模式滑条 0/25/50/75/100 听感等距；音量键逐按响度变化均匀
- 100% 响度与旧版一致（上限不缩水）
- 硬件模式回归：DAC 电平写入/节流/收养不受影响

### 风险
- 现有音量档位肌肉记忆失效（预期内）
- γ=3 下音量键每按在中段约 2~3dB/按，低段偏粗，如试听不满意可再调键步进

---

## Bug 2 + Bug 3（合并）退后台即停 / 与其他媒体共存 → 一律忽略音频焦点

### 根因（已核实）
两件事其实是同一个机制：**我们在响应系统的 AUDIOFOCUS_LOSS**。
- Bug 2：ColorOS 退后台/灭屏发 LOSS（已证），独占后端 [AndroidDirectAudioBackend.onFocusChange L1151-1212](file:///c:/Users/23314/Desktop/qplayer-hifi/android-shell/app/src/main/java/dev/t1m3/qplayer/android/playback/AndroidDirectAudioBackend.java#L1151-L1212) 收到 LOSS → pause；[reassertFocusResume L1114-1149](file:///c:/Users/23314/Desktop/qplayer-hifi/android-shell/app/src/main/java/dev/t1m3/qplayer/android/playback/AndroidDirectAudioBackend.java#L1114-L1149) 只做一次 1.5s 重查，`requestFocus()` 被拒即 `return`，永不重试 → 永久暂停
- Bug 3：系统路径 [AndroidAudioBackend](file:///c:/Users/23314/Desktop/qplayer-hifi/android-shell/app/src/main/java/dev/t1m3/qplayer/android/playback/AndroidAudioBackend.java)（MediaPlayer）同样 LOSS → pause，且**没有任何恢复**（ColorOS 从不回发 GAIN）→ 其他媒体一响，我们的音乐永久停止
- 前台服务与 MediaSessionCompat 都已存在（[AndroidManifest.xml L88-116](file:///c:/Users/23314/Desktop/qplayer-hifi/android-shell/app/src/main/AndroidManifest.xml#L88-L116)、[QPlayerActivity L644-657](file:///c:/Users/23314/Desktop/qplayer-hifi/android-shell/app/src/main/java/dev/t1m3/qplayer/android/ui/QPlayerActivity.java#L644-L657)）→ 排除"无前台服务"这一假设

### 方案（已拍板：按事件类型区分，而不是一刀切）
**焦点分支规则（两后端一致）**
- `AUDIOFOCUS_LOSS`（其他媒体抢焦点）→ **no-op**：不暂停、不降音量、不记状态
- `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`（提示音 / 导航语音）→ **no-op**：不暂停、**也不降音量**
- `AUDIOFOCUS_LOSS_TRANSIENT`（来电）→ **暂停**，收到 `AUDIOFOCUS_GAIN` 后恢复（用户已确认：**来电不能无视**）
- `AUDIOFOCUS_GAIN` → 仅在"因 TRANSIENT 暂停过"时恢复播放

**删除与保留**
- **删除** 原 `reassertFocusResume` / `focusResumeCheck` 的通用重查逻辑（原 Bug 2 计划的"持续重获取循环"整体取消）
- 仅保留一个**来电专用兜底重查**：TRANSIENT 丢失后每 2s 探测一次、最多 15 次，且**仅当 `AudioManager.getMode()` 不是 `MODE_IN_CALL`/`MODE_IN_COMMUNICATION` 时才尝试恢复**——防 ColorOS 不回发 GAIN 时永久卡在暂停（若 GAIN 正常送达则第一优先，兜底不会被触发）
- 仍保留 `requestFocus()` 调用（维持前台服务优先级、避免系统更激进回收），只是结果只用来判定上述三类分支
- **不做设置开关**：规则就是唯一行为
- 保留 `focusdump` adb 诊断（与 volprobe 同模式），真机确认 ColorOS 实际发什么事件

### 验证
- 独占：播放中按 Home / 锁屏 / 切到其他 app → 音乐不掉
- 非独占：网易云同时播放 → 双方**同时出声**，我们既不被暂停也不被压低
- 通知提示音 / 导航语音不再打断或压低我们的音乐
- **来电 → 暂停；挂断 → 自动恢复播放**；通话中不会误恢复（`getMode()` 守门）
- 手动暂停 / 播放行为不变

### 风险
- 若某 OEM 在焦点协议之外强制静音/压低非焦点持有者（Android 焦点是协作式，理论上不会），需真机复核 → `focusdump` 日志可判定
- 忽略焦点后不再有 duck，通知音期间我们保持原音量（符合"不影响我"的要求）

---

## Bug 4 拔耳机 / 拔 DAC / 断蓝牙 不自动暂停（核心实现）

### 根因（已核实）
- 全工程 `BECOMING_NOISY`：**零命中**——从未监听耳机拔出；蓝牙断开本身也不走该广播，需要设备回调
- 独占模式拔 DAC 现状：[HifiAudioDispatcher.java L145-180](file:///c:/Users/23314/Desktop/qplayer-hifi/android-shell/app/src/main/java/dev/t1m3/qplayer/android/playback/HifiAudioDispatcher.java#L145-L180) `onAudioDevicesRemoved()` → fallback 到系统路径**继续播** → 音乐从手机扬声器外放（比不暂停更糟）

### 方案（已改：不走插件，直接做进核心）
该功能依赖核心的系统设备回调，插件沙箱拿不到（即便硬做成插件也要先改核心 ABI），插件化收益不足 —— **放弃插件路线**（不新增 ABI capability、不做 `.qplug` 包）。

- **单一数据源**：HifiAudioDispatcher 已有的 `AudioDeviceCallback` 能收到有线耳机 / USB / 蓝牙 A2DP 三类输出设备的增删（独占与系统两模式共用，独占模式下已验证可见）—— 在 removed 分支补暂停
- **判定**：被移除的是当前活动输出设备，且正在播放 → 执行用户级暂停（`desiredPlaying=false`）
- **独占拔 DAC**：由"fallback 继续播"改为"暂停"；**保留**"启动时独占打不开 → fallback 到系统"路径（那是启动场景，不是播放中拔线）
- 有线场景双保险：PlaybackService 顺带注册 `ACTION_AUDIO_BECOMING_NOISY`（几行，成本极低）
- 插回后由现有路由逻辑重新直出，用户点播放即恢复

### 验证
- 独占播放中拔 DAC → 立即暂停、扬声器无声；插回 → 点播放恢复直出
- 非独占：拔 3.5mm 耳机 / 断蓝牙耳机 → 暂停，不再扬声器外放
- 未播放状态下插拔 → 不触发任何动作

### 风险
- 蓝牙断开的回调时序在不同 ROM 上略有差异 → 三场景都真机验证一遍
- 若"拔线后继续外放"是某天想要的行为，再加设置项；当前不做

---

## 功能 预解码（切歌间隔）

### 现状（已核实）
- 切歌链路 [PlayerController.playAt() L4833-5005](file:///c:/Users/23314/Desktop/qplayer-hifi/player-core/src/main/java/dev/t1m3/qplayer/bridge/PlayerController.java#L4833-L5005)：pause/fade-out → 按来源解析+加载（在线无缓存走 `resolveAndPlayNetease()`，**同步**解析直链）→ `playBackend()`
- `preloadAdjacent()` L5255-5283 **已存在**，但只预取歌词和封面，无音频预取
- 已具备的地基：跨曲 sink 复用（同格式不重开）、800ms 环形缓冲、开流音量收养
- 间隔构成：fade-out + 在线直链解析 + 缓存文件打开/下载起播 + Extractor/Codec 冷启动 +（格式不同时）sink 重配

### 方案（已拍板：两阶段全做，尽量完整）

**插件化评估（已核实）**：插件是 JS 沙箱，无法触碰 MediaExtractor/MediaCodec——**预解码管线必须留在核心**，无法整体做成插件。插件能参与的是音源层：下一首的直链预解析天然走现有 `resolveStream` capability（插件侧零改动，核心只是提前调用）。结论：核心做完整两阶段，插件侧无需任何变化。

**阶段 1：预解析**
- 当前曲目起播时，后台预解析"下一首"（按 playMode 推演队列）：
  - 网易云：提前拿直链 URL、打开缓存文件（走现有 resolveStream/缓存机制，音源侧零改动）
  - 本地：校验/预打开路径
- 触发点挂在现有 `preloadAdjacent()`，与歌词/封面预取并列
- 时效性兜底：切歌时直链已过期 → 降级现解析（现有逻辑），预解析失败不阻塞切歌
- 队列变化/seek/模式切换 → generation++ 使预解析结果作废（openGen 模式复用）

**阶段 2：预解码（真 gapless）**
- AndroidDirectAudioBackend 增加可选能力 `prepareNext(source)`（后端接口 default no-op，桌面路径零影响）：
  - 独立 prepare 线程：MediaExtractor + MediaCodec 预热，预解码约 1.5s 进预载缓冲
  - 触发：当前曲写入位置距结束 <10s，或解码线程已见 EOS
  - 切换：原子换解码器，sink 不动（同格式）；歌词/封面/UI/scrobble 并行进行，不在音频关键路径
- **自然播完不淡出**（硬切，gapless 关键）；手动切歌保留淡出
- 格式不同的曲目：仍走现有最小化重开路径；重采样统一不做（超范围）

### 验证
- 本地同格式 FLAC 连播：听感无缝；日志切歌首字节 <50ms
- 在线已缓存曲连播：同上；未缓存曲：预解析命中时直链解析不在关键路径
- 随机/seek/换队列下不错曲（generation 校验）

### 风险
- 双 MediaCodec 内存峰值（音频 codec 轻量，可接受）
- 预载与暂停/seek 竞态 → generation 兜底
- 直链过期 → 现解析兜底

---

## 实施顺序与版本

建议顺序（由简到繁）：
1. Bug 1 音量曲线 γ=3（单点改动，可听感迭代）
2. Bug 2+3 音频焦点静默（两后端 onFocusChange 全部 no-op，删掉所有恢复逻辑——代码是**净减少**）
3. Bug 4 设备移除即暂停（核心实现：HifiAudioDispatcher 回调 + BECOMING_NOISY 双保险）
4. 预解码阶段 1（预解析）
5. 预解码阶段 2（预解码，最大件）

全部完成后整版发布 v1.8.5-hifi；每步后出 debug 包供真机验证。

## 已拍板决策（2026-10-17，二次修订）

1. **Bug 1**：曲线陡度 γ = 3
2. **Bug 2 + Bug 3 合并**：按事件类型区分——`LOSS` 与 `LOSS_TRANSIENT_CAN_DUCK` **一律忽略**（别人播放任何内容都不影响我们，不暂停、不降音量、不自动恢复）；`LOSS_TRANSIENT` **来电极暂停、挂断后恢复**（用户明确：来电不能无视）。**不做任何设置开关**；原"无视焦点开关"与"通用恢复循环"两套计划取消，仅保留来电专用兜底重查
3. **Bug 4**：拔耳机 / 拔 DAC / 断蓝牙 = 暂停；**不做插件**，直接核心实现（依赖核心设备回调，插件拿不到）
4. **预解码**：两阶段全做；解码管线核心承载（插件 JS 沙箱无法触碰 MediaCodec），音源预解析走现有 resolveStream，插件侧零改动

无遗留决策点。（已知边界：少数 app 用 `GAIN_TRANSIENT` 请求焦点会走 `LOSS_TRANSIENT` 分支而被暂停；若真机上遇到这类误暂停，再叠加通话态判定收紧。来电分支由 `getMode()` 守门，不会误恢复。）

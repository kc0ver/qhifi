<p align="center">
  <img src="https://raw.githubusercontent.com/kc0ver/qhifi/master/shared-qml/app-icon.png?v=20261123" width="96" alt="QHIFI">
</p>

<h1 align="center">QHIFI</h1>

<p align="center">
  <b>QPlayer 的 Android HiFi fork</b><br>
  以用户态 USB 独占输出为核心，保留 QPlayer 的 QML 界面、播放器与插件体系
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-8.0%2B-A4C639" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/ABI-arm64--v8a-blue" alt="arm64-v8a">
  <img src="https://img.shields.io/badge/UI-QML%20%2F%20Miuix-7C6CF0" alt="QML / Miuix">
  <img src="https://img.shields.io/badge/engine-qml4j-465BA6" alt="qml4j">
  <a href="LICENSE.md"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="Apache-2.0"></a>
</p>

***

## QHIFI 是什么

QHIFI 是 [QPlayer](https://github.com/TIMER-err/qplayer) 的社区 fork，面向 Android 上的
HiFi 播放场景。它沿用 QPlayer 的 `player-core`、QML/Miuix 界面和插件系统，同时把
**用户态 USB 独占输出**作为本 fork 的核心能力：应用直接接管 USB DAC，绕开 Android 的
AudioFlinger 与厂商 HAL，不再依赖系统混音链。

QHIFI 只发布 Android 安装包。仓库中的 `desktop-host` 保留用于共享 QML/`player-core`
的开发和验证，不是本 fork 的发行目标。

> QHIFI 是社区 fork，不是上游 QPlayer 的官方发行版。上游的功能与修复会定期合并；本 fork
> 额外维护 USB 独占、音量体系、胆味音染、音频链路读数、系统字体回落和 QHIFI 设置。

## 核心特性

### 用户态 USB 独占输出

- 通过 `UsbManager` 取得 USB 设备权限，在 native 层用 **usbfs** 直接 claim DAC 的
  UAC 接口，绕开 `AudioFlinger`、AudioTrack 和厂商 HAL。

- 直接解析 UAC2 描述符（兼容 UAC1），选择 alt-setting，提交等时传输 URB，并读取反馈
  端点做时钟 pacing；不依赖 libusb，也不需要 root。

- 独占流按音源采样率打开，不经过系统重采样；硬件音量模式下 PCM 保持满幅。关闭软件
  衰减和胆味时，目标是 bit-perfect 输出。

- 开启独占（或启动时已开启）后立即抢占 DAC，不等首曲：音量键在播放前就能作用到真实
  设备；如果框架路径正在播放，会在当前位置移交给独占流。关闭独占后，空闲的抢占会释放。

- 独占不可用或未授权时回退到系统音频路径；独占播放期间拔掉 DAC 会**暂停播放**，不会
  把 HiFi 流切到扬声器继续播。

### 音量体系

独占路线和系统框架路线各自保存音量，互不继承：

- **硬件音量模式**：应用直接写 DAC 的 UAC 音量控制（UAC1/UAC2 各自实现），PCM 保持
  满幅；DAC 机身按钮与应用会互相覆盖。

- **软件音量模式**：完全不写 DAC 硬件音量，DAC 机身按钮作为前级，应用在其下做数字
  衰减；没有硬件音量控制的 DAC 会自动走软件模式。

- 软件音量可选**线性**或**幂曲线**（默认 γ=3，可在 1.0–6.0 调整）；硬件音量本身是
  dB 线性映射，不受曲线影响。

- 硬件/软件两种模式各自记忆独立电平（默认 30%），切换模式时恢复各自上次的值；从旧
  版本升级会沿用原有独占音量。

- 独占模式下显示音量 HUD，音量键由应用接管并直接调整独占流；系统框架路径继续使用
  上游的原生音量语义。

### 胆味音染（可选，仅独占路径）

在 USB 独占流送进 DAC 之前插入一段可选的电子管风格染色：

- 三种管型预设：**300B**（甜厚）、**EL34**（通透）、**6L6**（均衡）；强度 0–100
  （默认 55），可单独关闭频响倾斜。

- 采用 2 倍过采样 + 二次/三次谐波 + 包络调制 + 软限幅 + 20Hz 高通 + 两级倾斜；
  16-bit 写回前加 TPDF dither。

- 默认关闭。关闭时不进入处理函数，输出与原来一致；开启后歌词页的音频信息浮层会明确
  显示「胆味 · 300B · 55%」，不会把染色后的链路标成 bit-perfect。

- 可选的缓慢漂移（“呼吸”）默认关闭，保证效果可复现、可回归测试。

### 音频链路读数

设置页 **QHIFI** tab 的「音频链路」卡片会显示当前链路实际在做什么：

- 请求模式与生效模式（独占不可用时会标出回落）、独占状态、音源格式、输出设备、
  输出格式、是否重采样、位完美能力（已生效 / 可用 / 不支持）、USB 音频设备是否在位，
  以及仅独占流才显示的 UAC 版本。

- 不支持独占时会显示具体原因。

- 设备和链路都有手动刷新入口；插拔 DAC 或刚授权后不必重启应用。

歌词页的音频信息浮层另外显示音源、音源格式、输出格式、音色、独占状态和输出设备；
胆味开启时音色行显示管型、强度和「非 bit-perfect」，关闭时显示「直通 · bit-perfect」。

### 播放、界面与歌词（继承上游）

- 本地媒体库、播放队列、列表循环 / 随机 / 单曲循环，前台 `MediaSession` 接管锁屏、
  通知栏和蓝牙控制；播放队列是可拖拽排序的浮层。

- 支持本地播放列表、最近播放、睡眠定时、搜索和已下载歌曲。

- 歌曲信息与歌词在后台同步更新，返回前台后保留当前曲目状态；Activity 重建时保留
  QML 场景，不会重新加载界面。

- 沉浸歌词页的正文由宿主通过 Skija 直接绘制：逐字滚动、流体背景、mesh 渐变、翻译/
  罗马音；页面控件仍由 QML 渲染。歌词页和凝彩页支持播放时常亮。

- 安卓端原生对接词幕（Lyricon）：状态栏悬浮歌词支持锁定、转屏重定位，逐字歌词、
  翻译、罗马音与播放进度由播放服务同步。

- 支持歌曲下载：在菜单里为当前或选中歌曲选择音质（标准 / 较高 / 极高 / 无损 /
  Hi-Res），通过系统保存对话框写成 mp3/flac/m4a/ogg；插件下载复用音源的
  `resolveStream` 路径。

- Miuix QML 界面运行在 [qml4j](https://github.com/TIMER-err/qml4j) 上：统一的设计
  令牌、窄屏底部导航、宽屏侧栏，手机横屏和平板共用同一套响应式布局；界面缩放
  80%–150%。

- 支持简体中文和英文、深浅色主题、封面取色和统一动效。

- 整套界面使用 Miuix QML（`miuix.Core`），随包 QML 组件在构建期编译，运行时只装载
  预编译类，不再执行运行时编译。

### 插件与在线音源

QHIFI 核心不包含在线音源端点，也不分发音源实现。在线能力通过 `.qplug` 插件提供：

- 插件在隔离的 Rhino Realm 中运行，按清单声明能力与权限；

- 内置插件目录固定发布者公钥，只接受对应签名包，版本从各项目的 GitHub Releases 解析；

- 插件不能提供 QML，只能返回声明式对话框描述，由宿主渲染；

- 音质开关保持上游的全局 `exhigh` / `standard` 语义，所有音源插件共用。

插件页可以安装网易云音乐、QQ 音乐、酷我、咪咕、酷狗、汽水等第三方音源插件，以及
用于导出下载文件的工具插件。插件 ABI、权限、签名和凭据模型见
[插件开发文档](docs/plugins.md) 与 [插件安全模型](docs/plugin-security.md)。

### QHIFI 专有设置

设置页的 **QHIFI** tab 集中放置本 fork 的额外设置：

- USB 独占输出开关、输出设备选择、采样率跟随、音量模式、软件音量曲线与 γ 值；

- 胆味（电子管音色）：总开关、管型、强度、频响倾斜、缓慢漂移；

- 后台保活音轨（实验，默认关闭）；

- 音频链路读数卡片。

外观设置里还有本 fork 的字体差异：不随包内置 PingFang 字体，默认使用系统 CJK 字体；
字体选择器保留导入本地字体文件的入口，没有内置字体时不会显示无效选项。

## 与上游 QPlayer 的关系

QHIFI 跟随上游 QPlayer 的版本号，版本格式为 `<上游版本>-hifi`，对应 tag 为
`v<上游版本>-hifi`。`master` 保留上游历史与真实的合并基，便于定期
`git merge upstream/master`；不要 rebase、重排或改写历史。

上游新增的播放器功能、歌词、界面、插件和桌面修复会随合并进入本仓库；但本 fork 不发布
桌面安装包，也不承诺桌面端行为与上游发行版一致。

## 安装

### 要求

- Android 8.0 及以上（`minSdk 26`）；

- `arm64-v8a` 设备；

- 使用 USB 独占时，需要一台可通过 USB OTG 连接的 UAC DAC。

### 下载与安装

1. 从 [Releases](https://github.com/kc0ver/qhifi/releases) 下载最新的
   `QHIFI-v<版本>.apk`，例如 `QHIFI-v2.3.1-hifi.apk`。
2. 允许未知来源安装并安装 APK。
3. 首次启动时授予通知、媒体读取等必要权限；连接 USB DAC 时按系统弹窗授予 USB 访问权。

QHIFI 与上游 QPlayer 的包名和签名不同，不能直接覆盖安装上游版本，也没有自动数据迁移。
如果要同时安装多个构建：QHIFI 的 debug 变体使用 `.debug` 后缀，perf 使用 `.perf` 后缀，
release 使用正式包名，三者可以并存。

### 更新

同一签名的 QHIFI Release 可以直接覆盖安装，播放列表、设置和账号凭据会保留。应用内
更新会校验 Release 提供的 SHA-256（如果有），并复用本次进程内已下载且完整的安装包。

## 使用指南

### 本地播放

首次启动后授予媒体权限，应用会扫描设备音乐目录；也可以在设置中调整音乐文件夹。支持
本地播放列表、最近播放、队列和搜索。

### 在线音源

QHIFI 不内置在线音源。安装 `.qplug` 插件并登录后，推荐、歌单、搜索、喜欢等页面会按
插件声明的能力出现。未安装插件时，它仍然是一个完整的本地播放器。

### USB 独占

1. 用 OTG 连接 USB DAC。
2. 在系统弹窗中允许 QHIFI 访问该 USB 设备。
3. 打开 **设置 → QHIFI → USB 独占输出**。
4. 如需指定设备，在“输出设备”里选择 DAC；默认自动选择可用的 USB 音频设备。

开启后 QHIFI 会立即接管 DAC，音量键直接调整独占流。独占模式下，建议在系统设置里为
QHIFI 关闭电池优化，避免部分 ROM 在后台压制进程。若 ROM 仍然把后台的本应用当作
“未在放音”，可以打开“后台保活音轨（实验）”。

独占路径完全绕开 AudioFlinger，系统音量条不会反映这条流的电平；请以音量 HUD 或应用
内控件为准。

### 音量模式的选择

- 想让 DAC 的硬件音量控制由应用接管、PCM 保持满幅：选**硬件音量**。

- 想完全不动 DAC 硬件音量、把机身按钮作为前级：选**软件音量**。

两种模式各自保存电平；从一种切到另一种时，听到的响度可能变化，这是两条路线独立保存
的结果。没有硬件音量控制的 DAC 会自动使用软件音量。

### 胆味音染

胆味只作用于 USB 独占路径，默认关闭：

1. 先打开 **USB 独占输出**。
2. 在 **QHIFI** tab 打开「胆味（电子管音色）」。
3. 选择管型（300B / EL34 / 6L6），再调强度；55% 左右是日常听感的甜点。
4. 需要更“活”的听感时可以打开缓慢漂移；想要可复现的对比就保持关闭。

开启后歌词页的音频信息浮层会显示当前是「胆味 · 管型 · 强度」，明确标记非 bit-perfect。
关闭胆味后回到直通处理，输出不受影响。

## 开发

构建、测试、发版、桌面宿主、上游合并和项目结构见 [开发指南](docs/development.md)。

## 常见问题

**为什么我的 DAC 不出声或声音不正常？**
现阶段 QHIFI 在水月雨破晓2（DAWN PRO2，UAC 2.0）和水月雨星野3附赠的 Echo-BP
（UAC 1.0）上实际测试通过，不保证支持所有设备。UAC 1.0 与 UAC 2.0 的采样率和音量
控制机制不同，应用会按当前 DAC 自动走对应流程；如果独占打不开，先确认音源采样率在
DAC 支持范围内（Echo-BP 最高 96kHz/24bit）。

**链路里的“UAC 版本”是什么意思？**
USB DAC 分 UAC 1.0 与 UAC 2.0 两代音频类，采样率协商、时钟与音量控制的实现都不同。
QHIFI 会显示当前独占流实际使用的版本；没有打开独占流（系统框架输出）时不显示。

**为什么开启独占后，DAC 在没播放时也被占用？**
这是刻意的：开启独占就把 DAC 交给应用，音量键和音量控件在播放前就能作用到真实设备。
关闭独占后，空闲占用会释放。

**为什么独占模式下系统音量条不跟着动？**
独占流不经过系统混音，音量由应用接管。QHIFI 拦截音量键并直接调整独占流（硬件音量
或软件衰减），系统音量条不反映这条流；请以音量 HUD 或应用内控件为准。

**为什么开启独占后，切回系统音频时音量不一样？**
独占与框架两条路线各自保存电平，软件音量的默认值和曲线也不同；这是为了避免同一条
曲线跨路线产生响度跳变。

**为什么拔掉 USB DAC 后没有切到扬声器？**
独占模式下这是刻意行为：拔线即暂停，避免把 HiFi 流意外切到扬声器继续播放。

**为什么开启胆味后音频信息浮层显示“非 bit-perfect”？**
胆味会在样本域加入谐波和频响倾斜，输出已经不是原始信号。默认关闭；关闭后回到直通
处理，硬件音量满幅时仍是 bit-perfect 目标。

**为什么息屏或后台会无声？**
部分 ROM 会压制绕开音频框架的后台进程。先关闭 QHIFI 的电池优化；仍无效时可尝试
“后台保活音轨（实验）”。

**为什么界面用的是系统字体？**
本构建不随包内置 PingFang，默认回落到系统 CJK 字体，避免额外体积和无用的内置选项；
需要其他字体时可在字体选择器里导入本地字体文件。

**为什么更新后第一次启动不再出现“正在编译界面组件”？**
QML 已改为构建期预编译，APK 内只有预编译类和场景清单；启动时不再执行运行时编译。

**为什么不能直接覆盖安装上游 QPlayer？**
包名和签名都不同（`com.kc0ver.qhifi` 对 `dev.t1m3.qplayer`），需要卸载或并存安装；
本 fork 不提供自动数据迁移。

**为什么没有桌面版或 iOS 版？**
QHIFI 的目标是 Android 上的 USB 独占 HiFi 输出；桌面宿主只保留用于开发共享界面，
不发布。iOS 没有对应的 USB 独占路径。

**在线音源为什么是空的？**
核心不内置音源。安装 `.qplug` 插件并登录后，相关页面才会出现。

## 贡献

- 代码、文档和问题反馈欢迎提交到 [kc0ver/qhifi](https://github.com/kc0ver/qhifi)。

- 构建、测试、发版和上游合并流程见 [开发指南](docs/development.md)。

- 插件相关改动请先阅读 [插件 ABI](docs/plugins.md) 和
  [安全模型](docs/plugin-security.md)。

## 致谢

- [QPlayer](https://github.com/TIMER-err/qplayer) —— 本 fork 的上游播放器与共享核心。

- [qml4j](https://github.com/TIMER-err/qml4j) —— 纯 Java QML 引擎，驱动整套界面。

- [Skija](https://github.com/HumbleUI/Skija) —— JVM 上的 Skia 绑定，界面与歌词渲染。

- [miuix-qml](https://github.com/TIMER-err/miuix-qml) —— Miuix QML 组件库。

- [SPlayer](https://github.com/imsyy/SPlayer)、
  [AMLL](https://github.com/amll-dev/amll-player) —— 流体歌词与歌词动效的参考。

- [Lyricon](https://github.com/proify/lyricon) —— 状态栏歌词（词幕）对接。

- [swingwebview](https://github.com/webliteca/swingwebview) —— 桌面端网页登录的原生
  WebView 后端。

- 图标使用 Material Symbols Rounded。

## 许可证与声明

本项目使用 [Apache License 2.0](LICENSE.md)。`shared-qml/miuix` 中的组件库带有自己的
版本与许可说明，见 `shared-qml/miuix/`。

QHIFI 不提供在线音源，也不分发受版权保护的媒体。插件作者与用户需自行遵守服务条款和
当地法律。USB 独占会改变系统音频路由，使用前请确认设备和系统版本兼容；由此产生的
设备或听力风险由使用者自行承担。

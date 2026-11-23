# QHIFI 开发指南

本指南面向构建、测试、发版和维护 QHIFI 的开发者。面向用户的安装与使用说明见
[README](../README.md)。

## 环境

- JDK 21；

- Maven 3.9+；

- Android SDK（compileSdk 34）与 NDK `28.2.13676358`；

- Gradle wrapper 随仓库提供；

- Windows、macOS 或 Linux 均可构建 Android；本仓库的 CI 使用 Ubuntu。

## 项目结构

| 路径                                | 说明                                                                                              |
| --------------------------------- | ----------------------------------------------------------------------------------------------- |
| `player-core/`                    | 跨平台核心（Maven）：`PlayerController`、播放/队列、插件 ABI 与沙箱、歌词解析、胆味 DSP、资源与磁盘缓存、预编译 QML 场景装载。**不含在线音源端点**。 |
| `shared-qml/`                     | 共享 QML：`Main.qml`、页面、组件、`miuix.Core`、设计令牌。Android 与桌面开发宿主加载同一份。                                 |
| `qml-aot-tool/`                   | 仅构建期使用的 QML 编译器：生成组件类、场景清单与反射配置，不进入 APK。                                                        |
| `android-shell/`                  | Android 应用（Gradle）：`applicationId com.kc0ver.qhifi`，宿主集成、USB 权限与前台服务。                           |
| `android-shell/app/src/main/cpp/` | 用户态 UAC 驱动：usbfs claim、UAC2 描述符解析、等时 URB、反馈端点。                                                  |
| `desktop-host/`                   | 桌面开发宿主（Maven + LWJGL/GLFW + Skija）；QHIFI 不发布桌面版。                                                |
| `docs/`                           | 插件 ABI、安全模型、Miuix UI、胆味音染、QHIFI 待办。                                                             |
| `.github/`                        | Android-only Release 工作流、Release 正文模板。                                                          |

相关文档：

- [插件 ABI](plugins.md)

- [插件安全模型](plugin-security.md)

- [Miuix 界面与设计令牌](miuix-ui.md)

- [胆味音染设计与参数](tube-tone-plan.md)

- [QHIFI 待办与未完成项](qplayer-hifi-phase2-plan.md)

## Android Debug

```sh
# 仓库根目录：安装父 POM 与共享模块到 Maven Local
mvn -B -N install -DskipTests
mvn -B -DskipTests install -f player-core/pom.xml

# 运行 Android 单元测试并构建 debug APK
cd android-shell
./gradlew :app:testDebugUnitTest :app:assembleDebug
# Windows: .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug

# 性能排查使用独立的 perf 包（复用 release 构建配置，debug 签名）
./gradlew :app:assemblePerf
# Windows: .\gradlew.bat :app:assemblePerf
```

debug 产物：`android-shell/app/build/outputs/apk/debug/app-debug.apk`；perf 产物：
`android-shell/app/build/outputs/apk/perf/app-perf.apk`。

只有 `perf` 变体适合做帧时间对比，debug 太慢。首次安装或更新后，等待
`qplayer.lifecycle` 输出 `ready activity=...`，确认权限和插件提示已关闭，再比较相同
页面与滚动操作。Gradle 在构建主机编译全部随包 QML，再由常规 D8 步骤生成 DEX；应用内
不再运行 D8，也没有动态编译回退。

## Android Release

Release 签名通过环境变量提供；没有 keystore 时 Gradle 会产出未签名 APK：

```sh
export QPLAYER_KEYSTORE=/path/to/qplayer.jks
export QPLAYER_STORE_PASSWORD=...
export QPLAYER_KEY_ALIAS=...
export QPLAYER_KEY_PASSWORD=...

cd android-shell
./gradlew :app:assembleRelease
```

产物：`android-shell/app/build/outputs/apk/release/app-release.apk`。CI 会把它重命名为
`QHIFI-<tag>.apk` 并附到 Release；本地构建不会自动改名。

不要把 keystore、密码或 `local.properties` 提交到仓库。

## 测试

```sh
# 核心 JVM 测试
mvn -B -pl player-core test

# Android 单元测试（引擎、歌词回退、保存请求等）
cd android-shell && ./gradlew :app:testDebugUnitTest

# 桌面宿主测试（可选；桌面端不发布）
mvn -B -pl desktop-host -am test
```

`android-shell` 没有 `androidTest`；USB 独占、拔线、后台保活和在线源真实播放必须在
真机上验证。模拟器无法加载 arm64-only 原生库，也没有真实 USB 总线。

## 桌面宿主（仅开发）

桌面端用于共享 QML/`player-core` 的本地验证，QHIFI **不发布**桌面安装包：

```sh
mvn -B -pl desktop-host -am install
mvn -pl desktop-host exec:exec
```

> 关闭按钮为最小化到托盘（渲染线程销毁，音频继续播放），从托盘「退出」才会真正退出。
> macOS 的 JVM 开发运行需加 `-XstartOnFirstThread`。需要 GraalVM 原生分发包时参见
> [桌面打包说明](../desktop-host/dist/README.md)；本仓库不发布桌面产物。

## 测试与已知边界

- **只发布 Android arm64**：原生 USB 驱动与 Skija 只编译 `arm64-v8a`；x86\_64 模拟器
  无法运行。

- **USB 独占需要真机**：模拟器没有真实 USB 总线，独占路径无法打开；插拔、后台和 DAC
  兼容性也必须在真机验证。

- **没有 androidTest**：自动化测试覆盖 `player-core` 与 `android-shell` 的 JVM 单元
  测试；独占音频、在线源和 ROM 后台行为只能人工验证。

- **桌面端不发布**：`desktop-host` 只用于共享 UI/逻辑的开发验证。

- **在线音源依赖插件**：核心不提供任何在线音源端点；插件签名、权限和可用性由插件侧
  决定。

- **QML 在构建期编译**：修改 QML 后必须重新构建 APK，运行时没有源码回退。

- **胆味不是 bit-perfect**：它是刻意的染色，默认关闭；开启时音频信息浮层会明确标注。

- **部分 ROM 会压制后台应用**：独占输出绕开 AudioFlinger 后，系统可能把应用视为
  “未在放音”；可尝试关闭电池优化或开启后台保活音轨。

## 发版

1. 更新 `android-shell/app/build.gradle.kts` 的 `versionCode`（+1）和 `versionName`；
   `versionName` 保持 `<上游版本>-hifi`。
2. 更新 `.github/release-notes.md`：Release 的正文由该文件提供，包含 QHIFI 额外改动和
   本次合并的上游改动。
3. 提交并推送 `master`，然后创建 annotated tag：

```sh
git tag -a v<versionName> -m "QHIFI v<versionName>"
git push origin refs/tags/v<versionName>
```

例如 `versionName=2.3.1-hifi` 时，tag 是 `v2.3.1-hifi`。tag 必须与 `versionName`
严格一致，否则应用内更新检查和 CI 资产会对不上。

> 不要执行 `git push --tags`。仓库里同时存在上游的 `v2.3.1` 等 tag，整批推送可能触发
> 非 QHIFI 的 Release；只推送本次的 `v*-hifi` tag。

`release.yml` 只保留 Android job，构建时会运行 `:app:testDebugUnitTest`。CI 依赖四个
仓库 secret：`QPLAYER_KEYSTORE_BASE64`、`QPLAYER_STORE_PASSWORD`、
`QPLAYER_KEY_ALIAS`、`QPLAYER_KEY_PASSWORD`。签名密钥丢失后无法以同一签名更新已发布
版本。

## 合并上游

上游仓库：<https://github.com/TIMER-err/qplayer>。合并时保留真实合并基，不要 rebase：

```sh
git remote add upstream https://github.com/TIMER-err/qplayer.git   # 只需一次
git fetch --tags upstream
git merge upstream/master
```

合并后至少重新构建核心与 Android，并确认 QML/native 改动没有被静默重复或覆盖：

```sh
mvn -B -N install -DskipTests
mvn -B -DskipTests install -f player-core/pom.xml
mvn -B -pl desktop-host -am package -DskipTests
cd android-shell && ./gradlew :app:compileDebugJavaWithJavac  # 或 assembleDebug
```

# qml4j 启动编译分析

> 目标：解释 QHIFI 为什么每次启动都出现「正在编译界面组件」，以及 qml4j 是否已经提供了消除它的机制。
>
> 结论先行：**这不是 qml4j 的缺陷，而是 Android 宿主没有启用 qml4j 的持久化场景缓存。** qml4j 0.2.33 已提供 `QmlView.compilationCache(...)` + `CompiledSceneCache` 接口，QHIFI 目前只做了 D8 dex 缓存，QML 的 parse + ASM 编译仍会在每次进程/Activity 重建时重跑。补一个磁盘版 `CompiledSceneCache` 即可，不需要 fork qml4j。
>
> 补充：上游 QPlayer master 的**桌面端已经接入，并有对应单元测试**；Android 侧只有 `upstream/dev` 的未合入实现，且 key 固定、失效逻辑有缺陷（详见 §10）。

---

## 1. qml4j 是什么

qml4j 是纯 Java 的 QML 引擎，没有 Qt/C++，也没有离线代码生成步骤：

```text
.qml
  → ANTLR 解析（Qml4j.parse）
  → AST
  → QmlCompiler（ASM 生成 JVM 字节码）
  → ClassLoaderBackend.defineClasses()
  → Item / QObject 对象树
  → 绑定与事件处理交给内嵌 Rhino 执行
  → Skija 绘制
```

关键事实（对应 [README](https://github.com/TIMER-err/qml4j/blob/v0.2.33/README.md) 与源码）：

- **整个 QML 文档在运行时 JIT 编译**，每个对象生成一个 `Component$N` 类；一个 QML 文件最终会变成一个构造方法很大的类。
- **绑定不是字节码**：绑定和 handler 的源码被捕获后交给 Rhino；`RhinoBinding` 在构造时调用 `JsRuntime.compile`，所以 JS 是在对象树实例化阶段编译的，不在 QML 编译阶段。
- **Android 不能直接 define 原始 `.class`**：宿主必须提供 `ClassLoaderBackend`；qml4j 自带的 android-shell 用 D8 → DEX → `InMemoryDexClassLoader`，但该模块已被上游标记为 frozen，QHIFI 自己维护了一份 Android 集成。
- **默认没有持久化缓存**：`QmlEngine` / `Loader` / `QmlCompiler` 都是进程内对象，进程结束即丢失。是否缓存、缓存到哪里，由宿主决定。

## 2. QHIFI 当前的启动链路

Android 端的实际路径：

1. [QPlayerActivity.java](file:///c:/Users/23314/Desktop/qplayer-hifi/android-shell/app/src/main/java/dev/t1m3/qplayer/android/ui/QPlayerActivity.java#L383-L388) 创建 `getCacheDir()/qml-dex`，构造 `DexClassLoaderBackend(parent, 26, dexCache)`，再创建 `QmlEngine`。
2. [QmlGLSurfaceView.java](file:///c:/Users/23314/Desktop/qplayer-hifi/android-shell/app/src/main/java/io/github/timer_err/qml4j/android/QmlGLSurfaceView.java#L573-L628) 在 GL 线程的 `onSurfaceChanged` 里：
   - `QmlView.withStockTypes(engine).resources(resources)`；
   - 注册 `player` / `settings` / `i18n` / `hostWindow` 等 context；
   - 注册编译进度回调（驱动启动页的「正在编译界面组件… N」）；
   - 最后 `view.load(qmlSource)`。
3. `QmlView.load` → `Loader.instantiateInitial`：
   - **没有配置 `compilationCache` 时**，每次都走 `compileRoot`：解析 `Main.qml`，递归解析/编译所有被引用的 QML 类型，再逐个 `defineClasses`。
   - **配置了 `compilationCache` 时**，首次 `load` 先查缓存；命中则直接 restore 生成类，不解析 QML。

QHIFI 当前的 [DexClassLoaderBackend.java](file:///c:/Users/23314/Desktop/qplayer-hifi/android-shell/app/src/main/java/io/github/timer_err/qml4j/android/DexClassLoaderBackend.java#L41-L65) 已经做了两件事：

- 用 SHA-256（类字节码 + minApi）命名缓存 `.dex`，第二次启动跳过 D8；
- API 27+ 把缓存目录里的所有 dex 合并进一个 `InMemoryDexClassLoader`，避免 100 多层 parent 链。

这两点只解决 **D8 dexing**，不解决 **QML 编译**。`defineClasses` 仍然需要宿主先给出字节码，而字节码只能由 `QmlCompiler` 现编出来。所以用户看到的「每次启动都在编译」是真实存在的：D8 缓存命中了，但 parse + ASM 每次都重跑。

## 3. 编译规模：QHIFI 的 QML 包有多大

对当前 `shared-qml` 的统计：

| 指标 | 数值 |
|---|---:|
| `.qml` 文件数 | 142 |
| QML 源码总量 | 970,783 B（约 948 KiB） |
| 最大单文件 | `Main.qml` 43,497 B |
| 生成的 JVM 类 | 317 |
| 生成类字节码总量 | 1,551,025 B（约 1.48 MiB） |
| 被记录的 imported type | 114 |
| singleton | 1 |

其中 `Main.qml` 静态 `import` 了 `components`、`dialogs`、`pages`、`settings`、`miuix.Core`，并通过 `Component { XxxPage {} }` 引用页面，因此 `Loader` 在首次编译时会把大量页面/对话框一并解析、编译。启动页的进度计数正是来自这些 compound type 的逐个编译（`resolveCompound` → `compileAndDefine`）。

## 4. 实测：冷编译 vs 持久化场景恢复

测量方法：在临时 Maven 工程中使用 qml4j-core 0.2.33 + `JvmClassLoaderBackend`，以 QHIFI 的 `shared-qml/Main.qml` 为入口，注册 dummy `player` / `settings` / `i18n` / `hostWindow` context，统计编译、`defineClasses`、完整 load 的耗时。数值是桌面 JVM（Windows x86_64）量级，不是 Android 真机数据，但足以判断成本结构。

| 场景 | 总 load | 其中 QML 编译/缓存写入 | `defineClasses` | 说明 |
|---|---:|---:|---:|---|
| 冷启动，JVM 首次运行 | 2,958 ms | **1,574 ms** | 34 ms / 115 次调用 | 最接近「新进程首次编译」；317 类 |
| 冷启动，同一 JVM 第二次 | 1,544 ms | **809 ms** | 29 ms / 115 次调用 | 编译器本身已被 JIT 预热 |
| 场景缓存命中 | 815 ms | **0（未 parse）** | 11 ms / 1 次调用 | 不再读取任何 QML 资源 |

解读：

- **QML 编译本身约 0.8～1.6 秒**（桌面）；Android 的 CPU 更慢，且冷启动没有 JVM JIT 预热，实际只会更长。
- 冷路径的 `defineClasses` 被调用 **115 次**（根文档 + 114 个 imported type），在 Android 上每次都可能触发一次 D8；缓存未命中时这是叠加成本。场景缓存命中后合并为 **1 次**，317 个类一次定义。
- **缓存命中并不等于瞬时启动**：剩余约 0.8 秒是对象树实例化、绑定创建和 Rhino 编译 JS 的成本，这部分与缓存无关。
- 场景缓存命中时 `resources` 一次都没有被调用 —— 这证明 QML 源码没有被解析，而不是「解析得很快」。

> 注意：上面的「缓存命中」仍然使用桌面 `JvmClassLoaderBackend`。Android 侧还要走 `loadFromCombined` / ART 类加载，实际 restore 时间会更高，但通常仍远小于重新编译 142 个 QML 文件。

## 5. qml4j 已经给出的解法

### 5.1 API

[qml4j `QmlView`](https://github.com/TIMER-err/qml4j/blob/v0.2.33/qml4j-core/src/main/java/io/github/timer_err/qml4j/render/QmlView.java) 提供：

```java
public QmlView compilationCache(CompiledSceneCache cache, String key)
```

[qml4j `CompiledSceneCache`](https://github.com/TIMER-err/qml4j/blob/v0.2.33/qml4j-core/src/main/java/io/github/timer_err/qml4j/compiler/CompiledSceneCache.java)：

```java
public interface CompiledSceneCache {
    CompiledScene load(String key);
    void store(String key, CompiledScene scene);
}
```

[`CompiledScene`](https://github.com/TIMER-err/qml4j/blob/v0.2.33/qml4j-core/src/main/java/io/github/timer_err/qml4j/compiler/CompiledScene.java) 保存的是完整场景：

- `rootClassName`
- `classes`：所有生成类的 JVM 字节码
- `importedTypes`：文件路径 → 类名
- `singletons`：前缀 → 类型名 → 类名
- `jsImports`：JS 模块别名/路径
- `FORMAT_VERSION = 1`

它由 [`CompilationRecorder`](https://github.com/TIMER-err/qml4j/blob/v0.2.33/qml4j-core/src/main/java/io/github/timer_err/qml4j/render/CompilationRecorder.java) 在首次编译时记录，[`Loader.instantiateInitial`](https://github.com/TIMER-err/qml4j/blob/v0.2.33/qml4j-core/src/main/java/io/github/timer_err/qml4j/render/Loader.java) 只在**每个 Loader 的第一次 load** 查缓存；命中后 `restore` 一次性 `defineClasses(scene.classes())`，不解析 `qmldir`、不重新解析 QML。唯一例外是 `jsImports`：如果场景里有 `import "...js" as X`，restore 会重新读取并执行这些 JS 文件；QHIFI 当前没有 JS import，所以不触发资源读取。

### 5.2 已发布版本

- 持久化场景缓存由 commit `db73897`（2026-08-28，"feat: add persistent compiled scene cache"）引入。
- 包含该提交的 release：`v0.2.31`、`v0.2.32`、`v0.2.33`。
- QHIFI 当前使用的 **0.2.33** 已包含该 API（已用 `javap` 对本地 Maven jar 验证），**无需升级**。
- 上游没有内置磁盘实现：缓存键必须由宿主提供，因为只有宿主知道自己的完整资源/context/qml4j 版本。上游测试 [`CompiledSceneCacheTest`](https://github.com/TIMER-err/qml4j/blob/v0.2.33/qml4j-core/src/test/java/io/github/timer_err/qml4j/render/CompiledSceneCacheTest.java) 专门验证了 warm load 不读取资源、仍能恢复 singleton 和 JS import。

### 5.3 覆盖范围与边界

**能覆盖：**

- `Main.qml` 静态 import 的所有 QML 类型；
- `Component { Page {} }` 里的页面（编译期就会解析这些类型）；
- `miuix.Core` 通过 `qmldir` 提供的 compound type 和 singleton；
- `Loader.source: "miuix/Core/Menu.qml"` 这种自引用：`Loader.createFromSource` 会先查 `importedTypes`，命中后复用已恢复的类。

**不能覆盖：**

- 对象树实例化本身（新进程必须重新构造 Item/Property）；
- Rhino 对每个绑定源码的编译（`RhinoBinding` 构造时调用 `JsRuntime.compile`，`ScriptCache` 只在当前 ClassLoader 内有效，不持久化）；
- 运行时才第一次出现的、未在静态 import 图中的 QML 文件；
- 同一 `QmlView` 第二次 `load()`（`Loader.initialLoad` 已置 false，不再查缓存）；
- 插件 UI（QHIFI 的插件是声明式 `PluginDialogNode`，本来就不是 QML）。

## 6. 推荐落地方案：磁盘版 `CompiledSceneCache`

这是改动最小、风险最低、且不需要碰 qml4j 依赖的方案。

### 6.1 新增组件

在 `android-shell` 内新增一个纯 Java 的 `FileCompiledSceneCache implements CompiledSceneCache`（放在 `io.github.timer_err.qml4j.android` 或 `dev.t1m3.qplayer.android.app` 均可，建议与 `DexClassLoaderBackend` 同包）：

- 文件格式：magic + 序列化版本 + cache key + `rootClassName` + `classes` + `importedTypes` + `singletons` + `jsImports`；
- 写入：先写 `.tmp`，flush 后原子 `renameTo`，失败静默忽略；
- 读取：任何 `IOException` / 版本不符 / key 不符 / 结构非法都返回 `null`，回退到正常编译；
- 防御：对类数量、单个字节数组长度、总字节数设上限，避免损坏文件导致 OOM；
- 建议同步写入。首次启动时编译已经花了几秒，额外写 1.5 MiB 通常只有几十毫秒；如实测拖慢首帧，再改异步。

### 6.2 缓存键

qml4j 的 API 文档要求 key 在「任何可达 QML、qmldir、JS import、context 契约或 qml4j 版本变化」时改变。QHIFI 的 QML 全部打包在 APK assets 内，不会在安装后变化，因此可以：

```text
key = PackageInfo.lastUpdateTime + ":" + PackageInfo.versionName
      + ":qml4j-0.2.33:scene-v1"
```

- `lastUpdateTime` 在安装/覆盖安装/更新时变化，天然覆盖 QML 资源变化；
- `versionName` / `scene-v1` 用于人为升级格式或引擎版本时强制失效；
- 如果未来支持外部 QML、热重载或插件 QML，必须改为对完整 QML 资源做内容哈希，不能继续用 APK 时间戳。

### 6.3 接入点

1. [QPlayerActivity.java](file:///c:/Users/23314/Desktop/qplayer-hifi/android-shell/app/src/main/java/dev/t1m3/qplayer/android/ui/QPlayerActivity.java#L383-L388)：
   - 继续复用 `getCacheDir()/qml-dex`；
   - 在 `invalidateDexCacheOnReinstall(dexCache)` 之后创建 `FileCompiledSceneCache`，key 用上一节的规则；
   - 把 cache 和 key 传给 `QmlGLSurfaceView`（新增构造参数或 setter）。
2. [QmlGLSurfaceView.java](file:///c:/Users/23314/Desktop/qplayer-hifi/android-shell/app/src/main/java/io/github/timer_err/qml4j/android/QmlGLSurfaceView.java#L573-L628)：
   - 在 `QmlView.withStockTypes(engine).resources(resources)` 之后、`view.load(...)` 之前调用：
     ```java
     view.compilationCache(sceneCache, sceneKey);
     ```
   - 位置必须在 `load` 之前；context 注册顺序不影响编译缓存，但 key 必须覆盖 context 契约。
3. 失效：
   - 场景文件放进 `qml-dex` 目录（例如 `qml-dex/scene.bin`），现有的 `invalidateDexCacheOnReinstall` 会连它一起删掉；
   - `preloadCachedDex` 只扫描 `.dex`，不会误读场景文件；
   - **不要把场景缓存放到独立目录再调用一次 `invalidateDexCacheOnReinstall`**：第一次调用已经把 `apkStamp` 写成当前值，第二次会直接跳过删除。上游 `dev` 分支的 Android 实现就踩了这个坑（见 §10、§12）。
   - 场景缓存与 dex 缓存必须**同代失效**：`DexClassLoaderBackend.loadFromCombined` 是按类名从所有已缓存 dex 中取类，不校验字节码内容；只要旧 dex 里存在同名 `Component$N`，新场景的字节码就不会被使用。
4. 日志（开发期）：
   - `scene cache hit/miss, restore=Xms, compile=Xms, classes=N, bytes=N`；
   - 命中时不会触发 `CompileProgressListener`，启动页会停留在默认的「加载中」，这是预期行为；如需提示可显示「正在加载界面」。

### 6.4 预期效果

- **安装后第一次启动**：缓存未命中，仍按当前路径编译并写入场景文件；启动页进度正常。
- **之后每次启动 / Activity 重建**：命中缓存，跳过 142 个 QML 文件的 parse 和 ASM 编译；`defineClasses` 从 115 次降为 1 次；启动页不再显示「正在编译界面组件」。
- **D8**：API 27+ 上如果 `qml-dex` 缓存还在，restore 走 `loadFromCombined`，不触发 D8；API 26 没有 combined preload，会退回一次 `dexAll`，对 317 个类做一次 D8（仍是一次，不是 115 次）。如果 dex 缓存被系统清掉，最多也是一次 D8。
- **仍保留的成本**：对象树构造、绑定建立、Rhino JS 编译、字体/图片初始化。不要承诺「秒开」。

### 6.5 风险与对策

| 风险 | 对策 |
|---|---|
| 缓存键错误导致旧 UI | `lastUpdateTime` + versionName + scene 版本；开发期日志打印 key 与命中状态 |
| 场景文件损坏/截断 | 校验 magic/版本/长度/checksum，失败返回 null；原子写入 |
| dex 缓存与场景缓存不一致 | 两者同目录、同一 `apkStamp` 清理；key 包含 install 时间；绝不单独删除其中一个 |
| 旧 dex 按类名抢先命中 | `loadFromCombined` 不校验字节码；同代失效，或改为带字节码校验的加载 |
| qml4j 升级后旧字节码 | key 包含引擎版本/手动 schema；`CompiledScene.FORMAT_VERSION` 不够 |
| 缓存被篡改 | 缓存目录视为可信边界；plugin/外部 QML 不接入；checksum 只防损坏不防伪造 |
| Activity 重建后旧类未释放 | 继续使用现有 `DexClassLoaderBackend.dispose()`；场景缓存只存字节码，不持有 Class 对象 |
| 首次启动写入变慢 | 1.5 MiB 同步写通常可忽略；实测后再决定是否异步 |
| Rhino 绑定仍然每次编译 | 已知残余成本；不属于本次场景缓存范围 |

## 7. 备选方案

### 7.1 构建期预编译场景（AOT-lite）

做法：在 Gradle 构建时用 qml4j 编译 QML，把 `CompiledScene` 序列化后作为 asset 打包；运行时 `CompiledSceneCache.load` 直接返回它。

- **收益**：安装后的第一次启动也能跳过 QML 编译。
- **代价**：
  - qml4j 没有公开的「只编译不实例化」API；需要用一个捕获 `store` 的缓存 + dummy context 走 `QmlView.load`（构建期实例化一次，成本可接受），或向上游提议 `compileToScene(...)`。
  - 构建期必须与运行时使用完全相同的 qml4j 版本/类型注册表/context 名称，否则缓存无效。
  - 仍要运行时 D8/类加载，也仍要 Rhino JS 编译。
- **判断**：只有在「首次安装启动时间」也被视为问题时才值得做；否则收益/复杂度不划算。

### 7.2 预编译 + 预 dex + 文件式 ClassLoader（完整 AOT）

把生成的 317 个类在构建期 D8 成一个 dex，随 APK 打包，运行时用文件式 `DexClassLoader` 加载，让 ART 有机会做 dex2oat/AOT。

- **收益**：消除运行时 D8，并可能降低类加载/JIT 成本。
- **代价**：构建工具链复杂（需要独立 D8/R8 步骤），类加载路径/签名/keep 规则风险高，且 QML 变更必须重新构建 dex；收益主要在首次启动。
- **判断**：不建议作为第一步；等场景缓存落地并拿到真机数据后再评估。

### 7.3 保持 Activity/引擎常驻

不推荐。QHIFI 目前在 Activity 销毁时主动 `dispose()` 以释放 Skija/QML 内存；为了省编译而保活会引入内存泄漏和生命周期复杂度，且进程被杀后仍然要编译。

## 8. 验证与验收

在没有真机自动化测试的前提下，建议分两层验证：

**JVM 层（可自动化）**

1. `FileCompiledSceneCache` 的序列化 round-trip 测试：相同 key 能恢复 classes/imports/singletons/jsImports；
2. 损坏文件（截断、错误 magic、错误 key）返回 null，不抛异常；
3. 不同 key 不互相命中；
4. 可与 qml4j 的 `CompiledSceneCacheTest` 对齐，验证 warm load 不读取 `ResourceLoader`。

**真机层（人工日志）**

1. 首次安装启动：日志应出现 `scene cache miss` + 编译耗时 + `store`；
2. 第二次冷启动：日志应出现 `scene cache hit` + `restore` 耗时，且**不再出现** `splash.compiling` 进度；
3. Activity 重建（改字体/语言/开发者选项触发）：同样命中；
4. 覆盖安装：缓存被清理，首次启动重新编译一次；
5. 听感/视觉验收：所有页面、弹窗、主题、字体、歌词页与改动前一致。

验收标准：第二次启动的 QML 编译日志消失；启动页不再显示编译进度；应用功能与视觉无回归。启动总时长应明显下降，但不要求等于「秒开」。

## 9. 结论

1. **每次启动编译的根因**：qml4j 是运行时 JIT 引擎，QHIFI 没有配置 `QmlView.compilationCache`；现有 `qml-dex` 只缓存 D8 产物，不缓存 QML 编译结果。
2. **qml4j 已经准备好解法**：0.2.33 的 `CompiledSceneCache` 正是为此设计；QHIFI 只需补一个磁盘实现并接入，不需要修改 qml4j 源码或升级版本。
3. **收益量级**：QHIFI QML 包会生成 317 个类、约 1.55 MiB 字节码；桌面冷编译 0.8～1.6 秒，真机应更高；命中缓存后不再 parse/ASM，`defineClasses` 合并为一次。
4. **不要期待零成本启动**：对象树构造、Rhino 绑定编译、字体/图片初始化仍在每次启动发生；场景缓存解决的是「编译」而不是「整个启动」。
5. **如果还要优化首次安装启动**：再考虑构建期预编译场景（AOT-lite），完整 AOT 暂不建议。
6. **上游不是不用，而是分层采用**：QPlayer master 的桌面端已接入，并有 `DiskCompiledSceneCacheTest` 覆盖（`25b45a3`）；Android 侧只有 `upstream/dev` 未合入的实现，且存在场景缓存不随覆盖安装失效的缺陷。QHIFI 应复用桌面级实现，修好 Android 的 generation/key。

## 10. 上游采用情况：不是不用，而是分层采用

先纠正一个前提：上游并不是完全没有使用这个 API，真正缺的是 **Android 宿主的集成**。

### 10.1 qml4j 引擎本体

- qml4j 只提供 SPI 和测试用的内存实现，不提供磁盘实现；缓存目录、key、失效策略都留给宿主。
- qml4j 仓库里的 `android-shell` 已被明确标记为 frozen（README），不会跟进新 API。
- 桌面 demo 是开发工具，QML 从磁盘加载、经常改动，默认缓存反而妨碍迭代。

### 10.2 QPlayer master：桌面端已经使用

时间线本身就是证据：

- qml4j `db73897`（2026-08-28 21:20:57）加入 "persistent compiled scene cache"。
- QPlayer `25b45a3`（同日 21:21:29，32 秒后）"perf: cache desktop QML compilation" 把桌面端接上。

当前 upstream master 的桌面端：

- `Main` 构造 `DiskCompiledSceneCache(AppDirs.cacheDir().resolve("qml"), classpathResources.qmlFingerprint(currentVersion))`；
- [DesktopWindow.java](file:///c:/Users/23314/Desktop/qplayer-hifi/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/DesktopWindow.java#L495-L496) 和 [DesktopLyricRenderThread.java](file:///c:/Users/23314/Desktop/qplayer-hifi/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/window/DesktopLyricRenderThread.java#L58-L59) 都调用 `.compilationCache(cache, sceneKey(...))`；
- [QmlResourceFingerprint.java](file:///c:/Users/23314/Desktop/qplayer-hifi/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/resources/QmlResourceFingerprint.java) 的 key 覆盖：应用版本 + `CompiledScene.FORMAT_VERSION` + **qml4j 引擎 `.class` 摘要** + 所有 `.qml/.js/qmldir` 内容；
- [DiskCompiledSceneCache.java](file:///c:/Users/23314/Desktop/qplayer-hifi/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/resources/DiskCompiledSceneCache.java) 有 checksum、长度上限、原子写入、损坏删除；
- [DiskCompiledSceneCacheTest.java](file:///c:/Users/23314/Desktop/qplayer-hifi/desktop-host/src/test/java/dev/t1m3/qplayer/desktop/resources/DiskCompiledSceneCacheTest.java) 覆盖 round-trip、损坏文件、fingerprint 顺序无关和 QML 变化检测。

结论：**API 在生产宿主上可用，难点在 key/失效，而不是引擎能力。**

### 10.3 QPlayer dev：Android 端有实现，但没有合入 master

`upstream/dev`（contributor strokeshading 的分支）做过 Android 接入：

- `2c06dda` 首次接入 Android 场景缓存；
- `6a56605` 补文件；
- `0431f6c` 由上游作者合并进 `dev`；
- `0431f6c` **不是 master 的祖先**；dev HEAD 比 master 多 21 个提交，且包含大量无关实验功能（AppleMusic 歌词、automix、tempera 等）。这不是一个可直接合入的生产补丁。

当前 dev 代码有两处关键问题：

1. **缓存 key 固定**：`QmlGLSurfaceView` 传入 `new DiskCompiledSceneCache(sceneCacheDir.toPath(), "qml-main")`，key 也是 `"qml-main"`。场景文件永远同名，不随 QML/qml4j/宿主版本变化。
2. **失效逻辑实际不生效**：
   ```java
   java.io.File dexCache = new java.io.File(getCacheDir(), "qml-dex");
   invalidateDexCacheOnReinstall(dexCache);
   java.io.File sceneCache = new java.io.File(getCacheDir(), "qml-scene");
   invalidateDexCacheOnReinstall(sceneCache);
   ```
   `invalidateDexCacheOnReinstall` 只在 `apkStamp != lastUpdateTime` 时删除并写入 stamp。第一次调用已经把 stamp 写成当前值，第二次调用必然跳过删除。注释说"同样 reinstall 清空"，实际 **scene cache 从不随安装清理**。

后果：覆盖安装后 `qml-dex` 被清空、`qml-scene` 保留且 key 固定 → 旧场景永远命中，UI 停留在旧版本，直到用户手动清 App 数据。即使修复 stamp，常量 key 也只能靠"安装时清空"失效，无法应对同一次安装内的 QML 变化或热重载。

所以更准确的说法是：**上游在桌面上使用并验证了这个 API；Android 侧只有一个未合入、且失效逻辑有缺陷的贡献分支。** 不是引擎不支持，也不是上游明确否决。

### 10.4 QHIFI

- QHIFI 基于 upstream master：桌面端已使用 `DiskCompiledSceneCache`；Android 端没有接。
- QHIFI 的 `DexClassLoaderBackend` 是自己的加强版（磁盘 D8 缓存 + combined loader），比 qml4j frozen android-shell 更完整，但没有接场景缓存。
- 因此 QHIFI 缺的是 Android 宿主的那一半，不是 qml4j 的能力。

## 11. 为什么 qml4j 不自动启用

1. **引擎不知道完整的资源清单。** `ResourceLoader` 只有 `load(path)`，没有 list/stat；QML 还会通过 `Loader.source`、qmldir、JS import、动态类型解析加载文件。宿主可能从磁盘、网络、插件包加载。引擎无法在编译后可靠判断"下一次启动是否仍是同一份输入"。
2. **编译结果依赖宿主契约。** `QmlView.context` 的名字、`TypeRegistry` 里的自定义 Java 类型、宿主代码版本都会影响生成的 bytecode；只有宿主知道这些。
3. **安全边界。** 缓存里的 bytecode 是可直接执行的 Java 类，绕过 QML parser 和 safe-realm 检查；对不可信 QML，引擎不应默认持久化。checksum 只能防损坏，不能防伪造。
4. **开发/热重载。** 桌面端 QML 常从磁盘加载并频繁修改；默认缓存会让开发者看到旧 UI。QPlayer desktop 用内容 fingerprint 解决，但这依赖宿主的资源位置知识。
5. **格式与 ABI。** `CompiledScene.FORMAT_VERSION` 只描述序列化布局，不保证生成类与未来 qml4j 运行时的二进制兼容；上游桌面把 qml4j 引擎 class 摘要放进 key。引擎不宜独自承诺长期 on-disk ABI。
6. **Android 需要宿主后端。** D8、`InMemoryDexClassLoader`、dex 缓存、类加载器生命周期都由宿主掌控；qml4j 的 android-shell 是 frozen 参考实现，不维护。
7. **不是所有宿主都受益。** 小场景的缓存 I/O 可能超过编译；缓存只作用于第一次 `load`，动态 `Loader.source` 仍会编译；收益是宿主/场景特定的。
8. **API 文档本身把 key 交给宿主。** 它要求 key 在"任何可达 QML、qmldir、JavaScript import、context 契约或 qml4j 版本变化"时改变。这是刻意的边界。

所以正确的分工是：**引擎提供能力，宿主提供 key/存储/失效策略。** QPlayer desktop 已经这样做了；QHIFI 只需要把 Android 宿主这一半补齐。

## 12. 引入 API 的副作用（按严重度）

| # | 副作用 | 机制/证据 | 对策 |
|---|---|---|---|
| 1 | 旧场景/旧 dex 抢先命中 | dev 分支常量 key + 失效 bug；QHIFI 的 `loadFromCombined` 按类名从所有 dex 取类，不校验字节码 | 场景/dex 同代失效；一个 generation 目录；必要时校验字节码 |
| 2 | qml4j/宿主 ABI 变化 | `CompiledScene.FORMAT_VERSION` 不覆盖生成类的运行时 ABI；引擎升级可能 `LinkageError`/`NoSuchMethodError` | key 包含引擎版本/手动 schema；参考桌面的引擎 class 摘要 |
| 3 | 缓存即代码 | 缓存的 bytecode 绕过 QML parser/safe realm；checksum 不防伪造 | 只缓存可信的 APK 内 QML；plugin/外部 QML 不接入；目录视为可信边界 |
| 4 | 调试/热重载被掩盖 | warm load 完全不看 `qml` 参数；qml4j 测试和本次 benchmark 都用无效 QML 成功恢复 | dev 禁用缓存或使用内容哈希；日志打印 hit/miss 和 key |
| 5 | 覆盖不完整 | 只缓存第一次 load；动态 Loader 源、JS import、Rhino 绑定、对象树仍会执行 | 明确收益边界；不要把"不编译"宣传成"秒开" |
| 6 | 启动 I/O/内存/key 计算 | 1.55 MiB 读取/解码；桌面 fingerprint 要读全部 QML + qml4j class | 按场景大小决定是否启用；Android 用 APK 版本代替 class 摘要 |
| 7 | 磁盘增长/回收 | 桌面 fingerprint 每版本变化会产生新 `.qmlc`，没有清理；cacheDir 可能被系统回收 | 安装时清空；限制目录大小/旧文件清理；miss 时回退编译 |
| 8 | 多视图/类加载器 | 每个 QmlView/Loader 持有自己的 `QmlCompiler`，生成名从 `Component$0` 开始；共用 backend 可能重复定义 | 每个场景独立 engine/backend（桌面歌词窗口就是 `new QmlEngine()`） |
| 9 | 测试盲区/UX | 上游测试没覆盖旧 key、Android dex 交互、并发；命中后不再触发 `CompileProgressListener` | 自建 round-trip/损坏/失效测试；启动页显示通用"加载中" |

逐条展开最关键的三个：

**12.1 旧场景与旧 dex 的"双缓存不同代"是最高风险。**
`DexClassLoaderBackend.preloadCachedDex` 把目录里所有 `.dex` 合并成一个 loader；`loadFromCombined` 只按类名查找，找到就返回，**不比较请求的字节码**。因此只要旧 dex 里存在同名 `Component$N`，新场景的字节码就不会被定义。即使场景缓存正确失效，只要 dex 缓存残留旧代，旧类也可能抢先命中。反过来，scene cache 残留而 dex cache 被清空，则会把旧 scene 的字节码重新 D8 一遍。两者必须作为一个 generation 一起失效，不能各自独立清理。

**12.2 ABI 兼容不是 `FORMAT_VERSION` 能覆盖的。**
`CompiledScene.FORMAT_VERSION` 只保证"这个文件能被这一版 `CompiledScene` 反序列化"。生成的类会调用 qml4j runtime 的方法/字段；qml4j 升级后这些引用可能变化。上游桌面把 qml4j 引擎 `.class` 摘要放进 fingerprint，正是为了避免这一点。Android 上直接读取 DEX 里的引擎类做摘要不现实，所以至少要包含应用版本 + 手动 `QML_SCENE_SCHEMA`，在升级 qml4j 时强制 +1。

**12.3 缓存是代码，不是纯数据。**
QML 的 safe-realm/parser 校验只发生在编译时；缓存命中后直接 define 字节码。对 QHIFI 这种 QML 全部打包在 APK、缓存目录 app-private 的场景，风险很低；但不要把 plugin 或用户提供的 QML 接进同一个缓存。如果未来引入外部 QML，必须做签名/哈希校验，checksum 不够。

## 13. 对 QHIFI 落地方案的修正

1. **复用桌面端已验证的实现，而不是 dev 分支的简化版。**
   - 把 [DiskCompiledSceneCache.java](file:///c:/Users/23314/Desktop/qplayer-hifi/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/resources/DiskCompiledSceneCache.java) 和必要的 fingerprint 逻辑移到 `player-core`（或复制到 android-shell），保留 checksum、长度上限、原子写入、损坏删除和对应的 JVM 测试。
   - 桌面版 key 依赖 classpath/qml4j class digest；Android 改用 APK 版本 + 手动 schema，不要用固定字符串。
2. **一个 generation 目录。**
   - 推荐 `getCacheDir()/qml-cache/<apkStamp>/`，里面同时放 `*.dex` 和 `*.qmlc`；启动时若目录名不是当前 `lastUpdateTime`，删除旧 generation 并创建新目录。`DexClassLoaderBackend` 和场景缓存都指向同一个 generation。
   - 或者至少把 scene 文件放进 `qml-dex`，只调用一次 `invalidateDexCacheOnReinstall`。
   - 绝不出现"dex 清了、scene 没清"或反之；这是 dev 分支已经踩过的坑。
3. **key 规则。**
   - `lastUpdateTime + ':' + versionName + ':' + CompiledScene.FORMAT_VERSION + ':' + QML_SCENE_SCHEMA`。
   - `QML_SCENE_SCHEMA` 在 qml4j 升级、宿主类型/context 契约变化时手动 +1；不要只依赖 `FORMAT_VERSION`。
   - 如果未来支持外部 QML/热重载，改为内容哈希（可参考桌面的 [QmlResourceFingerprint.java](file:///c:/Users/23314/Desktop/qplayer-hifi/desktop-host/src/main/java/dev/t1m3/qplayer/desktop/resources/QmlResourceFingerprint.java)）。
4. **安全边界。**
   - 只缓存 APK 内打包的 QHIFI QML；plugin/第三方 QML 不接入。缓存目录视为可信边界。
5. **观测与开关。**
   - 日志 `scene cache hit/miss`、`restore/compile ms`、`classes/bytes`；dev build 可加 `sceneCache=off` 或内容哈希模式。
6. **验证。**
   - JVM：round-trip、损坏文件、key mismatch、不同 key 不互命中；
   - 真机：第二次启动不再出现 `splash.compiling`；**覆盖安装后必须 miss**（这是 dev 分支漏掉的用例）；Activity 重建命中；清 cache 后回退编译。
7. **上游。**
   - 如果要回馈上游，先修好 dev 分支的 stamp/key 再提 PR；不要直接合并整个 dev 分支。QHIFI 本地实现可以先落地，等上游稳定后再对齐。

## 14. 待确认

1. 场景缓存放在 `getCacheDir()/qml-cache/<apkStamp>/`（推荐，dex + scene 同代）还是单独目录？
2. 首次安装后的那一次编译是否可以接受？如果不能，是否要投入构建期预编译？
3. 这次只出报告，还是下一步直接在 `android-shell` 落地磁盘缓存 + JVM 测试？是否直接复用桌面 `DiskCompiledSceneCache`？
4. debug/外部 QML 场景是否需要内容哈希，还是 APK 时间戳就够了？
5. 是否要顺手修复 upstream/dev 的 Android 分支并提 PR，还是只在 QHIFI 内实现？

---

### 参考

- qml4j v0.2.33：[README](https://github.com/TIMER-err/qml4j/blob/v0.2.33/README.md) · [Loader](https://github.com/TIMER-err/qml4j/blob/v0.2.33/qml4j-core/src/main/java/io/github/timer_err/qml4j/render/Loader.java) · [QmlView](https://github.com/TIMER-err/qml4j/blob/v0.2.33/qml4j-core/src/main/java/io/github/timer_err/qml4j/render/QmlView.java) · [CompiledSceneCache](https://github.com/TIMER-err/qml4j/blob/v0.2.33/qml4j-core/src/main/java/io/github/timer_err/qml4j/compiler/CompiledSceneCache.java)
- qml4j 持久化缓存提交：`db73897`（v0.2.31 起）
- QHIFI 当前接入：[QPlayerActivity.java](file:///c:/Users/23314/Desktop/qplayer-hifi/android-shell/app/src/main/java/dev/t1m3/qplayer/android/ui/QPlayerActivity.java#L383-L388) · [QmlGLSurfaceView.java](file:///c:/Users/23314/Desktop/qplayer-hifi/android-shell/app/src/main/java/io/github/timer_err/qml4j/android/QmlGLSurfaceView.java#L573-L628) · [DexClassLoaderBackend.java](file:///c:/Users/23314/Desktop/qplayer-hifi/android-shell/app/src/main/java/io/github/timer_err/qml4j/android/DexClassLoaderBackend.java#L41-L65)
- QHIFI 依赖版本：[pom.xml](file:///c:/Users/23314/Desktop/qplayer-hifi/pom.xml#L22-L23)（qml4j 0.2.33 / skija 0.143.17）

# AGENTS.md

本文面向在本仓库工作的开发与协作代理，提供项目架构、关键约束、构建命令与验证指引。所有内容以当前仓库代码与构建脚本为准；面向最终用户的使用说明请同步维护在 [README.md](README.md)。

## 项目目标

**CN Wear Overlay** 为缺少完整 Google Wear SDK 或存在厂商魔改触觉实现的国产安卓智能手表适配 Wear Compose 表冠（rotary）触觉反馈，并修复部分系统触觉常量引发的崩溃与震感异常：

- 修复缺少系统类 `com.google.wear.input.WearHapticFeedbackConstants` 导致的 `ClassNotFoundException` / `NoClassDefFoundError` 崩溃。
- 适配 Wear Compose 表冠常量（`scrollFocus` / `scrollTick` / `scrollLimit`）及输入事件（如 OPPO `oplus_crown` 鼠标滚轮转 rotary）。
- 修复小米系统将 `CLOCK_TICK(4)` / `SEGMENT_FREQUENT_TICK(27)` 映射到 `TEXTURE_TICK(21)` 导致持续振动的问题（重映射为 `SEGMENT_TICK(26)`），并为旧版 OPPO（波形 `302`）与华为（`VibratorEx`）提供表冠触觉直通路径。

项目包含两套并行的交付形态，二者在平台识别、常量取值与触觉语义上必须保持一致：
1. **Gradle 插件**（`:plugin` + `runtime/`）：构建期通过 AGP ASM Instrumentation 改写应用及依赖字节码，并注入内嵌 runtime / stub JAR。
2. **Xposed 模块**（独立工程 `xposed/`）：基于 `libxposed` API 102，在目标应用进程 `onPackageReady` 安装运行期 Hook 与 DEX bridge。不 Hook `system_server` 或系统框架进程，因而兼容 LSPatch 等内嵌式免 root 框架。

## 仓库结构

根构建与 Xposed 构建相互独立，共享根目录版本号配置文件。

| 路径 | 职责 |
| --- | --- |
| `settings.gradle.kts` | 主构建入口，仅包含 `:plugin`（不包含 `xposed/`） |
| `version.properties` | `version` 与 `versionCode` 唯一数据源，`:plugin` 与 `xposed/:app` 共同读取 |
| `plugin/build.gradle.kts` | Gradle 插件构建与 Maven Central 发布配置；编译 `runtime/` 并内嵌两个 JAR |
| `plugin/.../gradle/CnWearOverlayPlugin.java` | 注册 `cnWearOverlay` 扩展；仅作用于 `com.android.application`；解压注入 JAR 并注册 `InstrumentationScope.ALL` |
| `plugin/.../gradle/CnWearOverlayExtension.java` | 插件扩展配置：`enabled`、`patchHapticsKt`、`remapXiaomiConstants`、`generateStub` |
| `plugin/.../gradle/asm/CnWearInstrumentationParams.java` | 传递给 AGP worker 的 `@Input` 参数（将 `patchHapticsKt && generateStub` 绑定为 `injectHasWearSdk`） |
| `plugin/.../gradle/asm/CnWearOverlayClassVisitorFactory.java` | 字节码插桩过滤与 `View` 子类判定 |
| `plugin/.../gradle/asm/CnWearOverlayClassVisitor.java` | ASM 字节码改写：`HapticsKt` 包装、`AndroidComposeView` 输入包装、`performHapticFeedback` 调用点重写 |
| `runtime/src/main/java/.../runtime/CnWearOverlay.java` | 跨插件/桩类/Xposed 的稳定公共入口，持有单例缓存的平台组件 |
| `runtime/src/main/java/.../runtime/BaseOverlay.java` | 基准实现：原样透传、`boot` classloader 探测、`HapticConstants` 反射构造、直连马达开关检查、OPPO 输入转换 |
| `runtime/src/main/java/.../runtime/XiaomiHaptics.java` | 小米适配：`(19, 18, 20)` 及 `4 / 27 -> 26` 重映射 |
| `runtime/src/main/java/.../runtime/OppoModernHaptics.java` | 现代 OPPO 适配：`(12, 12, 12)`、原生触觉透传、限定 `oplus_crown` 设备名 |
| `runtime/src/main/java/.../runtime/OppoLegacyHaptics.java` | 传统 OPPO 适配：`(12, 12, 12)`、拦截 `12` 并反射 `LinearmotorVibrator` 播放波形 `302` |
| `runtime/src/main/java/.../runtime/HuaweiHaptics.java` | 华为适配：`(19, 18, 20)`、拦截 `19/18/20` 并反射 `VibratorEx` 播放 `watchhaptic.crown.*` |
| `runtime/src/main/java/.../runtime/OverlayLog.java` | 运行时诊断日志封装（tag `CnWearOverlay`，动态读取 `Log.isLoggable`） |
| `runtime/src/main/java/com/google/wear/input/WearHapticFeedbackConstants.java` | 缺失 Google Wear 常量桩类，方法委托至 `CnWearOverlay` |
| `runtime/src/stubs/android/` | 仅用于编译 `:plugin` 内嵌 runtime 的极简 Android compile-only 桩（不打包分发） |
| `runtime/src/main/resources/META-INF/proguard/cnwearoverlay-runtime.pro` | 随 `cnwearoverlay-runtime.jar` 分发的 consumer R8/ProGuard 规则 |
| `xposed/settings.gradle.kts`、`xposed/build.gradle.kts`、`xposed/app/build.gradle.kts` | 独立 Xposed Android 构建；直接以 Android `bootClasspath` 编译 `runtime/src/main/java` 并用 D8 生成 bridge DEX |
| `xposed/app/src/main/java/.../cnwearoverlay/CnWearOverlayModule.java` | `XposedModule` 入口，仅在首个 `onPackageReady` 安装 Hook |
| `xposed/app/src/main/java/.../cnwearoverlay/Platform.java` | Xposed 侧平台检测与常量/重映射逻辑（需与 `runtime/` 语义同步） |
| `xposed/app/src/main/java/.../cnwearoverlay/Hooks.java` | Hook 安装协调器（View、Compose 输入、SDK bridge、`HapticsKt`） |
| `xposed/app/src/main/java/.../cnwearoverlay/CrownMouseHook.java` | OPPO 专属 `AndroidComposeView.dispatchGenericMotionEvent` Hook |
| `xposed/app/src/main/java/.../cnwearoverlay/WearSdkBridge.java` | 缺失 Google Wear SDK 时向应用 `dexElements` 末尾追加 `assets/cnwearoverlay/classes.dex` |
| `xposed/app/src/main/resources/META-INF/xposed/` | `java_init.list` 与 `module.prop`（声明 `minApiVersion=101`、`targetApiVersion=102`） |
| `xposed/app/proguard-rules.pro` | 仅保留 `CnWearOverlayModule` 类及其无参构造 |
| `.github/workflows/build.yml`、`release.yml` | CI 自动构建与带签名发版（GitHub Release + Maven Central） |

> 上述缩写包路径分别对应 `cc/star0/wear/lib/cnwearoverlay` 与 `cc/star0/wear/xposed/cnwearoverlay`。

## 工具链与构建命令

### 版本基线（以各 Gradle 配置为准）

- **Gradle Wrapper**：`9.7.1`（`xposed/gradle/wrapper/gradle-wrapper.properties`；daemon JVM 配置为 Java `25`，CI 使用 Temurin JDK `25`）
- **Java 目标版本**：`17`（`:plugin`、内嵌 runtime、`xposed/:app` 与 bridge DEX 编译均以 Java 17 为准）
- **插件工程 (`:plugin`)**：
  - `com.android.tools.build:gradle-api:9.4.1`（`compileOnly`）
  - `org.ow2.asm:asm:9.10.1`（`compileOnly`）
  - `org.jetbrains.kotlin:kotlin-stdlib:2.4.20`（`compileOnly`）
  - `com.vanniktech.maven.publish:0.37.0`
- **Xposed 工程 (`xposed/:app`)**：
  - AGP `9.3.3`、`compileSdk = 37`、`targetSdk = 37`、`minSdk = 26`、`buildToolsVersion = "37.0.0"`
  - `io.github.libxposed:api:102.0.0`（`compileOnly`，绝不打包进 APK）
  - `abiFilters` 限定 `arm64-v8a` 与 `armeabi-v7a`

### 常用命令（均从仓库根目录执行）

共用 `xposed/` 下的 Gradle Wrapper 并通过 `-p` 指定工程目录；构建 Xposed 需要配置 `ANDROID_HOME` 或被忽略的 `xposed/local.properties`（禁止将本机绝对路径提交到仓库）。

**Windows (PowerShell):**
```powershell
# 构建 Gradle 插件（含编译内嵌 runtime/stub JAR）
.\xposed\gradlew.bat -p . :plugin:build

# 校验 Gradle 插件元数据声明
.\xposed\gradlew.bat -p . :plugin:validatePlugins

# 仅生成插件内嵌 runtime 与 stub JAR
.\xposed\gradlew.bat -p . :plugin:cnWearRuntimeHelperJar :plugin:cnWearRuntimeStubJar

# 构建 Xposed Debug / Release APK（未设签名环境变量时 Release 为未签名）
.\xposed\gradlew.bat -p xposed :app:assembleDebug :app:assembleRelease

# 运行 Xposed Android Lint
.\xposed\gradlew.bat -p xposed :app:lintRelease

# 检查空白符与换行格式
git diff --check
```

**macOS / Linux:**
```bash
./xposed/gradlew -p . :plugin:build
./xposed/gradlew -p . :plugin:validatePlugins
./xposed/gradlew -p . :plugin:cnWearRuntimeHelperJar :plugin:cnWearRuntimeStubJar
./xposed/gradlew -p xposed :app:assembleDebug :app:assembleRelease
./xposed/gradlew -p xposed :app:lintRelease
git diff --check
```

### 发布与签名说明

- **Xposed 签名**：通过环境变量 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 启用 Release 签名。
- **Maven Central 发布**：`cc.star0.wear.lib:cnwearoverlay` 及插件 marker；需设置 `ORG_GRADLE_PROJECT_mavenCentralUsername`、`ORG_GRADLE_PROJECT_mavenCentralPassword`、`ORG_GRADLE_PROJECT_signAllPublications=true`、`ORG_GRADLE_PROJECT_signingInMemoryKey`、`ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`（任务 `:plugin:publishAndReleaseToMavenCentral`）。
- **版本发布校验**：推送 tag（如 `1.2.2` 或 `v1.2.2`）触发 `release.yml`，会强制校验 tag 与 `version.properties` 的 `version` 一致且非 `-SNAPSHOT`；发版前需同步递增 `version` 与 `versionCode`。

## 平台探测与触觉语义

### 核心原则

- **严禁依赖品牌字符串**：只通过 root/boot classloader 调用 `Class.forName(name, false, boot)` 探测系统类，避免命中应用内置桩类或第三方 SDK，也不提前触发类初始化。
- **原生 Wear OS 优先退出**：只要 boot classpath 存在 `com.google.wear.input.WearHapticFeedbackConstants`，立即认定为原生 Wear OS（如三星、TicWatch、OPPO/OnePlus Wear OS 版），所有适配保持非激活状态。
- **双端逻辑同步**：`runtime/`（`CnWearOverlay` + 各 `BaseOverlay` 子类）与 `xposed/`（`Platform.java` + `Hooks.java`）分别维护平台判断与 View 拦截分支。**修改任何平台识别类、常量值、事件转换或重映射逻辑时，必须同时检查两端并保持语义一致。**

### 平台适配矩阵（严格按探测顺序）

| 优先级 | 平台状态 | Boot Classpath 探测标记 | Compose 常量 `(focus, tick, limit)` | `View.performHapticFeedback` 处理 | OPPO 输入转换 |
| --- | --- | --- | --- | --- | --- |
| 1 | Google Wear OS (Inactive) | 存在 `com.google.wear.input.WearHapticFeedbackConstants` | 回落原逻辑 | 原样透传 | 否 |
| 2 | `XIAOMI` | 存在 `miwear.os.VibrationEffectId` 或 `com.xiaomi.miwear.input.WearHapticFeedbackConstants` | `(19, 18, 20)` | 将 `4` (`CLOCK_TICK`) / `27` (`SEGMENT_FREQUENT_TICK`) 重映射为 `26` (`SEGMENT_TICK`) | 否 |
| 3 | `OPPO_MODERN` | 同时存在 `android.os.linearmotorvibrator.LinearmotorVibrator` 与 `android.view.HapticScrollFeedbackProvider` | `(12, 12, 12)` | 原样透传（系统自行将 `12` 路由到波形 `302`） | 是，仅限 `InputDevice.getName()` 为 `"oplus_crown"` |
| 4 | `OPPO_LEGACY` | 存在 `LinearmotorVibrator`，不存在 `HapticScrollFeedbackProvider` | `(12, 12, 12)` | 拦截 `12`（不重入 View），反射 `linearmotor` 服务播放波形 `302` / 强度 `2` | 是，不限制设备名 |
| 5 | `HUAWEI` | 存在 `com.huawei.android.os.VibratorEx` | `(19, 18, 20)` | 拦截 `19/18/20`（不重入 View），反射 `VibratorEx.setHwVibrator` 播放 `watchhaptic.crown.*` | 否 |
| 6 | Unknown (Inactive) | 上述标记均不存在 | 回落原逻辑 | 原样透传 | 否 |

### 平台实现关键细节

- **`OPPO_LEGACY` 直连播放**（`OppoLegacyHaptics.perform`）：
  - 先通过 `BaseOverlay.canPerformDirectHaptic` 校验 `view != null`、`view.isAttachedToWindow()`、View 开关（尊重 `flags & 1` 忽略）及系统 `Settings.System` 的 `haptic_feedback_enabled`（尊重 `flags & 2` 忽略）。
  - 反射构建 `WaveformEffect`（`effectType=302`、`strength=2`、`loop=false`、`asynchronous=false`），并在调用 `vibrate` 前校验 `getEffectType()` 确认为 `302`（部分固件不支持高压马达时会降级成 `6`）。
  - 反射失败、无服务/权限、不支持或降级时直接返回 `false`。
- **`HUAWEI` 直连播放**（`HuaweiHaptics.perform`）：
  - 同样先经过 `canPerformDirectHaptic` 检查。
  - `SCROLL_LIMIT(20)` 优先检查并播放 `watchhaptic.crown.strength3`，不支持时回退到 `watchhaptic.crown.strength1`；`SCROLL_ITEM_FOCUS(19)` / `SCROLL_TICK(18)` 播放 `watchhaptic.crown.strength1`。结果会按效果名缓存在 `tickSupported` / `limitSupported` 中。
  - 反射或支持检查失败直接返回 `false`。
- **OPPO 鼠标滚轮转 Rotary**（`BaseOverlay.rotaryEvent`）：
  - 仅在 `isOppo()` 为真时生效，要求：`ACTION_SCROLL`、来源包含 `SOURCE_MOUSE` 且不包含 `SOURCE_ROTARY_ENCODER`、`pointerCount == 1`、`AXIS_VSCROLL` 为有限非零浮点数，且满足对应代际的设备名条件。
  - 仅复制 `PointerProperties` / `PointerCoords`，将 `AXIS_VSCROLL` 原值写入 `AXIS_SCROLL`，并将 source 设为 `SOURCE_ROTARY_ENCODER`；**不缩放、不取反**。
  - 若分发返回 `false`（未被 rotary 消费者处理），需用原始事件重试一次；正常返回与异常路径的 `finally` 中必须调用 `recycleRotaryEvent` 回收副本，且不得回收或篡改调用方原始事件。

## Gradle 插件与 ASM 插桩约束

### 插件开关与打包流水

- `CnWearOverlayPlugin` 仅注册在 `com.android.application`（应用在 library 模块为 no-op 并输出 warn）。
- `ExtractRuntimeJars` 通过 `TaskProvider` 在构建期把插件资源 `/cnwearoverlay-runtime.jar` 和（当 `generateStub` 为 `true` 时）`/cnwearoverlay-stub.jar` 解压到使用方的 `build/cnwearoverlay/`，并以 `builtBy` 关联 `implementation` 依赖，确保 `:app:clean` 与配置缓存下依赖顺序正确。
- 四个开关默认均为 `true`：
  - `enabled`：总开关；`false` 时不添加 JAR 依赖也不注册插桩。
  - `patchHapticsKt`：包装 `HapticsKt.getCustomRotaryConstants` 与 `AndroidComposeView.dispatchGenericMotionEvent`；即使 `remapXiaomiConstants=false`，也会将 `androidx.wear.compose.foundation.rotary.*` 包内的触觉调用代理至 `CnWearOverlay.performRotaryHapticFeedback`（从而保留 `OPPO_LEGACY` 和 `HUAWEI` 适配）。
  - `remapXiaomiConstants`：全局（`InstrumentationScope.ALL`）将 `View` 及其子类的 `performHapticFeedback` 虚调用重写为 `CnWearOverlay.performHapticFeedback`（包含小米重映射及旧 OPPO / 华为直连逻辑）。
  - `generateStub`：控制注入 `cnwearoverlay-stub.jar`；仅当 `patchHapticsKt && generateStub` 同时为 `true` 时，才包装 `HapticsKt.hasWearSDK(Context)`（`forceWearSdk() | orig(context)`）。

### ASM 字节码改写红线

- **防自我递归**：`CnWearOverlayClassVisitorFactory.isInstrumentable` 必须排除 `cc.star0.wear.lib.cnwearoverlay.runtime.*` 与 `com.google.wear.input.WearHapticFeedbackConstants`，否则运行时内部的原生透传调用会被再次改写成代理调用导致无限递归。
- **保留 `super` 调用**：`PerformHapticFeedbackRewriter` 仅匹配 `Opcodes.INVOKEVIRTUAL`，**绝不能**改写 `Opcodes.INVOKESPECIAL`（否则 `super.performHapticFeedback(...)` 经静态代理重新虚分派会重入子类造成无限递归）。
- **包装命名与帧表**：被包装方法重命名为 `<name>$cnwear$orig`，新生成的同名包装方法须严格保持参数栈形状、`StackMapTable`（`F_NEW`）和 `visitMaxs`。例如 `getCustomRotaryConstants` 通过 `LDC HapticConstants` 传入 `Class<?>`（确保 R8 混淆重命名后仍有效）。
- **Wear Compose 目标基线**：当前 `HapticsKt`、`HapticConstants$Wear4RotaryHapticConstants`（字段 `scrollFocus` / `scrollTick` / `scrollLimit` 及无参构造）和 `AndroidComposeView.dispatchGenericMotionEvent` 签名均按 **Wear Compose 1.6.2** 核实。升级或扩展 Compose 版本前，必须对照实际字节码检查签名与类层次，不能盲目放宽匹配条件。

### Compile-only Stubs 与 Consumer R8 规则

- **Stub 同步要求**：`plugin/build.gradle.kts` 使用 `classpath = files()` 配合 `runtime/src/stubs/` 编译 runtime，而 `xposed/app/build.gradle.kts` 使用 Android `bootClasspath` 编译同一份 `runtime/src/main/java` 源码。在 `runtime/` 中使用任何新的 `android.*` 类或方法时，**必须同步在 `runtime/src/stubs/android/` 补齐精确签名**，且注意 `sourcesJar` 与运行时 JAR 均不能把 stubs 打入分发产物。
- **R8 规则**（`cnwearoverlay-runtime.pro`）：通过 `-keep,allowshrinking,allowoptimization` 保留 `HapticConstants` 类名（支持 `cls.getName() + "$Wear4RotaryHapticConstants"` 拼接），`-keepclassmembers` 保护 `scrollFocus` / `scrollTick` / `scrollLimit` 防止常量传播，`-if` 条件保留 `Wear4RotaryHapticConstants.<init>()`。修改任何反射字段或类名拼接逻辑时必须同步更新此文件。

## Xposed 模块架构约束

- **入口与进程隔离**：`CnWearOverlayModule` 通过 `META-INF/xposed/java_init.list` 加载，必须保留公开无参构造（见 `xposed/app/proguard-rules.pro`）。只在 `onPackageReady` 且 `param.isFirstPackage()` 为真时执行 `Hooks.install`。
- **各自容错**：`Hooks.install` 按顺序执行：平台探测 → （`XIAOMI` / `OPPO_LEGACY` / `HUAWEI`）Hook `View.performHapticFeedback(int)` 与 `(int, int)` 并 `deoptimize` → （`OPPO`）安装 `CrownMouseHook` → 安装 `WearSdkBridge` → 按名查找并 Hook `HapticsKt.getCustomRotaryConstants`。后几步独立捕获异常，输入 Hook 或 Bridge 不依赖 `HapticsKt` 未被混淆。
- **避免双重触发与重入**：
  - `Hooks.hookViewPerformHapticFeedback` 中，`OPPO_LEGACY` 命中常量 `12` 直接调用模块主 DEX 的 `CnWearOverlay.performOppoLegacyScrollFeedback`，`HUAWEI` 命中 `19/18/20` 直接调用 `CnWearOverlay.performHuaweiRotaryFeedback`，无论返回 `true`/`false` 均不调用 `chain.proceed`，防止重入重载或退回系统默认震动。
  - `CrownMouseHook` 使用 `ExceptionMode.PASSTHROUGH` 保持原始异常传递，并在安装前探测 `dispatchGenericMotionEvent$cnwear$orig`：若目标应用已被 Gradle 插件插桩，则直接跳过以避免双重事件转换。
- **Bridge DEX 注入**：`compileWearSdkBridge` → `wearSdkBridgeJar` → `dexWearSdkBridge`（D8 `--min-api 29`，不经主 APK R8）将完整 runtime 与 `com.google.wear.input.WearHapticFeedbackConstants` 打入 `assets/cnwearoverlay/classes.dex`。`WearSdkBridge` 仅在目标 `BaseDexClassLoader` 缺失该类时将 DEX 追加至 `pathList.dexElements` 末尾（保留应用/系统已有类优先），校验失败则原子回滚。

## 开发规范、调试与验证清单

### 代码与注释规范

- 遵循 **Java 17**、**4 空格缩进**、**UTF-8** 编码与 **Gradle Kotlin DSL** 习惯。
- 注释重点记录**设备固件差异、反射契约、字节码栈/递归边界**，避免为直白代码写模板式注释；保留无关现有文档注释的完整性。

### 诊断日志 (`OverlayLog`)

- 默认关闭，基于 `Log.isLoggable("CnWearOverlay", Log.DEBUG)` 动态判定。
- 抓日志必须在目标应用**冷启动前**开启（因为平台探测结果 `CnWearOverlay.activeComponent` 与反射 `Api.INSTANCE` 均只初始化一次）：
  ```shell
  adb shell setprop log.tag.CnWearOverlay DEBUG
  adb shell am force-stop <your.application.id>
  adb logcat -b main -b system -b crash -v threadtime -T 1 "*:V"
  ```
- 排查结束后执行 `adb shell setprop log.tag.CnWearOverlay ""`（或 `INFO`）关闭。

### 验证建议（仓库现状与按需检查）

**注意**：本仓库目前**没有任何自动化单元测试或 UI/Instrumentation 测试**。Gradle 与 APK 构建成功不代表真机触觉反馈、马达权限或 R8 后 Compose 反射逻辑正确。

| 修改范围 | 建议验证动作 |
| --- | --- |
| `plugin/` 打包或扩展 | 执行 `:plugin:build`、`:plugin:validatePlugins`，核查 `cnwearoverlay-runtime.jar` / `cnwearoverlay-stub.jar` 内容与依赖顺序 |
| `asm/` 字节码插桩 | `:plugin:build` 后，结合真实接入 Wear Compose 的使用方应用做 Release (R8) 构建；重点核验 `View` 子类、`super` 调用、四个扩展开关组合及 `StackMapTable` 校验 |
| `runtime/` 或平台逻辑 | **同时构建** `:plugin:build` 与 `xposed` 的 `:app:assembleRelease`（确保 stub 与真实 Android SDK 编译均通过），按需核对 `Platform.java` 同步 |
| `cnwearoverlay-runtime.pro` | 使用真实 Wear Compose 应用打 Release R8 包，核验 `mapping.txt` 中 `HapticConstants` / `Wear4RotaryHapticConstants` 与三个字段可读性 |
| `xposed/` Hook 或 Bridge | `:app:assembleRelease` + `:app:lintRelease`；检查 APK 中 `assets/cnwearoverlay/classes.dex`、`META-INF/xposed/` 存在，并在目标设备上实测 |
| `README.md` / `AGENTS.md` | 核对文件路径、版本号、命令及 Markdown 链接均与当前仓库一致 |

完成任务交付时，请简明列出修改内容、实际执行的构建/检查命令与结果，以及尚未在真机覆盖验证的设备与场景范围。

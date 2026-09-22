# 项目协作说明

本文提供仓库导航、实现背景和验证建议，帮助后续修改快速落地。以当前用户任务和实际代码为准；常规实现、整理、修复及验证可以直接推进，无需为一般技术选择反复确认。以下约定可随项目演进调整，不要求每次任务执行全部检查。

## 项目目标

CN Wear Overlay 为小米 / OPPO 手表适配 Wear Compose 表冠触觉，并修复小米部分 `View.performHapticFeedback` 常量导致的震感异常。仓库包含构建期插桩和运行期 Xposed Hook 两种方案，主要设备语义应保持一致。

运行时通过 boot classpath 中的标记类识别平台，不依赖品牌字符串。系统存在 `com.google.wear.input.WearHapticFeedbackConstants` 时退出适配，保留系统原生行为。

## 仓库布局

| 位置 | 职责 |
| --- | --- |
| `settings.gradle.kts` | 主构建，只包含 `:plugin` |
| `plugin/build.gradle.kts` | Java Gradle 插件配置，编译并内嵌 runtime JAR |
| `plugin/src/main/java/.../gradle/CnWearOverlayPlugin.java` | 注册扩展、应用变体插桩、解压并注入 runtime 依赖 |
| `plugin/src/main/java/.../gradle/CnWearOverlayExtension.java` | 四个用户配置开关 |
| `plugin/src/main/java/.../gradle/asm/` | AGP instrumentation 工厂与 ASM visitor |
| `runtime/src/main/java/.../runtime/CnWearOverlay.java` | 平台识别、反射构造常量、触觉调用代理 |
| `runtime/src/main/java/com/google/wear/input/` | Google Wear 常量桩类 |
| `runtime/src/stubs/android/view/View.java` | 仅供编译 runtime 的最小 Android 桩 |
| `runtime/src/main/resources/META-INF/proguard/` | 随 runtime JAR 分发的 consumer 规则 |
| `xposed/settings.gradle.kts` | 独立构建，只包含 `:app` |
| `xposed/app/src/main/java/.../cnwearoverlay/` | Xposed 入口、Hook、平台适配与 SDK bridge |
| `xposed/app/src/main/resources/META-INF/xposed/` | 入口类列表与模块 API 描述 |

表格中的 `...` 省略共同的 Java 包路径；查找时直接按文件名定位即可。Java 实现分别位于 `cc.star0.wear.lib.cnwearoverlay` 与 `cc.star0.wear.xposed.cnwearoverlay` 包下。

## 工具链与常用命令

构建版本以 Gradle 文件为准。当前使用 Gradle 9.7.1；Xposed AGP 9.3.3、compileSdk / targetSdk 37、minSdk 26；插件编译依赖 AGP API 8.13.0、ASM 9.8、Kotlin stdlib 2.2.0。Java 源码目标版本为 17，不代表 Gradle 启动 JVM 必须为 17；已有 JDK 25 构建记录。

使用方通过 `includeBuild` 接入插件，步骤见 README。维护本仓库时，可复用 `xposed/` 中的 Gradle 启动脚本，并通过 `-p` 指定构建目录。以下命令均在本仓库根目录执行，按任务选用。

### Windows（PowerShell）

```powershell
# 主构建
.\xposed\gradlew.bat -p . :plugin:build

# 单独验证插件声明
.\xposed\gradlew.bat -p . :plugin:validatePlugins

# 仅生成内嵌 runtime JAR
.\xposed\gradlew.bat -p . :plugin:cnWearRuntimeHelperJar :plugin:cnWearRuntimeStubJar

# Xposed，需可用的 Android SDK
.\xposed\gradlew.bat -p xposed :app:assembleRelease

# 按需运行 Android lint
.\xposed\gradlew.bat -p xposed :app:lintRelease

# 查看变更格式
git diff --check
```

### macOS / Linux（终端）

```bash
# 主构建
./xposed/gradlew -p . :plugin:build

# 单独验证插件声明
./xposed/gradlew -p . :plugin:validatePlugins

# 仅生成内嵌 runtime JAR
./xposed/gradlew -p . :plugin:cnWearRuntimeHelperJar :plugin:cnWearRuntimeStubJar

# Xposed，需可用的 Android SDK
./xposed/gradlew -p xposed :app:assembleRelease

# 按需运行 Android lint
./xposed/gradlew -p xposed :app:lintRelease

# 查看变更格式
git diff --check
```

### 环境与产物

macOS / Linux 的示例通过 `bash` 调用脚本，无需先设置脚本执行权限。两类平台都通过环境变量 `ANDROID_HOME` 或被忽略的 `xposed/local.properties` 配置 SDK 路径，文档与共享构建脚本不写个人机器路径。

根构建不会构建 Xposed。Xposed 构建会直接编译 runtime 源码作为 bridge，因此修改 runtime 后通常需要检查两套构建。签名未配置，Release 输出默认是未签名 APK。

## Gradle 插件链路

插件 ID 为 `cc.star0.wear.lib.cnwearoverlay`，扩展名为 `cnWearOverlay`。四个开关默认均为 `true`：

| 开关 | 行为与关联 |
| --- | --- |
| `enabled` | 关闭整个变体的插桩和 runtime 注入 |
| `patchHapticsKt` | 包装 `getCustomRotaryConstants(View)`；同时启用桩类时包装 `hasWearSDK(Context)` |
| `remapXiaomiConstants` | 将 View 及其子类的触觉虚调用改为 runtime 静态代理 |
| `generateStub` | 控制 `cnwearoverlay-stub.jar` 注入及 `hasWearSDK` 强制路径 |

只对 Android application 模块注册处理。`InstrumentationScope.ALL` 让依赖中的 Compose 字节码也参与改写。

runtime 由两个 JAR 组成：`cnwearoverlay-runtime.jar` 包含辅助类和 consumer 规则，`cnwearoverlay-stub.jar` 包含 `com.google.wear.input` 桩类。它们打包进插件资源，再由 `cnWearOverlayExtractRuntimeJars` 任务解压到使用方的 `build/cnwearoverlay/`。依赖通过 `builtBy` 关联任务，这样 clean、增量构建和产物重建都有明确顺序。

修改插桩时重点检查以下语义：

- runtime 自身和 Google 桩类不参与触觉调用重写，避免代理递归调用自己。
- `View` 子类的 `INVOKEVIRTUAL` 也可命中；`super` 的 `INVOKESPECIAL` 保留原样，避免重新虚分派导致递归。
- 原始 Compose 方法加上 `$cnwear$orig` 后缀，新包装方法继续使用原名。新增分支需有正确的 frame、栈与方法描述符。
- `getCustomRotaryConstants` 向 runtime 传入 `HapticConstants.class`，返回 `null` 时执行原始逻辑。
- 当前签名和结构以 Wear Compose 1.6.2 为依据。扩展版本支持时结合实际依赖字节码核对，不只扩大名称匹配范围。

## 平台语义与反射

| 平台 | 识别标记 | focus / tick / limit | View 常量处理 |
| --- | --- | --- | --- |
| 系统 Google Wear SDK | `com.google.wear.input.WearHapticFeedbackConstants` | 系统原生 | 原样透传 |
| 小米 | `miwear.os.VibrationEffectId` 或 `com.xiaomi.miwear.input.WearHapticFeedbackConstants` | `19 / 18 / 20` | `4`、`27` → `26` |
| OPPO | `android.os.linearmotorvibrator.LinearmotorVibrator` | `12 / 12 / 12` | 原样透传 |
| 未识别平台 | 无上述标记 | 回落原逻辑 | 原样透传 |

`runtime/CnWearOverlay.java` 和 Xposed 的 `Platform.java` 各自实现平台判断与常量映射。修改其中一处时检查另一处是否也需要同步；两处内部 state 数值不同，保持外部行为一致即可。

Compose 的 `HapticConstants` 基类为 abstract。当前实现通过“基类名 + `$Wear4RotaryHapticConstants`”查找具体子类，调用私有无参构造，再写入三个 `Integer` 字段：`scrollFocus`、`scrollTick`、`scrollLimit`。创建独立实例，避免改动 Kotlin 全局单例。

这些反射关系决定 R8 约束：基类名及子类名有拼接关系；三个字段需要保留名字、存储和反射写入后的读取语义；子类无参构造需要在反射入口可达时保留。getter、单例与普通辅助方法可按实际引用优化。调整 keep rule 时，以这些具体访问点为依据，优先使用精确类名和成员签名。

## Xposed 链路

入口为 `CnWearOverlayModule`，由 `META-INF/xposed/java_init.list` 按名称加载，使用公开无参构造。初始化位于 `onPackageReady`，只在首个 package 回调中安装 Hook。`module.prop` 当前声明 `minApiVersion=101`、`targetApiVersion=102`；实际编译依赖为 `libxposed api:102.0.0`，作用域是 `compileOnly`。

`Hooks.install` 依次检测平台、在小米安装 View Hook、尝试安装缺失 SDK bridge、查找并 Hook Compose 常量方法。目标应用的 Compose 名称若被混淆，按名查找可能失效，不能仅凭模块 APK 构建成功推断目标应用的触觉行为正常。

`WearSdkBridge` 从模块 APK 的 `assets/cnwearoverlay/classes.dex` 读取兼容类。仅当目标 classloader 缺少 Google 常量类时，将新 DEX elements 追加到原数组末尾；现有类保持优先，校验加载失败时恢复原数组。构建链路是 `compileWearSdkBridge` → `wearSdkBridgeJar` → `dexWearSdkBridge` → assets；该 DEX 使用 D8、`min-api 29`，不经过主 APK 的 R8。

Xposed 的 ProGuard 文件仅保留模块入口和公开无参构造。外部 API 的覆写回调由 R8 依据编译 classpath 处理。反射指向目标应用或系统的类，不需要在模块中添加对应整包 keep。排查缺失类告警时先检查依赖与实际打包内容，再决定是否需要精确的 `dontwarn`。

## 修改与验证建议

跟随现有 Java 17、四空格缩进、Gradle Kotlin DSL 风格即可。注释侧重设备差异、反射原因和容易回归的行为；没有必要为简单逻辑增加模板式说明。通常不需要引入新的框架或重排不相关文件。

按改动选择验证范围：

| 改动 | 建议验证 |
| --- | --- |
| README / AGENTS | 路径、命令、版本与当前代码一致，Markdown 链接可解析 |
| SVG | XML 可解析、480×480 尺寸与 viewBox 正确、实际渲染和小尺寸辨识度 |
| 插件配置或打包 | `:plugin:build`；检查内嵌 JAR 内容及任务依赖 |
| ASM visitor | 插件构建，加使用方应用的编译 / Release 构建；关注 View 子类、super 调用和开关组合 |
| runtime / 平台映射 | 两套构建；按需验证小米、OPPO、原生 Wear OS、未识别平台的行为 |
| R8 规则 | 优化产物中的类名、构造器和字段；反射赋值后 getter 的结果；未使用路径是否可裁剪 |
| Xposed Hook / bridge | Release 构建；按需检查 APK 中入口描述与 bridge DEX，再用目标设备验证 |

目前仓库没有纳入版本控制的自动化测试套件，`:plugin:build` 的成功不等于真机触觉验证。新增容易回归的逻辑时可补充聚焦测试；简单文档修改不需要搭建测试框架。使用真实 Compose 类进行 R8 冒烟检查时，比模拟类更容易发现 Kotlin 构造器和字段优化问题。

临时验证脚本、映射文件、截图等可放在对应的 `build/` 下；可复用的测试应另行组织进源码。已有通过的验证不必无故重复，最终说明实际执行过的检查及剩余限制即可。

## 交付说明

面向使用者的概览、接入方式放在 README；面向维护者的架构背景和排错线索放在这里。完成任务时简要说明修改内容、验证结果与未验证的设备范围。可以根据明确任务直接修改与验证，无需增加额外审批环节。

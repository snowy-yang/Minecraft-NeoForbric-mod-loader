# NeoForbric —— 架构与内部实现

[English](introduction.md) | 简体中文

写给 mod 开发者和加载器开发者。本文求精确，不求浅显：按 NeoForbric 实际执行的顺序讲清它做了什么，并直接写出真实的类型名和文件名。本文描述的是 **`main` 分支**，而不是某个发布版；想了解发布版包含什么、玩家如何安装，请读 [README](README.zh-CN.md)。

> **本文描述的是哪份代码。** 仓库里有两代实现。`neoforbric-kernel/`，即*自主内核*，`main` 分支交付的、`neoforbric-kernel-installer/` 安装的都是它，它也是本文的主题。`neoforbric-loader/` 是第一代（*焊接方案*：以真正的 fabric-loader/Knot 为宿主，同时驱动 FML 和 FancyModLoader）。它在安装好的实例里已不再运行，但内核构建和测试所依据的工具与暂存产物仍由它构建，见 [§14](#14-neoforbric-loader-还有什么用)。

术语：

| 术语 | 在本文中的含义 |
| --- | --- |
| **生态（ecosystem）** | Fabric、NeoForge，对应 `net.neoforbric.api.Ecosystem.FABRIC` / `NEOFORGE` |
| **Forge 系（Forge family）** | NeoForge 所属的 FML 血统：`mods.toml` 清单、FML、事件总线。一个运行时、一种清单（`META-INF/neoforge.mods.toml`）、一条事件总线 |
| **补丁基底（patched base）** | `patched-mc-neoforge-1.21.1.jar`：NeoForge 自己打过补丁的 1.21.1——原版 Minecraft 打上 NeoForge 的补丁，是 NeoForbric 运行的那个游戏 |
| **载体（carrier）** | `neoforge-runtime.jar`，NeoForge 的运行时 jar，作为被动的 ABI 提供者加载：它的类存在，但它的加载器生命周期从不运行 |
| **引导侧 / 游戏侧（boot side / game side）** | 前者指由系统类加载器加载的代码，后者指由 `NeoForbricClassLoader` 定义的代码 |
| **第三方（guest）** | 凡是属于第三方 mod 的东西（第三方 mixin、第三方 jar） |

---

## 1. 要解决的问题

三个加载器都默认整个进程归自己管。把它们的 mod 放在一起跑，会以五种相互独立的方式出问题；内核的每个部分都在应对其中一种。

1. **启动权。** Fabric 启动 Knot；NeoForge 启动 ModLauncher/BootstrapLauncher，外加一个 JPMS 模块层。两个转换型类加载器，意味着每个游戏类都有两份定义。
2. **一个类，三套补丁。** 两个 Forge 系都会给*同一批* `net.minecraft` 类打补丁，插入的钩子各不相同，而一个 JVM 里每个类只能有一个版本。总得有某种机制逐类、逐方法地决定保留谁的方法体，然后还得应付落败方那边的 mod 原本的预期。
3. **生命周期与注册窗口。** 每个生态都有自己的阶段、自己的总线、自己那段允许写注册表的窗口，对冻结何时发生也各有各的看法。
4. **可见性。** 每个加载器都维护自己的 mod 列表。一个 mod 问自己的加载器“装了 Sodium 吗？”或“我在哪个平台上？”，得到的答案在单加载器实例里成立，放到这里就错了。
5. **为另一个游戏写的第三方字节码。** Fabric mixin 是对着原版字节码写的。到了补丁基底上，锚点挪了位置，方法被拆开，字段改了类型，lambda 重新编号，父类也换了。

在 1.21.1 上，命名空间*在*上面这几条之列，内核在三个地方回答它。基底发布时用的是 Mojmap 名称，NeoForge mod 也按 Mojmap 编译，但真正的 Fabric mod——包括 fabric-api——用的是 **intermediary** 名称（`class_2378`、`method_10342`）：发布的 Fabric jar 里装的就是这些，而 Mojmap 基底一个也不认识。所以内核编译所对的 Fabric 模块由构建过程重映射（`FabricModuleRemapTool`，intermediary→Mojmap，作用于暂存的模块），`RefmapRemapper` 重写每个模块的 mixin refmap——只改 VALUES，intermediary→mojmap，KEYS 原样保留，因为注解字符串必须和 Mixin 读到的类字节完全一致——内核再把 Fabric 自己的 intermediary 映射和 Mojang 的拼起来，为加载器一侧命名。少了 refmap 这一半，tiny-remapper 会改好类，但每个 `@At` 成员锚点都会静默跳过：refmap 还指着 Mojmap 目标根本不认识的 intermediary 名字。（`KernelMappingResolver` 的 javadoc 记录的是 26.x 时代那次常量池扫描；在那个基底上恒等映射是真的。在现在这个基底上不是。）

mod API 同样不在其列。NeoForbric 不重新实现 Fabric API 或 NeoForge API：mod 调用的是装进来的那个真正的 Fabric API mod，以及载体里真正的 NeoForge 类。内核掌管的是原本由加载器掌管的部分：类加载、发现、生命周期、注册窗口，以及 Fabric Loader 自己的 API（§5.1）；此外还有补丁基底带来的、不得不做的修复。其中有两类修复是复现原有行为，而不是调用它：一是 NeoForge 的 coremod 改写，由内核自己执行（§5.2）；二是在补丁基底上不再发出的钩子事件，由内核重新发出（§8）。

## 2. 方案总览

一个 JVM，一个转换型类加载器，一个生命周期，一次注册表冻结。任何真正的加载器生命周期都不会启动：没有 Knot，没有 ModLauncher，没有 FancyModLoader 的发现流程，也没有模块层。

```
system class loader  (BOOT side)
 ├─ neoforbric-kernel.jar            net.neoforbric.kernel.{boot,classloading,discovery,fabric,transform,mixin,
 │                                 access,metadata,mapping,interop,ui,util,soak}, net.neoforbric.api,
 │                                 vendored net.fabricmc.api / net.fabricmc.loader surface
 └─ its dependencies               ASM, sponge-mixin, SAT4J, NightConfig, tiny-remapper, class-tweaker, mapping-io
     │  new NeoForbricClassLoader(owned, parent)
     ▼
NeoForbricClassLoader  (GAME side — the only loader that defines game/ecosystem classes)
 owned jars, in this order (KernelOwnedClasspath.compose):
   1. patched-mc-neoforge-1.21.1.jar        --gameJar
   2. neoforge-runtime.jar                  --runtimeJar   (the carrier)
   3. Minecraft's own libraries             --libraryPath  (owned: mods mixin into DataFixerUpper & co.)
   4. kernel-bundled: mixinextras-fabric.jar, neoforbric-kernel-runtime.jar   (extracted to .neoforbric-kernel/lib/)
   5. guest mod jars: Forge-family, then Fabric, nested jars included; newest version first within one mod id
```

### 2.1 引导侧与游戏侧

`neoforbric-kernel.jar` 由父加载器加载，不引用任何游戏类型。凡是引用 `net.minecraft.*`、`net.minecraftforge.*`、`net.neoforged.*` 或 `net.fabricmc.fabric.*` 的代码，都放在 `src/runtime/java` 里（`net.neoforbric.kernel.runtime` 包及其 `soak`/`transfer` 子包，共 130 个文件）：以 `compileOnly` 方式针对暂存的游戏产物编译，打包成 `neoforbric-kernel-runtime.jar`，内嵌在引导 jar 的 `META-INF/jars/` 下，启动时由 `KernelBundledJars` 解出，再作为托管 jar 加载。

引导侧通过*字符串*访问游戏侧（`Class.forName`、`getMethod`、ASM owner 名）。`KernelRuntimeClasses` 登记了所有这样的字符串；启动时，`KernelRuntimeClasses.verify(loader)` 会经由已经组装好的流水线把整条接缝加载一遍。这样一来，如果引导 jar 构建时缺了游戏侧那一半，或者游戏侧某个方法改了名，就会在日志最前面失败，而不是等到某个 mod 构造到一半才失败。游戏侧只有一个类 `net.neoforbric.kernel.runtime.KernelHudLayer` 仍由 `KernelHudBridge` 在运行时用 ASM `ClassWriter` 生成；游戏侧其余代码都是编译而来。

### 2.2 `NeoForbricClassLoader` 与委派表

`classloading.NeoForbricClassLoader` 是一个扁平的、不涉及 JPMS 的 `URLClassLoader`。`loadClass` 按以下顺序查询 `DelegationPolicy`：

- **ALWAYS_PARENT**：JDK、ASM（`org.objectweb.asm.`）、Mixin（`org.spongepowered.asm.`）、log4j/slf4j、NightConfig（某个载体自带一份未做 shade 的旧版本，否则它会按子优先规则胜出）、`net.fabricmc.api.`、`net.fabricmc.loader.api.`、`FabricLoaderInternals` 里列出的 Fabric Loader 内部类（按精确名称匹配）、`net.neoforbric.api.`，以及内核的引导包（`boot`、`classloading`、`transform`、`mixin`、`access`、`mapping`、`metadata`、`discovery`、`fabric`、`util`、`interop`）。整个 JVM 恰好只有一份。
- **ALWAYS_GAME**：`net.minecraft.`、`com.mojang.blaze3d.`、`net.minecraftforge.`、`net.neoforged.`、`net.fabricmc.fabric.`、`net.neoforbric.kernel.runtime.`、`com.llamalad7.mixinextras.` 以及 Mixin 的合成包。要么在这里定义，要么根本不定义。
- **其余情况走子优先**：哪个托管 jar 里有这个类就在这里定义，否则交给父加载器。

`tryDefineGameClass` 读取字节，先跑 Mixin 前的转换链（`setTransformer`），再跑 Mixin 阶段（`setMixinTransformer`），最后带着一个真实的 `ProtectionDomain` 调用 `defineClass`（以该 jar 作为代码来源，JourneyMap、spark 等 mod 就是靠它找到自己的 jar）。这个加载器的其他职责：

- **Mixin 前的字节。** `getPreMixinClassBytes` 给 Mixin 提供走完转换链、尚未织入的字节；如果给的是织入后的字节，织入器就会把自己的输出再织入一遍。
- **生成的类。** `putGeneratedClass` 存放由转换器合成的类（class-tweaker 枚举扩展）；如果读到 `null` 且没有对应的生成条目，Mixin 就知道该由自己来合成。
- **备援 jar。** `setRescueJars` 设定的是在跨 jar 仲裁中被取代的那些 jar；*只有*在没有任何托管 jar 含有该类时才会查它们，因此它们无法遮蔽胜出方（§4.3）。
- **jar 所属生态。** `setJarFamilies` 记录每个 mod jar 被仲裁归入了哪个生态；`familyOfClass` / `familyOfResource` 为环境剥离器和加载器探测改写器提供依据。
- **可重入的定义。** 在 Mixin 第一次 `select()` 期间构造的第三方配置插件，可能会去加载当前正在定义的类；`define` 会取回已经完成的那份定义，而不是因重复定义抛出 `LinkageError` 而失败。
- **包清单。** 定义包时会带上所属 jar 的清单属性，因为真正的 FML 会读取 `Package.getImplementationVersion()`。

### 2.3 三个游戏产物

它们都不在仓库里，也不在任何 NeoForbric 下载包里：每一个都嵌有 Mojang 或 NeoForge 的代码。它们在哪台机器上运行，就在哪台机器上构建：给玩家用的由安装器构建（§13），给开发者用的由 `neoforbric-loader/run/` 下的脚本构建（§14）。

| 产物 | 说明 |
| --- | --- |
| `patched-mc-neoforge-1.21.1.jar` | 原版 1.21.1 打上 NeoForge 自己的补丁，由 NeoFormRuntime 产出（`NfrtRunner`，结果 `gameJarNoRecomp`）——NeoForge 自己的游戏 jar |
| `neoforge-runtime.jar` | NeoForge 的 `-universal` jar 加上其 userdev 配置声明的库，合并成一个 jar（`NeoForgeRuntimeBuilder`） |

载体是*被动*的：它们的类会被定义，它们的事件总线也会被使用，但它们所属加载器的发现、排序和生命周期从不运行。内核只提供它们的代码会查询的身份信息（`PassiveSeeder`，§3.2）。

## 3. 启动顺序

### 3.1 入口

`boot.KernelClientLaunch.main` 和 `boot.KernelServerLaunch.main` 都只有一行：

```java
int code = CompatibilityLaunchBoundary.run(() -> KernelBoot.launch(KernelBoot.Side.CLIENT, args));
if (code != 0) System.exit(code);
```

兼容性拒绝只会在 `CompatibilityLaunchBoundary` 这一处变成进程退出（退出码 `78`，§12.4），安装损坏而被拒绝的启动也只在这里退出（退出码 `2`，§3.2 第 0 步）。其他任何离开引导过程的异常都会先在这里写进 `latest.log`（消息和堆栈），然后原样重新抛出，因为启动器展示的是这个文件，而不是 stderr。`KernelBoot.launch` 自己处理 `--gameJar`、`--runtimeJar`（可重复，一个值里也可以用路径分隔符连接多个 jar —— PCL2 这类启动器遇到重复的参数只保留最后一个）和 `--libraryPath`；其余参数，以及 `--` 之后的全部内容，都转给游戏的 `Main.main`。专用服务器不接受 `--gameDir`，所以 `KernelBoot` 在服务端会把它去掉。游戏版本从基底 jar 的 `version.json` 读取（读不到时回退为 `1.21.1`）。

### 3.2 `KernelBoot.launch` 的执行顺序

0. **启动输入** —— `LaunchInputCheck.require(gameJars, runtimeJars)`，按内容判断，并且在从这些 jar 里读取任何东西之前进行：基底的 `Block` 必须实现 NeoForge 的扩展接口（即补丁基底）；`--runtimeJar` 必须完整携带 NeoForge 的运行时（加载器 SPI、`ModContainer`、`FMLLoader`、`FMLEnvironment` 和它自己的 `neoforge.mods.toml`）。不通过时，每个问题和修复办法（重新运行安装器，*Built artifacts* 留空）都写进日志，并以退出码 `2` 停止；`-Dneoforbric.launchInputCheck=off` 只发出警告。Issue #13：空的"运行时" jar 曾经在后面每一步都得到一个合法的空结果，最后在 `KernelRuntimeClasses.verify` 里死在 stderr 上，`latest.log` 里只有五行 INFO。这项检查只看条目名，不看每一个类；它留给后续步骤处理的情况列在该类的 javadoc 里。
1. **跨 jar 仲裁预扫描** —— `DuplicateModArbiter.arbitrate(mods/, envType)` 清点所有根候选和内嵌候选，在任一生态的发现开始之前先定下唯一的选择（§4.3）。
2. **载体版本** —— `EcosystemVersions.record(runtimeJars)`，这样一旦某个 mod 的 `versionRange` 载体满足不了，发现它的当下就能报出来。
3. **Forge 系发现** —— 所有带 Forge 系清单的 `mods/*.jar`，再加上仲裁方案选中的 JarJar 子 jar（`META-INF/jarjar/`），后者取自该方案在 `.neoforbric-kernel/candidates/` 下按内容寻址解压出的副本（旧的解压器写入 `.neoforbric-kernel/jarjar/`，只在仲裁关闭时运行）。
4. **在场信息** —— `ModPresence.publishForgeFamily(…)` 必须在构建 Fabric 侧之前调用，因为 Fabric 侧会把它读回去。
5. **Fabric 发现** —— `KernelFabricEcosystem.scan`，然后对所有内嵌 jar 的并集再做一轮仲裁（`DuplicateModArbiter.arbitrateNested`）；落败方从两份列表中都移除。
6. **Mixin 配置声明** —— Forge 系配置，来源包括 mod jar、内嵌 mod jar *以及载体*（NeoForge 自己的 `neoforge.mixins.json`）。
7. **构建 Fabric 生态** —— `KernelFabricEcosystem.build` 创建 `FabricLoader` 视图（§5.1）。
8. **托管 classpath** —— `KernelOwnedClasspath.compose`（顺序见 §2）。
9. **静态审计** —— 在加载任何东西之前扫一遍所有第三方 jar：`PortingLayerAudit`（自带 `net.neoforged.*`/`net.minecraftforge.*` 的 Fabric jar）、`FabricApiModuleLossAudit`、`FieldDriftAudit`、`MergedBaseUncalledMethods.scanGuests`、`AbiLinkAudit`（jar 里引用了、却在任何载体、基底或已安装的 jar 中都不存在的 Forge 系类）。
10. **类加载器** —— `new NeoForbricClassLoader(owned, bootLoader)`、备援 jar、jar 家族、`LoaderProbePolicy.bindGuestLoader`、`KernelFabricLauncher.install`（mod 的 `addToClassPath` 最终落到这里）。
11. **转换链** —— 91 个 `chain.register(…)` 调用点（§6）。
12. `loader.setTransformer((name, bytes) -> chain.applyBeforeMixin(name, bytes, ctx))`；上下文类加载器换成游戏类加载器；绑定 `KernelLifecycle`、`KernelHudBridge`；`KernelLoadReport` 和 `CrashAttribution` 拿到运行目录（以及各自的关闭钩子）。
13. **加载器身份，先于 Mixin** —— `PassiveSeeder.seedNeoForgePaths`、`seedNeoForgeLoader`、`publishForgeLoadingList`。这一步必须早于第一个经过 Mixin 转换器的类，因为 Mixin 会在那一刻构造所有第三方 `IMixinConfigPlugin`，而这些配置插件会在 `<clinit>` 里读取 `FMLPaths`/`FMLLoader`。
14. **Mixin** —— 先放 Fabric 配置，再追加 Forge 系配置（§7.1）；注册之前先调用 `MixinConfigOwners.publish`；`KernelMixinBootstrap.init`。
15. `PassiveSeeder.reportDependencies()` —— 放在 Mixin 之后，因为它要报告的内容有一半（写给另一个 mod、却没有挂上的 mixin）是在 Mixin 解析配置时才记录下来的。
16. `KernelRuntimeClasses.verify(loader)` —— 让内核自己的游戏侧过一遍已经搭好的流水线。
17. `PassiveSeeder.seedAll` —— NeoForge 的 `FMLLoader`、`ModList`、路径。
18. 审计报告（其中有 `MixinOverlapLint`，§7.6）、`KernelLoadReport.writeEvidence()`，然后是 `CompatibilityDecision.requireContinuation(isClient)` —— 进入游戏之前的决策点（§12.4）。
19. `KernelFabricEcosystem.runPreLaunch()` —— Fabric `preLaunch` 入口点，在 Mixin 之后、任何游戏类之前运行。
20. 加载入口类（`net.minecraft.server.dedicated.DedicatedServer` / `…client.gui.screens.TitleScreen` 是普查标志点；实际调用的是游戏的 `Main`）。如果 `LifecycleHookInjector` 没找到它的触发点，**内核拒绝启动**（`missedRequiredExcision()`）。
21. `Main.main(gameArgs)` —— 原版启动流程，其中真正的加载器触发点已被重定向。

### 3.3 被重定向的触发点

入口点是 NeoForge 自己的。`transform.LifecycleHookInjector` 在每个入口点里重定向一条 `invokestatic`（改 owner 和 name，描述符不变）：

| 侧 | 补丁基底中真正的调用 | 现在调用 |
| --- | --- | --- |
| 服务端 | `net.neoforged.neoforge.server.loading.ServerModLoader.load(Z)V`，位于 `net.minecraft.server.Main.main`，在 `Bootstrap.bootStrap()` 之后 | `KernelLifecycle.onServerModLoading(boolean)`，随后是 Fabric 的 `Hooks.startServer(null, null)` 标记（`-Dneoforbric.fabricHooks=off` 会省掉它） |
| 客户端 | `net.neoforged.neoforge.client.loading.ClientModLoader.begin()V`，位于 `net.minecraft.client.main.Main.main`，在 `Bootstrap.validate()` 之后、`new Minecraft` 之前 | `KernelLifecycle.onClientModLoading()` |

客户端稍后对 NeoForge `ClientModLoader` 的调用（`finish`、`completeModLoading`）由 `MethodBodyNeuter` 替换成存根；`setupModResourcePacks` 则改为重定向到 `KernelLifecycle.onClientResourcePacks`（§9.2）。

在客户端，同一个注入器还让 `Main.logEarlyException` 先调用 `KernelLifecycle.onEarlyStartupFailure`。这是原版给 `Main.main` 开头三步（检测版本、构造参数解析器、解析参数）准备的处理器：它只打印到 stderr，随后 `main` 直接退出（249、252、251）而不抛出异常，所以没有这个钩子时，结束游戏的那个错误永远到不了 `latest.log`。

### 3.4 原生注册窗口 —— `KernelLifecycle.driveNativeRegistration`

两侧走的是同一套步骤（源码注释里给它们编了号）：

- **0** 预置 NeoForge 的 `LoadingModList`；在客户端加载载体的内置翻译（`CarrierLanguages`）。
- **1** 注册 NeoForge 的基线注册表（`PassiveSeeder.seedNeoForgeRegistries`）；应用 NeoForge 自己的注册表修改（同步标志、回调）。
- **2** `registerNeoForgeContent` —— 注册窗口本身：
  1. 在内核创建的总线和容器上构造 `NeoForgeMod`（`KernelModContainerFactory`）；
  2. 构造每一个 Forge 系 `@Mod`（`KernelModLoader.constructMods`，§5.2/5.3）；
  3. 注册第三方 `@EventBusSubscriber` 类（`KernelEventSubscribers.registerAll`）；
  4. 发布 `FMLConstructModEvent`；注入 capability；
  5. NeoForge `GameData.vanillaSnapshot`，然后**解冻**；发布 `NewRegistryEvent`；
  6. 按 NeoForge 的注册顺序，在每条总线上为每个注册表触发 `RegisterEvent`；
  7. 打开 `minecraft:root`，运行 Fabric `main` 入口点（在客户端，只有 `-Dneoforbric.fabricMainInConstructor=off` 时才在这里运行；见 §3.5）；
  8. 属性事件、生成位置规则、`BlockEntityTypeAddBlocksEvent`、mod 添加的游戏规则分类、NeoForge 的提示框追加器；
  9. `closeRegistrationWindow`（在 `finally` 里）：建立方块→物品的关联，**冻结**，重建 NeoForge 的方块状态→id 映射，重新排序 NeoForge 的创造模式物品栏标签页。
- **2a–2c3** 校验 REGISTRATION 桥；在 `ModList` 中发布 NeoForge 基线；加载 STARTUP/COMMON 配置（客户端上还有 CLIENT）；接上两个载体自己的 `@EventBusSubscriber` 类；安装客户端重载监听器桥。
- **3a** 声明数据包注册表（`DataPackRegistryEvent.NewRegistry`，Fabric 动态注册表双向镜像）—— 在客户端会推迟到 Fabric 入口点之后（`DatapackRegistryDeclaration`）。
- **3a2** 启动游戏总线（`startGameBuses`）—— 必须在初始化阶段之前，因为在尚未启动的总线上调用 `IEventBus.post` 会静默返回。
- **3b**（服务端）向两个 Forge 系的第三方 mod 发布初始化生命周期（`fireModSetupLifecycle`）：通用初始化、专用服务器初始化、NeoForge 的 `RegistrationEvents.init()`（逐步执行，见 `RegistrationEventSteps` —— 它会发布 `RegisterCapabilitiesEvent` 和 `RegisterDataMapTypesEvent`）、IMC 入队/处理、加载完成；各阶段之间，延迟任务在每个 Forge 系各自使用的线程上运行（`NeoDeferredWork`；失败由 `DeferredWorkFailures` 读回）。随后写出 `load-report.txt`，并再次询问 `CompatibilityDecision.requireContinuation` —— 这是加载结束时的决策点。在客户端，这些阶段（包括通用初始化）全部推迟到 `onNeoClientSetup`（§3.5），因为此时 `Minecraft.getInstance()` 还是 null。
- **3b2** 打开晚注册的配置（在构造或初始化期间注册的）。
- **3c**（服务端）关闭 NeoForge 的负载注册阶段（`setupNeoForgeNetwork`）。这一步必须放在初始化之后：mod 会在 `FMLCommonSetupEvent` 里注册负载。

注册窗口只有一个，冻结也只有一次。焊接方案撞上的那堵“Tags not bound”墙（两个生态轮流重新冻结）在这里不可能出现。

### 3.5 `Minecraft.<init>` 里的客户端专用钩子

Fabric 和 NeoForge 在构造函数里需要的状态正好相反，所以内核设了两个锚点：

- `ClientEntrypointHookInjector` → `KernelLifecycle.onClientEntrypoints()`，在 `Options` 存在之前：重新打开注册表（以及 MinecraftForge 的注册表闸门），构造那些因为过早去拿 `Minecraft` 而被暂缓的 NeoForge mod，先运行 Fabric `main` 再运行 `client` 入口点（即 Fabric `Hooks.startClient` 的顺序），重新关闭并重新冻结，打开晚注册的 CLIENT 配置，然后声明数据包注册表。这次冻结前后就是 Fabric 的注册表冻结点：装了 fabric-registry-sync 时，Fabric mod 挂在 `BuiltInRegistries.freeze()` 头尾、或 `bootStrap()` 里调用 `freeze()` 前后的 `@Inject` 在这里运行（`FabricFreezeHookMixinAdapter`），也就是原生 Fabric 冻结的地方——入口点之后，`Minecraft.getInstance()` 已经有值。LiquidBounce 用这样的注入建它的创造模式标签页，留在 `Bootstrap` 里时拿不到客户端，直接崩溃。
- `NeoClientSetupHookInjector` 挂在基底调用 `ClientModLoader.finish()` 的位置 → `KernelLifecycle.onNeoClientSetup()`，在 `options` 赋值之后：校验 CLIENT_INIT 桥；预加载客户端资源管理器（MinecraftForge 在第一次资源重载内部运行 mod 加载，它的 mod 都指望在客户端初始化时就能读到自己的资源；`-Dneoforbric.clientResourcePreload=off`）；然后是 `fireClientSetupLifecycle`：通用初始化、客户端初始化、`RegistrationEvents.init()`、IMC、加载完成 —— 和真正的 NeoForge 一样，此时注册表处于冻结状态 —— 接着写出 `load-report.txt` 并做加载结束时的决策（`requireClientContinuation`），再处理晚注册的配置，最后关闭负载注册阶段。

## 4. 发现与仲裁

### 4.1 读取清单

- `discovery.NeoForbricModDiscoverer` 按描述文件给 jar 分类 —— `fabric.mod.json`、`META-INF/mods.toml`、`META-INF/neoforge.mods.toml` —— 并报告一个 jar 携带的*每一份*清单。最终加载哪一份由策略决定（§4.2）。
- `metadata.forge.ModsTomlParser`（净室实现；TOML 词法分析用 NightConfig），配合 `ForgeModsToml`、`ForgeModEntry`、`ForgeDependency`；`ForgeVersionRangeTranslator` 把 Maven 版本范围转成 Fabric 风格的谓词，所以 `net.neoforbric.api.UnifiedDependency` 只有一种写法（由 `VersionPredicate` 求值）。`UnifiedDependency` 还保留了 Fabric 里没有对应说法的两个维度：顺序（`BEFORE`/`AFTER`）和适用的侧。
- `fabric.FabricModMetadataParser` 是完整的 `fabric.mod.json` v1 读取器（入口点，包括适配器形式；`jars`；按侧区分的 `mixins`；`accessWidener`；`custom`）。`fabric.FabricModDiscovery` 会顺着 Fabric JiJ 继续发现（解压缓存在 `.neoforbric-kernel/jij/`）；`environment` 排除了当前侧的 mod 会被跳过，和 Fabric 上一样。
- 内嵌的 Fabric mod 按 fabric-loader 0.19.5 的 `ModSolver` 的规则决定加不加载（`fabric.NestedFabricRequirements`，由 `NestedCandidateInventory` 执行；没有选择计划时由 `FabricModDiscovery` 执行）：对 `minecraft` 或 `java` 的 `depends` 不包含当前版本，或者 `breaks` 包含当前版本，它就不加载；硬依赖只能由这些被排除的 mod 满足、或者只被它们内嵌的内嵌 mod 也一起不加载。实例是 ViaFabric：它在 `viafabric-mc26-2` 旁边还内嵌了 `viafabric-mc26-1`（`minecraft >=26.1 <=26.1.2`），原生只加载 `viafabric-mc26-2`。只判定 Fabric mod 在自己的 `jars` 里声明的 jar。NeoForge/MinecraftForge mod 内嵌的 jar 不判定，哪怕里面只有一个 `fabric.mod.json`，因为 Fabric Loader 从不打开没有 `fabric.mod.json` 的 jar。父 jar 的 `META-INF/jarjar/metadata.json` 声明过的子 jar 归 FML 管，FML 把它当成父 mod 的库加载；只是放在 `META-INF/jars/` 或 `META-INF/jarjar/` 里、在这个文件里没有条目的 jar，没有任何原生加载器会加载，内核保留它是因为内核对 Forge 系 mod 的遍历一直都会走这两个目录。两条路都能走到的 jar 保留，不管遍历先走了哪条。有一处和原生不同：内核要加载的某个 mod 硬依赖某个 id，而会加载的 mod 里没有一个满足这条要求时，被排除的副本中满足要求的那些会被留下，连同把它一路内嵌到某个已加载父 mod 的那些 jar，并打一行 WARN：`[NeoForbric/JiJ] nested <id> <version> in <parent> loaded although Fabric Loader would leave it out (<原因>): <依赖方> requires <id> <范围>, and nothing else installed meets that`。如果已安装的副本没有一个满足要求，而且会加载的 mod 里根本没有这个 id，就留下所有被排除的副本（规则加入之前内核本来就加载它们），WARN 的结尾换成 `<依赖方> requires <id> <范围>; no installed build meets that, and no other <id> would load`。依赖方在 `mods/` 里时原生会拒绝启动；内核在缺依赖时也照样加载这种 mod，所以把提供者排除掉只会让它少一个依赖。最终仍被排除的每个 mod 都打一行 `[NeoForbric/JiJ] nested <id> <version> in <parent> left out: <原因>`，它内嵌的 mod 也一样：被排除的 jar 仍会被打开，这样已加载的 mod 需要其中某个 jar 时才看得见。`mods/` 里的 jar 从不在这里判定；`fabricloader`/`mixinextras` 的范围、读不懂的范围、以及没有任何已安装 mod 能满足的依赖都不会让任何东西被排除。没有选择计划时（`-Dneoforbric.crossJarArbitration=off`），`FabricModDiscovery` 只走 `mods/` 和 Fabric 的 `jars`，所以 `KernelBoot.scanFabricMods` 把它读不到的 jar 的硬依赖交给它：Forge 系 mod 的，以及它们 JarJar 里那些 jar 的 Fabric 清单的（这些 jar 在这条路上不是 Fabric 容器，但在 classpath 上）。`-Dneoforbric.nestedRequirements=off` 恢复为全部加载；`off:<id>,<id>` 只放过这几个 id（内核不读 Fabric 的 `config/fabric_loader_dependencies.json`）。
- `discovery.ModAnnotationScanner` 按字节码描述符查找 `@Mod` 类；`ModFileScanner` 构建完整的 `ModFileScanData`（NeoForge 和 MinecraftForge 的形态不同：`EnumHolder` 与 `EnumData`），因为 JEI、Jade、Sophisticated Core 和 Sodium 都通过 `ModList.getAllScanData()` 找自己的插件。
- `net.neoforbric.api.DiscoveredMod` 是唯一的 mod 模型。

`--scan` 模式（`boot.Main --scan --mods <dir> --report out.json`）只运行发现，并写出确定性的 JSON；`run/diff-oracle.sh` 用一个独立的 Python 读取器解析同样的清单，与它交叉核对。

### 4.2 一个 jar，多份清单 —— `MultiLoaderArbiter`

通用 jar 为每个加载器各带一份清单和一个胶水类。如果不仲裁，两个生态都会初始化它。`MultiLoaderArbiter.ownerOf(jar)` 按偏好挑出一个 —— 默认是 **NeoForge、Fabric**（`-Dneoforbric.multiLoaderPreference=…`）。NeoForge 排在最前，是因为游戏基底本来就是 NeoForge 的。如果一个 jar 带着某个加载器残留的清单、却没有对应的实现，就不会交给那个加载器。jar 仍然留在 classpath 上；这里只决定它的身份。

### 4.3 两个 jar，同一个 mod id —— `DuplicateModArbiter`

把一个 Fabric 整合包和一个 NeoForge 整合包合在一起，同一个 id 下就会出现两个*文件*。落败方必须**移出** classpath（否则按“第一个 URL 胜出”的规则，它可能遮蔽胜出方，还会带进自己的 mixin 配置）。

- **清点** —— `NestedCandidateInventory` 遍历每一个根 jar 和内嵌 jar（深度 ≤ 8，有防 zip 炸弹的字节上限，不限归档数量），构建出一张由候选和父→子边组成的图；内嵌 jar 解压到 `.neoforbric-kernel/candidates/` 下，以 SHA-256 为键。
- **选择** —— `ReachableCandidateSelector` 为整个实例构建一个布尔模型（内嵌候选只能经由被选中的父 jar 存在），并用 SAT4J 求解；`JointCandidateSelector` 提供子句：硬依赖/软依赖、`breaks`/`incompatible` 互斥，以及来自 `CandidateContractScanner` 的符号契约（只采用强到足以约束选择的证据 —— 例如某个 mixin 必需的成员）。搜索按工作量设限，从不按时钟设限（`CONFLICT_BUDGET`、`VARIABLE_LIMIT`、`-Dneoforbric.arbitrationMaxNodes`，默认 100 000），所以同一个文件夹在任何机器上都会选出同样的 jar。
- **偏好** —— 顶层重复：`-Dneoforbric.dupeIdPreference`，未设置时回退到 `multiLoaderPreference`。内嵌重复：`-Dneoforbric.nestedDupePreference`，默认 NeoForge、Fabric —— 之所以这样排，是因为多加载器库针对各加载器的构建会把该加载器缺少的阶段换成存根，而这个顺序能让调用空方法的调用方最少（javadoc 里记录了定下这个顺序的案例）。
- **覆盖设置** —— `-Dneoforbric.modOwner=sodium=fabric,…` 或 `<rundir>/neoforbric-mods.txt`（每行一条 `<mod id> = <loader>`；实例第一次出现重复时，内核会写出一份带注释的模板）。命令行优先于文件。
- **关掉的 jar** —— `<rundir>/neoforbric-disabled.txt` 列出 `mods/` 里的 jar 文件名（注释和写错的行与 `neoforbric-mods.txt` 的处理方式相同）。`DisabledMods` 让这些 jar 不进入扫描，所以它们不会成为声明；它们进入 `Decision.suppressedJars`，但绝不进入 `rescueJars`；`-Dneoforbric.crossJarArbitration=off` 时仍会缓存一份只含这些 jar 的决定。`load-report.txt` 会列出它们。
- **残留处理** —— 落败的生态会得到一个仅在场的别名，让 `isLoaded(id)` 仍能作答（`Decision.aliases`）；对于已加载的 mod，它在另一个生态的构建可以作为最后手段借出缺失的类（`rescueJars`）；`ArbitratedAwayClasses` 统计落败构建里有、胜出方却缺少的内容。`MergeReport` 写出 `.neoforbric-kernel/merge-report.txt`，逐条解释每项决定。
- `-Dneoforbric.crossJarArbitration=off` 会完全关闭这一机制。

### 4.4 回答“我在哪个加载器上？”和“X 装了吗？”

- `LoaderProbePolicy` + `transform.LoaderProbeRewriter`（COREMOD 阶段）改写单加载器第三方类里的 `Class.forName` 调用点，让平台探测（`FMLLoader`、`FabricLoader`）按该 jar 被仲裁到的生态作答（`-Dneoforbric.loaderProbes=off`）。
- `net.neoforbric.api.ModPresence` 给出跨生态的答案。`transform.ForeignModPresenceInjector` 把它以逻辑或并入两个 Forge 系的 `ModList.isLoaded`；Fabric 侧把每个 Forge 系 mod 注册成仅在场的容器（只有身份 —— 没有入口点、mixin 或资源），让 `FabricLoader.isModLoaded` 能作答；Forge 系的列表也预置了 Fabric mod（`-Dneoforbric.crossEcosystemPresence=off` 恢复单加载器的答案）。`net.neoforbric.api.ModIds` 负责映射各生态拼写不同的 id（`cloth-config` / `cloth_config`）。
- `ModConstructionOrder` 按拓扑顺序构造 Forge 系 mod —— 一个 mod 排在它依赖的、以及它声明 `AFTER` 的所有 mod 之后；并列时按字母序；遇到环会点名报出，不会悄悄拆开（`-Dneoforbric.modOrder=name` 恢复按文件名排序）。Fabric mod 按 Fabric Loader 自己的顺序初始化，即按 mod id 排序（`FabricLoadOrder`；`-Dneoforbric.fabricOrder=off` 让它们回到拓扑顺序）。

## 5. 由内核驱动的两个生态

### 5.1 Fabric —— 不运行任何 Fabric Loader 代码

- `fabric.KernelFabricLoader` *就是* `FabricLoader` 单例：它是内核状态的一个视图，入口点索引在 mod 代码运行之前就已冻结。`KernelModContainer` 通过 zip `FileSystem` 暴露每个 jar；`KernelLanguageAdapters` 支持 `languageAdapters`（fabric-language-kotlin）；`KernelObjectShare`、`KernelVersion`、`KernelMappingResolver`（恒等映射）补齐了整套 API。
- 面向 mod 的 API 以原名内置：`src/main/java/net/fabricmc/api`（7 个文件）和 `src/main/java/net/fabricmc/loader`（30 个文件：`loader.api.*`，加上 mod 实际会链接到的那一小部分内部类——`FabricLoaderImpl`、`ModContainerImpl`、`EntrypointStorage`、`Hooks`、`FabricLauncher`/`FabricLauncherBase`、`DefaultLanguageAdapter`、`StringUtil`，以及旧版的 `net.fabricmc.loader.FabricLoader`）。`FabricLoaderInternals` 按确切名称把这些内部类固定交给父加载器；`-Dneoforbric.fabricImpl=off` 则不提供它们。报告的 API 级别是 `KernelFabricEcosystem.FABRIC_LOADER_API_LEVEL = "0.19.3"`。
- 入口点：`preLaunch` 在 Mixin 之后（§3.2 第 19 步）；`main` 在注册窗口内（服务端）或在 `Minecraft.<init>` 里（客户端，默认）；`client` 在 `Minecraft.<init>` 里；`server` 在专用服务器上。每个 mod 的入口点都隔离调用——抛出异常的 mod 会记录下来并跳过——调用时该 mod 对应的 NeoForge `ModContainer` 处于激活状态（`KernelForeignShimContext`），因为 Fabric mod 可能持有某个多加载器库的 NeoForge 构建。
- Fabric 的访问加宽器 / class tweaker 在 `ACCESS` 阶段运行（`access.ClassTweakerTransformer`）；`@Environment` 剥离在 `ENV_STRIP` 阶段（`EnvironmentStripTransformer`，只作用于仲裁归 Fabric 的 jar 里的类）。

### 5.2 NeoForge

- **身份** —— `PassiveSeeder` 创建当前的 `FMLLoader`、`FMLPaths`，以及根据本次启动选中的 jar 构建的 `LoadingModList` 和 `ModList`——不做发现，没有模块层，不排序。
- **构造** —— `KernelModLoader`：一个由 `BusBuilder` 构建的 `IEventBus`，加上一个内核的 `ModContainer`（`net.neoforbric.kernel.runtime.KernelModContainer`）；构造函数参数按类型填充（`IEventBus`、`Dist`、`ModContainer`）。
- **总线** —— 内核自己发布的 mod 总线事件都走 `KernelLifecycle.postModBusEvent`；初始化阶段走 `runtime.KernelNeoSetup`；延迟任务在 NeoForge 原本运行它的那个线程上运行（`NeoDeferredWork`）。
- **枚举扩展** —— `NeoEnumExtensions` 把每个 jar 的 `META-INF/enumextensions.json` 交给 NeoForge 自己的 `RuntimeEnumExtender`；`NeoEnumExtensionInjector` 在 COREMOD 阶段接近末尾的位置注册（这个位置很关键：它会在那一刻定义 FML 类），而且只在有 mod 声明了扩展时才注册。
- **Coremod** —— NeoForge 的 coremod jar 从不加载；`transform.NativeCoremodParity` 代为执行它的改写（花盆 `potted`、生物群系气候/效果、结构设置、`finalizeSpawn`），时机在 Mixin *之后*，和 NeoForge 运行它们的时机一致。

### 5.3 事件之外的跨生态服务

- **网络** —— 在 Fabric API 和 NeoForge 共用同一个原版频道 id 的地方，`interop.PayloadInterop` 按运行时的负载类选择自定义负载编解码器；`CommonNetworkInteropInjector` 仲裁两边都要占用的 `c:version` / `c:register` 频道（`-Dneoforbric.commonNetworkInterop=off`）；内核把 NeoForge 的注册表同步（装了 fabric-api 时还有 fabric-api 的）应用到游戏注册表上，`KernelRegistryRevert` 在断开连接时恢复连接之前的 id；`NetworkChannelCensus` 比对已注册的频道和已声明的频道。`KernelClientSmoke` 的 `-Dneoforbric.clientSmokeCarry=<tick>` 演练用游戏自己的键盘和鼠标输入把整条链跑一遍。
- **物品/流体/能量传输** —— `KernelTransferInterop` + `runtime/transfer/` 桥接 Fabric 的传输 API 和 NeoForge 的 `ResourceHandler`，装了 Team Reborn Energy 时也桥接它（`-Dneoforbric.transferBridge=off`、`-Dneoforbric.hopperFabricStorage=off`）。只在相关 API 存在时启用，是否存在通过查找资源来判断。

## 6. 转换流水线

`transform.TransformPhase` 规定了顺序：`RAW_PATCH`、`DEOBF_REMAP`、`ENV_STRIP`、`ACCESS`、`COREMOD`、`FABRIC_BUILTIN`、`MIXIN`。`TransformChain` 运行链上的阶段（`RAW_PATCH` … `FABRIC_BUILTIN`）；同一阶段内，先按 `predepends` 做拓扑排序，再按 `sortIndex`，最后按注册顺序。`MIXIN` 是终结阶段，不能注册进链里。这个基底上 `RAW_PATCH` 和 `DEOBF_REMAP` 里同样什么都没注册：intermediary→Mojmap 的翻译发生在这些类被提供之前——内核编译所对的模块在构建时重映射，refmap 重写随模块重映射一起完成（§1）——所以 guest 类到达加载器时已经在基底的命名空间里了。

`KernelBoot` 注册了什么（91 个调用点，部分有条件）：

| 阶段 | 注册内容 |
| --- | --- |
| `ENV_STRIP` | `EnvironmentStripTransformer` |
| `ACCESS` | `ClassTweakerTransformer`（Fabric），以及由每个 mod jar **和两个载体**的 `META-INF/accesstransformer*.cfg` 构建的 `access.AccessTransformer`——载体的 AT 之所以要紧，是因为合并在哪里保留了另一个 Forge 系的方法体，就在哪里连带保留了那个方法体的访问修饰符（`MenuScreens.register` 合并后成了 private） |
| `COREMOD` | 86 项注册：加载器探测改写器、Mixin 织入器插槽（`FmlContextLoaderRewriter`、`ModuleClassLoaderInitInjector`）、`GuestMixinPluginGuard`、生命周期重定向、`NeoForbricMergedBaseCompatTransformer`、capability 组合，以及 `transform/` 里 70 个 `*Injector` 类中的大部分，每个类要么修复一处合并弄坏的具名接缝，要么接上一座桥（§8） |
| `FABRIC_BUILTIN` | `RestoredAccessTransformer`（给 COREMOD 恢复出来的成员重新应用访问修饰符）、`MergedBaseFrameRecomputer`（第三方类的栈映射帧引用了合并删掉的父类时，重新计算这些帧） |

Mixin 后阶段（`KernelMixinBootstrap`）是固定的组合：

```
Mixin (via MixinWeaverSlot) → NativeCoremodParity → PostMixinFixups → InterfaceDefaultConflictRepair
                           → ForgeTransferShapeAudit.certify
```

按类别列出值得一提的修复（每个类是因为哪个案例写出来的，请看它的 javadoc）：

- **基底不变量** —— `NeoForbricMergedBaseCompatTransformer`（lambda 引导句柄与 static 属性的冲突；按键映射的 `MAP` 初始化器，把原版的 `KeyMapping.MAP` 作为“按键 → 映射”的视图加回去（`KernelKeyMappingMap`；NeoForge 把它改了类型，LiquidBounce 在屏幕里每次按键都会读它）；原版的 `FriendlyByteBuf.writeByte(int)` 在 NeoForge 的重编译把 byte 型调用绑到其扩展的 `writeByte(byte)`（只是转发回原处）的那些地方重新出现（10 个网络 `write` 方法里的 14 处；ViaFabricPlus 的能力标志重定向锚定在原版调用上；`-Dneoforbric.vanillaWriteByte=off`）；把基底里烧死的对 `net/neoforbric/loader/impl/…` 的调用重定向到 `net.neoforbric.kernel.interop`），

- **包与数据** —— `DataPackHookInjector`、`ClientPackHookInjector`、`PackMetadataFailSoftInjector`、`PackOverlayMutabilityInjector`、`NullPackGuardInjector`、`PackScreenHiddenFilterInjector`、`RegistryDirectoryOwnerInjector`（§9）。
- **UI** —— `ModsButtonRedirector`（NeoForge 的暂停菜单 lambda 打开 `KernelModListScreen`）、`HudElementBridgeInjector`、`CreativePagerBridgeInjector`、`EarlyKeyMappingRegistrationInjector`、`CreativeSearchTreesInjector`。最后这个修的是生产者和消费者被合并劈到两边：合并后的 `SessionSearchTrees` 里，原版的 `updateCreativeTooltips(Provider, List)` / `updateCreativeTags(List)`（以及 `getSearchTree`）保留的是 MinecraftForge 的方法体，把搜索树存进一个私有 map；而创造模式物品栏界面是 NeoForge 的，只读 `CreativeModeTabSearchRegistry`。界面只有在 `CreativeModeTabs.tryRebuildTabContents` 报告标签页变了时才自己重建搜索树，所以一个自己重建标签页、再用原版方法刷新搜索的 mod（TCDCommons，每次进世界都这么做）会让整局的创造模式搜索都搜不到东西。现在这三个方法体改为调用 `KernelCreativeSearch`，对每个带搜索栏的标签页走 NeoForge 的带 key 方法；`-Dneoforbric.creativeSearchTrees=off` 关闭。`-Dneoforbric.clientSmokeCreativeSearch=<tick>[,query…]` 会在真实的创造模式界面里打字搜索并把结果网格写进日志。
- **插桩** —— `ClientSmokeTickInjector`（除非 `-Dneoforbric.clientSmoke=true`，否则不起作用）、`EventChainAuditInjector`（除非 `-Dneoforbric.eventChainAudit=<report>`，否则不起作用）、`ServerTickSamplerInjector`、`CompatibilityPromptTickInjector`、`ServerCompatibilityTickInjector` —— 专用服的完成节拍接缝：在 `MinecraftServer.tickServer` 的每个正常返回前织入 `KernelServerCompatibilityTicks.onCompatibilityTick`，让启动之后才出现的必需损失通过游戏自己的保存路径停下一个严格模式的服务器。
- **加固** —— `ChunkExecutorGuardInjector`（服务器停止后，拒收提交给其区块执行器的任务；`-Dneoforbric.chunkExecutorGuard=off`）。

承诺会落到某个类上的转换器要声明这个类（`AnchorSet`）；`AnchorLedger` 会报告经过转换器却没有被改动的类（记为一次 **Miss**，立即以 ERROR 级别记日志），并在普查标志点报告从未加载过的类。`RepairDriftCensus` 把这些声明拿到一个*候选*游戏构建上重放——升级载体时要问的正是这个问题。

## 7. 合并基底上的 Mixin

### 7.1 内核就是 Mixin 服务

`mixin.NeoForbricMixinService` 通过 `META-INF/services/org.spongepowered.asm.service.IMixinService` 注册（连同 `NeoForbricMixinServiceBootstrap` 和 `NeoForbricGlobalPropertyService`）；Mixin 库用的是 Fabric 的分支（`net.fabricmc:sponge-mixin`，版本见 `gradle.properties` 里的 `mixin_version`），MixinExtras 则是内核自带的 `mixinextras-fabric`（放在游戏侧，因为它生成的 `LocalRef` 类必须和游戏共用同一个加载器）。`KernelMixinBootstrap.init` 绑定服务、注册每个配置、安装 `KernelMixinErrorHandler`、把织入器装成流水线的最后一级，然后把环境推进到 `INIT` 和 `DEFAULT`。配置的准备（以及插件的构造）发生在第一个经过转换器的类上，也就是 `KernelRuntimeClasses.verify`。

顺序：Fabric 配置按 Fabric Loader 的顺序，Forge 系配置追加在后面。Mixin 按优先级排序，注册顺序只用来决定同优先级的先后，所以验证最少的那一组会成为最外层，包在已知可靠的栈外面。`MixinWeaverSlot` 让第三方 mod 能按 NeoForge 允许的方式替换织入器（LibJF 包装了 `FMLMixinClassProcessor.transformer`）。

### 7.2 Mixin 读取之前，第三方配置会被怎样处理

`NeoForbricMixinService` 会改写每个配置的 JSON：

1. **整份配置的拦截** —— `MixinConfigPolicy.isDisabled`：`MergedBaseMixinCompat` 里的内置列表（`-Dneoforbric.mergedBaseCompat=off` 去掉这份列表；`-Dneoforbric.enableMixinConfigs` 把某个配置强制加回来），再加上 `-Dneoforbric.disableMixinConfigs`（逗号分隔，支持末尾的 `*` 通配）。
2. **放宽** —— 名字不以 `neoforbric` 开头的配置都是第三方配置，一律放宽：`injectors.defaultRequire → 0`、`overwrites.requireAnnotations → false`、`required → false`。单个注入器上的 `require`/`expect` 仍然优先。`-Dneoforbric.relaxGuestMixins=off` 恢复严格行为；放宽关闭时，`-Dneoforbric.relaxMixinOverwrites` 会放宽指定的配置；`-Dneoforbric.mixinDiagnostics` 让注入要求保持严格（这样每一处不匹配都会报出来），同时保留 `required=false`。
3. **逐个丢弃 mixin** —— 取 `KernelGuestMixinAdapter.unfitMixins`（见下文）、手工列表 `MergedBaseMixinCompat.SUPPRESSED_MIXINS` 和 `-Dneoforbric.suppressMixins=config:Mixin,…` 三者的并集，再减去 `-Dneoforbric.keepMixins`。丢弃一个 mixin，也会连带丢弃所有依赖它添加的接口的 mixin。

### 7.3 `MixinFit` —— 看能否解析，不看出处

`KernelGuestMixinAdapter` 让 `MixinFit.evaluate` 解析第三方 mixin 点名的每个锚点——每个 `@Shadow`、每个注入器的目标方法、每个 `@At(target=…)`——解析对象是合并后目标类经过**转换链之后**的字节：

| 判定 | 含义 | 默认动作 |
| --- | --- | --- |
| `FIT` | 所有锚点都能解析 | 应用 |
| `PARTIAL` | 部分能解析 | **应用**（只有在 `-Dneoforbric.mixinFit=strict` 下才丢弃）；总会报告 |
| `UNFIT` | 一个都解析不了 | 丢弃 |
| `HAZARD` | 能顺利应用，但 @Shadow 了一个被合并弄成孤立的字段 | 丢弃 |

细化规则：纯 accessor/invoker mixin 永远保留；由另一个 mod 的 mixin 满足的锚点（`ForeignMixinTargets`、`MixinAddedMembers`）算作已解析；`@Group` 注入器按组整体判定；如果一个注入器只绑定在合并后的游戏里没有任何地方调用的合并基底方法上，它就**不算**已解析（活性检查，`MergedBaseUncalledMethods`，`-Dneoforbric.mixinFit.liveness=off`），除非某个已安装的 mod 调用了这个方法；只按名字写的 `@Inject` 选择器，如果它绑到的方法（Mixin 取目标类里第一个同名方法）不是 handler 写给的那个（该方法的全部参数再加上与返回类型对应的 callback，或者只有 callback），Mixin 会报 "Invalid descriptor" 拒绝，这种也**不算**已解析（`-Dneoforbric.mixinFit.handlerFit=off`）：合并基底可能在原版方法的位置上放了载体的重载，不管 mod 属于哪个生态。`MixinOverloadPin` 会钉住的选择器（见 7.4）按它落到的那个重载来判，所以钉住和这条规则不会同时作用在一个注入器上；`@Surrogate` 只按 Mixin 查找它的方式算数——handler 的名字、与绑到方法的 callback 描述符完全一致、注解是可见的。如果这个名字恰好只绑到一个方法、handler 也不抓局部变量，并且它的某个 `@At` 在那个方法里一定能找到注入点（`HEAD`；方法里有返回时的 `RETURN`/`TAIL`；成员确实在方法里、并且数量超过 `ordinal` 的 `INVOKE`、`INVOKE_ASSIGN`、`FIELD`、`NEW`；带 slice 的一律不算），这个缺失就是一次*拒绝*（`MixinFit.Rejection`）：Mixin 在找到的每个注入点上检查 handler，在第一个点上直接抛异常，不看 `require`，异常让这个 mixin 对该类的应用整体失败——排在它后面的注入器全部跟着丢，配置仍是 required 的话整个游戏也停。如果不能确定有注入点，Mixin 可能一个点也找不到，什么也不注入、也不抛异常，所以这种绑定仍按普通缺失处理，日志那一行会写明原因（`-Dneoforbric.mixinFit.rejectionPoint=off` 恢复旧做法，把这种绑定都当成拒绝）。所以带着拒绝的 mixin——不管是 `PARTIAL`、因为在另一个 mod 的类上有缺失而保留的，还是 `UNFIT` 但因为另一个 mod 的 mixin 也改这个类而保留的——都不会原样保留：如果 mixin 里没有别的代码调用它、它不在 `@Group` 里、也没有哪个目标按原写法绑上它，就在 Mixin 读到 mixin 之前把这一个注入器剪掉（`GuestInjectorPruner`，见 7.4，对 Mixin 实际收到的节点、带着目标类的代码再按同一条规则问一次），mixin 的其余部分照常应用，剪掉的注入器记一条 `CONFIRMED` 发现，作者自己给它定的次数（`require`，没有就看 `defaultRequire`）至少为一时算 required；剪不了就像 `UNFIT` 一样整个丢掉。被内核修复整个顶替的 mixin（`SupersededMixins`）总是整个丢掉，那一行不向玩家发问，看到修复生效后自动消解。按名字保留的 mixin（`MergedBaseMixinCompat.KEPT_MIXINS`、`-Dneoforbric.keepMixins`）不经判定，原样交给 Mixin。`-Dneoforbric.guestInjectorPruner.refused=off` 让这种 mixin 照旧交给 Mixin，`PARTIAL` 汇总里也会单独计数。`PARTIAL` 和 `UNFIT` 的 mixin 都会先交给 `MixinRetarget` 问一次，改写后缺的锚点更少、是适配器会保留的 mixin、而且没有多出拒绝，就采用这份计划——`UNFIT` 的改写不能留下任何拒绝（`MixinRetarget.adopt`）。内核按名字压掉的 mixin 根本不判定，既没有判定日志，也不计入 `PARTIAL` 数。`MixinFitReport` 离线执行同样的判定：`MixinFitReport <merged-base.jar> <mods-dir> [--verbose]`。

只有 mod 自己的平台本来有这个成员，缺失才算基底造成的：Fabric mod 的平台是原版 1.21.1，NeoForge mod 的平台是 NeoForge 打过补丁的 1.21.1（取声明这个配置的 mod 的生态，`MixinConfigOwners.ecosystemOf`；没有唯一一个 mod 认领的配置一律不问）。打过补丁的游戏声明了原版没有的方法——`KeyMapping.getKeyModifier()`、`AxeItem.canPerformAction`、NeoForge 的 `EnderDragon.getParts()`——其中一些被 NeoForge 的补丁丢掉或改了类型，所以对 NeoForge mod 来说，“原版也没有”什么也证明不了。如果一个注入器的 `method` 选择器全是普通名字，点名的方法这个平台也全都没有，并且没有任何东西要求它必须注入成功——没有 ≥ 1 的 `require`、配置原本的 `injectors.defaultRequire` 为 0、不在 `@Group` 里、没开 `mixin.debug.countInjections`——那么原生 Mixin 会一声不吭地跳过这个注入器，mixin 的其余部分照常应用（sponge-mixin 0.17.x 和上游 Mixin 0.8.7 都一样：`TargetSelectors.validate` 只在要求次数大于 0 时才抛错；配置的 `required` 只决定这种错误是否致命）。“普通名字”指一个方法名，可以带描述符，可以带目标类自己作为所有者；`@` 开头的动态选择器（MixinSquared 的 `@MixinSquared:Handler` 会解析到另一个 mixin 的处理方法）、`+` 或 `{n,}` 量词（其最小次数不管 `require` 是多少都会抛错）、带点号的所有者、正则和格式不对的描述符一律不回答。`NativeAbsentTargets` 把这样的注入器既不算已解析也不算缺失，只打一行 info 日志，于是 mixin 按其余锚点判定；其余都不缺时整个交给 Mixin —— 不产生发现，不触发策略停止。Not Enough Crashes 对 `BlockEntity.populateCrashReport` 的 `@Inject`（1.21.1 里叫 `fillCrashReportCategory`）就是这个情况。“平台也没有”由基底的原始字节加上随包分发的差异表 `native-only-methods.txt` 回答：每个平台各有一份，列出它的游戏在原版包里声明、而这个基底没有声明的方法，以及只有这个基底才有、位于原版包里的类。原始类里仍然声明着的方法、`net/minecraft/`/`com/mojang/` 之外的类、别的 mod 的类，一律不会被判成“平台也没有”。这些行只对推导它们的那个基底成立，所以表里还记录了那个基底的成员摘要（原版包里每个类及其方法的名字和描述符，不含方法体）；由摘要不同的 jar 提供的类不回答，并打一条警告说明。`com/mojang/` 下 Minecraft 自己的库（brigadier、DataFixerUpper、authlib 等）按它们自己的字节回答：内核把它们放在基底旁边加载，每个平台加载的都是同样的 jar（原版 1.21.1 的版本 JSON 列出它们，NeoForge 21.1 的启动器配置继承这份清单，且不额外加任何 `com.mojang` 库），所以表里也记录了这些 jar 各自的成员摘要（`library` 行），恰好由其中之一提供的类按基底的类那样回答；别的版本的库 jar 不回答。这些行对每个平台只描述一个游戏——原版 1.21.1、NeoForge 21.1 打过补丁的 1.21.1——表里的 `platform` 行记着这些版本。如果一个 mod 必需的 `minecraft` 范围，或对应平台的 `neoforge` 范围，不包含这个版本，它就不是为这个游戏做的（它自己的加载器在这里会拒绝它，而它面向的更新的游戏可能就有那个方法），所以不回答，并打一行 info 说明是哪条要求；范围读不懂的、内核没有发布其清单（`ModPresence`）的 mod 也不回答。这些情况下缺失都照旧算基底造成的。`-Dneoforbric.mixinFit.nativeAbsent=off` 让这种注入器重新算作缺失。

以前的规则是看出处，而那是错的：合并基底*本身就是* NeoForge 打过补丁的 Minecraft，再拼进了 Forge，所以“Forge 系的类”说的是这个 jar 的大部分。

### 7.4 适配器 —— 把注入器挪到代码的新位置

当合并把第三方注入器要找的东西挪了位置，内核会把注入器跟着挪过去，而不是丢弃它。每个适配器的适用面都很窄，靠表格或证明驱动：

`MixinRetarget` 和 `MixinStubRebind`（委派存根 → 实际承载方法体的重载；先算出一个参数再转调的存根——`Player.doSweepAttack` 的判定框、`EntityFluidInteraction.update` 的谓词、`Entity.restituteMovementAfterCollisions` 的方块位置——只在承载方法体的重载的其他调用方全都是原版里调用存根签名的方法时才挪，所以 NeoForge 注册表快照直接调用的 `MappedRegistry.register(int, …)` 不会挂上 fabric-registry-sync 的新增条目回调）、`MixinOverloadPin`（只按名字写的 `@Inject`，Mixin 会绑到排在前面的另一生态的重载上时，钉到 handler 唯一对得上的那个重载——只在第一个重载接不住 handler 时才动；钉不了就说明原因）、`MixinMergedTwin`（以 `$neoforbricneo` 改名的匿名孪生类）、`MixinAnonymousRetarget` + `MergedBaseAnonymousDrift`（重新编号的 `Outer$N`）、`MixinAtWidenedCall` 和 `MixinWrapOperationShim`（NeoForge 补丁加宽过或重排过的调用；`@Redirect` 只跟着复核过的 `REDIRECTABLE` 行里的加宽静态调用走，经一个丢掉追加参数的包装——creativecore 的 `RegistryFriendlyByteBuf.decorator`，此后它建的缓冲区带的是 `ConnectionType.OTHER`，NeoForge 那些看连接类型的 codec 在这些缓冲区上走原版线格式）、`MixinRelocatedCall`、`MixinSubtypeOwnerRetarget`（同一个调用换了 owner：子类型，或字段被 NeoForge 补丁加宽后的类型——`RangedBowAttackGoal.mob` 让 `Monster.lookAt` 变成了 `Mob.lookAt`；注入点拿到经由该字段那次调用的 ordinal，handler 外面加一道守卫，只在字段装的是原版类型时运行）、`MixinShearsRelay`、`MixinHandlerShim`、`MixinAtShape`（不同 Mixin 分支之间 `at=[…]` 与 `at=…` 的差异）、`MixinLocalsCapture`（`CAPTURE_FAILHARD → CAPTURE_FAILSOFT`）、`InsertedLambdaArgumentShim`、`MergedBaseCalleeSwaps`（其中的 `REPLACED` 行：载体在唯一调用点整个替换掉的原版私有方法，由 `MixinRetarget` 的 R7 跟随——`StructureTemplate.placeEntities` → NeoForge 的 `addEntitiesToWorld`，挪过去的 HEAD handler 从 settings 上读回原版的参数；这类选择器由上面那条 handler 匹配规则发现，这些行只负责挪）、`MergedBaseAbsorbedCalls`、`CarrierHelpers`（表 `carrier-helpers.txt`）、`CarrierRenames`（表 `carrier-renames.txt`：载体把原版方法体整个或分段搬进了它新加的、描述符相同的方法，每一行是随方法体一起搬过去的一个调用或字段访问，以及它在 mod 自己那套游戏的这个方法（参考方法）里出现了几次——Fabric mod 的参考方法是原版的，Forge 或 NeoForge mod 的是对应载体打过补丁的那个；次数不同时按生态分成几行。`MixinRetarget` 的 R3 只在 mod 自己那套游戏里方法体还在原处、注入器的每个锚点都是这些搬过去的调用之一而且绑定的次数不超过参考方法里的次数（写了 `ordinal` 的锚点要求次数完全一致）、并且合并后的类仍然调用这个改名方法时，才把选择器挪过去——唯一的例外是表里标成 `UNCALLED` 的那一行，挪过去只是为了让注入器绑上。这一行是 NeoForge 的 `ItemStack.addDetailsToTooltipComponents`：原版的 tooltip 方法体，NeoForge 把它留成私有方法、从来不调用（NeoForge 用自己的 appender 画 tooltip）；普查会排除所有不是私有的、或者同一 nest 里有任何代码调用它的改名方法。挪到这里的注入器永远不会运行，而且会如实报告：MixinFit 判它“永远不运行”，最终类检查把它记为一个永远不运行的注入器（CONFIRMED，但不是 required），只会在这个 mod 的那一行做标记，不会阻止启动。malilib 的最后一个 tooltip 钩子（`onGetTooltipComponentsLast`，required）会落到这里；trinkets 作为 Fabric mod 加载时（它是通用 jar，默认按 NeoForge 加载），它的属性行钩子也会落到这里；如果不挪，malilib 的钩子哪里都绑不上，成为一条 CONFIRMED 的 required 损失，严格策略下所有带 malilib（Litematica、MiniHUD、Tweakeroo）的客户端都会被拦下，默认策略下会弹出询问。让这类钩子在 NeoForge 真正生成 tooltip 行的地方运行，目前还没有做。改名方法包含参考方法的全部调用和字段访问、并且每个出现的次数都相同的行是 `RENAME`，否则是 `PIECE`（NeoForge 的 `addDetailsToTooltipTail`、`startSleepInBed` 的 lambda、四个 HUD 层）；挪进片段的只能是只依赖那个调用本身的 handler：不能共享变量、不能捕获局部变量、不能用 slice，要拿方法参数的 handler 只在方法把自己的参数原样交给这个片段、并且之前没有改写过这些参数时才挪。能取消方法的 handler（可取消的 `@Inject`、带 `@Cancellable` 的回调）只在合并后的方法会把片段返回的 `Either` left 原样返回时才挪进片段——表里标成 `LEFT` 的行，并且运行时再按实际字节码核对一遍：NeoForge 的 `startSleepInBed` 把 lambda 的结果交给 `EventHooks.canPlayerStartSleeping`，结果带问题就直接返回——而且这个 handler 所有可能的取消值都必须是 `Either.left(..)`，分析时会跟进它自己 mixin 里的 lambda 和私有辅助方法。apoli-legacy 的鸟类禁睡和 Fabric API 的睡觉朝向否决（`MODIFY_SLEEPING_DIRECTION`）都是这样在 lambda 里取消的，所以 NeoForge 的 `CanPlayerSleepEvent` 会像看到原版的问题一样看到它们的问题，`startSleepInBed` 也会把它返回；apoli 取消时用的是 `Either.left(null)`，NeoForge 的这个钩子读不了它，会在 `startSleepInBed` 里面直接抛异常。走原版 `BedBlock` 这条路径时两边都会失败（apoli 自己的游戏里是 `BedBlock` 对这个 null 调 `message()` 时抛异常），但在 NeoForbric 上，会判断 null 的其他调用方（别的 mod 的睡袋、自定义床）也会失败；这和加入这张表之前一样。在完整的 `RENAME` 上，共享变量的 handler 只在它所在 mixin 里同一方法上共享同一个变量的 handler 全部一起挪时才挪。只看字节码的时候，R3 曾把注入器挪进形状相同但毫不相干的方法（text_styles 的颜色钩子挪到了阴影颜色上，ViaFabricPlus 的物品使用和快捷栏按键钩子挪进了别的原版方法，goldenpotions 的标签页图标挪进了另一个标签页的 lambda），也挪进过那个没人调用的 tooltip 改名方法体，而且都被判为完全匹配。前四个现在 R3 不再挪：ViaFabricPlus 5.0.2 的那两个是 NeoForge 在原处换掉的调用上的重定向，改由 `ReplacedCallRedirects` 挪（见下）。`-Dneoforbric.mixinRetarget.renameCensus=off` 恢复只看字节码的做法，`-Dneoforbric.mixinRetarget.renameCensus.leftExit=off` 让所有能取消的 handler 都不进片段，`-Dneoforbric.mixinRetarget.renameCensus.uncalled=off` 让所有注入器都不进 `UNCALLED` 的方法体），以及按功能面划分的 Fabric 适配器（`FabricBlockBreakMixinAdapter`、`FabricEntityMixinAnchors`、`FabricClientMixinAnchors`——它还把任何 Fabric mod 挂在 `Gui.extractRenderState` 里屏幕绘制调用前后的 `@Inject` 挪到 NeoForge 的 `ClientHooks.extractScreen` 前后，打过补丁的方法体在那里画屏幕；LiquidBounce 的整个浏览器菜单就画在这里——`FabricEnchantmentMixinAdapter`、`FabricMiningMixinAdapter`、`FabricSoundMixinAdapter`、`FabricServerLanguageMixinAdapter`），以及 `ReplacedCallRedirects`：载体在原处把一个原版调用换成了自己的调用，而 mod 对原版调用的 `@Redirect` 只是在转发原版调用外面加一个条件时，这个重定向挪到载体的调用上、改为转发载体的调用。只沿着表里的行挪，每行写明两者在哪里互相对应、哪些操作数是同一个值以及理由（ViaFabricPlus 的快捷栏按键：`KeyMapping.matches` → `isActiveAndMatches`；物品持续使用：`ItemStack.isSameItem` → `CommonHooks.canContinueUsing`，handler 自己的逻辑仍按原版的参数顺序看两个物品堆；铲子压路：`FLATTENABLES.get` → 方块状态的 `SHOVEL_FLATTEN` 修改）；只对自己那套游戏里做的是原版调用的生态生效；`-Dneoforbric.replacedCallRedirects=off` 关闭。还有 `MixinTwinRebind`：基底保留了某个原版方法、但游戏里没有任何代码调用它，而载体在它的位置加了一个重载时，按原版签名写的注入器挪到这个重载上。只沿着 `carrier-twins.txt` 的行挪（`CarrierTwinCensusTest` 推导：原版方法对这个 mod 的生态来说没人调用、也不是载体存根；那个重载包含原版的每一个参数，按局部变量名和类型一一对应；该生态自己那套游戏里调用原版方法的每个方法，在基底里都改为调用这个重载）。handler 捕获的原版参数在重载里位置不同时会被包一层：外层接重载的参数，再按原版的参数交给原 handler。只在原版方法仍然没人调用（没有已安装的 mod 引用它）时挪；只挪捕获全部原版参数或不捕获参数的 `@Inject`，以及能确定自身参数约定的 `@At` 类注入器（`@ModifyVariable` 不挪）；每个 `INVOKE`/`FIELD` 注入点在两个方法体里出现的次数必须相同。NeoForge 给 `ModelBlockRenderer.shouldRenderFace` 加了方块自己的位置参数，并且排在原版那个前面，所以 LiquidBounce 的 X-Ray 面剔除钩子（按名字选择、接原版的四个参数）以前会绑到 NeoForge 的重载上、在那里被拒绝，连带整个方块渲染 mixin 一起失效；`-Dneoforbric.mixinTwinRebind=off` 关闭。还有 `ThinnedCallOrdinals`：载体把一个原版调用换掉了几处、保留了其余几处，使这个调用出现的次数比原版少时，按原版方法体数出来的 `@At(INVOKE)` ordinal 会改成补丁后方法体里对应同一个调用的那一处。只沿着复核过的行改：每行写明原版的每一处对应保留下来的哪一处（或者已经没有），以及每个保留下来的调用后面紧跟的是哪个调用（在两个 jar 上核对过，运行时还会对实际的方法再核对一次）。ViaFabricPlus 的 1.12.2 放置钩子位于 `MultiPlayerGameMode.performUseItemOn` 第三个 `ItemStack.isEmpty()` 之前；NeoForge 和 MinecraftForge 把原版对两只手物品的 `isEmpty` 判断换成了 `doesSneakBypassUse`，所以合并后的方法体只剩下第三个。只对按原版次数编译的生态生效；`-Dneoforbric.thinnedCallOrdinals=off` 关闭。`GuestInjectorPruner`（COREMOD）在内核接管了某些注入器功能的地方，从第三方 mixin 类里剪掉这些单独的注入器；另外在字节码提供器的适配器全部跑完之后，剪掉判定认定 Mixin 会直接拒绝的注入器（见 7.3）——每个都要在 Mixin 即将拿到的节点上按同一条规则再确认一次，已经被某个适配器挪到合适位置的注入器会留下。有几个适配器会读取 `src/main/resources/net/neoforbric/kernel/mixin/` 下随包分发的表（`carrier-helpers.txt`、`carrier-renames.txt`、`carrier-stubs.txt`、`carrier-twins.txt`、`lambda-permutations.txt`、`uncalled-methods.txt`、`native-only-methods.txt`）；`CarrierHelperCensusTest`、`CarrierRenameCensusTest`、`CarrierTwinCensusTest`、`UncalledMethodCensusTest` 和 `NativeOnlyMethodsCensusTest` 根据暂存的 jar（`native-only-methods.txt` 读的是基底、NeoForge 打过补丁的游戏和原版自己的 jar）重新推导 `carrier-helpers.txt`、`carrier-renames.txt`、`carrier-twins.txt`、`uncalled-methods.txt` 和 `native-only-methods.txt`，并把它们锁定。把 handler 方法体改名挪到一边、原名换成包装的那几处（`MixinRetarget` 的守卫和 R7、`MixinAtWidenedCall` 的 redirect、`MixinSubtypeOwnerRetarget` 的守卫），改名时会带上 mixin 类的标记，免得同一目标上两个 mixin 的同名 handler 被合并成一个方法体。`MixinFitLivenessCensusStagedTest` 另有一份普查：Fabric mixin 点名的锚点里，在原版 1.21.1 上能解析、按内核的判定在这个基底上解析不了的那些。只有 fabric-api 的行有断言（多出一行构建就失败）；`NEOFORBRIC_ANCHOR_PACKS` 指定的其他语料只出报告（`build/reports/vanilla-anchor-census.txt`）——第三方 mod 的行不会让任何东西失败，得有人去读报告。

### 7.5 归因

`MixinConfigOwners` 在注册前把每个配置映射到它所属的 mod，这样 Mixin 自己报出的失败就会点名那个 mod（`-Dneoforbric.mixinModIdDecoration` 还会把 mod id 写进生成的 handler 名）。`KernelMixinErrorHandler` 把准备/应用阶段的失败记到该 mod 的那一行上，但不改变 Mixin 的决定。`FinalMixinApplications` 在所有阶段结束后观察每个已定义的类——某个 handler 零引用，就证明它没有挂上。当某个具名的内核修复完成了那个 mixin 做的*全部*事情时，`SupersededMixins` 不让这次失败记到该 mod 的那一行上；当该 mod 自己的配置插件本来就会拒绝这个 mixin 时，`PluginDeclinedMixins` 也这样处理；`ForeignMixinBreaks` 记录那些专门写来挂到另一个 mod 上、结果没挂上的 mixin。`MixinCompatibility` 让一个 mixin 从预检到应用始终带着同一个身份。

### 7.6 跨 mod 的重叠 —— `MixinOverlapLint`

`MixinFit` 拿一个 mixin 对照基底来判；两个各自都合身的 mod 放在一起仍可能相撞。`MixinOverlapLint` 列出每个 handler 的占用（目标方法、`@At` 调用、ordinal），把不同 mod 的占用两两配对 —— 打包在一个 jar 里的模块算作装进来的那个 jar，同一 mod id 的两个 jar 算作同一个 mod：

| 规则 | 组合 | 类别 |
| --- | --- | --- |
| R1 | 同一方法上的两个 `@Overwrite` —— 只留下一个方法体：优先级高的那个，优先级相同时留先应用的那个 | 冲突 |
| R2 | 同一调用上的两个 `@Redirect`，ordinal 相同或未指定 —— Mixin 只保留一个 | 冲突 |
| R3 | 一个 `@Overwrite`，加上另一个 mod 在该方法里的任意注入器 | 冲突 |
| R4 | 同一调用上的 `@Redirect` 和另一个 mod 的 `@WrapOperation`/`@ModifyExpressionValue` | 提示 |

通配符/正则选择器，或目标类读不到时的裸方法名，不产生占用；slice 不读。启动时（§3.2 第 18 步）它按 `NeoForbricMixinService` 实际交给 Mixin 的配置来读（内核删掉的 mixin 已不在里面），每个 mod 每条冲突记一条 `SUSPECTED` 发现，id 为 `mixin-overlap:<owner>.<name><desc>[@<at>]`，detail 里点名另一个 mod；日志里记各规则计数和耗时毫秒数（`-Dneoforbric.mixinOverlapLint=off` 关掉）。崩溃调用栈经过一个记录了冲突的方法时，`CrashAttribution` 会同时点名这两个 mod。离线：`MixinOverlapLint <merged-base.jar> <mods-dir> [--json out]`（递归查找 jar）。

## 8. 事件桥

随 MinecraftForge 支持一起删掉了，这一节记录删了什么、留下了什么。26.x 的内核维护着一台**重发多路复用器**：96 行 `GameEventBridge`，每行把合并基底上落单的一个 Forge 系事件重新发布一遍，由 `boot.GameEventMultiplexer` 安装，游戏侧的另一半是 `runtime.KernelGame*Events` 和 `KernelGameResultBridges`，再由 `net.neoforbric.api.EventBridges.verify` 校验。在这个基底上这些全都没有活可干：Forge 系只剩一个，事件就是 NeoForge 自己的，补丁基底会自己发布它们——MinecraftForge 的监听器没有类可以听，也没有桥能到达它。

删掉之后仍留下来的，因为它们审计的是这个基底本身，而不是通向某个消失的生态：

- **`HookCallSiteCensus`** —— 基底仍在调用的 NeoForge `ClientHooks`/`EventHooks` 有哪些，离线和启动时各查一遍。
- **`DeadHookWorklist`** —— 普查里死掉的钩子与真正在等它们的 jar 的连接表，按注意到的已安装 mod 数量排序（它过去读取的 26.x 伤害/攻击接缝，其说明保留在 javadoc 里作为历史）。
- **朝 NeoForge 方向的重发以接缝修复的形式留存** —— NeoForge 的补丁挪动了 Fabric mod 所钩住的原版调用时，修复落在基底上（`VanillaDamageReadInjector` 等），是 `*Injector`，不是总线桥；每一个都连同它的开关列在 §6。
- **mixin 套不上补丁基底的 Fabric API 事件**，仍然从 NeoForge 自己的调用点发出（`KernelHudBridge` 对 `HudElementRegistry`；tooltip 和 block-break 适配器，§7.4）。

## 9. 数据包、资源与数据

### 9.1 服务端数据

真正的加载器会遍历 `ModList` 里的 mod 文件，把每个 mod jar 变成一个包。内核往 `ModList` 发布 mod 时 `modFiles` 是空的，所以这次遍历什么也找不到。由 `DataPackHookInjector` → `KernelLifecycle.onServerDataPacks` → `KernelDataPacks` / `runtime.KernelDataPackSource` 提供每个 Forge 系 jar 的 `data/`，载体的一并提供（`c:` 约定标签、`neoforge:` 伤害类型和数据映射只存在于载体里）。元数据通过 NeoForge 的 `ResourcePackLoader.readWithOptionalMeta` 读取，保留根包的覆盖层。载体和 mod 带了同一个文件时，标签会叠加，按“后者胜出”处理的文件则由 NeoForge 胜出。Fabric mod 的数据和在 Fabric 上一样，由 fabric-api 自己的资源加载器提供。`KernelPackFinders` 让 Forge 系 mod 能添加自己的包查找器（`-Dneoforbric.modDataPacks=off` 会关闭 Forge 系的包）。

### 9.2 客户端资源

NeoForge 的 `mod_resources` 来源在合并基底上是孤立的。`ClientPackHookInjector` 把 `ClientModLoader.setupModResourcePacks(PackRepository)` 的方法体重定向到 `KernelLifecycle.onClientResourcePacks`，由 `KernelClientPacks` 在第一次资源重载之前为每个生态 jar 添加一个包（兼容性强制设为 `COMPATIBLE`，两个真正的加载器也是这么做的）。`PackScreenHiddenFilterInjector` 让这些包不出现在资源包界面里。内核自己的资源（Mods 按钮图标）放在 `neoforbric-kernel-runtime.jar` 里。

### 9.3 同时写给三个加载器的包元数据

多加载器的 `pack.mcmeta` 为每个加载器各带一节，而在 NeoForbric 上，三个解析器都会读它。`KernelPackMetadata` + `PackMetadataFailSoftInjector` 防止某个解析不了的外来小节导致整个包被丢弃；`PackOverlayMutabilityInjector`（修复）和 `NullPackGuardInjector`（兜底）处理 NeoForge 合并覆盖层时去修改 fabric-api 已冻结列表的问题（两者的说明都在 `KernelPackRepair` 里）。

### 9.4 条件、注册表、数据映射、世界生成

- **资源条件** —— 两个求值器：`runtime.KernelFabricConditions`（`fabric:load_conditions`；fabric-api 自己的那两个 mixin 套不上）和 `KernelNeoConditions`——每个都防止一种方言让另一种方言的文件失败。（26.x 还有第三个，`KernelForgeConditions`，管 MinecraftForge 自己的条件文件；随读取它的生态一起删掉了。）
- **注册表目录** —— `RegistryDirectoryOwnerInjector` / `KernelRegistryDirectories`：`registryDirPath` 的返回值与所属生态的做法一致。**别名** —— `KernelRegistryAliases` 在 fabric 的 mixin 覆盖不到的查找上解析 fabric 侧的别名（两个平台现在都往同一个 `MappedRegistry` 上加 `addAlias`；26.x 的 `RegistryAliasParityInjector`——把读取那一半织进八个原版查找，因为合并基底丢掉了 NeoForge 自己的——随它绕开的 wrapper 一起删掉了）；`PostMixinFixups.askTheRegistryWhetherAnAliasTargetExists` 让 fabric 的别名目标警告读注册表真正的键集（`-Dneoforbric.aliasPresenceParity=off` 保持 Mixin 织出的样子）。
- **数据包注册表** —— `KernelLifecycle.registerDataPackRegistries` 发布 `DataPackRegistryEvent.NewRegistry`，让 mod 能声明自己的注册表；把它发到基线总线上同时让 NeoForge 自己的 `neoforge:biome_modifier`/`neoforge:structure_modifier` 数据包注册表变得可解析，并且双向镜像 Fabric 的动态注册表。
- **数据映射** —— 会加载 NeoForge 数据映射（`KernelNeoDataMapWatch`、`KernelNeoWorldgen`）。所有经由 holder 的数据映射查询最后都落在 `Holder.Reference.getData`，它会调 `key()`，而还没注册的值没有 key，于是抛 `Trying to access unbound value`；原版的氧化、打蜡、去皮表对任何方块都能回答。`UnboundHolderDataInjector` 让未绑定的 holder 回答“没有数据”（没有 key 的值不可能出现在任何数据映射里），NeoForge 的钩子因此回落到原版的表——Fabric mod 在初始化里调用它们时期望的就是这样（`-Dneoforbric.unboundHolderData=off` 可关闭）。这一点和原生 NeoForge 不同：原生在任何时候都会在这里抛错。值所属的注册表还开着时，这个回答是静默的；一旦该注册表的任一 `frozen` 标志被置上（它的 `freeze()` 已经跑过，而 `freeze()` 遇到这样的值会抛 "Some intrusive holders were not registered"），`util.KernelUnboundHolderData` 就对每个这样的值 WARN 一次（最多 64 条，之后再打一行汇总），写明值、注册表和发起查询的调用栈——原生 NeoForge 会抛的那个错仍然被报告出来，只是查询照样回答“没有数据”。这条 WARN 说的是“注册表关闭时它还没注册”，不是“它永远注册不进去”：注册表可以被 unfreeze，NeoForbric 也会为 Fabric 客户端 entrypoint 重新打开注册表。
- **世界生成** —— 数据包驱动的生物群系/结构修改器走 NeoForge 自己那一轮修改器处理（`KernelNeoWorldgen`）；内核声明它们读取的数据包注册表（上一行），让修改器可解析，不存在第二个生态的驱动程序要复制。数据映射由同一个类看管，对映射加载完成前到达的查找有一个兜底（`-Dneoforbric.neoDataMapFallback=off` 关闭）。`NativeCoremodParity`、`BiomeInfoRebaseInjector`、`BiomeLateWriteInjector` 让修改后的视图能被读到。

## 10. 统一 API —— `net.neoforbric.api`

固定交给父加载器（每个 JVM 只有一份），是内核和全部三个兼容层共用的语言，免得两两互相翻译：

| 类型 | 作用 |
| --- | --- |
| `Ecosystem`、`Side` | 唯一一套生态与端（side）词汇 |
| `ForeignType` | 每行把一个 Forge 系概念映射到它在基底 NeoForge 类中的类名——只有名字 |
| `DiscoveredMod`、`UnifiedDependency`、`VersionPredicate`、`ModIds` | mod 与依赖模型 |
| `ModPresence`、`ModCatalog` | “X 是否在运行”，以及玩家看到的列表，状态为 `OK` / `DEGRADED` / `FAILED` |
| `GameEventBridge`、`EventBridges` | 桥清单及其校验 |
| `CompatibilityFinding`、`CompatibilityFindings` | 每次启动的证据账本（§12） |

它不是给 mod 用的稳定 API。

## 11. Mods 界面

`runtime.KernelModListScreen` 取代两个系各自的 mod 列表界面（`ModsButtonRedirector`），读取 `ModCatalog`；`ModCatalog` 由 `KernelModCatalog` 根据发现结果填充，并再读一遍每个 jar，取出描述、作者和 logo。每个 `ModCatalog.Entry` 都带有 `status` 和 `statusDetail`；如果它是以内嵌 jar 的形式带进来的，还会写明是哪个 mod 自带了它。
`KernelModConfigScreens` 负责打开 mod 自己的配置界面。

Fabric mod 的配置界面只在一个地方声明：一个实现 Mod Menu 的 `com.terraformersmc.modmenu.api.ModMenuApi` 的 `"modmenu"` 入口点。这个接口属于 Mod Menu 这个 mod，所以没装 Mod Menu 时，入口点类连链接都过不了。找到已安装的 Mod Menu 时照旧问它。否则由 `boot.ModMenuApiStandIn` 把这五个 API 类型及其默认值返回的 `util.NullScreenFactory` 的替身交给 `NeoForbricClassLoader.putGeneratedClass`（与 Mod Menu 20.0.3 的公开形状完全一致，从 `src/modmenuApi/java` 编译，以 `.class.bin` 资源的形式放在游戏侧 jar 里）。加载器只在所有自有 jar 都找不到这个类之后才定义这些字节，所以真正的 Mod Menu 仍然优先。只在客户端、只有 API 这一个包外加那一个类：`isModLoaded("modmenu")` 仍然是 false，Mod Menu 的其余内部类也仍然不存在。随后 `fabric.ModMenuConfigFactories` 按 Mod Menu 初始化时的方式读取这些入口点：先读每个 mod 自己的工厂：如果它是接口默认值所返回的那个类的实例就跳过（即 Mod Menu 的 `instanceof NullScreenFactory`，所以重写后又退回默认值的 mod 不会出现一个按了没反应的 Config 按钮，问"有没有"时也不需要构建界面）；再用 `putIfAbsent` 合并每个入口点的 `getProvidedConfigScreenFactories()`，和 Mod Menu 一样每次查询都重新合并。坏掉的入口点会被跳过。`-Dneoforbric.modMenuStandIn=off` 恢复旧行为：Fabric mod 只有装了 Mod Menu 才有 Config 按钮。

## 12. 兼容性报告与策略

### 12.1 证据

`net.neoforbric.api.CompatibilityFinding(id, modId, feature, source, confidence, required, detail, evidence)`；`Confidence` 取值为 `SUSPECTED`、`CONFIRMED` 或 `RESOLVED`。只有 `CONFIRMED && required` 能阻止启动；`RESOLVED` 只作为证据保留，从不给 mod 打标记。`CompatibilityFindings` 是每次启动的账本，引导代码、游戏界面和发布检查共用它。写入方包括：依赖审计（`DependencyAudit`，跨生态——没有哪个解析器会处理合在一起的整个集合）、仲裁、Mixin 预检/应用、缺失的桥、延迟任务失败、§3.2 的静态审计、`KernelTransferInterop`，以及服务端/客户端的后期检出项。

### 12.2 文件 —— 都在 `<gameDir>/.neoforbric-kernel/` 下

| 文件 | 何时写入 |
| --- | --- |
| `load-report.txt` | 在进入游戏前的边界处作为证据写一次，初始化生命周期结束后再写一次，`ServerStartedEvent` 时（世界已就绪；内置服务器也会发布这个事件）以及后期检出项到来时还会再写（`-Dneoforbric.loadReportRewrite=off` 只保留第一次写入）；如果加载始终没有完成，由关闭钩子写入。使用系统语言 |
| `compatibility-report.json` | 与它放在一起，机器可读的检出项 |
| `merge-report.txt` | 两个 jar 声明同一个 mod id 时（§4.3） |
| `crash-analysis.txt` | 生成崩溃报告之后：调用栈指向哪些 mod，调用栈经过的方法上有 mixin 重叠时同时点名双方（`CrashAttribution`，§7.6；`-Dneoforbric.crashAnalysis=off`）。Forge 的 `Suspected Mods:` 那一行依赖一个模块层，而内核不构建这个模块层 |
| `crash-suspects.json` | 与它放在一起：`{schema:1, report, clash, suspects:[{modId,name,jar,reason,depth}]}`。下一次客户端启动时，在仲裁之前，`CrashSuspectOffer` 提出不加载这些 jar 启动（冲突时保留第一个被点名的一方），选“不加载启动”就把它们追加进 `<gameDir>/neoforbric-disabled.txt`；无论怎么回答，都把文件改名为 `crash-suspects.offered.json`。服务器、无显示环境和 `-Dneoforbric.dependencyDialog=off` 只在日志里写出这些行 |

同一位置还有几个工作目录：`lib/`（解压出来的自带 jar）、`jij/`、`jarjar/`、`candidates/`。

### 12.3 依赖对话框

`ui.DependencyDialog` 向玩家显示未满足的硬依赖和跨 mod 的 mixin 失效。这个窗口是一个**独立的 JVM**（`DependencyDialogMain`，启动时 classpath 里只有内核 jar 这一项），因为在 macOS 上游戏带着 `-XstartOnFirstThread` 运行，AWT 无法和 GLFW 共用第一个线程。父子进程之间只共享 `DependencyReport` 里的制表符分隔文件格式；文案在 `DialogLang` 里（跟随系统语言，`-Dneoforbric.dialogLanguage=<code>` 可强制指定一种）。`-Dneoforbric.dependencyDialog=on`（默认）| `off` | `dryRun`（派生真正的子进程，但禁用 AWT——闸门断言的就是它）。子进程 10 分钟后超时。确认窗口的退出码：`0` 继续，`4` 退出或关掉窗口，其他任何值（`3` 画不出窗口、启动器自己的 `1`）表示窗口没能让人回答。同一个子进程还有第三种窗口 `--isolation`，即 §12.2 的崩溃嫌疑提示：退出码 `2` 表示不加载它们启动；除了那两个明确的按钮，其他任何情况都按加载全部 mod 启动处理。

### 12.4 策略 —— `-Dneoforbric.compatibilityPolicy`

`ui.CompatibilityDecision.policy()`：

| 值 | 已确认的必要功能缺失…… |
| --- | --- |
| `ask`（默认） | 在对话框里询问玩家一次（依赖提示也并入其中）；只有明确选择“继续启动”才算批准，选“退出”或关掉窗口算拒绝。客户端上对话框弹不出来时——`java.home/bin/java` 不能执行（FCL 这类安卓启动器）、子进程起不来或画不出窗口、10 分钟没人回答——问题改到游戏里问：不批准任何东西，由 `KernelCompatibilityPrompts` 在标题界面用对话框同样的按钮询问，选“退出”就停止游戏。启动参数要求直接进世界（`--quickPlaySingleplayer`/`Multiplayer`/`Realms`：世界会在游戏能问之前就加载）或设了 `-Dneoforbric.dependencyDialog=off` 时，照旧不批准。专用服务器没人可问 → 不批准 |
| `continue` | 接受并记录在案；提示仍可能显示 |
| `strict` | 阻止启动；不显示任何窗口 |
| 其他任何值 | 按 `strict` 处理（失败时按拒绝处理） |

这个决定会在进入游戏前的边界处询问一次（§3.2 第 18 步），加载结束时再问一次（服务端在 §3.4 第 3b 步，客户端在 `fireClientSetupLifecycle`），专用服务器还会在 `ServerStartedEvent` 时再问一次（世界加载期间出现的必要功能失败会让服务器按正常流程停机）。拒绝时抛出 `CompatibilityDecision.LaunchStopped`；`CompatibilityLaunchBoundary` 把它转成退出码 **78** 并打印报告路径；在 `Minecraft.<init>` 内部则通过 `SilentInitException` 退出，因此不会作为崩溃上报。启动之后才出现的检出项从不派生 Swing 进程，也不会退出 JVM：在服务端，它们在一个 tick 完成后的边界处处理（`LateServerCompatibility`）；在客户端，则在渲染线程的某个 tick 上由一个原生 Minecraft 界面处理（`KernelCompatibilityPrompts`、`KernelCompatibilityScreen`）。安装好的版本配置不传任何 JVM 参数，所以玩家得到的是 `ask`；`run/launch-kernel-{client,server}.sh` 默认用 `strict`（客户端脚本还默认 `-Dneoforbric.dependencyDialog=off`）。

## 13. 安装器 —— `neoforbric-kernel-installer/`

纯 JDK 实现，没有依赖，字节码 release 17，版本 `0.3.1-beta2`。

```
java -jar neoforbric-kernel-installer.jar                     # window (InstallerGui)
java -jar neoforbric-kernel-installer.jar --dir DIR [options] # headless install
    --mc 1.21.1  --artifacts DIR  --jdk PATH  --remote  --release TAG  --mirror PREFIX  --offline
java -jar neoforbric-kernel-installer.jar --doctor [--dir DIR] [--jdk PATH]
```

### 13.1 在玩家的机器上构建游戏产物

`ArtifactBuilder.build` 在 `<mcDir>/.neoforbric-build/` 下运行，从第一个未完成的步骤接着做：

```
neoforge userdev ─┬→ neoforge-runtime.jar ─────────────┐   (NeoForgeRuntimeBuilder)
                  └→ NFRT → patched-mc-neoforge-1.21.1.jar ┘   (NfrtRunner, gameJarNoRecomp)
                                                                │
                                                       链接检查 ─→ 不通过的一组文件会让安装失败
```

- `NeoForgeRuntimeBuilder` 解开安装器下载的 NeoForge userdev jar，组装出版本配置要加载的运行时：`-universal` jar 加上其 userdev 配置声明的库，作为一个嵌套 jar。
- `NfrtRunner` 运行 NeoFormRuntime（`Pins.NFRT = "2.0.18"`，取的结果是 `Pins.NFRT_RESULT = "gameJarNoRecomp"`：只打二进制补丁，不用反编译器，也不用 `javac`），它的结果**就是**游戏基底：之后没有任何东西叠在它上面。
- `MergedBaseTool` 从安装器的资源里解出 `neoforbric-merge-tools.jar`，对照打包在安装器里的已审核基线运行 `net.neoforbric.tools.MergedLinkChecker`。**只要链接检查报告的不是 `new 0`，安装就会失败。**
- `--artifacts DIR`（窗口里的 “Built artifacts (leave empty)”）只供开发者使用，用来跳过构建。`GameArtifacts` 只从这一个目录取这两个 jar，并在下载或写入任何东西之前逐个打开检查：基底必须是 Minecraft 1.21.1，且它 `net/minecraft/` 下的类引用 `net/neoforged/`；运行时必须带着 NeoForge 自己的核心类和它的 mod 加载器（`FMLLoader`、`IModInfo`），并且清单主段的 `Implementation-Version` 必须是锁定的版本（`Pins.NEOFORGE`）。这之后才做链接检查——单靠链接检查，任何不引用自身以外任何东西的 jar 都能通过（issue #13）；提供的一组文件没通过链接检查时，报告为这些文件彼此对不上，并给出同样的出路。
- `Pins`：`MINECRAFT = "1.21.1"`（唯一支持的版本）、`NEOFORGE = "21.1.256"`、`NFRT = "2.0.18"`、`NFRT_RESULT = "gameJarNoRecomp"`，每一项的理由都写在源码里。`BuildStamp` 让每个缓存产物都以整组锁定版本为键，所以锁定版本一升级，就不可能沿用缓存里的旧产物。
- `JdkLocator` 要求构建工具用 Java ≥ 21（NeoFormRuntime 的 class 文件版本是 65）；它依次尝试当前运行的 JVM、启动器的运行时、系统里的 Java，从不下载 JDK。游戏本身需要 Java 21。

### 13.2 版本配置

`Installer` 写出 `versions/1.21.1-neoforbric/1.21.1-neoforbric.json`：

- `inheritsFrom: "1.21.1"`，`mainClass: net.neoforbric.kernel.boot.KernelClientLaunch`，没有 JVM 参数；
- 游戏参数 `--gameJar <patched-mc-neoforge>`、`--runtimeJar <neoforge-runtime>`、`--libraryPath <every vanilla library for this platform>`；
- `libraries`：自带的 NeoForbric jar 和内核的第三方依赖（取自内核自己的 `printBootClasspath`），再加上两个游戏产物，分别暂存为 `net.neoforbric:patched-mc-neoforge`、`net.neoforbric:neoforge-runtime`；
- 一个 `neoforbric` 块，只是元数据，声明了 `net.fabricmc:fabric-loader:0.19.3`。有些启动器靠搜索 JSON 文本来识别加载器，这样它们就会把这个实例当作装了 mod 的实例（并给它单独的 mods 文件夹）。

两个生态的 mod 都放进 `<mcDir>/mods`（启动器开了版本隔离的话，则放进 `versions/1.21.1-neoforbric/mods`）。

### 13.3 NeoForbric 自己的 jar 从哪里来

默认构建会自带这些 jar（`bundleNeoForbric` 写出带 SHA-1 的 `neoforbric-kernel-libraries.json`）。`-Pslim` 构建一个都不带，安装时再通过 `RemoteSource` 获取：NeoForbric 的 jar 取自 GitHub 发布版，其他库优先从 Maven 获取。`-PreleasePin` 把 `neoforbric-release.properties`（tag、仓库、`manifestSha256`）编译进 jar。清单的摘要就是信任锚，所以发布要分两步（先 `releaseAssets`，再做锁定后的 slim 构建）。只有 `run/evidence.py release-check` 确认内核 jar 和 merge-tools jar 与已验收的候选版本逐字节一致，`releaseAssets` 才肯写出发布资产。

### 13.4 `--doctor`

`Doctor.examine` 不写任何东西、不建任何东西、不下载任何东西，只报告：平台、找到的 JVM、基础版本是否已安装、锁定版本、哪些产物已经存在或将会构建、预计占用的磁盘空间，以及一行结论。

## 14. `neoforbric-loader/` 还有什么用

- **合并工具。** `neoforbric-loader/src/tools/java/net/neoforbric/tools/` 下的 `MergedLinkChecker`，由 `:mergeToolsJar` 构建成 `neoforbric-merge-tools-0.1.0.jar`，安装器自带并运行这个 jar（26.x 的合并工具——`MergedBaseBuilder`、`RuntimeInteropPatcher` 及其辅助类——已随合并基底一起删除，没有基底要合并了）。已审核的链接基线是 `neoforbric-loader/src/test/resources/merge/link-check-baseline.txt`。
- **开发者流水线。** `run/assemble-neoforge-runtime.sh` 在 `neoforbric-loader/run/neoforge-runtime/` 下生成内核游戏侧编译所对的暂存 `neoforge-runtime`，每个闸门也在它上面运行；`run/launch-1.21.11.sh` 启动它（26.2 世代的启动脚本留在树里作为历史）。`NEOFORBRIC_OLD`（或 `-Pneoforbric.stagedRoot`）可以让另一个工作树指向这些产物。
- **测试输入。** `run/livemod-src*`/`testmod-src` 里的金丝雀 mod 源码（`build-testmods.sh`），以及它各个运行目录里的 mod 集合，供发现对照基准读取。
- **安装器载荷。** 安装器的打包清单里仍有 `net.neoforbric:neoforbric-loader` 和 `net.neoforbric:neoforbricruntime`，所以安装好的版本配置会把它们列为库。内核代码没有提到任何 `net.neoforbric.loader` 类，唯一的引用是 §6 里的重定向。

`neoforbric-loader/` 自己的引导路径（Knot 宿主，加上 `bootstrap.sh` 打上的八个 fabric-loader 底座补丁）内核并不使用。焊接方案的设计记录在 `neoforbric-loader/README.md` 和 `neoforbric-loader/run/README.md` 里。焊接方案的安装器已随之删除；发布的那一个是 `neoforbric-kernel-installer/`（§13）。

## 15. 仓库结构

```
NeoForbric/
├── README.md                      player-facing, describes the latest release
├── introduction.md                this document, describes main
├── LICENSE, NOTICE
├── bootstrap.sh                   clones ./fabric-loader for neoforbric-loader (not needed by the kernel)
├── MOD_TEST_FAILURES.md           per-mod compatibility results (Chinese)
├── .github/workflows/build.yml    CI: job `build` (bootstrap + neoforbric-loader) and job `kernel`
│
├── neoforbric-kernel/                THE KERNEL — own Gradle build and wrapper
│   ├── build.gradle               boot jar (release 21), runtimeJar, transferTest, staged-artifact wiring
│   ├── gradle.properties          ASM, sponge-mixin, MixinExtras, SAT4J, NightConfig … versions
│   ├── src/main/java/net/neoforbric/api/          the unified API (12 files)
│   ├── src/main/java/net/neoforbric/kernel/       boot (69), transform (70), mixin (66), fabric (14), metadata
│   │                                           (fabric/ + forge manifest parsers), access (7), classloading (6),
│   │                                           discovery (5), interop (4), ui (5), util (7), mapping (5), soak (2)
│   ├── src/main/java/net/fabricmc/             vendored Fabric API surface (38 files)
│   ├── src/main/resources/                     Mixin service registrations; mixin census tables
│   ├── src/runtime/                            GAME side, net.neoforbric.kernel.runtime (+ soak/, transfer/)
│   ├── src/test/, src/transferTest/            unit tests; transfer-engine tests
│   ├── canary/                                 canary mods the gates build and load
│   └── run/                                    launch-kernel-{client,server}.sh, lib.sh, diff-oracle.sh,
│                                               mixin-inventory.sh, merge-packs.sh, build-*-canary.sh,
│                                               evidence.py, reports/ (recorded compatibility evidence)
│
├── neoforbric-kernel-installer/      THE INSTALLER — src/main/java/net/neoforbric/installer/kernel/, packaging/
│
├── neoforbric-loader/                first generation; merge tools + artifact pipeline (§14)
├── neoforbric-kernel-installer/       玩家运行的安装器（§13）
└── fabric-loader/                 gitignored upstream checkout for neoforbric-loader
```

## 16. 构建与测试

目前的开发入口是 `python3 tools/dev.py client`（Windows 上用 `py tools/dev.py client`）。它借用安装器的产物流水线，在 `neoforbric-kernel/.dev/` 下准备一套隔离的游戏输入，解析库/资源和锁定的编译 API，然后构建并启动当前的内核。需要 JDK 21+ 和 Python 3.9+。Gradle 提供 `prepareDev`、`runClient`、`runServer` 和 `devDoctor`；准备和启动必须分成两次 Gradle 调用，因为游戏侧的接线在任务运行之前就已配置完毕。命令和配置见[开发指南](neoforbric-kernel/run/README.md)。

`check` 还会运行开发/证据工具的自测，以及打包链接闸门的合成控制。`integrationTest` 要求暂存好的游戏和传输测试套件齐备，并且不允许任何测试被跳过；普通的 `test` 仍允许本地测试夹具缺失，并打印已执行/已跳过的数量。完整的集成测试套件除了基础游戏，还需要它指名的那些 mod 测试夹具；游戏准备好了，并不等于宣称每个兼容性包或真实实例闸门都已经跑过。

```sh
cd neoforbric-kernel
./gradlew --offline jar        # boot jar; nests neoforbric-kernel-runtime.jar only when the staged artifacts exist
./gradlew --offline test       # unit suite (depends on compileRuntimeJava)
./gradlew --offline check      # + transferTest (real Fabric/NeoForge transaction engines)
java -cp <boot-cp> net.neoforbric.kernel.boot.Main --scan --mods <dir> --report out.json
```

- **暂存产物。** 游戏侧编译时依赖暂存的 `patched-mc-neoforge-1.21.1.jar` 和 `neoforge-runtime.jar`，外加取自本地 Minecraft 安装的 brigadier、datafixerupper 和 gson，传输模块要用的一个 fabric-api jar（`-Pneoforbric.fabricApi`），以及按 SHA-256 锁定的 Team Reborn Energy 4.2.0（`run/energy-api/energy-4.2.0.jar` 或 `-Pneoforbric.rebornEnergy`）。没有暂存的 jar 时，`compileRuntimeJava` 会被跳过，`jar` 产出一个没有游戏侧的引导 jar——CI 构建的就是这种 jar。启动时负责发现这种 jar 的是 `KernelRuntimeClasses.verify`。
- **单元测试。** 这些数字是数源码得到的，不是跑出来的：`src/test` 的 438 个 `*Test.java` 文件里有 2 624 个 `@Test` 方法和 2 个 `@ParameterizedTest` 方法（各有两个用例）；`src/transferTest` 的 4 个文件里有 61 个 `@Test` 方法（只数位于行首的注解，用 `grep` 扫已跟踪的文件）。很多测试会读取暂存的 jar，没有它们就会跳过，所以 `./gradlew check` 依据 JUnit XML 断言，而不看 Gradle 的退出码。
- **闸门 —— 已移除。** `run/gate-m*.sh`（58 个脚本）和兼容性夹具 `run/compat/` 已经删除。它们对真实实例的真实日志和文件做断言，大多带有指名的负控制（一个 `-D…=off`，或移除某项输入，必须恰好让指名的那几项检查变红），这本身是有价值的——但它们需要开发者自己的机器、不在本仓库里的第三方 mod 整合包，以及一层在 Windows 上跑不起来的 Bash。现在剩下的是 `./gradlew check`：单元测试套件、`transferTest`、工具自测、安装器打包后的链接闸门，再加上 `tools/junit_report.py` 的 skip 棘轮——某个测试悄悄开始跳过时它会让构建失败。这一层里有两样不是夹具，仍然保留：`run/evidence.py`（`releaseAssets` 调用的发布门禁）和 `run/reports/`（记录下来的兼容性实测证据）。

- **CI**（`.github/workflows/build.yml`）：`build` 作业先自举，再构建 `neoforbric-loader/`。`kernel` 作业（JDK 21，没有游戏文件）在 `neoforbric-kernel/` 中运行 `./gradlew build -Pneoforbric.skipBaseline=ci-unstaged`：编译启动侧，运行不需要游戏文件的单元测试。没有游戏文件时约三分之一的测试会跳过，跳过的集合必须与 `src/test/skip-baseline/ci-unstaged.tsv` 逐行一致（`skipRatchet`、`tools/junit_report.py`）：新开始跳过的测试会让作业失败，不再跳过的行必须删掉。运行页面会显示测试数 / 实际执行 / 跳过数和最常见的跳过原因，JUnit 报告作为 `kernel-test-results` 上传；基线可以用该产物里的 `skips-actual-ci-unstaged.tsv` 或 `-Pneoforbric.writeSkipBaseline` 重新生成。`kernel-prepared` 作业（JDK 21）先在 runner 上用 `tools/dev.py prepare --no-assets` 构建游戏文件（Minecraft 从 Mojang 下载，NeoForge 从它自己的 maven 下载，补丁基底和载体在 runner 上构建，单元测试要读的两个 canary mod 和报告也一并准备好；只缓存上游下载的文件，派生出的东西一律不上传），再用 `-Pneoforbric.requireFixtures=staged,game-side,mc-libraries,java-21` 运行同一套测试外加 `transferTest`：除第三方 mod 整合包以外的各类测试夹具在这里都齐全，所以任何其他类别的跳过、或者没有标注类别的跳过，都会在跳过的那个测试上让作业失败。仍然跳过的 136 个测试全都需要不在本仓库里的第三方 mod 整合包，同样由 `ci-prepared.tsv` 卡住。`development-tools` 作业在 Windows、Linux 和 macOS 上运行 `tools/dev.py tool-test` 和打包后的链接闸门。其余门禁（客户端、第三方整合包、长时间 soak）需要开发者自己的机器，手工运行。

## 17. 系统属性

在 JVM 上用 `-D` 设置。安装好的版本配置一个也不设。`src/main` 和 `src/runtime` 中大约有 250 个不同的 `neoforbric.*` 属性名；几乎每项修复都有一个 `-Dneoforbric.<name>=off` 开关，关掉时会记一条 WARN，说明失去了什么。这些开关是给二分排查和闸门的负控制用的。开发者常用的有：

**策略与报告**

| 属性 | 作用 |
| --- | --- |
| `neoforbric.compatibilityPolicy` | `ask`（默认）、`continue`、`strict`；其他任何值都按 `strict` 处理 |
| `neoforbric.dependencyDialog` | `on`（默认）、`off`、`dryRun` |
| `neoforbric.dialogLanguage` | 强制指定对话框的语言（如 `ja`） |
| `neoforbric.loadReportRewrite` | `off`：以第一次写入的 `load-report.txt` 为准 |
| `neoforbric.crashAnalysis` | `off`：不生成 `crash-analysis.txt` |
| `neoforbric.debug` | 启用 `NeoForbricLog.debug` 的日志行 |

**仲裁与顺序**

| 属性 | 作用 |
| --- | --- |
| `neoforbric.multiLoaderPreference` | 单个 jar 内的生态优先顺序，默认 `neoforge,minecraftforge,fabric` |
| `neoforbric.dupeIdPreference`, `neoforbric.nestedDupePreference` | 顶层 / 内嵌重复项的跨 jar 优先顺序 |
| `neoforbric.modOwner` | `id=loader,…` 形式的锁定；也可用 `<rundir>/neoforbric-mods.txt` |
| `neoforbric.crossJarArbitration` | `off`：同一个 id 的两个 jar 都会加载 |
| `neoforbric.nestedRequirements` | `off`：`minecraft`/`java` 范围不包含本游戏的内嵌 Fabric mod 照样加载；`off:<id>,…`：只有这几个照样加载 |
| `neoforbric.arbitrationMaxNodes` | 选择器的工作量上限（默认 100 000，封顶 1 000 000） |
| `neoforbric.modOrder` | `name`：按文件名决定构造顺序 |
| `neoforbric.fabricOrder` | `off`：Fabric mod 按拓扑顺序，而不是按 mod id 顺序 |
| `neoforbric.fabricMainInConstructor` | `off`：客户端的 Fabric `main` 入口点在 `Minecraft` 之前的窗口里运行 |
| `neoforbric.loaderProbes`, `neoforbric.crossEcosystemPresence` | `off`：平台探测 / 在场查询按单一加载器的方式作答 |

**Mixin**

| 属性 | 作用 |
| --- | --- |
| `neoforbric.relaxGuestMixins` | `off`：不放宽第三方配置 |
| `neoforbric.relaxMixinOverwrites` | 要放宽的配置，csv（支持 `*` 通配） |
| `neoforbric.mixinDiagnostics` | 保持注入要求严格，让每一处不适配都暴露出来 |
| `neoforbric.mixinFit` | `strict`：连 `PARTIAL` 的 mixin 也丢弃 |
| `neoforbric.mixinFit.liveness` | `off`：位于无人调用的方法上的注入器也算已解析 |
| `neoforbric.mixinFit.nativeAbsent` | `off`：mod 自己的平台也没有的注入目标重新算作缺失的锚点 |
| `neoforbric.mixinFit.nativeAbsent.base` | `<摘要>`：让 `native-only-methods.txt` 改为信任这个合并基底成员摘要，而不是表里记录的那个（测试里的夹具游戏用） |
| `neoforbric.mixinOverlapLint` | `off`：启动时不报告跨 mod 的 mixin 重叠（§7.6） |
| `neoforbric.guestMixinAdapter` | `off`：不做推导出来的丢弃，只用手写清单 |
| `neoforbric.mergedBaseCompat` | `off`：去掉内置的不兼容清单 |
| `neoforbric.disableMixinConfigs`, `neoforbric.enableMixinConfigs` | 要禁用 / 强制启用的配置，csv |
| `neoforbric.suppressMixins`, `neoforbric.keepMixins` | 要丢弃 / 保留的 `config:Mixin`，csv |

**诊断与测试驱动**

| 属性 | 作用 |
| --- | --- |
| `neoforbric.clientSmoke`（+ `clientSmokeWorld`、`clientSmokeReadyTicks`、`clientSmokeDisconnectTicks` 等） | 无人值守的客户端运行：进入世界、停留、离开、退出 |
| `neoforbric.eventChainAudit` | 跨总线审计的报告文件 |
| `neoforbric.definedClassEvidence` | 存放每个已定义类的内容寻址记录的目录 |
| `neoforbric.traceClassDefine` | 二进制类名的 csv；每个类第一次被定义时记下调用栈 |
| `neoforbric.tickSampler` | `off`：不对服务端 tick 耗时采样 |

**部分修复开关**——`neoforbric.commonNetworkInterop`、`neoforbric.playPayloadFallThrough`、`neoforbric.chunkExecutorGuard`、`neoforbric.forgeCapabilities`、`neoforbric.forgeWorldgen`、`neoforbric.transferBridge`、`neoforbric.hopperFabricStorage`、`neoforbric.clientResourcePreload`、`neoforbric.earlyConfigs`、`neoforbric.fabricHooks`、`neoforbric.fabricImpl`、`neoforbric.kernelBundledFirst`、`neoforbric.modDataPacks`、`neoforbric.modMenuStandIn`。`neoforbric.kernel.registryRedirect=true` 会启用一个实验性的注册表包装器重定向。

## 18. 不变量

违反其中任何一条，故障通常都会在离原因很远的地方冒出来。

1. **引导代码不直接引用任何游戏类型。** 它通过字符串访问游戏侧，所有这类字符串都在 `KernelRuntimeClasses` 里。跨边界共享的一律是 `ALWAYS_PARENT`；游戏侧的一律是 `ALWAYS_GAME`。每个 JVM 中每个类只有一份。
2. **`MIXIN` 是末端阶段。** 没有任何东西通过 `TransformChain` 注册到它里面；Mixin 拿到的是 Mixin 前的字节；Mixin 后各阶段的顺序是固定的。
3. **加载器身份在第一个类到达 Mixin 转换器之前就已存在。** 晚于这一刻再预置，第三方插件的 `<clinit>` 就会永久决定加载器缓存的 `dist`（FML 的 `FMLEnvironment` 把它存进一次性的 `static final` 字段）。
4. **一个注册窗口，一次冻结。** 内容注册发生在 `unfreeze` 和 `closeRegistrationWindow` 之间；客户端会为自己的入口点重新打开一次，然后重新冻结。
5. **要么生命周期触发点完成重定向，要么内核不启动。**
6. **游戏总线在各初始化阶段之前启动；负载阶段在这些阶段之后关闭。**
7. **仲裁只决定一次。** 预扫描得出的方案由两次发现共同使用；后续各轮只做核验，绝不重新选择。选择的上限按工作量算，不按时间算。
8. **修复一旦停用，就会明说** —— `AnchorSet`/`AnchorLedger`、`EventBridges.verify`、每个开关一条 WARN。
9. **归因从不改变结果。** 错误处理器和报告只做记录；Mixin 的决定和 mod 的失败都保持原样。`CompatibilityDecision` 从不退出 JVM；只有启动边界才会退出。
10. **任何带有 Mojang、MinecraftForge 或 NeoForge 字节的东西都不提交、不分发。** 游戏侧以 `compileOnly` 方式链接暂存的 jar；这些 jar 由安装器在玩家的机器上构建。

## 19. 现状与已知边界

- **仅支持 Minecraft 1.21.1**，Mojmap 基底。基底本身不需要翻译，但 Fabric 一侧在这个基底上**不是**恒等映射：真正的 fabric 模块和 mod 用的是 intermediary 名称，内核在类被提供之前回答这一点——构建时重映射它编译所对的模块，`RefmapRemapper` 把 refmap 的 VALUES 重写进基底的命名空间（KEYS 原样），`kernel/mapping/` 就是做这件事的机器。以 jar 形式安装的第三方 fabric mod 是尚未闭合的边界：整合包期/运行期的 guest mod 重映射是已经记录的后续工作（§20），在它落地之前，guest fabric jar 读到的仍是 intermediary 名称。
- **游戏基底就是 NeoForge 的游戏，没有合并。** NFRT 的二进制补丁结果原样使用：没有第二个生态拼进来，所以不存在要报告的冲突裁决。mod 因为 NeoForbric 给不了而丢掉的东西由 `DeadHookWorklist`、`HookCallSiteCensus`、`FieldDriftAudit`、`AbiLinkAudit`、`CapabilityUseAudit` 报告；已有的修复也是一条通用规则，而不是按 mod 点名的案例。只声明 `META-INF/mods.toml` 的 jar（MinecraftForge mod）属于这里没有加载器能跑的一类。
- **`PARTIAL` 的 mixin 默认应用** —— 宁可保留只应用了一半的结果（并让它可见），也不丢掉还能工作的钩子。
- **一个类，一份副本。** 同一个 mod 的两个生态构建相互竞争时，只有一个胜出；落败的生态看到的是在场别名，而不是该 mod 自己的平台胶水代码。
- **靠实测，不靠承诺。** `MOD_TEST_FAILURES.md` 记录了针对当前 `main` 代码的逐 mod 测试（每个 jar 只带上它必需的依赖单独运行，进入世界、截图、退出），用的是三组全新随机抽取的 Modrinth mod：平均 89.0% 加载时没有失败行（91.8% 进入了世界；79.1% 在加载报告里没有任何一项被标为 DEGRADED），而同一批 jar 在发布版 v0.2.0 上是 80.5%。
- **版本。** `neoforbric-kernel/build.gradle` 写的是 `0.1.0-SNAPSHOT`；安装器是 `0.3.1-beta2`。`net.neoforbric.api` 是内部 API，随时可能变动，不另行通知。

## 20. 延伸阅读

- [`neoforbric-kernel/README.md`](neoforbric-kernel/README.zh-CN.md) —— 内核自己的概述和闸门说明
- [`neoforbric-loader/README.md`](neoforbric-loader/README.zh-CN.md)、[`neoforbric-loader/run/README.md`](neoforbric-loader/run/README.md) —— 第一代，以及产物流水线
- [`neoforbric-loader/CREDITS.md`](neoforbric-loader/CREDITS.md)、[`neoforbric-loader/MAPPINGS.md`](neoforbric-loader/MAPPINGS.md) —— 净室边界与在映射上的立场
- 类的 javadoc。`net.neoforbric.kernel` 下几乎每个类的开头都写着它是为了哪个故障而存在的。

NeoForbric 与 Mojang、FabricMC、MinecraftForge、NeoForged 均无关联。

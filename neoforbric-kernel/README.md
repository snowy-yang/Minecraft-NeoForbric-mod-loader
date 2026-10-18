# NeoForbric 内核

[English](README.en.md) | 简体中文

一个**自主**的统一 Minecraft mod 加载器，在同一个 Minecraft 26.2 实例上运行 **Fabric + 传统 MinecraftForge + NeoForge** 的 mod。它从零重写了 `neoforbric-loader` 的“焊接方案”。

做源码开发的话，先看[开发指南](run/README.md)。在仓库根目录运行 `python3 tools/dev.py client`，会准备一个隔离的游戏环境，然后启动当前的内核。Gradle 还提供 `prepareDev`、`runClient`、`runServer`、`toolTest` 和 `integrationTest` 这几个任务。

## 为什么重写

`../neoforbric-loader` 做到三合一的办法是*把三个真正的独立加载器焊接在一起*：以真正的 fabric-loader/Knot 为宿主（8 个底座补丁），借助反射冒充，并排驱动真正的 FML 和 FancyModLoader，底下是一个按字节合并出来的 3-ABI 游戏基底。这套方案能用，但对 38 堵墙做的一次根因普查表明，**约 53% 的冲突直接由这种架构造成**（生命周期焊接 + 多条流水线的字节合并）。本内核删掉了焊接方案：**一个**转换型类加载器，**一个**生命周期状态机，**一套**注册表/标签模型；三个生态都只是构建在内核自有服务之上的适配器。真正的加载器生命周期从不启动，Forge/NeoForge 的通用 jar 只是被动的 ABI 载体。

重写*消除不了*的两类墙是第三方 mixin/ABI 与合并基底不匹配（C），以及跨生态的注册表/标签/资源/网络语义（D）。它们成了内核的一等工作线：mixin 适配器层；只冻结一次的注册表模型，它让“Tags not bound”在结构上不可能发生。

构建它时依据的里程碑计划不在仓库里。不过计划断言的内容都在，就是 `run/` 下的闸门脚本：每个里程碑一个，各自对真实实例的真实日志做断言。

## 架构（两侧，没有 JPMS 模块层）

- **引导侧**（系统类加载器，`neoforbric-kernel.jar`）：类加载、发现、依赖解析、转换流水线、Mixin 服务、映射、访问权限、元数据，以及内置的 `net.fabricmc.api.*` 接口面。不含任何游戏类型。
- **游戏侧**（`NeoForbricClassLoader`，唯一的转换型类加载器，`neoforbric-kernel-runtime.jar`）：所有引用 `net.minecraft.*` / `net.minecraftforge.*` / `net.neoforged.*` / `net.fabricmc.fabric.*` 的代码，也就是注册表、生命周期、事件、网络、资源这些机制。这一侧针对暂存的 jar 编译，并以 JiJ 方式内嵌 MixinExtras。

旧的“Knot 类加载器分离”法则留了下来，成了内核自己的引导侧↔游戏侧分离。

**游戏侧现状**：已填满。`src/runtime/java`（撰写本文时有 130 个文件，最新数量见 introduction.md §2）针对暂存的 jar 编译，作为 `neoforbric-kernel-runtime.jar` 分发。里面有注册表和生命周期驱动、Forge 系的两个初始化阶段（`FMLCommonSetupEvent` 与 `FMLClientSetupEvent`）、条件求值器、包来源，以及统一的 Mods 界面。本文件的早先版本说它是空的。写那句话时确实如此，后来情况变了，那句话却一直没改。

注入的字节码仍会直接调用一些引导侧的静态成员。还有少数类仍由引导侧的工厂（`KernelModContainerFactory`、`KernelHudBridge`、`KernelGameLookup`）在运行时用 ASM 合成。这些是根本没法编译的情况，每一处都在所在位置写明了原因。

## 统一 API（`net.neoforbric.api`）

正在按领域逐个扩充。它是内核和三个兼容层共同对齐的词汇与服务，免得各层两两互相迁就。它在 `DelegationPolicy` 里固定交给父加载器，理由和 `net.fabricmc.api.` 一样：每个 JVM 里恰好只有一份。

| 类型 | 取代了什么 |
|---|---|
| `Ecosystem` | 五个不同的生态枚举（两个拼写不同，一个缺了 NeoForge，一个已经没人用），外加一段手写的互相转换代码 |
| `ForeignType` | 每个调用点上成对相邻的 Forge/NeoForge 类名字面量 |
| `DiscoveredMod`, `UnifiedDependency` | 从 `kernel.metadata` 移到这里；唯一的 mod 模型 |
| `ModPresence` | `boot.KernelForeignMods`；“mod X 是否在运行”的唯一答案，注入的字节码现在通过 `net/neoforbric/api/ModPresence.isLoaded` 调用它 |
| `ModCatalog` | 玩家看到的唯一列表，以及每个 mod 的最终结果：`OK`、`DEGRADED` 或 `FAILED` |

它建立在两条规则上，两条都是吃过亏才学到的：

- **中枢把各 Forge 系之间的分歧当作数据保留，不取平均把它抹掉**。早先一版统一的订阅者注册一下子弄坏了三样东西（见 `KernelEventSubscribers` 自己的 javadoc）。所以 `ForeignType` 只映射*名字*，两个 `ServerModLoader.load` 触发点保留各自不同的描述符和不同的钩子。
- **不是每次归并都意味着漏了拆分。**`LoaderProbePolicy.Family` 故意只有两个取值：一个 NeoForge mod 探测 MinecraftForge 的 `FMLLoader` 时，仍然必须得到肯定的回答。

## 共用部分（沿用，未重写）

合并基底的流水线留在 `../neoforbric-loader` 里：`src/tools/{MergedBaseBuilder,MergedLinkChecker,
RuntimeInteropPatcher}` + `run/{build-merged-base,assemble-*-runtime}.sh` 产出 3-ABI 游戏 jar 和被动运行时 jar。内核使用这些产物（编译时用 compileOnly 提供类型，启动时由运行环境提供）。LGPL 和 Mojang 衍生的产物从不打包进来。

## 里程碑

`run/` 下原来的闸门脚本、以及 `run/compat/`，已经移除。它们针对真实实例的真实日志和产物做断言，是一份
真正的第二意见，但代价是必须有开发者自己的机器、仓库里没有的第三方 mod 整合包，以及一层在 Windows 上
跑不起来的 Bash。

它们覆盖的东西现在归单元测试：把 staged 游戏文件放好后跑 `./gradlew test` 和 `transferTest`，依据 JUnit
XML 断言而不是 Gradle 的退出码；再加上 `tools/junit_report.py` 的 skip 棘轮——某个测试悄悄开始跳过时它会让
构建失败。

这一层里有两样不是夹具，仍然保留：`run/evidence.py`（安装器 `releaseAssets` 任务调用的发布门禁）和
`run/reports/`（分析文档按路径引用的实测兼容性证据）。

旧的 `neoforbric-loader` 仍保持可运行，用作**差分对照基准**，并且仍负责构建内核所用的共享游戏产物。

## 构建与运行

```sh
./gradlew --offline test          # the unit suite; ~a third of it reads the staged game jars and
                                  # SKIPS without them, so read the results, not Gradle's exit code
./gradlew --offline jar           # boot jar
# unified discovery over a mods/ dir → deterministic JSON (the oracle anchor):
java -cp <boot-cp> net.neoforbric.kernel.boot.Main --scan --mods <dir> --report out.json
```

构建产出 Java 21 字节码（`options.release = 21`），游戏本身需要 Java 25。依赖来自 maven.fabricmc.net + Maven Central。

## 许可证

Apache-2.0（`LICENSE`）。内置的 Fabric Loader 源码的署名，以及对 FML/FancyModLoader 的净室立场，见 `NOTICE`。

# HookBus 定稿

内核内部使用的钩子总线。**不对外**：mod 永远看不到这个类型，它只在内核自己的桥接代码和注入进基底的字节码之间传话。

状态：**接口定稿**。`net.neoforbric.kernel.hook` 已实现并有测试锁定契约；字节码生成器（`HookEmitter`）属下一阶段，本文第 5 节给出它必须满足的形态。

---

## 1. 清点：两个生态到底要钩什么

数据从暂存产物实测得出，不是估计：

| | fabric-api | NeoForge |
|---|---:|---:|
| 来源 | 49 个内嵌模块 / 57 份 mixin 配置 | 载体里 11 个 `*Hooks` 类，其中 8 个在基底里有调用点 |
| 声明形式 | 311 条 mixin 条目 | 488 处调用点 |
| 粒度 | 目标**类** | 目标**(类, 方法)** |
| 唯一目标 | 233 个类 | 208 个类 / 368 个接缝 |
| 端分布 | 215 通用 + 96 客户端 | 137 / 488 在客户端侧 |

两者合起来：

- **并集 377 个类**
- **交集 64 个类** —— 这些是 HookBus 的核心覆盖对象，两个生态都要钩同一个类

钩子来源分布：

| 钩子类 | 调用点 |
|---|---:|
| `EventHooks` | 199 |
| `CommonHooks` | 137 |
| `ClientHooks` | 113 |
| `ServerLifecycleHooks` | 24 |
| `DataMapHooks` | 6 |
| `DataPackRegistriesHooks` | 4 |
| `VanillaInventoryCodeHooks` | 4 |
| `MonsterRoomHooks` | 1 |

最密的接缝：`MinecraftServer#runServer`（13 处）、`LevelRenderer#renderLevel`（11 处）、`KeyboardHandler#lambda$keyPress$5`（4 处）。调用最频繁的钩子成员：`canEntityGrief`（25）、`canLivingConvert`（11）、`onLivingConvert`（10）、`canCropGrow`（10）、`fireCropGrowPost`（10）。

**复现**：`python3 tools/survey_seams.py`（仓库根目录下跑）。它从 `.dev/` 的暂存产物重新算一遍上面每个数字，需要 JDK 21 的 `javap`。做法是把 fabric-api 的内嵌模块解出来读每份 `*.mixins.json` 的 `package` + `mixins`/`client`/`server`，对每个 mixin 类跑 `javap -v` 读 `@Mixin` 注解；NeoForge 侧在基底 jar 里找引用那 8 个钩子类的类，`javap -c` 取每个调用点所在的方法。命名空间用 `.dev/api/intermediary-1.21.1.jar`（obf→intermediary）与 `.dev/mappings/mojmap-1.21.1.proguard`（mojmap→obf，要反过来读）拼出 intermediary→Mojmap 的链。

`tools/test_survey_seams.py` 锁住两个方向的读取和 `$` 内部类规则——读反了照样能算出一个像样的数字，只是每个接缝都指向错的类。

---

## 2. 范围

`transform/` 下 70 个类不是一类东西。按"是否引用 runtime 侧"分，实测两类：

| 类别 | 数量 | 是否走 HookBus |
|---|---:|---|
| **桥接** —— 往基底字节码里插一个对 runtime 侧的调用，让某个生态的钩子响 | 23 | **是** |
| **修复** —— 不派发，纯改写基底自己的字节码，把 NeoForge 补丁弄坏的东西补回来 | 47 | 否 |

**HookBus 只覆盖那 23 个桥接类。** 剩下的 47 个塞不进来——它们不派发，没有 handler，硬塞会变成"为了用总线而伪造事件"，比现在更糟。它们收敛到 `SeamRewriter`（另一份定稿）。

这不是妥协，是分类：一个东西要么有观察者，要么没有。有观察者的走总线，没有的走改写。

---

## 3. 标识

```java
record HookSite(String owner, String name, String desc)   // 被钩的方法（宿主）
record HookTarget(String owner, String name, String desc) // INVOKE/FIELD 时被调用的成员
enum   HookPoint { HEAD, RETURN, INVOKE, FIELD }

record HookKey(HookSite site, HookPoint point, HookTarget target, int ordinal)
```

- `owner` 是**内部名**（`net/minecraft/world/entity/LivingEntity`），不是点分名。整个内核的 ASM 侧都用内部名，这里不引入第二种写法。
- `desc` 是 JVM 方法描述符（`(Lnet/minecraft/world/damagesource/DamageSource;F)Z`）。
- `target` 只在 `INVOKE` / `FIELD` 时有值，`HEAD` / `RETURN` 时为 `null`。
- `ordinal` 区分同一方法里同一 `target` 的第 N 次出现；`-1` 表示"全部"。

构造即校验，畸形输入立刻抛 `IllegalArgumentException`，不留到运行时：

| 字段 | 规则 |
|---|---|
| `owner` | 非空；不含 `.`、`;`、`[`、`(`；不以 `/` 开头或结尾 |
| `name` | 非空；允许 `<init>` / `<clinit>` |
| `desc` | 以 `(` 开头；`(` 与 `)` 配对；`)` 之后有且只有一个合法返回类型（含 `V`） |

`RETURN` 同时覆盖 Mixin 的 `RETURN` 与 `TAIL`——`ordinal = -1` 是最后一个返回点，其余按出现顺序编号。不为 `TAIL` 单开一个枚举值。

---

## 4. 契约

### 4.1 顺序

**优先级降序，同优先级按注册顺序升序。**

两个键独立排序，不共享状态。同一 `HookKey` 上重复订阅同一个 `HookBody` 实例是幂等的（第二次不生效），不同实例按各自优先级排。

排序在订阅时确定，派发时用不可变快照：**派发中途的订阅/退订不影响本次派发**。

### 4.2 取消

```java
sealed interface HookResult {
    record Continue() implements HookResult {}
    record Cancel(Object value) implements HookResult {}   // value 可为 null（void 方法）
}
```

- 按顺序调用，**第一个 `Cancel` 生效**，其后的 body 不再调用。
- 全部 `Continue` 则 `dispatch` 返回 `Continue`。
- `Cancel.value` 为 `null` 只对返回 `void` 的宿主方法合法；对非 `void` 宿主，生成器负责装箱/拆箱，总线不解释类型。

### 4.3 错误

一个 body 抛异常：**捕获、记录、继续派发**。

- 记录进 `HookErrorLedger`，条目含 `HookKey`、body 的注册方标识、异常本身。
- 不改变派发结果：该 body 视为 `Continue`。
- 理由和 §18 的"归因从不改变结果"一致——一个观察者写崩了，不该让别人的钩子跟着消失。同时它必须可见，否则就是静默腐坏。

### 4.4 并发

`HookBus` 线程安全。派发无锁（读不可变快照），订阅/退订加锁。服务端 tick 和客户端渲染线程会同时派发不同的键。

### 4.5 类型校验

总线**不做**运行期反射校验——每个派发都在热路径上。校验发生在两处：

1. **构造时**：`HookSite` / `HookTarget` 的描述符结构性校验（第 3 节）。
2. **绑定时**：`HookEmitter` 拒绝绑定到它解析不出 JVM 类型的描述符。

body 拿到的 `HookCall` 里参数是 `Object[]`，装箱由生成器负责。总线不猜类型。

---

## 5. 字节码形态

生成器必须产出的形态，`HEAD` 为例（宿主 `m(A)B`）：

```
LDC      <bindingId:int>
<把 this/参数装箱成 Object[]>
INVOKESTATIC  net/neoforbric/kernel/hook/HookDispatch.dispatchHead
              (I[Ljava/lang/Object;)Lnet/neoforbric/kernel/hook/HookResult;
DUP
INSTANCEOF    net/neoforbric/kernel/hook/HookResult$Cancel
IFEQ          <continue>
CHECKCAST     net/neoforbric/kernel/hook/HookResult$Cancel
INVOKEVIRTUAL ...$Cancel.value()Ljava/lang/Object;
<拆箱成 B>
IRETURN
<continue:>
POP
<原方法体>
```

硬性要求：

- **bindingId 稳定**：由 `HookKey` 的规范化字符串哈希得出，跨启动一致，便于日志和崩溃归因。
- **不改变栈映射帧的语义**：插入点前后的 `FrameNode` 必须重算，否则 `VerifyError`。这条已有先例（`MergedBaseFrameRecomputer`）。
- **宿主是 `void` 时**，`Cancel.value` 丢弃，直接 `RETURN`。
- **`RETURN` 点**：每个 `RETURN` 指令前插入，`Cancel` 时用其值替换返回值。
- **`INVOKE` / `FIELD`**：不改栈形状，只在调用前后各留一个观察点；`Cancel` 在 `INVOKE` 上无意义，只允许 `Continue`。

---

## 6. 明确不做的事

| 不做 | 理由 |
|---|---|
| 给 mod 用 | 内核内部。mod 仍然调真正的 Fabric API 和真正的 NeoForge 类（§1 的约束不变） |
| 事件对象模型 | 总线只传 `Object[]`。事件类型是上层 API 的事，不是总线的 |
| 按名字的模糊匹配 | 每个键都是精确的 `owner/name/desc`。模糊匹配会让接缝在载体升级时静默漂移 |
| 自动发现接缝 | 接缝表由普查推导并锁进测试（第 1 节的方法），不在运行时猜 |
| 覆盖那 47 个修复类 | 见第 2 节 |
| 反射调用 body | body 是 `HookBody` 实例，直接接口调用 |

---

## 7. 验收

`src/test/java/net/neoforbric/kernel/hook/` 下的测试锁定以下契约，缺一不可：

1. **标识校验** —— 畸形 `owner` / `name` / `desc` 全部抛 `IllegalArgumentException`，合法的全部接受。
2. **顺序** —— 优先级降序；同优先级按注册顺序；两个键互不干扰。
3. **取消** —— 第一个 `Cancel` 生效，其后的 body 不被调用；全 `Continue` 返回 `Continue`。
4. **错误隔离** —— 一个 body 抛异常，其余照常运行，异常进账本，派发结果不受影响。
5. **幂等与退订** —— 重复订阅同一实例不生效；退订后不再调用；退订不存在的订阅不抛。
6. **快照** —— 派发中途订阅不影响本次派发。
7. **无游戏类型** —— `hook` 包的类字节里不出现 `net/minecraft/` 或 `net/neoforged/` 引用。这条用 ASM 查常量池，不靠反射。
8. **确定性** —— 相同注册序列产生相同派发序列，跑两遍结果一致。

# NeoForbric Kernel

English | [简体中文](README.md)

A **sovereign** unified Minecraft mod loader that runs **Fabric + NeoForge**
mods on one Minecraft 26.2 instance — a ground-up rewrite of the `neoforbric-loader` "weld".

For source development, start with [the development guide](run/README.md). From the repository root,
`python3 tools/dev.py client` prepares an isolated game environment and launches the current kernel.
Gradle also exposes `prepareDev`, `runClient`, `runServer`, `toolTest` and `integrationTest`.

## Why a rewrite

`../neoforbric-loader`, the first generation, reached tri-in-one by *welding three genuine sovereign loaders
together*: real fabric-loader/Knot as host (8 substrate patches), real FML and FancyModLoader reflectively
impersonated and driven side-by-side, over a byte-merged 3-ABI game base. It works, but a 38-wall root-cause
census showed **~53% of the conflicts are direct artifacts of that architecture** (lifecycle-welding +
multi-pipeline byte-merge). This kernel deletes the weld: **one** transforming classloader, **one** lifecycle
state machine, **one** registry/tag model — with the two ecosystems reduced to adapters over kernel-owned
services. No genuine loader lifecycle ever boots; the NeoForge universal jar is a passive ABI carrier only.

The two wall categories a rewrite does *not* eliminate — guest-mixin/ABI mismatch against the NeoForge-patched
base (C), and cross-ecosystem registry/tag/resource/network semantics (D) — become first-class kernel workstreams
(the mixin adapter layer; the single-freeze registry model that makes "Tags not bound" structurally impossible).

The milestone plan this was built against is not in the repository; what it asserted is, as the gate
scripts under `run/` — one per milestone, each asserting on the real logs of a real instance.

## Architecture (two sides, no JPMS module layer)

- **BOOT side** (system classloader, `neoforbric-kernel.jar`): classloading, discovery, dependency resolution,
  the transform pipeline, the Mixin service, mapping, access, metadata, the vendored `net.fabricmc.api.*`
  surface. No game types.
- **GAME side** (`NeoForbricClassLoader`, the one transforming CL, `neoforbric-kernel-runtime.jar`): everything that
  references `net.minecraft.*` / `net.neoforged.*` / `net.fabricmc.fabric.*` —
  registry/lifecycle/event/network/resource machinery — typed against the staged jars, JiJ-nesting MixinExtras.

The old "Knot classloader split" law survives as the kernel's own boot↔game split.

**Status of the game side:** filled. `src/runtime/java` (130 files at the time of writing; introduction.en.md §2
keeps the current count) is compiled against the staged jars and shipped as `neoforbric-kernel-runtime.jar` —
registry and lifecycle drivers, the two setup phases (`FMLCommonSetupEvent` and `FMLClientSetupEvent`),
the condition evaluators, the pack sources, the
unified Mods screen. An earlier revision of this file said it was empty, which was true when it was written
and stopped being true without the sentence changing.

Injected bytecode still calls some boot-side statics directly, and a few classes are still synthesized at
runtime with ASM by boot-side factories (`KernelModContainerFactory`, `KernelHudBridge`, `KernelGameLookup`) —
those are the cases that cannot be compiled at all, and each says why where it lives.

## The unified API (`net.neoforbric.api`)

Being grown domain by domain: the vocabulary and services the kernel and both compatibility layers align to,
instead of accommodating each other pairwise. Parent-pinned in `DelegationPolicy` for the same reason
`net.fabricmc.api.` is — exactly one copy per JVM.

| type | what it replaced |
|---|---|
| `Ecosystem` | five different ecosystem enums (two with different spellings, one missing NeoForge, one dead) plus a hand-written translator |
| `ForeignType` | pairs of adjacent class-name literals at each call site |
| `DiscoveredMod`, `UnifiedDependency` | moved here from `kernel.metadata`; the one mod model |
| `ModPresence` | `boot.KernelForeignMods`; the one answer to "is mod X running", which injected bytecode now calls as `net/neoforbric/api/ModPresence.isLoaded` |
| `ModCatalog` | the one list a player sees, and what became of each mod — `OK`, `DEGRADED` or `FAILED` |

Two rules it is built on, both learned the hard way:

- **The hub carries per-ecosystem divergence as data; it does not average it away.** An earlier unified
  subscriber registration broke three things at once (see `KernelEventSubscribers`' own javadoc). So
  `ForeignType` maps *names* only.
- **Not every grouping is a missing split.** `LoaderProbePolicy.Family` stays two-valued on purpose: a
  NeoForge mod probing for the Forge family's `FMLLoader` must still be told yes.

## The hook bus (`kernel.hook`)

The kernel's internal dispatch layer. **Not public**: no mod ever sees it. Of the seventy classes in `transform/`,
twenty-three splice a call to the game side so an ecosystem's hook fires, and those go through this bus; the other
forty-seven rewrite the base's own bytecode and dispatch to nobody, and belong to `SeamRewriter`.

The contract — ordering, cancellation, error isolation, threading, key validation — and the measurement behind the
split are in [`HOOKBUS.md`](HOOKBUS.md) (Chinese). The numbers are measured, not estimated: fabric-api's 49 modules
carry 311 mixin entries naming 233 classes; NeoForge's 8 hook classes appear at 488 call sites across 208 classes;
377 classes in union, 64 in both.

## Shared assets (kept, not rewritten)

The staging pipeline stays in `../neoforbric-loader`: its `run/` scripts produce the NeoForge-patched game jar
and the passive NeoForge runtime jar. The kernel consumes them (compileOnly for typing, runtime-supplied at
launch). LGPL / Mojang-derived artifacts are never bundled.

## Milestones

The gate scripts that used to live under `run/`, and `run/compat/` with them, have been removed. They asserted
on real logs and artifacts from a real instance — a genuine second opinion, but one that needed a developer's
own machine, third-party mod packs that are not in this repository, and a Bash layer that cannot run on Windows.

What they covered is the unit suite's job now: `./gradlew test` and `transferTest` with the staged game files in
place, asserted from the JUnit XML rather than Gradle's exit code, plus the skip ratchet in
`tools/junit_report.py`, which fails the build when a test quietly starts skipping.

Two things from that layer were not fixtures and are still here: `run/evidence.py`, the release gate that the
installer's `releaseAssets` task calls, and `run/reports/`, the recorded compatibility evidence the analysis
documents cite by path.

The old `neoforbric-loader` is kept runnable as the **differential oracle**, and still builds the shared game
artifacts the kernel consumes.

## Build & run

```sh
./gradlew --offline test          # the unit suite; ~a third of it reads the staged game jars and
                                  # SKIPS without them, so read the results, not Gradle's exit code
./gradlew --offline jar           # boot jar
# unified discovery over a mods/ dir → deterministic JSON (the oracle anchor):
java -cp <boot-cp> net.neoforbric.kernel.boot.Main --scan --mods <dir> --report out.json
```

Build targets Java 21 bytecode (`options.release = 21`); the game itself needs Java 25. Deps come from
maven.fabricmc.net + Maven Central.

## License

Apache-2.0 (`LICENSE`). Attribution for vendored Fabric Loader sources and the clean-room stance on
FML/FancyModLoader: `NOTICE`.

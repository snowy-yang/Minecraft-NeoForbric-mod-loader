# Credits & provenance

NeoForbric is a clean-room unified mod loader. It stands on the work of two prior loaders.

## Fabric Loader — substrate (reused, not vendored)

NeoForbric's core — class loading, mod discovery and dependency solving, metadata
parsing, the Mixin service, the game-provider framework — **is**
[Fabric Loader](https://github.com/FabricMC/fabric-loader), © FabricMC, licensed
under the **Apache License 2.0**.

This repository does not contain that source. `bootstrap.sh` clones the upstream
repository, at the release pinned as `fabric_loader_ref` in `gradle.properties`,
into a sibling `fabric-loader/` directory, and `build.gradle` compiles its source
roots alongside NeoForbric's own. So no file here carries a FabricMC copyright
header — but the jar the build produces does contain compiled FabricMC code,
which remains © FabricMC under the Apache License 2.0. See [NOTICE](NOTICE).

The eight changes NeoForbric needs in upstream's code live as unified diffs in
`patches/fabric-loader/`, applied by `bootstrap.sh` and re-verified against the
pinned release by `run/verify-substrate-patches.sh`. Keeping them as patches,
rather than editing a vendored copy, is what makes "what did NeoForbric change in
Fabric Loader" a one-command question.

The mod-facing API packages (`net.fabricmc.api`, `net.fabricmc.loader.api`) are
untouched by those patches and keep their original names, so existing Fabric mods
link against them unchanged.

## Forge Mod Loader (FML) — behavioural reference only (NOT reused)

NeoForbric's Forge-compatibility code was written **clean-room** from public
specifications. [FML](https://github.com/MinecraftForge/FML), © cpw and
contributors, licensed under the **GNU LGPL v2.1 or later**, was read only as a
behavioural reference for:

- Access Transformer semantics and the `.cfg` directive grammar,
- SRG naming behaviour,
- the coremod ordering algorithm (sort index + topological pre-depends),
- Maven version-range semantics,
- the Forge mod lifecycle / event model.

**No FML source code is included in NeoForbric.** This keeps the distribution free
of LGPL-derived code, so the whole project remains Apache-2.0. The Forge-side
support targets **modern** Forge (`mods.toml` + ModLauncher) and NeoForge
(`neoforge.mods.toml`), not the 1.8-era FML that was used as a reference.

> Maintainer note: do not copy code from FML (or any other LGPL/GPL source) into
> the `net.neoforbric.*` tree. If LGPL-derived code is ever genuinely needed, it must
> live in a separately-licensed sibling module with its own `LICENSE-lgpl.txt` and
> a source-availability offer.

## NeoForge runtime — supplied at run time, not redistributed

NeoForbric loads the genuine NeoForge runtime rather than
reimplementing them. Those jars are not in this repository and are not
distributed with it: the scripts under `run/` fetch them from their upstream
Maven repositories (`maven.neoforged.net`) and build
the patched Minecraft base they need on the machine that runs them. NeoForbric's own
driver (`NeoForbricNeoForgeRuntime`) reaches it
reflection-only, so the loader carries no compile-time dependency on either.

## Mappings

See [MAPPINGS.md](MAPPINGS.md). NeoForbric never bundles MCP data.

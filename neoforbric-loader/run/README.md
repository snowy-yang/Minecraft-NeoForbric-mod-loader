# `run/` — first-generation loader tooling (the kernel pipeline superseded it)

For current development, start with [the kernel development guide](../../neoforbric-kernel/run/README.md) and
the installer ([`../../neoforbric-kernel-installer/`](../../neoforbric-kernel-installer/)). This directory keeps
the first-generation loader's tooling — the Knot-based boot path and the scripts that assemble its runtime
carriers. It still produces shared staged artifacts the kernel's tests read.

NeoForbric's loader is Apache-2.0 source. What it runs on top of is not: the Minecraft jar belongs to Mojang,
and the NeoForge runtime is LGPL. None of that is committed here. Everything the scripts produce — patched
game jars, runtime carriers, game instances, logs, worlds — is deliberately ignored (see `../.gitignore`).

## What is here today

| Script | Produces / does |
|---|---|
| `assemble-neoforge-runtime.sh` | `neoforge-runtime.jar` — the Knot-loaded NeoForge API + registration bridge carrier. The kernel's staged tree names it `neoforge-runtime/neoforge-runtime.jar`. |
| `launch-1.21.11.sh` | MC **1.21.11** through the NeoForbric loader, on the Fabric **intermediary** namespace: vanilla obf jar + Fabric's intermediary + natives from a local Fabric profile. This is the loader path's 1.21.x anchor. |
| `launch-client-26.2.sh`, `launch-server-26.2.sh` | MC **26.2** boots on the NeoForge-patched, Mojmap-named base — identity mode, no intermediary remap. Kept for the 26.2 generation they were written against; the current kernel anchor is 1.21.1 (see the kernel's `launch-kernel-server.sh`). |
| `regress-client.sh` | Client regression gate: quick-play into a fixed singleplayer world, wait for the client-ready canary. |
| `regress-real-mods.sh` | The standing regression gate for loader-side changes: substrate patches, unit tests, a headless registration baseline, and a real server boot with real third-party mods. |
| `build-testmods.sh` | Compiles the canary mods from `livemod-src-neoforge/` against the runtime-supplied patched MC. |
| `check-merged-links.sh` | The integration/release link gate — every named jar must exist and its committed symbol baseline must match. Missing inputs are failures; nothing is rebuilt here. |
| `test-link-check.sh` | Self-test for MergedLinkChecker's baseline mode, on synthetic jars — no staged artifacts needed. |
| `verify-substrate-patches.sh` | The required `../fabric-loader` substrate patches still match `patches/fabric-loader/*.patch`. |

MC 26.2 ships deobfuscated, so the 26.2 launchers run in identity mode. The 1.21.x path is the one that needs
an `intermediary` mapping and a Fabric profile for LWJGL natives.

Launchers read your Minecraft directory from `$MC_DIR`; several scripts default to the macOS location.
Inspect the selected script's platform assumptions before using it on another operating system.

## Mod sources

- `bridge-src-neoforge/` — NeoForbric's own bridge mod. It opens the Fabric-content window inside the
  genuine Forge-family registration span, so Fabric-registered content lands in the same window NeoForge
  mods register in.
- `livemod-src-neoforge/` — the canaries: a server config that must sync over the socket, gameplay events,
  and cross-ecosystem mod-presence lookups, each logging a line per outcome. Built by `build-testmods.sh`.
- `testmod-src/` — minimal Fabric and NeoForge probe mods, used to check that discovery classifies each one
  correctly and that a remapped class links against the live game.

## Checks

- `verify-substrate-patches.sh` — the required `../fabric-loader` patches still match
  `patches/fabric-loader/*.patch`. Compared against the pinned upstream release, so it passes whether you
  left them as working-tree changes or committed them. `../../bootstrap.sh` runs it for you.
- `regress-real-mods.sh` — the standing regression gate for loader-side changes: substrate patches, unit
  tests, a headless registration baseline, and a real server boot with real third-party mods.

## Historical note

The tables above replace a pipeline that no longer exists in this directory: `build-patched-forge.sh`
(traditional-MinecraftForge-patched 26.2 jar — Forge support is removed from the project),
`build-merged-base.sh` + `dedupe-runtime-overlap.sh` (the both-families merged jar — the kernel now boots
NeoForge's own patched 1.21.1 base instead), and the `launch-*-merged.sh` pair. The kernel's
`patched-mc-neoforge-1.21.1.jar` is staged by `tools/dev.py prepare`, not by anything here.

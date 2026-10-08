# Fluid-interaction canaries

Two mods for `run/compat/fluid-parity-gate.py --mods`, each using only its own loader's API and registering at common
setup, as mods do:

- `neo/` — `neoforbricfluidneo`, NeoForge: lava next to an iron block or an emerald block becomes glowstone.
- `forge/` — `neoforbricfluidforge`, MinecraftForge: lava next to a lapis block or an emerald block becomes shroomlight,
  and water next to a lapis block becomes a sponge.

Each prints `[FluidCanary] <loader> <fluid> rule fired at x, y, z` when its rule runs. The gate compiles each against
the native loader installed by `native-controls.py prepare` (never against a NeoForbric carrier), runs it on that native
server, and runs both unchanged on the kernel. The emerald block is matched by both mods, so the gate can see whether
one liquid reacts twice. Test fixtures only.

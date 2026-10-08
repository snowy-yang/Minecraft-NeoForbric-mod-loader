#!/usr/bin/env bash
# M45 — the things every player does: craft, smelt, brew, swim, meet the Ender Dragon. Each of them threw on the merged base.
#
#   crafting remainders — fabric-item-api-v1's class tweaker injects FabricItem into Item, whose merged hierarchy has
#     MinecraftForge's IForgeItem; both default getCraftingRemainder(ItemStack), so with fabric-api installed every
#     crafting-table result, brew and bucket fuel threw IncompatibleClassChangeError (InterfaceDefaultConflictRepair now
#     judges a conflict against the jar's own interfaces and settles this one through NeoForge's overload).
# A dedicated server with a probe mod (canary/everyday-actions) and the unmodified fabric-item-api-v1:
#   remainders through ItemStack, Item(ItemStack) and NeoForge's Item(ItemInstance) (control); a cake crafted from its
#   recipe leaves three buckets; an idle furnace ticks and a lit one smelts raw iron (controls); a brewing stand makes
#   awkward potions; a pig stands in a Fabric mod's untagged fluid (no interaction) and in its water-tagged fluid (it
#   swims); a dragon is added and found by its part, hurt through it, and removed (controls); the server stops cleanly.
#   Fabric fuels — fabric-content-registries fires its fuel events from vanilla's vanillaBurnTimes, which the merged
#     game never calls (it builds fuels from NeoForge's data map), so a Fabric mod's fuel could not go in a furnace
#     (FabricFuelValuesInjector runs them on NeoForge's builder): coal (control), the probe mod's dirt fuel, and the
#     carpets its exclusion removes.
#   Fabric fluids — a Fabric mod's fluid declares no NeoForge FluidType, and NeoForge's lookup threw "Mod fluids must
#     override getFluidType" at the first entity to touch one: 'Ticking entity' took the server down
#     (ForeignFluidTypeInjector gives it the type its fluid tags imply).
#
#   1. positive — STRICT, every case passes, zero confirmed required findings, no exception on stop.
#   2. off — -Dneoforbric.defaultConflictRepair=off -Dneoforbric.foreignFluidTypes=off -Dneoforbric.fabricFuel=off: exactly
#      the repaired cases fail and the controls hold.
#      forgePartTracking is in that list because since 86c93b6c the dragon's null NeoForge getParts() is guarded twice:
#      ForgePartTrackingInjector reads it as no parts in the server's tracking callbacks. With only dragonParts off the
#      dragon is then added without its parts (dragon.add still fails: part lookup=null), and hurting it through a part
#      and removing it no longer throw, so dragon.hurt and dragon.remove passed and the first nightly went red on the
#      control, not on a repair. Both switched off, the three dragon cases fail as they did before (measured).
# Not covered here: a client (the dragon's parts in the client's entity lookups), and a native server as an oracle.
# GATE-PARALLEL: rundirs=server-everyday-m45 mem=2000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SERVER_DIR="$KERNEL/run/server-everyday-m45"
RESULTS="$BUILD/verification/m45-everyday-actions"
FAIL=0
REPAIRED="{'remainder.stack', 'remainder.item', 'craft.cake', 'fuel.fabric', 'fuel.exclusion', 'fluid.untagged', 'fluid.water'}"
rm -rf "$RESULTS"; mkdir -p "$RESULTS"

kernel_jar
bash "$KERNEL/run/build-everyday-actions-canary.sh" > "$RESULTS/build.log" 2>&1 || { cat "$RESULTS/build.log"; exit 1; }

# run_server <phase> <policy> <extra jvm flags>
run_server() {
  local phase="$1" policy="$2" extra="$3" pid
  mkdir -p "$SERVER_DIR"
  reap_stale_server "$SERVER_DIR"
  rm -rf "$SERVER_DIR/world" "$SERVER_DIR/mods" "$SERVER_DIR/.neoforbric-kernel" "$SERVER_DIR/logs"
  mkdir -p "$SERVER_DIR/mods"
  cp "$KERNEL/run/canary/neoforbriceveryday.jar" "$KERNEL/run/canary/neoforbricgoo.jar" "$KERNEL"/run/canary/m45-modules/*.jar "$SERVER_DIR/mods/"
  echo "eula=true" > "$SERVER_DIR/eula.txt"
  seed_server_properties "$SERVER_DIR"
  printf 'level-name=world\nlevel-type=minecraft:flat\ngenerate-structures=false\nmax-tick-time=-1\npause-when-empty-seconds=0\nonline-mode=false\n' >> "$SERVER_DIR/server.properties"
  RUNDIR="$SERVER_DIR" NEOFORBRIC_COMPAT_POLICY="$policy" \
    NEOFORBRIC_JVM="-Dneoforbric.everydayProbe=$RESULTS/$phase.json -Dneoforbric.everydayPhase=$phase $extra" \
    "$KERNEL/run/launch-kernel-server.sh" < /dev/null > "$RESULTS/$phase.log" 2>&1 &
  pid=$!; record_server_pid "$SERVER_DIR" "$pid"
  await_server "$pid" "$RESULTS/$phase.log" 240 30
  rm -f "$SERVER_DIR/.neoforbric-gate.pid"
  cp "$SERVER_DIR/.neoforbric-kernel/compatibility-report.json" "$RESULTS/$phase-compatibility.json" 2>/dev/null || true
}

# judge <phase> <python expression over `failed`> <what>
judge() {
  local phase="$1" rule="$2" what="$3"
  check "$phase: the server started" 'Done \(' "$RESULTS/$phase.log"
  if python3 - "$RESULTS/$phase.json" "$phase" "$rule" <<'PY'
import json, sys
report, phase, rule = json.load(open(sys.argv[1])), sys.argv[2], sys.argv[3]
assert report['phase'] == phase, report['phase']
cases = {c['name']: c for c in report['cases']}
assert len(cases) == 15, sorted(cases)
failed = {name for name, c in cases.items() if not c['pass']}
for name in sorted(failed): print(f"[kernel]   {phase}: {name} failed — {cases[name]['detail'][:240]}")
assert eval(rule, {'failed': failed, 'cases': cases}), (phase, sorted(failed))
PY
  then echo "[kernel] PASS $phase: $what"
  else echo "[kernel] FAIL $phase: $what (see $RESULTS/$phase.json)"; FAIL=1; fi
}

step "1. positive: crafting, smelting, brewing and the dragon work"
run_server positive strict ""
judge positive "not failed" "all 15 cases pass"
check_absent "positive: the server stopped without an exception" 'Exception stopping the server|still alive .* after announcing its stop' "$RESULTS/positive.log"
if python3 -c "import json,sys; r=json.load(open(sys.argv[1])); sys.exit(0 if r['confirmedRequired']==0 else 1)" "$RESULTS/positive-compatibility.json" 2>/dev/null
then echo "[kernel] PASS positive: zero confirmed required findings under STRICT"
else echo "[kernel] FAIL positive: STRICT report missing or has confirmed required findings"; FAIL=1; fi
check "positive: Item's remainder conflict was settled" 'Item inherits getCraftingRemainder.*gave it one that asks getCraftingRemainder\(Lnet/minecraft/world/item/ItemInstance' "$RESULTS/positive.log"
check "positive: foreign fluids get a NeoForge type" 'gets the one its fluid tags imply' "$RESULTS/positive.log"
check "positive: Fabric's fuel events run on NeoForge's fuel builder" 'NeoForbric/Fuel\] DataMapHooks.populateFuelValues runs fabric-content-registries' "$RESULTS/positive.log"

step "2. off: the same server with the repairs switched off"
run_server off continue "-Dneoforbric.defaultConflictRepair=off -Dneoforbric.foreignFluidTypes=off -Dneoforbric.fabricFuel=off"
judge off "failed == $REPAIRED" "exactly the repaired cases fail; the controls hold"

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M45 EVERYDAY GATE GREEN — crafting, smelting, brewing, mod fluids and the dragon work, each by its repair"
else
  echo "[kernel] ❌ M45 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"

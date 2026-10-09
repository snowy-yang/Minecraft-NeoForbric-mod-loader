#!/usr/bin/env bash
# Launch the MC 26.2 CLIENT through the NeoForbric loader, from the NeoForge-patched, Mojmap-named game jar,
# loading NeoForge + Fabric mods together (Mojmap-canonical / identity mode).
#
# 26.2 is Mojmap-native (the vanilla jar is already deobfuscated), so NeoForbric runs identity — no intermediary,
# no remap (-Dneoforbric.runtimeNamespace=named). The Knot-loaded halves are dropped into mods/:
#   - neoforbricruntime.jar      : the loader's NeoForge runtime driver (preLaunch entrypoint).
#   - neoforge-runtime.jar       : the runtime-supplied NeoForge runtime (universal + FML/bus libs),
#                                   assembled by run/assemble-neoforge-runtime.sh (NeoForge is loaded at
#                                   runtime, never bundled in loader source).
# This is the client twin of run/launch-server-26.2.sh: one patched base, one runtime, one bridge. Nothing here
# stages a second ecosystem's runtime, and no patch is needed across runtime jars because there is only one.
#
# Usage: [PATCHED=…] [NFRT_JAR=…] [BRIDGE_JAR=…] [RUNDIR=…] [NEOFORBRIC_JVM=…] ./launch-client-26.2.sh [extra args]
set -euo pipefail

MC="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT="$(cd "$HERE/.." && pwd)"

PATCHED="${PATCHED:-$HERE/neoforge-patched/patched-mc-neoforge-26.2.jar}"
NFRT_JAR="${NFRT_JAR:-$HERE/neoforge-runtime/neoforge-runtime.jar}"
RUNDIR="${RUNDIR:-$PROJECT/run/client-26.2}"
NATIVES="${NATIVES_DIR:-$MC/versions/26.2/26.2-natives}"
ASSETS="$MC/assets"
mkdir -p "$RUNDIR/mods"

if [ ! -f "$PATCHED" ]; then echo "patched MC jar not found: $PATCHED" >&2; exit 2; fi

# 1) Build loader core (parent-loaded, classpath JAR) + the Knot-loaded runtime module.
"$PROJECT/gradlew" -q -p "$PROJECT" jar runtimeJar
CORE="$(ls "$PROJECT"/build/libs/neoforbric-loader-*.jar | head -1)"

# 2) Stage the Knot-loaded NeoForge infrastructure into mods/ (overwriting prior copies). Test/content mods
#    are placed into mods/ by the caller; these two are always required for a NeoForge run.
cp "$(ls "$PROJECT"/build/libs/neoforbricruntime-*.jar | head -1)" "$RUNDIR/mods/neoforbricruntime.jar"
if [ -f "$NFRT_JAR" ]; then cp "$NFRT_JAR" "$RUNDIR/mods/neoforge-runtime.jar";
else echo "[launch] WARN: NeoForge runtime jar missing ($NFRT_JAR) — run run/assemble-neoforge-runtime.sh" >&2; fi
# The NeoForge bridge (@Mod("neoforbric")): opens the Fabric-content window inside NeoForge's genuine
# registration span, so Fabric mods register at the right time instead of being discovered and never run.
BRIDGE_JAR="${BRIDGE_JAR:-$HERE/neoforge-runtime/neoforbric-bridge-neoforge.jar}"
if [ -f "$BRIDGE_JAR" ]; then cp "$BRIDGE_JAR" "$RUNDIR/mods/neoforbric-bridge-neoforge.jar"; fi

# 3) Loader dependency jars (runtime classpath minus the build class/resource dirs — we use the core jar).
DEPS="$("$PROJECT/gradlew" -q -p "$PROJECT" printRuntimeClasspath | tail -1 | tr ':' '\n' | grep -vE "build/(classes|resources)" | paste -sd: -)"

# 4) Vanilla 26.2 libraries + asset index from the version manifest (the client needs both).
VANILLA_CP="$(python3 - "$MC" <<'PY'
import json, os, sys
mc = sys.argv[1]
d = json.load(open(os.path.join(mc, 'versions', '26.2', '26.2.json')))
out = []
for lib in d.get('libraries', []):
    p = lib.get('name', '').split(':')
    if len(p) < 3: continue
    grp, art, ver = p[0].replace('.', '/'), p[1], p[2]
    cls = ('-' + p[3]) if len(p) > 3 else ''
    jar = os.path.join(mc, 'libraries', grp, art, ver, f"{art}-{ver}{cls}.jar")
    if os.path.exists(jar):
        out.append(jar)
print(os.pathsep.join(out))
PY
)"
ASSET_INDEX="$(python3 -c "import json;print(json.load(open('$MC/versions/26.2/26.2.json'))['assetIndex']['id'])")"

# Patched jar LAST so its (Mojmap) MC classes win over any stale game classes elsewhere.
CP="$CORE:$DEPS:$VANILLA_CP:$PATCHED"

echo "[launch] rundir=$RUNDIR  assetIndex=$ASSET_INDEX"
echo "[launch] patched MC = $PATCHED"
echo "[launch] mods: $(ls "$RUNDIR/mods" | paste -sd' ' -)"
cd "$RUNDIR"
exec java -XstartOnFirstThread -Djava.awt.headless=true -Djava.library.path="$NATIVES" \
  -Dneoforbric.runtimeNamespace=named -Dneoforbric.forgeFamily=neoforge ${NEOFORBRIC_JVM:-} \
  -cp "$CP" net.neoforbric.loader.impl.launch.NeoForbricClient \
  --version 26.2-neoforbric --gameDir "$RUNDIR" --assetsDir "$ASSETS" --assetIndex "$ASSET_INDEX" \
  --accessToken 0 --username NeoForbricDev --uuid 00000000000000000000000000000000 \
  --userType legacy --versionType release "$@"

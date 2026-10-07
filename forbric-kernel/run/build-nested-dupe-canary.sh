#!/usr/bin/env bash
# Builds the pair of canary mods gate-m19-nesteddupe.sh needs: a Fabric mod and a NeoForge mod that each
# JiJ-nest their own build of ONE library, both declaring the mod id `forbricnestlib`.
#
# That is the shape no gate pack had. Every pack's nested duplicates are same-family (one fabric-api-base nested
# by five Fabric mods), which both loaders already deduplicate on their own. What nobody was checking is the SAME
# id claimed by two ECOSYSTEMS — each loader only ever deduplicates within its own family — and that is what made
# Xaero's xaerolib construct twice on a real 26.2 pack.
#
# The library carries a one-shot registry that throws on a second registration, the way xaerolib's config channel
# does, so the gate proves the BEHAVIOUR and not only the arbitration log line.
#
# Output: run/canary/forbricnestfab.jar (contains META-INF/jars/forbricnestlib-fabric.jar)
#         run/canary/forbricnestneo.jar (contains META-INF/jarjar/forbricnestlib-neo.jar)
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SRC="$KERNEL/canary/nesteddupe"
OUT="$KERNEL/run/canary"
canary_scratch nesteddupe
NEO_RT="$OLD/run/neoforge-runtime/neoforge-runtime.jar"

step "prerequisites"
[ -f "$NEO_RT" ] || { echo "[kernel] FAIL neoforge runtime absent: $NEO_RT"; exit 1; }
mkdir -p "$BUILD"
kernel_jar
KERNEL_JAR="$BUILD/libs/forbric-kernel-0.1.0-SNAPSHOT.jar"
[ -f "$KERNEL_JAR" ] || { echo "[kernel] FAIL kernel jar not built"; exit 1; }
echo "[kernel] neoforge runtime + kernel jar present"

mkdir -p "$WORK"/{libfab,libneo,parfab,parneo} "$OUT"

# The registry class is compiled into BOTH library jars, byte-identical, exactly as a real multiloader library
# ships its shared half twice. On a classpath carrying both, one copy is defined (first URL wins) and both
# bootstraps reach it — which is how the real duplicate registration happened.
step "compile the shared library, once per platform"
javac -nowarn -proc:none --release 21 -cp "$KERNEL_JAR" -d "$WORK/libfab" \
      "$SRC/lib/src/forbric/nestlib/NestLibRegistry.java" \
      "$SRC/lib/src/forbric/nestlib/NestLibFabric.java" 2>&1 | grep -v '^Note:' || true
javac -nowarn -proc:none --release 21 -cp "$NEO_RT" -d "$WORK/libneo" \
      "$SRC/lib/src/forbric/nestlib/NestLibRegistry.java" \
      "$SRC/lib/src/forbric/nestlib/NestLibNeo.java" 2>&1 | grep -v '^Note:' || true
[ -f "$WORK/libfab/forbric/nestlib/NestLibFabric.class" ] || { echo "[kernel] FAIL fabric lib did not compile"; exit 1; }
[ -f "$WORK/libneo/forbric/nestlib/NestLibNeo.class" ] || { echo "[kernel] FAIL neo lib did not compile"; exit 1; }

cp "$SRC/lib/fabric.mod.json" "$WORK/libfab/"
mkdir -p "$WORK/libneo/META-INF" && cp "$SRC/lib/neoforge.mods.toml" "$WORK/libneo/META-INF/"
(cd "$WORK/libfab" && jar --create --file "$WORK/forbricnestlib-fabric.jar" .) || exit 1
(cd "$WORK/libneo" && jar --create --file "$WORK/forbricnestlib-neo.jar" .) || exit 1
echo "[kernel] built both builds of forbricnestlib"

step "compile the two parents and nest their own build"
javac -nowarn -proc:none --release 21 -cp "$KERNEL_JAR" -d "$WORK/parfab" \
      "$SRC/parentfabric/src/forbric/nestparent/NestParentFabric.java" 2>&1 | grep -v '^Note:' || true
javac -nowarn -proc:none --release 21 -cp "$NEO_RT" -d "$WORK/parneo" \
      "$SRC/parentneo/src/forbric/nestparent/NestParentNeo.java" 2>&1 | grep -v '^Note:' || true
[ -f "$WORK/parfab/forbric/nestparent/NestParentFabric.class" ] || { echo "[kernel] FAIL fabric parent did not compile"; exit 1; }
[ -f "$WORK/parneo/forbric/nestparent/NestParentNeo.class" ] || { echo "[kernel] FAIL neo parent did not compile"; exit 1; }

cp "$SRC/parentfabric/fabric.mod.json" "$WORK/parfab/"
mkdir -p "$WORK/parfab/META-INF/jars"
cp "$WORK/forbricnestlib-fabric.jar" "$WORK/parfab/META-INF/jars/"
# No META-INF/jarjar/metadata.json here, on purpose: real Fabric JiJ parents (xaerominimap-fabric) ship none, and
# the NeoForge parent names its own platform artifact (forbricnestlib-neo, as a multiloader library's neo build does).
# The gate must hold on that real shape: any in-range build of the same mod id meets the NeoForge coordinate.

mkdir -p "$WORK/parneo/META-INF/jarjar"
cp "$SRC/parentneo/neoforge.mods.toml" "$WORK/parneo/META-INF/"
# Both the metadata.json NeoForge's JarJarSelector reads and the jar itself: the kernel's extractor reads the former
# and takes the latter, so a pair that disagrees would extract nothing and the gate would pass for a wrong reason.
cp "$SRC/parentneo/metadata.json" "$WORK/parneo/META-INF/jarjar/"
cp "$WORK/forbricnestlib-neo.jar" "$WORK/parneo/META-INF/jarjar/"

(cd "$WORK/parfab" && jar --create --file "$WORK/forbricnestfab.jar" .) || exit 1
(cd "$WORK/parneo" && jar --create --file "$WORK/forbricnestneo.jar" .) || exit 1
publish_canary "$WORK/forbricnestfab.jar" "$OUT/forbricnestfab.jar" || exit 1
publish_canary "$WORK/forbricnestneo.jar" "$OUT/forbricnestneo.jar" || exit 1

step "result"
echo "[kernel] ✅ built $OUT/forbricnestfab.jar + $OUT/forbricnestneo.jar"
unzip -l "$OUT/forbricnestfab.jar" | grep -E 'jars/|fabric.mod.json'
unzip -l "$OUT/forbricnestneo.jar" | grep -E 'jarjar/|neoforge.mods.toml'

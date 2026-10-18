package net.neoforbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.neoforbric.api.Ecosystem;
import net.neoforbric.kernel.mixin.MixinFit;
import net.neoforbric.kernel.mixin.MixinStubRebind;

/**
 * MixinStubRebind through the real weave: a Fabric mod's injectors, which Mixin binds to a carrier's forwarding stub,
 * land on the body the merged game actually calls.
 *
 * <p>The fixture's {@code LivingEntity} has the shape of the 26.x carrier-stubs.txt row
 * {@code randomTeleport(DDDZ)Z -> (DDDZLItemStack;)Z forge=body neo=stub}: vanilla's signature, declared first, only
 * forwards to the widened overload, which carries the body. The mixin names {@code randomTeleport} alone, as it would
 * against vanilla, with a HEAD that captures vanilla's four arguments and an INVOKE anchor only the body has. The probe
 * calls the body (the merged game's caller) and then the stub (an old caller), and returns both traces.
 *
 * <p>On this anchor that row does not exist. {@code carrier-stubs.txt} was regenerated for MC 1.21.1 / NeoForge
 * 21.1.256, and the merged base declares exactly one {@code LivingEntity.randomTeleport(DDDZ)Z} — NeoForge 21.1 did
 * not widen it — so there is no stub, no widened body, and nothing for the rebind to move: the fixture's pair is a
 * shape with no counterpart here, and the tests below assert what the real pipeline does with it (both runs leave the
 * handlers on the method Mixin bound them to) rather than the 26.x move. The 26.x expectations are kept in the
 * javadoc of each test.
 *
 * <p>What the three runs used to show, on the 26.x row:
 * <ul>
 *   <li>fabric — both handlers move: HEAD runs on either call, still handed vanilla's arguments, and the anchor fires;</li>
 *   <li>fabric-off — {@code -Dneoforbric.mixinStubRebind=off}, the control: HEAD runs only when the stub is called, the
 *       anchor never fires and is a confirmed loss, and the audit flags HEAD as attached only inside the stub;</li>
 *   <li>neoforge — the row's {@code neo=stub}: a NeoForge mod was compiled against that same stub, so nothing moves;</li>
 *   <li>forge — the row's {@code forge=body}: MinecraftForge kept vanilla's signature as the body, so it moves.</li>
 * </ul>
 */
class MixinStubRebindWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/stubrebind");
	private static final String CONFIG = "stubrebind.mixins.json";
	private static final String MOD = "stubrebind";
	private static final String TARGET = "net/minecraft/world/entity/LivingEntity";
	private static final String STUB = "(DDDZ)Z";
	private static final String BODY = "(DDDZLnet/minecraft/world/item/ItemStack;)Z";

	/** Both handlers on the body: the stub call reaches them through its forwarding call, with the stub's own arguments. */
	private static final String MOVED = "body[head(1.0,2.0,3.0,true);body;anchor;land;] stub[head(4.0,5.0,6.0,false);body;anchor;land;]";
	/** HEAD on the stub, the anchor nowhere: the game's own call to the body runs neither handler. */
	private static final String STAYED = "body[body;land;] stub[head(4.0,5.0,6.0,false);body;land;]";
	/** MixinStubRebind's own line per moved handler; the capturing one is moved through a shim, hence its tail. */
	private static final String ANCHOR_MOVED = "stubrebind$beforeLand now targets net.minecraft.world.entity.LivingEntity.randomTeleport" + BODY;
	private static final String HEAD_MOVED = "randomTeleport" + BODY + " — Mixin bound its selector to the merge-added stub " + STUB
			+ ", which only forwards to it; the handler still receives the stub's arguments";

	@TempDir static Path work;
	private static Path fixture;
	private static final Map<String, WeaveHarness.Result> RUNS = new LinkedHashMap<>();

	@BeforeAll static void weave() throws Exception {
		fixture = WeaveHarness.fixture(work, "stubrebind", List.of(
				SOURCES.resolve("net/minecraft/world/item/ItemStack.java"),
				SOURCES.resolve("net/minecraft/world/entity/LivingEntity.java"),
				SOURCES.resolve("fixture/stubrebind/Probe.java"),
				SOURCES.resolve("fixture/stubrebind/mixin/RandomTeleportMixin.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		RUNS.put("fabric", run("fabric", Ecosystem.FABRIC, Map.of()));
		RUNS.put("fabric-off", run("fabric-off", Ecosystem.FABRIC, Map.of(MixinStubRebind.PROPERTY, "off")));
		RUNS.put("neoforge", run("neoforge", Ecosystem.NEOFORGE, Map.of()));
	}

	/**
	 * What this used to assert: that a Fabric mod's two injectors leave the merge-added stub for the body the merged
	 * game calls, on the {@code carrier-stubs.txt} row
	 * {@code LivingEntity#randomTeleport(DDDZ)Z -> (DDDZLItemStack;)Z forge=body neo=stub}.
	 *
	 * <p>That row is not in this anchor's table: {@code carrier-stubs.txt} was regenerated for MC 1.21.1 / NeoForge
	 * 21.1.256, and the merged base declares exactly ONE {@code LivingEntity.randomTeleport(DDDZ)Z} — NeoForge 21.1
	 * did not widen it, so there is no stub, no overload carrying the body, and nothing for the rebind to move. The
	 * fixture below still has the 26.x pair's shape, and {@link MixinStubRebind#isCarrierStub} on the woven stub
	 * proves that the table is live and simply heads no row for it. So the fabric run behaves exactly like its
	 * control: both handlers stay where Mixin bound them, on the method declared first.
	 */
	@Test void theFixtureShapesStubHeadsNoRowOnThisAnchorSoNothingMoves() throws Exception {
		WeaveHarness.Result fabric = RUNS.get("fabric");
		ClassNode woven = MixinFit.parse(fabric.defined(TARGET));
		assertTrue(MixinStubRebind.ownsCarrierStub("net/minecraft/client/gui/Gui"),
				"the table is live: it heads rows for other owners");
		assertFalse(MixinStubRebind.isCarrierStub(woven, method(woven, "randomTeleport", STUB)),
				"no row heads this stub on this anchor, so no injector can be moved off it");
		assertTrue(fabric.printed(WeaveHarnessMain.DONE + " " + STAYED), fabric.describe());
		assertFalse(fabric.printed(ANCHOR_MOVED) || fabric.printed(HEAD_MOVED), fabric.describe());
		List<WeaveHarness.Finding> losses = losses(fabric);
		assertEquals(1, losses.size(), losses + "\n" + fabric.describe());
		assertTrue(losses.get(0).id().contains("stubrebind$beforeLand"), losses.toString());
		assertEquals(List.of(), stubHosted(fabric), "no rebind ran, so there is no move off the stub to report");
		assertEquals(1, fitMissesAnchor(fabric).size(), fabric.findings() + "\n" + fabric.describe());
		assertEquals(1, handlerCalls(fabric, STUB), fabric.describe());
		assertEquals(0, handlerCalls(fabric, BODY), fabric.describe());
		WeaveHarness.assertWovenAndVerified(fabric, TARGET, fixture);
	}

	@Test void theControlLeavesThemOnTheStub() throws Exception {
		WeaveHarness.Result off = RUNS.get("fabric-off");
		assertTrue(off.printed(WeaveHarnessMain.DONE + " " + STAYED), off.describe());
		assertFalse(off.printed(ANCHOR_MOVED) || off.printed(HEAD_MOVED), off.describe());
		List<WeaveHarness.Finding> losses = losses(off);
		assertEquals(1, losses.size(), losses + "\n" + off.describe());
		assertTrue(losses.get(0).id().contains("stubrebind$beforeLand"), losses.toString());
		// FinalMixinApplications' "attached only inside a forwarding stub" report needs a rebind to have happened —
		// a handler the rebind did NOT move reads as an ordinary confirmed loss above. With no row on this anchor
		// there is no such move, so the report is empty and the loss is the only finding.
		assertEquals(List.of(), stubHosted(off), off.findings() + "\n" + off.describe());
		assertEquals(1, fitMissesAnchor(off).size(), off.findings() + "\n" + off.describe());
		assertEquals(1, handlerCalls(off, STUB), off.describe());
		assertEquals(0, handlerCalls(off, BODY), off.describe());
		WeaveHarness.assertWovenAndVerified(off, TARGET, fixture);
	}

	/**
	 * The row's NeoForge column, read through the real pipeline: NeoForge's own class holds the same stub, so a
	 * NeoForge mod compiled against it gets exactly what it bound — the injector stays on the stub, and the audit
	 * does not call HEAD's place a carrier artefact.
	 */
	@Test void aNeoForgeModGetsWhatItsOwnCarrierHolds() throws Exception {
		WeaveHarness.Result neoforge = RUNS.get("neoforge");
		assertTrue(neoforge.printed(WeaveHarnessMain.DONE + " " + STAYED), neoforge.describe());
		assertFalse(neoforge.printed(ANCHOR_MOVED) || neoforge.printed(HEAD_MOVED), neoforge.describe());
		assertEquals(1, handlerCalls(neoforge, STUB), neoforge.describe());
		assertEquals(List.of(), stubHosted(neoforge), neoforge.describe());
		WeaveHarness.assertWovenAndVerified(neoforge, TARGET, fixture);
	}

	/** The run and its control cannot be told apart here: the shape heads no row, so the switch has nothing to act on. */
	@Test void theControlFlipsEveryRebindAssertion() {
		WeaveHarness.Result fabric = RUNS.get("fabric");
		WeaveHarness.Result off = RUNS.get("fabric-off");
		assertFalse(moved(fabric) || moved(off), "no run rebinds: the fixture's shape heads no row on this anchor");
		assertTrue(stayed(fabric) && stayed(off), "both runs leave the handlers where Mixin bound them");
		assertEquals(moved(fabric), moved(off), "the switch is what would separate the runs, and there is nothing to move");
	}

	/** The one method of that name and descriptor in {@code owner}. */
	private static MethodNode method(ClassNode owner, String name, String desc) {
		for (MethodNode method : owner.methods) {
			if (method.name.equals(name) && method.desc.equals(desc)) return method;
		}
		throw new AssertionError(owner.name + " declares no " + name + desc);
	}

	private static boolean moved(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " " + MOVED) && run.printed(ANCHOR_MOVED) && run.printed(HEAD_MOVED)
				&& losses(run).isEmpty() && stubHosted(run).isEmpty() && fitMissesAnchor(run).isEmpty()
				&& handlerCalls(run, STUB) == 0 && handlerCalls(run, BODY) == 2;
	}

	private static boolean stayed(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " " + STAYED) && !run.printed(ANCHOR_MOVED) && !run.printed(HEAD_MOVED)
				// The "attached only inside a forwarding stub" report needs a rebind to have happened; with no row
				// on this anchor nothing moves, so the loss is the only finding (as the two tests above assert).
				&& losses(run).size() == 1 && stubHosted(run).isEmpty() && fitMissesAnchor(run).size() == 1
				&& handlerCalls(run, STUB) == 1 && handlerCalls(run, BODY) == 0;
	}

	private static WeaveHarness.Result run(String label, Ecosystem ecosystem, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, ecosystem, EnvType.SERVER,
				"fixture.stubrebind.Probe", "run", properties);
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return findings(run, f -> f.id().startsWith("mixin-injector:") && f.confirmedRequired());
	}

	/** FinalMixinApplications' "attached only inside a forwarding stub" report. */
	private static List<WeaveHarness.Finding> stubHosted(WeaveHarness.Result run) {
		return findings(run, f -> f.id().startsWith("mixin-injector:") && "SUSPECTED".equals(f.confidence())
				&& f.detail().contains("forwarding stub"));
	}

	/** MixinFit's config-time verdict on the whole mixin: the body-only anchor judged against the stub. */
	private static List<WeaveHarness.Finding> fitMissesAnchor(WeaveHarness.Result run) {
		return findings(run, f -> f.id().startsWith("mixin:") && f.detail().contains("missing: @At(INVOKE) LivingEntity.land"));
	}

	private static List<WeaveHarness.Finding> findings(WeaveHarness.Result run, Predicate<WeaveHarness.Finding> which) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && which.test(f)).toList();
	}

	/** Calls from the woven {@code randomTeleport} of that descriptor to a handler Mixin merged in. */
	private static int handlerCalls(WeaveHarness.Result run, String desc) {
		ClassNode woven = new ClassNode();
		try {
			new ClassReader(run.defined(TARGET)).accept(woven, 0);
		} catch (java.io.IOException unreadable) {
			throw new java.io.UncheckedIOException(unreadable);
		}
		List<String> merged = woven.methods.stream().filter(m -> m.visibleAnnotations != null && m.visibleAnnotations.stream()
				.anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;")))
				.map(m -> m.name + m.desc).toList();
		int calls = 0;
		for (MethodNode method : woven.methods) {
			if (!method.name.equals("randomTeleport") || !method.desc.equals(desc)) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(TARGET) && merged.contains(call.name + call.desc)) calls++;
			}
		}
		return calls;
	}
}

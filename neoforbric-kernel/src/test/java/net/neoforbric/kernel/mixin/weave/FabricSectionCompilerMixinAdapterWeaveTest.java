package net.neoforbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.neoforbric.api.Ecosystem;
import net.neoforbric.kernel.mixin.FabricSectionCompilerMixinAdapter;

/**
 * {@code FabricSectionCompilerMixinAdapter} through the real weave, on a CLIENT run: fabric-renderer-api's chunk setup
 * and tessellation redirect move together onto the compile body that receives NeoForge's extra geometry.
 *
 * <p>The fixture's {@code SectionCompiler} (compiled with its local variable table, as the game's is) keeps vanilla's
 * four-argument {@code compile} as a stub forwarding to NeoForge's five-argument one, which walks the blocks,
 * tessellates them into the {@code startedLayers} map and adds the extra geometry. The guest pair shares a renderer
 * and an emitter between a setup {@code @Inject} (which also takes {@code startedLayers} by name) and a tessellation
 * {@code @Redirect}; both select {@code compile} by name, so Mixin binds them to the stub. With the adapter both move
 * to the live overload — the setup through a generated wrapper that takes the extra list and hands the original its
 * own arguments — and every block is emitted by Fabric's renderer, with NeoForge's geometry kept. With
 * {@code -Dneoforbric.fabricChunkRendering=off} the mixin stays as its module authored it: the rescue's
 * refmap-resolved judging keeps it (a PARTIAL, not the UNFIT the raw strings earned), but nothing renames its
 * intermediary-named {@code @Shadow} and nothing moves its handlers, so Mixin fails the application on the
 * shadow and the kernel reports the one failed apply — and the chunk is tessellated by vanilla.
 */
class FabricSectionCompilerMixinAdapterWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/fabricsectioncompiler");
	private static final String CONFIG = "fabricsectioncompiler.mixins.json";
	private static final String REFMAP = "fabricsectioncompiler-refmap.json";
	private static final String MOD = "fabricsectioncompiler";
	private static final String COMPILER = "net/minecraft/client/renderer/chunk/SectionCompiler";
	private static final String FABRIC_QUADS = "quads=[fabric:stone, fabric:ctm_glass, neoforge:fluid]";
	private static final String VANILLA_QUADS = "quads=[vanilla:stone, vanilla:ctm_glass, neoforge:fluid]";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result adapted;
	private static WeaveHarness.Result off;

	@BeforeAll static void weaveBoth() throws Exception {
		fixture = WeaveHarness.fixture(work, MOD, sources(),
				Map.of(CONFIG, SOURCES.resolve(CONFIG), REFMAP, SOURCES.resolve(REFMAP)), List.of("-g"));
		adapted = run("adapted", Map.of(FabricSectionCompilerMixinAdapter.PROPERTY, "on"));
		off = run("off", Map.of(FabricSectionCompilerMixinAdapter.PROPERTY, "off"));
	}

	@Test void fabricsRendererEmitsEveryBlockOfTheLiveCompile() throws Exception {
		assertTrue(adaptedHolds(adapted), adapted.describe() + "\nfindings: " + adapted.findings());
		assertTrue(adapted.printed("[NeoForbric/Renderer] Fabric's chunk-meshing take-over now runs in the live section compile body"),
				adapted.describe());
		assertTrue(WeaveHarness.hasMergedMethod(adapted.defined(COMPILER)), adapted.describe());
		WeaveHarness.assertWovenAndVerified(adapted, COMPILER, fixture);
	}

	@Test void withTheSwitchOffTheModuleCannotApplyItsTakeoverIsReportedAndVanillaTessellates() throws Exception {
		assertTrue(offHolds(off), off.describe() + "\nfindings: " + off.findings());
		WeaveHarness.assertWovenAndVerified(off, COMPILER, fixture);
	}

	/** The run and its control must be told apart by the very predicates the test uses on them. */
	@Test void theControlFlipsEveryAssertion() {
		assertTrue(adaptedHolds(adapted) && !adaptedHolds(off), "adapted predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(adapted), "control predicate does not separate the runs");
	}

	private static boolean adaptedHolds(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " " + FABRIC_QUADS) && losses(run).isEmpty();
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		// With the adapter off nothing renames the module's intermediary-named @Shadow, so the one thing
		// the off-run reports is the mixin's own failed application — a mixin-level finding, not the
		// per-injector losses the 26.x-era module (whose shadow was Mojmap-authored) produced.
		List<WeaveHarness.Finding> failed = run.findings().stream()
				.filter(f -> f.modId().equals(MOD) && f.required() && f.id().startsWith("mixin:")
						&& f.detail().contains("failed to apply")).toList();
		return run.printed(WeaveHarnessMain.DONE + " " + VANILLA_QUADS) && failed.size() == 1;
	}

	/** Every required injector of the mod the runtime audit reports, at any confidence. */
	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.id().startsWith("mixin-injector:") && f.modId().equals(MOD)
				&& f.required()).toList();
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.CLIENT,
				"fixture.fabricsectioncompiler.Probe", "run", properties);
	}

	private static List<Path> sources() throws Exception {
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
	}
}

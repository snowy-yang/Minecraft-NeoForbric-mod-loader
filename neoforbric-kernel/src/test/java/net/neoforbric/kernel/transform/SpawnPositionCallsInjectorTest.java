package net.neoforbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipFile;
import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import net.neoforbric.kernel.mixin.MixinFit;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** The merged BaseSpawner, rewritten, on the real staged jars; architectury's real mixin is the witness. */
class SpawnPositionCallsInjectorTest {
	private static final Path STAGED = TestFixtures.stagedRoot();
	private static final Path MERGED = STAGED.resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
	private static final Path NEO = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final Path ARCHITECTURY = Path.of("run/client-popular/mods/architectury-fabric-21.1.10.jar");

	@Test void theVanillaCallsAreBackInServerTickAfterNeoForgesEvent() throws Exception {
		byte[] original = read(MERGED, "net/minecraft/world/level/BaseSpawner.class");
		byte[] rewritten = injector().transform(SpawnPositionCallsInjector.TARGET, original, null);
		assertNotSame(original, rewritten, "the merged BaseSpawner carries NeoForge's hook, so it must be rewritten");
		MethodNode tick = serverTick(rewritten);
		assertEquals(0, calls(tick, SpawnPositionCallsInjector.NEO, SpawnPositionCallsInjector.NEO_NAME));
		assertEquals(1, calls(tick, SpawnPositionCallsInjector.RUNTIME, "decide"));
		assertEquals(1, calls(tick, SpawnPositionCallsInjector.MOB, SpawnPositionCallsInjector.CHECK_RULES));
		assertEquals(1, calls(tick, SpawnPositionCallsInjector.MOB, SpawnPositionCallsInjector.CHECK_OBSTRUCTION));
		new Analyzer<>(new BasicVerifier()).analyze("net/minecraft/world/level/BaseSpawner", tick);
		assertSame(rewritten, injector().transform(SpawnPositionCallsInjector.TARGET, rewritten, null), "a second pass changes nothing");
	}

	@Test void architecturysRedirectsFitTheRewrittenSpawnerAndMissedTheMergedOne() throws Exception {
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(ARCHITECTURY), "popular-pack architectury fixture absent");
		byte[] mixin = read(ARCHITECTURY, "dev/architectury/mixin/fabric/MixinBaseSpawner.class");
		byte[] original = read(MERGED, "net/minecraft/world/level/BaseSpawner.class");
		byte[] rewritten = injector().transform(SpawnPositionCallsInjector.TARGET, original, null);
		Function<String, byte[]> before = resources(original), after = resources(rewritten);
		assertNotEquals(MixinFit.Verdict.FIT, MixinFit.evaluate(mixin, before).verdict(), "premise: the merged base misses both");
		assertEquals(MixinFit.Verdict.FIT, MixinFit.evaluate(mixin, after).verdict(), MixinFit.evaluate(mixin, after).toString());
	}

	/**
	 * Every hook site becomes vanilla's pair again, in the caller.
	 *
	 * <p>This compared the rewritten methods against the SAME methods in {@link TestFixtures#vanillaJar()} — vanilla
	 * made the two calls, so the counts had to come back equal. That cannot be done on 1.21.1: the file there is the
	 * launcher's own client jar and its classes are still OBFUSCATED (it has every class, under names like
	 * {@code guu}, and no {@code net/minecraft/world/level/NaturalSpawner} entry), so no vanilla method can be named
	 * in it. The reference is NeoForge's own hook instead, read out of the staged runtime jar: its body is the pair
	 * vanilla made, and the injector's whole claim is that it puts that pair back where the hook was. The counts
	 * therefore still have to match site for site — one {@code checkSpawnRules} and one {@code checkSpawnObstruction}
	 * per hook removed — and a missed or doubled site still fails.
	 */
	@Test void naturalAndSummonedSpawnsGetVanillasCallsBackInEveryMethodVanillaMadeThem() throws Exception {
		byte[] hooks = read(NEO, "net/neoforged/neoforge/event/EventHooks.class");
		MethodNode position = node(hooks).methods.stream()
				.filter(m -> m.name.equals(SpawnPositionCallsInjector.NEO_POSITION)).findFirst().orElseThrow();
		int rulesPerSite = calls(position, SpawnPositionCallsInjector.MOB, SpawnPositionCallsInjector.CHECK_RULES);
		int obstructionPerSite = calls(position, SpawnPositionCallsInjector.MOB, SpawnPositionCallsInjector.CHECK_OBSTRUCTION);
		assertEquals(1, rulesPerSite, "NeoForge's checkSpawnPosition runs the mob's own rules once");
		assertEquals(1, obstructionPerSite, "…and its obstruction check once");
		for (String target : List.of(SpawnPositionCallsInjector.NATURAL, SpawnPositionCallsInjector.SUMMON)) {
			String entry = target.replace('.', '/') + ".class";
			byte[] original = read(MERGED, entry);
			byte[] rewritten = injector().transform(target, original, null);
			assertNotSame(original, rewritten, target + " carries NeoForge's hook, so it must be rewritten");
			ClassNode after = node(rewritten), before = node(original);
			int sites = 0;
			for (MethodNode method : before.methods) sites += calls(method, SpawnPositionCallsInjector.NEO, SpawnPositionCallsInjector.NEO_POSITION);
			assertTrue(sites > 0, "premise: " + target + " calls NeoForge's position hook somewhere");
			for (MethodNode method : after.methods) {
				assertEquals(0, calls(method, SpawnPositionCallsInjector.NEO, SpawnPositionCallsInjector.NEO_POSITION), method.name);
				new Analyzer<>(new BasicVerifier()).analyze(after.name, method);
			}
			for (MethodNode beforeMethod : before.methods) {
				int wasHooks = calls(beforeMethod, SpawnPositionCallsInjector.NEO, SpawnPositionCallsInjector.NEO_POSITION);
				if (wasHooks == 0) continue;
				MethodNode afterMethod = after.methods.stream()
						.filter(m -> m.name.equals(beforeMethod.name) && m.desc.equals(beforeMethod.desc)).findFirst().orElseThrow();
				assertEquals(wasHooks * rulesPerSite, calls(afterMethod, SpawnPositionCallsInjector.MOB, SpawnPositionCallsInjector.CHECK_RULES),
						target + "." + beforeMethod.name);
				assertEquals(wasHooks * obstructionPerSite, calls(afterMethod, SpawnPositionCallsInjector.MOB, SpawnPositionCallsInjector.CHECK_OBSTRUCTION),
						target + "." + beforeMethod.name);
			}
			assertSame(rewritten, injector().transform(target, rewritten, null), "a second pass changes nothing");
		}
	}

	@Test void architecturysChunkGenerationRedirectFitsTheRewrittenNaturalSpawner() throws Exception {
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(ARCHITECTURY), "popular-pack architectury fixture absent");
		byte[] mixin = read(ARCHITECTURY, "dev/architectury/mixin/fabric/MixinNaturalSpawner.class");
		String entry = "net/minecraft/world/level/NaturalSpawner.class";
		byte[] original = read(MERGED, entry);
		byte[] rewritten = injector().transform(SpawnPositionCallsInjector.NATURAL, original, null);
		Function<String, byte[]> before = path -> readOrNull(MERGED, path);
		Function<String, byte[]> after = path -> path.equals(entry) ? rewritten : readOrNull(MERGED, path);
		assertNotEquals(MixinFit.Verdict.FIT, MixinFit.evaluate(mixin, before).verdict(), "premise: the merged base misses it");
		assertEquals(MixinFit.Verdict.FIT, MixinFit.evaluate(mixin, after).verdict(), MixinFit.evaluate(mixin, after).toString());
	}

	@Test void theSwitchLeavesNeoForgesHookInPlace() throws Exception {
		System.setProperty(SpawnPositionCallsInjector.PROPERTY, "off");
		try {
			byte[] original = read(MERGED, "net/minecraft/world/level/BaseSpawner.class");
			assertSame(original, injector().transform(SpawnPositionCallsInjector.TARGET, original, null));
		} finally {
			System.clearProperty(SpawnPositionCallsInjector.PROPERTY);
		}
	}

	private static SpawnPositionCallsInjector injector() {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED) && Files.isRegularFile(NEO), "staged merged base absent");
		return new SpawnPositionCallsInjector(path -> {
			for (Path jar : List.of(MERGED, NEO)) {
				byte[] bytes = readOrNull(jar, path);
				if (bytes != null) return bytes;
			}
			return null;
		});
	}

	private static Function<String, byte[]> resources(byte[] spawner) {
		return path -> path.equals("net/minecraft/world/level/BaseSpawner.class") ? spawner : readOrNull(MERGED, path);
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode serverTick(byte[] bytes) {
		ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
		return node.methods.stream().filter(m -> m.name.equals("serverTick") && m.desc.equals(SpawnPositionCallsInjector.HOST_DESC)).findFirst().orElseThrow();
	}

	private static int calls(MethodNode method, String owner, String name) {
		int n = 0;
		for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode c && c.owner.equals(owner) && c.name.equals(name)) n++;
		return n;
	}

	private static byte[] read(Path jar, String entry) throws Exception {
		TestFixtures.require(NativeCoremodParityTest.fixtureOf(jar), Files.isRegularFile(jar), jar + " absent");
		byte[] bytes = readOrNull(jar, entry);
		assertNotNull(bytes, entry + " in " + jar);
		return bytes;
	}

	private static byte[] readOrNull(Path jar, String entry) {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			var found = zip.getEntry(entry);
			if (found == null) return null;
			try (var in = zip.getInputStream(found)) { return in.readAllBytes(); }
		} catch (Exception unreadable) {
			return null;
		}
	}
}

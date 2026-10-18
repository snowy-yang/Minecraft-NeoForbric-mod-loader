package net.neoforbric.kernel.transform;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** NeoForge's coremod rewrites, done by the kernel, on the real merged classes. */
@ResourceLock("system-properties")
class NativeCoremodParityTest {
	private static final Path STAGED = TestFixtures.stagedRoot();
	private static final Path MERGED = STAGED.resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
	/** tools/dev.py prepare downloads NeoForge's universal jar here; its coremods jar is nested inside. */
	private static final Path NEO_DOWNLOADS = TestFixtures.minecraftDir().resolve(".neoforbric-build/dl");
	/** A native NeoForge instance's extracted nested jars, on a machine that ran one. */
	private static final Path NEO_COREMODS = Path.of("build/journeymap-native/instance/.cache/jij");
	private static final String POT = "net/minecraft/world/level/block/FlowerPotBlock";
	private static final String BIOME = "net/minecraft/world/level/biome/Biome";
	private static final String STRUCTURE = "net/minecraft/world/level/levelgen/structure/Structure";

	@AfterEach void reset() {
		for (String key : List.of(NativeCoremodParity.PROPERTY, NativeCoremodParity.FLOWER_POT, NativeCoremodParity.BIOME,
				NativeCoremodParity.STRUCTURE, NativeCoremodParity.FINALIZE)) System.clearProperty(key);
	}

	@Test void everyFlowerPotReadGoesThroughTheGetterExceptTheGetter() throws Exception {
		ClassNode after = rewritten(POT);
		assertEquals(0, reads(after, POT, "potted", m -> !m.desc.equals("()Lnet/minecraft/world/level/block/Block;") && !m.name.equals("<init>")));
		// 4 on 1.21.1, not the 5 the 26.x base had: FlowerPotBlock lost randomTick there, and its codec reaches the
		// plant through one lambda rather than two. useWithoutItem, getCloneItemStack, isEmpty and the codec.
		assertEquals(4, calls(after, POT, "getPotted"), "useWithoutItem, getCloneItemStack, isEmpty and the codec");
		verify(after);
		byte[] once = NativeCoremodParity.apply(POT, read(MERGED, POT));
		assertSame(once, NativeCoremodParity.apply(POT, once), "a second pass changes nothing");
	}

	@Test void biomeAndStructureReadsGoThroughNeoForgesModifiedView() throws Exception {
		ClassNode biome = rewritten(BIOME);
		assertEquals(0, reads(biome, BIOME, "climateSettings", m -> !m.desc.equals("()Lnet/minecraft/world/level/biome/Biome$ClimateSettings;") && !m.name.equals("<init>")));
		assertEquals(0, reads(biome, BIOME, "specialEffects", m -> !m.desc.equals("()Lnet/minecraft/world/level/biome/BiomeSpecialEffects;") && !m.name.equals("<init>")));
		// 1.21.1's Biome reads climateSettings 8 times and specialEffects 13 times outside their own accessors (the
		// 26.x base read them 10 and 6 times); every one of those reads is what the rewrite moves onto the view.
		assertEquals(8, calls(biome, BIOME, "getModifiedClimateSettings") - calls(node(read(MERGED, BIOME)), BIOME, "getModifiedClimateSettings"));
		assertEquals(13, calls(biome, BIOME, "getModifiedSpecialEffects") - calls(node(read(MERGED, BIOME)), BIOME, "getModifiedSpecialEffects"));
		assertEquals(1, reads(biome, BIOME, "specialEffects", m -> m.name.equals("getSpecialEffects")), "getSpecialEffects stays raw, as NeoForge leaves it");
		verify(biome);
		ClassNode structure = rewritten(STRUCTURE);
		assertEquals(4, calls(structure, STRUCTURE, "getModifiedStructureSettings") - calls(node(read(MERGED, STRUCTURE)), STRUCTURE, "getModifiedStructureSettings"));
		verify(structure);
	}

	@Test void everyFinalizeSpawnInNeoForgesListIsRedirectedAndNothingElse() throws Exception {
		int before = 0, after = 0;
		for (String target : NativeCoremodParity.FINALIZE_TARGETS) {
			ClassNode original = node(read(MERGED, target));
			int raw = virtualFinalize(original), supers = specialFinalize(original);
			assertTrue(raw >= 1, target + " has a finalizeSpawn call on the merged base");
			ClassNode rewritten = node(NativeCoremodParity.apply(target, read(MERGED, target)));
			assertEquals(0, virtualFinalize(rewritten), target);
			assertEquals(raw, calls(rewritten, NativeCoremodParity.RUNTIME, "finalizeMobSpawn"), target);
			assertEquals(supers, specialFinalize(rewritten), "super.finalizeSpawn in an override is not an event site: " + target);
			verify(rewritten);
			before += raw;
			after += calls(rewritten, NativeCoremodParity.RUNTIME, "finalizeMobSpawn");
		}
		assertEquals(before, after);
		// The census: every such call in the merged game is in a listed class.
		try (ZipFile zip = new ZipFile(MERGED.toFile())) {
			Set<String> outside = new TreeSet<>();
			for (ZipEntry entry : Collections.list(zip.entries())) {
				if (!entry.getName().endsWith(".class") || !entry.getName().startsWith("net/minecraft/")) continue;
				String name = entry.getName().substring(0, entry.getName().length() - 6);
				if (NativeCoremodParity.FINALIZE_TARGETS.contains(name)) continue;
				if (virtualFinalize(node(zip.getInputStream(entry).readAllBytes())) > 0) outside.add(name);
			}
			assertEquals(Set.of(), outside, "finalizeSpawn calls NeoForge's coremod would not see");
		}
	}

	@Test void theTrialSpawnersNeoForgeHookIsRoutedThroughBothFamilies() throws Exception {
		ClassNode rewritten = rewritten(NativeCoremodParity.TRIAL_SPAWNER);
		assertEquals(0, calls(rewritten, NativeCoremodParity.NEO_HOOKS, NativeCoremodParity.NEO_SPAWNER_HOOK));
		assertEquals(1, calls(rewritten, NativeCoremodParity.RUNTIME, "finalizeTrialSpawner"));
		verify(rewritten);
	}

	/**
	 * The list is NeoForge's own, character for character.
	 *
	 * <p>It used to be NeoForge's list plus TrialSpawner, compared against the {@code finalize_spawn_targets.json}
	 * in the staged {@code merged-base/forge-runtime-interop.jar} — the MinecraftForge half of the two-family
	 * model. On 1.21.1 there is no such artifact and no {@code net/minecraftforge} anywhere in the base, so that
	 * half is gone rather than re-anchored: what is left, and what this asserts, is that the list the kernel
	 * rewrites by is exactly the one NeoForge 21.1.256 ships.
	 *
	 * <p>The json is a bare array of internal names ({@code ["net/minecraft/..."]}), not the {@code {"class": ...}}
	 * objects the MinecraftForge one was, so it is read as such: the dotted-name pattern the two-family version
	 * used matched nothing here, which is how a green test would have been asserting against an empty set.
	 */
	@Test void theTargetListIsExactlyNeoForgesOwn() throws Exception {
		String json = neoForgeFinalizeTargets();
		Set<String> listed = new TreeSet<>();
		var match = java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(json);
		while (match.find()) listed.add(match.group(1).replace('.', '/'));
		assertEquals(new TreeSet<>(NativeCoremodParity.FINALIZE_TARGETS), listed);
	}

	/**
	 * NeoForge's own finalize_spawn_targets.json: from the coremods jar nested in the universal jar tools/dev.py
	 * downloaded, else from a native instance's extracted copy. Both are NeoForge's official build, not a mod.
	 */
	private static String neoForgeFinalizeTargets() throws Exception {
		String targets = "net/neoforged/neoforge/coremods/finalize_spawn_targets.json";
		List<Path> universal = Files.isDirectory(NEO_DOWNLOADS)
				? Files.list(NEO_DOWNLOADS).filter(p -> p.getFileName().toString().matches("neoforge-.+-universal\\.jar")).sorted().toList()
				: List.of();
		if (!universal.isEmpty()) {
			try (ZipFile outer = new ZipFile(universal.get(universal.size() - 1).toFile())) {
				ZipEntry nested = Collections.list(outer.entries()).stream()
						.filter(e -> e.getName().matches("META-INF/jarjar/net\\.neoforged\\.neoforge-coremods-.+\\.jar"))
						.findFirst().orElseThrow(() -> new AssertionError(universal + " has no nested coremods jar"));
				try (var in = new java.util.zip.ZipInputStream(outer.getInputStream(nested))) {
					for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
						if (entry.getName().equals(targets)) return new String(in.readAllBytes());
					}
				}
				throw new AssertionError(nested.getName() + " has no " + targets);
			}
		}
		Optional<Path> neo = Files.isDirectory(NEO_COREMODS) ? Files.walk(NEO_COREMODS).filter(p -> p.getFileName().toString().startsWith("net.neoforged.neoforge-coremods-")).findFirst() : Optional.empty();
		TestFixtures.require(Fixture.MC_LIBRARIES, neo.isPresent(), "NeoForge universal jar absent from " + NEO_DOWNLOADS);
		try (ZipFile zip = new ZipFile(neo.get().toFile())) {
			return new String(zip.getInputStream(zip.getEntry(targets)).readAllBytes());
		}
	}

	/**
	 * Vanilla's shape is left raw, and both switches stand every rule down.
	 *
	 * <p>The vanilla half used to read {@code net/minecraft/world/level/block/FlowerPotBlock} out of
	 * {@link TestFixtures#vanillaJar()}. That cannot be done on 1.21.1: the file there is the launcher's own
	 * client jar, whose classes are still OBFUSCATED (it holds every class, under names like {@code guu}, and
	 * exactly 26 {@code net/minecraft/**} entries — the bootstrap mains and the JFR events), so no vanilla class
	 * can be named in it at all. What this half guards is {@link NativeCoremodParity}'s rule that a getter which
	 * IS vanilla's {@code return this.field} means there is nothing to route; that rule is about a SHAPE, so the
	 * shape is built here and handed to the same entry point the game hands the real class to. It fails the same
	 * way if the rule regresses: the second method's raw read would become a {@code getPotted()} call.
	 */
	@Test void vanillasShapeAndTheSwitchesLeaveClassesAlone() throws Exception {
		byte[] vanillaPot = vanillaFlowerPot();
		assertSame(vanillaPot, NativeCoremodParity.apply(POT, vanillaPot), "vanilla's getter reads the field; nothing to route");
		for (String property : List.of(NativeCoremodParity.PROPERTY, NativeCoremodParity.FLOWER_POT)) {
			System.setProperty(property, "off");
			byte[] merged = read(MERGED, POT);
			assertSame(merged, NativeCoremodParity.apply(POT, merged), property);
			System.clearProperty(property);
		}
		System.setProperty(NativeCoremodParity.FINALIZE, "off");
		String zombie = "net/minecraft/world/entity/monster/Zombie";
		byte[] zombieBytes = read(MERGED, zombie);
		assertSame(zombieBytes, NativeCoremodParity.apply(zombie.replace('/', '.'), zombieBytes));
		byte[] other = read(MERGED, "net/minecraft/world/level/block/Blocks");
		assertSame(other, NativeCoremodParity.apply("net.minecraft.world.level.block.Blocks", other));
	}

	/**
	 * Vanilla 1.21.1's {@code FlowerPotBlock}: {@code private final Block potted},
	 * {@code public Block getPotted() { return this.potted; }} — three real instructions, which is exactly what
	 * {@code returnsField} recognises — and one more method that reads the field raw, as vanilla's
	 * {@code useWithoutItem} and {@code getCloneItemStack} do.
	 */
	private static byte[] vanillaFlowerPot() {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, POT, null, "java/lang/Object", null);
		writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "potted", "Lnet/minecraft/world/level/block/Block;", null, null).visitEnd();
		returnsPotted(writer.visitMethod(Opcodes.ACC_PUBLIC, "getPotted", "()Lnet/minecraft/world/level/block/Block;", null, null));
		// A caller that reads the field itself, as vanilla's useWithoutItem and getCloneItemStack do.
		returnsPotted(writer.visitMethod(Opcodes.ACC_PUBLIC, "content", "()Lnet/minecraft/world/level/block/Block;", null, null));
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static void returnsPotted(MethodVisitor method) {
		method.visitCode();
		method.visitVarInsn(Opcodes.ALOAD, 0);
		method.visitFieldInsn(Opcodes.GETFIELD, POT, "potted", "Lnet/minecraft/world/level/block/Block;");
		method.visitInsn(Opcodes.ARETURN);
		method.visitMaxs(1, 1);
		method.visitEnd();
	}

	private static ClassNode rewritten(String internalName) throws Exception {
		byte[] original = read(MERGED, internalName);
		byte[] out = NativeCoremodParity.apply(internalName.replace('/', '.'), original);
		assertNotSame(original, out, internalName + " is rewritten");
		return node(out);
	}

	private static int reads(ClassNode node, String owner, String field, java.util.function.Predicate<MethodNode> in) {
		int n = 0;
		for (MethodNode m : node.methods) {
			if (!in.test(m)) continue;
			for (AbstractInsnNode insn : m.instructions) if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD && f.owner.equals(owner) && f.name.equals(field)) n++;
		}
		return n;
	}

	private static int calls(ClassNode node, String owner, String name) {
		int n = 0;
		for (MethodNode m : node.methods) for (AbstractInsnNode insn : m.instructions) if (insn instanceof MethodInsnNode c && c.owner.equals(owner) && c.name.equals(name)) n++;
		return n;
	}

	private static int virtualFinalize(ClassNode node) { return finalizeCalls(node, Opcodes.INVOKEVIRTUAL); }
	private static int specialFinalize(ClassNode node) { return finalizeCalls(node, Opcodes.INVOKESPECIAL); }
	private static int finalizeCalls(ClassNode node, int opcode) {
		int n = 0;
		for (MethodNode m : node.methods) for (AbstractInsnNode insn : m.instructions)
			if (insn.getOpcode() == opcode && insn instanceof MethodInsnNode c && c.name.equals("finalizeSpawn") && c.desc.equals(NativeCoremodParity.FINALIZE_DESC)) n++;
		return n;
	}

	private static void verify(ClassNode node) throws Exception {
		for (MethodNode m : node.methods) if (m.instructions.size() > 0) new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node;
	}

	/** One class of {@code jar}, which some thirty transform tests read through here; a jar present without it fails. */
	static byte[] read(Path jar, String internalName) {
		return TestFixtures.requireEntry(fixtureOf(jar), jar, internalName + ".class");
	}

	/**
	 * Which fixture {@code jar} is, from where it lives: the callers pass staged carriers, the local vanilla jar and
	 * pinned mod jars through the one helper, and each is missing on a different kind of machine.
	 */
	static Fixture fixtureOf(Path jar) {
		Path at = jar.toAbsolutePath().normalize();
		if (at.startsWith(TestFixtures.stagedRoot().toAbsolutePath().normalize())) return Fixture.STAGED;
		if (at.startsWith(TestFixtures.minecraftDir().toAbsolutePath().normalize())) return Fixture.MC_LIBRARIES;
		return Fixture.THIRD_PARTY;
	}
}

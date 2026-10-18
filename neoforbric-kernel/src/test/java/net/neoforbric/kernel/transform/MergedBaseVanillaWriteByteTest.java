/* Copyright 2026 The NeoForbric Project. Licensed under the Apache License, Version 2.0. */
package net.neoforbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Covers calling vanilla's {@code FriendlyByteBuf.writeByte(int)} again where NeoForge's recompile bound the call to its
 * extension's {@code writeByte(byte)}.
 *
 * <p>The decisive assertions read the other jars: the extension's overload only forwards to vanilla's, and after the
 * repair every merged method that made the extension call makes vanilla's call instead.
 *
 * <p>Re-anchored, twice over. The census used to be a three-way one — vanilla's own body for the class was read out
 * of {@link TestFixtures#vanillaJar()} and the repaired merged method had to make exactly the calls vanilla makes,
 * same owners, same descriptors, as often. That half cannot run on this anchor: the fixture is Mojang's OBFUSCATED
 * launcher jar, so it has no {@code net/minecraft/...} entry for a merged class to be compared against (checked in
 * {@link #everyMergedSiteBecomesVanillasCall()} rather than assumed). And the shape the census was counting is not in
 * this base at all: 1.21.1's patched jar calls {@code writeByte(int)} everywhere vanilla does, so the repair stands
 * down, which is what the census now asserts — with the per-site re-bind loop left live for the day NeoForge's
 * recompile binds one again.
 */
class MergedBaseVanillaWriteByteTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
	private static final Path NEO_RUNTIME = TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final Path VANILLA = TestFixtures.vanillaJar();
	private static final String EXTENSION = "net/neoforged/neoforge/common/extensions/IFriendlyByteBufExtension";
	private static final String BUF = "net/minecraft/network/FriendlyByteBuf";
	private static final String BYTE = "(B)Lnet/minecraft/network/FriendlyByteBuf;";
	private static final String INT = "(I)Lnet/minecraft/network/FriendlyByteBuf;";
	private static final String ABILITIES = "net/minecraft/network/protocol/game/ServerboundPlayerAbilitiesPacket";
	private static final String FIXTURE = "net/minecraft/network/NeoForbricWriteByteFixture";

	@AfterEach void reset() {
		System.clearProperty(NeoForbricMergedBaseCompatTransformer.VANILLA_WRITE_BYTE_PROPERTY);
	}

	/** The swap is behaviour-preserving only because NeoForge's overload is nothing but the forward. */
	@Test void theExtensionsOverloadOnlyForwardsToVanillas() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(NEO_RUNTIME), "NeoForge runtime not staged: " + NEO_RUNTIME);
		ClassNode extension = read(entry(NEO_RUNTIME, EXTENSION + ".class"));
		MethodNode overload = extension.methods.stream().filter(m -> m.name.equals("writeByte") && m.desc.equals(BYTE))
				.findFirst().orElseThrow(() -> new AssertionError("NeoForge's extension no longer declares writeByte(byte); "
						+ "delete the repair rather than leaving a claim on a shape that is gone"));
		List<String> code = new ArrayList<>();
		for (AbstractInsnNode insn : overload.instructions) {
			if (insn.getOpcode() < 0) continue;
			code.add(insn instanceof MethodInsnNode call ? call.owner + "." + call.name + call.desc : Integer.toString(insn.getOpcode()));
		}
		assertEquals(List.of(Integer.toString(Opcodes.ALOAD), EXTENSION + ".self()L" + BUF + ";", Integer.toString(Opcodes.ILOAD),
				BUF + ".writeByte" + INT, Integer.toString(Opcodes.ARETURN)), code);
	}

	@Test void everyMergedSiteBecomesVanillasCall() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "merged base not staged: " + MERGED_BASE);
		TestFixtures.require(Fixture.MC_LIBRARIES, Files.isRegularFile(VANILLA), "no vanilla 1.21.1 jar at " + VANILLA);
		// The premise of the re-anchor above: the vanilla fixture cannot answer for a merged class, because its
		// entries are obfuscated. Asserted, not assumed — the day a Mojmap-named jar is staged here, the three-way
		// census can be restored and this fails saying so.
		try (ZipFile vanilla = new ZipFile(VANILLA.toFile())) {
			assertNull(vanilla.getEntry(BUF + ".class"),
					"premise: the staged 1.21.1.jar is Mojang's OBFUSCATED launcher jar, so it cannot be read by the "
							+ "names the merged base uses");
			assertNotNull(vanilla.getEntry("net/minecraft/client/main/Main.class"),
					"…and only the launcher's own entries keep their Mojmap names");
		}

		Map<String, Integer> sites = new TreeMap<>();
		int scanned = 0;
		try (ZipFile merged = new ZipFile(MERGED_BASE.toFile())) {
			for (ZipEntry entry : Collections.list(merged.entries())) {
				if (!entry.getName().startsWith("net/minecraft/") || !entry.getName().endsWith(".class")) continue;
				scanned++;
				byte[] before = merged.getInputStream(entry).readAllBytes();
				ClassNode node = read(before);
				Map<String, List<String>> calls = writeBytes(node);
				if (calls.values().stream().noneMatch(c -> c.stream().anyMatch(s -> s.endsWith(BYTE)))) continue;
				// Nothing in this base takes this path today (the assertion below says so), and it is kept live so
				// that the day NeoForge's recompile binds a call to the extension again, the repair is driven
				// against the real bytes that came back rather than against the synthetic fixture alone.
				String name = node.name.replace('/', '.');
				Map<String, List<String>> after = writeBytes(read(new NeoForbricMergedBaseCompatTransformer().transform(name, before, null)));
				for (Map.Entry<String, List<String>> method : calls.entrySet()) {
					long count = method.getValue().stream().filter(s -> s.endsWith(BYTE)).count();
					if (count == 0) continue;
					sites.merge(name, (int) count, Integer::sum);
					// Every site keeps its position and its owner: only the descriptor moves, to vanilla's overload.
					assertEquals(method.getValue().size(), after.get(method.getKey()).size(),
							name + "." + method.getKey() + " lost or gained a writeByte call");
					assertTrue(after.get(method.getKey()).stream().noneMatch(s -> s.endsWith(BYTE)),
							name + "." + method.getKey() + " still calls NeoForge's writeByte(byte): "
									+ after.get(method.getKey()));
					assertEquals(count, after.get(method.getKey()).stream().filter(s -> s.endsWith(INT)).count(),
							name + "." + method.getKey() + " did not re-bind every site to vanilla's writeByte(int)");
				}
			}
		}
		assertTrue(scanned > 7000, "the census must read the whole game, not a corner of it: " + scanned);
		// Re-anchored: this base is NeoForge 1.21.1's own patched jar, and its recompile never bound these calls to
		// the extension — every site vanilla makes is already writeByte(int) (ServerboundPlayerAbilitiesPacket.write
		// included, which the test below pins), so the repair has nothing to re-bind and stands down. 26.x's
		// fourteen sites in ten write methods are not here; if any come back, the loop above drives the repair
		// against them.
		assertEquals(Map.of(), sites, "the extension call is back in the merged base — the loop above must be "
				+ "re-derived against these bytes: " + sites);
	}

	/**
	 * The injector's premise, read off the real base. ViaFabricPlus' 1.15.2 ability-flags redirect anchors on
	 * {@code ServerboundPlayerAbilitiesPacket.write}'s call to vanilla's {@code writeByte(int)}; that is the call the
	 * merged method makes on this base, so the mixin binds with no repair in between — where 26.x's recompile had
	 * bound it to the extension's {@code writeByte(byte)} first.
	 */
	@Test void theInjectorsAnchorIsAlreadyVanillasCallOnThisBase() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "merged base not staged: " + MERGED_BASE);
		ClassNode node = read(entry(MERGED_BASE, ABILITIES + ".class"));
		assertEquals(Map.of("write(L" + BUF + ";)V", List.of(BUF + ".writeByte" + INT)), writeBytes(node),
				"ViaFabricPlus' redirect binds this exact call");
	}

	@Test void switchedOffItLeavesNeoForgesCall() {
		System.setProperty(NeoForbricMergedBaseCompatTransformer.VANILLA_WRITE_BYTE_PROPERTY, "off");
		byte[] fixture = fixture(FIXTURE);
		assertSame(fixture, new NeoForbricMergedBaseCompatTransformer().transform(FIXTURE.replace('/', '.'), fixture, null));
	}

	@Test void aGameClassCallsVanillasOverloadAndOneOutsideTheGameIsLeftAlone() {
		byte[] fixture = fixture(FIXTURE);
		ClassNode repaired = read(new NeoForbricMergedBaseCompatTransformer().transform(FIXTURE.replace('/', '.'), fixture, null));
		assertEquals(Map.of("write(L" + BUF + ";B)V", List.of(BUF + ".writeByte" + INT)), writeBytes(repaired));
		byte[] outside = fixture("net/neoforbric/other/WriteByteFixture");
		assertSame(outside, new NeoForbricMergedBaseCompatTransformer().transform("net.neoforbric.other.WriteByteFixture", outside, null));
	}

	/** Each method's {@code writeByte} calls on either buffer, as {@code owner.name+desc}, in order. */
	private static Map<String, List<String>> writeBytes(ClassNode node) {
		Map<String, List<String>> out = new TreeMap<>();
		for (MethodNode method : node.methods) {
			List<String> calls = new ArrayList<>();
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.name.equals("writeByte")
						&& (call.owner.equals(BUF) || call.owner.equals("net/minecraft/network/RegistryFriendlyByteBuf"))) {
					calls.add(call.owner + "." + call.name + call.desc);
				}
			}
			if (!calls.isEmpty()) out.put(method.name + method.desc, calls);
		}
		return out;
	}

	/** {@code static void write(FriendlyByteBuf, byte)} making NeoForge's call, as its recompile emits it. */
	private static byte[] fixture(String name) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		MethodVisitor write = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "write", "(L" + BUF + ";B)V", null, null);
		write.visitCode();
		write.visitVarInsn(Opcodes.ALOAD, 0);
		write.visitVarInsn(Opcodes.ILOAD, 1);
		write.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUF, "writeByte", BYTE, false);
		write.visitInsn(Opcodes.POP);
		write.visitInsn(Opcodes.RETURN);
		write.visitMaxs(2, 2);
		write.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static ClassNode read(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] entry(Path jar, String name) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(name);
			assertNotNull(entry, name + " in " + jar);
			return zip.getInputStream(entry).readAllBytes();
		}
	}
}

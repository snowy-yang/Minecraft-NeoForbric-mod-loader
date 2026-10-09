package net.neoforbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** NeoForge's hopper asking Fabric's item storage lookup, on the real 1.21.1 NeoForge HopperBlockEntity. */
@ResourceLock("system-properties")
class HopperFabricStorageInjectorTest {
	private static final Path STAGED = TestFixtures.stagedRoot();
	private static final Path MERGED = STAGED.resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
	private static final Path FABRIC_API = TestFixtures.fabricApi().toAbsolutePath();
	private static final String HOPPER = HopperFabricStorageInjector.HOPPER_INTERNAL;

	@AfterEach void reset() { System.clearProperty(HopperFabricStorageInjector.PROPERTY); }

	/**
	 * 1.21.1's premise: the hooks still precede the vanilla lookups (Fabric's mixin CAN bind them), but the hooks
	 * answer handled before the lookups run — so a Fabric storage NeoForge's capability cannot see is only ever
	 * reached by asking Fabric at the hooks' declines.
	 */
	@Test void premiseBodiesAreNeoForgesAndFabricsAnchorsRunAfterItsHooks() throws Exception {
		ClassNode merged = node(NativeCoremodParityTest.read(MERGED, HOPPER));
		MethodNode eject = method(merged, "ejectItems"), suck = method(merged, "suckInItems");
		assertEquals(1, calls(eject, "net/neoforged/neoforge/items/VanillaInventoryCodeHooks", "insertHook"));
		assertEquals(1, calls(eject, HOPPER, "getAttachedContainer"), "vanilla lookup present, after the hook");
		assertEquals(1, calls(suck, "net/neoforged/neoforge/items/VanillaInventoryCodeHooks", "extractHook"));
		assertEquals(1, calls(suck, HOPPER, "getSourceContainer"), "vanilla lookup present, after the hook");
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(FABRIC_API), "the pack's fabric-api is absent");
		byte[] mixin = nested(FABRIC_API, "fabric-transfer-api-v1", "net/fabricmc/fabric/mixin/transfer/HopperBlockEntityMixin.class");
		assertNotNull(mixin);
		List<String> targets = new ArrayList<>();
		for (MethodNode handler : node(mixin).methods) {
			if (handler.visibleAnnotations == null) continue;
			for (AnnotationNode a : handler.visibleAnnotations) collectTargets(a, targets);
		}
		assertTrue(targets.stream().anyMatch(t -> t.contains(";getAttachedContainer(")), targets.toString());
		assertTrue(targets.stream().anyMatch(t -> t.contains(";getSourceContainer(")), targets.toString());
	}

	@Test void bothHookDeclinesAskFabric() throws Exception {
		byte[] original = NativeCoremodParityTest.read(MERGED, HOPPER);
		ClassNode before = node(original);
		byte[] out = new HopperFabricStorageInjector().transform(HopperFabricStorageInjector.HOPPER, original, null);
		assertNotSame(original, out);
		ClassNode after = node(out);

		// ejectItems: the fabric ask sits at the insert hook's IFEQ; true returns true, false falls to vanilla.
		MethodNode eject = method(after, "ejectItems");
		MethodInsnNode insert = call(eject, HopperFabricStorageInjector.RUNTIME, "insert");
		assertTrue(insert.getPrevious() instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 2,
				"the ask carries the hopper");
		JumpInsnNode insertFalls = (JumpInsnNode) insert.getNext();
		assertEquals(Opcodes.IFEQ, insertFalls.getOpcode(), "insert's false answer falls to the vanilla lookups");
		assertEquals(Opcodes.ICONST_1, insertFalls.getNext().getOpcode());
		assertEquals(Opcodes.IRETURN, insertFalls.getNext().getNext().getOpcode());
		assertEquals(method(before, "ejectItems").maxLocals, eject.maxLocals);
		assertEquals(count(method(before, "ejectItems"), Opcodes.IRETURN) + 1, count(eject, Opcodes.IRETURN));

		// suckInItems: the fabric ask sits at the extract hook's IFNULL; NOT_FOUND falls, 1/0 is the answer.
		MethodNode suck = method(after, "suckInItems");
		MethodInsnNode extract = call(suck, HopperFabricStorageInjector.RUNTIME, "extract");
		JumpInsnNode extractFalls = (JumpInsnNode) extract.getNext().getNext();
		assertEquals(Opcodes.IFLT, extractFalls.getOpcode(), "NOT_FOUND falls to the vanilla lookups");
		assertEquals(Opcodes.IRETURN, extractFalls.getNext().getOpcode());
		assertEquals(count(method(before, "suckInItems"), Opcodes.IRETURN) + 1, count(suck, Opcodes.IRETURN));
		assertEquals(method(before, "suckInItems").maxLocals, suck.maxLocals);
		for (String unchanged : List.of("getItemsAtAndAbove", "getSourceContainer", "extractHook", "isGridAligned")) {
			assertEquals(callsNamed(method(before, "suckInItems"), unchanged), callsNamed(suck, unchanged), unchanged);
		}
		assertEquals(getstatics(method(before, "suckInItems"), "DOWN"), getstatics(suck, "DOWN"), "lithium's FIELD DOWN anchor count");
		new Analyzer<>(new BasicVerifier()).analyze(HOPPER, eject);
		new Analyzer<>(new BasicVerifier()).analyze(HOPPER, suck);

		assertSame(out, new HopperFabricStorageInjector().transform(HopperFabricStorageInjector.HOPPER, out, null), "a second pass changes nothing");
	}

	@Test void aVanillaHopperBodyIsLeftAlone() throws Exception {
		// Vanilla's ejectItems/suckInItems ask no VanillaInventoryCodeHooks; Fabric's own mixin applies there
		// and this repair's anchors are absent, so the injector must stand down whole.
		ClassNode vanilla = node(NativeCoremodParityTest.read(MERGED, HOPPER));
		MethodNode eject = method(vanilla, "ejectItems");
		for (AbstractInsnNode insn : eject.instructions.toArray()) {
			if (insn instanceof MethodInsnNode c && c.owner.equals("net/neoforged/neoforge/items/VanillaInventoryCodeHooks"))
				eject.instructions.remove(insn); // a body without the hooks, as vanilla's is
		}
		ClassWriter writer = new ClassWriter(0);
		vanilla.accept(writer);
		byte[] bytes = writer.toByteArray();
		assertSame(bytes, new HopperFabricStorageInjector().transform(HopperFabricStorageInjector.HOPPER, bytes, null),
				"a body Fabric's own mixin applies to is left alone");
	}

	@Test void theSwitchLeavesTheHopperAlone() throws Exception {
		System.setProperty(HopperFabricStorageInjector.PROPERTY, "off");
		byte[] original = NativeCoremodParityTest.read(MERGED, HOPPER);
		assertSame(original, new HopperFabricStorageInjector().transform(HopperFabricStorageInjector.HOPPER, original, null));
	}

	/** Both or neither: a drifted suckInItems leaves ejectItems alone too. */
	@Test void bothOrNeither() throws Exception {
		ClassNode drifted = node(NativeCoremodParityTest.read(MERGED, HOPPER));
		MethodNode suck = method(drifted, "suckInItems");
		for (AbstractInsnNode insn : suck.instructions.toArray()) {
			if (insn instanceof MethodInsnNode c && c.owner.equals("net/neoforged/neoforge/items/VanillaInventoryCodeHooks")
					&& c.name.equals("extractHook")) {
				// The hook no longer stores its Boolean where the IFNULL reads it: the decline shape is gone.
				AbstractInsnNode store = insn.getNext();
				while (store != null && store.getOpcode() < 0) store = store.getNext();
				if (store != null && store.getOpcode() == Opcodes.ASTORE) suck.instructions.remove(store);
			}
		}
		ClassWriter writer = new ClassWriter(0);
		drifted.accept(writer);
		byte[] bytes = writer.toByteArray();
		assertSame(bytes, new HopperFabricStorageInjector().transform(HopperFabricStorageInjector.HOPPER, bytes, null));
	}

	private static MethodNode method(ClassNode node, String name) {
		return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
	}

	private static int calls(MethodNode m, String owner, String name) {
		return (int) Arrays.stream(m.instructions.toArray()).filter(i -> i instanceof MethodInsnNode c && c.owner.equals(owner) && c.name.equals(name)).count();
	}

	private static int callsNamed(MethodNode m, String name) {
		return (int) Arrays.stream(m.instructions.toArray()).filter(i -> i instanceof MethodInsnNode c && c.name.equals(name)).count();
	}

	private static MethodInsnNode call(MethodNode m, String owner, String name) {
		return Arrays.stream(m.instructions.toArray()).filter(i -> i instanceof MethodInsnNode c && c.owner.equals(owner) && c.name.equals(name))
				.map(MethodInsnNode.class::cast).findFirst().orElseThrow(() -> new AssertionError(name));
	}

	private static int getstatics(MethodNode m, String name) {
		return (int) Arrays.stream(m.instructions.toArray()).filter(i -> i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && f.name.equals(name)).count();
	}

	private static int count(MethodNode m, int opcode) {
		return (int) Arrays.stream(m.instructions.toArray()).filter(i -> i.getOpcode() == opcode).count();
	}

	private static int size(MethodNode m) {
		return (int) Arrays.stream(m.instructions.toArray()).filter(i -> i.getOpcode() >= 0).count();
	}

	private static void collectTargets(AnnotationNode a, List<String> out) {
		if (a.values == null) return;
		for (int i = 0; i + 1 < a.values.size(); i += 2) {
			Object v = a.values.get(i + 1);
			if ("target".equals(a.values.get(i)) && v instanceof String s) out.add(s);
			if (v instanceof AnnotationNode nested) collectTargets(nested, out);
			if (v instanceof List<?> list) for (Object o : list) if (o instanceof AnnotationNode nested) collectTargets(nested, out);
		}
	}

	private static byte[] nested(Path outer, String module, String entry) throws Exception {
		try (ZipFile zip = new ZipFile(outer.toFile())) {
			for (ZipEntry jar : zip.stream().toList()) {
				if (!jar.getName().startsWith("META-INF/jars/" + module)) continue;
				Path tmp = Files.createTempFile("neoforbric-nested", ".jar");
				try (InputStream in = zip.getInputStream(jar)) { Files.write(tmp, in.readAllBytes()); }
				try (ZipFile inner = new ZipFile(tmp.toFile())) {
					ZipEntry found = inner.getEntry(entry);
					if (found != null) try (InputStream in = inner.getInputStream(found)) { return in.readAllBytes(); }
				} finally {
					Files.deleteIfExists(tmp);
				}
			}
		}
		return null;
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}
}

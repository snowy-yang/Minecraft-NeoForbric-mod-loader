/*
 * Copyright 2026 The NeoForbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.neoforbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;

/**
 * Covers the shutdown-save repair: on 26.x {@code IntegratedServer.stopServer} ran {@code teardownPublishedState()}
 * first and unguarded, and {@code MinecraftServer.stopServer()} — which writes players and worlds — second, so a
 * throw in the teardown ended the process with {@code level.dat} at the last autosave. Measured on a real install
 * after an Alt+F4: region files on disk at the moment of exit, {@code level.dat} sixty seconds behind.
 *
 * <p>Re-anchored to 1.21.1 for the two base-reading tests. 1.21.1's {@code IntegratedServer.stopServer()} calls
 * {@code MinecraftServer.stopServer()} — the save — as its FIRST action, then interrupts the LAN pinger, and has
 * no {@code teardownPublishedState()} method at all. The failure mode the repair guarded cannot occur, so the
 * repair correctly declines on the staged base; what is pinned below is that order (the save first, no teardown
 * call), which is what makes the repair unnecessary. The repair's own logic is still exercised, on a synthetic
 * stand-in with 26.x's exact shape, by the load-and-run tests further down.
 *
 * <p>The load-and-run test is the one with teeth. Asserting an exception table exists says nothing about whether
 * the save is REACHED, and it says nothing about whether the frames the pass writes are ones the JVM will accept
 * — a bad {@code StackMapTable} is a {@code VerifyError} at class load, which no bytecode inspection here would
 * notice. So one test defines the transformed class for real and calls it.
 */
public class MergedBaseShutdownSaveTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");

	private static final String INTEGRATED_SERVER = "net/minecraft/client/server/IntegratedServer";

	/** Set by the synthetic stand-in for {@code MinecraftServer.stopServer}. This is "the world was saved". */
	public static volatile boolean saved;
	/** What the synthetic teardown throws, so the test can tell its throw apart from any other failure. */
	public static volatile boolean teardownThrows = true;
	/**
	 * Set from inside the synthetic teardown's body.
	 *
	 * <p>Asserted by both behaviour tests, because without it they pass on the wrong throw. Mutation-testing this
	 * file found exactly that: the synthetic class is defined in its own loader, the test class was
	 * package-private, and so the teardown's very first instruction raised {@code IllegalAccessError} — which the
	 * repair dutifully caught, leaving the save to run and the test to go green having never reached the body it
	 * was written to exercise.
	 */
	public static volatile boolean teardownRan;

	/** The superclass of the synthetic {@code IntegratedServer}: its {@code stopServer} is the save. */
	public static class FakeMinecraftServer {
		public void stopServer() {
			saved = true;
		}
	}

	@Test
	void theStagedBaseSavesFirstAndHasNoUnguardedTeardown() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		ClassNode before = parse(readClass(INTEGRATED_SERVER + ".class"));
		MethodNode stop = method(before, "stopServer", "()V");
		assertNotNull(stop, "the base must still have IntegratedServer.stopServer()V");
		assertTrue(stop.tryCatchBlocks == null || stop.tryCatchBlocks.isEmpty(),
				"no handler is needed here: nothing runs before the save that could throw");
		assertTrue(noCall(stop, "teardownPublishedState"),
				"content drift: a teardown call came back — re-derive the repair that had to guard it");
		assertTrue(savesFirst(stop),
				"the save must be the first thing the body does; that order is why the repair is unnecessary");
	}

	@Test
	void theBaseIsHandedBackUntouchedBecauseTheFailureModeCannotOccur() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		byte[] in = readClass(INTEGRATED_SERVER + ".class");
		byte[] out = transform(in);
		assertSame(in, out, "the staged base already saves first and has no teardown call, so the pass must decline");

		ClassNode node = parse(out);
		MethodNode stop = method(node, "stopServer", "()V");
		assertTrue(savesFirst(stop), "the upstream order is deliberately kept — the save is first");
		new Analyzer<>(new BasicVerifier()).analyze(node.name, stop);
	}

	@Test
	void aSecondPassLeavesTheRepairedClassAlone() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		byte[] in = readClass(INTEGRATED_SERVER + ".class");
		byte[] once = transform(in);
		assertSame(in, once, "the pass declines on the base, so the base comes back unchanged");
		assertSame(once, transform(once));
	}

	/**
	 * The behaviour, executed: a teardown that throws must not stop the save from running.
	 *
	 * <p>Also the only check that the written {@code StackMapTable} is one the JVM accepts — defining the class
	 * runs the verifier.
	 */
	@Test
	void aThrowingTeardownNoLongerCostsTheSave() throws Exception {
		saved = false;
		teardownRan = false;
		teardownThrows = true;
		Object server = loadSynthetic().getDeclaredConstructor().newInstance();
		server.getClass().getMethod("stopServer").invoke(server);
		assertTrue(teardownRan, "the teardown body must have run — otherwise this passed on some other throw");
		assertTrue(saved, "the save must run even though the teardown threw");
	}

	@Test
	void aTeardownThatSucceedsIsUnaffected() throws Exception {
		saved = false;
		teardownRan = false;
		teardownThrows = false;
		Object server = loadSynthetic().getDeclaredConstructor().newInstance();
		server.getClass().getMethod("stopServer").invoke(server);
		assertTrue(teardownRan, "the teardown body must have run");
		assertTrue(saved, "the ordinary path must still save");
	}

	@Test
	void aBodyWhoseTeardownIsNotFollowedByTheSaveIsLeftAlone() {
		// Wrapping here would swallow the report and buy nothing, because there is no save downstream to protect.
		byte[] in = integratedServer(false);
		assertSame(in, transform(in));
	}

	@Test
	void aClassThatIsNotTheIntegratedServerIsLeftAlone() {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.name = "net/minecraft/server/dedicated/DedicatedServer";
		node.superName = "java/lang/Object";
		MethodNode stop = new MethodNode(Opcodes.ACC_PUBLIC, "stopServer", "()V", null, null);
		stop.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		stop.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name,
				"teardownPublishedState", "()V", false));
		stop.instructions.add(new InsnNode(Opcodes.RETURN));
		stop.maxStack = 1;
		stop.maxLocals = 1;
		node.methods.add(stop);
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		byte[] in = writer.toByteArray();
		assertSame(in, transform(in));
	}

	// --- helpers ---------------------------------------------------------------------------------------------

	private static Class<?> loadSynthetic() {
		byte[] bytes = transform(integratedServer(true));
		ClassLoader loader = new ClassLoader(MergedBaseShutdownSaveTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException {
				if (name.equals(INTEGRATED_SERVER.replace('/', '.'))) {
					return defineClass(name, bytes, 0, bytes.length);
				}
				throw new ClassNotFoundException(name);
			}
		};
		try {
			return loader.loadClass(INTEGRATED_SERVER.replace('/', '.'));
		} catch (ClassNotFoundException e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * A stand-in with upstream's exact {@code stopServer} shape: teardown, then the super call that saves.
	 *
	 * @param withSave whether the super call follows, which is the condition the pass requires
	 */
	private static byte[] integratedServer(boolean withSave) {
		String owner = MergedBaseShutdownSaveTest.class.getName().replace('.', '/');
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
		node.name = INTEGRATED_SERVER;
		node.superName = owner + "$FakeMinecraftServer";

		MethodNode ctor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		ctor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.superName, "<init>", "()V", false));
		ctor.instructions.add(new InsnNode(Opcodes.RETURN));
		ctor.maxStack = 1;
		ctor.maxLocals = 1;
		node.methods.add(ctor);

		// The teardown: throws when the test asks it to, exactly like the render-thread assertion does.
		MethodNode teardown = new MethodNode(Opcodes.ACC_PRIVATE, "teardownPublishedState", "()V", null, null);
		teardown.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, "failIfAsked", "()V", false));
		teardown.instructions.add(new InsnNode(Opcodes.RETURN));
		teardown.maxStack = 1;
		teardown.maxLocals = 1;
		node.methods.add(teardown);

		MethodNode stop = new MethodNode(Opcodes.ACC_PUBLIC, "stopServer", "()V", null, null);
		stop.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		stop.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name,
				"teardownPublishedState", "()V", false));
		if (withSave) {
			stop.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			stop.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.superName,
					"stopServer", "()V", false));
		}
		stop.instructions.add(new InsnNode(Opcodes.RETURN));
		stop.maxStack = 1;
		stop.maxLocals = 1;
		node.methods.add(stop);

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** Called from the synthetic teardown, so the throw comes from real control flow rather than a constant. */
	public static void failIfAsked() {
		teardownRan = true;
		if (teardownThrows) throw new IllegalStateException("Rendersystem called from wrong thread");
	}

	private static byte[] transform(byte[] in) {
		return new NeoForbricMergedBaseCompatTransformer().transform(
				INTEGRATED_SERVER.replace('/', '.'), in, null);
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name) && m.desc.equals(desc)) return m;
		}
		return null;
	}

	/** Whether the body calls {@code name} at all. */
	private static boolean noCall(MethodNode method, String name) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.name.equals(name)) return false;
		}
		return true;
	}

	/** Whether the first real instructions are {@code this.stopServer()} — the save, on the superclass. */
	private static boolean savesFirst(MethodNode method) {
		AbstractInsnNode first = nextReal(method.instructions.getFirst());
		AbstractInsnNode second = nextReal(first);
		return first instanceof VarInsnNode receiver && receiver.getOpcode() == Opcodes.ALOAD && receiver.var == 0
				&& second instanceof MethodInsnNode call && "stopServer".equals(call.name)
				&& call.getOpcode() == Opcodes.INVOKESPECIAL
				&& "net/minecraft/server/MinecraftServer".equals(call.owner);
	}

	private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
		AbstractInsnNode next = insn == null ? null : insn.getNext();
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		return next;
	}

	private static byte[] readClass(String entry) throws Exception {
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry e = zip.getEntry(entry);
			assertNotNull(e, entry + " must be in the staged merged base");
			try (InputStream in = zip.getInputStream(e)) {
				return in.readAllBytes();
			}
		}
	}
}

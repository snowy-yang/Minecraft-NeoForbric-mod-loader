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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import net.fabricmc.api.EnvType;
import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;

/**
 * The two client windows must open in this order inside {@code Minecraft.<init>}:
 *
 * <pre>singleton assigned -> Fabric client entrypoints -> Options built -> NeoForge client setup</pre>
 *
 * <p>Neither injector can express that on its own, which is why this test is about the pair rather than about
 * either one. The order is not a preference: Fabric's entrypoints must run while {@code options} is still NULL,
 * because keymapping registration rejects a built {@code Options} with "GameOptions has already been initialised";
 * NeoForge's {@code FMLClientSetupEvent} must run once {@code options} EXISTS, because JourneyMap's setup validates
 * its config against {@code Minecraft.getInstance().options.renderDistance()} and NPE'd on the null, leaving every
 * JourneyMap property null and killing the render loop the moment the world drew.
 *
 * <p>On this anchor only the FIRST half of that pair still has a home. The Fabric anchor is the first
 * {@code new Options}, which 1.21.1's constructor has; the NeoForge anchor the injector was written against is
 * {@code ClientModLoader.finish()}, which 1.21.1's {@code ClientModLoader} does not have at all — see
 * {@link #theNeoForgeAnchorThisInjectorLooksForIsGoneOnThisAnchor}, which asserts the gap on the real classes
 * instead of guessing at a replacement. The synthetic constructor below is the injector's own anchor shape, kept so
 * that the ordering logic itself is still covered.
 */
class ClientWindowOrderTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
	private static final Path NEO_RUNTIME = TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar");

	private static final String MINECRAFT = "net.minecraft.client.Minecraft";
	private static final String OPTIONS = "net/minecraft/client/Options";
	private static final String NEO_CLIENT_MOD_LOADER = "net/neoforged/neoforge/client/loading/ClientModLoader";
	private static final String CLIENT_MOD_LOADER_ENTRY =
			"net/neoforged/neoforge/client/loading/ClientModLoader.class";
	/**
	 * The Fabric window's landmark: Fabric Loader's own {@code Hooks.startClient}, which the kernel's window runs
	 * from, or the bare {@code KernelLifecycle.onClientEntrypoints} under {@code -Dneoforbric.fabricHooks=off}.
	 */
	private static final String FABRIC_HOOK = "fabric-client-entrypoints";
	private static final String NEO_HOOK = "onNeoClientSetup";

	/**
	 * The half of the pair the anchor still supports: on the REAL merged base the Fabric hook lands before the first
	 * {@code new Options}, so Fabric client entrypoints still fire with a live singleton and a null {@code options}.
	 */
	@Test
	void onTheRealConstructorTheFabricHookStillPrecedesTheFirstOptions() throws Exception {
		List<String> marks = landmarks(bothInjectors(mergedBaseMinecraft(), true));

		assertEquals(FABRIC_HOOK, marks.get(0), "the Fabric client-entrypoint hook was never inserted");
		assertEquals("NEW " + OPTIONS, marks.get(1), "the hook must precede the first Options allocation");
	}

	/**
	 * The NeoForge half, on the REAL classes, and the gap that keeps it from landing.
	 *
	 * <p>What this test used to assert: that applying both injectors to 1.21.1's {@code Minecraft} left the three
	 * landmarks in the documented order — Fabric hook, first {@code new Options}, {@code onNeoClientSetup}. That
	 * cannot hold here, because the NeoForge hook is anchored on {@code ClientModLoader.finish()}, the call genuine
	 * NeoForge makes at the end of client mod loading, and 1.21.1's {@code ClientModLoader} has no {@code finish}
	 * method at all ({@code begin}, {@code completeModLoading(Runnable)}, {@code checkForUpdates},
	 * {@code isLoading} — nothing else). The injector finds nothing, warns and hands the class back, so
	 * {@code KernelLifecycle.onNeoClientSetup} never runs and NeoForge's client setup phase never fires.
	 *
	 * <p>Why the fix is not a rename, and why this is recorded rather than repaired: the anchor's whole value is its
	 * TIMING — it must be the last kernel hook the constructor reaches, after the block-colour table, the particle
	 * providers and {@code initClientHooks}, because mods read those fields from client setup (the injector's own
	 * javadoc records that anchoring just after {@code options} only moved the NPE to {@code font}). 1.21.1's
	 * constructor does carry one NeoForge client-loading call, {@code ClientModLoader.begin(this, repository,
	 * resourceManager)}, and it sits BEFORE {@code font} is assigned — so it is not that place, and no other call in
	 * the constructor is. Choosing the real anchor is a change to the transformer's design, not to a constant.
	 */
	@Test
	void theNeoForgeAnchorThisInjectorLooksForIsGoneOnThisAnchor() throws Exception {
		byte[] real = mergedBaseMinecraft();
		byte[] loader = TestFixtures.requireEntry(Fixture.STAGED, NEO_RUNTIME, CLIENT_MOD_LOADER_ENTRY);

		ClassNode loaderNode = new ClassNode();
		new ClassReader(loader).accept(loaderNode, 0);
		assertTrue(loaderNode.methods.stream().noneMatch(m -> "finish".equals(m.name)),
				"the anchor the injector looks for: 1.21.1's ClientModLoader has no finish()");

		InsnList ctor = constructor(real);
		int begin = indexOf(ctor, insn -> insn instanceof MethodInsnNode call
				&& NEO_CLIENT_MOD_LOADER.equals(call.owner) && "begin".equals(call.name));
		String minecraftInternal = MINECRAFT.replace('.', '/');
		int font = indexOf(ctor, insn -> insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
				&& minecraftInternal.equals(field.owner) && "font".equals(field.name));
		assertTrue(begin >= 0, "the constructor's NeoForge client-loading call is ClientModLoader.begin(...)");
		assertTrue(font >= 0, "…and the constructor still assigns font");
		assertTrue(begin < font, "begin runs BEFORE font, so it cannot stand in for finish's timing");

		assertFalse(landmarks(bothInjectors(real, true)).contains(NEO_HOOK),
				"with no finish() to anchor on, the NeoForge client-setup hook is not inserted — the gap this test "
						+ "records");
	}

	/**
	 * And the ordering logic itself still holds, on the injector's own anchor shape: whatever order the two
	 * transformers are registered in, the landmarks come out the same. A synthetic constructor is the only place
	 * that shape exists on this anchor.
	 */
	@Test
	void theOrderDoesNotDependOnWhichInjectorRunsFirst() {
		assertOrder(bothInjectors(syntheticMinecraft(), true));
		assertOrder(bothInjectors(syntheticMinecraft(), false));
	}

	@Test
	void onASyntheticConstructorTheWindowsAlsoLandCorrectly() {
		assertOrder(bothInjectors(syntheticMinecraft(), true));
	}

	/** The Fabric hook goes before the FIRST new Options — a later one would be past the window. */
	@Test
	void theFabricHookPrecedesTheFirstOptionsAllocation() {
		List<String> marks = landmarks(new ClientEntrypointHookInjector()
				.transform(MINECRAFT, syntheticMinecraft(), ctx()));

		assertEquals(FABRIC_HOOK, marks.get(0));
		assertEquals("NEW " + OPTIONS, marks.get(1), "the hook must precede the first Options allocation");
	}

	/**
	 * With no {@code ClientModLoader.finish} present the Neo injector must not silently no-op: it warns and returns
	 * as-is. This is also the path 1.21.1's real constructor takes, which is the point of the test above.
	 */
	@Test
	void aConstructorWithoutTheNeoAnchorIsReturnedUnchanged() {
		byte[] in = minecraftWithoutNeoAnchor();
		assertSame(in, new NeoClientSetupHookInjector().transform(MINECRAFT, in, ctx()),
				"nothing to anchor to — the injector returns the input and warns");
	}

	@Test
	void neitherInjectorTouchesAnyOtherClass() {
		byte[] in = syntheticMinecraft();
		assertSame(in, new ClientEntrypointHookInjector().transform("net.example.Other", in, ctx()));
		assertSame(in, new NeoClientSetupHookInjector().transform("net.example.Other", in, ctx()));
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	private static void assertOrder(byte[] out) {
		List<String> marks = landmarks(out);
		int fabric = marks.indexOf(FABRIC_HOOK);
		int options = marks.indexOf("NEW " + OPTIONS);
		int neo = marks.indexOf(NEO_HOOK);

		assertTrue(fabric >= 0, "the Fabric client-entrypoint hook was never inserted");
		assertTrue(options >= 0, "the Options allocation anchor is gone from Minecraft.<init>");
		assertTrue(neo >= 0, "the NeoForge client-setup hook was never inserted");

		assertTrue(fabric < options, "Fabric entrypoints must fire while options is still null "
				+ "(keymapping registration rejects a built Options) — got " + marks);
		assertTrue(options < neo, "NeoForge client setup must fire after options exists "
				+ "(JourneyMap validates against options.renderDistance()) — got " + marks);
	}

	/** The landmark instructions of {@code <init>}, in bytecode order. */
	private static List<String> landmarks(byte[] classBytes) {
		List<String> marks = new ArrayList<>();
		for (AbstractInsnNode insn : constructor(classBytes).toArray()) {
			if (insn instanceof TypeInsnNode type && insn.getOpcode() == Opcodes.NEW
					&& OPTIONS.equals(type.desc) && !marks.contains("NEW " + OPTIONS)) {
				marks.add("NEW " + OPTIONS);
			} else if (insn instanceof MethodInsnNode call
					&& isFabricHook(call) && !marks.contains(FABRIC_HOOK)) {
				marks.add(FABRIC_HOOK);
			} else if (insn instanceof MethodInsnNode call
					&& NEO_HOOK.equals(call.name) && !marks.contains(call.name)) {
				marks.add(call.name);
			}
		}
		return marks;
	}

	/** The instructions of the first {@code <init>} of {@code Minecraft}. */
	private static InsnList constructor(byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		for (MethodNode method : node.methods) {
			if ("<init>".equals(method.name)) return method.instructions;
		}
		throw new AssertionError("no <init> in " + MINECRAFT);
	}

	private static int indexOf(InsnList instructions, java.util.function.Predicate<AbstractInsnNode> match) {
		int index = 0;
		for (AbstractInsnNode insn : instructions) {
			if (match.test(insn)) return index;
			index++;
		}
		return -1;
	}

	private static boolean isFabricHook(MethodInsnNode call) {
		return ("net/fabricmc/loader/impl/game/minecraft/Hooks".equals(call.owner) && "startClient".equals(call.name))
				|| ("net/neoforbric/kernel/boot/KernelLifecycle".equals(call.owner) && "onClientEntrypoints".equals(call.name));
	}

	private static byte[] bothInjectors(byte[] in, boolean fabricFirst) {
		ClassTransformer a = fabricFirst ? new ClientEntrypointHookInjector() : new NeoClientSetupHookInjector();
		ClassTransformer b = fabricFirst ? new NeoClientSetupHookInjector() : new ClientEntrypointHookInjector();
		return b.transform(MINECRAFT, a.transform(MINECRAFT, in, ctx()), ctx());
	}

	private static TransformContext ctx() {
		return new TransformContext(EnvType.CLIENT, false, "intermediary");
	}

	private static byte[] mergedBaseMinecraft() {
		return TestFixtures.requireEntry(Fixture.STAGED, MERGED_BASE, "net/minecraft/client/Minecraft.class");
	}

	/**
	 * {@code <init>} in the injector's reviewed shape: assign the singleton, build {@code Options}, then
	 * {@code ClientModLoader.finish()}. The real 1.21.1 class has no such method (see
	 * {@link #theNeoForgeAnchorThisInjectorLooksForIsGoneOnThisAnchor}); this stand-in exists so the ordering logic
	 * the injector implements stays covered.
	 */
	private static byte[] syntheticMinecraft() {
		return minecraft(true);
	}

	private static byte[] minecraftWithoutNeoAnchor() {
		return minecraft(false);
	}

	private static byte[] minecraft(boolean withNeoAnchor) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "net/minecraft/client/Minecraft", null, "java/lang/Object", null);

		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		// options = new Options()
		mv.visitTypeInsn(Opcodes.NEW, OPTIONS);
		mv.visitInsn(Opcodes.DUP);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, OPTIONS, "<init>", "()V", false);
		mv.visitInsn(Opcodes.POP);
		if (withNeoAnchor) {
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, NEO_CLIENT_MOD_LOADER, "finish", "()V", false);
		}
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}
}

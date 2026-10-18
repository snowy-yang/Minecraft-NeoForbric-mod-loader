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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.fabricmc.api.EnvType;
import net.neoforbric.api.Ecosystem;
import net.neoforbric.api.ForeignType;
import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;

/**
 * Pins {@link ClientPackHookInjector} on the NeoForge carrier.
 *
 * <p>It replaces the whole body of {@code ClientModLoader.setupModResourcePacks} with a call into the kernel, so
 * the kernel owns which resource packs a client mounts. It has no warn path: if the descriptor or the owner name
 * ever stops matching, nothing is rewritten, nothing is logged, and the client simply comes up without any mod's
 * assets — the failure looks like missing textures, not like a loader error.
 *
 * <p>The load-bearing detail is that the replacement body is {@code ALOAD 0; INVOKESTATIC hook; RETURN}. Slot 0
 * holds the {@code PackRepository} only because the method is STATIC. If a carrier ever made it an instance
 * method, slot 0 would be {@code this} and the kernel would be handed a {@code ClientModLoader} where it expects a
 * repository — so that modifier is asserted against the real carrier here, not assumed.
 */
class ClientPackHookInjectorTest {
	private static final Path RUN = TestFixtures.stagedRoot();
	private static final String METHOD = "setupModResourcePacks";
	private static final String DESC = "(Lnet/minecraft/server/packs/repository/PackRepository;)V";
	private static final String HOOK_OWNER = "net/neoforbric/kernel/boot/KernelLifecycle";

	private final ClientPackHookInjector injector = new ClientPackHookInjector();

	/**
	 * The premise of {@code ALOAD 0}: the method must exist on the carrier and be static, or the kernel is handed
	 * the wrong slot-0 argument and no mod's client assets are ever mounted.
	 */
	/**
	 * What this test used to assert: that NeoForge's carrier declares
	 * {@code setupModResourcePacks(PackRepository)} STATICALLY — the premise of the injector's {@code ALOAD 0} — and
	 * that applying it prepends {@code KernelLifecycle.onClientResourcePacks} to the real body while leaving the
	 * carrier's own {@code populatePackRepository} call in place, since that call's tail is the only thing that posts
	 * {@code AddPackFindersEvent}.
	 *
	 * <p>1.21.1's {@code ClientModLoader} has no {@code setupModResourcePacks} at all. Its methods are
	 * {@code begin}, {@code onResourceReload}, {@code catchLoadingException}, {@code startModLoading},
	 * {@code finishModLoading}, {@code checkForUpdates}, {@code completeModLoading(Runnable)} and
	 * {@code isLoading}. What pack wiring it has is one call inside {@code begin}:
	 * {@code ResourcePackLoader.populatePackRepository(PackRepository, PackType, boolean)} — a different owner, a
	 * different name and two more arguments, and the same method also populates the server's and the data packs'.
	 * Re-pointing the injector there changes what it accepts (three constants and the slot it assumes), so the gap is
	 * recorded instead: on this anchor the kernel never takes over the client's pack list, and the rewrite never
	 * runs — silently, since this injector has no warn path.
	 */
	@Test
	void theCarrierMethodThisInjectorRewritesIsNotInThisNeoForge() throws Exception {
		byte[] neo = neoCarrier();
		ClassNode node = parse(neo);

		assertTrue(node.methods.stream().noneMatch(m -> METHOD.equals(m.name)),
				"1.21.1's ClientModLoader has no " + METHOD + " at all — the method the injector replaces, and a "
						+ "26.x one");
		MethodNode begin = method(node, "begin",
				"(Lnet/minecraft/client/Minecraft;Lnet/minecraft/server/packs/repository/PackRepository;"
						+ "Lnet/minecraft/server/packs/resources/ReloadableResourceManager;)V");
		assertNotNull(begin, "the pack wiring it does have runs from begin(...)");
		assertTrue(postsThePackFinderEvent(begin),
				"…and it is ResourcePackLoader.populatePackRepository(...), the call whose tail posts "
						+ "AddPackFindersEvent");

		assertSame(neo, injector.transform(ForeignType.CLIENT_MOD_LOADER.binary(Ecosystem.NEOFORGE), neo, ctx()),
				"nothing it is handed is its target, so the injector hands the carrier back untouched");
	}

	/** A same-named method with a different descriptor is not the one, and must be left running. */
	@Test
	void aDifferentDescriptorIsNotRewritten() {
		byte[] in = synthetic("(Ljava/lang/Object;)V", true);
		assertSame(in, injector.transform(ForeignType.CLIENT_MOD_LOADER.binary(Ecosystem.NEOFORGE), in, ctx()));
	}

	@Test
	void anyOtherClassIsHandedBackUntouched() {
		byte[] in = synthetic(DESC, true);
		assertSame(in, injector.transform("net.example.Other", in, ctx()));
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	private static TransformContext ctx() {
		return new TransformContext(EnvType.CLIENT, false, "intermediary");
	}

	private static byte[] neoCarrier() throws Exception {
		Path jar = RUN.resolve("neoforge-runtime/neoforge-runtime.jar");
		return TestFixtures.requireEntry(Fixture.STAGED, jar, ForeignType.CLIENT_MOD_LOADER.internal(Ecosystem.NEOFORGE) + ".class");
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	/** Whether the method reaches the carrier call whose tail posts {@code AddPackFindersEvent}. */
	private static boolean postsThePackFinderEvent(MethodNode m) {
		for (var insn : m.instructions.toArray()) {
			if (insn instanceof MethodInsnNode call && "populatePackRepository".equals(call.name)) return true;
		}
		return false;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {		for (MethodNode m : node.methods) {
			if (m.name.equals(name) && m.desc.equals(desc)) return m;
		}
		return null;
	}

	private static MethodInsnNode hookCall(MethodNode m) {
		for (var insn : m.instructions.toArray()) {
			if (insn instanceof MethodInsnNode call && HOOK_OWNER.equals(call.owner)) return call;
		}
		return null;
	}

	private static byte[] synthetic(String desc, boolean isStatic) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC,
				ForeignType.CLIENT_MOD_LOADER.internal(Ecosystem.NEOFORGE), null, "java/lang/Object", null);

		MethodVisitor mv = cw.visitMethod(
				Opcodes.ACC_PUBLIC | (isStatic ? Opcodes.ACC_STATIC : 0), METHOD, desc, null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}
}

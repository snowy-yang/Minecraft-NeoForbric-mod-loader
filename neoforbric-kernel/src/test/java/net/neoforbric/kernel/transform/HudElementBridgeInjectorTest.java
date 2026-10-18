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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;

/**
 * Verifies in real {@code neoforge-runtime.jar} bytecode that the injector routes NeoForge's layer manager at the
 * seam 1.21.1 actually offers.
 *
 * <p>Re-anchored: the class used to assert the seam was {@code add(Identifier, GuiLayer[, BooleanSupplier])}. On this
 * anchor there is no {@code net.neoforged.neoforge.client.gui.GuiLayer} and no {@code Identifier}: the overload is
 * {@code add(ResourceLocation, LayeredDraw$Layer)}, so the 26.x descriptors matched nothing, the injector returned
 * every class untouched and warned "the seam has drifted" on every launch. The injector now matches the real
 * overload and the shapes are pinned here so the next drift is visible.
 *
 * <p>Routing it is correct even though fabric-api 0.116.17 ships no HUD element registry: its whole HUD API is
 * {@code HudRenderCallback}, dispatched by {@code InGameHudMixin}'s {@code @Inject(at = @At("TAIL"))} into
 * {@code Gui.render}, which the merge cannot break. {@code KernelHudBridge.wrap} therefore returns every layer by
 * identity here — the transformer hands the layer over, the bridge decides there is nothing to bridge.
 */
class HudElementBridgeInjectorTest {
	private static final Path NEOFORGE_RUNTIME = TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar");

	private static final String TARGET = "net.neoforged.neoforge.client.gui.GuiLayerManager";
	private static final String ENTRY = "net/neoforged/neoforge/client/gui/GuiLayerManager.class";
	private static final String LAYER = "Lnet/minecraft/client/gui/LayeredDraw$Layer;";
	private static final String HOOK = "net/neoforbric/kernel/boot/KernelHudBridge";

	@Test
	void theRealSeamIsLayeredDrawAndTheInjectorRoutesIt() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(NEOFORGE_RUNTIME), "staged neoforge-runtime.jar absent");
		byte[] before = readClass();
		ClassNode node = parse(before);

		List<String> adds = node.methods.stream().filter(m -> "add".equals(m.name)).map(m -> m.desc).toList();
		assertTrue(adds.contains("(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/client/gui/LayeredDraw$Layer;)"
						+ "Lnet/neoforged/neoforge/client/gui/GuiLayerManager;"),
				"the two-arg overload's shape moved — re-derive the seam the injector has to match: " + adds);
		assertTrue(adds.contains("(Lnet/neoforged/neoforge/client/gui/GuiLayerManager;Ljava/util/function/BooleanSupplier;)"
						+ "Lnet/neoforged/neoforge/client/gui/GuiLayerManager;"),
				"the sub-manager overload's shape moved: " + adds);
		assertTrue(adds.stream().noneMatch(desc -> desc.contains("Lnet/minecraft/resources/Identifier;")
						|| desc.contains("Lnet/neoforged/neoforge/client/gui/GuiLayer;")),
				"the 26.x GuiLayer/Identifier seam is back — re-derive the injector against it: " + adds);

		byte[] after = new HudElementBridgeInjector().transform(TARGET, before, null);
		assertNotSame(before, after, "the layer-taking overload matches, so the class must be rewritten");

		int hits = 0;
		for (MethodNode m : parse(after).methods) {
			if (!"add".equals(m.name) || !callsHook(m)) continue;
			hits++;
			assertPrologue(m);
		}
		assertEquals(1, hits, "exactly add(ResourceLocation, LayeredDraw$Layer) carries the wrap");
	}

	@Test
	void leavesTheDelegatingOverloadsAlone() throws Exception {
		ClassNode node = transformed();

		for (MethodNode m : node.methods) {
			if (!"add".equals(m.name)) continue;
			Type[] args = Type.getArgumentTypes(m.desc);
			if (args.length >= 2 && LAYER.equals(args[1].getDescriptor())) continue;

			// The Consumer overload and add(GuiLayerManager, BooleanSupplier) both funnel into the 3-arg one, so
			// touching them here would wrap twice.
			assertTrue(!callsHook(m), "add" + m.desc + " delegates and must not be routed a second time");
		}
	}

	@Test
	void transformedMethodsAnalyseCleanly() throws Exception {
		ClassNode node = transformed();
		for (MethodNode m : node.methods) {
			new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
		}
	}

	@Test
	void isIdempotent() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(NEOFORGE_RUNTIME), "staged neoforge-runtime.jar absent");

		byte[] once = new HudElementBridgeInjector().transform(TARGET, readClass(), null);
		assertSame(once, new HudElementBridgeInjector().transform(TARGET, once, null),
				"an already-routed GuiLayerManager must be passed straight through");
	}

	@Test
	void unrelatedClassesArePassedThrough() {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/Unrelated", null, "java/lang/Object", null);
		writer.visitEnd();
		byte[] bytes = writer.toByteArray();
		assertSame(bytes, new HudElementBridgeInjector().transform("com.example.Unrelated", bytes, null));
	}

	// --- helpers -------------------------------------------------------------------------------------------------

	private static ClassNode transformed() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(NEOFORGE_RUNTIME), "staged neoforge-runtime.jar absent");

		return parse(new HudElementBridgeInjector().transform(TARGET, readClass(), null));
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	/** ALOAD 1; ALOAD 2; INVOKESTATIC wrap; CHECKCAST LayeredDraw$Layer; ASTORE 2 — and it must come FIRST. */
	private static void assertPrologue(MethodNode m) {
		AbstractInsnNode insn = m.instructions.getFirst();
		while (insn != null && insn.getOpcode() == -1) insn = insn.getNext();

		assertEquals(Opcodes.ALOAD, insn.getOpcode());
		insn = next(insn);
		assertEquals(Opcodes.ALOAD, insn.getOpcode());
		insn = next(insn);
		assertEquals(Opcodes.INVOKESTATIC, insn.getOpcode());
		assertEquals(HOOK, ((MethodInsnNode) insn).owner);
		insn = next(insn);
		assertEquals(Opcodes.CHECKCAST, insn.getOpcode());
		insn = next(insn);
		assertEquals(Opcodes.ASTORE, insn.getOpcode());
	}

	private static AbstractInsnNode next(AbstractInsnNode insn) {
		AbstractInsnNode n = insn.getNext();
		while (n != null && n.getOpcode() == -1) n = n.getNext();
		assertNotNull(n);
		return n;
	}

	private static boolean callsHook(MethodNode m) {
		for (AbstractInsnNode insn : m.instructions) {
			if (insn instanceof MethodInsnNode call && HOOK.equals(call.owner) && "wrap".equals(call.name)) return true;
		}
		return false;
	}

	private static byte[] readClass() throws Exception {
		try (ZipFile zip = new ZipFile(NEOFORGE_RUNTIME.toFile())) {
			ZipEntry found = zip.getEntry(ENTRY);
			assertNotNull(found, ENTRY + " missing from the staged neoforge-runtime.jar");
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}
}

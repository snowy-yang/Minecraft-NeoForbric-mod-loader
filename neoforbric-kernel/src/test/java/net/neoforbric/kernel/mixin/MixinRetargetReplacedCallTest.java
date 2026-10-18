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

package net.neoforbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipFile;

import net.neoforbric.api.Ecosystem;
import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * R7's row on the real merged {@code StructureTemplate}, and the miss a guest injector written for vanilla's
 * {@code placeEntities} reads there.
 *
 * <p>What used to be asserted here is R7's <em>move</em>: MoogsStructureLib's Fabric {@code EntityProcessorMixin}, as
 * compiled, records its context before vanilla's {@code placeEntities} call, clears it after, and places processed
 * entities at the HEAD of {@code placeEntities}, cancelling — and R7 moved all three points onto NeoForge's
 * {@code addEntitiesToWorld}. That fixture needed a carrier that keeps vanilla's name with a changed shape: on the
 * MinecraftForge base the HEAD selector bound a reshaped {@code placeEntities}, which is what the row's message named
 * ("the name binds placeEntities&lt;shape&gt;, which the handler was not written for"). 1.21.1's NeoForge base has no
 * {@code placeEntities} at all, the name binds nothing, {@code MixinRetarget} finds no point to move, and the plan is
 * empty — the row, its ecosystems and the miss are what is guarded here. The move itself, and with it the adapter's
 * {@code adopt} of an UNFIT mixin's rewrite, cannot be exercised until a carrier leaves a same-named method behind.
 */
@ResourceLock("system-properties")
class MixinRetargetReplacedCallTest {
	private static final String TEMPLATE = "net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate";
	private static final String MIXIN = "test/EntityProcessorMixin";
	private static final MergedBaseCalleeSwaps.Replaced ROW = MergedBaseCalleeSwaps.REPLACED.getFirst();
	private static final String CALLBACK = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
	private static final String CALLBACK_RETURNABLE = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";

	@AfterEach void reset() {
		System.clearProperty(MixinRetarget.REPLACED_CALL_PROPERTY);
		MixinRetarget.reset();
		MixinStubRebind.forget();
	}

	private static Function<String, byte[]> merged() throws Exception {
		Path jar = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(jar), "staged merged base required");
		byte[] template;
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			template = zip.getInputStream(zip.getEntry(TEMPLATE + ".class")).readAllBytes();
		}
		return name -> name.equals(TEMPLATE + ".class") ? template : null;
	}

	@Test void theRowsVanillaMemberIsGoneAndItsReplacementIsThere() throws Exception {
		ClassNode template = MixinFit.parse(merged().apply(TEMPLATE + ".class"));
		assertTrue(template.methods.stream().noneMatch(m -> m.name.equals("placeEntities")),
				"vanilla's placeEntities is gone: " + ROW.because());
		// By name only: the row's descriptor is NeoForge's 26.x one (its addEntitiesToWorld takes a ProblemReporter),
		// and this base's does not. Reported with the class, not asserted as this base's shape.
		String replacement = ROW.replacement().substring(0, ROW.replacement().indexOf('('));
		assertTrue(template.methods.stream().anyMatch(m -> m.name.equals(replacement)),
				"the replacement the row names is declared: " + ROW.replacement());
		assertTrue(template.methods.stream().anyMatch(m -> ROW.caller().equals(m.name + m.desc)),
				"the caller the row names is declared: " + ROW.caller());
	}

	/** The row answers its own ecosystems only: a NeoForge mod was compiled against addEntitiesToWorld already. */
	@Test void theRowAnswersOnlyItsOwnEcosystem() {
		String vanilla = ROW.vanilla();
		String name = vanilla.substring(0, vanilla.indexOf('(')), desc = vanilla.substring(vanilla.indexOf('('));
		assertSame(ROW, MergedBaseCalleeSwaps.replaced(TEMPLATE, name, desc, Ecosystem.FABRIC));
		assertNull(MergedBaseCalleeSwaps.replaced(TEMPLATE, name, desc, Ecosystem.NEOFORGE));
		assertNull(MergedBaseCalleeSwaps.replaced(TEMPLATE, "placeInWorld", null, Ecosystem.FABRIC));
	}

	/**
	 * The miss a guest injector written for vanilla's member reads: named, but not moved — on this base the name binds
	 * nothing, so there is no point for R7 to carry to the replacement.
	 */
	@Test void aGuestInjectorWrittenForTheVanillaMemberIsNamedAsAMiss() throws Exception {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		Function<String, byte[]> resolver = merged();
		MixinFit.Result before = MixinFit.evaluate(moogsShaped(), resolver);
		assertEquals(MixinFit.Verdict.PARTIAL, before.verdict(), before.reason());
		List<String> misses = before.unresolved().stream().distinct().toList();
		assertTrue(misses.stream().anyMatch(u -> u.contains("@At(INVOKE) StructureTemplate.placeEntities in placeInWorld")),
				misses.toString());
		assertTrue(misses.stream().anyMatch(u -> u.contains("@Inject target StructureTemplate.placeEntities")), misses.toString());
		assertTrue(MixinRetarget.plan(MixinFit.parse(moogsShaped()), resolver).isEmpty(),
				"nothing to move: the name binds no method in the merged base");
	}

	/** RED control: with R7 off the plan is empty and the verdict keeps both misses. */
	@Test void withTheRuleOffNothingMoves() throws Exception {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		System.setProperty(MixinRetarget.REPLACED_CALL_PROPERTY, "off");
		Function<String, byte[]> resolver = merged();
		assertTrue(MixinRetarget.plan(MixinFit.parse(moogsShaped()), resolver).isEmpty());
		assertEquals(MixinFit.Verdict.PARTIAL, MixinFit.evaluate(moogsShaped(), resolver).verdict());
	}

	/** {@code mixinBytes} plus a HEAD injector whose name binds placeInWorld, which its handler was not written for. */
	private static byte[] withRefusedInjector(byte[] mixinBytes) {
		ClassNode mixin = MixinFit.parse(mixinBytes);
		mixin.methods.add(handler("onPlaceInWorld", "(Ljava/lang/String;" + CALLBACK + ")V", "placeInWorld",
				point("HEAD", null, null), false));
		ClassWriter writer = new ClassWriter(0);
		mixin.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * The body a rewrite renames aside is merged into the target as a plain method, by name: two mixins on one target
	 * whose handlers share a name must not land on one name, or Mixin keeps one body ("Method overwrite conflict").
	 * Used to assert it on two plans' asides; with no move to make on this base the name derivation itself is asserted.
	 */
	@Test void twoMixinsWithOneHandlerNameGetTwoAsideNames() {
		assertNotEquals(MixinHandlerShim.asideName(MIXIN, "processAndPlaceEntities", MixinRetarget.PROJECTED_SUFFIX),
				MixinHandlerShim.asideName("test/OtherEntityProcessorMixin", "processAndPlaceEntities", MixinRetarget.PROJECTED_SUFFIX));
	}

	/** EntityProcessorMixin with its HEAD injector only. */
	private static byte[] headOnly() {
		ClassNode mixin = MixinFit.parse(moogsShaped());
		mixin.methods.removeIf(m -> !m.name.equals("processAndPlaceEntities"));
		ClassWriter writer = new ClassWriter(0);
		mixin.accept(writer);
		return writer.toByteArray();
	}

	/** A HEAD handler that captures something else than vanilla's arguments is not one the projection can serve. */
	@Test void aHandlerNotWrittenForVanillasArgumentsIsLeftAlone() throws Exception {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		ClassNode mixin = MixinFit.parse(moogsShaped());
		MethodNode head = mixin.methods.stream().filter(m -> m.name.equals("processAndPlaceEntities")).findFirst().orElseThrow();
		head.desc = "(Lnet/minecraft/world/level/ServerLevelAccessor;" + CALLBACK + ")V";
		MixinRetarget.Plan plan = MixinRetarget.plan(mixin, merged());
		assertTrue(plan.rewrites().stream().noneMatch(r -> r.element() == MixinRetarget.Element.PROJECT), plan.describe());
	}

	private static Type[] append(Type[] types, Type last) {
		Type[] out = java.util.Arrays.copyOf(types, types.length + 1);
		out[types.length] = last;
		return out;
	}

	/** EntityProcessorMixin's three injectors, as compiled against vanilla. */
	private static byte[] moogsShaped() {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.access = Opcodes.ACC_PUBLIC;
		mixin.name = MIXIN;
		mixin.superName = "java/lang/Object";
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(TEMPLATE)))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(type));
		String caller = ROW.caller();
		String around = "(" + caller.substring(caller.indexOf('(') + 1, caller.indexOf(')')) + CALLBACK_RETURNABLE + ")V";
		mixin.methods.add(handler("captureContext", around, "placeInWorld", point("INVOKE", ROW.vanillaMember(), null), false));
		mixin.methods.add(handler("clearContext", around, "placeInWorld", point("INVOKE", ROW.vanillaMember(), "AFTER"), false));
		String vanilla = ROW.vanilla().substring(ROW.vanilla().indexOf('('));
		mixin.methods.add(handler("processAndPlaceEntities", Type.getMethodDescriptor(Type.VOID_TYPE,
				append(Type.getArgumentTypes(vanilla), Type.getType(CALLBACK))), "placeEntities", point("HEAD", null, null), true));
		ClassWriter writer = new ClassWriter(0);
		mixin.accept(writer);
		return writer.toByteArray();
	}

	private static AnnotationNode point(String value, String target, String shift) {
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", value));
		if (target != null) at.values.addAll(List.of("target", target));
		if (shift != null) at.values.addAll(List.of("shift", new String[] {"Lorg/spongepowered/asm/mixin/injection/At$Shift;", shift}));
		return at;
	}

	private static MethodNode handler(String name, String desc, String selector, AnnotationNode at, boolean cancellable) {
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(selector)), "at", new ArrayList<>(List.of(at))));
		if (cancellable) inject.values.addAll(List.of("cancellable", true));
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, name, desc, null, null);
		handler.visibleAnnotations = new ArrayList<>(List.of(inject));
		handler.instructions.add(new InsnNode(Opcodes.RETURN));
		handler.maxLocals = Type.getArgumentsAndReturnSizes(desc) >> 2;
		return handler;
	}
}

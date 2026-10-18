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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Holds the injection-point widening to the cases where moving a point cannot break the handler.
 *
 * <p>The merged base declares vanilla's {@code CustomPacketPayload.codec(FallbackProvider, List)} beside
 * NeoForge's four-argument one and calls only the long one. Polymer's {@code @ModifyExpressionValue} names the
 * short signature, matched nothing, and its payload codec patch never landed — so {@code polymer:hello} went out
 * with the unknown-id fallback codec and the client was disconnected at world join.
 *
 * <p>The restriction is the part that was learned the hard way. Written without it, this moved
 * fabric-networking's own {@code @WrapOperation} on the same call, whose handler mirrors the call's arguments —
 * Mixin then rejected the handler outright and the mixin stopped applying at all. A handler that describes the
 * call must never be pointed at a different call.
 */
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class MixinAtWidenedCallTest {
	private static final String OWNER = "net/minecraft/network/protocol/common/custom/CustomPacketPayload";
	private static final String SHORT = "L" + OWNER + ";codec(Ljava/util/List;)Lnet/minecraft/network/codec/StreamCodec;";
	private static final String LONG_DESC = "(Ljava/util/List;Lnet/minecraft/network/protocol/PacketFlow;)"
			+ "Lnet/minecraft/network/codec/StreamCodec;";
	private static final String MOVED = "L" + OWNER + ";codec" + LONG_DESC;

	@Test
	void anInjectionPointIsMovedToTheCallTheCarrierLengthened() {
		ClassNode mixin = mixin("Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;");

		assertEquals(1, MixinAtWidenedCall.widen(mixin, name -> targetClass(LONG_DESC)));
		assertEquals(MOVED, atTarget(mixin));
	}

	@Test void aSingleModifiedArgumentAtAnExplicitIndexSurvivesAppendedParameters() {
		ClassNode mixin = mixin("Lorg/spongepowered/asm/mixin/injection/ModifyArg;");
		MethodNode handler = mixin.methods.getFirst();
		handler.desc = "(Ljava/util/List;)Ljava/util/List;";
		handler.visibleAnnotations.getFirst().values.addAll(List.of("index", 0));
		assertEquals(1, MixinAtWidenedCall.widen(mixin, name -> targetClass(LONG_DESC)));
		assertEquals(MOVED, atTarget(mixin));
	}

	@Test void aModifyArgThatCapturesAllParametersOrInfersItsIndexRemainsUntouched() {
		for (String desc : List.of("(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;", "(Ljava/util/List;)Z")) {
			ClassNode mixin = mixin("Lorg/spongepowered/asm/mixin/injection/ModifyArg;");
			mixin.methods.getFirst().desc = desc;
			mixin.methods.getFirst().visibleAnnotations.getFirst().values.addAll(List.of("index", 0));
			assertEquals(0, MixinAtWidenedCall.widen(mixin, name -> targetClass(LONG_DESC)));
		}
		ClassNode mixin = mixin("Lorg/spongepowered/asm/mixin/injection/ModifyArg;");
		mixin.methods.getFirst().desc = "(Ljava/util/List;)Ljava/util/List;";
		assertEquals(0, MixinAtWidenedCall.widen(mixin, name -> targetClass(LONG_DESC)));
	}

	@Test void fixedIndexMustNameAnOriginalArgumentAndGroupsStillDoNotMove() {
		for (int index : List.of(-1, 1)) {
			ClassNode mixin = mixin("Lorg/spongepowered/asm/mixin/injection/ModifyArg;");
			mixin.methods.getFirst().desc = "(Ljava/util/List;)Ljava/util/List;";
			mixin.methods.getFirst().visibleAnnotations.getFirst().values.addAll(List.of("index", index));
			assertEquals(0, MixinAtWidenedCall.widen(mixin, name -> targetClass(LONG_DESC)));
		}
		ClassNode mixin = mixin("Lorg/spongepowered/asm/mixin/injection/ModifyArg;");
		mixin.methods.getFirst().desc = "(Ljava/util/List;)Ljava/util/List;";
		mixin.methods.getFirst().visibleAnnotations.getFirst().values.addAll(List.of("index", 0));
		mixin.methods.getFirst().invisibleAnnotations = List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;"));
		assertEquals(0, MixinAtWidenedCall.widen(mixin, name -> targetClass(LONG_DESC)));
	}

	/**
	 * Used to assert that fabric-registry-sync's {@code WorldLoaderMixin} point was moved onto the loader the
	 * carrier had lengthened to {@code (…, Executor, List)}. On this base the module's mixin is
	 * {@code SaveLoadingMixin} and its {@code @At} target is the layer loader as the merged base really compiles it
	 * ({@code loadAndReplaceLayer}), so the point resolves and nothing moves: what the test asserts now is that the
	 * five-argument {@code WorldLoader.load} the mixin's handler is written against is the one the base declares.
	 */
	@Test void actualFabricRegistryListReplacementTargetsTheCurrentFiveArgumentLoader() throws Exception {
		ClassNode mixin = StagedFabricMixinFixture.resolved("fabric-registry-sync-v0",
				"net/fabricmc/fabric/mixin/registry/sync/SaveLoadingMixin");
		byte[] targetBytes = TestFixtures.stagedClass("neoforge-base/patched-mc-neoforge-1.21.1.jar",
				"net.minecraft.server.WorldLoader");
		byte[] pruned = new net.neoforbric.kernel.transform.DuplicateLambdaPruneInjector()
				.transform("net.minecraft.server.WorldLoader", targetBytes, null);
		ClassNode target = MixinFit.parse(pruned);
		assertEquals(0, MixinAtWidenedCall.widen(mixin, name -> target), "the point resolves: nothing to widen");
		MethodNode handler = mixin.methods.stream().filter(m -> m.name.equals("modifyLoadedEntries")).findFirst().orElseThrow();
		AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst();
		String member = (String) MixinFit.value(at, "target");
		org.junit.jupiter.api.Assertions.assertTrue(member.contains("loadAndReplaceLayer"), member);
		String selector = MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(handler), "method")).getFirst();
		org.junit.jupiter.api.Assertions.assertTrue(
				selector.contains("Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"), selector);
	}

	/**
	 * The restriction. {@code @WrapOperation}'s handler takes the call's own arguments, so a longer call means a
	 * handler Mixin rejects — the mixin stops applying entirely, which is worse than the point not matching.
	 */
	@Test
	void anInjectorWhoseHandlerMirrorsTheCallIsLeftAlone() {
		ClassNode mixin = mixin("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");

		assertEquals(0, MixinAtWidenedCall.widen(mixin, name -> targetClass(LONG_DESC)));
		assertEquals(SHORT, atTarget(mixin));
	}

	/** The named call really being there is the ordinary case, and it must never be rewritten. */
	@Test
	void aPointThatAlreadyResolvesIsNotMoved() {
		ClassNode mixin = mixin("Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;");

		assertEquals(0, MixinAtWidenedCall.widen(mixin,
				name -> targetClass("(Ljava/util/List;)Lnet/minecraft/network/codec/StreamCodec;")));
		assertEquals(SHORT, atTarget(mixin));
	}

	@Test
	void aDifferentReturnTypeIsADifferentMethod() {
		assertNull(MixinAtWidenedCall.widenedIn(
				body("(Ljava/util/List;Lnet/minecraft/network/protocol/PacketFlow;)Ljava/lang/Object;"), SHORT));
	}

	@Test
	void theNamedParametersMustBeAPrefix() {
		assertNull(MixinAtWidenedCall.widenedIn(
				body("(Ljava/lang/String;Ljava/util/List;)Lnet/minecraft/network/codec/StreamCodec;"), SHORT));
	}

	@Test
	void theSwitchLeavesEveryPointAsCompiled() {
		String previous = System.getProperty(MixinAtWidenedCall.PROPERTY);
		System.setProperty(MixinAtWidenedCall.PROPERTY, "off");
		try {
			ClassNode mixin = mixin("Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;");
			assertEquals(0, MixinAtWidenedCall.widen(mixin, name -> targetClass(LONG_DESC)));
			assertEquals(SHORT, atTarget(mixin));
		} finally {
			if (previous == null) System.clearProperty(MixinAtWidenedCall.PROPERTY);
			else System.setProperty(MixinAtWidenedCall.PROPERTY, previous);
		}
	}

	/**
	 * A callback group is the mod's own statement that some of its alternatives are MEANT to miss — they are the
	 * shapes other game versions have. Iris paid for this one: moving one member of a {@code max=1} group made two
	 * match, the group's check failed, and its whole LevelRenderer mixin — every shader hook in it — went with it.
	 */
	@Test
	void aHandlerInACallbackGroupIsLeftAlone() {
		ClassNode mixin = mixin("Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;");
		mixin.methods.get(0).visibleAnnotations.add(
				new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;"));

		assertEquals(0, MixinAtWidenedCall.widen(mixin, name -> targetClass(LONG_DESC)));
		assertEquals(SHORT, atTarget(mixin));
	}

	/** A mixin with one injector of {@code injectorDesc}, selecting {@code <clinit>}, pointed at the short call. */
	private static ClassNode mixin(String injectorDesc) {
		ClassNode mixin = new ClassNode();
		mixin.name = "com/example/SomeMixin";
		mixin.version = Opcodes.V21;

		AnnotationNode target = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		target.values = new ArrayList<>(List.of("value",
				new ArrayList<>(List.of(Type.getObjectType("net/example/Target")))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(target));

		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", SHORT));
		AnnotationNode injector = new AnnotationNode(injectorDesc);
		injector.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of("<clinit>")), "at", at));

		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, "handler", "()V", null, null);
		handler.visibleAnnotations = new ArrayList<>(List.of(injector));
		mixin.methods = new ArrayList<>(List.of(handler));
		return mixin;
	}

	private static final String PARTICLE_NEW = "(Lnet/minecraft/core/particles/ParticleType;Lnet/minecraft/world/level/block/state/BlockState;)"
			+ "Lnet/minecraft/core/particles/BlockParticleOption;";
	private static final String PARTICLE_WIDE = "(Lnet/minecraft/core/particles/ParticleType;Lnet/minecraft/world/level/block/state/BlockState;"
			+ "Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/particles/BlockParticleOption;";

	/** The three mixins fabric-particles-v1 0.116.17 really ships: the module's EntityMixin, LivingEntityMixin
	 * and ServerPlayerMixin — the ones the dust test below used to read — are not in it. */
	private static final List<String> PARTICLES_MIXINS = List.of(
			"net/fabricmc/fabric/mixin/client/particle/BlockDustParticleMixin",
			"net/fabricmc/fabric/mixin/client/particle/ParticleManagerAccessor",
			"net/fabricmc/fabric/mixin/client/particle/ParticleManagerMixin");

	/**
	 * Used to assert that fabric-particles' {@code EntityMixin} and {@code LivingEntityMixin} followed NeoForge's
	 * dust constructor, which took the position appended, and that MixinFit's verdict agreed. 1.21.1's
	 * {@code BlockParticleOption} has no position-appended constructor and fabric-api 0.116.17 ships none of those
	 * mixins: there is no NEW point in the module for the widening to move. What it asserts now is the module as it
	 * really is — every mixin it ships, resolved as Mixin applies it, is left exactly as compiled on the merged
	 * base, and its target is still judged (so the verdict agrees with the rewrite in moving nothing).
	 */
	@Test void fabricParticlesOwnMixinsNeedNoConstructorWidening() throws Exception {
		for (String name : PARTICLES_MIXINS) {
			ClassNode mixin = StagedFabricMixinFixture.resolved("fabric-particles-v1", name);
			assertEquals(0, MixinAtWidenedCall.widen(mixin, MixinAtWidenedCallTest::merged), name);
			MixinFit.Result verdict = MixinFit.evaluate(StagedFabricMixinFixture.bytes(mixin),
					name1 -> name1.endsWith(".class") ? mergedBytes(name1.substring(0, name1.length() - ".class".length())) : null);
			org.junit.jupiter.api.Assertions.assertTrue(verdict.total() > 0, name + " was not judged: " + verdict);
		}
	}

	/**
	 * Used to assert that fabric's own {@code ServerPlayerMixin} landing burst still built the two-argument option,
	 * so its point was left as written. That mixin is not in 0.116.17; what is asserted now is the reason the whole
	 * NEW widening has nothing to do on this base: the constructor fabric's dust points named is the only one
	 * {@code BlockParticleOption} has.
	 */
	@Test void aNamedConstructorThatIsBuiltIsNotMoved() throws Exception {
		String vanilla = "(Lnet/minecraft/core/particles/ParticleType;"
				+ "Lnet/minecraft/world/level/block/state/BlockState;)V";
		List<String> constructors = StagedFabricMixinFixture.game("net/minecraft/core/particles/BlockParticleOption", false)
				.methods.stream().filter(m -> m.name.equals("<init>")).map(m -> m.desc).toList();
		org.junit.jupiter.api.Assertions.assertTrue(constructors.contains(vanilla), constructors.toString());
		org.junit.jupiter.api.Assertions.assertFalse(
				constructors.contains(vanilla.substring(0, vanilla.length() - 2) + "Lnet/minecraft/core/BlockPos;)V"),
				"the position-appended constructor is not 1.21.1's: " + constructors);
	}

	/** A construction nested in another's arguments pairs with its own init; two widened forms are ambiguous. */
	@Test void nestedConstructionsPairAndTwoWidenedFormsDecline() {
		String t = "p/T";
		MethodNode body = new MethodNode(Opcodes.ACC_STATIC, "m", "()V", null, null);
		body.instructions.add(new TypeInsnNode(Opcodes.NEW, "p/Outer"));
		body.instructions.add(new InsnNode(Opcodes.DUP));
		body.instructions.add(new TypeInsnNode(Opcodes.NEW, t));
		body.instructions.add(new InsnNode(Opcodes.DUP));
		body.instructions.add(new InsnNode(Opcodes.ICONST_1));
		body.instructions.add(new InsnNode(Opcodes.ICONST_2));
		body.instructions.add(new InsnNode(Opcodes.ICONST_3));
		body.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, t, "<init>", "(III)V", false));
		body.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "p/Outer", "<init>", "(Lp/T;)V", false));
		body.instructions.add(new InsnNode(Opcodes.RETURN));
		assertEquals("(III)Lp/T;", MixinAtWidenedCall.widenedNewIn(body, "(II)Lp/T;"));
		body.instructions.insertBefore(body.instructions.getLast(), new TypeInsnNode(Opcodes.NEW, t));
		body.instructions.insertBefore(body.instructions.getLast(), new InsnNode(Opcodes.DUP));
		body.instructions.insertBefore(body.instructions.getLast(), new InsnNode(Opcodes.ICONST_1));
		body.instructions.insertBefore(body.instructions.getLast(), new InsnNode(Opcodes.ICONST_2));
		body.instructions.insertBefore(body.instructions.getLast(), new InsnNode(Opcodes.LCONST_0));
		body.instructions.insertBefore(body.instructions.getLast(), new MethodInsnNode(Opcodes.INVOKESPECIAL, t, "<init>", "(IIJ)V", false));
		assertNull(MixinAtWidenedCall.widenedNewIn(body, "(II)Lp/T;"), "two widened constructors");
		assertNull(MixinAtWidenedCall.widenedNewIn(body, "Lp/T;"), "a class-name NEW target is not a constructor");
	}

	/**
	 * Used to assert that MixinFit's verdict on fabric-particles' {@code EntityMixin} NEW anchors was FIT on the
	 * merged base and PARTIAL — naming the miss — with the NEW switch off. Neither that mixin nor the
	 * position-appended constructor it followed exists on this base, so there is no miss for the switch to reveal;
	 * what is asserted now is that the switch changes nothing about the module's real mixins, which is what a
	 * widening that has nothing to do must look like.
	 */
	@Test void theVerdictAgreesWithTheRewrite() throws Exception {
		String previous = System.getProperty(MixinAtWidenedCall.NEW_PROPERTY);
		System.setProperty(MixinAtWidenedCall.NEW_PROPERTY, "off");
		try {
			for (String name : PARTICLES_MIXINS) {
				ClassNode mixin = StagedFabricMixinFixture.resolved("fabric-particles-v1", name);
				assertEquals(0, MixinAtWidenedCall.widen(mixin, MixinAtWidenedCallTest::merged), name);
				MixinFit.Result verdict = MixinFit.evaluate(StagedFabricMixinFixture.bytes(mixin),
						name1 -> name1.endsWith(".class") ? mergedBytes(name1.substring(0, name1.length() - ".class".length())) : null);
				org.junit.jupiter.api.Assertions.assertTrue(verdict.total() > 0, name + " was not judged: " + verdict);
			}
		} finally {
			if (previous == null) System.clearProperty(MixinAtWidenedCall.NEW_PROPERTY);
			else System.setProperty(MixinAtWidenedCall.NEW_PROPERTY, previous);
		}
	}

	/** The merged base's own class, as a node, for a resolver. */
	private static ClassNode merged(String name) {
		byte[] bytes = mergedBytes(name);
		return bytes == null ? null : MixinFit.parse(bytes);
	}

	private static byte[] mergedBytes(String name) {
		java.nio.file.Path base = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(base.toFile())) {
			java.util.zip.ZipEntry entry = zip.getEntry(name + ".class");
			return entry == null ? null : zip.getInputStream(entry).readAllBytes();
		} catch (java.io.IOException unreadable) {
			throw new java.io.UncheckedIOException(unreadable);
		}
	}

	/**
	 * For every injector kind, MixinFit calls the widened INVOKE point resolved exactly when the rewrite will move it.
	 * It used to call it resolved for ANY kind, so a @Redirect or @WrapOperation read FIT while nothing would ever
	 * attach it.
	 */
	@Test void theInvokeVerdictAgreesWithTheRewriteForEveryInjector() throws Exception {
		String[][] kinds = {
				{ "Lorg/spongepowered/asm/mixin/injection/Inject;", "()V", null },
				{ "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;", "()V", null },
				{ "Lorg/spongepowered/asm/mixin/injection/Redirect;", "(Ljava/util/List;)Lnet/minecraft/network/codec/StreamCodec;", null },
				{ "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", "()V", null },
				{ "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;", "()V", null },
				{ "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;", "()V", null },
				{ "Lorg/spongepowered/asm/mixin/injection/ModifyArg;", "(Ljava/util/List;)Ljava/util/List;", null },
				{ "Lorg/spongepowered/asm/mixin/injection/ModifyArg;", "(Ljava/util/List;)Ljava/util/List;", "index" } };
		int moved = 0;
		for (String[] kind : kinds) {
			ClassNode mixin = mixin(kind[0]);
			mixin.methods.getFirst().desc = kind[1];
			if ("index".equals(kind[2])) mixin.methods.getFirst().visibleAnnotations.getFirst().values.addAll(List.of("index", 0));
			if ("group".equals(kind[2])) mixin.methods.getFirst().visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;"));
			byte[] target = bytes(targetClass(LONG_DESC));
			MixinFit.Result verdict = MixinFit.evaluate(bytes(mixin), name -> name.equals("net/example/Target.class") ? target : null);
			int widened = MixinAtWidenedCall.widen(mixin, name -> targetClass(LONG_DESC));
			moved += widened;
			assertEquals(widened == 1 ? MixinFit.Verdict.FIT : MixinFit.Verdict.PARTIAL, verdict.verdict(),
					java.util.Arrays.toString(kind) + ": " + verdict.reason());
		}
		assertEquals(3, moved, "@Inject, @ModifyExpressionValue and the fixed-index @ModifyArg");

		// The switch: a widened call reads as resolved for any kind again, the @Redirect included.
		System.setProperty(MixinFit.ANCHOR_MOVERS_PROPERTY, "off");
		try {
			ClassNode redirect = mixin("Lorg/spongepowered/asm/mixin/injection/Redirect;");
			redirect.methods.getFirst().desc = "(Ljava/util/List;)Lnet/minecraft/network/codec/StreamCodec;";
			byte[] target = bytes(targetClass(LONG_DESC));
			assertEquals(MixinFit.Verdict.FIT, MixinFit.evaluate(bytes(redirect), name -> name.equals("net/example/Target.class") ? target : null).verdict());
		} finally {
			System.clearProperty(MixinFit.ANCHOR_MOVERS_PROPERTY);
		}
	}

	/**
	 * A @Group alternative is never moved, and is still judged by whether its call is there in widened form: Iris's
	 * addMainPass group names vanilla's six-argument call beside NeoForge's seven-argument one, and the group — not
	 * that one point — has to hit. Judged by the rewrite, the satisfied group would read as a miss.
	 */
	@Test void aGroupAlternativeOnAWidenedCallIsJudgedAsAnAlternative() throws Exception {
		ClassNode mixin = mixin("Lorg/spongepowered/asm/mixin/injection/Inject;");
		mixin.methods.getFirst().visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;"));
		byte[] target = bytes(targetClass(LONG_DESC));
		assertEquals(MixinFit.Verdict.FIT, MixinFit.evaluate(bytes(mixin), name -> name.equals("net/example/Target.class") ? target : null).verdict());
		assertEquals(0, MixinAtWidenedCall.widen(mixin, name -> targetClass(LONG_DESC)), "and it is not moved");
	}

	/**
	 * creativecore's shape: a @Redirect of RegistryFriendlyByteBuf.decorator(RegistryAccess), which NeoForge's
	 * configuration listener calls with a ConnectionType appended. A static call on a REDIRECTABLE row: the point moves to
	 * the widened call and the handler is wrapped to take its arguments, so the verdict reads FIT. Before the row it read
	 * PARTIAL and the required redirect stopped a strict launch; -Dneoforbric.mixinAtWidenRedirect=off is that, still.
	 */
	@Test void aRedirectOfAReviewedWidenedStaticCallMovesThroughAWrapper() {
		byte[] targetBytes = bytes(listener());
		MixinFit.Result verdict = MixinFit.evaluate(bytes(creativecoreShaped()), name -> name.equals(LISTENER + ".class") ? targetBytes : null);
		assertEquals(MixinFit.Verdict.FIT, verdict.verdict(), verdict.reason());
		ClassNode mixin = creativecoreShaped();
		assertEquals(1, MixinAtWidenedCall.widen(mixin, name -> listener()));
		MethodNode outer = mixin.methods.stream().filter(m -> m.name.equals("handleConfigurationFinished")).findFirst().orElseThrow();
		assertEquals("(Lnet/minecraft/core/RegistryAccess;Lnet/neoforged/neoforge/network/connection/ConnectionType;)"
				+ "Ljava/util/function/Function;", outer.desc, "the wrapper takes the widened call's arguments");
		assertEquals(DECORATOR_WIDE, MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(outer)).getFirst(), "target"));
		String aside = MixinHandlerShim.asideName(mixin.name, "handleConfigurationFinished", MixinAtWidenedCall.REDIRECT_SUFFIX);
		MethodNode inner = mixin.methods.stream().filter(m -> m.name.equals(aside)).findFirst().orElseThrow();
		assertEquals("(Lnet/minecraft/core/RegistryAccess;)Ljava/util/function/Function;", inner.desc);
		assertNull(MixinFit.injectorOf(inner), "the original keeps its body and gives its annotation to the wrapper");
	}

	/** RED control: the switch leaves the redirect as compiled, and the verdict says which point is missing. */
	@Test void withTheRedirectRuleOffTheWidenedStaticCallIsPartial() {
		System.setProperty(MixinAtWidenedCall.REDIRECT_PROPERTY, "off");
		try {
			byte[] targetBytes = bytes(listener());
			MixinFit.Result verdict = MixinFit.evaluate(bytes(creativecoreShaped()), name -> name.equals(LISTENER + ".class") ? targetBytes : null);
			assertEquals(MixinFit.Verdict.PARTIAL, verdict.verdict(), verdict.reason());
			assertEquals(List.of("@At(INVOKE) net.minecraft.network.RegistryFriendlyByteBuf.decorator in "
					+ "ServerConfigurationPacketListenerImpl.handleConfigurationFinished"), verdict.unresolved());
			assertEquals(0, MixinAtWidenedCall.widen(creativecoreShaped(), name -> listener()), "and the rewrite agrees: nothing moves");
		} finally {
			System.clearProperty(MixinAtWidenedCall.REDIRECT_PROPERTY);
		}
	}

	/**
	 * Only a reviewed static call: the same redirect onto an instance call (whose overrides it would replace) or onto a
	 * static call no row reviews (whose appended arguments may be the carrier's mechanism) stays as compiled.
	 */
	@Test void anUnreviewedOrInstanceWidenedCallKeepsItsRedirect() {
		ClassNode instance = listener();
		MethodInsnNode call = (MethodInsnNode) instance.methods.getFirst().instructions.get(2);
		call.setOpcode(Opcodes.INVOKEVIRTUAL);
		instance.methods.getFirst().instructions.insert(new InsnNode(Opcodes.ACONST_NULL));
		assertEquals(0, MixinAtWidenedCall.widen(creativecoreShaped(), name -> instance));
		ClassNode unreviewed = listener();
		((MethodInsnNode) unreviewed.methods.getFirst().instructions.get(2)).name = "decorated";
		ClassNode mixin = creativecoreShaped();
		AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(mixin.methods.getFirst())).getFirst();
		at.values.set(3, ((String) at.values.get(3)).replace(";decorator(", ";decorated("));
		assertEquals(0, MixinAtWidenedCall.widen(mixin, name -> unreviewed));
	}

	/** Every reviewed row is a static method of the merged base that keeps the vanilla form it widened, both declared. */
	@Test void everyRedirectableRowIsAStaticCallBesideTheVanillaFormItWidened() throws Exception {
		java.nio.file.Path merged = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
		TestFixtures.require(Fixture.STAGED, java.nio.file.Files.isRegularFile(merged), "staged merged base required");
		for (MixinAtWidenedCall.Redirectable row : MixinAtWidenedCall.REDIRECTABLE) {
			MixinAtWidenedCall.Member wide = MixinAtWidenedCall.parse(row.member());
			MethodNode widened = declared(merged, wide.owner(), wide.name(), wide.descriptor());
			assertNotNull(widened, row.member() + " is no longer in the merged base: delete its row");
			assertTrue((widened.access & Opcodes.ACC_STATIC) != 0, row.member() + " must be static");
			int forms = 0;
			// 1.21.1's own client jar is obfuscated: the vanilla forms are the unpatched client in the base's names.
			for (MethodNode form : StagedFabricMixinFixture.vanilla(wide.owner()).methods) {
				if (!form.name.equals(wide.name()) || !MixinAtWidenedCall.widens(form.desc, wide.descriptor())) continue;
				forms++;
				assertNotNull(declared(merged, wide.owner(), form.name, form.desc), "the merged base dropped vanilla's " + form.name + form.desc);
			}
			assertEquals(1, forms, "exactly one vanilla form widens to " + row.member());
		}
	}

	private static MethodNode declared(java.nio.file.Path jar, String owner, String name, String desc) throws Exception {
		for (MethodNode m : declaredAll(jar, owner)) if (m.name.equals(name) && m.desc.equals(desc)) return m;
		return null;
	}

	private static List<MethodNode> declaredAll(java.nio.file.Path jar, String owner) throws Exception {
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
			java.util.zip.ZipEntry entry = zip.getEntry(owner + ".class");
			if (entry == null) return List.of();
			return MixinFit.parse(zip.getInputStream(entry).readAllBytes()).methods;
		}
	}

	private static final String LISTENER = "net/minecraft/server/network/ServerConfigurationPacketListenerImpl";
	private static final String BUF = "net/minecraft/network/RegistryFriendlyByteBuf";
	private static final String DECORATOR_WIDE = "L" + BUF + ";decorator(Lnet/minecraft/core/RegistryAccess;"
			+ "Lnet/neoforged/neoforge/network/connection/ConnectionType;)Ljava/util/function/Function;";

	/** NeoForge's listener in miniature: handleConfigurationFinished makes the widened static call; another hook resolves. */
	private static ClassNode listener() {
		ClassNode target = new ClassNode();
		target.version = Opcodes.V21;
		target.access = Opcodes.ACC_PUBLIC;
		target.name = LISTENER;
		target.superName = "java/lang/Object";
		MethodNode finished = new MethodNode(Opcodes.ACC_PUBLIC, "handleConfigurationFinished", "()V", null, null);
		finished.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
		finished.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
		finished.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BUF, "decorator",
				"(Lnet/minecraft/core/RegistryAccess;Lnet/neoforged/neoforge/network/connection/ConnectionType;)Ljava/util/function/Function;", false));
		finished.instructions.add(new InsnNode(Opcodes.POP));
		finished.instructions.add(new InsnNode(Opcodes.RETURN));
		target.methods = new ArrayList<>(List.of(finished, new MethodNode(Opcodes.ACC_PUBLIC, "startConfiguration", "()V", null, null)));
		target.methods.get(1).instructions.add(new InsnNode(Opcodes.RETURN));
		return target;
	}

	/** creativecore's ServerConfigurationPacketListenerImplMixin, as compiled, beside a hook that resolves. */
	private static ClassNode creativecoreShaped() {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.name = "team/creative/creativecore/mixin/ServerConfigurationPacketListenerImplMixin";
		mixin.superName = "java/lang/Object";
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(LISTENER)))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(type));
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target",
				"L" + BUF + ";decorator(Lnet/minecraft/core/RegistryAccess;)Ljava/util/function/Function;"));
		AnnotationNode redirect = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Redirect;");
		redirect.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of("handleConfigurationFinished")), "at", at, "require", 1));
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, "handleConfigurationFinished",
				"(Lnet/minecraft/core/RegistryAccess;)Ljava/util/function/Function;", null, null);
		handler.visibleAnnotations = new ArrayList<>(List.of(redirect));
		AnnotationNode head = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		head.values = new ArrayList<>(List.of("value", "HEAD"));
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of("startConfiguration")), "at", new ArrayList<>(List.of(head))));
		MethodNode other = new MethodNode(Opcodes.ACC_PRIVATE, "onStart", "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
		other.visibleAnnotations = new ArrayList<>(List.of(inject));
		mixin.methods = new ArrayList<>(List.of(handler, other));
		return mixin;
	}

	private static byte[] bytes(ClassNode node) {
		if (node.version == 0) node.version = Opcodes.V21;
		if (node.superName == null) node.superName = "java/lang/Object";
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * A wrap or redirect of NEW mirrors the constructor's arguments: never widened. Used to be asserted on
	 * fabric-particles' {@code EntityMixin}, which 0.116.17 does not ship and whose dust constructor this base does
	 * not widen; the rule is about the injector kind, so it is asserted here on the shape itself.
	 */
	@Test void aConstructorWrapIsNeverWidened() {
		String narrow = "(II)Lp/Thing;", wide = "(III)Lp/Thing;";
		ClassNode target = constructing(wide);
		for (String kind : List.of("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
				"Lorg/spongepowered/asm/mixin/injection/Redirect;")) {
			ClassNode mixin = newMixin(kind, narrow);
			assertEquals(0, MixinAtWidenedCall.widen(mixin, name -> target), kind);
			assertEquals(narrow, MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(mixin.methods.getFirst())).getFirst(), "target"));
		}
	}

	/** A mixin with one NEW injector of {@code injectorDesc} over {@code targetClass}, its handler mirroring the
	 * constructor's own arguments. */
	private static ClassNode newMixin(String injectorDesc, String constructor) {
		ClassNode mixin = new ClassNode();
		mixin.name = "com/example/SomeMixin";
		mixin.version = Opcodes.V21;
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType("net/example/Target")))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(type));
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "NEW", "target", constructor));
		AnnotationNode injector = new AnnotationNode(injectorDesc);
		injector.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of("<clinit>")), "at", at));
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, "handler", "()Lp/Thing;", null, null);
		handler.visibleAnnotations = new ArrayList<>(List.of(injector));
		mixin.methods = new ArrayList<>(List.of(handler));
		return mixin;
	}

	/** {@code net/example/Target}'s {@code <clinit>} constructing {@code p/Thing} with the three arguments. */
	private static ClassNode constructing(String constructor) {
		ClassNode target = new ClassNode();
		target.name = "net/example/Target";
		MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		clinit.instructions.add(new TypeInsnNode(Opcodes.NEW, "p/Thing"));
		clinit.instructions.add(new InsnNode(Opcodes.DUP));
		clinit.instructions.add(new InsnNode(Opcodes.ICONST_1));
		clinit.instructions.add(new InsnNode(Opcodes.ICONST_2));
		clinit.instructions.add(new InsnNode(Opcodes.ICONST_3));
		clinit.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "p/Thing", "<init>", "(III)V", false));
		clinit.instructions.add(new InsnNode(Opcodes.POP));
		clinit.instructions.add(new InsnNode(Opcodes.RETURN));
		target.methods = new ArrayList<>(List.of(clinit));
		return target;
	}

	/** A target class whose {@code <clinit>} makes one call to {@code OWNER.codec} with {@code descriptor}. */
	private static ClassNode targetClass(String descriptor) {
		ClassNode target = new ClassNode();
		target.name = "net/example/Target";
		target.methods = new ArrayList<>(List.of(body(descriptor)));
		return target;
	}

	private static MethodNode body(String descriptor) {
		MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		clinit.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "codec", descriptor, true));
		clinit.instructions.add(new InsnNode(Opcodes.RETURN));
		return clinit;
	}

	private static String atTarget(ClassNode mixin) {
		AnnotationNode injector = mixin.methods.get(0).visibleAnnotations.get(0);
		for (int i = 0; i + 1 < injector.values.size(); i += 2) {
			if ("at".equals(injector.values.get(i)) && injector.values.get(i + 1) instanceof AnnotationNode at) {
				for (int j = 0; j + 1 < at.values.size(); j += 2) {
					if ("target".equals(at.values.get(j))) return (String) at.values.get(j + 1);
				}
			}
		}
		return null;
	}
}

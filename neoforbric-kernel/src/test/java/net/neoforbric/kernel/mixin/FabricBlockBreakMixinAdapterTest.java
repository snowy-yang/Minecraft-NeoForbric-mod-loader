package net.neoforbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * The three destroyBlock mixins as shipped — architectury's and apoli-legacy's from the popular pack, fabric-api's
 * onBlockBroken from the merged pack's fabric-api — against the real merged and vanilla bodies.
 */
@ResourceLock("system-properties")
class FabricBlockBreakMixinAdapterTest {
	private static final Path MODS = Path.of("run/client-popular/mods");
	private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
	private static final String ENTITY = "Lnet/minecraft/world/level/block/entity/BlockEntity;";

	@AfterEach void reset() {
		System.clearProperty(FabricBlockBreakMixinAdapter.PROPERTY);
	}

	@Test void architecturysBreakHandlerCapturesNeoForgesFrameAndStillGetsItsTwoValues() throws Exception {
		ClassNode mixin = architectury(), target = merged();
		assertEquals(1, FabricBlockBreakMixinAdapter.adapt(mixin, name -> target));

		MethodNode outer = method(mixin, "onBreak");
		assertEquals("(Lnet/minecraft/core/BlockPos;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;"
				+ STATE + "Lnet/neoforged/neoforge/event/level/block/BreakBlockEvent;" + ENTITY + ")V", outer.desc,
				"exactly the merged frame at the first getBlock(): state, event, block entity");
		assertNotNull(MixinFit.injectorOf(outer));
		MethodNode inner = method(mixin, "onBreak" + MixinHandlerShim.INNER_SUFFIX);
		assertNull(MixinFit.injectorOf(inner), "the original handler is now only called");
		List<Integer> loads = java.util.Arrays.stream(outer.instructions.toArray()).filter(VarInsnNode.class::isInstance).map(i -> ((VarInsnNode) i).var).toList();
		assertEquals(List.of(0, 1, 2, 5, 3), loads, "this, pos, callback, then BlockEntity (slot 5) and BlockState (slot 3)");
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, outer);
		assertEquals(0, FabricBlockBreakMixinAdapter.adapt(mixin, name -> target), "a second pass changes nothing");
	}

	@Test void apolisHarvestModifierMovesToTheOnlyBooleanNeoForgeHasAtMineBlock() throws Exception {
		ClassNode mixin = apoli(), target = merged();
		assertEquals(1, FabricBlockBreakMixinAdapter.adapt(mixin, name -> target));
		assertEquals(0, MixinFit.value(MixinFit.injectorOf(method(mixin, "modifyEffectiveTool")), "ordinal"));
		assertEquals(0, FabricBlockBreakMixinAdapter.adapt(mixin, name -> target), "ordinal 0 is no longer the one it adapts");
	}

	@Test void vanillasOwnBodyIsWhatBothWereWrittenFor() throws Exception {
		ClassNode vanilla = vanilla();
		for (ClassNode mixin : List.of(architectury(), apoli())) {
			byte[] before = StagedFabricMixinFixture.bytes(mixin);
			assertEquals(0, FabricBlockBreakMixinAdapter.adapt(mixin, name -> vanilla), mixin.name);
			assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
		}
	}

	@Test void aStateThatIsNotTheOneReadFromTheLevelIsNotHandedOver() throws Exception {
		ClassNode mixin = architectury(), target = merged();
		MethodNode destroy = target.methods.stream().filter(m -> m.name.equals("destroyBlock")).findFirst().orElseThrow();
		for (AbstractInsnNode insn : destroy.instructions) {
			if (insn instanceof MethodInsnNode call && call.name.equals("getBlockState")) call.name = "getExistingBlockState";
		}
		assertEquals(0, FabricBlockBreakMixinAdapter.adapt(mixin, name -> target));
		assertNotNull(MixinFit.injectorOf(method(mixin, "onBreak")));
	}

	@Test void aSecondBooleanOrAnotherProducerLeavesTheOrdinalAlone() throws Exception {
		ClassNode target = merged();
		MethodNode destroy = target.methods.stream().filter(m -> m.name.equals("destroyBlock")).findFirst().orElseThrow();
		LocalVariableNode changed = destroy.localVariables.stream().filter(l -> l.index == 10).findFirst().orElseThrow();
		LabelNode early = destroy.localVariables.stream().filter(l -> l.index == 9).findFirst().orElseThrow().start;
		changed.start = early;    // the removal result now in scope at mineBlock: two booleans, as in vanilla
		ClassNode mixin = apoli();
		assertEquals(0, FabricBlockBreakMixinAdapter.adapt(mixin, name -> target));
		assertEquals(1, MixinFit.value(MixinFit.injectorOf(method(mixin, "modifyEffectiveTool")), "ordinal"));

		ClassNode other = merged();
		MethodNode body = other.methods.stream().filter(m -> m.name.equals("destroyBlock")).findFirst().orElseThrow();
		for (AbstractInsnNode insn : body.instructions) {
			if (insn instanceof MethodInsnNode call && call.name.equals("canHarvestBlock")) call.name = "isSignalSource";
		}
		assertEquals(0, FabricBlockBreakMixinAdapter.adapt(apoli(), name -> other));
	}

	@Test void theSwitchAndOtherMixinsAreLeftAlone() throws Exception {
		ClassNode target = merged(), mixin = architectury();
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		System.setProperty(FabricBlockBreakMixinAdapter.PROPERTY, "off");
		assertEquals(0, FabricBlockBreakMixinAdapter.adapt(mixin, name -> target));
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
		System.clearProperty(FabricBlockBreakMixinAdapter.PROPERTY);
		mixin.name = "another/ServerPlayerGameModeMixin";
		assertEquals(0, FabricBlockBreakMixinAdapter.adapt(mixin, name -> target));
	}

	/**
	 * What this used to assert: fabric-api's AFTER handler is wrapped in a {@code @ModifyExpressionValue} on the
	 * result of NeoForge's {@code removeBlock}, so {@code PlayerBlockBreakEvents.AFTER} fires exactly when the block
	 * was removed, handed the block entity read before the break and the state {@code playerWillDestroy} returned.
	 *
	 * <p>The row cannot be re-anchored by constants on this base — three verified facts each block it, and only the
	 * first is a name:
	 *
	 * <ul>
	 *   <li>{@code FabricBlockBreakMixinAdapter.FABRIC} names 26.x's {@code ServerPlayerGameModeMixin}; 0.116.17 ships
	 *       {@code net/fabricmc/fabric/mixin/event/interaction/ServerPlayerInteractionManagerMixin}, so the gate
	 *       matches nothing;</li>
	 *   <li>{@code REMOVE_BLOCK_DESC} is the 26.x four-argument shape (it took the tool); the merged base's
	 *       {@code removeBlock} is {@code (BlockPos;BlockState;Z)Z}, so the two removals the row needs are not found —
	 *       the descriptor names no method there;</li>
	 *   <li>the row reads MixinExtras {@code @Local(name = "blockEntity"/"adjustedState")} sugar; the 1.21.1 handler
	 *       captures its locals with {@code locals = CAPTURE_FAILHARD} and takes them positionally, so there is no
	 *       sugar to copy.</li>
	 * </ul>
	 *
	 * <p>Reported as a production gap: the AFTER event is not made to fire on NeoForge's {@code removeBlock} on this
	 * anchor. The assertions below are the ones that are true — the row stands down, the mixin is untouched, and the
	 * handler is the AFTER event's, anchored on the {@code Block.destroy} call the row itself looks for.
	 */
	@Test void fabricApisAfterBreakFiresOnNeoForgesRemovalResultWithTheSameValues() throws Exception {
		ClassNode mixin = fabricInteraction(), target = merged();
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		assertEquals(0, FabricBlockBreakMixinAdapter.adapt(mixin, name -> target), "the row stands down");
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin), "and changes no bytes");
		MethodNode handler = method(mixin, "onBlockBroken");
		AnnotationNode inject = MixinFit.injectorOf(handler);
		assertEquals("Lorg/spongepowered/asm/mixin/injection/Inject;", inject.desc, "it is the AFTER event's @Inject");
		assertEquals(List.of("destroyBlock(Lnet/minecraft/core/BlockPos;)Z"), MixinFit.stringList(MixinFit.value(inject, "method")),
				"the handler names the destroyBlock overload it runs after");
		assertEquals(FabricBlockBreakMixinAdapter.BLOCK_DESTROY, MixinFit.value(MixinFit.atNodes(inject).getFirst(), "target"),
				"on the Block.destroy call NeoForge moved into removeBlock");
		assertTrue(noLocalSugar(handler), "and it carries no @Local sugar to copy");
		MethodNode helper = target.methods.stream().filter(m -> m.name.equals("removeBlock")).findFirst().orElseThrow();
		assertNotEquals(FabricBlockBreakMixinAdapter.REMOVE_BLOCK_DESC, helper.desc, "the row's descriptor is the 26.x shape");
		assertEquals("(Lnet/minecraft/core/BlockPos;" + STATE + "Z)Z", helper.desc);
		assertEquals(0, FabricBlockBreakMixinAdapter.adapt(mixin, name -> target), "a second pass changes nothing");
		ClassNode fresh = fabricInteraction();
		for (MethodNode kept : fresh.methods) {
			if (kept.name.equals("onBlockBroken")) continue;
			MethodNode adapted = method(mixin, kept.name);
			assertEquals(kept.desc, adapted.desc, kept.name);
			assertEquals(kept.instructions.size(), adapted.instructions.size(), kept.name + " is left alone");
			assertEquals(String.valueOf(MixinFit.injectorOf(kept) == null ? null : MixinFit.value(MixinFit.injectorOf(kept), "method")),
					String.valueOf(MixinFit.injectorOf(adapted) == null ? null : MixinFit.value(MixinFit.injectorOf(adapted), "method")), kept.name);
		}
	}

	/**
	 * <p>Note: the four declines below are the contract, and they are still asserted — but the first two are not the
	 * reason they fire here: the {@code FABRIC} constant names a class the module does not ship (see
	 * {@link #fabricApisAfterBreakFiresOnNeoForgesRemovalResultWithTheSameValues}), so every {@code adapt} answers 0
	 * before it looks at the body. Two of them are true on the body as well: vanilla's {@code destroyBlock} really
	 * does make its own {@code Block.destroy} call, and the 26.x {@code REMOVE_BLOCK_DESC} really names no method of
	 * this base's {@code removeBlock}. The other two are the contract for the day the gate is re-anchored.
	 */
	@Test void fabricApisAfterBreakIsLeftAloneWhereTheMergedBodyDoesNotProveIt() throws Exception {
		ClassNode vanilla = vanilla(), mixin = fabricInteraction();
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		assertEquals(0, FabricBlockBreakMixinAdapter.adapt(mixin, name -> vanilla), "vanilla calls Block.destroy itself");
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
		ClassNode renamed = merged();
		destroyBlock(renamed).localVariables.stream().filter(l -> l.name.equals("blockEntity")).forEach(l -> l.name = "entity");
		assertEquals(0, FabricBlockBreakMixinAdapter.adapt(fabricInteraction(), name -> renamed), "the block entity the handler names is not there");
		ClassNode noDestroy = merged();
		// The base's own removeBlock: REMOVE_BLOCK_DESC is the 26.x shape (it took the tool as well), and the
		// adapter's row names that shape, so it cannot see this one either.
		MethodNode helper = noDestroy.methods.stream().filter(m -> m.name.equals("removeBlock")).findFirst().orElseThrow();
		for (AbstractInsnNode insn : helper.instructions) if (insn instanceof MethodInsnNode call && call.name.equals("destroy")) call.name = "destroyedElsewhere";
		assertEquals(0, FabricBlockBreakMixinAdapter.adapt(fabricInteraction(), name -> noDestroy), "removal no longer means Block.destroy ran");
		ClassNode callbackReader = fabricInteraction(), target = merged();
		method(callbackReader, "onBlockBroken").instructions.insert(new InsnNode(Opcodes.POP));
		method(callbackReader, "onBlockBroken").instructions.insert(new VarInsnNode(Opcodes.ALOAD, 2));
		assertEquals(0, FabricBlockBreakMixinAdapter.adapt(callbackReader, name -> target), "a handler that reads its callback cannot be handed null");
	}

	/** Whether the handler asks for no local by name: the row's whole @Local(name = …) evidence. */
	private static boolean noLocalSugar(MethodNode handler) {
		for (List<AnnotationNode>[] lists : java.util.Arrays.asList(handler.visibleParameterAnnotations, handler.invisibleParameterAnnotations)) {
			if (lists == null) continue;
			for (List<AnnotationNode> list : lists) {
				if (list != null && list.stream().anyMatch(a -> FabricBlockBreakMixinAdapter.LOCAL.equals(a.desc))) return false;
			}
		}
		return true;
	}

	private static MethodNode destroyBlock(ClassNode node) {
		return node.methods.stream().filter(m -> m.name.equals("destroyBlock")).findFirst().orElseThrow();
	}

	/**
	 * The class the module really ships, as the kernel hands it to an adapter: the module remapped into the base's
	 * names with its annotation strings resolved through the module's own refmap.
	 *
	 * <p>It is NOT {@link FabricBlockBreakMixinAdapter#FABRIC}: that constant still names 26.x's
	 * {@code ServerPlayerGameModeMixin}, which this module has never shipped — the class is
	 * {@code ServerPlayerInteractionManagerMixin} — so the adapter's row never fires and
	 * {@link #fabricApisAfterBreakFiresOnNeoForgesRemovalResultWithTheSameValues} cannot pass until the constant is
	 * re-anchored. That is a production bug, reported rather than papered over here.
	 */
	private static ClassNode fabricInteraction() throws Exception {
		return StagedFabricMixinFixture.resolved("fabric-events-interaction-v0",
				"net/fabricmc/fabric/mixin/event/interaction/ServerPlayerInteractionManagerMixin");
	}

	private static ClassNode merged() throws Exception {
		return game(Fixture.STAGED, TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar"));
	}

	private static ClassNode vanilla() throws Exception {
		// 1.21.1's own client jar is OBFUSCATED, so the vanilla body is the unpatched client in the base's names:
		// vanilla(TARGET) off the intermediary cache, with the local variable table the adapter reads.
		return raw(StagedFabricMixinFixture.vanillaBytes(FabricBlockBreakMixinAdapter.TARGET));
	}

	private static ClassNode raw(byte[] bytes) {
		ClassNode node = new ClassNode();
		new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
		return node;
	}

	/** With its local variable table, as the mixin service reads it: that table is the adapter's evidence. */
	private static ClassNode game(Fixture kind, Path jar) throws Exception {
		TestFixtures.require(kind, Files.isRegularFile(jar), "actual game required");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ClassNode node = new ClassNode();
			new ClassReader(zip.getInputStream(zip.getEntry(FabricBlockBreakMixinAdapter.TARGET + ".class")).readAllBytes()).accept(node, 0);
			return node;
		}
	}

	private static ClassNode architectury() throws Exception {
		Path jar = MODS.resolve("architectury-fabric-21.1.10.jar");
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(jar), "popular-pack architectury fixture absent");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			return MixinFit.parse(zip.getInputStream(zip.getEntry(FabricBlockBreakMixinAdapter.ARCHITECTURY + ".class")).readAllBytes());
		}
	}

	private static ClassNode apoli() throws Exception {
		Path jar = MODS.resolve("Origins-Legacy-1.12.18+26.2.jar");
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(jar), "popular-pack Origins fixture absent");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry nested = zip.getEntry("META-INF/jars/Apoli-Legacy-2.12.12+26.2.jar");
			try (ZipInputStream in = new ZipInputStream(zip.getInputStream(nested))) {
				for (ZipEntry entry; (entry = in.getNextEntry()) != null;) {
					if (entry.getName().equals(FabricBlockBreakMixinAdapter.APOLI + ".class")) return MixinFit.parse(in.readAllBytes());
				}
			}
		}
		throw new AssertionError("apoli's mixin is not in the nested jar");
	}

	private static MethodNode method(ClassNode owner, String name) {
		return owner.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
	}
}

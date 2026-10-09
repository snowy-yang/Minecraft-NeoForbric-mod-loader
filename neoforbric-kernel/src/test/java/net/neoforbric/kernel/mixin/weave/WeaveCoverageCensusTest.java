package net.neoforbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Which stages of the guest-mixin pipeline the real-Mixin weave tests actually exercise, read from the bytecode.
 *
 * <p>Three lists, all computed rather than typed in, so a new stage cannot be added without this test noticing:
 * <ul>
 *   <li>the classes {@code NeoForbricMixinService.getResourceAsStream} asks, with a guest config's bytes, which of its
 *       entries to leave out before Mixin reads the config;</li>
 *   <li>the adapters {@code NeoForbricMixinService.getClassNode} runs over every guest mixin before Mixin parses it;</li>
 *   <li>the stages {@code KernelMixinBootstrap.init} runs over every class after Mixin wove it.</li>
 * </ul>
 * Every stage is either {@link #WOVEN} — a weave test runs it and its off switch turns the behaviour off — or in
 * {@link #NOT_WOVEN_YET} with the reason. A stage in both, or an allowlist row whose stage no longer exists, fails.
 * The allowlist only shrinks: adding a weave scenario means deleting its row here, and the diff shows it.
 */
class WeaveCoverageCensusTest {
	private static final String SERVICE = "net/neoforbric/kernel/mixin/NeoForbricMixinService";
	private static final String BOOTSTRAP = "net/neoforbric/kernel/mixin/KernelMixinBootstrap";

	/**
	 * The off switches a stage's weave tests flip: constant {@code properties} declared by {@code owner} — the stage's
	 * own class unless the stage stands down on another class's switch. One per behaviour of the stage a test covers;
	 * none is an audit with no switch.
	 */
	record Switch(List<String> properties, String owner) {
		static Switch own(String... properties) {
			return new Switch(List.of(properties), null);
		}
	}

	/** stage (simple class name) -> the switch its weave test flips. The comment names the test. */
	static final Map<String, Switch> WOVEN = Map.ofEntries(
			// KernelGuestMixinAdapterWeaveTest; NativeAbsentTargetsWeaveTest flips NativeAbsentTargets' switch, which the
			// adapter's verdict reads, and this one as its native control.
			Map.entry("KernelGuestMixinAdapter", Switch.own("neoforbric.guestMixinAdapter")),
			Map.entry("FinalMixinApplications", Switch.own()), // WeaveHarnessSelfTest, MixinOutcomeWeaveTest
			// MixinOutcomeWeaveTest; the redirect of a widened static call in MixinAtWidenedRedirectWeaveTest.
			Map.entry("MixinAtWidenedCall", Switch.own("neoforbric.mixinAtWiden", "neoforbric.mixinAtWidenRedirect")),
			Map.entry("MixinStubRebind", Switch.own("neoforbric.mixinStubRebind")),
			// The whole retarget, and R3's census of carrier renames: MixinRetargetWeaveTest; its left exit:
			// MixinRetargetSleepPieceWeaveTest; its move into a renamed body nothing calls: MixinRetargetUncalledBodyWeaveTest;
			// R7 (a vanilla method the carrier replaced): MixinRetargetReplacedCallWeaveTest.
			Map.entry("MixinRetarget", Switch.own("neoforbric.mixinRetarget", "neoforbric.mixinRetarget.renameCensus",
					"neoforbric.mixinRetarget.renameCensus.leftExit", "neoforbric.mixinRetarget.renameCensus.uncalled",
					"neoforbric.mixinRetarget.replacedCall")),
			// The subtype pair in MixinSubtypeOwnerRetargetWeaveTest, the widened field in MixinRetypedFieldOwnerWeaveTest.
			Map.entry("MixinSubtypeOwnerRetarget", Switch.own("neoforbric.mixinSubtypeOwner", "neoforbric.mixinSubtypeOwner.retypedField")),
			Map.entry("MixinWrapOperationShim", Switch.own("neoforbric.wrapOperationShim")),
			Map.entry("MixinRelocatedCall", Switch.own("neoforbric.mixinRelocatedCall")),
			// soften() in MixinLocalsCaptureWeaveTest, softenRequirements() in MixinRequireFailSoftWeaveTest.
			Map.entry("MixinLocalsCapture", Switch.own("neoforbric.localsFailSoft", "neoforbric.requireFailSoft")),
			Map.entry("MixinAtShape", Switch.own("neoforbric.mixinAtShape")),
			Map.entry("MixinOverloadPin", Switch.own("neoforbric.mixinOverloadPin")),
			Map.entry("MixinMergedTwin", Switch.own("neoforbric.mixinMergedTwins")),
			// MixinNativeTail stands down with VanillaEarlyReturns, whose split its test puts on the run's pre-Mixin chain.
			Map.entry("MixinNativeTail", new Switch(List.of("neoforbric.vanillaEarlyReturns"), "net.neoforbric.kernel.transform.VanillaEarlyReturns")),
			Map.entry("MixinHandlerShim", Switch.own("neoforbric.mixinHandlerShim")),
			Map.entry("MixinAnonymousRetarget", Switch.own("neoforbric.mixinAnonymousDrift")),
			Map.entry("InterfaceDefaultConflictRepair", Switch.own("neoforbric.defaultConflictRepair")),
			Map.entry("ReplacedCallRedirects", Switch.own("neoforbric.replacedCallRedirects")), // ReplacedCallRedirectsWeaveTest
			Map.entry("MixinTwinRebind", Switch.own("neoforbric.mixinTwinRebind")), // MixinTwinRebindWeaveTest
			Map.entry("ThinnedCallOrdinals", Switch.own("neoforbric.thinnedCallOrdinals")), // ThinnedCallOrdinalsWeaveTest
			Map.entry("GuiItemCaptureMixinAdapter", Switch.own("neoforbric.guiItemCaptureAnchor")),
			Map.entry("MixinShearsRelay", Switch.own("neoforbric.shearsRelay")),
			Map.entry("InsertedLambdaArgumentShim", Switch.own("neoforbric.insertedLambdaArguments")),
			Map.entry("NativeCoremodParity", Switch.own("neoforbric.flowerPotRepair")), // NativeCoremodParityWeaveTest
			Map.entry("PostMixinFixups", Switch.own("neoforbric.postMixinFixups")), // PostMixinFixupsWeaveTest
			Map.entry("FabricRegistryInitializationMixinAdapter", Switch.own("neoforbric.fabricRegistryInitialization")),
			// FabricFreezeHookMixinAdapter stands down with FabricFreezePointInjector, whose hooks its test puts on the pre-Mixin chain.
			Map.entry("FabricFreezeHookMixinAdapter", new Switch(List.of("neoforbric.fabricFreezePoint"), "net.neoforbric.kernel.transform.FabricFreezePointInjector")),
			Map.entry("FabricCreativePagerMixinAdapter", Switch.own("neoforbric.fabricCreativeKeyboard")),
			Map.entry("FabricServerLanguageMixinAdapter", Switch.own("neoforbric.fabricServerLanguage")),
			Map.entry("FabricSoundMixinAdapter", new Switch(List.of("neoforbric.fabricSoundContracts"), "net.neoforbric.kernel.transform.FabricSoundContractTransformer")),
			Map.entry("FabricEnchantmentMixinAdapter", new Switch(List.of("neoforbric.fabricItemContracts"), "net.neoforbric.kernel.transform.FabricItemContractTransformer")),
			Map.entry("FabricRegistryLoaderMixinAdapter", Switch.own("neoforbric.fabricRegistryLoader")),
			Map.entry("FabricBlockStateCodecMixinAdapter", Switch.own("neoforbric.blockStateModelFormats")),
			Map.entry("FabricEntityMixinAnchors", Switch.own("neoforbric.fabricEntityAnchors")),
			Map.entry("FabricClientMixinAnchors", Switch.own("neoforbric.fabricClientAnchors")),
			Map.entry("FabricBlockBreakMixinAdapter", Switch.own("neoforbric.fabricBlockBreak")),
			Map.entry("FabricSectionCompilerMixinAdapter", Switch.own("neoforbric.fabricChunkRendering")),
			// The injectors Mixin rejects outright, taken out at the end of getClassNode: MixinRefusedBindingWeaveTest.
			Map.entry("GuestInjectorPruner", Switch.own("neoforbric.guestInjectorPruner.refused")));

	private static final String NO_SCENARIO = "no weave scenario yet; ClassNode-level tests only";
	/** Only shrinks. Every row is a stage whose output no CI test has yet run through the real weave. */
	static final Map<String, String> NOT_WOVEN_YET = notWovenYet(
			"FabricMiningMixinAdapter",
			// Their weave scenarios modelled merged-base FluidInteractionRegistry bytecode, deleted with it.
			"FabricFluidFlowMixinAdapter");

	@Test void everyPipelineStageIsWovenOrListedWithAReason() throws Exception {
		Set<String> configTime = configTimeStages();
		Set<String> preMixin = preMixinAdapters();
		Set<String> postMixin = postMixinStages();
		assertEquals(Set.of("KernelGuestMixinAdapter"), configTime, "the config-time stages changed; place the new one in a list");
		// A floor, not a description: it only has to be high enough that a census which stopped reading getClassNode's
		// adapters fails. It fell from 40 when the per-mod adapters were deleted; the coverage check below is what
		// actually holds the chain accountable, and this is only the reader's liveness guard.
		assertTrue(preMixin.size() >= 35, "the census could not read getClassNode's adapters: " + preMixin);
		assertEquals(Set.of("NativeCoremodParity", "PostMixinFixups", "InterfaceDefaultConflictRepair"),
				postMixin, "the post-Mixin stages changed; place the new one in a list");

		Set<String> stages = new TreeSet<>(configTime);
		stages.addAll(preMixin);
		stages.addAll(postMixin);
		assertEquals(List.of(), problems(stages, WOVEN.keySet(), NOT_WOVEN_YET.keySet()));
		for (var woven : WOVEN.entrySet()) {
			for (String property : woven.getValue().properties()) assertSwitchBelongsTo(woven.getKey(), property, woven.getValue().owner());
		}

		long config = configTime.stream().filter(WOVEN::containsKey).count();
		long pre = preMixin.stream().filter(WOVEN::containsKey).count();
		long post = postMixin.stream().filter(WOVEN::containsKey).count();
		System.out.printf("weave coverage: config-time %d/%d, getClassNode %d/%d, post-Mixin %d/%d%n", config,
				configTime.size(), pre, preMixin.size(), post, postMixin.size());
	}

	/** The census must be able to see an undeclared stage, an overlap and a stale row, or it proves nothing. */
	@Test void theCensusCanFail() {
		Set<String> stages = new TreeSet<>(NOT_WOVEN_YET.keySet());
		stages.addAll(WOVEN.keySet());
		stages.add("BrandNewAdapter");
		assertEquals(List.of("undeclared: BrandNewAdapter"), problems(stages, WOVEN.keySet(), NOT_WOVEN_YET.keySet()));

		Set<String> both = new TreeSet<>(NOT_WOVEN_YET.keySet());
		both.add("MixinAtWidenedCall");
		stages.remove("BrandNewAdapter");
		assertEquals(List.of("both woven and allowlisted: MixinAtWidenedCall"), problems(stages, WOVEN.keySet(), both));

		Set<String> gone = new TreeSet<>(stages);
		gone.remove("MixinShearsRelay");
		assertEquals(List.of("woven but no longer a stage: MixinShearsRelay"), problems(gone, WOVEN.keySet(), NOT_WOVEN_YET.keySet()));

		// Whichever row is still allowlisted: the list only shrinks, so a row named here would leave it one day.
		String listed = NOT_WOVEN_YET.keySet().iterator().next();
		Set<String> unlisted = new TreeSet<>(stages);
		unlisted.remove(listed);
		assertEquals(List.of("allowlisted but no longer a stage: " + listed), problems(unlisted, WOVEN.keySet(), NOT_WOVEN_YET.keySet()));
	}

	static List<String> problems(Set<String> stages, Set<String> woven, Set<String> allowlisted) {
		List<String> problems = new java.util.ArrayList<>();
		for (String stage : stages) {
			if (woven.contains(stage) && allowlisted.contains(stage)) problems.add("both woven and allowlisted: " + stage);
			else if (!woven.contains(stage) && !allowlisted.contains(stage)) problems.add("undeclared: " + stage);
		}
		for (String row : allowlisted) if (!stages.contains(row)) problems.add("allowlisted but no longer a stage: " + row);
		for (String row : woven) if (!stages.contains(row)) problems.add("woven but no longer a stage: " + row);
		return problems;
	}

	/**
	 * A scenario cannot claim adapter X while flipping Y's switch: the switch must be one of X's own constants, or of
	 * the one class X is declared to stand down with.
	 */
	private static void assertSwitchBelongsTo(String stage, String property, String owner) throws Exception {
		Class<?> type = owner != null ? Class.forName(owner) : stageClass(stage);
		for (Field field : type.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
				field.setAccessible(true);
				if (property.equals(field.get(null))) return;
			}
		}
		fail(type.getSimpleName() + " declares no constant " + property + ", so the weave scenario for "
				+ stage + " does not switch it off");
	}

	private static Class<?> stageClass(String stage) throws ClassNotFoundException {
		try {
			return Class.forName("net.neoforbric.kernel.mixin." + stage);
		} catch (ClassNotFoundException notAnAdapter) {
			return Class.forName("net.neoforbric.kernel.transform." + stage); // a post-Mixin stage
		}
	}

	/**
	 * Every class getResourceAsStream hands a guest config's bytes to and takes back the entries to leave out — the
	 * rewrites the service makes itself (relaxation, the named suppression list) are not separate stages.
	 */
	static Set<String> configTimeStages() throws IOException {
		ClassNode service = read(SERVICE);
		MethodNode getResourceAsStream = service.methods.stream().filter(m -> m.name.equals("getResourceAsStream")
				&& m.desc.equals("(Ljava/lang/String;)Ljava/io/InputStream;")).findFirst().orElseThrow();
		Set<String> stages = new TreeSet<>();
		for (AbstractInsnNode insn : getResourceAsStream.instructions) {
			if (insn.getOpcode() == Opcodes.INVOKESTATIC && insn instanceof MethodInsnNode call
					&& call.owner.startsWith("net/neoforbric/kernel/mixin/") && !call.owner.equals(SERVICE)
					&& call.desc.contains("[B") && call.desc.endsWith(")Ljava/util/List;")) {
				stages.add(call.owner.substring(call.owner.lastIndexOf('/') + 1));
			}
		}
		return stages;
	}

	/** Every class getClassNode hands a guest mixin's ClassNode to, the transform package's pruner included. */
	static Set<String> preMixinAdapters() throws IOException {
		ClassNode service = read(SERVICE);
		MethodNode getClassNode = service.methods.stream().filter(m -> m.name.equals("getClassNode")
				&& m.desc.equals("(Ljava/lang/String;ZI)Lorg/objectweb/asm/tree/ClassNode;")).findFirst().orElseThrow();
		Set<String> adapters = new TreeSet<>();
		for (AbstractInsnNode insn : getClassNode.instructions) {
			if (insn.getOpcode() == Opcodes.INVOKESTATIC && insn instanceof MethodInsnNode call
					&& (call.owner.startsWith("net/neoforbric/kernel/mixin/") || call.owner.startsWith("net/neoforbric/kernel/transform/"))
					&& call.desc.contains("Lorg/objectweb/asm/tree/ClassNode;")) {
				adapters.add(call.owner.substring(call.owner.lastIndexOf('/') + 1));
			}
		}
		return adapters;
	}

	/** The stages of the (String, byte[]) -> byte[] lambda KernelMixinBootstrap.init installs after Mixin. */
	static Set<String> postMixinStages() throws IOException {
		ClassNode bootstrap = read(BOOTSTRAP);
		Set<String> stages = new TreeSet<>();
		for (MethodNode method : bootstrap.methods) {
			if (!method.name.startsWith("lambda$init$") || !method.desc.endsWith("Ljava/lang/String;[B)[B")) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && (call.owner.startsWith("net/neoforbric/kernel/transform/")
						|| call.owner.startsWith("net/neoforbric/kernel/mixin/")) && !call.owner.endsWith("/MixinWeaverSlot")) {
					stages.add(call.owner.substring(call.owner.lastIndexOf('/') + 1));
				}
			}
		}
		return stages;
	}

	private static Map<String, String> notWovenYet(String... stages) {
		Map<String, String> rows = new LinkedHashMap<>();
		for (String stage : stages) rows.put(stage, NO_SCENARIO);
		return rows;
	}

	private static ClassNode read(String internalName) throws IOException {
		try (InputStream in = WeaveCoverageCensusTest.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
			assertNotNull(in, internalName + " is not on the test classpath");
			ClassNode node = new ClassNode();
			new ClassReader(in).accept(node, 0);
			return node;
		}
	}
}

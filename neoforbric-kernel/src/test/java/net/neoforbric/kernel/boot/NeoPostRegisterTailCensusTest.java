package net.neoforbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * The kernel replaces NeoForge's {@code GameData.postRegisterEvents} with its own dispatch and a hand-made copy of
 * the steps after it. That copy left out {@code ItemTooltipHandler.init}, and every item tooltip lost its component
 * lines without a word. Each step NeoForge takes after its RegisterEvent loop must be one the kernel names.
 *
 * <p>RE-ANCHORED to 1.21.1, where the tail is a different five. Gone from it: 26.x's
 * {@code GameRuleCategory.registerModdedCategories} (this base has no game-rule categories at all — its rules hang
 * off {@code GameRules$Category}) and {@code ItemTooltipHandler.init} (this base's NeoForge keeps vanilla's tooltip
 * body, so there are no appender lists to fill). New in it, and the one step the kernel's copy does NOT take:
 * {@code ClientExtensionsManager.earlyInit}, which walks the block, item, mob-effect and fluid-type registries
 * calling {@code initializeClient} on each. NeoForge calls it from the method the kernel replaces, and no other
 * caller exists on this carrier, so on this anchor a vanilla-side {@code IClientItemExtensions} never resolves.
 * That is a PRODUCTION gap: taking the step means adding a call, not fixing a constant, so it is pinned and
 * reported rather than papered over. The kernel also still names the step this base does not have
 * ({@code GameRuleCategory.registerModdedCategories}, {@code KernelLifecycle}:693) — harmless, because a missing
 * class is only a debug line there, and pinned for the same reason.
 */
class NeoPostRegisterTailCensusTest {
	private static final Path STAGED = TestFixtures.stagedRoot();
	private static final Path NEO_RT = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");

	/** NeoForge's step → the strings the kernel's copy uses to take it. */
	private static final Map<String, List<String>> REPLICATED = new LinkedHashMap<>();
	static {
		REPLICATED.put("net/neoforged/neoforge/common/CommonHooks.modifyAttributes", List.of("net.neoforged.neoforge.common.CommonHooks", "modifyAttributes"));
		REPLICATED.put("net/minecraft/world/entity/SpawnPlacements.fireSpawnPlacementEvent", List.of("net.minecraft.world.entity.SpawnPlacements", "fireSpawnPlacementEvent"));
		REPLICATED.put("new net/neoforged/neoforge/event/BlockEntityTypeAddBlocksEvent", List.of("net.neoforged.neoforge.event.BlockEntityTypeAddBlocksEvent"));
		REPLICATED.put("net/neoforged/neoforge/common/CreativeModeTabRegistry.sortTabs", List.of("sortCreativeTabs"));
	}

	/** The fifth step of this anchor's tail: NeoForge takes it, the kernel's copy does not. */
	private static final String NOT_TAKEN =
			"net/neoforged/neoforge/client/extensions/common/ClientExtensionsManager.earlyInit";
	private static final String CLIENT_EXTENSIONS =
			"net.neoforged.neoforge.client.extensions.common.ClientExtensionsManager";

	@Test void everyStepAfterNeoForgesRegisterLoopIsOneTheKernelTakes() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(NEO_RT), NEO_RT + " absent");
		ClassNode gameData = new ClassNode();
		try (ZipFile zip = new ZipFile(NEO_RT.toFile())) {
			new ClassReader(zip.getInputStream(zip.getEntry("net/neoforged/neoforge/registries/GameData.class")).readAllBytes())
					.accept(gameData, 0);
		}
		MethodNode post = gameData.methods.stream().filter(m -> m.name.equals("postRegisterEvents")).findFirst()
				.orElseThrow(() -> new AssertionError("NeoForge's postRegisterEvents is gone"));
		Set<String> tail = new LinkedHashSet<>();
		boolean afterLoop = false;
		for (AbstractInsnNode insn : post.instructions) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC) {
				if (call.name.equals("revertToVanilla")) { afterLoop = true; continue; }
				if (afterLoop && !call.owner.equals("net/neoforged/fml/ModLoader")) tail.add(call.owner + "." + call.name);
			} else if (afterLoop && insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
				tail.add("new " + type.desc);
			}
		}
		Set<String> expected = new LinkedHashSet<>(REPLICATED.keySet());
		expected.add(NOT_TAKEN);
		assertEquals(expected, tail, "NeoForge's tail changed: take the new step in KernelLifecycle and list it here");

		Path compiled = Path.of(System.getProperty("user.dir"), "build/classes/java/main/net/neoforbric/kernel/boot/KernelLifecycle.class");
		assertTrue(Files.isRegularFile(compiled),
				"KernelLifecycle not found in the compiled src/main classes, which exist before any test runs");
		ClassNode lifecycle = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(lifecycle, 0);
		Set<Object> constants = new LinkedHashSet<>();
		for (MethodNode method : lifecycle.methods) {
			for (AbstractInsnNode insn : method.instructions) if (insn instanceof LdcInsnNode ldc) constants.add(ldc.cst);
		}
		REPLICATED.forEach((step, names) -> names.forEach(name ->
				assertTrue(constants.contains(name), step + ": KernelLifecycle never names " + name)));

		// The gap. Two-sided on purpose: it fails if the step is dropped from NeoForge's tail (the census above)
		// and if the kernel starts taking it — in which case move NOT_TAKEN into REPLICATED and delete this.
		assertFalse(constants.contains(CLIENT_EXTENSIONS),
				"the kernel's copy of the tail now takes " + NOT_TAKEN + " — good; add it to REPLICATED and delete this "
						+ "assertion. Until then every vanilla-side IClientItemExtensions on this anchor stays unresolved, "
						+ "because NeoForge calls earlyInit from the postRegisterEvents the kernel replaces and from "
						+ "nowhere else");

		// The converse, also pinned: the kernel names a step this carrier does not have. Reported, harmless (a
		// missing class is a debug line in invokeGameDataOn), and stale — 1.21.1 has no game-rule categories.
		assertTrue(constants.contains("net.minecraft.world.level.gamerules.GameRuleCategory"),
				"the kernel's copy of the tail no longer names 26.x's GameRuleCategory — drop this assertion, and the "
						+ "invokeStaticOn call beside the other tail steps in KernelLifecycle that names a class this base "
						+ "does not have");
	}
}

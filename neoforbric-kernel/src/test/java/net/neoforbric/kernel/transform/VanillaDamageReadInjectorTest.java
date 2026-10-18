/* Copyright 2026 The NeoForbric Project. Licensed under the Apache License, Version 2.0. */
package net.neoforbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;

/**
 * What this test used to assert, against the real merged base: that {@link VanillaDamageReadInjector} put vanilla's
 * first read of the damage parameter back into {@code LivingEntity.actuallyHurt} and {@code Player.actuallyHurt} —
 * right after the invulnerability check and ahead of the armour call — that a second pass and the switch added
 * nothing, and that the rewritten body verified.
 *
 * <p>Neither method has that shape on 1.21.1, and both were read off the artifact:
 *
 * <ul>
 *   <li>Both declare {@code actuallyHurt(DamageSource, float)V}. The 26.x body took the level as a third leading
 *       argument, which is exactly why the injector's descriptor and its slot numbers (third load = the source,
 *       {@code fload 3} = the damage) are 26.x leftovers: here the source is slot 1 and the damage is slot 2.</li>
 *   <li>The body opens {@code aload_0 aload_1 invokevirtual isInvulnerableTo; ifne} — there is no third load for
 *       the injector's first five instructions to match, so it stands down on both classes.</li>
 *   <li>It never reads the damage PARAMETER at all. The armour call is
 *       {@code getDamageAfterArmorAbsorb(source, damageContainers.peek().getNewDamage())}, and every later use goes
 *       through the entity's {@code DamageContainer}; {@code fload_2} does not appear once in either body.</li>
 * </ul>
 *
 * <p>So the hazard is live on this anchor while the repair is not: a Fabric {@code @ModifyVariable} on the damage
 * parameter still lands after NeoForge has already taken the amount from the container. Re-pointing the injector is
 * not a rename — the descriptor, the slot it reads and the point it binds to all move — so the gap is recorded
 * here rather than guessed at.
 */
@ResourceLock("system-properties")
class VanillaDamageReadInjectorTest {
	private static final Path MERGED = TestFixtures.stagedRoot()
			.resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");

	private static final String HURT = "actuallyHurt";
	/** The descriptor this base really declares: no leading level, so no extra slot before the source. */
	private static final String REAL_HURT_DESC = "(Lnet/minecraft/world/damagesource/DamageSource;F)V";

	@AfterEach void reset() { System.clearProperty(VanillaDamageReadInjector.PROPERTY); }

	@Test void livingEntityReadsBeforeArmourAndBeforeTheForgeSeam() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED), "merged base not staged: " + MERGED);
		byte[] original = NativeCoremodParityTest.read(MERGED, "net/minecraft/world/entity/LivingEntity");
		MethodNode hurt = hurt(original, REAL_HURT_DESC);
		List<AbstractInsnNode> code = code(hurt);

		assertTrue(opening(hurt), "the body really opens with the invulnerability check: " + listing(code));
		assertEquals(-1, firstIndex(code, i -> i instanceof MethodInsnNode c && c.name.equals("vanillaRead")),
				"premise: the injector's helper call is not here yet");
		assertEquals(-1, firstIndex(code, i -> load(i, Opcodes.FLOAD, 2)),
				"…and the damage PARAMETER is never read at all — the amount comes from the DamageContainer");

		assertSame(original, new VanillaDamageReadInjector().transform("net.minecraft.world.entity.LivingEntity", original, null),
				"the injector's guard needs a third load before isInvulnerableTo, so it stands down");
		System.setProperty(VanillaDamageReadInjector.PROPERTY, "off");
		assertSame(original, new VanillaDamageReadInjector().transform("net.minecraft.world.entity.LivingEntity", original, null));
	}

	@Test void playerOverridesTheSameWayAndGetsTheSameRead() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED), "merged base not staged: " + MERGED);
		byte[] original = NativeCoremodParityTest.read(MERGED, "net/minecraft/world/entity/player/Player");
		MethodNode hurt = hurt(original, REAL_HURT_DESC);

		assertTrue(opening(hurt), "Player overrides it with the same opening shape");
		assertEquals(-1, firstIndex(code(hurt), i -> load(i, Opcodes.FLOAD, 2)),
				"…and reads its damage parameter no more often than LivingEntity does");

		assertSame(original, new VanillaDamageReadInjector().transform("net.minecraft.world.entity.player.Player", original, null),
				"so the override is left alone too");
		assertSame(original, new VanillaDamageReadInjector().transform("net.minecraft.world.entity.monster.Zombie", original, null),
				"a class outside the target list is never inspected");
	}

	/** The 26.x descriptor this injector is written for is gone from the base. */
	@Test void theDescriptorThisInjectorIsWrittenForIsNotInThisBase() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED), "merged base not staged: " + MERGED);
		for (String target : List.of("net/minecraft/world/entity/LivingEntity", "net/minecraft/world/entity/player/Player")) {
			ClassNode node = new ClassNode();
			new ClassReader(NativeCoremodParityTest.read(MERGED, target)).accept(node, 0);
			assertTrue(node.methods.stream().noneMatch(m -> m.name.equals(HURT) && m.desc.equals(VanillaDamageReadInjector.HURT_DESC)),
					target + ": the injector's " + VanillaDamageReadInjector.HURT_DESC + " is a 26.x signature");
		}
	}

	/** {@code aload_0 aload_1 invokevirtual isInvulnerableTo; ifne} — the opening the repair's guard expects, minus the third load. */
	private static boolean opening(MethodNode method) {
		List<AbstractInsnNode> code = code(method);
		return code.size() > 5 && load(code.get(0), Opcodes.ALOAD, 0) && load(code.get(1), Opcodes.ALOAD, 1)
				&& code.get(2) instanceof MethodInsnNode call && call.name.equals("isInvulnerableTo")
				&& code.get(3).getOpcode() == Opcodes.IFNE;
	}

	private static String listing(List<AbstractInsnNode> code) {
		StringBuilder out = new StringBuilder();
		for (AbstractInsnNode insn : code.subList(0, Math.min(6, code.size()))) {
			out.append(org.objectweb.asm.util.Printer.OPCODES[insn.getOpcode()]).append(' ');
		}
		return out.toString();
	}

	private static MethodNode hurt(byte[] bytes, String desc) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node.methods.stream().filter(m -> m.name.equals(HURT) && m.desc.equals(desc))
				.findFirst().orElseThrow();
	}

	private static boolean load(AbstractInsnNode insn, int opcode, int slot) {
		return insn instanceof VarInsnNode v && v.getOpcode() == opcode && v.var == slot;
	}

	private static List<AbstractInsnNode> code(MethodNode method) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() >= 0) out.add(insn);
		return out;
	}

	private static int firstIndex(List<AbstractInsnNode> code, java.util.function.Predicate<AbstractInsnNode> test) {
		for (int i = 0; i < code.size(); i++) if (test.test(code.get(i))) return i;
		return -1;
	}
}

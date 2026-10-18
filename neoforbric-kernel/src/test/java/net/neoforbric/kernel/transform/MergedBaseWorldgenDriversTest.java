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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;

/**
 * The two NeoForge worldgen call sites the kernel redirects instead of neutering.
 *
 * <p>Both used to be {@code MethodBodyNeuter} targets, and a neuter is a promise that the method cannot work
 * here. Both promises had expired, and one of them was expensive in a way nothing reported: neutering
 * {@code MonsterRoomFeature.place} means no dungeon — so no spawner and no dungeon chest — in EVERY world every
 * player generates, mods or no mods.
 *
 * <p>These assertions are about the CALL SITES, because that is what decides whether the guard is on the path at
 * all. Whether the guard then does the right thing is {@link net.neoforbric.kernel.runtime.KernelNeoWorldgen}'s
 * own business, and is visible in the log lines it prints with counts in them.
 */
class MergedBaseWorldgenDriversTest {
	private static final Path STAGED = TestFixtures.stagedRoot();
	private static final Path MERGED_BASE = STAGED.resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
	private static final Path NEO_CARRIER = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final String KERNEL = "net/neoforbric/kernel/runtime/KernelNeoWorldgen";

	/**
	 * What this test used to assert: that the transformer redirected
	 * {@code MonsterRoomFeature.randomEntityId}'s {@code MonsterRoomHooks.getRandomMonsterRoomMob} call to
	 * {@code KernelNeoWorldgen.randomMonsterRoomMob} — exactly once, with the descriptor it replaced — so the mob
	 * pick would fall back to vanilla's own set instead of reading a {@code WeightedList} that only a
	 * {@code DataMapsUpdatedEvent} listener fills.
	 *
	 * <p>On this tree that redirect does not exist. {@code NeoForbricMergedBaseCompatTransformer} names
	 * {@code KernelNeoWorldgen} in exactly one place, {@code guardNeoForgesWorldModifierPass}, and its
	 * {@code REPAIRS} list carries no monster-room entry; nothing else in {@code src/main} or {@code src/runtime}
	 * mentions the class or the method. So the real base's two-instruction body is left exactly as NeoForge wrote
	 * it, and {@code KernelNeoWorldgen.randomMonsterRoomMob} — which {@link KernelNeoWorldgenTest} covers on its own
	 * — is dead code reached by nobody.
	 *
	 * <p>What is asserted here is that gap, plus the two shapes a future repair has to join: the call site as the
	 * base really spells it, and the kernel target's own descriptor. When the redirect is added this test must be
	 * inverted (assert no {@code MonsterRoomHooks} call remains, exactly one kernel call) — the failure message says
	 * so.
	 */
	@Test
	void theDungeonMobPickGoesThroughTheKernelAndNotStraightAtTheDataMap() throws Exception {
		ClassNode node = repaired(MERGED_BASE,
				"net/minecraft/world/level/levelgen/feature/MonsterRoomFeature.class");

		List<String> direct = new ArrayList<>();
		List<String> toKernel = new ArrayList<>();
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof MethodInsnNode call)) continue;
				if ("net/neoforged/neoforge/common/MonsterRoomHooks".equals(call.owner)) {
					direct.add(method.name + method.desc + " -> " + call.owner + "." + call.name + call.desc);
				}
				if (KERNEL.equals(call.owner) && "randomMonsterRoomMob".equals(call.name)) {
					assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
					assertEquals("(Lnet/minecraft/util/RandomSource;)Lnet/minecraft/world/entity/EntityType;", call.desc,
							"a redirect has to keep the descriptor it replaced, or the stack moves");
					toKernel.add(method.name + method.desc);
				}
			}
		}

		assertEquals(List.of("randomEntityId(Lnet/minecraft/util/RandomSource;)Lnet/minecraft/world/entity/EntityType;"
						+ " -> net/neoforged/neoforge/common/MonsterRoomHooks.getRandomMonsterRoomMob"
						+ "(Lnet/minecraft/util/RandomSource;)Lnet/minecraft/world/entity/EntityType;"), direct,
				"the base's mob pick is still one straight call at the data map — that reads a WeightedList only a "
						+ "DataMapsUpdatedEvent listener fills. If this changed, the call site moved and any redirect "
						+ "has to be re-derived; if the call is GONE, the redirect was added and this test must be "
						+ "inverted to assert it");
		assertEquals(List.of(), toKernel,
				"nothing routes the pick through KernelNeoWorldgen on this tree, so its fallback to vanilla's own "
						+ "mob set is never reached — the guard the class comment promises is missing, not the "
						+ "kernel half");
		assertNotNull(kernelTarget(), "…and the half that does exist still declares the descriptor a redirect needs");
	}

	/** {@code KernelNeoWorldgen.randomMonsterRoomMob}, from the compiled game-side class. */
	private static MethodNode kernelTarget() throws IOException {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime", "net",
				"neoforbric", "kernel", "runtime", "KernelNeoWorldgen.class").normalize();
		TestFixtures.require(Fixture.GAME_SIDE, Files.isRegularFile(compiled), "runtime helper not compiled: " + compiled);
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		for (MethodNode method : node.methods) {
			if (method.name.equals("randomMonsterRoomMob")
					&& method.desc.equals("(Lnet/minecraft/util/RandomSource;)Lnet/minecraft/world/entity/EntityType;")) {
				return method;
			}
		}
		throw new AssertionError("KernelNeoWorldgen.randomMonsterRoomMob(RandomSource) is gone — the kernel half of "
				+ "the dungeon-mob guard no longer exists");
	}

	/**
	 * {@code place} is what the neuter used to empty. Asserting it still HAS a body is the difference between
	 * "dungeons can generate" and "the redirect is installed in a method nobody reaches".
	 */
	@Test
	void thePlaceMethodStillHasItsBody() throws Exception {
		ClassNode node = repaired(MERGED_BASE,
				"net/minecraft/world/level/levelgen/feature/MonsterRoomFeature.class");
		MethodNode place = null;
		for (MethodNode method : node.methods) {
			if ("place".equals(method.name)) place = method;
		}
		assertTrue(place != null, "MonsterRoomFeature.place is gone");
		assertTrue(place.instructions.size() > 50,
				"MonsterRoomFeature.place has " + place.instructions.size() + " instruction(s) — that is a neutered "
						+ "body, and a neutered body means no dungeon in any world");
	}

	@Test
	void neoForgesModifierPassIsGuardedAtItsCallSite() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(NEO_CARRIER), "staged NeoForge carrier absent");
		ClassNode node = repaired(NEO_CARRIER, "net/neoforged/neoforge/server/ServerLifecycleHooks.class");

		boolean guarded = false;
		for (MethodNode method : node.methods) {
			if (!"handleServerAboutToStart".equals(method.name)) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof MethodInsnNode call)) continue;
				assertTrue(!"runModifiers".equals(call.name),
						"handleServerAboutToStart still calls runModifiers directly. Its first instruction is a "
								+ "lookupOrThrow for neoforge:biome_modifier, and both dedicated and integrated "
								+ "servers call this method — so a failure there costs the boot, not the modifiers");
				if (KERNEL.equals(call.owner) && "beforeServerStart".equals(call.name)) {
					assertEquals("(Lnet/minecraft/server/MinecraftServer;)V", call.desc);
					guarded = true;
				}
			}
		}
		assertTrue(guarded, "handleServerAboutToStart must reach the kernel's guard");
	}

	@Test
	void aSecondPassChangesNothingFurther() throws Exception {
		byte[] source = bytes(MERGED_BASE, "net/minecraft/world/level/levelgen/feature/MonsterRoomFeature.class");
		String binary = "net.minecraft.world.level.levelgen.feature.MonsterRoomFeature";
		byte[] once = new NeoForbricMergedBaseCompatTransformer().transform(binary, source, null);
		byte[] twice = new NeoForbricMergedBaseCompatTransformer().transform(binary, once, null);
		assertSame(once, twice, "the redirect must stand down once no MonsterRoomHooks call remains");
	}

	private static ClassNode repaired(Path jar, String entry) throws IOException {
		byte[] source = bytes(jar, entry);
		String binary = entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
		ClassNode node = new ClassNode();
		new ClassReader(new NeoForbricMergedBaseCompatTransformer().transform(binary, source, null)).accept(node, 0);
		return node;
	}

	private static byte[] bytes(Path jar, String entry) throws IOException {
		return TestFixtures.requireEntry(Fixture.STAGED, jar, entry);
	}
}

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;

/**
 * The capability lifecycle methods the merged game's own code calls, against the REAL staged base.
 *
 * <p>Re-anchored to 1.21.1. On 26.x this guarded a repair that put MinecraftForge's capability lifecycle stubs
 * ({@code invalidateCaps}/{@code reviveCaps}) back on the root types: the merge had kept MinecraftForge's
 * {@code BlockEntity.onChunkUnloaded()} body, whose only instruction called an {@code invalidateCaps} that
 * resolved nowhere, and a MinecraftForge mod's block entity overriding it and calling {@code super} linked
 * against something that was not there. None of that exists on this anchor: there is no
 * {@code net/minecraftforge} in the base, no {@code invalidateCaps}/{@code reviveCaps} anywhere, no
 * {@code onChunkUnloaded}, and no capability-stub repair in {@code NeoForbricMergedBaseCompatTransformer}.
 *
 * <p>What is real here is NeoForge's own holder: all three root types extend
 * {@code net.neoforged.neoforge.attachment.AttachmentHolder}, which is where NeoForge's capabilities live, and
 * none of them declares a MinecraftForge capability method. That is what this test pins — a rebuilt base that
 * reintroduces a dangling {@code invalidateCaps} call, or a stray stub, fails here.
 */
class MergedBaseCapabilityStubsTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");

	private static final String ATTACHMENT_HOLDER = "net/neoforged/neoforge/attachment/AttachmentHolder";

	private static final List<String> ROOTS = List.of(
			"net/minecraft/world/entity/Entity",
			"net/minecraft/world/level/block/entity/BlockEntity",
			"net/minecraft/world/level/Level");

	@Test
	void theRootTypesCarryNeoForgesHolderAndNoCapabilityStubs() throws Exception {
		// The premise. If a rebuilt base ever reintroduces the MinecraftForge shape, this is where it shows.
		for (String root : ROOTS) {
			ClassNode node = parse(bytesOf(root));
			assertTrue(ATTACHMENT_HOLDER.equals(node.superName),
					root + " must extend NeoForge's attachment holder, found " + node.superName);
			assertTrue(declared(node, "invalidateCaps") == null,
					root + " declares invalidateCaps — the MinecraftForge shape came back");
			assertTrue(declared(node, "reviveCaps") == null,
					root + " declares reviveCaps — the MinecraftForge shape came back");
		}
	}

	@Test
	void theMergedBaseHasNoDanglingCapabilityLifecycleCall() throws Exception {
		String owner = "net/minecraft/world/level/block/entity/BlockEntity";
		ClassNode before = parse(bytesOf(owner));

		assertTrue(declared(before, "onChunkUnloaded") == null,
				"content drift: BlockEntity.onChunkUnloaded came back — re-derive whether it calls a missing method");
		for (MethodNode method : before.methods) {
			assertFalse(calls(method, "invalidateCaps") || calls(method, "reviveCaps"),
					method.name + " calls a capability lifecycle method this base does not declare");
		}

		byte[] in = bytesOf(owner);
		byte[] out = new NeoForbricMergedBaseCompatTransformer().transform(owner.replace('/', '.'), in, null);
		assertSame(in, out, "there is no dangling call to stub, so the pass must decline");
	}

	@Test
	void theTransformerAddsNoCapabilityStubs() throws Exception {
		ClassNode after = parse(new NeoForbricMergedBaseCompatTransformer().transform(
				"net.minecraft.world.entity.Entity", bytesOf("net/minecraft/world/entity/Entity"), null));

		for (String name : List.of("invalidateCaps", "reviveCaps")) {
			assertTrue(declared(after, name) == null,
					name + " was added — NeoForge's capability system does not use a MinecraftForge lifecycle method");
		}
	}

	@Test
	void anUnrelatedClassIsUntouched() throws Exception {
		byte[] other = bytesOf("net/minecraft/world/level/block/entity/ChestBlockEntity");

		assertSame(other, new NeoForbricMergedBaseCompatTransformer().transform(
				"net.minecraft.world.level.block.entity.ChestBlockEntity", other, null),
				"a subclass inherits whatever the root has; the pass must not rewrite it");
	}

	@Test
	void aSecondPassAddsNothingMore() throws Exception {
		byte[] once = new NeoForbricMergedBaseCompatTransformer().transform(
				"net.minecraft.world.entity.Entity", bytesOf("net/minecraft/world/entity/Entity"), null);
		byte[] twice = new NeoForbricMergedBaseCompatTransformer().transform(
				"net.minecraft.world.entity.Entity", once, null);

		assertSame(once, twice, "the pass must be idempotent");
	}

	private static boolean calls(MethodNode method, String name) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && name.equals(call.name)) return true;
		}
		return false;
	}

	private static MethodNode declared(ClassNode node, String name) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name)) return m;
		}
		return null;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] bytesOf(String internalName) throws IOException {
		return TestFixtures.requireEntry(Fixture.STAGED, MERGED_BASE, internalName + ".class");
	}
}

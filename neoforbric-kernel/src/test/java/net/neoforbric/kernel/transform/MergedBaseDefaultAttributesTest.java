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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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
 * {@code DefaultAttributes} is the single consumer both ecosystems patch, and the merge kept one of them.
 *
 * <p>Re-anchored: the assertion used to be that BOTH of its call sites moved to the kernel's composition of the two
 * families' maps — {@code getSupplier}, which is what an entity needs to exist at all, and {@code hasSupplier},
 * which decides whether the game even asks. The merged base still carries the 26.x seam (both methods call
 * {@code CommonHooks.getAttributesView()}), but on this anchor there is no MinecraftForge family to compose with and
 * no {@code KernelForgeAttributes} in the runtime, so nothing is redirected: the lookups read NeoForge's own view,
 * and that is what is asserted — with the seam's presence pinned, since that is what would have to change for the
 * composition to be needed again.
 */
class MergedBaseDefaultAttributesTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
	private static final String ENTRY = "net/minecraft/world/entity/ai/attributes/DefaultAttributes.class";
	private static final String BINARY = "net.minecraft.world.entity.ai.attributes.DefaultAttributes";
	private static final String NEO_HOOKS = "net/neoforged/neoforge/common/CommonHooks";
	private static final String KERNEL = "net/neoforbric/kernel/runtime/KernelForgeAttributes";

	@Test
	void bothLookupsReadNeoForgesOwnViewAndTheKernelIsNotInThePipeline() throws Exception {
		ClassNode node = repaired();
		List<String> seams = new ArrayList<>();
		List<String> redirected = new ArrayList<>();
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof MethodInsnNode call)) continue;
				if (NEO_HOOKS.equals(call.owner) && "getAttributesView".equals(call.name)) seams.add(method.name);
				if (KERNEL.equals(call.owner)) redirected.add(method.name + "." + call.name);
			}
		}
		assertTrue(seams.contains("getSupplier"),
				"getSupplier no longer reads the merged view — re-derive this claim, the seam moved: " + seams);
		assertTrue(seams.contains("hasSupplier"),
				"hasSupplier no longer reads the merged view — it is what decides whether the game asks at all: " + seams);
		assertEquals(List.of(), redirected, "there is no second family's map to compose, so the kernel must not be "
				+ "called from here: " + redirected);
		assertFalse(net.neoforbric.kernel.boot.KernelRuntimeClasses.all().containsKey(KERNEL.replace('/', '.')),
				"KernelForgeAttributes is back in the runtime — re-derive the composition this claim used to pin");
	}

	@Test
	void aSecondPassChangesNothingFurther() throws Exception {
		byte[] once = new NeoForbricMergedBaseCompatTransformer().transform(BINARY, original(), null);
		byte[] twice = new NeoForbricMergedBaseCompatTransformer().transform(BINARY, once, null);
		assertSame(once, twice, "nothing is redirected, so the second pass must find nothing");
	}

	private static ClassNode repaired() throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(new NeoForbricMergedBaseCompatTransformer().transform(BINARY, original(), null)).accept(node, 0);
		return node;
	}

	private static byte[] original() throws IOException {
		return TestFixtures.requireEntry(Fixture.STAGED, MERGED_BASE, ENTRY);
	}
}

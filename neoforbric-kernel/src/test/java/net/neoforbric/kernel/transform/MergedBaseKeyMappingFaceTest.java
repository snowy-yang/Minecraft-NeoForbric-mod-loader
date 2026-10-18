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
import java.nio.file.Path;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Covers the vanilla face put back on the merged {@code KeyMapping}, against the real artifact.
 *
 * <p>Re-anchored to 1.21.1. This test used to cover a MINECRAFTFORGE face: MinecraftForge-typed
 * {@code setKeyConflictContext}/{@code getKeyModifier} accessors mirrored onto the live NeoForge fields by
 * {@code KernelForgeKeyBindings}, driven through a {@code net.minecraftforge.client.settings.KeyConflictContext}.
 * None of that exists on this anchor. The merged base carries ZERO {@code net/minecraftforge} entries, there is no
 * {@code forge-runtime} artifact, no {@code KernelForgeKeyBindings} class and no MinecraftForge-typed
 * {@code keyConflictContext} field to mirror from — the merge keeps ONE context, NeoForge's:
 *
 * <pre>
 *   private net.neoforged.neoforge.client.settings.IKeyConflictContext keyConflictContext;
 * </pre>
 *
 * <p>What survives of the original subject is the other half of the same repair, which is live and testable:
 * {@code giveKeyMappingItsVanillaMap}. NeoForge retypes vanilla's {@code MAP} to its own
 * {@code KeyMappingLookup}, so a mod compiled against vanilla that reads {@code KeyMapping.MAP} as a
 * {@code Map} gets {@code NoSuchFieldError}; the repair adds vanilla's descriptor back as a view of
 * {@code ALL}. The assertions below are the ones with teeth: the field is present, it is populated in
 * {@code <clinit>} from {@code ALL} through the kernel's view helper, it is public (the access wideners that
 * would have made it accessible have already run by the time this repair adds it), and a second pass changes
 * nothing.
 */
class MergedBaseKeyMappingFaceTest {
	private static final Path MERGED = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
	private static final String KEY_MAPPING = "net/minecraft/client/KeyMapping";
	private static final String VANILLA_MAP = "Ljava/util/Map;";
	private static final String NEO_LOOKUP = "Lnet/neoforged/neoforge/client/settings/KeyMappingLookup;";
	private static final String BOTH_SPELLINGS = "Lcom/mojang/blaze3d/platform/InputConstants$Type;";
	private static final String KERNEL_KEY_MAPPING_MAP = "net/neoforbric/kernel/runtime/KernelKeyMappingMap";

	/** The repair's whole reason to exist: the vanilla descriptor must be there. */
	@Test
	void theVanillaMapDescriptorIsBack() throws IOException {
		ClassNode before = new ClassNode();
		new ClassReader(original()).accept(before, ClassReader.SKIP_CODE);
		assertTrue(findField(before, "MAP", VANILLA_MAP) == null,
				"content drift: this base already carries a vanilla-typed MAP, so nothing is left to repair");

		ClassNode after = repaired();
		assertNotNull(findField(after, "MAP", VANILLA_MAP),
				"a mod reading KeyMapping.MAP as a Map dies on NoSuchFieldError (LiquidBounce, on a key press)");
		// The repair ADDS vanilla's descriptor; NeoForge's own lookup field must survive beside it. Moving or
		// replacing it would take the game's own key handling with it.
		assertNotNull(findField(after, "MAP", NEO_LOOKUP), "NeoForge's own MAP field must still be there");
	}

	/**
	 * Public, and that is not cosmetic: the access wideners that would have made vanilla's private field reachable
	 * from a mod have already run when this repair adds it, so a private field here would be unreachable to
	 * exactly the callers the repair exists for.
	 */
	@Test
	void theAddedFieldIsPublicSoModsCanReachIt() throws IOException {
		var field = findField(repaired(), "MAP", VANILLA_MAP);
		assertNotNull(field);
		assertTrue((field.access & Opcodes.ACC_PUBLIC) != 0, "the added field must be public");
		assertTrue((field.access & Opcodes.ACC_STATIC) != 0, "and static, as vanilla's was");
	}

	/**
	 * The assertion with teeth. A field that exists but is never assigned reads as null for every consumer, which
	 * is the same NoSuchFieldError one step later. It has to be filled in {@code <clinit>}, from {@code ALL},
	 * through the kernel's own view helper, and assigned right after {@code ALL} itself is — before any mapping
	 * exists to be grouped.
	 */
	@Test
	void theMapIsFilledFromAllInTheClassInitializer() throws IOException {
		ClassNode node = repaired();
		MethodNode clinit = find(node, "<clinit>", "()V");
		assertNotNull(clinit, "KeyMapping has a static initializer");

		int allStore = -1;
		int viewStore = -1;
		boolean readsAll = false;
		boolean callsTheView = false;
		int i = 0;
		for (AbstractInsnNode insn : clinit.instructions) {
			if (insn instanceof FieldInsnNode f && KEY_MAPPING.equals(f.owner) && "ALL".equals(f.name)
					&& "Ljava/util/Map;".equals(f.desc)) {
				if (f.getOpcode() == Opcodes.PUTSTATIC) allStore = i;
			}
			if (insn instanceof FieldInsnNode f && KEY_MAPPING.equals(f.owner) && "MAP".equals(f.name)
					&& VANILLA_MAP.equals(f.desc) && f.getOpcode() == Opcodes.PUTSTATIC) {
				viewStore = i;
			}
			if (insn instanceof FieldInsnNode f && KEY_MAPPING.equals(f.owner) && "ALL".equals(f.name)
					&& f.getOpcode() == Opcodes.GETSTATIC) {
				readsAll = true;
			}
			if (insn instanceof MethodInsnNode call && KERNEL_KEY_MAPPING_MAP.equals(call.owner)
					&& "vanillaView".equals(call.name)) {
				callsTheView = true;
			}
			i++;
		}
		assertTrue(readsAll, "the view must be built from ALL, which the class still keeps and fills");
		assertTrue(callsTheView, "and through KernelKeyMappingMap.vanillaView, which groups on each read");
		assertTrue(viewStore >= 0, "the added MAP field is never assigned, so it reads as null");
		assertTrue(allStore >= 0 && viewStore > allStore,
				"MAP must be assigned right after ALL (" + allStore + " -> " + viewStore + "), before any mapping exists");
	}

	@Test
	void aSecondPassLeavesTheClassAlone() throws IOException {
		byte[] once = new NeoForbricMergedBaseCompatTransformer()
				.transform("net.minecraft.client.KeyMapping", original(), null);
		byte[] twice = new NeoForbricMergedBaseCompatTransformer()
				.transform("net.minecraft.client.KeyMapping", once, null);
		assertSame(once, twice, "re-running must not stack another field on top");
	}

	/** The live field the merge keeps: ONE conflict context, NeoForge's. There is no MinecraftForge one to mirror. */
	@Test
	void theMergeKeepsExactlyOneConflictContextAndItIsNeoForges() throws IOException {
		ClassNode before = new ClassNode();
		new ClassReader(original()).accept(before, ClassReader.SKIP_CODE);
		long contexts = before.fields.stream().filter(f -> "keyConflictContext".equals(f.name)).count();
		assertEquals(1, contexts, "the merge keeps one conflict context");
		assertEquals(NEO_LOOKUP.replace("KeyMappingLookup", "IKeyConflictContext"),
				before.fields.stream().filter(f -> "keyConflictContext".equals(f.name)).findFirst().orElseThrow().desc,
				"and it is NeoForge's — there is no MinecraftForge type on this anchor to mirror from");
	}

	private static MethodNode find(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name) && m.desc.equals(desc)) return m;
		}
		return null;
	}

	private static org.objectweb.asm.tree.FieldNode findField(ClassNode node, String name, String desc) {
		for (var f : node.fields) {
			if (f.name.equals(name) && f.desc.equals(desc)) return f;
		}
		return null;
	}

	private static ClassNode repaired() throws IOException {
		byte[] out = new NeoForbricMergedBaseCompatTransformer()
				.transform("net.minecraft.client.KeyMapping", original(), null);
		ClassNode node = new ClassNode();
		new ClassReader(out).accept(node, 0);
		return node;
	}

	private static byte[] original() throws IOException {
		return TestFixtures.requireEntry(Fixture.STAGED, MERGED, KEY_MAPPING + ".class");
	}
}

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;

/**
 * Covers the item-attribute path of {@code ItemStack}, against the REAL staged base.
 *
 * <p>Re-anchored to 1.21.1. On 26.x this guarded a repair whose visible symptom was that elytra flight did not
 * work: the merge kept vanilla's {@code ItemStack.forEachModifier}, which read the raw
 * {@code DataComponents.ATTRIBUTE_MODIFIERS} component, while NeoForge's own gliding support listens on
 * {@code ItemAttributeModifierEvent} — posted only by {@code getAttributeModifiers()} — so the attribute that
 * decides gliding was never raised. The repair rewrote the two {@code forEachModifier} reads to call NeoForge.
 *
 * <p>On this anchor the base already has that shape: both overloads call {@code getAttributeModifiers()} and
 * neither reads the raw component, and {@code NeoForbricMergedBaseCompatTransformer} carries no attribute repair
 * at all. The repair is therefore unnecessary. The one raw read left in the class is the tooltip's, in
 * {@code addAttributeTooltips}, which must stay raw — routing a tooltip through the event would change what a
 * tooltip says, not what an entity can do.
 */
class MergedBaseItemAttributesTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");

	private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";

	@Test
	void theStagedBaseAlreadyAsksNeoForge() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		ClassNode node = parse(readClass(MERGED_BASE));
		List<MethodNode> each = overloads(node);
		assertEquals(2, each.size(), "both forEachModifier overloads must still be there");
		for (MethodNode m : each) {
			assertTrue(callsNeoForge(m), "the base must already call getAttributeModifiers in " + m.desc);
			assertFalse(readsRawComponent(m), "and must not read the raw component in " + m.desc);
		}
	}

	/** The repair is unnecessary on this anchor, so the pass must hand the class back untouched. */
	@Test
	void theRepairIsUnnecessaryBecauseTheBaseAlreadyHasTheShape() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		byte[] in = readClass(MERGED_BASE);
		byte[] out = transform(in);
		assertSame(in, out, "the staged base already has the repaired shape — the pass must not rewrite it");
	}

	@Test
	void bothOverloadsAskNeoForgeInTheBase() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		ClassNode node = parse(transform(readClass(MERGED_BASE)));
		List<MethodNode> each = overloads(node);
		assertEquals(2, each.size());
		for (MethodNode m : each) {
			assertTrue(callsNeoForge(m), "every overload must ask NeoForge, not just the first: " + m.desc);
			assertFalse(readsRawComponent(m), "the raw read must be gone from " + m.desc);
			new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
		}
	}

	/**
	 * Nothing but the tooltip reads the raw component.
	 *
	 * <p>The 26.x repair rewrote the two reads inside {@code forEachModifier}. On this anchor those two reads are
	 * already gone and exactly one raw read remains, in {@code addAttributeTooltips}. Pinning it here keeps a
	 * future repair from "finishing the job" and changing what a tooltip says.
	 */
	@Test
	void theOnlyRawReadIsTheTooltipOne() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		ClassNode before = parse(readClass(MERGED_BASE));
		ClassNode after = parse(transform(readClass(MERGED_BASE)));
		assertEquals(1, rawReads(before), "exactly one raw read is left, and it is the tooltip's");
		assertEquals(1, rawReads(after), "and the pass must not touch it");
		assertTrue(rawReadsIn(before, "addAttributeTooltips"),
				"the surviving raw read must be addAttributeTooltips'");
	}

	@Test
	void aSecondPassLeavesTheClassAlone() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		byte[] once = transform(readClass(MERGED_BASE));
		assertSame(once, transform(once));
	}

	@Test
	void noAttributeRepairIsClaimedOnAnotherClass() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		byte[] other = readEntry(MERGED_BASE, "net/minecraft/world/item/Item.class");
		// Byte identity used to be the assertion here, and it stopped being available the moment a LATER repair
		// found something of its own in a class this test picked (the radians-to-degrees constant, in
		// LivingEntity). "No repair touches this class" was never what this test meant; "no ATTRIBUTE repair
		// does" is, and the claim reporter says exactly that without depending on what else the pass does.
		List<String> hits = new ArrayList<>();
		new NeoForbricMergedBaseCompatTransformer()
				.transform("net.minecraft.world.item.Item", other, null, hits::add);
		assertFalse(hits.stream().anyMatch(h -> h.toLowerCase().contains("attribute")),
				"the attribute repair is pinned to ItemStack; it claimed another class. Claims seen: " + hits);
	}

	// --- helpers ---------------------------------------------------------------------------------------------

	private static List<MethodNode> overloads(ClassNode node) {
		List<MethodNode> out = new ArrayList<>();
		for (MethodNode m : node.methods) {
			if ("forEachModifier".equals(m.name)) out.add(m);
		}
		return out;
	}

	private static boolean readsRawComponent(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC
					&& "net/minecraft/core/component/DataComponents".equals(f.owner)
					&& "ATTRIBUTE_MODIFIERS".equals(f.name)) {
				return true;
			}
		}
		return false;
	}

	private static boolean callsNeoForge(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode m && "getAttributeModifiers".equals(m.name)) return true;
		}
		return false;
	}

	private static int rawReads(ClassNode node) {
		int n = 0;
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC
						&& "net/minecraft/core/component/DataComponents".equals(f.owner)
						&& "ATTRIBUTE_MODIFIERS".equals(f.name)) {
					n++;
				}
			}
		}
		return n;
	}

	private static boolean rawReadsIn(ClassNode node, String methodName) {
		for (MethodNode m : node.methods) {
			if (!methodName.equals(m.name)) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC
						&& "net/minecraft/core/component/DataComponents".equals(f.owner)
						&& "ATTRIBUTE_MODIFIERS".equals(f.name)) {
					return true;
				}
			}
		}
		return false;
	}

	private static byte[] transform(byte[] bytes) {
		return new NeoForbricMergedBaseCompatTransformer()
				.transform(ITEM_STACK.replace('/', '.'), bytes, null);
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] readClass(Path jar) throws Exception {
		return readEntry(jar, ITEM_STACK + ".class");
	}

	private static byte[] readEntry(Path jar, String entry) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry e = zip.getEntry(entry);
			assertNotNull(e, entry + " must be in " + jar.getFileName());
			try (InputStream in = zip.getInputStream(e)) {
				return in.readAllBytes();
			}
		}
	}
}

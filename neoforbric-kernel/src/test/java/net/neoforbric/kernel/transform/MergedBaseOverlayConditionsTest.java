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
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;

/**
 * The merged {@code OverlayMetadataSection}'s overlay codec, against the REAL staged base.
 *
 * <p>Re-anchored to 1.21.1. On 26.x this guarded a repair that wrapped the list codec in
 * {@code OverlayMetadataSection$OverlayEntry.listCodecForPackType} right after NeoForge's
 * {@code ConditionalOps.decodeListWithElementConditions}: a pack.mcmeta overlay gated by a condition the NeoForge
 * evaluator could not judge was IGNORED, and ignoring it MOUNTED the directory.
 *
 * <p>On this anchor neither half of that anchor exists: {@code OverlayEntry} has no {@code listCodecForPackType}
 * method, its {@code CODEC} is built straight from {@code RecordCodecBuilder.create}, and nothing in the class
 * mentions {@code ConditionalOps}. So {@code vetoUnjudgeableOverlayConditions} correctly declines, and what is
 * left to pin is the shape that replaced the funnel: both the vanilla {@code overlays} and the
 * {@code neoforge:overlays} metadata section types are built over the SAME {@code CODEC} field in
 * {@code OverlayMetadataSection.<clinit>}, so one codec still covers both sections.
 */
class MergedBaseOverlayConditionsTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
	private static final String SECTION = "net/minecraft/server/packs/OverlayMetadataSection";
	private static final String ENTRY = "net/minecraft/server/packs/OverlayMetadataSection$OverlayEntry";
	private static final String CONDITIONAL_OPS = "net/neoforged/neoforge/common/conditions/ConditionalOps";
	private static final String METADATA_SECTION_TYPE = "net/minecraft/server/packs/metadata/MetadataSectionType";

	@Test
	void theConditionalDecoderAnchorIsGoneSoTheRepairDeclines() throws Exception {
		byte[] original = bytesOf(ENTRY);
		ClassNode node = parse(original);

		assertTrue(node.methods.stream().noneMatch(m -> "listCodecForPackType".equals(m.name)),
				"content drift: the method the repair anchors in came back — re-derive the repair");
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode insn : m.instructions) {
				assertFalse(insn instanceof MethodInsnNode call && CONDITIONAL_OPS.equals(call.owner),
						m.name + " reaches " + CONDITIONAL_OPS + " — the repair's anchor is back");
			}
		}

		byte[] out = new NeoForbricMergedBaseCompatTransformer()
				.transform(ENTRY.replace('/', '.'), original, null);
		assertSame(original, out, "with no conditional list decoder to wrap, the repair must be a no-op");
	}

	@Test
	void aSecondPassChangesNothingFurther() throws Exception {
		byte[] once = new NeoForbricMergedBaseCompatTransformer().transform(ENTRY.replace('/', '.'),
				bytesOf(ENTRY), null);
		assertSame(once, new NeoForbricMergedBaseCompatTransformer().transform(ENTRY.replace('/', '.'), once, null));
	}

	/** The premise: both section types read their entries through one codec, so one wrap would cover both. */
	@Test
	void bothOverlaySectionsAreBuiltOverTheSameCodec() throws Exception {
		ClassNode section = parse(bytesOf(SECTION));
		MethodNode clinit = section.methods.stream()
				.filter(m -> "<clinit>".equals(m.name)).findFirst().orElseThrow();

		List<String> sectionNames = new ArrayList<>();
		int fromCodec = 0;
		String previousString = null;
		for (AbstractInsnNode insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) previousString = s;
			if (!(insn instanceof MethodInsnNode call) || !METADATA_SECTION_TYPE.equals(call.owner)
					|| !"fromCodec".equals(call.name)) {
				continue;
			}
			AbstractInsnNode prev = previousReal(call);
			assertTrue(prev instanceof FieldInsnNode codec && SECTION.equals(codec.owner)
							&& "CODEC".equals(codec.name) && codec.getOpcode() == Opcodes.GETSTATIC,
					"each section type must be built over the section's own CODEC field, found " + describe(prev));
			sectionNames.add(previousString);
			fromCodec++;
		}

		assertEquals(2, fromCodec, "the vanilla and the NeoForge section type both build on the same CODEC");
		assertTrue(sectionNames.contains("overlays") && sectionNames.contains("neoforge:overlays"),
				"both section names must be present, found " + sectionNames);
	}

	private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
		AbstractInsnNode previous = insn.getPrevious();
		while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
		return previous;
	}

	private static String describe(AbstractInsnNode insn) {
		return insn == null ? "nothing" : insn.getClass().getSimpleName() + "@" + insn.getOpcode();
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] bytesOf(String internal) throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry entry = zip.getEntry(internal + ".class");
			assertNotNull(entry, internal);
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}
}

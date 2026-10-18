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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * The block-breaking overlay's model-data lookup on 1.21.1, against the REAL staged base.
 *
 * <p>Re-anchored to 1.21.1. On 26.x this guarded a repair for {@code net.minecraft.client.renderer.extract.LevelExtractor},
 * whose block-destroy path asked MinecraftForge's {@code ModelDataManager} — always null on the merged base, so the
 * render frame died the moment any block was being broken — and the repair replaced the lookup with
 * MinecraftForge's {@code ModelData.EMPTY}. None of that exists on this anchor: there is no
 * {@code net/minecraftforge} in the base, no {@code LevelExtractor}, and no such repair in
 * {@code NeoForbricMergedBaseCompatTransformer}.
 *
 * <p>What is real here is NeoForge's own model-data plumbing, and it is already correct: the chunk region holds a
 * {@code Long2ObjectFunction<ModelData>} snapshot, its deprecated 4-argument constructor passes NeoForge's own
 * {@code ModelDataManager.EMPTY_SNAPSHOT} — the empty model data is the game's, not a repair's — and the client
 * level owns a live {@code ModelDataManager}. The assertions below pin that, so a rebuilt base that hands the
 * chunk region an always-null manager fails here instead of NPE-ing mid-frame.
 */
class MergedBaseBlockBreakingOverlayTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");

	private static final String CHUNK_REGION = "net/minecraft/client/renderer/chunk/RenderChunkRegion";
	private static final String CLIENT_LEVEL = "net/minecraft/client/multiplayer/ClientLevel";
	private static final String MODEL_DATA_MANAGER = "net/neoforged/neoforge/client/model/data/ModelDataManager";
	private static final String MODEL_DATA = "net/neoforged/neoforge/client/model/data/ModelData";

	@Test
	void theModelDataLookupIsNeoForgesOwnAndNeverAnAlwaysNullManager() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");

		ClassNode region = parse(readClass(CHUNK_REGION + ".class"));
		assertTrue(hasField(region, "modelDataSnapshot",
						"Lit/unimi/dsi/fastutil/longs/Long2ObjectFunction;"),
				"the chunk region must carry the model-data snapshot it reads");
		assertTrue(passesNeoForgeEmptySnapshot(region),
				"the deprecated constructor must pass NeoForge's own ModelDataManager.EMPTY_SNAPSHOT");
		assertTrue(readsSnapshot(region), "getModelData(BlockPos) must read that snapshot");

		ClassNode level = parse(readClass(CLIENT_LEVEL + ".class"));
		assertTrue(hasField(level, "modelDataManager", "L" + MODEL_DATA_MANAGER + ";"),
				"the client level must own a live ModelDataManager");
		assertTrue(readsManager(level), "getModelDataManager() must return that field, never an always-null manager");
	}

	@Test
	void aSecondPassLeavesTheRepairedClassAlone() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		byte[] in = readClass(CHUNK_REGION + ".class");
		byte[] once = transform(in);
		assertSame(in, once, "no repair targets this class any more — the pass must hand it back untouched");
		assertSame(once, transform(once));

		ClassNode after = parse(once);
		MethodNode get = method(after, "getModelData",
				"(Lnet/minecraft/core/BlockPos;)L" + MODEL_DATA + ";");
		new Analyzer<>(new BasicVerifier()).analyze(after.name, get);
	}

	// --- helpers -------------------------------------------------------------------------------------------------

	private static boolean passesNeoForgeEmptySnapshot(ClassNode node) {
		for (MethodNode m : node.methods) {
			if (!"<init>".equals(m.name) || !m.desc.endsWith("[Lnet/minecraft/client/renderer/chunk/RenderChunk;)V")) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC
						&& MODEL_DATA_MANAGER.equals(f.owner) && "EMPTY_SNAPSHOT".equals(f.name)) return true;
			}
		}
		return false;
	}

	private static boolean readsSnapshot(ClassNode node) {
		for (MethodNode m : node.methods) {
			if (!"getModelData".equals(m.name)) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD
						&& "modelDataSnapshot".equals(f.name)) return true;
			}
		}
		return false;
	}

	private static boolean readsManager(ClassNode node) {
		for (MethodNode m : node.methods) {
			if (!"getModelDataManager".equals(m.name)) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD
						&& "modelDataManager".equals(f.name)) return true;
			}
		}
		return false;
	}

	private static boolean hasField(ClassNode node, String name, String desc) {
		return node.fields.stream().anyMatch(f -> name.equals(f.name) && desc.equals(f.desc));
	}

	private static byte[] transform(byte[] bytes) {
		return new NeoForbricMergedBaseCompatTransformer().transform(CHUNK_REGION.replace('/', '.'), bytes, null);
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name) && m.desc.equals(desc)) return m;
		}
		throw new AssertionError("no method " + name + desc + " in " + node.name);
	}

	private static byte[] readClass(String entry) throws Exception {
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			assertNotNull(found, entry + " missing from the staged merged base");
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}
}

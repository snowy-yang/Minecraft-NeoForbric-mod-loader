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
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;

/**
 * Covers {@code Mob.getSpawnType()} against the REAL staged base.
 *
 * <p>Re-anchored to 1.21.1. On 26.x this test guarded a repair that moved {@code Mob.getSpawnReason()}'s read off a
 * never-written {@code spawnReason} field (of the 26.x type {@code net.minecraft.world.entity.EntitySpawnReason})
 * onto the {@code spawnType} field the game actually writes, and deliberately left the dead declaration in place.
 * None of that shape exists on this anchor: the spawn enum is {@code net.minecraft.world.entity.MobSpawnType},
 * {@code Mob} declares exactly ONE spawn field ({@code spawnType}), there is no {@code spawnReason} declaration and
 * no {@code getSpawnReason()} accessor, and {@code NeoForbricMergedBaseCompatTransformer} carries no spawn-field
 * repair at all. The repair is therefore unnecessary because the base already has the coherent shape.
 *
 * <p>What is worth guarding is that coherence: the accessor must read the one field something writes, and the
 * dead-field shape must not come back. A rebuilt base that reintroduces a second, unwritten spawn field fails
 * here instead of answering null for every mob.
 *
 * <p>Checked against the staged base rather than a fixture, because what can drift is what the OTHER
 * repository's merge emits.
 */
class MergedBaseSpawnReasonTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
	private static final String ENTRY = "net/minecraft/world/entity/Mob.class";
	private static final String OWNER = "net/minecraft/world/entity/Mob";
	private static final String SPAWN_TYPE = "Lnet/minecraft/world/entity/MobSpawnType;";

	@Test
	void theAccessorReadsTheOneFieldSomethingWrites() throws Exception {
		ClassNode node = parse(original());

		assertTrue(node.fields.stream().noneMatch(f -> "spawnReason".equals(f.name)),
				"content drift: a second, unwritten spawn field came back — re-derive the repair that moved the read");
		assertEquals(1, node.fields.stream()
						.filter(f -> "spawnType".equals(f.name) && SPAWN_TYPE.equals(f.desc)).count(),
				"exactly one spawn field, typed MobSpawnType");
		assertTrue(writes(node, "spawnType") > 0, "spawnType is the field the game writes; that is the premise");
		assertEquals("spawnType", fieldRead(method(node, "getSpawnType", "()" + SPAWN_TYPE)));
	}

	@Test
	void nothingReadsADeadSpawnReasonField() throws Exception {
		// The 26.x repair tolerated the dead `spawnReason` declaration, because an access widener or a mixin might
		// name it. This base has no such field, so the only thing left to assert is that nothing reads one.
		ClassNode node = parse(original());

		int reads = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD
						&& OWNER.equals(f.owner) && "spawnReason".equals(f.name)) {
					reads++;
				}
			}
		}

		assertEquals(0, reads, "nothing may read spawnReason — this base does not declare it, so any read is a link error");
	}

	@Test
	void aSecondPassChangesNothing() throws Exception {
		byte[] once = new NeoForbricMergedBaseCompatTransformer().transform(
				"net.minecraft.world.entity.Mob", original(), null);
		byte[] twice = new NeoForbricMergedBaseCompatTransformer().transform(
				"net.minecraft.world.entity.Mob", once, null);

		assertSame(once, twice, "whatever the pass did to Mob on the first run, a second run must find nothing more");
	}

	@Test
	void noOtherClassIsDraggedIntoTheSpawnFieldRule() throws Exception {
		// On 26.x this pinned the repair to Mob by name: a rule that rewrote by field name alone would have hit an
		// unrelated class. The repair is gone, so what survives is the same guard stated against the claims and
		// the bytecode: no spawn-field rule may claim LivingEntity, and no class may be given a reach into Mob's
		// spawn field by the pass.
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		byte[] other = readClass("net/minecraft/world/entity/LivingEntity.class");
		assertNotNull(other, "content drift: LivingEntity absent from this base");

		List<String> hits = new ArrayList<>();
		byte[] out = new NeoForbricMergedBaseCompatTransformer()
				.transform("net.minecraft.world.entity.LivingEntity", other, null, hits::add);

		assertFalse(hits.stream().anyMatch(h -> h.toLowerCase().contains("spawn")),
				"a spawn-field rule claimed LivingEntity instead of Mob. Claims seen: " + hits);

		ClassNode node = parse(out);
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode f && OWNER.equals(f.owner)
						&& ("spawnType".equals(f.name) || "spawnReason".equals(f.name))) {
					fail("LivingEntity now reaches Mob." + f.name + " — the pass moved a spawn read across classes");
				}
			}
		}
	}

	private static String fieldRead(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD && OWNER.equals(f.owner)) {
				return f.name;
			}
		}
		return null;
	}

	private static int writes(ClassNode node, String field) {
		int n = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD
						&& OWNER.equals(f.owner) && field.equals(f.name)) {
					n++;
				}
			}
		}
		return n;
	}

	private static byte[] original() throws IOException {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		byte[] in = readClass(ENTRY);
		assertNotNull(in, "content drift: Mob absent from this base");
		return in;
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
		assertNotNull(null, "missing " + name + desc);
		throw new AssertionError();
	}

	private static byte[] readClass(String entry) throws IOException {
		try (ZipFile jar = new ZipFile(MERGED_BASE.toFile())) {
			var e = jar.getEntry(entry);
			if (e == null) return null;
			try (InputStream in = jar.getInputStream(e)) {
				return in.readAllBytes();
			}
		}
	}
}

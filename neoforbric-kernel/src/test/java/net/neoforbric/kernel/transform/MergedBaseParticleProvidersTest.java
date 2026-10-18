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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;

/**
 * Covers the particle-provider map of {@code ParticleEngine}, the 1.21.1 counterpart of 26.x
 * {@code ParticleResources}, against the REAL staged base.
 *
 * <p>Re-anchored to 1.21.1. On 26.x this guarded a repair for a class that carried TWO {@code providers}
 * declarations under the same name: vanilla's {@code Int2ObjectMap} keyed by particle id and NeoForge's
 * {@code Map<Identifier, ?>}, with only the NeoForge one ever written, so the vanilla-typed map fabric-api reads
 * directly stayed null and a particle registered the vanilla way never rendered. On this anchor the class is
 * {@code net.minecraft.client.particle.ParticleEngine}, it declares ONE {@code providers} field
 * ({@code java.util.Map}, keyed by {@code ResourceLocation}), there is no second {@code providers} declaration and
 * no {@code providersByName} twin, and {@code NeoForbricMergedBaseCompatTransformer} carries no particle repair
 * that fires here. The repair is therefore unnecessary because the base already has the coherent shape.
 *
 * <p>A synthetic fixture would be the wrong subject twice over: what can drift is what the other repository's
 * merge emits. So the base is checked directly, and the surviving assertions are the ones with teeth — one map,
 * filled by {@code register}, read by {@code makeParticle}.
 */
class MergedBaseParticleProvidersTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
	private static final String ENTRY = "net/minecraft/client/particle/ParticleEngine.class";
	private static final String OWNER = "net/minecraft/client/particle/ParticleEngine";
	private static final String ID_KEYED = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;";
	private static final String NAME_KEYED = "Ljava/util/Map;";

	@Test
	void thereIsOneProviderMapAndItIsTheNameKeyedOne() throws Exception {
		ClassNode node = parse(original());

		long providers = node.fields.stream().filter(f -> "providers".equals(f.name)).count();
		assertEquals(1, providers, "content drift: a second `providers` declaration came back — re-derive the repair");
		assertTrue(hasField(node, "providers", NAME_KEYED),
				"the one map must be the name-keyed java.util.Map the vanilla registry reads");
		assertFalse(hasField(node, "providers", ID_KEYED),
				"and there must be no int-keyed `providers` twin for the repair to give a view of");
	}

	/**
	 * The assertion that rejects "an empty map is enough". The field must be the one {@code register} fills AND the
	 * one {@code makeParticle} reads; a map nothing writes, or that nothing reads, satisfies the shape and still
	 * loses every registration.
	 */
	@Test
	void registerFillsTheMapThatMakeParticleReads() throws Exception {
		ClassNode node = parse(original());

		assertEquals(1, writers(node), "the map is initialised once, in <init>");
		MethodNode register = method(node, "register",
				"(Lnet/minecraft/core/particles/ParticleType;Lnet/minecraft/client/particle/ParticleProvider;)V");
		boolean puts = false;
		for (AbstractInsnNode insn : register.instructions) {
			if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD
					&& OWNER.equals(f.owner) && "providers".equals(f.name)) {
				puts = true;
			}
		}
		assertTrue(puts, "register(ParticleType, ParticleProvider) must read the providers map it registers into");

		MethodNode make = method(node, "makeParticle",
				"(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)Lnet/minecraft/client/particle/Particle;");
		boolean reads = false;
		for (AbstractInsnNode insn : make.instructions) {
			if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD
					&& OWNER.equals(f.owner) && "providers".equals(f.name)) {
				reads = true;
			}
		}
		assertTrue(reads, "makeParticle must read the same map — otherwise a registration is never rendered");
	}

	/**
	 * 26.x carried a {@code providersByName} twin that lost its only producer in the merge. Nothing may read such a
	 * field here, because this base does not declare one.
	 */
	@Test
	void nothingReadsAProvidersByNameField() throws Exception {
		ClassNode node = parse(original());
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD) {
					assertFalse("providersByName".equals(f.name),
							method.name + " reads providersByName, which this base does not declare");
				}
			}
		}
	}

	@Test
	void aSecondPassLeavesTheClassAlone() throws Exception {
		byte[] in = original();
		byte[] once = new NeoForbricMergedBaseCompatTransformer().transform(
				"net.minecraft.client.particle.ParticleEngine", in, null);
		assertSame(in, once, "no repair targets this class any more — the pass must hand it back untouched");
		assertSame(once, new NeoForbricMergedBaseCompatTransformer().transform(
				"net.minecraft.client.particle.ParticleEngine", once, null));
	}

	@Test
	void anotherClassIsUntouched() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		byte[] other = readClass("net/minecraft/client/particle/ParticleProvider.class");
		assertNotNull(other, "content drift: ParticleProvider absent from this base");
		assertSame(other, new NeoForbricMergedBaseCompatTransformer()
				.transform("net.minecraft.client.particle.ParticleProvider", other, null));
	}

	private static boolean hasField(ClassNode node, String name, String desc) {
		return node.fields.stream().anyMatch(f -> name.equals(f.name) && desc.equals(f.desc));
	}

	private static int writers(ClassNode node) {
		int n = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD
						&& OWNER.equals(f.owner) && "providers".equals(f.name)) n++;
			}
		}
		return n;
	}

	private static byte[] original() throws IOException {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		byte[] in = readClass(ENTRY);
		assertNotNull(in, "content drift: ParticleEngine absent from this base");
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

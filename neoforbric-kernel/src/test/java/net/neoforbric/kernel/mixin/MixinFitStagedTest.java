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

package net.neoforbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;

/**
 * The three anchor kinds over REAL fabric-api mixins and the RAW merged base — the verdicts before any repair.
 *
 * <p>Two of these used to read fabric-api classes that 0.116.17 does not ship: {@code RenderPipelineBuilderMixin}
 * (fabric-rendering-v1) and {@code AttributeSupplierBuilderAccessor} (fabric-object-builder-api-v1), and
 * {@code transfer/SimpleContainerMixin}, which is {@code transfer/SimpleInventoryMixin}. The accessor is rebuilt
 * here in the shape it had — the field it names, {@code AttributeSupplier$Builder.builder}, is still widened by the
 * merge and {@code WidenedFieldTwinInjector} still carries that row — because the fabric class that read it is not
 * here; the other two are asserted on the real classes that carry the same contract.
 */
class MixinFitStagedTest {
	static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar").normalize();
	static final Path CLIENT_MODS = Path.of(System.getProperty("user.dir"), "run", "client-kernel", "mods").normalize();

	static final String SIMPLE_CONTAINER_MIXIN = "net/fabricmc/fabric/mixin/transfer/SimpleInventoryMixin.class";
	static final String ATTRIBUTE_BUILDER_OWNER = "net/minecraft/world/entity/ai/attributes/AttributeSupplier$Builder";
	static final String IMMUTABLE_MAP_BUILDER = "Lcom/google/common/collect/ImmutableMap$Builder;";

	/**
	 * Used to assert that fabric-rendering-v1's {@code RenderPipelineBuilderMixin} read PARTIAL on the raw base with
	 * the arity named ("handler wraps a 11-arg constructor, the call site constructs with 12"). That mixin is not in
	 * 0.116.17 and no mixin the module does ship has an arity mismatch, so the same contract — a NEW point the raw
	 * base cannot answer is named as an anchor — is asserted on the real NEW point that cannot bind.
	 */
	@Test
	void aNewPointTheRawBaseDoesNotHaveIsNamedAsAnAnchor() throws Exception {
		Function<String, byte[]> resolver = rawResolver();
		ClassNode mixin = StagedFabricMixinFixture.resolved("fabric-item-api-v1",
				"net/fabricmc/fabric/mixin/item/BrewingStandBlockEntityMixin");
		MixinFit.Result r = MixinFit.evaluate(StagedFabricMixinFixture.bytes(mixin), resolver);
		assertEquals(MixinFit.Verdict.PARTIAL, r.verdict(), r.unresolved().toString());
		assertTrue(r.unresolved().stream().anyMatch(u -> u.contains("@At(NEW)") && u.contains("is not constructed in")),
				r.unresolved().toString());
	}

	/**
	 * Mixin's InvalidAccessorException on every fabric-api boot, now named as an anchor: the merged
	 * {@code AttributeSupplier$Builder.builder} is a {@code java.util.Map}, where vanilla declares — and the accessor
	 * reads — an {@code ImmutableMap.Builder}.
	 */
	@Test
	void theAttributeBuilderAccessorCannotBindOnTheRawBase() throws Exception {
		Function<String, byte[]> resolver = rawResolver();
		MixinFit.Result r = MixinFit.evaluate(attributeBuilderAccessor(), resolver);
		assertEquals(1, r.unresolved().size(), r.unresolved().toString());
		assertTrue(r.unresolved().get(0).contains("@Accessor field")
				&& r.unresolved().get(0).contains("builder:Lcom/google/common/collect/ImmutableMap$Builder;"), r.unresolved().toString());
	}

	/** …and binds once the twin injector has restored vanilla's descriptor beside the merged one. */
	@Test
	void theAttributeBuilderAccessorBindsAgainstTheTwinnedBase() throws Exception {
		Function<String, byte[]> raw = rawResolver();
		String owner = ATTRIBUTE_BUILDER_OWNER;
		byte[] twinned = new net.neoforbric.kernel.transform.WidenedFieldTwinInjector().transform(owner.replace('/', '.'), raw.apply(owner + ".class"), null);
		Function<String, byte[]> after = name -> (owner + ".class").equals(name) ? twinned : raw.apply(name);
		MixinFit.Result r = MixinFit.evaluate(attributeBuilderAccessor(), after);
		assertTrue(r.unresolved().isEmpty(), r.unresolved().toString());
	}

	/**
	 * {@code fabric-object-builder-api-v1}'s {@code AttributeSupplierBuilderAccessor}, in the shape 0.116.17 no
	 * longer ships but the merged base still needs: an {@code @Accessor} for {@code AttributeSupplier$Builder.builder}
	 * as vanilla declares it.
	 */
	static byte[] attributeBuilderAccessor() {
		org.objectweb.asm.tree.ClassNode mixin = new org.objectweb.asm.tree.ClassNode();
		mixin.version = org.objectweb.asm.Opcodes.V21;
		mixin.access = org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_ABSTRACT;
		mixin.name = "net/fabricmc/fabric/mixin/object/builder/AttributeSupplierBuilderAccessor";
		org.objectweb.asm.tree.AnnotationNode type =
				new org.objectweb.asm.tree.AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new java.util.ArrayList<>(java.util.List.of("value",
				new java.util.ArrayList<>(java.util.List.of(org.objectweb.asm.Type.getObjectType(ATTRIBUTE_BUILDER_OWNER)))));
		mixin.invisibleAnnotations = new java.util.ArrayList<>(java.util.List.of(type));
		org.objectweb.asm.tree.MethodNode getter = new org.objectweb.asm.tree.MethodNode(
				org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_ABSTRACT, "builder", "()" + IMMUTABLE_MAP_BUILDER, null, null);
		org.objectweb.asm.tree.AnnotationNode accessor =
				new org.objectweb.asm.tree.AnnotationNode("Lorg/spongepowered/asm/mixin/gen/Accessor;");
		accessor.values = new java.util.ArrayList<>(java.util.List.of("value", "builder"));
		getter.visibleAnnotations = new java.util.ArrayList<>(java.util.List.of(accessor));
		mixin.methods = new java.util.ArrayList<>(java.util.List.of(getter));
		return StagedFabricMixinFixture.bytes(mixin);
	}

	/**
	 * An explicit-descriptor selector resolves on the merged base as Mixin applies it: 0.116.17's transfer mixin
	 * names {@code SimpleContainer.setItem(int, ItemStack)} with its descriptor and the merged body still declares
	 * it, so R1 has nothing to move. Used to assert the opposite — PARTIAL, R1's to retarget — on 26.x's
	 * {@code SimpleContainerMixin}, which is not in this module.
	 */
	@Test
	void anExplicitDescriptorSelectorResolvesOnTheMergedBase() throws Exception {
		Function<String, byte[]> resolver = rawResolver();
		ClassNode mixin = StagedFabricMixinFixture.resolved("fabric-transfer-api-v1",
				"net/fabricmc/fabric/mixin/transfer/SimpleInventoryMixin");
		MixinFit.Result r = MixinFit.evaluate(StagedFabricMixinFixture.bytes(mixin), resolver);
		assertEquals(MixinFit.Verdict.FIT, r.verdict(), r.unresolved().toString());
		assertEquals(r.total(), r.resolved(), r.toString());
	}

	// ---------------------------------------------------------------------------------------------------------------

	static Function<String, byte[]> rawResolver() {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		return name -> {
			try {
				return readFromJar(MERGED_BASE, name);
			} catch (Exception e) {
				return null;
			}
		};
	}

	static byte[] nested(String module, String entry) throws Exception {
		Path fabricApi = fabricApiJar();
		TestFixtures.require(Fixture.STAGED, fabricApi != null, "fabric-api jar absent from run/client-kernel/mods");
		byte[] bytes = readFromNestedJar(fabricApi, module, entry);
		// The pinned build is the one these verdicts were read from; another fabric-api in the pack may lack the entry.
		if (fabricApi.getFileName().toString().equals(TestFixtures.FABRIC_API_JAR)) {
			assertNotNull(bytes, entry + " absent from the nested " + module + " of " + fabricApi);
		}
		TestFixtures.require(Fixture.THIRD_PARTY, bytes != null, entry + " absent from the nested " + module);
		return bytes;
	}

	static Path fabricApiJar() throws Exception {
		if (Files.isDirectory(CLIENT_MODS)) try (var files = Files.list(CLIENT_MODS)) {
			Path found = files.filter(p -> p.getFileName().toString().startsWith("fabric-api-")).findFirst().orElse(null);
			if (found != null) return found;
		}
		Path pinned = TestFixtures.fabricApi();
		return Files.isRegularFile(pinned) ? pinned : null;
	}

	static byte[] readFromJar(Path jar, String entry) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			if (found == null) return null;
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}

	static byte[] readFromNestedJar(Path outer, String modulePrefix, String entry) throws Exception {
		try (ZipFile zip = new ZipFile(outer.toFile())) {
			for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
				ZipEntry nested = e.nextElement();
				if (!nested.getName().startsWith("META-INF/jars/" + modulePrefix)) continue;
				Path tmp = Files.createTempFile("neoforbric-nested", ".jar");
				try (InputStream in = zip.getInputStream(nested)) {
					Files.write(tmp, in.readAllBytes());
				}
				try {
					byte[] bytes = readFromJar(tmp, entry);
					if (bytes != null) return bytes;
				} finally {
					Files.deleteIfExists(tmp);
				}
			}
		}
		return null;
	}
}

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
import org.junit.jupiter.api.AfterEach;
import org.objectweb.asm.tree.ClassNode;
import org.junit.jupiter.api.Test;

/**
 * R1 and R2 over the REAL fabric-api mixins and the REAL merged base: the two container mixins of
 * fabric-transfer-api-v1, the two air-check redirects of fabric-block-api-v1, and one the rules must refuse.
 *
 * <p>Every mixin here is read as the kernel hands it to a rule — {@code StagedFabricMixinFixture.resolved}, the
 * module remapped into the base's names with its annotation strings resolved through the module's own refmap. Read
 * raw, a Yarn-authored selector names a class the Mojmap base does not have, and every verdict is a miss.
 *
 * <p>Two of these used to be about classes 0.116.17 does not ship: {@code transfer/SimpleContainerMixin} and
 * {@code transfer/BaseContainerBlockEntityMixin} are {@code SimpleInventoryMixin} and
 * {@code LockableContainerBlockEntityMixin}, and {@code block/LevelChunkSectionMixin} is {@code ChunkSectionMixin};
 * fabric-content-registries ships no {@code FuelValuesMixin}. The 26.x premise behind the first — NeoForge moving
 * {@code setChanged()} into a 3-argument {@code setItem} — does not hold here: the merged
 * {@code SimpleContainer.setItem(int, ItemStack)} still calls {@code setChanged()} itself.
 */
class MixinRetargetStagedTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar").normalize();
	private static final Path CLIENT_MODS = Path.of(System.getProperty("user.dir"), "run", "client-kernel", "mods").normalize();

	private static final String SIMPLE_CONTAINER_MIXIN = "net/fabricmc/fabric/mixin/transfer/SimpleInventoryMixin.class";
	private static final String BASE_CONTAINER_MIXIN = "net/fabricmc/fabric/mixin/transfer/LockableContainerBlockEntityMixin.class";
	private static final String FUEL_VALUES_MIXIN = "net/fabricmc/fabric/mixin/content/registry/FuelValuesMixin.class";
	private static final String SET_ITEM_STUB = "setItem(ILnet/minecraft/world/item/ItemStack;)V";
	private static final String SET_ITEM_DELEGATE = "setItem(ILnet/minecraft/world/item/ItemStack;Z)V";

	@AfterEach
	void reset() {
		MixinRetarget.reset();
	}

	/**
	 * Used to assert that both container mixins read PARTIAL — the {@code setChanged()} call had moved into a
	 * 3-argument {@code setItem} — and that R1's plan rewrote their selector onto it. On 1.21.1 the merged
	 * {@code SimpleContainer.setItem(int, ItemStack)} still calls {@code setChanged()} itself, so both bind as
	 * authored: what this asserts now is that they FIT and that R1 leaves them alone.
	 */
	@Test
	void fabricTransfersTwoContainerMixinsFitAsAuthored() throws Exception {
		Function<String, byte[]> resolver = mergedResolver();
		for (String entry : new String[] { SIMPLE_CONTAINER_MIXIN, BASE_CONTAINER_MIXIN }) {
			ClassNode mixin = StagedFabricMixinFixture.resolved("fabric-transfer-api-v1", withoutClass(entry));
			MixinFit.Result fit = MixinFit.evaluate(StagedFabricMixinFixture.bytes(mixin), resolver);
			assertEquals(MixinFit.Verdict.FIT, fit.verdict(), entry + ": " + fit.unresolved());
			assertEquals(fit.total(), fit.resolved(), entry + ": " + fit.toString());

			MixinRetarget.Plan plan = MixinRetarget.plan(mixin, resolver);
			assertTrue(plan.isEmpty(), entry + ": " + plan.describe());
		}
	}

	/** R2 over the real fabric-block-api-v1 mixins: the isAir→isEmpty swap the census pins. */
	/**
	 * R2 over the real fabric-block-api-v1 air checks. Both mixins are read as the kernel reads them, and both end
	 * FIT: {@code ChunkSectionMixin}'s redirect onto {@code BlockState.isAir} in {@code LevelChunkSection.setBlockState}
	 * is the one the merged body replaced with {@code isEmpty} and R2 moves; the counter mixin's point still binds
	 * where it was written and R2 must leave it alone.
	 */
	@Test
	void fabricBlockApisAirChecksGoPartialToFit() throws Exception {
		Function<String, byte[]> resolver = mergedResolver();
		for (String entry : new String[] { "block/ChunkSectionMixin", "block/ChunkSectionBlockStateCounterMixin" }) {
			ClassNode mixin = StagedFabricMixinFixture.resolved("fabric-block-api-v1", "net/fabricmc/fabric/mixin/" + entry);
			byte[] bytes = StagedFabricMixinFixture.bytes(mixin);
			MixinFit.Result raw = MixinFit.evaluate(bytes, resolver);
			MixinRetarget.Plan plan = MixinRetarget.plan(mixin, resolver);
			MixinFit.Result after = MixinFit.evaluate(MixinRetarget.rewritten(bytes, plan), resolver);
			assertEquals(MixinFit.Verdict.FIT, after.verdict(), entry + " after: " + after.unresolved());
			if (raw.verdict() == MixinFit.Verdict.PARTIAL) {
				assertEquals(1, plan.rewrites().size(), entry + ": " + plan.describe());
				assertEquals(MixinRetarget.Element.AT_TARGET, plan.rewrites().get(0).element());
				assertEquals("Lnet/minecraft/world/level/block/state/BlockState;isEmpty()Z", plan.rewrites().get(0).to());
			} else {
				assertEquals(MixinFit.Verdict.FIT, raw.verdict(), entry + ": " + raw.unresolved());
				assertTrue(plan.isEmpty(), entry + " binds as written: " + plan.describe());
			}
		}
	}

	/**
	 * Honest negative: the rules move only what they can prove. Used to assert it on fabric-content-registries'
	 * {@code FuelValuesMixin}, whose {@code @Local} sugars name the stub's parameters; that mixin is not in
	 * 0.116.17, so the negative is asserted on a real mixin no rule covers either — fabric-object-builder's
	 * settings accessor, whose misses are all {@code @Accessor} fields, which no selector or @At row can move.
	 */
	@Test
	void aMixinWhoseMissesNoRuleCoversIsLeftAlone() throws Exception {
		Function<String, byte[]> resolver = mergedResolver();
		ClassNode mixin = StagedFabricMixinFixture.resolved("fabric-object-builder-api-v1",
				"net/fabricmc/fabric/mixin/object/builder/AbstractBlockSettingsAccessor");
		MixinFit.Result raw = MixinFit.evaluate(StagedFabricMixinFixture.bytes(mixin), resolver);
		assertEquals(MixinFit.Verdict.PARTIAL, raw.verdict(), "premise");
		assertTrue(raw.unresolved().stream().allMatch(u -> u.contains("@Accessor field")), raw.unresolved().toString());
		MixinRetarget.Plan plan = MixinRetarget.plan(mixin, resolver);
		assertTrue(plan.isEmpty(), "an @Accessor field is not a selector or an @At row: " + plan.describe());
	}

	private static String withoutClass(String entry) {
		return entry.endsWith(".class") ? entry.substring(0, entry.length() - ".class".length()) : entry;
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static Function<String, byte[]> mergedResolver() {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		return name -> {
			try {
				return readFromJar(MERGED_BASE, name);
			} catch (Exception e) {
				return null;
			}
		};
	}

	private static byte[] nested(String module, String entry) throws Exception {
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

	private static Path fabricApiJar() throws Exception {
		if (Files.isDirectory(CLIENT_MODS)) try (var files = Files.list(CLIENT_MODS)) {
			Path found = files.filter(p -> p.getFileName().toString().startsWith("fabric-api-")).findFirst().orElse(null);
			if (found != null) return found;
		}
		Path pinned = TestFixtures.fabricApi();
		return Files.isRegularFile(pinned) ? pinned : null;
	}

	private static byte[] readFromJar(Path jar, String entry) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			if (found == null) return null;
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}

	private static byte[] readFromNestedJar(Path outer, String modulePrefix, String entry) throws Exception {
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

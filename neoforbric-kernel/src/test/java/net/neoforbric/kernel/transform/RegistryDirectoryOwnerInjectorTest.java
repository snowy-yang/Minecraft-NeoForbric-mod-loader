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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.neoforbric.api.DiscoveredMod;
import net.neoforbric.api.Ecosystem;
import net.neoforbric.api.ModPresence;
import net.neoforbric.kernel.boot.KernelRegistryDirectories;
import net.neoforbric.kernel.mixin.MergedBaseMixinCompat;

/**
 * WorldWeaver's world presets and biome data are read from the directory WorldWeaver ships them in, as on native
 * Fabric.
 *
 * <p>Real bytes throughout, wherever they exist on this machine: {@code Registries}' directory methods from the
 * merged base, NeoForge's own {@code prefixNamespace}, and the two mixin handlers that decide the outcome on native
 * Fabric — fabric-registry-sync's {@code RegistriesMixin} and WorldWeaver's {@code RegistryDataLoaderMixinEarly} —
 * out of the sweep pack's jars.
 *
 * <p><b>Re-anchored, and most of this class went with the 26.x base.</b> It used to load {@code Registries} from
 * three jars into a throwaway loader and call the two loaders' handlers by hand, so it could show that wover's
 * presets come out as on native Fabric and that the injector hands every answer to the owner. Two things are gone on
 * 1.21.1: {@code Registries} has no {@code registryDirPath} at all (it has {@code elementsDirPath} and
 * {@code tagsDirPath}, and no {@code componentsDirPath}), and fabric-registry-sync's {@code RegistriesMixin} ships
 * no directory handler — only {@code init} and {@code hasInitialised} — so the race this injector arbitrated cannot
 * happen here. The arms therefore drove a seam that does not exist, off jars that are not in the sweep pack, and were
 * skipped rather than run. What is left asserts the real bytes: the seam the injector needs is absent, it stands
 * down, and neither loader ships a handler that would put it back.
 */
class RegistryDirectoryOwnerInjectorTest {
	private static final Path STAGED = TestFixtures.stagedRoot();
	private static final Path MERGED_BASE = STAGED.resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
	private static final Path SWEEP = Path.of("build/compat-inputs/sweep-1.21.1/mods");
	private static final Path FABRIC_API = SWEEP.resolve("fabric-api-0.116.17+1.21.1.jar");
	private static final Path WORLDWEAVER = SWEEP.resolve("worldweaver-21.0.13.jar");

	private static final String REGISTRIES = "net/minecraft/core/registries/Registries";
	private static final String COMMON_HOOKS = "net/neoforged/neoforge/common/CommonHooks";
	private static final String FABRIC_MIXIN = "net/fabricmc/fabric/mixin/registry/sync/RegistriesMixin";
	private static final String WOVER_MIXIN = "org/betterx/wover/core/mixin/registry/RegistryDataLoaderMixinEarly";
	private static final String DIR_DESC = "(Lnet/minecraft/resources/ResourceKey;)Ljava/lang/String;";
	private static final String WOVER_HANDLER_DESC = "(Lnet/minecraft/class_5321;"
			+ "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V";

	@BeforeEach
	@AfterEach
	void reset() {
		System.clearProperty(RegistryDirectoryOwnerInjector.PROPERTY);
		ModPresence.publishForgeFamily(List.of());
		ModPresence.publishFabric(List.of());
		// The hook is told fabric-registry-sync's modifier is beside Registries, which is what the arms used to
		// simulate; on this base nothing ships one, which is what the test below pins.
		KernelRegistryDirectories.resetForTests(Boolean.TRUE);
	}

	@Test
	void theMergedBodyIsNeoForgesAndTheInjectorsSeamIsNotOnThisBase() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		byte[] in = classBytes(MERGED_BASE, REGISTRIES);
		ClassNode merged = node(in);

		// Re-anchored: this drove the injector over the merged Registries and asserted the edit handed every answer
		// to the owner, anchored on RegistryDirectoryOwnerInjector.METHOD. On 1.21.1 that method is not there — the
		// two directory methods are elementsDirPath and tagsDirPath — so the injector stands down, and what can be
		// asserted is the premise it would have to match.
		assertTrue(merged.methods.stream().noneMatch(m -> RegistryDirectoryOwnerInjector.METHOD.equals(m.name)),
				"the injector's anchor is on this base again — re-derive the edit it makes: " + names(merged));
		assertTrue(merged.methods.stream().noneMatch(m -> m.name.endsWith("DirPath") && !DIR_DESC.equals(m.desc)),
				"a directory method with another shape appeared: " + names(merged));
		for (String dir : List.of("elementsDirPath", "tagsDirPath")) {
			MethodNode body = method(merged, dir);
			assertEquals(DIR_DESC, body.desc, dir);
			assertTrue(calls(body, COMMON_HOOKS, "prefixNamespace"),
					"the merged body prefixes through NeoForge's hook — if it stopped, re-derive the repair");
		}

		byte[] out = transform(in);
		assertSame(in, out, "the anchor is missing, so the injector must stand down and say so");
		for (MethodNode m : node(out).methods) {
			assertTrue(!calls(m, RegistryDirectoryOwnerInjector.HOOK_OWNER, "registryDirPath"),
					m.name + " hands its answer to the owner, but this base has no seam to do it from");
		}
	}

	@Test
	void neitherLoaderShipsTheDirectoryRaceThisInjectorArbitrated() throws Exception {
		// The arms that stood in for the two loaders are gone with their 26.x jars. Both halves are asserted against
		// the real 1.21.1 jars, because the day fabric-registry-sync's handler comes back is the day this repair has
		// to be re-derived instead of stood down, and wover's own handler is what it would have to arbitrate against.
		requireAll(FABRIC_API, WORLDWEAVER);

		ClassNode fabric = node(nestedClassBytes(FABRIC_API, "fabric-registry-sync-v0-", FABRIC_MIXIN));
		Set<String> fabricMethods = names(fabric);
		assertTrue(fabricMethods.stream().anyMatch(name -> name.startsWith("init(")),
				"the mixin is still there, just without a directory handler: " + fabricMethods);
		assertTrue(fabricMethods.stream().noneMatch(name -> name.startsWith("prependDirectory")
						|| name.startsWith("prependTagDirectory")),
				"fabric-registry-sync has a directory handler again — re-derive the repair this test stands down on: "
						+ fabricMethods);

		ClassNode wover = node(nestedClassBytes(WORLDWEAVER, "wover-core-api-", WOVER_MIXIN));
		assertEquals(WOVER_HANDLER_DESC, method(wover, "prependDirectoryWithNamespace").desc,
				"wover's own handler is still what puts its presets and biome data back on Fabric");
	}

	@Test
	void switchedOffRegistriesStaysAsMerged() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		System.setProperty(RegistryDirectoryOwnerInjector.PROPERTY, "off");
		byte[] in = classBytes(MERGED_BASE, REGISTRIES);
		assertSame(in, transform(in));
		assertTrue(new RegistryDirectoryOwnerInjector().anchors().anchors().isEmpty());
	}

	@Test
	void otherClassesAreLeftAlone() {
		byte[] bytes = {(byte) 0xCA, (byte) 0xFE};
		assertSame(bytes, new RegistryDirectoryOwnerInjector().transform("net.minecraft.core.Registry", bytes, null));
	}

	@Test
	void theHookFallsBackToTheMergedAnswerRatherThanThrowing() {
		ModPresence.publishFabric(List.of(mod(Ecosystem.FABRIC, "wover")));
		assertEquals("wover/x", KernelRegistryDirectories.registryDirPath("wover/x", null, "x"));
		assertEquals("wover/x", KernelRegistryDirectories.registryDirPath("wover/x", "wover", null));
		assertEquals(null, KernelRegistryDirectories.registryDirPath(null, "unclaimed", "x"));
		assertEquals("x", KernelRegistryDirectories.registryDirPath(null, "wover", "x"));
		ModPresence.publishFabric(List.of());
		assertEquals("wover/x", KernelRegistryDirectories.registryDirPath("wover/x", "wover", "x"),
				"before the Fabric mods are published, every registry keeps the merged answer");
	}

	@Test
	void fabricsModifierIsNotPinnedOffTheMergedBase() {
		// Pinned, it would not crash anything: the hook finds it missing from Registries, every registry keeps the
		// merged directory, and WorldWeaver's world presets and biome data load empty again. That cost is the pin's.
		String mixin = "fabric-registry-sync-v0.mixins.json:RegistriesMixin";
		assertFalse(MergedBaseMixinCompat.SUPPRESSED_MIXINS.contains(mixin), mixin);
		assertFalse(MergedBaseMixinCompat.SUPPRESSED_UNLESS_PRUNED.contains(mixin), mixin);
		assertFalse(MergedBaseMixinCompat.DISABLED_CONFIGS.contains("fabric-registry-sync-v0.mixins.json"));
	}

	// --- plumbing ---

	private static byte[] transform(byte[] bytes) {
		return new RegistryDirectoryOwnerInjector().transform(RegistryDirectoryOwnerInjector.TARGET, bytes, null);
	}

	/** Each input under its own fixture kind: the staged jars and the sweep's mods are on different machines. */
	private static void requireAll(Path... inputs) {
		for (Path input : inputs) {
			TestFixtures.require(NativeCoremodParityTest.fixtureOf(input), Files.isRegularFile(input),
					input + " absent — skipping real-bytes check");
		}
	}

	private static DiscoveredMod mod(Ecosystem ecosystem, String id) {
		return new DiscoveredMod(ecosystem, id, "1.0.0", id, List.of(), List.of(), null, id + ".jar");
	}

	private static byte[] classBytes(Path jar, String internalName) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(internalName + ".class");
			assertNotNull(entry, internalName + " is not in " + jar.getFileName());
			return zip.getInputStream(entry).readAllBytes();
		}
	}

	private static byte[] nestedClassBytes(Path jar, String nestedPrefix, String internalName) throws IOException {
		try (ZipFile outer = new ZipFile(jar.toFile())) {
			List<String> seen = new ArrayList<>();
			for (ZipEntry entry : Collections.list(outer.entries())) {
				if (!entry.getName().startsWith("META-INF/jars/" + nestedPrefix)) continue;
				seen.add(entry.getName());
				try (ZipInputStream nested = new ZipInputStream(outer.getInputStream(entry))) {
					for (ZipEntry inner; (inner = nested.getNextEntry()) != null; ) {
						if (inner.getName().equals(internalName + ".class")) return nested.readAllBytes();
					}
				}
			}
			throw new AssertionError(internalName + " not found in " + jar.getFileName() + " " + seen);
		}
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static Set<String> names(ClassNode node) {
		Set<String> names = new TreeSet<>();
		for (MethodNode m : node.methods) names.add(m.name + m.desc);
		return names;
	}

	private static MethodNode method(ClassNode node, String name) {
		for (MethodNode m : node.methods) if (m.name.equals(name)) return m;
		throw new AssertionError(node.name + " has no " + name);
	}

	private static boolean calls(MethodNode method, String owner, String name) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return true;
		}
		return false;
	}
}

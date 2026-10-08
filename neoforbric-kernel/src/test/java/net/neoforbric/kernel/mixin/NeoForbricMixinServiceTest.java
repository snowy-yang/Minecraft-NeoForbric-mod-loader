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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Pins which registered configs get relaxed. The distinction is invisible until exactly one injector fails to
 * patch, at which point it decides between a soft skip and a fatal {@code MixinApplyError} that aborts the launch.
 */
class NeoForbricMixinServiceTest {
	private static final List<String> SAMPLE = List.of(
			"forge.mixins.json",
			"neoforge.mixins.json",
			"minecraft.mixins.json",
			"neoforbric-kernel.mixins.json",
			"bookshelf.common.mixins.json",
			"geckolib.mixins.json");

	@AfterEach
	void reset() {
		System.clearProperty("neoforbric.relaxGuestMixins");
		System.clearProperty("neoforbric.relaxMixinOverwrites");
		System.clearProperty("neoforbric.suppressMixins");
		System.clearProperty("neoforbric.keepMixins");
		System.clearProperty(net.neoforbric.kernel.transform.GuestInjectorPruner.PROPERTY);
		System.clearProperty(FabricRegistryInitializationMixinAdapter.PROPERTY);
		NeoForbricMixinService.setGuestConfigs(List.of());
	}

	/**
	 * The {@code ModelManagerMixin} pin is conditional on the pruner: trimmed to the injectors that fit by default,
	 * pinned whole only when {@code -Dneoforbric.guestInjectorPruner=off}. The kill switch has to reproduce the OLD
	 * behaviour (pinned, block models load, plugins dead) and never the half-applied one (4666 missingno models),
	 * which is what an unconditional removal of the pin would have shipped.
	 */
	@Test
	void theModelManagerMixinIsPinnedOnlyWhenThePrunerIsOff() {
		String config = "fabric-model-loading-api-v1.mixins.json";
		assertTrue(MergedBaseMixinCompat.SUPPRESSED_UNLESS_PRUNED.contains(config + ":ModelManagerMixin"),
				"precondition: the entry moved to SUPPRESSED_UNLESS_PRUNED");
		assertFalse(MergedBaseMixinCompat.SUPPRESSED_MIXINS.contains(config + ":ModelManagerMixin"),
				"and left the unconditional list");

		assertFalse(NeoForbricMixinService.suppressedMixinsFor(config).contains("ModelManagerMixin"),
				"by default the pruner trims the mixin, so it must NOT be suppressed");

		System.setProperty(net.neoforbric.kernel.transform.GuestInjectorPruner.PROPERTY, "off");
		assertTrue(NeoForbricMixinService.suppressedMixinsFor(config).contains("ModelManagerMixin"),
				"with the pruner off the whole-mixin pin returns");
	}

	/**
	 * {@code -Dneoforbric.keepMixins} has to reach the SHIPPED suppression list, not just the adapter's derived one.
	 * It did not, and the failure mode is the expensive kind: re-testing a hand-pinned entry changed nothing while
	 * looking exactly like the mixin having been tried and re-suppressed. Both former pins
	 * ({@code SynchronizeRegistriesTaskMixin}, jade's {@code FogRendererMixin}) were diagnosed only once this
	 * worked.
	 */
	/**
	 * A hand-listed or property-listed suppression removes the mixin before Mixin reads the config, so it never
	 * runs — and it used to leave one log line and nothing in the report. It is a confirmed removal the kernel
	 * made on purpose: in the ledger, on the mod's row, and not a continue-or-quit question.
	 */
	@Test
	void aSuppressionByNameIsAConfirmedFindingThatAsksNothing(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
			throws Exception {
		System.setProperty(FabricRegistryInitializationMixinAdapter.PROPERTY, "off");
		String config = "fabric-registry-sync-v0.mixins.json";
		String pkg = "net.fabricmc.fabric.mixin.registry.sync";
		assertTrue(MergedBaseMixinCompat.SUPPRESSED_MIXINS.contains(config + ":BootstrapMixin"), "precondition");
		java.nio.file.Path jar = dir.resolve("registry-sync.jar");
		try (var out = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar))) {
			out.putNextEntry(new java.util.jar.JarEntry(config));
			out.write(("{\"required\":true,\"package\":\"" + pkg + "\",\"mixins\":[\"BootstrapMixin\",\"StillRunsMixin\","
					+ "\"PropertyListedMixin\"]}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
			out.closeEntry();
		}
		System.setProperty("neoforbric.suppressMixins", config + ":PropertyListedMixin");
		net.neoforbric.api.CompatibilityFindings.reset();
		try (var loader = new net.neoforbric.kernel.classloading.NeoForbricClassLoader(new java.net.URL[] {jar.toUri().toURL()},
				getClass().getClassLoader())) {
			NeoForbricMixinService.bind(loader, net.fabricmc.api.EnvType.CLIENT);
			String rewritten;
			try (var in = new NeoForbricMixinService().getResourceAsStream(config)) {
				rewritten = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			}
			assertFalse(rewritten.contains("BootstrapMixin") || rewritten.contains("PropertyListedMixin"), rewritten);

			var findings = net.neoforbric.api.CompatibilityFindings.all();
			var hand = findings.stream().filter(f -> f.id().equals(MixinCompatibility.id(config, pkg + ".BootstrapMixin")))
					.findFirst().orElseThrow(() -> new AssertionError("no finding for the hand-listed mixin: " + findings));
			assertTrue(hand.confidence() == net.neoforbric.api.CompatibilityFinding.Confidence.CONFIRMED && !hand.required(),
					hand.toString());
			assertTrue(hand.evidence().contains("source=MergedBaseMixinCompat.SUPPRESSED_MIXINS"), hand.evidence().toString());
			assertTrue(hand.evidence().contains("config required=true"), "the mod's own declaration is kept: " + hand.evidence());
			var property = findings.stream().filter(f -> f.id().equals(MixinCompatibility.id(config, pkg + ".PropertyListedMixin")))
					.findFirst().orElseThrow();
			assertTrue(property.evidence().contains("source=-Dneoforbric.suppressMixins"), property.evidence().toString());
			assertTrue(findings.stream().noneMatch(f -> f.id().contains("StillRunsMixin")), "a kept mixin is not reported");
			assertTrue(net.neoforbric.api.CompatibilityFindings.confirmedRequired().isEmpty());
		} finally {
			NeoForbricMixinService.bind(null, net.fabricmc.api.EnvType.SERVER);
			net.neoforbric.api.CompatibilityFindings.reset();
		}
	}

	/**
	 * What MixinOverlapLint reads: the config as Mixin was served it, so a mixin the kernel dropped is not half of an
	 * overlap, and the jar's own bytes for a config nothing rewrote.
	 */
	@Test
	void theServedConfigIsTheOneMixinReadNotTheJars(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
			throws Exception {
		String config = "overlap-served.mixins.json";
		String untouched = "overlap-untouched.mixins.json";
		String json = "{\"package\":\"x.y\",\"mixins\":[\"Kept\",\"Dropped\"]}";
		java.nio.file.Path jar = dir.resolve("served.jar");
		try (var out = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar))) {
			for (String name : List.of(config, untouched)) {
				out.putNextEntry(new java.util.jar.JarEntry(name));
				out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
				out.closeEntry();
			}
		}
		System.setProperty("neoforbric.suppressMixins", config + ":Dropped");
		try (var loader = new net.neoforbric.kernel.classloading.NeoForbricClassLoader(new java.net.URL[] {jar.toUri().toURL()},
				getClass().getClassLoader())) {
			NeoForbricMixinService.bind(loader, net.fabricmc.api.EnvType.CLIENT);
			byte[] read;
			try (var in = new NeoForbricMixinService().getResourceAsStream(config)) {
				read = in.readAllBytes();
			}
			assertFalse(new String(read, java.nio.charset.StandardCharsets.UTF_8).contains("Dropped"), "precondition");
			assertArrayEquals(read, NeoForbricMixinService.servedConfig(config));
			assertEquals(json, new String(NeoForbricMixinService.servedConfig(untouched), java.nio.charset.StandardCharsets.UTF_8));
		} finally {
			NeoForbricMixinService.bind(null, net.fabricmc.api.EnvType.SERVER);
			net.neoforbric.api.CompatibilityFindings.reset();
		}
	}

	@Test
	void keepMixinsOverridesTheShippedSuppressionList() {
		System.setProperty(FabricRegistryInitializationMixinAdapter.PROPERTY, "off");
		String config = "fabric-registry-sync-v0.mixins.json";
		assertTrue(NeoForbricMixinService.suppressedMixinsFor(config).contains("BootstrapMixin"),
				"precondition: this entry ships in MergedBaseMixinCompat.SUPPRESSED_MIXINS");

		System.setProperty("neoforbric.keepMixins", config + ":BootstrapMixin");
		assertFalse(NeoForbricMixinService.suppressedMixinsFor(config).contains("BootstrapMixin"),
				"an explicit keepMixins must beat the shipped default");
		assertTrue(NeoForbricMixinService.suppressedMixinsFor(config).contains("MainMixin"),
				"and must not disturb its siblings — it names one mixin, not the config");
	}

	@Test
	void keepMixinsAlsoOverridesAnExplicitSuppressMixins() {
		String config = "example.mixins.json";
		System.setProperty("neoforbric.suppressMixins", config + ":SomeMixin");
		assertTrue(NeoForbricMixinService.suppressedMixinsFor(config).contains("SomeMixin"));

		System.setProperty("neoforbric.keepMixins", config + ":SomeMixin");
		assertFalse(NeoForbricMixinService.suppressedMixinsFor(config).contains("SomeMixin"),
				"keepMixins subtracts last, so it wins over suppressMixins too");
	}

	@Test
	void keepMixinsForAnUnrelatedConfigChangesNothing() {
		System.setProperty(FabricRegistryInitializationMixinAdapter.PROPERTY, "off");
		String config = "fabric-registry-sync-v0.mixins.json";
		System.setProperty("neoforbric.keepMixins", "other.mixins.json:BootstrapMixin");
		assertTrue(NeoForbricMixinService.suppressedMixinsFor(config).contains("BootstrapMixin"),
				"the config name is part of the key — a same-named mixin elsewhere must not unpin this one");
	}

	@Test
	void restoredRegistryMixinsRunByDefaultAndExplicitSuppressionsStillWin() {
		String config = "fabric-registry-sync-v0.mixins.json";
		assertFalse(NeoForbricMixinService.suppressedMixinsFor(config).contains("BootstrapMixin"));
		assertFalse(NeoForbricMixinService.suppressedMixinsFor(config).contains("RegistryDataLoaderMixin"));
		System.setProperty("neoforbric.suppressMixins", config + ":BootstrapMixin");
		assertTrue(NeoForbricMixinService.suppressedMixinsFor(config).contains("BootstrapMixin"));
	}

	@Test
	void aGuestConfigNamedAfterAnEcosystemIsStillRelaxed() {
		// The exclusion used to be a PREFIX match on forge./neoforge./minecraft., which was harmless only while the
		// registered set was Fabric-only. A guest mod may legitimately name its config forge.mixins.json, and
		// leaving it strict turns one moved anchor into a fatal apply error.
		NeoForbricMixinService.setGuestConfigs(SAMPLE);

		assertTrue(NeoForbricMixinService.isRelaxedConfig("forge.mixins.json"));
		assertTrue(NeoForbricMixinService.isRelaxedConfig("neoforge.mixins.json"));
		assertTrue(NeoForbricMixinService.isRelaxedConfig("minecraft.mixins.json"));
		assertTrue(NeoForbricMixinService.isRelaxedConfig("bookshelf.common.mixins.json"));
		assertTrue(NeoForbricMixinService.isRelaxedConfig("geckolib.mixins.json"));
	}

	@Test
	void theKernelsOwnConfigIsNeverRelaxed() {
		// The kernel authors no mixins today, but a failure in one it did author must crash loudly rather than be
		// silently skipped.
		NeoForbricMixinService.setGuestConfigs(SAMPLE);

		assertFalse(NeoForbricMixinService.isRelaxedConfig("neoforbric-kernel.mixins.json"));
	}

	@Test
	void aConfigThatWasNeverRegisteredIsNotRelaxed() {
		NeoForbricMixinService.setGuestConfigs(SAMPLE);

		assertFalse(NeoForbricMixinService.isRelaxedConfig("something-else.mixins.json"));
	}

	@Test
	void relaxGuestMixinsOffRestoresStrictBehaviourForEveryone() {
		System.setProperty("neoforbric.relaxGuestMixins", "off");
		NeoForbricMixinService.setGuestConfigs(SAMPLE);

		assertFalse(NeoForbricMixinService.isRelaxedConfig("bookshelf.common.mixins.json"));
		assertFalse(NeoForbricMixinService.isRelaxedConfig("geckolib.mixins.json"));
	}

	@Test
	void relaxMixinOverwritesStillWorksAsAnExplicitOverride() {
		NeoForbricMixinService.setGuestConfigs(List.of());

		System.setProperty("neoforbric.relaxMixinOverwrites", "explicit.mixins.json,prefixed.*");
		assertTrue(NeoForbricMixinService.isRelaxedConfig("explicit.mixins.json"));
		assertTrue(NeoForbricMixinService.isRelaxedConfig("prefixed.anything.json"));
		assertFalse(NeoForbricMixinService.isRelaxedConfig("unlisted.mixins.json"));
	}
}

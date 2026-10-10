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

package net.neoforbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.neoforbric.api.Ecosystem;
import net.neoforbric.api.ModCatalog;
import net.neoforbric.api.Side;
import net.neoforbric.kernel.transform.CreativePagerBridgeInjector;
import net.neoforbric.kernel.transform.GuestInjectorPruner;

/** The attribution rows: recorded by needle, judged by side and by kill switch, marked by catalog identity. */
@org.junit.jupiter.api.parallel.ResourceLock("ModCatalog")
class FabricApiModuleLossAuditTest {
	private static final String LOOT = "net/fabricmc/fabric/api/loot/v3/LootTableEvents";   // 1.21.1: not a Loss row — the mixin binds as written
	private static final String MODEL_PLUGIN = "net/fabricmc/fabric/api/client/model/loading/v1/ModelLoadingPlugin";
	private static final String CREATIVE = "net/fabricmc/fabric/api/client/creativetab/v1/FabricCreativeModeInventoryScreen";
	private static final String DYNAMIC_REGISTRIES = "net/fabricmc/fabric/api/event/registry/DynamicRegistries";
	private static final String SETUP_CALLBACK = "net/fabricmc/fabric/api/event/registry/DynamicRegistrySetupCallback";

	private List<ModCatalog.Entry> previous;

	@AfterEach
	void forget() {
		FabricApiModuleLossAudit.reset();
		System.clearProperty(GuestInjectorPruner.PROPERTY);
		System.clearProperty(CreativePagerBridgeInjector.PROPERTY);
		System.clearProperty(FabricApiModuleLossAudit.SWITCH);
		if (previous != null) ModCatalog.publish(previous);
	}

	private void publish(ModCatalog.Entry... entries) {
		previous = ModCatalog.everything();
		ModCatalog.publish(List.of(entries));
	}

	private static ModCatalog.Entry entry(String modId, String jar, String bundledBy) {
		return new ModCatalog.Entry(Ecosystem.FABRIC, modId, modId, "1", "", List.of(), jar, "", bundledBy);
	}

	private static byte[] classNaming(String... types) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Listener", null, "java/lang/Object", null);
		MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "init", "()V", null, null);
		m.visitCode();
		for (String type : types) {
			m.visitTypeInsn(Opcodes.NEW, type);
			m.visitInsn(Opcodes.POP);
		}
		m.visitInsn(Opcodes.RETURN);
		m.visitMaxs(2, 1);
		m.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static ModCatalog.Entry degraded(String modId) {
		return ModCatalog.failures().stream().filter(e -> e.modId().equals(modId)).findFirst().orElse(null);
	}

	/**
	 * 1.21.1: a loot listener is not a Loss row at all. The 26.x bridge is gone because the mixin's intermediary
	 * descriptors match this base's lambdas exactly, so LootTableEvents fire natively — nothing is lost, so no
	 * needle matches and the listener is never recorded.
	 */
	@Test
	void aLootListenerIsNotALossAtAllBecauseTheMixinBindsAsWritten() {
		publish(entry("balm", "balm.jar", ""));
		FabricApiModuleLossAudit.note("balm.jar", classNaming(LOOT));
		assertTrue(FabricApiModuleLossAudit.users().isEmpty(), "no surface is lost, so nobody is recorded");

		FabricApiModuleLossAudit.report(Side.DEDICATED_SERVER);
		assertTrue(ModCatalog.failures().isEmpty(), "the mixin fires the events, so nothing is lost: " + ModCatalog.failures());
	}

	@Test
	void aClientOnlySurfaceIsALossOnTheClientAndNotOnTheServer() {
		publish(entry("pager", "pager.jar", ""));
		System.setProperty(CreativePagerBridgeInjector.PROPERTY, "off");
		FabricApiModuleLossAudit.note("pager.jar", classNaming(CREATIVE));
		FabricApiModuleLossAudit.report(Side.DEDICATED_SERVER);
		assertTrue(ModCatalog.failures().isEmpty(), "a dedicated server never opens the creative screen");
		FabricApiModuleLossAudit.report(Side.CLIENT);
		ModCatalog.Entry pager = degraded("pager");
		assertTrue(pager != null && pager.statusDetail().startsWith("fabric-creative-tab-api-v1: "), String.valueOf(pager));
	}

	/** A class IMPLEMENTING the creative-tab interface: owo-lib's mixin, which the fallback leaves out. */
	private static byte[] classImplementing(String itf) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "com/example/PagerMixin", null, "java/lang/Object",
				new String[] {itf});
		cw.visitEnd();
		return cw.toByteArray();
	}

	@Test
	void theCreativePagerRowFollowsTheBridgeAndNamesTheAssertionErrorNotACast() {
		publish(entry("pager", "pager.jar", ""));
		FabricApiModuleLossAudit.note("pager.jar", classNaming(CREATIVE));
		FabricApiModuleLossAudit.report(Side.CLIENT);
		assertTrue(ModCatalog.failures().isEmpty(), "the bridge backs the interface from NeoForge's pager: " + ModCatalog.failures());

		System.setProperty(CreativePagerBridgeInjector.PROPERTY, "off");
		FabricApiModuleLossAudit.report(Side.CLIENT);
		ModCatalog.Entry pager = degraded("pager");
		assertTrue(pager != null && pager.statusDetail().contains("AssertionError"), String.valueOf(pager));
		assertFalse(pager.statusDetail().contains("ClassCastException"),
				"fabric-api's class tweaker injects the interface, so a cast succeeds: " + pager.statusDetail());
	}

	@Test
	void aModImplementingTheCreativeInterfaceIsToldItsMixinIsLeftOut() {
		publish(entry("owo", "owo.jar", ""), entry("caller", "caller.jar", ""));
		System.setProperty(CreativePagerBridgeInjector.PROPERTY, "off");
		FabricApiModuleLossAudit.note("owo.jar", classImplementing(CREATIVE));
		FabricApiModuleLossAudit.note("caller.jar", classNaming(CREATIVE));
		FabricApiModuleLossAudit.report(Side.CLIENT);
		ModCatalog.Entry owo = degraded("owo");
		assertTrue(owo != null && owo.statusDetail().contains("mixin implementing FabricCreativeModeInventoryScreen is left out"),
				String.valueOf(owo));
		assertTrue(owo.statusDetail().contains("unless -Dneoforbric.pinnedContracts=off"), "that switch keeps it: " + owo.statusDetail());
		ModCatalog.Entry caller = degraded("caller");
		assertTrue(caller != null && caller.statusDetail().contains("every call throws AssertionError"), String.valueOf(caller));
	}

	/** With Fabric's own mixin unpinned it implements the interface itself: nothing is lost, bridge or not. */
	@Test
	@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
	void theCreativePagerRowIsNoLossWhileFabricsOwnMixinApplies() {
		publish(entry("owo", "owo.jar", ""), entry("caller", "caller.jar", ""));
		System.setProperty(CreativePagerBridgeInjector.PROPERTY, "off");
		System.setProperty("neoforbric.keepMixins", net.neoforbric.kernel.mixin.MergedBaseMixinCompat.CREATIVE_PAGER_PIN);
		try {
			FabricApiModuleLossAudit.note("owo.jar", classImplementing(CREATIVE));
			FabricApiModuleLossAudit.note("caller.jar", classNaming(CREATIVE));
			FabricApiModuleLossAudit.report(Side.CLIENT);
			assertTrue(ModCatalog.failures().isEmpty(), String.valueOf(ModCatalog.failures()));
		} finally {
			System.clearProperty("neoforbric.keepMixins");
		}
	}

	@Test
	void theRegistryNeedleIsTheClassNotThePackage() {
		FabricApiModuleLossAudit.note("lithostitched.jar", classNaming(DYNAMIC_REGISTRIES));
		assertTrue(FabricApiModuleLossAudit.users().isEmpty(), "DynamicRegistries works — it must not be recorded");
		FabricApiModuleLossAudit.note("other.jar", classNaming(SETUP_CALLBACK));
		assertEquals(Set.of("other.jar"), FabricApiModuleLossAudit.users().get(FabricApiModuleLossAudit.LOSSES.get(1)));
	}

	@Test
	void fabricApisOwnModuleIsNeverAUserAndAThirdPartyBesideItIs() {
		publish(entry("owo", "owo.jar", ""),
				entry("fabric-creative-tab-api-v1", "fabric-creative-tab-api-v1.jar", "fabric-api"));
		// The creative row only fires while its restoration is switched off — the kill switch every sibling
		// test sets too; without it the bridge answers for the interface and nobody is degraded.
		System.setProperty(CreativePagerBridgeInjector.PROPERTY, "off");
		FabricApiModuleLossAudit.note("owo.jar", classNaming(CREATIVE));
		FabricApiModuleLossAudit.note("fabric-creative-tab-api-v1.jar", classNaming(CREATIVE));
		FabricApiModuleLossAudit.report(Side.CLIENT);
		assertTrue(degraded("owo") != null, "the third party is named");
		assertTrue(degraded("fabric-creative-tab-api-v1") == null, "the module that DEFINES the surface is not its user");
	}

	@Test
	void threeClassesFromOneJarNameItOnce() {
		FabricApiModuleLossAudit.note("owo.jar", classNaming(CREATIVE));
		FabricApiModuleLossAudit.note("owo.jar", classNaming(CREATIVE));
		FabricApiModuleLossAudit.note("owo.jar", classNaming(CREATIVE, SETUP_CALLBACK));
		assertEquals(Set.of("owo.jar"), FabricApiModuleLossAudit.users().get(FabricApiModuleLossAudit.LOSSES.get(0)));
		assertEquals(Set.of("owo.jar"), FabricApiModuleLossAudit.users().get(FabricApiModuleLossAudit.LOSSES.get(1)));
		assertEquals(2, FabricApiModuleLossAudit.users().size(), "two surfaces, one jar each");
	}

	@Test void separatelyInstalledDefiningModuleIsNotItsOwnThirdPartyConsumer() {
		String before = System.setProperty(net.neoforbric.kernel.mixin.FabricRegistryLoaderMixinAdapter.PROPERTY, "off");
		try {
		publish(entry("fabric-registry-sync-v0", "module.jar", ""), entry("consumer", "consumer.jar", ""));
		FabricApiModuleLossAudit.note("module.jar", classNaming(SETUP_CALLBACK));
		FabricApiModuleLossAudit.note("consumer.jar", classNaming(SETUP_CALLBACK));
		FabricApiModuleLossAudit.report(Side.DEDICATED_SERVER);
		assertTrue(degraded("fabric-registry-sync-v0") == null);
		assertTrue(degraded("consumer") != null);
		} finally {
			if (before == null) System.clearProperty(net.neoforbric.kernel.mixin.FabricRegistryLoaderMixinAdapter.PROPERTY);
			else System.setProperty(net.neoforbric.kernel.mixin.FabricRegistryLoaderMixinAdapter.PROPERTY, before);
		}
	}

	@Test
	void switchedOffItRecordsAndMarksNothing() {
		publish(entry("owo", "owo.jar", ""));
		System.setProperty(FabricApiModuleLossAudit.SWITCH, "off");
		FabricApiModuleLossAudit.note("owo.jar", classNaming(CREATIVE));
		FabricApiModuleLossAudit.report(Side.CLIENT);
		assertFalse(FabricApiModuleLossAudit.enabled());
		assertTrue(ModCatalog.failures().isEmpty(), "report only, and only when asked");
	}
}

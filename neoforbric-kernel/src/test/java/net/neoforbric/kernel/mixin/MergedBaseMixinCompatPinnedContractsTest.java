/* Copyright 2026 The NeoForbric Project. Licensed under the Apache License, Version 2.0. */
package net.neoforbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import net.neoforbric.kernel.transform.CreativePagerFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * {@link MergedBaseMixinCompat#PINNED_CONTRACTS} is a hand list, so it is read back off fabric-api itself: every row's
 * pin implements exactly that interface on exactly that target, and every fabric-api pin that implements an interface
 * has its row — a pin added later without one would bring back the unguarded {@code AssertionError}.
 */
class MergedBaseMixinCompatPinnedContractsTest {
	private static final Pattern PACKAGE = Pattern.compile("\"package\"\\s*:\\s*\"([^\"]+)\"");

	@Test void everyFabricApiPinThatImplementsAnInterfaceHasItsRowAndEveryRowIsTrue() throws Exception {
		Map<String, byte[]> modules = fabricApiModules();
		List<MergedBaseMixinCompat.PinnedContract> read = new ArrayList<>();
		int judged = 0;
		for (String pin : MergedBaseMixinCompat.SUPPRESSED_MIXINS) {
			String config = pin.substring(0, pin.indexOf(':'));
			if (!config.startsWith("fabric-")) continue; // other mods' pins have no fixture here
			String module = config.substring(0, config.indexOf('.'));
			byte[] jar = modules.get(module);
			assertNotNull(jar, "fabric-api nests no " + module);
			Map<String, byte[]> entries = entries(jar);
			Matcher pkg = PACKAGE.matcher(new String(entries.get(config), StandardCharsets.UTF_8));
			assertTrue(pkg.find(), config);
			String mixin = pkg.group(1).replace('.', '/') + "/" + pin.substring(pin.indexOf(':') + 1).replace('.', '/');
			// The pin's TARGET is a runtime concept: the base the kernel patches is Mojmap-named, so the
			// row is derived from the module as the build remaps it — the raw module's targets are
			// intermediary class_* names no base class answers. Only the item-group module is remapped
			// into the stage; the registry pins contribute no interface rows, so their targets never
			// reach the comparison below.
			byte[] derived = module.equals("fabric-item-group-api-v1") ? CreativePagerFixtures.remappedModule(mixin + ".class") : entries.get(mixin + ".class");
			var node = MixinFit.parse(derived);
			judged++;
			for (String contract : MixinFit.contributedInterfaces(node)) {
				for (String target : MixinFit.mixinTargets(node)) read.add(new MergedBaseMixinCompat.PinnedContract(pin, target, contract));
			}
		}
		// 1.21.1: the 26.x RegistryDataLoaderMixin pin died with its mixin (the module no longer ships
		// it); the four that remain are BootstrapMixin, MainMixin, MinecraftClientMixin and
		// CreativeInventoryScreenMixin — every fabric-api pin this bundle carries.
		assertEquals(4, judged, "the fabric-api pins were all read: " + judged);
		assertEquals(Set.copyOf(read), Set.copyOf(MergedBaseMixinCompat.PINNED_CONTRACTS));
		for (var row : MergedBaseMixinCompat.PINNED_CONTRACTS) assertTrue(MergedBaseMixinCompat.SUPPRESSED_MIXINS.contains(row.pin()));
	}

	/** Lifted by -Dneoforbric.keepMixins or -Dneoforbric.mergedBaseCompat=off, a pin's mixin applies and implements its interface. */
	@Test @ResourceLock("system-properties")
	void aPinIsInForceUntilSomethingLiftsIt() {
		System.setProperty(net.neoforbric.kernel.transform.CreativePagerBridgeInjector.PROPERTY, "off");
		String pin = MergedBaseMixinCompat.CREATIVE_PAGER_PIN;
		assertTrue(MergedBaseMixinCompat.pinInForce(pin));
		assertFalse(MergedBaseMixinCompat.pinInForce(pin.substring(0, pin.indexOf(':') + 1) + "NotPinned"));
		try {
			System.setProperty("neoforbric.keepMixins", pin);
			assertFalse(MergedBaseMixinCompat.pinInForce(pin), "kept by name");
			System.clearProperty("neoforbric.keepMixins");
			System.setProperty("neoforbric.mergedBaseCompat", "off");
			assertFalse(MergedBaseMixinCompat.pinInForce(pin), "the built-in lists switched off");
			System.setProperty("neoforbric.suppressMixins", pin);
			assertTrue(MergedBaseMixinCompat.pinInForce(pin), "named again by hand");
		} finally {
			System.clearProperty(net.neoforbric.kernel.transform.CreativePagerBridgeInjector.PROPERTY);
			System.clearProperty("neoforbric.keepMixins");
			System.clearProperty("neoforbric.mergedBaseCompat");
			System.clearProperty("neoforbric.suppressMixins");
		}
	}

	/**
	 * The raw-byte check in front of the pinned-contract parse turns away only what the parse would: across every class
	 * of owo-lib and fabric-api's item-group module, a class relies on the interface exactly when its bytes pass. A
	 * needle spelled as a descriptor or with dots would miss owo's mixin here, and let it throw in the game.
	 */
	@Test
	void thePinnedContractPrefilterTurnsAwayOnlyWhatTheParseWould() throws Exception {
		MergedBaseMixinCompat.PinnedContract row = MergedBaseMixinCompat.PINNED_CONTRACTS.getFirst();
		byte[] named = net.neoforbric.kernel.util.ByteScan.needle(row.contract());
		Map<String, byte[]> classes = new HashMap<>();
		for (var e : entries(fabricApiModules().get("fabric-item-group-api-v1")).entrySet()) {
			if (e.getKey().endsWith(".class")) classes.put(e.getKey(), e.getValue());
		}
		try (ZipFile owo = new ZipFile(CreativePagerFixtures.owo().toFile())) {
			for (ZipEntry e : owo.stream().toList()) {
				if (e.getName().endsWith(".class")) classes.put(e.getName(), owo.getInputStream(e).readAllBytes());
			}
		}
		List<String> relying = new ArrayList<>();
		int turnedAway = 0;
		for (var e : classes.entrySet()) {
			boolean parsed;
			try {
				parsed = MixinFit.referencesAny(MixinFit.parse(e.getValue()), Set.of(row.contract()));
			} catch (RuntimeException unreadable) {
				parsed = false;
			}
			assertEquals(parsed, KernelGuestMixinAdapter.reliesOn(e.getValue(), row.contract(), named), e.getKey());
			if (parsed) relying.add(e.getKey());
			if (!net.neoforbric.kernel.util.ByteScan.contains(e.getValue(), named)) turnedAway++;
		}
		assertTrue(relying.contains(CreativePagerFixtures.OWO_MIXIN + ".class"), relying.toString());
		assertTrue(turnedAway > classes.size() * 9 / 10, turnedAway + " of " + classes.size() + " never parsed");
	}

	/** fabric-api's nested module jars, by module id (the file name up to its version). */
	private static Map<String, byte[]> fabricApiModules() throws Exception {
		Map<String, byte[]> out = new HashMap<>();
		try (ZipFile api = new ZipFile(CreativePagerFixtures.fabricApi().toFile())) {
			for (ZipEntry e : api.stream().toList()) {
				String name = e.getName();
				if (!name.startsWith("META-INF/jars/fabric-") || !name.endsWith(".jar")) continue;
				String file = name.substring("META-INF/jars/".length());
				Matcher id = Pattern.compile("^(fabric-.+?-v\\d+)-").matcher(file);
				if (id.find()) out.put(id.group(1), api.getInputStream(e).readAllBytes());
			}
		}
		return out;
	}

	private static Map<String, byte[]> entries(byte[] jar) throws Exception {
		Map<String, byte[]> out = new HashMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jar))) {
			for (ZipEntry e; (e = in.getNextEntry()) != null; ) if (!e.isDirectory()) out.put(e.getName(), in.readAllBytes());
		}
		return out;
	}
}

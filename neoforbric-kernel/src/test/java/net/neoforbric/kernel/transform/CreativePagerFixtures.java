/* Copyright 2026 The NeoForbric Project. Licensed under the Apache License, Version 2.0. */
package net.neoforbric.kernel.transform;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;

/**
 * The real jars the creative pager tests read: fabric-api's item-group module — raw (yarn/intermediary-typed, the
 * anchor the module really ships) AND as the build remaps it into the base's Mojmap namespace (the interface the
 * bridge answers on a NeoForbric client) — and owo-lib (the mixin that crashed).
 *
 * <p>Found beside this checkout first, then beside the staged tree's checkout — the mod fixtures live in ignored
 * directories a second working tree does not have. Absent everywhere, the test is skipped, never failed.
 */
public final class CreativePagerFixtures {
	public static final String API_JAR = "fabric-api-0.116.17+1.21.1.jar";
	public static final String OWO_JAR = "owo-lib-0.13.1+26.2.jar";
	public static final String CREATIVE_MODULE = "META-INF/jars/fabric-item-group-api-v1-";
	public static final String INTERFACE = CreativePagerBridgeInjector.API;
	public static final String PINNED_MIXIN = "net/fabricmc/fabric/mixin/itemgroup/client/CreativeInventoryScreenMixin";
	public static final String OWO_MIXIN = "io/wispforest/owo/mixin/itemgroup/MixinCreativeModeInventoryScreenMixin";

	private CreativePagerFixtures() {
	}

	/** {@code relative} under this checkout's neoforbric-kernel, else under the staged tree's checkout; skipped if neither. */
	public static Path find(String relative) {
		Path here = Path.of(relative);
		if (Files.isRegularFile(here)) return here;
		Path staged = TestFixtures.stagedRoot().toAbsolutePath().normalize();
		Path checkout = staged.getParent() == null ? null : staged.getParent().getParent();
		Path there = checkout == null ? here : checkout.resolve("neoforbric-kernel").resolve(relative);
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(there), relative + " is not present beside this checkout or the staged one");
		return there;
	}

	public static Path fabricApi() {
		Path pinned = TestFixtures.fabricApi();
		return Files.isRegularFile(pinned) ? pinned : find("run/client-merged-pack/mods/" + API_JAR);
	}

	public static Path owo() {
		return find("build/compat-inputs/sweep90/mods/" + OWO_JAR);
	}

	/** One entry of the build's remapped item-group module — the interface as a NeoForbric client installs it. */
	public static byte[] remappedModule(String entry) {
		Path jar = find("build/transfer-api-compile/fabric-item-group-api-v1-0.116.17.jar");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry e = zip.getEntry(entry);
			if (e == null) throw new AssertionError(entry + " is not in the remapped item-group module");
			return zip.getInputStream(e).readAllBytes();
		} catch (Exception unreadable) {
			throw new AssertionError("could not read " + jar, unreadable);
		}
	}

	/** One entry of fabric-api's nested item-group module, raw as it ships. */
	public static byte[] creativeModule(String entry) throws Exception {
		try (ZipFile api = new ZipFile(fabricApi().toFile())) {
			ZipEntry nested = api.stream().filter(e -> e.getName().startsWith(CREATIVE_MODULE) && e.getName().endsWith(".jar"))
					.findFirst().orElseThrow(() -> new AssertionError("fabric-api nests no creative-tab module"));
			try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(api.getInputStream(nested).readAllBytes()))) {
				for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
					if (e.getName().equals(entry)) return in.readAllBytes();
				}
			}
		}
		throw new AssertionError(entry + " is not in fabric-api's item-group module");
	}

	public static byte[] owoClass(String internalName) throws Exception {
		try (ZipFile owo = new ZipFile(owo().toFile())) {
			ZipEntry entry = owo.getEntry(internalName + ".class");
			if (entry == null) throw new AssertionError(internalName + " is not in " + OWO_JAR);
			return owo.getInputStream(entry).readAllBytes();
		}
	}

	/**
	 * What the ACCESS phase leaves the screen as, on any client with fabric-api. The 26.x module injected the
	 * interface itself through a {@code .classtweaker}; 1.21.1's declares only an {@code .accesswidener} that
	 * widens {@code CreativeModeTab} display fields — it does not touch the screen — and the interface now
	 * reaches the screen through the pinned mixin's {@code implements} clause alone. So the screen the COREMOD
	 * phase receives is the merged bytes unchanged, and the BRIDGE is what attaches the interface.
	 */
	public static byte[] classTweaked(String binaryName, byte[] bytes) {
		return bytes;
	}
}

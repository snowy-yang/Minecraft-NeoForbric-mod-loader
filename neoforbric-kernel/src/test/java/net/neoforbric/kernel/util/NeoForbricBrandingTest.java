/* Copyright 2026 The NeoForbric Project. Licensed under the Apache License, Version 2.0. */
package net.neoforbric.kernel.util;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;

import org.junit.jupiter.api.Test;

/** The release a launcher profile installed, read off the kernel jar's own Maven file name. */
class NeoForbricBrandingTest {
	@Test void theInstalledJarsFileNameCarriesTheRelease() throws Exception {
		URL installed = new URL("file:/Users/p/Library/Application%20Support/minecraft/libraries/net/neoforbric/neoforbric-kernel/"
				+ "0.3.1-beta/neoforbric-kernel-0.3.1-beta.jar");
		assertEquals("0.3.1-beta", NeoForbricBranding.versionFrom(null, installed));
		assertEquals("0.4.0", NeoForbricBranding.versionFrom(null, new URL("file:/C:/mc/libraries/net/neoforbric/neoforbric-kernel/0.4.0/neoforbric-kernel-0.4.0.jar")));
	}

	@Test void theOverrideWinsAndItsLeadingVIsDropped() throws Exception {
		URL installed = new URL("file:/x/neoforbric-kernel-0.3.1-beta.jar");
		assertEquals("0.3.2", NeoForbricBranding.versionFrom("v0.3.2", installed));
		assertEquals("0.3.2-beta2", NeoForbricBranding.versionFrom(" 0.3.2-beta2 ", installed));
	}

	@Test void classDirectoriesAndForeignJarsTellNothing() throws Exception {
		assertNull(NeoForbricBranding.versionFrom(null, new URL("file:/repo/neoforbric-kernel/build/classes/java/main/")));
		assertNull(NeoForbricBranding.versionFrom(null, new URL("file:/x/some-other.jar")));
		assertNull(NeoForbricBranding.versionFrom("", null));
	}
}

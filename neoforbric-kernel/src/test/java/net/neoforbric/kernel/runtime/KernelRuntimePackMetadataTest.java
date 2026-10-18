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

package net.neoforbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;

/**
 * The pack-format field this anchor's game reads, and what the kernel's own runtime pack declares.
 *
 * <p>What this was written for: the kernel's runtime pack declared only {@code pack_format}, and from format 65 the
 * game required {@code min_format} and {@code max_format}, so every client boot printed "Error reading optional pack
 * metadata for neoforbric/neoforbric-kernel-runtime" with a {@code JsonParseException} stack and NeoForge's reader
 * fell back. The pack still loaded — its compatibility is forced — so this was noise, but it was noise that looked
 * like a NeoForbric failure in every log a player sends.
 *
 * <p>RE-ANCHORED to 1.21.1, where the field went back the other way and the file is now the stale half. This line
 * has no {@code PackFormat} class at all — that record and its range are the later shape — and
 * {@code PackMetadataSection}'s codec requires an {@code int pack_format} ({@code description} beside it, and an
 * optional {@code supported_formats}). The kernel's runtime {@code pack.mcmeta} still declares the LATER
 * {@code min_format}/{@code max_format} and no {@code pack_format}, so the game's reader rejects it and the same log
 * noise is back, from the other direction.
 *
 * <p>The old test parsed the file with the game's own codec. That cannot be done here: the codec for the
 * {@code description} field is the {@code Component} codec, which reaches {@code ItemStack}'s static initialiser and
 * therefore the registries — a parse needs a bootstrapped game, and this suite has none. So the reader's shape is
 * asserted off its own class file instead, the required format off the game's own {@code version.json}, and the
 * kernel's file as what it is. That last assertion is the GAP, pinned rather than papered over: the file is a
 * PRODUCTION leftover this test cannot repair (it is a resource, not a boot-side constant) and is reported. It
 * fails the moment someone re-anchors the file, which is the point of pinning it here.
 */
class KernelRuntimePackMetadataTest {
	@Test
	void theGamesReaderTakesPackFormatAndTheKernelsFileStillDeclaresTheLaterFields() throws Exception {
		Path mcmeta = Path.of(System.getProperty("user.dir"), "src", "runtime", "resources", "pack.mcmeta");
		assertTrue(Files.isRegularFile(mcmeta), mcmeta + " is the file this test is about");
		String text = Files.readString(mcmeta, StandardCharsets.UTF_8);

		// This line's reader: PackMetadataSection, whose format is an int. No PackFormat class exists here — the
		// record the file was written for is the later shape.
		try (URLClassLoader cl = gameLoader()) {
			Class<?> section = Class.forName("net.minecraft.server.packs.metadata.pack.PackMetadataSection", false, cl);
			assertEquals(int.class, section.getMethod("packFormat").getReturnType(),
					"this line's pack format is an int, and pack_format is the field that carries it");
			assertThrows(ClassNotFoundException.class,
					() -> Class.forName("net.minecraft.server.packs.metadata.pack.PackFormat", false, cl),
					"PackFormat is the later pack-metadata shape and is not on this line");
		}

		// The format the running game reports for its own resources, from the client jar's version.json: the value
		// a pack served to it has to declare. The staged merged base is a class-only jar, so it has no version.json.
		int resource = resourceVersion();

		// What this line's reader needs, and what the kernel's file does not have. PRODUCTION gap — see the class
		// javadoc; a re-anchored file declares pack_format: resource and neither of the two fields below.
		assertFalse(has(text, "pack_format"),
				"the kernel's runtime pack.mcmeta declares no pack_format, which is the field this line's reader "
						+ "requires: it is written for the later shape, and the game logs 'Error reading optional pack "
						+ "metadata for neoforbric/neoforbric-kernel-runtime' for it. A re-anchored file must declare "
						+ "pack_format: " + resource + " here instead");
		assertTrue(has(text, "min_format") && has(text, "max_format"),
				"the later fields it declares instead — the shape a pack.mcmeta has from format 65 onwards");
	}

	/** {@code pack_version.resource} from the game's own {@code version.json}. */
	private static int resourceVersion() throws IOException {
		Path vanilla = TestFixtures.vanillaJar();
		TestFixtures.require(Fixture.MC_LIBRARIES, Files.isRegularFile(vanilla),
				"the local Minecraft install has no 1.21.1 client jar");
		try (ZipFile jar = new ZipFile(vanilla.toFile())) {
			String text = new String(jar.getInputStream(jar.getEntry("version.json")).readAllBytes(),
					StandardCharsets.UTF_8);
			return number(text, "resource");
		}
	}

	private static boolean has(String json, String key) {
		return java.util.regex.Pattern.compile("\"" + key + "\"\\s*:").matcher(json).find();
	}

	private static int number(String json, String key) {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
		assertTrue(m.find(), "version.json has no " + key);
		return Integer.parseInt(m.group(1));
	}

	/**
	 * The merged game, the NeoForge carrier beside it and Minecraft 1.21.1's own library set, read from the
	 * launcher's version JSON — the list the build resolves for the game side's test linkage too. The carrier is
	 * there because the base is NeoForge-patched: its classes implement interfaces only the carrier carries
	 * ({@code IHolderLookupProviderExtension}, on the {@code Component} path these signatures resolve through).
	 */
	private static URLClassLoader gameLoader() throws Exception {
		Path merged = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
		Path carrier = TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(merged) && Files.isRegularFile(carrier),
				"the staged merged game or the NeoForge carrier is absent");
		Path libraries = TestFixtures.minecraftDir().resolve("libraries");
		Path versionJson = libraries.getParent().resolve("versions/1.21.1/1.21.1.json");
		TestFixtures.require(Fixture.MC_LIBRARIES, Files.isRegularFile(versionJson),
				"no 1.21.1 version JSON beside the local Minecraft libraries");
		List<URL> urls = new ArrayList<>(List.of(merged.toUri().toURL(), carrier.toUri().toURL()));
		java.util.regex.Matcher path = java.util.regex.Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+\\.jar)\"")
				.matcher(Files.readString(versionJson, StandardCharsets.UTF_8));
		while (path.find()) {
			Path library = libraries.resolve(path.group(1));
			if (Files.isRegularFile(library)) urls.add(library.toUri().toURL());
		}
		TestFixtures.require(Fixture.MC_LIBRARIES, urls.size() > 1,
				"none of 1.21.1's libraries is in the local Minecraft install");
		return new URLClassLoader(urls.toArray(new URL[0]), KernelRuntimePackMetadataTest.class.getClassLoader());
	}
}

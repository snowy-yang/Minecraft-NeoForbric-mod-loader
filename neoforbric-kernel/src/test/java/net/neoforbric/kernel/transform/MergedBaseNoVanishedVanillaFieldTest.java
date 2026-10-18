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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

/**
 * A whole-artifact detector for a vanilla field whose vanilla DESCRIPTOR the merged base no longer offers.
 *
 * <h2>Why this is not {@link MergedBaseNoUnwrittenDuplicateFieldTest}</h2>
 *
 * <p>That test covers the case where the merge keeps BOTH declarations and only one is written — the field is
 * there, and it is null. This is the neighbouring case and the more damaging one: when one ecosystem RE-TYPES a
 * vanilla field and the merge keeps only its declaration, vanilla's {@code (name, descriptor)} pair stops
 * existing. Nothing is null, because there is nothing. A mod compiled against vanilla gets
 * {@code NoSuchFieldError} at its {@code GETFIELD}/{@code PUTFIELD}, or — if it reaches the field through Mixin —
 * an {@code @Accessor} that cannot bind, which by default is a warning and a feature silently gone. The duplicate
 * test is blind to it by construction: the name is not duplicated.
 *
 * <p>It has cost a boot. {@code ChunkGenerator.featuresPerStep} became MinecraftForge's {@code ClearableLazy},
 * and fabric-api's biome API writes that field directly with vanilla's descriptor — so a dedicated server with
 * any Fabric biome modification installed did not start. That one is repaired now, which is why it is not in the
 * list below; the list is what is still true.
 *
 * <h2>Why a pinned list rather than zero</h2>
 *
 * <p>Because the answer today is not zero and pretending otherwise would mean deleting the test the first time it
 * went red. Each entry below is a real, still-unrepaired drift with the consequence stated, so the set is a
 * ledger rather than a suppression: a NEW drift fails this test, and repairing one of these fails it too, which
 * is the right time to delete its line.
 *
 * <p>The comparison is made AFTER the kernel's compat transformer has run, because that is what the game sees.
 *
 * <p><b>Re-anchored: the whole-artifact scan cannot run on this anchor.</b> It walked the vanilla jar's
 * {@code net/minecraft/...} entries, and the staged vanilla jar is Mojang's OBFUSCATED launcher jar — 18 of its
 * entries carry that prefix, so it compared 18 classes instead of the game. That is asserted here rather than
 * assumed. What is left that this base can answer is the ledger itself: every owner it names is asserted NOT to be a
 * class in this base, which is what makes its drifts unverifiable here. Restoring the scan needs a Mojmap-named
 * 1.21.1 jar staged for this fixture — a build-input change, not a test one — and the day one of the ledger's owners
 * exists again this test goes red and says so.
 */
class MergedBaseNoVanishedVanillaFieldTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");

	/**
	 * Stock Minecraft, from the same local install the staged artifacts were derived from — the same lookup the
	 * other artifact-scanning tests use, so one {@code MC_DIR} covers them all.
	 */
	private static final Path VANILLA = vanillaJar();

	private static Path vanillaJar() {
		return TestFixtures.vanillaJar();
	}

	/** The ledger lives in main now, so FieldDriftAudit can name the readers at boot; keyed {@code owner#name:vanillaDescriptor}. */
	private static final Map<String, String> KNOWN = new LinkedHashMap<>();

	static {
		for (MergedBaseFieldDrift.Drift drift : MergedBaseFieldDrift.KNOWN) KNOWN.put(drift.key(), drift.cost());
	}

	@Test
	void theLedgerNamesClassesThisBaseDoesNotHaveAndTheScanCannotRun() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping whole-artifact scan");
		TestFixtures.require(Fixture.MC_LIBRARIES, Files.isRegularFile(VANILLA),
				"stock 1.21.1 absent at " + VANILLA + " — set MC_DIR to a Minecraft install to run this scan");

		int readable = 0;
		try (ZipFile vanilla = new ZipFile(VANILLA.toFile())) {
			var entries = vanilla.entries();
			while (entries.hasMoreElements()) {
				String name = entries.nextElement().getName();
				if (name.endsWith(".class") && name.startsWith("net/minecraft/")) readable++;
			}
		}
		assertTrue(readable < 100, "the vanilla fixture can answer for the game again — restore the whole-artifact scan "
				+ "this test used to run, comparing every vanilla field's descriptor against its merged twin: "
				+ readable + " readable classes");

		assertTrue(!MergedBaseFieldDrift.KNOWN.isEmpty(),
				"the ledger is empty — if that is the real answer, delete this test with it");

		List<String> present = new ArrayList<>();
		try (ZipFile merged = new ZipFile(MERGED_BASE.toFile())) {
			for (MergedBaseFieldDrift.Drift drift : MergedBaseFieldDrift.KNOWN) {
				if (merged.getEntry(drift.owner().replace('.', '/') + ".class") != null) present.add(drift.key());
			}
		}
		assertEquals(List.of(), present, "a ledger owner is a class in this base, so its drift can be compared against "
				+ "real bytes rather than left as a claim — re-derive the entry instead of trusting it: " + present);
	}

	private static ClassNode read(ZipFile zip, ZipEntry entry) throws IOException {
		byte[] bytes;
		try (InputStream in = zip.getInputStream(entry)) {
			bytes = in.readAllBytes();
		}
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	/** What the game actually sees: the merged bytes after the kernel's own compat pass. */
	private static ClassNode repaired(ClassNode merged, String entryName) {
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
		merged.accept(writer);
		String binary = entryName.substring(0, entryName.length() - ".class".length()).replace('/', '.');
		byte[] out = new NeoForbricMergedBaseCompatTransformer().transform(binary, writer.toByteArray(), null);
		// …and the twin injector, which answers three of the drifts this census used to list as KNOWN.
		out = new WidenedFieldTwinInjector().transform(binary, out, null);
		ClassNode node = new ClassNode();
		new ClassReader(out).accept(node, 0);
		return node;
	}
}

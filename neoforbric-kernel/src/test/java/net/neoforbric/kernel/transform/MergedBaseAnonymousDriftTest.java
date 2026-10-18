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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import net.neoforbric.kernel.mixin.MergedBaseAnonymousDrift;

/**
 * Derives the three anonymous-drift buckets from stock 1.21.1 and the merged base and asserts they EQUAL the
 * pinned constants. The identity of an anonymous class is its non-{@code <init>} method set (name + descriptor)
 * plus its superclass; the constructor descriptor and the {@code val$} captures are javac's plumbing.
 *
 * <p>Re-anchored, and the derivation cannot run on this anchor: the staged stock jar is Mojang's OBFUSCATED launcher
 * jar, so it holds the game under obfuscated names and no {@code net/minecraft/...$N.class} entry exists to read a
 * vanilla shape from. That is asserted below rather than assumed. The merged half is intact (hundreds of anonymous
 * classes under {@code net/minecraft}), and the pinned table is 26.x-derived — its {@code RESHAPED}/{@code
 * CAPTURE_ONLY} names are not classes in this base at all, which is the evidence that nothing here can confirm the
 * {@code RELOCATED} mapping either. Re-deriving the table needs a Mojmap-named 1.21.1 jar staged for this fixture,
 * which is a build-input change and not a test one.
 */
class MergedBaseAnonymousDriftTest {
	private static final Path MERGED = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
	private static final Pattern ANONYMOUS = Pattern.compile("^(net/minecraft/|com/mojang/).*\\$\\d+\\.class$");

	record Shape(String superName, Set<String> methods, String ctorDesc, Set<String> captures) {
		String identity() {
			return superName + "|" + methods;
		}

		/** The same class, possibly with methods a patch ADDED: every vanilla method is still there, same superclass. */
		boolean stillHolds(Shape vanilla) {
			return superName.equals(vanilla.superName) && methods.containsAll(vanilla.methods);
		}
	}

	@Test
	void theBucketsCannotBeReDerivedOnThisAnchorAndTheTableIsPinnedToAnotherBase() throws Exception {
		Path vanilla = vanillaJar();
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED), "staged merged base absent");
		TestFixtures.require(Fixture.MC_LIBRARIES, Files.isRegularFile(vanilla), "stock 1.21.1 absent: " + vanilla);
		Map<String, Shape> vanillaShapes = shapes(vanilla), mergedShapes = shapes(MERGED);

		try (ZipFile zip = new ZipFile(vanilla.toFile())) {
			assertNull(zip.getEntry("net/minecraft/world/level/Level.class"),
					"the vanilla fixture can answer for the game again — restore the derivation this test used to run");
			assertNotNull(zip.getEntry("net/minecraft/client/main/Main.class"),
					"the vanilla fixture must still be the real 1.21.1 launcher jar: " + vanilla);
		}
		assertTrue(vanillaShapes.size() < 20, "this fixture yields only the launcher's own anonymous classes, not "
				+ "the game's: " + vanillaShapes.keySet());
		assertTrue(mergedShapes.size() > 500, "this does not look like a full merged base ("
				+ mergedShapes.size() + " anonymous classes)");

		int absent = 0;
		for (String name : MergedBaseAnonymousDrift.RESHAPED) {
			if (!mergedShapes.containsKey(name)) absent++;
		}
		for (String name : MergedBaseAnonymousDrift.CAPTURE_ONLY) {
			if (!mergedShapes.containsKey(name)) absent++;
		}
		assertTrue(absent > 0, "every class the pinned table names is in this base — the derivation can run again, so "
				+ "re-derive the three buckets instead of leaving them uncompared");
	}

	private static Map<String, Shape> shapes(Path jar) throws IOException {
		Map<String, Shape> out = new HashMap<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : zip.stream().toList()) {
				if (!ANONYMOUS.matcher(entry.getName()).matches()) continue;
				ClassNode node;
				try (InputStream in = zip.getInputStream(entry)) {
					node = new ClassNode();
					new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				}
				Set<String> methods = new TreeSet<>();
				String ctor = "";
				for (MethodNode m : node.methods) {
					if ("<init>".equals(m.name)) ctor = m.desc; else if (!"<clinit>".equals(m.name)) methods.add(m.name + m.desc);
				}
				Set<String> captures = new TreeSet<>();
				for (FieldNode f : node.fields) if (f.name.startsWith("val$") || f.name.startsWith("this$")) captures.add(f.name + ":" + f.desc);
				out.put(entry.getName().substring(0, entry.getName().length() - ".class".length()),
						new Shape(node.superName, methods, ctor, captures));
			}
		}
		return out;
	}

	private static Path vanillaJar() {
		return TestFixtures.vanillaJar();
	}
}

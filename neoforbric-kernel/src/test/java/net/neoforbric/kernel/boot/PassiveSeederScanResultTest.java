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

import static net.neoforbric.kernel.boot.PassiveSeederLoadingModListTest.call;
import static net.neoforbric.kernel.boot.PassiveSeederLoadingModListTest.seededList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import net.neoforbric.api.ModPresence;
import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import net.neoforbric.kernel.discovery.NeoForbricModDiscoverer;
import net.neoforbric.kernel.discovery.ModFileScanner;

/**
 * The index a mod file answers {@code getScanResult()} with — the walk RollingGate's constructor makes, and the
 * seam on this anchor where that index is reachable.
 *
 * <p>What paid for it: RollingGate's constructor walks {@code LoadingModList.getModFiles()} and reads every entry's
 * {@code getFile().getScanResult().getAnnotations()} for its rule containers. Seeded files had no scan, so it threw
 * out of its own constructor and every RollingGate and Server++ rule was missing.
 *
 * <p>RE-ANCHORED to 1.21.1, and the loss is a PRODUCTION gap rather than a contract change:
 * {@code PassiveSeeder.buildModFile} fills the 26.x {@code ModFile} fields {@code contents} (a
 * {@code net.neoforged.fml.jarcontents.JarContents}) and {@code id}, and neither that package nor those fields
 * exist on this line — 1.21.1's {@code ModFile} carries the jar as a {@link cpw.mods.jarhandling.SecureJar} in a
 * {@code jar} field — so every seeded file keeps a NULL file and that walk cannot reach a scan result at all.
 * {@link #theSeededListStillResolvesEveryMod} pins that consequence; it is reported, not papered over.
 *
 * <p>Every claim the old tests made about a seeded file's index is kept, on the file the kernel DOES build on this
 * anchor: {@code KernelModFile}, the game-side {@code IModFile}, whose {@code getScanResult()} hands out the very
 * same {@code ModFileScanner.scanShared} index a seeded file was meant to hand out. So, each its own failure mode:
 * a NeoForge jar answers with ITS index, in FML's shape, and with the same object for every reader of that jar;
 * nothing is scanned until a mod asks; a jar-less presence alias answers empty, never with the index of a jar
 * another ecosystem is running; a jar that cannot be indexed answers empty instead of throwing; and
 * {@code -Dneoforbric.seededScanData=off} puts every reader back on its own index.
 */
class PassiveSeederScanResultTest {
	private static final String RULES = "Lprobe/Rules;";

	@TempDir
	Path tmp;

	@AfterEach
	void clearGlobals() {
		System.clearProperty(ModFileScanner.SEEDED_INDEX_PROPERTY);
		ModPresence.publishFabric(List.of());
		ModPresence.publishForgeFamily(List.of());
		MultiLoaderArbiter.reset();
	}

	/**
	 * The seeded list itself still holds every mod — the half of the old subject that survives on this anchor.
	 *
	 * <p>Its files do not: each {@code ModFileInfo} keeps a null file, because no {@code ModFile} can be built here
	 * (see the class javadoc), so {@code getModFileById(id).getFile().getScanResult()} — the walk RollingGate makes
	 * — cannot be reached through the list at all.
	 *
	 * <p>The MinecraftForge jar is in the fixture to be left out: this loader runs Fabric and NeoForge mods, so a
	 * jar whose only Forge-family manifest is the traditional {@code META-INF/mods.toml} is named and skipped
	 * rather than listed. The old test asserted the opposite — that it was in the list for presence, with an empty
	 * index — which was the shape while the base still carried {@code net/minecraftforge}.
	 */
	@Test
	void theSeededListStillResolvesEveryMod() throws Exception {
		try (URLClassLoader game = runtimeLoader()) {
			Path mods = Files.createDirectories(tmp.resolve("mods"));
			writeJar(mods.resolve("neoprobe.jar"), "META-INF/neoforge.mods.toml", toml("neoprobe"),
					"probe/neo/RuleContainer");
			// MinecraftForge jar, annotated the same way: it is in this list for presence, and its index is the
			// other FML's business.
			writeJar(mods.resolve("forgeprobe.jar"), "META-INF/mods.toml", toml("forgeprobe"),
					"probe/forge/RuleContainer");
			// A Fabric jar, listed for presence only; outside mods/ as the Fabric side's own jars are.
			Path fabricJar = Files.createDirectories(tmp.resolve("fabric")).resolve("fabricprobe.jar");
			writeJar(fabricJar, "fabric.mod.json",
					"{\"schemaVersion\":1,\"id\":\"fabricprobe\",\"version\":\"1.0.0\"}", "probe/fabric/RuleContainer");
			ModPresence.publishFabric(new NeoForbricModDiscoverer().discoverJar(fabricJar));

			Object list = seed(game, mods);

			for (String id : List.of("neoprobe", "fabricprobe")) {
				Object fileInfo = call(list, "getModFileById", String.class, id);
				assertNotNull(fileInfo, id + " must still resolve through the seeded list");
				assertNull(call(fileInfo, "getFile"), id + " carries no file: no ModFile can be built on this anchor");
			}
			assertNull(call(list, "getModFileById", String.class, "forgeprobe"),
					"a MinecraftForge-only jar is not a Forge-family mod on this anchor, so it is not listed");
		}
	}

	/**
	 * A NeoForge jar's file answers with ITS index, in FML's shape, and with the same object for every reader of that
	 * jar; a jar-less presence alias answers empty instead of exposing a jar another ecosystem is running.
	 *
	 * <p>Was {@code aSeededNeoForgeFileAnswersWithItsJarsIndexAndOtherEcosystemsAnswerEmpty}: the same three claims,
	 * read off the seeded files (a NeoForge one, a Fabric presence entry, a MinecraftForge entry). Those files are
	 * null on this anchor, so they are read off {@code KernelModFile}, the file the kernel does build — including the
	 * jar-less alias, which is the shape a Fabric or MinecraftForge presence entry has in {@code ModList}.
	 */
	@Test
	void aNeoForgeJarAnswersWithItsOwnIndexAndAJarlessAliasWithAnEmptyOne() throws Exception {
		try (URLClassLoader game = runtimeLoader()) {
			Path mods = Files.createDirectories(tmp.resolve("mods"));
			Path neoJar = mods.resolve("neoprobe.jar");
			writeJar(neoJar, "META-INF/neoforge.mods.toml", toml("neoprobe"), "probe/neo/RuleContainer");
			seed(game, mods);

			Object scan = call(file(game, "neoprobe", neoJar), "getScanResult");
			assertEquals(List.of("probe.neo.RuleContainer"),
					membersAnnotatedWith((Set<?>) call(scan, "getAnnotations"), RULES),
					"the jar's own index, in FML's shape (TYPE members are dotted names)");
			assertSame(scan, call(file(game, "neoprobe", neoJar), "getScanResult"),
					"computed once, then the same object for every reader of that jar");

			assertTrue(((Set<?>) call(call(file(game, "fabricprobe", null), "getScanResult"), "getAnnotations"))
					.isEmpty(),
					"a presence alias has no jar, so it answers empty — never the index of a jar Fabric is running");
		}
	}

	@Test
	void aFileReachingTheJarThroughALinkStillReadsTheSameIndex() throws Exception {
		// One jar is one index however its path is spelled: a linked instance folder, macOS's /var for /private/var.
		try (URLClassLoader game = runtimeLoader()) {
			Path mods = Files.createDirectories(tmp.resolve("mods"));
			Path neoJar = mods.resolve("linkprobe.jar");
			writeJar(neoJar, "META-INF/neoforge.mods.toml", toml("linkprobe"), "probe/link/RuleContainer");
			Path link = Files.createSymbolicLink(tmp.resolve("linked.jar"), neoJar);
			seed(game, mods);

			assertSame(call(file(game, "linkprobe", neoJar), "getScanResult"),
					call(file(game, "linkprobe", link), "getScanResult"));
		}
	}

	@Test
	void nothingIsScannedUntilAModAsks() throws Exception {
		try (URLClassLoader game = runtimeLoader()) {
			Path mods = Files.createDirectories(tmp.resolve("mods"));
			Path jar = mods.resolve("lazyprobe.jar");
			writeJar(jar, "META-INF/neoforge.mods.toml", toml("lazyprobe"), null);

			seed(game, mods);
			Object file = file(game, "lazyprobe", jar); // built, but not read: the index is still unbuilt

			// The jar gains its annotated class only AFTER the file exists. An index built at construction would not
			// have it.
			writeJar(jar, "META-INF/neoforge.mods.toml", toml("lazyprobe"), "probe/lazy/RuleContainer");
			Files.setLastModifiedTime(jar, FileTime.fromMillis(System.currentTimeMillis() + 10_000));

			assertEquals(List.of("probe.lazy.RuleContainer"),
					membersAnnotatedWith((Set<?>) call(call(file, "getScanResult"), "getAnnotations"), RULES),
					"the scan must run when getScanResult() is first called, not when the file is built");
		}
	}

	/**
	 * A file the ASM pass cannot read at all is the one way {@code ModFileScanner.scan} hands back null for a file
	 * that exists; its reader must then keep an empty answer rather than throw at whoever asked.
	 *
	 * <p>Was {@code aJarThatCannotBeIndexedAnswersEmptyInsteadOfThrowing}, which read that off a seeded file. The
	 * scanner half (null rather than a throw) is {@code ModFileScannerTest.scanReturnsNullRatherThanThrowingWhenTheSpiIsAbsent};
	 * what is asserted here is the reader's half, which no other test covers.
	 */
	@Test
	void aJarThatCannotBeIndexedAnswersEmptyInsteadOfThrowing() throws Exception {
		try (URLClassLoader game = runtimeLoader()) {
			Path mods = Files.createDirectories(tmp.resolve("mods"));
			Path notAJar = mods.resolve("brokenprobe.jar");
			Files.writeString(notAJar, "not a jar at all\n");
			seed(game, mods);

			Object file = file(game, "brokenprobe", notAJar);
			Object scan = call(file, "getScanResult");
			assertTrue(((Set<?>) call(scan, "getAnnotations")).isEmpty(), "an unindexable jar reads as empty");
			assertSame(scan, call(file, "getScanResult"), "and says so once: the empty answer is kept");
		}
	}

	/**
	 * {@code -Dneoforbric.seededScanData=off} puts every reader back on its own index.
	 *
	 * <p>Was {@code offSwitchIsTheOldNotStartedYetThrow}: with the switch off the seeded file had no scan at all, so
	 * its {@code getScanResult()} threw FML's own "Scanning of this mod file has not started yet." — the behaviour
	 * before {@code seedScanResult} existed. There is no seeded file on this anchor (see the class javadoc), so the
	 * switch is asserted where it is still observable: sharing on, then sharing off.
	 */
	@Test
	void offSwitchPutsEveryReaderBackOnItsOwnIndex() throws Exception {
		try (URLClassLoader game = runtimeLoader()) {
			Path mods = Files.createDirectories(tmp.resolve("mods"));
			Path jar = mods.resolve("offprobe.jar");
			writeJar(jar, "META-INF/neoforge.mods.toml", toml("offprobe"), "probe/off/RuleContainer");
			seed(game, mods);

			assertSame(call(file(game, "offprobe", jar), "getScanResult"),
					call(file(game, "offprobe", jar), "getScanResult"), "on: one index per jar, handed to every reader");

			System.setProperty(ModFileScanner.SEEDED_INDEX_PROPERTY, "off");
			assertFalse(ModFileScanner.seededIndexEnabled());
			assertNotSame(call(file(game, "offprobe", jar), "getScanResult"),
					call(file(game, "offprobe", jar), "getScanResult"),
					"off: every reader scans its own jar again, the pre-shared-index behaviour");
		}
	}

	// --- helpers ---

	/** The game-side {@code IModFile} the kernel builds for a jar: {@code ModList}'s own file type. */
	private static Object file(ClassLoader game, String modId, Path jar) throws Exception {
		return Class.forName("net.neoforbric.kernel.runtime.KernelModFile", true, game)
				.getConstructor(String.class, Path.class).newInstance(modId, jar);
	}

	private static Object seed(ClassLoader game, Path mods) throws Exception {
		PassiveSeederLoadingModListTest.FakeFmlLoader loader = new PassiveSeederLoadingModListTest.FakeFmlLoader();
		PassiveSeeder.seedNeoForgeLoadingModList(game, PassiveSeederLoadingModListTest.FakeFmlLoader.class, loader,
				mods);
		Object list = seededList(loader);
		// Every input of the seed is here by now, so seeding nothing is the kernel's failure, not a missing fixture.
		assertNotNull(list, "no LoadingModList was seeded");
		return list;
	}

	private static List<String> membersAnnotatedWith(Set<?> annotations, String descriptor) throws Exception {
		List<String> members = new ArrayList<>();
		for (Object data : annotations) {
			Object type = data.getClass().getMethod("annotationType").invoke(data);
			if (descriptor.equals(type.getClass().getMethod("getDescriptor").invoke(type))) {
				members.add((String) data.getClass().getMethod("memberName").invoke(data));
			}
		}
		return members;
	}

	/**
	 * The staged neoforge-runtime plus the kernel's compiled game side, child of this test's loader so the
	 * boot-side scanner is ONE class on both sides, the way {@code DelegationPolicy} pins it in a real boot.
	 */
	private URLClassLoader runtimeLoader() throws Exception {
		Path compiled = Path.of(System.getProperty("neoforbric.testRuntimeClasses", "build/classes/java/runtime"));
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(PassiveSeederLoadingModListTest.NEO_RUNTIME),
				"staged neoforge-runtime or compiled game side absent");
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(compiled),
				"staged neoforge-runtime or compiled game side absent");
		Path stubs = PassiveSeederLoadingModListTest.loggingStubs(tmp.resolve("stubs"));
		return new GameLoader(new URL[] {stubs.toUri().toURL(), compiled.toUri().toURL(),
				PassiveSeederLoadingModListTest.NEO_RUNTIME.toUri().toURL(),
				PassiveSeederLoadingModListTest.commonsLang3()},
				PassiveSeederScanResultTest.class.getClassLoader());
	}

	/**
	 * Parent-first, as {@code runtimeLoader}'s comment requires — the boot-side {@code ModFileScanner} has to be ONE
	 * class on both sides — except for the two logging packages, which this loader must serve from its own stand-ins.
	 *
	 * <p>Its parent carries Minecraft's real {@code com.mojang.logging.LogUtils} (build.gradle routes Minecraft's
	 * library set onto the test runtime classpath) and deliberately carries no slf4j (the suite's contract is a
	 * classpath with no logging backend, so several tests can assert NeoForbricLog's fallback). Parent-first, the
	 * real {@code LogUtils.<clinit>} therefore wins over the stand-in and dies on
	 * {@code NoClassDefFoundError: org/slf4j/MarkerFactory} inside {@code LoadingModList.<clinit>}, which takes the
	 * whole seeding with it. Same shape as {@code FmlTransformerViewTest.GameLoader}.
	 */
	private static final class GameLoader extends URLClassLoader {
		GameLoader(URL[] urls, ClassLoader parent) {
			super(urls, parent);
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (!name.startsWith("com.mojang.logging.") && !name.startsWith("org.slf4j.")) {
				return super.loadClass(name, resolve);
			}
			synchronized (getClassLoadingLock(name)) {
				Class<?> loaded = findLoadedClass(name);
				if (loaded == null) {
					try {
						loaded = findClass(name);
					} catch (ClassNotFoundException noStub) {
						return super.loadClass(name, resolve);
					}
				}
				if (resolve) resolveClass(loaded);
				return loaded;
			}
		}
	}

	private static String toml(String modId) {
		return "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"test\"\n[[mods]]\nmodId=\"" + modId
				+ "\"\nversion=\"1.0.0\"\n";
	}

	/** A jar with one metadata file and, optionally, one class carrying a CLASS-retention {@code @probe.Rules}. */
	private static void writeJar(Path jar, String metadataName, String metadata, String annotatedClass)
			throws Exception {
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			PassiveSeederLoadingModListTest.put(zip, metadataName, metadata);
			if (annotatedClass != null) {
				zip.putNextEntry(new ZipEntry(annotatedClass + ".class"));
				zip.write(annotated(annotatedClass));
				zip.closeEntry();
			}
		}
	}

	private static byte[] annotated(String internalName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
		// Invisible, as RollingGate's container annotation and @JeiPlugin are: the index reads bytecode.
		AnnotationVisitor rules = cw.visitAnnotation(RULES, false);
		rules.visit("value", "probe_rules");
		rules.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}
}

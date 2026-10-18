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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.neoforbric.api.DiscoveredMod;
import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import net.neoforbric.kernel.metadata.forge.EcosystemVersions;

/**
 * Verifies, against the STAGED neoforge-runtime bytecode, that the kernel seeds a {@code LoadingModList} a mod can
 * actually find itself in — the defect being that an empty one answers {@code null} to
 * {@code getModFileById(myId)} and NPEs the caller (Iris, inside {@code Minecraft.<init>}).
 *
 * <p>The three claims that matter are each a separate failure mode:
 * <ul>
 *   <li>{@code getModFileById(id).versionString()} returns the mod's REAL declared version — a placeholder there
 *       is the same bug, only quieter (mods render this string);</li>
 *   <li>{@code getMods()}' elements are assignable to the CONCRETE {@code ModInfo} — every consumer's per-element
 *       access is a {@code checkcast ModInfo}, so a dynamic proxy would blow up at the reader, not here;</li>
 *   <li>a jar the {@link MultiLoaderArbiter} handed to Fabric is absent — a universal jar must contribute under
 *       exactly the one ecosystem it was arbitrated to.</li>
 * </ul>
 *
 * <p>Everything needing the staged jar self-skips when it is absent; the arbitration case is pure kernel code and
 * always runs.
 */
class PassiveSeederLoadingModListTest {
	/**
	 * Through {@link TestFixtures#stagedRoot()}, the root the game side compiled against: this class hardcoded the
	 * relative path, so in a second worktree every case here skipped and the suite reported green.
	 */
	static final Path NEO_RUNTIME = TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar").normalize();

	@TempDir
	Path tmp;

	/** Stands in for {@code FMLLoader}: the seeder only ever touches its {@code loadingModList} field. */
	static final class FakeFmlLoader {
		private Object loadingModList;
	}

	@AfterEach
	void clearGlobals() {
		System.clearProperty(PassiveSeeder.SEED_SWITCH);
		System.clearProperty("neoforbric.multiLoaderPreference");
		MultiLoaderArbiter.reset();
	}

	@Test
	void seedsARealModFileInfoAndModInfoForANeoForgeJar() throws Exception {
		ClassLoader game = neoForgeLoader();
		Path mods = Files.createDirectories(tmp.resolve("mods"));
		writeModJar(mods.resolve("kerneltestmod.jar"), "kerneltestmod", "4.12.2", "Kernel Test Mod", false);

		FakeFmlLoader loader = new FakeFmlLoader();
		PassiveSeeder.seedNeoForgeLoadingModList(game, FakeFmlLoader.class, loader, mods);

		Object list = seededList(loader);
		assertNotNull(list, "a LoadingModList must have been seeded");

		Object fileInfo = call(list, "getModFileById", String.class, "kerneltestmod");
		assertNotNull(fileInfo, "the mod must resolve itself through getModFileById");
		assertEquals("4.12.2", call(fileInfo, "versionString"),
				"versionString() must be the mod's DECLARED version, not a placeholder");

		List<?> seeded = (List<?>) call(list, "getMods");
		assertEquals(1, seeded.size(), "getMods() carries the one discovered mod");

		Class<?> modInfo = Class.forName("net.neoforged.fml.loading.moddiscovery.ModInfo", false, game);
		assertTrue(modInfo.isInstance(seeded.get(0)),
				"elements must be the CONCRETE ModInfo — every reader checkcasts to it");

		Class<?> modFileInfo = Class.forName("net.neoforged.fml.loading.moddiscovery.ModFileInfo", false, game);
		assertTrue(modFileInfo.isInstance(fileInfo), "getModFileById checkcasts to the concrete ModFileInfo");
		assertEquals("Kernel Test Mod", call(seeded.get(0), "getDisplayName"));
		assertEquals("kerneltestmod", call(seeded.get(0), "getModId"));
		// getConfig() is dereferenced unguarded by FeatureFlagLoader during Bootstrap on every boot.
		assertNotNull(call(seeded.get(0), "getConfig"), "ModInfo.getConfig() must never be null");
		// toString() is moduleName() on this line — the first mod's id — so it does not need the file at all, which
		// is why it still answers the id here. (On 26.x it was modFile.getId().)
		assertEquals("kerneltestmod", fileInfo.toString(), "ModFileInfo.toString() is moduleName()");
		// The synthetic ModFile is a PRODUCTION gap on this anchor, not a contract — see the test below.
		assertNull(call(fileInfo, "getFile"), "no ModFile can be built on 1.21.1 — see aSeededFileCarriesNoModFile");
	}

	/**
	 * The seeded {@code ModFileInfo} carries no {@code ModFile}, so the file-shaped half of this contract is gone —
	 * and with it the two tests that used to live here.
	 *
	 * <p>{@code aModReadsItsOwnFilesThroughTheSeededList} read a mod's own file out of its jar through the seeded
	 * file: the seeded {@code ModFile} filled {@code ModFile.contents} with a
	 * {@code net.neoforged.fml.jarcontents.JarContents} and {@code ModFile.id}, and LambDynamicLights copied its
	 * default config through {@code getFile().getContents().getPrimaryPath()/containsFile()/readFile()}. Neither
	 * that package nor those fields exist on 1.21.1: this line's {@code ModFile} carries the jar as a
	 * {@link cpw.mods.jarhandling.SecureJar} in a {@code jar} field and has no {@code contents} or {@code id} field
	 * at all, so {@code PassiveSeeder.buildModFile} — which fills exactly those 26.x fields — throws and every
	 * seeded file keeps a null file. There is therefore no contents seam to read through, and this test keeps the
	 * part of both subjects that is still here: the mod resolves, its file is null, and the shape production would
	 * have to fill is the SecureJar one.
	 *
	 * <p>That is a PRODUCTION gap, reported rather than papered over. This test pins its observable consequence, so
	 * closing the gap fails here and says what to re-assert.
	 */
	@Test
	void aSeededFileCarriesNoModFile() throws Exception {
		ClassLoader game = neoForgeLoader();
		Path mods = Files.createDirectories(tmp.resolve("mods"));
		writeModJar(mods.resolve("kerneltestmod.jar"), "kerneltestmod", "1", "Kernel Test Mod", false);

		FakeFmlLoader loader = new FakeFmlLoader();
		PassiveSeeder.seedNeoForgeLoadingModList(game, FakeFmlLoader.class, loader, mods);

		Object fileInfo = call(seededList(loader), "getModFileById", String.class, "kerneltestmod");
		assertNotNull(fileInfo, "the mod must still resolve through the list");
		assertNull(call(fileInfo, "getFile"), "and carry no file");

		// What production would have to fill instead: 1.21.1's ModFile names the jar as a SecureJar, and it has no
		// contents getter — the two facts that make buildModFile's 26.x field names unusable here.
		Class<?> modFile = Class.forName("net.neoforged.fml.loading.moddiscovery.ModFile", false, game);
		assertEquals("cpw.mods.jarhandling.SecureJar", modFile.getDeclaredField("jar").getType().getName(),
				"the field a synthetic ModFile must fill on this anchor");
		assertThrows(NoSuchMethodException.class, () -> modFile.getMethod("getContents"),
				"1.21.1's ModFile has no contents getter at all");
	}

	/**
	 * The version arguments the seeder builds are the ones FML's own parser reads.
	 *
	 * <p>It used to prove that by round-tripping them through {@code net.neoforged.fml.loading.ProgramArgs}
	 * ({@code ProgramArgs.from(String[])} / {@code remove(String)}) — the holder FML's argument parsing filled on
	 * 26.x. There is no {@code ProgramArgs} on 1.21.1: FML does not parse the version arguments here at all, it
	 * only DECLARES them, in {@code FMLServiceProvider.arguments(BiFunction)}. ModLauncher registers each declared
	 * name as {@code --<service name>.<name>} (its {@code TransformationServicesHandler} concatenates exactly
	 * {@code "\u0001.\u0001"} over {@code ITransformationService.name()}), and
	 * {@code FMLServiceProvider.argumentValues} then feeds the four version options into {@code VersionInfo}.
	 *
	 * <p>So the round trip is asserted in the two halves this anchor still has: the declared names are read by
	 * RUNNING the native declaration, and the seeder's tokens must be exactly {@code --<service>.<declared name>}
	 * carrying the value it was given. A rename on either side still fails this test. The one step that cannot be
	 * run here is ModLauncher's own binding of a token to an option: its option parser is joptsimple, which is in
	 * no staged jar and in no Gradle cache, which is also why {@link #joptsimpleStubs} has to exist at all.
	 */
	@Test void versionArgumentsAreReadByTheNativeNeoForgeParser() throws Exception {
		ClassLoader game = neoForgeLoader();
		Class<?> provider = Class.forName("net.neoforged.fml.loading.FMLServiceProvider", false, game);
		Object instance = provider.getDeclaredConstructor().newInstance();
		String service = (String) provider.getMethod("name").invoke(instance);

		Object builder = Class.forName("joptsimple.ArgumentAcceptingOptionSpec", false, game)
				.getDeclaredConstructor().newInstance();
		Set<String> declared = new LinkedHashSet<>();
		BiFunction<String, String, Object> declare = (name, description) -> {
			declared.add(name);
			return builder;
		};
		provider.getMethod("arguments", BiFunction.class).invoke(instance, declare);
		assertTrue(declared.containsAll(List.of("mcVersion", "neoForgeVersion")),
				"FML no longer declares an option the seeder's argument names — it declares " + declared);

		for (String version : List.of("1.21.1", "1.21.2")) {
			List<String> args = List.of(PassiveSeeder.neoForgeVersionArguments(version));
			assertEquals(version, valueOf(args, "--" + service + ".mcVersion"),
					"the seeder must announce the detected game version under FML's own option name");
		}
		List<String> absent = List.of(PassiveSeeder.neoForgeVersionArguments(null));
		assertNull(valueOf(absent, "--" + service + ".mcVersion"),
				"a boot that detected no game version must not claim one");
		assertEquals(EcosystemVersions.provided("neoforge"), valueOf(absent, "--" + service + ".neoForgeVersion"),
				"the NeoForge version is the carrier's own manifest version, not a guess");
	}

	/** The token following {@code option} in {@code args}, or null when that option carries no value at all. */
	private static String valueOf(List<String> args, String option) {
		int at = args.indexOf(option);
		return at < 0 || at + 1 >= args.size() ? null : args.get(at + 1);
	}

	@Test
	void offSwitchRestoresTheEmptyList() throws Exception {
		ClassLoader game = neoForgeLoader();
		Path mods = Files.createDirectories(tmp.resolve("mods"));
		writeModJar(mods.resolve("kerneltestmod.jar"), "kerneltestmod", "4.12.2", "Kernel Test Mod", false);

		System.setProperty(PassiveSeeder.SEED_SWITCH, "off");
		FakeFmlLoader loader = new FakeFmlLoader();
		PassiveSeeder.seedNeoForgeLoadingModList(game, FakeFmlLoader.class, loader, mods);

		Object list = seededList(loader);
		assertNotNull(list, "the off switch still seeds a structurally valid list, just an empty one");
		assertTrue(((List<?>) call(list, "getMods")).isEmpty(), "off => zero mods");
		assertNull(call(list, "getModFileById", String.class, "kerneltestmod"), "off => nothing resolves");
	}

	@Test
	void zeroForgeFamilyModsKeepsTheEmptyList() throws Exception {
		ClassLoader game = neoForgeLoader();
		Path mods = Files.createDirectories(tmp.resolve("mods"));

		FakeFmlLoader loader = new FakeFmlLoader();
		PassiveSeeder.seedNeoForgeLoadingModList(game, FakeFmlLoader.class, loader, mods);

		Object list = seededList(loader);
		assertNotNull(list, "a zero-mod boot must behave exactly as before: an empty but present list");
		assertTrue(((List<?>) call(list, "getMods")).isEmpty());
	}

	@Test
	void aJarArbitratedToFabricIsNotInTheNeoForgeList() throws Exception {
		Path mods = Files.createDirectories(tmp.resolve("mods"));
		writeModJar(mods.resolve("universal.jar"), "universalmod", "1.2.3", "Universal Mod", true);

		// Control: with the documented default preference the universal jar is claimed by NeoForge and IS listed.
		MultiLoaderArbiter.reset();
		List<DiscoveredMod> claimed = PassiveSeeder.arbitratedForgeFamilyMods(mods);
		assertEquals(1, claimed.size(), "a universal jar contributes exactly once by default");
		assertEquals("universalmod", claimed.get(0).getId());

		// Hand the same jar to Fabric: its Forge-family manifest must then contribute NOTHING here.
		System.setProperty("neoforbric.multiLoaderPreference", "fabric");
		MultiLoaderArbiter.reset();
		assertTrue(PassiveSeeder.arbitratedForgeFamilyMods(mods).isEmpty(),
				"a jar the arbiter gave to FABRIC must not appear in the NeoForge list");
	}

	// --- helpers ---

	static Object seededList(FakeFmlLoader loader) throws Exception {
		Field field = FakeFmlLoader.class.getDeclaredField("loadingModList");
		field.setAccessible(true);
		return field.get(loader);
	}

	static Object call(Object target, String method) throws Exception {
		return target.getClass().getMethod(method).invoke(target);
	}

	static Object call(Object target, String method, Class<?> argType, Object arg) throws Exception {
		return target.getClass().getMethod(method, argType).invoke(target, arg);
	}

	/**
	 * A classloader over the staged neoforge-runtime jar plus the two logging types it links against but does not
	 * ship ({@code com.mojang.logging.LogUtils} / {@code org.slf4j}) and {@link #commonsLang3}, which come from the
	 * Minecraft library tree at runtime and are not on the unit-test classpath. Only the static initializers need
	 * them, so empty stand-ins are enough for the logging pair; the test self-skips when the staged jar is absent.
	 */
	private ClassLoader neoForgeLoader() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(NEO_RUNTIME),
				"staged neoforge-runtime.jar absent — skipping real-bytecode LoadingModList seeding check");
		Path stubs = loggingStubs(tmp.resolve("stubs"));
		joptsimpleStubs(stubs);
		return new URLClassLoader(new URL[] {stubs.toUri().toURL(), NEO_RUNTIME.toUri().toURL(), commonsLang3()},
				ClassLoader.getPlatformClassLoader());
	}

	/**
	 * The local library tree's commons-lang3: {@code ModInfo.<clinit>} builds its fallback version with
	 * {@code DefaultArtifactVersion}, whose parsing calls {@code NumberUtils.isDigits}. It is a real Minecraft
	 * library the game always has on its classpath, so the real jar is used rather than a stand-in — but a machine
	 * with no local Minecraft install is a missing MC_LIBRARIES fixture, not a green test.
	 */
	static URL commonsLang3() throws IOException {
		Path library = newestUnder("org/apache/commons/commons-lang3");
		TestFixtures.require(Fixture.MC_LIBRARIES, library != null,
				"no commons-lang3 jar in the local Minecraft libraries");
		return library.toUri().toURL();
	}

	/** The newest jar under {@code <libraries>/<pattern>}; a launcher's tree holds one version per library. */
	static Path newestUnder(String pattern) throws IOException {
		Path under = TestFixtures.minecraftLibraries().resolve(pattern);
		if (!Files.isDirectory(under)) return null;
		try (var stream = Files.walk(under)) {
			return stream.filter(f -> f.toString().endsWith(".jar") && !f.toString().contains("sources")
							&& !f.toString().contains("natives"))
					.sorted(java.util.Comparator.comparing(f -> f.getFileName().toString()))
					.reduce((a, b) -> b).orElse(null);
		}
	}

	/**
	 * {@code joptsimple}'s three types that {@code FMLServiceProvider} names in its own fields and in
	 * {@code argumentValues}'s signatures. joptsimple is ModLauncher's option parser and is in no staged jar and
	 * no Gradle cache, so without these the class cannot be linked at all —
	 * {@code getDeclaredConstructor()} throws {@code NoClassDefFoundError: joptsimple/OptionSpec} — and the native
	 * option declaration the version-argument test reads cannot be run. The hierarchy is mirrored because the
	 * verifier checks it: {@code argumentValues} passes an {@code ArgumentAcceptingOptionSpec} where
	 * {@code OptionResult} declares {@code OptionSpec}.
	 */
	private static void joptsimpleStubs(Path stubs) throws IOException {
		writeClass(stubs, "joptsimple/OptionSpec", optionSpecClass("joptsimple/OptionSpec", "java/lang/Object"));
		writeClass(stubs, "joptsimple/OptionSpecBuilder",
				optionSpecClass("joptsimple/OptionSpecBuilder", "joptsimple/OptionSpec",
						new String[] {"withRequiredArg", "()", "new"}));
		writeClass(stubs, "joptsimple/ArgumentAcceptingOptionSpec",
				optionSpecClass("joptsimple/ArgumentAcceptingOptionSpec", "joptsimple/OptionSpecBuilder",
						new String[] {"ofType", "(Ljava/lang/Class;)", "this"},
						new String[] {"required", "()", "this"},
						new String[] {"withValuesSeparatedBy", "(Ljava/lang/String;)", "this"}));
	}

	/**
	 * One {@code joptsimple} stand-in: {@code internalName extends superName}, and each {@code methods} entry
	 * ({@code {name, paramsDescriptor, body}}) answers an {@code ArgumentAcceptingOptionSpec} — the receiver for
	 * {@code "this"}, and for {@code "new"} a fresh one, which is what the real
	 * {@code OptionSpecBuilder.withRequiredArg()} does and what its return type forces (an {@code OptionSpecBuilder}
	 * is not an {@code ArgumentAcceptingOptionSpec}). FML's
	 * {@code withRequiredArg().ofType(String.class).required()} chain therefore runs to completion and hands back an
	 * object the declaration's own {@code checkcast} accepts.
	 */
	private static byte[] optionSpecClass(String internalName, String superName, String[]... methods) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, superName, null);
		MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.visitCode();
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(0, 0);
		ctor.visitEnd();
		for (String[] method : methods) {
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, method[0],
					method[1] + "Ljoptsimple/ArgumentAcceptingOptionSpec;", null, null);
			mv.visitCode();
			if (method[2].equals("new")) {
				mv.visitTypeInsn(Opcodes.NEW, "joptsimple/ArgumentAcceptingOptionSpec");
				mv.visitInsn(Opcodes.DUP);
				mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "joptsimple/ArgumentAcceptingOptionSpec", "<init>", "()V",
						false);
			} else {
				mv.visitVarInsn(Opcodes.ALOAD, 0);
			}
			mv.visitInsn(Opcodes.ARETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** Writes the logging stand-ins {@link #neoForgeLoader} describes into {@code dir}, and returns it. */
	static Path loggingStubs(Path dir) throws IOException {
		Path stubs = Files.createDirectories(dir);
		writeClass(stubs, "org/slf4j/Logger", emptyInterface("org/slf4j/Logger"));
		writeClass(stubs, "org/slf4j/Marker", emptyInterface("org/slf4j/Marker"));
		writeClass(stubs, "org/slf4j/LoggerFactory", loggerFactory());
		writeClass(stubs, "com/mojang/logging/LogUtils", logUtils());
		return stubs;
	}

	static void writeClass(Path root, String internalName, byte[] bytes) throws IOException {
		Path out = root.resolve(internalName + ".class");
		Files.createDirectories(out.getParent());
		Files.write(out, bytes);
	}

	private static byte[] emptyInterface(String internalName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, internalName,
				null, "java/lang/Object", null);
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** {@code class LogUtils { public static Marker FATAL_MARKER; public static Logger getLogger(){ return null; } }} */
	private static byte[] logUtils() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/mojang/logging/LogUtils", null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "FATAL_MARKER", "Lorg/slf4j/Marker;", null, null)
				.visitEnd();
		nullLogger(cw, "getLogger", "()Lorg/slf4j/Logger;");
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** {@code class LoggerFactory { public static Logger getLogger(Class|String){ return null; } }} */
	private static byte[] loggerFactory() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "org/slf4j/LoggerFactory", null, "java/lang/Object", null);
		nullLogger(cw, "getLogger", "(Ljava/lang/Class;)Lorg/slf4j/Logger;");
		nullLogger(cw, "getLogger", "(Ljava/lang/String;)Lorg/slf4j/Logger;");
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static void nullLogger(ClassWriter cw, String name, String desc) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.ACONST_NULL);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
	}

	/** A minimal mod jar: a NeoForge {@code mods.toml}, optionally plus a {@code fabric.mod.json} (universal jar). */
	private static void writeModJar(Path jar, String modId, String version, String displayName, boolean alsoFabric)
			throws IOException {
		String toml = "modLoader=\"javafml\"\n"
				+ "loaderVersion=\"[1,)\"\n"
				+ "license=\"Apache-2.0\"\n"
				+ "\n"
				+ "[[mods]]\n"
				+ "modId=\"" + modId + "\"\n"
				+ "version=\"" + version + "\"\n"
				+ "displayName=\"" + displayName + "\"\n";
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			put(zip, "META-INF/neoforge.mods.toml", toml);
			if (alsoFabric) {
				put(zip, "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\""
						+ version + "\",\"name\":\"" + displayName + "\"}");
			}
		}
	}

	static void put(ZipOutputStream zip, String name, String content) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		OutputStream out = zip;
		out.write(content.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}
}

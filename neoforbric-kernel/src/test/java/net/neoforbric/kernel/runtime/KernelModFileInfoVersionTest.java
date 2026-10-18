package net.neoforbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.neoforbric.api.ModPresence;
import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import net.neoforbric.kernel.discovery.NeoForbricModDiscoverer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** The same discovery -> ModInfo -> owning file path PAL reads during its constructor. */
class KernelModFileInfoVersionTest {
    private static final Path STAGED = TestFixtures.stagedRoot();
    @TempDir Path temp;

    @AfterEach
    void clearMetadata() {
        ModPresence.publishForgeFamily(List.of());
        ModPresence.publishFabric(List.of());
    }

    @Test
    void bothCarriersDelegateVersionStringToTheFirstModVersion() throws Exception {
        for (String family : List.of("forge", "neoforge")) {
            Path jar = STAGED.resolve(family + "-runtime/" + family + "-runtime.jar");
            TestFixtures.require(Fixture.STAGED, Files.isRegularFile(jar), "staged carrier absent");
            String namespace = family.equals("forge") ? "net/minecraftforge" : "net/neoforged";
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                String entry = namespace + "/fml/loading/moddiscovery/ModFileInfo.class";
                ClassNode node = new ClassNode();
                new ClassReader(zip.getInputStream(zip.getEntry(entry)).readAllBytes()).accept(node, 0);
                var method = node.methods.stream().filter(m -> m.name.equals("versionString")).findFirst().orElseThrow();
                var calls = new java.util.ArrayList<String>();
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call) calls.add(call.name + call.desc);
                }
                assertTrue(calls.contains("getMods()Ljava/util/List;"), calls.toString());
                assertTrue(calls.contains("getVersion()Lorg/apache/maven/artifact/versioning/ArtifactVersion;"), calls.toString());
                assertTrue(calls.contains("toString()Ljava/lang/String;"), calls.toString());
            }
        }
    }

    @Test
    void neoDeclaredVersionWinsOverAnUnrelatedManifestVersion() throws Exception {
        Path jar = fixture("neo.jar", "META-INF/neoforge.mods.toml", "4.2.1-dev", "99.0");
        try (URLClassLoader runtime = runtimeLoader()) {
            String version = version(info(runtime, jar));
            assertEquals("4.2.1-dev", version);
            assertTrue(version.contains("dev"), "PAL's dev-version branch must receive the actual declared version");
        }
    }

    @Test
    void forgeDeclaredVersionIsNotReadForAnEcosystemThisLoaderDoesNotRun() throws Exception {
        // Re-anchored: META-INF/mods.toml alone is a traditional MinecraftForge mod, and this loader runs Fabric
        // and NeoForge only (NeoForbricModDiscoverer names and skips such a jar; EcosystemTest, AbiLinkAuditTest
        // and KernelDataPacksTest assert the same rule). Discovery therefore returns nothing, and the owning file
        // falls back to the unknown version — the point of this test is that the fallback is not a crash for it.
        Path jar = fixture("forge.jar", "META-INF/mods.toml", "3.8.7", null);
        assertTrue(new NeoForbricModDiscoverer().discoverJar(jar).isEmpty(),
                "a mods.toml-only jar is not discovered");
        try (URLClassLoader runtime = runtimeLoader()) {
            assertEquals("0.0", version(info(runtime, jar)), "no declaration means the unknown version, answered not thrown");
        }
    }

    @Test
    void bothTomlDialectsUseTheResolvedManifestPlaceholder() throws Exception {
        // Re-anchored: the loader reads exactly ONE Forge-family dialect, neoforge.mods.toml; mods.toml-only jars
        // are skipped by discovery. The placeholder resolution ${file.jarVersion} -> Implementation-Version is
        // still what this pins, now over the single dialect the loader actually serves.
        try (URLClassLoader runtime = runtimeLoader()) {
            Path jar = fixture("neo.jar", "META-INF/neoforge.mods.toml", "${file.jarVersion}", "7.6.5-dev");
            assertEquals("7.6.5-dev", version(info(runtime, jar)), "discovery must resolve neoforge.mods.toml");
        }
    }

    @Test
    void aPalReleaseVersionSupportsItsActualContainsCall() throws Exception {
        Path jar = fixture("pal.jar", "META-INF/neoforge.mods.toml", "1.2.6+mc.1.21.1", null);
        try (URLClassLoader runtime = runtimeLoader()) {
            String version = version(info(runtime, jar));
            assertEquals("1.2.6+mc.1.21.1", version);
            assertFalse(version.contains("dev"), "PAL must stay on its production branch, not be forced into dev mode");
        }
    }

    @Test
    void missingVersionKeepsTheOwnersExistingExplicitUnknownValue() throws Exception {
        Path jar = fixture("unknown.jar", "META-INF/neoforge.mods.toml", null, null);
        try (URLClassLoader runtime = runtimeLoader()) {
            Object owner = info(runtime, jar);
            String version = version(owner);
            assertNotNull(version);
            // KernelModMetadata already defines unknown as 0.0. This fix follows its owner, just as both
            // carriers do, instead of inventing a second fallback or changing all mod version semantics.
            assertEquals("0.0", version);
            assertEquals(owner.getClass().getMethod("getVersion").invoke(owner).toString(), version);
            assertFalse(version.contains("dev"));
        }
    }

    @Test
    void fileVersionUsesItsPublishedOwnerRatherThanLaterPresenceMetadata() throws Exception {
        Path first = fixture("first.jar", "META-INF/neoforge.mods.toml", "2.0.1", null);
        Path later = fixture("later.jar", "META-INF/neoforge.mods.toml", "9.9.9-dev", null);
        try (URLClassLoader runtime = runtimeLoader()) {
            Object owner = info(runtime, first);
            ModPresence.publishForgeFamily(new NeoForbricModDiscoverer().discoverJar(later));
            assertEquals("2.0.1", version(owner), "versionString must agree with its owner even if global discovery changes");
        }
    }

    private Object info(ClassLoader runtime, Path jar) throws Exception {
        ModPresence.publishForgeFamily(new NeoForbricModDiscoverer().discoverJar(jar));
        return Class.forName("net.neoforbric.kernel.runtime.KernelModInfo", true, runtime)
                .getConstructor(String.class, Path.class).newInstance("versionprobe", jar);
    }

    /**
     * Re-anchored to 1.21.1: this asserted the 26.x shape, where {@code IModFile} exposed a
     * {@code net.neoforged.fml.jarcontents.JarContents} with a {@code visitContent(path, visitor)} walk. Neither
     * that package nor that visitor exists on this line — 1.21.1's {@code IModFile} carries the jar as a
     * {@link cpw.mods.jarhandling.SecureJar} ({@code getSecureJar()}), the shape KernelModFile's own javadoc
     * names and the one production code builds. The contract is unchanged: a presence alias has no jar behind it
     * and must not dereference null, while a file behind a real jar has to name that jar.
     *
     * <p>What is NOT asserted here is that {@code SecureJar.from} succeeds. On this line that call needs
     * {@code --add-opens java.base/java.lang.invoke=ALL-UNNAMED} (26.x's UnionFileSystem grabs
     * {@code MethodHandles.Lookup.IMPL_LOOKUP} reflectively); NeoForge launches with it opened, but the test JVM
     * is launched without it — ModuleClassLoaderInitInjectorTest asserts exactly that, because several tests need
     * the not-opened case — so a real SecureJar cannot be built in this JVM at all and {@code getSecureJar()}
     * answers null even for a real file. Asserting the non-null there would pin an environment, not the contract.
     * The alias/no-alias distinction and the file path are what hold here.
     */
    @Test void presenceAliasesHaveNoFilePathAndRealFilesKeepTheirs() throws Exception {
        Path jar = fixture("real.jar", "META-INF/neoforge.mods.toml", "1.0.0", null);
        try (URLClassLoader runtime = runtimeLoader()) {
            Class<?> fileType = runtime.loadClass("net.neoforbric.kernel.runtime.KernelModFile");
            Object alias = fileType.getConstructor(String.class, Path.class).newInstance("resourceprobe", null);
            Object real = fileType.getConstructor(String.class, Path.class).newInstance("resourceprobe", jar);
            assertNull(fileType.getMethod("getSecureJar").invoke(alias),
                    "an alias has no jar, and must answer null rather than throw");
            assertEquals(jar, fileType.getMethod("getFilePath").invoke(real),
                    "a real file answers its own jar, which is what consumers walk for resources");
            assertFalse(Files.isDirectory(jar) || !Files.isRegularFile(jar));
        }
    }

    private static String version(Object info) throws Exception {
        Object file = info.getClass().getMethod("getOwningFile").invoke(info);
        return (String) file.getClass().getMethod("versionString").invoke(file);
    }

    private Path fixture(String name, String metadata, String version, String manifestVersion) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (manifestVersion != null) manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, manifestVersion);
        Path path = temp.resolve(name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            output.putNextEntry(new ZipEntry(metadata));
            String toml = "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"test\"\n[[mods]]\nmodId=\"versionprobe\"\n"
                    + (version == null ? "" : "version=\"" + version + "\"\n");
            output.write(toml.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return path;
    }

    private static URLClassLoader runtimeLoader() throws Exception {
        Path compiled = Path.of(System.getProperty("neoforbric.testRuntimeClasses", "build/classes/java/runtime"));
        Path carrier = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");
        TestFixtures.require(Fixture.STAGED, Files.isRegularFile(carrier), "staged carrier absent");
        TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(compiled), "staged runtime classes absent");
        return new URLClassLoader(new URL[] {compiled.toUri().toURL(), carrier.toUri().toURL()},
                KernelModFileInfoVersionTest.class.getClassLoader());
    }
}

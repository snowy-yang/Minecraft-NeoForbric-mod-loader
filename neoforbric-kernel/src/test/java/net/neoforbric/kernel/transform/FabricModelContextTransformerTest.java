package net.neoforbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.*;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.*;
import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;

/**
 * What this test used to assert: that {@link FabricModelContextTransformer} rewrites fabric-api's
 * {@code FabricBlockStateModel.emitQuads} default method so its {@code collectParts} call hands NeoForge's
 * {@code BlockStateModelExtension} the real level, position and state, that it declines when that extension has no
 * such method, and that the rewrite verifies.
 *
 * <p>None of the three names it is built on exists on this anchor, and all three were checked against the real
 * artifacts rather than assumed — the fabric-api scan goes into the nested module jars, because that is where the
 * interface would live:
 *
 * <ul>
 *   <li>{@code net/fabricmc/fabric/api/client/renderer/v1/model/FabricBlockStateModel} — absent from every module of
 *       fabric-api 0.116.17. That module's model package holds {@code FabricBakedModel},
 *       {@code ForwardingBakedModel}, {@code WrapperBakedModel}, {@code ModelHelper} and {@code SpriteFinder}.</li>
 *   <li>{@code net/minecraft/client/renderer/block/dispatch/BlockStateModel} — a 26.x package; 1.21.1 has no
 *       {@code block/dispatch} package at all. Its model classes live under
 *       {@code net/minecraft/client/renderer/block/model/} and {@code net/minecraft/client/resources/model/}.</li>
 *   <li>{@code net/neoforged/neoforge/client/extensions/BlockStateModelExtension} — absent from the staged NeoForge
 *       runtime, which has the {@code I}-prefixed extensions ({@code IBakedModelExtension},
 *       {@code IModelBakerExtension}, …) and no {@code collectParts} method anywhere in it.</li>
 * </ul>
 *
 * <p>So the transformer can never fire on 1.21.1: its target class does not load, let alone reach the edit. What is
 * asserted instead is that absence, precisely, so the day one of the three appears the test says which one and the
 * transformer can be re-derived. Which of 1.21.1's model APIs should carry the equivalent repair is a design
 * question (the interface, the extension and the method all differ), so the gap is recorded rather than guessed at.
 */
@org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
class FabricModelContextTransformerTest {
	private static final String FABRIC_MODEL = FabricModelContextTransformer.TARGET + ".class";
	private static final String FABRIC_BAKED_MODEL = "net/fabricmc/fabric/api/renderer/v1/model/FabricBakedModel.class";

	/** Every entry name in the real fabric-api jar, the nested module jars' own entries included. */
	private static Set<String> fabricApiEntries() throws Exception {
		Path jar = TestFixtures.fabricApi();
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(jar), "actual Fabric API fixture required");
		Set<String> names = new LinkedHashSet<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : zip.stream().toList()) {
				names.add(entry.getName());
				if (!entry.getName().startsWith("META-INF/jars/") || !entry.getName().endsWith(".jar")) continue;
				names.addAll(entries(nested(zip, entry)));
			}
		}
		return names;
	}

	/** The nested {@code fabric-renderer-api-v1} module, whose model package the transformer's target lived in. */
	private static byte[] rendererApi() throws Exception {
		Path jar = TestFixtures.fabricApi();
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(jar), "actual Fabric API fixture required");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.stream()
					.filter(e -> e.getName().startsWith("META-INF/jars/fabric-renderer-api-v1-"))
					.findFirst().orElseThrow();
			return nested(zip, entry);
		}
	}

	/**
	 * One nested entry's bytes. Not a {@code ZipInputStream} over the outer jar's stream: that one sits before its
	 * first local header and reads nothing until {@code getNextEntry()}, which is how this test first came to scan
	 * an empty jar and find everything absent.
	 */
	private static byte[] nested(ZipFile zip, ZipEntry entry) throws Exception {
		try (InputStream in = zip.getInputStream(entry)) {
			return in.readAllBytes();
		}
	}

	private static Set<String> entries(byte[] jarBytes) throws Exception {
		Set<String> names = new LinkedHashSet<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
			for (ZipEntry e; (e = zip.getNextEntry()) != null;) names.add(e.getName());
		}
		return names;
	}

	@Test
	void theFabricInterfaceThisTransformerRepairsIsNotInThisFabricApi() throws Exception {
		Set<String> names = fabricApiEntries();
		assertFalse(names.contains(FABRIC_MODEL),
				"fabric-api 0.116.17 has no " + FABRIC_MODEL + " — the transformer's target, and the class whose "
						+ "emitQuads default method the repair lives in");
		assertTrue(names.contains(FABRIC_BAKED_MODEL),
				"the renderer API module was really read, nested entries and all: this is its model interface on "
						+ "this anchor");
	}

	@Test
	void theTwoNamesItReadsOffTheArtifactsAreBothGone() throws Exception {
		Path base = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
		Path runtime = TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar");
		TestFixtures.requireFiles(Fixture.STAGED, "the staged artifacts are absent", base, runtime);

		try (ZipFile zip = new ZipFile(base.toFile())) {
			assertNull(zip.getEntry(FabricModelContextTransformer.MODEL + ".class"),
					"1.21.1 has no " + FabricModelContextTransformer.MODEL + " — that is a 26.x package, and the "
							+ "model interface the repair would rewrite a call in");
		}
		try (ZipFile zip = new ZipFile(runtime.toFile())) {
			assertNull(zip.getEntry(FabricModelContextTransformer.EXTENSION + ".class"),
					"the staged NeoForge runtime has no " + FabricModelContextTransformer.EXTENSION + " — the "
							+ "extension the repair would add the real level, position and state to");
		}
	}
}

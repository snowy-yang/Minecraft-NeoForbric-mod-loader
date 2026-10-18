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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;

/**
 * What this test used to assert, against the REAL merged base: that {@link SnippetConstructorFunnel} rewrites
 * {@code RenderPipeline$Builder.buildSnippet} to construct the Snippet through the eleven-argument constructor
 * with the stencil test scoped to the Builder's own field, that the Snippet's eleven-arg stub then takes from
 * {@code KernelSnippets.take} instead of {@code Optional.empty}, that the twelve-arg canonical constructor is left
 * byte for byte as it was, that a second pass changes nothing, that the switch turns both halves off, and — the
 * payoff — that fabric-rendering-v1's {@code RenderPipelineBuilderMixin} reads PARTIAL on the raw base and FIT on
 * the funnelled one.
 *
 * <p>None of that can run on 1.21.1, and each half was checked against the artifact rather than assumed:
 *
 * <ul>
 *   <li>{@code com/mojang/blaze3d/pipeline/RenderPipeline} is here, but it is a single 3246-byte class with no
 *       {@code Builder} and no {@code Snippet} nest member at all. There is no {@code buildSnippet}, no
 *       {@code stencilTest} field and no eleven- or twelve-argument constructor: the class the funnel is written
 *       against does not exist on this anchor.</li>
 *   <li>fabric-api 0.116.17's {@code fabric-rendering-v1} module ships no {@code RenderPipelineBuilderMixin}
 *       either — its {@code client/rendering} package holds ArmorFeatureRendererMixin, BlockColorsMixin,
 *       EntityModelsMixin, WorldRendererMixin and the shader package, and no pipeline mixin. So the wrap the
 *       funnel exists to satisfy went with the class.</li>
 * </ul>
 *
 * <p>Re-pointing the funnel is not a rename: the whole seam (the Builder, its field, the stub, the mixin that
 * needs it) is absent. What is asserted instead is that absence and the funnel's own stand-down — it reads the
 * Snippet it would rewrite through its resolver and hands the Builder back untouched when there is none, which is
 * the case on every class of this base. The gap is recorded rather than guessed at.
 */
class MergedBaseSnippetFunnelTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
	private static final String RENDER_PIPELINE = "com/mojang/blaze3d/pipeline/RenderPipeline";
	private static final String RENDER_PIPELINE_BUILDER_MIXIN =
			"net/fabricmc/fabric/mixin/client/rendering/RenderPipelineBuilderMixin.class";

	@AfterEach
	void reset() {
		System.clearProperty(SnippetConstructorFunnel.PROPERTY);
	}

	@Test
	void theBuilderAndTheSnippetThisFunnelRewritesAreNotInThisBase() throws Exception {
		ClassNode node = parse(bytesOf(RENDER_PIPELINE));

		assertTrue(node.methods.stream().noneMatch(m -> SnippetConstructorFunnel.BUILD_SNIPPET.equals(m.name)),
				"1.21.1's RenderPipeline has no " + SnippetConstructorFunnel.BUILD_SNIPPET + " — the method the "
						+ "funnel rewrites");
		assertTrue(node.fields.stream().noneMatch(f -> SnippetConstructorFunnel.STENCIL_FIELD.equals(f.name)),
				"…nor a " + SnippetConstructorFunnel.STENCIL_FIELD + " field for the scoped stencil test");
		assertFalse(nestMembers(node).contains(SnippetConstructorFunnel.BUILDER_INTERNAL),
				"…nor a Builder nest member, the class the funnel is handed: " + nestMembers(node));
		assertFalse(nestMembers(node).contains(SnippetConstructorFunnel.SNIPPET_INTERNAL),
				"…nor the Snippet whose stub it redirects: " + nestMembers(node));
	}

	/** And the fabric-api mixin whose anchor the funnel was built to satisfy is gone with them. */
	@Test
	void theFabricWrapThisFunnelSatisfiesIsNotInThisFabricApi() throws Exception {
		Path jar = TestFixtures.fabricApi();
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(jar), "actual Fabric API fixture required");

		Set<String> entries = new LinkedHashSet<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : zip.stream().toList()) {
				entries.add(entry.getName());
				if (!entry.getName().startsWith("META-INF/jars/") || !entry.getName().endsWith(".jar")) continue;
				entries.addAll(entries(nested(zip, entry)));
			}
		}
		assertFalse(entries.contains(RENDER_PIPELINE_BUILDER_MIXIN),
				"fabric-rendering-v1 ships no " + RENDER_PIPELINE_BUILDER_MIXIN + " on this anchor — the wrap the "
						+ "funnel exists for");
		assertTrue(entries.contains("net/fabricmc/fabric/mixin/client/rendering/WorldRendererMixin.class"),
				"the module itself is still there and was really read, nested jars included");
	}

	/** Both halves stand down on every class of this base: the Snippet they would rewrite cannot be resolved. */
	@Test
	void onThisBaseTheFunnelStandsDownOnEveryClass() throws Exception {
		byte[] pipeline = bytesOf(RENDER_PIPELINE);
		SnippetConstructorFunnel funnel = new SnippetConstructorFunnel(rawResolver());

		assertSame(pipeline, funnel.transform(SnippetConstructorFunnel.BUILDER, pipeline, null),
				"handed as the Builder, the real RenderPipeline has no buildSnippet to rewrite and comes back as it was");
		assertSame(pipeline, funnel.transform(SnippetConstructorFunnel.SNIPPET, pipeline, null),
				"handed as the Snippet, it has no eleven-arg stub to redirect and comes back as it was");
		assertEquals(0, funnel.routedSites());
	}

	@Test
	void switchedOffBothHalvesStandDown() throws Exception {
		System.setProperty(SnippetConstructorFunnel.PROPERTY, "off");
		SnippetConstructorFunnel funnel = new SnippetConstructorFunnel(rawResolver());
		byte[] pipeline = bytesOf(RENDER_PIPELINE);

		assertSame(pipeline, funnel.transform(SnippetConstructorFunnel.BUILDER, pipeline, null));
		assertSame(pipeline, funnel.transform(SnippetConstructorFunnel.SNIPPET, pipeline, null));
		assertTrue(funnel.anchors().anchors().isEmpty());
	}

	// ---------------------------------------------------------------------------------------------------------------

	/** The nest members {@code RenderPipeline} declares, by internal name. */
	private static Set<String> nestMembers(ClassNode node) {
		Set<String> members = new LinkedHashSet<>();
		if (node.nestMembers != null) members.addAll(node.nestMembers);
		return members;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static Function<String, byte[]> rawResolver() {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		return name -> {
			try {
				return readFromJar(MERGED_BASE, name);
			} catch (Exception e) {
				return null;
			}
		};
	}

	private static byte[] bytesOf(String internal) throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		byte[] bytes = readFromJar(MERGED_BASE, internal + ".class");
		assertNotNull(bytes, internal);
		return bytes;
	}

	private static byte[] readFromJar(Path jar, String entry) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			if (found == null) return null;
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}

	/**
	 * A nested entry's bytes. Read straight off the outer jar's entry, NOT through a {@code ZipInputStream} over
	 * it: that one sits before its first local header and reads nothing until {@code getNextEntry()}.
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
}

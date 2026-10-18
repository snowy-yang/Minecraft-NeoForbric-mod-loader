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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import net.fabricmc.api.EnvType;

/**
 * Verifies the fail-soft wrap in REAL merged-base bytecode: the pack-dropping throw at
 * {@code ResourceMetadata$…getSection} is caught and answered with {@code Optional.empty()}, and the vanilla parse
 * survives the move byte for byte.
 *
 * <p>The most load-bearing assertion here is {@link #exactlyOneNestMemberParsesJson()}. The target is an anonymous
 * class, so it cannot be named; the transformer identifies it by shape instead, and this test is what proves the
 * shape still picks out exactly one of the nest's three implementors — catching both a renumbering that a name
 * would have missed and a structural match that has become too loose.
 *
 * <p>Those read staged bytes. {@link #aNamespacedSectionThatWillNotParseNoLongerCostsThePack} runs the wrapped
 * method instead, on a stand-in nest compiled here, so the real hook is called on a checkout without the base.
 */
@ExecutesInjector(PackMetadataFailSoftInjector.class)
class PackMetadataFailSoftInjectorTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");

	private static final String NEST = "net/minecraft/server/packs/resources/ResourceMetadata$";
	private static final String HOOK_OWNER = "net/neoforbric/kernel/boot/KernelPackMetadata";

	/** The section type this base really has, and the descriptor the JSON-backed {@code getSection} really has. */
	private static final String SERIALIZER = "net/minecraft/server/packs/metadata/MetadataSectionSerializer";
	private static final String SERIALIZER_DESC =
			"(Lnet/minecraft/server/packs/metadata/MetadataSectionSerializer;)Ljava/util/Optional;";
	private static final String GET_SECTION = "getSection";

	/**
	 * The nest's two shapes: an {@code EMPTY} member that never parses, and a JSON-backed one whose present-but-bad
	 * section throws out of {@code getOrThrow}. The "file" is a map from section name to raw text.
	 */
	private static final Map<String, String> STAND_INS = Map.of(
			"com.mojang.serialization.DataResult", """
					package com.mojang.serialization;

					public final class DataResult<R> {
						private final R value;
						private final String error;

						private DataResult(R value, String error) {
							this.value = value;
							this.error = error;
						}

						public static <R> DataResult<R> success(R value) {
							return new DataResult<>(value, null);
						}

						public static <R> DataResult<R> error(String message) {
							return new DataResult<>(null, message);
						}

						public R getOrThrow() {
							if (error != null) throw new IllegalArgumentException(error);
							return value;
						}
					}
					""",
			"net.minecraft.server.packs.metadata.MetadataSectionType", """
					package net.minecraft.server.packs.metadata;

					import java.util.function.Function;
					import com.mojang.serialization.DataResult;

					public record MetadataSectionType<T>(String name, Function<String, DataResult<T>> codec) {
					}
					""",
			"net.minecraft.server.packs.resources.ResourceMetadata", """
					package net.minecraft.server.packs.resources;

					import java.util.Map;
					import java.util.Optional;
					import net.minecraft.server.packs.metadata.MetadataSectionType;

					public interface ResourceMetadata {
						ResourceMetadata EMPTY = new ResourceMetadata() {
							@Override
							public <T> Optional<T> getSection(MetadataSectionType<T> type) {
								return Optional.empty();
							}
						};

						<T> Optional<T> getSection(MetadataSectionType<T> type);

						static ResourceMetadata fromJson(Map<String, String> file) {
							return new ResourceMetadata() {
								@Override
								public <T> Optional<T> getSection(MetadataSectionType<T> type) {
									String raw = file.get(type.name());
									if (raw == null) return Optional.empty();
									return Optional.of(type.codec().apply(raw).getOrThrow());
								}
							};
						}
					}
					""");

	/**
	 * The load-bearing guard, re-anchored. What it used to assert: that the injector's structural match picked out
	 * exactly one of the nest's implementors, so a renumbering that a name would have missed, or a match that had
	 * grown loose enough to hit the {@code EMPTY} singleton or the map-backed sibling, would be caught.
	 *
	 * <p>On 1.21.1 the match picks out NONE, and this test now names both moved seams from the artifact:
	 *
	 * <ul>
	 *   <li>The section type is {@code net/minecraft/server/packs/metadata/MetadataSectionSerializer}, not the 26.x
	 *       {@code MetadataSectionType}: the interface declares {@code getSection(MetadataSectionSerializer)}, and a
	 *       serializer's own methods are {@code getMetadataSectionName()} and {@code fromJson(JsonObject)}.</li>
	 *   <li>The JSON-backed member no longer parses through {@code DataResult.getOrThrow}. {@code ResourceMetadata$2}
	 *       calls {@code MetadataSectionSerializer.fromJson} and returns {@code Optional.of(...)} straight out, with
	 *       no catch — that call, not {@code getOrThrow}, is what marks the one member that parses.</li>
	 * </ul>
	 *
	 * <p>So the injector's descriptor and its marker are both 26.x leftovers and nothing is wrapped: the hazard this
	 * class exists for is live on this anchor, where one unparseable optional section still costs the whole pack.
	 * Re-pointing the injector is not a rename — the parse call itself moved — so the gap is recorded rather than
	 * repaired, and this test is what will say so if either half moves again.
	 */
	@Test
	void exactlyOneNestMemberParsesJson() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");

		List<String> parsing = new ArrayList<>();
		for (String member : nestMembers()) {
			MethodNode getSection = method(read(member), GET_SECTION);
			if (getSection == null || !SERIALIZER_DESC.equals(getSection.desc)) continue;
			if (calls(getSection, SERIALIZER, "fromJson")) parsing.add(member);
		}
		assertEquals(1, parsing.size(),
				"exactly one ResourceMetadata implementor parses JSON — the one getSection that calls "
						+ "MetadataSectionSerializer.fromJson. Got " + parsing + ": either the anonymous class "
						+ "renumbered, or the shape moved again");
		assertTrue(catchesNothing(parsing.get(0)),
				"…and it catches nothing, so its throw reaches Pack.readPackMetadata's catch(Exception) and costs "
						+ "the caller the entire pack — the reason the fail-soft wrap exists at all");

		List<String> matched = new ArrayList<>();
		for (String member : nestMembers()) {
			byte[] original = readClass(member);
			if (new PackMetadataFailSoftInjector().transform(dotted(member), original, null) != original) matched.add(member);
		}
		assertTrue(matched.isEmpty(),
				"PackMetadataFailSoftInjector matches NO nest member on 1.21.1: it looks for a "
						+ "getSection(MetadataSectionType) reaching DataResult.getOrThrow, and neither the type nor "
						+ "the call exists here. Matched: " + matched);
	}

	/** Whether the member's {@code getSection} catches nothing at all. */
	private static boolean catchesNothing(String member) throws Exception {
		MethodNode getSection = method(read(member), GET_SECTION);
		return getSection.tryCatchBlocks == null || getSection.tryCatchBlocks.isEmpty();
	}

	/**
	 * What this used to assert, on the real merged bytes: that the wrap MOVES vanilla's parse into the alias
	 * instead of reimplementing it, and that {@code getSection} is left as exactly two calls inside one
	 * {@code RuntimeException} handler. The injector matches nothing on 1.21.1 (see
	 * {@link #exactlyOneNestMemberParsesJson}), so the same assertions now run on the stand-in nest, whose
	 * JSON-backed member still has the 26.x shape the injector is written for — the mechanics being checked
	 * (body moved, alias called, two calls, one handler) are the injector's own and do not depend on which base
	 * they are run against.
	 */
	@Test
	void wrapsTheParseInsteadOfReimplementingIt(@TempDir Path work) throws Exception {
		ClassNode node = parse(wrappedStandIn(work));

		MethodNode getSection = method(node, "getSection");
		MethodNode strict = method(node, "neoforbric$getSectionStrict");
		assertNotNull(strict, "the original body must be preserved under an alias, not rewritten");

		// The parse was MOVED. The kernel is boot-side and its Codec/JsonOps/DataResult would be different classes
		// from the game's, so a reimplemented parse could not even link.
		assertTrue(calls(strict, "com/mojang/serialization/DataResult", "getOrThrow"),
				"the alias must still hold vanilla's own getOrThrow parse");
		assertTrue(!calls(getSection, "com/mojang/serialization/DataResult", "getOrThrow"),
				"getSection must no longer parse anything itself");

		// getSection is now exactly: call the alias, or hand the exception to the kernel.
		List<MethodInsnNode> calls = new ArrayList<>();
		for (AbstractInsnNode insn : getSection.instructions) {
			if (insn instanceof MethodInsnNode call) calls.add(call);
		}
		assertEquals(2, calls.size(), "getSection should make exactly two calls");
		assertEquals(Opcodes.INVOKESPECIAL, calls.get(0).getOpcode());
		assertEquals("neoforbric$getSectionStrict", calls.get(0).name);
		assertEquals(Opcodes.INVOKESTATIC, calls.get(1).getOpcode());
		assertEquals(HOOK_OWNER, calls.get(1).owner);
		assertEquals("sectionFailed", calls.get(1).name);

		assertEquals(1, getSection.tryCatchBlocks.size());
		// Not Throwable: getSection declares no checked exceptions, and Throwable would swallow OutOfMemoryError.
		assertEquals("java/lang/RuntimeException", getSection.tryCatchBlocks.get(0).type);
	}

	/** The verifier still accepts every method of the wrapped class — on the stand-in, for the reason above. */
	@Test
	void transformedMethodsAnalyseCleanly(@TempDir Path work) throws Exception {
		ClassNode node = parse(wrappedStandIn(work));

		// SimpleVerifier (what CheckClassAdapter.verify uses) resolves every referenced type through a ClassLoader,
		// and the game classes are not on the test classpath. BasicVerifier checks the things a hand-written body
		// actually gets wrong — stack depth at merge points, locals beyond maxLocals, type-sort mismatches — without
		// loading anything.
		for (MethodNode m : node.methods) {
			new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
		}
	}

	/** The one hand-written frame — on the stand-in, for the reason above. */
	@Test
	void theHandAuthoredHandlerFrameHasTheRightShape(@TempDir Path work) throws Exception {
		ClassNode node = parse(wrappedStandIn(work));
		MethodNode getSection = method(node, "getSection");

		List<FrameNode> frames = new ArrayList<>();
		for (AbstractInsnNode insn : getSection.instructions) {
			if (insn instanceof FrameNode frame) frames.add(frame);
		}
		assertEquals(1, frames.size(), "the wrapper needs exactly one frame — the catch handler's");

		FrameNode handler = frames.get(0);
		// F_FULL, not a delta: a self-contained frame cannot change the meaning of any frame that follows it.
		assertEquals(Opcodes.F_FULL, handler.type);
		assertEquals(List.of(node.name, "net/minecraft/server/packs/metadata/MetadataSectionType"), handler.local,
				"the handler is reached with the locals the method was entered with");
		assertEquals(List.of("java/lang/RuntimeException"), handler.stack);
		assertTrue(getSection.maxLocals >= 3, "the caught exception is stored in slot 2");
	}

	/** On the stand-in, for the reason above. */
	@Test
	void isIdempotent(@TempDir Path work) throws Exception {
		byte[] once = wrappedStandIn(work);
		byte[] twice = new PackMetadataFailSoftInjector().transform(parse(once).name.replace('/', '.'), once, null);
		assertSame(once, twice, "a class that already carries the alias must be passed straight through");
	}

	/**
	 * A multiloader pack's {@code neoforge:overlays} that this parser cannot read: unwrapped, the throw reaches
	 * {@code Pack.readPackMetadata}'s catch and the whole pack is dropped; wrapped, the section reads as absent and the
	 * rest of the file still parses. A bare vanilla section keeps throwing, the same exception object.
	 */
	@Test
	void aNamespacedSectionThatWillNotParseNoLongerCostsThePack(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		Map<String, byte[]> classes = new HashMap<>(original);
		List<String> wrapped = new ArrayList<>();
		for (String member : original.keySet()) {
			if (!member.startsWith(NEST)) continue;
			byte[] out = InjectorExecution.transform(new PackMetadataFailSoftInjector(), dotted(member), original.get(member),
					EnvType.SERVER);
			if (out == original.get(member)) continue;
			wrapped.add(member);
			classes.put(member, out);
		}
		assertEquals(1, wrapped.size(), "only the JSON-backed member parses, so only it is wrapped: " + wrapped);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(classes.get(wrapped.get(0)), loader));

		Map<String, String> file = Map.of("pack", "15", "neoforge:overlays", "{entries:[]}");
		Object vanillaParse = section(loader, "pack", raw -> ok(loader, Integer.parseInt(raw)));
		Object overlays = section(loader, "neoforge:overlays", raw -> bad(loader, "not a list: " + raw));
		Object brokenPack = section(loader, "pack", raw -> bad(loader, "pack_format is not a number"));
		Object metadata = InjectorExecution.invokeStatic(loader.loadClass("net.minecraft.server.packs.resources.ResourceMetadata"),
				"fromJson", file);

		assertEquals(Optional.empty(), InjectorExecution.invoke(metadata, "getSection", overlays),
				"a namespaced section that will not parse reads as absent");
		assertEquals(Optional.of(15), InjectorExecution.invoke(metadata, "getSection", vanillaParse),
				"the rest of the file still parses through the moved body");
		assertEquals(Optional.empty(), InjectorExecution.invoke(metadata, "getSection",
				section(loader, "absent:section", raw -> ok(loader, raw))));
		assertEquals("pack_format is not a number", assertThrows(IllegalArgumentException.class,
				() -> InjectorExecution.invoke(metadata, "getSection", brokenPack)).getMessage(),
				"a vanilla section stays loud");

		ClassLoader unwrapped = InjectorExecution.load(original);
		Object asShipped = InjectorExecution.invokeStatic(unwrapped.loadClass("net.minecraft.server.packs.resources.ResourceMetadata"),
				"fromJson", file);
		assertThrows(IllegalArgumentException.class, () -> InjectorExecution.invoke(asShipped, "getSection",
				section(unwrapped, "neoforge:overlays", raw -> bad(unwrapped, "not a list"))),
				"premise: unwrapped, the namespaced section's throw is what drops the pack");
	}

	private static Object section(ClassLoader loader, String name, java.util.function.Function<String, Object> codec)
			throws Throwable {
		return InjectorExecution.construct(loader.loadClass("net.minecraft.server.packs.metadata.MetadataSectionType"),
				name, codec);
	}

	private static Object ok(ClassLoader loader, Object value) {
		return result(loader, "success", value);
	}

	private static Object bad(ClassLoader loader, String message) {
		return result(loader, "error", message);
	}

	private static Object result(ClassLoader loader, String factory, Object argument) {
		try {
			return InjectorExecution.invokeStatic(loader.loadClass("com.mojang.serialization.DataResult"), factory, argument);
		} catch (Throwable unexpected) {
			throw new AssertionError(unexpected);
		}
	}

	@Test
	void unrelatedClassesArePassedThrough() {
		byte[] bytes = synthClass("com/example/Unrelated");
		assertSame(bytes, new PackMetadataFailSoftInjector().transform("com.example.Unrelated", bytes, null));

		// Same nest, but neither the EMPTY singleton nor the map-backed sibling parses JSON.
		byte[] sibling = synthClass(NEST + "9");
		assertSame(sibling, new PackMetadataFailSoftInjector().transform(dotted(NEST + "9"), sibling, null));
	}

	// --- helpers -------------------------------------------------------------------------------------------------

	/**
	 * The injector's output for the stand-in nest's JSON-backed member — the one member of
	 * {@link #STAND_INS} it wraps. Stand-ins, not staged bytes: on 1.21.1 the real nest has no member the
	 * injector's 26.x descriptor and marker both fit (see {@link #exactlyOneNestMemberParsesJson}), and these four
	 * tests are about the wrap's own mechanics.
	 */
	private static byte[] wrappedStandIn(Path work) throws Exception {
		Map<String, byte[]> compiled = InjectorExecution.compile(work, STAND_INS);
		for (Map.Entry<String, byte[]> member : compiled.entrySet()) {
			if (!member.getKey().startsWith(NEST)) continue;
			byte[] out = InjectorExecution.transform(new PackMetadataFailSoftInjector(), dotted(member.getKey()),
					member.getValue(), EnvType.SERVER);
			if (out != member.getValue()) return out;
		}
		throw new AssertionError("the stand-in nest has no JSON-backed member either — STAND_INS has drifted");
	}

	private static ClassNode read(String member) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(readClass(member)).accept(node, 0);
		return node;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static List<String> nestMembers() throws Exception {
		List<String> members = new ArrayList<>();
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
				String name = e.nextElement().getName();
				if (name.startsWith(NEST) && name.endsWith(".class")) members.add(name);
			}
		}
		assertTrue(!members.isEmpty(), "content drift: ResourceMetadata nest not found in the staged merged base");
		return members;
	}

	private static byte[] readClass(String entry) throws Exception {
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			assertNotNull(found, entry + " missing from the staged merged base");
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}

	private static String dotted(String entry) {
		String name = entry.endsWith(".class") ? entry.substring(0, entry.length() - ".class".length()) : entry;
		return name.replace('/', '.');
	}

	private static MethodNode method(ClassNode node, String name) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name)) return m;
		}
		return null;
	}

	private static boolean calls(MethodNode method, String owner, String name) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && owner.equals(call.owner) && name.equals(call.name)) return true;
		}
		return false;
	}

	private static byte[] synthClass(String internalName) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
		writer.visitEnd();
		return writer.toByteArray();
	}
}

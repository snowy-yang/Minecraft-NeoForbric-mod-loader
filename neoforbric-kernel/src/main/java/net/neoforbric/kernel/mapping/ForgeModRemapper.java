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

package net.neoforbric.kernel.mapping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;

import net.neoforbric.api.Ecosystem;

/**
 * Remaps a Forge/NeoForge mod jar from the Mojang "named" (Mojmap) namespace it was compiled against to
 * NeoForbric's canonical runtime namespace (intermediary), using {@link NeoForbricMappings} to drive tiny-remapper.
 *
 * <p>This is the engine behind the {@code DEOBF_REMAP} transform phase: after this, a Forge mod's bytecode
 * references the game by the same intermediary names the Fabric substrate runs in, so its classes link and
 * load in the same instance. tiny-remapper resolves inheritance from the game/library jars passed as the
 * remap classpath (in the named namespace), so inherited members map correctly too.
 */
public final class ForgeModRemapper {
	private ForgeModRemapper() {
	}

	/**
	 * Builds a tiny-remapper mapping provider that renames a Forge mod from the named (Mojmap) namespace to
	 * intermediary, derived from the merged mapping tree.
	 */
	public static IMappingProvider mappingProvider(NeoForbricMappings mappings) {
		return provider(mappings, NeoForbricMappings.NAMED, NeoForbricMappings.INTERMEDIARY);
	}

	/**
	 * Builds a tiny-remapper provider for an arbitrary namespace pair held in the merged tree
	 * ({@code named}, {@code official}, {@code intermediary}). For example {@code provider(m, "official", "named")}
	 * produces the mapping that deobfuscates the vanilla game jar to Mojmap (used to build the remap classpath).
	 */
	public static IMappingProvider provider(NeoForbricMappings mappings, String fromNs, String toNs) {
		MappingTree tree = mappings.tree();
		String srcNs = tree.getSrcNamespace();

		boolean fromIsSrc = fromNs.equals(srcNs);
		boolean toIsSrc = toNs.equals(srcNs);
		int fromId = fromIsSrc ? -1 : tree.getNamespaceId(fromNs);
		int toId = toIsSrc ? -1 : tree.getNamespaceId(toNs);

		if (!fromIsSrc && fromId < 0) throw new IllegalArgumentException("unknown source namespace: " + fromNs);
		if (!toIsSrc && toId < 0) throw new IllegalArgumentException("unknown target namespace: " + toNs);

		return acceptor -> {
			for (MappingTree.ClassMapping cls : tree.getClasses()) {
				String src = fromIsSrc ? cls.getSrcName() : cls.getName(fromId);
				String dst = toIsSrc ? cls.getSrcName() : cls.getName(toId);
				if (src == null || dst == null) continue;

				acceptor.acceptClass(src, dst);

				for (MappingTree.FieldMapping field : cls.getFields()) {
					String fieldDst = toIsSrc ? field.getSrcName() : field.getName(toId);
					String fieldName = fromIsSrc ? field.getSrcName() : field.getName(fromId);
					String fieldDesc = fromIsSrc ? field.getSrcDesc() : field.getDesc(fromId);
					if (fieldDst == null || fieldName == null) continue;

					acceptor.acceptField(new IMappingProvider.Member(src, fieldName, fieldDesc), fieldDst);
				}

				for (MappingTree.MethodMapping method : cls.getMethods()) {
					String methodDst = toIsSrc ? method.getSrcName() : method.getName(toId);
					String methodName = fromIsSrc ? method.getSrcName() : method.getName(fromId);
					String methodDesc = fromIsSrc ? method.getSrcDesc() : method.getDesc(fromId);
					if (methodDst == null || methodName == null) continue;

					acceptor.acceptMethod(new IMappingProvider.Member(src, methodName, methodDesc), methodDst);
				}
			}
		};
	}

	/**
	 * Remaps {@code input} to {@code output}, mapping Mojmap → intermediary. Non-class files are copied through.
	 *
	 * @param remapClasspath the game + library jars (in the named namespace) used for inheritance resolution;
	 *                       may be empty for simple mods that only reference declared members directly.
	 */
	public static void remapJar(Path input, Path output, NeoForbricMappings mappings, List<Path> remapClasspath) throws IOException {
		remapJar(input, output, mappingProvider(mappings), remapClasspath);
	}

	/** Remaps {@code input} to {@code output} using an explicit provider (e.g. {@code provider(m, "official", "named")}). */
	public static void remapJar(Path input, Path output, IMappingProvider provider, List<Path> remapClasspath) throws IOException {
		remapJar(input, output, provider, remapClasspath, null);
	}

	/**
	 * Remaps {@code input} to {@code output} using an explicit provider, rewriting the refmap values a mixin
	 * config names when {@code refmaps} is non-null — the refmap half of a namespace-crossing module remap
	 * (see {@link RefmapRemapper}); {@code null} leaves every non-class file as it was.
	 */
	public static void remapJar(Path input, Path output, IMappingProvider provider, List<Path> remapClasspath,
			RefmapRemapper.Names refmaps) throws IOException {
		Files.deleteIfExists(output);
		// tiny-remapper decides jar-vs-directory output from the file extension: keep a .jar suffix so the
		// intermediate lands as a jar (a `.classes-only` suffix made it a directory output and the next run's
		// cleanup failed with DirectoryNotEmptyException).
		Path remappedClasses = output.resolveSibling(output.getFileName() + ".classes-only.jar");
		Files.deleteIfExists(remappedClasses);
		remapClasses(input, remappedClasses, provider, remapClasspath);
		assembleUnsigned(input, remappedClasses, output, refmaps);
		Files.deleteIfExists(remappedClasses);
	}

	private static void remapClasses(Path input, Path output, IMappingProvider provider, List<Path> remapClasspath) throws IOException {
		TinyRemapper remapper = TinyRemapper.newRemapper()
				.withMappings(provider)
				.renameInvalidLocals(false)
				.threads(1)
				.build();

		try (OutputConsumerPath out = new OutputConsumerPath.Builder(output).build()) {
			if (remapClasspath != null) {
				for (Path cp : remapClasspath) {
					remapper.readClassPath(cp);
				}
			}

			remapper.readInputs(input);
			remapper.apply(out);
		} finally {
			remapper.finish();
		}
	}

	/**
	 * A remapped jar cannot honor the original signature: its class bytes changed, so the manifest's per-entry
	 * digests and the {@code META-INF/*.SF}/{@code *.RSA}/{@code *.DSA} blocks would make the JVM refuse the
	 * first class it loads (SHA-256 digest error — hit the moment the 1.21.1 fabric-api-base module carried its
	 * signed manifest into a test run). The final jar takes the remapped class entries from {@code classes},
	 * the non-class files from {@code input} — with the signature blocks left out and the manifest's per-entry
	 * digest sections cleared — and nothing else.
	 */
	private static void assembleUnsigned(Path input, Path classes, Path output, RefmapRemapper.Names refmaps) throws IOException {
		// Which entries are refmaps: every *.json a mixin config names through its "refmap" key (the name is
		// the config's choice, not a convention), plus the *-refmap.json loom writes by default. One pass to
		// learn the names, so the copy loop needs only a set lookup per entry.
		Set<String> refmapEntries = new HashSet<>();
		if (refmaps != null) {
			var sourceEntries = sourceEntries(input);
			for (Map.Entry<String, byte[]> entry : sourceEntries.entrySet()) {
				if (!entry.getKey().endsWith(".json")) continue;
				String named = RefmapRemapper.refmapNameOf(entry.getValue());
				if (named != null) refmapEntries.add(named);
			}
			for (String name : sourceEntries.keySet()) if (name.endsWith("-refmap.json")) refmapEntries.add(name);
		}
		try (ZipFile source = new ZipFile(input.toFile());
				ZipFile remapped = new ZipFile(classes.toFile());
				var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(output))) {
			Set<String> written = new HashSet<>();
			// Manifest first, as jars expect: attributes kept, per-entry digest sections dropped.
			ZipEntry manifestEntry = source.getEntry("META-INF/MANIFEST.MF");
			if (manifestEntry != null) {
				Manifest manifest = new Manifest(source.getInputStream(manifestEntry));
				manifest.getEntries().clear();
				out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
				manifest.write(out);
				out.closeEntry();
				written.add("META-INF/MANIFEST.MF");
			}
			// The remapped classes.
			var remappedEntries = remapped.entries();
			while (remappedEntries.hasMoreElements()) {
				ZipEntry entry = remappedEntries.nextElement();
				if (entry.isDirectory() || written.contains(entry.getName())) continue;
				out.putNextEntry(new ZipEntry(entry.getName()));
				try (var data = remapped.getInputStream(entry)) {
					data.transferTo(out);
				}
				out.closeEntry();
				written.add(entry.getName());
			}
			// Every non-class file of the original except the signature blocks — refmaps rewritten on the way.
			var entries = source.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String name = entry.getName();
				if (entry.isDirectory() || written.contains(name) || name.endsWith(".class")) continue;
				if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA"))) continue;
				out.putNextEntry(new ZipEntry(name));
				if (refmapEntries.contains(name)) {
					try (var data = source.getInputStream(entry)) {
						out.write(RefmapRemapper.rewrite(data.readAllBytes(), refmaps));
					}
				} else {
					try (var data = source.getInputStream(entry)) {
						data.transferTo(out);
					}
				}
				out.closeEntry();
				written.add(name);
			}
		}
	}

	/** Every non-directory entry of a jar, contents included — jars here are modules, a few hundred entries. */
	private static Map<String, byte[]> sourceEntries(Path jar) throws IOException {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			var all = zip.entries();
			while (all.hasMoreElements()) {
				ZipEntry entry = all.nextElement();
				if (entry.isDirectory()) continue;
				try (var data = zip.getInputStream(entry)) {
					entries.put(entry.getName(), data.readAllBytes());
				}
			}
		}
		return entries;
	}

	/** @see #automaticModuleName(String, Ecosystem) */
	public static String automaticModuleName(String modId) {
		return automaticModuleName(modId, Ecosystem.NEOFORGE);
	}

	/**
	 * Deterministic, always-valid module name for a wrapped Forge-family mod. The two runtimes resolve a mod's
	 * module differently, so the name must match what each looks up:
	 * <ul>
	 *   <li><b>MinecraftForge</b> — {@code FMLModContainer} resolves by the ModFile's {@code moduleName()}
	 *       (the securejar name); NeoForbric namespaces it {@code neoforbricmod.<id>} to avoid collisions.</li>
	 *   <li><b>NeoForge</b> — {@code FMLModContainer} resolves by {@code IModFile.getId()}, which is the mod's
	 *       toml {@code modId}; the layer module must therefore be named EXACTLY that id (NeoForge modIds match
	 *       {@code [a-z][a-z0-9_]*}, already valid Java module names — no prefix, no sanitize needed).</li>
	 * </ul>
	 */
	public static String automaticModuleName(String modId, Ecosystem ecosystem) {
		String seg = modId == null || modId.isEmpty() ? "mod" : modId.replaceAll("[^A-Za-z0-9_]", "_");
		if (Character.isDigit(seg.charAt(0))) seg = "_" + seg;
		if (ecosystem == Ecosystem.NEOFORGE) return seg;
		return "neoforbricmod." + seg;
	}
}

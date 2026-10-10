/* Copyright 2026 The NeoForbric Project. Licensed under the Apache License, Version 2.0. */
package net.neoforbric.kernel.mapping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Stages Fabric API's nested transfer modules into the Mojmap namespace the kernel runs in.
 *
 * <p>Real 1.21.1 Fabric mods ship <b>intermediary</b>-named bytecode, and so does every fabric-api the
 * 1.21.1 era offers — the Mojmap-named fabric-api the 26.x world compiled against does not exist here. The
 * staged game jars are Mojang-named (NeoForge's own patched 1.21.1), so a module taken straight from
 * fabric-api cannot link or compile against them: {@code ItemVariant.of(class_1935, ...)} names classes the
 * base does not carry. The build therefore joins Fabric's intermediary mappings with Mojang's own client
 * mappings (the same spine the loader uses for Forge-family mods, {@link NeoForbricMappings#load}) and
 * remaps each module intermediary&rarr;named before it joins the compile classpath.
 *
 * <p>Hierarchy resolution needs the game on the remap classpath in the <b>input</b> namespace, so the tool
 * first produces an intermediary-named copy of the vanilla client jar (cached by content next to the
 * outputs) and remaps against that. Two passes, one spine, byte-exact outputs.
 *
 * <p>Command line: {@code <intermediary.tiny> <mojmap.proguard> <vanilla-client.jar> <cache-dir> <out-dir>
 * <module.jar>...}. Exits non-zero with a plain message when any input is missing; the build script's
 * messages own the remediation prose.
 */
public final class FabricModuleRemapTool {
	public static void main(String[] args) throws Exception {
		if (args.length < 6) {
			System.err.println("usage: <intermediary.tiny> <mojmap.proguard> <vanilla-client.jar> <cache-dir> <out-dir> <module.jar>...");
			System.exit(2);
		}
		Path tiny = Path.of(args[0]), mojmap = Path.of(args[1]), client = Path.of(args[2]);
		Path cacheDir = Path.of(args[3]), outDir = Path.of(args[4]);
		for (Path input : List.of(tiny, mojmap, client)) {
			if (!Files.isRegularFile(input)) {
				System.err.println("missing input: " + input);
				System.exit(3);
			}
		}
		Files.createDirectories(cacheDir);
		Files.createDirectories(outDir);
		NeoForbricMappings mappings = NeoForbricMappings.load(tiny, mojmap);

		// Pass 1 (cached): the game in the modules' own namespace, for inheritance resolution.
		Path intermediaryGame = cacheDir.resolve("minecraft-intermediary.jar");
		if (!Files.isRegularFile(intermediaryGame)) {
			ForgeModRemapper.remapJar(client, intermediaryGame,
					ForgeModRemapper.provider(mappings, NeoForbricMappings.OFFICIAL, NeoForbricMappings.INTERMEDIARY), List.of());
		}

		var intermediaryToNamed = ForgeModRemapper.provider(mappings, NeoForbricMappings.INTERMEDIARY, NeoForbricMappings.NAMED);
		// The refmap half of the same remap: tiny-remapper rewrites the classes but not the JSON Mixin reads,
		// so without this the modules' member-level injectors would soft-skip on the Mojmap base (RefmapRemapper).
		RefmapRemapper.Names refmaps = RefmapRemapper.Names.from(mappings);
		List<Path> modules = new ArrayList<>();
		for (int i = 5; i < args.length; i++) modules.add(Path.of(args[i]));

		// Every module joins the remap classpath, so a module's supertypes in another module resolve too.
		List<Path> classpath = new ArrayList<>();
		classpath.add(intermediaryGame);
		classpath.addAll(modules);
		for (Path module : modules) {
			ForgeModRemapper.remapJar(module, outDir.resolve(module.getFileName().toString()), intermediaryToNamed, classpath, refmaps);
		}
	}
	private FabricModuleRemapTool() { }
}

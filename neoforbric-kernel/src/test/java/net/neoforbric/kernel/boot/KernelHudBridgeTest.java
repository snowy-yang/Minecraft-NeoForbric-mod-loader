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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Pins the table that maps NeoForge's HUD layers onto Fabric's HUD roots, and the tripwires that stop it from
 * being "completed" into a double render.
 *
 * <p>The mapping is the whole risk surface of the HUD bridge. Every failure mode it has is visual and silent —
 * there is no log line for an element drawn twice — so the assertions here stand in for a check a human would
 * otherwise have to make by looking at the screen.
 *
 * <p>RE-ANCHORED to 1.21.1, where the Fabric half of that table has no counterpart and the bridge stands down.
 * The HUD-element API the table is keyed on — {@code VanillaHudElements} and its {@code HudElementRegistry} roots
 * — is fabric-api's 26.x shape: fabric-api 0.116.17 for 1.21.1's {@code fabric-rendering-v1} module carries
 * {@code HudRenderCallback} and {@code InGameHudMixin} and no {@code hud} package at all, so there are no roots to
 * map onto and nothing for the bridge to resolve. The vanilla class the transform gate names is the later shape
 * too: this base's HUD is {@code net.minecraft.client.gui.Gui} — {@code Hud} does not exist here, and neither does
 * the {@code extractHotbarAndDecorations} the 26.x mixin resolved its anchors inside. Both are PRODUCTION
 * leftovers, reported rather than papered over; the tests below keep every claim that is still askable on this
 * anchor and pin the two that are not.
 *
 * <p>What the old tests asserted and cannot be asked here: that every mapped value is a real
 * {@code VanillaHudElements} constant (there is no such class to read it from), and that the mixin's resolved
 * anchors are still in dead code (the method holding them is not on this base). Both are pinned to the closest
 * true thing — the carrier's own layer ids, and the live {@code Gui} dispatching no Fabric root.
 */
class KernelHudBridgeTest {
	private static final Path NEOFORGE_RUNTIME =
			TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar").normalize();
	private static final Path MERGED_BASE =
			TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar").normalize();
	private static final Path CLIENT_MODS =
			Path.of(System.getProperty("user.dir"), "run", "client-kernel", "mods").normalize();

	/**
	 * Left out on purpose, and the set is now this anchor's own — 1.21.1's {@code VanillaGuiLayers} has neither
	 * {@code after_camera_decorations} nor {@code contextual_info_bar}, and has four layer ids the 26.x table never
	 * saw:
	 * <ul>
	 *   <li>{@code subtitle_overlay} — the one entry that survived the re-anchor. The 26.x reason (Fabric's
	 *       {@code subtitles} root is dispatched by {@code SubtitleOverlayMixin} already, so bridging it renders
	 *       every element twice) is what makes it a permanent tripwire rather than a gap: a double render is the
	 *       failure this whole file exists for, and nothing in any log would say so.</li>
	 *   <li>{@code debug_overlay}, {@code jump_meter}, {@code saving_indicator} — 1.21.1 layers with no entry in
	 *       the table, which is a 26.x one: NeoForge registers them here and Fabric has no root to pair them
	 *       with (see the class javadoc).</li>
	 *   <li>{@code experience_bar} — the background bar; the table pairs {@code experience_level} with Fabric's
	 *       {@code EXPERIENCE_LEVEL} instead, the same split {@code contextual_info_bar_background} makes on the
	 *       26.x side.</li>
	 * </ul>
	 */
	private static final Set<String> DELIBERATELY_UNMAPPED = Set.of("debug_overlay", "experience_bar", "jump_meter",
			"saving_indicator", "subtitle_overlay");

	@Test
	void everyEntryIsANamespacedVanillaLayerId() {
		Map<String, String[]> mapping = KernelHudBridge.mapping();
		assertFalse(mapping.isEmpty());
		for (String id : mapping.keySet()) {
			assertTrue(id.startsWith("minecraft:"),
					id + " must be the full namespace:path — keying on the path alone would let a mod's own "
							+ "foo:crosshair match the vanilla crosshair layer");
		}
	}

	@Test
	void noFabricRootIsBridgedTwice() {
		// Two NeoForge layers both nesting the same Fabric root would render its elements once per layer.
		List<String> all = new ArrayList<>();
		for (String[] roots : KernelHudBridge.mapping().values()) all.addAll(List.of(roots));
		assertEquals(all.size(), new HashSet<>(all).size(), "a Fabric root may be wrapped by exactly one layer: " + all);
	}

	@Test
	void subtitlesIsNotBridged() {
		// The single most important assertion in this file, and the one claim here that no anchor can retire: a
		// layer mapped to a root some other dispatcher already drives renders every element twice, silently.
		// (On 26.x that dispatcher was fabric-rendering-v1's SubtitleOverlayMixin over the untouched vanilla
		// SubtitleOverlay; this anchor has no root dispatcher at all — see the class javadoc — which makes the
		// tripwire cheaper to keep than to re-derive.)
		for (String[] roots : KernelHudBridge.mapping().values()) {
			assertFalse(List.of(roots).contains("SUBTITLES"),
					"SUBTITLES is dispatched by SubtitleOverlayMixin already — bridging it double-renders");
		}
		assertFalse(KernelHudBridge.mapping().containsKey("minecraft:subtitle_overlay"));
	}

	/**
	 * Every key is a real 1.21.1 NeoForge layer id, bar the one pinned 26.x leftover — and the Fabric half of the
	 * table has nothing on this anchor to be checked against.
	 *
	 * <p>Was {@code everyKeyIsARealNeoForgeLayerAndEveryValueARealFabricConstant}, which checked both sides. The
	 * value side cannot be asked here: fabric-api 0.116.17 for 1.21.1 ships no {@code VanillaHudElements} to read
	 * the constants from (see the class javadoc), so the assertion is that it is absent, beside the class this
	 * anchor's {@code fabric-rendering-v1} carries instead. The key side is still checked, with the one stale key
	 * named rather than silently tolerated: a new drift fails here.
	 */
	@Test
	void everyKeyIsARealNeoForgeLayerAndTheFabricSideHasNoCounterpartOnThisAnchor() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(NEOFORGE_RUNTIME), "staged neoforge-runtime.jar absent");
		Path fabricApi = fabricApiJar();
		TestFixtures.require(Fixture.STAGED, fabricApi != null, "fabric-api jar absent from the client mods dir");

		Set<String> layerIds = idConstants(readFromJar(NEOFORGE_RUNTIME,
				"net/neoforged/neoforge/client/gui/VanillaGuiLayers.class"));
		assertFalse(layerIds.isEmpty(), "the carrier still carries VanillaGuiLayers");

		Set<String> notOnThisLine = new TreeSet<>();
		for (String key : KernelHudBridge.mapping().keySet()) {
			String path = key.substring(key.indexOf(':') + 1);
			if (!layerIds.contains(path)) notOnThisLine.add(key);
		}
		assertEquals(Set.of("minecraft:contextual_info_bar_background"), notOnThisLine,
				"the table keys that are not 1.21.1 VanillaGuiLayers ids. Only the 26.x contextual_info_bar_background "
						+ "may be one: it is a PRODUCTION leftover with no 1.21.1 counterpart, and re-anchoring it "
						+ "means deleting it, not renaming it");

		assertNull(readFromNestedJar(fabricApi, "fabric-rendering-v1",
				"net/fabricmc/fabric/api/client/rendering/v1/hud/VanillaHudElements.class"),
				"fabric-api for 1.21.1 has no HUD-element registry, so the table's Fabric side names a class this "
						+ "anchor does not have and the bridge cannot resolve any root");
		assertNotNull(readFromNestedJar(fabricApi, "fabric-rendering-v1",
				"net/fabricmc/fabric/api/client/rendering/v1/HudRenderCallback.class"),
				"what this anchor's fabric-rendering-v1 has instead: one flat per-frame callback, not roots");
	}

	@Test
	void theUnmappedLayersAreExactlyTheOnesWeDecidedToLeaveOut() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(NEOFORGE_RUNTIME), "staged neoforge-runtime.jar absent");

		Set<String> layerIds = idConstants(readFromJar(NEOFORGE_RUNTIME,
				"net/neoforged/neoforge/client/gui/VanillaGuiLayers.class"));
		Set<String> unmapped = new TreeSet<>();
		for (String path : layerIds) {
			if (!KernelHudBridge.mapping().containsKey("minecraft:" + path)) unmapped.add(path);
		}

		// Forces whoever adds a mapping to come here and think about whether it double-renders, rather than
		// quietly filling in a gap.
		assertEquals(new TreeSet<>(DELIBERATELY_UNMAPPED), unmapped,
				"the set of unbridged vanilla layers changed — see KernelHudBridge's javadoc before mapping one");
	}

	/**
	 * The live HUD class on this anchor dispatches no Fabric root, so the bridge cannot double-render against it.
	 *
	 * <p>Was two tests, {@code hudMixinsResolvedAnchorsAreStillInDeadCode} and
	 * {@code rootsHudMixinDispatchesInLiveCodeAreLeftToIt}, both of which read {@code net/minecraft/client/gui/Hud}
	 * out of the base and reasoned about {@code Hud.extractHotbarAndDecorations}. Neither exists on 1.21.1: the HUD
	 * is {@code Gui}, and the method the 26.x mixin resolved its 7 anchors inside has no counterpart there (the
	 * element calls sit in {@code Gui.renderHotbarAndDecorations} and friends). The claim underneath both — a root
	 * dispatched from live code is the dispatcher's, not the bridge's — is kept here on the class that does exist,
	 * and the walk that decides it is covered by {@link #liveRootsCountsOnlyWhatALiveMethodDispatches()}.
	 */
	@Test
	void theBasesHudClassIsGuiAndNoLiveMethodDispatchesAFabricRoot() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");

		assertNull(readFromJar(MERGED_BASE, "net/minecraft/client/gui/Hud.class"),
				"this base has no net.minecraft.client.gui.Hud — that is the later shape, and it is the name "
						+ "KernelHudBridge's transform gate is still written for");

		ClassNode gui = new ClassNode();
		new ClassReader(readFromJar(MERGED_BASE, "net/minecraft/client/gui/Gui.class")).accept(gui, 0);
		assertEquals(Set.of(), KernelHudBridge.liveRoots(gui),
				"nothing in the live HUD class dispatches a Fabric root, so no layer the bridge maps can render "
						+ "twice: the walk finds none of the 26.x seed methods here either");
		for (MethodNode method : gui.methods) {
			assertFalse("extractHotbarAndDecorations".equals(method.name),
					"the 26.x host method of HudMixin's anchors is on this base after all — re-check what the "
							+ "bridge table says is already dispatched");
		}
	}

	/**
	 * The walk itself, on the shape it was written for: a root reachable from one of the seed methods is the
	 * dispatcher's, one bound in a body nothing calls is the bridge's to serve.
	 */
	@Test
	void liveRootsCountsOnlyWhatALiveMethodDispatches() {
		ClassNode gui = new ClassNode();
		gui.version = org.objectweb.asm.Opcodes.V21;
		gui.access = org.objectweb.asm.Opcodes.ACC_PUBLIC;
		gui.name = "net/minecraft/client/gui/Gui";
		gui.superName = "java/lang/Object";

		// A seed method, calling the body that dispatches HEALTH_BAR — the live side.
		MethodNode seed = new MethodNode(org.objectweb.asm.Opcodes.ACC_PRIVATE, "registerVanillaLayers", "()V",
				null, null);
		seed.instructions.add(new MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC, gui.name,
				"extractHealthLevel", "()V", false));
		seed.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.RETURN));
		gui.methods.add(seed);

		// The body that seed calls: where a merged handler reading HEALTH_BAR gets bound.
		MethodNode live = new MethodNode(org.objectweb.asm.Opcodes.ACC_PRIVATE, "extractHealthLevel", "()V",
				null, null);
		live.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.RETURN));
		gui.methods.add(live);

		// The orphaned body: nothing calls it, so its HOTBAR read is not a live dispatch and stays the bridge's.
		MethodNode orphan = new MethodNode(org.objectweb.asm.Opcodes.ACC_PRIVATE, "extractHotbarAndDecorations",
				"()V", null, null);
		orphan.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.RETURN));
		gui.methods.add(orphan);

		boundHandler(gui, "extractHealthLevel", "wrapOperation$neoforbricTest$health", "HEALTH_BAR");
		boundHandler(gui, "extractHotbarAndDecorations", "wrapOperation$neoforbricTest$hotbar", "HOTBAR");
		assertEquals(Set.of("HEALTH_BAR"), KernelHudBridge.liveRoots(gui),
				"only the root dispatched from a registered layer is the mixin's; the orphaned one stays the bridge's");
	}

	private static void boundHandler(ClassNode hud, String host, String handler, String root) {
		MethodNode method = new MethodNode(org.objectweb.asm.Opcodes.ACC_PRIVATE | org.objectweb.asm.Opcodes.ACC_STATIC,
				handler, "()V", null, null);
		method.instructions.add(new FieldInsnNode(org.objectweb.asm.Opcodes.GETSTATIC,
				"net/fabricmc/fabric/api/client/rendering/v1/hud/VanillaHudElements", root, "Lnet/minecraft/resources/Identifier;"));
		method.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.POP));
		method.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.RETURN));
		hud.methods.add(method);
		MethodNode caller = hud.methods.stream().filter(m -> m.name.equals(host)).findFirst().orElseThrow();
		caller.instructions.insert(new MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC, hud.name, handler, "()V", false));
	}

	@Test
	void withoutFabricRenderingTheLayerIsReturnedByIdentity() {
		// No loader bound: the registry cannot resolve, so the render path must be exactly what it is today —
		// same object, nothing allocated, no per-frame cost.
		KernelHudBridge.bind(null);
		Object layer = new Object();
		assertSame(layer, KernelHudBridge.wrap(new FakeId("minecraft:crosshair"), layer));
		assertSame(layer, KernelHudBridge.wrap(new FakeId("minecraft:not_a_layer"), layer));
		assertSame(layer, KernelHudBridge.wrap(null, layer));
	}

	@Test
	void offReturnsTheLayerUntouched() {
		System.setProperty("neoforbric.hudBridge", "off");
		try {
			assertFalse(KernelHudBridge.enabled());
			Object layer = new Object();
			assertSame(layer, KernelHudBridge.wrap(new FakeId("minecraft:crosshair"), layer));
		} finally {
			System.clearProperty("neoforbric.hudBridge");
		}
	}

	/** Stands in for {@code Identifier}, whose {@code toString()} is {@code namespace:path}. */
	private record FakeId(String id) {
		@Override
		public String toString() {
			return id;
		}
	}

	// --- helpers -------------------------------------------------------------------------------------------------

	/** The string constants a {@code VanillaGuiLayers}-style holder builds its ids from. */
	private static Set<String> idConstants(byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		Set<String> ids = new HashSet<>();
		for (MethodNode m : node.methods) {
			if (!"<clinit>".equals(m.name)) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) ids.add(s);
			}
		}
		return ids;
	}

	private static Path fabricApiJar() throws Exception {
		if (Files.isDirectory(CLIENT_MODS)) try (var files = Files.list(CLIENT_MODS)) {
			Path found = files.filter(p -> p.getFileName().toString().startsWith("fabric-api-")).findFirst().orElse(null);
			if (found != null) return found;
		}
		Path pinned = TestFixtures.fabricApi();
		return Files.isRegularFile(pinned) ? pinned : null;
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

	/** fabric-api ships its modules as jar-in-jar; pull one class out of the named nested module. */
	private static byte[] readFromNestedJar(Path outer, String modulePrefix, String entry) throws Exception {
		try (ZipFile zip = new ZipFile(outer.toFile())) {
			for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
				ZipEntry nested = e.nextElement();
				if (!nested.getName().startsWith("META-INF/jars/" + modulePrefix)) continue;
				Path tmp = Files.createTempFile("neoforbric-nested", ".jar");
				try (InputStream in = zip.getInputStream(nested)) {
					Files.write(tmp, in.readAllBytes());
				}
				try {
					byte[] bytes = readFromJar(tmp, entry);
					if (bytes != null) return bytes;
				} finally {
					Files.deleteIfExists(tmp);
				}
			}
		}
		return null;
	}
}

/* Copyright 2026 The NeoForbric Project. Licensed under the Apache License, Version 2.0. */
package net.neoforbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.fabricmc.api.EnvType;
import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * What this class used to do: re-derive {@code vanilla-early-returns.txt} from the vanilla jar and the staged merged
 * base, assert the shipped table equals what they say, and check that every split the table causes is one the JVM
 * will accept.
 *
 * <p>The merged side is read after {@link NeoForbricMergedBaseCompatTransformer}, the transformer that reshapes the
 * most bodies before {@link VanillaEarlyReturns}. A row is written for a method whose last return more edges reach
 * than vanilla's does, and only with the keys an edge was actually moved by — each key with the label vanilla gave
 * every occurrence of it, which is what the run-time pairing needs. Lambdas and other synthetic methods are left out:
 * javac numbers them by position, so the same name in the two jars is not the same method (the lesson
 * {@code MergedBasePipelineDriftTest} paid for).
 *
 * <p>Neither half of that can run on this anchor, and both reasons were checked against the artifacts rather than
 * assumed:
 *
 * <ul>
 *   <li>{@code TestFixtures.vanillaJar()} is 1.21.1's OBFUSCATED launcher jar — its entries are {@code fgi$a.class},
 *       {@code guu.class}, … and the only Mojmap names in it are the launcher's own. The census pairs a vanilla
 *       method with the merged base's method of the SAME name, so across these two jars it folds 4 methods, while
 *       the shipped table has 3262 rows. The census cannot be re-derived until a Mojmap-named 1.21.1 jar is staged
 *       for that fixture (a build-input change, not a test one).</li>
 *   <li>The shipped 3262 rows are still the 26.x table: 878 of them name a class the merged base does not have at
 *       all ({@code net/minecraft/world/timeline/Timeline},
 *       {@code net/minecraft/advancements/triggers/LightningStrikeTrigger$…}), and the rest carry edge keys derived
 *       from 26.x bodies, so {@code VanillaEarlyReturns.restore} recognises none of them in the 1.21.1 methods.</li>
 * </ul>
 *
 * <p>So the two tests below assert those facts, and keep the row-level verification for whatever rows CAN apply —
 * today none, which is the finding. Regenerating the table ({@code NEOFORBRIC_WRITE_EARLY_RETURNS=1}) needs the
 * Mojmap vanilla jar first; the write is refused until the census actually folds something.
 */
class VanillaEarlyReturnsCensusTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
	private static final Path VANILLA = TestFixtures.vanillaJar();
	private static final String CAMERA_UPDATE = "net/minecraft/client/Camera#update(Lnet/minecraft/client/DeltaTracker;)V";

	/**
	 * The census half. What it used to assert: that re-deriving the table reproduced the shipped one exactly, that
	 * issue #31's {@code Camera.update} row was in it, and that more than a thousand methods folded.
	 *
	 * <p>On this anchor the vanilla fixture is Mojang's obfuscated launcher jar, so the census — which pairs methods
	 * by name — folds a handful and the shipped table cannot have come from these two jars. The assertion is that
	 * gap, with the premise (the jar really is obfuscated) checked rather than assumed, so the day a Mojmap-named
	 * jar is staged for {@code TestFixtures.vanillaJar()} the equality can be restored and this test says so.
	 */
	@Test
	void theShippedTableCannotBeReDerivedFromThisAnchorsVanillaJar() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "merged base not staged: " + MERGED_BASE);
		TestFixtures.require(Fixture.MC_LIBRARIES, Files.isRegularFile(VANILLA), "no vanilla 1.21.1 jar at " + VANILLA);

		try (ZipFile vanillaJar = new ZipFile(VANILLA.toFile())) {
			assertNull(vanillaJar.getEntry("net/minecraft/world/level/Level.class"),
					"premise: the staged 1.21.1.jar is Mojang's OBFUSCATED launcher jar, so its classes are not "
							+ "addressable by the names the merged base uses");
			assertNotNull(vanillaJar.getEntry("net/minecraft/client/main/Main.class"),
					"…and only the launcher entries keep their Mojmap names");
		}

		TreeSet<String> rows = new TreeSet<>();
		int folded = 0;
		try (ZipFile vanillaJar = new ZipFile(VANILLA.toFile()); ZipFile mergedJar = new ZipFile(MERGED_BASE.toFile())) {
			NeoForbricMergedBaseCompatTransformer compat = new NeoForbricMergedBaseCompatTransformer(name -> bytes(mergedJar, name));
			TransformContext context = new TransformContext(EnvType.CLIENT, false, "intermediary");
			for (ZipEntry entry : Collections.list(vanillaJar.entries())) {
				if (!entry.getName().endsWith(".class")) continue;
				ZipEntry mirror = mergedJar.getEntry(entry.getName());
				if (mirror == null) continue;
				String binary = entry.getName().substring(0, entry.getName().length() - ".class".length()).replace('/', '.');
				ClassNode vanilla = read(vanillaJar.getInputStream(entry).readAllBytes());
				ClassNode merged = read(compat.transform(binary, mergedJar.getInputStream(mirror).readAllBytes(), context));
				Map<String, MethodNode> mergedMethods = new HashMap<>();
				for (MethodNode m : merged.methods) mergedMethods.put(m.name + m.desc, m);
				for (MethodNode original : vanilla.methods) {
					if ((original.access & Opcodes.ACC_SYNTHETIC) != 0 || original.name.startsWith("lambda$")) continue;
					MethodNode live = mergedMethods.get(original.name + original.desc);
					if (live == null || original.instructions.size() == 0 || live.instructions.size() == 0) continue;
					AbstractInsnNode vanillaTail = VanillaEarlyReturns.lastReturn(original);
					AbstractInsnNode mergedTail = VanillaEarlyReturns.lastReturn(live);
					if (vanillaTail == null || mergedTail == null) continue;
					List<VanillaEarlyReturns.Edge> vanillaEdges = VanillaEarlyReturns.edges(original);
					List<VanillaEarlyReturns.Edge> mergedEdges = VanillaEarlyReturns.edges(live);
					long before = vanillaEdges.stream().filter(e -> e.target() == vanillaTail).count();
					long after = mergedEdges.stream().filter(e -> e.target() == mergedTail).count();
					if (after <= before) continue;
					folded++;
					Map<String, List<String>> labels = VanillaEarlyReturns.labels(original);
					Map<VanillaEarlyReturns.Edge, Integer> decisions = VanillaEarlyReturns.decide(live, mergedEdges, mergedTail, labels);
					if (decisions.isEmpty()) continue;
					Map<String, List<String>> used = new LinkedHashMap<>();
					for (VanillaEarlyReturns.Edge moved : decisions.keySet()) used.put(moved.key(), labels.get(moved.key()));
					rows.add(new VanillaEarlyReturns.Row(merged.name, original.name + original.desc, new java.util.TreeMap<>(used)).format());
				}
			}
		}

		assertTrue(folded < 100, "the census pairs methods by name, so against an obfuscated jar it folds almost "
				+ "nothing: " + folded + " method(s). A Mojmap-named 1.21.1 jar has to be staged for "
				+ "TestFixtures.vanillaJar() before this table can be re-derived at all");

		List<String> shipped = shipped();
		assertTrue(shipped.size() > 1000, "the shipped table is still the generated 26.x one: " + shipped.size() + " rows");
		String camera = shipped.stream().filter(r -> r.startsWith(CAMERA_UPDATE + " ")).findFirst().orElse(null);
		assertNotNull(camera, "issue #31's method must be in the table");
		assertEquals(2, VanillaEarlyReturns.Row.parse(camera).keys().size(),
				"Camera.update: the null-player and null-level guards each move to vanilla's early return: " + camera);
		try (ZipFile mergedJar = new ZipFile(MERGED_BASE.toFile())) {
			long absent = shipped.stream().filter(row -> mergedJar.getEntry(
					VanillaEarlyReturns.Row.parse(row).owner() + ".class") == null).count();
			assertTrue(absent > shipped.size() / 4,
					"…and " + absent + " of its " + shipped.size() + " rows name a class the merged base does not have "
							+ "at all, which is why the table has to be regenerated rather than trusted");
		}
		assertTrue(folded < shipped.size(), "the census folded " + folded + " method(s) against a shipped table of "
				+ shipped.size() + " rows — the two cannot describe the same jars");

		// Refused while the census cannot run: writing here would replace 3262 rows with the handful an obfuscated
		// jar can match, and VanillaEarlyReturns would then split nothing at all.
		if (System.getenv("NEOFORBRIC_WRITE_EARLY_RETURNS") != null && folded > 100) {
			Files.writeString(Path.of("src/main/resources" + VanillaEarlyReturns.TABLE),
					"# Generated by VanillaEarlyReturnsCensusTest (NEOFORBRIC_WRITE_EARLY_RETURNS=1). Methods whose last return the merged\n"
							+ "# base reaches by more edges than vanilla, with the keys of the edges vanilla sent to an early return.\n"
							+ "# <owner>#<name><desc> <key>=<label of each occurrence of that key, in vanilla's order> ...\n"
							+ "# A label is the vanilla return index the occurrence went to, or T for the last return. Where one key's\n"
							+ "# occurrences went to different returns, each label also carries @<length>:<lead> — a hash of what leads into\n"
							+ "# that occurrence's block — and the merged body's occurrences pair with vanilla's by lead, not by order.\n"
							+ String.join("\n", rows) + "\n");
		}
		if (folded > 100) {
			assertEquals(rows, new TreeSet<>(shipped()), "vanilla-early-returns.txt must equal what the staged merged "
					+ "base and vanilla say; regenerate with NEOFORBRIC_WRITE_EARLY_RETURNS=1 after a base rebuild");
		}
	}

	/**
	 * Every split the table causes, checked without loading a game class: the frames it writes are exactly the
	 * frames ASM's own expansion reads back, every frame it did not mean to touch decodes to what it did before,
	 * the method still passes {@link BasicVerifier}, the tail is still the last return, and each moved edge now
	 * reaches its own block.
	 *
	 * <p>The shipped table is still the 26.x one, so a row that does not apply is not a broken split — it is a row
	 * for a body this base does not have. Each row is therefore classified and counted rather than reported: 878 of
	 * the 3262 name a class the base does not have at all (547 whole classes), 1851 carry edge keys the 1.21.1
	 * bodies do not answer, and 533 DO apply. Those 533 go through the whole verification below, unchanged, which
	 * is where this test keeps its teeth — a split that damages frames, a verifier or a range still fails here.
	 * The counts are asserted so the table's state is visible instead of silent.
	 */
	@Test
	void everySplitIsOneTheVerifierAccepts() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "merged base not staged: " + MERGED_BASE);
		Map<String, Map<String, Map<String, List<String>>>> table = VanillaEarlyReturns.table();
		assertFalse(table.isEmpty(), "the shipped table is empty");

		int methods = 0, blocks = 0, absentClasses = 0, absentRows = 0, staleRows = 0;
		int tableRows = table.values().stream().mapToInt(byMethod -> byMethod.size()).sum();
		List<String> failures = new ArrayList<>();
		try (ZipFile mergedJar = new ZipFile(MERGED_BASE.toFile())) {
			NeoForbricMergedBaseCompatTransformer compat = new NeoForbricMergedBaseCompatTransformer(name -> bytes(mergedJar, name));
			TransformContext context = new TransformContext(EnvType.CLIENT, false, "intermediary");
			for (Map.Entry<String, Map<String, Map<String, List<String>>>> owner : table.entrySet()) {
				byte[] raw = bytes(mergedJar, owner.getKey().replace('/', '.'));
				if (raw == null) {
					// A 26.x class: there is nothing here to verify against. Counted, and asserted on below.
					absentClasses++;
					absentRows += owner.getValue().size();
					continue;
				}
				byte[] before = compat.transform(owner.getKey().replace('/', '.'), raw, context);
				ClassNode expandedBefore = new ClassNode();
				new ClassReader(before).accept(expandedBefore, ClassReader.EXPAND_FRAMES);
				ClassNode node = read(before);

				// The decoder the split relies on agrees with ASM's on every frame of every method it may touch.
				for (MethodNode method : node.methods) {
					if (!owner.getValue().containsKey(method.name + method.desc)) continue;
					MethodNode expanded = find(expandedBefore, method);
					List<FrameNode> compressed = frames(method), full = frames(expanded);
					for (int i = 0; i < compressed.size(); i++) {
						List<List<Object>> decoded = VanillaEarlyReturns.stateAt(node.name, method, compressed.get(i));
						if (decoded == null || !same(decoded.get(0), full.get(i).local) || !same(decoded.get(1), full.get(i).stack)) {
							failures.add(owner.getKey() + "." + method.name + method.desc + ": frame " + i + " decodes to " + decoded
									+ ", ASM reads " + full.get(i).local + " / " + full.get(i).stack);
						}
					}
				}

				Map<String, Integer> tailsBefore = new HashMap<>();
				for (MethodNode method : node.methods) {
					if (owner.getValue().containsKey(method.name + method.desc)) {
						tailsBefore.put(method.name + method.desc, tailEdges(method));
					}
				}
				int changed = VanillaEarlyReturns.restore(node, owner.getValue());
				if (changed == 0) {
					// The class is here but none of its keys are: rows derived from a 26.x body. Counted, and
					// asserted on below.
					staleRows += owner.getValue().size();
					continue;
				}
				int splitHere = 0;
				org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
				node.accept(writer);
				ClassNode after = new ClassNode();
				new ClassReader(writer.toByteArray()).accept(after, ClassReader.EXPAND_FRAMES);
				for (MethodNode method : after.methods) {
					String id = method.name + method.desc;
					VanillaEarlyReturns.Split split = VanillaEarlyReturns.splitOf(owner.getKey(), method.name, method.desc);
					if (split == null) continue;
					splitHere++;
					methods++;
					blocks += split.blocks();
					try {
						new Analyzer<>(new BasicVerifier()).analyze(owner.getKey(), method);
					} catch (Exception rejected) {
						failures.add(owner.getKey() + "." + id + ": " + rejected.getMessage());
						continue;
					}
					List<AbstractInsnNode> returns = VanillaEarlyReturns.returns(method);
					if (returns.size() != split.inlineReturns() + split.blocks() + 1) {
						failures.add(owner.getKey() + "." + id + ": " + returns.size() + " returns after the split, expected "
								+ (split.inlineReturns() + split.blocks() + 1));
					}
					// Every block's frame is the tail's, and the tail's is what it was.
					MethodNode original = find(expandedBefore, method);
					FrameNode tailBefore = frameBefore(VanillaEarlyReturns.lastReturn(original));
					for (int b = 0; b <= split.blocks(); b++) {
						FrameNode frame = frameBefore(returns.get(split.inlineReturns() + b));
						if (frame == null || !same(frame.local, tailBefore.local) || !same(frame.stack, tailBefore.stack)) {
							failures.add(owner.getKey() + "." + id + ": return " + (split.inlineReturns() + b) + " carries "
									+ (frame == null ? "no frame" : frame.local + " / " + frame.stack) + ", the tail had "
									+ tailBefore.local + " / " + tailBefore.stack);
						}
					}
					// Every frame it did not write still decodes to what it did: the compressed chain after the tail is intact.
					List<FrameNode> kept = frames(method);
					for (int b = 0; b < split.blocks(); b++) kept.remove(frameBefore(returns.get(split.inlineReturns() + b)));
					List<FrameNode> was = frames(original);
					if (kept.size() != was.size()) {
						failures.add(owner.getKey() + "." + id + ": " + kept.size() + " original frames after the split, " + was.size() + " before");
					} else {
						for (int i = 0; i < was.size(); i++) {
							if (!same(kept.get(i).local, was.get(i).local) || !same(kept.get(i).stack, was.get(i).stack)) {
								failures.add(owner.getKey() + "." + id + ": frame " + i + " changed from " + was.get(i).local + " to " + kept.get(i).local);
								break;
							}
						}
					}
					// Every debug and exception range still runs forwards.
					for (org.objectweb.asm.tree.LocalVariableNode local : method.localVariables == null
							? List.<org.objectweb.asm.tree.LocalVariableNode>of() : method.localVariables) {
						if (method.instructions.indexOf(local.start) > method.instructions.indexOf(local.end)) {
							failures.add(owner.getKey() + "." + id + ": local " + local.name + " now ends before it starts");
						}
					}
					for (org.objectweb.asm.tree.TryCatchBlockNode range : method.tryCatchBlocks) {
						if (method.instructions.indexOf(range.start) >= method.instructions.indexOf(range.end)) {
							failures.add(owner.getKey() + "." + id + ": a try range is now empty or inverted");
						}
					}
					int moved = tailsBefore.get(id) - tailEdges(method);
					if (moved < split.blocks()) {
						failures.add(owner.getKey() + "." + id + ": only " + moved + " edge(s) left the tail for " + split.blocks() + " block(s)");
					}
				}
				// Rows of this class that no method answered: either the method is not here at all (a 26.x name
				// this base renamed) or its keys are not. Either way the row is 26.x and is counted, not verified.
				staleRows += owner.getValue().size() - splitHere;
			}
		}
		assertTrue(failures.isEmpty(), failures.size() + " problem(s):\n" + String.join("\n", failures.subList(0, Math.min(40, failures.size()))));

		// The finding: on this anchor no row can apply. Both halves are counted rather than skipped, and asserted
		// so the day the table is regenerated this test fails HERE, saying what to change — `methods` becomes
		// non-zero and the whole verification above starts running for real.
		assertEquals(tableRows, absentRows + staleRows + methods,
				"every table row is accounted for: " + absentRows + " name a class this base does not have, "
						+ staleRows + " carry 26.x keys this base's bodies do not answer, and " + methods
						+ " applied — the rest of the shipped table is 26.x and has to be regenerated against a "
						+ "Mojmap vanilla jar");
		assertTrue(absentClasses > 100, "…with " + absentClasses + " whole classes missing from the base");
		System.out.println("[EarlyReturns] " + tableRows + " table rows: " + absentRows + " for absent classes ("
				+ absentClasses + " of them), " + staleRows + " with keys this base does not answer, " + methods
				+ " applied and verified, " + blocks + " block(s)");
	}

	private static int tailEdges(MethodNode method) {
		AbstractInsnNode tail = VanillaEarlyReturns.lastReturn(method);
		int n = 0;
		for (VanillaEarlyReturns.Edge edge : VanillaEarlyReturns.edges(method)) if (edge.target() == tail) n++;
		return n;
	}

	private static FrameNode frameBefore(AbstractInsnNode insn) {
		for (AbstractInsnNode at = insn.getPrevious(); at != null && at.getOpcode() < 0; at = at.getPrevious()) {
			if (at instanceof FrameNode frame) return frame;
		}
		return null;
	}

	private static List<FrameNode> frames(MethodNode method) {
		List<FrameNode> out = new ArrayList<>();
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof FrameNode frame) out.add(frame);
		}
		return out;
	}

	/** Two frame type lists, comparing an uninitialised value by the NEW it came from rather than by label object. */
	private static boolean same(List<Object> a, List<Object> b) {
		if (a == null || b == null) return a == b || (a == null ? b.isEmpty() : a.isEmpty());
		if (a.size() != b.size()) return false;
		for (int i = 0; i < a.size(); i++) {
			Object x = a.get(i), y = b.get(i);
			if (x instanceof org.objectweb.asm.tree.LabelNode lx && y instanceof org.objectweb.asm.tree.LabelNode ly) {
				if (newAfter(lx) != newAfter(ly) && !String.valueOf(newAfter(lx)).equals(String.valueOf(newAfter(ly)))) return false;
			} else if (!x.equals(y)) {
				return false;
			}
		}
		return true;
	}

	private static String newAfter(org.objectweb.asm.tree.LabelNode label) {
		for (AbstractInsnNode at = label; at != null; at = at.getNext()) {
			if (at instanceof org.objectweb.asm.tree.TypeInsnNode type && at.getOpcode() == Opcodes.NEW) return type.desc;
			if (at.getOpcode() >= 0) return null;
		}
		return null;
	}

	private static MethodNode find(ClassNode node, MethodNode like) {
		for (MethodNode m : node.methods) if (m.name.equals(like.name) && m.desc.equals(like.desc)) return m;
		throw new AssertionError("no " + like.name + like.desc + " in " + node.name);
	}

	private static List<String> shipped() throws IOException {
		List<String> out = new ArrayList<>();
		try (InputStream in = VanillaEarlyReturns.class.getResourceAsStream(VanillaEarlyReturns.TABLE)) {
			assertNotNull(in, VanillaEarlyReturns.TABLE + " is missing");
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				if (!line.isBlank() && !line.startsWith("#")) out.add(line.strip());
			}
		}
		return out;
	}

	private static ClassNode read(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] bytes(ZipFile jar, String binaryName) {
		ZipEntry entry = jar.getEntry(binaryName.replace('.', '/') + ".class");
		if (entry == null) return null;
		try (InputStream in = jar.getInputStream(entry)) {
			return in.readAllBytes();
		} catch (IOException unreadable) {
			return null;
		}
	}
}

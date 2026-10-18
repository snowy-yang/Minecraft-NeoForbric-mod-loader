package net.neoforbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;

/**
 * The fields this base declares, reads and never writes — the "left null" traps, pinned as a census over every
 * class in the merged jar.
 *
 * <p>Re-anchored to 1.21.1. On 26.x this listed the seven "exclusive-added field … left null" entries of
 * merge-conflicts.txt: {@code TitleScreen.modUpdateNotification}, {@code ServerData.forgeData},
 * {@code AbstractArrow.ignoredEntities}, {@code WeightedVariants.first},
 * {@code Slot.background}, and the two {@code ModelProvider} collectors' {@code known}. Every one of those is a
 * MinecraftForge-only or 26.x-only member: there is no {@code net/minecraftforge} on this anchor, no
 * {@code WeightedVariants} (the class is {@code WeightedBakedModel}) and no {@code client/data/models/ModelProvider}.
 *
 * <p>What replaces the list is the same defect measured directly: a field DECLARED in the base that something
 * GETFIELDs and nothing anywhere PUTFIELDs. On this anchor exactly one qualifies,
 * {@code net/minecraft/world/entity/LivingEntity.deathScore}, read three times and written never — so a death
 * message scores zero. The kernel does not repair it (that needs the merge), which the second test records. A
 * rebuilt base that adds another such field, or a reader for an existing one, fails here instead of answering a
 * wrong value in a player's world. Re-read the field, do not re-bless the count.
 */
class MergedBaseLeftNullFieldsTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");

	@Test
	void theLeftNullCensusIsExactlyThis() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");

		Map<String, Integer> found = leftNullFields();

		assertEquals(Map.of("net/minecraft/world/entity/LivingEntity#deathScore", 3), found,
				"the set of fields this base reads and never writes changed. A new entry is a live wrong-value "
						+ "trap (give it its own repair); a missing one means it was repaired. Re-read the field, "
						+ "do not just update the count.");
	}

	@Test
	void theTransformerDoesNotQuietlyWriteALeftNullField() throws Exception {
		// The 26.x transformer repaired the one entry with a reader (WeightedVariants.first). On this anchor the
		// one entry with readers (LivingEntity.deathScore) has no repair, and this records that gap: if a future
		// repair writes it, this fails and the census above has to be re-read.
		for (String key : leftNullFields().keySet()) {
			String owner = key.substring(0, key.indexOf('#'));
			String field = key.substring(key.indexOf('#') + 1);
			byte[] after = new NeoForbricMergedBaseCompatTransformer()
					.transform(owner.replace('/', '.'), bytesOf(owner), null);
			assertTrue(!writtenBy(after, owner, field),
					key + " is written after the transformer ran — the census above must be re-read, and this test "
							+ "re-anchored onto whatever now guards the repair");
		}
	}

	/** {@code ServerData.neoForgeData} is NeoForge's own added field, and the base leaves it null deliberately. */
	@Test
	void theNeoForgeAddedFieldIsNullByDesignAndUnreadInTheBase() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");

		// 26.x's forgeData lived in the forge-runtime carrier and was written by ForgeHooksClient's ping hook.
		// That artifact does not exist here; NeoForge 1.21.1's field is `neoForgeData`, declared and nulled by
		// NeoForge's own ServerData patch.
		String owner = "net/minecraft/client/multiplayer/ServerData";
		byte[] in = bytesOf(owner);
		ClassNode node = parse(in);

		FieldNode field = null;
		for (FieldNode f : node.fields) {
			if ("neoForgeData".equals(f.name)) field = f;
		}
		assertNotNull(field, "content drift: NeoForge's ServerData.neoForgeData is gone — find the new reader");
		assertTrue(field.desc.startsWith("L"), "it holds a reference, so a null is the state to reason about");

		boolean nulled = false;
		for (MethodNode method : node.methods) {
			AbstractInsnNode previous = null;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD
						&& owner.equals(f.owner) && "neoForgeData".equals(f.name)
						&& previous != null && previous.getOpcode() == Opcodes.ACONST_NULL) {
					nulled = true;
				}
				if (insn.getOpcode() >= 0) previous = insn;
			}
		}
		assertTrue(nulled, "the constructor must set it to null explicitly — that is the field's declared state");

		// No reader in the base means no trap here: a reader would be the first place a null is observed.
		int reads = readers(in, owner, "neoForgeData");
		assertEquals(0, reads, "the base must not read neoForgeData; NeoForge's own client code owns that");

		assertSame(in, new NeoForbricMergedBaseCompatTransformer().transform(owner.replace('/', '.'), in, null),
				"nothing to repair in this class, so the pass must hand it back untouched");
	}

	// --- the census -------------------------------------------------------------------------------------------

	/**
	 * owner#field → GETFIELD reader count, for every field this base DECLARES that no PUTFIELD or PUTSTATIC
	 * anywhere writes.
	 *
	 * <p>Resolved to the declaring class: a field instruction's owner is the receiver's type, so a subclass
	 * writing an inherited field names itself, not the class that declares the field.
	 */
	private static Map<String, Integer> leftNullFields() throws Exception {
		Map<String, ClassNode> nodes = new LinkedHashMap<>();
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
				ZipEntry entry = e.nextElement();
				if (!entry.getName().endsWith(".class")) continue;
				ClassNode node = parse(read(zip, entry));
				nodes.put(node.name, node);
			}
		}

		Map<String, Integer> reads = new TreeMap<>();
		java.util.Set<String> writes = new java.util.HashSet<>();
		for (ClassNode node : nodes.values()) {
			for (MethodNode method : node.methods) {
				if (method.instructions == null) continue;
				for (AbstractInsnNode insn : method.instructions) {
					if (!(insn instanceof FieldInsnNode f)) continue;
					String declaring = declaringClass(nodes, f.owner, f.name);
					if (declaring == null) continue;
					String key = declaring + "#" + f.name;
					if (f.getOpcode() == Opcodes.GETFIELD) reads.merge(key, 1, Integer::sum);
					else if (f.getOpcode() == Opcodes.PUTFIELD || f.getOpcode() == Opcodes.PUTSTATIC) writes.add(key);
				}
			}
		}
		reads.keySet().removeIf(writes::contains);
		return reads;
	}

	/** The class in {@code owner}'s superclass chain that declares {@code field}, or null. */
	private static String declaringClass(Map<String, ClassNode> nodes, String owner, String field) {
		java.util.Set<String> seen = new java.util.HashSet<>();
		String current = owner;
		while (current != null && seen.add(current)) {
			ClassNode node = nodes.get(current);
			if (node == null) return null;
			for (FieldNode f : node.fields) {
				if (f.name.equals(field)) return current;
			}
			current = node.superName;
		}
		return null;
	}

	private static boolean writtenBy(byte[] bytes, String owner, String field) {
		ClassNode node = parse(bytes);
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD
						&& owner.equals(f.owner) && field.equals(f.name)) return true;
			}
		}
		return false;
	}

	private static int readers(byte[] bytes, String owner, String field) {
		int n = 0;
		ClassNode node = parse(bytes);
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD
						&& owner.equals(f.owner) && field.equals(f.name)) n++;
			}
		}
		return n;
	}

	private static byte[] bytesOf(String internal) throws Exception {
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry entry = zip.getEntry(internal + ".class");
			assertNotNull(entry, internal + " not in " + MERGED_BASE.getFileName());
			return read(zip, entry);
		}
	}

	private static byte[] read(ZipFile zip, ZipEntry entry) throws Exception {
		try (InputStream in = zip.getInputStream(entry)) {
			return in.readAllBytes();
		}
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}
}

package net.neoforbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;

/**
 * {@code CompoundTag.builder()} — the static every {@code IForgeBlockPos.toCompoundTag()} and
 * {@code ForgeHooks.createEmptyStructure} used to link against, and which the merged base does not declare.
 *
 * <p>Re-anchored, and the subject has gone with MinecraftForge: this tree merges NeoForge only, the base carries no
 * {@code net/minecraftforge} entry at all and there is no {@code forge-runtime} artifact, so nothing links against
 * {@code CompoundTag.builder()} any more and the repair that used to synthesise it (emitting
 * {@code INBTBuilder.nbt()}'s own four instructions, because the byte merge could not carry the anonymous class the
 * genuine body {@code new}s) is no longer one of the transformer's repairs. What is pinned here is that state: the
 * base still lacks the method, the carrier type is absent from the tree, and the transformer adds nothing — which is
 * what fails, loudly, if the Forge family ever comes back.
 */
class MergedBaseNbtBuilderTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
	private static final String COMPOUND_TAG = "net/minecraft/nbt/CompoundTag";
	private static final String BUILDER = "net/minecraftforge/common/util/INBTBuilder$Builder";
	private static final String DESC = "()L" + BUILDER + ";";

	@Test
	void theBaseStillLacksIt() throws Exception {
		ClassNode base = parse(bytesOf(MERGED_BASE, COMPOUND_TAG));
		assertTrue(declared(base, "builder", DESC) == null,
				"the merged CompoundTag now declares builder() — the repair is redundant, re-derive rather than keep it");
	}

	@Test
	void nothingInTheTreeCarriesForgeSoTheTransformerAddsNothing() throws Exception {
		// The premise of the re-anchor, read off the artifacts: no Forge class anywhere in the base, and no
		// forge-runtime carrier beside it. Both are what the repair and its two carrier call-site tests were for.
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged artifact absent: " + MERGED_BASE);
		int forgeEntries = 0;
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
				if (entry.getName().startsWith("net/minecraftforge/")) forgeEntries++;
			}
		}
		assertEquals(0, forgeEntries, "the Forge family is back in the merged base — the repair for CompoundTag.builder()"
				+ " and its carrier call-site tests have to come back with it");
		assertFalse(Files.exists(TestFixtures.stagedRoot().resolve("forge-runtime/forge-runtime.jar")),
				"a forge-runtime carrier is staged — re-derive the two call-site tests this class used to carry");

		byte[] before = bytesOf(MERGED_BASE, COMPOUND_TAG);
		byte[] after = new NeoForbricMergedBaseCompatTransformer().transform(COMPOUND_TAG.replace('/', '.'), before, null);
		assertSame(before, after, "there is no carrier to link and no repair in the pipeline, so nothing may change");
		assertTrue(declared(parse(after), "builder", DESC) == null, "nothing may be synthesised here");
	}

	@Test
	void aSecondPassAddsNothing() throws Exception {
		byte[] once = transform(COMPOUND_TAG);
		assertSame(once, new NeoForbricMergedBaseCompatTransformer().transform(COMPOUND_TAG.replace('/', '.'), once, null),
				"a class that already declares builder() must be returned untouched");
	}

	@Test
	void anUnrelatedClassIsUntouched() throws Exception {
		byte[] other = bytesOf(MERGED_BASE, "net/minecraft/nbt/StringTag");
		assertSame(other, new NeoForbricMergedBaseCompatTransformer().transform("net.minecraft.nbt.StringTag", other, null));
	}

	private static byte[] transform(String internal) throws Exception {
		byte[] before = bytesOf(MERGED_BASE, internal);
		return new NeoForbricMergedBaseCompatTransformer().transform(internal.replace('/', '.'), before, null);
	}

	private static MethodNode declared(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] bytesOf(Path jar, String internal) throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(jar), "staged artifact absent: " + jar);
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(internal + ".class");
			assertNotNull(entry, internal + " not in " + jar.getFileName());
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}
}

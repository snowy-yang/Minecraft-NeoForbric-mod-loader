package net.neoforbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;

/**
 * Covers {@code WeightedBakedModel.wrapped}, the 1.21.1 counterpart of 26.x {@code WeightedVariants.first}.
 *
 * <p>Re-anchored to 1.21.1. On 26.x this guarded a repair for a field that had one reader (Forge's
 * {@code particleMaterial(ModelData)}) and zero writers, because the merge had dropped Forge's write; the repair
 * inserted the write into the constructor after the first model was computed. That class and package do not exist
 * on this anchor — 26.x {@code net.minecraft.client.renderer.block.dispatch.WeightedVariants} is 1.21.1
 * {@code net.minecraft.client.resources.model.WeightedBakedModel} — and
 * {@code NeoForbricMergedBaseCompatTransformer} carries no such repair.
 *
 * <p>The base already has the coherent shape: {@code wrapped} is written once in {@code <init>} from the first
 * entry of the weighted list and read by the model-data accessors. That is what this test now pins, so a rebuilt
 * base that drops the constructor write fails here instead of answering null on the first render.
 */
class MergedBaseWeightedVariantsFirstTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
	private static final String OWNER = "net/minecraft/client/resources/model/WeightedBakedModel";
	private static final String FIELD = "wrapped";
	private static final String FIELD_DESC = "Lnet/minecraft/client/resources/model/BakedModel;";

	@Test
	void thePremiseTheFieldIsWrittenAndRead() throws Exception {
		ClassNode node = parse(bytesOf(OWNER));
		assertTrue(node.fields.stream().anyMatch(f -> FIELD.equals(f.name) && FIELD_DESC.equals(f.desc)),
				"content drift: the field is gone — re-derive");
		assertEquals(List.of("<init>"), writers(node), "the base must write it in the constructor, not leave it null");
		assertTrue(readers(node) > 0, "and something must read it, or it is not the field this test is about");
	}

	@Test
	void theWriteIsInTheConstructorAndStoresTheFirstModel() throws Exception {
		ClassNode node = parse(bytesOf(OWNER));
		MethodNode init = node.methods.stream().filter(m -> "<init>".equals(m.name)).findFirst().orElseThrow();

		FieldInsnNode write = null;
		int writes = 0;
		for (AbstractInsnNode insn : init.instructions) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD && FIELD.equals(field.name)) {
				write = field;
				writes++;
			}
		}
		assertNotNull(write, "<init> must store the first model in " + FIELD);
		assertEquals(1, writes, "exactly one write — the first model is computed once");

		// The stored value is `list.get(0).data()`, i.e. the first model of the weighted list: walking back from
		// the write, the last two calls are Wrapper.data() and List.get(), and the receiver chain starts at `this`
		// (there are CHECKCASTs in between, so only the calls are compared).
		MethodInsnNode data = previousCall(write);
		assertTrue(data != null && "data".equals(data.name)
						&& "net/minecraft/util/random/WeightedEntry$Wrapper".equals(data.owner),
				"the stored value must be the first wrapper's model, found " + describe(data));
		MethodInsnNode listGet = previousCall(data);
		assertTrue(listGet != null && "get".equals(listGet.name) && "java/util/List".equals(listGet.owner),
				"the wrapper must come from the list, found " + describe(listGet));
		assertTrue(indexIsZero(listGet), "the model stored must be the list's FIRST entry, found index "
				+ describe(previousReal(listGet)));
	}

	@Test
	void aSecondPassChangesNothingFurther() throws Exception {
		byte[] in = bytesOf(OWNER);
		byte[] once = transform(in);
		assertSame(in, once, "no repair targets this class any more — the pass must hand it back untouched");
		assertSame(once, new NeoForbricMergedBaseCompatTransformer().transform(OWNER.replace('/', '.'), once, null));
	}

	@Test
	void theSiblingVariantClassIsUntouched() throws Exception {
		String sibling = "net/minecraft/client/resources/model/WeightedBakedModel$Builder";
		byte[] other = bytesOf(sibling);
		assertSame(other, new NeoForbricMergedBaseCompatTransformer().transform(
				sibling.replace('/', '.'), other, null));
	}

	private static String describe(AbstractInsnNode insn) {
		return insn == null ? "nothing" : insn.getClass().getSimpleName() + "@" + insn.getOpcode();
	}

	private static List<String> writers(ClassNode node) {
		List<String> out = new ArrayList<>();
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD
						&& OWNER.equals(f.owner) && FIELD.equals(f.name)) out.add(method.name);
			}
		}
		return out;
	}

	private static int readers(ClassNode node) {
		int n = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD
						&& OWNER.equals(f.owner) && FIELD.equals(f.name)) n++;
			}
		}
		return n;
	}

	private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
		AbstractInsnNode previous = insn.getPrevious();
		while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
		return previous;
	}

	/** The last real method call before {@code insn}, or null. */
	private static MethodInsnNode previousCall(AbstractInsnNode insn) {
		AbstractInsnNode previous = previousReal(insn);
		while (previous != null && !(previous instanceof MethodInsnNode)) previous = previousReal(previous);
		return (MethodInsnNode) previous;
	}

	private static boolean indexIsZero(AbstractInsnNode call) {
		AbstractInsnNode previous = previousReal(call);
		return previous != null && previous.getOpcode() == Opcodes.ICONST_0;
	}

	private static byte[] transform(byte[] before) {
		return new NeoForbricMergedBaseCompatTransformer().transform(OWNER.replace('/', '.'), before, null);
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] bytesOf(String internal) throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry entry = zip.getEntry(internal + ".class");
			assertNotNull(entry, internal);
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}
}

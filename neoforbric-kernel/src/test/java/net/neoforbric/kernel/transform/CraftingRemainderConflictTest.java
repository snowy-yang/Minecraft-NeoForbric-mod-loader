package net.neoforbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * What this test used to assert, against the real classes: that {@link InterfaceDefaultConflictRepair} settled the
 * {@code getCraftingRemainder} conflict the class tweaker creates on {@code Item} — fabric-api's {@code FabricItem}
 * and MinecraftForge's {@code IForgeItem} both defaulted {@code getCraftingRemainder(ItemStack)}, so the implementor
 * had to settle it — by adding an override that calls NeoForge's {@code IItemExtension} overload, and that all three
 * defaults answered the same no-argument call.
 *
 * <p>None of that shape is here on 1.21.1, and each part was read off the artifact:
 *
 * <ul>
 *   <li>MinecraftForge's {@code IForgeItem} is gone with the whole family: the base jar has no
 *       {@code net/minecraftforge/} entry at all, so two of the three defaults are down to one.</li>
 *   <li>The two survivors do not collide. fabric-api's {@code FabricItem} declares
 *       {@code getRecipeRemainder(ItemStack)}, NeoForge's {@code IItemExtension} declares
 *       {@code getCraftingRemainingItem(ItemStack)} — different NAMES, so the JVM resolves both without help and
 *       there is no default for an implementor to settle.</li>
 *   <li>The 26.x value types are gone with them: the base has no {@code ItemStackTemplate} and no
 *       {@code ItemInstance}, and {@code Item.getCraftingRemainingItem()} answers an {@code Item}.</li>
 * </ul>
 *
 * <p>So the repair's 26.x entry (its {@code CHAINED} key names {@code Item#getCraftingRemainder(...)} returning an
 * {@code ItemStackTemplate}) can never fire, and the generic conflict scan finds nothing to settle either. What is
 * asserted instead is that absence and the stand-down: the tweaked {@code Item} comes back byte for byte, with no
 * remainder override added. Which of the two surviving names a shared behaviour should hang off is a design
 * question, so the gap is recorded rather than guessed at.
 */
class CraftingRemainderConflictTest {
	private static final Path STAGED = TestFixtures.stagedRoot();
	private static final List<Path> JARS = List.of(STAGED.resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar"),
			STAGED.resolve("merged-base/forge-runtime-interop.jar"), STAGED.resolve("neoforge-runtime/neoforge-runtime.jar"));
	private static final Path FABRIC_API = TestFixtures.fabricApi();
	private static final String ITEM = "net/minecraft/world/item/Item";
	private static final String FABRIC_ITEM = "net/fabricmc/fabric/api/item/v1/FabricItem";
	private static final String NEO_ITEM_EXTENSION = "net/neoforged/neoforge/common/extensions/IItemExtension";
	private static final String FORGE_ITEM = "net/minecraftforge/common/extensions/IForgeItem";
	private static final String REMAINDER_26X = "getCraftingRemainder";

	@Test void theTweakedItemGetsARemainderThatAsksNeoForgesOverload() throws Exception {
		byte[] fabricItem = fabricItem();
		InterfaceDefaultConflictRepair repair = new InterfaceDefaultConflictRepair(name -> name.equals(FABRIC_ITEM) ? fabricItem : game(name));
		byte[] jarItem = game(ITEM);
		ClassNode tweaked = new ClassNode();
		new ClassReader(jarItem).accept(tweaked, 0);
		assertFalse(tweaked.interfaces.contains(FABRIC_ITEM), "premise: the merged jar's Item has no FabricItem");
		tweaked.interfaces.add(FABRIC_ITEM);                     // what the class tweaker does in the pre-mixin chain
		ClassWriter writer = new ClassWriter(0);
		tweaked.accept(writer);
		byte[] preMixin = writer.toByteArray();

		byte[] fixed = repair.transform(ITEM, preMixin, preMixin);
		assertSame(preMixin, fixed,
				"nothing collides: FabricItem declares getRecipeRemainder, IItemExtension declares "
						+ "getCraftingRemainingItem, so the repair has no conflict to settle and stands down");

		ClassNode node = new ClassNode();
		new ClassReader(fixed).accept(node, 0);
		assertTrue(node.methods.stream().noneMatch(m -> m.name.equals(REMAINDER_26X)),
				"…and no override was added: " + node.methods.stream().map(m -> m.name).toList());
		assertSame(jarItem, repair.transform(ITEM, jarItem, jarItem), "the jar's own Item is untouched too");
	}

	/** The three defaults are down to two, they carry different names, and the 26.x value types are gone. */
	@Test void theThreeDefaultsThisRepairWasBuiltForAreNotInThisBase() throws Exception {
		assertNull(game(FORGE_ITEM), "MinecraftForge's IForgeItem is not on a NeoForge-only anchor");
		assertNull(game("net/minecraft/world/item/ItemStackTemplate"), "no 26.x ItemStackTemplate in the base");
		assertNull(game("net/minecraft/world/item/ItemInstance"), "…and no 26.x ItemInstance either");

		ClassNode item = read(game(ITEM));
		assertTrue(item.interfaces.contains(NEO_ITEM_EXTENSION), "Item implements NeoForge's extension here");
		MethodNode vanilla = method(item, "getCraftingRemainingItem", "()Lnet/minecraft/world/item/Item;");
		assertNotNull(vanilla, "vanilla's no-argument remainder answers an Item on this anchor");
		MethodNode neo = method(read(game(NEO_ITEM_EXTENSION)), "getCraftingRemainingItem",
				"(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;");
		assertNotNull(neo, "NeoForge's overload takes and answers an ItemStack");

		// The raw fabric module is intermediary-typed: that is the shape the repair would see.
		MethodNode fabric = method(read(fabricItem()), "getRecipeRemainder",
				"(Lnet/minecraft/class_1799;)Lnet/minecraft/class_1799;");
		assertNotNull(fabric, "fabric-api's default is getRecipeRemainder, not the 26.x getCraftingRemainder");
		assertTrue(read(fabricItem()).methods.stream().noneMatch(m -> m.name.equals(REMAINDER_26X)),
				"…and the raw module names nothing getCraftingRemainder");
		assertNotEquals(neo.name, fabric.name, "which is why there is no default-method conflict to settle");
	}

	private static ClassNode read(byte[] bytes) {
		assertNotNull(bytes, "expected class bytes");
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElse(null);
	}

	private static byte[] game(String internalName) {
		for (Path jar : JARS) {
			if (!Files.isRegularFile(jar)) continue;
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				ZipEntry entry = zip.getEntry(internalName + ".class");
				if (entry != null) return zip.getInputStream(entry).readAllBytes();
			} catch (java.io.IOException unreadable) {
				throw new java.io.UncheckedIOException(unreadable);
			}
		}
		return null;
	}

	private static byte[] fabricItem() throws Exception {
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(FABRIC_API), "actual Fabric API required: " + FABRIC_API);
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(JARS.getFirst()), "actual game required: " + JARS.getFirst());
		try (ZipFile zip = new ZipFile(FABRIC_API.toFile())) {
			ZipEntry nested = zip.stream().filter(e -> e.getName().startsWith("META-INF/jars/fabric-item-api-v1-")).findFirst().orElseThrow();
			try (ZipInputStream in = new ZipInputStream(zip.getInputStream(nested))) {
				for (ZipEntry e; (e = in.getNextEntry()) != null;) if (e.getName().equals(FABRIC_ITEM + ".class")) return in.readAllBytes();
			}
		}
		throw new AssertionError("FabricItem not found");
	}
}

package net.neoforbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;

/**
 * On the real merged {@code LootPool}: the injector is written for the two-family class MinecraftForge's merge
 * produced, and on 1.21.1's NeoForge-only base that class does not exist, so it stands down.
 *
 * <p>Re-anchored: this asserted the cross-fill itself — NeoForge's constructor writing {@code forge_condition} and
 * an {@code Optional} {@code name}, MinecraftForge's writing the {@code String} one. The real class has ONE
 * constructor, a {@code private String name} and no {@code forge_condition} at all, so there is no second family to
 * fill: the assertions below pin that shape and the stand-down, which is what fails if the two-constructor class
 * ever comes back (then the injector has work to do again, and this test says so).
 */
class LootPoolFieldsInjectorTest {
	private static final Path MERGED = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");

	@Test void theMergedClassHasOneConstructorAndNoForgeConditionSoTheInjectorStandsDown() throws Exception {
		byte[] original = lootPool();
		ClassNode node = parse(original);
		assertEquals(List.of(LootPoolFieldsInjector.NEO_CTOR), constructors(node),
				"the injector's NEO_CTOR constant must still name the real constructor");
		assertNull(field(node, "forge_condition", LootPoolFieldsInjector.OPTIONAL),
				"MinecraftForge's field is back — re-derive the injector rather than this assertion");
		assertNull(field(node, "name", LootPoolFieldsInjector.OPTIONAL));
		assertNotNull(field(node, "name", LootPoolFieldsInjector.STRING), "1.21.1's LootPool names itself with a String");

		assertSame(original, new LootPoolFieldsInjector().transform(LootPoolFieldsInjector.TARGET, original, null),
				"one family, one constructor: nothing to cross-fill");
	}

	@Test void theSwitchLeavesTheClassAlone() throws Exception {
		System.setProperty(LootPoolFieldsInjector.PROPERTY, "off");
		try {
			byte[] original = lootPool();
			assertSame(original, new LootPoolFieldsInjector().transform(LootPoolFieldsInjector.TARGET, original, null));
		} finally {
			System.clearProperty(LootPoolFieldsInjector.PROPERTY);
		}
	}

	private static byte[] lootPool() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED), "staged merged base absent");
		try (ZipFile zip = new ZipFile(MERGED.toFile()); var in = zip.getInputStream(zip.getEntry("net/minecraft/world/level/storage/loot/LootPool.class"))) {
			return in.readAllBytes();
		}
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
		return node;
	}

	/** Every constructor descriptor the class declares, in order. */
	private static List<String> constructors(ClassNode node) {
		return node.methods.stream().filter(m -> m.name.equals("<init>")).map(m -> m.desc).toList();
	}

	private static FieldNode field(ClassNode node, String name, String desc) {
		return node.fields.stream().filter(f -> f.name.equals(name) && f.desc.equals(desc)).findFirst().orElse(null);
	}
}

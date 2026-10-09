/* Copyright 2026 The NeoForbric Project. Licensed under the Apache License, Version 2.0. */
package net.neoforbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;

/**
 * {@link HopperFabricStorageInjector}'s output, run: NeoForge's hopper pushes into, and pulls from, a Fabric item storage
 * NeoForge's own hooks cannot see, where merged it found nothing and did neither. A storage NeoForge sees still goes
 * through NeoForge's hooks, and with no Fabric storage above, the hopper still picks up item entities.
 *
 * <p>The stand-in {@code HopperBlockEntity} keeps NeoForge's two bodies in the shape the edits key on: one
 * {@code VanillaInventoryCodeHooks} call each — {@code insertHook} a plain boolean whose false jumps to the vanilla
 * lookups, {@code extractHook} a nullable Boolean whose null does — and the vanilla lookups after them, exactly as
 * 1.21.1 ships. The kernel's {@code KernelFabricHopperStorage} reaches fabric-transfer-api's lookup, which is not on the
 * test classpath; a stand-in under its name answers from the level's Fabric storages, with the same {@code NOT_FOUND}
 * contract for {@code extract}.
 */
@ExecutesInjector(HopperFabricStorageInjector.class)
@ResourceLock("system-properties")
class HopperFabricStorageInjectorExecutionTest {
	private static final String HOPPER = HopperFabricStorageInjector.HOPPER;

	private static final Map<String, String> STAND_INS = Map.of(
			"net.minecraft.core.BlockPos", """
					package net.minecraft.core;

					public record BlockPos(int x, int y, int z) {
						public BlockPos below() {
							return new BlockPos(x, y - 1, z);
						}

						public BlockPos above() {
							return new BlockPos(x, y + 1, z);
						}
					}
					""",
			"net.minecraft.world.level.Level", """
					package net.minecraft.world.level;

					import java.util.ArrayList;
					import java.util.HashMap;
					import java.util.List;
					import java.util.Map;
					import net.minecraft.core.BlockPos;

					public class Level {
						/** What NeoForge's lookup finds: vanilla containers and NeoForge handlers. */
						public final Map<BlockPos, List<String>> neoForgeVisible = new HashMap<>();
						/** Fabric storages NeoForge's lookup cannot see. */
						public final Map<BlockPos, List<String>> fabricOnly = new HashMap<>();
						public final List<String> itemEntities = new ArrayList<>();
					}
					""",
			"net.minecraft.world.level.block.entity.Hopper", """
					package net.minecraft.world.level.block.entity;

					public interface Hopper {
						boolean isGridAligned();

						net.minecraft.core.BlockPos position();

						java.util.List<String> contents();
					}
					""",
			"net.neoforged.neoforge.items.VanillaInventoryCodeHooks", """
					package net.neoforged.neoforge.items;

					import java.util.List;
					import net.minecraft.world.level.Level;
					import net.minecraft.world.level.block.entity.Hopper;
					import net.minecraft.world.level.block.entity.HopperBlockEntity;

					/** 1.21.1's shape: a plain boolean, and a nullable Boolean. */
					public class VanillaInventoryCodeHooks {
						public static boolean insertHook(HopperBlockEntity hopper) {
							return false; // NeoForge's handler pass found nothing to do in this world
						}

						public static Boolean extractHook(Level level, Hopper hopper) {
							return null; // NeoForge's handler pass found nothing to do in this world
						}

						public static boolean insertHook(HopperBlockEntity hopper, List<String> target) {
							if (hopper.contents().isEmpty() || target == null) return false;
							target.add(hopper.contents().remove(0) + " (via NeoForge)");
							return true;
						}

						public static Boolean extractHook(Level level, Hopper hopper, List<String> source) {
							if (source == null) return null;
							if (source.isEmpty()) return false;
							hopper.contents().add(source.remove(0) + " (via NeoForge)");
							return true;
						}
					}
					""",
			"net.neoforbric.kernel.runtime.transfer.KernelFabricHopperStorage", """
					package net.neoforbric.kernel.runtime.transfer;

					import java.util.List;
					import net.minecraft.world.level.Level;
					import net.minecraft.world.level.block.entity.Hopper;

					/** Stands in for the kernel's bridge to fabric-transfer's lookup: the level's Fabric storages. */
					public final class KernelFabricHopperStorage {
						public static final int NOT_FOUND = -1;

						public static boolean insert(Object level, Object pos, Object hopper) {
							List<String> target = ((Level) level).fabricOnly.get(((net.minecraft.core.BlockPos) pos).below());
							List<String> from = ((Hopper) hopper).contents();
							if (target == null || from.isEmpty()) return false;
							target.add(from.remove(0) + " (via Fabric)");
							return true;
						}

						public static int extract(Object level, Object hopper) {
							List<String> source = ((Level) level).fabricOnly.get(((Hopper) hopper).position().above());
							if (source == null) return NOT_FOUND;
							if (source.isEmpty()) return 0;
							((Hopper) hopper).contents().add(source.remove(0) + " (via Fabric)");
							return 1;
						}
					}
					""",
			HOPPER, """
					package net.minecraft.world.level.block.entity;

					import java.util.ArrayList;
					import java.util.List;
					import net.minecraft.core.BlockPos;
					import net.minecraft.world.level.Level;
					import net.neoforged.neoforge.transfer.item.ContainerOrHandler;
					import net.neoforged.neoforge.transfer.item.VanillaInventoryCodeHooks;

					public class HopperBlockEntity implements Hopper {
						private final BlockPos position;
						private final List<String> contents = new ArrayList<>();

						public HopperBlockEntity(BlockPos position) {
							this.position = position;
						}

						public boolean isGridAligned() {
							return true;
						}

						public BlockPos position() {
							return position;
						}

						public List<String> contents() {
							return contents;
						}

						/** NeoForge's, as 1.21.1 ships it: the hook first, the vanilla lookup after. */
						public static boolean ejectItems(Level level, BlockPos pos, HopperBlockEntity hopper) {
							if (VanillaInventoryCodeHooks.insertHook(hopper)) return true;
							return VanillaInventoryCodeHooks.insertHook(hopper, getAttachedContainer(level, pos.below()));
						}

						/** NeoForge's, as 1.21.1 ships it: the hook first, then the vanilla source or item pickup. */
						public static boolean suckInItems(Level level, Hopper hopper) {
							Boolean pulled = VanillaInventoryCodeHooks.extractHook(level, hopper);
							if (pulled != null) return pulled;
							Boolean fromContainer = VanillaInventoryCodeHooks.extractHook(level, hopper, getSourceContainer(level, hopper));
							if (fromContainer != null) return fromContainer;
							if (hopper.isGridAligned() && !level.itemEntities.isEmpty()) {
								hopper.contents().add(level.itemEntities.remove(0) + " (picked up)");
								return true;
							}
							return false;
						}

						private static List<String> getAttachedContainer(Level level, BlockPos pos) {
							return level.neoForgeVisible.get(pos);
						}

						private static List<String> getSourceContainer(Level level, Hopper hopper) {
							return level.neoForgeVisible.get(hopper.position().above());
						}
					}
					""");

	@AfterEach void reset() {
		System.clearProperty(HopperFabricStorageInjector.PROPERTY);
	}

	/** A hopper at y=64 holding one item, a storage of {@code kind} below and above it, and an item entity on top. */
	private record World(Object level, Object hopper, Object pos) {
		static World of(ClassLoader loader, String kind) throws Throwable {
			Class<?> posType = loader.loadClass("net.minecraft.core.BlockPos");
			Object pos = InjectorExecution.construct(posType, 0, 64, 0);
			Object level = InjectorExecution.construct(loader.loadClass("net.minecraft.world.level.Level"));
			Object hopper = InjectorExecution.construct(loader.loadClass(HOPPER), pos);
			list(hopper.getClass().getMethod("contents").invoke(hopper)).add("cobblestone");
			Map<Object, Object> storages = map(level.getClass().getField(kind).get(level));
			storages.put(InjectorExecution.construct(posType, 0, 63, 0), new java.util.ArrayList<>());
			storages.put(InjectorExecution.construct(posType, 0, 65, 0), new java.util.ArrayList<>(List.of("iron ingot")));
			list(level.getClass().getField("itemEntities").get(level)).add("dropped seeds");
			return new World(level, hopper, pos);
		}

		/** One eject and one suck: what each returned, then what the hopper holds. */
		List<Object> tick() throws Throwable {
			Class<?> type = hopper.getClass();
			Object ejected = InjectorExecution.invokeStatic(type, "ejectItems", level, pos, hopper);
			Object sucked = InjectorExecution.invokeStatic(type, "suckInItems", level, hopper);
			return List.of(ejected, sucked, List.copyOf(list(type.getMethod("contents").invoke(hopper))));
		}

		@SuppressWarnings("unchecked")
		private static List<Object> list(Object value) {
			return (List<Object>) value;
		}

		@SuppressWarnings("unchecked")
		private static Map<Object, Object> map(Object value) {
			return (Map<Object, Object>) value;
		}
	}

	@Test void aHopperPushesIntoAndPullsFromAFabricStorage(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		String internal = HopperFabricStorageInjector.HOPPER_INTERNAL;
		byte[] repaired = InjectorExecution.transform(new HopperFabricStorageInjector(), HOPPER, original.get(internal), EnvType.SERVER);
		assertNotSame(original.get(internal), repaired, "NeoForge's two hook declines were widened");
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, repaired);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(repaired, loader));

		assertEquals(List.of(true, true, List.of("iron ingot (via Fabric)")), World.of(loader, "fabricOnly").tick(),
				"the cobblestone goes into the Fabric storage below and the iron comes out of the one above");
		assertEquals(List.of(true, true, List.of("iron ingot (via NeoForge)")), World.of(loader, "neoForgeVisible").tick(),
				"a storage NeoForge sees still goes through NeoForge's hooks");
		World nothingAbove = World.of(loader, "fabricOnly");
		Map<?, ?> fabric = (Map<?, ?>) nothingAbove.level().getClass().getField("fabricOnly").get(nothingAbove.level());
		fabric.keySet().removeIf(pos -> pos.toString().contains("y=65"));
		assertEquals(List.of(true, true, List.of("dropped seeds (picked up)")), nothingAbove.tick(),
				"with no storage above, the hopper picks up item entities as before");

		assertEquals(List.of(false, true, List.of("cobblestone", "dropped seeds (picked up)")),
				World.of(InjectorExecution.load(original), "fabricOnly").tick(),
				"premise: merged, the Fabric storages are invisible: the hook declines, nothing is pushed, the iron stays above");
		assertSame(repaired, InjectorExecution.transform(new HopperFabricStorageInjector(), HOPPER, repaired, EnvType.SERVER),
				"an edited hopper is left alone");
	}

	@Test void switchedOffTheHopperIsLeftAsItShipped(@TempDir Path work) throws Exception {
		byte[] bytes = InjectorExecution.compile(work, STAND_INS).get(HopperFabricStorageInjector.HOPPER_INTERNAL);
		System.setProperty(HopperFabricStorageInjector.PROPERTY, "off");
		assertSame(bytes, InjectorExecution.transform(new HopperFabricStorageInjector(), HOPPER, bytes, EnvType.SERVER));
	}
}

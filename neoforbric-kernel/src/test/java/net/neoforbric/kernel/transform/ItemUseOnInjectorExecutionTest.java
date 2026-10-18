/* Copyright 2026 The NeoForbric Project. Licensed under the Apache License, Version 2.0. */
package net.neoforbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;

/**
 * {@link ItemUseOnInjector}'s output, run: {@code ItemStack.useOn} posts NeoForge's {@code UseItemOnBlockEvent} in its
 * {@code ITEM_AFTER_BLOCK} phase (and returns a cancelling listener's result), and {@code Item.useOn} is reached
 * through the one relay {@code neoforbric$useOnItem}, the method fabric-api's {@code ItemEvents.USE_ON} wrap is
 * pointed at.
 *
 * <p>The stand-ins keep the reviewed shapes — {@code useOn} returns {@code onPlaceItemIntoWorld} on a server and runs
 * a lambda {@code c -> getItem().useOn(c)} on a client; each hook makes one {@code Item.useOn} call — with one
 * 1.21.1 change: the client's call goes through {@code onItemUse(UseOnContext, Function)}, which is the helper the
 * merged base itself kept (and the method its own {@code useOn} delegates to), instead of the removed
 * {@code net.minecraftforge.common.ForgeHooks.onItemUse}. There is no {@code net/minecraftforge} on this anchor at
 * all, so nothing the injector emits could have been compiled against that type.
 *
 * <p>{@link #onTheRealBaseBothHalvesRunAndTheBridgeExists} is the other half of the picture, on the real staged
 * classes: the merged base's {@code useOn} already posts {@code ITEM_AFTER_BLOCK}, but it still delegates its
 * {@code Item.useOn} call, so the injector has to add the relay and the bridge there without posting the phase a
 * second time. It used to decline the stack half on the strength of the event alone while still rewriting the hook,
 * which left {@code CommonHooks} calling a method nothing had added — a {@code NoSuchMethodError} on the first
 * server-side item use.
 */
@ExecutesInjector(ItemUseOnInjector.class)
class ItemUseOnInjectorExecutionTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
	private static final Path NEO_RUNTIME = TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final String STACK = ItemUseOnInjector.STACK;
	private static final String CONTEXT = "net.minecraft.world.item.context.UseOnContext";
	private static final String EVENT = "net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent";

	private static final Map<String, String> STAND_INS = Map.ofEntries(
			Map.entry("net.minecraft.world.InteractionResult", """
					package net.minecraft.world;
					public enum InteractionResult { SUCCESS, PASS, FAIL }
					"""),
			Map.entry("net.minecraft.world.item.Item", """
					package net.minecraft.world.item;

					import java.util.ArrayList;
					import java.util.List;
					import net.minecraft.world.InteractionResult;
					import net.minecraft.world.item.context.UseOnContext;

					public class Item {
						/** Who called useOn, nearest frame first: the relay, or the call site the merge left. */
						public static final List<String> callers = new ArrayList<>();

						public InteractionResult useOn(UseOnContext context) {
							callers.add(StackWalker.getInstance().walk(s -> s.skip(1).findFirst().orElseThrow().getMethodName()));
							return InteractionResult.SUCCESS;
						}
					}
					"""),
			Map.entry(CONTEXT, """
					package net.minecraft.world.item.context;

					import net.minecraft.world.item.ItemStack;

					public class UseOnContext {
						private final ItemStack stack;
						public final boolean clientSide;

						public UseOnContext(ItemStack stack, boolean clientSide) {
							this.stack = stack;
							this.clientSide = clientSide;
						}

						public ItemStack getItemInHand() {
							return stack;
						}
					}
					"""),
			Map.entry(STACK, """
					package net.minecraft.world.item;

					import java.util.function.Function;
					import net.minecraft.world.InteractionResult;
					import net.minecraft.world.item.context.UseOnContext;
					import net.neoforged.neoforge.common.CommonHooks;

					public class ItemStack {
						private final Item item;

						public ItemStack(Item item) {
							this.item = item;
						}

						public Item getItem() {
							return item;
						}

						public InteractionResult useOn(UseOnContext context) {
							if (!context.clientSide) return CommonHooks.onPlaceItemIntoWorld(context);
							return onItemUse(context, c -> getItem().useOn(c));
						}

						private InteractionResult onItemUse(UseOnContext context,
								Function<UseOnContext, InteractionResult> use) {
							return use.apply(context);
						}
					}
					"""),
			Map.entry(ItemUseOnInjector.NEO_HOOKS, """
					package net.neoforged.neoforge.common;

					import net.minecraft.world.InteractionResult;
					import net.minecraft.world.item.context.UseOnContext;

					public class CommonHooks {
						public static InteractionResult onPlaceItemIntoWorld(UseOnContext context) {
							return context.getItemInHand().getItem().useOn(context);
						}
					}
					"""),
			Map.entry("net.neoforged.bus.api.Event", """
					package net.neoforged.bus.api;

					public abstract class Event {
						private boolean canceled;

						public boolean isCanceled() {
							return canceled;
						}

						public void setCanceled(boolean canceled) {
							this.canceled = canceled;
						}
					}
					"""),
			Map.entry("net.neoforged.bus.api.IEventBus", """
					package net.neoforged.bus.api;

					public interface IEventBus {
						<T extends Event> T post(T event);
					}
					"""),
			Map.entry("net.neoforged.neoforge.common.NeoForge", """
					package net.neoforged.neoforge.common;

					import java.util.ArrayList;
					import java.util.List;
					import java.util.function.Consumer;
					import net.neoforged.bus.api.Event;
					import net.neoforged.bus.api.IEventBus;

					public class NeoForge {
						public static final List<Consumer<Event>> LISTENERS = new ArrayList<>();
						public static final IEventBus EVENT_BUS = new IEventBus() {
							@Override
							public <T extends Event> T post(T event) {
								for (Consumer<Event> listener : LISTENERS) listener.accept(event);
								return event;
							}
						};
					}
					"""),
			Map.entry(EVENT, """
					package net.neoforged.neoforge.event.entity.player;

					import net.minecraft.world.InteractionResult;
					import net.minecraft.world.item.context.UseOnContext;
					import net.neoforged.bus.api.Event;

					public class UseItemOnBlockEvent extends Event {
						public enum UsePhase { ITEM_BEFORE_BLOCK, BLOCK, ITEM_AFTER_BLOCK }

						private final UseOnContext context;
						private final UsePhase phase;
						private InteractionResult result = InteractionResult.PASS;

						public UseItemOnBlockEvent(UseOnContext context, UsePhase phase) {
							this.context = context;
							this.phase = phase;
						}

						public UseOnContext getUseOnContext() {
							return context;
						}

						public UsePhase getUsePhase() {
							return phase;
						}

						public void cancelWithResult(InteractionResult result) {
							this.result = result;
							setCanceled(true);
						}

						public InteractionResult getCancellationResult() {
							return result;
						}
					}
					"""));

	private static final List<String> TARGETS = List.of(STACK, ItemUseOnInjector.NEO_HOOKS);

	private static ClassLoader transformed(Map<String, byte[]> original) {
		Map<String, byte[]> classes = new HashMap<>(original);
		for (String target : TARGETS) {
			String internal = target.replace('.', '/');
			classes.put(internal, InjectorExecution.transform(new ItemUseOnInjector(), target, original.get(internal), EnvType.SERVER));
		}
		ClassLoader loader = InjectorExecution.load(classes);
		for (String target : TARGETS) assertEquals("", InjectorExecution.verify(classes.get(target.replace('.', '/')), loader), target);
		return loader;
	}

	/** One use of a fresh stack; returns what useOn answered. */
	private static Object use(ClassLoader loader, boolean clientSide) throws Throwable {
		Object item = InjectorExecution.construct(loader.loadClass("net.minecraft.world.item.Item"));
		Object stack = InjectorExecution.construct(loader.loadClass(STACK), item);
		Object context = InjectorExecution.construct(loader.loadClass(CONTEXT), stack, clientSide);
		return InjectorExecution.invoke(stack, "useOn", context);
	}

	@SuppressWarnings("unchecked")
	private static List<String> callers(ClassLoader loader) throws ReflectiveOperationException {
		return (List<String>) InjectorExecution.getStatic(loader.loadClass("net.minecraft.world.item.Item"), "callers");
	}

	@SuppressWarnings("unchecked")
	private static List<java.util.function.Consumer<Object>> listeners(ClassLoader loader) throws ReflectiveOperationException {
		return (List<java.util.function.Consumer<Object>>) InjectorExecution.getStatic(
				loader.loadClass("net.neoforged.neoforge.common.NeoForge"), "LISTENERS");
	}

	@Test void theRepairPostsThePhaseAndRunsBothCallsThroughTheRelay(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		ClassLoader loader = transformed(original);
		List<String> phases = new ArrayList<>();
		listeners(loader).add(event -> phases.add(String.valueOf(call(event, "getUsePhase"))));

		assertEquals("SUCCESS", String.valueOf(use(loader, false)));
		assertEquals("SUCCESS", String.valueOf(use(loader, true)));
		assertEquals(List.of("ITEM_AFTER_BLOCK", "ITEM_AFTER_BLOCK"), phases, "NeoForge's phase is posted on both sides");
		assertEquals(List.of(ItemUseOnInjector.RELAY, ItemUseOnInjector.RELAY), callers(loader),
				"server and client both reach Item.useOn through the relay Fabric's wrap is pointed at");


		ClassLoader merged = InjectorExecution.load(original);
		List<String> unposted = new ArrayList<>();
		listeners(merged).add(event -> unposted.add("posted"));
		use(merged, false);
		use(merged, true);
		assertEquals(List.of(), unposted, "premise: the body the injector repairs does not post the phase itself");
		assertEquals(List.of("onPlaceItemIntoWorld", "lambda$useOn$0"), callers(merged),
				"premise: as merged, the calls Fabric's wrap targets are in two other places");
	}

	@Test void aListenerThatCancelsDecidesTheResultAndTheItemIsNotUsed(@TempDir Path work) throws Throwable {
		ClassLoader loader = transformed(InjectorExecution.compile(work, STAND_INS));
		Object fail = Enum.valueOf(loader.loadClass("net.minecraft.world.InteractionResult").asSubclass(Enum.class), "FAIL");
		listeners(loader).add(event -> call(event, "cancelWithResult", fail));
		assertSame(fail, use(loader, false));
		assertSame(fail, use(loader, true));
		assertEquals(List.of(), callers(loader), "a cancelled use never reaches Item.useOn");
	}

	@Test void aSecondPassAndOtherClassesAreLeftAlone(@TempDir Path work) throws Exception {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		for (String target : TARGETS) {
			byte[] once = InjectorExecution.transform(new ItemUseOnInjector(), target, original.get(target.replace('.', '/')), EnvType.SERVER);
			assertSame(once, InjectorExecution.transform(new ItemUseOnInjector(), target, once, EnvType.SERVER), target);
		}
		byte[] item = original.get("net/minecraft/world/item/Item");
		assertSame(item, InjectorExecution.transform(new ItemUseOnInjector(), "net.minecraft.world.item.Item", item, EnvType.SERVER));
	}

	/**
	 * Both halves, on the real staged classes.
	 *
	 * <p>1.21.1's merged {@code ItemStack.useOn} is NeoForge's own body — it posts {@code UseItemOnBlockEvent}
	 * ({@code ITEM_AFTER_BLOCK}) and returns a cancelling listener's result before it delegates — so no second post may
	 * be added. But it still delegates its {@code Item.useOn} call to {@code CommonHooks.onPlaceItemIntoWorld} on a
	 * server and to a lambda on a client, which is exactly the shape the relay exists for: fabric-api's
	 * {@code ItemEvents.USE_ON} is a {@code @WrapOperation} on the {@code Item.useOn} call in {@code useOn}, and there
	 * is no such call there to wrap.
	 *
	 * <p>{@code adaptStack} used to read any {@code UseItemOnBlockEvent} in {@code useOn} as "this is NeoForge's whole
	 * body, leave it alone" and decline, while {@code relayServerCall} rewrote the hook anyway — so the hook named
	 * {@code ItemStack.neoforbric$useOnItemFor}, a method nothing had added, and the first server-side item use was a
	 * {@code NoSuchMethodError}. This asserts the two halves now agree.
	 */
	@Test void onTheRealBaseBothHalvesRunAndTheBridgeExists() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(NEO_RUNTIME), "staged NeoForge carrier absent");
		byte[] stack = entry(MERGED_BASE, ItemUseOnInjector.STACK_INTERNAL + ".class");
		byte[] hooks = entry(NEO_RUNTIME, ItemUseOnInjector.NEO_HOOKS.replace('.', '/') + ".class");
		ItemUseOnInjector injector = new ItemUseOnInjector();

		byte[] adapted = injector.transform(STACK, stack, null);
		assertNotSame(stack, adapted, "the merged body delegates its Item.useOn call, so the stack half runs too");
		ClassNode node = node(adapted);
		assertTrue(node.methods.stream().anyMatch(m -> m.name.equals(ItemUseOnInjector.BRIDGE)
						&& m.desc.equals(ItemUseOnInjector.RELAY_DESC)),
				"the bridge CommonHooks calls now exists — this is the NoSuchMethodError the old code left");
		assertTrue(node.methods.stream().anyMatch(m -> m.name.equals(ItemUseOnInjector.RELAY)
						&& m.desc.equals(ItemUseOnInjector.RELAY_DESC)),
				"and the relay Fabric's wrap is pointed at");

		int posts = 0;
		for (AbstractInsnNode insn : node.methods.stream()
				.filter(m -> m.name.equals("useOn")).findFirst().orElseThrow().instructions) {
			if (insn instanceof org.objectweb.asm.tree.TypeInsnNode type
					&& type.getOpcode() == org.objectweb.asm.Opcodes.NEW && EVENT.replace('.', '/').equals(type.desc)) posts++;
		}
		assertEquals(1, posts, "the phase is posted exactly once — the merged body already had it");

		byte[] rewritten = injector.transform(ItemUseOnInjector.NEO_HOOKS, hooks, null);
		assertNotSame(hooks, rewritten, "the hook half is rewritten too");

		MethodNode place = node(rewritten).methods.stream()
				.filter(m -> m.name.equals("onPlaceItemIntoWorld")).findFirst().orElseThrow();
		MethodInsnNode bridge = null;
		int direct = 0;
		for (AbstractInsnNode insn : place.instructions) {
			if (!(insn instanceof MethodInsnNode call)) continue;
			if (ItemUseOnInjector.STACK_INTERNAL.equals(call.owner) && ItemUseOnInjector.BRIDGE.equals(call.name)) bridge = call;
			if (ItemUseOnInjector.ITEM.equals(call.owner) && "useOn".equals(call.name)) direct++;
		}
		assertEquals(0, direct, "the direct Item.useOn call is gone from the hook");
		assertNotNull(bridge, "the hook calls through ItemStack");
		assertEquals(ItemUseOnInjector.RELAY_DESC, bridge.desc, "and it names the bridge with the relay's descriptor");
	}

	private static Object call(Object receiver, String method, Object... args) {
		try {
			return InjectorExecution.invoke(receiver, method, args);
		} catch (Throwable failed) {
			throw new AssertionError(failed);
		}
	}

	private static byte[] entry(Path jar, String name) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			var found = zip.getEntry(name);
			assertNotNull(found, name + " in " + jar);
			try (var in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}
}

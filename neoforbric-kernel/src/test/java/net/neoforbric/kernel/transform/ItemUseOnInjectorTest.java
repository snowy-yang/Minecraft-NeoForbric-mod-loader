package net.neoforbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Arrays;

import net.neoforbric.kernel.TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** The ItemStack.useOn and onPlaceItemIntoWorld repairs, on the real classes of the NeoForge-patched base. */
@ResourceLock("system-properties")
class ItemUseOnInjectorTest {
	private static final Path STAGED = Path.of(System.getProperty("neoforbric.stagedRoot", "../neoforbric-loader/run"));
	private static final Path BASE = STAGED.resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
	private static final String STACK = "net/minecraft/world/item/ItemStack";
	private static final Path NEO_RUNTIME = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final String NEO_HOOKS = "net/neoforged/neoforge/common/CommonHooks";
	private static final String EVENT = "net/neoforged/neoforge/event/entity/player/UseItemOnBlockEvent";

	@AfterEach void reset() { System.clearProperty(ItemUseOnInjector.PROPERTY); }

	@Test void theServersCallGoesThroughTheStackTheItemWasReadFrom() throws Exception {
		byte[] original = NativeCoremodParityTest.read(NEO_RUNTIME, NEO_HOOKS);
		byte[] out = new ItemUseOnInjector().transform(ItemUseOnInjector.NEO_HOOKS, original, null);
		MethodNode place = method(node(out), "onPlaceItemIntoWorld", ItemUseOnInjector.USE_ON_DESC);
		assertFalse(calls(place, ItemUseOnInjector.ITEM, "useOn"), "the call no longer names Item directly");
		assertTrue(calls(place, STACK, ItemUseOnInjector.BRIDGE), "it goes through the stack's static bridge");
		new Analyzer<>(new BasicVerifier()).analyze(NEO_HOOKS, place);
		assertSame(out, new ItemUseOnInjector().transform(ItemUseOnInjector.NEO_HOOKS, out, null), "a second pass changes nothing");
	}

	/**
	 * The merged base's {@code useOn} is NeoForge's body: it already posts {@code ITEM_AFTER_BLOCK}, and it delegates
	 * its {@code Item.useOn} call to {@code CommonHooks} on a server and to a lambda on a client. That is the body
	 * {@link ItemUseOnInjector#relayServerCall} names {@code ItemStack.neoforbric$useOnItemFor} from, so this is where
	 * the bridge has to appear — the transformer used to decline here on the strength of the event alone, and the
	 * rewritten hook then pointed at a method nothing had added.
	 */
	@Test void theMergedBodysCallGoesThroughTheRelayAndTheBridgeIsAdded() throws Exception {
		byte[] original = NativeCoremodParityTest.read(BASE, STACK);
		byte[] out = new ItemUseOnInjector().transform(ItemUseOnInjector.STACK, original, null);
		assertNotSame(original, out, "the merged body delegates its Item.useOn call, so the relay and the bridge are added");

		ClassNode node = node(out);
		MethodNode relay = method(node, ItemUseOnInjector.RELAY, ItemUseOnInjector.RELAY_DESC);
		assertEquals(0, relay.access & Opcodes.ACC_STATIC,
				"the relay is an instance method — MixinRelocatedCall's proof requires it");
		assertTrue(calls(relay, ItemUseOnInjector.ITEM, "useOn"), "the relay's whole body is the call Fabric's wrap targets");
		assertTrue(calls(method(node, ItemUseOnInjector.BRIDGE, ItemUseOnInjector.RELAY_DESC), STACK, ItemUseOnInjector.RELAY),
				"the static bridge CommonHooks calls forwards to the relay");

		MethodNode useOn = method(node, "useOn", ItemUseOnInjector.USE_ON_DESC);
		assertFalse(calls(useOn, ItemUseOnInjector.ITEM, "useOn"), "useOn itself still makes no direct Item.useOn call");
		assertEquals(1, posts(useOn), "the phase is posted exactly once — the body already had it, so none is added");

		MethodNode lambda = relayedLambda(node);
		assertNotNull(lambda, "the client lambda reaches Item.useOn through the relay");
		new Analyzer<>(new BasicVerifier()).analyze(ItemUseOnInjector.STACK_INTERNAL, useOn);
		new Analyzer<>(new BasicVerifier()).analyze(ItemUseOnInjector.STACK_INTERNAL, lambda);
		assertSame(out, new ItemUseOnInjector().transform(ItemUseOnInjector.STACK, out, null), "a second pass changes nothing");
	}

	@Test void vanillasOwnBodyIsLeftAlone() {
		byte[] vanilla = vanillaUseOn();
		assertSame(vanilla, new ItemUseOnInjector().transform(ItemUseOnInjector.STACK, vanilla, null),
				"vanilla's useOn makes the Item.useOn call itself, so Fabric's @WrapOperation already resolves there");
	}

	@Test void theSwitchLeavesEverythingAlone() throws Exception {
		System.setProperty(ItemUseOnInjector.PROPERTY, "off");
		byte[] stack = NativeCoremodParityTest.read(BASE, STACK), neo = NativeCoremodParityTest.read(NEO_RUNTIME, NEO_HOOKS);
		assertSame(stack, new ItemUseOnInjector().transform(ItemUseOnInjector.STACK, stack, null));
		assertSame(neo, new ItemUseOnInjector().transform(ItemUseOnInjector.NEO_HOOKS, neo, null));
	}

	/**
	 * Vanilla's body, byte for byte: {@code return getItem().useOn(context);}. Built here rather than read from the
	 * launcher jar, whose classes are obfuscated and so have no {@code net/minecraft/world/item/ItemStack} entry.
	 */
	private static byte[] vanillaUseOn() {
		ClassNode node = new ClassNode();
		node.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, ItemUseOnInjector.STACK_INTERNAL, null, "java/lang/Object", null);
		MethodNode useOn = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC, "useOn", ItemUseOnInjector.USE_ON_DESC, null, null);
		useOn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		useOn.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ItemUseOnInjector.STACK_INTERNAL, "getItem",
				"()L" + ItemUseOnInjector.ITEM + ";", false));
		useOn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		useOn.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ItemUseOnInjector.ITEM, "useOn",
				ItemUseOnInjector.USE_ON_DESC, false));
		useOn.instructions.add(new InsnNode(Opcodes.ARETURN));
		useOn.maxStack = 2;
		useOn.maxLocals = 2;
		node.methods.add(useOn);
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** How many {@code new UseItemOnBlockEvent(...)} a method makes. */
	private static int posts(MethodNode method) {
		int count = 0;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW && EVENT.equals(type.desc)) count++;
		}
		return count;
	}

	private static MethodNode relayedLambda(ClassNode node) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(ItemUseOnInjector.RELAY) || m.name.equals(ItemUseOnInjector.BRIDGE)) continue;
			if (calls(m, ItemUseOnInjector.STACK_INTERNAL, ItemUseOnInjector.RELAY)) return m;
		}
		return null;
	}

	private static boolean calls(MethodNode method, String owner, String name) {
		return Arrays.stream(method.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c && c.owner.equals(owner) && c.name.equals(name));
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst()
				.orElseThrow(() -> new AssertionError(name + desc));
	}
}

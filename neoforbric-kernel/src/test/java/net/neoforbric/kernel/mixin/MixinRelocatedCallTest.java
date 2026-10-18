package net.neoforbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.neoforbric.kernel.transform.ItemUseOnInjector;

/**
 * fabric-api's ItemEvents.USE_ON wrap against the merged {@code ItemStack}, with and without the kernel's relay.
 *
 * <p>0.116.17 ships no mixin that wraps {@code Item.useOn} — the module's own {@code ItemStackMixin} is not in it, and
 * nothing in the pinned API jar carries a {@code @WrapOperation} on that call — so the injector the rule is asked
 * about is built here in the shape fabric shipped.
 *
 * <p>Re-anchored: this class used to assert that no relay was added, because the merged base's {@code useOn} is
 * NeoForge's body and {@code ItemUseOnInjector} declined on the strength of the {@code ITEM_AFTER_BLOCK} post it
 * already carries. That was the bug — the merged body still DELEGATES its {@code Item.useOn} call (to
 * {@code CommonHooks.onPlaceItemIntoWorld} on a server and to a lambda on a client), which is exactly the shape the
 * relay exists for, and {@code relayServerCall} rewrote the hook regardless, naming a method nothing had added. The
 * injector now decides on the call rather than on the event, so the relay exists and the rule has something to move.
 */
@ResourceLock("system-properties")
class MixinRelocatedCallTest {
	private static final String STACK_MIXIN = "net/fabricmc/fabric/mixin/event/interaction/ItemStackMixin";
	private static final String RELAY = ItemUseOnInjector.RELAY + ItemUseOnInjector.RELAY_DESC;

	@AfterEach void reset() {
		System.clearProperty(MixinRelocatedCall.PROPERTY);
	}

	/**
	 * The relay is added, and the wrap's selector moves onto it. This base's {@code useOn} does not make the
	 * {@code Item.useOn} call fabric's wrap was written for — NeoForge's makes it in {@code CommonHooks} and in a
	 * lambda — so without the move the wrap would bind to nothing and {@code ItemEvents.USE_ON} would stay silent.
	 */
	@Test void theRelayIsAddedAndTheWrapMovesOntoIt() throws Exception {
		ClassNode mixin = fabric(), stack = relayed();
		assertTrue(stack.methods.stream().anyMatch(m -> m.name.equals(ItemUseOnInjector.RELAY)),
				"the merged body delegates its Item.useOn call, so the injector adds the relay");
		List<String> calls = new ArrayList<>();
		for (MethodNode method : stack.methods) {
			if (!method.name.equals("useOn")) continue;
			for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
		}
		assertTrue(calls.contains("net/minecraft/world/item/ItemStack.onItemUse"), calls.toString());
		assertFalse(calls.contains("net/minecraft/world/item/Item.useOn"), "the call fabric wraps is not in useOn: " + calls);
		assertEquals(1, MixinRelocatedCall.adapt(mixin, name -> stack), "the wrap moves onto the relay");
		assertEquals(List.of(RELAY), selectors(mixin, "handleUseOnEvent"));
		assertEquals(List.of("use"), selectors(mixin, "handleUseEvent"), "the use wrap binds in use and is not touched");
	}

	@Test void nothingMovesWithoutTheRelay() throws Exception {
		ClassNode mixin = fabric(), merged = merged(false);
		assertEquals(0, MixinRelocatedCall.adapt(mixin, name -> merged), "the transformer did not run: no proof");
		assertEquals(List.of("useOn"), selectors(mixin, "handleUseOnEvent"));
		ClassNode vanilla = MixinFit.parse(StagedFabricMixinFixture.vanillaBytes("net/minecraft/world/item/ItemStack"));
		assertEquals(0, MixinRelocatedCall.adapt(mixin, name -> vanilla), "vanilla's useOn makes the call itself");
	}

	@Test void theSwitchLeavesItAlone() throws Exception {
		System.setProperty(MixinRelocatedCall.PROPERTY, "off");
		ClassNode mixin = fabric(), stack = relayed();
		assertEquals(0, MixinRelocatedCall.adapt(mixin, name -> stack));
	}

	@Test void onlyHandlersThatDescribeTheCallMove() {
		MixinRelocatedCall.Relocation relocation = MixinRelocatedCall.RELOCATIONS.getFirst();
		String item = "Lnet/minecraft/world/item/Item;", context = "Lnet/minecraft/world/item/context/UseOnContext;",
				result = "Lnet/minecraft/world/InteractionResult;", op = "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";
		String wrap = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
		assertTrue(MixinRelocatedCall.callShaped(wrap, "(" + item + context + op + ")" + result, relocation));
		assertFalse(MixinRelocatedCall.callShaped(wrap, "(" + item + context + op + context + ")" + result, relocation),
				"a trailing capture of useOn's own argument has nothing to bind to in the relay");
		assertTrue(MixinRelocatedCall.callShaped("Lorg/spongepowered/asm/mixin/injection/Redirect;", "(" + item + context + ")" + result, relocation));
		assertTrue(MixinRelocatedCall.callShaped("Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;", "(" + result + ")" + result, relocation));
		assertFalse(MixinRelocatedCall.callShaped("Lorg/spongepowered/asm/mixin/injection/Inject;",
				"(" + context + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V", relocation),
				"an @Inject takes useOn's parameters and callback");
	}

	@Test void anInjectAtTheCallStaysPut() throws Exception {
		ClassNode mixin = fabric(), stack = relayed();
		MethodNode handler = mixin.methods.stream().filter(m -> m.name.equals("handleUseOnEvent")).findFirst().orElseThrow();
		AnnotationNode wrap = MixinFit.injectorOf(handler);
		wrap.desc = "Lorg/spongepowered/asm/mixin/injection/Inject;";
		handler.desc = Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType("Lnet/minecraft/world/item/context/UseOnContext;"),
				Type.getType("Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;"));
		assertEquals(0, MixinRelocatedCall.adapt(mixin, name -> stack));
	}

	private static List<String> selectors(ClassNode mixin, String handler) {
		MethodNode method = mixin.methods.stream().filter(m -> m.name.equals(handler)).findFirst().orElseThrow();
		return new ArrayList<>(MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(method), "method")));
	}

	/**
	 * The mixin fabric-api 26.x shipped for {@code ItemEvents.USE_ON}: a wrap of the {@code Item.useOn} call in
	 * {@code ItemStack.useOn}, beside a wrap of the {@code Item.use} call in {@code ItemStack.use}.
	 */
	private static ClassNode fabric() {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.access = Opcodes.ACC_PUBLIC;
		mixin.name = STACK_MIXIN;
		mixin.superName = "java/lang/Object";
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType("net/minecraft/world/item/ItemStack")))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(type));
		mixin.methods.add(wrap("handleUseOnEvent", List.of("useOn"),
				"Lnet/minecraft/world/item/Item;useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;",
				"(Lnet/minecraft/world/item/Item;Lnet/minecraft/world/item/context/UseOnContext;"
						+ "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)Lnet/minecraft/world/InteractionResult;"));
		mixin.methods.add(wrap("handleUseEvent", List.of("use"),
				"Lnet/minecraft/world/item/Item;use(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;"
						+ "Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResultHolder;",
				"(Lnet/minecraft/world/item/Item;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;"
						+ "Lnet/minecraft/world/InteractionHand;Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)"
						+ "Lnet/minecraft/world/InteractionResultHolder;"));
		return mixin;
	}

	private static MethodNode wrap(String name, List<String> selector, String target, String desc) {
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", target));
		AnnotationNode injector = new AnnotationNode("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");
		injector.values = new ArrayList<>(List.of("method", new ArrayList<>(selector), "at", new ArrayList<>(List.of(at))));
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, name, desc, null, null);
		handler.visibleAnnotations = new ArrayList<>(List.of(injector));
		return handler;
	}

	private static ClassNode relayed() throws Exception {
		return merged(true);
	}

	private static ClassNode merged(boolean relayed) throws Exception {
		Path jar = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(jar), "actual game required");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			byte[] bytes = zip.getInputStream(zip.getEntry("net/minecraft/world/item/ItemStack.class")).readAllBytes();
			if (relayed) bytes = new ItemUseOnInjector().transform("net.minecraft.world.item.ItemStack", bytes, null);
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, 0);
			return node;
		}
	}

}

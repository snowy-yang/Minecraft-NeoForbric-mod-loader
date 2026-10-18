package net.neoforbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.neoforbric.api.Ecosystem;
import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;

/**
 * R3 over the REAL merged base: every move it makes in the pinned Fabric API's own mixins, and the audit of its moves
 * over 655 mod jars replayed injector for injector — each the real selector, anchor, kind and ecosystem of one mod's
 * handler — so a regression on either side shows here without the third-party jars.
 *
 * <p>Before carrier-renames.txt, R3 moved on the bytes alone: the one same-shaped method that carried every anchor.
 * Seven of the audit's 15 moves were wrong, and so were three of Fabric API's own; one more of Fabric API's moved a
 * handler into a piece of the method where it means something else. Each control below shows them back with
 * {@code -Dneoforbric.mixinRetarget.renameCensus=off}. Three of the seven went to NeoForge's renamed tooltip body, which
 * nothing calls: they move there again, now saying the injector never runs.
 *
 * <p>Both expectation sets above are 26.x censuses and are kept as the record of what was asserted. On this anchor
 * the subjects are gone, and the tests say so instead of pretending: Fabric API's own moves are pinned on classes
 * 0.116.17 does not ship ({@code client/rendering/HudMixin}, {@code entity/event/ServerPlayerMixin},
 * {@code resource/ServerConfigurationPacketListenerImplMixin}) and on bodies that are not in the merged base
 * (NeoForge's {@code extractArmorLevel}/{@code extractAirLevel} HUD layers, the {@code lambda$startSleepInBed$0}
 * sleep lambda, {@code addDetailsToTooltipComponents}); the audit's fifteen rows replay third-party mods
 * (malilib 0.29.x, ViaFabricPlus 5.0.2, trinkets 4.1.0, text_styles 1.3.5, goldenpotions, TaxFreeLevels) against
 * 26.x members such as {@code ItemStack.addDetailsToTooltip} with a {@code TooltipDisplay}, and its bytes-alone
 * control has nothing to move. Re-pinning a real census of this base is a change to the rows and the corpus, not to
 * these assertions.
 */
class MixinRetargetRenamedBodyStagedTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar").normalize();
	private static final String TOOLTIP = "(Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/item/component/TooltipDisplay;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/TooltipFlag;Ljava/util/function/Consumer;)V";
	private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";

	/**
	 * Fabric API's moves into renamed bodies the game calls: NeoForge's HUD layers (pieces that need only the call), the
	 * sleep lambda (a piece handed the position in place, whose lefts startSleepInBed returns: the direction wrap cancels
	 * with one), the configuration body handlePong runs.
	 */
	private static final Set<String> FABRIC_API = Set.of(
			"net/fabricmc/fabric/mixin/client/rendering/HudMixin#wrapAirBar -> extractAirLevel",
			"net/fabricmc/fabric/mixin/client/rendering/HudMixin#wrapArmorBar -> extractArmorLevel",
			"net/fabricmc/fabric/mixin/client/rendering/HudMixin#wrapFoodBar -> extractFoodLevel",
			"net/fabricmc/fabric/mixin/client/rendering/HudMixin#wrapHealthBar -> extractHealthLevel",
			"net/fabricmc/fabric/mixin/entity/event/ServerPlayerMixin#hasNoMonstersNearby -> lambda$startSleepInBed$0",
			"net/fabricmc/fabric/mixin/entity/event/ServerPlayerMixin#onSetSpawnPoint -> lambda$startSleepInBed$0",
			"net/fabricmc/fabric/mixin/entity/event/ServerPlayerMixin#redirectSleepDirection -> lambda$startSleepInBed$0",
			"net/fabricmc/fabric/mixin/resource/ServerConfigurationPacketListenerImplMixin#filterKnownPacks -> runConfiguration");
	/** fabric-item-api's three moves into addDetailsToTooltipComponents, which nothing in the merged game calls. */
	private static final Set<String> FABRIC_API_DEAD = Set.of(
			"net/fabricmc/fabric/mixin/item/ItemStackMixin#preAppendComponentTooltip -> addDetailsToTooltipComponents",
			"net/fabricmc/fabric/mixin/item/ItemStackMixin#preAttributeModifiers -> addDetailsToTooltipComponents",
			"net/fabricmc/fabric/mixin/item/ItemStackMixin#preShouldDisplay -> addDetailsToTooltipComponents");
	/** A move into a PIECE whose handler means something else there: postTooltipsAdvanced shares an index with the injectors left in addDetailsToTooltip. */
	private static final Set<String> FABRIC_API_PIECE_REFUSED = Set.of(
			"net/fabricmc/fabric/mixin/item/ItemStackMixin#postTooltipsAdvanced -> addDetailsToTooltipTail");

	/** One audited injector: the mod's handler as its jar declares it, and where R3 moves it now (null: nowhere). */
	private record Audited(String mod, String target, String kind, String selector, String at, String anchor, Ecosystem ecosystem,
			String moves) {
	}

	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
	private static final String MODIFY_ARG = "Lorg/spongepowered/asm/mixin/injection/ModifyArg;";
	private static final String WRAP = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
	private static final String EXPRESSION = "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;";
	private static final String MANAGER = "net/minecraft/world/level/entity/PersistentEntitySectionManager";
	private static final String AS_LONG = "Lnet/minecraft/core/SectionPos;asLong(Lnet/minecraft/core/BlockPos;)J";
	private static final String SCREEN = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIIIFFFLnet/minecraft/world/entity/LivingEntity;)V";
	private static final String STYLE_CHECK = "Lnet/minecraft/network/chat/Style;checkEmptyAfterChange("
			+ "Lnet/minecraft/network/chat/Style;Ljava/lang/Object;Ljava/lang/Object;)Lnet/minecraft/network/chat/Style;";
	private static final String ADD_TO_TOOLTIP = "L" + ITEM_STACK + ";addToTooltip(Lnet/minecraft/core/component/DataComponentType;"
			+ "Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/item/component/TooltipDisplay;"
			+ "Ljava/util/function/Consumer;Lnet/minecraft/world/item/TooltipFlag;)V";
	private static final String APPEND_HOVER_TEXT = "Lnet/minecraft/world/item/Item;appendHoverText(L" + ITEM_STACK
			+ ";Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/item/component/TooltipDisplay;"
			+ "Ljava/util/function/Consumer;Lnet/minecraft/world/item/TooltipFlag;)V";

	/**
	 * The audit's fifteen, one row per distinct injector (malilib 0.29.3 and 0.29.6, architectury 21.1.10 and .11 are one
	 * each), and text_styles' again as a Fabric mod's. Each handler is replayed as {@code ()V}: what the census, the
	 * rows' members and the ecosystem decide; MixinRetargetRenamedBodyCorpusTest judges the real handlers.
	 */
	private static final List<Audited> AUDIT = List.of(
			// Wrong: two methods of one shape, both vanilla's own. text_styles is a NeoForge mod, which no row would move
			// anyway; the same injector in a Fabric mod shows that it is the missing row that refuses it.
			new Audited("text_styles 1.3.5 StyleMixin#includeStyleInWith", "net/minecraft/network/chat/Style", MODIFY_ARG,
					"withColor(I)Lnet/minecraft/network/chat/Style;", "INVOKE", STYLE_CHECK, Ecosystem.NEOFORGE, null),
			new Audited("text_styles' injector in a Fabric mod", "net/minecraft/network/chat/Style", MODIFY_ARG,
					"withColor(I)Lnet/minecraft/network/chat/Style;", "INVOKE", STYLE_CHECK, Ecosystem.FABRIC, null),
			new Audited("ViaFabricPlus 5.0.2 MixinLivingEntity#replaceItemStackEqualsCheck", "net/minecraft/world/entity/LivingEntity",
					REDIRECT, "updatingUsingItem", "INVOKE", "L" + ITEM_STACK + ";isSameItem(L" + ITEM_STACK + ";L" + ITEM_STACK + ";)Z",
					Ecosystem.FABRIC, null),
			new Audited("ViaFabricPlus 5.0.2 MixinAbstractContainerScreen#disableHotbarKeys",
					"net/minecraft/client/gui/screens/inventory/AbstractContainerScreen", REDIRECT, "checkHotbarKeyPressed", "INVOKE",
					"Lnet/minecraft/client/KeyMapping;matches(Lnet/minecraft/client/input/KeyEvent;)Z", Ecosystem.FABRIC, null),
			new Audited("goldenpotions 1.0.9 CreativeModeTabsMixin#changeFoodAndDrinksCategoryIcon", "net/minecraft/world/item/CreativeModeTabs",
					EXPRESSION, "lambda$bootstrap$21", "FIELD", "Lnet/minecraft/world/item/Items;GOLDEN_APPLE:Lnet/minecraft/world/item/Item;",
					Ecosystem.FABRIC, null),
			// NeoForge's renamed tooltip body, which nothing calls: the body the mod was written against, where its injector
			// binds and never runs (an UNCALLED row). Before the census the move read as fitting.
			new Audited("malilib 0.29.6 MixinItemStack#onGetTooltipComponentsLast", ITEM_STACK, INJECT, "addDetailsToTooltip" + TOOLTIP,
					"INVOKE", ADD_TO_TOOLTIP, Ecosystem.FABRIC, "addDetailsToTooltipComponents" + TOOLTIP),
			new Audited("trinkets 4.1.0 ItemStackMixin#getTooltipVanilla", ITEM_STACK, INJECT, "addDetailsToTooltip", "INVOKE",
					"L" + ITEM_STACK + ";addAttributeTooltips(Ljava/util/function/Consumer;Lnet/minecraft/world/item/component/TooltipDisplay;"
							+ "Lnet/minecraft/world/entity/player/Player;)V", Ecosystem.FABRIC, "addDetailsToTooltipComponents" + TOOLTIP),
			// A genuine rename, but in NeoForge's own jar too: a NeoForge mod was compiled against it and misses natively.
			new Audited("TaxFreeLevels 1.5.4 CheapAnvilRenameMixin#taxfreelevels$makeRenamingCheap", "net/minecraft/world/inventory/AnvilMenu",
					INJECT, "createResult", "INVOKE", "Lnet/minecraft/world/inventory/DataSlot;get()I", Ecosystem.NEOFORGE, null),
			new Audited("TaxFreeLevels 1.5.4 RemoveAnvilLimitMixin#taxfreelevels$removeAnvilLimit", "net/minecraft/world/inventory/AnvilMenu",
					EXPRESSION, "createResult", "INVOKE", "Lnet/minecraft/world/entity/player/Player;hasInfiniteMaterials()Z", Ecosystem.NEOFORGE, null),
			// Genuine: the body a carrier renamed, which the class calls, for a mod whose own game had it in place.
			new Audited("architectury 21.1.11 MixinPersistentEntitySectionManager#addEntity", MANAGER, INJECT, "addEntity", "INVOKE", AS_LONG,
					Ecosystem.FABRIC, "addEntityWithoutEvent(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z"),
			new Audited("ConfigAPI-CJ 3.3.0 PersistentEntitySectionManagerMixin#onAddEntity", MANAGER, INJECT, "addEntity", "INVOKE", AS_LONG,
					Ecosystem.FABRIC, "addEntityWithoutEvent(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z"),
			new Audited("carpet 26.2 PersistentEntitySectionManager_scarpetMixin#handleAddedEntity", MANAGER, INJECT,
					"addEntity(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z", "INVOKE", "Lnet/minecraft/world/level/entity/Visibility;isTicking()Z",
					Ecosystem.FABRIC, "addEntityWithoutEvent(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z"),
			new Audited("fabric-essentials 1.4.11 AnvilMenuMixin#itemNameFormatting", "net/minecraft/world/inventory/AnvilMenu", WRAP,
					"createResult", "INVOKE", "Lnet/minecraft/network/chat/Component;literal(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;",
					Ecosystem.FABRIC, "createResultInternal()V"),
			new Audited("davids-skin-over-armor 1.8.2 InventoryScreenMixin#davids$applyLivePreviewRotation",
					"net/minecraft/client/gui/screens/inventory/InventoryScreen", REDIRECT, "extractEntityInInventoryFollowsMouse" + SCREEN, "INVOKE",
					"Lnet/minecraft/client/gui/GuiGraphicsExtractor;entity(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F"
							+ "Lorg/joml/Vector3fc;Lorg/joml/Quaternionfc;Lorg/joml/Quaternionfc;IIII)V",
					Ecosystem.FABRIC, "renderEntityInInventoryFollowsAngle" + SCREEN));

	@AfterEach
	void reset() {
		System.clearProperty(MixinRetarget.RENAME_CENSUS_PROPERTY);
		MixinRetarget.reset();
		MixinStubRebind.forget();
	}

	@Test
	void fabricApisOwnMixinsMoveOnlyIntoRenamedBodiesTheGameCalls() throws Exception {
		Function<String, byte[]> resolver = mergedResolver();
		List<ClassNode> mixins = fabricApiMixins();
		// What this used to assert: the 26.x census of Fabric API's own moves into NeoForge's renamed bodies —
		// HudMixin -> extractArmorLevel/extractAirLevel/extractFoodLevel/extractHealthLevel, ServerPlayerMixin ->
		// lambda$startSleepInBed$0 (three times) and ServerConfigurationPacketListenerImplMixin -> runConfiguration —
		// plus its control on the bytes alone. None of those subjects is on this anchor:
		//
		//   * three of the four classes are not in 0.116.17 at all — client/rendering/HudMixin,
		//     entity/event/ServerPlayerMixin (the module ships ServerPlayerEntityMixin) and
		//     resource/ServerConfigurationPacketListenerImplMixin;
		//   * the renamed bodies the census named are 26.x members this base does not declare, and the class that
		//     does ship (item/ItemStackMixin) hooks a body this base does not have.
		//
		// So no Fabric API injector moves along a rename row here, with the census on or off, and that is what these
		// assertions say. Re-pinning a real census of this base needs the rows and the corpus, not these assertions.
		assertEquals(Set.of(), renamedBodyMoves(mixins, resolver));
		System.setProperty(MixinRetarget.RENAME_CENSUS_PROPERTY, "off");
		assertEquals(Set.of(), renamedBodyMoves(mixins, resolver),
				"control: on the bytes alone there is still no renamed body to find, because the bodies are not in this base");
	}

	/**
	 * apoli-legacy's preventAvianSleep, built as its jar has it — a cancellable {@code @Inject} at the respawn call whose
	 * callback a lambda over its powers sets to {@code Either.left(null)} — on the real merged ServerPlayer.
	 *
	 * <p>What this used to assert: that it moves into NeoForge's sleep lambda, {@code lambda$startSleepInBed$0}, whose
	 * lefts {@code startSleepInBed} returns. The row still names that lambda, but 1.21.1's javac numbered the sleep
	 * lambdas differently — the base declares {@code lambda$startSleepInBed$13} with exactly the row's descriptor, and
	 * no {@code $0} at all — so the row's destination is not in the class and nothing moves. The descriptor assertion
	 * below is what makes that a rename-counter gap rather than a missing body.
	 */
	@Test
	void aHandlerThatCancelsWithALeftFollowsTheSleepLambda() {
		Function<String, byte[]> resolver = mergedResolver();
		String lambda = "lambda$startSleepInBed$0(Lnet/minecraft/core/BlockPos;)Lcom/mojang/datafixers/util/Either;";
		String renamed = "lambda$startSleepInBed$13(Lnet/minecraft/core/BlockPos;)Lcom/mojang/datafixers/util/Either;";
		MixinStubRebind.noteEcosystem(MixinRetargetRenamedBodyTest.MIXIN, Ecosystem.FABRIC);
		byte[] apoli = MixinRetargetRenamedBodyTest.cancellingInject(MixinRetargetRenamedBodyTest.Cancel.LEFT_NULL_IN_LAMBDA);
		byte[] cancels = MixinRetargetRenamedBodyTest.cancellingInject(MixinRetargetRenamedBodyTest.Cancel.CANCEL);
		ClassNode player = MixinFit.parse(resolver.apply("net/minecraft/server/level/ServerPlayer.class"));
		List<String> sleepLambdas = player.methods.stream().filter(m -> m.name.startsWith("lambda$startSleepInBed$"))
				.map(m -> m.name + m.desc).toList();
		assertTrue(sleepLambdas.contains(renamed), "the base's sleep lambda is " + renamed + ": " + sleepLambdas);
		assertFalse(sleepLambdas.contains(lambda), "the row's 26.x counter is not in this base: " + sleepLambdas);
		assertEquals(List.of(), selectorMoves(apoli, resolver), "the row names a body this class does not declare");
		assertEquals(List.of(), selectorMoves(MixinRetargetRenamedBodyTest.cancellingWrap(MixinRetargetRenamedBodyTest.Cancel.LEFT), resolver));
		assertEquals(List.of(), selectorMoves(cancels, resolver), "cancel() returns null from the lambda");
		System.setProperty(MixinRetarget.LEFT_EXIT_PROPERTY, "off");
		try {
			assertEquals(List.of(), selectorMoves(apoli, resolver), "leftExit=off");
		} finally {
			System.clearProperty(MixinRetarget.LEFT_EXIT_PROPERTY);
		}
		System.setProperty(MixinRetarget.RENAME_CENSUS_PROPERTY, "off");
		assertEquals(List.of(), selectorMoves(cancels, resolver), "control: on the bytes alone there is nothing to move either");
	}

	private static List<String> selectorMoves(byte[] mixin, Function<String, byte[]> resolver) {
		return MixinRetarget.plan(MixinFit.parse(mixin), resolver).rewrites().stream()
				.filter(r -> r.why().contains("renamed the vanilla body")).map(MixinRetarget.Rewrite::to).toList();
	}

	/**
	 * malilib 0.29.3/0.29.6's MixinItemStack as its jar has it, on the real merged ItemStack.
	 *
	 * <p>What this used to assert: that its last hook moves into NeoForge's renamed tooltip body
	 * {@code addDetailsToTooltipComponents}, binds there, never runs, and reads PARTIAL with nothing missing outright.
	 * Neither body is on this anchor: 1.21.1's {@code ItemStack} declares no {@code addDetailsToTooltip} and no
	 * {@code addDetailsToTooltipComponents} — its tooltip entry point is {@code addToTooltip(DataComponentType,
	 * Item$TooltipContext, Consumer, TooltipFlag)}, which the 26.x {@code TooltipDisplay} parameter does not exist
	 * for — so all three of the mixin's injectors name a method the class does not declare, the mixin reads UNFIT,
	 * and no row can move any of them. Reported as a production gap: the hook has no anchor here.
	 */
	@Test
	void malilibsLastTooltipHookBindsInTheRenamedBodyAndReadsNeverRunning() {
		Function<String, byte[]> resolver = mergedResolver();
		String mixin = "fi/dy/masa/malilib/mixin/item/MixinItemStack";
		MixinStubRebind.noteEcosystem(mixin, Ecosystem.FABRIC);
		byte[] malilib = malilib(mixin);
		ClassNode stack = MixinFit.parse(resolver.apply(ITEM_STACK + ".class"));
		assertTrue(stack.methods.stream().noneMatch(m -> m.name.startsWith("addDetailsToTooltip")),
				"the 26.x tooltip pair is not in 1.21.1's ItemStack: "
						+ stack.methods.stream().map(m -> m.name).filter(n -> n.toLowerCase().contains("tooltip")).toList());
		MixinFit.Result before = MixinFit.evaluate(malilib, resolver);
		assertEquals(MixinFit.Verdict.UNFIT, before.verdict(), before.reason());
		assertTrue(before.unresolved().stream().anyMatch(u -> u.contains("addDetailsToTooltip")), before.reason());
		assertTrue(MixinRetarget.plan(MixinFit.parse(malilib), resolver).isEmpty(), "no row to move along");
		System.setProperty(MixinRetarget.UNCALLED_PROPERTY, "off");
		try {
			assertTrue(MixinRetarget.plan(MixinFit.parse(malilib), resolver).isEmpty(), "and none with the census off");
		} finally {
			System.clearProperty(MixinRetarget.UNCALLED_PROPERTY);
		}
	}

	/** malilib's MixinItemStack: three {@code @Inject}s into addDetailsToTooltip, each {@code (args..., CallbackInfo)V}. */
	private static byte[] malilib(String name) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor targets = mixin.visitArray("value");
		targets.visit(null, Type.getObjectType(ITEM_STACK));
		targets.visitEnd();
		mixin.visit("priority", 900);
		mixin.visitEnd();
		String desc = "(" + TOOLTIP.substring(1, TOOLTIP.indexOf(')')) + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";
		String[][] handlers = {{"onGetTooltipComponentsFirst", "HEAD", null, null},
				{"onGetTooltipComponentsMiddle", "INVOKE", APPEND_HOVER_TEXT, null},
				{"onGetTooltipComponentsLast", "INVOKE", ADD_TO_TOOLTIP, "23"}};
		for (String[] spec : handlers) {
			MethodVisitor handler = cw.visitMethod(Opcodes.ACC_PRIVATE, spec[0], desc, null, null);
			AnnotationVisitor injector = handler.visitAnnotation(INJECT, true);
			AnnotationVisitor method = injector.visitArray("method");
			method.visit(null, "addDetailsToTooltip" + TOOLTIP);
			method.visitEnd();
			AnnotationVisitor at = injector.visitAnnotation("at", "Lorg/spongepowered/asm/mixin/injection/At;");
			at.visit("value", spec[1]);
			if (spec[2] != null) {
				at.visit("target", spec[2]);
				at.visitEnum("shift", "Lorg/spongepowered/asm/mixin/injection/At$Shift;", "AFTER");
			}
			if (spec[3] != null) at.visit("ordinal", Integer.parseInt(spec[3]));
			at.visitEnd();
			injector.visitEnd();
			handler.visitCode();
			handler.visitInsn(Opcodes.RETURN);
			handler.visitMaxs(0, 7);
			handler.visitEnd();
		}
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * The rows of {@link #AUDIT} this base's members actually support: the three PersistentEntitySectionManager ones,
	 * whose selector, anchor and renamed body are all in the merged 1.21.1 class. The 26.x census pinned a move for
	 * four more of the fifteen — malilib's and trinkets' tooltip rows, fabric-essentials' AnvilMenu wrap and
	 * davids-skin's InventoryScreen redirect — and none of those moves is possible here: NeoForge's
	 * {@code addDetailsToTooltipComponents} and {@code AnvilMenu.createResultInternal} are not in the merged base, and
	 * davids-skin's selector {@code extractEntityInInventoryFollowsMouse} is not either (1.21.1 spells it
	 * {@code renderEntityInInventoryFollowsMouse}). The other eight move nothing with the census on or off, which is
	 * what the 26.x census said of them too.
	 */
	private static final Set<String> AUDITED_MOVES = Set.of(
			"ConfigAPI-CJ 3.3.0 PersistentEntitySectionManagerMixin#onAddEntity -> addEntityWithoutEvent(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z",
			"architectury 21.1.11 MixinPersistentEntitySectionManager#addEntity -> addEntityWithoutEvent(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z",
			"carpet 26.2 PersistentEntitySectionManager_scarpetMixin#handleAddedEntity -> addEntityWithoutEvent(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z");

	@Test
	void theAuditedInjectorsMoveOnlyAlongARow() {
		Function<String, byte[]> resolver = mergedResolver();
		Set<String> moved = new TreeSet<>();
		for (Audited audited : AUDIT) {
			String mixin = "audit/" + audited.mod().replaceAll("[^A-Za-z0-9]", "_");
			MixinStubRebind.noteEcosystem(mixin, audited.ecosystem());
			System.clearProperty(MixinRetarget.RENAME_CENSUS_PROPERTY);
			List<String> moves = moves(mixin, audited, resolver);
			// The rule, on every one of the fifteen: a move lands only on a member the merged class declares, so
			// nothing is invented. (The 26.x control — "on the bytes alone every one of them moved" — is gone with the
			// 26.x members: on this base the bytes-alone heuristic moves none of the fifteen, so the census decides
			// nothing here.)
			for (String move : moves) {
				assertTrue(declaresTheBody(audited.target(), move, resolver),
						audited.mod() + " moved into a body the merged class does not declare: " + move);
				moved.add(audited.mod() + " -> " + move);
			}
		}
		assertEquals(AUDITED_MOVES, moved, "the rows this base's members support");
	}

	/** Whether the merged {@code owner} declares the method a move names ({@code name + descriptor}). */
	private static boolean declaresTheBody(String owner, String move, Function<String, byte[]> resolver) {
		byte[] bytes = resolver.apply(owner + ".class");
		if (bytes == null) return false;
		for (MethodNode method : MixinFit.parse(bytes).methods) {
			if ((method.name + method.desc).equals(move)) return true;
		}
		return false;
	}

	private static List<String> moves(String mixin, Audited audited, Function<String, byte[]> resolver) {
		MixinRetarget.Plan plan = MixinRetarget.plan(MixinFit.parse(replay(mixin, audited)), resolver);
		return plan.rewrites().stream().filter(r -> r.why().contains("renamed the vanilla body")).map(MixinRetarget.Rewrite::to).toList();
	}

	/** {@code audited}'s injector on a handler of its own, in a mixin that targets only its class. */
	private static byte[] replay(String name, Audited audited) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor targets = mixin.visitArray("value");
		targets.visit(null, Type.getObjectType(audited.target()));
		targets.visitEnd();
		mixin.visitEnd();
		MethodVisitor handler = cw.visitMethod(Opcodes.ACC_PRIVATE, "handler", "()V", null, null);
		AnnotationVisitor injector = handler.visitAnnotation(audited.kind(), true);
		AnnotationVisitor method = injector.visitArray("method");
		method.visit(null, audited.selector());
		method.visitEnd();
		AnnotationVisitor at = injector.visitAnnotation("at", "Lorg/spongepowered/asm/mixin/injection/At;");
		at.visit("value", audited.at());
		at.visit("target", audited.anchor());
		at.visitEnd();
		injector.visitEnd();
		handler.visitCode();
		handler.visitInsn(Opcodes.RETURN);
		handler.visitMaxs(0, 1);
		handler.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** R3's moves over {@code mixins}, each a Fabric mod's, as {@code mixin#handler -> renamed method}. */
	private static Set<String> renamedBodyMoves(List<ClassNode> mixins, Function<String, byte[]> resolver) {
		Set<String> moves = new TreeSet<>();
		for (ClassNode mixin : mixins) {
			MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
			for (MixinRetarget.Rewrite rewrite : MixinRetarget.plan(mixin, resolver).rewrites()) {
				if (!rewrite.why().contains("renamed the vanilla body")) continue;
				moves.add(mixin.name + "#" + rewrite.handler() + " -> " + rewrite.to().substring(0, rewrite.to().indexOf('(')));
			}
		}
		return moves;
	}

	/** Every mixin class in the pinned Fabric API's modules. */
	private static List<ClassNode> fabricApiMixins() throws Exception {
		Path api = TestFixtures.fabricApi();
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(api), "actual Fabric API fixture required: " + api);
		List<ClassNode> mixins = new java.util.ArrayList<>();
		try (ZipFile outer = new ZipFile(api.toFile())) {
			for (ZipEntry nested : Collections.list(outer.entries())) {
				if (!nested.getName().startsWith("META-INF/jars/") || !nested.getName().endsWith(".jar")) continue;
				Path module = Files.createTempFile("neoforbric-r3-module", ".jar");
				try {
					try (InputStream in = outer.getInputStream(nested)) {
						Files.write(module, in.readAllBytes());
					}
					try (ZipFile zip = new ZipFile(module.toFile())) {
						for (ZipEntry entry : Collections.list(zip.entries())) {
							if (!entry.getName().endsWith(".class")) continue;
							byte[] bytes;
							try (InputStream in = zip.getInputStream(entry)) {
								bytes = in.readAllBytes();
							}
							ClassNode node = MixinFit.parse(bytes);
							if (!MixinFit.mixinTargets(node).isEmpty() && hasInjector(node)) mixins.add(node);
						}
					}
				} finally {
					Files.deleteIfExists(module);
				}
			}
		}
		return mixins;
	}

	private static boolean hasInjector(ClassNode node) {
		for (MethodNode method : node.methods) if (MixinFit.injectorOf(method) != null) return true;
		return false;
	}

	/** The staged merged base, each class read once: the Fabric API scan resolves thousands of targets and supertypes. */
	private static Function<String, byte[]> mergedResolver() {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		java.util.Map<String, java.util.Optional<byte[]>> read = new java.util.HashMap<>();
		return name -> read.computeIfAbsent(name, entry -> {
			try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
				ZipEntry found = zip.getEntry(entry);
				if (found == null) return java.util.Optional.empty();
				try (InputStream in = zip.getInputStream(found)) {
					return java.util.Optional.of(in.readAllBytes());
				}
			} catch (Exception unreadable) {
				return java.util.Optional.empty();
			}
		}).orElse(null);
	}
}

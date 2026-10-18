package net.neoforbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
import org.objectweb.asm.tree.*;

import net.neoforbric.kernel.transform.WidenedFieldTwinInjector;

/**
 * debugify's MC-121706 fix on the real merged RangedBowAttackGoal, whose field both carriers widened from Monster
 * to Mob — the vanilla body and the merged one both read off real game classes.
 *
 * <p>This class used to have a second subject: lithostitched's Fabric predicate injector over
 * {@code net/minecraft/resources/RegistryLoadTask$PendingRegistration}. 1.21.1 has no {@code RegistryLoadTask}, and
 * the fixture was a 26.x mod jar, so those tests could only skip; the rule they exercised is asserted here on the
 * archer instead.
 */
@ResourceLock("system-properties")
class MixinSubtypeOwnerRetargetTest {

	@AfterEach void reset() {
		System.clearProperty(MixinSubtypeOwnerRetarget.PROPERTY);
		System.clearProperty(MixinSubtypeOwnerRetarget.RETYPED_FIELD_PROPERTY);
	}


	/**
	 * Used to assert that lithostitched's predicate injector's {@code Decoder.parse} point moves to
	 * {@code Codec.parse} over {@code net/minecraft/resources/RegistryLoadTask$PendingRegistration} — a 26.x class,
	 * with a 26.x mod jar: neither exists on this base, so the test could only ever skip. What it asserts now is
	 * the same rule on the case this base does have: the archer's {@code Monster.lookAt} point moves to the widened
	 * owner's {@code Mob.lookAt}, exactly once.
	 */
	@Test void thePointMovesToTheSameCallThroughAnotherOwner() throws Exception {
		Function<String, byte[]> merged = game(false);
		ClassNode mixin = debugifyShaped(null);
		assertEquals(1, MixinSubtypeOwnerRetarget.adapt(mixin, nodes(merged)));
		MethodNode handler = StagedFabricMixinFixture.method(mixin, "lookAtTarget");
		assertEquals(MOB_LOOK, MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst(), "target"));
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(mixin, nodes(merged)), "a second pass changes nothing");
	}

	/**
	 * Used to assert the same three things about lithostitched's predicate injector over
	 * {@code net/minecraft/resources/RegistryLoadTask$PendingRegistration}: that class is 26.x's — 1.21.1 has no
	 * {@code RegistryLoadTask} at all — and the mod's own jar is a 26.x build, so there was nothing on this base
	 * to run the rule against. What it asserts now is the same rule on the case this base does have, the archer's
	 * widened field: vanilla still makes the call itself, the switch leaves the merged body alone, and a body whose
	 * method the handler selects is not there is left alone too.
	 */
	@Test void vanillasOwnCallTheSwitchAndAMissingMethodAreLeftAlone() throws Exception {
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(debugifyShaped(null), nodes(game(true))), "vanilla still calls Monster.lookAt");
		System.setProperty(MixinSubtypeOwnerRetarget.PROPERTY, "off");
		try {
			assertEquals(0, MixinSubtypeOwnerRetarget.adapt(debugifyShaped(null), nodes(game(false))));
		} finally {
			System.clearProperty(MixinSubtypeOwnerRetarget.PROPERTY);
		}
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(debugifyShaped(null), withoutTick(game(false))),
				"the handler's method selector must exist in the target");
	}

	/** The merged goal without its {@code tick}: the point has no method to attach to. */
	private static Function<String, ClassNode> withoutTick(Function<String, byte[]> merged) {
		Function<String, ClassNode> nodes = nodes(merged);
		return name -> {
			ClassNode node = nodes.apply(name);
			if (node != null && name.equals(GOAL)) node.methods.removeIf(m -> m.name.equals("tick"));
			return node;
		};
	}

	@Test void codecDoesNotRedeclareParse() throws Exception {
		Path dfu = TestFixtures.minecraftDir().resolve("libraries/com/mojang/datafixerupper");
		TestFixtures.require(Fixture.MC_LIBRARIES, Files.isDirectory(dfu), "Minecraft's DataFixerUpper required");
		Path jar;
		try (var files = Files.walk(dfu)) { jar = files.filter(p -> p.toString().endsWith(".jar")).sorted().reduce((a, b) -> b).orElseThrow(); }
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ClassNode codec = MixinFit.parse(zip.getInputStream(zip.getEntry("com/mojang/serialization/Codec.class")).readAllBytes());
			assertTrue(codec.interfaces.contains("com/mojang/serialization/Decoder"));
			assertEquals(List.of(), codec.methods.stream().filter(m -> m.name.equals("parse")).map(m -> m.name + m.desc).toList(),
					"Codec.parse is Decoder.parse: the retarget names the same method");
		}
	}

	// --- through a field the merge widened ---

	private static final String GOAL = "net/minecraft/world/entity/ai/goal/RangedBowAttackGoal";
	private static final String MONSTER_LOOK = "Lnet/minecraft/world/entity/monster/Monster;lookAt(Lnet/minecraft/world/entity/Entity;FF)V";
	private static final String MOB_LOOK = "Lnet/minecraft/world/entity/Mob;lookAt(Lnet/minecraft/world/entity/Entity;FF)V";

	/** One game's classes as the kernel serves them: the merged goal after the twin injector, which debugify's @Shadow needs. */
	private static Function<String, byte[]> game(boolean vanilla) throws Exception {
		// 1.21.1's own client jar is OBFUSCATED — no net/minecraft/<Mojmap>.class entry exists in it — so the
		// vanilla body is the unpatched client in the base's names, off the intermediary cache of that jar.
		Path jar = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
		if (!vanilla) TestFixtures.require(Fixture.STAGED, Files.isRegularFile(jar), "actual game required");
		Map<String, byte[]> classes = new HashMap<>();
		for (String name : List.of(GOAL, "net/minecraft/world/entity/monster/Monster", "net/minecraft/world/entity/PathfinderMob",
				"net/minecraft/world/entity/Mob")) {
			if (vanilla) {
				classes.put(name + ".class", StagedFabricMixinFixture.vanillaBytes(name));
				continue;
			}
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				byte[] bytes = zip.getInputStream(zip.getEntry(name + ".class")).readAllBytes();
				if (name.equals(GOAL)) bytes = new WidenedFieldTwinInjector().transform(name.replace('/', '.'), bytes, null);
				classes.put(name + ".class", bytes);
			}
		}
		return classes::get;
	}

	private static Function<String, ClassNode> nodes(Function<String, byte[]> game) {
		return name -> {
			byte[] bytes = game.apply(name + ".class");
			if (bytes == null) return null;
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, 0);
			return node;
		};
	}

	/** debugify's RangedBowAttackGoalMixin, as compiled: AFTER Monster.lookAt in tick, a @Shadow of vanilla's mob. */
	private static ClassNode debugifyShaped(Integer ordinal) {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT;
		mixin.name = "dev/example/RangedBowAttackGoalMixin";
		mixin.superName = "java/lang/Object";
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(GOAL)))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(type));
		FieldNode shadow = new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "mob", "Lnet/minecraft/world/entity/monster/Monster;", null, null);
		shadow.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/Shadow;")));
		mixin.fields = new ArrayList<>(List.of(shadow));
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", MONSTER_LOOK, "shift",
				new String[] {"Lorg/spongepowered/asm/mixin/injection/At$Shift;", "AFTER"}));
		if (ordinal != null) at.values.addAll(List.of("ordinal", ordinal));
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of("tick")), "at", new ArrayList<>(List.of(at))));
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, "lookAtTarget", "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
		handler.visibleAnnotations = new ArrayList<>(List.of(inject));
		handler.instructions.add(new InsnNode(Opcodes.RETURN));
		handler.maxLocals = 2;
		mixin.methods = new ArrayList<>(List.of(handler));
		return mixin;
	}

	private static byte[] bytes(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * Both looks in the merged tick are Mob.lookAt; the archer's own is the second (the vehicle's is first, through Mob in
	 * vanilla too). The point moves there, the handler goes behind a guard on the field holding a Monster, and the verdict
	 * agrees — before the move it read the miss as PARTIAL, and Mixin's require=1 then stopped a strict launch.
	 */
	@Test void debugifysFixFollowsTheArchersOwnLookThroughTheWidenedField() throws Exception {
		Function<String, byte[]> merged = game(false);
		assertEquals(MixinFit.Verdict.FIT, MixinFit.evaluate(bytes(debugifyShaped(null)), merged).verdict());
		ClassNode mixin = debugifyShaped(null);
		assertEquals(1, MixinSubtypeOwnerRetarget.adapt(mixin, nodes(merged)));
		MethodNode guard = StagedFabricMixinFixture.method(mixin, "lookAtTarget");
		AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(guard)).getFirst();
		assertEquals(MOB_LOOK, MixinFit.value(at, "target"));
		assertEquals(1, MixinFit.value(at, "ordinal"));
		List<String> code = new ArrayList<>();
		for (AbstractInsnNode insn : guard.instructions) {
			if (insn instanceof FieldInsnNode f) code.add("getfield " + f.owner + "." + f.name + ":" + f.desc);
			if (insn instanceof TypeInsnNode t) code.add("instanceof " + t.desc);
			if (insn instanceof MethodInsnNode m) code.add("call " + m.name);
		}
		assertEquals(List.of("getfield dev/example/RangedBowAttackGoalMixin.mob:Lnet/minecraft/world/entity/Mob;",
				"instanceof net/minecraft/world/entity/monster/Monster", "call " + MixinHandlerShim.asideName(mixin.name, "lookAtTarget",
						MixinSubtypeOwnerRetarget.NARROW_SUFFIX)), code);
		assertNull(MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin, MixinHandlerShim.asideName(mixin.name, "lookAtTarget",
				MixinSubtypeOwnerRetarget.NARROW_SUFFIX))));

		// The mod's own ordinal 0 is vanilla's first Monster.lookAt: still the archer's, the second Mob.lookAt.
		ClassNode first = debugifyShaped(0);
		assertEquals(1, MixinSubtypeOwnerRetarget.adapt(first, nodes(merged)));
		assertEquals(1, MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(StagedFabricMixinFixture.method(first, "lookAtTarget"))).getFirst(), "ordinal"));
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(debugifyShaped(1), nodes(merged)), "vanilla had one Monster.lookAt: ordinal 1 missed natively");
	}

	/** RED control: with the rule off the point stays as compiled, and the verdict names the miss Mixin will meet. */
	@Test void withTheRuleOffTheArchersLookIsTheMissItWas() throws Exception {
		System.setProperty(MixinSubtypeOwnerRetarget.RETYPED_FIELD_PROPERTY, "off");
		Function<String, byte[]> merged = game(false);
		ClassNode mixin = debugifyShaped(null);
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(mixin, nodes(merged)));
		MixinFit.Result fit = MixinFit.evaluate(bytes(debugifyShaped(null)), merged);
		assertEquals(MixinFit.Verdict.PARTIAL, fit.verdict());
		assertEquals(List.of("@At(INVOKE) net.minecraft.world.entity.monster.Monster.lookAt in RangedBowAttackGoal.tick"), fit.unresolved());
	}

	@Test void vanillasGoalStillMakesTheCallItself() throws Exception {
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(debugifyShaped(null), nodes(game(true))));
	}

	/** The same name through another owner is the same call only when nothing between the two redeclares it. */
	@Test void aMethodTheNarrowTypeRedeclaresIsAnotherCall() {
		Map<String, ClassNode> world = new HashMap<>();
		world.put("a/Wide", type("a/Wide", "java/lang/Object", true));
		world.put("a/Middle", type("a/Middle", "a/Wide", false));
		world.put("a/Narrow", type("a/Narrow", "a/Middle", false));
		assertTrue(MixinSubtypeOwnerRetarget.sameMethod("a/Narrow", "a/Wide", "look", "()V", world::get));
		world.put("a/Middle", type("a/Middle", "a/Wide", true));
		assertFalse(MixinSubtypeOwnerRetarget.sameMethod("a/Narrow", "a/Wide", "look", "()V", world::get), "Middle overrides it");
		assertFalse(MixinSubtypeOwnerRetarget.sameMethod("a/Wide", "a/Narrow", "look", "()V", world::get), "not a supertype");
	}

	/**
	 * A receiver that is the field on one path and something else on another: which call vanilla named is unknowable, so
	 * nothing moves (a guess here would run the handler after a look it was never written for).
	 */
	@Test void aReceiverThatMayBeEitherDeclines() {
		ClassNode goal = new ClassNode();
		goal.version = Opcodes.V21;
		goal.access = Opcodes.ACC_PUBLIC;
		goal.name = GOAL;
		goal.superName = "java/lang/Object";
		goal.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "mob", "Lnet/minecraft/world/entity/Mob;", null, null));
		goal.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "other", "Lnet/minecraft/world/entity/Mob;", null, null));
		MethodNode tick = new MethodNode(Opcodes.ACC_PUBLIC, "tick", "(Z)V", null, null);
		LabelNode join = new LabelNode(), otherwise = new LabelNode();
		tick.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
		tick.instructions.add(new JumpInsnNode(Opcodes.IFEQ, otherwise));
		tick.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		tick.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, GOAL, "mob", "Lnet/minecraft/world/entity/Mob;"));
		tick.instructions.add(new JumpInsnNode(Opcodes.GOTO, join));
		tick.instructions.add(otherwise);
		tick.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		tick.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, GOAL, "other", "Lnet/minecraft/world/entity/Mob;"));
		tick.instructions.add(join);
		tick.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
		tick.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
		tick.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
		tick.instructions.add(new InsnNode(Opcodes.FCONST_0));
		tick.instructions.add(new InsnNode(Opcodes.FCONST_0));
		tick.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/entity/Mob", "lookAt", "(Lnet/minecraft/world/entity/Entity;FF)V", false));
		tick.instructions.add(new InsnNode(Opcodes.RETURN));
		tick.maxStack = 4;
		tick.maxLocals = 3;
		goal.methods.add(tick);
		Map<String, ClassNode> world = new HashMap<>();
		world.put(GOAL, goal);
		world.put("net/minecraft/world/entity/Mob", type("net/minecraft/world/entity/Mob", "java/lang/Object", false));
		world.get("net/minecraft/world/entity/Mob").methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "lookAt", "(Lnet/minecraft/world/entity/Entity;FF)V", null, null));
		world.put("net/minecraft/world/entity/monster/Monster", type("net/minecraft/world/entity/monster/Monster", "net/minecraft/world/entity/Mob", false));
		assertEquals(0, MixinSubtypeOwnerRetarget.adapt(debugifyShaped(null), world::get));
		// …and the same body with the field on both paths moves.
		((FieldInsnNode) tick.instructions.get(7)).name = "mob";
		assertEquals(1, MixinSubtypeOwnerRetarget.adapt(debugifyShaped(null), world::get));
	}

	private static ClassNode type(String name, String superName, boolean declaresLook) {
		ClassNode node = new ClassNode();
		node.name = name;
		node.superName = superName;
		if (declaresLook) node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "look", "()V", null, null));
		return node;
	}
}

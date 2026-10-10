/* Copyright 2026 The NeoForbric Project. Licensed under the Apache License, Version 2.0. */
package net.neoforbric.kernel.transform;

import net.neoforbric.kernel.util.NeoForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * NeoForge's hopper asks Fabric's item storage lookup where its own found nothing.
 *
 * <p>fabric-transfer-api-v1's {@code HopperBlockEntityMixin} anchors after vanilla's {@code getAttachedContainer} and
 * {@code getSourceContainer}; 1.21.1's NeoForge hopper still calls both — but before them it asks
 * {@code VanillaInventoryCodeHooks.insertHook}/{@code extractHook} and, when those answer handled, never reaches the
 * vanilla lookups where Fabric's mixin sits. A Fabric storage NeoForge's item capability cannot see answers the hook
 * empty, so the vanilla path runs — and there Fabric's own mixin binds, which covers blocks WITH a Fabric inventory
 * container. What stays unseen either way: unslotted Fabric storages, storages on blocks without a block entity, and
 * every storage when the transfer bridge is off. Two edits, both at the hooks' "declined" jumps:
 * <ul>
 * <li>{@code ejectItems}: {@code insertHook}'s {@code IFEQ} to the vanilla path first asks
 * {@code KernelFabricHopperStorage.insert(level, pos, hopper)}; a Fabric storage's answer is returned, false falls to
 * vanilla as before;</li>
 * <li>{@code suckInItems}: {@code extractHook}'s {@code IFNULL} to the vanilla path first asks
 * {@code KernelFabricHopperStorage.extract(level, hopper)}; 1 or 0 is returned as the hopper's answer — so it does not
 * pick up item entities, as natively — and {@code NOT_FOUND} falls to vanilla as before.</li>
 * </ul>
 * No local, field read, or call to a hopper method is added, so INVOKE and FIELD anchors on those, and their ordinals,
 * are unchanged (lithium's among them). RETURN/JUMP/LOAD ordinals after each hook shift by a few — no mod in any pack
 * uses them here. Both edits or neither; a body without both hooks (vanilla's, or a carrier's where Fabric's own mixin
 * applies) is left alone. {@code -Dneoforbric.hopperFabricStorage=off}.
 */
public final class HopperFabricStorageInjector implements ClassTransformer {
	public static final String PROPERTY = "neoforbric.hopperFabricStorage";
	static final String HOPPER = "net.minecraft.world.level.block.entity.HopperBlockEntity";
	static final String HOPPER_INTERNAL = "net/minecraft/world/level/block/entity/HopperBlockEntity";
	static final String RUNTIME = "net/neoforbric/kernel/runtime/transfer/KernelFabricHopperStorage";
	static final String HOOKS = "net/neoforged/neoforge/items/VanillaInventoryCodeHooks";

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "neoforbric:hopper-fabric-storage"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("hoppers left as NeoForge ships them with -D" + PROPERTY + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(HOPPER, AnchorSet.Severity.REQUIRED,
				"hoppers ignore Fabric item storages NeoForge cannot see — unslotted, block-only, or all of them with the "
						+ "transfer bridge off"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || !HOPPER.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		String declined = repair(node);
		if (declined != null) {
			if (!declined.isEmpty()) {
				NeoForbricLog.warn("[NeoForbric/Hopper] left HopperBlockEntity as NeoForge ships it: %s — hoppers ignore Fabric storages "
						+ "NeoForge cannot see", declined);
			}
			return bytes;
		}
		// COMPUTE_FRAMES: the edits add branch targets whose frames and stack maxima must be derived, and
		// javac's own frames no longer describe the reshaped control flow. The getCommonSuperClass fallback
		// covers a merge of types this transform-time loader cannot load (the unit tests' stand-ins, or a
		// boot-order class): such a merge degrades to Object, which every reference type satisfies.
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
			@Override protected String getCommonSuperClass(String type1, String type2) {
				try {
					return super.getCommonSuperClass(type1, type2);
				} catch (RuntimeException unavailable) {
					return "java/lang/Object";
				}
			}
		};
		node.accept(writer);
		NeoForbricLog.info("[NeoForbric/Hopper] HopperBlockEntity's two VanillaInventoryCodeHooks declines now ask Fabric's "
				+ "item storage lookup first (a Fabric storage NeoForge's item capability cannot see never reached "
				+ "fabric-transfer-api-v1's HopperBlockEntityMixin)");
		return writer.toByteArray();
	}

	/** Null when both edits were made; "" when there is nothing to do (a carrier's own body, or already done); else why not. */
	static String repair(ClassNode hopper) {
		MethodNode eject = method(hopper, "ejectItems", "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;L" + HOPPER_INTERNAL + ";)Z");
		MethodNode suck = method(hopper, "suckInItems", "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/entity/Hopper;)Z");
		if (eject == null || suck == null) return "no ejectItems/suckInItems";
		if (calls(eject, RUNTIME, null) + calls(suck, RUNTIME, null) > 0) return "";
		if (calls(eject, HOOKS, "insertHook") != 1) return "ejectItems does not ask NeoForge's insert hook exactly once";
		if (calls(suck, HOOKS, "extractHook") != 1) return "suckInItems does not ask NeoForge's extract hook exactly once";
		// ejectItems: aload hopper; insertHook; IFEQ vanilla; iconst_1; ireturn — false ("not handled") jumps to the
		// vanilla lookups. The fabric ask takes that jump: it is asked only when NeoForge's hook declined, and its own
		// false answer falls to the vanilla lookups exactly where the original jump went.
		JumpInsnNode insertDeclined = null;
		for (AbstractInsnNode insn : eject.instructions) {
			if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(HOOKS) || !call.name.equals("insertHook")) continue;
			if (!(next(call) instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFEQ) continue;
			if (insertDeclined != null) return "ejectItems has two insert-hook declines";
			insertDeclined = jump;
		}
		if (insertDeclined == null) return "ejectItems has no insert-hook decline";

		// The ask goes BEFORE the vanilla path's label, not after the decline jump: between the jump and
		// the label lies the hook's own handled fallthrough (iconst_1; ireturn), which must stay live — an
		// insertion after the jump would capture the handled path too, replacing NeoForge's answer with
		// Fabric's and leaving the displaced fallthrough as dead code the JVM refuses for want of a frame.
		LabelNode insertVanilla = insertDeclined.label;
		LabelNode insertAsk = new LabelNode();
		InsnList ask = new InsnList();
		ask.add(insertAsk);
		ask.add(new VarInsnNode(Opcodes.ALOAD, 0));
		ask.add(new VarInsnNode(Opcodes.ALOAD, 1));
		ask.add(new VarInsnNode(Opcodes.ALOAD, 2));
		ask.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "insert", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z", false));
		ask.add(new JumpInsnNode(Opcodes.IFEQ, insertVanilla));
		ask.add(new InsnNode(Opcodes.ICONST_1));
		ask.add(new InsnNode(Opcodes.IRETURN));
		eject.instructions.insertBefore(insertVanilla, ask);
		insertDeclined.label = insertAsk;

		// suckInItems: extractHook; astore; aload; ifnull vanilla — null ("not handled") jumps to the vanilla
		// lookups. The fabric ask takes that jump the same way: NOT_FOUND (-1) falls to vanilla, and 1 or 0 is the
		// hopper's whole answer, so a Fabric storage that has nothing still stops the item-entity pickup, as the
		// native hook's answer would have.
		JumpInsnNode extractDeclined = null;
		for (AbstractInsnNode insn : suck.instructions) {
			if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(HOOKS) || !call.name.equals("extractHook")) continue;
			AbstractInsnNode store = next(call);
			AbstractInsnNode load = store == null ? null : next(store);
			if (store == null || store.getOpcode() != Opcodes.ASTORE || load == null || load.getOpcode() != Opcodes.ALOAD) continue;
			if (!(next(load) instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFNULL) continue;
			if (extractDeclined != null) return "suckInItems has two extract-hook declines";
			extractDeclined = jump;
		}
		if (extractDeclined == null) return "suckInItems has no extract-hook decline";

		// Same placement rule as ejectItems: before the vanilla label, past the handled fallthrough
		// (aload pulled; booleanValue; ireturn) the insertion must not displace.
		LabelNode extractVanilla = extractDeclined.label;
		LabelNode extractAsk = new LabelNode();
		InsnList ask2 = new InsnList();
		ask2.add(extractAsk);
		ask2.add(new VarInsnNode(Opcodes.ALOAD, 0));
		ask2.add(new VarInsnNode(Opcodes.ALOAD, 1));
		ask2.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "extract", "(Ljava/lang/Object;Ljava/lang/Object;)I", false));
		// DUP + IFLT + IRETURN: the branch pops one copy, the fallthrough returns the other. A branch
		// straight to the vanilla path would enter it with the leftover copy on the stack — the exact
		// corruption the verifier caught — so the declined branch first pops its copy, then falls into
		// the vanilla path with the empty stack it was promised.
		LabelNode extractPop = new LabelNode();
		ask2.add(new InsnNode(Opcodes.DUP));
		ask2.add(new JumpInsnNode(Opcodes.IFLT, extractPop));
		ask2.add(new InsnNode(Opcodes.IRETURN));
		ask2.add(extractPop);
		ask2.add(new InsnNode(Opcodes.POP));
		// No GOTO: the pop sits directly before the vanilla label, so the declined path falls into it.
		suck.instructions.insertBefore(extractVanilla, ask2);
		extractDeclined.label = extractAsk;
		return null;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}

	private static int calls(MethodNode method, String owner, String name) {
		int n = 0;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && (name == null || call.name.equals(name))) n++;
		}
		return n;
	}

	private static AbstractInsnNode next(AbstractInsnNode insn) {
		AbstractInsnNode next = insn == null ? null : insn.getNext();
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		return next;
	}
}

/*
 * Copyright 2026 The NeoForbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.neoforbric.kernel.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.neoforbric.kernel.util.NeoForbricLog;

/**
 * At every normal completed server tick, outside event dispatch and save callbacks.
 *
 * <p>Splices {@code KernelServerCompatibilityTicks.onCompatibilityTick(server)} immediately before every
 * plain {@code RETURN} of {@code MinecraftServer.tickServer(BooleanSupplier)} — the same seam shape the
 * 26.x weld used, re-derived on this base for the same reason {@link ServerTickSamplerInjector} is a
 * COREMOD transform and not a mixin: the chain already owns this class, so there is no mixin config to
 * register and nothing new in the config list a gate asserts on. The hook the splice reaches is
 * game-side; it hands the server to {@code LateServerCompatibility.tick}, which decides whether a
 * late required loss must stop a strict dedicated server through normal saving.
 *
 * <p>Insert-before, never hand-rolled frames: the splice asks only for the receiver on the operand stack,
 * every local and frame beneath it is the method's own, and {@code COMPUTE_MAXS} covers the one-slot
 * difference.
 */
public final class ServerCompatibilityTickInjector implements ClassTransformer {
	private static final String TARGET = "net.minecraft.server.MinecraftServer";
	private static final String OWNER = "net/neoforbric/kernel/runtime/KernelServerCompatibilityTicks";
	private static final String HOOK = "onCompatibilityTick";

	@Override public String name() { return "neoforbric-late-server-compatibility"; }

	@Override public AnchorSet anchors() {
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"confirmed required losses discovered after startup must stop a strict dedicated server through normal saving"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!TARGET.equals(className) || bytes == null || bytes.length == 0) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		boolean changed = false;
		for (MethodNode method : node.methods) {
			if (!method.name.equals("tickServer") || !method.desc.equals("(Ljava/util/function/BooleanSupplier;)V")) continue;
			for (AbstractInsnNode instruction : method.instructions) {
				if (instruction instanceof MethodInsnNode call && call.owner.equals(OWNER) && call.name.equals(HOOK)) return bytes;
			}
			for (AbstractInsnNode instruction : method.instructions.toArray()) {
				if (instruction.getOpcode() != Opcodes.RETURN) continue;
				InsnList hook = new InsnList();
				hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
				hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, HOOK, "(Ljava/lang/Object;)V", false));
				method.instructions.insertBefore(instruction, hook);
				changed = true;
			}
			method.maxStack = Math.max(method.maxStack, 1);
		}
		if (!changed) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		NeoForbricLog.debug("[NeoForbric/Tick] server completed-tick compatibility check spliced into MinecraftServer.tickServer");
		return writer.toByteArray();
	}
}

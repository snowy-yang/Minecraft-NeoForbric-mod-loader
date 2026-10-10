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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.neoforbric.kernel.TestFixtures;

/**
 * {@link ServerCompatibilityTickInjector}'s splice on the actual 1.21.1 base: every plain return of
 * {@code MinecraftServer.tickServer} gains one completed-tick check, a second pass changes nothing, and
 * an unrelated class is passed through untouched.
 */
class ServerCompatibilityTickInjectorTest {
	private static final String SERVER = "net.minecraft.server.MinecraftServer";
	private static final String BASE = "neoforge-base/patched-mc-neoforge-1.21.1.jar";

	@Test
	void everyNormalReturnInTheActualServerHasOneCheckAndSecondPassDoesNothing() throws Exception {
		byte[] bytes = TestFixtures.stagedClass(BASE, SERVER);
		var injector = new ServerCompatibilityTickInjector();
		byte[] transformed = injector.transform(SERVER, bytes, null);
		assertNotSame(bytes, transformed);
		assertSame(transformed, injector.transform(SERVER, transformed, null), "a second pass changes nothing");
		ClassNode node = new ClassNode();
		new ClassReader(transformed).accept(node, 0);
		int returns = 0, checks = 0;
		for (MethodNode m : node.methods) {
			if (!m.name.equals("tickServer") || !m.desc.equals("(Ljava/util/function/BooleanSupplier;)V")) continue;
			for (AbstractInsnNode i : m.instructions) {
				if (i.getOpcode() == Opcodes.RETURN) {
					returns++;
					assertTrue(i.getPrevious() instanceof MethodInsnNode call && call.name.equals("onCompatibilityTick"),
							"every plain return is preceded by the completed-tick check");
				}
				if (i instanceof MethodInsnNode call && call.name.equals("onCompatibilityTick")) checks++;
			}
		}
		assertTrue(returns > 0, "tickServer has at least one plain return on this base");
		assertEquals(returns, checks);
		assertSame(bytes, injector.transform("net.minecraft.client.Minecraft", bytes, null),
				"a class the injector does not target is returned as it came");
	}
}

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

package net.neoforbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Whether the audit may call NeoForge's ItemTooltipEvent dead, pinned on the staged merged base.
 *
 * <p>What this was written for: on the 26.x merged base {@code ItemStack.getTooltipLines} posted MinecraftForge's
 * {@code ForgeEventFactory.onItemTooltip} and none of NeoForge's, so NeoForge's event had no call site at all and
 * every mod subscribing to it was listed by {@code DeadHookWorklist} as waiting on a dead event. The test pinned
 * that premise: if a merge or a repair brought NeoForge's event back, it went red in the over-report-safe
 * direction and the row was deleted.
 *
 * <p>RE-ANCHORED to the NeoForge-only base, where the premise is inverted: there is no {@code net/minecraftforge}
 * anywhere, and {@code getTooltipLines} posts NeoForge's {@code EventHooks.onItemTooltip}. The event is therefore
 * LIVE, the audit must not report it, and the same assertion now guards the opposite mistake — a row claiming a
 * dead event that a mod's tooltip listener does actually receive.
 */
class DeadEventAuditStagedTest {
	@Test
	void theMergedItemStackPostsNeoForgesTooltipEventAndMinecraftForgeIsGone() throws Exception {
		Path merged = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar").normalize();
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(merged), "staged merged base absent");
		ClassNode node = new ClassNode();
		try (ZipFile zip = new ZipFile(merged.toFile())) {
			ZipEntry entry = zip.getEntry("net/minecraft/world/item/ItemStack.class");
			assertNotNull(entry, "ItemStack is missing from the staged merged base " + merged);
			try (InputStream in = zip.getInputStream(entry)) {
				new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			}
		}
		boolean forge = false, neo = false, found = false;
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call)) continue;
				if (call.owner.startsWith("net/minecraftforge/")) forge = true;
				if (!m.name.equals("getTooltipLines")) continue;
				if ("net/neoforged/neoforge/event/EventHooks".equals(call.owner) && "onItemTooltip".equals(call.name)) neo = true;
			}
			if (m.name.equals("getTooltipLines")) found = true;
		}
		assertTrue(found, "ItemStack.getTooltipLines is gone");
		assertTrue(neo, "NeoForge's EventHooks.onItemTooltip is the poster on this base, so the audit must not list "
				+ "NeoForge's ItemTooltipEvent as dead — a mod's tooltip listener does receive it");
		assertFalse(forge, "a net/minecraftforge reference survived into this base — MinecraftForge is not part of it");
	}
}

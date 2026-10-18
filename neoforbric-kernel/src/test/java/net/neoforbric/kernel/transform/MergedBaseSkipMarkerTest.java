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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;

/**
 * The merged {@code SimpleJsonResourceReloadListener}'s reader, against the REAL staged base.
 *
 * <p>Re-anchored to 1.21.1. On 26.x this guarded a repair for fabric-api's half-applied condition mixin: the
 * producer half returns a bare {@code Object} "skip this file" sentinel from a {@code @WrapOperation} on
 * {@code Codec.parse}, the consumer half recognises it, and where the merged class carried two
 * {@code lambda$scanDirectory$0} overloads the live {@code invokedynamic} bound the one the consumer did not
 * target — so the marker reached {@code DataResult.ifSuccess} and died casting it to {@code Optional}.
 *
 * <p>On this anchor the whole shape is gone: 1.21.1's reader parses each file with
 * {@code GsonHelper.fromJson(Reader)} into a {@code JsonElement} and never goes through {@code Codec} or
 * {@code DataResult} at all, there is one {@code scanDirectory} and no {@code scanDirectoryWithModifier}, and no
 * {@code lambda$scanDirectory$0}. {@code NeoForbricMergedBaseCompatTransformer} still carries
 * {@code translateAGuestsPrivateSkipMarker}, but its anchor — a {@code DataResult.ifSuccess} call inside those two
 * readers — does not exist here, so it correctly declines. What this test now pins is that absence: a future base
 * that brings the {@code Codec}/{@code DataResult} reader back fails here and forces the repair to be re-derived.
 */
class MergedBaseSkipMarkerTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
	private static final String ENTRY = "net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener.class";
	private static final String BINARY = "net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener";

	@Test
	void theReaderParsesJsonDirectlySoThereIsNoMarkerToTranslate() throws Exception {
		ClassNode node = original();

		MethodNode scan = method(node, "scanDirectory");
		boolean gson = false;
		for (AbstractInsnNode insn : scan.instructions) {
			if (insn instanceof MethodInsnNode call && "fromJson".equals(call.name)
					&& "net/minecraft/util/GsonHelper".equals(call.owner)) gson = true;
			assertFalse(insn instanceof MethodInsnNode call && "ifSuccess".equals(call.name),
					"scanDirectory must not reach DataResult.ifSuccess — this base parses JsonElement directly");
		}
		assertTrue(gson, "the reader must parse each file with GsonHelper.fromJson, the 1.21.1 shape");
	}

	@Test
	void noReaderReachesDataResultAtAll() throws Exception {
		ClassNode node = original();
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn : method.instructions) {
				assertFalse(insn instanceof MethodInsnNode call && "ifSuccess".equals(call.name),
						method.name + " reaches DataResult.ifSuccess directly, so the repair's anchor is back and "
								+ "this test must be re-derived");
			}
		}
	}

	@Test
	void theTwoLambdaShapeThatMadeThisNecessaryIsGone() throws Exception {
		// The premise, asserted so the repair can be retired deliberately rather than left as dead weight. On 26.x
		// fabric's @Inject targeted the (…,Object) overload while the live invokedynamic bound the (…,Optional)
		// one. This base has neither.
		ClassNode node = original();

		for (MethodNode method : node.methods) {
			assertFalse(method.name.startsWith("lambda$scanDirectory$0"),
					"content drift: the two-lambda shape is back — re-derive whether fabric-api's condition mixin "
							+ "now applies whole, and whether this repair is needed again");
		}
	}

	@Test
	void theTransformerDeclinesAndASecondPassChangesNothing() throws Exception {
		byte[] in = bytes();
		byte[] once = new NeoForbricMergedBaseCompatTransformer().transform(BINARY, in, null);
		assertSame(in, once, "no DataResult.ifSuccess exists here, so the skip-marker repair must be a no-op");
		assertSame(once, new NeoForbricMergedBaseCompatTransformer().transform(BINARY, once, null));
	}

	private static ClassNode original() throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(bytes()).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name) {
		for (MethodNode m : node.methods) {
			if (name.equals(m.name) && (m.access & Opcodes.ACC_STATIC) != 0) return m;
		}
		throw new AssertionError("no static " + name + " in " + node.name);
	}

	private static byte[] bytes() throws Exception {
		return TestFixtures.requireEntry(Fixture.STAGED, MERGED_BASE, ENTRY);
	}
}

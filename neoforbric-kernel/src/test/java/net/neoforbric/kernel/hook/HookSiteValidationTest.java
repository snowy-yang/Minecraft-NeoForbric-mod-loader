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

package net.neoforbric.kernel.hook;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * A key that cannot mean anything is refused where it is written, not at the first dispatch.
 *
 * <p>The failure this prevents is the quiet one: a seam spelled with the dotted class name, or with {@code V} in its
 * parameter list, binds to nothing, dispatches nothing, and looks exactly like a seam that works until someone
 * reads the log for another reason. Every rule asserted here is one the weaver would otherwise have to guess at.
 */
class HookSiteValidationTest {
	private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	private static final String HURT = "(Lnet/minecraft/world/damagesource/DamageSource;F)Z";

	@Test
	void aWellFormedSiteIsAccepted() {
		HookSite site = new HookSite(LIVING, "hurt", HURT);
		assertEquals(LIVING + "#hurt" + HURT, site.canonical());
	}

	@Test
	void theDottedClassNameIsRejectedRatherThanNormalised() {
		IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
				() -> new HookSite("net.minecraft.world.entity.LivingEntity", "hurt", HURT));
		assertEquals(true, thrown.getMessage().contains("internal names use '/'"), thrown.getMessage());
	}

	@Test
	void anOwnerThatIsEmptyOrHasAnEmptySegmentIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> new HookSite("", "hurt", HURT));
		assertThrows(IllegalArgumentException.class, () -> new HookSite("/net/minecraft/A", "hurt", HURT));
		assertThrows(IllegalArgumentException.class, () -> new HookSite("net/minecraft/A/", "hurt", HURT));
		assertThrows(IllegalArgumentException.class, () -> new HookSite("net//minecraft/A", "hurt", HURT));
	}

	@Test
	void aDescriptorThatIsNotAMethodDescriptorIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> new HookSite(LIVING, "hurt", "Z"));
		assertThrows(IllegalArgumentException.class, () -> new HookSite(LIVING, "hurt", "(FZ"));
		assertThrows(IllegalArgumentException.class, () -> new HookSite(LIVING, "hurt", "(F)"));
		assertThrows(IllegalArgumentException.class, () -> new HookSite(LIVING, "hurt", "(F)ZZ"));
		assertThrows(IllegalArgumentException.class, () -> new HookSite(LIVING, "hurt", "(Lnet/minecraft/A)Z"));
	}

	@Test
	void voidIsLegalAsAReturnTypeAndIllegalAsAParameter() {
		assertDoesNotThrow(() -> new HookSite(LIVING, "tick", "()V"));
		IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
				() -> new HookSite(LIVING, "tick", "(V)V"));
		assertEquals(true, thrown.getMessage().contains("as a parameter type"), thrown.getMessage());
	}

	@Test
	void arraysAndEveryPrimitiveTypeParse() {
		assertDoesNotThrow(() -> new HookSite(LIVING, "f", "([[I[J[F[D[B[C[S[Z[Lnet/minecraft/A;)V"));
		assertThrows(IllegalArgumentException.class, () -> new HookSite(LIVING, "f", "([V)V"));
	}

	@Test
	void theConstructorNamesAreMethodNames() {
		assertDoesNotThrow(() -> new HookSite(LIVING, "<init>", "(Lnet/minecraft/world/level/Level;)V"));
		assertDoesNotThrow(() -> new HookSite(LIVING, "<clinit>", "()V"));
		assertThrows(IllegalArgumentException.class, () -> new HookSite(LIVING, "a.b", "()V"));
	}
}

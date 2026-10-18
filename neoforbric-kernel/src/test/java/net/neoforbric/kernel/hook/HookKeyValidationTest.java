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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * The point and the target have to agree with each other, or the key names a place that does not exist.
 *
 * <p>{@code HEAD} has exactly one site, so an ordinal is a mistake rather than a narrower binding; {@code INVOKE}
 * and {@code FIELD} are meaningless without the member they observe. The factories exist so a caller writes the
 * legal combination and never meets the canonical constructor's checks.
 */
class HookKeyValidationTest {
	private static final HookSite SITE = new HookSite("net/minecraft/world/entity/Player", "attack",
			"(Lnet/minecraft/world/entity/Entity;)V");
	private static final HookTarget TARGET = new HookTarget("net/minecraft/world/entity/Entity", "hurt",
			"(Lnet/minecraft/world/damagesource/DamageSource;F)Z");

	@Test
	void headTakesNoTargetAndNoOrdinal() {
		HookKey key = HookKey.head(SITE);
		assertNull(key.target());
		assertEquals(-1, key.ordinal());
		assertThrows(IllegalArgumentException.class, () -> new HookKey(SITE, HookPoint.HEAD, null, 0));
		assertThrows(IllegalArgumentException.class, () -> new HookKey(SITE, HookPoint.HEAD, TARGET, -1));
	}

	@Test
	void invokeAndFieldRequireATarget() {
		assertThrows(IllegalArgumentException.class, () -> new HookKey(SITE, HookPoint.INVOKE, null, -1));
		assertThrows(IllegalArgumentException.class, () -> new HookKey(SITE, HookPoint.FIELD, null, -1));
		assertNotNull(HookKey.invoke(SITE, TARGET, -1).target());
		assertNotNull(HookKey.field(SITE, TARGET, 2).target());
	}

	@Test
	void returnTakesAnOrdinalButNoTarget() {
		assertDoesNotThrow(() -> HookKey.returns(SITE, -1));
		assertDoesNotThrow(() -> HookKey.returns(SITE, 3));
		assertThrows(IllegalArgumentException.class, () -> new HookKey(SITE, HookPoint.RETURN, TARGET, -1));
	}

	@Test
	void anOrdinalBelowMinusOneIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> new HookKey(SITE, HookPoint.RETURN, null, -2));
	}

	@Test
	void theCanonicalFormNamesEveryComponentThatDistinguishesTwoKeys() {
		assertEquals(SITE.canonical() + "/HEAD#-1", HookKey.head(SITE).canonical());
		assertEquals(SITE.canonical() + "/INVOKE@" + TARGET.canonical() + "#2",
				HookKey.invoke(SITE, TARGET, 2).canonical());
		assertEquals(true, !HookKey.returns(SITE, 0).canonical().equals(HookKey.returns(SITE, 1).canonical()));
	}
}

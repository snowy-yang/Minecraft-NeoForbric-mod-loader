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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code int} the woven bytecode carries, and what happens when two seams want the same one.
 *
 * <p>The id has to be stable across launches because it is the only thing in a crash report that names which seam a
 * frame came from — an id derived from a counter would renumber itself when the registration order changed. A
 * collision is refused rather than resolved: two seams sharing an id would deliver one seam's calls to the other's
 * bodies, and nothing downstream could tell that apart from working code.
 */
class HookDispatchTest {
	private static final HookSite SITE = new HookSite("net/minecraft/server/level/ServerPlayer", "tick", "()V");

	@BeforeEach
	@AfterEach
	void resetDispatch() {
		HookDispatch.reset();
	}

	@Test
	void bindingTheSameKeyTwiceYieldsTheSameId() {
		HookKey key = HookKey.head(SITE);
		int first = HookDispatch.bind(key);
		int second = HookDispatch.bind(key);

		assertEquals(first, second);
		assertEquals(key, HookDispatch.keyOf(first));
	}

	@Test
	void theIdIsTheCanonicalHashSoItSurvivesARestart() {
		HookKey key = HookKey.head(SITE);

		assertEquals(key.canonical().hashCode(), HookDispatch.bind(key));
	}

	@Test
	void distinctSeamsGetDistinctIds() {
		List<Integer> ids = new ArrayList<>();
		ids.add(HookDispatch.bind(HookKey.head(SITE)));
		ids.add(HookDispatch.bind(HookKey.returns(SITE, -1)));
		ids.add(HookDispatch.bind(HookKey.returns(SITE, 0)));
		ids.add(HookDispatch.bind(HookKey.invoke(SITE, new HookTarget("net/minecraft/world/entity/Entity", "hurt",
				"(Lnet/minecraft/world/damagesource/DamageSource;F)Z"), -1)));

		assertEquals(4, ids.stream().distinct().count(), ids.toString());
	}

	@Test
	void anIdNobodyBoundContinuesAndNamesNothing() {
		HookResult result = HookDispatch.dispatch(Integer.MIN_VALUE, null, new Object[0]);

		assertInstanceOf(HookResult.Continue.class, result);
		assertNull(HookDispatch.keyOf(Integer.MIN_VALUE));
	}

	@Test
	void aWovenCallReachesTheSubscribedBody() {
		HookKey key = HookKey.head(SITE);
		int id = HookDispatch.bind(key);
		Object self = new Object();
		List<Object> seen = new ArrayList<>();
		HookDispatch.bus().subscribe(key, 0, call -> {
			seen.add(call.self());
			seen.add(call.arg(0));
			return HookResult.cancel("stopped");
		}, "test");

		HookResult result = HookDispatch.dispatch(id, self, new Object[] { 7 });

		assertEquals("stopped", assertInstanceOf(HookResult.Cancel.class, result).value());
		assertEquals(List.of(self, 7), seen);
	}

	@Test
	void aHashCollisionBetweenTwoSeamsIsRefusedRatherThanResolved() {
		// "Aa" and "BB" are the standard pair with equal String.hashCode. Both sit at the same offset of the
		// canonical form, so the two canonical strings hash equal while naming different seams.
		HookKey first = HookKey.head(new HookSite("x/Aa", "m", "()V"));
		HookKey second = HookKey.head(new HookSite("x/BB", "m", "()V"));
		assertEquals(first.canonical().hashCode(), second.canonical().hashCode(),
				"the pair must actually collide for this test to mean anything");

		HookDispatch.bind(first);
		IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> HookDispatch.bind(second));

		assertTrue(thrown.getMessage().contains("is claimed by both"), thrown.getMessage());
		assertEquals(first, HookDispatch.keyOf(first.canonical().hashCode()));
	}

	@Test
	void resetForgetsBothTheIdsAndTheBindings() {
		HookKey key = HookKey.head(SITE);
		int id = HookDispatch.bind(key);
		HookDispatch.bus().subscribe(key, 0, call -> HookResult.cancel(1), "test");

		HookDispatch.reset();

		assertNull(HookDispatch.keyOf(id));
		assertEquals(0, HookDispatch.bus().bodies(key).size());
	}
}

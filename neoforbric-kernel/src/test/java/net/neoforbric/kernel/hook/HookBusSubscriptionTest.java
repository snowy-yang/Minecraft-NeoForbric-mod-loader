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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Subscribing twice, and unsubscribing.
 *
 * <p>Idempotence is by instance, not by name: a bridge that runs its setup twice — because two mods pulled the same
 * module in, or because a launch retried — must not end up with its observer called twice per tick, and there is no
 * name to key that on. Unsubscribing something that is not bound is not an error, because the caller that wants to
 * be sure a binding is gone cannot know whether it was ever there.
 */
class HookBusSubscriptionTest {
	private static final HookSite SITE = new HookSite("net/minecraft/world/level/Level", "tick", "()V");

	private HookBus bus;
	private HookKey key;

	@BeforeEach
	void setUp() {
		bus = new HookBus();
		key = HookKey.head(SITE);
	}

	@Test
	void theSameInstanceOnTheSameKeyRunsOnce() {
		List<String> order = new ArrayList<>();
		HookBody body = call -> {
			order.add("call");
			return HookResult.CONTINUE;
		};
		HookBus.Subscription first = bus.subscribe(key, 0, body, "a");
		HookBus.Subscription second = bus.subscribe(key, 0, body, "a");

		assertSame(first.body(), second.body());
		bus.dispatch(key, null, new Object[0]);

		assertEquals(List.of("call"), order);
		assertEquals(1, bus.bodies(key).size());
	}

	@Test
	void twoInstancesOnTheSameKeyBothRun() {
		List<String> order = new ArrayList<>();
		bus.subscribe(key, 0, call -> {
			order.add("one");
			return HookResult.CONTINUE;
		}, "a");
		bus.subscribe(key, 0, call -> {
			order.add("two");
			return HookResult.CONTINUE;
		}, "b");

		bus.dispatch(key, null, new Object[0]);

		assertEquals(List.of("one", "two"), order);
	}

	@Test
	void theSameInstanceOnTwoKeysRunsOnBoth() {
		HookKey other = HookKey.returns(SITE, -1);
		List<String> order = new ArrayList<>();
		HookBody body = call -> {
			order.add(call.key().point().name());
			return HookResult.CONTINUE;
		};
		bus.subscribe(key, 0, body, "a");
		bus.subscribe(other, 0, body, "a");

		bus.dispatch(key, null, new Object[0]);
		bus.dispatch(other, null, new Object[0]);

		assertEquals(List.of("HEAD", "RETURN"), order);
	}

	@Test
	void unsubscribingStopsTheCalls() {
		List<String> order = new ArrayList<>();
		HookBody body = call -> {
			order.add("call");
			return HookResult.CONTINUE;
		};
		HookBus.Subscription subscription = bus.subscribe(key, 0, body, "a");

		assertTrue(bus.unsubscribe(subscription));
		bus.dispatch(key, null, new Object[0]);

		assertEquals(List.of(), order);
		assertEquals(0, bus.bodies(key).size());
		assertFalse(bus.keys().contains(key));
	}

	@Test
	void unsubscribingTwiceReportsFalseTheSecondTime() {
		HookBus.Subscription subscription = bus.subscribe(key, 0, call -> HookResult.CONTINUE, "a");

		assertTrue(bus.unsubscribe(subscription));
		assertFalse(bus.unsubscribe(subscription));
		assertFalse(bus.unsubscribe(null));
	}

	@Test
	void unsubscribingOneOfTwoLeavesTheOther() {
		List<String> order = new ArrayList<>();
		HookBody kept = call -> {
			order.add("kept");
			return HookResult.CONTINUE;
		};
		HookBus.Subscription dropped = bus.subscribe(key, 0, call -> {
			order.add("dropped");
			return HookResult.CONTINUE;
		}, "a");
		bus.subscribe(key, 0, kept, "b");

		bus.unsubscribe(dropped);
		bus.dispatch(key, null, new Object[0]);

		assertEquals(List.of("kept"), order);
	}

	@Test
	void clearDropsEveryBinding() {
		bus.subscribe(key, 0, call -> HookResult.CONTINUE, "a");
		bus.subscribe(HookKey.returns(SITE, -1), 0, call -> HookResult.CONTINUE, "b");

		bus.clear();

		assertEquals(0, bus.keys().size());
		assertEquals(0, bus.bodies(key).size());
	}

	@Test
	void aNullKeyOrBodyIsRejected() {
		assertThrowsIllegalArgument(() -> bus.subscribe(null, 0, call -> HookResult.CONTINUE, "a"));
		assertThrowsIllegalArgument(() -> bus.subscribe(key, 0, null, "a"));
	}

	private static void assertThrowsIllegalArgument(org.junit.jupiter.api.function.Executable call) {
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, call);
	}
}

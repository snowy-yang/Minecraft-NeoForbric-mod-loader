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
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Order, cancellation and the snapshot a running dispatch reads.
 *
 * <p>These are the three properties that make a shared hook layer safe: two ecosystems can both subscribe without
 * either being able to silence the other by accident, a cancel is honoured once rather than by whoever happens to
 * run first in an unordered list, and a body that subscribes while the bus is already dispatching cannot insert
 * itself into the call it is inside.
 */
class HookBusDispatchTest {
	private static final HookSite SITE = new HookSite("net/minecraft/world/entity/LivingEntity", "hurt",
			"(Lnet/minecraft/world/damagesource/DamageSource;F)Z");

	private HookBus bus;
	private HookKey key;

	@BeforeEach
	void setUp() {
		bus = new HookBus();
		key = HookKey.head(SITE);
	}

	/** A body that appends its name and continues. */
	private HookBody observing(List<String> order, String name) {
		return call -> {
			order.add(name);
			return HookResult.CONTINUE;
		};
	}

	@Test
	void higherPriorityRunsFirstWhateverTheRegistrationOrderWas() {
		List<String> order = new ArrayList<>();
		bus.subscribe(key, 0, observing(order, "low"), "a");
		bus.subscribe(key, 100, observing(order, "high"), "b");
		bus.subscribe(key, 50, observing(order, "mid"), "c");

		bus.dispatch(key, null, new Object[0]);

		assertEquals(List.of("high", "mid", "low"), order);
	}

	@Test
	void equalPriorityKeepsRegistrationOrder() {
		List<String> order = new ArrayList<>();
		bus.subscribe(key, 10, observing(order, "first"), "a");
		bus.subscribe(key, 10, observing(order, "second"), "b");
		bus.subscribe(key, 10, observing(order, "third"), "c");

		bus.dispatch(key, null, new Object[0]);

		assertEquals(List.of("first", "second", "third"), order);
	}

	@Test
	void twoKeysDoNotShareAnOrder() {
		List<String> order = new ArrayList<>();
		HookKey other = HookKey.returns(SITE, -1);
		bus.subscribe(other, 0, observing(order, "other"), "a");
		bus.subscribe(key, 100, observing(order, "mine"), "b");

		bus.dispatch(key, null, new Object[0]);

		assertEquals(List.of("mine"), order);
		assertEquals(1, bus.bodies(other).size());
	}

	@Test
	void theFirstCancelWinsAndLaterBodiesDoNotRun() {
		List<String> order = new ArrayList<>();
		bus.subscribe(key, 100, call -> HookResult.cancel(0), "cancelling");
		bus.subscribe(key, 0, observing(order, "never"), "b");

		HookResult result = bus.dispatch(key, null, new Object[0]);

		HookResult.Cancel cancel = assertInstanceOf(HookResult.Cancel.class, result);
		assertEquals(0, cancel.value());
		assertEquals(List.of(), order);
	}

	@Test
	void continuingEverywhereYieldsContinue() {
		bus.subscribe(key, 0, call -> HookResult.CONTINUE, "a");
		bus.subscribe(key, 0, call -> HookResult.CONTINUE, "b");

		assertInstanceOf(HookResult.Continue.class, bus.dispatch(key, null, new Object[0]));
	}

	@Test
	void aCancelWithANullValueIsDistinguishableFromContinuing() {
		bus.subscribe(key, 0, call -> HookResult.cancel(null), "void host");

		HookResult result = bus.dispatch(key, null, new Object[0]);

		HookResult.Cancel cancel = assertInstanceOf(HookResult.Cancel.class, result);
		assertNull(cancel.value());
	}

	@Test
	void anEmptyKeyDispatchesContinueWithoutBuildingACall() {
		assertSame(HookResult.CONTINUE, bus.dispatch(key, null, new Object[0]));
	}

	@Test
	void aBodySubscribingDuringADispatchDoesNotJoinThatDispatch() {
		List<String> order = new ArrayList<>();
		bus.subscribe(key, 10, call -> {
			order.add("outer");
			bus.subscribe(key, 0, observing(order, "inner"), "self");
			return HookResult.CONTINUE;
		}, "outer");

		bus.dispatch(key, null, new Object[0]);
		assertEquals(List.of("outer"), order);

		bus.dispatch(key, null, new Object[0]);
		assertEquals(List.of("outer", "outer", "inner"), order);
	}

	@Test
	void theSameSequenceProducesTheSameOrderTwice() {
		List<List<String>> runs = new ArrayList<>();
		for (int run = 0; run < 2; run++) {
			HookBus fresh = new HookBus();
			List<String> order = new ArrayList<>();
			fresh.subscribe(key, 5, observing(order, "a"), "a");
			fresh.subscribe(key, 5, observing(order, "b"), "b");
			fresh.subscribe(key, 9, observing(order, "c"), "c");
			fresh.subscribe(key, 1, observing(order, "d"), "d");
			fresh.dispatch(key, null, new Object[0]);
			runs.add(order);
		}

		assertEquals(runs.get(0), runs.get(1));
	}

	@Test
	void theCallCarriesTheReceiverAndTheBoxedArguments() {
		Object self = new Object();
		Object[] args = { "damage", 4.0f };
		List<Object> seen = new ArrayList<>();
		bus.subscribe(key, 0, call -> {
			seen.add(call.self());
			seen.add(call.arity());
			seen.add(call.arg(0));
			seen.add(call.arg(1));
			return HookResult.CONTINUE;
		}, "a");

		bus.dispatch(key, self, args);

		assertEquals(List.of(self, 2, "damage", 4.0f), seen);
	}
}

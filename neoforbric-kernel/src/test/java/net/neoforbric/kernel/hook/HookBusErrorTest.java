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
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * One body throwing must not remove the others, and must not disappear either.
 *
 * <p>Both halves are load-bearing. Letting the throw out would let the lowest-priority mod on a seam delete every
 * hook above it, on the paths where a missing hook is a crash or a silent behaviour change. Swallowing it silently
 * would be the decay this kernel's other ledgers exist to catch: the hook simply stops working and nothing says so.
 */
class HookBusErrorTest {
	private static final HookSite SITE = new HookSite("net/minecraft/server/MinecraftServer", "runServer", "()V");

	private HookBus bus;
	private HookKey key;

	@BeforeEach
	void setUp() {
		bus = new HookBus();
		key = HookKey.head(SITE);
	}

	@Test
	void aThrowingBodyIsRecordedAndTheRestStillRun() {
		List<String> order = new ArrayList<>();
		RuntimeException boom = new RuntimeException("boom");
		bus.subscribe(key, 100, call -> {
			throw boom;
		}, "broken-mod");
		bus.subscribe(key, 0, call -> {
			order.add("after");
			return HookResult.CONTINUE;
		}, "healthy-mod");

		HookResult result = bus.dispatch(key, null, new Object[0]);

		assertInstanceOf(HookResult.Continue.class, result);
		assertEquals(List.of("after"), order);
		assertEquals(1, bus.errors().failures().size());
		HookErrorLedger.Failure failure = bus.errors().failures().get(0);
		assertSame(boom, failure.error());
		assertEquals(key, failure.key());
		assertEquals("broken-mod", failure.owner());
	}

	@Test
	void anErrorIsTreatedAsContinueSoALaterCancelStillWins() {
		bus.subscribe(key, 100, call -> {
			throw new IllegalStateException("boom");
		}, "broken-mod");
		bus.subscribe(key, 0, call -> HookResult.cancel("replacement"), "cancelling");

		HookResult result = bus.dispatch(key, null, new Object[0]);

		assertEquals("replacement", assertInstanceOf(HookResult.Cancel.class, result).value());
	}

	@Test
	void anErrorDoesNotEscapeEvenWhenItIsAnErrorRatherThanAnException() {
		bus.subscribe(key, 0, call -> {
			throw new AssertionError("not an Exception");
		}, "broken-mod");

		HookResult result = bus.dispatch(key, null, new Object[0]);

		assertInstanceOf(HookResult.Continue.class, result);
		assertEquals(1, bus.errors().failures().size());
	}

	@Test
	void theLedgerIsBoundedButKeepsCounting() {
		for (int i = 0; i < HookErrorLedger.LIMIT + 10; i++) {
			// Captured so each iteration is a distinct instance: a non-capturing lambda is a singleton, and
			// subscribing one instance twice is a no-op by design, which would leave this with a single body.
			final int n = i;
			bus.subscribe(key, 0, call -> {
				throw new RuntimeException("boom " + n);
			}, "mod-" + i);
		}

		bus.dispatch(key, null, new Object[0]);

		assertEquals(HookErrorLedger.LIMIT, bus.errors().failures().size());
		assertEquals(10, bus.errors().dropped());
	}

	@Test
	void clearingTheLedgerLeavesTheBindingsAlone() {
		bus.subscribe(key, 0, call -> {
			throw new RuntimeException("boom");
		}, "broken-mod");
		bus.dispatch(key, null, new Object[0]);
		bus.errors().clear();

		assertEquals(0, bus.errors().failures().size());
		assertEquals(1, bus.bodies(key).size());
	}
}

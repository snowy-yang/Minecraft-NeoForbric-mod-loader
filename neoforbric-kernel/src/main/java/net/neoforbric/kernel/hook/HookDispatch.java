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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The static landing site the weaver's {@code INVOKESTATIC} reaches.
 *
 * <p>Injected bytecode cannot carry a {@link HookKey} object, so it carries an {@code int} instead. The id is
 * {@link HookKey#canonical()}'s {@code String.hashCode()}: specified by the language, so it is the same on every
 * JVM and across launches, which is what makes it usable in a log line or a crash report.
 *
 * <p>A collision is refused at {@link #bind} rather than resolved. Two seams sharing an id would silently deliver
 * one seam's calls to the other's bodies, and a hash collision between two names this long is a signal that the
 * canonical form is wrong, not something to paper over.
 */
public final class HookDispatch {
	private static final Map<Integer, HookKey> KEYS = new ConcurrentHashMap<>();
	private static volatile HookBus bus = new HookBus();

	private HookDispatch() {
	}

	/**
	 * Registers {@code key} and returns the id the emitted bytecode will carry.
	 *
	 * @throws IllegalStateException if a different key already holds this id
	 */
	public static int bind(HookKey key) {
		if (key == null) {
			throw new IllegalArgumentException("key is null");
		}
		int id = key.canonical().hashCode();
		HookKey previous = KEYS.putIfAbsent(id, key);
		if (previous != null && !previous.equals(key)) {
			throw new IllegalStateException("binding id " + id + " is claimed by both " + previous + " and " + key);
		}
		return id;
	}

	/** The key a bound id names, or {@code null}. */
	public static HookKey keyOf(int bindingId) {
		return KEYS.get(bindingId);
	}

	/**
	 * What every woven call site runs.
	 *
	 * <p>An unbound id is not an error: the weaver can emit a call for a seam nothing subscribed to, and the answer
	 * for that is the host's own behaviour. It is logged once per id so a seam that never binds is visible.
	 */
	public static HookResult dispatch(int bindingId, Object self, Object[] args) {
		HookKey key = KEYS.get(bindingId);
		if (key == null) {
			unbound(bindingId);
			return HookResult.CONTINUE;
		}
		return bus.dispatch(key, self, args);
	}

	private static final java.util.Set<Integer> REPORTED = ConcurrentHashMap.newKeySet();

	private static void unbound(int bindingId) {
		if (REPORTED.add(bindingId)) {
			net.neoforbric.kernel.util.NeoForbricLog.debug(
					"[NeoForbric/Hook] binding " + bindingId + " has no key; the host keeps its own behaviour");
		}
	}

	public static HookBus bus() {
		return bus;
	}

	/** For tests: a fresh bus and an empty id table. */
	public static void reset() {
		KEYS.clear();
		REPORTED.clear();
		bus = new HookBus();
	}
}

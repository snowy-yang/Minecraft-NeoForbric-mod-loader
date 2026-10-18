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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The registry and dispatcher behind every bridge the kernel weaves.
 *
 * <h2>Ordering</h2>
 *
 * <p>Priority descending, then registration order ascending. Both are decided when a body is subscribed, and a
 * dispatch reads an immutable snapshot, so subscribing during a dispatch cannot change the sequence already running.
 *
 * <h2>Cancellation</h2>
 *
 * <p>The first {@link HookResult.Cancel} wins and the bodies after it are not called. Everything continuing yields
 * {@link HookResult#CONTINUE}.
 *
 * <h2>Errors</h2>
 *
 * <p>A body that throws is recorded in {@link #errors()} and treated as {@code Continue}. The dispatch never
 * propagates it: one observer's failure must not remove the others, and the ledger is what makes it visible.
 *
 * <h2>Threading</h2>
 *
 * <p>Subscribing and unsubscribing take a lock; dispatching does not, so the client render thread and a server tick
 * can be in here at the same time on different keys.
 */
public final class HookBus {
	/** The handle {@link #unsubscribe} takes. */
	public record Subscription(HookKey key, HookBody body) {
	}

	private record Registration(int priority, long sequence, HookBody body, String owner) {
	}

	private final Object lock = new Object();
	private final Map<HookKey, List<Registration>> byKey = new HashMap<>();
	private final HookErrorLedger errors = new HookErrorLedger();
	private long sequence;

	/** The order dispatch reads. Replaced wholesale on every mutation so a dispatch never sees a half-built list. */
	private volatile Map<HookKey, List<HookBody>> ordered = Map.of();

	/**
	 * Binds {@code body} to {@code key}.
	 *
	 * <p>Subscribing the same instance to the same key twice is a no-op: it returns the existing handle rather than
	 * adding a second call. Two different instances on one key both run.
	 */
	public Subscription subscribe(HookKey key, int priority, HookBody body) {
		return subscribe(key, priority, body, "unknown");
	}

	/** @param owner who registered it, for the error ledger. */
	public Subscription subscribe(HookKey key, int priority, HookBody body, String owner) {
		if (key == null) {
			throw new IllegalArgumentException("key is null");
		}
		if (body == null) {
			throw new IllegalArgumentException("body is null");
		}
		synchronized (lock) {
			List<Registration> list = byKey.computeIfAbsent(key, k -> new ArrayList<>());
			for (Registration existing : list) {
				if (existing.body() == body) {
					return new Subscription(key, body);
				}
			}
			list.add(new Registration(priority, sequence++, body, owner));
			list.sort((a, b) -> a.priority() != b.priority()
					? Integer.compare(b.priority(), a.priority())
					: Long.compare(a.sequence(), b.sequence()));
			rebuild();
		}
		return new Subscription(key, body);
	}

	/** @return whether it was bound. Unsubscribing something absent is not an error. */
	public boolean unsubscribe(Subscription subscription) {
		if (subscription == null) {
			return false;
		}
		synchronized (lock) {
			List<Registration> list = byKey.get(subscription.key());
			if (list == null) {
				return false;
			}
			boolean removed = list.removeIf(r -> r.body() == subscription.body());
			if (list.isEmpty()) {
				byKey.remove(subscription.key());
			}
			if (removed) {
				rebuild();
			}
			return removed;
		}
	}

	/**
	 * Runs every body bound to {@code key} in order.
	 *
	 * @param self the receiver, or {@code null} for a static host
	 * @param args the host's parameters, already boxed by the emitter
	 */
	public HookResult dispatch(HookKey key, Object self, Object[] args) {
		List<HookBody> bodies = ordered.get(key);
		if (bodies == null || bodies.isEmpty()) {
			return HookResult.CONTINUE;
		}
		HookCall call = new HookCall(key, self, args);
		for (HookBody body : bodies) {
			HookResult result;
			try {
				result = body.call(call);
			} catch (Throwable t) {
				errors.record(key, ownerOf(key, body), t);
				continue;
			}
			if (result instanceof HookResult.Cancel) {
				return result;
			}
		}
		return HookResult.CONTINUE;
	}

	/** The bodies bound to {@code key}, in dispatch order. Empty when none are. */
	public List<HookBody> bodies(HookKey key) {
		List<HookBody> bodies = ordered.get(key);
		return bodies == null ? List.of() : bodies;
	}

	/** Every key with at least one body. */
	public java.util.Set<HookKey> keys() {
		return ordered.keySet();
	}

	public HookErrorLedger errors() {
		return errors;
	}

	/** Drops every binding. For tests and for a launch that restarts the game side. */
	public void clear() {
		synchronized (lock) {
			byKey.clear();
			sequence = 0;
			ordered = Map.of();
		}
	}

	private String ownerOf(HookKey key, HookBody body) {
		List<Registration> list = byKey.get(key);
		if (list != null) {
			for (Registration r : list) {
				if (r.body() == body) {
					return r.owner();
				}
			}
		}
		return "unknown";
	}

	private void rebuild() {
		Map<HookKey, List<HookBody>> next = new HashMap<>(byKey.size() * 2);
		for (Map.Entry<HookKey, List<Registration>> e : byKey.entrySet()) {
			List<HookBody> bodies = new ArrayList<>(e.getValue().size());
			for (Registration r : e.getValue()) {
				bodies.add(r.body());
			}
			next.put(e.getKey(), List.copyOf(bodies));
		}
		ordered = Map.copyOf(next);
	}
}

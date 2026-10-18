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
import java.util.Collections;
import java.util.List;

/**
 * The bodies that threw, kept so a broken observer is visible without being fatal.
 *
 * <p>A throwing body is recorded and the dispatch continues (see {@link HookBus#dispatch}). Both halves matter:
 * letting the throw through would let one mod's broken listener delete every later one, and swallowing it silently
 * would be the kind of quiet decay this kernel's other ledgers exist to prevent.
 *
 * <p>Bounded at {@link #LIMIT}: a body that throws on every server tick would otherwise grow this without end. Past
 * the limit the count keeps rising and the entries do not.
 */
public final class HookErrorLedger {
	public static final int LIMIT = 64;

	public record Failure(HookKey key, String owner, Throwable error) {
	}

	private final List<Failure> failures = new ArrayList<>();
	private long dropped;

	public synchronized void record(HookKey key, String owner, Throwable error) {
		if (failures.size() < LIMIT) {
			failures.add(new Failure(key, owner, error));
		} else {
			dropped++;
		}
	}

	public synchronized List<Failure> failures() {
		return Collections.unmodifiableList(new ArrayList<>(failures));
	}

	/** How many failures were seen past {@link #LIMIT} and not kept. */
	public synchronized long dropped() {
		return dropped;
	}

	public synchronized void clear() {
		failures.clear();
		dropped = 0;
	}
}

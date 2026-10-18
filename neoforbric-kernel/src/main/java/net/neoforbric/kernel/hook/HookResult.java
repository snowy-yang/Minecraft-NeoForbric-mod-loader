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

/**
 * What a {@link HookBody} decided.
 *
 * <p>{@code Cancel.value} may be {@code null}: a {@code void} host has no value to return, and
 * {@code Optional}-based shapes cannot tell that apart from "continue".
 */
public sealed interface HookResult {
	/** Continue with the next body, or with the host's own code once none is left. */
	record Continue() implements HookResult {
	}

	/** Stop: the host returns {@code value} (ignored when the host is {@code void}). */
	record Cancel(Object value) implements HookResult {
	}

	HookResult CONTINUE = new Continue();

	static HookResult cancel(Object value) {
		return new Cancel(value);
	}
}

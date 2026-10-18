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
 * What a {@link HookBody} is handed.
 *
 * <p>{@code self} is the receiver of an instance host and {@code null} for a static one. {@code args} are the host
 * method's parameters, boxed by the emitter; this package never interprets them, which is what keeps it free of
 * game types.
 */
public record HookCall(HookKey key, Object self, Object[] args) {
	public HookCall {
		if (key == null) {
			throw new IllegalArgumentException("key is null");
		}
		if (args == null) {
			throw new IllegalArgumentException("args is null");
		}
	}

	/** The {@code i}th parameter, already boxed. */
	public Object arg(int i) {
		return args[i];
	}

	public int arity() {
		return args.length;
	}
}

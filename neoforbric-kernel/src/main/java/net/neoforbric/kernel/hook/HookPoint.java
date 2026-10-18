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
 * Where in a host method a binding observes.
 *
 * <p>There is no {@code TAIL}: Mixin's {@code TAIL} is the last return point, which {@link HookKey} already names
 * as {@code RETURN} with {@code ordinal = -1}. Two enum values for one instruction would let a binding be
 * registered under one and looked up under the other.
 */
public enum HookPoint {
	/** The method's entry, before its first instruction. Cancellable. */
	HEAD,
	/** Before each {@code RETURN}; {@code ordinal = -1} is the last one. Cancellable, with the returned value. */
	RETURN,
	/** Before or after one call site named by {@link HookKey#target()}. Observation only. */
	INVOKE,
	/** At one field access named by {@link HookKey#target()}. Observation only. */
	FIELD
}

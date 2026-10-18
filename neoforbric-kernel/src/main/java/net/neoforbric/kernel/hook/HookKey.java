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
 * The identity of one dispatch slot: a host method, a point in it, and which occurrence of that point.
 *
 * <p>{@code ordinal} is {@code -1} for "all occurrences" and {@code 0..n} for one of them. It is meaningless for
 * {@link HookPoint#HEAD}, which has exactly one, and required to be {@code -1} there.
 *
 * <p>Use the factories rather than the canonical constructor: they reject the combinations that cannot mean
 * anything — an {@code INVOKE} with no target, a {@code HEAD} with an ordinal — at the call site.
 */
public record HookKey(HookSite site, HookPoint point, HookTarget target, int ordinal) {
	public HookKey {
		if (site == null) {
			throw new IllegalArgumentException("site is null");
		}
		if (point == null) {
			throw new IllegalArgumentException("point is null");
		}
		if (ordinal < -1) {
			throw new IllegalArgumentException("ordinal is negative but not -1 (all): " + ordinal);
		}
		boolean needsTarget = point == HookPoint.INVOKE || point == HookPoint.FIELD;
		if (needsTarget && target == null) {
			throw new IllegalArgumentException(point + " needs a target member");
		}
		if (!needsTarget && target != null) {
			throw new IllegalArgumentException(point + " takes no target, got " + target);
		}
		if (point == HookPoint.HEAD && ordinal != -1) {
			throw new IllegalArgumentException("HEAD has exactly one point; ordinal must be -1, got " + ordinal);
		}
	}

	public static HookKey head(HookSite site) {
		return new HookKey(site, HookPoint.HEAD, null, -1);
	}

	/** Every return point; {@code ordinal = -1} is the last one. */
	public static HookKey returns(HookSite site, int ordinal) {
		return new HookKey(site, HookPoint.RETURN, null, ordinal);
	}

	public static HookKey invoke(HookSite site, HookTarget target, int ordinal) {
		return new HookKey(site, HookPoint.INVOKE, target, ordinal);
	}

	public static HookKey field(HookSite site, HookTarget target, int ordinal) {
		return new HookKey(site, HookPoint.FIELD, target, ordinal);
	}

	/** The canonical form, and the input to the stable binding id. */
	public String canonical() {
		StringBuilder b = new StringBuilder().append(site.canonical()).append('/').append(point);
		if (target != null) {
			b.append('@').append(target.canonical());
		}
		return b.append('#').append(ordinal).toString();
	}

	@Override
	public String toString() {
		return canonical();
	}
}

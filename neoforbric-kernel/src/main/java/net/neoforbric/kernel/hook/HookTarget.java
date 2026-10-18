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
 * The member an {@link HookPoint#INVOKE} or {@link HookPoint#FIELD} binding observes.
 *
 * <p>Separate from {@link HookSite} because it is the thing being called, not the thing being edited: the host is
 * where the bytecode changes, the target is what it already does there.
 */
public record HookTarget(String owner, String name, String desc) {
	public HookTarget {
		Descriptors.checkInternalName(owner, "target owner");
		Descriptors.checkMethodName(name, "target name");
		Descriptors.checkMethodDescriptor(desc, "target desc");
	}

	public String canonical() {
		return owner + '.' + name + desc;
	}

	@Override
	public String toString() {
		return canonical();
	}
}

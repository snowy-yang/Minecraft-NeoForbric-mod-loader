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
 * The host method a binding attaches to.
 *
 * <p>All three components are validated on construction — see {@link Descriptors}. {@code owner} is a JVM internal
 * name because every other ASM-facing type in the kernel uses that form; the dotted form is rejected rather than
 * silently normalised, so a caller that guessed wrong finds out here.
 */
public record HookSite(String owner, String name, String desc) {
	public HookSite {
		Descriptors.checkInternalName(owner, "owner");
		Descriptors.checkMethodName(name, "name");
		Descriptors.checkMethodDescriptor(desc, "desc");
	}

	/** The canonical {@code owner#name desc} form, used in logs and in the binding-id hash. */
	public String canonical() {
		return owner + '#' + name + desc;
	}

	@Override
	public String toString() {
		return canonical();
	}
}

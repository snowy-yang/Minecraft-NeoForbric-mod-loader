/*
 * Copyright 2026 The Forbric Project
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

package net.forbric.kernel.runtime;

import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.neoforged.neoforge.resource.ResourcePackLoader;

/**
 * The seam the base's pack-repository population is redirected to: NeoForge's
 * {@code ResourcePackLoader.populatePackRepository}, unchanged, under the kernel's own name.
 */
public final class KernelPackFinders {
	private KernelPackFinders() {
	}

	/** NeoForge's signature exactly, so the redirect is an owner and a name. */
	public static void populatePackRepository(PackRepository repository, PackType type, boolean trusted) {
		ResourcePackLoader.populatePackRepository(repository, type, trusted);
	}
}

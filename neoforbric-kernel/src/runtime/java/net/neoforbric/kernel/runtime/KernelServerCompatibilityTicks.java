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

package net.neoforbric.kernel.runtime;

import net.minecraft.server.MinecraftServer;

/**
 * The server's completed-tick carrier for {@link net.neoforbric.kernel.boot.LateServerCompatibility}.
 *
 * <p>{@code ServerCompatibilityTickInjector} splices {@link #onCompatibilityTick} before every normal
 * return of {@code MinecraftServer.tickServer}, so late compatibility findings are consumed once per
 * completed tick, outside event dispatch and save callbacks — the boundary the dedicated-server halt
 * needs, because a stop issued there goes through the game's own saving path.
 *
 * <p>The game-side half of a boot-side decision: this class may touch {@code MinecraftServer} and
 * {@code halt}, the boot side may not. Integrated servers defer to the client's native screen and are
 * answered by {@code LateServerCompatibility} itself.
 */
public final class KernelServerCompatibilityTicks {
	private KernelServerCompatibilityTicks() {
	}

	/** Invoked by the tick return seam, with the server the tick belongs to. */
	public static void onCompatibilityTick(Object value) {
		MinecraftServer server = (MinecraftServer) value;
		net.neoforbric.kernel.boot.LateServerCompatibility.tick(server, server.isDedicatedServer(), () -> server.halt(false));
	}
}

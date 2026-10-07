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

import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.FuelValues;
import net.neoforged.neoforge.event.EventHooks;

/**
 * Lets mods say how long something burns, through NeoForge's burn-time event.
 *
 * <p>{@code EventHooks.getItemBurnTime} posts NeoForge's {@code FurnaceFuelBurnTimeEvent}. The hook's
 * four-argument shape takes the {@code FuelValues} the call site is inside, which is why the redirect pushes
 * the receiver through here rather than calling a shorter form at the site.
 */
public final class KernelFuelValues {
	private static final AtomicBoolean WARNED = new AtomicBoolean();

	private KernelFuelValues() {
	}

	/**
	 * NeoForge's burn-time hook.
	 *
	 * @param base what the game's own table says, before the event is consulted
	 * @param fuel the {@code FuelValues} the call site is inside; the hook requires it
	 */
	public static int burnDuration(ItemStack stack, int base, RecipeType<?> type, FuelValues fuel) {
		int value = base;
		try {
			value = EventHooks.getItemBurnTime(stack, value, type, fuel);
		} catch (Throwable t) {
			warnOnce(t);
		}
		return value;
	}

	/**
	 * One line, ever.
	 *
	 * <p>This runs for every fuel lookup in every furnace, so a per-call line would be the loudest thing in the
	 * log; and a throw here would take the smelt with it, which is a worse outcome than the event not being
	 * asked. The value carried so far is returned either way.
	 */
	private static void warnOnce(Throwable t) {
		if (WARNED.compareAndSet(false, true)) {
			ForbricLog.warn("[Forbric/Fuel] NeoForge's burn-time hook failed — mods cannot change how long anything "
					+ "burns", Reflect.unwrap(t));
		}
	}
}

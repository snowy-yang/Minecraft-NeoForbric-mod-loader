/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.EventHooks;

/**
 * A finished conversion, told once through NeoForge's hook.
 *
 * <p>NeoConversionPostInjector redirects the conversion-forward call sites here; {@code EventHooks.onLivingConvert}
 * posts NeoForge's {@code LivingConversionEvent.Post}, which is where mods riding on a conversion — data
 * attachments among them — hear about it.
 */
public final class KernelConversions {
	private KernelConversions() {
	}

	public static void onLivingConvert(LivingEntity from, LivingEntity to) {
		EventHooks.onLivingConvert(from, to);
	}
}

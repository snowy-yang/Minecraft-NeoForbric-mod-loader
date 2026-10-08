/* Copyright 2026 The NeoForbric Project. Licensed under the Apache License, Version 2.0. */
package net.neoforbric.kernel.interop;

import net.neoforbric.api.Ecosystem;
import net.neoforbric.api.ModPresence;

public final class CompatPluginOwnership {
    private CompatPluginOwnership() { }
    public static boolean controlifyUsesNeoForge() {
        var mod = ModPresence.metadata("controlify");
        return mod != null && mod.getEcosystem() == Ecosystem.NEOFORGE;
    }
}

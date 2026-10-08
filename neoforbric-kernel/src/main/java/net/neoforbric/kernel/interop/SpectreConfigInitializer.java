/* Copyright 2026 The NeoForbric Project. Licensed under the Apache License, Version 2.0. */
package net.neoforbric.kernel.interop;

/** Fabric config entrypoint contract when SpectreLib's selected implementation is NeoForge. */
public interface SpectreConfigInitializer {
    void onInitializeConfig();
}

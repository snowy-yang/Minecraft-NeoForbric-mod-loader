/* Copyright 2026 The NeoForbric Project. Licensed under the Apache License, Version 2.0. */
package net.neoforbric.kernel.runtime;
import net.minecraft.client.gui.Hud;import net.neoforbric.kernel.interop.CreateHudScope;

public final class KernelCreateHudQuery {
	private KernelCreateHudQuery() { }
	public static Object next(Hud hud){return CreateHudScope.query(hud,()->hud.nextContextualInfoState());}
}

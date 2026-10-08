package net.neoforbric.kernel.mixin;

import net.neoforbric.api.Ecosystem;

/** Test seam for other packages: mark a mixin as a Fabric mod's, and forget it again. */
public final class MixinStubRebindAccess {
	private MixinStubRebindAccess() {
	}

	public static void fabric(String mixinInternalName) {
		MixinStubRebind.noteEcosystem(mixinInternalName, Ecosystem.FABRIC);
	}

	public static void forget() {
		MixinStubRebind.forget();
	}
}

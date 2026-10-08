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

package net.neoforbric.loader.impl.forge.neoforge;

import net.neoforbric.loader.impl.forge.runtime.NeoForbricFabricMains;
import net.neoforbric.loader.impl.util.NeoForbricLog;

/**
 * The Fabric-content window for the REAL NeoForge lifecycle: invoked (reflectively) by the NeoForbric NeoForge
 * bridge mod's {@code RegisterEvent} listener, i.e. inside NeoForge's genuine registration span
 * ({@code GameData.postRegisterEvents} — a GLOBAL {@code unfreezeData} .. per-registry {@code RegisterEvent} ..
 * {@code freezeData}). Because the unfreeze is global and holds across the whole dispatch, a listener on the
 * first {@code RegisterEvent} sees every registry writable — plain {@code Registry.register} (what Fabric mods
 * call) succeeds, and NeoForge's own trailing {@code freezeData} freezes the Fabric-registered content too.
 *
 * <p>NeoForge registries are vanilla {@code MappedRegistry}. A defensive {@code GameData.unfreezeData()} is issued in case
 * the window is reached outside NeoForge's ambient unfreeze; NeoForge's trailing {@code freezeData} handles the
 * re-freeze, so this window never freezes (freezing mid-dispatch would strand later {@code RegisterEvent}s).
 *
 * <p>Runs at most once. Off in headless-register mode (that driver runs the window itself) and unless
 * {@code -Dneoforbric.fabricMainDeferred=true} (else the vanilla hooks run the mains normally).
 */
public final class NeoForbricNeoFabricWindow {
	private NeoForbricNeoFabricWindow() {
	}

	/** Reflective entrypoint for the NeoForge bridge mod. Safe to call from every RegisterEvent; runs once. */
	public static void openWindowAndRunFabricMains() {
		if (Boolean.getBoolean(NeoForbricNeoForgeRuntime.HEADLESS_REGISTER)) return; // headless drives mains itself
		if (!Boolean.getBoolean("neoforbric.fabricMainDeferred")) return; // hooks will run mains normally

		// Shared, process-wide guard: at most once across both ecosystems' windows.
		if (NeoForbricFabricMains.alreadyRan()) return;

		ClassLoader cl = NeoForbricNeoFabricWindow.class.getClassLoader();
		try {
			boolean unfroze = unfreezeDefensively(cl);
			NeoForbricLog.info("[NeoForbric/NeoBridge] registration window open"
					+ (unfroze ? " (defensively unfroze registries)" : " (registries already unfrozen by NeoForge)")
					+ " - running Fabric main entrypoints");

			NeoForbricFabricMains.runOnce();
		} catch (Throwable t) {
			NeoForbricLog.error("[NeoForbric/NeoBridge] Fabric window failed", t);
		}
		// Intentionally NO freezeData: NeoForge's postRegisterEvents freezes after its RegisterEvent loop, which
		// includes the content Fabric just registered. Freezing here would strand NeoForge's remaining events.
	}

	/**
	 * Client-hook variant (substrate patch 0005 via {@link net.neoforbric.loader.impl.forge.runtime.NeoForbricClientWindow}):
	 * run the deferred Fabric {@code "main"} entrypoints (once, shared {@link #RAN} guard) and the {@code "client"}
	 * entrypoints inside one registry-open span. On the client the vanilla hooks reach mod init BEFORE NeoForge's
	 * genuine {@code ClientModLoader.begin} registration window, so — unlike the server path where the bridge fires
	 * inside NeoForge's ambient unfreeze — here we open the window ourselves ({@code GameData.unfreezeData}) and
	 * re-freeze after, so plain {@code Registry.register} (Fabric content, incl. {@code onInitializeClient} particle
	 * types etc.) succeeds. A client-init failure PROPAGATES; the registries are always re-frozen.
	 */
	public static void runClientInitUnlocked(Runnable clientInit) {
		if (Boolean.getBoolean(NeoForbricNeoForgeRuntime.HEADLESS_REGISTER)
				|| !Boolean.getBoolean("neoforbric.fabricMainDeferred")) {
			clientInit.run(); // no NeoForge registration window in play — run the client stage unchanged
			return;
		}

		ClassLoader cl = NeoForbricNeoFabricWindow.class.getClassLoader();
		boolean opened = openWindow(cl);
		try {
			NeoForbricFabricMains.runOnce(); // shared guard: at most once across both ecosystems' windows
			clientInit.run(); // client entrypoints, still unfrozen; genuine failures propagate to the caller
		} finally {
			if (opened) closeWindow(cl);
		}
	}

	private static boolean openWindow(ClassLoader cl) {
		try {
			Class.forName("net.neoforged.neoforge.registries.GameData", false, cl).getMethod("unfreezeData").invoke(null);
			NeoForbricLog.info("[NeoForbric/NeoBridge] client registration window open (GameData.unfreezeData)"
					+ " - running deferred Fabric main + client entrypoints");
			return true;
		} catch (Throwable t) {
			NeoForbricLog.error("[NeoForbric/NeoBridge] client window: could not unfreeze registries", t);
			return false;
		}
	}

	private static void closeWindow(ClassLoader cl) {
		try {
			Class.forName("net.neoforged.neoforge.registries.GameData", false, cl).getMethod("freezeData").invoke(null);
		} catch (Throwable t) {
			NeoForbricLog.error("[NeoForbric/NeoBridge] client window relock failed", t);
		}
	}

	/**
	 * If the item registry is currently frozen, call {@code GameData.unfreezeData()} to open every registry, and
	 * report {@code true} (so the caller can note the window was NOT reached inside NeoForge's ambient unfreeze).
	 * When already unfrozen (the normal genuine-lifecycle case), do nothing and report {@code false}.
	 */
	private static boolean unfreezeDefensively(ClassLoader cl) {
		try {
			Class<?> builtin = Class.forName("net.minecraft.core.registries.BuiltInRegistries", false, cl);
			Object itemReg = builtin.getField("ITEM").get(null);
			java.lang.reflect.Field frozen = findField(itemReg.getClass(), "frozen");
			boolean isFrozen = false;
			if (frozen != null) {
				frozen.setAccessible(true);
				isFrozen = frozen.getBoolean(itemReg);
			}
			if (isFrozen) {
				Class.forName("net.neoforged.neoforge.registries.GameData", false, cl)
						.getMethod("unfreezeData").invoke(null);
				return true;
			}
		} catch (Throwable t) {
			NeoForbricLog.warn("[NeoForbric/NeoBridge] defensive unfreeze probe failed (continuing)", t);
		}
		return false;
	}

	private static java.lang.reflect.Field findField(Class<?> c, String name) {
		for (; c != null; c = c.getSuperclass()) {
			try {
				return c.getDeclaredField(name);
			} catch (NoSuchFieldException ignore) {
				// try superclass
			}
		}
		return null;
	}
}

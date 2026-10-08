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

package net.neoforbric.kernel.mixin;

import net.neoforbric.kernel.util.NeoForbricLog;

/**
 * Whether a mixin config may be registered at all — the whole-config gate, applied before Mixin ever reads the JSON.
 *
 * <p>Lifted verbatim out of {@code KernelFabricEcosystem}, where it only ever saw the Fabric half of the registered
 * set. Every ecosystem's configs now pass through the same gate, which is what makes
 * {@code -Dneoforbric.disableMixinConfigs} usable for bisecting a bad Forge/NeoForge config: without it the only way to
 * take one out of the picture is an edit-and-rebuild.
 *
 * <p>Registering nothing for a config is stronger than suppressing individual mixins — it takes a whole module out,
 * which is what an all-or-nothing module (and bisecting) needs. See {@link MergedBaseMixinCompat#DISABLED_CONFIGS}
 * for why that built-in list is deliberately empty.
 */
public final class MixinConfigPolicy {
	private MixinConfigPolicy() {
	}

	/**
	 * Whether {@code config} must not be registered: either it is on the built-in merged-base incompatibility list
	 * ({@link MergedBaseMixinCompat#DISABLED_CONFIGS}) or {@code -Dneoforbric.disableMixinConfigs} names it (csv; a
	 * trailing {@code *} is a prefix glob).
	 *
	 * <p>{@code -Dneoforbric.enableMixinConfigs} (csv) forces a config back ON over the built-in list. That list is a
	 * record of what was true when each entry was measured, and the merged base keeps changing underneath it — so
	 * re-testing an entry has to be one flag, not an edit-and-rebuild. It overrides only the built-in list, never an
	 * explicit {@code -Dneoforbric.disableMixinConfigs} on the same command line.
	 */
	public static boolean isDisabled(String config) {
		if (MergedBaseMixinCompat.enabled() && MergedBaseMixinCompat.DISABLED_CONFIGS.contains(config)
				&& !isForceEnabled(config)) {
			return true;
		}

		String csv = System.getProperty("neoforbric.disableMixinConfigs");
		if (csv == null || csv.isEmpty()) return false;

		for (String raw : csv.split(",")) {
			String entry = raw.trim();
			if (entry.isEmpty()) continue;

			if (entry.endsWith("*")) {
				if (config.startsWith(entry.substring(0, entry.length() - 1))) return true;
			} else if (config.equals(entry)) {
				return true;
			}
		}

		return false;
	}

	/** Whether {@code -Dneoforbric.enableMixinConfigs} (csv) names {@code config}, forcing it on over the built-in list. */
	private static boolean isForceEnabled(String config) {
		String csv = System.getProperty("neoforbric.enableMixinConfigs");
		if (csv == null || csv.isEmpty()) return false;

		for (String raw : csv.split(",")) {
			if (config.equals(raw.trim())) {
				NeoForbricLog.warn("[NeoForbric/Mixin] mixin config %s FORCE-ENABLED by -Dneoforbric.enableMixinConfigs over "
						+ "the built-in merged-base incompatibility list — expect the recorded breakage", config);
				return true;
			}
		}
		return false;
	}
}

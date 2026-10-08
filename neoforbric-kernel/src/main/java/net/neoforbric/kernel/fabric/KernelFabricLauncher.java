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

package net.neoforbric.kernel.fabric;

import java.nio.file.Path;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;

import net.neoforbric.kernel.classloading.NeoForbricClassLoader;
import net.neoforbric.kernel.classloading.LoaderProbePolicy;
import net.neoforbric.kernel.util.NeoForbricLog;

/**
 * The kernel's answer to {@code FabricLauncherBase.getLauncher()} — what a mod reaches for when it needs to put a
 * jar it just unpacked onto the running classpath.
 *
 * <p>Under Fabric that call lands on Knot. There is no Knot here, so it lands on the sovereign loader instead:
 * the jar becomes an owned URL and its classes go through the full pipeline (access tweakers, the compat chain,
 * then Mixin), which is what the mod expects and what Fabric's own {@code addToClassPath} provides.
 *
 * <p>{@code allowedPrefixes} is accepted and ignored: it exists so Knot can keep a jar's packages from shadowing
 * another's, and the kernel has one flat loader with no per-jar package filter to express that against.
 *
 * <p>The jar is recorded as Fabric's for environment stripping: Knot strips what it loads from it like any other
 * Fabric class, and a client-only member left in it on a server fails the class the way CreativeCore's did.
 * {@code -Dneoforbric.envStrip.runtimeJars=off} leaves such a jar unstripped, as before.
 */
public final class KernelFabricLauncher implements FabricLauncher {
	/** {@code -Dneoforbric.envStrip.runtimeJars=off}: a jar added here is not recorded as Fabric's for the strip. */
	public static final String RUNTIME_JARS_SWITCH = "neoforbric.envStrip.runtimeJars";

	private final NeoForbricClassLoader loader;
	private final EnvType envType;

	private KernelFabricLauncher(NeoForbricClassLoader loader, EnvType envType) {
		this.loader = loader;
		this.envType = envType;
	}

	/** Builds the launcher view and publishes it. Call once, before any mod class loads. */
	public static void install(NeoForbricClassLoader loader, EnvType envType) {
		FabricLauncherBase.setLauncher(new KernelFabricLauncher(loader, envType));
	}

	@Override
	public void addToClassPath(Path path, String... allowedPrefixes) {
		try {
			loader.addURL(path.toUri().toURL());
			if (!"off".equalsIgnoreCase(System.getProperty(RUNTIME_JARS_SWITCH, "on").trim())) {
				loader.addRuntimeJarFamily(path, LoaderProbePolicy.Family.FABRIC);
			}
			NeoForbricLog.info("[NeoForbric/Fabric] a mod added %s to the classpath at runtime", path.getFileName());
		} catch (Throwable t) {
			throw new RuntimeException("could not add " + path + " to the classpath", t);
		}
	}

	@Override
	public ClassLoader getTargetClassLoader() {
		return loader;
	}

	@Override
	public EnvType getEnvironmentType() {
		return envType;
	}

	@Override
	public boolean isDevelopment() {
		return false;
	}
}

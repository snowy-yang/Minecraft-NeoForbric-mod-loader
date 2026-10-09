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

package net.neoforbric.installer.kernel;

/**
 * The upstream versions this installer builds against.
 *
 * <p>They are pins, not defaults: each one was chosen because a specific thing breaks at the neighbouring
 * versions, and the reason lives next to the number so nobody "updates" it back into the failure. Everything the
 * install produces is keyed on this set, so bumping any of them invalidates the cached artifacts that depend on
 * it.
 */
final class Pins {

	private Pins() {
	}

	/**
	 * The only Minecraft version this generation supports: 1.21.1, the long-lived modding line.
	 *
	 * <p>The loader's game-side code, its weave pins and every gate under neoforbric-kernel/run/ are written
	 * against this version's class names, so this is an anchor, not a default: moving it means re-validating
	 * the patched-base expectations (the kernel's staged-name constants and the gates' fixture versions all
	 * derive from it). 1.21.1 is where NeoForge 21.1.x — the line NeoForged still maintains in parallel with
	 * the newest Minecraft — and the largest body of live Fabric and NeoForge mods both are.
	 */
	static final String MINECRAFT = "1.21.1";

	/**
	 * NeoForge, on the 21.1 release line that still tracks Minecraft 1.21.1.
	 *
	 * <p>21.1.x is the LTS branch of NeoForge for this Minecraft: it takes backports and stays compatible
	 * with the 1.21.1 mod corpus, which is exactly the population the anchored test pack draws from. Within
	 * the line this is the newest stable (non-beta) build; betas are excluded for the same reason the 26.2
	 * line's betas were: the title screen brands whatever build runs, and mods' {@code neoforge} version
	 * ranges are checked against it.
	 */
	static final String NEOFORGE = "21.1.256";

	/**
	 * NeoFormRuntime, pinned to the build actually validated rather than the newest published one.
	 *
	 * <p>NFRT's own jar digest is part of its cache key, so a different NFRT is entitled to produce different
	 * bytes. 2.0.18 is the build whose {@code gameJar} result was checked byte-for-byte against the reference
	 * patched base of its day; the 21.1 re-anchor revalidated the {@code gameJarNoRecomp} path against it.
	 */
	static final String NFRT = "2.0.18";

	/**
	 * The NeoForm result that IS the game base.
	 *
	 * <p>{@code gameJarNoRecomp} is the binary-patch path — {@code preProcessJar → binaryPatch →
	 * copyUnpatchedClasses → applyDevTransforms} — and it produces the same 10,963 classes as the {@code gameJar}
	 * recompile path in about six seconds, with no decompiler, no 4 GB heap and no {@code javac}.
	 *
	 * <p>It must not be {@code gameJarNoRecompWithNeoForge}: that variant routes through
	 * {@code binaryWithNeoForge} and folds NeoForge's own classes into the jar, which would then define them
	 * twice — once inside the game base, once in {@code neoforge-runtime.jar}.
	 */
	static final String NFRT_RESULT = "gameJarNoRecomp";

	/** The NeoForge artifact NFRT is pointed at. The bare coordinate does not exist on the Maven. */
	static String neoforgeUserdevCoordinate() {
		return "net.neoforged:neoforge:" + NEOFORGE + ":userdev";
	}

	/** NeoFormRuntime's own fat jar. */
	static String nfrtCoordinate() {
		return "net.neoforged:neoform-runtime:" + NFRT + ":all";
	}

	/** A one-line summary for the build stamp, so a cached artifact records what produced it. */
	static String stamp() {
		return "mc=" + MINECRAFT + " neoforge=" + NEOFORGE
				+ " nfrt=" + NFRT + " result=" + NFRT_RESULT;
	}
}

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

package net.neoforbric.kernel.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.neoforbric.kernel.discovery.ModFileScanner;
import net.neoforbric.kernel.util.NeoForbricLog;
import cpw.mods.jarhandling.SecureJar;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.ModFileScanData;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

/**
 * The {@code IModFile} behind a kernel-constructed mod: its real jar when it has one, a placeholder when it does
 * not.
 *
 * <h2>What this class made visible</h2>
 *
 * <p>It replaces a {@link java.lang.reflect.Proxy} that switched on method NAMES and answered everything it did
 * not name through a {@code defaultReturn} — null for objects, false for booleans, empty for collections. Four
 * of this interface's methods were named; the others were answered by that fallback, and nothing anywhere
 * said so. Written as a class, javac lists them all and each one is now a line with a reason next to it.
 *
 * <p>The class was first written answering every one of those the way the proxy had, deliberately, so that the
 * rewrite could not move behaviour — and it recorded that some of them were almost certainly wrong. This is that
 * separate change: {@link #getFileName()}, {@link #getType()} and {@link #getModFileInfo()} now answer with the
 * values a real mod file has. Each was null, and null is not a value any consumer expects from them: a mod
 * filtering the file list by type dropped every kernel-loaded mod, and a mod walking from a file back to its
 * info — the direction NeoForge's own error path takes — dereferenced null.
 *
 * <p>21.1's {@code IModFile} carries the jar as a {@link SecureJar} ({@code getSecureJar}) and has no
 * {@code getId()} or {@code getContents()} at all — those are 26.x interface shapes. A mod reading files out of
 * its own jar goes through the secure jar's {@code getRootPaths}/probe, which is what FML itself hands out on
 * this line, so a kernel-constructed file that answers null there takes the caller down exactly the way a
 * missing jar would.
 */
public final class KernelModFile implements IModFile {
	private final String modId;
	private final Path path;
	private final SecureJar contents;
	private final Path jar;

	/**
	 * The info that owns this file. Set by {@link KernelModFileInfo}'s constructor, which is what builds this
	 * object: the two refer to each other, so one of them has to be filled in second.
	 */
	private IModFileInfo modFileInfo;

	/** Memoised: most instances are never asked, and walking a hundred jars for nobody is pure boot cost. */
	private ModFileScanData scanResult;

	/**
	 * @param jar the mod's real jar, or null for a presence alias which has none. A mod that reads files out of
	 *            its own jar through {@code getModInfo().getOwningFile().getFile().getSecureJar()} gets nothing
	 *            without it: on 26.x this was a JarContents walk that produced a null Pack and died inside
	 *            {@code PackRepository.discoverAvailable} ("Cannot invoke Pack.streamSelfAndChildren() because
	 *            pack is null", a world that would not load); on 21.1 the same walk starts at the secure jar.
	 */
	public KernelModFile(String modId, Path jar) {
		this.modId = modId;
		this.jar = jar;
		this.path = jar != null ? jar : Path.of("neoforbric-kernel", modId + ".jar");
		// Native visitors enumerate every published identity, including cross-ecosystem aliases. An alias
		// contributes no resources, but is still a valid file-shaped entry; null aborts the entire traversal.
		this.contents = jar == null ? null : contentsOf(jar);
	}

	private static SecureJar contentsOf(Path jar) {
		try {
			return SecureJar.from(jar);
		} catch (Throwable t) {
			NeoForbricLog.debug("[NeoForbric/Container] no SecureJar for %s: %s", jar.getFileName(),
					String.valueOf(t));
			return null;
		}
	}

	@Override
	public Path getFilePath() {
		return path;
	}

	@Override
	public SecureJar getSecureJar() {
		return contents;
	}

	/**
	 * Built on demand, and never null.
	 *
	 * <p>It is reached from further away than it looks: {@code ModList.getAllScanData()} streams sortedList →
	 * getOwningFile → getFile → getScanResult, so EVERY published mod is asked for one the moment anything calls
	 * it. Sodium does, right after its config walk, and NPE'd on a null inside {@code Minecraft.<init>} before
	 * the window ever opened.
	 *
	 * <p>The scan is real: {@code ModFileScanner.scan} walks the jar's classes and builds FML's own
	 * {@code ModFileScanData}, so annotation-driven discovery (JEI plugins, Jade providers, Sophisticated Core,
	 * Sodium's third-party config hooks) finds what it would on the carrier. Only a jar-less presence alias — a
	 * mod id the kernel publishes without a file behind it — gets the empty result, which reads exactly like a
	 * mod file that declares no annotations.
	 *
	 * <p>The index is the one the seeded {@code LoadingModList}'s {@code ModFile} for the same jar hands out
	 * ({@code ModFileScanner.scanShared}): natively {@code ModList} is built out of {@code LoadingModList}, so
	 * both hand out the same {@code ModFile} and therefore the same index — and the jar is walked once, however
	 * many entries of the two lists ask.
	 */
	@Override
	public synchronized ModFileScanData getScanResult() {
		if (scanResult == null) {
			Object real = jar == null ? null : ModFileScanner.scanShared(jar, getClass().getClassLoader());
			scanResult = real instanceof ModFileScanData data ? data : new ModFileScanData();
		}
		return scanResult;
	}

	/** The jar's file name, or the placeholder path's for a mod that has no jar. Never null. */
	@Override
	public String getFileName() {
		Path name = path.getFileName();
		return name == null ? modId + ".jar" : name.toString();
	}

	/**
	 * {@code MOD}, which is what every file the kernel publishes is.
	 *
	 * <p>Null was not a neutral answer: consumers filter the file list by type, and one comparing against
	 * {@code Type.MOD} dropped every kernel-loaded mod from whatever it was building.
	 */
	@Override
	public IModFile.Type getType() {
		return IModFile.Type.MOD;
	}

	/** The info that owns this file — the walk back up, which NeoForge's own error path takes. */
	@Override
	public IModFileInfo getModFileInfo() {
		return modFileInfo;
	}

	/** Called once by {@link KernelModFileInfo}'s constructor. */
	void setModFileInfo(IModFileInfo info) {
		this.modFileInfo = info;
	}

	// --- answered the way the proxy's defaultReturn answered them -----------------------------------------

	/** Empty, as before. The mods of this file are reached through {@code IModFileInfo.getMods()} instead. */
	@Override
	public List<IModInfo> getModInfos() {
		return List.of();
	}

	/** Null, as before. */
	@Override
	public Supplier<Map<String, Object>> getSubstitutionMap() {
		return null;
	}

	/** Null, as before. The kernel does not discover mod files the way FML's locators do. */
	@Override
	public ModFileDiscoveryAttributes getDiscoveryAttributes() {
		return null;
	}

	/**
	 * Accepted and ignored, as the proxy ignored it. FML writes the signature verification outcome here during
	 * its own discovery; the kernel's files are never on that path, and no consumer of a kernel-published file
	 * has a use for the answer.
	 */
	@Override
	public void setSecurityStatus(cpw.mods.jarhandling.SecureJar.Status status) {
	}

	// 21.1's IModFile asks for resources by path segments rather than through the SecureJar alone; the kernel
	// keeps one jar per mod, so the first segment walk is the whole answer.
	@Override
	public Path findResource(String... path) {
		return jar.resolve(String.join("/", path));
	}

	@Override
	public String toString() {
		return "KernelModFile[" + modId + "]";
	}
}

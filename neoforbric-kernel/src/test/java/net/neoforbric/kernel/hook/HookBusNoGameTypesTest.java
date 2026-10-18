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

package net.neoforbric.kernel.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The hook package must not name a game type, and this is checked in the bytecode rather than by reading the source.
 *
 * <p>The package's whole claim is that it sits on the BOOT side of the delegation split while being called from
 * woven game-side bytecode — that works only while it references nothing the boot loader cannot see. A single
 * {@code net/minecraft/} or {@code net/neoforged/} string in a descriptor would not fail to compile, because the
 * build classpath has them; it would fail at launch, in the one place where the split cannot be undone.
 *
 * <p>Reading the constant pool is the check that cannot be talked around: a javadoc {@code {@link}} is not in the
 * class file, so what this sees is exactly what the JVM would have to resolve.
 */
class HookBusNoGameTypesTest {
	private static final List<String> FORBIDDEN = List.of(
			"net/minecraft/", "net/neoforged/", "net/fabricmc/", "com/mojang/blaze3d/");

	@Test
	void noClassInTheHookPackageReferencesAGameOrEcosystemType() throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main", "net",
				"neoforbric", "kernel", "hook").normalize();
		assertTrue(Files.isDirectory(compiled), "the hook package is not compiled: " + compiled);

		List<Path> classes;
		try (Stream<Path> walk = Files.walk(compiled)) {
			classes = walk.filter(p -> p.toString().endsWith(".class")).sorted().toList();
		}
		// A vacuous pass would be worse than no test: an empty or partial directory must fail here.
		assertTrue(classes.size() >= 10, "expected the whole package, found " + classes.size() + ": " + classes);

		List<String> offenders = new ArrayList<>();
		for (Path file : classes) {
			byte[] bytes = Files.readAllBytes(file);
			String pool = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
			for (String forbidden : FORBIDDEN) {
				if (pool.contains(forbidden)) {
					offenders.add(compiled.relativize(file) + " -> " + forbidden);
				}
			}
		}
		assertEquals(List.of(), offenders, "the hook bus must stay free of game types");
	}

	@Test
	void theReaderFindsGameTypesElsewhereInTheSameBuild() throws Exception {
		// Positive control: the same byte scan, over the rest of the kernel, must find plenty. Without this a clean
		// result above could be the reader failing to read rather than the package being clean.
		Path kernel = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main", "net", "neoforbric",
				"kernel").normalize();
		assertTrue(Files.isDirectory(kernel), "the kernel is not compiled: " + kernel);

		int scanned = 0;
		int withGameTypes = 0;
		try (Stream<Path> walk = Files.walk(kernel)) {
			for (Path file : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
				scanned++;
				String pool = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1);
				if (FORBIDDEN.stream().anyMatch(pool::contains)) {
					withGameTypes++;
				}
			}
		}
		assertTrue(scanned > 300, "expected the kernel's classes, scanned " + scanned);
		assertTrue(withGameTypes > 100, "the reader found only " + withGameTypes + " of " + scanned + " classes naming a game type");
	}
}

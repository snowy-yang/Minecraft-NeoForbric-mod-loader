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

package net.neoforbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.neoforbric.api.CompatibilityFinding;
import net.neoforbric.api.CompatibilityFindings;
import net.neoforbric.api.ModCatalog;
import net.neoforbric.kernel.ui.CompatibilityDecision;
import net.neoforbric.kernel.boot.LateServerCompatibility;

/**
 * {@link ServerCompatibilityTickInjector}'s output, run: the spliced {@code MinecraftServer.tickServer}
 * hands the server to {@code KernelServerCompatibilityTicks.onCompatibilityTick}, which hands it to
 * {@link LateServerCompatibility} — and a late required loss stops a strict dedicated server through the
 * game's own {@code halt} path. The hook is the kernel's real game-side
 * {@code KernelServerCompatibilityTicks}, compiled from {@code src/runtime/java} against a stand-in
 * {@code MinecraftServer} that carries the same member shape the splice and the hook read: an
 * {@code isDedicatedServer()} answer and a {@code halt(boolean)} that records the call. The boot-side
 * decision classes are the real ones, reached through the parent loader.
 */
@ExecutesInjector(ServerCompatibilityTickInjector.class)
@ResourceLock("ModCatalog")
@ResourceLock("system-properties")
class ServerCompatibilityTickInjectorExecutionTest {
	private static final Path RUNTIME = Path.of("src/runtime/java/net/neoforbric/kernel/runtime");
	private static final String SERVER = "net.minecraft.server.MinecraftServer";
	private static final String SERVER_INTERNAL = "net/minecraft/server/MinecraftServer";

	private static final Map<String, String> STAND_INS = Map.of(SERVER, """
			package net.minecraft.server;

			public class MinecraftServer {
				public int haltedWith = Integer.MIN_VALUE;
				public int compatTicks;

				public boolean isDedicatedServer() {
					return true;
				}

				public void halt(boolean waitForServer) {
					haltedWith = waitForServer ? 1 : 0;
				}

				public void tickServer(java.util.function.BooleanSupplier hasTimeLeft) {
					compatTicks++;
					if (hasTimeLeft.getAsBoolean()) {
						return;
					}
					throw new IllegalStateException("the tick ran out of time");
				}
			}
			""");

	@BeforeEach @AfterEach void reset() {
		CompatibilityFindings.reset();
		CompatibilityDecision.reset();
		System.clearProperty(CompatibilityDecision.PROPERTY);
		ModCatalog.publish(List.of());
	}

	/** The harness has no instance-field reader; the stand-in's fields are public and inherited. */
	private static Object field(Object receiver, String name) throws ReflectiveOperationException {
		return receiver.getClass().getField(name).get(receiver);
	}

	private static Map<String, byte[]> compile(Path work, boolean integrated) throws Exception {
		Map<String, String> sources = new HashMap<>(STAND_INS);
		Path source = RUNTIME.resolve("KernelServerCompatibilityTicks.java");
		assertTrue(Files.isRegularFile(source), "the game-side hook's source is part of the checkout: " + source.toAbsolutePath());
		sources.put("net/neoforbric/kernel/runtime/KernelServerCompatibilityTicks.java", Files.readString(source));
		if (integrated) {
			sources.put("net/minecraft/server/IntegratedServer.java", """
					package net.minecraft.server;

					public class IntegratedServer extends MinecraftServer {
						@Override public boolean isDedicatedServer() {
							return false;
						}
					}
					""");
		}
		return InjectorExecution.compile(work, sources);
	}

	private void record(boolean required) {
		CompatibilityFindings.record(new CompatibilityFinding("late", "probe", "test feature", "test",
				CompatibilityFinding.Confidence.CONFIRMED, required, "late execution test", List.of()));
	}

	@Test void aLateRequiredLossStopsAStrictDedicatedServerThroughTheGamesOwnHalt(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = compile(work, false);
		byte[] plain = original.get(SERVER_INTERNAL);
		byte[] spliced = new ServerCompatibilityTickInjector().transform(SERVER, plain, null);
		assertNotSame(plain, spliced, "the stand-in tickServer gained the completed-tick seam");
		assertEquals("", InjectorExecution.verify(spliced, InjectorExecution.load(original)));

		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(SERVER_INTERNAL, spliced);
		ClassLoader loader = InjectorExecution.load(classes);
		var constructor = loader.loadClass(SERVER).getDeclaredConstructor();
		constructor.setAccessible(true);
		Object server = constructor.newInstance();
		System.setProperty(CompatibilityDecision.PROPERTY, "strict");
		InjectorExecution.invoke(server, "tickServer", (BooleanSupplier) () -> true);
		assertEquals(1, field(server, "compatTicks"), "the tick itself ran");
		assertEquals(Integer.MIN_VALUE, field(server, "haltedWith"), "no loss recorded yet: no halt");

		record(true);
		InjectorExecution.invoke(server, "tickServer", (BooleanSupplier) () -> true);
		assertEquals(0, field(server, "haltedWith"), "halt(false): save, then stop");
	}

	@Test void aNonRequiredLossLeavesTheServerRunning(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = compile(work, false);
		byte[] spliced = new ServerCompatibilityTickInjector().transform(SERVER, original.get(SERVER_INTERNAL), null);
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(SERVER_INTERNAL, spliced);
		ClassLoader loader = InjectorExecution.load(classes);
		var constructor = loader.loadClass(SERVER).getDeclaredConstructor();
		constructor.setAccessible(true);
		Object server = constructor.newInstance();

		System.setProperty(CompatibilityDecision.PROPERTY, "strict");
		record(false);
		InjectorExecution.invoke(server, "tickServer", (BooleanSupplier) () -> true);
		assertEquals(1, field(server, "compatTicks"), "the tick ran");
		assertEquals(Integer.MIN_VALUE, field(server, "haltedWith"), "an optional loss never halts");
	}

	@Test void anIntegratedServerDefersToTheClient(@TempDir Path work) throws Throwable {
		// Not a dedicated server: the subclass's answer flipped, the hook's dispatch follows it.
		Map<String, byte[]> original = compile(work, true);
		byte[] spliced = new ServerCompatibilityTickInjector().transform(SERVER, original.get(SERVER_INTERNAL), null);
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(SERVER_INTERNAL, spliced);
		ClassLoader loader = InjectorExecution.load(classes);
		var constructor = loader.loadClass("net.minecraft.server.IntegratedServer").getDeclaredConstructor();
		constructor.setAccessible(true);
		Object server = constructor.newInstance();

		System.setProperty(CompatibilityDecision.PROPERTY, "strict");
		record(true);
		InjectorExecution.invoke(server, "tickServer", (BooleanSupplier) () -> true);
		assertEquals(Integer.MIN_VALUE, field(server, "haltedWith"),
				"the client's native screen owns the decision on an integrated server");
	}

	@Test void theTickThatThrowsStillRanItsOwnBody(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = compile(work, false);
		byte[] spliced = new ServerCompatibilityTickInjector().transform(SERVER, original.get(SERVER_INTERNAL), null);
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(SERVER_INTERNAL, spliced);
		ClassLoader loader = InjectorExecution.load(classes);
		var constructor = loader.loadClass(SERVER).getDeclaredConstructor();
		constructor.setAccessible(true);
		Object server = constructor.newInstance();
		try {
			InjectorExecution.invoke(server, "tickServer", (BooleanSupplier) () -> false);
			fail("the stand-in's own exception propagates: the seam never swallows it");
		} catch (IllegalStateException expected) {
			assertEquals("the tick ran out of time", expected.getMessage());
		}
		assertEquals(1, field(server, "compatTicks"));
	}
}

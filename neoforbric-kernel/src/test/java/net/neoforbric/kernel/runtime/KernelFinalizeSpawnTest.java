/* Copyright 2026 The NeoForbric Project. Licensed under the Apache License, Version 2.0. */
package net.neoforbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One Forge family on this anchor: NeoForge's. {@code EventHooks.finalizeMobSpawn} and
 * {@code finalizeMobSpawnSpawner} each post exactly one {@code FinalizeSpawnEvent} on {@code NeoForge.EVENT_BUS},
 * finalize the mob once when the event was not canceled — the spawner hook only when it was asked to initialize —
 * and hand the finalization the event's own difficulty, spawn reason and spawn data.
 *
 * <p>Re-anchored from a two-family model (NeoForge + a MinecraftForge {@code MobSpawnEvent.FinalizeSpawn}). This
 * build carries no {@code net/minecraftforge} class at all and {@code EventHooks} posts no such event;
 * introduction.en.md §8 records the deletion. The tests below pin the single-family behaviour the real bytecode has,
 * which is also why the trace never contains a second event name.
 */
class KernelFinalizeSpawnTest {
	@TempDir Path directory;

	@Test void oneNeoForgeEventThenOneFinalizationWithWhatItLeft() throws Exception {
		try (FinalizeSpawnFixture fixture = new FinalizeSpawnFixture(directory)) {
			Object chosenDifficulty = fixture.newDifficulty(), chosenData = fixture.newData();
			fixture.set("neo", (Consumer<Object>) event -> {
				set(event, "setDifficulty", chosenDifficulty);
				set(event, "setSpawnData", chosenData);
			});
			Object result = fixture.finalizeMobSpawn();
			assertEquals(List.of("neo", "finalize"), fixture.trace(), "one event, then the finalization");
			assertEquals(1, fixture.count("neoPosts"));
			assertEquals(1, fixture.count("finalizes"), "the mob is finalized exactly once");
			assertSame(chosenDifficulty, fixture.value("finalDifficulty"), "the listener's difficulty reaches the finalization");
			assertSame(chosenData, fixture.value("finalData"), "and the spawn data the one finalization receives");
			assertSame(fixture.value("finalResult"), result, "finalizeSpawn's own answer is returned");
		}
	}

	@Test void theFinalizationReceivesTheEventsSpawnReason() throws Exception {
		try (FinalizeSpawnFixture fixture = new FinalizeSpawnFixture(directory)) {
			fixture.finalizeMobSpawn();
			assertEquals("TRIAL_SPAWNER", String.valueOf(fixture.value("finalReason")),
					"the event's getSpawnType is what EventHooks hands Mob.finalizeSpawn");
		}
	}

	@Test void aCancelSkipsTheFinalization() throws Exception {
		try (FinalizeSpawnFixture fixture = new FinalizeSpawnFixture(directory)) {
			fixture.set("neo", (Consumer<Object>) event -> set(event, "setCanceled", true));
			assertNull(fixture.finalizeMobSpawn(), "a canceled event returns null");
			assertEquals(List.of("neo"), fixture.trace(), "the event was still posted");
			assertEquals(0, fixture.count("finalizes"));
		}
	}

	@Test void aVetoIsNotACancelSoTheMobIsStillFinalized() throws Exception {
		try (FinalizeSpawnFixture fixture = new FinalizeSpawnFixture(directory)) {
			fixture.set("neo", (Consumer<Object>) event -> set(event, "setSpawnCancelled", true));
			fixture.finalizeMobSpawn();
			assertTrue(fixture.vetoed(), "the world insertion stays vetoed");
			assertEquals(1, fixture.count("finalizes"), "a veto is not a cancel: the mob is still finalized");
		}
	}

	@Test void theTrialSpawnerFinalizesOnlyWhenAskedToInitialize() throws Exception {
		try (FinalizeSpawnFixture fixture = new FinalizeSpawnFixture(directory)) {
			Object chosenData = fixture.newData();
			fixture.set("neo", (Consumer<Object>) event -> set(event, "setSpawnData", chosenData));
			Object event = fixture.finalizeTrialSpawner(true);
			assertEquals(List.of("neo", "finalize"), fixture.trace());
			assertEquals(1, fixture.count("finalizes"), "initialize=true finalizes once");
			assertSame(chosenData, fixture.value("finalData"));
			assertSame(chosenData, fixture.eventValue(event, "getSpawnData"), "the returned event carries what the listener left");
			assertNotNull(fixture.value("lastNeo").getClass().getField("spawner").get(fixture.value("lastNeo")),
					"NeoForge's event names its spawner");
		}
		try (FinalizeSpawnFixture fixture = new FinalizeSpawnFixture(directory.resolve("uninitialized"))) {
			Object event = fixture.finalizeTrialSpawner(false);
			assertEquals(List.of("neo"), fixture.trace(), "initialize=false posts the event but does not finalize");
			assertEquals(0, fixture.count("finalizes"));
			assertFalse(fixture.canceled(event), "the event is returned either way, and it was not canceled");
		}
	}

	@Test void aCancelAtTheTrialSpawnerIsReportedAndNothingIsFinalized() throws Exception {
		try (FinalizeSpawnFixture fixture = new FinalizeSpawnFixture(directory)) {
			fixture.set("neo", (Consumer<Object>) event -> set(event, "setCanceled", true));
			Object event = fixture.finalizeTrialSpawner(true);
			assertTrue(fixture.canceled(event), "the returned event is the canceled one");
			assertEquals(0, fixture.count("finalizes"));
		}
	}

	private static void set(Object event, String setter, Object value) {
		try {
			for (var method : event.getClass().getMethods()) {
				if (method.getName().equals(setter) && method.getParameterCount() == 1) { method.invoke(event, value); return; }
			}
			throw new AssertionError(setter);
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
	}
}

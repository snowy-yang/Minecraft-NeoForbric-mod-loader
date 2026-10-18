/* Copyright 2026 The NeoForbric Project. Licensed under the Apache License, Version 2.0. */
package net.neoforbric.kernel.runtime;

import net.neoforbric.kernel.TestFixtures;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

/**
 * The compiled KernelFinalizeSpawn against NeoForge's actual spawner hook bytecode, with the game and the event
 * bus replaced by counting stand-ins at the boundary: every event post, every finalization and the veto flag are
 * observed.
 *
 * <p>Re-anchored to 1.21.1 and to a NeoForge-only Forge family. The previous version modelled TWO Forge families —
 * NeoForge's {@code FinalizeSpawnEvent} and a MinecraftForge {@code MobSpawnEvent.FinalizeSpawn} — and stubbed the
 * game's spawn-reason enum as {@code net.minecraft.world.entity.EntitySpawnReason}. Neither exists on this anchor:
 * <ul>
 *   <li>the spawn reason on 1.21.1 is {@code net.minecraft.world.entity.MobSpawnType}, which is what
 *       {@code KernelFinalizeSpawn} and both {@code EventHooks} signatures name, and</li>
 *   <li>this build carries no {@code net/minecraftforge} class at all (the carrier has zero such entries) and
 *       {@code EventHooks} posts exactly one event, NeoForge's own, on {@code NeoForge.EVENT_BUS}.</li>
 * </ul>
 * introduction.en.md §8 records the deletion: "there is one Forge family, its events are NeoForge's own, and the
 * patched base posts them itself". The stubs below therefore model that one family, and the tests assert the
 * single-post, single-finalization behaviour the real bytecode has.
 */
final class FinalizeSpawnFixture implements AutoCloseable {
	private final URLClassLoader loader;
	private final Class<?> probe, helper;
	private final Object mob;

	FinalizeSpawnFixture(Path directory) throws Exception {
		Path classes = directory.resolve("classes"); Files.createDirectories(classes);
		List<String> args = new ArrayList<>(List.of("-proc:none", "-d", classes.toString()));
		for (var source : sources().entrySet()) {
			Path path = directory.resolve("src/" + source.getKey().replace('.', '/') + ".java");
			Files.createDirectories(path.getParent()); Files.writeString(path, source.getValue()); args.add(path.toString());
		}
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, errors, errors, args.toArray(String[]::new)), errors.toString());
		// NeoForge's own two hooks, taken verbatim from the carrier: finalizeMobSpawn is what finalizeMobSpawn
		// calls, and finalizeMobSpawnSpawner is the spawner entry point finalizeTrialSpawner calls with
		// initialize=false. BOTH have to be lifted in — keeping only the spawner one left the plain hook resolving
		// to the compile-only stub below, whose body is empty, so the call met NoSuchMethodError.
		ClassNode original = new ClassNode();
		new ClassReader(TestFixtures.stagedClass("neoforge-runtime/neoforge-runtime.jar", "net.neoforged.neoforge.event.EventHooks")).accept(original, 0);
		ClassWriter writer = new ClassWriter(0); writer.visit(original.version, original.access, original.name, null, "java/lang/Object", null);
		for (String name : List.of("finalizeMobSpawn", "finalizeMobSpawnSpawner")) {
			var hook = original.methods.stream().filter(m -> m.name.equals(name)).findFirst()
					.orElseThrow(() -> new AssertionError("NeoForge's EventHooks no longer carries " + name));
			hook.accept(writer);
		}
		writer.visitEnd();
		Files.write(classes.resolve("net/neoforged/neoforge/event/EventHooks.class"), writer.toByteArray());
		Path runtime = Path.of(System.getProperty("neoforbric.test.runtimeClasses", "build/classes/java/runtime"));
		assertTrue(Files.isRegularFile(runtime.resolve("net/neoforbric/kernel/runtime/KernelFinalizeSpawn.class")));
		loader = new URLClassLoader(new URL[] { classes.toUri().toURL(), runtime.toUri().toURL(),
				Path.of("build/classes/java/main").toUri().toURL() }, ClassLoader.getPlatformClassLoader());
		probe = type("fixture.FinalizeProbe");
		helper = type("net.neoforbric.kernel.runtime.KernelFinalizeSpawn");
		mob = probe.getField("mob").get(null);
	}

	/** {@code KernelFinalizeSpawn.finalizeMobSpawn(mob, level, difficulty, TRIAL_SPAWNER, data)}. */
	Object finalizeMobSpawn() throws Exception {
		return call(method("finalizeMobSpawn"), mob, value("level"), value("difficulty"), reason(), value("originalData"));
	}

	/** {@code KernelFinalizeSpawn.finalizeTrialSpawner(..., spawner, initialize)}. */
	Object finalizeTrialSpawner(boolean initialize) throws Exception {
		return call(method("finalizeTrialSpawner"), mob, value("level"), value("difficulty"), reason(), value("originalData"),
				value("spawner"), initialize);
	}

	/** The 1.21.1 spawn reason the production signatures and both EventHooks overloads name. */
	private Object reason() throws Exception {
		return type("net.minecraft.world.entity.MobSpawnType").getField("TRIAL_SPAWNER").get(null);
	}

	private Method method(String name) {
		for (Method method : helper.getMethods()) if (method.getName().equals(name)) return method;
		throw new AssertionError(name);
	}

	private Object call(Method method, Object... args) throws Exception {
		try { return method.invoke(null, args); }
		catch (InvocationTargetException wrapped) {
			if (wrapped.getCause() instanceof RuntimeException failure) throw failure;
			if (wrapped.getCause() instanceof Error failure) throw failure;
			throw wrapped;
		}
	}

	Object value(String name) throws Exception { return probe.getField(name).get(null); }
	void set(String name, Object value) throws Exception { probe.getField(name).set(null, value); }
	int count(String name) throws Exception { return probe.getField(name).getInt(null); }
	boolean vetoed() throws Exception { return (boolean) mob.getClass().getMethod("isSpawnCancelled").invoke(mob); }
	Object newData() throws Exception { return type("fixture.Data").getConstructor().newInstance(); }
	Object newDifficulty() throws Exception { return type("net.minecraft.world.DifficultyInstance").getConstructor().newInstance(); }
	@SuppressWarnings("unchecked") List<String> trace() throws Exception { return (List<String>) value("trace"); }
	boolean canceled(Object event) throws Exception { return (boolean) event.getClass().getMethod("isCanceled").invoke(event); }
	Object eventValue(Object event, String getter) throws Exception { return event.getClass().getMethod(getter).invoke(event); }
	private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, true, loader); }
	@Override public void close() throws Exception { loader.close(); }

	private static Map<String, String> sources() {
		Map<String, String> out = new LinkedHashMap<>();
		for (String name : List.of("net.minecraft.world.DifficultyInstance", "com.mojang.datafixers.util.Either",
				"net.minecraft.world.level.BaseSpawner", "net.minecraft.world.level.storage.ValueInput")) {
			int dot = name.lastIndexOf('.'); out.put(name, "package " + name.substring(0, dot) + "; public class " + name.substring(dot + 1) + " {}");
		}
		for (String name : List.of("net.minecraft.world.entity.SpawnGroupData", "net.minecraft.world.level.ServerLevelAccessor")) {
			int dot = name.lastIndexOf('.'); out.put(name, "package " + name.substring(0, dot) + "; public interface " + name.substring(dot + 1) + " {}");
		}
		// 1.21.1's spawn reason. TRIAL_SPAWNER is the constant the fixture drives both entry points with.
		out.put("net.minecraft.world.entity.MobSpawnType", "package net.minecraft.world.entity; public enum MobSpawnType { COMMAND, TRIAL_SPAWNER }");
		out.put("fixture.Data", "package fixture; public class Data implements net.minecraft.world.entity.SpawnGroupData {}");
		out.put("fixture.Level", "package fixture; public class Level implements net.minecraft.world.level.ServerLevelAccessor {}");
		out.put("net.minecraft.world.entity.Entity", "package net.minecraft.world.entity; public class Entity { public double getX(){return 1;} public double getY(){return 2;} public double getZ(){return 3;} }");
		out.put("net.minecraft.world.entity.Mob", """
			package net.minecraft.world.entity;
			public class Mob extends Entity {
			 private boolean cancelled;
			 public void setSpawnCancelled(boolean value){cancelled=value;} public boolean isSpawnCancelled(){return cancelled;}
			 public SpawnGroupData finalizeSpawn(net.minecraft.world.level.ServerLevelAccessor level,net.minecraft.world.DifficultyInstance difficulty,MobSpawnType reason,SpawnGroupData data){
			  fixture.FinalizeProbe.finalizes++; fixture.FinalizeProbe.trace.add("finalize");
			  fixture.FinalizeProbe.finalDifficulty=difficulty; fixture.FinalizeProbe.finalData=data; fixture.FinalizeProbe.finalReason=reason;
			  return fixture.FinalizeProbe.finalResult;
			 }
			}
			""");
		out.put("net.neoforged.neoforge.common.extensions.IOwnedSpawner", "package net.neoforged.neoforge.common.extensions; public interface IOwnedSpawner { com.mojang.datafixers.util.Either getOwner(); }");
		out.put("fixture.Spawner", "package fixture; public class Spawner implements net.neoforged.neoforge.common.extensions.IOwnedSpawner { public com.mojang.datafixers.util.Either getOwner(){return new com.mojang.datafixers.util.Either();} }");
		out.put("net.neoforged.bus.api.Event", "package net.neoforged.bus.api; public class Event {}");
		out.put("net.neoforged.bus.api.IEventBus", "package net.neoforged.bus.api; public interface IEventBus { Event post(Event event); }");
		// The bus the real EventHooks posts on: NeoForge.EVENT_BUS, one family, no MinecraftForge side.
		out.put("net.neoforged.neoforge.common.NeoForge", "package net.neoforged.neoforge.common; public class NeoForge { public static final net.neoforged.bus.api.IEventBus EVENT_BUS=fixture.FinalizeProbe.neoBus; }");
		String common = " private net.minecraft.world.entity.Mob mob; private net.minecraft.world.DifficultyInstance difficulty; private net.minecraft.world.entity.MobSpawnType reason;"
				+ " private net.minecraft.world.entity.SpawnGroupData data; private boolean canceled; public Object spawner;"
				+ " public net.minecraft.world.DifficultyInstance getDifficulty(){return difficulty;} public void setDifficulty(net.minecraft.world.DifficultyInstance d){difficulty=d;}"
				+ " public net.minecraft.world.entity.SpawnGroupData getSpawnData(){return data;} public void setSpawnData(net.minecraft.world.entity.SpawnGroupData d){data=d;}"
				+ " public boolean isCanceled(){return canceled;} public void setCanceled(boolean c){canceled=c;} public net.minecraft.world.entity.Mob getEntity(){return mob;}"
				+ " public void setSpawnCancelled(boolean c){mob.setSpawnCancelled(c);} public boolean isSpawnCancelled(){return mob.isSpawnCancelled();}";
		// The real 1.21.1 shape: FinalizeSpawnEvent extends MobSpawnEvent and implements ICancellableEvent, whose
		// isCanceled/setCanceled are what EventHooks branches on.
		out.put("net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent", "package net.neoforged.neoforge.event.entity.living; public class FinalizeSpawnEvent extends net.neoforged.bus.api.Event {"
				+ common + " public net.minecraft.world.entity.MobSpawnType getSpawnType(){return reason;}"
				+ " public FinalizeSpawnEvent(net.minecraft.world.entity.Mob m,net.minecraft.world.level.ServerLevelAccessor l,double x,double y,double z,net.minecraft.world.DifficultyInstance d,"
				+ "net.minecraft.world.entity.MobSpawnType r,net.minecraft.world.entity.SpawnGroupData g,com.mojang.datafixers.util.Either s){mob=m;difficulty=d;reason=r;data=g;spawner=s;} }");
		// Compile-only; replaced by NeoForge's actual hook before loading.
		out.put("net.neoforged.neoforge.event.EventHooks", "package net.neoforged.neoforge.event; public class EventHooks { public static net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent finalizeMobSpawnSpawner(net.minecraft.world.entity.Mob m,net.minecraft.world.level.ServerLevelAccessor l,net.minecraft.world.DifficultyInstance d,net.minecraft.world.entity.MobSpawnType r,net.minecraft.world.entity.SpawnGroupData g,net.neoforged.neoforge.common.extensions.IOwnedSpawner s,boolean f){return null;} }");
		out.put("fixture.FinalizeProbe", """
			package fixture;
			import java.util.*; import java.util.function.*;
			import net.minecraft.world.entity.*; import net.minecraft.world.DifficultyInstance;
			import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
			public class FinalizeProbe {
			 public static final List<String> trace=new ArrayList<>(); public static int neoPosts,finalizes;
			 public static final Mob mob=new Mob(); public static final Level level=new Level(); public static final Spawner spawner=new Spawner();
			 public static DifficultyInstance difficulty=new DifficultyInstance(),finalDifficulty;
			 public static SpawnGroupData originalData=new Data(),finalData,finalResult=new Data(); public static MobSpawnType finalReason;
			 public static Consumer<FinalizeSpawnEvent> neo=e->{};
			 public static FinalizeSpawnEvent lastNeo;
			 public static final net.neoforged.bus.api.IEventBus neoBus=e->{neoPosts++;trace.add("neo");lastNeo=(FinalizeSpawnEvent)e;neo.accept(lastNeo);return e;};
			}
			""");
		return out;
	}
}

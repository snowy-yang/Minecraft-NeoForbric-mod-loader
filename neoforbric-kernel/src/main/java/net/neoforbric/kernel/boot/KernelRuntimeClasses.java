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

package net.neoforbric.kernel.boot;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.neoforbric.kernel.classloading.NeoForbricClassLoader;
import net.neoforbric.kernel.util.NeoForbricLog;

/**
 * The boot→game seam: every {@code net.neoforbric.kernel.runtime.} class the boot side names, and every method it
 * calls on one.
 *
 * <h2>Why a registry, and why it is checked at boot</h2>
 *
 * <p>The boot side cannot name a game-side class as a TYPE — that is the whole reason the game side exists — so
 * it names them as STRINGS: a {@code Class.forName} argument, a {@code getMethod} name, an ASM internal name.
 * Nothing in the compiler relates a string to the code that satisfies it. A string that has drifted produces no
 * error and no warning; it produces a {@code ClassNotFoundException} or {@code NoSuchMethodException} at the
 * moment of USE, which for these is the middle of a mod's construction.
 *
 * <p>Two failures are worth separating and both are caught here. A boot jar built on a machine with no staged
 * artifacts carries no {@code neoforbric-kernel-runtime.jar} at all — it launches, boots, loads mods, and dies on
 * the first game-side class with a message that names the class and says nothing about the build. And a
 * game-side method renamed without its caller produces the same shape of report one layer deeper. Checking the
 * whole seam once, right after the pipeline is assembled, turns both into one line at the top of the log.
 *
 * <p>This lists the SEAM, not the whole game side. A name belongs here when boot-side code spells it; the classes
 * a game-side class reaches on its own are ordinary Java to it, checked by javac, and listing them would be
 * listing things that cannot drift. {@code KernelRuntimeClassesTest} enforces that correspondence in both
 * directions — a registry nobody is forced to update goes stale silently, which is the defect it exists to
 * prevent, one level up.
 *
 * <p>{@link Origin#GENERATED} entries are deliberately NOT checked: they are emitted by a boot-side
 * {@code ClassWriter} on demand and are correctly absent from the jar. They are listed anyway so this file is the
 * one place that answers "what is on the game side, and how does it get there".
 */
public final class KernelRuntimeClasses {
	/** How a game-side kernel class comes into being. */
	public enum Origin {
		/** Compiled from {@code src/runtime/java} and delivered in {@code neoforbric-kernel-runtime.jar}. */
		COMPILED,
		/**
		 * Emitted at runtime by a boot-side ASM {@code ClassWriter}. Correct — and unavoidable — where the class
		 * must implement a type that is NOT on the game source set's compile classpath, i.e. anything from
		 * fabric-api, which is a mod the user installs rather than a staged artifact.
		 */
		GENERATED,
	}

	/**
	 * A static method the boot side calls across the seam.
	 *
	 * <p>Every type named here is a JDK type on purpose. Game objects cross this boundary as {@code Object} —
	 * they have to, the boot side cannot name them — so the signature is expressible on both sides, and that is
	 * what makes it checkable from here at all.
	 */
	public record Call(String name, Class<?> returns, Class<?>... parameters) {
	}

	private record Entry(Origin origin, List<Call> calls) {
	}

	/** Binary name → how it is delivered and what is called on it. Insertion-ordered for a stable message. */
	private static final Map<String, Entry> CLASSES = new LinkedHashMap<>();

	static {
		// The full-power game-side lookup NeoForge's EventBus needs to spin listener lambdas. See KernelGameLookup.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelGameLookupHelper", new Entry(Origin.COMPILED, List.of(
				new Call("lookup", MethodHandles.Lookup.class))));
		// The mod-container factory: the whole IModInfo/IModFileInfo/IModFile/IConfigurable chain plus the
		// ModContainer itself. Only this entry point is named from the boot side; the five classes behind it are
		// reached through it, game-side, with the compiler checking every call. See KernelModContainerFactory.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelContainers", new Entry(Origin.COMPILED, List.of(
				new Call("container", Object.class, String.class, Object.class, Path.class),
				new Call("container", Object.class, String.class, Object.class, Path.class, Object.class),
				new Call("modInfo", Object.class, String.class, Path.class),
				new Call("minecraftContainer", Object.class))));
		// Encoding a payload in the Fabric play context. Every parameter is Object (netty is not on the runtime
		// source set's compile path), so the call CAN be checked: a rename on either side becomes one line at the
		// top of the log instead of an AbstractMethodError inside the netty pipeline.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelPacketContext", new Entry(Origin.COMPILED, List.of(
				new Call("encodeInFabricContext", void.class, Object.class, Object.class, Object.class,
						Object.class))));
		// Serving a Forge-family mod jar's own data/ to the server datapack repository. The POLICY — which jars
		// carry data, who owns them, what each pack is called and how they stack — stays boot-side in
		// KernelDataPacks, where it names no game type and is tested as such. See KernelDataPackSource.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelDataPackSource", new Entry(Origin.COMPILED, List.of(
				new Call("buildPack", Object.class, String.class, Path.class, Object.class),
				new Call("addSource", void.class, Object.class, List.class, String.class))));
		// Serving an ecosystem jar's assets/ to the CLIENT resource repository: every Pack, the visible parent
		// that lets a player's own pack sit above mod textures, the overlay count and the RepositorySource. The
		// policy stays boot-side in KernelClientPacks. See KernelClientPackSource.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelClientPackSource", new Entry(Origin.COMPILED, List.of(
				new Call("buildPack", Object.class, String.class, Path.class, boolean.class, boolean.class),
				new Call("buildParentPack", Object.class, String.class, List.class),
				new Call("withOverlays", int.class, List.class),
				new Call("addSource", void.class, Object.class, List.class, String.class))));
		// The registry repairs the kernel makes once its registration window closes. WHEN each runs is
		// hand-ordered in KernelLifecycle.closeRegistrationWindow and stays there; only what each DOES is here.
		// See KernelRegistryContent.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelRegistryContent", new Entry(Origin.COMPILED, List.of(
				new Call("rebuildBlockStateIds", int.class),
				new Call("initialiseBlockStateCaches", int.class),
				new Call("initialiseBlockInfoCaches", boolean.class),
				new Call("sortCreativeTabs", void.class),
				new Call("startCreativeTabProbe", void.class),
				new Call("linkBlockItems", int.class),
				new Call("logRegisteredContent", void.class))));
		// Loading NeoForge's configs: the early pass and the late pass that catches what it could not have seen.
		// WHICH types each covers stays boot-side, where lateConfigTypes has a test. See KernelConfigLoad.
		// Key mappings a mod registered on a bus that is not in ModList, so the one fan-out from Options.<init>
		// never reached it. See KernelForeignShimKeys and KernelForeignShimContext.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelForeignShimKeys", new Entry(Origin.COMPILED, List.of(
				new Call("deliver", int.class, java.util.Collection.class))));
		CLASSES.put("net.neoforbric.kernel.runtime.KernelConfigLoad", new Entry(Origin.COMPILED, List.of(
				new Call("loadEarly", void.class, List.class),
				new Call("openLate", List.class, List.class))));
		CLASSES.put("net.neoforbric.kernel.runtime.KernelCompatibilityPrompts", new Entry(Origin.COMPILED, List.of()));
		// Optional game-side soak driver, named reflectively by KernelSoakHooks; ordinary sessions never load it.
		CLASSES.put("net.neoforbric.kernel.runtime.soak.ClientSoakController", new Entry(Origin.COMPILED,
				List.of(new Call("onTick", void.class, Object.class))));
		CLASSES.put("net.neoforbric.kernel.runtime.KernelPortalSpawn", new Entry(Origin.COMPILED, List.of()));
		CLASSES.put(KernelTransferInterop.BRIDGE, new Entry(Origin.COMPILED, List.of(new Call("install", void.class))));
		CLASSES.put(KernelTransferInterop.ISSUES, new Entry(Origin.COMPILED,
				List.of(new Call("setReporter", void.class, java.util.function.Consumer.class))));
		// Only required, loaded and verified when Team Reborn Energy is installed: its signatures stay free of Reborn
		// types, but its body is the one place that links against them.
		CLASSES.put(KernelTransferInterop.ENERGY, new Entry(Origin.COMPILED, List.of(new Call("install", void.class))));
		// Called by the merged HopperBlockEntity's bytecode (HopperFabricStorageInjector); required whenever
		// fabric-transfer-api and NeoForge's hopper are both present, with or without the bridge.
		CLASSES.put(KernelTransferInterop.HOPPER, new Entry(Origin.COMPILED, List.of()));
		// No PairedTransactions entry on this line: 26.x paired live transaction objects across ecosystems
		// through the neo transfer framework's TransactionContext; 1.21.1 capabilities are immediate with
		// simulate booleans, so the adapters carry no paired journals at all.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelLootModifiers", new Entry(Origin.COMPILED, List.of()));
		CLASSES.put("net.neoforbric.kernel.runtime.KernelFeatureFlags", new Entry(Origin.COMPILED, List.of()));
		CLASSES.put("net.neoforbric.kernel.runtime.KernelSnippets", new Entry(Origin.COMPILED, List.of()));
		CLASSES.put("net.neoforbric.kernel.runtime.KernelWidenedFields", new Entry(Origin.COMPILED, List.of()));
		// The NeoForge setup phases: NeoForge dispatches on a per-mod IEventBus while EventBus 7 resolves a bus
		// from the EVENT plus that mod's BusGroup. See KernelNeoSetup.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelNeoSetup", new Entry(Origin.COMPILED, List.of(
				new Call("firePhase", int.class, Map.class, net.neoforbric.api.ForeignType.class, String.class))));
		// NeoForge's registry phase: NewRegistryEvent, then RegisterEvent for every registry in NeoForge's own
		// order. Which buses and which order stay boot-side, as does the unfreeze/freeze window it all runs
		// inside. See KernelNeoRegistries.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelNeoRegistries", new Entry(Origin.COMPILED, List.of(
				new Call("collect", List.class, boolean.class),
				new Call("fireRegisterEvents", int.class, List.class, List.class),
				new Call("postNewRegistryEvent", int.class, List.class))));
		// Materialises the kernel's own annotation scan into NeoForge's ModFileScanData. The scan itself is
		// boot-side bytecode work; only this last step needs game types. See ModFileScanner.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelScanData", new Entry(Origin.COMPILED, List.of(
				new Call("build", Object.class, List.class, List.class))));
		// Fills the client ResourceManager with its selected packs before mod setup, because the kernel's
		// mod-loading window sits before the game's first resource reload. See KernelClientResources.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelClientResources", new Entry(Origin.COMPILED, List.of(
				new Call("preload", int.class))));
		// Fabric's fluid models for NeoForge's completeness check; FabricFluidModelsInjector calls hasModel.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelFabricFluidModels", new Entry(Origin.COMPILED, List.of()));
		// Vanilla's compostables map behind NeoForge's data map: the merged ComposterBlock's bootStrap records vanilla's
		// own entries and its four composter sites ask the rest (CompostablesFallbackInjector); no boot-side call.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelCompostables", new Entry(Origin.COMPILED, List.of()));
		// No KernelNeoTooltips on this line: 26.x's NeoForge rebuilt item tooltips over appender lists, which the
		// kernel had to build and thread Fabric's component providers through. 1.21.1 keeps vanilla's tooltip body
		// (NeoForge adds attribute lines and the ItemTooltipEvent at the end), so fabric-item-api binds it natively
		// and the kernel draws no tooltips of its own.
		// No KernelRecipeSync on this line: the recipe-sync payload it filtered was 26.x's CommonHooks.sendRecipes
		// path, which 1.21.1 does not have.
		// The unified Mods screen. Named by ModsButtonRedirector as an ASM internal name rather than called, so
		// it has no Call entries — the seam is the class existing and carrying a (Screen) constructor, and a
		// missing runtime jar would otherwise surface as a NoClassDefFoundError the moment a player opens the
		// pause menu.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelModListScreen", new Entry(Origin.COMPILED, List.of()));
		// The three config-screen registries behind that screen's Config button. Called by the client smoke,
		// which is the only thing that can prove a screen belonging to another mod actually opens.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelModConfigScreens", new Entry(Origin.COMPILED, List.of(
				new Call("summary", String.class),
				new Call("firstWithConfig", net.neoforbric.api.ModCatalog.Entry.class, String.class),
				new Call("openById", Object.class, String.class, Object.class))));
		// The merged base's ParticleResources carries two same-named `providers` fields — vanilla's int-keyed one
		// and NeoForge's Identifier-keyed one — and only NeoForge's is written. This turns the other into a live
		// view of it, because fabric-api reads that field DIRECTLY.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelParticleProviders", new Entry(Origin.COMPILED, List.of(
				new Call("intKeyedView", Object.class, java.util.Map.class))));
		// Both ecosystems re-type KeyMapping's vanilla MAP to their own KeyMappingLookup and the merge keeps neither
		// vanilla's descriptor nor a value for it; a mod reading it as a Map died. The transformer adds the field back
		// and this is its value: a view of vanilla's ALL by key.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelKeyMappingMap", new Entry(Origin.COMPILED, List.of(
				new Call("vanillaView", Object.class, java.util.Map.class))));
		// NeoForge worldgen the merge left with no driver: its data maps (nothing named DataMapLoader at all),
		// its biome/structure modifier pass, and the monster-room mob pick that the kernel used to answer by
		// neutering the whole dungeon feature. Its two entry points are called from REWRITTEN CALL SITES, so
		// their descriptors are the ones the merged base and the carrier already had — game types, not the JDK
		// types this registry's own seams use. Listed with no calls for that reason alone.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelNeoWorldgen", new Entry(Origin.COMPILED, List.of()));
		// Called from the position check SpawnPositionCallsInjector writes into BaseSpawner.serverTick.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelSpawnPosition", new Entry(Origin.COMPILED, List.of()));
		// NeoForge's coremods, done after Mixin (NativeCoremodParity) and before it (BiomeInfoRebaseInjector):
		// the call targets and helpers they route to.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelFinalizeSpawn", new Entry(Origin.COMPILED, List.of()));
		CLASSES.put("net.neoforbric.kernel.runtime.KernelBiomeView", new Entry(Origin.COMPILED, List.of()));
		// NeoForge's condition evaluator runs over every datapack element from every pack in the merged base, so
		// a Fabric mod's own condition id failed the whole registry load. This wraps ICondition.CODEC; the call
		// site is an inserted instruction in that class's <clinit>, in Codec, which no JDK type can stand for.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelNeoConditions", new Entry(Origin.COMPILED, List.of()));
		// The reordering Operation a wrapped @WrapOperation handler receives; called from the outer handler
		// MixinWrapOperationShim writes into a guest mixin, so there is no boot-side entry point.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelWrapOperations", new Entry(Origin.COMPILED, List.of()));
		// The vanilla-shaped Operation a Fabric shears wrap is handed at the carrier's canPerformAction; called from the
		// outer handler MixinShearsRelay writes into the guest mixin.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelShears", new Entry(Origin.COMPILED, List.of()));
		// Asked by the merged Fluid.getFluidType() (ForeignFluidTypeInjector) before NeoForge's throwing lookup, and by
		// EntityFluidInteraction.getFluidTypeByTag before its throw (FabricFluidBehaviorInjector).
		CLASSES.put("net.neoforbric.kernel.runtime.KernelFluidTypes", new Entry(Origin.COMPILED, List.of()));
		// MinecraftForge's Hurt, Damage and player Attack, from the seams ForgeDamageSeamsInjector writes into the merged
		// actuallyHurt and Player.hurtServer; Player.<clinit> reports the attack seam in.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelLivingDamage", new Entry(Origin.COMPILED, List.of()));
		// FabricCreativeModeInventoryScreen answered from NeoForge's pager: the bodies CreativePagerBridgeInjector gives
		// the creative screen call the first, and the screen implements the second. Descriptors name game types only.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelCreativePager", new Entry(Origin.COMPILED, List.of()));
		CLASSES.put("net.neoforbric.kernel.runtime.KernelCreativePagerScreen", new Entry(Origin.COMPILED, List.of()));
		// No reload-listener naming classes on this line: 1.21.1 has no name-keyed reload listener graph.
		// fabric-api's own two mixins for fabric:load_conditions cannot apply on the merged base, so nothing
		// evaluated them. Wrapped into ConditionalOps' one codec factory by an inserted instruction, in Codec,
		// which no JDK type can stand for.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelFabricConditions", new Entry(Origin.COMPILED, List.of()));
		// No model-format runtime classes on these lines: 26.x's UnbakedModelParser dispatch and the
		// BlockStateModel.Unbaked codec rework do not exist on 1.21.1, where vanilla's ModelBakery reads block
		// models and each ecosystem's loaders sit where they always did.
		// Between a NeoForge mod's getContextClassLoader() and its cast to FML's TransformingClassLoader
		// (FmlContextLoaderRewriter): LibJF's ASM layer reaches the Mixin weaver through what it returns.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelFmlTransformerView", new Entry(Origin.COMPILED, List.of(
				new Call("contextLoader", ClassLoader.class, ClassLoader.class))));
		// Generated per boot by KernelHudBridge when fabric-rendering-v1 is present: it implements both
		// HudElement and GuiLayer, and fabric-api is not on the runtime source set's compile classpath.
		CLASSES.put("net.neoforbric.kernel.runtime.KernelHudLayer", new Entry(Origin.GENERATED, List.of()));
		// No KernelPipRenderers on this line: the picture-in-picture render framework it served was 26.x's
		// GuiRenderer world, which 1.21.1 does not have.
	}

	private KernelRuntimeClasses() {
	}

	/** Every registered game-side class, mapped to how it is delivered. */
	public static Map<String, Origin> all() {
		Map<String, Origin> out = new LinkedHashMap<>();
		CLASSES.forEach((name, entry) -> out.put(name, entry.origin()));
		return Map.copyOf(out);
	}

	/** The names that must be present in an owned jar for this kernel to be complete. */
	public static List<String> compiled() {
		return CLASSES.entrySet().stream()
				.filter(e -> e.getValue().origin() == Origin.COMPILED)
				.filter(e -> !KernelTransferInterop.ownsOptionalRuntime(e.getKey())
						|| KernelTransferInterop.optionalRuntimeActive(e.getKey()))
				.map(Map.Entry::getKey)
				.toList();
	}

	/** The calls the boot side makes on {@code binaryName}; empty if it is not registered. */
	public static List<Call> callsOn(String binaryName) {
		Entry entry = CLASSES.get(binaryName);
		return entry == null ? List.of() : entry.calls();
	}

	/**
	 * Loads every {@link Origin#COMPILED} class through {@code loader} and resolves every method the boot side
	 * calls on it.
	 *
	 * <p>A real load, not a resource probe, because the two failures worth separating are only distinguishable
	 * that way: a class that is in no owned jar means the boot jar was built without staged artifacts, and a class
	 * that IS there but does not define means the pipeline carrying it broke. Those have different fixes, so they
	 * get different messages. A third, outside the kernel: a class that defines but whose method signatures name a
	 * game type this launch does not have. That is the game jars' fault, not the build's, and it is reported, not
	 * thrown -- {@link LaunchInputCheck} should have stopped such a launch already, and this must not be the place it
	 * dies if it did not.
	 *
	 * <p>Runs after the transform chain and Mixin are installed, so these classes take exactly the path every game
	 * class takes. Nothing targets them, but a self-check that skipped the pipeline would not be checking the
	 * thing that can break.
	 *
	 * <p>{@code initialize = false}: proving the class links is the point; running its static initialiser at boot
	 * is not, and for a class that one day holds game state it would be actively wrong.
	 *
	 * @return true if the whole seam is present and callable
	 */
	public static boolean verify(NeoForbricClassLoader loader) {
		List<String> names = compiled();
		int ok = 0;

		for (String name : names) {
			Class<?> c;
			try {
				c = Class.forName(name, false, loader);
			} catch (ClassNotFoundException | LinkageError absent) {
				if (loader.findResource(name.replace('.', '/') + ".class") == null) {
					NeoForbricLog.error("[NeoForbric/Runtime] the kernel's own game-side class %s is in no owned jar. "
							+ "This boot jar was built without the staged game artifacts, so "
							+ "neoforbric-kernel-runtime.jar was never packed into it, and everything needing a "
							+ "game-side kernel class will fail to link far from here. Fix: put the staged jars "
							+ "in ../neoforbric-loader/run/ and rebuild with ./gradlew jar", name);
				} else {
					NeoForbricLog.error("[NeoForbric/Runtime] the kernel's own game-side class %s is present in an "
							+ "owned jar but would not define: %s", name, String.valueOf(absent));
				}
				continue;
			}

			List<String> broken;
			try {
				broken = unresolvable(c, callsOn(name));
			} catch (LinkageError unlinkable) {
				// Loading with initialize=false resolves nothing, so a class whose method signatures name a game type
				// that is not there loads fine and fails HERE: getMethod resolves the parameter and return types of
				// every public method the class declares, not only the one asked for. Issue #13's runtime jars held
				// no NeoForge, and this NoClassDefFoundError was the whole boot's last word -- on stderr, outside
				// latest.log -- because only NoSuchMethodException was caught.
				NeoForbricLog.error("[NeoForbric/Runtime] the kernel's own game-side class %s is there, but a game type its "
						+ "methods name cannot be loaded: %s. The kernel was built against a game that has it, so the "
						+ "game jars this launch was given (--gameJar / --runtimeJar) are incomplete or from another "
						+ "build. Players: run the NeoForbric installer again with \"Built artifacts\" left empty; "
						+ "developers: python3 tools/dev.py prepare", name, String.valueOf(unlinkable));
				continue;
			}
			if (!broken.isEmpty()) {
				NeoForbricLog.error("[NeoForbric/Runtime] %s is there but the boot side calls methods it does not have: "
						+ "%s. Boot-side call sites name these as strings, so this is not a compile error on "
						+ "either side — it would have surfaced inside mod construction instead",
						name, String.join(", ", broken));
				continue;
			}

			// No check that c.getClassLoader() == loader. It would read well and it can never fail: this package
			// is pinned ALWAYS_GAME, so loadClass routes it to defineGameClass, which either defines it here or
			// throws — there is no path on which it comes back from somewhere else. The invariant that CAN break
			// is the pin itself, and that is a pure function of DelegationPolicy, asserted in
			// KernelRuntimeClassesTest where it can actually be made to fail.
			ok++;
		}

		if (ok == names.size()) {
			NeoForbricLog.info("[NeoForbric/Runtime] game-side kernel classes: %d/%d linked", ok, names.size());
			return true;
		}

		NeoForbricLog.error("[NeoForbric/Runtime] game-side kernel classes: %d/%d linked", ok, names.size());
		return false;
	}

	/** The calls {@code c} cannot satisfy, described the way a reader would need to fix them. */
	private static List<String> unresolvable(Class<?> c, List<Call> calls) {
		List<String> broken = new ArrayList<>();

		for (Call call : calls) {
			try {
				Method m = c.getMethod(call.name(), call.parameters());
				if (!call.returns().isAssignableFrom(m.getReturnType())) {
					broken.add(call.name() + " returns " + m.getReturnType().getSimpleName() + ", not "
							+ call.returns().getSimpleName());
				}
			} catch (NoSuchMethodException missing) {
				broken.add(call.name() + describe(call.parameters()));
			}
		}

		return broken;
	}

	private static String describe(Class<?>[] parameters) {
		List<String> names = new ArrayList<>();
		for (Class<?> p : parameters) names.add(p.getSimpleName());
		return "(" + String.join(", ", names) + ")";
	}
}

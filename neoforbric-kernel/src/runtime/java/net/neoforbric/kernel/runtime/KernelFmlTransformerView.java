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

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import cpw.mods.modlauncher.ClassTransformer;
import cpw.mods.modlauncher.LaunchPluginHandler;
import cpw.mods.modlauncher.TransformingClassLoader;

import org.spongepowered.asm.launch.MixinLaunchPluginLegacy;
import org.spongepowered.asm.service.modlauncher.MixinTransformationHandler;

import net.neoforbric.kernel.mixin.MixinWeaverSlot;
import net.neoforbric.kernel.util.NeoForbricLog;

/**
 * modlauncher's {@code TransformingClassLoader}, as far as a NeoForge mod walks it to reach the live Mixin
 * weaver — built from the carrier's own classes and holding the kernel's weaver, so that what the mod writes
 * back is what weaves.
 *
 * <h2>Who walks it</h2>
 *
 * <p>LibJF's mixin plugin ({@code libjf_unsafe_v0}, the NeoForge build): {@code (TransformingClassLoader)} the
 * context loader, then by reflection {@code classTransformer} on the loader, {@code pluginHandler} on the
 * transformer, {@code LaunchPluginHandler.get("mixin")} to the Mixin plugin, {@code processors.getFirst()} to
 * the transformation handler, and its {@code transformer} — which it wraps in its own {@code AsmTransformer}
 * and writes back. On NeoForge every class goes through Mixin and then LibJF's {@code libjf:asm} patches
 * (LibJF Data Manipulation's resource-pack hook is one). Under the kernel the context loader is
 * {@code NeoForbricClassLoader}, the cast failed, and LibJF logged "Could not initialize LibJF ASM" and applied
 * no patch at all.
 *
 * <h2>What is built, and what is not</h2>
 *
 * <p>Exactly that chain and nothing beside it. {@code TransformingClassLoader}, {@code ClassTransformer} and
 * {@code LaunchPluginHandler} are modlauncher's own classes, allocated without a constructor: the real ones
 * want a {@code TransformStore} of registered transformers and a plugin handler the kernel does not have, and
 * nothing here ever asks them to transform, load or define a class — a guest only reads their fields.
 * {@code MixinLaunchPluginLegacy} and {@code MixinTransformationHandler} are Mixin's modlauncher service
 * classes, allocated the same way: neither has a static initialiser, and the only field filled on the handler
 * is the transformer itself. The plugin handler's {@code plugins} map holds exactly one entry — "mixin", the
 * name LibJF asks for — because its {@code get} reads that map and nothing else.
 *
 * <h2>When it cannot be built</h2>
 *
 * <p>The real context loader comes back, and the guest's cast fails exactly as it did before this existed.
 * Allocating a {@code TransformingClassLoader} initialises {@code ModuleClassLoader}, whose static initialiser
 * needs java.lang.invoke opened; the kernel's launch profile does not open it, and
 * {@code ModuleClassLoaderInitInjector} is what lets that initialiser finish anyway. Nothing thrown while
 * building — {@code ExceptionInInitializerError} included — leaves this method: the caller is a guest's
 * plugin, and it runs while the kernel is defining a class.
 *
 * <p>{@code -Dneoforbric.fmlTransformerView=off}: the real loader, always.
 */
public final class KernelFmlTransformerView {
	private KernelFmlTransformerView() {
	}

	/** The view once built; null until then, and forever when building it failed. */
	private static volatile TransformingClassLoader view;
	private static volatile boolean gaveUp;
	private static boolean saidTooEarly;

	/**
	 * What {@code (TransformingClassLoader) Thread.currentThread().getContextClassLoader()} should cast in a
	 * NeoForge mod class: the view, or {@code real} when there is none. {@code FmlContextLoaderRewriter} puts this
	 * call between those two instructions and leaves the cast in place.
	 */
	public static ClassLoader contextLoader(ClassLoader real) {
		if (!MixinWeaverSlot.enabled()) return real;
		TransformingClassLoader built = view;
		if (built == null && !gaveUp) built = build();
		return built != null ? built : real;
	}

	private static synchronized TransformingClassLoader build() {
		if (view != null || gaveUp) return view;
		IMixinTransformer weaver = MixinWeaverSlot.currentOr(MixinWeaverSlot.original());
		if (weaver == null) {
			// Not a failure to remember: Mixin comes up before any guest plugin can run, so this is only reachable
			// from a class loaded before the kernel's Mixin bootstrap. A later caller may still get the view.
			if (saidTooEarly) return null;
			saidTooEarly = true;
			NeoForbricLog.warn("[NeoForbric/FmlView] a NeoForge mod asked for modlauncher's TransformingClassLoader "
					+ "before Mixin was up; it gets the kernel's loader, and its cast fails as it would have before");
			return null;
		}
		try {
			MixinTransformationHandler handler = allocate(MixinTransformationHandler.class);
			Field transformer = MixinTransformationHandler.class.getDeclaredField("transformer");
			transformer.setAccessible(true);
			transformer.set(handler, weaver);

			MixinLaunchPluginLegacy plugin = allocate(MixinLaunchPluginLegacy.class);
			write(MixinLaunchPluginLegacy.class, "processors", plugin, List.of(handler));

			LaunchPluginHandler plugins = allocate(LaunchPluginHandler.class);
			Map<String, Object> only = new HashMap<>();
			only.put("mixin", plugin);
			write(LaunchPluginHandler.class, "plugins", plugins, only);

			ClassTransformer carrier = allocate(ClassTransformer.class);
			write(ClassTransformer.class, "pluginHandler", carrier, plugins);

			TransformingClassLoader loader = allocate(TransformingClassLoader.class);
			write(TransformingClassLoader.class, "classTransformer", loader, carrier);

			MixinWeaverSlot.watch(() -> {
				try {
					return transformer.get(handler);
				} catch (IllegalAccessException impossible) {
					// setAccessible(true) above; answering "no replacement" keeps the kernel's own weaver.
					return null;
				}
			});
			view = loader;
			NeoForbricLog.info("[NeoForbric/FmlView] a NeoForge mod reaches the Mixin weaver through modlauncher's "
					+ "TransformingClassLoader — it is handed a view whose MIXIN plugin holds the kernel's weaver, "
					+ "and whatever it writes there weaves every class from then on, as on NeoForge");
			return loader;
		} catch (Throwable unbuildable) {
			gaveUp = true;
			NeoForbricLog.warn("[NeoForbric/FmlView] could not build modlauncher's TransformingClassLoader view (%s) — a "
					+ "NeoForge mod that reaches the Mixin weaver through it (LibJF's ASM layer) starts without its "
					+ "class patches, as before", String.valueOf(cause(unbuildable)));
			return null;
		}
	}

	private static void write(Class<?> owner, String name, Object target, Object value) throws ReflectiveOperationException {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	/** An instance with no constructor run — the class IS initialised, which is what a view needs and all it gets. */
	private static <T> T allocate(Class<T> type) throws ReflectiveOperationException {
		Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
		Field theUnsafe = unsafeType.getDeclaredField("theUnsafe");
		theUnsafe.setAccessible(true);
		Object unsafe = theUnsafe.get(null);
		return type.cast(unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, type));
	}

	/**
	 * What actually went wrong: allocation goes through {@code Method.invoke}, so a failed class initialisation
	 * arrives as an {@code InvocationTargetException} around an {@code ExceptionInInitializerError}.
	 */
	private static Throwable cause(Throwable thrown) {
		Throwable t = thrown;
		while ((t instanceof java.lang.reflect.InvocationTargetException || t instanceof ExceptionInInitializerError)
				&& t.getCause() != null) {
			t = t.getCause();
		}
		return t;
	}
}

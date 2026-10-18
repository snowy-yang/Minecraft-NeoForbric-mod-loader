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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import net.neoforbric.kernel.classloading.LoaderProbePolicy;
import net.neoforbric.kernel.transform.FmlContextLoaderRewriter;
import net.neoforbric.kernel.transform.ModuleClassLoaderInitInjector;

/**
 * A NeoForge mod that wraps the Mixin weaver the way NeoForge lets it — through modlauncher's
 * {@code TransformingClassLoader}, its {@code ClassTransformer}, the plugin handler's {@code "mixin"} launch plugin
 * and that plugin's {@code MixinTransformationHandler.transformer} — is the one that weaves from then on: the
 * rewritten cast gets the game-side view, the view holds the kernel's weaver, and the kernel reads the weaver back
 * through {@link MixinWeaverSlot} for every class.
 *
 * <p>1.21.1's FML has no {@code classloading.transformation} package: the walk below is modlauncher's chain, the one
 * {@code KernelFmlTransformerView} builds and the one {@code FmlContextLoaderRewriter} recognises by name. An
 * earlier version of this test walked 26.x's {@code ClassProcessorSet}/{@code ClassProcessorIds.MIXIN}/
 * {@code FMLMixinClassProcessor}, none of which exists here.
 *
 * <p>LibJF's ASM layer is the mod that paid for it: its plugin cast the context loader to that class and got
 * {@code NeoForbricClassLoader}, so "Could not initialize LibJF ASM" and none of its {@code libjf:asm} patches (LibJF
 * Data Manipulation's resource-pack hook) ever ran. The walk below is the walk its {@code MixinPlugin.onLoad}
 * makes, field for field.
 */
class FmlTransformerViewTest {
	private static final Path STAGED = TestFixtures.stagedRoot();
	private static final String PROBE = "com.example.libjf.MixinPlugin";
	/** The class {@code ModuleClassLoaderInitInjector} exists to patch — its name on 1.21.1, not 26.x's. */
	private static final String MODULE_CLASS_LOADER = "cpw.mods.cl.ModuleClassLoader";
	/** modlauncher's loader, the class the kernel's own FmlContextLoaderRewriter recognises. 1.21.1's FML has
	 * no classloading.transformation package: the chain a mod walks is modlauncher's own. */
	private static final String TRANSFORMING_LOADER = "cpw/mods/modlauncher/TransformingClassLoader";

	private final IMixinTransformer kernelWeaver = weaver();

	@BeforeEach
	@AfterEach
	void clean() {
		MixinWeaverSlot.reset();
		System.clearProperty(MixinWeaverSlot.SWITCH);
		forgetTheView();
	}

	/**
	 * {@code KernelFmlTransformerView} remembers its view — and its one refusal — in static fields for the life of
	 * the class, which on this class path is the whole run, and deliberately never retries after a failure. That is
	 * right for a game and wrong for a suite: a case that shows the view cannot be built would poison every later
	 * case in the same JVM. So each case starts from a view that has not been asked yet.
	 */
	private static void forgetTheView() {
		try {
			Class<?> type = Class.forName("net.neoforbric.kernel.runtime.KernelFmlTransformerView", true,
					FmlTransformerViewTest.class.getClassLoader());
			for (String name : List.of("view", "gaveUp", "saidTooEarly")) {
				Field field = type.getDeclaredField(name);
				field.setAccessible(true);
				field.set(null, "view".equals(name) ? null : Boolean.FALSE);
			}
		} catch (ReflectiveOperationException | LinkageError notOnThisLoader) {
			// Nothing was loaded, so nothing was remembered.
		}
	}

	/**
	 * <p>The walk below is the real one — modlauncher's chain, which is what {@code FmlContextLoaderRewriter}
	 * recognises and what {@code KernelFmlTransformerView} builds. It used to be blocked on
	 * {@code ModuleClassLoaderInitInjector.TARGET} naming 26.x's {@code net.neoforged.fml.classloading.ModuleClassLoader}
	 * instead of this anchor's {@code cpw.mods.cl.ModuleClassLoader}, so the injector never fired, the static
	 * initialiser threw {@code InaccessibleObjectException} in a JVM without {@code --add-opens
	 * java.base/java.lang.invoke}, and no view could be built. That is fixed; what this case still needs from the
	 * harness is {@link #forgetTheView()}, because the view a case builds outlives it.
	 */
	@Test
	void aNeoForgeModThatWrapsTheWeaverIsTheOneThatWeaves() throws Exception {
		MixinWeaverSlot.install(kernelWeaver);
		try (GameLoader game = new GameLoader(true, rewritten(plugin()))) {
			Object loader = probe(game);
			assertEquals(TRANSFORMING_LOADER.replace('/', '.'), loader.getClass().getName(),
					"the cast succeeded, on FML's own class");

			// LibJF's MixinPlugin.onLoad, field for field.
			Object classTransformer = read(loader, game.loadClass(TRANSFORMING_LOADER.replace('/', '.')), "classTransformer");
			Object pluginHandler = read(classTransformer, game.loadClass("cpw.mods.modlauncher.ClassTransformer"),
					"pluginHandler");
			Map<?, ?> plugins = (Map<?, ?>) read(pluginHandler,
					game.loadClass("cpw.mods.modlauncher.LaunchPluginHandler"), "plugins");
			Object processor = plugins.get("mixin");
			Class<?> processorType = game.loadClass("org.spongepowered.asm.launch.MixinLaunchPluginLegacy");
			assertInstanceOf(processorType, processor);
			Object handler = ((List<?>) read(processor, processorType, "processors")).getFirst();
			Class<?> handlerType = game.loadClass("org.spongepowered.asm.service.modlauncher.MixinTransformationHandler");
			assertInstanceOf(handlerType, handler);
			Field transformer = handlerType.getDeclaredField("transformer");
			transformer.setAccessible(true);
			assertSame(kernelWeaver, transformer.get(handler), "the view holds the weaver the kernel weaves with");

			// Before the write-back the kernel still weaves with its own.
			assertSame(kernelWeaver, MixinWeaverSlot.currentOr(kernelWeaver));
			IMixinTransformer wrapper = weaver();
			transformer.set(handler, wrapper);
			assertSame(wrapper, MixinWeaverSlot.currentOr(kernelWeaver), "the wrapper weaves every class after this");

			// A second mod walks the same view and finds the first one's wrapper, as on NeoForge.
			assertSame(loader, probe(game));
		}
	}

	@Test
	void withoutTheRewriteTheCastFailsAsItAlwaysDid() throws Exception {
		MixinWeaverSlot.install(kernelWeaver);
		try (GameLoader game = new GameLoader(true, plugin())) {
			assertCastFails(game);
		}
	}

	@Test
	void withoutTheInitialiserFixTheViewCannotExistAndTheCastFailsAsBefore() throws Exception {
		// build.gradle starts the test JVM without NeoForge's --add-opens, which is the launch this case is about.
		assertFalse(Object.class.getModule().isOpen("java.lang.invoke",
				getClass().getClassLoader().getUnnamedModule()), "this JVM opened java.lang.invoke");
		MixinWeaverSlot.install(kernelWeaver);
		try (GameLoader game = new GameLoader(false, rewritten(plugin()))) {
			assertCastFails(game);
			assertSame(kernelWeaver, MixinWeaverSlot.currentOr(kernelWeaver), "no view, so nothing to read back");
			assertCastFails(game); // and asking again neither throws anything new nor retries
		}
	}

	@Test
	void beforeMixinIsUpTheKernelsLoaderComesBack() throws Exception {
		try (GameLoader game = new GameLoader(true, rewritten(plugin()))) {
			assertCastFails(game);
		}
	}

	@Test
	void switchedOffTheKernelsLoaderComesBack() throws Exception {
		MixinWeaverSlot.install(kernelWeaver);
		byte[] rewritten = rewritten(plugin());
		System.setProperty(MixinWeaverSlot.SWITCH, "off");
		try (GameLoader game = new GameLoader(true, rewritten)) {
			assertCastFails(game);
		}
	}

	@Test
	void theSlotAnswersWithTheFallbackUntilSomethingIsWatched() {
		IMixinTransformer fallback = weaver();
		assertSame(fallback, MixinWeaverSlot.currentOr(fallback));
		MixinWeaverSlot.watch(() -> "not a transformer");
		assertSame(fallback, MixinWeaverSlot.currentOr(fallback));
		IMixinTransformer wrapper = weaver();
		MixinWeaverSlot.watch(() -> wrapper);
		assertSame(wrapper, MixinWeaverSlot.currentOr(fallback));
	}

	/** A second boot in one process weaves with its own Mixin, not through the first boot's view of the old one. */
	@Test
	void installingAWeaverForgetsTheSlotOfTheLastOne() {
		MixinWeaverSlot.install(weaver());
		IMixinTransformer oldWrapper = weaver();
		MixinWeaverSlot.watch(() -> oldWrapper);

		IMixinTransformer next = weaver();
		MixinWeaverSlot.install(next);
		assertSame(next, MixinWeaverSlot.currentOr(next));
	}

	/**
	 * The kernel's class pipeline weaves through the slot, not through the transformer it captured at bootstrap:
	 * the captured one is exactly the field nobody would read after a wrapper replaced it.
	 */
	@Test
	void theKernelWeavesWithWhatTheSlotHolds() throws Exception {
		Path compiled = Path.of("build", "classes", "java", "main", "net", "neoforbric", "kernel", "mixin",
				"KernelMixinBootstrap.class");
		assertTrue(Files.isRegularFile(compiled),
				"KernelMixinBootstrap not found in the compiled src/main classes, which exist before any test runs");
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		List<String> weaving = new ArrayList<>();
		for (MethodNode method : node.methods) {
			boolean weaves = false;
			boolean throughSlot = false;
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof MethodInsnNode call)) continue;
				if (call.name.equals("transformClassBytes")) weaves = true;
				if (call.owner.equals("net/neoforbric/kernel/mixin/MixinWeaverSlot") && call.name.equals("currentOr")) {
					throughSlot = true;
				}
			}
			if (weaves) {
				weaving.add(method.name);
				assertTrue(throughSlot, method.name + " weaves with a transformer it did not read from MixinWeaverSlot");
			}
		}
		assertTrue(!weaving.isEmpty(), "found no weaving call to check");
	}

	private static void assertCastFails(GameLoader game) {
		InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> probe(game));
		assertInstanceOf(ClassCastException.class, thrown.getCause());
	}

	private static Object probe(ClassLoader game) throws Exception {
		Method probe = Class.forName(PROBE, true, game).getMethod("onLoad");
		return probe.invoke(null);
	}

	private static Object read(Object target, Class<?> owner, String name) throws Exception {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static byte[] rewritten(byte[] plugin) {
		byte[] out = new FmlContextLoaderRewriter(name -> LoaderProbePolicy.Family.NEOFORGE).transform(PROBE, plugin, null);
		assertTrue(out != plugin, "the rewriter did not touch the plugin");
		return out;
	}

	/**
	 * {@code "classTransformer"; return (TransformingClassLoader) Thread.currentThread().getContextClassLoader();} —
	 * the field name is what marks the method as one that walks to the weaver, which the rewriter requires.
	 */
	private static byte[] plugin() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, PROBE.replace('.', '/'), null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "onLoad", "()Ljava/lang/Object;", null, null);
		mv.visitCode();
		mv.visitLdcInsn("classTransformer");
		mv.visitInsn(Opcodes.POP);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getContextClassLoader", "()Ljava/lang/ClassLoader;", false);
		mv.visitTypeInsn(Opcodes.CHECKCAST, TRANSFORMING_LOADER);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static IMixinTransformer weaver() {
		return (IMixinTransformer) Proxy.newProxyInstance(IMixinTransformer.class.getClassLoader(),
				new Class<?>[] {IMixinTransformer.class}, (proxy, method, args) -> switch (method.getName()) {
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == args[0];
					case "toString" -> "weaver@" + Integer.toHexString(System.identityHashCode(proxy));
					default -> null;
				});
	}

	/**
	 * The game side as far as this path needs it: the kernel's runtime classes, the NeoForge carrier and log4j
	 * (ClassTransformer's logger). ModuleClassLoader goes through the injector, as the kernel's chain would put it;
	 * the plugin is defined from the bytes given.
	 */
	private static final class GameLoader extends URLClassLoader {
		private final boolean fixInitialiser;
		private final byte[] plugin;

		GameLoader(boolean fixInitialiser, byte[] plugin) throws Exception {
			super(urls(), FmlTransformerViewTest.class.getClassLoader());
			this.fixInitialiser = fixInitialiser;
			this.plugin = plugin;
		}

		/**
		 * The two Mixin classes the view is built from are the ones this loader must serve itself: on 1.21.1 the
		 * Mixin launch plugin is Mixin's own, and both it and the transformation handler implement and take
		 * modlauncher's {@code ILaunchPluginService}, which only the carrier has. Defined from the app loader they
		 * cannot see it, and the view cannot be built — so they are defined from here, with the mixin jar beside
		 * the carrier, while every other Mixin class still comes from the app loader so that the weaver the slot
		 * holds is the one the view hands out.
		 */
		private static final List<String> FROM_HERE = List.of(
				"org.spongepowered.asm.launch.MixinLaunchPluginLegacy",
				"org.spongepowered.asm.service.modlauncher.MixinTransformationHandler");

		private static URL[] urls() throws Exception {
			Path compiled = Path.of(System.getProperty("neoforbric.testRuntimeClasses", "build/classes/java/runtime"));
			Path carrier = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");
			TestFixtures.require(Fixture.STAGED, Files.isRegularFile(carrier), "staged runtime classes/carrier absent");
			TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(compiled), "staged runtime classes/carrier absent");
			// A compileOnly dependency build.gradle resolves and hands every test run, staged or not.
			String log4j = System.getProperty("neoforbric.log4jApiForTests", "");
			assertTrue(!log4j.isEmpty() && Files.isRegularFile(Path.of(log4j)), "log4j-api absent: '" + log4j + "'");
			URL mixin = IMixinTransformer.class.getProtectionDomain().getCodeSource().getLocation();
			return new URL[] {compiled.toUri().toURL(), carrier.toUri().toURL(), Path.of(log4j).toUri().toURL(), mixin};
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (!FROM_HERE.contains(name)) return super.loadClass(name, resolve);
			synchronized (getClassLoadingLock(name)) {
				Class<?> loaded = findLoadedClass(name);
				if (loaded == null) loaded = findClass(name);
				if (resolve) resolveClass(loaded);
				return loaded;
			}
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			if (PROBE.equals(name)) return defineClass(name, plugin, 0, plugin.length);
			// 1.21.1's FML does not ship its own module class loader: the one whose static initialiser looks
			// IMPL_LOOKUP up is securejarhandler's, the class TransformingClassLoader extends.
			if (fixInitialiser && MODULE_CLASS_LOADER.equals(name)) {
				try (InputStream in = getResourceAsStream(name.replace('.', '/') + ".class")) {
					byte[] bytes = new ModuleClassLoaderInitInjector().transform(name, in.readAllBytes(), null);
					return defineClass(name, bytes, 0, bytes.length);
				} catch (java.io.IOException unreadable) {
					throw new ClassNotFoundException(name, unreadable);
				}
			}
			return super.findClass(name);
		}
	}
}

package net.neoforbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;

/**
 * What this test used to assert: that {@link FabricFluidBehaviorInjector} rewrites the merged
 * {@code EntityFluidInteraction.getFluidTypeByTag} so an unknown tag is asked about at
 * {@code KernelFluidTypes.byTag} before NeoForge throws, that water and lava are still NeoForge's own answers, that
 * the rewrite verifies and runs, and that two throws in the method make it decline.
 *
 * <p>On 1.21.1 there is no such class, method or API on either side, and each absence was read off the real
 * artifacts rather than assumed:
 *
 * <ul>
 *   <li>{@code net/minecraft/world/entity/EntityFluidInteraction} — absent from the merged base, which has no
 *       {@code FluidInteraction} class at all. It is a 26.x NeoForge class.</li>
 *   <li>{@code getFluidTypeByTag} — no method of that name in any class of the staged NeoForge runtime. 1.21.1's
 *       NeoForge fluid surface is per-type ({@code IEntityExtension.getFluidTypeHeight(FluidType)},
 *       {@code isInFluidType(FluidType)}, {@code getEyeInFluidType()}), with no tag-to-type conversion to
 *       intercept.</li>
 *   <li>the Fabric side — fabric-api 0.116.17 has no {@code EntityFluidInteractionRegistry} and nothing else named
 *       {@code EntityFluidInteraction} or {@code FluidBehavior} in any of its modules. The registry whose tags the
 *       repair existed for is not in this version of the API.</li>
 * </ul>
 *
 * <p>So the injector can never fire here: its anchor class does not load, and neither API side has the seam it was
 * written against. What is asserted instead is that absence, by name, so that whichever side grows it first makes
 * this test say which one. The 1.21.1 equivalent is a design question (the whole tag-to-type conversion is gone),
 * so the gap is recorded rather than guessed at.
 */
@ResourceLock("system-properties")
class FabricFluidBehaviorInjectorTest {
	private static final Path MERGED = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
	private static final Path RUNTIME = TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final String INTERACTION = "net/minecraft/world/entity/EntityFluidInteraction";

	@AfterEach void reset() { System.clearProperty(FabricFluidBehaviorInjector.PROPERTY); }

	@Test void theClassItRepairsIsNotInThisBase() throws Exception {
		TestFixtures.requireFiles(Fixture.STAGED, "the staged merged base is absent", MERGED);
		try (ZipFile zip = new ZipFile(MERGED.toFile())) {
			assertNull(zip.getEntry(INTERACTION + ".class"), "1.21.1 has no " + INTERACTION + " — the class the "
					+ "injector rewrites, and a 26.x NeoForge class");
			assertNotNull(zip.getEntry("net/minecraft/world/entity/Entity.class"),
					"the entity package itself is there; the fluid-tracking class is what is gone");
		}
	}

	@Test void theNeoForgeRuntimeHasNoGetFluidTypeByTag() throws Exception {
		TestFixtures.requireFiles(Fixture.STAGED, "the staged NeoForge runtime is absent", RUNTIME);
		assertEquals(Set.of(), methodsNamed(RUNTIME, FabricFluidBehaviorInjector.NAME),
				"no class in the staged NeoForge runtime declares " + FabricFluidBehaviorInjector.NAME
						+ " — the method the injector edits, and with it the tag-to-FluidType conversion it edits");
	}

	@Test void theFabricApiHasNoFluidBehaviourRegistry() throws Exception {
		Path jar = TestFixtures.fabricApi();
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(jar), "actual Fabric API fixture required");
		Set<String> hits = new LinkedHashSet<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : zip.stream().toList()) {
				if (entry.getName().contains("EntityFluidInteraction") || entry.getName().contains("FluidBehavior")) {
					hits.add(entry.getName());
				}
			}
		}
		assertEquals(Set.of(), hits, "fabric-api 0.116.17 has no fluid-behaviour registry: the tags the injector "
				+ "asked the kernel to resolve are registered by a version of the API this anchor does not have");
	}

	/** And with the class it names not there, nothing the injector is handed is its target: it stands down. */
	@Test void theInjectorStandsDownOnThisAnchor() throws Exception {
		byte[] entity = TestFixtures.requireEntry(Fixture.STAGED, MERGED, "net/minecraft/world/entity/Entity.class");
		assertSame(entity, new FabricFluidBehaviorInjector().transform(FabricFluidBehaviorInjector.INTERACTION, entity, null),
				"there is no EntityFluidInteraction to repair on this anchor");
		System.setProperty(FabricFluidBehaviorInjector.PROPERTY, "off");
		assertSame(entity, new FabricFluidBehaviorInjector().transform(FabricFluidBehaviorInjector.INTERACTION, entity, null));
	}

	/** Every class in {@code jar} that declares a method named {@code name}, by internal name. */
	private static Set<String> methodsNamed(Path jar, String name) throws Exception {
		Set<String> owners = new LinkedHashSet<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : Collections.list(zip.entries())) {
				if (!entry.getName().endsWith(".class")) continue;
				String[] declared = new String[1];
				byte[] bytes = zip.getInputStream(entry).readAllBytes();
				try {
					new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
						@Override public void visit(int version, int access, String owner, String signature, String superName, String[] interfaces) {
							declared[0] = owner;
						}

						@Override public MethodVisitor visitMethod(int access, String methodName, String desc, String sig, String[] exceptions) {
							if (name.equals(methodName)) owners.add(declared[0]);
							return null;
						}
					}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				} catch (RuntimeException malformed) {
					throw new AssertionError("unreadable class " + entry.getName(), malformed);
				}
			}
		}
		return owners;
	}
}

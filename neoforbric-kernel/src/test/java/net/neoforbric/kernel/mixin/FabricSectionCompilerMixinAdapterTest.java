package net.neoforbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class FabricSectionCompilerMixinAdapterTest {
	private ClassNode mixin() throws Exception {
		return StagedFabricMixinFixture.remapped("fabric-renderer-indigo", FabricSectionCompilerMixinAdapter.MIXIN);
	}

	private ClassNode target(boolean vanilla) throws Exception {
		var path = vanilla ? TestFixtures.vanillaJar() : TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
		TestFixtures.require(vanilla ? Fixture.MC_LIBRARIES : Fixture.STAGED, java.nio.file.Files.isRegularFile(path), path + " absent");
		try (var zip = new java.util.zip.ZipFile(path.toFile())) {
			ClassNode node = new ClassNode();
			new org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry(FabricSectionCompilerMixinAdapter.TARGET + ".class")).readAllBytes()).accept(node, 0);
			return node;
		}
	}

	/** Both injects, and the redirect with its widened handler, move onto the live overload. */
	@Test void movesIndigosThreeInjectorsOntoTheLiveCompileBody() throws Exception {
		ClassNode mixin = mixin();
		ClassNode target = target(false);
		byte[] before = StagedFabricMixinFixture.bytes(target);
		assertEquals(3, FabricSectionCompilerMixinAdapter.adapt(mixin, n -> target));
		for (String handler : List.of("hookBuild", "hookBuildReturn", "hookBuildRenderBlock")) {
			MethodNode method = StagedFabricMixinFixture.method(mixin, handler);
			assertEquals(List.of("compile" + FabricSectionCompilerMixinAdapter.LIVE),
					MixinFit.value(MixinFit.injectorOf(method), "method"), handler);
		}
		MethodNode draw = StagedFabricMixinFixture.method(mixin, "hookBuildRenderBlock");
		assertTrue(draw.desc.endsWith("Lnet/neoforged/neoforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)V"),
				"the handler takes the widened call's own two extra arguments: " + draw.desc);
		assertEquals(1, java.util.stream.StreamSupport.stream(draw.instructions.spliterator(), false)
				.filter(i -> i instanceof MethodInsnNode c && c.desc.contains("ModelData")).count(),
				"its own pass-through call forwards both");
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, draw);
		// The injects' handlers were authored for the delegate's four arguments; both grow the live
		// overload's extra List before the callback, with the MixinExtras sugar contract intact: one
		// signature entry and one annotation slot per parameter, and the sugar still last.
		for (String handler : List.of("hookBuild", "hookBuildReturn")) {
			MethodNode grown = StagedFabricMixinFixture.method(mixin, handler);
			assertTrue(grown.desc.contains("Ljava/util/List;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;"),
					handler + " carries the live overload's list: " + grown.desc);
			if (grown.signature != null)
				assertTrue(grown.signature.contains("Ljava/util/List;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable<"),
						handler + " names the widened parameter in its signature too: " + grown.signature);
			if ("hookBuild".equals(handler))    // the sugar-carrying handler needs the aligned slots
				assertEquals(java.util.Arrays.asList(org.objectweb.asm.Type.getArgumentTypes(grown.desc)).size(),
						grown.invisibleParameterAnnotations == null ? 0 : grown.invisibleParameterAnnotations.length,
						handler + " keeps one annotation slot per widened parameter");
			else
				assertNull(grown.invisibleParameterAnnotations,
						handler + " declares no sugar, so it carries no per-parameter annotations to align");
		}
		MethodNode build = StagedFabricMixinFixture.method(mixin, "hookBuild");
		assertNotNull(build.invisibleParameterAnnotations);
		assertTrue(build.invisibleParameterAnnotations[build.invisibleParameterAnnotations.length - 1].stream()
				.anyMatch(a -> "Lcom/llamalad7/mixinextras/sugar/Local;".equals(a.desc)),
				"the captured builder map stays the handler's last, sugar-typed parameter");
		// The intermediary-named shadow binds the base's own name after the move.
		assertTrue(mixin.methods.stream().noneMatch(m -> "method_60903".equals(m.name)),
				"the intermediary-named shadow is renamed away");
		assertNotNull(StagedFabricMixinFixture.method(mixin, "getOrBeginLayer"));
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(target), "NeoForge's extra geometry and native compile body stay intact");
		assertEquals(0, FabricSectionCompilerMixinAdapter.adapt(mixin, n -> target), "second adaptation is a no-op");
	}

	/** The delegate the handlers select as authored holds neither anchor: refusing to move leaves them nowhere. */
	@Test void aLiveBodyWithoutTheLoopRefusesEveryMove() throws Exception {
		ClassNode mixin = mixin();
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		ClassNode target = target(false);
		for (MethodNode m : target.methods) for (var i : m.instructions)
			if (i instanceof MethodInsnNode c && c.name.equals("betweenClosed")) c.name = "changedLoop";
		assertEquals(0, FabricSectionCompilerMixinAdapter.adapt(mixin, n -> target));
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
	}

	@Test void vanillaCompileAndTheDisabledPassLeaveTheModuleAlone() throws Exception {
		ClassNode vanilla = StagedFabricMixinFixture.vanilla(FabricSectionCompilerMixinAdapter.TARGET);
		assertEquals(0, FabricSectionCompilerMixinAdapter.adapt(mixin(), n -> vanilla),
				"vanilla's own compile keeps the full body; there is no delegate to move off");
		String old = System.setProperty(FabricSectionCompilerMixinAdapter.PROPERTY, "off");
		try {
			ClassNode merged = target(false);
			assertEquals(0, FabricSectionCompilerMixinAdapter.adapt(mixin(), n -> merged));
		} finally {
			if (old == null) System.clearProperty(FabricSectionCompilerMixinAdapter.PROPERTY);
			else System.setProperty(FabricSectionCompilerMixinAdapter.PROPERTY, old);
		}
	}
}

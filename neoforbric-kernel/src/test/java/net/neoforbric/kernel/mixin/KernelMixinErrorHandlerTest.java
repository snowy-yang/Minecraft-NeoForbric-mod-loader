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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinErrorHandler;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import net.neoforbric.api.Ecosystem;
import net.neoforbric.api.ModCatalog;

/** The handler over a proxied IMixinInfo: the owner is marked, the action is never changed, and the bootstrap registers it. */
@org.junit.jupiter.api.parallel.ResourceLock("ModCatalog")
class KernelMixinErrorHandlerTest {
	@org.junit.jupiter.api.BeforeEach
	@org.junit.jupiter.api.AfterEach
	void clearCompatibilityEvidence() { net.neoforbric.api.CompatibilityFindings.reset(); SupersededMixins.reset(); }

	private List<ModCatalog.Entry> previous;

	@Test
	void missingMixinMetadataKeepsEvidenceWithoutInventingAModOrBreakingTheErrorHandler() {
		var action = new KernelMixinErrorHandler().onApplyError("example.Target", new IllegalStateException(), null,
				IMixinErrorHandler.ErrorAction.WARN);
		assertSame(IMixinErrorHandler.ErrorAction.WARN, action);
		assertTrue(ModCatalog.failures().isEmpty());
		assertEquals(1, net.neoforbric.api.CompatibilityFindings.all().size());
	}

	@Test
	void theOriginalRequiredDeclarationSurvivesTheConfigsRelaxation() {
		MixinCompatibility.rememberOriginalConfig("required.mixins.json", "{\"required\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertTrue(MixinCompatibility.required("required.mixins.json", false),
				"the runtime config was relaxed, but the player's required-feature policy still needs the original declaration");
		MixinCompatibility.rememberOriginalConfig("optional.mixins.json", "{\"required\":false}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertTrue(!MixinCompatibility.required("optional.mixins.json", false));
		MixinCompatibility.reset();
		assertTrue(!MixinCompatibility.required("required.mixins.json", false), "a new launch must not inherit the old declaration");
	}

	@BeforeEach
	void publish() {
		previous = ModCatalog.everything();
		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned("x.mixins.json", "xmod", Ecosystem.FABRIC)));
		ModCatalog.publish(List.of(new ModCatalog.Entry(Ecosystem.FABRIC, "xmod", "X", "1", "", List.of(), "x.jar", "", "")));
	}

	@AfterEach
	void forget() {
		MixinConfigOwners.reset();
		ModCatalog.publish(previous);
	}

	@Test
	void anApplyFailureMarksTheOwningMod() {
		IMixinErrorHandler handler = new KernelMixinErrorHandler();
		IMixinErrorHandler.ErrorAction out = handler.onApplyError("net.minecraft.Foo", new RuntimeException("boom"),
				info("x.mixins.json", "a.b.FooMixin"), IMixinErrorHandler.ErrorAction.WARN);
		assertSame(IMixinErrorHandler.ErrorAction.WARN, out);
		assertEquals(1, ModCatalog.failures().size());
		ModCatalog.Entry xmod = ModCatalog.failures().get(0);
		assertEquals("xmod", xmod.modId());
		assertEquals(ModCatalog.Status.DEGRADED, xmod.status());
		assertTrue(xmod.statusDetail().contains("FooMixin") && xmod.statusDetail().contains("net.minecraft.Foo"), xmod.statusDetail());
		var finding = net.neoforbric.api.CompatibilityFindings.confirmedRequired().getFirst();
		assertEquals("mixin:x.mixins.json", finding.source());
		assertTrue(finding.evidence().stream().anyMatch(e -> e.contains("net.minecraft.Foo")));
	}

	@Test
	void aPrepareFailureMarksTheOwningModToo() {
		IMixinInfo info = info("x.mixins.json", "a.b.BarMixin");
		new KernelMixinErrorHandler().onPrepareError(info.getConfig(), new IllegalStateException(), info, IMixinErrorHandler.ErrorAction.ERROR);
		assertEquals(1, ModCatalog.failures().size());
		assertTrue(ModCatalog.failures().get(0).statusDetail().contains("BarMixin"));
	}

	/**
	 * A mixin the kernel has taken over is not a loss once the takeover is SEEN, so its mod is then not marked.
	 *
	 * <p>Seen, not named: the failure is recorded like any other and resolved only when the class carrying the
	 * replacement is defined with the replacement in its bytes. And the action stays unchanged throughout —
	 * suppressing the MARK must never suppress Mixin's own decision, which is what keeps a required config erroring.
	 */
	@Test
	void aMixinTheKernelSupersedesIsUnmarkedOnlyOnceItsReplacementIsDefined() {
		String superseded = "net.fabricmc.fabric.mixin.resource.conditions.SimpleJsonResourceReloadListenerMixin";
		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned("s.mixins.json", "xmod", Ecosystem.FABRIC)));

		IMixinErrorHandler.ErrorAction out = new KernelMixinErrorHandler().onApplyError("net.minecraft.Foo",
				new RuntimeException("boom"), info("s.mixins.json", superseded),
				IMixinErrorHandler.ErrorAction.WARN);

		assertSame(IMixinErrorHandler.ErrorAction.WARN, out, "attribution never changes Mixin's own decision");
		assertEquals(1, ModCatalog.failures().size(), "a table entry is a claim; nothing has shown the repair yet");
		SupersededMixins.observeDefinition(CONDITIONAL_OPS, conditionalOps(false));
		assertEquals(1, ModCatalog.failures().size(), "ConditionalOps defined without the wrap proves nothing");
		SupersededMixins.observeDefinition(CONDITIONAL_OPS, conditionalOps(true));
		assertTrue(ModCatalog.failures().isEmpty(),
				"the kernel does this mixin's job itself, so marking its mod reports a loss that did not happen");
		assertTrue(net.neoforbric.api.CompatibilityFindings.confirmedRequired().isEmpty());
	}

	/** A replacement proved before the mixin fails resolves the failure as it is recorded. */
	@Test
	void aReplacementDefinedBeforeTheFailureResolvesItOnArrival() {
		String superseded = "net.fabricmc.fabric.mixin.resource.conditions.SimpleJsonResourceReloadListenerMixin";
		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned("s.mixins.json", "xmod", Ecosystem.FABRIC)));
		SupersededMixins.observeDefinition(CONDITIONAL_OPS, conditionalOps(true));
		new KernelMixinErrorHandler().onApplyError("net.minecraft.Foo", new RuntimeException("boom"),
				info("s.mixins.json", superseded), IMixinErrorHandler.ErrorAction.WARN);
		assertTrue(ModCatalog.failures().isEmpty());
	}

	/** -Dneoforbric.fabricConditions=off leaves the wrap in place but makes it do nothing: the loss is real again. */
	@Test
	void aSwitchedOffReplacementDoesNotResolveTheLossEvenWhenItsBytesAreThere() {
		String previous = System.getProperty("neoforbric.fabricConditions");
		try {
			System.setProperty("neoforbric.fabricConditions", "off");
			String superseded = "net.fabricmc.fabric.mixin.resource.conditions.SimpleJsonResourceReloadListenerMixin";
			MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned("s.mixins.json", "xmod", Ecosystem.FABRIC)));
			new KernelMixinErrorHandler().onApplyError("net.minecraft.Foo", new RuntimeException("boom"),
					info("s.mixins.json", superseded), IMixinErrorHandler.ErrorAction.WARN);
			SupersededMixins.observeDefinition(CONDITIONAL_OPS, conditionalOps(true));
			assertEquals(1, ModCatalog.failures().size());
			assertEquals(1, net.neoforbric.api.CompatibilityFindings.confirmedRequired().size());
		} finally {
			if (previous == null) System.clearProperty("neoforbric.fabricConditions");
			else System.setProperty("neoforbric.fabricConditions", previous);
		}
	}

	/**
	 * The superseded-mixin wording the client boot log has to carry: the two patterns gate-m9 used to grep for.
	 *
	 * <p>The gate once asserted the name-only resolution after the handler had stopped printing it, so a run in
	 * which the repair WAS seen went red, and nothing printed when the proof resolved the failure. The gate is
	 * gone now, so its two patterns are asserted here directly rather than through a script: the resolution is
	 * logged once the repair is seen in the defined ConditionalOps, in either order, and not while it is unseen,
	 * never defined, or turned off by either switch.
	 */
	@Test
	void theSupersededResolutionIsLoggedOnlyWhenTheRepairIsSeenInTheDefinedClass() throws Exception {
		String superseded = "net.fabricmc.fabric.mixin.resource.conditions.SimpleJsonResourceReloadListenerMixin";
		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned("s.mixins.json", "xmod", Ecosystem.FABRIC)));
		Runnable fail = () -> new KernelMixinErrorHandler().onApplyError(
				"net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener",
				new RuntimeException("InvalidInjectionException"), info("s.mixins.json", superseded),
				IMixinErrorHandler.ErrorAction.WARN);
		Runnable wrapped = () -> SupersededMixins.observeDefinition(CONDITIONAL_OPS, conditionalOps(true));
		Runnable unwrapped = () -> SupersededMixins.observeDefinition(CONDITIONAL_OPS, conditionalOps(false));

		assertSettled(bootLog(null, fail, wrapped));
		assertSettled(bootLog(null, wrapped, fail));
		assertNotSettled(bootLog(null, fail, unwrapped));
		assertNotSettled(bootLog(null, fail));
		assertNotSettled(bootLog(SupersededMixins.PROPERTY, fail, wrapped));
		assertNotSettled(bootLog("neoforbric.fabricConditions", fail, wrapped));
	}

	/** gate-m9's first pattern: the failure resolved as superseded, naming the class the repair was seen in. */
	private static final String SUPERSEDED_SETTLED = "NeoForbric/Mixin\\].*SimpleJsonResourceReloadListenerMixin is"
			+ " superseded.*seen in the defined net\\.neoforged\\.neoforge\\.common\\.conditions\\.ConditionalOps,"
			+ " so its mod is not marked";

	/** gate-m9's second pattern, run there with {@code check_absent}: the mod must not be reported as marked. */
	private static final String SUPERSEDED_MARKED =
			"NeoForbric/Mixin\\].*SimpleJsonResourceReloadListenerMixin.*is marked";

	private static void assertSettled(String log) {
		assertTrue(java.util.regex.Pattern.compile(SUPERSEDED_SETTLED).matcher(log).find(),
				"the resolution must name the ConditionalOps the repair was seen in:\n" + log);
		assertFalse(java.util.regex.Pattern.compile(SUPERSEDED_MARKED).matcher(log).find(),
				"a superseded mixin's mod must not be marked:\n" + log);
	}

	private static void assertNotSettled(String log) {
		assertFalse(java.util.regex.Pattern.compile(SUPERSEDED_SETTLED).matcher(log).find(),
				"no resolution may be claimed while the repair was not seen:\n" + log);
	}

	/** The steps' log, with {@code switchOff} set to off while they run. */
	private static String bootLog(String switchOff, Runnable... steps) throws Exception {
		net.neoforbric.api.CompatibilityFindings.reset();
		SupersededMixins.reset();
		String previous = switchOff == null ? null : System.getProperty(switchOff);
		java.io.PrintStream out = System.out, err = System.err;
		java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
		// NeoForbricLog has no log4j here: info goes to System.out, warn to System.err. Both, or half the log is lost.
		java.io.PrintStream sink = new java.io.PrintStream(buffer, true, java.nio.charset.StandardCharsets.UTF_8);
		try {
			if (switchOff != null) System.setProperty(switchOff, "off");
			System.setOut(sink);
			System.setErr(sink);
			for (Runnable step : steps) step.run();
		} finally {
			System.setOut(out);
			System.setErr(err);
			if (switchOff != null && previous == null) System.clearProperty(switchOff);
			else if (switchOff != null) System.setProperty(switchOff, previous);
		}
		return buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
	}

	private static final String CONDITIONAL_OPS = "net.neoforged.neoforge.common.conditions.ConditionalOps";

	/** ConditionalOps' codec factory, with or without the kernel's wrap before its one exit. */
	private static byte[] conditionalOps(boolean wrapped) {
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
		writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC,
				CONDITIONAL_OPS.replace('.', '/'), null, "java/lang/Object", null);
		var factory = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
				"createConditionalCodecWithConditions",
				"(Lcom/mojang/serialization/Codec;Ljava/lang/String;)Lcom/mojang/serialization/Codec;", null, null);
		factory.visitCode();
		factory.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
		if (wrapped) factory.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC,
				"net/neoforbric/kernel/runtime/KernelFabricConditions", "alsoAskFabric",
				"(Lcom/mojang/serialization/Codec;)Lcom/mojang/serialization/Codec;", false);
		factory.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
		factory.visitMaxs(1, 2);
		factory.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	/** With the switch off it is an ordinary failure again — which is how the claim in each entry gets checked. */
	@Test
	void theSupersededSwitchTurnsThemBackIntoOrdinaryFailures() {
		String previousValue = System.getProperty(SupersededMixins.PROPERTY);
		try {
			System.setProperty(SupersededMixins.PROPERTY, "off");
			String superseded = "net.fabricmc.fabric.mixin.resource.conditions.SimpleJsonResourceReloadListenerMixin";
			MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned("s.mixins.json", "xmod", Ecosystem.FABRIC)));

			new KernelMixinErrorHandler().onApplyError("net.minecraft.Foo", new RuntimeException("boom"),
					info("s.mixins.json", superseded), IMixinErrorHandler.ErrorAction.WARN);

			assertEquals(1, ModCatalog.failures().size());
			assertTrue(ModCatalog.failures().get(0).statusDetail().contains(superseded));
		} finally {
			if (previousValue == null) System.clearProperty(SupersededMixins.PROPERTY);
			else System.setProperty(SupersededMixins.PROPERTY, previousValue);
		}
	}

	/**
	 * A reason the kernel worked out while READING the mixin reaches the row the player sees.
	 *
	 * <p>Two different moments and two different classes: the diagnosis is made when the mixin is read, the mark
	 * when it fails to apply. Losing it in between leaves the load report saying "InvalidInjectionException",
	 * which is true and tells nobody anything.
	 */
	@Test
	void aReasonTheKernelWorkedOutReachesTheRow() {
		net.neoforbric.kernel.transform.DuplicateLambdaPruneInjector.recordDroppedForTest(
				"net/example/Target", "lambda$doThing$0", "(I)V");
		org.objectweb.asm.tree.ClassNode mixin = new org.objectweb.asm.tree.ClassNode();
		mixin.name = "a/b/ThingMixin";
		org.objectweb.asm.tree.AnnotationNode at =
				new org.objectweb.asm.tree.AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		at.values = new java.util.ArrayList<>(List.of("value", new java.util.ArrayList<>(
				List.of(org.objectweb.asm.Type.getObjectType("net/example/Target")))));
		mixin.visibleAnnotations = new java.util.ArrayList<>(List.of(at));
		org.objectweb.asm.tree.MethodNode handler = new org.objectweb.asm.tree.MethodNode(
				org.objectweb.asm.Opcodes.ASM9, org.objectweb.asm.Opcodes.ACC_PRIVATE, "onThing",
				"(ILorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
		org.objectweb.asm.tree.AnnotationNode inject =
				new org.objectweb.asm.tree.AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new java.util.ArrayList<>(List.of("method",
				new java.util.ArrayList<>(List.of("lambda$doThing$0"))));
		handler.visibleAnnotations = new java.util.ArrayList<>(List.of(inject));
		mixin.methods = new java.util.ArrayList<>(List.of(handler));

		org.objectweb.asm.tree.ClassNode target = new org.objectweb.asm.tree.ClassNode();
		target.name = "net/example/Target";
		target.methods = new java.util.ArrayList<>(List.of(new org.objectweb.asm.tree.MethodNode(
				org.objectweb.asm.Opcodes.ASM9, org.objectweb.asm.Opcodes.ACC_PRIVATE, "lambda$doThing$0",
				"(Ljava/lang/String;)V", null, null)));
		// A lambda is never pinned; what the pass leaves behind is the reason, for the row below.
		assertEquals(0, MixinOverloadPin.pin(mixin, name -> target.name.equals(name) ? target : null));
		assertNotNull(MixinOverloadPin.reasonFor("a.b.ThingMixin"));

		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned("t.mixins.json", "xmod", Ecosystem.FABRIC)));
		new KernelMixinErrorHandler().onApplyError("net.example.Target", new RuntimeException(),
				info("t.mixins.json", "a.b.ThingMixin"), IMixinErrorHandler.ErrorAction.WARN);

		assertEquals(1, ModCatalog.failures().size());
		assertTrue(ModCatalog.failures().get(0).statusDetail().contains("the byte merge did not keep"),
				ModCatalog.failures().get(0).statusDetail());
	}

	@Test
	void theActionIsNeverChanged() {
		IMixinErrorHandler handler = new KernelMixinErrorHandler();
		for (IMixinErrorHandler.ErrorAction in : IMixinErrorHandler.ErrorAction.values()) {
			assertSame(in, handler.onApplyError("t", new RuntimeException(), info("x.mixins.json", "M"), in));
			assertSame(in, handler.onPrepareError(info("x.mixins.json", "M").getConfig(), new RuntimeException(), info("x.mixins.json", "M"), in));
		}
	}

	@Test
	void anUnownedConfigMarksNobody() {
		new KernelMixinErrorHandler().onApplyError("t", new RuntimeException(), info("nobody.mixins.json", "M"), IMixinErrorHandler.ErrorAction.WARN);
		assertTrue(ModCatalog.failures().isEmpty());
	}

	@Test
	void theBootstrapRegistersTheHandlerByItsRealName() throws Exception {
		assertEquals(KernelMixinErrorHandler.class.getName(), KernelMixinErrorHandler.NAME);
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main", "net", "neoforbric", "kernel", "mixin",
				"KernelMixinBootstrap.class").normalize();
		assertTrue(Files.isRegularFile(compiled), "the bootstrap is compiled");
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		boolean registered = false;
		for (MethodNode m : node.methods) {
			if (!m.name.equals("init")) continue;
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof LdcInsnNode ldc && KernelMixinErrorHandler.NAME.equals(ldc.cst)) {
					AbstractInsnNode next = insn.getNext();
					while (next != null && next.getOpcode() < 0) next = next.getNext();
					registered |= next instanceof MethodInsnNode call && "org/spongepowered/asm/mixin/Mixins".equals(call.owner)
							&& "registerErrorHandlerClass".equals(call.name);
				}
			}
		}
		assertTrue(registered, "init() hands the handler's name to Mixins.registerErrorHandlerClass");
	}

	private static IMixinInfo info(String configName, String className) {
		IMixinConfig config = (IMixinConfig) Proxy.newProxyInstance(KernelMixinErrorHandlerTest.class.getClassLoader(),
				new Class<?>[] { IMixinConfig.class }, (proxy, method, args) -> switch (method.getName()) {
					case "getName" -> configName;
					case "isRequired" -> true;
					case "toString" -> configName;
					default -> throw new UnsupportedOperationException(method.getName());
				});
		return (IMixinInfo) Proxy.newProxyInstance(KernelMixinErrorHandlerTest.class.getClassLoader(),
				new Class<?>[] { IMixinInfo.class }, (proxy, method, args) -> switch (method.getName()) {
					case "getConfig" -> config;
					case "getClassName" -> className;
					case "getName" -> className.substring(className.lastIndexOf('.') + 1);
					case "toString" -> className;
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}
}

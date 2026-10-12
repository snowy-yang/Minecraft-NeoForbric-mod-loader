/* Copyright 2026 The NeoForbric Project. Licensed under the Apache License, Version 2.0. */
package net.neoforbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import net.neoforbric.kernel.util.NeoForbricLog;

/**
 * Moves Fabric's Indigo chunk-meshing take-over onto the section compile body that carries NeoForge's
 * additional-geometry pass. On 1.21.1 the class behind Indigo's {@code SectionBuilderMixin} is vanilla's
 * own {@code SectionCompiler}, whose short {@code compile} overload is only a delegate: the live body —
 * the overload taking NeoForge's list of {@code AdditionalSectionRenderer}s — holds the block loop and
 * the render call. Left alone, Indigo's two {@code @Inject}s select the delegate, whose four
 * instructions hold neither their anchors nor the builder map the first one captures, and its
 * {@code @Redirect} of the seven-argument {@code renderBatched} finds no call at all, because the live
 * body calls NeoForge's widened nine-argument overload. Indigo's models then never receive any chunk
 * geometry and every world renders empty sections.
 *
 * <p>So both {@code @Inject}s are re-pointed at the live overload — their anchors (the loop's
 * {@code BlockPos.iterate}, the method's {@code RETURN}) and the builder {@code Map} the first one
 * captures exist only there — and the redirect follows its call onto the nine-argument form: its point
 * becomes that call, its handler takes the {@code ModelData} and {@code RenderType} the widened call
 * adds, and its own pass-through invocation forwards them. Indigo's own render path ignores both; they
 * stay what the live body read them to be. The selectors are written in the base's own names, which the
 * module's refmap does not know, so Mixin binds them literally — the same literal form the entity
 * anchors write for the sleep lambda. A changed delegate, a live body without the loop, a handler set
 * beyond the three Indigo ships, or a different renderer signature leaves the mixin as the mod wrote it.
 */
public final class FabricSectionCompilerMixinAdapter {
	public static final String PROPERTY = "neoforbric.fabricChunkRendering";
	static final String MIXIN = "net/fabricmc/fabric/mixin/client/indigo/renderer/SectionBuilderMixin";
	static final String TARGET = "net/minecraft/client/renderer/chunk/SectionCompiler";
	private static final String ARGS = "Lnet/minecraft/core/SectionPos;"
			+ "Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;"
			+ "Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;";
	static final String OLD = "(" + ARGS + ")L" + TARGET + "$Results;";
	static final String LIVE = "(" + ARGS + "Ljava/util/List;)L" + TARGET + "$Results;";
	/** The annotation strings stay Yarn-authored in the remapped module; these are their authored forms. */
	private static final String ITERATE = "Lnet/minecraft/util/math/BlockPos;iterate(Lnet/minecraft/util/math/BlockPos;"
			+ "Lnet/minecraft/util/math/BlockPos;)Ljava/lang/Iterable;";
	private static final String RENDER_BLOCK = "Lnet/minecraft/client/render/block/BlockRenderManager;renderBlock("
			+ "Lnet/minecraft/block/BlockState;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/world/BlockRenderView;"
			+ "Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;"
			+ "ZLnet/minecraft/util/math/random/Random;)V";
	/** The same anchors once the config's refmap has resolved them — the judging view Mixin binds through. */
	private static final String BETWEEN_CLOSED = "Lnet/minecraft/core/BlockPos;betweenClosed(Lnet/minecraft/core/BlockPos;"
			+ "Lnet/minecraft/core/BlockPos;)Ljava/lang/Iterable;";
	/** The Yarn-authored name of the base's {@code getOrBeginLayer}, which the 1.21.1 module shadows. */
	private static final String SHADOW = "method_60903";
	private static final String SHADOW_DESC = "(Ljava/util/Map;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;"
			+ "Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/BufferBuilder;";
	private static final String SHADOW_TO = "getOrBeginLayer";
	private static final String RENDERER = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;";
	private static final String MODEL_DATA = "Lnet/neoforged/neoforge/client/model/data/ModelData;";
	private static final String RENDER_TYPE = "Lnet/minecraft/client/renderer/RenderType;";
	/** The call vanilla's own body makes — the widened form the redirect's handler must learn. */
	private static final String NARROW_DESC = "(Lnet/minecraft/world/level/block/state/BlockState;"
			+ "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;"
			+ "Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;"
			+ "ZLnet/minecraft/util/RandomSource;)V";
	private static final String WIDE_DESC = NARROW_DESC.substring(0, NARROW_DESC.length() - 2)
			+ MODEL_DATA + RENDER_TYPE + ")V";
	private static final String NARROW = "renderBatched" + NARROW_DESC;
	private static final String WIDE = "renderBatched" + WIDE_DESC;
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";

	private FabricSectionCompilerMixinAdapter() { }

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!enabled() || !MIXIN.equals(mixin.name)) return 0;
		ClassNode target = targets.apply(TARGET);
		if (target == null) return 0;
		MethodNode stub = method(target, "compile", OLD), live = method(target, "compile", LIVE);
		if (stub == null || live == null || calls(stub, TARGET, "compile", LIVE) != 1
				|| callsName(stub, "net/minecraft/core/BlockPos", "betweenClosed") != 0
				|| callsName(live, "net/minecraft/core/BlockPos", "betweenClosed") != 1
				|| count(live, RENDERER + WIDE) != 1 || count(live, RENDERER + NARROW) != 0) return 0;
		// No local-variable premise: the 1.21.1 javac writes the startedLayers Map into the live body's
		// LVT, but only a flags-0 read carries an LVT — the rescue judges a SKIP_DEBUG node and
		// class-serve a flags-0 one, and this adapter must not depend on which its caller holds. The
		// betweenClosed==1 and widened-call==1 counts above already pin the live body uniquely on this
		// base; the Map the setup captures is guaranteed by the @Local sugar the premise below demands.
		List<MethodNode> injects = injectors(mixin, INJECT), redirects = injectors(mixin, REDIRECT);
		if (injects.size() != 2 || redirects.size() != 1
				|| mixin.methods.stream().filter(FabricSectionCompilerMixinAdapter::isInjector).count() != 3) return 0;
		MethodNode setup = injects.stream().filter(FabricSectionCompilerMixinAdapter::capturesBuilders).findFirst().orElse(null);
		MethodNode done = injects.stream().filter(m -> m != setup).findFirst().orElse(null);
		MethodNode redirect = redirects.getFirst();
		if (setup == null || !anchors(setup, ITERATE, BETWEEN_CLOSED) || !anchors(done, "RETURN")
				|| !anchors(redirect, RENDER_BLOCK, RENDERER + NARROW) || !widenHandler(redirect)
				|| !renameShadow(mixin)) return 0;

		// Both injects and the redirect bind the live overload; the redirect's point becomes the call it makes.
		// The injects' handlers were authored for the delegate's four arguments, and the live overload
		// takes one more — the additional-geometry list — so the callback's descriptor must grow it before
		// the callback slot, exactly as a mixin compiled against the five-argument overload's would read.
		widenInject(setup);
		widenInject(done);
		setMethod(setup, "compile" + LIVE);
		setMethod(done, "compile" + LIVE);
		setMethod(redirect, "compile" + LIVE);
		for (int i = 1; i < MixinFit.injectorOf(redirect).values.size(); i += 2)
			if ("at".equals(MixinFit.injectorOf(redirect).values.get(i - 1)))
				setTarget(MixinFit.injectorOf(redirect).values.get(i), RENDERER + WIDE);
		NeoForbricLog.info("[NeoForbric/Renderer] Fabric's chunk-meshing take-over now runs in the live section compile body");
		return 3;
	}

	private static boolean isInjector(MethodNode method) {
		return MixinFit.injectorOf(method) != null;
	}

	private static List<MethodNode> injectors(ClassNode mixin, String kind) {
		return mixin.methods.stream().filter(m -> {
			AnnotationNode injector = MixinFit.injectorOf(m);
			return injector != null && kind.equals(injector.desc);
		}).toList();
	}

	/** The loop-opening inject: anchored on the block loop, taking the builders by MixinExtras {@code @Local}. */
	private static boolean capturesBuilders(MethodNode setup) {
		if (setup.invisibleParameterAnnotations == null) return false;
		return java.util.Arrays.stream(setup.invisibleParameterAnnotations).anyMatch(p ->
				p != null && p.stream().anyMatch(a -> "Lcom/llamalad7/mixinextras/sugar/Local;".equals(a.desc)));
	}

	private static boolean anchors(MethodNode handler, String... allowed) {
		AnnotationNode injector = MixinFit.injectorOf(handler);
		if (injector == null || MixinFit.value(injector, "slice") != null) return false;
		List<String> selectors = MixinFit.stringList(MixinFit.value(injector, "method"));
		// The selector as the module authored it ("build", Yarn) or as the config's refmap resolves it for
		// judging — the delegate overload, owner-less the way the resolution keeps it.
		if (selectors.size() != 1 || !("build".equals(selectors.getFirst()) || ("compile" + OLD).equals(selectors.getFirst()))) return false;
		List<AnnotationNode> at = MixinFit.atNodes(injector);
		if (at.size() != 1) return false;
		Object kind = MixinFit.value(at.getFirst(), "value");
		if ("RETURN".equals(kind)) return java.util.Arrays.asList(allowed).contains("RETURN");
		if (!"INVOKE".equals(kind) || MixinFit.value(at.getFirst(), "ordinal") != null) return false;
		String target = String.valueOf(MixinFit.value(at.getFirst(), "target"));
		return java.util.Arrays.asList(allowed).contains(target);
	}

	/**
	 * Inserts the live overload's extra {@code List} argument before the inject's callback slot — the
	 * descriptor, and the parameter-annotation arrays with it, or a sugar parameter the handler also
	 * declares would answer an index one past the array's end (MixinExtras reads the annotations by
	 * the widened parameter list).
	 */
	private static void widenInject(MethodNode handler) {
		int callback = handler.desc.indexOf("Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;");
		if (callback < 0 || handler.desc.contains("Ljava/util/List;Lorg/spongepowered/asm/mixin/injection/callback/")) return;
		// The body was compiled against the delegate's argument list; the extra parameter the live
		// overload adds sits before the callback, so every local slot from the callback's on shifts one
		// up — the callback itself, any sugar parameter after it, and any local the body addresses by
		// slot beyond the parameters.
		Type[] beforeCallback = Type.getArgumentTypes(handler.desc.substring(0, callback) + ")V");
		shiftLocals(handler, beforeCallback.length + 1);
		int listSlot = beforeCallback.length;    // the List goes where the callback began
		handler.desc = handler.desc.substring(0, callback) + "Ljava/util/List;" + handler.desc.substring(callback);
		// The signature must name the widened parameter too: MixinExtras' sugar machinery parses one
		// generics entry per parameter from the signature, and a signature left at the narrower
		// parameter count reads one entry past its end against the widened descriptor.
		if (handler.signature != null) {
			int callbackSig = handler.signature.indexOf("Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable<");
			if (callbackSig > 0)
				handler.signature = handler.signature.substring(0, callbackSig) + "Ljava/util/List;" + handler.signature.substring(callbackSig);
		}
		handler.invisibleParameterAnnotations = growAnnotations(handler.invisibleParameterAnnotations, listSlot);
		handler.visibleParameterAnnotations = growAnnotations(handler.visibleParameterAnnotations, listSlot);
		handler.maxLocals += 1;
	}

	/** One empty annotation slot at {@code at}, so the widened parameter list keeps its annotations aligned. */
	@SuppressWarnings("unchecked")
	private static java.util.List<AnnotationNode>[] growAnnotations(java.util.List<AnnotationNode>[] annotations, int at) {
		if (annotations == null) return null;
		java.util.List<AnnotationNode>[] grown = new java.util.ArrayList[annotations.length + 1];
		for (int i = 0, k = 0; i < grown.length; i++)
			grown[i] = i == at ? new ArrayList<>() : annotations[k++];
		return grown;
	}

	/** Every local-variable slot from {@code from} on shifts one up, for a parameter inserted before it. */
	private static void shiftLocals(MethodNode handler, int from) {
		for (var insn : handler.instructions) {
			if (insn instanceof org.objectweb.asm.tree.VarInsnNode load && load.var >= from) load.var++;
			if (insn instanceof org.objectweb.asm.tree.IincInsnNode iinc && iinc.var >= from) iinc.var++;
		}
		for (var local : handler.localVariables == null ? new java.util.ArrayList<org.objectweb.asm.tree.LocalVariableNode>() : handler.localVariables)
			if (local.index >= from) local.index++;
	}


	/**
	 * The 1.21.1 module's {@code @Shadow} carries the intermediary name fabric-api authored it with
	 * ({@code method_60903} — Yarn never named this one, so the refmap holds no key for it either), and the
	 * base this runs against knows the member as {@code getOrBeginLayer}; the descriptor the module remap
	 * typed it with already matches. The shadow is renamed — declaration and every call the mixin's own
	 * bodies make to it — because Mixin would bind neither on this base: natively the intermediary name
	 * binds only in the intermediary namespace the loader path runs, and a refmap lookup misses here.
	 */
	private static boolean renameShadow(ClassNode mixin) {
		MethodNode shadow = method(mixin, SHADOW, SHADOW_DESC);
		if (shadow == null || (shadow.access & Opcodes.ACC_ABSTRACT) == 0
				|| shadow.visibleAnnotations == null || shadow.visibleAnnotations.stream().noneMatch(a ->
						"Lorg/spongepowered/asm/mixin/Shadow;".equals(a.desc))) return false;
		shadow.name = SHADOW_TO;
		for (MethodNode body : mixin.methods)
			if (body.instructions != null) for (var insn : body.instructions)
				if (insn instanceof MethodInsnNode call && SHADOW.equals(call.name) && call.desc.equals(SHADOW_DESC))
					call.name = SHADOW_TO;
		return true;
	}

	/** The redirect's handler takes the widened call's own two extra arguments and forwards them itself. */
	private static boolean widenHandler(MethodNode redirect) {
		if (!redirect.desc.endsWith("Lnet/minecraft/util/RandomSource;)V")) return false;
		redirect.desc = redirect.desc.substring(0, redirect.desc.length() - 2) + MODEL_DATA + RENDER_TYPE + ")V";
		int modelData = slotOf(redirect, 8), renderType = slotOf(redirect, 9);
		for (var insn : redirect.instructions)
			if (insn instanceof MethodInsnNode call && call.name.equals("renderBatched") && call.desc.equals(NARROW_DESC)) {
				call.desc = WIDE_DESC;
				redirect.instructions.insertBefore(insn, new VarInsnNode(Opcodes.ALOAD, modelData));
				redirect.instructions.insertBefore(insn, new VarInsnNode(Opcodes.ALOAD, renderType));
			}
		redirect.maxLocals = Math.max(redirect.maxLocals, renderType + 1);
		redirect.maxStack = Math.max(redirect.maxStack, 12);
		return true;
	}

	/** The slot of the handler's reference argument at the given index; the first is {@code this}. */
	private static int slotOf(MethodNode handler, int argument) {
		int slot = 1;
		Type[] args = Type.getArgumentTypes(handler.desc);
		for (int i = 0; i < argument && i < args.length; i++) slot += args[i].getSize();
		return slot;
	}

	private static void setMethod(MethodNode handler, String selector) {
		AnnotationNode injector = MixinFit.injectorOf(handler);
		for (int i = 1; i < injector.values.size(); i += 2)
			if ("method".equals(injector.values.get(i - 1))) injector.values.set(i, List.of(selector));
	}

	private static void setTarget(Object at, String member) {
		if (at instanceof AnnotationNode single) setTarget(single, member);
		if (at instanceof List<?> list) for (Object node : list) setTarget(node, member);
	}

	private static void setTarget(AnnotationNode annotation, String member) {
		if (annotation == null || annotation.values == null) return;
		for (int i = 1; i < annotation.values.size(); i += 2)
			if ("target".equals(annotation.values.get(i - 1))) annotation.values.set(i, member);
	}

	private static MethodNode method(ClassNode type, String name, String desc) {
		return type.methods.stream().filter(m -> name.equals(m.name) && desc.equals(m.desc)).findFirst().orElse(null);
	}

	private static int count(MethodNode method, String member) {
		int count = 0;
		for (var insn : method.instructions)
			if (insn instanceof MethodInsnNode call && member.equals("L" + call.owner + ";" + call.name + call.desc)) count++;
		return count;
	}

	/** Occurrences of a call by owner and name, whatever descriptor it takes. */
	private static int callsName(MethodNode method, String owner, String name) {
		int count = 0;
		for (var insn : method.instructions)
			if (insn instanceof MethodInsnNode call && owner.equals(call.owner) && name.equals(call.name)) count++;
		return count;
	}

	private static int calls(MethodNode method, String owner, String name, String desc) {
		return count(method, "L" + owner + ";" + name + desc);
	}
}

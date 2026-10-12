package net.fabricmc.fabric.mixin.client.indigo.renderer;

import java.util.Map;

import com.mojang.blaze3d.vertex.BufferBuilder;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.VertexSorting;

import net.fabricmc.fabric.impl.client.indigo.renderer.accessor.AccessChunkRendererRegion;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.TerrainRenderContext;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Synthetic guest mixin authored as the kernel loads fabric-renderer-indigo's own: the selector and the
 * {@code @At} targets keep the Yarn names the module was written with and resolve through its refmap,
 * while every class reference is the base's Mojmap. The setup takes the builder map by MixinExtras
 * {@code @Local}, the redirect's handler is the narrow call's own shape and passes through to it.
 */
@Mixin(SectionCompiler.class)
abstract class SectionBuilderMixin {
	/** The intermediary name the 1.21.1 module authored it with — Yarn never named this member. */
	@Shadow
	protected abstract BufferBuilder method_60903(Map<RenderType, BufferBuilder> startedLayers,
			SectionBufferBuilderPack buffers, RenderType layer);

	@Inject(method = "build", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/util/math/BlockPos;iterate(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/BlockPos;)Ljava/lang/Iterable;"))
	private void hookBuild(SectionPos section, RenderChunkRegion region, VertexSorting sorting,
			SectionBufferBuilderPack buffers, CallbackInfoReturnable<SectionCompiler.Results> info,
			@Local(ordinal = 0) Map<RenderType, BufferBuilder> startedLayers) {
		AccessChunkRendererRegion access = (AccessChunkRendererRegion) region;
		TerrainRenderContext context = TerrainRenderContext.POOL.get();
		if (context == null) {
			context = new TerrainRenderContext();
			TerrainRenderContext.POOL.set(context);
		}
		access.fabric_setRenderer(context);
		context.prepare(region, type -> method_60903(startedLayers, buffers, type));
	}

	@Redirect(method = "build", require = 1, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/render/block/BlockRenderManager;renderBlock(Lnet/minecraft/block/BlockState;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/world/BlockRenderView;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;ZLnet/minecraft/util/math/random/Random;)V"))
	private void hookBuildRenderBlock(BlockRenderDispatcher dispatcher, BlockState state, BlockPos origin,
			BlockAndTintGetter level, PoseStack pose, VertexConsumer consumer, boolean check, RandomSource random) {
		if (level instanceof AccessChunkRendererRegion access) {
			access.fabric_getRenderer().tessellateBlock(state, origin, null, pose);
			return;
		}
		dispatcher.renderBatched(state, origin, level, pose, consumer, check, random);
	}

	@Inject(method = "build", at = @At("RETURN"))
	private void hookBuildReturn(SectionPos section, RenderChunkRegion region, VertexSorting sorting,
			SectionBufferBuilderPack buffers, CallbackInfoReturnable<SectionCompiler.Results> info) {
		((AccessChunkRendererRegion) region).fabric_getRenderer().release();
		((AccessChunkRendererRegion) region).fabric_setRenderer(null);
	}
}

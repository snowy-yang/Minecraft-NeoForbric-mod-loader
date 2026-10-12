package net.fabricmc.fabric.impl.client.indigo.renderer.render;

import java.util.function.Function;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

/** Hand-written stand-in, not game code: Indigo's terrain render context, one per compile, held in a
 * thread-local pool exactly as the real one is. {@link #prepare} wires the per-layer buffer the
 * section's own builder map hands out, and a tessellated block lands in it as the renderer's quad. */
public class TerrainRenderContext {
	public static final ThreadLocal<TerrainRenderContext> POOL = new ThreadLocal<>();

	private Function<RenderType, BufferBuilder> buffers;

	public void prepare(BlockAndTintGetter region, Function<RenderType, BufferBuilder> consumer) {
		this.buffers = consumer;
	}

	public void tessellateBlock(BlockState state, BlockPos origin, Object model, PoseStack pose) {
		VertexConsumer consumer = buffers.apply(new RenderType("solid"));
		consumer.put("fabric:" + state.model().name());
	}

	public void release() {
		POOL.remove();
	}
}

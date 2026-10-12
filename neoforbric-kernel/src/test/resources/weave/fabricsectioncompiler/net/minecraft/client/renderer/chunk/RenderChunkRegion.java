package net.minecraft.client.renderer.chunk;

import java.util.List;

import net.fabricmc.fabric.impl.client.indigo.renderer.accessor.AccessChunkRendererRegion;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.TerrainRenderContext;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

/** Hand-written stand-in, not game code: a row of blocks to compile, carrying the renderer Indigo's
 * own region mixin would hold on it. */
public class RenderChunkRegion implements BlockAndTintGetter, AccessChunkRendererRegion {
	private final List<String> blocks;
	private TerrainRenderContext renderer;

	public RenderChunkRegion(List<String> blocks) {
		this.blocks = blocks;
	}

	public BlockPos first() {
		return new BlockPos(0);
	}

	public BlockPos last() {
		return new BlockPos(blocks.size() - 1);
	}

	public BlockState getBlockState(BlockPos pos) {
		return new BlockState(new BlockStateModel(blocks.get(pos.x())));
	}

	@Override
	public TerrainRenderContext fabric_getRenderer() {
		return renderer;
	}

	@Override
	public void fabric_setRenderer(TerrainRenderContext renderer) {
		this.renderer = renderer;
	}
}

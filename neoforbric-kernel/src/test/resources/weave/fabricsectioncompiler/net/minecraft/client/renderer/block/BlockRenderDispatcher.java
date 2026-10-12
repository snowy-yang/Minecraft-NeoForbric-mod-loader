package net.minecraft.client.renderer.block;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

/** Hand-written stand-in, not game code: a block render dispatcher of the merged 1.21.1 shape, whose
 * compile body calls the widened nine-argument overload while Fabric's module redirects the narrow one. */
public class BlockRenderDispatcher {
	public void renderBatched(BlockState state, BlockPos origin, BlockAndTintGetter level, PoseStack pose,
			VertexConsumer consumer, boolean check, RandomSource random) {
		throw new AssertionError("the narrow overload only exists for the redirect's compiled signature");
	}

	public void renderBatched(BlockState state, BlockPos origin, BlockAndTintGetter level, PoseStack pose,
			VertexConsumer consumer, boolean check, RandomSource random, ModelData model, RenderType type) {
		consumer.put("vanilla:" + state.model().name());
	}
}

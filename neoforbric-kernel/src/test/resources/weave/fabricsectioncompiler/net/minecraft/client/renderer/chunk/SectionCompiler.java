package net.minecraft.client.renderer.chunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexSorting;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Hand-written stand-in, not game code: vanilla 1.21.1's four-argument {@code compile} is a stub
 * forwarding to NeoForge's five-argument overload, which takes the additional-geometry list and carries
 * the body — the block walk and the builder map the quads land in — and calls the widened nine-argument
 * {@code renderBatched} where vanilla's own calls the seven-argument one.
 */
public class SectionCompiler {
	public static final class Results {
		public final List<String> quads = new ArrayList<>();
	}

	private final BlockRenderDispatcher renderer = new BlockRenderDispatcher();

	/** The layer buffer the real base begins on demand — keyed into the section's own started-layers
	 * map, so what a block tessellates into is what the compile returns; the module's renamed shadow
	 * binds here. */
	public BufferBuilder getOrBeginLayer(Map<RenderType, List<String>> startedLayers, SectionBufferBuilderPack buffers, RenderType layer) {
		return new BufferBuilder(quad -> startedLayers.computeIfAbsent(layer, key -> new ArrayList<>()).add(quad));
	}

	public Results compile(SectionPos section, RenderChunkRegion region, VertexSorting sorting, SectionBufferBuilderPack buffers) {
		return compile(section, region, sorting, buffers, List.of());
	}

	public Results compile(SectionPos section, RenderChunkRegion region, VertexSorting sorting, SectionBufferBuilderPack buffers,
			List<String> additionalGeometry) {
		Results results = new Results();
		Map<RenderType, List<String>> startedLayers = new LinkedHashMap<>();
		VertexConsumer solid = quad -> startedLayers.computeIfAbsent(new RenderType("solid"), layer -> new ArrayList<>()).add(quad);
		PoseStack pose = new PoseStack();
		RandomSource random = new RandomSource();
		for (BlockPos pos : BlockPos.betweenClosed(region.first(), region.last())) {
			BlockState state = region.getBlockState(pos);
			ModelData model = new ModelData(state.model().name());
			RenderType type = new RenderType("solid");
			renderer.renderBatched(state, pos, region, pose, solid, true, random, model, type);
		}
		for (String extra : additionalGeometry) solid.put("neoforge:" + extra);
		for (List<String> layer : startedLayers.values()) results.quads.addAll(layer);
		return results;
	}
}

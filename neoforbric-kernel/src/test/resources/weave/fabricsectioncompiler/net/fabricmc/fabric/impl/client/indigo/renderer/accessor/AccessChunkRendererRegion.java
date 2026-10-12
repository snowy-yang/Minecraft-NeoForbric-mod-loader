package net.fabricmc.fabric.impl.client.indigo.renderer.accessor;

import net.fabricmc.fabric.impl.client.indigo.renderer.render.TerrainRenderContext;

/** Hand-written stand-in, not game code: the accessor Indigo's other mixin implements on the region. */
public interface AccessChunkRendererRegion {
	TerrainRenderContext fabric_getRenderer();

	void fabric_setRenderer(TerrainRenderContext renderer);
}

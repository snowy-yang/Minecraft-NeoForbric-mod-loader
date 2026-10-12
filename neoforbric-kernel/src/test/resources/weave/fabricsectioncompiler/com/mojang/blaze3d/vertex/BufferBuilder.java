package com.mojang.blaze3d.vertex;

import java.util.function.Consumer;

/** Hand-written stand-in, not game code: the buffer a section's layer is compiled into, sinking
 * each quad where the section's layer map collects them. */
public class BufferBuilder implements VertexConsumer {
	private final Consumer<String> sink;

	public BufferBuilder(Consumer<String> sink) {
		this.sink = sink;
	}

	@Override
	public void put(String quad) {
		sink.accept(quad);
	}
}

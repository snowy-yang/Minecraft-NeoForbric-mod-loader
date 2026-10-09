package net.neoforbric.kernel.runtime.transfer;

/**
 * Resource identity conversion between Fabric's variants and NeoForge's carriers. A null conversion means
 * unrepresentable, never "discard its metadata".
 *
 * <p>{@code N} is the native carrier of this era's NeoForge API: an {@code ItemStack} for items, a
 * {@code FluidStack} for fluids. 26.x moved an abstract {@code Resource} between the APIs; 1.21.1's
 * capabilities speak concrete carriers, so the codec also carries amounts: {@link #toNeo} builds the carrier
 * holding {@code nativeAmount} units, and {@link #neoAmount} reads one back. Fabric units per native unit is
 * the quantization factor the adapters share (one for items, 81 per millibucket for fluids).
 */
public interface TransferCodec<F, N> {
	/** The native carrier of {@code resource} holding {@code nativeAmount} units; never null. */
	N toNeo(F resource, long nativeAmount);
	/** Fabric's variant of a native carrier's identity (the amount is the adapters' business). */
	F toFabric(N resource);
	/** The amount a native carrier holds, in native units. */
	long neoAmount(N resource);
	boolean isFabricBlank(F resource);
	/** Fabric units per one native NeoForge unit: one for items, 81 for fluids. */
	long fabricUnits();
}

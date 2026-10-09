package net.neoforbric.kernel.runtime.transfer;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Exact 1.21.1 identity mappings. Both APIs share the game's registry objects and component patches, so a
 * conversion is a re-wrap: never a copy through a registry lookup, never metadata dropped. A blank variant
 * converts to the API's empty carrier and back, and an empty carrier is a blank variant, so a blank never
 * crosses the bridge as a resource with an identity.
 *
 * <p>Fluid amounts convert at exactly 81 Fabric units per millibucket (droplets); the sub-millibucket
 * remainder is the adapters' to keep at its source, never to round after a mutation.
 */
public final class TransferResources {
	private TransferResources() { }

	public static final TransferCodec<ItemVariant, ItemStack> ITEMS = new TransferCodec<>() {
		public ItemStack toNeo(ItemVariant value, long nativeAmount) {
			if (value.isBlank() || nativeAmount <= 0) return ItemStack.EMPTY;
			// toStack cannot exceed the int count an ItemStack carries; larger offers are the adapter's to clamp.
			return value.toStack((int) Math.min(nativeAmount, Integer.MAX_VALUE));
		}
		public ItemVariant toFabric(ItemStack value) { return value.isEmpty() ? ItemVariant.blank() : ItemVariant.of(value); }
		public long neoAmount(ItemStack value) { return value.isEmpty() ? 0 : value.getCount(); }
		public boolean isFabricBlank(ItemVariant value) { return value.isBlank(); }
		public long fabricUnits() { return 1; }
	};

	public static final TransferCodec<FluidVariant, FluidStack> FLUIDS = new TransferCodec<>() {
		public FluidStack toNeo(FluidVariant value, long nativeAmount) {
			if (value.isBlank() || nativeAmount <= 0) return FluidStack.EMPTY;
			return new FluidStack(value.getRegistryEntry(), (int) Math.min(nativeAmount, Integer.MAX_VALUE), value.getComponents());
		}
		public FluidVariant toFabric(FluidStack value) {
			return value.isEmpty() ? FluidVariant.blank() : FluidVariant.of(value.getFluid(), value.getComponentsPatch());
		}
		public long neoAmount(FluidStack value) { return value.isEmpty() ? 0 : value.getAmount(); }
		public boolean isFabricBlank(FluidVariant value) { return value.isBlank(); }
		public long fabricUnits() { return 81; }
	};
}

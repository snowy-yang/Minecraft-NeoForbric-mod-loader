package org.betterx.bclib.mixin.common.shears;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fixture.shearsrelay.ShearTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.PumpkinBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A tag-based shears check in BCLib's 1.21.1 shape: a Fabric mod's @WrapOperation of vanilla's stack.is(Item) in
 * useItemOn, (ItemStack, Item, Operation) -> boolean, extending the original for a tagged shears item. The body is
 * the fixture's own.
 */
@Mixin(PumpkinBlock.class)
public class PumpkinBlockMixin {
	@WrapOperation(method = "useItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;is(Lnet/minecraft/world/item/Item;)Z"))
	private boolean bclib_isShears(ItemStack instance, Item item, Operation<Boolean> original) {
		return original.call(instance, item) || (item == Items.SHEARS && ShearTags.isShear(instance));
	}
}

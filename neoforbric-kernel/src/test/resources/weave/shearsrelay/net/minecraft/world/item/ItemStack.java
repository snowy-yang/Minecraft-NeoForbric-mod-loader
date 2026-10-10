package net.minecraft.world.item;

import net.neoforged.neoforge.common.ItemAbility;

/** Fixture stand-in: vanilla's typed is(item) and NeoForge's ability question, asked of the item. */
public final class ItemStack {
	private final Item item;

	public ItemStack(Item item) {
		this.item = item;
	}

	public Item getItem() {
		return item;
	}

	/** Vanilla 1.21.1's own question: {@code is(Item)}, a plain method on the stack (26.x's generic is(Object) is gone). */
	public boolean is(Item item) {
		return this.item == item;
	}

	public boolean canPerformAction(ItemAbility ability) {
		return item.canPerformAction(this, ability);
	}
}

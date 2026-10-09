package net.neoforbric.kernel.runtime.transfer;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Location-bound live views that never retain a resolved foreign store. Every operation resolves it anew,
 * and a removed endpoint becomes empty. If removal happens inside a provider callback, throwing before the
 * adapter's scope commits lets the real engine roll the provider back instead of silently writing into a
 * dead object.
 *
 * <p>26.x also kept the NeoForge-side live views here; 1.21.1's adapters absorb them (NativeTransferAdapters
 * takes the lookup, validity and generation directly), so what remains is the Fabric side — any ecosystem's
 * storages — and energy, whose immediate {@link IEnergyStorage} is not an adapter's job to wrap twice.
 */
public final class LiveTransferEndpoints {
	private LiveTransferEndpoints() { }
	static final class Unavailable extends IllegalStateException {
		Unavailable(String message) { super(message); }
	}
	static void stillValid(BooleanSupplier valid) {
		if (valid != null && !valid.getAsBoolean()) throw new Unavailable("Transfer endpoint invalidated during an operation");
	}
	static void unchanged(long before, LongSupplier generation) {
		if (generation != null && before != generation.getAsLong()) throw new Unavailable("Transfer capability invalidated during an operation");
	}

	/** The same rules for a NeoForge energy store: resolved per operation, empty once its endpoint is gone. */
	public static IEnergyStorage energy(Supplier<IEnergyStorage> lookup, BooleanSupplier valid, LongSupplier generation) {
		return new LiveEnergy(lookup, valid, generation);
	}

	private record LiveEnergy(Supplier<IEnergyStorage> lookup, BooleanSupplier valid, LongSupplier generation) implements IEnergyStorage {
		private IEnergyStorage current() { return valid.getAsBoolean() ? lookup.get() : null; }
		private int move(int maximum, boolean simulate) {
			IEnergyStorage h = current();
			if (h == null) return 0;
			long before = generation.getAsLong();
			int amount = simulate ? h.receiveEnergy(maximum, true) : h.receiveEnergy(maximum, false);
			stillValid(valid); unchanged(before, generation);
			return amount;
		}
		public int receiveEnergy(int maximum, boolean simulate) { return move(maximum, simulate); }
		public int extractEnergy(int maximum, boolean simulate) {
			IEnergyStorage h = current();
			if (h == null) return 0;
			long before = generation.getAsLong();
			int amount = h.extractEnergy(maximum, simulate);
			stillValid(valid); unchanged(before, generation);
			return amount;
		}
		public int getEnergyStored() { IEnergyStorage h = current(); return h == null ? 0 : h.getEnergyStored(); }
		public int getMaxEnergyStored() { IEnergyStorage h = current(); return h == null ? 0 : h.getMaxEnergyStored(); }
		public boolean canExtract() { IEnergyStorage h = current(); return h != null && h.canExtract(); }
		public boolean canReceive() { IEnergyStorage h = current(); return h != null && h.canReceive(); }
		// Identity, not the record's component equality: two live views of one place are two endpoints.
		@Override public boolean equals(Object other) { return this == other; }
		@Override public int hashCode() { return System.identityHashCode(this); }
	}

	public static <F> SlottedStorage<F> fabric(Supplier<SlottedStorage<F>> lookup, BooleanSupplier valid, F empty) {
		return fabric(lookup, valid, () -> 0L, empty);
	}
	public static <F> SlottedStorage<F> fabric(Supplier<SlottedStorage<F>> lookup, BooleanSupplier valid, LongSupplier generation, F empty) {
		return new SlottedStorage<>() {
			private SlottedStorage<F> current() { return valid.getAsBoolean() ? lookup.get() : null; }
			private SingleSlotStorage<F> current(int slot) { var s = current(); return s != null && slot >= 0 && slot < s.getSlotCount() ? s.getSlot(slot) : null; }
			public int getSlotCount() { var s = current(); return s == null ? 0 : s.getSlotCount(); }
			public long insert(F resource, long max, TransactionContext tx) {
				var s = current(); if (s == null) return 0;
				long before = generation.getAsLong();
				long amount = s.insert(resource, max, tx); stillValid(valid); unchanged(before, generation); return amount;
			}
			public long extract(F resource, long max, TransactionContext tx) {
				var s = current(); if (s == null) return 0;
				long before = generation.getAsLong();
				long amount = s.extract(resource, max, tx); stillValid(valid); unchanged(before, generation); return amount;
			}
			public SingleSlotStorage<F> getSlot(int index) {
				if (index < 0) throw new IndexOutOfBoundsException(index);
				return new SingleSlotStorage<>() {
					public boolean isResourceBlank() { var s = current(index); return s == null || s.isResourceBlank(); }
					public F getResource() { var s = current(index); return s == null ? empty : s.getResource(); }
					public long getAmount() { var s = current(index); return s == null ? 0 : s.getAmount(); }
					public long getCapacity() { var s = current(index); return s == null ? 0 : s.getCapacity(); }
					public boolean supportsInsertion() { var s = current(index); return s != null && s.supportsInsertion(); }
					public boolean supportsExtraction() { var s = current(index); return s != null && s.supportsExtraction(); }
					public long insert(F resource, long max, TransactionContext tx) {
						var s = current(index); if (s == null) return 0;
						long before = generation.getAsLong();
						long amount = s.insert(resource, max, tx); stillValid(valid); unchanged(before, generation); return amount;
					}
					public long extract(F resource, long max, TransactionContext tx) {
						var s = current(index); if (s == null) return 0;
						long before = generation.getAsLong();
						long amount = s.extract(resource, max, tx); stillValid(valid); unchanged(before, generation); return amount;
					}
				};
			}
			public Iterator<StorageView<F>> iterator() {
				return new Iterator<>() {
					int next;
					public boolean hasNext() { return next < getSlotCount(); }
					public StorageView<F> next() { if (!hasNext()) throw new NoSuchElementException(); return getSlot(next++); }
				};
			}
		};
	}
}

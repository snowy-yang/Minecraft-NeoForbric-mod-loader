package net.neoforbric.kernel.runtime.transfer;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.neoforged.neoforge.energy.IEnergyStorage;
import team.reborn.energy.api.EnergyStorage;

/**
 * Team Reborn Energy (the Fabric ecosystem's energy API) and 1.21.1's immediate NeoForge
 * {@link IEnergyStorage}, bridged the same way the item and fluid adapters bridge their APIs:
 * <ul>
 * <li><b>Fabric consumer, NeoForge store</b> ({@link #fabric}): inside an open Fabric transaction the
 * Reborn-facing view answers with a {@code simulate=true} call and registers the real call as a close
 * callback that replays on commit and does nothing on abort. Between simulation and replay another actor
 * may change the store, so the replay may move less than the simulation said.</li>
 * <li><b>NeoForge consumer, Reborn store</b> ({@link #neo}): each operation opens a Fabric root of its own
 * (a NeoForge caller brings no transaction); a simulate aborts that scope, an execute commits it, so a
 * simulation leaves the Reborn store exactly as it found it.</li>
 * </ul>
 *
 * <p>26.x paired live transaction objects through both engines here; 1.21.1 NeoForge energy is immediate
 * with simulate booleans, so the pairing is gone and only the two honest half-bridges remain.
 *
 * <p>Units are 1:1 (see EnergyUnits). A Reborn request larger than an int is asked of NeoForge as
 * Integer.MAX_VALUE; the rest is never moved and stays in its source. A provider answer outside [0, request]
 * is rejected before the scope commits, so it is rolled back.
 *
 * <p>Only this class, RebornEnergyBridge and nothing else in the runtime names a Reborn type. They are loaded
 * only when KernelTransferInterop found Team Reborn Energy installed; without it no Reborn class is ever
 * requested.
 */
public final class RebornEnergyAdapters {
	private RebornEnergyAdapters() { }

	/** A NeoForge view of a Reborn store; our own Reborn view of a NeoForge store unwraps to that store. */
	public static IEnergyStorage neo(EnergyStorage storage) {
		Objects.requireNonNull(storage);
		if (storage instanceof FromNeo own) return own.handler();
		return new FromFabric(storage);
	}

	/** A Reborn view of a NeoForge store; our own NeoForge view of a Reborn store unwraps to that store. */
	public static EnergyStorage fabric(IEnergyStorage handler) {
		Objects.requireNonNull(handler);
		if (handler instanceof FromFabric own) return own.storage();
		return new FromNeo(handler);
	}

	/** A Reborn store resolved again for every operation, under LiveTransferEndpoints' rules. */
	public static EnergyStorage live(Supplier<EnergyStorage> lookup, BooleanSupplier valid, LongSupplier generation) {
		return new Live(lookup, valid, generation);
	}

	/** Reborn (transactional) seen as NeoForge (immediate): each call owns a one-shot Fabric root. */
	private record FromFabric(EnergyStorage storage) implements IEnergyStorage {
		public int receiveEnergy(int maximum, boolean simulate) { return move(maximum, simulate, true); }
		public int extractEnergy(int maximum, boolean simulate) { return move(maximum, simulate, false); }
		private int move(int maximum, boolean simulate, boolean receive) {
			if (maximum < 0) throw new IllegalArgumentException("Negative energy amount");
			if (maximum == 0) return 0;
			try (Transaction nested = Transaction.openOuter()) {
				long moved = EnergyUnits.moved(receive ? storage.insert(maximum, nested) : storage.extract(maximum, nested), maximum);
				if (!simulate) nested.commit();
				return (int) moved;
			} catch (LiveTransferEndpoints.Unavailable invalidated) {
				NativeTransferAdapters.requireSuccessfulRollback(invalidated, storage);
				TransferIssues.report("ENDPOINT_INVALIDATED", storage, invalidated.getMessage() + "; the nested operation was rolled back");
				return 0;
			}
		}
		public int getEnergyStored() { return EnergyUnits.saturated(EnergyUnits.reported(storage.getAmount())); }
		public int getMaxEnergyStored() { return EnergyUnits.saturated(EnergyUnits.reported(storage.getCapacity())); }
		public boolean canExtract() { return storage.supportsExtraction(); }
		public boolean canReceive() { return storage.supportsInsertion(); }
		@Override public boolean equals(Object other) { return this == other; }
		@Override public int hashCode() { return System.identityHashCode(this); }
	}

	/** NeoForge (immediate) seen as Reborn (transactional): answer with the simulation, replay at commit. */
	private record FromNeo(IEnergyStorage handler) implements EnergyStorage {
		public boolean supportsInsertion() { return handler.canReceive(); }
		public boolean supportsExtraction() { return handler.canExtract(); }
		public long insert(long maximum, TransactionContext context) { return move(maximum, context, true); }
		public long extract(long maximum, TransactionContext context) { return move(maximum, context, false); }
		private long move(long maximum, TransactionContext context, boolean receive) {
			int request = EnergyUnits.request(maximum);
			if (request == 0) return 0;
			int simulated = receive ? handler.receiveEnergy(request, true) : handler.extractEnergy(request, true);
			if (EnergyUnits.moved(simulated, request) == 0) return 0;
			// The real call runs when the Fabric transaction commits, with the simulated amount as its cap.
			// A store that dies or shrinks before then moves less than promised, and the mismatch is reported.
			context.addCloseCallback((closed, result) -> {
				int moved = receive ? handler.receiveEnergy(simulated, false) : handler.extractEnergy(simulated, false);
				if (moved != simulated) TransferIssues.report("REPLAY_MISMATCH", handler,
						"the real energy move transferred " + moved + " of the promised " + simulated + "; another actor changed the store between simulate and commit");
			});
			return simulated;
		}
		public long getAmount() { return EnergyUnits.reported(handler.getEnergyStored()); }
		public long getCapacity() { return EnergyUnits.reported(handler.getMaxEnergyStored()); }
		@Override public boolean equals(Object other) { return this == other; }
		@Override public int hashCode() { return System.identityHashCode(this); }
	}

	private record Live(Supplier<EnergyStorage> lookup, BooleanSupplier valid, LongSupplier generation) implements EnergyStorage {
		private EnergyStorage current() { return valid.getAsBoolean() ? lookup.get() : null; }
		public boolean supportsInsertion() { var s = current(); return s != null && s.supportsInsertion(); }
		public boolean supportsExtraction() { var s = current(); return s != null && s.supportsExtraction(); }
		public long insert(long maximum, TransactionContext context) {
			var s = current(); if (s == null) return 0;
			long before = generation.getAsLong();
			long moved = s.insert(maximum, context); LiveTransferEndpoints.stillValid(valid); LiveTransferEndpoints.unchanged(before, generation); return moved;
		}
		public long extract(long maximum, TransactionContext context) {
			var s = current(); if (s == null) return 0;
			long before = generation.getAsLong();
			long moved = s.extract(maximum, context); LiveTransferEndpoints.stillValid(valid); LiveTransferEndpoints.unchanged(before, generation); return moved;
		}
		public long getAmount() { var s = current(); return s == null ? 0 : s.getAmount(); }
		public long getCapacity() { var s = current(); return s == null ? 0 : s.getCapacity(); }
		@Override public boolean equals(Object other) { return this == other; }
		@Override public int hashCode() { return System.identityHashCode(this); }
	}
}

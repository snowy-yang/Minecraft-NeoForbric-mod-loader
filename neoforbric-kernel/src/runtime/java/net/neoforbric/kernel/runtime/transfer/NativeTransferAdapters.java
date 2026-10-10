package net.neoforbric.kernel.runtime.transfer;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Slot-preserving item/fluid adapters between Fabric's transactional Storage and 1.21.1's immediate
 * NeoForge capabilities.
 *
 * <p>26.x paired live transaction objects across the two engines through a NeoForge transaction framework;
 * 1.21.1 has none, and its capabilities are immediate with {@code simulate}/{@code execute} flags instead.
 * The pairing is replaced by two half-bridges, each honestly weaker than a real transaction, in the same
 * direction as the vanilla hopper that simulates a move before performing it:
 * <ul>
 * <li><b>Fabric consumer, NeoForge store</b> ({@link #fabric}): inside an open Fabric transaction every
 * operation runs the NeoForge call with {@code simulate=true} and answers with what the simulation returned;
 * a close callback replays the same call with {@code simulate=false} when the Fabric transaction commits, and
 * does nothing when it aborts. Between simulation and replay another actor may change the store, so the
 * replay may move less than the simulation said — the price of bridging an immediate API into a
 * transactional one. The amount reported to the Fabric caller describes the simulated outcome.</li>
 * <li><b>NeoForge consumer, Fabric store</b> ({@link #neo}): each operation joins the Fabric transaction
 * already open on the thread, or opens a root of its own; {@code simulate=true} performs in a nested scope
 * and aborts it, {@code execute} commits. A Fabric store therefore keeps its own rollback, and a simulation
 * leaves it exactly as it found it.</li>
 * </ul>
 *
 * <p>Amounts are never rounded after a mutation: a sub-quantum Fabric result is rolled back, then retried
 * once with an exactly representable maximum. An arbitrary unslotted Fabric Storage cannot implement indexed
 * insertion and is intentionally not accepted. Introspection is conservative: Fabric has no per-resource
 * validity query. Fluids: {@code fill}/{@code drain} choose their own tank, so a slot-indexed Fabric view of
 * a fluid handler is read-accurate while its mutations land wherever the handler puts them — the same answer
 * the whole-storage operation would have given.
 */
public final class NativeTransferAdapters {
	private NativeTransferAdapters() { }

	// ---- Public per-kind entries: NeoForge handler -> Fabric SlottedStorage ----

	/** A fixed NeoForge item handler as Fabric sees it; our own view of a Fabric storage unwraps to it. */
	public static SlottedStorage<ItemVariant> fabric(IItemHandler handler, TransferCodec<ItemVariant, ItemStack> codec) {
		Objects.requireNonNull(handler);
		check(codec);
		if (handler instanceof ItemNeoView own && own.codec == codec) return own.storage();
		return fabricCore(ItemOps.INSTANCE, () -> handler, null, null, codec);
	}

	/** A fixed NeoForge fluid handler as Fabric sees it; our own view of a Fabric storage unwraps to it. */
	public static SlottedStorage<FluidVariant> fabricFluids(IFluidHandler handler, TransferCodec<FluidVariant, FluidStack> codec) {
		Objects.requireNonNull(handler);
		check(codec);
		if (handler instanceof FluidNeoView own && own.codec == codec) return own.storage();
		return fabricCore(FluidOps.INSTANCE, () -> handler, null, null, codec);
	}

	/** A live-resolved NeoForge item handler as Fabric sees it: every operation asks the endpoint again. */
	public static SlottedStorage<ItemVariant> fabric(Supplier<IItemHandler> lookup, BooleanSupplier valid, LongSupplier generation,
			TransferCodec<ItemVariant, ItemStack> codec) {
		Objects.requireNonNull(lookup);
		check(codec);
		return fabricCore(ItemOps.INSTANCE, lookup, valid, generation, codec);
	}

	/** A live-resolved NeoForge fluid handler as Fabric sees it: every operation asks the endpoint again. */
	public static SlottedStorage<FluidVariant> fabricFluids(Supplier<IFluidHandler> lookup, BooleanSupplier valid, LongSupplier generation,
			TransferCodec<FluidVariant, FluidStack> codec) {
		Objects.requireNonNull(lookup);
		check(codec);
		return fabricCore(FluidOps.INSTANCE, lookup, valid, generation, codec);
	}

	// ---- Public per-kind entries: Fabric SlottedStorage -> NeoForge handler ----

	/** A fixed Fabric slotted item storage as NeoForge sees it; our own view of a NeoForge handler unwraps to it. */
	public static IItemHandler neo(SlottedStorage<ItemVariant> storage, TransferCodec<ItemVariant, ItemStack> codec) {
		Objects.requireNonNull(storage);
		check(codec);
		if (storage instanceof FabricEngine<?, ?> engine && engine.codec == codec && engine.ops == ItemOps.INSTANCE)
			return ((FabricEngine<ItemVariant, IItemHandler>) engine).original();
		return new ItemNeoView(new NeoEngine<>(storage, codec), codec);
	}

	/** A fixed Fabric slotted fluid storage as NeoForge sees it; our own view of a NeoForge handler unwraps to it. */
	public static IFluidHandler neoFluids(SlottedStorage<FluidVariant> storage, TransferCodec<FluidVariant, FluidStack> codec) {
		Objects.requireNonNull(storage);
		check(codec);
		if (storage instanceof FabricEngine<?, ?> engine && engine.codec == codec && engine.ops == FluidOps.INSTANCE)
			return ((FabricEngine<FluidVariant, IFluidHandler>) engine).original();
		return new FluidNeoView(new NeoEngine<>(storage, codec), codec);
	}

	/** A live-resolved Fabric item storage as NeoForge sees it: every operation asks the endpoint again. */
	public static IItemHandler neo(Supplier<SlottedStorage<ItemVariant>> lookup, BooleanSupplier valid, LongSupplier generation,
			TransferCodec<ItemVariant, ItemStack> codec) {
		Objects.requireNonNull(lookup);
		check(codec);
		return new ItemNeoView(new NeoEngine<>(LiveTransferEndpoints.fabric(lookup, valid, generation, ItemVariant.blank()), codec), codec);
	}

	/** A live-resolved Fabric fluid storage as NeoForge sees it: every operation asks the endpoint again. */
	public static IFluidHandler neoFluids(Supplier<SlottedStorage<FluidVariant>> lookup, BooleanSupplier valid, LongSupplier generation,
			TransferCodec<FluidVariant, FluidStack> codec) {
		Objects.requireNonNull(lookup);
		check(codec);
		return new FluidNeoView(new NeoEngine<>(LiveTransferEndpoints.fabric(lookup, valid, generation, FluidVariant.blank()), codec), codec);
	}

	// ---- Package-private engine entries (the transfer scenario suite drives these directly) ----

	/**
	 * How to drive one kind of NeoForge handler: read its slots, and move with a {@code simulate} flag —
	 * the whole of 1.21.1's transaction surface. {@code slot < 0} means "the handler's choice", which for
	 * items iterates and for fluids is what {@code fill}/{@code drain} already do.
	 */
	interface NativeOps<F, H> {
		int slotCount(H handler);
		/** The identity at {@code slot}, in Fabric terms; null when the slot has none this codec converts. */
		F resource(H handler, int slot);
		/** The amount at {@code slot}, in native units. */
		long amount(H handler, int slot);
		/** The capacity at {@code slot}, in native units, regardless of resource. */
		long capacity(H handler, int slot);
		/** Whether the slot can ever accept; NeoForge has no per-resource validity query on this path. */
		boolean acceptable(H handler, int slot);
		/** How many native units moved; never mutates when {@code simulate}. */
		long insert(H handler, int slot, F resource, long maximum, boolean simulate);
		/** How many native units of {@code resource} moved; never mutates when {@code simulate}. */
		long extract(H handler, int slot, F resource, long maximum, boolean simulate);
	}

	/** The Fabric view of a NeoForge handler, fixed or live-resolved; the scenario suite drives this directly. */
	static <F, H> SlottedStorage<F> fabricCore(NativeOps<F, H> ops, Supplier<H> lookup, BooleanSupplier valid, LongSupplier generation,
			TransferCodec<F, ?> codec) {
		Objects.requireNonNull(ops);
		Objects.requireNonNull(lookup);
		check(codec);
		return new FabricEngine<>(ops, lookup, valid, generation, codec);
	}

	/**
	 * The mutation surface the NeoForge facades (and the scenario suite) drive on a Fabric storage. Direction
	 * and execution are separate axes: a simulate answers with what would move and aborts its scope (the one
	 * direction with a real rollback), an execute commits it. Returns the native units moved.
	 */
	interface NeoMoves<F> {
		long insert(int slot, F resource, long nativeAmount, boolean execute);
		long extract(int slot, F resource, long nativeAmount, boolean execute);
	}

	/** The NeoForge-side semantics core, parameterized for the tests; production reaches it through the facades. */
	static <F> NeoMoves<F> neoCore(SlottedStorage<F> storage, TransferCodec<F, ?> codec) {
		Objects.requireNonNull(storage);
		check(codec);
		return new NeoEngine<>(storage, codec);
	}

	// ---- Shared helpers ----

	private static void check(TransferCodec<?, ?> codec) {
		Objects.requireNonNull(codec);
		if (codec.fabricUnits() <= 0) throw new IllegalArgumentException("A transfer unit must be positive");
	}

	private static void amount(long amount) {
		if (amount < 0) throw new IllegalArgumentException("Negative transfer amount");
	}

	private static long validResult(long moved, long maximum) {
		if (moved < 0 || moved > maximum) throw new IllegalStateException("Provider returned an invalid transfer amount: " + moved + "/" + maximum);
		return moved;
	}

	private static long multiply(long nativeAmount, long units) {
		if (nativeAmount < 0) throw new IllegalStateException("Provider returned a negative amount/capacity");
		return nativeAmount > Long.MAX_VALUE / units ? Long.MAX_VALUE : nativeAmount * units;
	}

	static void requireSuccessfulRollback(LiveTransferEndpoints.Unavailable failure, Object provider) {
		// An abort whose close failed leaves the Fabric participant in an unknown state; reporting zero in
		// that case would assert a rollback the participant itself failed to perform.
		if (failure.getSuppressed().length != 0) {
			TransferIssues.report("ROLLBACK_FAILED", provider, "Endpoint invalidated and the Fabric rollback failed; resource state is unknown");
			throw new IllegalStateException("Cross-API rollback failed; resource state is unknown", failure);
		}
	}

	// ---- Fabric -> NeoForge: a SlottedStorage view of an immediate handler ----

	/**
	 * Every operation simulates on the NeoForge side and registers the real call as a Fabric close callback.
	 * The simulation promises what the replay will attempt, not what it will achieve: another actor may change
	 * the store between the two, and the replay runs with the simulated amount as its cap. A replay that
	 * cannot run (endpoint gone) or fails is reported and re-thrown, aborting the Fabric transaction — the
	 * same all-or-nothing the 26.x pairing gave, at the cost of never committing a promise we could not keep.
	 */
	private static final class FabricEngine<F, H> implements SlottedStorage<F> {
		private final NativeOps<F, H> ops;
		private final Supplier<H> lookup;
		private final BooleanSupplier valid;
		private final LongSupplier generation;
		private final TransferCodec<F, ?> codec;

		FabricEngine(NativeOps<F, H> ops, Supplier<H> lookup, BooleanSupplier valid, LongSupplier generation, TransferCodec<F, ?> codec) {
			this.ops = ops;
			this.lookup = lookup;
			this.valid = valid;
			this.generation = generation;
			this.codec = codec;
		}

		/** The handler this view was built over, for the {@code neo()} unwrap. */
		H original() { return lookup.get(); }

		private H current() {
			if (valid != null && !valid.getAsBoolean()) return null;
			return lookup.get();
		}

		public int getSlotCount() {
			H handler = current();
			return handler == null ? 0 : ops.slotCount(handler);
		}

		public SingleSlotStorage<F> getSlot(int slot) {
			Objects.checkIndex(slot, getSlotCount());
			return new Slot(slot);
		}

		public long insert(F resource, long maximum, TransactionContext transaction) { return move(-1, resource, maximum, transaction, true); }
		public long extract(F resource, long maximum, TransactionContext transaction) { return move(-1, resource, maximum, transaction, false); }

		long move(int slot, F resource, long maximum, TransactionContext context, boolean insert) {
			amount(maximum);
			if (maximum == 0 || codec.isFabricBlank(resource)) return 0;
			H handler = current();
			if (handler == null) return 0;
			long before = generation == null ? 0 : generation.getAsLong();
			long request = Math.min(maximum / codec.fabricUnits(), Integer.MAX_VALUE);
			if (request == 0) return 0;
			long simulated;
			try {
				simulated = validResult(insert ? ops.insert(handler, slot, resource, request, true) : ops.extract(handler, slot, resource, request, true), request);
				LiveTransferEndpoints.stillValid(valid);
				LiveTransferEndpoints.unchanged(before, generation);
			} catch (LiveTransferEndpoints.Unavailable invalidated) {
				requireSuccessfulRollback(invalidated, handler);
				TransferIssues.report("ENDPOINT_INVALIDATED", handler, invalidated.getMessage() + "; nothing moved and no replay was promised");
				return 0;
			}
			if (simulated == 0) return 0;
			// The promise needs BOTH hooks, each for a failure the other cannot see (both probed against the
			// real 1.21.1 fabric-transfer-api):
			// - an ordinary close callback fires when THIS scope closes — eagerly at a nested commit — so it is
			//   the only place that learns a nested scope ABORTED. It cancels the promise; a committed scope
			//   leaves it alive for the root's decision.
			// - an outer close callback defers to the outermost close, so the replay never runs at a nested
			//   commit. But it fires at the root close with the ROOT's result even when the promise's own scope
			//   aborted — without the cancellation it would replay a move fabric already undid.
			boolean[] cancelled = { false };
			context.addCloseCallback((closed, result) -> { if (!result.wasCommitted()) cancelled[0] = true; });
			context.addOuterCloseCallback(result -> {
				if (cancelled[0] || !result.wasCommitted()) return;
				// The replay re-resolves and re-checks: the endpoint may die between the simulation and the
				// commit. A dead endpoint or a failed replay is REPORTED, never thrown: fabric does not roll
				// back the other participants when a close callback throws (probed: a SnapshotParticipant
				// that committed kept its mutation), so rethrowing could not deliver all-or-nothing — it would
				// only crash the commit path after the loss already happened.
				H live;
				try {
					live = current();
				} catch (LiveTransferEndpoints.Unavailable invalidated) {
					TransferIssues.report("ENDPOINT_INVALIDATED", lookup, "the endpoint was invalidated before the simulated move replayed; nothing was written to it");
					return;
				}
				if (live == null) {
					TransferIssues.report("ENDPOINT_INVALIDATED", lookup, "the endpoint died before the simulated move could be performed");
					return;
				}
				try {
					long moved = insert ? ops.insert(live, slot, resource, simulated, false) : ops.extract(live, slot, resource, simulated, false);
					if (moved != simulated) TransferIssues.report("REPLAY_MISMATCH", live,
							"the real move transferred " + moved + " of the promised " + simulated + "; another actor changed the store between simulate and commit");
				} catch (RuntimeException failed) {
					TransferIssues.report("REPLAY_FAILED", live, "the real move threw after the simulation promised " + simulated
							+ "; the native store may be short by that amount");
				}
			});
			return simulated * codec.fabricUnits();
		}

		public Iterator<StorageView<F>> iterator() {
			return new Iterator<>() {
				int next;
				public boolean hasNext() { return next < getSlotCount(); }
				public StorageView<F> next() { if (!hasNext()) throw new NoSuchElementException(); return getSlot(next++); }
			};
		}

		private final class Slot implements SingleSlotStorage<F> {
			final int index;
			Slot(int index) { this.index = index; }
			public boolean isResourceBlank() { H h = current(); return h == null || ops.resource(h, index) == null || codec.isFabricBlank(ops.resource(h, index)); }
			public F getResource() { H h = current(); return h == null ? null : ops.resource(h, index); }
			public long getAmount() { H h = current(); return h == null ? 0 : multiply(ops.amount(h, index), codec.fabricUnits()); }
			public long getCapacity() { H h = current(); return h == null ? 0 : multiply(ops.capacity(h, index), codec.fabricUnits()); }
			public boolean supportsInsertion() { H h = current(); return h != null && ops.acceptable(h, index); }
			public boolean supportsExtraction() { H h = current(); return h != null; }
			public long insert(F resource, long maximum, TransactionContext context) { return move(index, resource, maximum, context, true); }
			public long extract(F resource, long maximum, TransactionContext context) { return move(index, resource, maximum, context, false); }
		}
	}

	// ---- NeoForge -> Fabric: an immediate handler view of a transactional storage ----

	/**
	 * NeoForge's callers arrive without a Fabric transaction, so every operation opens one: joined to the
	 * thread's open transaction when there is one (a mod may call us from inside a Fabric callback), else a
	 * root that closes before the answer returns. A simulation always aborts its own scope, so it leaves the
	 * Fabric store exactly as it found it — the one direction that still has a real rollback on this line.
	 */
	private record NeoEngine<F>(SlottedStorage<F> storage, TransferCodec<F, ?> codec) implements NeoMoves<F> {
		public long insert(int slot, F resource, long nativeAmount, boolean execute) { return move(slot, resource, nativeAmount, execute, true); }
		public long extract(int slot, F resource, long nativeAmount, boolean execute) { return move(slot, resource, nativeAmount, execute, false); }

		private long move(int slot, F resource, long nativeAmount, boolean execute, boolean inserting) {
			amount(nativeAmount);
			if (nativeAmount == 0 || resource == null || codec.isFabricBlank(resource)) return 0;
			// A provider may accept less than requested, including a fraction of one mB. Every unsuccessful
			// trial is aborted in its OWN nested scope; no rounded amount is ever returned for an unrounded
			// mutation. The first trial offers everything; the second offers only what the first proved
			// representable.
			long request = Math.multiplyExact(nativeAmount, codec.fabricUnits());
			for (int attempt = 0; attempt < 2 && request > 0; attempt++) {
				// Join the thread's open transaction when a Fabric callback called us; else open a root.
				TransactionContext joined = Transaction.getCurrentUnsafe();
				try (Transaction nested = Transaction.openNested(joined)) {
					long moved = validResult(slot < 0
							? (inserting ? storage.insert(resource, request, nested) : storage.extract(resource, request, nested))
							: slotOperation(slot, resource, request, nested, inserting), request);
					if (moved % codec.fabricUnits() == 0) {
						if (execute) nested.commit();
						return moved / codec.fabricUnits();
					}
					request = moved / codec.fabricUnits() * codec.fabricUnits();
				} catch (LiveTransferEndpoints.Unavailable invalidated) {
					requireSuccessfulRollback(invalidated, storage);
					TransferIssues.report("ENDPOINT_INVALIDATED", storage, invalidated.getMessage() + "; the nested operation was rolled back");
					return 0;
				}
			}
			return 0;
		}

		private long slotOperation(int slot, F resource, long request, Transaction nested, boolean inserting) {
			SingleSlotStorage<F> at = storage.getSlot(slot);
			return inserting ? at.insert(resource, request, nested) : at.extract(resource, request, nested);
		}
	}

	// ---- The NeoForge facades ----

	/** An {@link IItemHandler} over a Fabric slotted storage. Unwraps to the storage it came from. */
	private record ItemNeoView(NeoEngine<ItemVariant> engine, TransferCodec<ItemVariant, ItemStack> codec) implements IItemHandler {
		SlottedStorage<ItemVariant> storage() { return engine.storage(); }
		public int getSlots() { return engine.storage().getSlotCount(); }
		public ItemStack getStackInSlot(int slot) {
			SingleSlotStorage<ItemVariant> at = engine.storage().getSlot(slot);
			return codec.toNeo(at.getResource(), at.getAmount());
		}
		public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
			amount(stack.getCount());
			if (stack.isEmpty()) return stack;
			ItemVariant variant = codec.toFabric(stack);
			long moved = engine.insert(slot, variant, stack.getCount(), !simulate);
			return moved == 0 ? stack : moved >= stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount((int) (stack.getCount() - moved));
		}
		public ItemStack extractItem(int slot, int amount, boolean simulate) {
			amount(amount);
			if (amount == 0) return ItemStack.EMPTY;
			SingleSlotStorage<ItemVariant> at = engine.storage().getSlot(slot);
			ItemVariant there = at.getResource();
			if (there == null || codec.isFabricBlank(there)) return ItemStack.EMPTY;
			long moved = engine.extract(slot, there, amount, !simulate);
			return moved == 0 ? ItemStack.EMPTY : codec.toNeo(there, moved);
		}
		public int getSlotLimit(int slot) { return (int) Math.min(engine.storage().getSlot(slot).getCapacity(), Integer.MAX_VALUE); }
		// Fabric cannot answer resource-specific validity without attempting a transaction. Avoid a false
		// negative when the storage is merely full; insert still delegates to its real filter.
		public boolean isItemValid(int slot, ItemStack stack) { return !stack.isEmpty() && engine.storage().getSlot(slot).supportsInsertion(); }
	}

	/** An {@link IFluidHandler} over a Fabric slotted storage. Unwraps to the storage it came from. */
	private record FluidNeoView(NeoEngine<FluidVariant> engine, TransferCodec<FluidVariant, FluidStack> codec) implements IFluidHandler {
		SlottedStorage<FluidVariant> storage() { return engine.storage(); }
		public int getTanks() { return engine.storage().getSlotCount(); }
		public FluidStack getFluidInTank(int tank) {
			SingleSlotStorage<FluidVariant> at = engine.storage().getSlot(tank);
			return codec.toNeo(at.getResource(), at.getAmount() / codec.fabricUnits());
		}
		public int getTankCapacity(int tank) { return (int) Math.min(engine.storage().getSlot(tank).getCapacity() / codec.fabricUnits(), Integer.MAX_VALUE); }
		public boolean isFluidValid(int tank, FluidStack stack) { return !stack.isEmpty() && engine.storage().getSlot(tank).supportsInsertion(); }
		public int fill(FluidStack resource, FluidAction action) {
			amount(resource.getAmount());
			if (resource.isEmpty()) return 0;
			return (int) engine.insert(-1, codec.toFabric(resource), resource.getAmount(), action.execute());
		}
		public FluidStack drain(FluidStack resource, FluidAction action) {
			amount(resource.getAmount());
			if (resource.isEmpty()) return FluidStack.EMPTY;
			long moved = engine.extract(-1, codec.toFabric(resource), resource.getAmount(), action.execute());
			return moved == 0 ? FluidStack.EMPTY : codec.toNeo(codec.toFabric(resource), moved);
		}
		public FluidStack drain(int maxAmount, FluidAction action) {
			amount(maxAmount);
			if (maxAmount == 0) return FluidStack.EMPTY;
			for (StorageView<FluidVariant> view : engine.storage()) {
				FluidVariant there = view.getResource();
				if (there == null || codec.isFabricBlank(there)) continue;
				long moved = engine.extract(-1, there, maxAmount, action.execute());
				return moved == 0 ? FluidStack.EMPTY : codec.toNeo(there, moved);
			}
			return FluidStack.EMPTY;
		}
	}

	// ---- The per-kind native strategies ----

	/** 1.21.1 item handlers: slot-indexed {@code insertItem}/{@code extractItem} with a simulate flag. */
	private enum ItemOps implements NativeOps<ItemVariant, IItemHandler> {
		INSTANCE;
		private final TransferCodec<ItemVariant, ItemStack> codec = TransferResources.ITEMS;
		public int slotCount(IItemHandler handler) { return handler.getSlots(); }
		public ItemVariant resource(IItemHandler handler, int slot) { return codec.toFabric(handler.getStackInSlot(slot)); }
		public long amount(IItemHandler handler, int slot) { return codec.neoAmount(handler.getStackInSlot(slot)); }
		public long capacity(IItemHandler handler, int slot) { return handler.getSlotLimit(slot); }
		public boolean acceptable(IItemHandler handler, int slot) { return true; }
		public long insert(IItemHandler handler, int slot, ItemVariant resource, long maximum, boolean simulate) {
			ItemStack offered = codec.toNeo(resource, maximum);
			if (offered.isEmpty()) return 0;
			// slot < 0: the handler's own choice, exactly as a vanilla hopper's loop.
			if (slot < 0) {
				long moved = 0;
				for (int i = 0; i < handler.getSlots() && moved < maximum; i++) {
					ItemStack remainder = handler.insertItem(i, offered.copyWithCount((int) (maximum - moved)), simulate);
					long took = (maximum - moved) - remainder.getCount();
					if (took > 0) { moved += took; offered = codec.toNeo(resource, maximum - moved); }
					if (offered.isEmpty()) break;
				}
				return moved;
			}
			ItemStack remainder = handler.insertItem(slot, offered, simulate);
			return maximum - remainder.getCount();
		}
		public long extract(IItemHandler handler, int slot, ItemVariant resource, long maximum, boolean simulate) {
			if (slot < 0) {
				long moved = 0;
				for (int i = 0; i < handler.getSlots() && moved < maximum; i++) {
					ItemStack there = handler.getStackInSlot(i);
					if (there.isEmpty() || !resource.matches(there)) continue;
					ItemStack took = handler.extractItem(i, (int) Math.min(maximum - moved, there.getCount()), simulate);
					moved += took.getCount();
				}
				return moved;
			}
			ItemStack there = handler.getStackInSlot(slot);
			if (there.isEmpty() || !resource.matches(there)) return 0;
			return handler.extractItem(slot, (int) Math.min(maximum, there.getCount()), simulate).getCount();
		}
	}

	/** 1.21.1 fluid handlers: whole-tank {@code fill}/{@code drain} with a FluidAction, which is the simulate flag. */
	private enum FluidOps implements NativeOps<FluidVariant, IFluidHandler> {
		INSTANCE;
		private final TransferCodec<FluidVariant, FluidStack> codec = TransferResources.FLUIDS;
		public int slotCount(IFluidHandler handler) { return handler.getTanks(); }
		public FluidVariant resource(IFluidHandler handler, int tank) { return codec.toFabric(handler.getFluidInTank(tank)); }
		public long amount(IFluidHandler handler, int tank) { return codec.neoAmount(handler.getFluidInTank(tank)); }
		public long capacity(IFluidHandler handler, int tank) { return handler.getTankCapacity(tank); }
		public boolean acceptable(IFluidHandler handler, int tank) { return true; }
		public long insert(IFluidHandler handler, int tank, FluidVariant resource, long maximum, boolean simulate) {
			// fill chooses its tank; the slot index is advisory, as documented on the class.
			return handler.fill(codec.toNeo(resource, maximum), simulate ? FluidAction.SIMULATE : FluidAction.EXECUTE);
		}
		public long extract(IFluidHandler handler, int tank, FluidVariant resource, long maximum, boolean simulate) {
			return handler.drain(codec.toNeo(resource, maximum), simulate ? FluidAction.SIMULATE : FluidAction.EXECUTE).getAmount();
		}
	}
}

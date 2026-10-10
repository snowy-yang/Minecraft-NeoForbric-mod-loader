package net.neoforbric.kernel.runtime.transfer;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;

/**
 * The 1.21.1 semantics, exercised against a real marker resource: no Minecraft bootstrap, no thread-local
 * state shared across scenarios. Every rule checked here was probed against the genuine fabric-transfer-api
 * classes this suite runs with:
 * <ul>
 * <li><b>an aborted fabric transaction never touches the native store</b> — the adapter simulates and
 *     promises the replay only at the OUTERMOST close, so a nested child that commits and a root that
 *     aborts leaves the native store exactly as it was (an ordinary close callback would have fired at
 *     the child's commit, and there would be no undo);</li>
 * <li><b>a commit replays what the simulation promised, best-effort</b> — another actor may have changed
 *     the store in between, and a replay that moves less or fails is reported (fabric does not roll back
 *     the other participants when a close callback throws — probed — so rethrowing could not deliver
 *     all-or-nothing, only crash the commit after the loss already happened);</li>
 * <li><b>the native consumer owns a fabric scope</b> — joined to the thread's open transaction or a root of
 *     its own; a simulate aborts its scope (the one direction with a real rollback), an execute commits it;</li>
 * <li><b>units</b> — the codec's fabric-units factor is respected in both directions and never rounds a
 *     mutation;</li>
 * <li><b>conservation</b> — units moved between two endpoints through both directions of the adapters;</li>
 * <li><b>the live endpoint rules</b> — re-resolved per operation, empty once gone.</li>
 * </ul>
 */
public final class NativeTransferScenarios {
	public record Token(String name) {
		@Override public boolean equals(Object other) { return other instanceof Token t && name.equals(t.name); }
		@Override public int hashCode() { return name.hashCode(); }
	}
	private static final Token VALUE = new Token("test:resource"), EMPTY = new Token("empty");

	/** Identity in both directions, a chosen unit factor, and the blank marker as the only fabric blank. */
	private static TransferCodec<Token, Token> codec(long units) {
		return new TransferCodec<>() {
			public Token toNeo(Token resource, long nativeAmount) { return resource; }
			public Token toFabric(Token nativeResource) { return nativeResource; }
			public long neoAmount(Token nativeResource) { return nativeResource == null ? 0 : 1; }
			public boolean isFabricBlank(Token resource) { return EMPTY.equals(resource); }
			public long fabricUnits() { return units; }
		};
	}

	// ---- Tanks: the two stores the engines drive ----

	/** The native side: an immediate store with simulate flags — the shape 1.21.1's capabilities hand out. */
	static class NeoTank {
		long amount; final long capacity; int realCalls; int simulations;
		NeoTank(long capacity) { this.capacity = capacity; }
		long insert(long maximum, boolean simulate) {
			long moved = Math.min(capacity - amount, maximum);
			if (!simulate) { amount += moved; realCalls++; } else simulations++;
			return moved;
		}
		long extract(long maximum, boolean simulate) {
			long moved = Math.min(amount, maximum);
			if (!simulate) { amount -= moved; realCalls++; } else simulations++;
			return moved;
		}
	}

	/** The fabric side: a genuine SnapshotParticipant, so Fabric's own rollback machinery is what runs. */
	static class FabricTank extends SnapshotParticipant<Long> implements SingleSlotStorage<Token> {
		long amount; final long capacity; int finalCommits; int rollbacks;
		FabricTank(long capacity) { this.capacity = capacity; }
		protected Long createSnapshot() { return amount; }
		protected void readSnapshot(Long snapshot) { amount = snapshot; rollbacks++; }
		protected void onFinalCommit() { finalCommits++; }
		public boolean isResourceBlank() { return amount == 0; }
		public Token getResource() { return amount == 0 ? EMPTY : VALUE; }
		public long getAmount() { return amount; }
		public long getCapacity() { return capacity; }
		public long insert(Token resource, long maximum, TransactionContext tx) {
			if (!VALUE.equals(resource)) return 0;
			updateSnapshots(tx);
			long moved = Math.min(capacity - amount, maximum); amount += moved; return moved;
		}
		public long extract(Token resource, long maximum, TransactionContext tx) {
			if (!VALUE.equals(resource)) return 0;
			updateSnapshots(tx);
			long moved = Math.min(amount, maximum); amount -= moved; return moved;
		}
	}

	/** NativeOps over a NeoTank: one slot, insert/extract with simulate, everything in native units. */
	private static final NativeTransferAdapters.NativeOps<Token, NeoTank> NEO_OPS = new NativeTransferAdapters.NativeOps<>() {
		public int slotCount(NeoTank tank) { return 1; }
		public Token resource(NeoTank tank, int slot) { return tank.amount == 0 ? null : VALUE; }
		public long amount(NeoTank tank, int slot) { return tank.amount; }
		public long capacity(NeoTank tank, int slot) { return tank.capacity; }
		public boolean acceptable(NeoTank tank, int slot) { return true; }
		public long insert(NeoTank tank, int slot, Token resource, long maximum, boolean simulate) {
			return VALUE.equals(resource) ? tank.insert(maximum, simulate) : 0;
		}
		public long extract(NeoTank tank, int slot, Token resource, long maximum, boolean simulate) {
			return VALUE.equals(resource) ? tank.extract(maximum, simulate) : 0;
		}
	};

	/** The fabric view of a native tank, through the same core the public adapters use. */
	private static SlottedStorage<Token> fabric(NeoTank tank, long units) {
		return NativeTransferAdapters.fabricCore(NEO_OPS, () -> tank, null, null, codec(units));
	}
	private static SlottedStorage<Token> fabric(Supplier<NeoTank> lookup, BooleanSupplier valid, long units) {
		return NativeTransferAdapters.fabricCore(NEO_OPS, lookup, valid, null, codec(units));
	}
	/** The native moves surface over a fabric tank, through the same core the facades use. */
	private static NativeTransferAdapters.NeoMoves<Token> neo(FabricTank tank, long units) {
		return NativeTransferAdapters.neoCore(tank, codec(units));
	}

	// ---- Checks ----

	private static void eq(long wanted, long actual) { if (wanted != actual) throw new AssertionError(wanted + " != " + actual); }
	private static void yes(boolean value) { if (!value) throw new AssertionError("condition failed"); }
	private static void closed() { yes(Transaction.getCurrentUnsafe() == null); }

	// ---- Scenarios: the fabric consumer, native store ----

	/**
	 * A committed child keeps its promise pending until the root decides: an aborted root drops everything —
	 * the child's included — and a committed root replays the whole sequence, once each, in order.
	 */
	public static void fabricNestedRollback() {
		NeoTank tank = new NeoTank(100); var storage = fabric(tank, 1);
		try (Transaction root = Transaction.openOuter()) {
			eq(10, storage.insert(VALUE, 10, root));
			try (Transaction child = root.openNested()) { eq(20, storage.insert(VALUE, 20, child)); child.commit(); }
			// The child committed, but its promise waits for the root: the native store is untouched.
			eq(0, tank.amount); eq(2, tank.simulations);
			try (Transaction child = root.openNested()) { eq(7, storage.insert(VALUE, 7, child)); }   // no commit
		}
		eq(0, tank.amount); eq(0, tank.realCalls); eq(3, tank.simulations); closed();
		try (Transaction root = Transaction.openOuter()) {
			eq(10, storage.insert(VALUE, 10, root));
			try (Transaction child = root.openNested()) { eq(20, storage.insert(VALUE, 20, child)); child.commit(); }
			try (Transaction child = root.openNested()) { eq(7, storage.insert(VALUE, 7, child)); child.commit(); }
			root.commit();
		}
		// 10 + 20 + 7 — replayed in the order they were promised.
		eq(37, tank.amount); eq(3, tank.realCalls); closed();
		// An extract can only take what has actually landed: the second transaction sees the replayed state.
		try (Transaction root = Transaction.openOuter()) { eq(7, storage.extract(VALUE, 7, root)); root.commit(); }
		eq(30, tank.amount); eq(4, tank.realCalls); closed();
	}

	/**
	 * The documented cost of the promised-replay design: within ONE transaction the native store still holds its
	 * pre-transaction state, so an extract after an insert in the same transaction sees none of the promised
	 * units. Fabric's own participants stage their writes and would see them; the native store cannot stage
	 * without the undo-on-abort fabric never gives a non-participant. Aborts stay perfectly clean instead.
	 */
	public static void intraTransactionReadsSeeThePreTransactionState() {
		NeoTank tank = new NeoTank(100); var storage = fabric(tank, 1);
		try (Transaction root = Transaction.openOuter()) {
			eq(40, storage.insert(VALUE, 40, root));
			eq(0, storage.extract(VALUE, 10, root));   // the insert has not landed yet
			eq(0, tank.amount);
			root.commit();
		}
		eq(40, tank.amount); eq(1, tank.realCalls); closed();
	}

	/** Repeated simulations reserve nothing; only the commit's replay mutates, once. */
	public static void simulation() {
		NeoTank tank = new NeoTank(23); var storage = fabric(tank, 1);
		for (int i = 0; i < 3; i++) {
			try (Transaction root = Transaction.openOuter()) { eq(23, storage.insert(VALUE, 100, root)); }
			eq(0, tank.amount); eq(0, tank.realCalls); eq(i + 1, tank.simulations);
		}
		try (Transaction root = Transaction.openOuter()) { eq(23, storage.insert(VALUE, 100, root)); root.commit(); }
		eq(23, tank.amount); eq(1, tank.realCalls); eq(4, tank.simulations); closed();
	}

	/** The replay may move less than promised: reported, never silently swallowed, never a failed commit. */
	public static void replayMismatch() {
		NeoTank tank = new NeoTank(100);
		TransferIssues.clearForTest();
		var storage = fabric(tank, 1);
		try (Transaction root = Transaction.openOuter()) {
			eq(30, storage.insert(VALUE, 30, root));
			// Another actor drains the tank between the simulation and the commit's replay.
			tank.amount = 95;
			root.commit();
		}
		eq(100, tank.amount); eq(1, tank.realCalls);
		yes(TransferIssues.findingsForTest().stream().anyMatch(f -> "REPLAY_MISMATCH".equals(f.code())));
		TransferIssues.clearForTest(); closed();
	}

	/**
	 * An endpoint that dies between simulate and commit is reported and the commit completes: fabric does not
	 * roll back the other participants when a close callback throws (probed against the real API — a committed
	 * SnapshotParticipant kept its mutation), so an exception could not undo anything. The units the fabric
	 * side already moved are lost to the dead endpoint, and the report is what says so.
	 */
	public static void deadEndpointAtCommitIsReported() {
		var present = new java.util.concurrent.atomic.AtomicBoolean(true);
		NeoTank tank = new NeoTank(100);
		FabricTank fabric = new FabricTank(100);
		TransferIssues.clearForTest();
		var storage = fabric(() -> tank, present::get, 1);
		try (Transaction root = Transaction.openOuter()) {
			eq(10, fabric.insert(VALUE, 10, root));
			eq(10, storage.insert(VALUE, 10, root));
			present.set(false);
			root.commit();
		}
		eq(10, fabric.amount); eq(0, tank.realCalls); eq(1, tank.simulations);
		yes(TransferIssues.findingsForTest().stream().anyMatch(f -> "ENDPOINT_INVALIDATED".equals(f.code())));
		TransferIssues.clearForTest(); closed();
	}

	/** A provider that throws mid-simulation never promises a replay, and the fabric scope still closes. */
	public static void providerFailure() {
		NeoTank tank = new NeoTank(100) {
			@Override long insert(long maximum, boolean simulate) {
				super.insert(maximum, simulate); throw new IllegalStateException("provider failed after simulating");
			}
		};
		var storage = fabric(tank, 1);
		try (Transaction root = Transaction.openOuter()) {
			try { storage.insert(VALUE, 12, root); throw new AssertionError("expected a failure"); }
			catch (IllegalStateException expected) { }
		}
		eq(0, tank.amount); eq(0, tank.realCalls); eq(1, tank.simulations); closed();
	}

	/** A provider answering outside [0, request] is rejected before any replay is promised. */
	public static void invalidAmounts() {
		NeoTank tank = new NeoTank(100) {
			@Override long insert(long maximum, boolean simulate) { super.insert(maximum, simulate); return maximum + 1; }
		};
		var storage = fabric(tank, 1);
		try (Transaction root = Transaction.openOuter()) {
			try { storage.insert(VALUE, 10, root); throw new AssertionError("expected a failure"); }
			catch (IllegalStateException expected) { }
		}
		eq(0, tank.amount); closed();
	}

	// ---- Scenarios: the native consumer, fabric store ----

	/** A simulate aborts its scope and leaves the fabric store untouched; an execute commits its scope. */
	public static void nativeSimulateAndExecute() {
		FabricTank tank = new FabricTank(100); var moves = neo(tank, 1);
		eq(30, moves.insert(-1, VALUE, 30, true)); eq(30, tank.amount); eq(1, tank.finalCommits); eq(0, tank.rollbacks);
		eq(30, moves.insert(-1, VALUE, 30, false));   // simulate: the store is unchanged
		eq(30, tank.amount); eq(1, tank.finalCommits); eq(1, tank.rollbacks); closed();
		eq(9, moves.insert(-1, VALUE, 9, true)); eq(39, tank.amount); eq(2, tank.finalCommits); closed();
		// Extraction is the other direction, same rules.
		eq(5, moves.extract(-1, VALUE, 5, false)); eq(39, tank.amount); eq(2, tank.rollbacks); closed();
		eq(5, moves.extract(-1, VALUE, 5, true)); eq(34, tank.amount); eq(3, tank.finalCommits); closed();
	}

	/** While a fabric transaction is open, the native consumer joins it — rollback of the root drops the move. */
	public static void nativeConsumerJoinsOpenFabricTransaction() {
		FabricTank tank = new FabricTank(100); var moves = neo(tank, 1);
		try (Transaction root = Transaction.openOuter()) {
			eq(10, moves.insert(-1, VALUE, 10, false));   // simulate aborts only its own nested scope
			eq(0, tank.amount);
			eq(10, moves.insert(-1, VALUE, 10, true));    // execute joins the open root
			eq(10, tank.amount);
		}
		eq(0, tank.amount); eq(0, tank.finalCommits); eq(2, tank.rollbacks); closed();
	}

	/** Units are respected: 81 fabric units per native unit, and no mutation ever rounds a fraction. */
	public static void quantization() {
		FabricTank tank = new FabricTank(1000); tank.amount = 170;
		var moves = neo(tank, 81);
		eq(2, moves.extract(-1, VALUE, 3, true));    // 3 native units = 243 fabric; only 162 there -> 2 whole
		eq(8, tank.amount); eq(1, tank.finalCommits); closed();
		eq(0, moves.extract(-1, VALUE, 1, true));    // 81 fabric = 1 native; 8 fabric cannot make one
		eq(8, tank.amount); eq(2, tank.rollbacks); closed();
		NeoTank neo = new NeoTank(100); neo.amount = 2;
		var fabric = fabric(neo, 81);
		try (Transaction root = Transaction.openOuter()) { eq(81, fabric.extract(VALUE, 161, root)); root.commit(); }
		eq(1, neo.amount); eq(1, neo.realCalls); closed();
	}

	/** Both directions between two endpoints: every unit accounted for, in both orders. */
	public static void conservation() {
		FabricTank source = new FabricTank(10000); source.amount = 8191;
		NeoTank destination = new NeoTank(33);
		var target = fabric(destination, 81);
		try (Transaction root = Transaction.openOuter()) {
			long accepted;
			try (Transaction simulation = root.openNested()) { accepted = target.insert(VALUE, source.amount, simulation); }
			eq(0, destination.amount); eq(2673, accepted);
			eq(accepted, source.extract(VALUE, accepted, root));
			eq(accepted, target.insert(VALUE, accepted, root));
			root.commit();
		}
		eq(8191, source.amount + 81 * destination.amount); eq(1, destination.realCalls); closed();
		// Reverse the whole amount through the native consumer's path.
		var intoSource = neo(source, 81);
		long total = 0;
		while (destination.amount > 0) {
			long moved = intoSource.insert(-1, VALUE, destination.amount, true);
			if (moved == 0) break;
			destination.amount -= moved;
			total += moved;
		}
		eq(33, total); eq(8191, source.amount); eq(0, destination.amount); closed();
	}

	/** Live endpoints: re-resolved per operation, a swap changes what the view sees, a removal makes it empty. */
	public static void liveEndpoints() {
		NeoTank first = new NeoTank(100), replacement = new NeoTank(100);
		var current = new java.util.concurrent.atomic.AtomicReference<NeoTank>(first);
		var present = new java.util.concurrent.atomic.AtomicBoolean(true);
		var live = fabric(current::get, present::get, 1);
		try (Transaction root = Transaction.openOuter()) { live.insert(VALUE, 10, root); root.commit(); }
		current.set(replacement);
		try (Transaction root = Transaction.openOuter()) { live.insert(VALUE, 20, root); root.commit(); }
		eq(10, first.amount); eq(20, replacement.amount);
		present.set(false);
		try (Transaction root = Transaction.openOuter()) { eq(0, live.insert(VALUE, 5, root)); }
		eq(20, replacement.amount); closed();
	}

	/** A fabric store that throws during a native move leaves its own rollback to run, and the move fails out. */
	public static void fabricStoreFailureDuringANativeMoveIsRolledBack() {
		FabricTank tank = new FabricTank(100) {
			@Override public long insert(Token resource, long maximum, TransactionContext tx) {
				super.insert(resource, maximum, tx);
				throw new UnsupportedOperationException("store died mid-move");
			}
		};
		var moves = neo(tank, 1);
		try { moves.insert(-1, VALUE, 10, true); throw new AssertionError("expected a failure"); }
		catch (UnsupportedOperationException expected) { }
		eq(0, tank.amount); closed();
	}

	/** Blank resources move nothing in either direction, and a null fabric resource is refused on the native side. */
	public static void blanks() {
		NeoTank tank = new NeoTank(100); var storage = fabric(tank, 1);
		FabricTank fabric = new FabricTank(100); var moves = neo(fabric, 1);
		try (Transaction root = Transaction.openOuter()) {
			eq(0, storage.insert(EMPTY, 10, root));
			eq(0, storage.extract(EMPTY, 10, root));
			root.commit();
		}
		eq(0, tank.simulations);
		eq(0, moves.insert(-1, EMPTY, 10, true));
		eq(0, moves.insert(-1, null, 10, true));
		eq(0, fabric.amount); closed();
	}
}

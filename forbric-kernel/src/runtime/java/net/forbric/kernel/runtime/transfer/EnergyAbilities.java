package net.forbric.kernel.runtime.transfer;

import net.neoforged.neoforge.transfer.energy.EnergyHandler;

/**
 * What a bridged energy view knows about its store's direction. NeoForge's EnergyHandler has no such query; Reborn's
 * EnergyStorage (supportsInsertion/Extraction) does. A view that carries a Reborn store implements this, so the
 * other API can answer with the store's own flags instead of a guess. The flags are hints for consumers; every
 * insert and extract is still the store's to refuse.
 */
interface EnergyAbilities {
	boolean canInsert();
	boolean canExtract();

	/** The insertion hint for a NeoForge-typed view: the store's own flag, else the legacy rule (capacity > 0). */
	static boolean neoCanInsert(EnergyHandler handler) {
		return handler instanceof EnergyAbilities known ? known.canInsert() : handler.getCapacityAsLong() > 0;
	}
	static boolean neoCanExtract(EnergyHandler handler) {
		return handler instanceof EnergyAbilities known ? known.canExtract() : handler.getCapacityAsLong() > 0;
	}
	/** Reborn's supportsInsertion for a NeoForge-typed view: the store's own flag, else Reborn's default (true). */
	static boolean fabricCanInsert(EnergyHandler handler) {
		return !(handler instanceof EnergyAbilities known) || known.canInsert();
	}
	static boolean fabricCanExtract(EnergyHandler handler) {
		return !(handler instanceof EnergyAbilities known) || known.canExtract();
	}
}

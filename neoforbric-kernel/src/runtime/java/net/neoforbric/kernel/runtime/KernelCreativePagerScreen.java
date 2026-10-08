/* Copyright 2026 The NeoForbric Project. Licensed under the Apache License, Version 2.0. */
package net.neoforbric.kernel.runtime;

import java.util.List;

import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.neoforge.client.gui.CreativeTabsScreenPage;

/**
 * What {@link KernelCreativePager} needs from the merged {@code CreativeModeInventoryScreen}: NeoForge's pager state,
 * the screen's selected tab, and its two private methods.
 *
 * <p>CreativePagerBridgeInjector adds this interface to the screen with one-instruction trampolines, so every decision
 * of the Fabric pager API is ordinary Java here instead of branching bytecode in the screen. The {@code neoforbric$}
 * prefix keeps the trampolines out of any mod's way.
 */
public interface KernelCreativePagerScreen {
	/** NeoForge's pages, as its {@code init} built them. */
	List<CreativeTabsScreenPage> neoforbric$pages();

	/** The page NeoForge draws. */
	CreativeTabsScreenPage neoforbric$currentPage();

	/** NeoForge's own {@code setCurrentPage}, so anything hooked on it still runs. */
	void neoforbric$setCurrentPage(CreativeTabsScreenPage page);

	/** The screen's static selected tab. */
	CreativeModeTab neoforbric$selectedTab();

	/** The screen's private {@code selectTab}. */
	void neoforbric$selectTab(CreativeModeTab tab);

	/** The screen's private {@code updateSelection} — Fabric's name, where owo's hook sits. */
	void neoforbric$updateSelection();
}

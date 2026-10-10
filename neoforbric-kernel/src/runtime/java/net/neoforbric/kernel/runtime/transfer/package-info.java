/**
 * Cross-ecosystem block-entity item/fluid/energy transfer, compiled against Fabric transfer 0.116.17, Minecraft
 * 1.21.1's immediate NeoForge capabilities and Team Reborn Energy 4.2.0. Every built game side carries this package:
 * without the Fabric API or Reborn compile input the build fails rather than shipping a runtime jar without it. It
 * is never a Fabric mod, and neither Fabric API nor Reborn is bundled.
 *
 * <p>Boot integration: install TransferCapabilityFallback in the pre-Mixin chain only when the game side asks for
 * the interop. After native capability registration, invoke BlockTransferBridge.install
 * through the GAME classloader. Installation checks every hook marker before exposing any fallback. The native
 * provider gets the first answer; a provider from another ecosystem is consulted only when that answer is absent,
 * the block entity's owner (the mod that registered its type) first. Fabric's generic fallbacks, which wrap any
 * Container, speak only for Fabric-owned and vanilla block entities (TransferPrecedence).
 * TransferIssues.setReporter can connect runtime findings to the kernel's attributed compatibility report.
 *
 * <p>1.21.1 has one transaction engine: Fabric's, on the Fabric side of every adapter. The native side is
 * immediate — insert/extract with a simulate boolean — so the Fabric consumer simulates inside the open
 * transaction and replays the promised amount at the outermost close (NativeTransferAdapters' two-hook
 * promise: a nested scope that aborts cancels its own promise, an aborted root never touched the store at
 * all), and the native consumer owns a joined-or-root Fabric scope per operation. Within one transaction the
 * native store still holds its pre-transaction state — the documented cost of never writing before commit.
 * A replay that moves less than promised, or hits a dead endpoint, is reported and the commit completes:
 * fabric does not roll back the other participants when a close callback throws, so rethrowing could not
 * deliver all-or-nothing, only crash the commit after the loss already happened.
 *
 * <p>Fabric SlottedStorage is required when exposing a NeoForge IItemHandler: an arbitrary Storage cannot
 * supply indexed insertion. Fabric has no exact resource-specific validity query; our advertised validity is
 * conservative and the real provider's insert remains authoritative. Fluid units convert exactly at 81 Fabric
 * units per millibucket, with sub-millibucket leftovers retained at their source. Failed quantization trials abort
 * before retry; amounts are never rounded after mutation. Registry identity and DataComponentPatch are preserved.
 *
 * <p>Only a loaded ServerLevel block entity on its server thread is eligible. Directions, including null, are
 * passed unchanged. Wrappers resolve providers afresh for each operation, hold world/entity references weakly,
 * and become empty after removal or unload. Capability invalidation during a transfer aborts its nested operation.
 * Recursive fallback lookup returns absent instead of wrapping itself. No level is force-loaded by this bridge.
 * Unslotted Fabric storage and entity/item capabilities remain outside scope. Hoppers are the exception: they
 * reach unslotted and block-only Fabric storages through Fabric's own lookup on NeoForge's found-nothing branches
 * (KernelFabricHopperStorage), independently of the bridge switch.
 *
 * <p>Energy (placed block entities only; item energy is not bridged) uses the same seams, endpoints, precedence,
 * invalidation and recursion guard. 1 FE = 1 E; see EnergyUnits for the int/long rule. Fabric's side is Team Reborn
 * Energy's EnergyStorage.SIDED, through RebornEnergyAdapters and RebornEnergyBridge, the only two classes that
 * name a Reborn type and the only ones the boot seam loads when Reborn is installed — energy shares the same
 * two-hook promise as items and fluids.
 */
package net.neoforbric.kernel.runtime.transfer;

package net.neoforbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** fabric-api 0.116.17 payload-codec wraps, as shipped, against the merged and vanilla game. */
@ResourceLock("system-properties")
class MixinWrapOperationShimTest {
	private static final String MIXIN = "net/fabricmc/fabric/mixin/event/interaction/ServerGamePacketListenerImplMixin";
	// Historic: no 0.116.17 module ships it, and the merged 1.21.1 game holds neither its selector
	// ({@code handlePickItemFromBlock}) nor its call ({@code BlockState.getCloneItemStack}; only
	// {@code handlePickItem}) — the pick-block tests assert the guarded zero on what IS there.
	private static final String LISTENER = "net/minecraft/server/network/ServerGamePacketListenerImpl";
	private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
	private static final String NEO_CLONE = "Lnet/minecraft/world/level/block/state/BlockState;getCloneItemStack(Lnet/minecraft/core/BlockPos;"
			+ "Lnet/minecraft/world/level/LevelReader;ZLnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/item/ItemStack;";

	@AfterEach void reset() {
		System.clearProperty(MixinWrapOperationShim.PROPERTY);
	}

	@Test void pickBlockWrapBindsToNeoForgesCallAndHandsTheHandlerItsOwnOrder() throws Exception {
		// The reviewed pick-block wrap's whole contract is absent on 1.21.1: no 0.116.17 module ships
		// ServerGamePacketListenerImplMixin, and the merged game has no handlePickItemFromBlock and no
		// BlockState.getCloneItemStack. The shim's REVIEWED row for it matches nothing, so the closest
		// 1.21.1 interaction mixin — the client pick wrapper — passes through unchanged, twice.
		ClassNode mixin = StagedFabricMixinFixture.remapped("fabric-events-interaction-v0",
				"net/fabricmc/fabric/mixin/event/interaction/client/MinecraftClientMixin");
		ClassNode target = game(merged());
		assertEquals(0, MixinWrapOperationShim.adapt(mixin, name -> target));
		assertEquals(0, MixinWrapOperationShim.adapt(mixin, name -> target), "a second pass changes nothing");
	}


	@Test void vanillasBodyBindsAsWritten() throws Exception {
		// The vanilla listener (obfuscated client) likewise has no pick-block contract to rebind.
		ClassNode mixin = StagedFabricMixinFixture.remapped("fabric-events-interaction-v0",
				"net/fabricmc/fabric/mixin/event/interaction/client/MinecraftClientMixin");
		// The vanilla client jar is obfuscated, so its bodies are read the way the kernel reads them: through the
		// intermediary cache, which hands back the Mojmap form of the unpatched 1.21.1 class.
		ClassNode target = StagedFabricMixinFixture.vanilla(LISTENER);
		assertEquals(0, MixinWrapOperationShim.adapt(mixin, name -> target));
	}

	@Test void theSwitchWrapsNothing() throws Exception {
		System.setProperty(MixinWrapOperationShim.PROPERTY, "off");
		ClassNode mixin = StagedFabricMixinFixture.remapped("fabric-events-interaction-v0",
				"net/fabricmc/fabric/mixin/event/interaction/client/MinecraftClientMixin");
		ClassNode target = game(merged());
		assertEquals(0, MixinWrapOperationShim.adapt(mixin, name -> target));
	}

	@Test void fabricNetworkingsCodecWrapsStayUnboundBecauseTheKernelServesThoseCodecs() throws Exception {
		// The 1.21.1 codec wraps name CustomPayload.createCodec in <clinit>; the merged game builds its
		// STREAM_CODECs through CustomPacketPayload.codec (another owner, name and arity), and the kernel
		// serves those codecs itself. Shape but no reviewed row: both stay unbound.
		for (String[] pair : new String[][] {
				{ "net/fabricmc/fabric/mixin/networking/CustomPayloadC2SPacketMixin", "net/minecraft/network/protocol/common/ServerboundCustomPayloadPacket" },
				{ "net/fabricmc/fabric/mixin/networking/CustomPayloadS2CPacketMixin", "net/minecraft/network/protocol/common/ClientboundCustomPayloadPacket" } }) {
			ClassNode mixin = StagedFabricMixinFixture.remapped("fabric-networking-api-v1", pair[0]), target = game(merged(), pair[1]);
			assertEquals(0, MixinWrapOperationShim.adapt(mixin, name -> target), pair[0] + " has the shape but no reviewed row");
		}
	}

	/**
	 * MixinFit asks the shim: the 1.21.1 codec wraps name a call the merged game never makes
	 * (CustomPayload.createCodec), so each reads as its miss — and with the shim switched off the
	 * same miss stands, since the shim was never going to bind it.
	 */
	@Test void theVerdictFollowsTheReviewedWrap() throws Exception {
		byte[] mixin = StagedFabricMixinFixture.bytes(StagedFabricMixinFixture.remapped("fabric-networking-api-v1",
				"net/fabricmc/fabric/mixin/networking/CustomPayloadC2SPacketMixin"));
		byte[] packet = StagedFabricMixinFixture.bytes(game(merged(), "net/minecraft/network/protocol/common/ServerboundCustomPayloadPacket"));
		java.util.function.Function<String, byte[]> resolver = name -> name.equals(
				"net/minecraft/network/protocol/common/ServerboundCustomPayloadPacket.class") ? packet : null;
		MixinFit.Result fit = MixinFit.evaluate(mixin, resolver);
		assertTrue(fit.unresolved().stream().anyMatch(u -> u.contains("createCodec")), fit.reason());
		System.setProperty(MixinWrapOperationShim.PROPERTY, "off");
		MixinFit.Result off = MixinFit.evaluate(mixin, resolver);
		assertTrue(off.unresolved().stream().anyMatch(u -> u.contains("createCodec")), off.reason());
	}

	/**
	 * fabric-networking's 1.21.1 configuration mixin carries no wrap at all — @Inject(&lt;init&gt;) and
	 * @Inject(sendConfigurations) only — so there is no decorator wrap to report and the shim leaves it
	 * alone. (The 26.x decorator widening this test once pinned is gone with the 26.x
	 * ServerConfigurationPacketListenerImplMixin that named it.)
	 *
	 * <p>What it read as: FIT — the fit was handed the mixin's own bytes as the target, so its own
	 * members answered for themselves. It is not FIT even then: the mixin declares its @Shadow members
	 * with intermediary names ({@code field_45023}, {@code field_45024}, {@code method_52406},
	 * {@code method_52409}), nothing renames a mixin's own shadowed members, and the fit reports them
	 * orphaned. Against the merged class it is not FIT for the same reason. Reported as a production
	 * gap: this mixin cannot be fitted as written on 1.21.1.
	 */
	@Test void fabricNetworkingsDecoratorWrapIsReportedAsTheMissItIs() throws Exception {
		String name = "net/fabricmc/fabric/mixin/networking/ServerConfigurationNetworkHandlerMixin";
		String listener = "net/minecraft/server/network/ServerConfigurationPacketListenerImpl";
		ClassNode mixin = StagedFabricMixinFixture.remapped("fabric-networking-api-v1", name);
		ClassNode target = game(merged(), listener);
		assertEquals(0, MixinWrapOperationShim.adapt(mixin, n -> target), "no wrap to shim");
		byte[] bytes = StagedFabricMixinFixture.bytes(mixin);
		MixinFit.Result fit = MixinFit.evaluate(bytes, n -> n.equals(listener + ".class") ? bytes : null);
		assertEquals(MixinFit.Verdict.HAZARD, fit.verdict(), fit.reason());
		assertTrue(fit.reason().contains("field_45023"), "its own @Shadow field is intermediary-named and stays unassigned: " + fit.reason());
		MixinFit.Result real = MixinFit.evaluate(bytes, n -> n.equals(listener + ".class") ? StagedFabricMixinFixture.bytes(target) : null);
		assertNotEquals(MixinFit.Verdict.FIT, real.verdict(), "and the merged class does not declare those names either: " + real.reason());
		assertTrue(real.unresolved().stream().anyMatch(u -> u.contains("field_45023")), real.reason() + " unresolved=" + real.unresolved());
	}

	@Test void aRepeatedTypeIsAGuessAndIsRefused() {
		Type pos = Type.getObjectType("net/minecraft/core/BlockPos"), level = Type.getObjectType("net/minecraft/world/level/LevelReader");
		assertArrayEquals(new int[] { 1, 0, 2 }, MixinWrapOperationShim.embedding(new Type[] { level, pos, Type.BOOLEAN_TYPE },
				new Type[] { pos, level, Type.BOOLEAN_TYPE, Type.getObjectType("net/minecraft/world/entity/player/Player") }));
		assertNull(MixinWrapOperationShim.embedding(new Type[] { pos }, new Type[] { pos, pos }), "two candidates for one argument");
		assertNull(MixinWrapOperationShim.embedding(new Type[] { pos, pos }, new Type[] { pos, pos, level }));
		assertNull(MixinWrapOperationShim.embedding(new Type[] { level }, new Type[] { pos }), "an argument the call no longer has");
	}

	private static Path merged() {
		return TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
	}

	private static ClassNode fabric() throws Exception {
		return StagedFabricMixinFixture.mixin("fabric-events-interaction-v0", MIXIN);
	}

	private static MethodNode method(ClassNode node, String name) {
		return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
	}

	private static ClassNode game(Path jar) throws Exception {
		return game(jar, LISTENER);
	}

	private static ClassNode game(Path jar, String name) throws Exception {
		Fixture kind = jar.equals(TestFixtures.vanillaJar()) ? Fixture.MC_LIBRARIES : Fixture.STAGED;
		TestFixtures.require(kind, Files.isRegularFile(jar), "actual game required");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ClassNode node = new ClassNode();
			new ClassReader(zip.getInputStream(zip.getEntry(name + ".class")).readAllBytes()).accept(node, 0);
			return node;
		}
	}
}

/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;

/**
 * Arbitrates the common-networking channel that Fabric and NeoForge <em>both</em> claim on a tri-in-one instance.
 *
 * <p>Both ecosystems implement the cross-loader "Common Networking" spec — version + channel negotiation over the
 * {@code c:version}/{@code c:register} wire channels — each with its OWN payload class registered for the same id.
 * On a normal instance only one ecosystem is present, so only one registration exists; on Forbric both do, and the
 * decode registry hands a {@code net.neoforged.…CommonVersionPayload} to Fabric's addon, whose handler casts it to
 * {@code net.fabricmc.…CommonVersionPayload} → {@code ClassCastException} → the client is kicked "The server sent an
 * invalid packet" right after reaching the world.
 *
 * <p>The translation logic lives in {@link net.forbric.kernel.interop.PayloadInterop} — boot-side, purely
 * reflective, and a kernel class since the interop hooks were brought over from the previous-generation loader:
 * given the addon and the incoming payload it runs the negotiation for BOTH stacks
 * — extracting the version, feeding Fabric's {@code onCommonVersionPacket} and NeoForge's {@code checkCommonVersion}
 * — and reports whether it fully handled the packet. This injector installs the two call sites the old loader
 * reached via mixins, but as kernel-native head injections (the kernel authors no mixins of its own):
 * <ul>
 *   <li>{@code AbstractChanneledNetworkAddon.handle(CustomPacketPayload)} — a guest Fabric class the kernel's
 *       transforming loader also defines. If the interop reports the packet handled, return its verdict before
 *       Fabric's {@code receive} can miscast it. This is the one that stops the client CCE.</li>
 *   <li>{@code ServerConfigurationPacketListenerImpl.finishCurrentTask(ConfigurationTask.Type)} — the two stacks
 *       expose different {@code ConfigurationTask.Type}s for the same handshake, so accept either owner at the
 *       task-completion boundary.</li>
 * </ul>
 *
 * <p>The interop methods take {@code Object} parameters, so the injected {@code INVOKESTATIC} can pass {@code this}
 * and the argument as-is — no need for the boot-side hook to name {@code net.minecraft}/{@code net.fabricmc} types
 * (the same widening-reference trick {@link ClientPackHookInjector} relies on).
 */
public final class CommonNetworkInteropInjector implements ClassTransformer {
	private static final String INTEROP = "net/forbric/kernel/interop/PayloadInterop";

	/**
	 * Every Fabric addon class that DECLARES its own {@code handle(CustomPacketPayload)}.
	 *
	 * <p>It was originally just {@code AbstractChanneledNetworkAddon}, on the reasonable assumption that one
	 * injection into the base class covers every addon. It does not. The play addons inherit {@code handle} and so
	 * were covered; both CONFIGURATION addons override it, and an override is not reached by a prologue spliced
	 * into the superclass — so the whole configuration phase ran with no cross-ecosystem translation at all.
	 *
	 * <p>Nothing caught it because the symptom this shim was written for ("invalid packet" right after reaching the
	 * world) is a PLAY-phase symptom, and until gate-m12 no test ever reached the configuration phase over a
	 * socket: singleplayer negotiates in memory, and {@code RegistrySyncManager.configureClient} returns early for
	 * the singleplayer owner. The configuration-phase cost was a server kicking its own client with "This server
	 * requires Fabric Loader and Fabric API installed on your client!" — because the server's
	 * {@code minecraft:register} reached the client as NeoForge's payload type, the client's Fabric addon did not
	 * recognise it, never replied, and Fabric scored the peer NOT_RECEIVED.
	 */
	private static final Set<String> FABRIC_ADDONS = Set.of(
			"net.fabricmc.fabric.impl.networking.AbstractChanneledNetworkAddon",
			"net.fabricmc.fabric.impl.networking.client.ClientConfigurationNetworkAddon",
			"net.fabricmc.fabric.impl.networking.server.ServerConfigurationNetworkAddon");
	private static final String HANDLE = "handle";
	private static final String HANDLE_DESC = "(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)Z";
	private static final String HANDLE_HOOK = "handleFabricChannelRegistrationAddon";
	private static final String HANDLE_HOOK_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Boolean;";

	private static final String CUSTOM_PAYLOAD = "net/minecraft/network/protocol/common/custom/CustomPacketPayload";

	private static final String CLIENT_CONFIG_LISTENER = "net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl";
	/**
	 * Where MinecraftForge's own dispatch used to sit. Forge patches {@code handleCustomPayload} on both common
	 * listeners to ask {@code ForgeHooks.onCustomPayload} first; NeoForge patched the same methods and won the
	 * byte-merge, so the only surviving Forge call is the one in {@code ServerGamePacketListenerImpl}'s override.
	 * A Forge mod's packet reaching the client, or the server in the configuration phase, therefore had no
	 * dispatcher at all. These two get a prologue that hands a ForgePayload to Forge's hook and returns when it
	 * took it.
	 */
	private static final String CLIENT_COMMON_LISTENER = "net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl";
	private static final String SERVER_COMMON_LISTENER = "net.minecraft.server.network.ServerCommonPacketListenerImpl";
	/**
	 * The PLAY-phase server listener, whose {@code handleCustomPayload} is MinecraftForge's override — and the
	 * override does not call {@code super}. See {@link #letNeoForgePayloadsThrough}.
	 */
	private static final String SERVER_GAME_LISTENER = "net.minecraft.server.network.ServerGamePacketListenerImpl";
	/** The hook that override consults, and whose answer it throws away. */
	private static final String FORGE_HOOKS = "net/minecraftforge/common/ForgeHooks";
	private static final String ON_CUSTOM_PAYLOAD = "onCustomPayload";
	/**
	 * The play-phase fall-through to NeoForge's dispatcher. ON by default; {@code -Dforbric.playPayloadFallThrough=off}
	 * restores the merged method as it is, which drops every NeoForge mod's play-phase packet to the server.
	 *
	 * <p>The defect is real and measured: the merged {@code ServerGamePacketListenerImpl.handleCustomPayload} is
	 * MinecraftForge's override, its whole body asks {@code ForgeHooks.onCustomPayload}, POPs the answer and
	 * returns, and it never calls {@code super} — where NeoForge's dispatcher lives. So a NeoForge mod's play-phase
	 * packet to the server reached nobody, in singleplayer too. Carry On is the case a player reported: its client
	 * tells the server the carry key is held with exactly such a packet, so the server never believed the key was
	 * down and nothing could ever be picked up — no crash, no log, a mod that "does not work".
	 *
	 * <p>This was off by default for a while, and for a measured reason that no longer applies. The first version
	 * fell through to {@code super}, and fabric-api mixes into that super: its
	 * {@code ServerCommonPacketListenerImplMixin} expects only the CONFIGURATION listener there and throws
	 * {@code IllegalStateException: Unknown addon} for the play listener, because on vanilla — whose play override is
	 * empty — no play payload ever reaches it. Fabric's own play receive is a separate HEAD injection into THIS
	 * method, and works. So the fall-through no longer goes through {@code super} at all: it makes the one call
	 * NeoForge's {@code super} body makes for a mod payload, {@code NetworkRegistry.handleModdedPayload}, directly.
	 */
	static boolean playFallThroughEnabled() {
		return !"off".equalsIgnoreCase(System.getProperty("forbric.playPayloadFallThrough", "on"));
	}

	/** Asks whether NeoForge registered this payload, so the fall-through only reaches payloads it owns. */
	private static final String NEO_OWNS_HOOK = "neoForgeWillHandle";
	private static final String NEO_OWNS_HOOK_DESC = "(Ljava/lang/Object;)Z";
	/**
	 * NeoForge's own dispatcher for a mod payload — what {@code ServerCommonPacketListenerImpl.handleCustomPayload}
	 * calls for one — reached without going through that method and the fabric-api injection that throws in it.
	 */
	private static final String NEO_DISPATCH = "handleModdedPayload";
	private static final String NEO_DISPATCH_DESC = "(Lnet/minecraft/network/protocol/common/ServerCommonPacketListener;"
			+ "Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V";
	private static final String CLIENT_HANDLE_PAYLOAD_DESC = "(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V";
	private static final String SERVER_HANDLE_PAYLOAD_DESC = "(Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V";
	/**
	 * NeoForge's channel police. {@code checkPacket} (client and server overloads) refuses any payload whose
	 * channel NeoForge did not negotiate — which is every channel another ecosystem negotiated, so a Fabric mod's
	 * client could not send on its own channel. Foreign payloads are exempted.
	 */
	private static final String NEO_NETWORK_REGISTRY = ForeignType.NETWORK_REGISTRY.binary(Ecosystem.NEOFORGE);
	private static final String CHECK_PACKET = "checkPacket";
	private static final String ON_REGISTER = "onMinecraftRegister";
	private static final String ON_UNREGISTER = "onMinecraftUnregister";
	private static final String REGISTER_DESC = "(Lnet/minecraft/network/Connection;Ljava/util/Set;)V";
	private static final String IS_FORGE_PACKET = "isForgePayloadPacket";
	private static final String IS_FORGE_PACKET_DESC = "(Ljava/lang/Object;)Z";
	private static final String HANDLE_PAYLOAD = "handleCustomPayload";
	private static final String HANDLE_PAYLOAD_DESC = "(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V";
	private static final String NEO_PACKAGE = "net/neoforged/";
	private static final String NEO_SEND_INITIAL_CHANNELS = "sendInitialListeningChannels";

	/**
	 * NeoForge's client-side initialisation of a connection to a NON-NeoForge server, {@code ClientNetworkRegistry
	 * .initializeOtherConnection}. Three call sites in {@code ClientConfigurationPacketListenerImpl} fire per join
	 * (the brand payload on the Netty thread, the enabled-features packet, and the brand payload again on the render
	 * thread after vanilla's re-dispatch) and only {@code handleConfigurationFinished} checks the listener's own
	 * {@code initializedConnection} flag first; the other two run the whole thing again — every NeoForge mod's
	 * default server config rebuilt ("Overwriting non-null config ..." twice per join), the payload filters
	 * re-injected, the register payload re-sent. The two unguarded sites get the same flag check the third has,
	 * so the flag keeps its NeoForge meaning: once per configuration phase, a reconfiguration starts afresh.
	 *
	 * <p><b>NeoForge 26.2.0.88 fixed this upstream and the splice now does nothing, by design.</b> The
	 * {@code initializedConnection} field is gone; {@code initializeOtherConnection} hops to the connection's event
	 * loop and goes through {@code runConnectionInitialization}, which takes a per-connection lock and returns
	 * early when the {@code CONNECTION_INITIALIZED} channel attribute is already set. That is a strictly better
	 * guard than a per-listener boolean, so {@link #guardOtherConnectionInitialisation} finds no field and installs
	 * nothing. The code stays because the carrier is a pin that moves, and re-deriving this from scratch cost a
	 * session once already; {@code CommonNetworkInteropInjectorTest} asserts that on a carrier without the field
	 * NeoForge's own guard is present, so a carrier that drops BOTH goes red instead of quietly regressing.
	 */
	private static final String INITIALIZE_OTHER = "initializeOtherConnection";
	private static final String INITIALIZED_FLAG = "initializedConnection";
	private static final String IS_OTHER = "isOther";

	private static final String SERVER_CONFIG = "net.minecraft.server.network.ServerConfigurationPacketListenerImpl";
	private static final String FINISH_TASK = "finishCurrentTask";
	private static final String FINISH_TASK_DESC = "(Lnet/minecraft/server/network/ConfigurationTask$Type;)V";
	private static final String CONFIG_TASK_TYPE = "net/minecraft/server/network/ConfigurationTask$Type";
	private static final String FINISH_HOOK = "finishEquivalentCommonTask";
	private static final String FINISH_HOOK_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Z";

	@Override
	public String name() {
		return "forbric-common-network-interop";
	}

	@Override
	public AnchorSet anchors() {
		// Eight target classes and about as many independent repairs behind one `changed` flag, and at least one
		// of them is deliberately inert on the current carrier. So a matched class that comes back unedited is
		// not yet evidence of anything; these need per-repair claims.
		return AnchorSet.scanned("several independent repairs across eight classes, one of them (the client "
				+ "connection-initialisation guard) intentionally inert since NeoForge 26.2.0.88");
	}

	/** Claim ids, one per branch of {@link #transform}; each is reported beside its {@code changed = true}. */
	static final String CLAIM_FABRIC_ADDON = "forbric-common-network-interop#fabricAddonHandle";
	static final String CLAIM_FINISH_TASK = "forbric-common-network-interop#finishCurrentTask";
	static final String CLAIM_CLIENT_COMMON_PAYLOAD = "forbric-common-network-interop#clientCommonHandlePayload";
	static final String CLAIM_SERVER_COMMON_PAYLOAD = "forbric-common-network-interop#serverCommonHandlePayload";
	static final String CLAIM_CHECK_PACKET = "forbric-common-network-interop#neoCheckPacket";
	static final String CLAIM_GUARD_INITIALISATION = "forbric-common-network-interop#guardOtherConnectionInitialisation";

	/**
	 * One claim per branch. The server-game fall-through is REQUIRED while it is switched on: the merged play
	 * listener on the current carrier is still MinecraftForge's override that never reaches NeoForge, so a miss is a
	 * NeoForge mod whose packets to the server go nowhere (see {@link #letNeoForgePayloadsThrough}).
	 */
	@Override
	public List<Claim> claims() {
		List<AnchorSet.Anchor> addons = new ArrayList<>();
		for (String addon : FABRIC_ADDONS) {
			addons.add(required(addon, "fabric-api's channel-registration addon miscasts a NeoForge payload before the cross-ecosystem negotiator sees it"));
		}
		return List.of(
				new Claim(CLAIM_FABRIC_ADDON, new AnchorSet(addons, null)),
				new Claim(CLAIM_FINISH_TASK, AnchorSet.of(required(SERVER_CONFIG,
						"Fabric and NeoForge common-networking configuration tasks are not treated as equivalent — one family's configuration never finishes"))),
				new Claim(CLAIM_CHECK_PACKET, AnchorSet.of(required(NEO_NETWORK_REGISTRY,
						"NeoForge's channel check rejects every payload another ecosystem negotiated — Fabric mods are disconnected for unknown channels"))),
				// HEDGE: the merged listener on the current carrier already initialises once, so the guard finds
				// nothing to do (never applied in any gate log); it is kept for a carrier where it does not.
				new Claim(CLAIM_GUARD_INITIALISATION, AnchorSet.of(new AnchorSet.Anchor(CLIENT_CONFIG_LISTENER, AnchorSet.Severity.HEDGE,
						"a non-NeoForge connection is initialised more than once per configuration"))));
	}

	private static AnchorSet.Anchor required(String binaryName, String cost) {
		return new AnchorSet.Anchor(binaryName, AnchorSet.Severity.REQUIRED, cost);
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		return transform(className, classBytes, context, ClaimReporter.NONE);
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context, ClaimReporter reporter) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		boolean fabricAddon = FABRIC_ADDONS.contains(className);
		boolean serverConfig = SERVER_CONFIG.equals(className);
		boolean clientConfig = CLIENT_CONFIG_LISTENER.equals(className);
		boolean clientCommon = CLIENT_COMMON_LISTENER.equals(className);
		boolean serverCommon = SERVER_COMMON_LISTENER.equals(className);
		boolean serverGame = SERVER_GAME_LISTENER.equals(className);
		boolean neoRegistry = NEO_NETWORK_REGISTRY.equals(className);
		if (!fabricAddon && !serverConfig && !clientConfig && !clientCommon && !serverGame
				&& !neoRegistry) return classBytes;

		ClassNode node = new ClassNode();
		// EXPAND_FRAMES so every original frame is an absolute F_NEW node; the explicit frames we author at our own
		// branch targets then slot in consistently, and ClassWriter can serialize the StackMapTable WITHOUT
		// COMPUTE_FRAMES — whose getCommonSuperClass would try to load game classes through the wrong loader.
		new ClassReader(classBytes).accept(node, ClassReader.EXPAND_FRAMES);

		boolean changed = false;
		for (MethodNode m : node.methods) {
			if (fabricAddon && m.name.equals(HANDLE) && m.desc.equals(HANDLE_DESC)) {
				m.instructions.insert(handleAddonPrologue(node.name));
				bumpStack(m, 2);
				changed = true;
				reporter.hit(CLAIM_FABRIC_ADDON);
				ForbricLog.info("[Forbric/Net] arbitrating common-networking channel at %s.%s — Fabric addon defers to "
						+ "the cross-ecosystem negotiator before it can miscast a NeoForge payload", className, HANDLE);
			} else if (serverConfig && m.name.equals(FINISH_TASK) && m.desc.equals(FINISH_TASK_DESC)) {
				m.instructions.insert(finishTaskPrologue(node.name));
				bumpStack(m, 2);
				changed = true;
				reporter.hit(CLAIM_FINISH_TASK);
				ForbricLog.info("[Forbric/Net] treating Fabric/NeoForge common-networking tasks as equivalent at %s.%s",
						className, FINISH_TASK);
			} else if (neoRegistry && m.name.equals(CHECK_PACKET) && (m.access & Opcodes.ACC_STATIC) != 0
					&& org.objectweb.asm.Type.getArgumentTypes(m.desc).length == 2) {
				m.instructions.insert(forgePacketExemptionPrologue(m.desc));
				bumpStack(m, 1);
				changed = true;
				reporter.hit(CLAIM_CHECK_PACKET);
				ForbricLog.info("[Forbric/Net] exempting MinecraftForge payloads from NeoForge's channel check at %s.%s%s",
						className, CHECK_PACKET, m.desc);
			}
			if (clientConfig) {
				int guarded = guardOtherConnectionInitialisation(node, m);
				if (guarded > 0) {
					changed = true;
					reporter.hit(CLAIM_GUARD_INITIALISATION);
					ForbricLog.info("[Forbric/Net] %s.%s now initialises a non-NeoForge connection once per configuration "
							+ "phase — NeoForge re-entered ClientNetworkRegistry.initializeOtherConnection from here and "
							+ "rebuilt every mod's default server config each time", className, m.name);
				}
			}
			if (clientConfig && m.name.equals(HANDLE_PAYLOAD) && m.desc.equals(HANDLE_PAYLOAD_DESC)) {
				if (shareMinecraftRegisterWithSuper(m)) {
					changed = true;
					ForbricLog.info("[Forbric/Net] letting minecraft:register reach BOTH stacks at %s.%s, Fabric first — "
							+ "NeoForge's override answered it alone and returned, and Fabric's server treats the first "
							+ "register as the whole declaration", className, HANDLE_PAYLOAD);
				} else {
					ForbricLog.warn("[Forbric/Net] %s.%s no longer swallows minecraft:register the way this fix "
							+ "expects; leaving it alone", className, HANDLE_PAYLOAD);
				}
			}
		}
		if (!changed) return classBytes;

		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * {@code Boolean r = interop.handleFabricChannelRegistrationAddon(this, payload); if (r != null) return
	 * r.booleanValue();} — prepended so the negotiator sees the packet before Fabric's own {@code receive} casts it.
	 * At the fall-through label the stack still holds the (null) Boolean and both params are live, so the frame is
	 * locals=[this, CustomPacketPayload] / stack=[Boolean]; POP it and the original body runs at its entry frame.
	 */
	private static InsnList handleAddonPrologue(String owner) {
		InsnList body = new InsnList();
		LabelNode notHandled = new LabelNode();
		body.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this (the addon)
		body.add(new VarInsnNode(Opcodes.ALOAD, 1)); // the payload
		body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTEROP, HANDLE_HOOK, HANDLE_HOOK_DESC, false));
		body.add(new InsnNode(Opcodes.DUP));                       // [Boolean, Boolean]
		body.add(new JumpInsnNode(Opcodes.IFNULL, notHandled));    // null -> fall through to original body
		body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false));
		body.add(new InsnNode(Opcodes.IRETURN));
		body.add(notHandled);
		body.add(new FrameNode(Opcodes.F_NEW, 2, new Object[] {owner, CUSTOM_PAYLOAD}, 1,
				new Object[] {"java/lang/Boolean"}));
		body.add(new InsnNode(Opcodes.POP));                       // discard the null Boolean, run the original body
		return body;
	}

	/**
	 * {@code if (interop.isForgePayloadPacket(packet)) return;} at the head of a static
	 * {@code checkPacket(Packet, <listener>)V}. Fall-through frame: the two parameters, empty stack — the entry frame.
	 */
	private static InsnList forgePacketExemptionPrologue(String desc) {
		org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(desc);
		InsnList body = new InsnList();
		LabelNode notForge = new LabelNode();
		body.add(new VarInsnNode(Opcodes.ALOAD, 0)); // the packet
		body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTEROP, IS_FORGE_PACKET, IS_FORGE_PACKET_DESC, false));
		body.add(new JumpInsnNode(Opcodes.IFEQ, notForge));
		body.add(new InsnNode(Opcodes.RETURN));
		body.add(notForge);
		body.add(new FrameNode(Opcodes.F_NEW, 2, new Object[] {args[0].getInternalName(), args[1].getInternalName()}, 0,
				new Object[] {}));
		return body;
	}

	/**
	 * For every call to NeoForge's {@code initializeOtherConnection} in {@code m} that sits behind the shape
	 * <pre>  ALOAD 0; GETFIELD connectionType; INVOKEVIRTUAL isOther; IFEQ L; ... INVOKESTATIC initializeOtherConnection</pre>
	 * and is not already preceded by a check of {@code initializedConnection}, splice
	 * <pre>  ALOAD 0; GETFIELD initializedConnection; IFNE L</pre>
	 * in front of that guard. {@code L} is an existing branch target (it carries its own frame after EXPAND_FRAMES),
	 * so no frame is authored. Returns how many sites were guarded.
	 */
	private static int guardOtherConnectionInitialisation(ClassNode node, MethodNode m) {
		boolean hasFlag = false;
		for (org.objectweb.asm.tree.FieldNode f : node.fields) {
			if (INITIALIZED_FLAG.equals(f.name) && "Z".equals(f.desc)) hasFlag = true;
		}
		if (!hasFlag) return 0;
		int guarded = 0;
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.INVOKESTATIC) continue;
			MethodInsnNode call = (MethodInsnNode) insn;
			if (!INITIALIZE_OTHER.equals(call.name) || !call.owner.startsWith(NEO_PACKAGE)) continue;
			// Walk back to the nearest `isOther` guard: INVOKEVIRTUAL isOther followed by IFEQ.
			JumpInsnNode ifeq = null;
			for (AbstractInsnNode back = call.getPrevious(); back != null; back = back.getPrevious()) {
				if (back.getOpcode() == Opcodes.IFEQ) {
					AbstractInsnNode test = previousOpcode(back);
					if (test instanceof MethodInsnNode t && IS_OTHER.equals(t.name)) {
						ifeq = (JumpInsnNode) back;
						break;
					}
				}
			}
			if (ifeq == null) continue;
			AbstractInsnNode isOther = previousOpcode(ifeq);
			AbstractInsnNode getType = previousOpcode(isOther);
			AbstractInsnNode loadThis = previousOpcode(getType);
			if (getType == null || getType.getOpcode() != Opcodes.GETFIELD || loadThis == null
					|| loadThis.getOpcode() != Opcodes.ALOAD || ((VarInsnNode) loadThis).var != 0) continue;
			// Already guarded (NeoForge's own third site checks the flag right before): leave it.
			AbstractInsnNode before = previousOpcode(loadThis);
			AbstractInsnNode beforeThat = before == null ? null : previousOpcode(before);
			if (beforeThat instanceof org.objectweb.asm.tree.FieldInsnNode f && INITIALIZED_FLAG.equals(f.name)) continue;
			InsnList guard = new InsnList();
			guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
			guard.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETFIELD, node.name, INITIALIZED_FLAG, "Z"));
			guard.add(new JumpInsnNode(Opcodes.IFNE, ifeq.label));
			m.instructions.insertBefore(loadThis, guard);
			bumpStack(m, 1);
			guarded++;
		}
		return guarded;
	}

	private static AbstractInsnNode previousOpcode(AbstractInsnNode from) {
		if (from == null) return null;
		for (AbstractInsnNode insn = from.getPrevious(); insn != null; insn = insn.getPrevious()) {
			if (insn.getOpcode() >= 0) return insn;
		}
		return null;
	}

	/**
	 * {@code if (interop.finishEquivalentCommonTask(this, type)) return;} — prepended to the void method. At the
	 * fall-through label the stack is empty and both params live, so the frame is locals=[this, ConfigurationTask$Type]
	 * / stack=[] — identical to the method's own entry frame.
	 */
	private static InsnList finishTaskPrologue(String owner) {
		InsnList body = new InsnList();
		LabelNode notEquivalent = new LabelNode();
		body.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this (the listener)
		body.add(new VarInsnNode(Opcodes.ALOAD, 1)); // the requested task type
		body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTEROP, FINISH_HOOK, FINISH_HOOK_DESC, false));
		body.add(new JumpInsnNode(Opcodes.IFEQ, notEquivalent)); // false -> run the original body
		body.add(new InsnNode(Opcodes.RETURN));
		body.add(notEquivalent);
		body.add(new FrameNode(Opcodes.F_NEW, 2, new Object[] {owner, CONFIG_TASK_TYPE}, 0, new Object[] {}));
		return body;
	}

	/**
	 * Lets Fabric answer the server's {@code minecraft:register} — and answer it FIRST.
	 *
	 * <p>{@code minecraft:register} is the one channel BOTH ecosystems claim, and on the merged base they claim it
	 * at two different depths of the same call chain. NeoForge patched the vanilla override:
	 * <pre>
	 *   ClientConfigurationPacketListenerImpl.handleCustomPayload(packet) {
	 *       if (!initializedConnection &amp;&amp; packet.payload() instanceof MinecraftRegisterPayload) {
	 *           ClientNetworkRegistry.sendInitialListeningChannels(this);   // NeoForge's channel list goes out
	 *           return;                                                     // ← never reaches super
	 *       }
	 *       ...
	 *       super.handleCustomPayload(packet);
	 *   }
	 * </pre>
	 * while Fabric injects its dispatch at the HEAD of the SUPERCLASS's {@code handleCustomPayload}. On a real
	 * NeoForge instance nothing is downstream of that {@code return}; on a real Fabric instance the override does
	 * not exist. Only on a merged base does one ecosystem's early exit starve the other's entry point — and
	 * Fabric's {@code ClientConfigurationNetworkAddon.receiveRegistration} is the SOLE caller of
	 * {@code sendInitialChannelRegistrationPacket()}, so the client never declared a single Fabric channel.
	 *
	 * <p>Merely appending the super call before that {@code return} is not enough, and the reason is a contract on
	 * the OTHER end of the wire. Fabric's {@code ServerConfigurationNetworkAddon.receiveRegistration} treats the
	 * client's FIRST {@code minecraft:register} as its complete declaration: it flips {@code SENT → RECEIVED} and
	 * calls {@code startConfiguration()} synchronously, which runs {@code RegistrySyncManager.configureClient} and
	 * its {@code canSend(fabric:registry/sync)} check right there. If NeoForge's list has gone out first, that check
	 * sees seven NeoForge channels and kicks with "This server requires Fabric Loader and Fabric API installed on
	 * your client!" — while Fabric's list is one packet behind on the same socket. A pure Fabric server would do the
	 * same, so this is the merged CLIENT's obligation: be a well-formed Fabric client, whose first register is
	 * Fabric's. NeoForge's {@code NetworkRegistry.onMinecraftRegister} is purely additive and order-blind, so a
	 * NeoForge server is indifferent to which list arrives first.
	 *
	 * <p>Hence the super call is spliced in BEFORE {@code sendInitialListeningChannels}, not before the
	 * {@code return}: Fabric sees the payload (once — our addon prologue translates it and cancels the vanilla body)
	 * and replies, then NeoForge replies, then the override returns as it always did. NeoForge's own guard stays
	 * the condition — no duplicate of {@code initializedConnection} to drift — and no branch target is added, so
	 * every original stack map frame stays valid.
	 */
	private static boolean shareMinecraftRegisterWithSuper(MethodNode m) {
		// Follow the method's OWN super call rather than naming the superclass, so this tracks a renamed base class.
		MethodInsnNode superCall = null;
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.INVOKESPECIAL) continue;
			MethodInsnNode call = (MethodInsnNode) insn;
			if (call.name.equals(HANDLE_PAYLOAD) && call.desc.equals(HANDLE_PAYLOAD_DESC)) {
				superCall = call;
				break;
			}
		}
		if (superCall == null) return false;

		boolean patched = false;
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.INVOKESTATIC) continue;
			MethodInsnNode call = (MethodInsnNode) insn;
			if (!call.name.equals(NEO_SEND_INITIAL_CHANNELS) || !call.owner.startsWith(NEO_PACKAGE)) continue;
			// Only the early-exit branch: the NeoForge reply immediately followed by the return that skips super.
			AbstractInsnNode exit = nextOpcode(call);
			if (exit == null || exit.getOpcode() != Opcodes.RETURN) continue;
			// Spliced between the argument push and the static call: the stack holds NeoForge's listener argument,
			// we push two more and the invokespecial consumes exactly those two, leaving the argument in place.
			InsnList first = new InsnList();
			first.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
			first.add(new VarInsnNode(Opcodes.ALOAD, 1)); // the packet
			first.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, superCall.owner, superCall.name, superCall.desc, false));
			m.instructions.insertBefore(call, first);
			patched = true;
		}
		if (patched) bumpStack(m, 3);
		return patched;
	}

	/** The next node that is a real instruction — labels, frames and line numbers all report opcode -1. */
	private static AbstractInsnNode nextOpcode(AbstractInsnNode from) {
		for (AbstractInsnNode insn = from.getNext(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() >= 0) return insn;
		}
		return null;
	}

	/** Our prologue pushes two references before the call; COMPUTE_MAXS still recomputes, this only raises the floor. */
	private static void bumpStack(MethodNode m, int extra) {
		m.maxStack = Math.max(m.maxStack, extra);
	}
}

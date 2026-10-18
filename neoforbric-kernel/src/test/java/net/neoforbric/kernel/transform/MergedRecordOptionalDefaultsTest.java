/* Copyright 2026 The NeoForbric Project. Licensed under the Apache License, Version 2.0. */
package net.neoforbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;

/**
 * The merged base's records and the constructors that leave an {@code Optional} component null.
 *
 * <p>Re-anchored to 1.21.1. On 26.x NeoForge's {@code ServerStatus} carried a MinecraftForge-added
 * {@code Optional<ServerStatusPing> forgeData}, vanilla's five-argument constructor delegated to NeoForge's,
 * and NeoForge's left {@code forgeData} null while the merged {@code CODEC} encoded it with
 * {@code optionalFieldOf}, which cannot take a null — so a mod rebuilding the status with vanilla's constructor
 * stopped the server tick loop. {@link MergedRecordOptionalDefaults} set the missing component to
 * {@code Optional.empty()}.
 *
 * <p>On this anchor there is no {@code forgeData}: 1.21.1's {@code ServerStatus} has three {@code Optional}
 * components ({@code players}, {@code version}, {@code favicon}), NeoForge adds a plain {@code boolean isModded},
 * and the deprecated five-argument constructor delegates to the six-argument one, which assigns every component.
 * No record in the base leaves an {@code Optional} null, so the transformer correctly declines — the first test
 * pins that, and the second keeps the census that would catch a new one.
 *
 * <p>Known production gap, deliberately not papered over here: {@code MergedRecordOptionalDefaults.TARGETS} still
 * names {@code net.minecraft.network.protocol.status.ServerStatus}, a stale 26.x target. The repair is a harmless
 * no-op on it, but the entry should be re-derived by whoever owns that transformer; the census below is what would
 * fail if a record that DOES need the repair ever appeared.
 */
@ResourceLock("system-properties")
class MergedRecordOptionalDefaultsTest {
	private static final Path MERGED = TestFixtures.stagedRoot()
			.resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
	private static final String STATUS = "net.minecraft.network.protocol.status.ServerStatus";

	@AfterEach void reset() { System.clearProperty(MergedRecordOptionalDefaults.PROPERTY); }

	@Test void everyServerStatusConstructorSetsEveryOptional() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED), "merged base not staged: " + MERGED);
		byte[] original = NativeCoremodParityTest.read(MERGED, "net/minecraft/network/protocol/status/ServerStatus");
		ClassNode node = node(original);
		assertEquals(List.of(), MergedRecordOptionalDefaults.unsetOptionals(node),
				"the base this tree is calibrated against leaves no Optional component null, so the repair is a no-op");
		assertTrue(delegatesToTheFullConstructor(node),
				"the deprecated five-argument constructor must delegate to the six-argument one, which sets everything");

		byte[] out = new MergedRecordOptionalDefaults().transform(STATUS, original, null);
		assertSame(original, out, "with nothing left null, the transformer must hand the class back untouched");
		ClassNode repaired = node(out);
		for (MethodNode m : repaired.methods) {
			if (m.name.equals("<init>")) new Analyzer<>(new BasicVerifier()).analyze(repaired.name, m);
		}
		assertSame(original, new MergedRecordOptionalDefaults().transform("net.minecraft.server.MinecraftServer", original, null));
		System.setProperty(MergedRecordOptionalDefaults.PROPERTY, "off");
		assertSame(original, new MergedRecordOptionalDefaults().transform(STATUS, original, null));
	}

	@Test void targetsNameEveryMergedRecordLeavingAnOptionalNull() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED), "merged base not staged: " + MERGED);
		TreeSet<String> found = new TreeSet<>();
		Set<String> records = new TreeSet<>();
		try (ZipFile zip = new ZipFile(MERGED.toFile())) {
			for (ZipEntry entry : Collections.list(zip.entries())) {
				if (!entry.getName().endsWith(".class")) continue;
				ClassReader reader = new ClassReader(zip.getInputStream(entry).readAllBytes());
				if (!"java/lang/Record".equals(reader.getSuperName())) continue;
				ClassNode node = new ClassNode();
				reader.accept(node, 0);
				records.add(node.name.replace('/', '.'));
				if (!MergedRecordOptionalDefaults.unsetOptionals(node).isEmpty()) found.add(node.name.replace('/', '.'));
			}
		}
		assertEquals(new TreeSet<String>(), found,
				"no record on this base leaves an Optional component null; a new entry here needs the repair and "
						+ "must be added to TARGETS");
		// The stale 26.x target is still a real record, so it is harmless; a target that named something else
		// entirely would mean the list had drifted into nonsense.
		for (String target : MergedRecordOptionalDefaults.TARGETS) {
			assertTrue(records.contains(target), target + " is not a record on this base");
		}
	}

	/** Whether the deprecated five-argument constructor delegates to the six-argument one. */
	private static boolean delegatesToTheFullConstructor(ClassNode record) {
		for (MethodNode ctor : record.methods) {
			if (!ctor.name.equals("<init>") || !ctor.desc.endsWith("Ljava/util/Optional;Z)V")) continue;
			for (AbstractInsnNode insn : ctor.instructions) {
				if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
						&& call.owner.equals(record.name) && call.name.equals("<init>")
						&& call.desc.endsWith("Ljava/util/Optional;ZZ)V")) {
					return true;
				}
			}
		}
		return false;
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}
}

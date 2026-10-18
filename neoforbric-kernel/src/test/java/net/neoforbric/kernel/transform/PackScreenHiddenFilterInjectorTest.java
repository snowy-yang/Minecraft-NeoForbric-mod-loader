/*
 * Copyright 2026 The NeoForbric Project
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

package net.neoforbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;

/**
 * Guards the hidden-pack filter: {@code Pack.isHidden} gates listing and not application, which is how a mod's own
 * assets are meant to work — always applied, never a row in the player's resource-pack list.
 *
 * <p>What this class used to guard was a kernel repair. The merge was held to keep NeoForge's {@code Pack} (so
 * {@code isHidden} exists) and vanilla's {@code TransferableSelectionList} and {@code PackSelectionModel} (so
 * nothing reads it), and {@link PackScreenHiddenFilterInjector} patched a
 * {@code Stream.filter(Entry::notHidden)} into {@code TransferableSelectionList.updateList} — MinecraftForge's call
 * site. None of that is true on 1.21.1, and every piece of it was checked against the real classes:
 *
 * <ul>
 *   <li>1.21.1's {@code TransferableSelectionList} has no {@code updateList} method at all — that is a 26.x /
 *       MinecraftForge shape — so the method the injector repairs does not exist and it can never fire.</li>
 *   <li>1.21.1's {@code PackSelectionModel$Entry} has no {@code notHidden()} at all, so the predicate the injector
 *       would build has no target.</li>
 *   <li>The merged base's {@code PackSelectionModel} IS NeoForge's, and it already filters hidden packs where the
 *       rows are built: its constructor filters {@code getSelectedPacks()} and {@code getAvailablePacks()} through
 *       two {@code Predicate} lambdas that ask {@code Pack.isHidden()}, and {@code findNewPacks()} does the same.
 *       Vanilla's own {@code PackSelectionModel} (the unpatched merged classes the pipeline produced) reads
 *       {@code isHidden} zero times, which is what makes those three reads NeoForge's.</li>
 * </ul>
 *
 * <p>So the casualty this repair existed for is not on this anchor, and what is asserted instead is the shape that
 * really carries the contract: the filter has to be there, in the model, ahead of the rows. Lose any of NeoForge's
 * three predicates and this fails, the same way the repair's absence used to.
 */
class PackScreenHiddenFilterInjectorTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("neoforge-base/patched-mc-neoforge-1.21.1.jar");
	private static final String MODEL = "net/minecraft/client/gui/screens/packs/PackSelectionModel";
	private static final String PACK = "net/minecraft/server/packs/repository/Pack";
	private static final String STREAM = "java/util/stream/Stream";

	/** The three predicates, in the three methods NeoForge patched them into, are the whole filter. */
	@Test
	void theStagedModelFiltersHiddenPacksWhereTheRowsAreBuilt() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		ClassNode model = parse(readClass(MODEL + ".class"));

		Set<String> predicates = new LinkedHashSet<>();
		for (MethodNode method : model.methods) {
			int filters = 0;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof InvokeDynamicInsnNode indy && "test".equals(indy.name)) {
					Handle target = (Handle) indy.bsmArgs[1];
					assertEquals(MODEL, target.getOwner(), "the filter's predicate is one of the model's own lambdas");
					assertEquals("(L" + PACK + ";)Z", target.getDesc(), "…over a Pack, as the list holds Packs");
					predicates.add(target.getName());
				}
				if (insn instanceof MethodInsnNode call && "filter".equals(call.name) && STREAM.equals(call.owner)) filters++;
			}
			if (method.name.equals("<init>")) {
				assertEquals(2, filters, "the constructor filters getSelectedPacks() and getAvailablePacks()");
			} else if (method.name.equals("findNewPacks")) {
				assertEquals(1, filters, "findNewPacks() filters the packs it is about to add to the model");
			} else {
				assertEquals(0, filters, method.name + " builds rows, it does not filter them");
			}
		}

		assertEquals(3, predicates.size(), "NeoForge patches exactly three hidden-pack predicates into the model");
		assertEquals(predicates, hiddenReaders(model),
				"every filter predicate asks Pack.isHidden(), and every method that asks Pack.isHidden() is one of "
						+ "the filter predicates — a read outside them is a check nobody consumes");
	}

	/** The screen and the interface have no half of the repair left on this anchor; both were checked. */
	@Test
	void theStagedScreenHasNeitherUpdateListNorNotHidden() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		ClassNode screen = parse(readClass(PackScreenHiddenFilterInjector.LIST + ".class"));
		assertFalse(screen.methods.stream().anyMatch(m -> PackScreenHiddenFilterInjector.UPDATE.equals(m.name)),
				"1.21.1's TransferableSelectionList has no " + PackScreenHiddenFilterInjector.UPDATE
						+ " — the method the injector repairs is a 26.x shape, and the filter it would add is already "
						+ "applied by the model");
		ClassNode entry = parse(readClass(PackScreenHiddenFilterInjector.ENTRY + ".class"));
		assertFalse(entry.methods.stream().anyMatch(m -> "notHidden".equals(m.name)),
				"1.21.1's PackSelectionModel$Entry has no notHidden() — the predicate the injector would build has "
						+ "no target on this anchor");
	}

	/** And the injector therefore stands down on the real class rather than guessing at a shape it does not know. */
	@Test
	void theRepairStandsDownOnTheRealScreen() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		byte[] screen = readClass(PackScreenHiddenFilterInjector.LIST + ".class");
		byte[] once = transform(screen);
		assertSame(screen, once, "the injector must hand back a screen whose shape it does not recognise");
		assertSame(once, transform(once), "…and a second pass changes nothing either");
	}

	@Test
	void anyOtherClassIsHandedBackUntouched() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		byte[] other = readClass("net/minecraft/client/gui/screens/ChatScreen.class");
		assertSame(other, new PackScreenHiddenFilterInjector()
				.transform("net.minecraft.client.gui.screens.ChatScreen", other, null));
	}

	// --- helpers ---------------------------------------------------------------------------------------------

	private static byte[] transform(byte[] bytes) {
		return new PackScreenHiddenFilterInjector()
				.transform(PackScreenHiddenFilterInjector.LIST.replace('/', '.'), bytes, null);
	}

	/** The names of the model's methods that read {@code Pack.isHidden()}. */
	private static Set<String> hiddenReaders(ClassNode model) {
		Set<String> readers = new LinkedHashSet<>();
		for (MethodNode method : model.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && PACK.equals(call.owner) && "isHidden".equals(call.name)
						&& call.getOpcode() == Opcodes.INVOKEVIRTUAL) {
					readers.add(method.name);
				}
			}
		}
		return readers;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] readClass(String entry) throws Exception {
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry e = zip.getEntry(entry);
			assertNotNull(e, entry + " must be in the staged merged base");
			try (InputStream in = zip.getInputStream(e)) {
				return in.readAllBytes();
			}
		}
	}
}

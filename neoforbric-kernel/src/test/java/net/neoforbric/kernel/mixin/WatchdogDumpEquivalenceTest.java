package net.neoforbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.neoforbric.api.*;
import net.neoforbric.kernel.TestFixtures;
import net.neoforbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.tree.ClassNode;

/**
 * The watchdog row's deferred proof, as much of it as this base still has.
 *
 * <p>Used to assert — over fabric-crash-report-info-v1's {@code ServerWatchdogMixin}, the merged
 * {@code ServerWatchdog} and NeoForge's {@code ThreadInfoUtil} — that the finding stays SUSPECTED until the helper is
 * defined, then RESOLVED, then CONFIRMED once the helper's body changes. Two of those three fixtures are gone from
 * this anchor: 0.116.17 ships no {@code ServerWatchdogMixin} and 1.21.1's {@code ServerWatchdog} has no
 * {@code createWatchdogCrashReport} for it to replace, so {@code WatchdogDumpEquivalence.candidate} — which matches
 * the mixin's name, the handler's fingerprint and the replaced body's fingerprints — can never be reached, and the
 * finding it would open cannot be observed here. What is asserted is the half that is real, the carrier's helper the
 * row proves against, and that the other two halves are absent.
 */
@ResourceLock("ModCatalog")
class WatchdogDumpEquivalenceTest {
	private static final String CONFIG = "fabric-crash-report-info-v1.mixins.json";
	private static final String MIXIN_ENTRY = WatchdogDumpEquivalence.MIXIN.replace('.', '/') + ".class";
	private static final Path CARRIER = TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar").normalize();

	@BeforeEach
	@AfterEach
	void reset() {
		MixinCompatibility.reset();
		CompatibilityFindings.reset();
		WatchdogDumpEquivalence.reset();
		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned(CONFIG, "crash-info", Ecosystem.FABRIC)));
	}

	/** The proof's other half: the helper is defined, and observing it settles the pending state. */
	@Test
	void observingTheCarriersHelperSettlesTheDeferredProof() throws Exception {
		TestFixtures.requireFiles(Fixture.STAGED, "staged NeoForge carrier", CARRIER);
		ClassNode helper;
		try (ZipFile zip = new ZipFile(CARRIER.toFile())) {
			ZipEntry entry = zip.getEntry(WatchdogDumpEquivalence.HELPER.replace('.', '/') + ".class");
			TestFixtures.require(Fixture.STAGED, entry != null, WatchdogDumpEquivalence.HELPER + " absent from the carrier");
			helper = MixinFit.parse(zip.getInputStream(entry).readAllBytes());
		}
		assertTrue(helper.methods.stream().anyMatch(m -> m.name.equals("getEntireStacktrace")
				&& m.desc.equals("(Ljava/lang/management/ThreadInfo;)Ljava/lang/String;")),
				"the method the row hashes: " + WatchdogDumpEquivalence.HELPER);
		assertTrue(WatchdogDumpEquivalence.helperUnknown(), "nothing observed yet");
		WatchdogDumpEquivalence.observeHelper(helper);
		assertFalse(WatchdogDumpEquivalence.helperUnknown(), "the helper is defined: the proof is no longer pending");
	}

	/** The mixin the row was written for, and the body it replaced, are both gone from this anchor. */
	@Test
	void theMixinAndTheBodyItReplacedAreNotInThisBase() throws Exception {
		assertFalse(moduleShips("fabric-crash-report-info-v1", MIXIN_ENTRY),
				"the module ships no ServerWatchdogMixin: " + WatchdogDumpEquivalence.MIXIN);
		ClassNode watchdog = StagedFabricMixinFixture.game(WatchdogDumpEquivalence.TARGET.replace('.', '/'), false);
		assertTrue(watchdog.methods.stream().noneMatch(m -> m.name.equals("createWatchdogCrashReport")),
				"1.21.1's ServerWatchdog has no body for the mixin to replace");
	}

	/** Whether the pinned fabric-api's nested module carries an entry. */
	private static boolean moduleShips(String module, String entry) throws Exception {
		Path api = TestFixtures.fabricApi();
		TestFixtures.require(Fixture.THIRD_PARTY, api != null && Files.isRegularFile(api), "fabric-api jar absent");
		try (ZipFile outer = new ZipFile(api.toFile())) {
			for (ZipEntry nested : outer.stream().filter(e -> e.getName().startsWith("META-INF/jars/" + module)).toList()) {
				Path tmp = Files.createTempFile("neoforbric-watchdog", ".jar");
				try (InputStream in = outer.getInputStream(nested)) {
					Files.write(tmp, in.readAllBytes());
				}
				try (ZipFile inner = new ZipFile(tmp.toFile())) {
					return inner.getEntry(entry) != null;
				} finally {
					Files.deleteIfExists(tmp);
				}
			}
		}
		return false;
	}
}

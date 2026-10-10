package net.neoforbric.kernel.transfer;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.neoforbric.kernel.runtime.transfer.TransferIssues;

/**
 * Each scenario runs in a child loader of its own with genuine Fabric transaction machinery and no
 * thread-local state shared across tests. 1.21.1 needs no byte-code transform for this: the engines the
 * adapters drive are Fabric's own transaction classes and our immediate-capability cores, and the 26.x
 * hook that re-wired two transaction managers into one pairing is gone with the framework it served.
 */
class TransferInteropTest {
	/** Captures what the scenarios report, so a test can assert the codes and none leak to the next one. */
	private static final java.util.List<TransferIssues.Issue> REPORTED = new java.util.concurrent.CopyOnWriteArrayList<>();

	@BeforeEach
	void captureReports() {
		REPORTED.clear();
		TransferIssues.setReporter(REPORTED::add);
	}

	@AfterEach
	void restoreReports() {
		TransferIssues.setReporter(issue -> {
		});
	}

	@Test void fabricNestedChildrenCommitOrAbortIndependently() throws Exception { scenario("fabricNestedRollback"); }
	@Test void repeatedSimulationDoesNotReserveOrChangeAnything() throws Exception { scenario("simulation"); }
	@Test void replayMayMoveLessThanPromisedAndSaysSo() throws Exception { scenario("replayMismatch"); }
	@Test void aDeadEndpointAtCommitIsReportedAndTheCommitCompletes() throws Exception { scenario("deadEndpointAtCommitIsReported"); }
	@Test void providerFailureAfterSimulatingPromisesNothing() throws Exception { scenario("providerFailure"); }
	@Test void invalidProviderCountsRollBack() throws Exception { scenario("invalidAmounts"); }
	@Test void nativeSimulateAbortsItsOwnScope() throws Exception { scenario("nativeSimulateAndExecute"); }
	@Test void nativeConsumerJoinsTheOpenFabricTransaction() throws Exception { scenario("nativeConsumerJoinsOpenFabricTransaction"); }
	@Test void fluidQuantizationNeverRoundsAMutation() throws Exception { scenario("quantization"); }
	@Test void transferBetweenTwoEndpointsConservesEveryUnit() throws Exception { scenario("conservation"); }
	@Test void cachedViewsResolveNewProvidersAndRejectMidTransferInvalidation() throws Exception { scenario("liveEndpoints"); }
	@Test void fabricStoreFailureDuringANativeMoveIsRolledBack() throws Exception { scenario("fabricStoreFailureDuringANativeMoveIsRolledBack"); }
	@Test void blankResourcesMoveNothingInEitherDirection() throws Exception { scenario("blanks"); }
	@Test void intraTransactionReadsSeeThePreTransactionState() throws Exception { scenario("intraTransactionReadsSeeThePreTransactionState"); }

	private void scenario(String method) throws Exception {
		String[] entries = System.getProperty("neoforbric.transferTestClasspath").split(File.pathSeparator);
		URL[] urls = new URL[entries.length];
		for (int i = 0; i < entries.length; i++) urls[i] = Path.of(entries[i]).toUri().toURL();
		try (URLClassLoader game = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
			try {
				Class.forName("net.neoforbric.kernel.runtime.transfer.NativeTransferScenarios", true, game)
						.getMethod(method).invoke(null);
			} catch (InvocationTargetException failure) {
				if (failure.getCause() instanceof Error error) throw error;
				if (failure.getCause() instanceof Exception exception) throw exception;
				throw failure;
			}
		}
	}
}

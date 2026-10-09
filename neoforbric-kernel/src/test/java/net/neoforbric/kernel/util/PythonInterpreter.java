package net.neoforbric.kernel.util;

/**
 * How a test names the Python interpreter it runs.
 *
 * <p>A bare {@code python3} is a Windows Store shim: it prints "Python was not found; run without arguments to
 * install from the Microsoft Store" and exits with a non-zero status, which the calling test reads as the probe
 * failing rather than as the interpreter being missing. {@code $PYTHON} names a real interpreter, and the build's
 * own harness already sets it; every probe resolves the name in one place so that changing the convention once
 * reaches all of them.
 */
public final class PythonInterpreter {
	private PythonInterpreter() {
	}

	public static final String PYTHON_VARIABLE = "PYTHON";

	/** The interpreter to put first on a command line: {@code $PYTHON} when it names one, else {@code python3}. */
	public static String command() {
		String configured = System.getenv(PYTHON_VARIABLE);
		return configured == null || configured.isBlank() ? "python3" : configured;
	}

	/** {@link #command()} unless {@code requested} already names an interpreter other than the bare default. */
	public static String command(String requested) {
		return "python3".equals(requested) ? command() : requested;
	}
}

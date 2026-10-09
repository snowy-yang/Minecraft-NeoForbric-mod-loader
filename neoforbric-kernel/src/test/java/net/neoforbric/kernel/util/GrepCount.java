package net.neoforbric.kernel.util;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The one line the gates and their tests both need to agree on: does a pattern count a line in a text file?
 * A gate asks {@code grep -acE <pattern> <file>}, so a test that checks what a gate would have to conclude MUST
 * mean the same thing by "matches" as grep does. Implementing {@code grep} again here — in Java — is the only
 * way to make that agreement testable, since {@code grep} is not a program that exists on every platform this
 * suite runs on. On Windows it is often missing entirely, which makes the test fail for a reason that has
 * nothing to do with the behaviour under test.
 *
 * <p>The contract is grep's, byte for byte:
 * <ul>
 *   <li>{@code -a} — treat the file as text even if it looks binary. {@code Pattern} always does.</li>
 *   <li>{@code -c} — count matching LINES, not matches. One match on a line is a hit.</li>
 *   <li>{@code -E} — an extended regular expression. Java's {@link Pattern} is a superset, so it accepts every
 *       ERE these gates use (character classes, {@code ()}, {@code |}, {@code +}, {@code ?}, {@code *},
 *       bounded repeats).</li>
 * </ul>
 *
 * <p>What this deliberately does NOT reproduce is grep's POSIX-class set ({@code [[:digit:]]}) — no gate uses
 * one, and pretending to support it would be a silent second lint.
 */
public final class GrepCount {

	/** How many lines of {@code text} match {@code pattern} the way {@code grep -acE} would count them. */
	public static int matchingLines(String pattern, String text) {
		Pattern compiled = Pattern.compile(pattern);
		int hits = 0;
		for (String line : text.split("\r\n|\r|\n", -1)) {
			if (compiled.matcher(line).find()) hits++;
		}
		return hits;
	}

	/**
	 * Whether at least one line of the file matches, again exactly as {@code grep -acE} answers. A file that
	 * cannot be read is an {@link java.io.UncheckedIOException} — the caller asked about a log it just wrote,
	 * so there is no legitimate "absent" answer to give.
	 */
	public static boolean matches(String pattern, Path file) {
		if (!Files.isRegularFile(file)) return false;
		try {
			return matchingLines(pattern, Files.readString(file, StandardCharsets.ISO_8859_1)) > 0;
		} catch (java.io.IOException unreadable) {
			throw new java.io.UncheckedIOException(unreadable);
		}
	}

	/**
	 * Whether {@code pattern} is one grep could run. A malformed pattern fails the gate at runtime and reads
	 * like a mismatch, so it is worth saying up front that it is the pattern and not the log.
	 */
	public static boolean isValidPattern(String pattern) {
		try {
			Pattern.compile(pattern);
			return true;
		} catch (PatternSyntaxException bad) {
			return false;
		}
	}

	private GrepCount() {
	}
}

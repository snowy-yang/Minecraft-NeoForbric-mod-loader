package net.neoforbric.kernel.runtime.transfer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.function.Consumer;

import net.neoforbric.kernel.util.NeoForbricLog;

/** The boot integration can attribute these runtime findings through its normal compatibility catalog. */
public final class TransferIssues {
	private TransferIssues() { }
	public record Issue(String code, String providerClass, String detail) { }
	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
	private static volatile Consumer<Issue> reporter = issue -> NeoForbricLog.warn("[NeoForbric/Transfer] %s: %s — %s",
			issue.code(), issue.providerClass(), issue.detail());
	public static void setReporter(Consumer<Issue> sink) { reporter = java.util.Objects.requireNonNull(sink); }
	public static void report(String code, Object provider, String detail) {
		String owner = provider == null ? "unknown" : provider.getClass().getName();
		reportType(code, owner, detail);
	}
	public static void reportType(String code, String owner, String detail) {
		if (REPORTED.add(code + ":" + owner)) reporter.accept(new Issue(code, owner, detail));
	}

	// 1.21.1: the scenario suite asserts which codes a failed replay reports. Same-package test seams: the
	// reporter swap alone cannot see a code that was already reported once, so the capture sees the set too.
	static final List<Issue> findingsForTest() { return List.copyOf(REPORTED.stream().map(k -> { String[] parts = k.split(":", 2); return new Issue(parts[0], parts[1], ""); }).toList()); }
	static void clearForTest() { REPORTED.clear(); }
}

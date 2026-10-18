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

package net.neoforbric.kernel.hook;

/**
 * Structural validation for the names and descriptors a {@link HookKey} carries.
 *
 * <p>Every one of these checks runs at construction, so a malformed key fails where it is written rather than at
 * the first dispatch — the failure mode this package exists to avoid is a seam that silently binds to nothing.
 * Nothing here reads a class file: these are syntax rules, not resolution.
 */
final class Descriptors {
	private Descriptors() {
	}

	/** A JVM internal name: {@code net/minecraft/world/entity/LivingEntity}, never the dotted form. */
	static String checkInternalName(String name, String what) {
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException(what + " is empty");
		}
		if (name.startsWith("/") || name.endsWith("/")) {
			throw new IllegalArgumentException(what + " starts or ends with '/': " + name);
		}
		if (name.indexOf("//") >= 0) {
			throw new IllegalArgumentException(what + " has an empty segment: " + name);
		}
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c == '.' || c == ';' || c == '[' || c == '(' || c == ')') {
				throw new IllegalArgumentException(what + " contains '" + c + "' (internal names use '/', not '.'): " + name);
			}
		}
		return name;
	}

	/** A method name, including {@code <init>} and {@code <clinit>}. */
	static String checkMethodName(String name, String what) {
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException(what + " is empty");
		}
		if (name.indexOf('.') >= 0 || name.indexOf(';') >= 0 || name.indexOf('/') >= 0) {
			throw new IllegalArgumentException(what + " is not a method name: " + name);
		}
		return name;
	}

	/**
	 * A method descriptor, e.g. {@code (Lnet/minecraft/world/damagesource/DamageSource;F)Z}.
	 *
	 * <p>{@code V} is accepted as the return type only: a parameter of type {@code void} is not a descriptor, and
	 * accepting it here would move that failure to the weaver.
	 */
	static String checkMethodDescriptor(String desc, String what) {
		if (desc == null || desc.isEmpty()) {
			throw new IllegalArgumentException(what + " is empty");
		}
		if (desc.charAt(0) != '(') {
			throw new IllegalArgumentException(what + " does not start with '(': " + desc);
		}
		int close = desc.indexOf(')');
		if (close < 0) {
			throw new IllegalArgumentException(what + " has no ')': " + desc);
		}
		int i = 1;
		while (i < close) {
			i = parseType(desc, i, close, what, false);
		}
		if (i != close) {
			throw new IllegalArgumentException(what + " has a malformed parameter list: " + desc);
		}
		int end = parseType(desc, close + 1, desc.length(), what, true);
		if (end != desc.length()) {
			throw new IllegalArgumentException(what + " has trailing characters after the return type: " + desc);
		}
		return desc;
	}

	private static int parseType(String desc, int i, int limit, String what, boolean mayBeVoid) {
		int dims = 0;
		while (i < limit && desc.charAt(i) == '[') {
			dims++;
			i++;
		}
		if (dims > 255) {
			throw new IllegalArgumentException(what + " has more than 255 array dimensions: " + desc);
		}
		if (i >= limit) {
			throw new IllegalArgumentException(what + " ends inside a type: " + desc);
		}
		char c = desc.charAt(i);
		switch (c) {
			case 'V':
				if (!mayBeVoid) {
					throw new IllegalArgumentException(what + " uses 'V' as a parameter type: " + desc);
				}
				if (dims > 0) {
					throw new IllegalArgumentException(what + " uses an array of 'V': " + desc);
				}
				return i + 1;
			case 'Z': case 'B': case 'C': case 'S': case 'I': case 'J': case 'F': case 'D':
				return i + 1;
			case 'L': {
				int semi = desc.indexOf(';', i);
				if (semi < 0 || semi >= limit) {
					throw new IllegalArgumentException(what + " has an unterminated 'L' type: " + desc);
				}
				checkInternalName(desc.substring(i + 1, semi), what + " object type");
				return semi + 1;
			}
			default:
				throw new IllegalArgumentException(what + " has an unknown type character '" + c + "': " + desc);
		}
	}
}

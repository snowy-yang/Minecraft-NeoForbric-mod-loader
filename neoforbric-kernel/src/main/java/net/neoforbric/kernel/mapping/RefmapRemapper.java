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

package net.neoforbric.kernel.mapping;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.json.JsonFormat;

import net.fabricmc.mappingio.tree.MappingTree;

/**
 * Rewrites a Fabric refmap's VALUES from intermediary names into the Mojmap namespace the merged base runs
 * under — the refmap half of {@link FabricModuleRemapTool}'s job.
 *
 * <p>Why this must exist at all: tiny-remapper rewrites a module's CLASSES (descriptors, call sites, and the
 * {@code @Mixin} target array — verified against the remapped fabric-transfer module), but the refmap JSON
 * rides through a remap untouched, and it is the refmap that Mixin consults for every member-level
 * {@code @Inject}/{@code @At}/{@code @Shadow} string. A loom-built refmap maps the names a mixin was WRITTEN
 * with (in fabric-api those are the intermediary names themselves) to the INTERMEDIARY runtime names — and
 * both sides of that mapping miss a Mojmap-named target. Without this rewrite the mixin's class target binds
 * but every member anchor misses, the injection soft-skips, and the module loads and registers its event
 * callbacks while none of them can ever fire. (Sinytra Connector solves the same problem for its own runs by
 * patching the refmap during jar transformation — {@code research/Connector}'s {@code RefmapStorage}.)
 *
 * <p>Both sides of the mapping come from the same merged tree the bytecode remap uses
 * ({@link NeoForbricMappings}), so refmap names cannot drift from what the remapper wrote into the class
 * files. Intermediary member names are globally unique across the game jar, so owner-less refmap forms
 * resolve by name alone. Anything the tree does not know — a mod's own class, an already-Named name — passes
 * through unchanged; an entry that fails to resolve stays visible in the output, which is the honest outcome.
 *
 * <p>Only VALUES are rewritten. The KEYS are the annotation strings inside the mixin's class bytes, which
 * tiny-remapper does not touch, so they must keep matching verbatim.
 */
public final class RefmapRemapper {
	private RefmapRemapper() {
	}

	/** Reverse lookups (intermediary → Mojmap) over the merged tree, built once per remap run. */
	public static final class Names {
		final Map<String, String> classByIntermediary = new HashMap<>();
		final Map<String, String> methodByIntermediaryNameDesc = new HashMap<>();
		final Map<String, String> methodByIntermediaryName = new HashMap<>();
		final Map<String, String> fieldByIntermediaryNameDesc = new HashMap<>();
		final Map<String, String> fieldByIntermediaryName = new HashMap<>();

		/** Builds the reverse indexes over every class the merged tree knows. */
		public static Names from(NeoForbricMappings mappings) {
			Names names = new Names();
			int intermediary = mappings.tree().getNamespaceId(NeoForbricMappings.INTERMEDIARY);
			for (MappingTree.ClassMapping cls : mappings.tree().getClasses()) {
				String intermediaryClassName = cls.getName(intermediary);
				if (intermediaryClassName == null || intermediaryClassName.equals(cls.getSrcName())) continue;
				names.classByIntermediary.put(intermediaryClassName, cls.getSrcName());
				for (MappingTree.MethodMapping method : cls.getMethods()) {
					String name = method.getName(intermediary);
					if (name == null) continue;
					names.methodByIntermediaryName.putIfAbsent(name, method.getSrcName());
					String desc = method.getDesc(intermediary);
					if (desc != null) names.methodByIntermediaryNameDesc.putIfAbsent(name + desc, method.getSrcName());
				}
				for (MappingTree.FieldMapping field : cls.getFields()) {
					String name = field.getName(intermediary);
					if (name == null) continue;
					names.fieldByIntermediaryName.putIfAbsent(name, field.getSrcName());
					String desc = field.getDesc(intermediary);
					if (desc != null) names.fieldByIntermediaryNameDesc.putIfAbsent(name + desc, field.getSrcName());
				}
			}
			return names;
		}
	}

	/** The refmap file a mixin config names, or {@code null} when this is not a mixin config. */
	public static String refmapNameOf(byte[] json) {
		String text = new String(json, StandardCharsets.UTF_8);
		if (!text.contains("\"refmap\"")) return null;
		try {
			Config config = JsonFormat.fancyInstance().createParser().parse(new StringReader(text));
			String refmap = config.get("refmap");
			return refmap == null || refmap.isBlank() || "true".equals(refmap) ? null : refmap;
		} catch (RuntimeException malformed) {
			return null;   // holds anything a jar may hold; a parse failure just means "not ours to touch"
		}
	}

	/**
	 * Rewrites every value of a refmap JSON. Both layouts loom emits are walked: the current
	 * {@code mappings:{mixin:{key:value}}} and the legacy {@code data:{namespace:{mixin:{key:value}}}}} — the
	 * walk is over String leaves, so any nesting depth resolves the same way, and an entry that already holds
	 * a nested config is descended into, never overwritten.
	 */
	public static byte[] rewrite(byte[] json, Names names) {
		Config config = JsonFormat.fancyInstance().createParser().parse(new StringReader(new String(json, StandardCharsets.UTF_8)));
		rewriteValues(config.get("mappings"), names);
		rewriteValues(config.get("data"), names);
		StringWriter out = new StringWriter(json.length + 1024);
		JsonFormat.fancyInstance().createWriter().write(config, out);
		return out.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static void rewriteValues(Object node, Names names) {
		if (!(node instanceof Config config)) return;
		for (Config.Entry entry : config.entrySet()) {
			if (entry.getValue() instanceof Config nested) rewriteValues(nested, names);
			else if (entry.getValue() instanceof String value) entry.setValue(remapValue(value, names));
		}
	}

	/** Rewrites one refmap value in whatever form loom wrote it. */
	static String remapValue(String value, Names names) {
		if (value == null || value.isEmpty()) return value;
		// Lowner;member... — a member (or the class itself) with its owner.
		if (value.startsWith("L") && value.indexOf(';') > 0) {
			int member = value.indexOf(';');
			String owner = value.substring(1, member), rest = value.substring(member + 1);
			String mappedOwner = names.classByIntermediary.getOrDefault(owner, owner);
			if (rest.isEmpty()) return "L" + mappedOwner + ";";
			return "L" + mappedOwner + ";" + remapValue(rest, names);
		}
		// name(descriptor)ret — a method, owner-less (the owner came from the L-form above).
		int descriptor = value.indexOf('(');
		if (descriptor > 0) {
			String name = value.substring(0, descriptor), descriptorText = value.substring(descriptor);
			String mappedName = names.methodByIntermediaryNameDesc.get(name + descriptorText);
			if (mappedName == null) mappedName = names.methodByIntermediaryName.get(name);
			if (mappedName == null) return remapDescriptor(value, names);
			return mappedName + remapDescriptor(descriptorText, names);
		}
		// name:descriptor — the field form.
		int colon = value.indexOf(':');
		if (colon > 0) {
			String name = value.substring(0, colon), descriptorText = value.substring(colon + 1);
			String mappedName = names.fieldByIntermediaryNameDesc.get(name + descriptorText);
			if (mappedName == null) mappedName = names.fieldByIntermediaryName.get(name);
			if (mappedName == null) return remapDescriptor(value, names);
			return mappedName + ":" + remapDescriptor(descriptorText, names);
		}
		// A bare class name, or a bare member name.
		String mapped = names.classByIntermediary.get(value);
		if (mapped != null) return mapped;
		mapped = names.methodByIntermediaryName.get(value);
		if (mapped != null) return mapped;
		mapped = names.fieldByIntermediaryName.get(value);
		return mapped != null ? mapped : value;
	}

	/** Rewrites every {@code Lname;} inside a descriptor; primitives and array brackets keep their shape. */
	static String remapDescriptor(String descriptor, Names names) {
		if (descriptor.indexOf('L') < 0) return descriptor;
		StringBuilder out = new StringBuilder(descriptor.length() + 64);
		for (int at = 0; at < descriptor.length(); at++) {
			char c = descriptor.charAt(at);
			if (c != 'L') { out.append(c); continue; }
			int end = descriptor.indexOf(';', at);
			if (end < 0) { out.append(c); continue; }
			String name = descriptor.substring(at + 1, end);
			out.append('L').append(names.classByIntermediary.getOrDefault(name, name)).append(';');
			at = end;
		}
		return out.toString();
	}
}

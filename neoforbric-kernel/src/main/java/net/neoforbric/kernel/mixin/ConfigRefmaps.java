/* Copyright 2026 The NeoForbric Project. Licensed under the Apache License, Version 2.0. */
package net.neoforbric.kernel.mixin;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.json.JsonFormat;

/**
 * The refmap of one mixin config, resolved the way Mixin resolves at apply time — for judging.
 *
 * <p>Why this must exist: a 1.21.1-era Fabric mod's injectors are authored in the names its mixin was
 * WRITTEN with — Yarn for fabric-api's own modules, and the same loom flow for every mod built against
 * them — and those annotation strings are <b>keys</b> into the refmap the loom build shipped beside the
 * class. The module remap rewrites the refmap's VALUES into the base's Mojmap namespace but touches
 * neither the keys nor the annotation strings themselves ({@link net.neoforbric.kernel.mapping.RefmapRemapper}
 * rewrites values only, by design), so the bytes the kernel serves and judges carry Yarn-authored
 * selectors and {@code @At} targets that resolve through the refmap at apply time and miss it nowhere
 * else. Mixin resolves them; the kernel's own fitness read did not, and judged half a module set
 * {@link MixinFit.Verdict#UNFIT} for anchors that bind perfectly — then dropped them.
 *
 * <p>This class is the judging half of that one seam: given the config's JSON bytes and the resource
 * reader that served them, it reads the config's {@code refmap} entry and answers, for each mixin class
 * the config declares, the Mojmap member its annotation string names — exactly the lookup Mixin makes
 * when it applies the mixin. What asks it is the fitness flow that decides whether a mixin stays; the
 * bytes Mixin loads are untouched, and Mixin still resolves the same strings through the same refmap at
 * apply time, so the two can never drift apart: both sides read the one file the mod shipped.
 *
 * <p>A config with no refmap, a refmap that cannot be read, or an entry the refmap does not hold
 * answers nothing — the string is judged as it stands, exactly as before.
 */
final class ConfigRefmaps {
	private ConfigRefmaps() { }

	/** The refmap of one config: for each mixin class name (internal), its annotation-string → member map. */
	record Refmap(Map<String, Map<String, String>> mappings) {
		/** The Mojmap member {@code key} names in {@code mixinClass}, or null when the refmap does not know. */
		String member(String mixinClass, String key) {
			Map<String, String> perClass = mappings.get(mixinClass);
			return perClass == null ? null : perClass.get(key);
		}

		/**
		 * Resolves, on a COPY judged only here, the annotation strings of every injector and {@code @Shadow} in
		 * {@code mixin} through this refmap — the same lookups Mixin makes at apply time: the injector's selector
		 * strings ({@code method=}), each {@code @At} member target, and the {@code @Shadow}'s member — so the
		 * fitness verdict describes the anchors that will actually bind. The node passed in is mutated; callers
		 * judge a parsed copy and never the bytes the game loads.
		 */
		void resolve(org.objectweb.asm.tree.ClassNode mixin) {
			String self = mixin.name;
			for (org.objectweb.asm.tree.MethodNode method : mixin.methods) {
				if (method.visibleAnnotations != null) for (var annotation : method.visibleAnnotations) resolveMember(self, annotation);
				if (method.invisibleAnnotations != null) for (var annotation : method.invisibleAnnotations) resolveMember(self, annotation);
			}
		}

		/** The annotation keys whose value is another annotation — a member string inside those resolves too. */
		private static final java.util.Set<String> NESTED = java.util.Set.of("at", "value", "slice");

		private void resolveMember(String self, org.objectweb.asm.tree.AnnotationNode annotation) {
			if (annotation == null || annotation.values == null) return;
			// Mixin resolves a @Shadow's own member string through the refmap too — the name the class
			// declares the field or method under is a key like any other when the annotation names one.
			if ("Lorg/spongepowered/asm/mixin/Shadow;".equals(annotation.desc)) {
				Object aliased = annotation.values.size() >= 2 && "value".equals(String.valueOf(annotation.values.get(0)))
						? annotation.values.get(1) : null;
				if (aliased instanceof String name) {
					String resolved = member(self, name);
					if (resolved != null) annotation.values.set(1, resolved);
				}
				return;
			}
			for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
				String key = String.valueOf(annotation.values.get(i));
				Object value = annotation.values.get(i + 1);
				if ("method".equals(key) && value instanceof List<?> selectorList) {
					@SuppressWarnings("unchecked") List<Object> selectors = (List<Object>) selectorList;
					for (int k = 0; k < selectors.size(); k++) {
						if (selectors.get(k) instanceof String name) {
							String resolved = member(self, name);
							if (resolved != null) selectors.set(k, ownerless(resolved, name));
						}
					}
				} else if ("target".equals(key) && value instanceof String target) {
					String resolved = member(self, target);
					if (resolved != null) annotation.values.set(i + 1, resolved);
				} else if (NESTED.contains(key) && value instanceof List<?> nested && !nested.isEmpty()
						&& nested.getFirst() instanceof org.objectweb.asm.tree.AnnotationNode) {
					for (Object node : nested) if (node instanceof org.objectweb.asm.tree.AnnotationNode inner) resolveMember(self, inner);
				} else if (NESTED.contains(key) && value instanceof org.objectweb.asm.tree.AnnotationNode single) {
					resolveMember(self, single);
				}
			}
		}

		/** {@code member} is a full {@code Lowner;name(desc)ret} string; a selector keeps its owner-less form. */
		private static String ownerless(String member, String original) {
			int semi = member.indexOf(';');
			return semi >= 0 && member.startsWith("L") && original.indexOf(';') < 0 ? member.substring(semi + 1) : member;
		}

	}

	/** The empty refmap: every string answers null and is judged as it stands. */
	static final Refmap NONE = new Refmap(Map.of());

	/**
	 * Reads the refmap one config names, through the same reader that served the config itself; {@link #NONE}
	 * when the config declares no refmap or the file cannot be read as one.
	 */
	static Refmap of(byte[] configJson, Function<String, byte[]> resource) {
		String named = asString(parse(configJson), List.of("refmap"));
		if (named == null || named.isEmpty()) return NONE;
		byte[] bytes = resource.apply(named);
		if (bytes == null) return NONE;
		Config root = parse(bytes);
		if (root == null) return NONE;
		Config mappings = root.get(List.of("mappings"));
		if (!(mappings instanceof Config)) return NONE;
		Map<String, Map<String, String>> out = new LinkedHashMap<>();
		for (Map.Entry<String, Object> perMixin : mappings.valueMap().entrySet()) {
			if (!(perMixin.getValue() instanceof Config perClass)) continue;
			Map<String, String> members = new LinkedHashMap<>();
			for (Map.Entry<String, Object> entry : perClass.valueMap().entrySet())
				if (entry.getValue() instanceof String member) members.put(entry.getKey(), member);
			out.put(perMixin.getKey(), members);
		}
		return out.isEmpty() ? NONE : new Refmap(Map.copyOf(out));
	}

	/** The config's declared mixin classes, both common and per-side arrays, as internal names. */
	static List<String> declaredMixins(byte[] configJson, String pkg) {
		Config root = parse(configJson);
		if (root == null) return List.of();
		var out = new java.util.ArrayList<String>();
		for (String side : List.of("mixins", "client", "server")) {
			Object array = root.get(List.of(side));
			if (!(array instanceof List<?> entries)) continue;
			for (Object name : entries)
				if (name instanceof String mixin) out.add(pkg.replace('.', '/') + "/" + mixin);
		}
		return out;
	}

	private static Config parse(byte[] bytes) {
		try {
			return JsonFormat.fancyInstance().createParser().parse(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
		} catch (RuntimeException notJson) {
			return null;
		}
	}

	private static String asString(Config config, List<String> path) {
		Object value = config == null ? null : config.get(path);
		return value instanceof String s ? s : null;
	}
}

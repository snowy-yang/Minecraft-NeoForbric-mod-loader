/* Copyright 2026 The NeoForbric Project. Licensed under the Apache License, Version 2.0. */
package net.neoforbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Bytecode and mixin-annotation utilities shared by the mixin machinery that adapts a guest mod's compiled mixin to
 * the game this loader runs.
 *
 * <p>These are deliberately named after nothing: they were first written as the private helpers of one mod's mixin
 * decorator, which made a single-mod adapter the owner of the whole package's ASM vocabulary. What is left here has
 * no opinion about whose mixin it is editing — it is the shape-reading layer every adapter in this package leans on.
 */
public final class MixinBytecode {
	private MixinBytecode() { }

	/** Whether {@code a}'s {@code method} selector is exactly the one selector {@code selector} — as authored. */
	public static boolean selects(AnnotationNode a, String selector) {
		return a != null && MixinFit.stringList(MixinFit.value(a, "method")).equals(List.of(selector));
	}

	/** The one method named {@code name} in {@code c}, or null. */
	public static MethodNode named(ClassNode c, String name) {
		return c.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElse(null);
	}

	/** The one method in {@code c} whose {@code name + descriptor} is {@code s}, or null. */
	public static MethodNode selector(ClassNode c, String s) {
		return c.methods.stream().filter(m -> (m.name + m.desc).equals(s)).findFirst().orElse(null);
	}

	/** The call {@code c} makes, in the {@code owner;name+descriptor} form an {@code @At} target takes. */
	public static String member(MethodInsnNode c) {
		return "L" + c.owner + ";" + c.name + c.desc;
	}

	/** How many calls of {@code member} {@code m} makes. */
	public static int count(MethodNode m, String member) {
		int n = 0;
		for (var i : m.instructions)
			if (i instanceof MethodInsnNode c && member.equals(member(c))) n++;
		return n;
	}

	/** The first call of {@code member} in {@code m}, or null. */
	public static MethodInsnNode first(MethodNode m, String member) {
		for (var i : m.instructions)
			if (i instanceof MethodInsnNode c && member.equals(member(c))) return c;
		return null;
	}

	/** How many instructions in {@code m} push the constant {@code value}. */
	public static int constants(MethodNode m, int value) {
		int n = 0;
		for (var i : m.instructions)
			if (i instanceof IntInsnNode c && c.operand == value) n++;
		return n;
	}

	/** The index of {@code i} in {@code m}, or -1 — so two positions can be compared without walking. */
	public static int index(MethodNode m, AbstractInsnNode i) {
		return i == null ? -1 : m.instructions.indexOf(i);
	}

	/** The next instruction that really is one: labels, frames and line numbers are not. */
	public static AbstractInsnNode next(AbstractInsnNode n) {
		do { n = n.getNext(); } while (n != null && n.getOpcode() < 0);
		return n;
	}

	/** The previous instruction that really is one: labels, frames and line numbers are not. */
	public static AbstractInsnNode previous(AbstractInsnNode n) {
		do { n = n.getPrevious(); } while (n != null && n.getOpcode() < 0);
		return n;
	}

	/** The one store into {@code slot}, when it directly takes the result of the one call of {@code member}. */
	public static VarInsnNode storedFrom(MethodNode m, int slot, String member) {
		VarInsnNode store = null;
		for (var i : m.instructions)
			if (i instanceof VarInsnNode v && v.getOpcode() == org.objectweb.asm.Opcodes.ASTORE && v.var == slot) {
				if (store != null) return null;
				store = v;
			}
		return store != null && count(m, member) == 1 && previous(store) instanceof MethodInsnNode c
				&& member.equals(member(c)) ? store : null;
	}

	/** Sugar {@code @Local} annotations for the parameters at {@code indexes}, starting at {@code param}. */
	@SuppressWarnings("unchecked")
	public static List<AnnotationNode>[] local(int params, int param, int... indexes) {
		List<AnnotationNode>[] result = new List[params];
		for (int n = 0; n < indexes.length; n++) {
			AnnotationNode local = new AnnotationNode("Lcom/llamalad7/mixinextras/sugar/Local;");
			local.values = new ArrayList<>(List.of("index", indexes[n]));
			result[param + n] = new ArrayList<>(List.of(local));
		}
		return result;
	}

	/** {@code a.key = value}, replacing any earlier value — an annotation this loader already rewrote once. */
	public static void set(AnnotationNode a, String key, Object value) {
		if (a.values == null) a.values = new ArrayList<>();
		for (int i = 0; i < a.values.size(); i += 2) {
			if (key.equals(a.values.get(i))) { a.values.set(i + 1, value); return; }
		}
		a.values.add(key);
		a.values.add(value);
	}

	/** {@code a.key}, when present. */
	public static void remove(AnnotationNode a, String key) {
		if (a.values == null) return;
		for (int i = 0; i < a.values.size(); i += 2) {
			if (key.equals(a.values.get(i))) { a.values.remove(i + 1); a.values.remove(i); return; }
		}
	}
}

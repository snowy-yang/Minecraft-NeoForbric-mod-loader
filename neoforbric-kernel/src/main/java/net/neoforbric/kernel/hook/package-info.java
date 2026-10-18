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

/**
 * The hook bus the kernel's bridges dispatch through. <b>Internal</b> — no mod ever sees these types.
 *
 * <h2>What it is for</h2>
 *
 * <p>Of the seventy classes in {@code transform/}, twenty-three splice a call to the game side so that an
 * ecosystem's hook fires; the other forty-seven rewrite the base's own bytecode and dispatch to nobody. Only the
 * twenty-three belong here — see {@code HOOKBUS.md} for the measurement behind that split.
 *
 * <h2>Why it names no game type</h2>
 *
 * <p>Every parameter is {@code Object}, and {@link net.neoforbric.kernel.hook.HookCall} hands a body a boxed
 * argument array it never interprets. That is what lets the package sit on the BOOT side of the delegation split
 * while being called from woven game-side bytecode, and it is what makes the whole contract testable without a
 * game running. {@code HookBusNoGameTypesTest} pins it by reading this package's constant pools.
 *
 * <h2>What it deliberately is not</h2>
 *
 * <ul>
 *   <li>Not an event model. It carries an {@code Object[]}; typed events are an upper layer's problem.
 *   <li>Not fuzzy. Every key is an exact {@code owner/name/desc}; a name-only match is how a seam drifts silently
 *       when the carrier moves.
 *   <li>Not reflective. Bodies are called through {@link net.neoforbric.kernel.hook.HookBody}, directly.
 * </ul>
 */
package net.neoforbric.kernel.hook;

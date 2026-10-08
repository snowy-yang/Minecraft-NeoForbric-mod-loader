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

package net.neoforbric.loader.impl.forge.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.neoforbric.loader.impl.util.NeoForbricLog;

/**
 * Dev-only merged-client smoke controller. Assumes launch-time quick-play selected a fixed world and only
 * automates readiness logging, clean disconnect, and client shutdown.
 */
public final class NeoForbricClientSmokeController {
	private static final String ENABLED = "neoforbric.clientSmoke";
	private static final String WORLD_NAME = "neoforbric.clientSmokeWorld";
	private static final String READY_TICKS = "neoforbric.clientSmokeReadyTicks";
	private static final String DISCONNECT_TICKS = "neoforbric.clientSmokeDisconnectTicks";

	private static Object lastLevel;
	private static int worldTicks;
	private static boolean joinedLogged;
	private static boolean readyLogged;
	private static boolean disconnectRequested;
	private static boolean stopRequested;

	private NeoForbricClientSmokeController() {
	}

	public static void onMinecraftTick(Object minecraft) {
		if (!Boolean.getBoolean(ENABLED) || minecraft == null || stopRequested) return;

		Object level = fieldValue(minecraft, "level");
		Object player = fieldValue(minecraft, "player");
		if (level != null && player != null) {
			if (level != lastLevel) {
				lastLevel = level;
				worldTicks = 0;
				joinedLogged = false;
				readyLogged = false;
				disconnectRequested = false;
			}

			worldTicks++;
			if (!joinedLogged) {
				joinedLogged = true;
				String requestedWorld = System.getProperty(WORLD_NAME, "<quick-play>");
				NeoForbricLog.info("[NeoForbric/ClientSmoke] joined world via quick-play: " + requestedWorld);
			}
			if (!readyLogged && worldTicks >= Integer.getInteger(READY_TICKS, 60)) {
				readyLogged = true;
				NeoForbricLog.info("[NeoForbric/ClientSmoke] client-ready after " + worldTicks + " world ticks");
			}
			if (!disconnectRequested && worldTicks >= Integer.getInteger(DISCONNECT_TICKS, 120)) {
				disconnectRequested = true;
				NeoForbricLog.info("[NeoForbric/ClientSmoke] requesting clean disconnect after " + worldTicks + " world ticks");
				invokeNoArg(minecraft, "disconnectWithSavingScreen");
			}
			return;
		}

		lastLevel = null;
		worldTicks = 0;
		if (disconnectRequested) {
			disconnectRequested = false;
			stopRequested = true;
			NeoForbricLog.info("[NeoForbric/ClientSmoke] clean disconnect observed; stopping client");
			invokeNoArg(minecraft, "stop");
		}
	}

	private static Object fieldValue(Object owner, String name) {
		Field field = findField(owner.getClass(), name);
		if (field == null) return null;
		try {
			field.setAccessible(true);
			return field.get(owner);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	private static void invokeNoArg(Object owner, String name) {
		Method method = findMethod(owner.getClass(), name);
		if (method == null) return;
		try {
			method.setAccessible(true);
			method.invoke(owner);
		} catch (ReflectiveOperationException | RuntimeException e) {
			NeoForbricLog.warn("[NeoForbric/ClientSmoke] could not invoke Minecraft." + name, e);
		}
	}

	private static Field findField(Class<?> c, String name) {
		for (; c != null; c = c.getSuperclass()) {
			try {
				return c.getDeclaredField(name);
			} catch (NoSuchFieldException ignore) {
				// try superclass
			}
		}
		return null;
	}

	private static Method findMethod(Class<?> c, String name) {
		for (; c != null; c = c.getSuperclass()) {
			for (Method method : c.getDeclaredMethods()) {
				if (method.getName().equals(name) && method.getParameterCount() == 0) {
					return method;
				}
			}
		}
		return null;
	}
}

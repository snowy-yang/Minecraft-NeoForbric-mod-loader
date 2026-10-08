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

package net.neoforbric.loader.impl.forge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.neoforbric.loader.impl.forge.runtime.NeoForbricClientSmokeController;

/**
 * Dev-only merged-client smoke tick hook.
 */
@Mixin(targets = "net.minecraft.client.Minecraft")
public class NeoForbricClientSmokeMixin {
	@Inject(method = "tick()V", at = @At("TAIL"), require = 0)
	private void neoforbric$runClientSmokeController(CallbackInfo ci) {
		NeoForbricClientSmokeController.onMinecraftTick(this);
	}
}

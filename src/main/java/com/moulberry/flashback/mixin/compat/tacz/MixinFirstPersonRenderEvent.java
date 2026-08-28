package com.moulberry.flashback.mixin.compat.tacz;

import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * View bob for TACZ guns is now handled at the camera level in MixinCamera,
 * which shifts the actual viewport position and rotation. This creates a true
 * screen bob that moves the entire view together with natural tilt.
 */
@IfModLoaded("tacz")
@Pseudo
@Mixin(targets = "com.tacz.guns.client.event.FirstPersonRenderGunEvent", remap = false)
public abstract class MixinFirstPersonRenderEvent {
}

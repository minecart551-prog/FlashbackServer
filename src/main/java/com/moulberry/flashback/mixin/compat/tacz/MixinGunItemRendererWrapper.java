package com.moulberry.flashback.mixin.compat.tacz;

import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * This mixin previously wrapped applyFirstPersonGunTransform, but that call is inside
 * a lambda in GunItemRendererWrapper.renderFirstPerson, so the @WrapOperation never fires.
 *
 * View bob for TACZ guns is now handled by MixinFirstPersonRenderEvent, which injects
 * directly into FirstPersonRenderGunEvent.applyFirstPersonGunTransform at RETURN.
 */
@IfModLoaded("tacz")
@Pseudo
@Mixin(targets = "com.tacz.guns.client.renderer.item.GunItemRendererWrapper", remap = false)
public abstract class MixinGunItemRendererWrapper {
}

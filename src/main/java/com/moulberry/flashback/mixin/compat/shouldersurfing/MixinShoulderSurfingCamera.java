package com.moulberry.flashback.mixin.compat.shouldersurfing;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.visuals.AccurateEntityPositionHandler;
import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.joml.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shoulder Surfing derives its camera orbit basis from a mouse driven rotation
 * ({@code ShoulderSurfingCamera#renderRotation}), which never follows the recorded player rotation
 * while watching a replay. Flashback forces the recorded rotation at the end of
 * {@code Camera#setup}, but by then Shoulder Surfing has already computed its offset direction
 * using the stale rotation, so the camera offset stays world fixed and the camera appears to spin
 * in place instead of orbiting the player model.
 *
 * <p>Injecting at the head of {@code ShoulderSurfingCamera#setup} happens inside
 * {@code CameraMixin}'s redirect, before both the collision ray trace and the camera
 * {@code move}, so the offset is computed in the correct (recorded) camera space.
 */
@IfModLoaded("shouldersurfing")
@Pseudo
@Mixin(targets = "com.github.exopandora.shouldersurfing.client.ShoulderSurfingCamera", remap = false)
public class MixinShoulderSurfingCamera {

    @Inject(method = "setup", at = @At("HEAD"))
    private void flashback$setup(Camera camera, BlockGetter level, float partialTick, Entity cameraEntity, CallbackInfo ci) {
        if (!Flashback.isInReplay()) {
            return;
        }

        Vector2f rotation = AccurateEntityPositionHandler.getAccurateRotation(cameraEntity, partialTick);
        if (rotation != null) {
            camera.setRotation(rotation.y, rotation.x);
        } else {
            camera.setRotation(cameraEntity.getViewYRot(partialTick), cameraEntity.getViewXRot(partialTick));
        }
    }

}

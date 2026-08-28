package com.moulberry.flashback.mixin.playback;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.visuals.AccurateEntityPositionHandler;
import com.moulberry.flashback.visuals.ViewBobState;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import org.joml.Vector2f;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class MixinCamera {

    @Shadow
    private float eyeHeightOld;

    @Shadow
    public float eyeHeight;

    @Shadow
    protected abstract void setRotation(float f, float g);

    @Shadow
    protected abstract void setPosition(double d, double e, double f);

    @Shadow
    private Entity entity;

    @Inject(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V", ordinal = 0, shift = At.Shift.AFTER))
    public void afterSetPosition(BlockGetter blockGetter, Entity entity, boolean bl, boolean bl2, float partialTick, CallbackInfo ci)  {
        Vector2f rotation = AccurateEntityPositionHandler.getAccurateRotation(entity, partialTick);
        if (rotation != null) {
            this.setRotation(rotation.y, rotation.x);
        }
        Vector3d position = AccurateEntityPositionHandler.getAccuratePosition(entity, partialTick);
        if (position != null) {
            double camY = position.y + Mth.lerp(partialTick, this.eyeHeightOld, this.eyeHeight);

            Player viewPlayer = Flashback.getSpectatingPlayer();
            if (viewPlayer != null && this.entity == viewPlayer) {
                ItemStack mainHand = viewPlayer.getMainHandItem();
                if (!mainHand.isEmpty() && mainHand.getItem().getClass().getName().contains("com.tacz.guns")) {
                    ViewBobState.BobState state = ViewBobState.getState(viewPlayer.getId());
                    if (state != null) {
                        float f = state.walkDist - state.walkDistO;
                        float phase = -(state.walkDist + f * partialTick);
                        float bob = Mth.lerp(partialTick, state.oBob, state.bob);

                        if (Math.abs(bob) > 0.001f) {
                            float sinPhase = Mth.sin(phase * (float) Math.PI);
                            float cosPhase = Mth.cos(phase * (float) Math.PI);

                            float bobX = sinPhase * bob * 0.5F;
                            float bobY = -Math.abs(cosPhase * bob);

                            this.setPosition(
                                position.x + bobX,
                                camY + bobY,
                                position.z
                            );

                            if (rotation != null) {
                                float yaw = rotation.y + sinPhase * bob * 3.0F;
                                float pitch = rotation.x + Math.abs(cosPhase * bob - 0.2F) * bob * 5.0F;
                                this.setRotation(yaw, pitch);
                            }
                            return;
                        }
                    }
                }
            }

            this.setPosition(position.x, camY, position.z);
        }
    }

}

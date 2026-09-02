package com.moulberry.flashback.mixin.playback;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import com.moulberry.flashback.visuals.AccurateEntityPositionHandler;
import com.moulberry.flashback.visuals.ViewBobState;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
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

    @Inject(method = "setup", at = @At("RETURN"))
    public void afterSetup(BlockGetter blockGetter, Entity entity, boolean bl, boolean bl2, float partialTick, CallbackInfo ci) {
        if (!Flashback.isInReplay()) return;

        Vector2f rotation = AccurateEntityPositionHandler.getAccurateRotation(entity, partialTick);
        if (rotation != null) {
            this.setRotation(rotation.y, rotation.x);
        }
        Vector3d position = AccurateEntityPositionHandler.getAccuratePosition(entity, partialTick);
        if (position != null) {
            double camY = position.y + Mth.lerp(partialTick, this.eyeHeightOld, this.eyeHeight);

            Player viewPlayer = Flashback.getSpectatingPlayer();
            if (viewPlayer != null && entity == viewPlayer && Minecraft.getInstance().options.getCameraType().isFirstPerson()) {
                ItemStack mainHand = viewPlayer.getMainHandItem();
                boolean isTaczGun = !mainHand.isEmpty() && mainHand.getItem().getClass().getName().contains("com.tacz.guns");
                if (isTaczGun) {
                    ViewBobState.BobState state = ViewBobState.getState(viewPlayer.getId());
                    if (state == null) {
                        LocalPlayer localPlayer = Minecraft.getInstance().player;
                        if (localPlayer != null) {
                            state = ViewBobState.getState(localPlayer.getId());
                        }
                    }
                    if (state != null) {
                        float f = state.walkDist - state.walkDistO;
                        float phase = -(state.walkDist + f * partialTick);
                        float bob = Mth.lerp(partialTick, state.oBob, state.bob);

                        EditorState editorState = EditorStateManager.getCurrent();
                        if (editorState != null) {
                            bob *= editorState.replayVisuals.viewBobMultiplier;
                        }

                        if (Math.abs(bob) > 0.001f) {
                            float sinPhase = Mth.sin(phase * (float) Math.PI);

                            this.setPosition(
                                position.x,
                                camY,
                                position.z
                            );

                            if (rotation != null) {
                                float yaw = rotation.y;
                                float pitch = rotation.x + sinPhase * bob * 2.0F;
                                this.setRotation(yaw, pitch);
                            }
                            return;
                        }
                    }
                } else {
                    ViewBobState.BobState state = ViewBobState.getState(viewPlayer.getId());
                    if (state == null) {
                        LocalPlayer localPlayer = Minecraft.getInstance().player;
                        if (localPlayer != null) {
                            state = ViewBobState.getState(localPlayer.getId());
                        }
                    }
                    if (state != null) {
                        float f = state.walkDist - state.walkDistO;
                        float phase = -(state.walkDist + f * partialTick);
                        float bob = Mth.lerp(partialTick, state.oBob, state.bob);

                        EditorState editorState = EditorStateManager.getCurrent();
                        if (editorState != null) {
                            bob *= editorState.replayVisuals.viewBobMultiplier;
                        }

                        if (Math.abs(bob) > 0.001f) {
                            float sinPhase = Mth.sin(phase * (float) Math.PI);
                            float cosPhase = Mth.cos(phase * (float) Math.PI);

                            float bobX = sinPhase * bob * 0.25F;
                            float bobY = -Math.abs(cosPhase * bob) * 0.5F;

                            this.setPosition(
                                position.x + bobX,
                                camY + bobY,
                                position.z
                            );

                            if (rotation != null) {
                                float yaw = rotation.y;
                                float pitchFreq = editorState != null ? editorState.replayVisuals.viewBobPitchFrequency : 0.5f;
                                float pitch = rotation.x + Math.abs(Mth.cos(phase * pitchFreq * (float) Math.PI - 0.2F) * bob) * 2.5F;
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

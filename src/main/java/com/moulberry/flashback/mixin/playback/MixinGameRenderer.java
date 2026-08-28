package com.moulberry.flashback.mixin.playback;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.ext.ItemInHandRendererExt;
import com.moulberry.flashback.ext.MinecraftExt;
import com.moulberry.flashback.visuals.AccurateEntityPositionHandler;
import com.moulberry.flashback.visuals.CameraRotation;
import com.moulberry.flashback.visuals.ViewBobState;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import com.mojang.math.Axis;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer {

    @WrapOperation(method = "render", at=@At(value = "FIELD", target = "Lnet/minecraft/client/Options;pauseOnLostFocus:Z"))
    public boolean getPauseOnLostFocus(Options instance, Operation<Boolean> original) {
        if (ReplayUI.isActive() || Flashback.EXPORT_JOB != null) {
            return false;
        }
        return original.call(instance);
    }

    /*
     * Render item in hand for spectators in a replay
     */

    @Shadow
    @Final
    Minecraft minecraft;

    @Shadow @Final private Camera mainCamera;

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;tickRain(Lnet/minecraft/client/Camera;)V"), cancellable = true)
    public void tick(CallbackInfo ci) {
        if (Flashback.isInReplay() && !((MinecraftExt) minecraft).flashback$getReplayTimer().manager.runsNormally()) {
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At("HEAD"))
    public void renderHead(float partialTick, long l, boolean bl, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;

        if (Flashback.RECORDER != null && player != null) {
            Flashback.RECORDER.trackPartialPosition(player, partialTick);
        }

        AccurateEntityPositionHandler.apply(Minecraft.getInstance().level, partialTick);
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V", remap = false, ordinal = 0), cancellable = true)
    public void render_noGui(float f, long l, boolean bl, CallbackInfo ci) {
        if (Flashback.isExporting() && Flashback.EXPORT_JOB.getSettings().noGui()) {
            ci.cancel();
        }
    }

    @WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;getPlayerMode()Lnet/minecraft/world/level/GameType;"))
    public GameType getPlayerMode(MultiPlayerGameMode instance, Operation<GameType> original) {
        if (Flashback.getSpectatingPlayer() != null) {
            return GameType.SURVIVAL;
        }
        return original.call(instance);
    }

    // Wrap bobView to apply spectating player's walking bob at the GameRenderer scope.
    // For TACZ guns, we skip this because TACZ's mulPoseMatrix(transformMatrix)
    // reorients the coordinate system, absorbing any pre-existing translate into
    // the gun's local space where it becomes negligible. Camera-level bob in
    // MixinCamera handles TACZ gun bob instead.
    @WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;bobView(Lcom/mojang/blaze3d/vertex/PoseStack;F)V"))
    public void renderItemInHand_bobView(GameRenderer instance, PoseStack poseStack, float partialTick, Operation<Void> original) {
        Player viewPlayer = Flashback.getSpectatingPlayer();
        if (viewPlayer != null) {
            ItemStack mainHand = viewPlayer.getMainHandItem();
            if (mainHand.isEmpty()) {
                // RemotePlayer inventory not synced — check via KeepingItemRenderer
                mainHand = flashback$getKeepingItem();
            }
            if (!mainHand.isEmpty() && mainHand.getItem().getClass().getName().contains("com.tacz.guns")) {
                // For TACZ guns: apply our custom bob (no horizontal, slight vertical + rotation)
                // Use local player's ViewBobState since the spectated player has no movement data
                ViewBobState.BobState state = ViewBobState.getState(viewPlayer.getId());
                if (state == null) {
                    LocalPlayer localPlayer = Minecraft.getInstance().player;
                    if (localPlayer != null) {
                        state = ViewBobState.getState(localPlayer.getId());
                    }
                }
                if (state == null) return;

                float f = state.walkDist - state.walkDistO;
                float phase = -(state.walkDist + f * partialTick);
                float bob = Mth.lerp(partialTick, state.oBob, state.bob);

                if (Math.abs(bob) < 0.001f) return;

                float sinPhase = Mth.sin(phase * (float) Math.PI);
                float cosPhase = Mth.cos(phase * (float) Math.PI);

                float bobY = -Math.abs(cosPhase * bob) * 0.25F;

                poseStack.translate(0, bobY, 0.0F);
                poseStack.mulPose(Axis.ZP.rotationDegrees(sinPhase * bob * 2.0F));
                poseStack.mulPose(Axis.XP.rotationDegrees(Math.abs(Mth.cos(phase * (float) Math.PI - 0.2F) * bob) * 3.0F));
                return;
            }
        } else {
            original.call(instance, poseStack, partialTick);
        }
    }

    @WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V"))
    public void renderItemInHand_renderHandsWithItems(ItemInHandRenderer instance, float f, PoseStack poseStack, MultiBufferSource.BufferSource bufferSource, LocalPlayer localPlayer, int i, Operation<Void> original) {
        AbstractClientPlayer spectatingPlayer = Flashback.getSpectatingPlayer();
        if (spectatingPlayer != null) {
            Entity entity = this.minecraft.getCameraEntity() == null ? this.minecraft.player : this.minecraft.getCameraEntity();
            float frozenPartialTick = ((MinecraftExt)this.minecraft).flashback$getReplayTimer().manager.isEntityFrozen(entity) ? 1.0f : f;
            ((ItemInHandRendererExt)instance).flashback$renderHandsWithItems(frozenPartialTick, poseStack, bufferSource, spectatingPlayer, i);
        } else {
            original.call(instance, f, poseStack, bufferSource, localPlayer, i);
        }
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V"))
    public void renderLevel_setupCamera(Camera instance, BlockGetter blockGetter, Entity entity, boolean bl, boolean bl2, float f, Operation<Void> original) {
        if (Flashback.isInReplay()) {
            f = ((MinecraftExt)this.minecraft).flashback$getLocalPlayerPartialTick(f);
        }
        original.call(instance, blockGetter, entity, bl, bl2, f);
    }

    @Inject(method = "getNightVisionScale", at = @At("HEAD"), cancellable = true)
    private static void getNightVisionScale(LivingEntity livingEntity, float f, CallbackInfoReturnable<Float> cir) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null && editorState.replayVisuals.overrideNightVision) {
            cir.setReturnValue(1.0f);
        }
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V", shift = At.Shift.AFTER))
    public void renderLevel(float f, long l, PoseStack poseStack, CallbackInfo ci) {
        // TODO astavie: this is horrible and you should feel bad
//        Camera instance = this.mainCamera;
//        var rotation = CameraRotation.modifyViewQuaternion(instance.rotation());
//        var angles = rotation.getEulerAnglesYXZ(new Vector3f());
//        instance.setRotation(-angles.y / 0.017453292F, angles.x / 0.017453292F);
    }

    @Inject(method = "tryTakeScreenshotIfNeeded", at = @At("HEAD"), cancellable = true)
    public void tryTakeScreenshotIfNeeded(CallbackInfo ci) {
        if (Flashback.isInReplay()) {
            ci.cancel();
        }
    }

    @Inject(method = "getFov", at = @At("HEAD"), cancellable = true)
    public void getFov(Camera camera, float f, boolean bl, CallbackInfoReturnable<Double> cir) {
        if (Flashback.isInReplay()) {
            if (!bl) {
                cir.setReturnValue(70.0);
                return;
            }
            EditorState editorState = EditorStateManager.getCurrent();
            if (editorState != null && editorState.replayVisuals.overrideFov) {
                cir.setReturnValue((double) editorState.replayVisuals.overrideFovAmount);
            } else {
                int fov = this.minecraft.options.fov().get().intValue();
                cir.setReturnValue((double) fov);
            }
        }
    }

    @Unique
    private static java.lang.reflect.Method flashback$getCurrentItemMethod;
    @Unique
    private static java.lang.reflect.Method flashback$getRendererMethod;

    @Unique
    private static ItemStack flashback$getKeepingItem() {
        try {
            if (flashback$getRendererMethod == null) {
                Class<?> kirClass = Class.forName("com.tacz.guns.api.client.other.KeepingItemRenderer");
                flashback$getRendererMethod = kirClass.getMethod("getRenderer");
                flashback$getCurrentItemMethod = kirClass.getMethod("getCurrentItem");
            }
            Object renderer = flashback$getRendererMethod.invoke(null);
            if (renderer == null) return ItemStack.EMPTY;
            return (ItemStack) flashback$getCurrentItemMethod.invoke(renderer);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

}

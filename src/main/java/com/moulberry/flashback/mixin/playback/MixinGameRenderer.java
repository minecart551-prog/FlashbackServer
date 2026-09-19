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
import com.moulberry.flashback.visuals.ViewBobState;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameType;
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
        if (Flashback.getSpectatingPlayer() != null && this.minecraft.options.getCameraType().isFirstPerson()) {
            return GameType.SURVIVAL;
        }
        return original.call(instance);
    }

    // Wrap bobView to prevent vanilla bob for TACZ guns during replay.
    // During normal gameplay, TACZ cancels bobView via cancelItemInHandViewBobbing.
    // We must do the same here — TACZ handles all gun positioning internally through
    // its own renderFirstPerson/applyFirstPersonGunTransform pipeline. Adding any
    // custom bob (translate, ZP roll, XP rotation) here would conflict with TACZ's own
    // transforms and cause the gun to shift left.
    //
    // Walk bob for TACZ guns is applied inside MixinTaczRightHandRender/MixinTaczLeftHandRender,
    // directly on the poseStack that TACZ uses for gun model rendering, where it actually
    // affects the gun and arm positioning.
    @WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;bobView(Lcom/mojang/blaze3d/vertex/PoseStack;F)V"))
    public void renderItemInHand_bobView(GameRenderer instance, PoseStack poseStack, float partialTick, Operation<Void> original) {
        if (!this.minecraft.options.getCameraType().isFirstPerson()) {
            original.call(instance, poseStack, partialTick);
            return;
        }
        Player viewPlayer = Flashback.getSpectatingPlayer();
        if (viewPlayer != null) {
            ItemStack mainHand = viewPlayer.getMainHandItem();
            if (!mainHand.isEmpty() && mainHand.getItem().getClass().getName().contains("com.tacz.guns")) {
                // Cancel vanilla bobView for TACZ guns. Walk bob is applied inside
                // MixinTaczRightHandRender/MixinTaczLeftHandRender instead.
                return;
            }
        }
        original.call(instance, poseStack, partialTick);
    }

    @WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V"))
    public void renderItemInHand_renderHandsWithItems(ItemInHandRenderer instance, float f, PoseStack poseStack, MultiBufferSource.BufferSource bufferSource, LocalPlayer localPlayer, int i, Operation<Void> original) {
        AbstractClientPlayer spectatingPlayer = Flashback.getSpectatingPlayer();
        if (spectatingPlayer != null && this.minecraft.options.getCameraType().isFirstPerson()) {
            Entity entity = this.minecraft.getCameraEntity() == null ? this.minecraft.player : this.minecraft.getCameraEntity();
            float frozenPartialTick = ((MinecraftExt)this.minecraft).flashback$getReplayTimer().manager.isEntityFrozen(entity) ? 1.0f : f;

            ItemStack mainHand = spectatingPlayer.getMainHandItem();
            boolean isTaczGun = !mainHand.isEmpty() && mainHand.getItem().getClass().getName().contains("com.tacz.guns");

            if (isTaczGun) {
                // TACZ cancels vanilla bobView() but does NOT replace the Y translation bob.
                // It only handles rotation bob (via xBob/yBob) and bone sway animations.
                // We apply the bob Y translation directly to the poseStack, which flows through
                // to bedrockGunModel.render(). Since our translation is applied first on the
                // poseStack, it becomes the OUTERMOST transform — applied in screen space
                // after all of TACZ's internal transforms (rotations, positioning, etc.).
                ViewBobState.BobState state = ViewBobState.getState(spectatingPlayer.getId());
                if (state == null) {
                    LocalPlayer localPl = Minecraft.getInstance().player;
                    if (localPl != null) {
                        state = ViewBobState.getState(localPl.getId());
                    }
                }

                boolean hasBob = false;
                if (state != null) {
                    float walkDelta = state.walkDist - state.walkDistO;
                    float phase = -(state.walkDist + walkDelta * frozenPartialTick);
                    float bob = Mth.lerp(frozenPartialTick, state.oBob, state.bob);

                    EditorState editorState = EditorStateManager.getCurrent();
                    if (editorState != null) {
                        bob *= editorState.replayVisuals.viewBobMultiplier;
                    }

                    if (Math.abs(bob) > 0.001f) {
                        float cosPhase = Mth.cos(phase * (float) Math.PI);
                        float bobY = -Math.abs(cosPhase * bob) * 0.25F * 0.5F;

                        poseStack.pushPose();
                        poseStack.translate(0, bobY, 0);
                        hasBob = true;
                    }
                }

                try {
                    ((ItemInHandRendererExt)instance).flashback$renderHandsWithItems(frozenPartialTick, poseStack, bufferSource, spectatingPlayer, i);
                } finally {
                    if (hasBob) {
                        poseStack.popPose();
                    }
                }
            } else {
                ((ItemInHandRendererExt)instance).flashback$renderHandsWithItems(frozenPartialTick, poseStack, bufferSource, spectatingPlayer, i);
            }
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
        if (!Flashback.isInReplay()) return;
        if (!this.minecraft.options.getCameraType().isFirstPerson()) return;

        Player viewPlayer = Flashback.getSpectatingPlayer();
        if (viewPlayer == null) return;

        ViewBobState.BobState state = ViewBobState.getState(viewPlayer.getId());
        if (state == null) {
            LocalPlayer localPlayer = Minecraft.getInstance().player;
            if (localPlayer != null) {
                state = ViewBobState.getState(localPlayer.getId());
            }
        }
        if (state == null) return;

        float walkDelta = state.walkDist - state.walkDistO;
        float phase = -(state.walkDist + walkDelta * f);
        float bob = Mth.lerp(f, state.oBob, state.bob);

        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null) {
            bob *= editorState.replayVisuals.viewBobMultiplier;
        }

        if (Math.abs(bob) < 0.001f) return;

        float sinPhase = Mth.sin(phase * (float) Math.PI);

        ItemStack mainHand = viewPlayer.getMainHandItem();
        if (!mainHand.isEmpty() && mainHand.getItem().getClass().getName().contains("com.tacz.guns")) {
            poseStack.mulPose(Axis.ZP.rotationDegrees(sinPhase * bob * 2.0F));
        }
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

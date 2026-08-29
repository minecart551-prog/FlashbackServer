package com.moulberry.flashback.mixin.compat.tacz;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.moulberry.flashback.Flashback;
import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * During replay, TACZ's RightHandRender calls renderFirstPersonArm(LocalPlayer,...).
 * The view player is NOT a LocalPlayer, so it falls back to the empty-handed local player.
 * This mixin intercepts and renders the arm using the spectating player instead.
 */
@IfModLoaded("tacz")
@Pseudo
@Mixin(targets = "com.tacz.guns.client.model.functional.RightHandRender", remap = false)
public class MixinTaczRightHandRender {

    private static final org.slf4j.Logger HAND_LOGGER = org.slf4j.LoggerFactory.getLogger("flashback-hand-debug");
    private static int handDebugCounter = 0;

    @Unique
    private static AbstractClientPlayer flashback$getTargetPlayer() {
        try {
            Class<?> helper = Class.forName("com.tacz.guns.client.compat.RecordingCompatHelper");
            Method getViewPlayer = helper.getMethod("getViewPlayer");
            Object viewPlayer = getViewPlayer.invoke(null);
            if (viewPlayer instanceof AbstractClientPlayer acp) {
                return acp;
            }
        } catch (Exception ignored) {}
        return Minecraft.getInstance().player;
    }

    @Unique
    private static void flashback$renderArm(AbstractClientPlayer player, HumanoidArm hand, PoseStack matrixStack, int combinedLight) {
        var renderManager = Minecraft.getInstance().getEntityRenderDispatcher();
        var renderer = (PlayerRenderer) renderManager.getRenderer(player);
        var buffer = Minecraft.getInstance().renderBuffers().bufferSource();
        if (hand == HumanoidArm.RIGHT) {
            renderer.renderRightHand(matrixStack, buffer, combinedLight, player);
        } else {
            renderer.renderLeftHand(matrixStack, buffer, combinedLight, player);
        }
        buffer.endBatch();
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void flashback$render(PoseStack poseStack, com.mojang.blaze3d.vertex.VertexConsumer vertexBuffer,
            ItemDisplayContext transformType, int light, int overlay, CallbackInfo ci) {
        if (!Flashback.isInReplay()) return;
        if (!transformType.firstPerson()) return;

        try {
            Field f = this.getClass().getDeclaredField("bedrockGunModel");
            f.setAccessible(true);
            Object bedrockGunModel = f.get(this);
            if (bedrockGunModel == null) return;

            Method getRenderHand = bedrockGunModel.getClass().getMethod("getRenderHand");
            if (!(boolean) getRenderHand.invoke(bedrockGunModel)) return;

            // Find delegateRender method
            Method delegateMethod = null;
            for (Method m : bedrockGunModel.getClass().getMethods()) {
                if (m.getName().equals("delegateRender") && m.getParameterCount() == 1) {
                    delegateMethod = m;
                    break;
                }
            }
            if (delegateMethod == null) return;

            // Mimic TACZ's own code exactly: mulPose 180, save matrices, delegate with fresh PoseStack
            ci.cancel();
            poseStack.mulPose(Axis.ZP.rotationDegrees(180f));
            Matrix3f normal = new Matrix3f(poseStack.last().normal());
            Matrix4f pose = new Matrix4f(poseStack.last().pose());
            AbstractClientPlayer player = flashback$getTargetPlayer();
            int finalLight = light;

            if (++handDebugCounter % 30 == 0) {
                net.minecraft.world.entity.player.Player spec = com.moulberry.flashback.Flashback.getSpectatingPlayer();
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                net.minecraft.world.entity.Entity camEntity = mc.cameraEntity;
                float specYaw = spec != null ? spec.getYRot() : 0f;
                float specPitch = spec != null ? spec.getXRot() : 0f;
                float specYRotO = spec != null ? ((LivingEntity) spec).yRotO : 0f;
                float specXRotO = spec != null ? ((LivingEntity) spec).xRotO : 0f;
                float camYaw = camEntity != null ? camEntity.getYRot() : 0f;
                float camPitch = camEntity != null ? camEntity.getXRot() : 0f;
                // Get xBob/yBob via RemotePlayerExt
                float xBob = 0f, yBob = 0f, xBobO = 0f, yBobO = 0f;
                if (spec instanceof com.moulberry.flashback.ext.RemotePlayerExt rpe) {
                    xBob = rpe.flashback$getXBob(1.0f);
                    yBob = rpe.flashback$getYBob(1.0f);
                    xBobO = rpe.flashback$getXBob(0.0f);
                    yBobO = rpe.flashback$getYBob(0.0f);
                }
                // poseStack matrix decomposition: extract yaw from the matrix
                float matrixYaw = (float) Math.toDegrees(Math.atan2(pose.m02(), pose.m22()));
                float matrixPitch = (float) Math.toDegrees(Math.asin(-pose.m12()));
                HAND_LOGGER.info("[HAND-R-DBG] player={}({}) camEntity={}({}) specYaw={} specPitch={} specYawO={} specPitchO={} camYaw={} camPitch={} xBob={} yBob={} xBobO={} yBobO={} matYaw={} matPitch={}",
                        player != null ? player.getClass().getSimpleName() : "null",
                        player != null ? player.getId() : -1,
                        camEntity != null ? camEntity.getClass().getSimpleName() : "null",
                        camEntity != null ? camEntity.getId() : -1,
                        String.format("%.2f", specYaw), String.format("%.2f", specPitch),
                        String.format("%.2f", specYRotO), String.format("%.2f", specXRotO),
                        String.format("%.2f", camYaw), String.format("%.2f", camPitch),
                        String.format("%.4f", xBob), String.format("%.4f", yBob),
                        String.format("%.4f", xBobO), String.format("%.4f", yBobO),
                        String.format("%.2f", matrixYaw), String.format("%.2f", matrixPitch));
            }

            Class<?> funcType = delegateMethod.getParameterTypes()[0];
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                funcType.getClassLoader(),
                new Class<?>[]{ funcType },
                (p, method, args) -> {
                    PoseStack poseStack2 = new PoseStack();
                    poseStack2.last().normal().mul(normal);
                    poseStack2.last().pose().mul(pose);
                    flashback$renderArm(player, HumanoidArm.RIGHT, poseStack2, finalLight);
                    Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
                    return null;
                }
            );
            delegateMethod.invoke(bedrockGunModel, proxy);
        } catch (Exception ignored) {}
    }
}

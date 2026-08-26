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

package com.moulberry.flashback.mixin.compat.tacz;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.moulberry.flashback.Flashback;
import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

@IfModLoaded("tacz")
@Pseudo
@Mixin(targets = "com.tacz.guns.client.model.functional.LeftHandRender", remap = false)
public class MixinTaczLeftHandRender {

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
            net.minecraft.world.item.ItemDisplayContext transformType, int light, int overlay, CallbackInfo ci) {
        if (!Flashback.isInReplay()) return;
        if (!transformType.firstPerson()) return;

        ci.cancel();

        poseStack.mulPose(Axis.ZP.rotationDegrees(180f));

        try {
            Field f = this.getClass().getDeclaredField("bedrockGunModel");
            f.setAccessible(true);
            Object bedrockGunModel = f.get(this);
            if (bedrockGunModel == null) return;

            Method getRenderHand = bedrockGunModel.getClass().getMethod("getRenderHand");
            if (!(boolean) getRenderHand.invoke(bedrockGunModel)) return;

            Method delegateMethod = null;
            for (Method m : bedrockGunModel.getClass().getMethods()) {
                if (m.getName().equals("delegateRender") && m.getParameterCount() == 1) {
                    delegateMethod = m;
                    break;
                }
            }
            if (delegateMethod == null) return;

            Class<?> funcType = delegateMethod.getParameterTypes()[0];
            AbstractClientPlayer player = flashback$getTargetPlayer();
            org.joml.Matrix3f normal = new org.joml.Matrix3f(poseStack.last().normal());
            org.joml.Matrix4f pose = new org.joml.Matrix4f(poseStack.last().pose());
            int finalLight = light;

            Object proxy = Proxy.newProxyInstance(
                funcType.getClassLoader(),
                new Class<?>[]{ funcType },
                (p, method, args) -> {
                    PoseStack poseStack2 = new PoseStack();
                    poseStack2.last().normal().mul(normal);
                    poseStack2.last().pose().mul(pose);
                    flashback$renderArm(player, HumanoidArm.LEFT, poseStack2, finalLight);
                    Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
                    return null;
                }
            );
            delegateMethod.invoke(bedrockGunModel, proxy);
        } catch (Exception ignored) {}
    }
}

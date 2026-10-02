package com.moulberry.flashback.mixin.playback;

import com.google.common.base.MoreObjects;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.ext.ItemInHandRendererExt;
import com.moulberry.flashback.ext.RemotePlayerExt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(ItemInHandRenderer.class)
public abstract class MixinItemInHandRenderer implements ItemInHandRendererExt {

    @Shadow
    private float oMainHandHeight;

    @Shadow
    private float mainHandHeight;

    @Shadow
    private float oOffHandHeight;

    @Shadow
    private float offHandHeight;

    @Shadow
    protected abstract void renderArmWithItem(AbstractClientPlayer abstractClientPlayer, float f, float g, InteractionHand interactionHand, float h, ItemStack itemStack, float i, PoseStack poseStack, MultiBufferSource multiBufferSource, int j);

    @Shadow
    private ItemStack mainHandItem;

    @Shadow
    private ItemStack offHandItem;

    @Shadow
    private static boolean isChargedCrossbow(ItemStack itemStack) {
        return false;
    }

    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void flashback$cancelThirdPersonArmRender(AbstractClientPlayer player, float f, float g, InteractionHand hand, float h, ItemStack stack, float i, PoseStack poseStack, MultiBufferSource buffer, int light, CallbackInfo ci) {
        if (!Flashback.isInReplay()) return;
        if (!Minecraft.getInstance().options.getCameraType().isFirstPerson()) {
            ci.cancel();
        }
    }

    @Unique
    private static final int RENDER_MAIN_HAND = 1;
    @Unique
    private static final int RENDER_OFF_HAND = 2;
    @Unique
    private static final int RENDER_BOTH_HANDS = RENDER_MAIN_HAND | RENDER_OFF_HAND;

    @Unique
    private static int evaluateWhichHandsToRender(AbstractClientPlayer player) {
        ItemStack mainStack = player.getMainHandItem();
        ItemStack offStack = player.getOffhandItem();
        boolean isHoldingBow = mainStack.is(Items.BOW) || offStack.is(Items.BOW);
        boolean isHoldingCrossbow = mainStack.is(Items.CROSSBOW) || offStack.is(Items.CROSSBOW);
        if (!isHoldingBow && !isHoldingCrossbow) {
            return RENDER_BOTH_HANDS;
        }
        if (player.isUsingItem()) {
            ItemStack useStack = player.getUseItem();
            InteractionHand interactionHand = player.getUsedItemHand();
            if (!useStack.is(Items.BOW) && !useStack.is(Items.CROSSBOW)) {
                return interactionHand == InteractionHand.MAIN_HAND && isChargedCrossbow(player.getOffhandItem()) ? RENDER_MAIN_HAND : RENDER_BOTH_HANDS;
            } else {
                return interactionHand == InteractionHand.MAIN_HAND ? RENDER_MAIN_HAND : RENDER_OFF_HAND;
            }
        }
        if (isChargedCrossbow(mainStack)) {
            return RENDER_MAIN_HAND;
        }
        return RENDER_BOTH_HANDS;
    }

    @Override
    public void flashback$renderHandsWithItems(float partialTick, PoseStack poseStack, MultiBufferSource.BufferSource bufferSource, AbstractClientPlayer clientPlayer, int i) {
        float g = clientPlayer.getAttackAnim(partialTick);
        InteractionHand interactionHand = MoreObjects.firstNonNull(clientPlayer.swingingArm, InteractionHand.MAIN_HAND);
        float viewXRot = Mth.lerp(partialTick, clientPlayer.xRotO, clientPlayer.getXRot());
        float viewYRot = Mth.lerp(partialTick, clientPlayer.yRotO, clientPlayer.getYRot());
        int handRenderSelection = evaluateWhichHandsToRender(clientPlayer);

        boolean isTaczGun = !this.mainHandItem.isEmpty() && this.mainHandItem.getItem().getClass().getName().contains("com.tacz.guns");

        poseStack.pushPose();

        // Apply vanilla rotation bob: (viewRot - walkBob) * 0.1
        // TACZ applies its own (viewRot - bob) * -0.1, so skip to avoid canceling out.
        if (!isTaczGun) {
            float xBob;
            float yBob;
            if (clientPlayer instanceof RemotePlayerExt ext) {
                xBob = ext.flashback$getXBob(partialTick);
                yBob = ext.flashback$getYBob(partialTick);
            } else if (clientPlayer instanceof LocalPlayer localPlayer) {
                xBob = Mth.lerp(partialTick, localPlayer.xBobO, localPlayer.xBob);
                yBob = Mth.lerp(partialTick, localPlayer.yBobO, localPlayer.yBob);
            } else {
                xBob = 0f;
                yBob = 0f;
            }
            poseStack.mulPose(Axis.XP.rotationDegrees((viewXRot - xBob) * 0.1f));
            poseStack.mulPose(Axis.YP.rotationDegrees((viewYRot - yBob) * 0.1f));
        }

        if ((handRenderSelection & RENDER_MAIN_HAND) != 0) {
            float l = interactionHand == InteractionHand.MAIN_HAND ? g : 0.0f;
            float m = 1.0f - Mth.lerp(partialTick, this.oMainHandHeight, this.mainHandHeight);
            renderArmWithItem(clientPlayer, partialTick, viewXRot, InteractionHand.MAIN_HAND, l, this.mainHandItem, m, poseStack, bufferSource, i);
        }
        if ((handRenderSelection & RENDER_OFF_HAND) != 0) {
            float l = interactionHand == InteractionHand.OFF_HAND ? g : 0.0f;
            float m = 1.0f - Mth.lerp(partialTick, this.oOffHandHeight, this.offHandHeight);
            renderArmWithItem(clientPlayer, partialTick, viewXRot, InteractionHand.OFF_HAND, l, this.offHandItem, m, poseStack, bufferSource, i);
        }

        poseStack.popPose();
        bufferSource.endBatch();
    }

    @Unique
    private UUID lastSpectatingPlayer = null;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    public void tick(CallbackInfo ci) {
        AbstractClientPlayer spectatingPlayer = Flashback.getSpectatingPlayer();
        if (spectatingPlayer == null) {
            this.lastSpectatingPlayer = null;
        } else {
            ItemStack newMainHandItem = spectatingPlayer.getMainHandItem();
            ItemStack newOffHandItem = spectatingPlayer.getOffhandItem();

            if (!spectatingPlayer.getUUID().equals(lastSpectatingPlayer)) {
                this.lastSpectatingPlayer = spectatingPlayer.getUUID();
                this.mainHandItem = newMainHandItem;
                this.offHandItem = newOffHandItem;
                this.oMainHandHeight = this.mainHandHeight = 1.0f;
                this.oOffHandHeight = this.offHandHeight = 1.0f;
                ci.cancel();
                return;
            }

            this.oMainHandHeight = this.mainHandHeight;
            this.oOffHandHeight = this.offHandHeight;
            if (ItemStack.matches(this.mainHandItem, newMainHandItem)) {
                this.mainHandItem = newMainHandItem;
            }
            if (ItemStack.matches(this.offHandItem, newOffHandItem)) {
                this.offHandItem = newOffHandItem;
            }
            float str = spectatingPlayer.getAttackStrengthScale(1.0f);
            this.mainHandHeight += Mth.clamp((this.mainHandItem == newMainHandItem ? str * str * str : 0.0f) - this.mainHandHeight, -0.4f, 0.4f);
            this.offHandHeight += Mth.clamp((float)(this.offHandItem == newOffHandItem ? 1 : 0) - this.offHandHeight, -0.4f, 0.4f);
            if (this.mainHandHeight < 0.1f) {
                this.mainHandItem = newMainHandItem;
            }
            if (this.offHandHeight < 0.1f) {
                this.offHandItem = newOffHandItem;
            }
            ci.cancel();
        }
    }

}
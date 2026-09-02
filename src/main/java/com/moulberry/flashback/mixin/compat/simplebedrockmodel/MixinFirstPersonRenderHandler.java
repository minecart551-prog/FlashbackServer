package com.moulberry.flashback.mixin.compat.simplebedrockmodel;

import cn.sh1rocu.simplebedrockmodel.api.event.RenderHandEvent;
import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import com.moulberry.flashback.Flashback;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels SimpleBedrockModel's RenderHandEvent in third-person during replay.
 *
 * <p>Without this, when in third-person spectating during replay:
 * <ol>
 *   <li>{@code ItemInHandLayer.render()} calls {@code renderArmWithItem()} for the local player</li>
 *   <li>SimpleBedrockModel's {@code sbm$renderHand} fires {@code RenderHandEvent}</li>
 *   <li>TACZ's {@code FirstPersonRenderEvent.onRenderHand} renders the gun as a GUI overlay</li>
 * </ol>
 * Since this is rendered through the hand rendering pipeline, F1 (hideGui) hides it,
 * confirming it goes through the item-in-hand path rather than entity rendering.
 */
@IfModLoaded("simplebedrockmodel")
@Pseudo
@Mixin(targets = "com.github.mcmodderanchor.simplebedrockmodel.v1.client.handler.FirstPersonRenderHandler", remap = false)
public class MixinFirstPersonRenderHandler {

    @Inject(method = "onRenderHand(Lcn/sh1rocu/simplebedrockmodel/api/event/RenderHandEvent;)V", at = @At("HEAD"), cancellable = true)
    private static void flashback$cancelThirdPersonGunRender(RenderHandEvent event, CallbackInfo ci) {
        if (!Flashback.isInReplay()) return;
        if (!Minecraft.getInstance().options.getCameraType().isFirstPerson()) {
            event.setCanceled(true);
        }
    }
}

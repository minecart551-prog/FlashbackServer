package com.moulberry.flashback.mixin.visuals;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntityRenderer.class)
public class MixinLivingEntityRendererRender {

    @Inject(method = "getWhiteOverlayProgress", at = @At("HEAD"), cancellable = true)
    public void getWhiteOverlayProgress(LivingEntity livingEntity, CallbackInfoReturnable<Float> cir) {
        if (Flashback.isInReplay()) {
            ReplayServer replayServer = Flashback.getReplayServer();
            if (replayServer != null && livingEntity.getId() == replayServer.getLocalPlayerId()) {
                // Prevent the spectator ghost overlay from making the recorded player body
                // semi-transparent when viewing in third person or free camera during replay
                cir.setReturnValue(0.0f);
            }
        }
    }

}
package com.moulberry.flashback.mixin.compat.tacz;

import com.moulberry.flashback.Flashback;
import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * During first-person replay, sounds should play as relative (first-person) sounds.
 * TACZ's isLocalPlayer() checks entity == Minecraft.getInstance().player, which is false
 * for the spectating RemotePlayer. This mixin makes it also return true when the entity
 * is the spectating player in first-person view.
 */
@IfModLoaded("tacz")
@Pseudo
@Mixin(targets = "com.tacz.guns.client.sound.SoundPlayManager", remap = false)
public class MixinTaczSoundPlayManager {

    @Inject(method = "isLocalPlayer", at = @At("HEAD"), cancellable = true)
    private static void flashback$isLocalPlayer(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (Flashback.isInReplay()
                && net.minecraft.client.Minecraft.getInstance().options.getCameraType().isFirstPerson()) {
            var spectating = Flashback.getSpectatingPlayer();
            if (spectating != null && entity == spectating) {
                cir.setReturnValue(true);
            }
        }
    }
}

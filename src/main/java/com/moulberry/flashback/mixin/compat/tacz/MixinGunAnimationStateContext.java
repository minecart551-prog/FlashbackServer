package com.moulberry.flashback.mixin.compat.tacz;

import com.moulberry.flashback.Flashback;
import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;

@Pseudo
@IfModLoaded("tacz")
@Mixin(targets = "com.tacz.guns.client.animation.statemachine.GunAnimationStateContext", remap = false)
public class MixinGunAnimationStateContext {

    @Inject(method = "shouldSlide", at = @At("HEAD"), cancellable = true)
    private void flashback$preventNullGunDataSlide(CallbackInfoReturnable<Boolean> cir) {
        if (Flashback.isInReplay()) {
            try {
                Field field = Class.forName("com.tacz.guns.client.animation.statemachine.GunAnimationStateContext")
                        .getDeclaredField("gunData");
                field.setAccessible(true);
                Object gunData = field.get(this);
                if (gunData == null) {
                    cir.setReturnValue(false);
                }
            } catch (Exception e) {
                cir.setReturnValue(false);
            }
        }
    }
}

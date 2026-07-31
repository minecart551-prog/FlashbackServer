package com.moulberry.flashback.mixin.compat.cyberware;

import com.moulberry.flashback.Flashback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.connor.cyberwarecore.CyberwareConfigLoader", remap = false)
public class MixinCyberwareConfigLoader {

    @Inject(method = "apply", at = @At("HEAD"), require = 0, cancellable = true)
    public void flashback$apply(CallbackInfo ci) {
        if (Flashback.isLoadingReplay) {
            ci.cancel();
        }
    }

}

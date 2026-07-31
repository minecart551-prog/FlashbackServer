package com.moulberry.flashback.mixin.compat.mca;

import com.moulberry.flashback.Flashback;
import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@IfModLoaded("mca")
@Pseudo
@Mixin(targets = {"net.mca.client.render.CribEntityRenderer", "fabric.net.mca.client.render.CribEntityRenderer"}, remap = false)
public class MixinCribEntityRenderer {

    @Inject(method = "render", at = @At("HEAD"), require = 0, cancellable = true)
    public void flashback$render(CallbackInfo ci) {
        if (Flashback.isInReplay()) {
            ci.cancel();
        }
    }

}

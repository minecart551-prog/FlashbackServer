package com.moulberry.flashback.mixin.compat.iris;

import com.moulberry.flashback.Flashback;
import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import net.irisshaders.iris.pathways.HandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@IfModLoaded("iris")
@Pseudo
@Mixin(value = HandRenderer.class, remap = false)
public class MixinIrisHandRenderer {

    @Inject(method = "canRender", at = @At("HEAD"), require = 0, cancellable = true)
    public void flashback$canRender(net.minecraft.client.Camera camera, net.minecraft.client.renderer.GameRenderer gameRenderer,
                                    CallbackInfoReturnable<Boolean> cir) {
        if (Flashback.isInReplay() && Flashback.getSpectatingPlayer() == null) {
            cir.setReturnValue(false);
        }
    }

}

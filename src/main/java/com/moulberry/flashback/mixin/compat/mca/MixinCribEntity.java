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
@Mixin(targets = {"net.mca.entity.CribEntity", "fabric.net.mca.entity.CribEntity"}, remap = false)
public class MixinCribEntity {

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    public void flashback$init(CallbackInfo ci) {
        if (Flashback.isInReplay()) {
            ((net.minecraft.world.entity.Entity) (Object) this).discard();
        }
    }

}

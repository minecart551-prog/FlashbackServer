package com.moulberry.flashback.mixin.compat.mca;

import com.moulberry.flashback.Flashback;
import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@IfModLoaded("mca")
@Pseudo
@Mixin(targets = {"net.mca.server.ServerInteractionManager", "fabric.net.mca.server.ServerInteractionManager"}, remap = false)
public class MixinMCAInteractionManager {

    @Inject(method = "onPlayerJoin", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    public void flashback$onPlayerJoin(ServerPlayer player, CallbackInfo ci) {
        if (Flashback.isInReplay()) {
            ci.cancel();
        }
    }

}

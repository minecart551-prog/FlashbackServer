package com.moulberry.flashback.mixin.compat.mca;

import com.moulberry.flashback.Flashback;
import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@IfModLoaded("mca")
@Mixin(value = PlayerRenderer.class, priority = 500)
public class MixinPlayerRenderer {

    @Shadow
    private PlayerModel<?> model;

    @Unique
    private EntityRendererProvider.Context flashback$context;
    @Unique
    private boolean flashback$slim;

    @Inject(method = "<init>", at = @At("HEAD"))
    public void flashback$capture(EntityRendererProvider.Context context, boolean slim, CallbackInfo ci) {
        this.flashback$context = context;
        this.flashback$slim = slim;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    public void flashback$restore(CallbackInfo ci) {
        if (Flashback.isInReplay()) {
            ModelPart modelPart = this.flashback$context.bakeLayer(
                this.flashback$slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER
            );
            this.model = new PlayerModel<>(modelPart, this.flashback$slim);
        }
    }

}

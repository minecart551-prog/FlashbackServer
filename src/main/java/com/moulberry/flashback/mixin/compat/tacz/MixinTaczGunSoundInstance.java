package com.moulberry.flashback.mixin.compat.tacz;

import com.moulberry.flashback.Flashback;
import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TACZ attenuates gun sound volume by squaring it against the distance to
 * Minecraft.getInstance().player. In a replay that player is the viewer's own
 * free camera, which stays where the replay started while the camera follows the
 * recorded player — so the measured distance keeps growing and every shot is
 * squared down to silence. Re-attenuate against the camera entity instead, which
 * is where vanilla's sound listener actually sits.
 */
@IfModLoaded("tacz")
@Pseudo
@Mixin(targets = "com.tacz.guns.client.sound.GunSoundInstance")
public class MixinTaczGunSoundInstance {

    @Inject(
            method = "<init>(Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFLnet/minecraft/world/entity/Entity;ILnet/minecraft/resources/ResourceLocation;ZZ)V",
            at = @At("RETURN")
    )
    private void flashback$attenuateFromCamera(SoundEvent soundEvent, SoundSource source,
                                                float volume, float pitch, Entity entity, int soundDistance,
                                                ResourceLocation registryName, boolean mono, boolean relative,
                                                CallbackInfo ci) {
        if (relative || soundDistance <= 0) return;
        if (!Flashback.isInReplay()) return;

        Entity listener = Minecraft.getInstance().cameraEntity;
        if (listener == null) return;

        float distance = (float) Math.sqrt(listener.distanceToSqr(entity.getX(), entity.getY(), entity.getZ()));
        float attenuated = volume * (1.0F - Math.min(1.0F, distance / soundDistance));
        ((AbstractSoundInstanceAccessor) (Object) this).flashback$setVolume(attenuated * attenuated);
    }
}

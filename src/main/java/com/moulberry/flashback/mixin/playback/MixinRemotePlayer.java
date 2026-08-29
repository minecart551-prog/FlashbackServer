package com.moulberry.flashback.mixin.playback;

import com.mojang.authlib.GameProfile;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.ext.RemotePlayerExt;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RemotePlayer.class)
public class MixinRemotePlayer extends AbstractClientPlayer implements RemotePlayerExt {

    @Unique
    private boolean wasSwinging = false;

    @Unique
    private float xBobO = 0.0f;
    @Unique
    private float xBob = 0.0f;
    @Unique
    private float yBobO = 0.0f;
    @Unique
    private float yBob = 0.0f;
    @Unique
    private Vec3 flashback$lastTickPos = null;

    @Unique
    private float flashback$lastTickSpeed = 0.0f;

    private MixinRemotePlayer(ClientLevel clientLevel, GameProfile gameProfile) {
        super(clientLevel, gameProfile);
    }

    @Inject(method = "aiStep", at = @At("RETURN"))
    public void aiStep(CallbackInfo ci) {
        if (Flashback.isInReplay()) {
            if (!this.wasSwinging && this.swinging) {
                this.resetAttackStrengthTicker();
            }
            this.wasSwinging = this.swinging;

            // xBob/yBob: rotation bob (view tilt from looking around)
            // Vanilla LocalPlayer.aiStep() uses: xBob += Mth.wrapDegrees(xRot - xBob) * 0.5f
            // We don't use Mth.wrapDegrees because our yaw values from AccurateEntityPositionHandler
            // can cross the ±180° boundary abruptly (replay interpolation), and wrapDegrees would
            // push yBob the wrong way around the circle, trapping it on the wrong side.
            // When the raw difference exceeds 180° (wrap-around), we snap immediately
            // instead of interpolating, which prevents the large residual twitch.
            this.xBobO = this.xBob;
            float xDelta = this.getXRot() - this.xBob;
            if (Math.abs(xDelta) > 180f) {
                this.xBobO = this.getXRot();
                this.xBob = this.getXRot();
            } else {
                this.xBob += xDelta * 0.5f;
            }
            this.yBobO = this.yBob;
            float yDelta = this.getYRot() - this.yBob;
            if (Math.abs(yDelta) > 180f) {
                this.yBobO = this.getYRot();
                this.yBob = this.getYRot();
            } else {
                this.yBob += yDelta * 0.5f;
            }

            // walk phase bob: RemotePlayer.aiStep() does NOT call super.aiStep(),
            // so we must manually update oBob/bob using the vanilla formula.
            // Vanilla: oBob = bob; bob = 0; ... later bob += (min(speed, hDist) - bob) * 0.4
            this.oBob = this.bob;
            this.bob = 0.0f;
            float speed = Math.min((float) this.getAttributeValue(Attributes.MOVEMENT_SPEED),
                    this.flashback$lastTickSpeed);
            this.bob += (speed - this.bob) * 0.4f;
        }
    }

    @Inject(method = "tick", at = @At("RETURN"))
    public void tick(CallbackInfo ci) {
        if (Flashback.isInReplay()) {
            // Vanilla only updates xRotO/yRotO for LocalPlayer (via turn()).
            // For RemotePlayer, these are never updated, causing getViewXRot(partialTick)
            // to lerp from a stale value — making the gun point in the wrong direction.
            this.xRotO = this.getXRot();
            this.yRotO = this.getYRot();

            if (this.flashback$lastTickPos != null) {
                double dx = this.position().x - this.flashback$lastTickPos.x;
                double dy = this.position().y - this.flashback$lastTickPos.y;
                double dz = this.position().z - this.flashback$lastTickPos.z;
                float dist = (float) Math.sqrt(dx * dx + dz * dz);

                // Vanilla: oWalkDist = walkDist happens at the start of Entity.aiStep(),
                // but RemotePlayer.aiStep() doesn't call super, so oWalkDist is never set.
                // TACZ's GunAnimationStateContext.getWalkDist() reads:
                //   walkDist + (walkDist - walkDistO) * partialTicks
                // Without updating walkDistO, the interpolation breaks.
                this.walkDistO = this.walkDist;

                // walkDist accumulates horizontal movement for the view bob phase.
                // Vanilla: walkDist += horizontalDistance * 0.6f (in Entity.aiStep)
                this.walkDist += dist * 0.6f;

                // Cache horizontal distance for bob calculation in aiStep()
                this.flashback$lastTickSpeed = dist;
            }
            this.flashback$lastTickPos = this.position();
        }
    }

    @Override
    public float flashback$getXBob(float partialTick) {
        return Mth.lerp(partialTick, this.xBobO, this.xBob);
    }

    @Override
    public float flashback$getYBob(float partialTick) {
        return Mth.lerp(partialTick, this.yBobO, this.yBob);
    }
}

package com.moulberry.flashback.mixin.record;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.record.Recorder;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public class MixinConnection {

    private static final org.slf4j.Logger REC_LOGGER = LoggerFactory.getLogger("flashback-record");

    @Inject(method = "genericsFtw", at = @At("HEAD"))
    private static void genericsFtw(Packet<?> packet, PacketListener packetListener, CallbackInfo ci) {
        Recorder recorder = Flashback.RECORDER;
        if (recorder != null) {
            if (packetListener instanceof ClientGamePacketListener) {
                if (packet instanceof ClientboundCustomPayloadPacket cp) {
                    ResourceLocation id = cp.getIdentifier();
                    REC_LOGGER.info("[Flashback Record] Incoming custom payload: namespace={} path={} class={}", id.getNamespace(), id.getPath(), packet.getClass().getSimpleName());
                }
                recorder.writePacketAsync(packet, ConnectionProtocol.PLAY);
            }
        }
    }

}
